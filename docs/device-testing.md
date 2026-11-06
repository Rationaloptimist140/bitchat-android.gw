# Two-phone device testing (Windows)

**Commit under test:** `064a7221cdc2b3c006cdddad6b0d2a42c6140674` on `ghostwire/dev`. Nothing in
this procedure touches `main`, signs anything or publishes anything.

## Before you start

- **Phones:** A (sender) and B (receiver). Android 8.0+ is enough; Android 11+ also lets the adb
  airplane-mode commands work.
- **PC:** Windows 10/11 with PowerShell 5.1 or 7, JDK 21, the Android SDK (platform 37 and
  platform-tools) and Git.
- **Package:** `com.bitchat.droid`, debug-signed with your PC's debug key. Installing test builds
  **deletes any existing bitchat data on the test phones**. Use test phones or accounts.
- **Rules for the whole session:**
  - Use throwaway nicknames and message texts like "t4-1".
  - Don't change nicknames during the test: Retry sending only appears on messages sent under the
    current nickname.
  - Note the time of every step.

### Status meanings

| Status | Meaning |
|---|---|
| Sending | Queued, or handed to the Bluetooth mesh |
| Sent | Only over the internet: a Nostr relay accepted it. Never shown over Bluetooth |
| Delivered | An authenticated receipt came back |
| Read | The recipient opened the chat |
| Failed | 7 days with no route, or every relay rejected it. Open your own failed message to see **Retry sending** |

### Retry timing to expect

- A message with no route is retried after 5 s, 30 s, 2 min, 10 min, 1 h, then 6 h.
- **When a transport comes back, waiting messages are retried at once:**
  - a new validated internet connection;
  - a Bluetooth peer appearing;
  - a Bluetooth session being set up.
- Each trigger can do this at most once every 30 s, so a flapping connection causes no burst. The
  app log line is `OutboxScheduler: Transport available (…)`.
- **A Bluetooth peer counts as "back" only after it really left.** A direct link that drops is
  removed after a 10 s grace period, which also drops its encryption session. If B comes back
  within those 10 s, A just continues on its normal timer (at most about 30 s).

---

## 1. PowerShell setup (every new window)

```powershell
$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"   # adjust to your JDK 21
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path         = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
Set-Location C:\src\bitchat-android.gw                                         # adjust to your clone
$Repo   = (Get-Location).Path
$Pkg    = "com.bitchat.droid"
$Adb    = (Get-Command adb).Source
$LogDir = Join-Path (Split-Path $Repo) "gw-logs"
New-Item -ItemType Directory -Force $LogDir | Out-Null

function Get-Apk([string]$serial, [string]$root = $Repo) {
  $abi = "$(& $Adb -s $serial shell getprop ro.product.cpu.abi)".Trim()
  if (-not $abi) { throw "Cannot read the ABI of $serial (connected and authorised?)" }
  $dir = Join-Path $root "app\build\outputs\apk\debug"
  $p = Join-Path $dir "app-$abi-debug.apk"
  if (Test-Path $p) { return $p }
  $u = Join-Path $dir "app-universal-debug.apk"
  if (Test-Path $u) { return $u }
  throw "No debug APK in $dir. Build first."
}
```

## 2. Verify the code (read-only)

```powershell
java -version                    # must report 21
git fetch origin
git status --short               # must print nothing
git switch ghostwire/dev         # first time: creates the local tracking branch
git pull --ff-only
git rev-parse HEAD               # must be 064a7221cdc2b3c006cdddad6b0d2a42c6140674
git rev-parse origin/main        # must be 998f1684878f1e8aaf2c4d5ad682795acd3cedad
& $Adb devices -l                # both phones must say "device"
$A = "SERIAL_OF_PHONE_A"
$B = "SERIAL_OF_PHONE_B"
```

## 3. Build the test build and the v4 baseline

