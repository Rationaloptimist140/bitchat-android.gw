package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrEnvelopePublisher
import com.bitchat.android.nostr.NostrPendingEventQueue
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Drives the durable outbox: claims due envelopes, routes each attempt over the mesh or Nostr,
 * records relay acceptance, expires stale work and recovers after crashes.
 *
 * It is the only component that transmits outbox envelopes. The router decides *which* transport
 * is usable; this class decides *when* to try and records the evidence. Every attempt uses the
 * envelope's original message UUID, so retries and transport fallback can never create a second
 * message at the receiver (which de-duplicates by message id and re-acknowledges duplicates).
 */
internal class OutboxScheduler(
    private val context: Context,
    private val repository: ConversationRepository,
    private val routing: Routing,
    private val publisher: NostrEnvelopePublisher = NostrEnvelopePublisher(context, repository),
    private val relayOutcomes: () -> kotlinx.coroutines.flow.Flow<NostrRelayManager.PublishOutcome> = {
        NostrRelayManager.privateMessaging(context).publishOutcomes
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    /** Transport availability, provided by [MessageRouter]. */
    interface Routing {
        val mesh: MeshService
        /** Mesh peer id when the conversation's peer is connected with an established session. */
        fun readyMeshTarget(conversationId: String): String?
        /** Mesh peer id when the peer is visible but has no session yet. */
        fun visibleMeshTarget(conversationId: String): String?
        /** Nostr target (Noise key hex or peer id) when internet delivery is allowed. */
        fun nostrTarget(conversationId: String): String?
        fun requestHandshake(conversationId: String, meshTarget: String)
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    private var outcomeJob: Job? = null
    @Volatile private var recovered = false

    val isRunning: Boolean get() = loopJob?.isActive == true

    @Synchronized
    fun start() {
        if (outcomeJob?.isActive != true) {
            outcomeJob = scope.launch {
                relayOutcomes().collect { outcome -> runCatching { onRelayOutcome(outcome) } }
            }
        }
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                runCatching { runOnce() }.onFailure { Log.w(TAG, "Outbox pass failed: ${it.javaClass.simpleName}") }
                val waitMs = runCatching { repository.withOutbox { it.nextDueAt() } }.getOrNull()
                    ?.let { (it - clock()).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS) } ?: MAX_WAIT_MS
                withTimeoutOrNull(waitMs) { wake.receive() }
            }
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Requests an immediate pass (new message, peer appeared, session or network available). */
    fun kick() {
        wake.trySend(Unit)
    }

    /** One scheduler pass. Public for deterministic tests. */
    suspend fun runOnce() {
        val now = clock()
        if (!recovered) {
            recovered = true
            val (adopted, failed) = repository.withOutbox {
                it.adoptLegacyPending(now, LEGACY_ADOPT_MAX_AGE_MS, now + ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS, now + ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS)
            }
            failed.forEach { AppStateStore.updatePrivateMessageStatus(it, DeliveryStatus.Failed(OutboxStore.EXPIRED_REASON)) }
            if (adopted > 0 || failed.isNotEmpty()) Log.i(TAG, "Adopted $adopted legacy pending messages, failed ${failed.size}")
        }
        repository.withOutbox { it.recoverExpiredLeases(now) }
        repository.withOutbox { it.expireDue(now) }.forEach { messageId ->
            AppStateStore.updatePrivateMessageStatus(messageId, DeliveryStatus.Failed(OutboxStore.EXPIRED_REASON))
        }
        val due = repository.withOutbox { it.claimDue(now, LEASE_MS, BATCH_SIZE) }
        due.forEach { envelope ->
            runCatching { dispatch(envelope) }.onFailure {
                Log.w(TAG, "Dispatch failed: ${it.javaClass.simpleName}")
                repository.withOutbox { store -> store.release(envelope.envelopeId, clock(), clock() + retryDelayMs(envelope.attemptCount), "dispatch-error") }
            }
        }
    }

    private suspend fun dispatch(envelope: OutboxStore.Envelope) {
        when (envelope.kind) {
            OutboxStore.Kind.MESSAGE -> dispatchMessage(envelope)
            OutboxStore.Kind.DELIVERY_ACK, OutboxStore.Kind.READ_RECEIPT -> dispatchReceipt(envelope)
        }
    }

    private suspend fun dispatchMessage(envelope: OutboxStore.Envelope) {
        val messageId = envelope.messageId ?: return
        val (conversationId, message) = repository.loadMessageWithConversation(messageId) ?: return
        val now = clock()
        val nickname = message.recipientNickname.orEmpty()

        if (envelope.state == OutboxStore.State.CARRIER_ACCEPTED) {
            // Accepted by a relay but no receipt yet: try the mesh once per epoch if the peer is
            // here (faster, and the receiver de-duplicates), otherwise republish over Nostr.
            val nextRepublish = now + republishDelayMs(envelope.republishCount)
            val meshTarget = routing.readyMeshTarget(conversationId)
            if (meshTarget != null && envelope.meshPromotedEpoch != envelope.retryEpoch) {
                routing.mesh.sendPrivateMessage(message.content, meshTarget, nickname, messageId)
                repository.withOutbox { it.markMeshSent(envelope.envelopeId, now, nextRepublish, envelope.retryEpoch) }
                return
            }
            val target = routing.nostrTarget(conversationId)
            if (target != null) publisher.publishMessage(envelope, message.content, target)
            repository.withOutbox { it.scheduleRepublish(envelope.envelopeId, now, nextRepublish) }
            return
        }

        routing.readyMeshTarget(conversationId)?.let { meshTarget ->
            routing.mesh.sendPrivateMessage(message.content, meshTarget, nickname, messageId)
            // Mesh carries no custodian evidence: wait for the receipt, then re-route if none.
            repository.withOutbox { it.markMeshSent(envelope.envelopeId, now, now + MESH_RECEIPT_TIMEOUT_MS, null) }
            return
        }

        routing.nostrTarget(conversationId)?.let { target ->
            when (val result = publisher.publishMessage(envelope, message.content, target)) {
                is NostrEnvelopePublisher.Result.Published -> Unit // acceptance arrives via relay OK
                is NostrEnvelopePublisher.Result.Unroutable -> repository.withOutbox {
                    it.release(envelope.envelopeId, now, now + retryDelayMs(envelope.attemptCount), "nostr-unroutable")
                }
            }
            return
        }

        // No usable transport yet: wait, and nudge a handshake if the peer is visible.
        routing.visibleMeshTarget(conversationId)?.let { routing.requestHandshake(conversationId, it) }
        repository.withOutbox { it.release(envelope.envelopeId, now, now + retryDelayMs(envelope.attemptCount), "no-route") }
    }

    private suspend fun dispatchReceipt(envelope: OutboxStore.Envelope) {
        val now = clock()
        when (publisher.publishReceipt(envelope)) {
            is NostrEnvelopePublisher.Result.Published -> Unit
            is NostrEnvelopePublisher.Result.Unroutable -> repository.withOutbox {
                it.release(envelope.envelopeId, now, now + retryDelayMs(envelope.attemptCount), "receipt-unroutable")
            }
        }
    }

    /** Relay `OK` evidence for any outbox event (T4). */
    internal suspend fun onRelayOutcome(outcome: NostrRelayManager.PublishOutcome) {
        if (outcome.kind != NostrPendingEventQueue.ResponseKind.ACCEPTED) return
        val now = clock()
        val accepted = repository.withOutbox {
            it.markCarrierAccepted(outcome.eventId, now, now + republishDelayMs(0))
        } ?: return
        if (accepted.kind == OutboxStore.Kind.MESSAGE && accepted.messageId != null) {
            AppStateStore.updatePrivateMessageStatus(accepted.messageId, DeliveryStatus.Sent)
        }
    }

    companion object {
        private const val TAG = "OutboxScheduler"
        const val LEASE_MS = 60_000L
        const val BATCH_SIZE = 25
        const val MESH_RECEIPT_TIMEOUT_MS = 30_000L
        private const val MIN_WAIT_MS = 250L
        private const val MAX_WAIT_MS = 30_000L
        private const val LEGACY_ADOPT_MAX_AGE_MS = ConversationDatabase.OUTBOX_MESSAGE_EXPIRY_MS

        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L)
        private val REPUBLISH_DELAYS_MS = longArrayOf(3_600_000L, 21_600_000L, 86_400_000L, 259_200_000L)

        /** Backoff for unaccepted work, with ±20% jitter so many devices do not retry in lockstep. */
        fun retryDelayMs(attempt: Int, random: Random = Random.Default): Long {
            val base = RETRY_DELAYS_MS[(attempt - 1).coerceIn(0, RETRY_DELAYS_MS.size - 1)]
            return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        }

        /** Republish schedule for relay-accepted messages still awaiting a receipt. */
        fun republishDelayMs(republishCount: Int): Long =
            REPUBLISH_DELAYS_MS[republishCount.coerceIn(0, REPUBLISH_DELAYS_MS.size - 1)]
    }
}
