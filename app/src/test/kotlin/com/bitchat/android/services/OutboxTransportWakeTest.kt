package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.ValidatedNetworkTracker
import com.bitchat.android.nostr.NostrEnvelopePublisher
import com.bitchat.android.nostr.NostrPendingEventQueue
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.services.OutboxScheduler.TransportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Backoff reset when a transport becomes available: Bluetooth peer / session, internet,
 * repeated connectivity changes, and no concurrent or duplicate sends.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxTransportWakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val mesh: MeshService = mock()
    @Volatile private var now = System.currentTimeMillis()
    @Volatile private var meshReady = false
    @Volatile private var nostrAllowed = false
    private val published = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private val routing = object : OutboxScheduler.Routing {
        override val mesh: MeshService get() = this@OutboxTransportWakeTest.mesh
        override fun readyMeshTarget(conversationId: String) = if (meshReady) "a1b2c3d4e5f60718" else null
        override fun visibleMeshTarget(conversationId: String) = if (meshReady) "a1b2c3d4e5f60718" else null
        override fun nostrTarget(conversationId: String) = if (nostrAllowed) "noise-key-hex" else null
        override fun requestHandshake(conversationId: String, meshTarget: String) = Unit
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
        databaseName = "outbox-wake-${UUID.randomUUID()}.db"
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

    private fun scheduler() = OutboxScheduler(context, repository, routing, FakePublisher(), relayOutcomes = { emptyFlow() }, clock = { now })

    private fun compose(id: String, conversation: String = "peer-alice") = runBlocking {
        repository.upsertMessageAndWait(
            conversation, setOf(conversation), "alice",
            BitchatMessage(id = id, sender = "me", content = "hello $id", timestamp = Date(now), isPrivate = true,
                recipientNickname = "alice", senderPeerID = "0102030405060708", deliveryStatus = DeliveryStatus.Sending),
            isRead = true, enqueueOutbound = true
        )
    }

    private fun pass(s: OutboxScheduler) = runBlocking { s.runOnce() }
    private fun envelope(id: String) = runBlocking { repository.withOutbox { it.envelopeForMessage(id) } }!!
    private fun wake(s: OutboxScheduler, event: TransportEvent, vararg ids: String) =
        runBlocking { s.onTransportAvailable(event, ids.toSet()) }

    /** Three failed no-route attempts: the next one is about 2 minutes away. */
    private fun backedOff(s: OutboxScheduler, id: String) {
        compose(id)
        pass(s); now += 6_000L
        pass(s); now += 37_000L
        pass(s); now += 3_000L
        assertEquals(3, envelope(id).attemptCount)
        assertEquals(OutboxStore.State.RETRY_WAIT, envelope(id).state)
        assertTrue(envelope(id).nextAttemptAt - now > 60_000L)
    }

    @Test
    fun `bluetooth session ready sends the waiting message now with the same id`() {
        val s = scheduler()
        backedOff(s, "w1")
        meshReady = true
        pass(s)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull()) // still on its timer

        assertEquals(1, wake(s, TransportEvent.MESH_SESSION_READY, "peer-alice"))
        pass(s)
        verify(mesh, times(1)).sendPrivateMessage("hello w1", "a1b2c3d4e5f60718", "alice", "w1")
        assertEquals(DeliveryStatus.Sending, runBlocking { repository.loadDeliveryStatusForTest("w1") }) // never Sent over mesh
        assertEquals(0, envelope("w1").retryEpoch)
    }

    @Test
    fun `bluetooth wake only affects that peer's conversation`() {
        val s = scheduler()
        backedOff(s, "w2")
        assertEquals(0, wake(s, TransportEvent.MESH_PEER_VISIBLE, "peer-bob"))
        assertTrue(envelope("w2").nextAttemptAt > now)
    }

    @Test
    fun `internet reconnection publishes the waiting message now with the same id`() {
        val s = scheduler()
        backedOff(s, "w3")
        nostrAllowed = true
        pass(s)
        assertTrue(published.isEmpty())

        assertEquals(1, wake(s, TransportEvent.INTERNET))
        pass(s)
        assertEquals(listOf("w3"), published.map { it.first })
    }

    @Test
    fun `repeated connectivity changes reset at most once per window`() {
        val s = scheduler()
        backedOff(s, "w4")
        val results = (1..10).map { now += 1_000L; wake(s, TransportEvent.INTERNET) }
        assertEquals(listOf(1) + List(9) { 0 }, results)
        pass(s) // the one extra attempt (still no route)
        val afterOne = envelope("w4")
        assertEquals(4, afterOne.attemptCount)
        assertTrue("backoff keeps growing", afterOne.nextAttemptAt - now >= 480_000L)

        repeat(20) { now += 1_000L; wake(s, TransportEvent.INTERNET); pass(s) } // 20 s of flapping
        assertEquals(4, envelope("w4").attemptCount)

        now += 15_000L
        assertEquals(1, wake(s, TransportEvent.INTERNET)) // new window
    }

    @Test
    fun `network tracker reports transitions, not repeated callbacks`() {
        val tracker = ValidatedNetworkTracker()
        assertTrue(tracker.onCapabilities("wifi", true))
        repeat(5) { assertFalse(tracker.onCapabilities("wifi", true)) } // signal/bandwidth updates
        assertFalse(tracker.onCapabilities("wifi", false))
        assertTrue(tracker.onCapabilities("wifi", true))                 // re-validated
        assertTrue(tracker.onCapabilities("cell", true))                 // switched network
        tracker.onLost("cell")
        assertTrue(tracker.onCapabilities("cell", true))                 // back after loss
    }

    @Test
    fun `leased envelope is never reset or claimed twice`() {
        val s = scheduler()
        backedOff(s, "w5")
        now += 200_000L
        val claimed = runBlocking { repository.withOutbox { it.claimDue(now, OutboxScheduler.LEASE_MS, 10) } }
        assertEquals(listOf("w5"), claimed.map { it.messageId })

        meshReady = true
        assertEquals(0, wake(s, TransportEvent.MESH_SESSION_READY, "peer-alice"))
        pass(s)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `concurrent wakes and passes send exactly once`() {
        val s = scheduler()
        backedOff(s, "w6")
        meshReady = true
        runBlocking {
            (1..6).map { i ->
                async(Dispatchers.IO) {
                    if (i % 2 == 0) s.onTransportAvailable(TransportEvent.MESH_SESSION_READY, setOf("peer-alice"))
                    else s.onTransportAvailable(TransportEvent.INTERNET)
                    s.runOnce()
                }
            }.awaitAll()
        }
        verify(mesh, times(1)).sendPrivateMessage(any(), any(), any(), eq("w6"))
    }

    @Test
    fun `internet wake does not cut short a mesh receipt wait`() {
        val s = scheduler()
        meshReady = true
        compose("w7")
        pass(s) // sent over the mesh; waiting 30 s for the receipt
        meshReady = false
        nostrAllowed = true
        now += 5_000L
        assertEquals(0, wake(s, TransportEvent.INTERNET))
        pass(s)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `session ready promotes a relay-accepted message to the mesh once per epoch`() {
        val s = scheduler()
        nostrAllowed = true
        compose("w8")
        pass(s)
        val eventId = published.single().second
        runBlocking { s.onRelayOutcome(NostrRelayManager.PublishOutcome(eventId, "wss://r", NostrPendingEventQueue.ResponseKind.ACCEPTED, null)) }
        assertEquals(OutboxStore.State.CARRIER_ACCEPTED, envelope("w8").state)

        meshReady = true
        now += 3_000L
        assertEquals(0, wake(s, TransportEvent.MESH_PEER_VISIBLE, "peer-alice")) // visible only: no
        assertEquals(1, wake(s, TransportEvent.MESH_SESSION_READY, "peer-alice"))
        pass(s)
        verify(mesh, times(1)).sendPrivateMessage("hello w8", "a1b2c3d4e5f60718", "alice", "w8")
        assertEquals(OutboxStore.State.CARRIER_ACCEPTED, envelope("w8").state) // still Sent, awaiting receipt

        now += 31_000L
        assertEquals(0, wake(s, TransportEvent.MESH_SESSION_READY, "peer-alice")) // already promoted
        pass(s)
        verify(mesh, times(1)).sendPrivateMessage(any(), any(), any(), eq("w8"))
    }

    @Test
    fun `expired and delivered envelopes are left alone`() {
        val s = scheduler()
        backedOff(s, "w9")
        runBlocking { repository.updateDeliveryStatus("w9", DeliveryStatus.Delivered("alice", Date(now))) }
        assertEquals(0, wake(s, TransportEvent.INTERNET))

        compose("w10")
        pass(s)
        now += ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS + 1_000L
        now += OutboxScheduler.WAKE_MIN_INTERVAL_MS
        assertEquals(0, wake(s, TransportEvent.INTERNET))
    }
}
