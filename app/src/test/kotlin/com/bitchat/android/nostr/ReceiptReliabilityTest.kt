package com.bitchat.android.nostr

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.OutboxStore
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID

/**
 * Stage D: durable receipts, re-acknowledgement of duplicates across transports, no
 * resurrection of deleted messages, and relay keep-alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptReliabilityTest {
    private val gson = Gson()
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ConversationRepository
    private lateinit var databaseName: String
    private val sender = NostrIdentity.generate()
    private val me = NostrIdentity.generate()
    private val conversation get() = "nostr_${sender.publicKeyHex.take(16)}"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppStateStore.clear()
        databaseName = "receipts-${UUID.randomUUID()}.db"
        repository = ConversationRepository(
            context = RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(repository)
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("receipts-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        FavoritesPersistenceService.installForTest(application, SecureIdentityStateManager(prefs, testOnly = true))
    }

    @After
    fun tearDown() {
        FavoritesPersistenceService.resetForTest()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `account DM receipt is queued durably before being marked delivered`() {
        val seenStore = seenStore()
        handler(seenStore).onGiftWrap(wrap("hello", "pm-1"), "", me)
        waitUntil { receipt("pm-1") != null }
        val envelope = receipt("pm-1")!!
        assertEquals(sender.publicKeyHex.lowercase(), envelope.receiptRecipientHex)
        assertTrue("account receipts use the private-messaging relays", envelope.receiptPrivateChannel)
        verify(seenStore).markDelivered("pm-1")
    }

    @Test
    fun `message already received over the mesh is not stored twice and is re-acknowledged`() {
        // Arrived over the mesh first (same message id).
        runBlocking {
            AppStateStore.addPrivateMessageDurably(
                conversation,
                BitchatMessage(id = "pm-2", sender = "sender", content = "hello", timestamp = Date(1_000L), isPrivate = true, senderPeerID = conversation)
            )
        }
        handler(seenStore()).onGiftWrap(wrap("hello", "pm-2"), "", me)
        waitUntil { receipt("pm-2") != null }
        val copies = AppStateStore.privateMessages.value.values.flatten().count { it.id == "pm-2" }
        assertEquals(1, copies)
    }

    @Test
    fun `retry of a deleted message is not resurrected but is acknowledged`() {
        val handler = handler(seenStore())
        handler.onGiftWrap(wrap("first copy", "pm-3"), "", me)
        waitUntil { runBlocking { repository.isKnownOrDeletedMessage("pm-3") } }
        runBlocking { repository.deleteMessage("pm-3"); repository.awaitPendingWrites() }
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(repository)

        // The sender retries with a new gift wrap carrying the same message id.
        handler(seenStore()).onGiftWrap(wrap("first copy", "pm-3"), "", me)
        runBlocking { delay(300); repository.awaitPendingWrites() }
        assertFalse(AppStateStore.privateMessages.value.values.flatten().any { it.id == "pm-3" })
        assertTrue(runBlocking { repository.loadDeliveryStatusForTest("pm-3") } == null)
        assertNotNull(receipt("pm-3"))
    }

    @Test
    fun `relay websocket client sends keep-alive pings`() {
        assertEquals(30_000, com.bitchat.android.net.OkHttpProvider.webSocketClient().pingIntervalMillis)
    }

    private fun receipt(messageId: String): OutboxStore.Envelope? =
        runBlocking { repository.withOutbox { it.receiptEnvelope(OutboxStore.Kind.DELIVERY_ACK, messageId) } }

    private fun seenStore(): SeenMessageStore = mock<SeenMessageStore>().also {
        whenever(it.hasDelivered(any())).thenReturn(false)
        whenever(it.hasBeenReadLocally(any())).thenReturn(false)
    }

    private fun handler(seenStore: SeenMessageStore): NostrDirectMessageHandler {
        val application = RuntimeEnvironment.getApplication()
        val state = ChatState(scope).apply { setNickname("me") }
        val dataManager = DataManager(application)
        return NostrDirectMessageHandler(
            application = application,
            state = state,
            privateChatManager = PrivateChatManager(state, MessageManager(state), dataManager, mock<NoiseSessionDelegate>()),
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, state, dataManager, scope),
            dataManager = dataManager,
            seenStoreProvider = { seenStore }
        )
    }

    private fun wrap(content: String, messageId: String): NostrEvent {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val embedded = requireNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient(content, messageId))
        val rumorBase = NostrEvent(pubkey = sender.publicKeyHex, createdAt = now - 20, kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", me.publicKeyHex)), content = embedded)
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())
        val seal = NostrEvent(pubkey = sender.publicKeyHex, createdAt = now - 10, kind = NostrKind.SEAL, tags = emptyList(),
            content = NostrCrypto.encryptNIP44(gson.toJson(rumor), me.publicKeyHex, sender.privateKeyHex)).sign(sender.privateKeyHex)
        val (wrapPrivate, wrapPublic) = NostrCrypto.generateKeyPair()
        return NostrEvent(pubkey = wrapPublic, createdAt = now - 5, kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", me.publicKeyHex)),
            content = NostrCrypto.encryptNIP44(gson.toJson(seal), me.publicKeyHex, wrapPrivate)).sign(wrapPrivate)
    }

    private fun waitUntil(condition: () -> Boolean) {
        runBlocking { withTimeout(5_000) { while (!condition()) delay(20) } }
    }
}
