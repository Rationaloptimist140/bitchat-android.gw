# Messaging reliability and security

This document describes how private messages move between devices, what each delivery status
means, and the evidence required for it. It covers the Bluetooth/Wi-Fi Aware mesh and the Nostr
internet transport.

## Delivery states

| Shown to the user | Stored `delivery_type` | Evidence required |
|---|---|---|
| Waiting | `Sending` (1) | Saved on this device; no carrier has accepted it yet |
| Sent | `Sent` (2) | **Nostr only:** a relay answered `["OK", <event id>, true, …]`, or `false` with a `duplicate:` reason (the relay already stores it) |
| Delivered | `Delivered` (3) | An authenticated `DELIVERED` receipt from the conversation counterpart |
| Read | `Read` (4) | An authenticated `READ_RECEIPT` from the conversation counterpart |
| Didn't send | `Failed` (5) | No route, every relay permanently rejected it, or it expired without any carrier accepting it |

Rules:

- **Choosing a route is not evidence.** Nothing sets Sent because the router picked Nostr.
- **A local socket write is not evidence.** OkHttp's `WebSocket.send()` returning true only means
  the frame was buffered on this device.
- **Relay acceptance is not delivery.** A relay is a store-and-forward custodian, so acceptance
  means the message left this device and is held for the recipient. Only the recipient's receipt
  proves delivery.
- **The mesh never shows Sent.** A Bluetooth neighbour is not a custodian: mesh store-and-forward
  for private messages is unused (`StoreForwardManager.cacheMessage` has no callers), multi-hop
  relays may drop packets, and a BLE write callback only proves a nearby radio received bytes. Mesh
  messages go Waiting → Delivered, exactly as before.
- **Status only moves forward** (`ConversationRepository.updateDeliveryStatus`, `statusPriority`):
  Sending < Sent < Delivered < Read. Failed can never replace Delivered or Read.

## Receive-side security

### Authenticated identity

The sender of a Nostr private message is the rumor's pubkey. It is accepted only after the seal's
Schnorr signature verifies and the seal pubkey equals the rumor pubkey
(`NostrProtocol.decryptPrivateMessage`). Identity fields inside the embedded BitChat packet are not
used.

**Favourite notifications** (`[FAVORITED]:npub…` / `[UNFAVORITED]:npub…`) received over Nostr are
applied only to the contact whose Nostr key is the authenticated sender. If the message claims a
different npub, it is rejected. A contact's Nostr key binding only changes over an authenticated
mesh (Noise) session. Previously a forged message could flip another contact's "favourited us"
flag and silently disable internet messaging to them.

### Verify before deduplicating

`NostrRelayManager` recomputes the event id and verifies the signature (`isValidSignature`)
**before** recording the id in the deduplicator. `NostrDirectMessageHandler` records a gift-wrap id
only after it decrypts and authenticates. A relay that serves a forged or corrupted copy first can
no longer suppress the genuine event delivered by another relay. An event whose subscription filter
cannot be evaluated is dropped (fail closed).

### Receipt binding (mesh and Nostr)

A `DELIVERED` or `READ_RECEIPT` updates a message only when:

1. the message exists in the conversation of the authenticated receipt sender, directly or through
   a recorded alias, and
2. the message was not authored by that sender.

The sender is identified by the mesh peer of the Noise session (and its Noise key), or by the
authenticated Nostr key. The check runs against the database (`ConversationDatabase.isOutgoingMessageInConversation`),
because closed conversations are not kept in memory. Unknown messages fail closed.

### Logging

`LogHygieneTest` fails the build if any `Log` call interpolates key material, seeds, decrypted
payloads or message bodies. New code logs coded reasons and truncated identifiers only. Relay
rejection logs include only the machine-readable NIP-01 prefix, never relay free text.

## Private-message relay isolation

Account private messaging uses a dedicated relay manager, `NostrRelayManager.privateMessaging`,
with its own WebSocket connections to the DM relays (`privateMessagingRelays()`, currently the
four defaults). It carries:

- gift-wrapped DMs, delivery and read receipts, and favourite notifications sent with the account identity;
- the account DM subscription (`#p` = the account pubkey), pinned to the DM relays.

The location manager (`NostrRelayManager.shared`) carries geohash channels, presence, location
notes and geohash-identity DMs. The private manager refuses geohash relays. Previously the
account DM subscription and DMs went to every connected relay, including relays chosen because
they are near the user's geohash, so one connection could link the account npub, and every npub it
messaged, to an approximate location.

Remaining limitation: both managers share the Tor SOCKS proxy without stream isolation, so a Tor
exit may see both connections; relay operators see separate connections.

## Relay acknowledgement

`NostrPendingEventQueue` tracks each (event, relay) delivery:

- **PENDING:** must be written (initially; after a retryable rejection once its delay passes; after
  the socket closed while unacknowledged; or after an acknowledgement timeout).
- **WRITTEN:** on the socket, waiting for `OK`.
- **Removed:** accepted (`OK true` or `duplicate:`) or permanently rejected (`invalid:`, `blocked:`,
  `restricted:`, `pow:`, `auth-required:`).

`rate-limited:`, `error:` and unprefixed rejections are retried with backoff (2 s doubling, at most
60 s). An acknowledgement sweep (every 5 s) returns writes unacknowledged after 20 s (40 s over Tor)
to pending and resends due work on open sockets. Outcomes are published on
`NostrRelayManager.publishOutcomes` (event id, relay, verdict, reason prefix).

## Known pre-existing test issues

- `FileUtilsClearAllMediaTest` "reports failure when a media directory survives" fails only in
  environments whose filesystem does not enforce directory write permissions.
- `CommandProcessorTest`: three tests are `@Ignore`d upstream.
- `GeohashParticipantTrackingTest` times out intermittently (line 76) when the geohash tests run on
  their own. This was reproduced on the unmodified upstream code (2–3 failures in each of three
  isolated runs); it passes in full-suite runs.
- A lock-order deadlock between `GeohashRepository` instances sharing one `GeohashParticipantStore`
  hung a full test run once. It is fixed: presence-refresh scheduling no longer takes the
  repository monitor from inside the store's emission.
