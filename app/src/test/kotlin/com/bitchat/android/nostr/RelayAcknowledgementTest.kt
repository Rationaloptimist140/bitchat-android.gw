package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrPendingEventQueue.ResponseKind
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Stage B: a socket write is never relay acceptance, relay OK frames are honoured, and private
 * messages reach Sent only on relay acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class RelayAcknowledgementTest {
    private var now = 1_000_000L
    private lateinit var repository: ConversationRepository
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        AppStateStore.clear()
        databaseName = "relay-ack-${UUID.randomUUID()}.db"
        repository = ConversationRepository(
            context = RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(repository)
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    // ---- queue semantics ----

    @Test
    fun `written deliveries stay queued until the relay accepts them`() {
        val queue = NostrPendingEventQueue(capacity = 8) { now }
        val e = event("written")
        val id = requireNotNull(queue.enqueue(e, listOf("r1"), null))
        queue.markWritten(id, "r1")
        assertEquals(1, queue.size())
        assertTrue(queue.pendingForRelay("r1").isEmpty())

        assertEquals(ResponseKind.ACCEPTED, queue.onRelayResponse(e.id, "r1", true, ""))
        assertEquals(0, queue.size())
    }

    @Test
    fun `duplicate reason counts as acceptance`() {
        val queue = NostrPendingEventQueue(capacity = 8) { now }
        val e = event("dup")
        queue.enqueue(e, listOf("r1"), null)
        assertEquals(ResponseKind.ACCEPTED, queue.onRelayResponse(e.id, "r1", false, "duplicate: already have it"))
        assertEquals(0, queue.size())
    }

    @Test
    fun `retryable rejection is retried after a delay`() {
        val queue = NostrPendingEventQueue(capacity = 8) { now }
        val e = event("rate")
        val id = requireNotNull(queue.enqueue(e, listOf("r1"), null))
        queue.markWritten(id, "r1")
        assertEquals(ResponseKind.RETRYABLE, queue.onRelayResponse(e.id, "r1", false, "rate-limited: slow down"))
        assertTrue(queue.pendingForRelay("r1").isEmpty())
        now += 2_001L
        assertEquals(listOf(id), queue.pendingForRelay("r1").map { it.queueId })
    }

    @Test
    fun `permanent rejection resolves only that relay`() {
        val queue = NostrPendingEventQueue(capacity = 8) { now }
        val e = event("perm")
        queue.enqueue(e, listOf("r1", "r2"), null)
        assertEquals(ResponseKind.PERMANENT, queue.onRelayResponse(e.id, "r1", false, "invalid: bad signature"))
        assertTrue(queue.pendingForRelay("r1").isEmpty())
        assertEquals(1, queue.pendingForRelay("r2").size)
    }

    @Test
    fun `socket loss and acknowledgement timeout return writes to pending`() {
        val queue = NostrPendingEventQueue(capacity = 8) { now }
        val a = requireNotNull(queue.enqueue(event("lost"), listOf("r1"), null))
        val b = requireNotNull(queue.enqueue(event("slow"), listOf("r2"), null))
        queue.markWritten(a, "r1")
        queue.markWritten(b, "r2")

        queue.requeueWritten("r1")
        assertEquals(listOf(a), queue.pendingForRelay("r1").map { it.queueId })

        now += 19_000L
        assertTrue(queue.expireUnacknowledged(20_000L).isEmpty())
        now += 2_000L
        assertEquals(setOf("r2"), queue.expireUnacknowledged(20_000L))
        assertEquals(listOf(b), queue.pendingForRelay("r2").map { it.queueId })
    }

    @Test
    fun `classification follows NIP-01 prefixes`() {
        assertEquals(ResponseKind.ACCEPTED, NostrPendingEventQueue.classify(true, null))
        assertEquals(ResponseKind.ACCEPTED, NostrPendingEventQueue.classify(false, "duplicate:"))
        listOf("invalid: x", "blocked: x", "restricted: x", "pow: x", "auth-required: x").forEach {
            assertEquals(it, ResponseKind.PERMANENT, NostrPendingEventQueue.classify(false, it))
        }
        listOf("rate-limited: x", "error: x", "something else", null).forEach {
            assertEquals("$it", ResponseKind.RETRYABLE, NostrPendingEventQueue.classify(false, it))
        }
        assertNull(NostrPendingEventQueue(capacity = 1).onRelayResponse("unknown", "r1", true, null))
    }

    // ---- relay manager ----

    @Test
    fun `relay OK frames drain the queue per relay and are reported as outcomes`() = runBlocking {
        val manager = NostrRelayManager.privateMessaging
        val relays = NostrRelayManager.privateMessagingRelays().take(2)
        val e = event("manager-${UUID.randomUUID()}")
        val before = manager.pendingDeliveryCountForTest()
        val firstOutcome = async(Dispatchers.Default) {
            withTimeout(5_000) { manager.publishOutcomes.first { it.eventId == e.id } }
        }
        delay(100)

        manager.sendEvent(e, relays)
        delay(100)
        assertEquals(before + 1, manager.pendingDeliveryCountForTest())

        manager.handleRelayFrameForTest(ok(e.id, true, ""), relays[0])
        val outcome = firstOutcome.await()
        assertEquals(relays[0], outcome.relayUrl)
        assertEquals(ResponseKind.ACCEPTED, outcome.kind)
        assertEquals(before + 1, manager.pendingDeliveryCountForTest())

        manager.handleRelayFrameForTest(ok(e.id, false, "duplicate: stored"), relays[1])
        assertEquals(before, manager.pendingDeliveryCountForTest())
    }

    // ---- private message status ----

    @Test
    fun `private message reaches Sent only on relay acceptance`() {
        storeOutgoing("status-sent")
        val transport = NostrTransport.getInstance(RuntimeEnvironment.getApplication())
        transport.trackPublishedMessage("event-sent", "status-sent", listOf("r1", "r2"))

        transport.onPublishOutcome(NostrRelayManager.PublishOutcome("event-sent", "r1", ResponseKind.RETRYABLE, "rate-limited"))
        transport.onPublishOutcome(NostrRelayManager.PublishOutcome("event-sent", "r1", ResponseKind.PERMANENT, "blocked"))
        assertEquals(DeliveryStatus.Sending, status("status-sent"))

        transport.onPublishOutcome(NostrRelayManager.PublishOutcome("event-sent", "r2", ResponseKind.ACCEPTED, null))
        assertEquals(DeliveryStatus.Sent, status("status-sent"))
    }

    @Test
    fun `geohash DM accepted by a location relay reaches Sent`() {
        storeOutgoing("geo-sent")
        val transport = NostrTransport.getInstance(RuntimeEnvironment.getApplication())
        transport.trackPublishedMessage("event-geo", "geo-sent", listOf("wss://geo.example"))
        NostrRelayManager.shared.handleRelayFrameForTest(ok("event-geo", true, ""), "wss://geo.example")
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { while (status("geo-sent") != DeliveryStatus.Sent) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun `private message rejected by every relay is failed, never sent`() {
        storeOutgoing("status-failed")
        val transport = NostrTransport.getInstance(RuntimeEnvironment.getApplication())
        transport.trackPublishedMessage("event-failed", "status-failed", listOf("r1", "r2"))
        transport.onPublishOutcome(NostrRelayManager.PublishOutcome("event-failed", "r1", ResponseKind.PERMANENT, "invalid"))
        transport.onPublishOutcome(NostrRelayManager.PublishOutcome("event-failed", "r2", ResponseKind.PERMANENT, "blocked"))
        assertTrue(status("status-failed") is DeliveryStatus.Failed)
    }

    @Test
    fun `no production code sets Sent without relay evidence`() {
        val allowed = setOf("NostrTransport.kt", "OutboxScheduler.kt", "ConversationRepository.kt", "OutboxStore.kt")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (Regex("""DeliveryStatus\.Sent\b""").containsMatchIn(line) &&
                        !line.contains("is DeliveryStatus.Sent") && !line.trim().startsWith("DeliveryStatus.Sent ->") &&
                        !line.trim().startsWith("is ") && !line.contains("statusPriority")
                    ) "${file.name}:${i + 1}" else null
                }
            }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun status(id: String): DeliveryStatus? = runBlocking {
        repository.awaitPendingWrites()
        repository.loadDeliveryStatusForTest(id)
    }

    private fun storeOutgoing(id: String) {
        repository.upsertMessage(
            conversationID = "peer-status",
            aliases = setOf("peer-status"),
            displayName = "peer",
            message = BitchatMessage(
                id = id, sender = "me", content = "hello", timestamp = Date(1_000L), isPrivate = true,
                senderPeerID = "0102030405060708", deliveryStatus = DeliveryStatus.Sending
            ),
            isRead = true
        )
        runBlocking { repository.awaitPendingWrites() }
    }

    private fun ok(eventId: String, accepted: Boolean, message: String): String = JsonArray().apply {
        add(JsonPrimitive("OK")); add(JsonPrimitive(eventId)); add(JsonPrimitive(accepted)); add(JsonPrimitive(message))
    }.toString()

    private fun event(content: String): NostrEvent {
        val identity = NostrIdentity.generate()
        return NostrEvent(
            pubkey = identity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(identity.privateKeyHex)
    }

    @Suppress("unused") private val gson = Gson()
}
