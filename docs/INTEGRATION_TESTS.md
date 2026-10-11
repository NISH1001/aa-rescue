# Integration tests

AA Rescue reacts to one event, a wireless Android Auto session dropping, and its job is
to put the phone back the way it was. These tests produce that event for real and check
the phone's actual state afterwards (music playing or not, turn-by-turn running or not,
which app is in front), not just the app's own log.

- **Car screen:** Google's [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu)
  runs on a Mac/PC and acts as the car display. The phone runs a genuine Android Auto
  session against it, with real Google Maps and YouTube Music.
- **Drop:** killing the DHU process is a real Android Auto disconnect. The phone sees
  the same `PROJECTION -> NOT_CONNECTED` transition a glitching head unit causes.
- **Checks:** `scripts/matrix.sh` reads live state from AA Rescue
  (`dumpsys … MediaWatcher state` → `projecting / trip / playing`), the foreground
  activity, and the app's trace for each run.

## Setup

### Computer

1. Android SDK with platform-tools (`adb`).
2. DHU **2.1 or newer**. 2.1 is on the canary channel:
   ```sh
   sdkmanager --channel=3 "extras;google;auto"
   ~/Library/Android/sdk/extras/google/auto/desktop-head-unit --version   # 2.1-…
   ```

### Phone

1. Install the AA Rescue APK and finish Setup in the app (Notification access + Accessibility).
2. Developer options → **Wireless debugging** (or USB debugging). Pair from the computer:
   ```sh
   adb mdns services                       # find the phone's pairing / connect ports
   adb pair <ip>:<pairing-port> <code>
   adb connect <ip>:<connect-port>
   ```
3. Android Auto settings → scroll to **Version**, tap it ~10 times → allow developer settings.
4. Android Auto → ⋮ → **Developer settings** → ⋮ → **Start head unit server**
   (a "Head unit server running" notification appears).
5. Android Auto → **Vehicles** → **Add new vehicles to Android Auto** must be on.
6. Keep the phone unlocked and awake while testing (the scripts set `svc power stayon`).

### Gotchas

- **DHU connects, then nothing happens** (no `Phone reported protocol version` in its
  output): stop and start the head unit server on the phone, then run again. The first
  connection after starting the server may also exit once; just re-run.
- **The DHU needs an open stdin.** With stdin closed it exits immediately. The scripts
  feed it through a FIFO, which is also how they send console commands
  (`tap x y`, `screenshot <file>`, `keycode …`).
- **Start trips on the car screen, not the phone.** A `google.navigation:` intent sent to
  the phone during a session does not start turn-by-turn on the car. The matrix taps
  the first suggestion and then Start on the DHU screen (800×480). Card layouts vary, so
  if it prints `(could not start trip on car screen)`, start a trip in the DHU window by
  hand and re-run. An existing connection and trip are reused.
- **Don't force-stop AA Rescue** during testing. Android turns off an app's
  Accessibility permission when it's force-stopped. Reinstalling (`adb install -r`) is fine.

## Running

```sh
scripts/matrix.sh <adb-serial>            # S1–S6
scripts/matrix.sh <adb-serial> s3 s4      # selected scenarios
scripts/e2e.sh <adb-serial> music_nav     # single drop + reconnect, prints the trace
```

Each scenario prints the state before and after the drop, the relevant trace lines, and
PASS/FAIL per check, ending with `RESULT: n passed, m failed`.

Order matters a little. S1/S2 need **no** trip on the car (the script ends one by tapping
✕ on the trip card). S3–S6 need one, and it carries over across reconnects.

## Scenarios

