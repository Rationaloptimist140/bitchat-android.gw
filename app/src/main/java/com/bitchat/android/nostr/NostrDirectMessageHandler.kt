package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.PrivateMessageOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val updateDeliveryStatus: (String, DeliveryStatus) -> Unit,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val seenStoreProvider: () -> SeenMessageStore = {
        SeenMessageStore.getInstance(application)
    },
    /** Confirms a receipt targets an outgoing message in the sender's conversation. */
    private val receiptBinding: suspend (messageID: String, counterpartIds: Set<String>) -> Boolean =
        AppStateStore::isOutgoingMessageInConversation
) {
    companion object {
        private const val TAG = "NostrDirectMessageHandler"

        /** Stable message id for a Nostr file transfer: sender key + payload digest. */
        internal fun fileMessageId(senderPubkey: String, payload: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            val seed = "nostr-file:".toByteArray(Charsets.UTF_8) + senderPubkey.lowercase().toByteArray(Charsets.UTF_8) + digest
            return java.util.UUID.nameUUIDFromBytes(seed).toString().uppercase()
        }
        private const val RECEIPT_EXPIRY_MS = 48L * 60 * 60 * 1000
        private const val REACK_MIN_INTERVAL_MS = 10L * 60 * 1000
    }

    private val seenStore by lazy(seenStoreProvider)

    // Gift-wrap deduplication. Only events that decrypted and authenticated successfully are
    // remembered, so a forged or corrupted copy that arrives first cannot suppress the genuine
    // event. Access is serialized by the processor's single-threaded scope.
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun alreadyProcessed(id: String): Boolean = seen.contains(id)

    private fun markProcessed(id: String) {
        if (!seen.add(id)) return
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
    }

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch {
            try {
                if (alreadyProcessed(giftWrap.id)) return@launch

                // Aligned with the history backfill window. Replays inside it are harmless: text is
                // de-duplicated by message id, deletions by tombstones, files by a content id.
                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > HistorySyncPolicy.MAX_GIFT_WRAP_AGE_SECONDS) return@launch

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }
                // The seal signature and seal/rumor pubkey binding are verified by
                // decryptPrivateMessage; only now is the event id trustworthy enough to remember.
                markProcessed(giftWrap.id)

                val (content, rawSenderPubkey, rumorTimestamp) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                if (!content.startsWith("bitchat1:")) return@launch

                val base64Content = content.removePrefix("bitchat1:")
                val packetData = base64URLDecode(base64Content) ?: return@launch
                val packet = BitchatPacket.fromBinaryData(packetData) ?: return@launch

                if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch

                val noisePayload = NoisePayload.decode(packet.payload) ?: return@launch
                val messageTimestamp = Date(rumorTimestamp * 1000L)
                val convKey = "nostr_${senderPubkey.take(16)}"
                repo.putNostrKeyMapping(convKey, senderPubkey)
                com.bitchat.android.nostr.GeohashAliasRegistry.put(convKey, senderPubkey)
                if (geohash.isNotEmpty()) {
                    // Remember which geohash this conversation belongs to so we can subscribe on-demand
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                }

                // Ensure sender appears in geohash people list even if they haven't posted publicly yet
                if (geohash.isNotEmpty()) {
                    // Cache a best-effort nickname and mark as participant
                    val cached = repo.getCachedNickname(senderPubkey)
                    if (cached == null) {
                        val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, messageTimestamp)
                }

                val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)
                val conversationID = ContactDirectory.canonicalConversationId(convKey)

                processNoisePayload(noisePayload, conversationID, senderNickname, messageTimestamp, senderPubkey, identity, geohash.isEmpty())

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity,
        isAccountDm: Boolean = false
    ) {
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return
                val existingMessages = state.getPrivateChatsValue()[conversationID] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) {
                    // Already have it: the sender retried, so our earlier receipt was probably
                    // lost. Re-acknowledge (rate-limited) instead of storing a second copy.
                    if (isAccountDm) sendReceipt(NoisePayloadType.DELIVERED, pm.messageID, senderPubkey, conversationID, recipientIdentity, isAccountDm)
                    return
                }

                val favoriteControl = FavoriteControlMessage.parse(pm.content)
                if (favoriteControl != null) {
                    val admitted = handleFavoriteControl(
                        favoriteControl,
                        conversationID,
                        senderNickname,
                        timestamp,
                        senderPubkey
                    )
                    if (!admitted) return
                    if (!seenStore.hasDelivered(pm.messageID) &&
                        sendReceipt(NoisePayloadType.DELIVERED, pm.messageID, senderPubkey, conversationID, recipientIdentity, isAccountDm)
                    ) {
                        seenStore.markDelivered(pm.messageID)
                    }
                    return
                }

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = conversationID,
                    senderNostrPubkey = senderPubkey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date())
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == conversationID
                val suppressUnread = seenStore.hasBeenReadLocally(pm.messageID)

                val admitted = withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessageDurably(
                        message = message,
                        suppressUnread = suppressUnread,
                        origin = PrivateMessageOrigin.NOSTR
                    )
                }
                if (!admitted) {
                    // Rejected as a duplicate of a stored or deleted message: re-acknowledge so a
                    // retrying sender converges. Blocked senders are unknown here and get nothing.
                    if (isAccountDm && isKnownOrDeleted(pm.messageID)) {
                        sendReceipt(NoisePayloadType.DELIVERED, pm.messageID, senderPubkey, conversationID, recipientIdentity, isAccountDm)
                    }
                    return
                }

                // seenStore "delivered" means the receipt is durably queued (account DMs) or sent.
                if (!seenStore.hasDelivered(pm.messageID) &&
                    sendReceipt(NoisePayloadType.DELIVERED, pm.messageID, senderPubkey, conversationID, recipientIdentity, isAccountDm)
                ) {
                    seenStore.markDelivered(pm.messageID)
                }

                if (isViewing && !suppressUnread &&
                    sendReceipt(NoisePayloadType.READ_RECEIPT, pm.messageID, senderPubkey, conversationID, recipientIdentity, isAccountDm)
                ) {
                    seenStore.markReadLocally(pm.messageID)
                    seenStore.markReadReceiptSent(pm.messageID)
                }
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                if (!receiptBinding(messageId, receiptCounterpartIds(conversationID, senderPubkey))) {
                    Log.w(TAG, "Ignoring delivery receipt not bound to its conversation")
                    return
                }
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Delivered(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                if (!receiptBinding(messageId, receiptCounterpartIds(conversationID, senderPubkey))) {
                    Log.w(TAG, "Ignoring read receipt not bound to its conversation")
                    return
                }
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Read(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.FILE_TRANSFER -> {
                // Properly handle encrypted file transfer
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    // Deterministic id: the same file from the same sender (relay replay, retry or
                    // backfill) is recognised instead of appearing again with a random id.
                    val uniqueMsgId = fileMessageId(senderPubkey, payload.data)
                    if (isKnownOrDeleted(uniqueMsgId)) return
                    val savedPath = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = BitchatMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = conversationID,
                        senderNostrPubkey = senderPubkey
                    )
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    val admitted = withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessageDurably(
                            message = message,
                            suppressUnread = false,
                            origin = PrivateMessageOrigin.NOSTR
                        )
                    }
                    if (!admitted) {
                        com.bitchat.android.features.file.FileUtils.deleteStoredMediaPaths(
                            application,
                            listOf(savedPath)
                        )
                    }
                } else {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $conversationID")
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.VOICE_FRAME,
            NoisePayloadType.PEER_STATE -> Unit // Peer state is bound to a live mesh Noise generation.
        }
    }

    private suspend fun handleFavoriteControl(
        control: FavoriteControlMessage,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String
    ): Boolean {
        return try {
            // Identity comes only from the authenticated seal key. A claimed npub inside the
            // message must match it; otherwise the message could change another contact's state.
            val claimedHex = control.npub?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
            if (control.npub != null && claimedHex?.lowercase() != senderPubkey.lowercase()) {
                Log.w(TAG, "Rejecting favorite notification whose claimed key differs from its sender")
                return false
            }
            val noiseKey = FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)

            if (noiseKey == null) {
                Log.w(TAG, "Favorite notification from Nostr sender without known Noise key")
                return false
            }

            // The Nostr key binding itself only changes over an authenticated mesh session.
            FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, control.isFavorite)
            val targetConversationID = ContactDirectory.canonicalConversationId(conversationID)

            val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
            val displayName = relationship
                ?.peerNickname
                ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                ?: senderNickname
            val guidance = if (control.isFavorite) {
                if (relationship?.isFavorite == true) {
                    " - mutual! You can continue DMs via Nostr when out of mesh."
                } else {
                    " - favorite back to continue DMs later."
                }
            } else {
                ". DMs over Nostr will pause unless you both favorite again."
            }
            val action = if (control.isFavorite) "favorited" else "unfavorited"
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$displayName $action you$guidance",
                timestamp = timestamp,
                isRelay = false,
                isPrivate = true,
                senderPeerID = targetConversationID
            )

            withContext(Dispatchers.Main) {
                privateChatManager.handleIncomingPrivateMessageDurably(
                    message = systemMessage,
                    suppressUnread = true,
                    origin = PrivateMessageOrigin.NOSTR
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Nostr favorite notification: ${e.message}")
            false
        }
    }

    /**
     * Sends a delivery/read receipt. Account-identity receipts go through the durable outbox
     * (retried until a relay accepts them, re-queued for duplicates at most every 10 minutes);
     * geohash-identity receipts keep the direct path. Returns true once queued or sent.
     */
    private suspend fun sendReceipt(
        type: NoisePayloadType,
        messageId: String,
        senderPubkey: String,
        conversationID: String,
        recipientIdentity: NostrIdentity,
        isAccountDm: Boolean
    ): Boolean {
        val repository = if (isAccountDm) AppStateStore.conversationRepositoryOrNull() else null
        if (repository != null) {
            val now = System.currentTimeMillis()
            val kind = if (type == NoisePayloadType.READ_RECEIPT) {
                com.bitchat.android.services.OutboxStore.Kind.READ_RECEIPT
            } else {
                com.bitchat.android.services.OutboxStore.Kind.DELIVERY_ACK
            }
            val queued = runCatching {
                repository.withOutbox {
                    it.enqueueReceipt(kind, messageId, conversationID, senderPubkey, true, now, now + RECEIPT_EXPIRY_MS, REACK_MIN_INTERVAL_MS)
                    true
                }
            }.getOrDefault(false)
            if (queued) com.bitchat.android.services.MessageRouter.tryGetInstance()?.kickOutbox()
            return queued
        }
        val nostrTransport = NostrTransport.getInstance(application)
        if (type == NoisePayloadType.READ_RECEIPT) {
            nostrTransport.sendReadReceiptGeohash(messageId, senderPubkey, recipientIdentity, privateChannel = isAccountDm)
        } else {
            nostrTransport.sendDeliveryAckGeohash(messageId, senderPubkey, recipientIdentity, privateChannel = isAccountDm)
        }
        return true
    }

    private suspend fun isKnownOrDeleted(messageId: String): Boolean =
        AppStateStore.conversationRepositoryOrNull()?.isKnownOrDeletedMessage(messageId) ?: false

    /** Conversation ids for a receipt sender, derived only from its authenticated key. */
    private fun receiptCounterpartIds(conversationID: String, senderPubkey: String): Set<String> {
        val noiseKey = try {
            FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)
        } catch (_: Exception) {
            null
        }
        return ContactDirectory.receiptCounterpartIds("nostr_${senderPubkey.take(16)}", noiseKey) +
            ContactDirectory.receiptCounterpartIds(conversationID)
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
