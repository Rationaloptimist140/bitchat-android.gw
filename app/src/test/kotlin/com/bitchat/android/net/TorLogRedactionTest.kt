package com.bitchat.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TorLogRedactionTest {
    @Test
    fun `relay destinations are removed from displayed Tor log lines`() {
        listOf(
            "SOCKS5 CONNECT to relay.damus.io:443",
            "Tor connection established to nostr21.com:443",
            "SOCKS connection closed for offchain.pub:443",
            "ERROR: Failed to connect to relay.primal.net:443: timeout"
        ).forEach { line ->
            val shown = ArtiTorManager.redactLogLine(line)
            assertFalse(shown, Regex("""damus|nostr21|offchain|primal""").containsMatchIn(shown))
        }
        assertEquals("SOCKS5 CONNECT to [destination]", ArtiTorManager.redactLogLine("SOCKS5 CONNECT to relay.damus.io:443"))
    }

    @Test
    fun `bootstrap progress lines are unchanged`() {
        val line = "Sufficiently bootstrapped; system SOCKS now functional"
        assertEquals(line, ArtiTorManager.redactLogLine(line))
    }
}