| # | Before the drop | Expected after the drop |
|---|---|---|
| S1 | No trip, nothing playing | Nothing touched; Maps not opened; event "nothing was playing, left as is" |
| S2 | No trip, YouTube Music playing | Music resumes; Maps not opened; no trip started |
| S3 | Trip on the car screen, music paused | Turn-by-turn running on the phone; music stays off; Maps in front |
| S4 | Trip + music, **Settings app** in front | Music resumes; trip running; Maps brought to the front |
| S5 | Trip + music, drop and **reconnect ~1 s later** | Connected again; music playing; nothing tapped after the reconnect |
| S6 | Trip + music, **two drops in a row** | Both drops: music resumes, trip running |
| S7 | Trip + music, **phone locked (PIN)**, stays disconnected | Music resumes while locked; after unlock, Maps opens into the trip and navigation runs |
| S8 | Trip + music, phone locked, drop, **auto-reconnect**, then unlock | After unlock nothing pops up (no Maps); phone stays where it was |

S7 and S8 are manual because they need a fingerprint/PIN unlock. Steps:

1. Start a trip on the DHU screen and play music.
2. Lock the phone: `adb shell input keyevent KEYCODE_POWER` (confirm `isKeyguardShowing=true`
   via `adb shell dumpsys window`).
3. Drop: kill the DHU. For S8, start it again within ~10 s.
4. Unlock with your fingerprint and look at the phone. Check the trace:
   `adb shell dumpsys activity service dev.nish.aarescue/.MediaWatcher`.

## Results

**v0.1.6** · 2026-10-10 · Pixel 9, Android 17, Android Auto 17.7, Google Maps 26.40,
YouTube Music 9.40, DHU 2.1 over wireless adb

| # | Checks | Result | Notes |
|---|---|---|---|
| S1 | music stays off · Maps not opened · "left as is" | ✅ 3/3 | |
| S2 | music resumed · Maps not opened · no trip started | ✅ 3/3 | |
| S3 | trip running · music stays off · Maps in front | ✅ 3/3 | Maps opened via the trip notification's action, then Start tapped |
| S4 | music resumed · trip running · Maps brought to front | ✅ 3/3 | |
| S5 | connected again · music playing · nothing tapped after reconnect | ✅ 3/3 | |
| S6 | ×2: music resumed · trip running | ✅ 4/4 | Second drop: pause arrived *after* the drop; caught by the keep-playing window |
| S7 | music resumed while locked · navigation after unlock | ✅ 2/2 | Manual. Maps' phone UI had forgotten the trip; restored via the saved trip action |
| S8 | no Maps pop-up after unlock | ✅ 1/1 | Manual |

**Total: 19/19 automated checks (S1–S6) + 3/3 manual (S7–S8).**

### Bugs these tests found

| Found in | Bug | Fixed in |
|---|---|---|
| e2e drop | After a drop Maps showed its trip summary with **Restart**; AA Rescue only knew Start/Resume | 0.1.4 |
| e2e drop | Maps briefly flashes a navigation notification right after a drop; it was taken as "navigation is back on" | 0.1.4 (must stay up 3 s) |
| S1/S2 | While projecting, Maps keeps a plain "Driving with Google Maps" notification up **even with no trip**; it was treated as a trip, so Maps got opened after drops with no navigation | 0.1.5 (only turn-by-turn notifications count) |
| S1 | Drop while locked → auto-reconnect → later unlock still opened Maps | 0.1.5 |
| S1 | "Couldn't resume" logged although music had resumed (judged too early) | 0.1.5 |
| S7 | After a drop while locked, Maps' phone screen forgot the trip; there was no Start/Restart to tap | 0.1.6 (reopen the trip via Maps' trip notification action, saved during the drive) |

### Not covered

- **The real head unit.** The DHU isn't the cheap wireless screen this was built for.
  Real glitches can differ, e.g. audio cutting out several seconds before the session
  drops (see *Timing → Music stopped before drop*). Real drives plus the trace log
  remain the final check.
- **The app's switches** (Resume music / Resume navigation off, the main pause switch).
  Each is checked before acting, but no drop has been run with them off.
- **Extend Unlock / trusted devices.** S7 used a PIN-locked phone; with a trusted device
  the lock screen is dismissed without a prompt, a path seen on real drives but not in
  this matrix.

## Cleanup

Android Auto → ⋮ → Developer settings → ⋮ → **Stop head unit server**. Optionally turn off
wireless debugging.
