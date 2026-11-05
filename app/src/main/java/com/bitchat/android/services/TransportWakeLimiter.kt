package com.bitchat.android.services

/**
 * Rate limit for transport-available wake-ups. A flapping network or a Bluetooth peer that
 * appears and disappears repeatedly can fire many events; each key is allowed through at most
 * once per [minIntervalMs]. The key set is bounded (oldest entries are dropped).
 */
internal class TransportWakeLimiter(
    private val minIntervalMs: Long,
    private val maxKeys: Int = 256
) {
    private val lastAllowed = LinkedHashMap<String, Long>()

    @Synchronized
    fun tryAcquire(key: String, now: Long): Boolean {
        val last = lastAllowed[key]
        if (last != null && now - last < minIntervalMs && now >= last) return false
        lastAllowed.remove(key)
        lastAllowed[key] = now
        while (lastAllowed.size > maxKeys) lastAllowed.remove(lastAllowed.keys.first())
        return true
    }
}
