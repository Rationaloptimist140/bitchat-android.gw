package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.OutboxStore
import com.google.gson.Gson

/**
 * Publishes outbox envelopes over the private-messaging relays.
 *
 * Signed gift wraps are persisted (encrypted) before any socket write and the same event — same
 * event id — is reused for every republish, so relays and receivers recognise repeats. A fresh
 * event (new id, same message UUID) is generated only when the stored one is missing or its
 * randomised timestamp is old enough to fall outside receivers' history windows.
 */
internal open class NostrEnvelopePublisher(
    private val context: Context,
    private val repository: ConversationRepository,
    private val transport: () -> NostrTransport = { NostrTransport.getInstance(context) },
    private val relays: () -> NostrRelayManager = { NostrRelayManager.privateMessaging(context) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed class Result {
        data class Published(val eventId: String, val regenerated: Boolean) : Result()
        data class Unroutable(val reason: String) : Result()
    }

    private val gson = Gson()

    /** Publishes (or republishes) a MESSAGE envelope's gift wrap. */
    open suspend fun publishMessage(envelope: OutboxStore.Envelope, content: String, nostrTarget: String): Result {
        val messageId = envelope.messageId ?: return Result.Unroutable("Missing message")
        reusableEvent(envelope)?.let { stored ->
            send(stored)
            return Result.Published(stored.id, regenerated = false)
        }
        return when (val prepared = transport().prepareAccountPrivateMessage(content, nostrTarget, messageId)) {
            is NostrTransport.PrepareResult.Unroutable -> Result.Unroutable(prepared.reason)
            is NostrTransport.PrepareResult.Prepared -> {
                persistThenSend(envelope, prepared.event)
                Result.Published(prepared.event.id, regenerated = true)
            }
        }
    }

    /** Publishes a delivery/read receipt envelope. */
    open suspend fun publishReceipt(envelope: OutboxStore.Envelope): Result {
        val messageId = envelope.subjectId ?: return Result.Unroutable("Missing message")
        val recipient = envelope.receiptRecipientHex ?: return Result.Unroutable("Missing recipient")
        reusableEvent(envelope)?.let { stored ->
            send(stored)
            return Result.Published(stored.id, regenerated = false)
        }
        val type = if (envelope.kind == OutboxStore.Kind.READ_RECEIPT) NoisePayloadType.READ_RECEIPT else NoisePayloadType.DELIVERED
        val event = transport().prepareAccountReceipt(type, messageId, recipient)
            ?: return Result.Unroutable("Receipt could not be prepared")
        persistThenSend(envelope, event)
        return Result.Published(event.id, regenerated = true)
    }

    private suspend fun reusableEvent(envelope: OutboxStore.Envelope): NostrEvent? {
        val stored = repository.withOutbox { it.activeNostrEvent(envelope.envelopeId) } ?: return null
        val ageSeconds = clock() / 1000 - stored.eventCreatedAt
        if (ageSeconds > STALE_EVENT_SECONDS) return null
        return runCatching { gson.fromJson(stored.eventJson, NostrEvent::class.java) }
            .getOrNull()
            ?.takeIf { it.id == stored.eventId && it.isValidSignature() }
    }

    private suspend fun persistThenSend(envelope: OutboxStore.Envelope, event: NostrEvent) {
        val now = clock()
        // Crash boundary T3: the signed event is durable before it can reach any relay.
        repository.withOutbox {
            it.recordNostrEvent(
                envelopeId = envelope.envelopeId,
                eventId = event.id,
                eventJson = gson.toJson(event),
                eventCreatedAt = event.createdAt.toLong(),
                now = now,
                acceptanceDeadline = now + ACCEPTANCE_WINDOW_MS
            )
        }
        send(event)
    }

    private fun send(event: NostrEvent) {
        NostrRelayManager.registerPendingGiftWrap(event.id)
        relays().sendEvent(event, NostrRelayManager.privateMessagingRelays())
        Log.d(TAG, "Published outbox event ${event.id.take(8)}…")
    }

    companion object {
        private const val TAG = "NostrEnvelopePublisher"

        /** Without relay acceptance in this window the same event is published again. */
        const val ACCEPTANCE_WINDOW_MS = 60_000L

        /**
         * Gift-wrap timestamps are backdated by up to 22 h; receivers (iOS: 24 h) only look back
         * a limited time. An event whose timestamp is older than this is replaced by a fresh one.
         */
        const val STALE_EVENT_SECONDS = 24L * 60 * 60
    }
}
