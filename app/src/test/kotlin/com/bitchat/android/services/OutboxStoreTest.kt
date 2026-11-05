package com.bitchat.android.services

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/**
 * Stage C: the durable outbox store, its transactions, cascades, panic wipe and the database
 * version 4 -> 5 migration (including a failed migration).
 */
@RunWith(RobolectricTestRunner::class)
class OutboxStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val now = System.currentTimeMillis()
    private val cipher = InMemoryConversationStorageCipher()
    private val week = ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS

    @Before
    fun setUp() {
        databaseName = "outbox-${UUID.randomUUID()}.db"
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.closeForTest()
        context.deleteDatabase(databaseName)
    }

    private fun newRepository() = ConversationRepository(
        context = context,
        dispatcher = Dispatchers.Unconfined,
        databaseName = databaseName,
        storageCipher = cipher
    )

    private fun <T> outbox(block: (OutboxStore) -> T): T = runBlocking { repository.withOutbox(block) }

    private fun outgoing(id: String, conversation: String = "peer-alice", status: DeliveryStatus = DeliveryStatus.Sending, sentAt: Long = now) =
        BitchatMessage(
            id = id, sender = "me", content = "secret body $id", timestamp = Date(sentAt), isPrivate = true,
            senderPeerID = "0102030405060708", deliveryStatus = status
        ).also { message ->
            runBlocking {
                repository.upsertMessageAndWait(conversation, setOf(conversation), "alice", message, isRead = true, enqueueOutbound = true)
            }
        }

    private fun status(id: String) = runBlocking { repository.loadDeliveryStatusForTest(id) }

    // ---- T1: atomic creation ----

    @Test
    fun `outgoing message and its envelope are created together`() {
        outgoing("m1")
        val envelope = outbox { it.envelopeForMessage("m1") }
        assertNotNull(envelope)
        assertEquals(OutboxStore.State.QUEUED, envelope!!.state)
        assertEquals(OutboxStore.Kind.MESSAGE, envelope.kind)

        // Incoming messages (no enqueue flag) never get an envelope.
        runBlocking {
            repository.upsertMessageAndWait(
                "peer-alice", setOf("peer-alice"), "alice",
                BitchatMessage(id = "in1", sender = "alice", content = "hi", timestamp = Date(now), isPrivate = true, senderPeerID = "peer-alice"),
                isRead = true
            )
        }
        assertNull(outbox { it.envelopeForMessage("in1") })
    }

    // ---- T2: leases and crash recovery ----

    @Test
    fun `claims are leased and recovered after a crash`() {
        outgoing("m2")
        val claimed = outbox { it.claimDue(now, leaseMs = 60_000L, limit = 10) }
        assertEquals(listOf("m2"), claimed.map { it.messageId })
        assertEquals(OutboxStore.State.TRANSMITTING, claimed.single().state)
        // A second scheduler pass cannot claim the same envelope while the lease holds.
        assertTrue(outbox { it.claimDue(now + 1_000L, 60_000L, 10) }.isEmpty())

        // Simulated process death: a fresh repository on the same database.
        repository.closeForTest()
        repository = newRepository()
        assertEquals(1, outbox { it.recoverExpiredLeases(now + 61_000L) })
        assertEquals(listOf("m2"), outbox { it.claimDue(now + 61_000L, 60_000L, 10) }.map { it.messageId })
    }

    // ---- T3/T4: stored events and relay acceptance ----

    @Test
    fun `signed events are stored encrypted and acceptance moves the message to Sent once`() {
        outgoing("m3")
        val envelope = outbox { it.claimDue(now, 60_000L, 10) }.single()
        val json = """{"id":"event-3","content":"ciphertext-marker"}"""
        outbox { it.recordNostrEvent(envelope.envelopeId, "event-3", json, now / 1000, now, now + 60_000L) }

        assertEquals(json, outbox { it.activeNostrEvent(envelope.envelopeId) }!!.eventJson)
        val raw = rawDatabase().use { db ->
            db.rawQuery("SELECT event_ciphertext FROM outbound_nostr_events", null).use { c -> c.moveToFirst(); c.getBlob(0) }
        }
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("ciphertext-marker"))

        val accepted = outbox { it.markCarrierAccepted("event-3", now + 5_000L, now + 3_600_000L) }
        assertEquals("m3", accepted?.messageId)
        assertEquals(DeliveryStatus.Sent, status("m3"))
        assertEquals(OutboxStore.State.CARRIER_ACCEPTED, outbox { it.envelopeForMessage("m3") }!!.state)
        assertNull("second acceptance is a no-op", outbox { it.markCarrierAccepted("event-3", now + 6_000L, now + 3_600_000L) })
    }

    // ---- T5: recipient evidence ----

    @Test
    fun `recipient receipt closes the envelope and late relay acceptance cannot regress it`() {
        outgoing("m4")
        val envelope = outbox { it.claimDue(now, 60_000L, 10) }.single()
        outbox { it.recordNostrEvent(envelope.envelopeId, "event-4", "{}", now / 1000, now, now + 60_000L) }
        runBlocking {
            repository.updateDeliveryStatus("m4", DeliveryStatus.Delivered("alice", Date(now)))
            repository.awaitPendingWrites()
        }
        assertEquals(OutboxStore.State.DELIVERED, outbox { it.envelopeForMessage("m4") }!!.state)
        assertNull(outbox { it.markCarrierAccepted("event-4", now + 1_000L, now + 3_600_000L) })
        assertTrue(status("m4") is DeliveryStatus.Delivered)
    }

    // ---- T6: expiry ----

    @Test
    fun `unaccepted messages expire to Failed while accepted ones stay Sent`() {
        outgoing("m5")
        outgoing("m6")
        val claimed = outbox { it.claimDue(now, 1_000L, 10) }
        val m6 = claimed.first { it.messageId == "m6" }
        outbox { it.recordNostrEvent(m6.envelopeId, "event-6", "{}", now / 1000, now, now + 60_000L) }
        outbox { it.markCarrierAccepted("event-6", now + 1_000L, now + 3_600_000L) }

        val failed = outbox { it.expireDue(now + week + 2_000L) }
        assertEquals(listOf("m5"), failed)
        assertTrue(status("m5") is DeliveryStatus.Failed)
        assertEquals(DeliveryStatus.Sent, status("m6"))
        assertEquals(OutboxStore.State.ACCEPTED_EXPIRED, outbox { it.envelopeForMessage("m6") }!!.state)
    }

    // ---- T7: manual retry ----

    @Test
    fun `manual retry is allowed only from Failed and keeps the message id`() {
        outgoing("m7")
        outbox { it.claimDue(now, 1_000L, 10) }
        outbox { it.expireDue(now + week + 2_000L) }
        val retryAt = now + week + 3_000L
        assertTrue(outbox { it.retryFailed("m7", retryAt, retryAt + week, retryAt + week) })
        val envelope = outbox { it.envelopeForMessage("m7") }!!
        assertEquals(OutboxStore.State.QUEUED, envelope.state)
        assertEquals(1, envelope.retryEpoch)
        assertEquals(DeliveryStatus.Sending, status("m7"))
        assertFalse("not Failed any more", outbox { it.retryFailed("m7", retryAt, retryAt + week, retryAt + week) })

        runBlocking { repository.updateDeliveryStatus("m7", DeliveryStatus.Delivered("alice", Date(retryAt))); repository.awaitPendingWrites() }
        assertFalse("delivered messages are never retried", outbox { it.retryFailed("m7", retryAt, retryAt + week, retryAt + week) })
    }

    // ---- deletion, tombstones, panic ----

    @Test
    fun `deleting a message removes its envelope and stored events`() {
        outgoing("m8")
        val envelope = outbox { it.claimDue(now, 60_000L, 10) }.single()
        outbox { it.recordNostrEvent(envelope.envelopeId, "event-8", "{}", now / 1000, now, now + 60_000L) }
        runBlocking { repository.deleteMessage("m8"); repository.awaitPendingWrites() }
        assertNull(outbox { it.envelopeForMessage("m8") })
        assertEquals(0L, outbox { it.rowCounts() }[OutboxStore.TABLE_EVENTS])
        assertTrue(outbox { it.claimDue(now + 120_000L, 60_000L, 10) }.isEmpty())
    }

    @Test
    fun `deleting a conversation removes its receipt envelopes`() {
        outbox { it.enqueueReceipt(OutboxStore.Kind.DELIVERY_ACK, "incoming-9", "peer-bob", "ab".repeat(32), true, now, now + 172_800_000L, 600_000L) }
        outgoing("m9", conversation = "peer-bob")
        runBlocking { repository.deleteConversationAndWait("peer-bob", setOf("peer-bob")); repository.awaitPendingWrites() }
        assertNull(outbox { it.receiptEnvelope(OutboxStore.Kind.DELIVERY_ACK, "incoming-9") })
        assertNull(outbox { it.envelopeForMessage("m9") })
    }

    @Test
    fun `panic wipe leaves no outbox data`() {
        outgoing("m10")
        val envelope = outbox { it.claimDue(now, 60_000L, 10) }.single()
        outbox { it.recordNostrEvent(envelope.envelopeId, "event-10", "{}", now / 1000, now, now + 60_000L) }
        outbox { it.enqueueReceipt(OutboxStore.Kind.READ_RECEIPT, "incoming-10", "peer-alice", "cd".repeat(32), true, now, now + 1_000L, 0L) }
        assertTrue(runBlocking { repository.clearAllAndWait() })
        repository.closeForTest()
        repository = newRepository()
        assertEquals(mapOf(OutboxStore.TABLE_ENVELOPES to 0L, OutboxStore.TABLE_EVENTS to 0L, OutboxStore.TABLE_SYNC to 0L), outbox { it.rowCounts() })
    }

    // ---- receipts ----

    @Test
    fun `receipts are idempotent and re-queued only after the re-ack interval`() {
        val recipient = "ef".repeat(32)
        assertTrue(outbox { it.enqueueReceipt(OutboxStore.Kind.DELIVERY_ACK, "incoming-11", "peer-alice", recipient, true, now, now + 100_000L, 600_000L) })
        assertFalse(outbox { it.enqueueReceipt(OutboxStore.Kind.DELIVERY_ACK, "incoming-11", "peer-alice", recipient, true, now + 1_000L, now + 100_000L, 600_000L) })
        val envelope = outbox { it.claimDue(now, 60_000L, 10) }.single()
        assertEquals(recipient, envelope.receiptRecipientHex)
        assertTrue(envelope.receiptPrivateChannel)
        outbox { it.recordNostrEvent(envelope.envelopeId, "receipt-11", "{}", now / 1000, now, now + 60_000L) }
        outbox { it.markCarrierAccepted("receipt-11", now + 1_000L, now + 3_600_000L) }

        assertFalse("within interval", outbox { it.enqueueReceipt(OutboxStore.Kind.DELIVERY_ACK, "incoming-11", "peer-alice", recipient, true, now + 2_000L, now + 100_000L, 600_000L) })
        assertTrue("after interval", outbox { it.enqueueReceipt(OutboxStore.Kind.DELIVERY_ACK, "incoming-11", "peer-alice", recipient, true, now + 700_000L, now + 800_000L, 600_000L) })
    }

    // ---- legacy rows ----

    @Test
    fun `legacy pending messages are adopted or failed by age`() {
        listOf("recent" to now - 1_000L, "stale" to now - week - 1_000L).forEach { (id, sentAt) ->
            runBlocking {
                repository.upsertMessageAndWait(
                    "peer-alice", setOf("peer-alice"), "alice",
                    BitchatMessage(id = id, sender = "me", content = id, timestamp = Date(sentAt), isPrivate = true,
                        senderPeerID = "0102030405060708", deliveryStatus = DeliveryStatus.Sending),
                    isRead = true, enqueueOutbound = false
                )
            }
        }
        val (adopted, failed) = outbox { it.adoptLegacyPending(now, week, now + week, now + week) }
        assertEquals(1, adopted)
        assertEquals(listOf("stale"), failed)
        assertNotNull(outbox { it.envelopeForMessage("recent") })
        assertTrue(status("stale") is DeliveryStatus.Failed)
        assertEquals(0, outbox { it.adoptLegacyPending(now, week, now + week, now + week) }.first)
    }

    // ---- migration ----

    @Test
    fun `version 4 database migrates to version 5 keeping every message`() {
        outgoing("kept-1")
        outgoing("kept-2", status = DeliveryStatus.Delivered("alice", Date(now)))
        repository.closeForTest()
        downgradeToVersion4()

        repository = newRepository()
        assertEquals(DeliveryStatus.Sending, status("kept-1"))
        assertTrue(status("kept-2") is DeliveryStatus.Delivered)
        assertEquals(0L, outbox { it.rowCounts() }[OutboxStore.TABLE_ENVELOPES])
        rawDatabase().use { assertEquals(5, it.version) }
        // Pending rows from version 4 are adopted by the scheduler's legacy pass.
        assertEquals(1, outbox { it.adoptLegacyPending(now, week, now + week, now + week) }.first)
    }

    @Test
    fun `failed migration rolls back and leaves the version 4 data intact`() {
        outgoing("survivor")
        repository.closeForTest()
        downgradeToVersion4()
        // Inject a failure: an object with the outbox table name makes index creation fail.
        rawDatabase().use { it.execSQL("CREATE VIEW outbound_envelopes AS SELECT 1 AS state, 1 AS next_attempt_at") }

        repository = newRepository()
        try {
            runBlocking { repository.loadDeliveryStatusForTest("survivor") }
            fail("expected the migration to fail")
        } catch (_: Exception) {
            // expected: SQLiteOpenHelper rolled the upgrade back
        }
        repository.closeForTest()
        rawDatabase().use { db ->
            assertEquals(4, db.version)
            assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM private_messages WHERE message_id = 'survivor'", null))
            db.execSQL("DROP VIEW outbound_envelopes")
        }
        repository = newRepository()
        assertEquals(DeliveryStatus.Sending, status("survivor"))
    }

    private fun rawDatabase(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)

    /** Turns the current database into a version 4 one: no outbox tables, user_version 4. */
    private fun downgradeToVersion4() {
        rawDatabase().use { db ->
            db.execSQL("DROP TABLE IF EXISTS outbound_nostr_events")
            db.execSQL("DROP TABLE IF EXISTS outbound_envelopes")
            db.execSQL("DROP TABLE IF EXISTS nostr_sync_state")
            db.version = 4
        }
    }
}
