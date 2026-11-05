package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrEnvelopePublisher
import com.bitchat.android.nostr.NostrPendingEventQueue
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/**
 * Stage C/D scheduler behaviour with a fake mesh, fake routing and a fake publisher: routing,
 * BLE -> Nostr fallback with the same message id, crash recovery, relay acceptance, expiry,
 * manual retry, deletion and panic wipe.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val mesh: MeshService = mock()
    private var now = System.currentTimeMillis()
    private var meshReady = false
    private var meshVisible = false
    private var nostrAllowed = false
    private val handshakes = mutableListOf<String>()
    private val published = mutableListOf<Pair<String, String>>() // (messageId, eventId)

    private val routing = object : OutboxScheduler.Routing {
        override val mesh: MeshService get() = this@OutboxSchedulerTest.mesh
        override fun readyMeshTarget(conversationId: String) = if (meshReady) "a1b2c3d4e5f60718" else null
        override fun visibleMeshTarget(conversationId: String) = if (meshVisible) "a1b2c3d4e5f60718" else null
        override fun nostrTarget(conversationId: String) = if (nostrAllowed) "noise-key-hex" else null
        override fun requestHandshake(conversationId: String, meshTarget: String) { handshakes += meshTarget }
    }

    private inner class FakePublisher : NostrEnvelopePublisher(context, repository, clock = { now }) {
        override suspend fun publishMessage(envelope: OutboxStore.Envelope, content: String, nostrTarget: String): Result {
            val eventId = "event-${envelope.messageId}-${published.size}"
            repository.withOutbox { it.recordNostrEvent(envelope.envelopeId, eventId, "{}", now / 1000, now, now + 60_000L) }
            published += envelope.messageId!! to eventId
            return Result.Published(eventId, regenerated = true)
        }
    }

    @Before
    fun setUp() {
        databaseName = "outbox-scheduler-${UUID.randomUUID()}.db"
        repository = ConversationRepository(context, Dispatchers.Unconfined, databaseName, InMemoryConversationStorageCipher())
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(repository)
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        context.deleteDatabase(databaseName)
    }

    private fun scheduler() = OutboxScheduler(
        context, repository, routing, FakePublisher(), relayOutcomes = { emptyFlow() }, clock = { now }
    )

    private fun compose(id: String) {
        runBlocking {
            repository.upsertMessageAndWait(
                "peer-alice", setOf("peer-alice"), "alice",
                BitchatMessage(id = id, sender = "me", content = "hello $id", timestamp = Date(now), isPrivate = true,
                    recipientNickname = "alice", senderPeerID = "0102030405060708", deliveryStatus = DeliveryStatus.Sending),
                isRead = true, enqueueOutbound = true
            )
        }
    }

    private fun pass(s: OutboxScheduler) = runBlocking { s.runOnce() }
    private fun status(id: String) = runBlocking { repository.loadDeliveryStatusForTest(id) }
    private fun envelope(id: String) = runBlocking { repository.withOutbox { it.envelopeForMessage(id) } }

    @Test
    fun `mesh-ready peer gets the message over the mesh and stays Waiting until the receipt`() {
        meshReady = true
        compose("m1")
        val s = scheduler()
        pass(s)
        verify(mesh, times(1)).sendPrivateMessage("hello m1", "a1b2c3d4e5f60718", "alice", "m1")
        assertEquals(DeliveryStatus.Sending, status("m1"))
        runBlocking { repository.updateDeliveryStatus("m1", DeliveryStatus.Delivered("alice", Date(now))) }
        assertEquals(OutboxStore.State.DELIVERED, envelope("m1")!!.state)
        now += 31_000L
        pass(s)
        verify(mesh, times(1)).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `missing mesh receipt falls back to Nostr with the same message id`() {
        meshReady = true
        compose("m2")
        val s = scheduler()
        pass(s)
        meshReady = false
        nostrAllowed = true
        now += 31_000L
        pass(s)
        assertEquals(listOf("m2"), published.map { it.first })
        verify(mesh, times(1)).sendPrivateMessage(any(), any(), any(), eq("m2"))
    }

    @Test
    fun `no route waits, nudges a handshake and sends nothing`() {
        meshVisible = true
        compose("m3")
        pass(scheduler())
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertTrue(published.isEmpty())
        assertEquals(listOf("a1b2c3d4e5f60718"), handshakes)
        assertEquals(OutboxStore.State.RETRY_WAIT, envelope("m3")!!.state)
    }

    @Test
    fun `relay acceptance moves the message to Sent`() {
        nostrAllowed = true
        compose("m4")
        val s = scheduler()
        pass(s)
        val eventId = published.single().second
        runBlocking { s.onRelayOutcome(NostrRelayManager.PublishOutcome(eventId, "wss://r", NostrPendingEventQueue.ResponseKind.ACCEPTED, null)) }
        assertEquals(DeliveryStatus.Sent, status("m4"))
        // Retryable or permanent outcomes never mark Sent.
        compose("m5")
        pass(s)
        val e5 = published.last().second
        runBlocking { s.onRelayOutcome(NostrRelayManager.PublishOutcome(e5, "wss://r", NostrPendingEventQueue.ResponseKind.PERMANENT, "blocked")) }
        assertEquals(DeliveryStatus.Sending, status("m5"))
    }

    @Test
    fun `crash after claiming is recovered and the same message is resent once`() {
        nostrAllowed = true
        compose("m6")
        // Process A claims and dies before dispatching.
        runBlocking { repository.withOutbox { it.claimDue(now, OutboxScheduler.LEASE_MS, 10) } }
        now += OutboxScheduler.LEASE_MS + 1_000L
        // Process B starts.
        pass(scheduler())
        assertEquals(listOf("m6"), published.map { it.first })
    }

    @Test
    fun `expired message fails and manual retry resends with the same id`() {
        compose("m7")
        val s = scheduler()
        pass(s)
        now += ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS + 1_000L
        pass(s)
        assertTrue(status("m7") is DeliveryStatus.Failed)

        val retryAt = now
        assertTrue(runBlocking {
            repository.withOutbox { it.retryFailed("m7", retryAt, retryAt + ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS, retryAt + ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS) }
        })
        nostrAllowed = true
        pass(s)
        assertEquals(listOf("m7"), published.map { it.first })
        assertEquals(DeliveryStatus.Sending, status("m7"))
    }

    @Test
    fun `deleted message is never sent`() {
        nostrAllowed = true
        compose("m8")
        runBlocking { repository.deleteMessage("m8"); repository.awaitPendingWrites() }
        pass(scheduler())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `panic wipe leaves nothing to send`() {
        nostrAllowed = true
        compose("m9")
        runBlocking { repository.clearAllAndWait() }
        pass(scheduler())
        assertTrue(published.isEmpty())
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `retry backoff grows and stays jittered within twenty percent`() {
        val first = OutboxScheduler.retryDelayMs(1)
        val third = OutboxScheduler.retryDelayMs(3)
        assertTrue(first in 4_000L..6_000L)
        assertTrue(third in 96_000L..144_000L)
        assertEquals(3_600_000L, OutboxScheduler.republishDelayMs(0))
        assertEquals(259_200_000L, OutboxScheduler.republishDelayMs(9))
    }
}
