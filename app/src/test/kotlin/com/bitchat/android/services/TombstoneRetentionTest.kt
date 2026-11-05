package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.HistorySyncPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/**
 * Deletion tombstones must outlive every legitimate re-delivery (sender outbox retries, relay
 * replays inside the accepted gift-wrap window) while storage stays bounded.
 */
@RunWith(RobolectricTestRunner::class)
class TombstoneRetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ConversationDatabase
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "tombstones-${UUID.randomUUID()}.db"
        open(maxMessagesTotal = 3, maxTombstones = 50)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `retention window covers the longest legitimate re-delivery`() {
        val longestRedelivery = ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS +
            HistorySyncPolicy.MAX_GIFT_WRAP_AGE_SECONDS * 1000
        assertTrue(ConversationDatabase.TOMBSTONE_RETENTION_MS > longestRedelivery)
        assertTrue(ConversationDatabase.MAX_TOMBSTONES > ConversationDatabase.MAX_MESSAGES_TOTAL)
    }

    @Test
    fun `recent deletions beyond the old count limit are kept and block replays`() {
        repeat(10) { store("m-$it") }
        repeat(10) { database.deleteMessage("m-$it") }

        assertEquals(10, tombstoneCount())
        // Previously only the newest 3 survived, so m-0 could be re-inserted by a retry.
        assertFalse(store("m-0").inserted)
        assertTrue(database.isKnownOrDeletedMessage("m-0"))
    }

    @Test
    fun `messages pruned by retention limits are not re-inserted by a replay`() {
        repeat(10) { store("p-$it", conversation = "peer-$it") }
        database.pruneToRetentionLimits()
        val pruned = (0 until 10).map { "p-$it" }.filter { it in database.loadSnapshot().deletedMessageIDs }
        assertTrue(pruned.size >= 7)
        pruned.forEach { assertFalse("replayed $it", store(it).inserted) }
    }

    @Test
    fun `tombstones older than the window are trimmed to the soft limit, oldest first`() {
        repeat(6) { store("old-$it") }
        repeat(6) { now += 1; database.deleteMessage("old-$it") }
        now += ConversationDatabase.TOMBSTONE_RETENTION_MS + day
        store("new-0")
        database.deleteMessage("new-0")

        val remaining = database.loadSnapshot().deletedMessageIDs
        assertEquals(3, remaining.size)
        assertTrue("new-0" in remaining)
        assertTrue("old-5" in remaining && "old-4" in remaining)
        assertFalse("old-0" in remaining)
    }

    @Test
    fun `hard ceiling bounds storage under abuse`() {
        database.close()
        context.deleteDatabase(databaseName)
        open(maxMessagesTotal = 3, maxTombstones = 5)
        repeat(9) { store("flood-$it") }
        repeat(9) { now += 1; database.deleteMessage("flood-$it") }

        assertEquals(5, tombstoneCount())
        val remaining = database.loadSnapshot().deletedMessageIDs
        assertTrue("flood-8" in remaining && "flood-4" in remaining)
        assertFalse("flood-3" in remaining)
    }

    @Test
    fun `panic wipe clears tombstones`() {
        store("w-0")
        database.deleteMessage("w-0")
        database.clearAll()
        assertEquals(0, tombstoneCount())
    }

    private fun open(maxMessagesTotal: Int, maxTombstones: Int) {
        database = ConversationDatabase(
            context = context,
            databaseName = databaseName,
            maxMessagesPerConversation = 1_000,
            maxMessagesTotal = maxMessagesTotal,
            maxPayloadBytes = Long.MAX_VALUE,
            storageCipher = InMemoryConversationStorageCipher(),
            maxTombstones = maxTombstones,
            clock = { now }
        )
    }

    private fun store(id: String, conversation: String = "peer") = database.upsertMessage(
        conversationID = conversation,
        aliases = setOf(conversation),
        displayName = conversation,
        message = BitchatMessage(id = id, sender = "peer", content = "hello-$id", timestamp = Date(now),
            isPrivate = true, senderPeerID = "0123456789abcdef"),
        isRead = true
    )

    private fun tombstoneCount(): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM deleted_private_messages", null)
            .use { it.moveToFirst(); it.getInt(0) }
}
