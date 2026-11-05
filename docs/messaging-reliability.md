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

## Durable outbox

Outgoing private messages and Nostr receipts are stored in a durable outbox in the conversation
database (`OutboxStore`, schema version 5). That way they survive app closure, process death and
reboots, and status changes are transactional with the message row.

### Tables

- `outbound_envelopes`: one row per (kind, message). Kinds are MESSAGE, DELIVERY_ACK and
  READ_RECEIPT. MESSAGE rows reference `private_messages(message_id)` with `ON DELETE CASCADE`.
  Receipt rows carry an encrypted payload (recipient key, relay channel) and their conversation id.
- `outbound_nostr_events`: the signed gift wrap(s) of an envelope, encrypted with the conversation
  storage cipher; `ON DELETE CASCADE` from the envelope. Superseded events are kept so late `OK`
  frames still map to their envelope.
- `nostr_sync_state`: per-relay history-sync position (used by history synchronisation).

Message content is never copied into the outbox; it is read from the encrypted message row.

### States

```
compose ─► QUEUED ─claim─► TRANSMITTING ─mesh receipt─────────────► DELIVERED ─read─► READ
             ▲               │  │   └─relay OK─► CARRIER_ACCEPTED ─receipt─┘
             │  no route /   │  │                   │   (republish +1h,+6h,+24h,+72h)
             │  mesh timeout │  │                   └─7 days─► ACCEPTED_EXPIRED (stays Sent)
             └── RETRY_WAIT ◄┘  │
                   └─ 7 days without acceptance ─► FAILED ─(user: Retry sending)─► QUEUED
deleting the message or conversation at any point removes the envelope (cascade)
```

| Envelope state | User sees |
|---|---|
| QUEUED, TRANSMITTING, RETRY_WAIT | Waiting |
| CARRIER_ACCEPTED, ACCEPTED_EXPIRED | Sent |
| DELIVERED / READ | Delivered / Read |
| FAILED | Didn't send |

### Transactions and crash recovery

| Point | Transaction | If the process dies just before commit |
|---|---|---|
| Compose | Message row and MESSAGE envelope (`upsertMessage(enqueueOutbound = true)`) | Neither exists; the UI never showed it |
| Claim | Due, unleased envelope → TRANSMITTING with a 60 s lease | The lease expires and the work is reclaimed (`recoverExpiredLeases`) |
| Event signing | The signed gift wrap is persisted before any socket write (`recordNostrEvent`) | The event never left the device; a fresh one is built |
| Socket write | (none) | The same stored event is republished; a relay that has it answers `duplicate:` (counted as accepted) |
| Relay OK | Envelope → CARRIER_ACCEPTED and message → Sent | The next republish gets `duplicate:` and the same transaction runs |
| Recipient receipt | Envelope → DELIVERED/READ inside the status update | Relays replay the receipt within the history window, and the sender's republishes are re-acknowledged |

Delivery is at least once. A crash between transmitting and recording can send the same message
twice; the receiver deduplicates by message id.

### Idempotency

- One envelope per (kind, message id) (partial unique indexes).
- Every transition is a conditional `UPDATE … WHERE state IN (…)`.
- Message status only moves forward (`statusPriority`). Failed cannot replace Delivered or Read.
- Republishing reuses the stored event id. A fresh event (new id, same message UUID) is created only
  when the stored one is missing or its randomised timestamp is more than 24 h old.

### Routing an attempt (`OutboxScheduler`)

1. Mesh peer connected with a Noise session → send over the mesh with the same message id, then wait
   30 s for the receipt.
2. Otherwise, mutual favourite with a known Nostr key → publish the stored (or new) gift wrap to the
   DM relays.
3. Otherwise wait with backoff (5 s, 30 s, 2 min, 10 min, 1 h, then every 6 h, ±20 % jitter) and
   nudge a Noise handshake if the peer is visible.

Once a relay has accepted a message, it is republished on the schedule above until a receipt
arrives. If the peer becomes reachable over the mesh, the message is also sent there once per retry
epoch. The first authenticated receipt from either transport completes it.

### Expiry, retry, deletion and panic wipe

- Unaccepted messages fail after 7 days. Accepted messages stop republishing after 7 days and stay Sent.
- **Retry sending** (message sheet) is the only backward transition: allowed only from Failed, it
  bumps `retry_epoch`, resets attempts and expiry, and keeps the message id.
- Deleting a message or conversation removes its envelopes and events, and the claim step re-checks
  tombstones. Retention pruning skips messages that still have an active envelope.
- Panic wipe: `clearAll()` destroys the storage key and then explicitly deletes every outbox table
  (it does not rely on cascades). The scheduler then finds no work.
- Messages left at Sending by an older version are adopted on first start if they are less than 7
  days old, otherwise marked Failed (they can be retried).

### Migration

Version 4 → 5 only creates tables and indexes. `SQLiteOpenHelper` runs it in a transaction: if it
fails, the database stays at version 4 with all data, and the database is never deleted or
recreated. Downgrading to an older app version after the migration is not supported (older builds
cannot open a version 5 database).

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
