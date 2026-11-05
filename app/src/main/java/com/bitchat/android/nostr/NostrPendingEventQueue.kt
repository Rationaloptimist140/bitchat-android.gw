package com.bitchat.android.nostr

/**
 * Thread-safe bounded queue of relay deliveries that have not yet been *accepted* by each target
 * relay.
 *
 * A delivery moves through, per relay:
 * - PENDING: needs to be written to that relay's socket (initially, after a retryable rejection,
 *   after the socket closed while a write was unacknowledged, or after an acknowledgement timeout);
 * - WRITTEN: handed to the WebSocket, waiting for the relay's `OK` frame;
 * and leaves the queue for that relay only when the relay accepts it (`OK true`, or `OK false`
 * with a `duplicate:` reason, which means the relay already stores it) or rejects it permanently.
 *
 * A successful `WebSocket.send()` only means the frame was buffered locally, so it is never
 * treated as relay acceptance.
 *
 * Queue entries have a local ID rather than using the Nostr event ID: the same signed event may be
 * intentionally published more than once with different relay sets or privacy provenance. Relay
 * responses are keyed by event ID and resolve every entry carrying that event for that relay.
 */
internal class NostrPendingEventQueue(
    private val capacity: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(capacity > 0)
    }

    data class Delivery(
        val queueId: Long,
        val event: NostrEvent,
        val liveLocationToken: Long?
    )

    /** How a relay's `OK` frame is interpreted. */
    enum class ResponseKind { ACCEPTED, RETRYABLE, PERMANENT }

    private enum class RelayState { PENDING, WRITTEN }

    private class RelayDelivery {
        var state: RelayState = RelayState.PENDING
        var writtenAt: Long = 0L
        var notBefore: Long = 0L
        var retryableRejections: Int = 0
    }

    private class Entry(
        val queueId: Long,
        val event: NostrEvent,
        val relays: MutableMap<String, RelayDelivery>,
        val liveLocationToken: Long?
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextQueueId = 1L

    fun enqueue(
        event: NostrEvent,
        relayUrls: Collection<String>,
        liveLocationToken: Long?
    ): Long? {
        val pendingRelays = relayUrls.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (pendingRelays.isEmpty()) return null

        return synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            val queueId = nextQueueId++
            entries.addLast(
                Entry(
                    queueId = queueId,
                    event = event,
                    relays = pendingRelays.associateWithTo(linkedMapOf()) { RelayDelivery() },
                    liveLocationToken = liveLocationToken
                )
            )
            queueId
        }
    }

    /** Deliveries that should be (re)written to [relayUrl] now. */
    fun pendingForRelay(relayUrl: String): List<Delivery> = synchronized(lock) {
        val now = clock()
        entries
            .asSequence()
            .filter { entry ->
                val relay = entry.relays[relayUrl] ?: return@filter false
                relay.state == RelayState.PENDING && relay.notBefore <= now
            }
            .map { Delivery(it.queueId, it.event, it.liveLocationToken) }
            .toList()
    }

    /** The frame was handed to the socket; acceptance is still unknown. */
    fun markWritten(queueId: Long, relayUrl: String) {
        synchronized(lock) {
            val relay = entries.firstOrNull { it.queueId == queueId }?.relays?.get(relayUrl) ?: return
            relay.state = RelayState.WRITTEN
            relay.writtenAt = clock()
        }
    }

    /**
     * Applies a relay `OK` frame. Returns the interpretation, or null when no queued delivery
     * for that event and relay exists (for example after it was already resolved).
     */
    fun onRelayResponse(eventId: String, relayUrl: String, accepted: Boolean, message: String?): ResponseKind? {
        val kind = classify(accepted, message)
        var matched = false
        synchronized(lock) {
            val now = clock()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.event.id != eventId) continue
                val relay = entry.relays[relayUrl] ?: continue
                matched = true
                when (kind) {
                    ResponseKind.ACCEPTED, ResponseKind.PERMANENT -> entry.relays.remove(relayUrl)
                    ResponseKind.RETRYABLE -> {
                        relay.retryableRejections += 1
                        relay.state = RelayState.PENDING
                        relay.notBefore = now + retryDelayMs(relay.retryableRejections)
                    }
                }
                if (entry.relays.isEmpty()) iterator.remove()
            }
        }
        return if (matched) kind else null
    }

    /** The socket to [relayUrl] closed: unacknowledged writes may be lost and must be resent. */
    fun requeueWritten(relayUrl: String) {
        synchronized(lock) {
            entries.forEach { entry ->
                entry.relays[relayUrl]?.takeIf { it.state == RelayState.WRITTEN }?.let {
                    it.state = RelayState.PENDING
                    it.notBefore = 0L
                }
            }
        }
    }

    /**
     * Writes not acknowledged within [timeoutMs] become pending again. Returns the relays that
     * now have work to resend.
     */
    fun expireUnacknowledged(timeoutMs: Long): Set<String> = synchronized(lock) {
        val deadline = clock() - timeoutMs
        buildSet {
            entries.forEach { entry ->
                entry.relays.forEach { (url, relay) ->
                    if (relay.state == RelayState.WRITTEN && relay.writtenAt <= deadline) {
                        relay.state = RelayState.PENDING
                        relay.notBefore = 0L
                        add(url)
                    }
                }
            }
        }
    }

    /** Relays that currently have pending deliveries ready to send. */
    fun relaysWithDueWork(): Set<String> = synchronized(lock) {
        val now = clock()
        buildSet {
            entries.forEach { entry ->
                entry.relays.forEach { (url, relay) ->
                    if (relay.state == RelayState.PENDING && relay.notBefore <= now) add(url)
                }
            }
        }
    }

    fun removeLiveLocationEvents() {
        synchronized(lock) {
            entries.removeAll { it.liveLocationToken != null }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        private val PERMANENT_PREFIXES = listOf("invalid:", "blocked:", "restricted:", "pow:", "auth-required:")

        /** NIP-01 `OK` interpretation. */
        fun classify(accepted: Boolean, message: String?): ResponseKind {
            if (accepted) return ResponseKind.ACCEPTED
            val reason = message?.trim()?.lowercase().orEmpty()
            return when {
                reason.startsWith("duplicate:") -> ResponseKind.ACCEPTED
                PERMANENT_PREFIXES.any { reason.startsWith(it) } -> ResponseKind.PERMANENT
                else -> ResponseKind.RETRYABLE // rate-limited:, error:, or no machine-readable prefix
            }
        }

        private fun retryDelayMs(rejections: Int): Long =
            (2_000L shl (rejections - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
    }
}
