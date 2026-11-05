package com.bitchat.android.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards against logging secrets or message plaintext. Every android.util.Log call in the app
 * and Wear sources is scanned for interpolation of key material, seeds, decrypted payloads or
 * message bodies. Sizes and lengths of such values are allowed.
 */
class LogHygieneTest {
    private val logCall = Regex("""\bLog\.(v|d|i|w|e|wtf|println)\s*\(""")
    private val forbidden = Regex(
        """\$\{?\s*(privateKey\w*|privateKeyHex|wrapPrivateKey|senderPrivateKey\w*|recipientPrivateKey\w*|deviceSeed|seed|plaintext|decrypted\w*|rumorJSON|sealJSON|messageContent|pm\.content|message\.content|content)\b(?!\s*\.(size|length|isEmpty|isNotEmpty))"""
    )

    @Test
    fun `log statements never interpolate secrets or message plaintext`() {
        val roots = listOf(File("src/main/java"), File("src/main/kotlin"), File("../wear/src/main/java"))
            .filter { it.isDirectory }
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (logCall.containsMatchIn(line) && forbidden.containsMatchIn(line)) {
                        "${file.invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
        }
        assertEquals("remove secret or plaintext interpolation from these log calls", emptyList<String>(), offenders)
    }
}
