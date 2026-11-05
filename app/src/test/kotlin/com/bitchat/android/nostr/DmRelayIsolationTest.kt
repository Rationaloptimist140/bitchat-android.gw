package com.bitchat.android.nostr

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Account private messaging must stay on its own relay manager and relays, never on relays
 * selected for a location channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class DmRelayIsolationTest {
    private val privateManager = NostrRelayManager.privateMessaging

    @Test
    fun `private messaging uses a separate manager limited to the DM relays`() {
        assertNotSame(NostrRelayManager.shared, privateManager)
        assertEquals(NostrRelayManager.PURPOSE_PRIVATE_MESSAGING, privateManager.purpose)
        assertEquals(NostrRelayManager.PURPOSE_LOCATION, NostrRelayManager.shared.purpose)
        assertEquals(
            NostrRelayManager.privateMessagingRelays().toSet(),
            privateManager.getRelayStatuses().map { it.url }.toSet()
        )
    }

    @Test
    fun `private manager refuses geohash and non DM relays`() {
        val before = privateManager.getRelayStatuses().map { it.url }.toSet()
        privateManager.ensureGeohashRelaysConnected("u4pruy", nRelays = 3, includeDefaults = true)
        privateManager.connectToRelays(listOf("wss://geohash-relay.example"))
        assertEquals(before, privateManager.getRelayStatuses().map { it.url }.toSet())
        assertTrue(privateManager.getRelaysForGeohash("u4pruy").isEmpty())
    }

    @Test
    fun `account gift wrap subscription is pinned to the DM relays on the private manager`() {
        val subscriptions = NostrSubscriptionManager(
            RuntimeEnvironment.getApplication(),
            owner = NostrRelayManager.OWNER_BACKGROUND,
            relayManagerProvider = { NostrRelayManager.privateMessaging(it) },
            giftWrapRelays = NostrRelayManager.privateMessagingRelays()
        )
        val id = "dm-isolation-${UUID.randomUUID()}"
        subscriptions.subscribeGiftWraps(
            pubkey = NostrIdentity.generate().publicKeyHex,
            sinceMs = System.currentTimeMillis() - 60_000L,
            id = id,
            handler = {}
        )
        try {
            val info = privateManager.getActiveSubscriptions()[id]
            assertEquals(NostrRelayManager.privateMessagingRelays().toSet(), info?.targetRelayUrls)
            assertTrue(NostrRelayManager.shared.getActiveSubscriptions()[id] == null)
        } finally {
            subscriptions.unsubscribe(id)
        }
    }
}
