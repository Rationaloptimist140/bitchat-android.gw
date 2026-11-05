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

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                onInternetAvailable()
            }
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
        runCatching { MessageRouter.tryGetInstance()?.kickOutbox() }
        runCatching { com.bitchat.android.nostr.NostrBackgroundRuntime.requestHistoryBackfill() }
    }
}
