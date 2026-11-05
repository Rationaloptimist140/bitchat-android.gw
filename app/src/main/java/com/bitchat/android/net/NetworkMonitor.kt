package com.bitchat.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.services.MessageRouter

/**
 * Reacts to the device regaining internet connectivity.
 *
 * Without this, relay sockets only recovered through their reconnect backoff (up to 5 minutes)
 * after a network change, and queued outbox work waited for its next scheduled attempt. When a
 * validated network becomes available, disconnected relays reconnect immediately and the outbox
 * is woken. Healthy sockets are left alone; Tor routing is unchanged because reconnection uses
 * the same proxy-aware client.
 */
object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    @Volatile private var registered = false

    private val tracker = ValidatedNetworkTracker()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            // Capability callbacks repeat for signal and bandwidth changes; act on transitions only.
            if (tracker.onCapabilities(network.toString(), validated)) onInternetAvailable()
        }

        override fun onLost(network: Network) {
            tracker.onLost(network.toString())
        }
    }

    fun start(context: Context) {
        if (registered) return
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        }.onFailure { Log.w(TAG, "Unable to register network callback") }
    }

    /** Reconnects relays and wakes the outbox. Public for tests and manual triggers. */
    fun onInternetAvailable() {
        NostrRelayManager.all().forEach { manager ->
            runCatching { manager.onNetworkAvailable() }
        }
        runCatching { MessageRouter.tryGetInstance()?.onInternetAvailable() }
        runCatching { com.bitchat.android.nostr.NostrBackgroundRuntime.requestHistoryBackfill() }
    }
}

/**
 * Turns the stream of default-network capability callbacks into "internet became available"
 * transitions: a validated network that differs from the last one, or the same network after it
 * lost validation or was lost. Repeated callbacks for an unchanged validated network are ignored.
 */
internal class ValidatedNetworkTracker {
    private var current: String? = null

    /** Returns true when this callback is a transition to a (new) validated network. */
    @Synchronized
    fun onCapabilities(networkId: String, validated: Boolean): Boolean {
        if (!validated) {
            if (current == networkId) current = null
            return false
        }
        if (current == networkId) return false
        current = networkId
        return true
    }

    @Synchronized
    fun onLost(networkId: String) {
        if (current == networkId) current = null
    }
}
