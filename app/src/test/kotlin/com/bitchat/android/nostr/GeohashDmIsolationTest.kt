package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Geohash-identity DMs keep their weaker, direct delivery and must never enter the durable
 * outbox, whose routes (mesh, account identity) would link the geohash persona to the account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class GeohashDmIsolationTest {
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ConversationRepository
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppStateStore.clear()
        GeohashAliasRegistry.clear()
        databaseName = "geo-dm-${UUID.randomUUID()}.db"
        repository = ConversationRepository(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined, databaseName, InMemoryConversationStorageCipher())
        AppStateStore.setConversationRepositoryForTest(repository)
    }

    @After
    fun tearDown() {
        GeohashAliasRegistry.clear()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
        scope.cancel()
    }

    @Test
    fun `geohash DM is stored without an outbox envelope`() {
        val geohashPeer = NostrIdentity.generate().publicKeyHex
        val alias = "nostr_${geohashPeer.take(16)}"
        GeohashAliasRegistry.put(alias, geohashPeer)

        val id = send(alias)
        assertTrue(runBlocking { repository.isKnownOrDeletedMessage(id) })
        assertNull(runBlocking { repository.withOutbox { it.envelopeForMessage(id) } })
    }

    @Test
    fun `account DM to the same kind of id still gets a durable envelope`() {
        val accountPeer = NostrIdentity.generate().publicKeyHex
        val id = send("nostr_${accountPeer.take(16)}")
        assertNotNull(runBlocking { repository.withOutbox { it.envelopeForMessage(id) } })
    }

    private fun send(peerID: String): String {
        val application = RuntimeEnvironment.getApplication()
        val state = ChatState(scope).apply { setNickname("me") }
        val manager = PrivateChatManager(state, MessageManager(state), DataManager(application), mock<NoiseSessionDelegate>())
        var sentId: String? = null
        val ok = runBlocking {
            manager.sendPrivateMessageDurably("hi", peerID, "peer", "me", "0123456789abcdef") { _, _, _, id -> sentId = id }
        }
        assertTrue(ok)
        return requireNotNull(sentId)
    }
}