The baseline is built in a separate, **detached** worktree. No branch is created, switched or
moved, and `main` is never checked out.

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
git worktree add --detach ..\gw-baseline 998f168
git -C ..\gw-baseline log --oneline -1          # 998f168 ...
git branch --show-current                       # still: ghostwire/dev
Push-Location ..\gw-baseline
.\gradlew.bat :app:assembleDebug --console=plain
Pop-Location
$Base = (Resolve-Path ..\gw-baseline).Path
Get-Apk $A; Get-Apk $A $Base; Get-Apk $B; Get-Apk $B $Base   # four existing .apk paths
```

**Choosing the APK.** `Get-Apk` reads the phone's `ro.product.cpu.abi` and returns the matching
`app-<abi>-debug.apk`:
- `arm64-v8a`: nearly all phones;
- `armeabi-v7a`: 32-bit phones and some Android Go devices;
- `x86` and `x86_64`: emulators.

If no ABI-specific APK exists, it falls back to `app-universal-debug.apk`.

## 4. Phone controls

```powershell
& $Adb -s $A shell am force-stop $Pkg                       # force-stop
& $Adb -s $A reboot                                         # reboot
& $Adb -s $A shell cmd connectivity airplane-mode enable    # Android 11+; otherwise use Quick Settings
& $Adb -s $A shell cmd connectivity airplane-mode disable
& $Adb -s $A shell svc wifi disable;  & $Adb -s $A shell svc wifi enable
& $Adb -s $A shell svc data disable;  & $Adb -s $A shell svc data enable
& $Adb -s $A shell am start -n "$Pkg/com.bitchat.android.MainActivity"   # open the app
```

Swap `$A` for `$B` as needed. Use Quick Settings for Bluetooth.

## 5. Logs

Capture **only** the app tags below; `*:S` silences every other tag.

```powershell
$GwTags = @('OutboxScheduler:V','MessageRouter:V','NostrRelayManager:V','NostrTransport:V',
            'NetworkMonitor:V','NostrBackground:V','NostrDirectMessageHandler:V',
            'ConversationDatabase:V','ArtiTorManager:V','*:S')

function Start-GwLog([string]$serial, [string]$name) {
  & $Adb -s $serial logcat -c
  $argList = @('-s', $serial, 'logcat', '-v', 'time') + $GwTags
  Start-Process -FilePath $Adb -ArgumentList $argList -NoNewWindow -PassThru `
    -RedirectStandardOutput (Join-Path $LogDir "$name-raw.log")
}
function Stop-GwLog($proc) { if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id } }

function Protect-GwLog([string]$name) {
  $in  = Join-Path $LogDir "$name-raw.log"
  $out = Join-Path $LogDir "$name.log"
  (Get-Content $in -Encoding utf8) `
    -replace 'wss?://[^\s"'',]+', 'wss://[relay]' `
    -replace 'npub1[0-9a-z]+', '[npub]' `
    -replace '(?<![0-9a-fA-F])[0-9a-fA-F]{12,}(?![0-9a-fA-F])', '[hex]' `
    -replace '(?i)\b(for|geohash[=: ]+)\s*[0-9bcdefghjkmnpqrstuvwxyz]{4,12}\b', '$1 [geohash]' `
    -replace '(/data|/storage|/sdcard)[^\s]*', '[path]' |
    Set-Content $out -Encoding utf8
  Remove-Item $in
  # Must print nothing:
  Select-String -Path $out -Pattern 'wss?://(?!\[relay\])', 'npub1', 'ArtiNative', '/data/', '/storage/', '[0-9a-fA-F]{12,}'
}

