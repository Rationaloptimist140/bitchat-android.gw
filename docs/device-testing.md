# Two-phone device testing (Windows)

This tests the `ghostwire/dev` messaging work on two real Android phones. It does not touch
`main`, sign anything, or publish anything.

- **Phones:** A (sender) and B (receiver), both Android 8.0 or newer. Android 11+ makes the
  optional adb network commands work.
- **PC:** Windows with a JDK 21, the Android SDK (platform 37 and platform-tools) and Git.
- **Package:** `com.bitchat.droid`. Debug builds use the same ID as store builds of bitchat, but
  a different signing key. Uninstall any store copy first; this deletes its data.

Every check has an **Expect** line. If a phone does something else, note the step number, the
time and both phones' logs (see "Collecting logs"), and move on.

---

## 1. One-time setup (PowerShell)

```powershell
# Adjust paths to your machine
$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path         = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

java -version          # must say 21
adb version

cd C:\src\bitchat-android.gw          # your clone of the fork
git fetch origin
git switch ghostwire/dev              # first time: git switch -c ghostwire/dev origin/ghostwire/dev
git pull --ff-only
git log --oneline -1                  # note the commit you are testing
```

On both phones, enable Developer options and then **USB debugging**. Plug both in and accept the
"Allow USB debugging" prompt on each.

```powershell
adb devices -l                        # both phones must show "device", not "unauthorized"
$A = "PASTE_SERIAL_OF_PHONE_A"
$B = "PASTE_SERIAL_OF_PHONE_B"
adb -s $A shell getprop ro.product.cpu.abi    # usually arm64-v8a
adb -s $B shell getprop ro.product.cpu.abi
```

## 2. Build and install the debug APK

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
Get-ChildItem app\build\outputs\apk\debug\*.apk

# Pick the APK matching each phone's ABI (arm64-v8a for almost all modern phones).
# The universal APK works everywhere but is larger.
$apk = "app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"

# Only if a store/F-Droid copy is installed (this DELETES its data):
# adb -s $A uninstall com.bitchat.droid
# adb -s $B uninstall com.bitchat.droid

adb -s $A install -r $apk
adb -s $B install -r $apk
adb -s $A shell am start -n com.bitchat.droid/com.bitchat.android.MainActivity
adb -s $B shell am start -n com.bitchat.droid/com.bitchat.android.MainActivity
```

If `install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a copy signed with another key is
installed. Uninstall it as shown above. If `am start` reports the activity doesn't exist, open the
app from the launcher instead.

