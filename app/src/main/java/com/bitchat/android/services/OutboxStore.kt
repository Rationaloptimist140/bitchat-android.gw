package com.bitchat.android.services

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bitchat.android.model.DeliveryStatus
import org.json.JSONObject
import java.util.UUID

/**
 * Durable outbox for private messages and Nostr receipts (schema version 5).
 *
 * Lives in the conversation database so an envelope and its message's delivery status change in
 * one transaction, share the at-rest cipher, and are removed by panic wipe and deletion. All calls
 * run on the conversation repository's single writer thread.
 *
 * MESSAGE envelopes reference `private_messages(message_id)` with ON DELETE CASCADE, so deleting a
 * message or conversation removes them (and their stored Nostr events). Receipt envelopes have no
 * message row and are deleted explicitly by conversation. Message content is never duplicated
 * here; it is read from the encrypted message row when needed.
 *
 * Idempotency: one envelope per (kind, message/subject id); every transition is a conditional
 * update on the current state; message status changes go through the forward-only
 * `ConversationDatabase.updateDeliveryStatus`.
 */
internal class OutboxStore(
    private val database: ConversationDatabase,
    private val cipher: ConversationStorageCipher
) {
    enum class Kind(val code: Int) {
        MESSAGE(1), DELIVERY_ACK(2), READ_RECEIPT(3);

        companion object {
            fun of(code: Int) = entries.first { it.code == code }
        }
    }

    enum class State(val code: Int, val active: Boolean) {
        QUEUED(0, true),
        TRANSMITTING(1, true),
        RETRY_WAIT(2, true),
        CARRIER_ACCEPTED(3, true),
        DELIVERED(4, false),
        READ(5, false),
        FAILED(6, false),
        ACCEPTED_EXPIRED(7, false);

        companion object {
            fun of(code: Int) = entries.first { it.code == code }
        }
    }

    data class Envelope(
        val envelopeId: String,
        val kind: Kind,
        val messageId: String?,
        val subjectId: String?,
        val conversationId: String?,
        val state: State,
        val retryEpoch: Int,
        val attemptCount: Int,
        val republishCount: Int,
        val nextAttemptAt: Long,
        val expiresAt: Long,
        val republishUntil: Long,
        val meshSentAt: Long?,
        val meshPromotedEpoch: Int,
        val carrierAcceptedAt: Long?,
        /** Receipt recipient (Nostr hex) for receipt envelopes. */
        val receiptRecipientHex: String?,
        /** Whether a receipt travels on the private-messaging relays. */
        val receiptPrivateChannel: Boolean
    )

    data class StoredEvent(val eventId: String, val eventJson: String, val eventCreatedAt: Long)

    data class Accepted(val kind: Kind, val messageId: String?)

    // ---------------------------------------------------------------- message envelopes

    /** Inserts the MESSAGE envelope for [messageId] inside the caller's transaction (T1). */
    fun insertMessageEnvelope(db: SQLiteDatabase, messageId: String, now: Long, expiresAt: Long, republishUntil: Long) {
        db.insertWithOnConflict(
            TABLE_ENVELOPES,
            null,
            ContentValues().apply {
                put("envelope_id", UUID.randomUUID().toString())
                put("kind", Kind.MESSAGE.code)
                put("message_id", messageId)
                put("state", State.QUEUED.code)
                put("next_attempt_at", 0L) // due immediately, independent of clock skew
                put("created_at", now)
                put("updated_at", now)
                put("expires_at", expiresAt)
                put("republish_until", republishUntil)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** Ensures a MESSAGE envelope exists for an already persisted outgoing message. */
    fun ensureMessageEnvelope(messageId: String, now: Long, expiresAt: Long, republishUntil: Long): Boolean {
        val db = database.writableDatabase
        return db.transaction {
            val exists = longForQuery(
                "SELECT COUNT(*) FROM private_messages WHERE message_id = ?", arrayOf(messageId)
            ) > 0L
            if (!exists) return@transaction false
            insertMessageEnvelope(this, messageId, now, expiresAt, republishUntil)
            true
        }
    }

    // ---------------------------------------------------------------- scheduling

    /**
     * Claims due work (T2). Returns envelopes leased to the caller. QUEUED/RETRY_WAIT envelopes
     * move to TRANSMITTING; CARRIER_ACCEPTED envelopes due for republishing keep their state.
     * Messages that were deleted or tombstoned are never returned (the cascade removed them).
     */
    fun claimDue(now: Long, leaseMs: Long, limit: Int): List<Envelope> {
        val db = database.writableDatabase
        return db.transaction {
            val rows = rawQuery(
                """
                SELECT * FROM $TABLE_ENVELOPES
                WHERE next_attempt_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
                  AND (state IN (${State.QUEUED.code}, ${State.RETRY_WAIT.code}, ${State.TRANSMITTING.code})
                       OR (state = ${State.CARRIER_ACCEPTED.code} AND republish_until > ?))
                ORDER BY next_attempt_at ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(now.toString(), now.toString(), now.toString(), limit.toString())
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEnvelope()) } }
            rows.mapNotNull { envelope ->
                if (envelope.kind == Kind.MESSAGE && envelope.messageId != null && isTombstoned(this, envelope.messageId)) {
                    delete(TABLE_ENVELOPES, "envelope_id = ?", arrayOf(envelope.envelopeId))
                    return@mapNotNull null
                }
                val claimedState = if (envelope.state == State.CARRIER_ACCEPTED) State.CARRIER_ACCEPTED else State.TRANSMITTING
                val updated = update(
                    TABLE_ENVELOPES,
                    ContentValues().apply {
                        put("state", claimedState.code)
                        put("lease_until", now + leaseMs)
                        put("attempt_count", envelope.attemptCount + 1)
                        put("updated_at", now)
                    },
                    "envelope_id = ? AND state = ?",
                    arrayOf(envelope.envelopeId, envelope.state.code.toString())
                )
                if (updated == 1) envelope.copy(state = claimedState, attemptCount = envelope.attemptCount + 1) else null
            }
        }
    }

    /** Leases left by a crash or a killed process become claimable again. */
    fun recoverExpiredLeases(now: Long): Int {
        val db = database.writableDatabase
        return db.update(
            TABLE_ENVELOPES,
            ContentValues().apply {
                put("state", State.RETRY_WAIT.code)
                putNull("lease_until")
                put("next_attempt_at", now)
                put("updated_at", now)
            },
            "state = ? AND lease_until IS NOT NULL AND lease_until <= ?",
            arrayOf(State.TRANSMITTING.code.toString(), now.toString())
        )
    }

    /** Returns a claimed envelope to waiting, to be retried at [nextAttemptAt]. */
    fun release(envelopeId: String, now: Long, nextAttemptAt: Long, errorCode: String?) {
        database.writableDatabase.update(
            TABLE_ENVELOPES,
            ContentValues().apply {
                put("state", State.RETRY_WAIT.code)
                putNull("lease_until")
                put("next_attempt_at", nextAttemptAt)
                put("updated_at", now)
                if (errorCode != null) put("last_error_code", errorCode) else putNull("last_error_code")
            },
            "envelope_id = ? AND state IN (${State.TRANSMITTING.code}, ${State.QUEUED.code}, ${State.RETRY_WAIT.code})",
            arrayOf(envelopeId)
        )
    }

    /** A mesh send was handed off; wait for the recipient's receipt until [nextAttemptAt]. */
    fun markMeshSent(envelopeId: String, now: Long, nextAttemptAt: Long, promotedEpoch: Int?) {
        database.writableDatabase.update(
            TABLE_ENVELOPES,
            ContentValues().apply {
                put("mesh_sent_at", now)
                putNull("lease_until")
                put("next_attempt_at", nextAttemptAt)
                put("updated_at", now)
                put("last_transport", TRANSPORT_MESH)
                if (promotedEpoch != null) {
                    put("mesh_promoted_epoch", promotedEpoch)
                } else {
                    put("state", State.RETRY_WAIT.code)
                }
            },
            "envelope_id = ? AND state NOT IN (${State.DELIVERED.code}, ${State.READ.code}, ${State.FAILED.code})",
            arrayOf(envelopeId)
        )
    }

    /** Schedules the next republish of an accepted envelope and releases its lease. */
    fun scheduleRepublish(envelopeId: String, now: Long, nextAttemptAt: Long) {
        database.writableDatabase.execSQL(
            """
            UPDATE $TABLE_ENVELOPES
            SET republish_count = republish_count + 1, next_attempt_at = ?, lease_until = NULL, updated_at = ?
            WHERE envelope_id = ? AND state = ${State.CARRIER_ACCEPTED.code}
            """.trimIndent(),
            arrayOf<Any>(nextAttemptAt, now, envelopeId)
        )
    }

    // ---------------------------------------------------------------- Nostr events

    /**
     * Persists a signed gift wrap before any socket write (T3), superseding earlier events of the
     * envelope. Unaccepted envelopes are rescheduled for [acceptanceDeadline] so an event that no
     * relay accepts is republished.
     */
    fun recordNostrEvent(
        envelopeId: String,
        eventId: String,
        eventJson: String,
        eventCreatedAt: Long,
        now: Long,
        acceptanceDeadline: Long
    ) {
        val db = database.writableDatabase
        db.transaction {
            execSQL(
                "UPDATE $TABLE_EVENTS SET superseded_at = ? WHERE envelope_id = ? AND superseded_at IS NULL AND event_id != ?",
                arrayOf<Any>(now, envelopeId, eventId)
            )
            insertWithOnConflict(
                TABLE_EVENTS,
                null,
                ContentValues().apply {
                    put("event_id", eventId)
                    put("envelope_id", envelopeId)
                    put("event_created_at", eventCreatedAt)
                    put("prepared_at", now)
                    put("event_ciphertext", cipher.encrypt(eventJson.toByteArray(Charsets.UTF_8), eventAad(eventId)))
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            update(
                TABLE_ENVELOPES,
                ContentValues().apply {
                    put("state", State.RETRY_WAIT.code)
                    putNull("lease_until")
                    put("next_attempt_at", acceptanceDeadline)
                    put("updated_at", now)
                    put("last_transport", TRANSPORT_NOSTR)
                },
                "envelope_id = ? AND state IN (${State.TRANSMITTING.code}, ${State.QUEUED.code}, ${State.RETRY_WAIT.code})",
                arrayOf(envelopeId)
            )
        }
    }

    /** The current (not superseded) signed event of an envelope, if any. */
    fun activeNostrEvent(envelopeId: String): StoredEvent? =
        database.readableDatabase.rawQuery(
            "SELECT event_id, event_created_at, event_ciphertext FROM $TABLE_EVENTS WHERE envelope_id = ? AND superseded_at IS NULL ORDER BY prepared_at DESC LIMIT 1",
            arrayOf(envelopeId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val eventId = cursor.getString(0)
            val json = runCatching {
                String(cipher.decrypt(cursor.getBlob(2), eventAad(eventId)), Charsets.UTF_8)
            }.getOrNull() ?: return null
            StoredEvent(eventId, json, cursor.getLong(1))
        }

    /**
     * Relay acceptance evidence for [eventId] (T4). The first acceptance moves a MESSAGE envelope
     * to CARRIER_ACCEPTED and its message to Sent in the same transaction; a receipt envelope is
     * complete. Returns null when the event is unknown or already past acceptance.
     */
    fun markCarrierAccepted(eventId: String, now: Long, firstRepublishAt: Long): Accepted? {
        val db = database.writableDatabase
        return db.transaction {
            val row = rawQuery(
                """
                SELECT e.envelope_id, e.kind, e.message_id, e.state FROM $TABLE_EVENTS ev
                JOIN $TABLE_ENVELOPES e ON e.envelope_id = ev.envelope_id
                WHERE ev.event_id = ?
                """.trimIndent(),
                arrayOf(eventId)
            ).use { c -> if (c.moveToFirst()) arrayOf(c.getString(0), c.getInt(1), c.getString(2), c.getInt(3)) else null }
                ?: return@transaction null
            val envelopeId = row[0] as String
            val kind = Kind.of(row[1] as Int)
            val messageId = row[2] as String?
            val state = State.of(row[3] as Int)
            if (state !in setOf(State.QUEUED, State.TRANSMITTING, State.RETRY_WAIT)) return@transaction null
            val values = ContentValues().apply {
                put("state", State.CARRIER_ACCEPTED.code)
                put("carrier_accepted_at", now)
                putNull("lease_until")
                put("updated_at", now)
                if (kind == Kind.MESSAGE) {
                    put("next_attempt_at", firstRepublishAt)
                } else {
                    put("republish_until", now) // receipts are complete once a relay holds them
                }
            }
            update(TABLE_ENVELOPES, values, "envelope_id = ?", arrayOf(envelopeId))
            if (kind == Kind.MESSAGE && messageId != null) {
                database.updateDeliveryStatus(messageId, DeliveryStatus.Sent)
            }
            Accepted(kind, messageId)
        }
    }

    // ---------------------------------------------------------------- recipient evidence

    /** Called inside the delivery-status transaction when an authenticated receipt arrived (T5). */
    fun onRecipientEvidence(db: SQLiteDatabase, messageId: String, read: Boolean, now: Long) {
        db.update(
            TABLE_ENVELOPES,
            ContentValues().apply {
                put("state", if (read) State.READ.code else State.DELIVERED.code)
                putNull("lease_until")
                put("updated_at", now)
            },
            "kind = ${Kind.MESSAGE.code} AND message_id = ? AND state NOT IN (${State.READ.code}${if (read) "" else ", ${State.DELIVERED.code}"})",
            arrayOf(messageId)
        )
    }

    // ---------------------------------------------------------------- expiry and retry

    /**
     * Expires due envelopes (T6). Unaccepted MESSAGE envelopes become FAILED with their message
     * marked Failed; accepted ones stop republishing and stay Sent. Returns failed message ids.
     */
    fun expireDue(now: Long): List<String> {
        val db = database.writableDatabase
        return db.transaction {
            val failed = rawQuery(
                """
                SELECT envelope_id, kind, message_id FROM $TABLE_ENVELOPES
                WHERE state IN (${State.QUEUED.code}, ${State.TRANSMITTING.code}, ${State.RETRY_WAIT.code})
                  AND expires_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
                """.trimIndent(),
                arrayOf(now.toString(), now.toString())
            ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getString(2))) } }
            failed.forEach { (envelopeId, _, _) ->
                update(
                    TABLE_ENVELOPES,
                    ContentValues().apply {
                        put("state", State.FAILED.code)
                        putNull("lease_until")
                        put("updated_at", now)
                        put("last_error_code", "expired")
                    },
                    "envelope_id = ?",
                    arrayOf(envelopeId)
                )
            }
            val failedMessages = failed.filter { it.second == Kind.MESSAGE.code }.mapNotNull { it.third }
            failedMessages.forEach { database.updateDeliveryStatus(it, DeliveryStatus.Failed(EXPIRED_REASON)) }
            execSQL(
                "UPDATE $TABLE_ENVELOPES SET state = ${State.ACCEPTED_EXPIRED.code}, lease_until = NULL, updated_at = ? WHERE state = ${State.CARRIER_ACCEPTED.code} AND republish_until <= ?",
                arrayOf<Any>(now, now)
            )
            failedMessages
        }
    }

    /** Marks a MESSAGE envelope failed immediately (no route can ever exist). */
    fun failNow(envelopeId: String, messageId: String?, now: Long, reason: String) {
        database.writableDatabase.transaction {
            val updated = update(
                TABLE_ENVELOPES,
                ContentValues().apply {
                    put("state", State.FAILED.code)
                    putNull("lease_until")
                    put("updated_at", now)
                    put("last_error_code", "unroutable")
                },
                "envelope_id = ? AND state IN (${State.QUEUED.code}, ${State.TRANSMITTING.code}, ${State.RETRY_WAIT.code})",
                arrayOf(envelopeId)
            )
            if (updated == 1 && messageId != null) database.updateDeliveryStatus(messageId, DeliveryStatus.Failed(reason))
        }
    }

    /**
     * Manual retry of a failed message (T7). The only backward transition: guarded on FAILED,
     * bumps the retry epoch and resets attempts and expiry. The message UUID never changes, so a
     * copy that did arrive is de-duplicated by the receiver. Returns true if a retry was queued.
     */
    fun retryFailed(messageId: String, now: Long, expiresAt: Long, republishUntil: Long): Boolean {
        val db = database.writableDatabase
        return db.transaction {
            val currentStatus = database.loadDeliveryStatus(messageId)
            if (currentStatus !is DeliveryStatus.Failed) return@transaction false
            val updated = update(
                TABLE_ENVELOPES,
                ContentValues().apply {
                    put("state", State.QUEUED.code)
                    putNull("lease_until")
                    put("next_attempt_at", now)
                    put("attempt_count", 0)
                    put("republish_count", 0)
                    put("expires_at", expiresAt)
                    put("republish_until", republishUntil)
                    putNull("carrier_accepted_at")
                    putNull("last_error_code")
                    put("updated_at", now)
                },
                "kind = ${Kind.MESSAGE.code} AND message_id = ? AND state = ${State.FAILED.code}",
                arrayOf(messageId)
            )
            if (updated == 1) {
                execSQL(
                    "UPDATE $TABLE_ENVELOPES SET retry_epoch = retry_epoch + 1 WHERE kind = ${Kind.MESSAGE.code} AND message_id = ?",
                    arrayOf(messageId)
                )
            } else {
                // Failed before the outbox existed (or envelope pruned): create a fresh one.
                val exists = longForQuery(
                    "SELECT COUNT(*) FROM $TABLE_ENVELOPES WHERE kind = ${Kind.MESSAGE.code} AND message_id = ?",
                    arrayOf(messageId)
                ) > 0L
                if (exists) return@transaction false
                insertMessageEnvelope(this, messageId, now, expiresAt, republishUntil)
            }
            database.updateDeliveryStatus(messageId, DeliveryStatus.Sending)
            true
        }
    }

    // ---------------------------------------------------------------- receipts

    /**
     * Queues a Nostr delivery/read receipt durably. If one already completed or failed for the
     * same message and [reackMinIntervalMs] has passed, it is re-queued so a sender that retried
     * (because our earlier receipt was lost) still gets one. Returns true if work was queued.
     */
    fun enqueueReceipt(
        kind: Kind,
        subjectMessageId: String,
        conversationId: String,
        recipientHex: String,
        privateChannel: Boolean,
        now: Long,
        expiresAt: Long,
        reackMinIntervalMs: Long
    ): Boolean {
        require(kind != Kind.MESSAGE)
        val db = database.writableDatabase
        return db.transaction {
            val existing = rawQuery(
                "SELECT envelope_id, state, last_reack_at FROM $TABLE_ENVELOPES WHERE kind = ? AND subject_id = ?",
                arrayOf(kind.code.toString(), subjectMessageId)
            ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), State.of(c.getInt(1)), c.getLong(2)) else null }
            if (existing == null) {
                val envelopeId = UUID.randomUUID().toString()
                insert(TABLE_ENVELOPES, null, ContentValues().apply {
                    put("envelope_id", envelopeId)
                    put("kind", kind.code)
                    put("subject_id", subjectMessageId)
                    put("conversation_id", conversationId)
                    put("state", State.QUEUED.code)
                    put("next_attempt_at", now)
                    put("created_at", now)
                    put("updated_at", now)
                    put("expires_at", expiresAt)
                    put("republish_until", expiresAt)
                    put("last_reack_at", now)
                    put("payload_ciphertext", encryptReceiptPayload(envelopeId, recipientHex, privateChannel))
                })
                return@transaction true
            }
            val (envelopeId, state, lastReack) = existing
            if (state.active && state != State.CARRIER_ACCEPTED) return@transaction false
            if (now - lastReack < reackMinIntervalMs) return@transaction false
            update(TABLE_ENVELOPES, ContentValues().apply {
                put("state", State.QUEUED.code)
                putNull("lease_until")
                put("next_attempt_at", now)
                put("expires_at", expiresAt)
                put("republish_until", expiresAt)
                put("last_reack_at", now)
                put("updated_at", now)
                putNull("carrier_accepted_at")
            }, "envelope_id = ?", arrayOf(envelopeId))
            execSQL("UPDATE $TABLE_EVENTS SET superseded_at = ? WHERE envelope_id = ? AND superseded_at IS NULL", arrayOf<Any>(now, envelopeId))
            true
        }
    }

    // ---------------------------------------------------------------- deletion, wipe, legacy

    fun deleteReceiptsForConversations(db: SQLiteDatabase, conversationIds: Collection<String>) {
        conversationIds.filter { it.isNotBlank() }.forEach { id ->
            db.delete(TABLE_ENVELOPES, "kind != ${Kind.MESSAGE.code} AND conversation_id = ? COLLATE NOCASE", arrayOf(id))
        }
    }

    fun clearAll(db: SQLiteDatabase) {
        db.delete(TABLE_EVENTS, null, null)
        db.delete(TABLE_ENVELOPES, null, null)
        db.delete(TABLE_SYNC, null, null)
    }

    /**
     * Adopts outgoing messages that were left at Sending before the outbox existed: recent ones
     * get a MESSAGE envelope, older ones are marked Failed (they can be retried manually).
     * Idempotent. Returns (adopted count, failed message ids).
     */
    fun adoptLegacyPending(now: Long, maxAgeMs: Long, expiresAt: Long, republishUntil: Long): Pair<Int, List<String>> {
        val db = database.writableDatabase
        return db.transaction {
            val candidates = rawQuery(
                """
                SELECT m.message_id, m.sent_at FROM private_messages m
                WHERE m.delivery_type = 1
                  AND NOT EXISTS (SELECT 1 FROM $TABLE_ENVELOPES e WHERE e.kind = ${Kind.MESSAGE.code} AND e.message_id = m.message_id)
                """.trimIndent(),
                emptyArray()
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
            var adopted = 0
            val failed = mutableListOf<String>()
            candidates.forEach { (messageId, sentAt) ->
                if (!database.isOwnOutgoingMessage(messageId)) return@forEach
                if (now - sentAt <= maxAgeMs) {
                    insertMessageEnvelope(this, messageId, now, expiresAt, republishUntil)
                    adopted++
                } else {
                    database.updateDeliveryStatus(messageId, DeliveryStatus.Failed(EXPIRED_REASON))
                    failed += messageId
                }
            }
            adopted to failed
        }
    }

    // ---------------------------------------------------------------- history sync

    /** Records that [relayUrl] delivered every stored event for [subscription] up to [syncedAt]. */
    fun recordSync(relayUrl: String, subscription: String, syncedAt: Long) {
        database.writableDatabase.execSQL(
            "INSERT INTO $TABLE_SYNC(relay_url, subscription, last_eose_at) VALUES(?, ?, ?) " +
                "ON CONFLICT(relay_url, subscription) DO UPDATE SET last_eose_at = MAX(last_eose_at, excluded.last_eose_at)",
            arrayOf<Any>(relayUrl, subscription, syncedAt)
        )
    }

    /** The oldest sync position across [relayUrls], or null if none of them ever synced. */
    fun lastSync(subscription: String, relayUrls: Collection<String>): Long? {
        if (relayUrls.isEmpty()) return null
        val placeholders = relayUrls.joinToString(",") { "?" }
        return database.readableDatabase.rawQuery(
            "SELECT MIN(last_eose_at) FROM $TABLE_SYNC WHERE subscription = ? AND relay_url IN ($placeholders)",
            arrayOf(subscription) + relayUrls.toTypedArray()
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    // ---------------------------------------------------------------- inspection

    fun envelopeForMessage(messageId: String): Envelope? =
        database.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_ENVELOPES WHERE kind = ${Kind.MESSAGE.code} AND message_id = ?",
            arrayOf(messageId)
        ).use { c -> if (c.moveToFirst()) c.toEnvelope() else null }

    fun receiptEnvelope(kind: Kind, subjectMessageId: String): Envelope? =
        database.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_ENVELOPES WHERE kind = ? AND subject_id = ?",
            arrayOf(kind.code.toString(), subjectMessageId)
        ).use { c -> if (c.moveToFirst()) c.toEnvelope() else null }

    fun rowCounts(): Map<String, Long> {
        val db = database.readableDatabase
        return listOf(TABLE_ENVELOPES, TABLE_EVENTS, TABLE_SYNC).associateWith {
            db.longForQuery("SELECT COUNT(*) FROM $it", emptyArray())
        }
    }

    /** Earliest time any envelope becomes due, for scheduling the next wake-up. */
    fun nextDueAt(): Long? =
        database.readableDatabase.rawQuery(
            """
            SELECT MIN(next_attempt_at) FROM $TABLE_ENVELOPES
            WHERE state IN (${State.QUEUED.code}, ${State.TRANSMITTING.code}, ${State.RETRY_WAIT.code})
               OR (state = ${State.CARRIER_ACCEPTED.code} AND next_attempt_at < republish_until)
            """.trimIndent(),
            emptyArray()
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    // ---------------------------------------------------------------- helpers

    private fun isTombstoned(db: SQLiteDatabase, messageId: String): Boolean =
        db.longForQuery("SELECT COUNT(*) FROM deleted_private_messages WHERE message_id = ?", arrayOf(messageId)) > 0L

    private fun encryptReceiptPayload(envelopeId: String, recipientHex: String, privateChannel: Boolean): ByteArray {
        val json = JSONObject().put("r", recipientHex).put("p", privateChannel).toString()
        return cipher.encrypt(json.toByteArray(Charsets.UTF_8), envelopeAad(envelopeId))
    }

    private fun Cursor.toEnvelope(): Envelope {
        fun col(name: String) = getColumnIndexOrThrow(name)
        fun longOrNull(name: String) = if (isNull(col(name))) null else getLong(col(name))
        val envelopeId = getString(col("envelope_id"))
        val payload = if (isNull(col("payload_ciphertext"))) null else runCatching {
            JSONObject(String(cipher.decrypt(getBlob(col("payload_ciphertext")), envelopeAad(envelopeId)), Charsets.UTF_8))
        }.getOrNull()
        return Envelope(
            envelopeId = envelopeId,
            kind = Kind.of(getInt(col("kind"))),
            messageId = if (isNull(col("message_id"))) null else getString(col("message_id")),
            subjectId = if (isNull(col("subject_id"))) null else getString(col("subject_id")),
            conversationId = if (isNull(col("conversation_id"))) null else getString(col("conversation_id")),
            state = State.of(getInt(col("state"))),
            retryEpoch = getInt(col("retry_epoch")),
            attemptCount = getInt(col("attempt_count")),
            republishCount = getInt(col("republish_count")),
            nextAttemptAt = getLong(col("next_attempt_at")),
            expiresAt = getLong(col("expires_at")),
            republishUntil = getLong(col("republish_until")),
            meshSentAt = longOrNull("mesh_sent_at"),
            meshPromotedEpoch = getInt(col("mesh_promoted_epoch")),
            carrierAcceptedAt = longOrNull("carrier_accepted_at"),
            receiptRecipientHex = payload?.optString("r")?.takeIf { it.isNotEmpty() },
            receiptPrivateChannel = payload?.optBoolean("p") ?: false
        )
    }

    private fun SQLiteDatabase.longForQuery(sql: String, args: Array<String>): Long =
        android.database.DatabaseUtils.longForQuery(this, sql, args)

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    companion object {
        const val TABLE_ENVELOPES = "outbound_envelopes"
        const val TABLE_EVENTS = "outbound_nostr_events"
        const val TABLE_SYNC = "nostr_sync_state"
        const val TRANSPORT_MESH = 1
        const val TRANSPORT_NOSTR = 2
        const val EXPIRED_REASON = "Not delivered in time"

        private fun eventAad(eventId: String) = "outbox-event:$eventId".toByteArray(Charsets.UTF_8)
        private fun envelopeAad(envelopeId: String) = "outbox-envelope:$envelopeId".toByteArray(Charsets.UTF_8)

        /** Creates the outbox tables (fresh install and the version 4 -> 5 migration). Additive only. */
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_ENVELOPES (
                    envelope_id TEXT PRIMARY KEY NOT NULL,
                    kind INTEGER NOT NULL,
                    message_id TEXT REFERENCES private_messages(message_id) ON DELETE CASCADE ON UPDATE CASCADE,
                    subject_id TEXT,
                    conversation_id TEXT COLLATE NOCASE,
                    state INTEGER NOT NULL,
                    retry_epoch INTEGER NOT NULL DEFAULT 0,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    republish_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL,
                    lease_until INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    republish_until INTEGER NOT NULL,
                    mesh_sent_at INTEGER,
                    mesh_promoted_epoch INTEGER NOT NULL DEFAULT -1,
                    carrier_accepted_at INTEGER,
                    last_reack_at INTEGER,
                    last_transport INTEGER,
                    last_error_code TEXT,
                    payload_ciphertext BLOB
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_outbound_message ON $TABLE_ENVELOPES(kind, message_id) WHERE message_id IS NOT NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_outbound_subject ON $TABLE_ENVELOPES(kind, subject_id) WHERE subject_id IS NOT NULL"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_outbound_due ON $TABLE_ENVELOPES(state, next_attempt_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_outbound_conversation ON $TABLE_ENVELOPES(conversation_id)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                    event_id TEXT PRIMARY KEY NOT NULL,
                    envelope_id TEXT NOT NULL REFERENCES $TABLE_ENVELOPES(envelope_id) ON DELETE CASCADE,
                    event_created_at INTEGER NOT NULL,
                    prepared_at INTEGER NOT NULL,
                    superseded_at INTEGER,
                    event_ciphertext BLOB NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_outbound_events_envelope ON $TABLE_EVENTS(envelope_id)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SYNC (
                    relay_url TEXT NOT NULL,
                    subscription TEXT NOT NULL,
                    last_eose_at INTEGER NOT NULL,
                    PRIMARY KEY (relay_url, subscription)
                )
                """.trimIndent()
            )
        }
    }
}
