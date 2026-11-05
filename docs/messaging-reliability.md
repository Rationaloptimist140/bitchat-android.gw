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

Remaining limitation: Tor streams are **not** isolated. The bundled native SOCKS bridge
(`tools/arti-build/src/lib.rs`) always negotiates "no authentication" and calls
`TorClient::connect` with default stream preferences, so DM relays, location relays and HTTP
requests can share one circuit and one exit. An exit (or someone watching it) can see that one Tor
user is connected to both the DM relays and a set of location relays at the same time. Relay
operators still see separate connections. Isolation needs a native change (see "Tor stream
isolation" below); it cannot be done from Kotlin alone.

### Tor stream isolation (design, not implemented)

1. **Native bridge** (`tools/arti-build/src/lib.rs`): accept SOCKS5 username/password (method
   0x02) when offered. Map each username to an `IsolationToken` (bounded map) and connect with
   `client.connect_with_prefs(addr, StreamPrefs::new().set_isolation(token))`. Stop logging
   destination hosts ("CONNECT to …", "established to …", "closed for …").
2. **Kotlin**: give the private-messaging and location relay managers separate OkHttp clients,
   with SOCKS credentials per purpose (`gw-dm-<epoch>`, `gw-location-<epoch>`), rotated on Tor
   restart. Java's SOCKS client offers username/password automatically and takes credentials from
   `java.net.Authenticator`, keyed by the client's dispatcher thread.
3. Rebuild `libarti_android.so` for all four ABIs with `tools/arti-build/build-arti.sh` (Rust and
   the NDK are needed). Then test on a device: bootstrap, reconnect, Tor off/on, and confirm with
   Arti debug logs that DM and location streams use different circuits.

Not done in this pass: the native libraries can't be rebuilt or device-tested here, and committing
unbuilt Rust would leave the source out of step with the shipped binaries. Interim mitigation:
destination hosts are removed from the Tor log line shown in the About screen
(`ArtiTorManager.redactLogLine`). The native bridge still writes them to logcat.

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
- **Tombstone retention.** Deleted and retention-pruned messages leave a tombstone that blocks
  re-insertion from any transport. A legitimate copy can arrive again until the sender's outbox
  gives up (7 days after sending) and its last gift wrap ages out of the accepted window (7 days
  23 hours): about 15 days. Tombstones younger than 30 days are therefore never pruned by count.
  Older tombstones are trimmed to 20 000 (oldest first), and a hard ceiling of 50 000 rows
  (about 4 MB) applies only under abuse, such as a flood that forces constant pruning. Previously
  tombstones were capped at 20 000 by count alone, and pruning creates tombstones too. A busy
  device could therefore drop a recent deletion within days and let a retry or relay replay bring
  the message back. No schema change was needed.
- Panic wipe: `clearAll()` destroys the storage key and then explicitly deletes every outbox table
  (it does not rely on cascades). The scheduler then finds no work.
- Messages left at Sending by an older version are adopted on first start if they are less than 7
  days old, otherwise marked Failed (they can be retried).

### Migration

Version 4 → 5 only creates tables and indexes. `SQLiteOpenHelper` runs it in a transaction: if it
fails, the database stays at version 4 with all data, and the database is never deleted or
recreated. Downgrading to an older app version after the migration is not supported (older builds
cannot open a version 5 database).

## Receipts and reconnection

- **Durable receipts.** Delivery and read receipts for account-identity DMs are outbox envelopes,
  retried until a relay accepts them. `SeenMessageStore`'s "delivered" now means the receipt is
  durably queued. Geohash-identity receipts keep the direct path.
- **Re-acknowledging duplicates.** When a private message arrives that is already stored (from any
  transport) or was deleted locally, it is not stored again. A receipt is re-queued at most every
  10 minutes, so a sender whose earlier receipt was lost stops retrying. Mesh receivers already
  acknowledged duplicates.
- **Keep-alive.** Relay WebSockets ping every 30 s, so half-open sockets (network switch, NAT
  timeout) fail and reconnect instead of silently swallowing writes.
- **Network monitor.** When a validated internet connection becomes available, disconnected relays
  reconnect immediately (backoff reset) and the outbox is woken (`NetworkMonitor`).
- **After a reboot or force-stop**, the outbox resumes when the message router is created: when the
  app is opened, or when someone replies from a notification. The boot-started mesh service alone
  does not start it, so queued messages wait until the app is opened.

## History synchronisation

- **Window.** The live account-DM subscription keeps its 48 h window. On start, and when internet
  returns (debounced to once per 5 minutes), a paginated backfill also fetches from the last
  completed sync minus 23 h, capped at 7 days. The 23 h covers the 22 h NIP-17 timestamp backdating
  plus a margin.
- **Pagination.** Relays cap results per query (the live filter uses `limit = 100`, and receipts
  count towards it). The backfill queries each DM relay in pages of 100, walking backwards with
  `until` until a relay returns a partial page. If a full page shares one second, the next page steps
  past it so paging always advances. At most 50 pages are fetched per relay per backfill.
- **Sync position.** `nostr_sync_state` records, per relay, the time a backfill started, and only
  after that relay's whole window was fetched. Positions never move backwards.
- **Age limit.** Gift wraps older than 7 days + 23 h are dropped (previously 48 h 15 min).
  Replays inside the window are harmless: text is deduplicated by message id, deletions by
  tombstones (kept for at least 30 days below the hard ceiling, see "Tombstone retention"), and files by a deterministic id.
- **File messages.** A file received over Nostr gets a stable id (sender key + SHA-256 of the
  payload), so relay replays, retries and backfill do not create duplicate file messages. Known or
  deleted files are skipped before anything is written to disk.

## Geohash-identity DMs (weaker guarantees, by design)

DMs in location channels use a per-geohash identity and the location relays. They keep their
upstream delivery model, and none of the private-messaging guarantees above apply to them:

- They are sent once, directly, on the location relays. They have **no durable outbox**: no retry
  after an app restart, no expiry and no Retry sending. They show Sent when a location relay
  accepts them, and Delivered/Read only if the recipient sends a receipt.
- Their receipts are sent once, without retry. A lost receipt leaves the sender at Sent.
- They are kept out of the outbox deliberately. Its routes (mesh, account-identity Nostr) would
  link the geohash persona to the account. `PrivateChatManager` creates no envelope for geohash
  aliases, and the scheduler refuses to route them.
- Before this fix, geohash DMs were given an outbox envelope that could never be routed. They then
  showed Failed after 7 days without a receipt and, in one edge case, could have been routed with
  the account identity.

The private-messaging guarantees are unaffected: account DMs and mesh messages never use the
geohash path.

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
