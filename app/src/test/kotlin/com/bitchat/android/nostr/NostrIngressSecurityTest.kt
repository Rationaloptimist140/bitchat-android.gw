package com.bitchat.android.nostr

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import android.os.Looper
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.Collections
import java.util.Date
import java.util.UUID

/**
 * Regression tests for the receive-side security fixes: authenticated favourite control,
 * verification before deduplication, fail-closed filtering and receipt binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NostrIngressSecurityTest {
    private val gson = Gson()
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ConversationRepository
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppStateStore.clear()
        databaseName = "nostr-ingress-${UUID.randomUUID()}.db"
        repository = ConversationRepository(
            context = RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(repository)
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("ingress-${UUID.randomUUID()}", Context.MODE_PRIVATE)
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

    // ---- S1: favourite control is bound to the authenticated sender ----

    @Test
    fun `favorite control claiming another contact's npub is rejected`() {
        val victim = NostrIdentity.generate()
        val attacker = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val victimNoiseKey = randomKey()
        val favorites = FavoritesPersistenceService.shared
        favorites.updateFavoriteStatus(victimNoiseKey, "victim", true)
        favorites.updateNostrPublicKey(victimNoiseKey, victim.npub)
        favorites.updatePeerFavoritedUs(victimNoiseKey, true)

        val handler = handler()
        handler.onGiftWrap(
            giftWrap(pm(FavoriteControlMessage.encode(false, victim.npub), "forged-unfavorite"), "forged-unfavorite", attacker, recipient),
            "",
            recipient
        )
        settle()

        assertTrue(favorites.getFavoriteStatus(victimNoiseKey)!!.theyFavoritedUs)
        assertEquals(victim.npub, favorites.getFavoriteStatus(victimNoiseKey)!!.peerNostrPublicKey)
    }

    @Test
    fun `favorite control from the authenticated contact is applied`() {
        val contact = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val noiseKey = randomKey()
        val favorites = FavoritesPersistenceService.shared
        favorites.updateFavoriteStatus(noiseKey, "contact", true)
        favorites.updateNostrPublicKey(noiseKey, contact.npub)
        favorites.updatePeerFavoritedUs(noiseKey, true)

        handler().onGiftWrap(
            giftWrap(pm(FavoriteControlMessage.encode(false, contact.npub), "real-unfavorite"), "real-unfavorite", contact, recipient),
            "",
            recipient
        )
        settle()

        assertFalse(favorites.getFavoriteStatus(noiseKey)!!.theyFavoritedUs)
    }

    // ---- S6: invalid events cannot poison deduplication ----

    @Test
    fun `corrupted gift wrap with the genuine id does not suppress the genuine event`() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val state = ChatState(scope).apply { setNickname("recipient") }
        val handler = handler(state = state)
        val genuine = giftWrap(pm("hello", "genuine-message"), "genuine", sender, recipient)
        val corrupted = genuine.copy(content = genuine.content.reversed())

        handler.onGiftWrap(corrupted, "", recipient)
        handler.onGiftWrap(genuine, "", recipient)

        waitUntil { state.getPrivateChatsValue().values.flatten().any { it.id == "genuine-message" } }
    }

    @Test
    fun `relay ingress drops forged events before deduplication and keeps the genuine one`() {
        val manager = NostrRelayManager.shared
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val author = NostrIdentity.generate()
        val genuine = NostrEvent(
            pubkey = author.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = "ingress-${UUID.randomUUID()}"
        ).sign(author.privateKeyHex)
        val forged = genuine.copy(content = "tampered")
        val subscriptionId = "ingress-test-${UUID.randomUUID()}"
        manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = subscriptionId,
            handler = { delivered.add(it.content) }
        )
        try {
            manager.handleRelayFrameForTest(frame(subscriptionId, forged), "wss://relay.example")
            manager.handleRelayFrameForTest(frame(subscriptionId, genuine), "wss://relay.example")
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                delivered.isNotEmpty()
            }
            assertEquals(listOf(genuine.content), delivered.toList())
        } finally {
            manager.unsubscribe(subscriptionId)
        }
    }

    // ---- Receipt binding ----

    @Test
    fun `delivery receipt from a different sender does not change status`() {
        val counterpart = NostrIdentity.generate()
        val stranger = NostrIdentity.generate()
        val me = NostrIdentity.generate()
        val conversation = "nostr_${counterpart.publicKeyHex.take(16)}"
        storeOutgoing(conversation, "outgoing-1")
        val applied = Collections.synchronizedList(mutableListOf<Pair<String, DeliveryStatus>>())
        val handler = handler(onStatus = { id, status -> applied.add(id to status) })

        handler.onGiftWrap(giftWrap(ack(NoisePayloadType.DELIVERED, "outgoing-1"), "stranger-ack", stranger, me), "", me)
        settle()
        assertEquals(emptyList<Pair<String, DeliveryStatus>>(), applied.toList())

        handler.onGiftWrap(giftWrap(ack(NoisePayloadType.DELIVERED, "outgoing-1"), "real-ack", counterpart, me), "", me)
        waitUntil { applied.isNotEmpty() }
        assertEquals("outgoing-1", applied.single().first)
        assertTrue(applied.single().second is DeliveryStatus.Delivered)
    }

    @Test
    fun `receipt for a message authored by the counterpart is ignored`() {
        val counterpart = NostrIdentity.generate()
        val me = NostrIdentity.generate()
        val conversation = "nostr_${counterpart.publicKeyHex.take(16)}"
        repository.upsertMessage(
            conversationID = conversation,
            aliases = setOf(conversation),
            displayName = "counterpart",
            message = BitchatMessage(
                id = "incoming-1", sender = "counterpart", content = "hi", timestamp = Date(1_000L),
                isPrivate = true, senderPeerID = conversation
            ),
            isRead = true
        )
        val applied = Collections.synchronizedList(mutableListOf<String>())
        handler(onStatus = { id, _ -> applied.add(id) })
            .onGiftWrap(giftWrap(ack(NoisePayloadType.READ_RECEIPT, "incoming-1"), "self-receipt", counterpart, me), "", me)
        settle()
        assertEquals(emptyList<String>(), applied.toList())
    }

    @Test
    fun `mesh receipt binding applies only to the counterpart conversation`() {
        storeOutgoing("peer-alice", "mesh-outgoing")
        AppStateStore.updatePrivateMessageStatusFromCounterpart(
            "mesh-outgoing", setOf("peer-mallory"), DeliveryStatus.Delivered("peer-mallory", Date())
        )
        Thread.sleep(300)
        assertFalse(runBlocking { repository.loadDeliveryStatusForTest("mesh-outgoing") } is DeliveryStatus.Delivered)

        AppStateStore.updatePrivateMessageStatusFromCounterpart(
            "mesh-outgoing", setOf("peer-alice"), DeliveryStatus.Delivered("peer-alice", Date())
        )
        waitUntil { runBlocking { repository.loadDeliveryStatusForTest("mesh-outgoing") } is DeliveryStatus.Delivered }
    }

    @Test
    fun `receipt binding fails closed for unknown messages`() {
        assertFalse(runBlocking { AppStateStore.isOutgoingMessageInConversation("missing", setOf("peer-alice")) })
    }

    // ---- helpers ----

    private fun handler(
        state: ChatState = ChatState(scope).apply { setNickname("recipient") },
        onStatus: (String, DeliveryStatus) -> Unit = { _, _ -> }
    ): NostrDirectMessageHandler {
        val application = RuntimeEnvironment.getApplication()
        val dataManager = DataManager(application)
        val seenStore = mock<SeenMessageStore>()
        whenever(seenStore.hasDelivered(any())).thenReturn(true)
        whenever(seenStore.hasBeenReadLocally(any())).thenReturn(false)
        return NostrDirectMessageHandler(
            application = application,
            state = state,
            privateChatManager = PrivateChatManager(
                state = state,
                messageManager = MessageManager(state),
                dataManager = dataManager,
                noiseSessionDelegate = mock<NoiseSessionDelegate>()
            ),
            updateDeliveryStatus = onStatus,
            scope = scope,
            repo = GeohashRepository(application, state, dataManager, scope),
            dataManager = dataManager,
            seenStoreProvider = { seenStore }
        )
    }

    private fun storeOutgoing(conversation: String, id: String) {
        repository.upsertMessage(
            conversationID = conversation,
            aliases = setOf(conversation),
            displayName = "peer",
            message = BitchatMessage(
                id = id, sender = "me", content = "outgoing", timestamp = Date(1_000L), isPrivate = true,
                senderPeerID = "0102030405060708", deliveryStatus = DeliveryStatus.Sending
            ),
            isRead = true
        )
        runBlocking { repository.awaitPendingWrites() }
    }

    private fun pm(content: String, id: String) =
        requireNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient(content = content, messageID = id))

    private fun ack(type: NoisePayloadType, id: String) =
        requireNotNull(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, id))

    private fun giftWrap(content: String, tag: String, sender: NostrIdentity, recipient: NostrIdentity): NostrEvent {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val rumorBase = NostrEvent(
            pubkey = sender.publicKeyHex, createdAt = now - 30, kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipient.publicKeyHex)), content = content
        )
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())
        val seal = NostrEvent(
            pubkey = sender.publicKeyHex, createdAt = now - 10, kind = NostrKind.SEAL, tags = emptyList(),
            content = NostrCrypto.encryptNIP44(gson.toJson(rumor), recipient.publicKeyHex, sender.privateKeyHex)
        ).sign(sender.privateKeyHex)
        val (wrapPrivate, wrapPublic) = NostrCrypto.generateKeyPair()
        return NostrEvent(
            pubkey = wrapPublic, createdAt = now - 5, kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipient.publicKeyHex), listOf("t", tag)),
            content = NostrCrypto.encryptNIP44(gson.toJson(seal), recipient.publicKeyHex, wrapPrivate)
        ).sign(wrapPrivate)
    }

    private fun frame(subscriptionId: String, event: NostrEvent): String = JsonArray().apply {
        add(JsonPrimitive("EVENT"))
        add(JsonPrimitive(subscriptionId))
        add(gson.toJsonTree(event))
    }.toString()

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun settle() {
        runBlocking { delay(200); repository.awaitPendingWrites() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        runBlocking {
            withTimeout(5_000) {
                while (!condition()) delay(20)
            }
        }
    }
}
