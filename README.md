# AA Rescue

When a wireless Android Auto screen drops mid-drive, Android pauses your music and
Google Maps falls out of navigation. AA Rescue notices the drop and puts things back:

- **Music** — presses play again, but only if it was playing when the screen dropped.
- **Navigation** — brings Google Maps forward and taps **Start**, but only during an active trip.

Nothing that was off gets turned on. Both behaviors can be toggled, and the switch at
the top pauses everything.

## Install

Download `release/aa-rescue-0.3.apk` to the phone and open it (allow installs from that
source), or with adb:

```sh
adb install -r release/aa-rescue-0.3.apk
```

Then open AA Rescue and finish Setup:

1. **Music control** — Notification access → AA Rescue → on.
2. **Maps auto-Start** — Accessibility → AA Rescue → on. If it's greyed out:
   App info → ⋮ → *Allow restricted settings*, then try again.

Tip: if your phone has a PIN, add the car screen's Bluetooth (or your speaker) under
Extend Unlock → Trusted devices, so Maps can be opened after a drop.

The APK is signed with a debug key (personal sideload build, not for the Play Store).

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Fake a drop for testing (adb only):

```sh
adb shell am broadcast -n dev.nish.aarescue/.DebugDropReceiver --ez nav true
```
