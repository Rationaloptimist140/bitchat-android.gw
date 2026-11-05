package com.bitchat.android.nostr

import android.os.Build
import android.os.Looper
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID

/** Stage E: history window, paginated backfill, sync positions and deterministic file ids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class HistorySyncTest {
    private val gson = Gson()
    private val hour = 60L * 60 * 1000
    private val now = 2_000_000_000_000L

    @Test
    fun `backfill window starts at the last sync minus the backdating allowance`() {
        assertEquals("first run keeps the 48 h window", now - 48 * hour, HistorySyncPolicy.backfillSinceMs(now, null))
        assertEquals("recent sync never narrows the live window", now - 48 * hour, HistorySyncPolicy.backfillSinceMs(now, now - hour))
        assertEquals("30 h offline", now - 30 * hour - 23 * hour, HistorySyncPolicy.backfillSinceMs(now, now - 30 * hour))
        assertEquals("long absence is capped", now - 7 * 24 * hour, HistorySyncPolicy.backfillSinceMs(now, now - 30 * 24 * hour))
        assertTrue(HistorySyncPolicy.MAX_GIFT_WRAP_AGE_SECONDS > 48 * 3600 + 900)
    }

    @Test
    fun `backfill pages backwards until a partial page and then reports completion`() {
        val manager = NostrRelayManager.privateMessaging
        val relay = NostrRelayManager.privateMessagingRelays().first()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val completed = Collections.synchronizedList(mutableListOf<String>())
        val since = 1_700_000_000
        val existing = manager.backfillPagesForTest().keys
        manager.backfill(
            baseFilter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), since = since),
            relayUrls = listOf(relay),
            handler = { received.add(it.id) },
            pageLimit = 2,
            onRelayComplete = { completed.add(it) }
        )
        val first = (manager.backfillPagesForTest().keys - existing).single()
        assertNull(manager.backfillPagesForTest()[first]?.until)
        feed(manager, first, relay, event(since + 500), event(since + 400))
        manager.handleRelayFrameForTest(eose(first), relay)

        val second = (manager.backfillPagesForTest().keys - existing).single()
        assertNotEquals(first, second)
        assertEquals(since + 400, manager.backfillPagesForTest()[second]?.until)
        assertTrue(completed.isEmpty())

        feed(manager, second, relay, event(since + 300))
        manager.handleRelayFrameForTest(eose(second), relay)
        assertEquals(listOf(relay), completed.toList())
        assertTrue((manager.backfillPagesForTest().keys - existing).isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, received.toSet().size)
    }

    @Test
    fun `a full page sharing one second steps past it and the page cap stops paging`() {
        val manager = NostrRelayManager.privateMessaging
        val relay = NostrRelayManager.privateMessagingRelays().first()
        val completed = Collections.synchronizedList(mutableListOf<String>())
        val existing = manager.backfillPagesForTest().keys
        val t = 1_700_100_000
        manager.backfill(
            baseFilter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), since = 1_600_000_000),
            relayUrls = listOf(relay), handler = {}, pageLimit = 2, maxPages = 3,
            onRelayComplete = { completed.add(it) }
        )
        val p1 = (manager.backfillPagesForTest().keys - existing).single()
        feed(manager, p1, relay, event(t), event(t + 10))
        manager.handleRelayFrameForTest(eose(p1), relay)
        val p2 = (manager.backfillPagesForTest().keys - existing).single()
        assertEquals(t, manager.backfillPagesForTest()[p2]?.until)
        feed(manager, p2, relay, event(t), event(t))
        manager.handleRelayFrameForTest(eose(p2), relay)
        val p3 = (manager.backfillPagesForTest().keys - existing).single()
        assertEquals("same second: step past it", t - 1, manager.backfillPagesForTest()[p3]?.until)
        feed(manager, p3, relay, event(t - 5), event(t - 6))
        manager.handleRelayFrameForTest(eose(p3), relay)
        assertEquals("page cap reached", listOf(relay), completed.toList())
    }

    @Test
    fun `sync positions keep the oldest relay and only move forward`() {
        val name = "sync-${UUID.randomUUID()}.db"
        val repository = ConversationRepository(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined, name, InMemoryConversationStorageCipher())
        try {
            runBlocking {
                repository.withOutbox { it.recordSync("wss://a", "chat-messages", 1_000L) }
                repository.withOutbox { it.recordSync("wss://b", "chat-messages", 5_000L) }
                repository.withOutbox { it.recordSync("wss://a", "chat-messages", 500L) } // never moves back
                assertEquals(1_000L, repository.withOutbox { it.lastSync("chat-messages", listOf("wss://a", "wss://b")) })
                assertNull(repository.withOutbox { it.lastSync("chat-messages", listOf("wss://c")) })
            }
        } finally {
            repository.closeForTest()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }

    @Test
    fun `file message ids are deterministic per sender and content`() {
        val payload = "file-bytes".toByteArray()
        val a = NostrDirectMessageHandler.fileMessageId("AB".repeat(32), payload)
        assertEquals(a, NostrDirectMessageHandler.fileMessageId("ab".repeat(32), payload))
        assertNotEquals(a, NostrDirectMessageHandler.fileMessageId("cd".repeat(32), payload))
        assertNotEquals(a, NostrDirectMessageHandler.fileMessageId("ab".repeat(32), "other".toByteArray()))
    }

    private fun feed(manager: NostrRelayManager, subscriptionId: String, relay: String, vararg events: NostrEvent) {
        events.forEach { manager.handleRelayFrameForTest(frame(subscriptionId, it), relay) }
    }

    private fun event(createdAt: Int): NostrEvent {
        val identity = NostrIdentity.generate()
        return NostrEvent(pubkey = identity.publicKeyHex, createdAt = createdAt, kind = NostrKind.TEXT_NOTE,
            tags = emptyList(), content = "history-${UUID.randomUUID()}").sign(identity.privateKeyHex)
    }

    private fun frame(id: String, event: NostrEvent) = JsonArray().apply {
        add(JsonPrimitive("EVENT")); add(JsonPrimitive(id)); add(gson.toJsonTree(event))
    }.toString()

    private fun eose(id: String) = JsonArray().apply { add(JsonPrimitive("EOSE")); add(JsonPrimitive(id)) }.toString()
}