Run the unit tests from the same shell (optional):

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
start app\build\reports\tests\testDebugUnitTest\index.html
```

## 3. Helper commands used below

Each line is an alternative to doing the same thing by hand on the phone.

```powershell
# Force-stop the app
adb -s $A shell am force-stop com.bitchat.droid
# Reboot a phone
adb -s $A reboot
# Airplane mode (Android 11+). If unsupported, use Quick Settings.
adb -s $A shell cmd connectivity airplane-mode enable
adb -s $A shell cmd connectivity airplane-mode disable
# Wi-Fi and mobile data
adb -s $A shell svc wifi disable;  adb -s $A shell svc wifi enable
adb -s $A shell svc data disable;  adb -s $A shell svc data enable
```

Turn Bluetooth on and off in Quick Settings: adb support for it varies by phone.

### Collecting logs

Start a log capture per phone before a test, and stop it with Ctrl+C afterwards.

```powershell
adb -s $A logcat -c
adb -s $A logcat -v time OutboxScheduler:V MessageRouter:V NostrRelayManager:V NostrTransport:V NetworkMonitor:V NostrBackground:V NostrDirectMessageHandler:V ConversationDatabase:V ArtiTorManager:V "*:S" | Tee-Object -FilePath A-step.log
```

Run the same in a second PowerShell window with `$B` and `B-step.log`. Logs contain no message
text or keys, but they do include relay URLs and timing. Review them before sharing.

## 4. Status meanings

| Status | Meaning |
|---|---|
| Sending | Queued, or written to the mesh but not yet confirmed |
| Sent | Only over the internet: at least one Nostr relay accepted the message |
| Delivered | B's app received it and sent an authenticated receipt |
| Read | B opened the conversation |
| Failed | No route for 7 days, or every relay rejected it. **Retry sending** is in the message's sheet |

Over Bluetooth the status goes straight from Sending to Delivered. It never shows Sent, because a
nearby phone isn't a custodian.

## 5. Test procedure

Do the tests in this order. Before starting, set a nickname on each phone. Leave both apps open
in the foreground unless a step says otherwise.

### T1 Database v4→v5 migration (do this first, on phone A)

This checks that upgrading keeps existing chats. It needs a build of the pre-change baseline,
which is built in a separate folder so `main` isn't touched.

```powershell
git worktree add ..\gw-baseline 998f168      # detached checkout of the v4 baseline
cd ..\gw-baseline
.\gradlew.bat :app:assembleDebug --console=plain
adb -s $A install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s $B install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
cd ..\bitchat-android.gw
```

1. With both phones on the baseline build and Bluetooth on, exchange 5 private messages each way.
   Leave the last message from B unread on A.
2. Install this build over the baseline without uninstalling: `adb -s $A install -r $apk`, then
   the same for `$B`.
3. Open the app on A.
   **Expect:** no crash or error banner. All 10 messages are present, in order, and the unread
   count is unchanged. Sending a new message works.
4. Optional database check: in Android Studio, open **App Inspection → Database Inspector**, pick
   `com.bitchat.droid` → `private_conversations.db`.
   **Expect:** the tables `outbound_envelopes`, `outbound_nostr_events` and `nostr_sync_state`
   exist.
5. Afterwards, remove the baseline folder: `git worktree remove ..\gw-baseline`.

### T2 Bluetooth-only messaging

Setup: both phones in airplane mode with Bluetooth turned back on, about 1 m apart.

1. Wait until each phone shows the other as a nearby peer. Open a private chat from A to B.
2. A sends "t2-1".
   **Expect:** A shows Sending, then Delivered within about 5 s, never Sent. B shows "t2-1" once.
3. B opens the chat.
   **Expect:** A changes to Read within about 5 s.
4. Walk B out of range (or turn B's Bluetooth off). A sends "t2-2".
   **Expect:** A stays at Sending.
5. Bring B back in range.
   **Expect:** "t2-2" arrives on B once, within a few seconds of B reappearing and its session
   being re-established, however long B was away. A changes to Delivered. No retry tap is needed.

### T3 Nostr (internet) messaging

Setup: favourite each other over Bluetooth first. In the chat, open each user's sheet and tap the
star on both phones. Wait until each side shows "Favorited you". Then turn Bluetooth **off** on
both phones and turn the internet on (Wi-Fi or mobile data).

1. A sends "t3-1".
   **Expect:** A shows Sending, then Sent (relay accepted) within about 10 s, then Delivered once B
   receives it. B shows "t3-1" once.
2. B opens the chat.
   **Expect:** A changes to Read.
3. Put B in airplane mode. A sends "t3-2".
   **Expect:** A shows Sent but not Delivered.
4. After 2 minutes, take B out of airplane mode and open the app.
   **Expect:** "t3-2" arrives once, and A changes to Delivered.

### T4 Offline outbox recovery

Setup: as in T3 (mutual favourites, internet, Bluetooth off).

1. Put A in airplane mode. A sends "t4-1", "t4-2" and "t4-3".
   **Expect:** all three stay at Sending. None shows Sent or Failed.
2. Take A out of airplane mode.
   **Expect:** within about 30 s all three show Sent, then Delivered. B shows each exactly once,
   in order.

### T5 Force-stop and reboot

1. Put A in airplane mode. A sends "t5-1". Then:
   `adb -s $A shell am force-stop com.bitchat.droid`
2. Take A out of airplane mode and open the app.
   **Expect:** "t5-1" is still shown and is delivered without a retry tap.
3. Put A in airplane mode. A sends "t5-2". Then run `adb -s $A reboot`.
4. After the reboot, unlock A, turn airplane mode off and open the app.
   **Expect:** "t5-2" is delivered without a retry tap.
   Known limitation: after a reboot, queued messages resume once the app has been opened, even if
   background auto-start is on.
5. Put B in airplane mode, A sends "t5-3", then force-stop B's app. Take B out of airplane mode
   and open the app.
   **Expect:** "t5-3" arrives once. A reaches Delivered.

### T6 Wi-Fi ↔ mobile data switching

Setup: as in T3. A on Wi-Fi with mobile data also on.

1. Start the log capture on A. A sends "t6-1".
   **Expect:** Delivered.
2. `adb -s $A shell svc wifi disable`, then wait 5 s. A sends "t6-2".
   **Expect:** within about 15 s the A log shows NetworkMonitor/NostrRelayManager reconnecting.
   "t6-2" reaches Sent, then Delivered. No message stays stuck at Sending.
3. `adb -s $A shell svc wifi enable`, then wait 10 s, and send "t6-3".
   **Expect:** Delivered.
4. Repeat steps 2 and 3 on B (the receiver).
   **Expect:** B receives every message once.

### T7 Duplicate prevention

1. Mutual favourites, with Bluetooth **and** internet on for both phones, close together. A sends
   "t7-1".
   **Expect:** B shows "t7-1" exactly once, even though both transports may carry it.
2. Put B in airplane mode with Bluetooth off. A sends "t7-2" and waits until it shows Sent. Then
   bring B fully online with Bluetooth and internet on.
   **Expect:** "t7-2" shows once.
3. On B, open the Conversations list, swipe the conversation with A and confirm deletion. (The app
   has no single-message delete.) Force-stop B, reopen it and wait 2 minutes with both transports
   on.
   **Expect:** none of the deleted messages come back, including from A's retries or relay
   replays. Messages on A stay at Delivered or Read. A new message from A then starts a fresh
   conversation containing only that message.
4. Send a small image from A over the internet only (Bluetooth off). Force-stop B and reopen it.
   **Expect:** one copy of the image.

### T8 Delivery and read receipts

1. Over Bluetooth only (as in T2) and over the internet only (as in T3), check:
   - Delivered appears only after B actually has the message, never just because A sent it.
   - Read appears only after B opens the conversation.
   - If B is viewing a different chat, A stays at Delivered.
2. Lost receipt: put A in airplane mode **right after** sending "t8-1" over the internet, while
   it is still at Sent. Wait 1 minute for B to receive it, then take A out of airplane mode.
   **Expect:** A reaches Delivered within a few minutes. B's receipt waits on the relays (B
   retries it until a relay accepts it), and A fetches it on reconnect.

### T9 Panic wipe

1. Put A in airplane mode. A sends "t9-1", leaving it queued.
2. On A, triple-tap the app logo in the header, then confirm.
   **Expect:** all chats are gone and the app is reset.
3. Take A out of airplane mode and wait 2 minutes.
   **Expect:** B never receives "t9-1".
4. Force-stop A and reopen it.
   **Expect:** still no old chats.

### T10 Tor recovery

1. On A, tap the logo to open About, then turn **Tor On**. Wait for "Connected (100%)".
   **Expect:** the Tor log line in About shows "[destination]" instead of relay hostnames.
2. Repeat T3 step 1 and T4 over Tor.
   **Expect:** the same results; first delivery may take up to about 1 minute.
3. With Tor on, toggle airplane mode on for 30 s, then off. Send "t10-1".
   **Expect:** Tor reconnects (About shows 100% again) and the message is delivered. No direct
   (non-Tor) connection is made while Tor is on.
4. Turn Tor Off and send "t10-2".
   **Expect:** delivered over the direct connection.
5. Turn Tor On again and send "t10-3".
   **Expect:** delivered after bootstrap.

Known limitation: over Tor, the DM relays and location relays can share a circuit. See
`docs/messaging-reliability.md` → "Tor stream isolation".

### T11 Geohash DMs (check they still work, nothing more)

1. Both phones join the same location channel. A opens a DM with B from the channel's people list
   and sends "t11-1".
   **Expect:** B receives it. A shows Sent when a location relay accepts it, and Delivered if B's
   receipt arrives.
2. Force-stop A while a geohash DM is still at Sending.
   **Expect:** it is **not** resent automatically after restart. This is by design: geohash DMs
   have no outbox.

## 6. Reporting results

For each test, note pass or fail, the phone models and Android versions, `git log --oneline -1`
and Tor on or off. For failures, include `A-step.log`, `B-step.log` and the time of the failure.