# Example: wrap a test
$la = Start-GwLog $A "T6-A"; $lb = Start-GwLog $B "T6-B"
#   ... run the test ...
Stop-GwLog $la; Stop-GwLog $lb
Protect-GwLog "T6-A"; Protect-GwLog "T6-B"
```

**Never** do any of the following:
- run `adb bugreport` or unfiltered `adb logcat`;
- add the `ArtiNative` tag (the native Tor bridge logs every destination host there);
- copy database files (`run-as`, `adb pull` of app data, Database Inspector exports);
- share screenshots with real names or real message text.

The captured tags contain no message text or keys, but the **raw** files can contain relay
hostnames, a geohash (a location) in `NostrBackground` lines, 12–16-character ID prefixes and
file paths. Share only the sanitised `*.log` files.

In T11, join a coarse (city-level) location channel. Run a panic wipe on both phones when you
finish.

## 6. Tests

Run the tests in this order.

### T1 Database v4→v5 migration

**Step 1: clean install of the baseline.** This deletes app data on both phones.

```powershell
& $Adb -s $A uninstall $Pkg; & $Adb -s $B uninstall $Pkg     # "Unknown package" is fine
& $Adb -s $A install (Get-Apk $A $Base); & $Adb -s $B install (Get-Apk $B $Base)
```

**Step 2: create data on the baseline.**
- Open the app on both phones, set nicknames and grant permissions. Bluetooth on, phones close
  together.
- Exchange 5 private messages each way.
- Favourite each other: the star in each user's sheet, on both phones, until "Favorited you"
  shows.
- Leave B's last message **unread** on A. Write down A's unread count.

**Step 3: upgrade in place.** Don't uninstall. Only upgrade; never install the baseline over the
new build, because the database can't be downgraded.

```powershell
& $Adb -s $A install -r (Get-Apk $A); & $Adb -s $B install -r (Get-Apk $B)
```

**Expect:**
- The app opens with no crash or storage-error message.
- All 10 messages are present, in order. A's unread count is unchanged. The favourites still
  show.
- A sends "t1-1" to B and B receives it once.

### T2 Bluetooth only

Setup: both phones in airplane mode with Bluetooth on, about 1 m apart, with a private chat open
from A to B.

1. A sends "t2-1".
   **Expect:** Sending, then **Delivered** within about 5 s; **never Sent**. B shows it once.
2. B opens the chat.
   **Expect:** A shows **Read** within about 5 s.
3. Turn B's Bluetooth off (or walk out of range). A sends "t2-2" and you wait **4 minutes**.
   **Expect:** A stays at Sending. By now its next scheduled retry is about 10 minutes away.
4. Turn B's Bluetooth on.
   **Expect:**
   - B reappears in A's peer list.
   - "t2-2" arrives on B **once**, within about 10 s of B reappearing, without waiting for the
     backed-off timer.
   - A shows Delivered.
   - A's log shows `Transport available (MESH_…)`.
5. Turn B's Bluetooth off for **under 5 s**, then on again, and A sends "t2-3" during the gap.
   **Expect:** delivered within about 30 s (the normal retry; the peer never "left").

### T3 Nostr only

Setup: mutual favourites (from T1), Bluetooth **off** on both phones, internet on.

1. A sends "t3-1".
   **Expect:** Sending, then **Sent** within about 10 s, then Delivered. B shows it once.
2. B opens the chat.
   **Expect:** A shows Read.
3. Put B in airplane mode. A sends "t3-2".
   **Expect:** A shows Sent and stays there.
4. After 2 minutes, take B out of airplane mode and open the app.
   **Expect:** "t3-2" arrives once and A shows Delivered within about 30 s.

### T4 Persistent outbox recovery

Setup as T3. Start logs on A.

1. Put A in airplane mode. A sends "t4-1", "t4-2" and "t4-3".
   **Expect:** all three stay at Sending; none shows Sent or Failed.
2. Take A out of airplane mode.
   **Expect:** all three Sent within about 30 s, then Delivered. B shows each once, in order. A's
   log shows `NetworkMonitor` reconnecting relays.
3. Put A in airplane mode. A sends "t4-4". Force-stop A, wait 1 minute, take A out of airplane
   mode and open the app.
   **Expect:** "t4-4" is still listed, then shows Sent and Delivered within about 90 s, without
   tapping Retry sending.
4. Put A in airplane mode. A sends "t4-5" and waits **10 minutes**, then takes A out of airplane
   mode.
   **Expect:** Sent within about 30 s, without waiting out a long backoff.

### T5 Reboot and force-stop

1. Put A in airplane mode. A sends "t5-1". Reboot A. Unlock it, turn airplane mode off and
   **don't open the app** for 3 minutes.
   **Expect:** B does **not** receive "t5-1" yet (known limitation, see Risks).
2. Open the app on A.
   **Expect:** "t5-1" Sent within about 90 s, then Delivered. B shows it once.
3. Reboot B, unlock it and don't open the app. A sends "t5-2".
   **Expect:**
   - A shows Sent.
   - B may show a notification, but A does **not** show Delivered while B's app stays closed.
   - Open the app on B: A shows Delivered within about 90 s.
4. Force-stop B. A sends "t5-3".
   **Expect:** A shows Sent. Open B: "t5-3" arrives once and A shows Delivered.

### T6 Wi-Fi ↔ mobile data

Setup as T3. A has Wi-Fi and mobile data both on. Start logs on A.

1. A sends "t6-1".
   **Expect:** Delivered.
2. Run `svc wifi disable` on A, wait 5 s, and send "t6-2".
   **Expect:** within about 15 s the log shows relays reconnecting on mobile data, then Sent and
   Delivered.
3. Run `svc wifi enable` on A, wait 10 s, and send "t6-3".
   **Expect:** Delivered.
4. **Flapping:** within 30 s, toggle A's Wi-Fi off and on 5 times while sending "t6-4" in the
   middle.
   **Expect:**
   - "t6-4" arrives once.
   - A's log shows **at most one** `Transport available (INTERNET)` line per 30 s.
   - No burst of repeated sends.
5. Repeat steps 2 and 3 on B.
   **Expect:** every message arrives once.

### T7 Duplicates and deleted-message replay

1. **T7a, Bluetooth then Nostr copy.**
   - A in airplane mode with Bluetooth on; B in airplane mode with Bluetooth off. A sends
     "t7-1". It is queued for the relays.
   - Turn on B's Bluetooth, still with no internet.
     **Expect:** "t7-1" arrives over Bluetooth within about 10 s of the peers seeing each other,
     and A shows Delivered.
   - Take A out of airplane mode for 1 minute, then take B out of airplane mode.
     **Expect:** B still shows "t7-1" **exactly once**. A stays Delivered.
2. **T7b, both transports on.** Both phones have Bluetooth and internet, close together. A sends
   "t7-2".
   **Expect:** exactly one copy on B.
3. **T7c, Nostr then Bluetooth promotion.**
   - B in airplane mode with Bluetooth off. A (online, Bluetooth on) sends "t7-3".
     **Expect:** A shows Sent.
   - Wait 20 s, then turn on B's Bluetooth only.
     **Expect:**
     - Within about 10 s of the Bluetooth session coming up, A also sends "t7-3" over
       Bluetooth: B shows it once and A shows Delivered.
     - A's log shows `Transport available (MESH_SESSION_READY)`.
   - Take B out of airplane mode.
     **Expect:** still one copy (the relay copy is de-duplicated).
4. **T7d, deleted-message replay.**
   - Over Nostr only, A sends "t7-4" and "t7-5" and waits for Delivered.
   - On B, open Conversations, swipe the chat with A and confirm deletion. (The app has no
     single-message delete.)
   - Force-stop B and reopen it. Reopening re-fetches the last 48 h or more from the relays,
     replaying those messages. Wait 3 minutes with internet and Bluetooth on.
   **Expect:** none of the deleted messages reappear. A's statuses don't go backwards.
5. **T7e, deletion while the sender still retries.**
   - A sends "t7-6" over Nostr and immediately goes into airplane mode, before Delivered shows.
   - B receives it, then deletes the conversation.
   - Take A out of airplane mode.
   **Expect:** B never shows "t7-6" again. A reaches Delivered, because B re-acknowledges the
   deleted message.
6. **T7f, files.** Over Nostr only, send a small image from A. Force-stop B and reopen it.
   **Expect:** one copy of the image.

### T8 Delivery and read receipts

1. Over Bluetooth (T2 setup) and over Nostr (T3 setup), check:
   - Delivered appears only after B has the message.
   - Read appears only after B opens that chat.
   - If B is viewing another chat, A stays Delivered.
2. **Lost receipt.**
   - A sends "t8-1" over Nostr. As soon as it shows Sent, put A in airplane mode.
   - When B shows the message, wait 1 minute, then take A out of airplane mode.
   **Expect:** A shows Delivered within a few minutes. B's receipt was retried until a relay
   accepted it, and A fetches it on reconnect.

### T9 Panic wipe

1. Put A in airplane mode. A sends "t9-1".
2. Triple-tap the logo in the header and confirm.
   **Expect:** all chats are gone.
3. Take A out of airplane mode and wait 2 minutes.
   **Expect:** B never receives "t9-1".
4. Force-stop A and reopen it.
   **Expect:** still empty.
5. Before T10, set a nickname again and re-favourite over Bluetooth (the wipe removed the
   favourites).

### T10 Tor recovery

Setup: mutual favourites and an internet connection. Start logs on A.

1. Open About on A (tap the logo) and turn on **Tor**. Wait for "Connected (100%)".
   **Expect:**
   - The About Tor line shows `[destination]` and no relay hostnames.
   - The log shows `ArtiTorManager: Tor ON: proxy set to …` before relays reconnect.
2. Repeat T3 step 1 and T4 steps 1–2.
   **Expect:** same results; the first delivery can take up to about 1 minute.
3. Airplane mode on for 30 s, then off. Send "t10-1".
   **Expect:** About returns to 100% and the message is delivered.
4. Wi-Fi off, then on (as in T6 steps 2–3).
   **Expect:** Tor recovers and messages are delivered.
5. Force-stop A, reopen it, and send "t10-2".
   **Expect:** Tor re-bootstraps to 100% (still on) and the message is delivered.
6. Reboot A, unlock it, open the app and send "t10-3".
   **Expect:** Tor is still on after the reboot, and the message is delivered after bootstrap.
7. Turn Tor off and send "t10-4".
   **Expect:** delivered directly.
8. Turn Tor on and send "t10-5".
   **Expect:** delivered after bootstrap.

Known limitation: over Tor, DM and location relays can share circuits.

### T11 Geohash / account identity separation

Setup: both phones online with Bluetooth on, mutual favourites, and an existing account chat
between them.

1. Both phones join the same **coarse** location channel. From the channel's people list, A opens
   a DM with B and sends "t11-1".
   **Expect:**
   - B gets it in a **separate** conversation under A's location-channel name, not in the account
     chat.
   - A shows Sent once a location relay accepts it, and Delivered if a receipt arrives.
2. Check A's log.
   **Expect:**
   - `MessageRouter: Routing PM via Nostr (geohash)` for "t11-1" only.
   - No `Refusing geohash relays on the private-messaging relay manager` warning.
3. A sends "t11-2" in the account chat.
   **Expect:** it arrives in the account chat only.
4. Put A in airplane mode with Bluetooth on. A sends geohash DM "t11-3" and account DM "t11-4".
   Then force-stop A, take it out of airplane mode and open the app.
   **Expect:**
   - "t11-4" is delivered automatically.
   - "t11-3" is **not** resent: geohash DMs have no outbox, by design.
   - It is never sent over Bluetooth or under the account identity, including when B is nearby
     on Bluetooth.
5. B replies in each conversation.
   **Expect:** each reply lands in the matching conversation on A.

## 7. Finish

```powershell
git worktree remove ..\gw-baseline
git worktree prune
git status --short           # still empty
```

Then panic-wipe both phones. For each test, report pass or fail, the phone models and Android
versions, `git rev-parse HEAD`, and Tor on or off. For failures, add the step, the time and the
sanitised logs.
