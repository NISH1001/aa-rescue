# AA Rescue

When a wireless Android Auto screen drops mid-drive, Android pauses your music and
Google Maps falls out of navigation. AA Rescue notices the drop and puts things back:

- **Music** — presses play again, but only if it was playing when the screen dropped.
- **Navigation** — brings Google Maps forward and taps **Start**, but only during an active trip.

It also handles the start of a drive: Android Auto's "Start music automatically" presses
play when it connects, and with no speaker or headphones connected that comes out of the
phone's own speaker. AA Rescue pauses it again (**Stop auto-started music**). With a
Bluetooth speaker, headphones or a wired output connected, autoplay is left alone.

Nothing that was off gets turned on. Both behaviors can be toggled, and the switch at
the top pauses everything. Timings (reaction delay, how long to keep music playing, how long
to wait for an unlock, etc.) are adjustable under **Timing**.

<p align="center">
  <img src="docs/main.png" width="280" alt="Main screen: toggles, test button, recent drops">
  &nbsp;&nbsp;
  <img src="docs/info.png" width="280" alt="Info sheet: pausing vs. turning off">
</p>

## Privacy

- **No internet permission.** Nothing the app sees can leave the phone.
- **Accessibility is scoped to Google Maps.** It only receives events from Maps
  (`res/xml/maps_clicker.xml`), only reads the screen while Maps is the app in front,
  and only acts for ~25 seconds after a drop, looking for a "Start"/"Resume" button.
  Android shows its generic "full control" warning for every accessibility app regardless.
- **Notification access** is used to press play on the music app and to see whether
  Maps has an active trip notification.

## Install

Download the APK from the [latest release](../../releases/latest) (or
`release/aa-rescue-0.1.7.apk` in this repo) to the phone and open it (allow installs from that
source), or with adb:

```sh
adb install -r release/aa-rescue-0.1.7.apk
```

Then open AA Rescue and finish Setup:

1. **Music control** — Notification access → AA Rescue → on.
2. **Maps auto-Start** — Accessibility → AA Rescue → on. If it's greyed out:
   App info → ⋮ → *Allow restricted settings*, then try again.

Tip: if your phone has a PIN, add the car screen's Bluetooth (or your speaker) under
Extend Unlock → Trusted devices, so Maps can be opened after a drop.

The APK is signed with a debug key (personal sideload build, not for the Play Store).
0.1.7 is signed with a different debug key than 0.1.6 and earlier, so it can't update those
in place: uninstall the old version first, then redo Setup.

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Read the app's trace log (adb only):

```sh
adb shell dumpsys activity service dev.nish.aarescue/.MediaWatcher
```

Integration tests with a real Android Auto session (Desktop Head Unit) and real drops:
setup, process, scenarios and latest results are in
[docs/INTEGRATION_TESTS.md](docs/INTEGRATION_TESTS.md).

```sh
scripts/e2e.sh <adb-serial> music_nav   # music + navigation must come back after the drop
scripts/e2e.sh <adb-serial> nothing     # nothing playing → nothing gets started
```

Scenario matrix (no trip / trip, music on / off, another app in front, instant reconnect,
back-to-back drops), checking the phone's real state after each drop:

```sh
scripts/matrix.sh <adb-serial>           # all of s1..s6
```

Fake a drop for testing (adb only):

```sh
adb shell am broadcast -n dev.nish.aarescue/.DebugDropReceiver --ez nav true
```

Fake a connection (adb only), then start music within the auto-start window to see it
paused when no speaker is connected:

```sh
adb shell am broadcast -n dev.nish.aarescue/.DebugDropReceiver --ez connect true
```
