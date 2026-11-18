---
topic: android
evidence: verified
---

# Screen lock: which adb command tells Kiln that a device has a PIN, pattern or password, without root

**Status (2026-11-18): working on the versions run.** `dumpsys lock_settings` answers on emulators running Android
8.0 (a gatekeeper SID), 11 and 15 (the credential type). Android 6, 7, 8.1, 9, 10 and 12 to 14 are untested; where the
dump has no answer, Kiln says nothing.

Devices: Android Emulator 36.4.9 on an Apple Silicon Mac, system images `android-26;default;arm64-v8a` (8.0),
`android-30;default;arm64-v8a` (11) and `android-35;google_apis;arm64-v8a` (15). adb over TCP only
(`ADB_USB=0 ADB_MDNS=0`, its own server port). Tools: [`tools/probe-screen-lock.sh`](../tools/probe-screen-lock.sh).

## Question

With a PIN, pattern or password, Android locks the device each time Glassworks turns the screen off, and someone has
to unlock it by hand (#522). The app reads `KeyguardManager.isDeviceSecure`. Kiln's `/android` has only adb's shell,
without root, and should say the same on Done. Which shell command tells a secure lock from None or Swipe on
Android 8 to 14, and what does it say when it can't tell?

## What we tried

Each candidate was read with no lock, with `locksettings set-pin 1234`, and after `locksettings clear --old 1234`
(`probe-screen-lock.sh --set-pin`). SIDs and protector IDs are device-unique: they are redacted here and Kiln never
logs them.

1. **`locksettings get-disabled`** (2026-11-18, verified). Not a secure-lock test.
   ```
   API 26: Unknown command: get-disabled
   API 30: true (no lock), "Error while executing command: get-disabled" (PIN)
   API 35: true (no lock), false (PIN), false (Swipe, after `locksettings set-disabled false`)
   ```
   It says whether the lock screen is off (None), not whether it is secure: Swipe and PIN both read false. Android 8
   doesn't have it.

2. **`dumpsys window policy`, `secure=` under `KeyguardServiceDelegate`** (2026-11-18, verified). Ruled out: stale.
   ```
   API 26: secure=true    with no lock, with a PIN, and with Swipe
   API 30: secure=false   no lock;  secure=true  PIN
   API 35: secure=false   no lock, after the emulator had run a while
   API 35: secure=true    no lock, right after a fresh boot
   ```
   It reads true on a device without a lock until something updates it, most likely because the window manager's
   copy of the keyguard state starts as secure (a guess: not checked in AOSP's `KeyguardServiceDelegate`).
   `dumpsys window` prints the same block.

3. **`dumpsys trust`** (2026-11-18, verified). No secure-lock field: per user only `trusted`/`trustState`,
   `trustManaged`, `deviceLocked` and `strongAuthRequired`, the same with and without a PIN.

4. **`dumpsys lock_settings`** (2026-11-18, verified). Works.
   ```
   API 26 (Android 8.0)                 API 30 (Android 11)            API 35 (Android 15)
   SP Enabled = false                   User State:                    User State:
       User 0                             User 0                         User 0
           SP Handle = 0                    SP Handle: <redacted>          LSKF-based SP protector ID: <redacted>
           SID = 0          (no lock)       SID: 0                         SID: <redacted>   (also with no lock)
           SID = <redacted> (PIN)           Quality: 0                     Quality: 0
                                            CredentialType: None | Pin     CredentialType: NONE | PIN
                                                          | Password                     | PATTERN
   ```
   Android 11 and 15 name the credential type: `None` or `NONE` for None and for Swipe, else `Pin`, `Password`,
   `PIN`, `PATTERN`. Android 8 has no type line; its gatekeeper `SID` is 0 without a credential
   and non-zero with one. Android 15 keeps a non-zero SID with no lock (its synthetic password always exists), so the
   SID is only read where no type line is printed. The dump needs `android.permission.DUMP`, which adb's shell has.

5. **`locksettings verify`** (2026-11-18, untested). Not run on purpose: with a lock set it would check an empty
   credential against it, a wrong unlock attempt that can count toward Android's lockout timer.

## Result

| Android | API | `dumpsys lock_settings` | How sure |
|---|---|---|---|
| 6, 7 | 23 to 25 | unknown | untested: the API 23 emulator didn't fit on the disk on 2026-11-18. Without a type or SID line Kiln says nothing |
| 8.0 | 26 | `SID = 0` / non-zero, per user | verified |
| 8.1, 9, 10 | 27 to 29 | expected as 8.0 or as 11; the parser takes either | untested |
| 11 | 30 | `CredentialType: None` / `Pin` / `Password` | verified |
| 12 to 14 | 31 to 34 | expected as 11 or 15; the parser takes either | untested |
| 15 | 35 | `CredentialType: NONE` / `PIN` / `PATTERN`; SID set even without a lock | verified |

The recipe (`parseScreenLock` in [`installer/src/recipe.ts`](../installer/src/recipe.ts)) reads the first user's
block: the type when there is one, else the SID, else nothing. It runs once, after the hand-off to setup, and logs
only "a PIN, pattern or password", "none or swipe" or "unknown". Done shows its Screen lock block only for a lock.

A PIN can be removed over adb only with the PIN itself (`locksettings clear --old <pin>`), so Kiln advises: turn
the lock off in Android's Settings → Security → Screen lock → None or Swipe ([Google's steps](https://support.google.com/android/answer/9079129)),
or leave Glassworks' screen always on, its default since #522.

Kiln's run on the emulators (2026-11-18), with a debug build of the generic app and the same recipe Kiln runs
(`run-node.ts --install`): API 35 with a PIN `"screenLock": "secure"`, API 26 with a PIN `secure`, API 30 without one
`none`.

## How to reproduce

1. Start an emulator on your own adb server, never the default one: `ADB_USB=0 ADB_MDNS=0
   ANDROID_ADB_SERVER_PORT=5071 emulator -avd <avd> -port 5590 -read-only -no-window`. `-read-only` throws the PIN
   away when the emulator stops.
2. `ADB_USB=0 ADB_MDNS=0 ANDROID_ADB_SERVER_PORT=5071 frames/generic-android/tools/probe-screen-lock.sh -s
   emulator-5590 --set-pin`. It reads every candidate (read-only), sets PIN 1234, reads again, clears it and reads a
   third time. Without `--set-pin` it only reads. Use `--set-pin` on an emulator only.
3. The recipe end to end, on the same adb server as step 1:
   ```sh
   export ADB_USB=0 ADB_MDNS=0 ANDROID_ADB_SERVER_PORT=5071
   echo emulator-5590 > /tmp/gw522-serial
   adb -s emulator-5590 shell locksettings set-pin 1234
   npx -y tsx@4.23.15 frames/generic-android/installer/run-node.ts --install --apk <generic apk> --serial-file /tmp/gw522-serial
   ```
   Its last line is `"screenLock"`. This writes: it installs the app (`--undo` removes it) and sets a PIN, which the
   `-read-only` emulator throws away when it stops, or `adb -s emulator-5590 shell locksettings clear --old 1234`.

## Open questions

- A real tablet (the Pixel Tablet with a PIN) has not been read yet.
- Android 6, 7, 8.1, 9, 10 and 12 to 14 are not run. A vendor build could print a different dump; then Kiln says
  nothing.
- A work profile or a second user with its own lock: Kiln reads user 0, the one the app runs as.
