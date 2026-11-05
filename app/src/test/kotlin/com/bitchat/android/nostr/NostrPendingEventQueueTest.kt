package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrPendingEventQueueTest {
    @Test
    fun `empty relay set is not queued`() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertNull(queue.enqueue(event("empty"), emptyList(), liveLocationToken = null))
        assertEquals(0, queue.size())
    }

    @Test
    fun `capacity evicts the oldest publish`() {
        val queue = NostrPendingEventQueue(capacity = 2)
        queue.enqueue(event("one"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("two"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("three"), listOf("relay"), liveLocationToken = null)

        assertEquals(
            listOf("two", "three"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    @Test
    fun `duplicate event publishes retain independent write state`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val signedEvent = event("same")
        val firstId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a", "relay-b"), liveLocationToken = null)
        )
        val secondId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a"), liveLocationToken = null)
        )
        assertNotEquals(firstId, secondId)

        // Writing one publish to relay-a leaves the other publish pending for relay-a.
        queue.markWritten(firstId, "relay-a")

        assertEquals(
            listOf(secondId),
            queue.pendingForRelay("relay-a").map { it.queueId }
        )
        assertEquals(
            listOf(firstId),
            queue.pendingForRelay("relay-b").map { it.queueId }
        )

        // A relay OK is keyed by event id: relay-a now stores the event for both publishes.
        queue.onRelayResponse(signedEvent.id, "relay-a", accepted = true, message = null)
        assertEquals(1, queue.size())
        assertEquals(emptyList<Long>(), queue.pendingForRelay("relay-a").map { it.queueId })
    }

    @Test
    fun `privacy purge retains non-live publishes`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("manual"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("live"), listOf("relay"), liveLocationToken = 42L)

        queue.removeLiveLocationEvents()

        assertEquals(
            listOf("manual"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    private fun event(content: String): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
    }
}
