package com.bitchat.android.nostr

/**
 * How far back to fetch account gift wraps.
 *
 * Senders backdate gift-wrap timestamps by up to 22 hours (NIP-17 timing privacy), so a message
 * published while this device was offline can carry a `created_at` up to 22 hours before it was
 * published. The window therefore starts at the last completed sync minus that backdating plus a
 * margin, never later than the live subscription's 48-hour window, and never earlier than a
 * bounded maximum so a device that was away for months does not download an unbounded history.
 */
object HistorySyncPolicy {
    const val ACCOUNT_DM_SUBSCRIPTION = "chat-messages"
    const val LIVE_WINDOW_MS = 48L * 60 * 60 * 1000
    const val BACKDATING_ALLOWANCE_MS = 23L * 60 * 60 * 1000 // 22 h NIP-17 backdating + 1 h margin
    const val MAX_BACKFILL_MS = 7L * 24 * 60 * 60 * 1000

    /** Gift wraps older than this are dropped: the maximum window plus the backdating allowance. */
    const val MAX_GIFT_WRAP_AGE_SECONDS = (MAX_BACKFILL_MS + BACKDATING_ALLOWANCE_MS) / 1000

    fun backfillSinceMs(nowMs: Long, lastSyncMs: Long?): Long {
        val liveSince = nowMs - LIVE_WINDOW_MS
        if (lastSyncMs == null) return liveSince
        val fromSync = lastSyncMs - BACKDATING_ALLOWANCE_MS
        return minOf(liveSince, fromSync).coerceAtLeast(nowMs - MAX_BACKFILL_MS)
    }
}
