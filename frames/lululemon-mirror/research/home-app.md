# Mirror home app: how can adb, without root, make S1 Kiosk the Mirror's home screen on Android 6?

**Status (2026-11-15): read on the maintainer's unit, nothing written.** Android 6 has no `cmd`, so
`cmd package set-home-activity` doesn't exist, and adb's shell can't disable another app. The way
that works without root is Android's own chooser: the person taps S1 Kiosk, then Always.

Device: lululemon Mirror (MSM8916), community image (Glass Panes `mirror-android-1`), Android 6.0.1,
`userdebug`, `ro.debuggable=0`, SELinux enforcing. adb as `shell`, no root.

## Question

The S1 recipe makes S1 Kiosk the home app with `cmd package set-home-activity`, then disables
Aqara's launcher. Kiln's Mirror recipe has to do the same with what an unrooted Android 6 shell has,
and undo has to give the old home app back.

## What we tried

All read-only, by serial, on 2026-11-15 (verified). Serials left out.

1. **Is there `cmd`?**
   ```
   $ adb shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
   /system/bin/sh: cmd: not found
   $ adb shell ls -l /system/bin/cmd
   /system/bin/cmd: No such file or directory
   ```
   No `cmd` at all (it came with Android 7). Rules out `cmd package set-home-activity` and
   `cmd package resolve-activity`.

2. **What `pm` offers.** `adb shell pm` lists `install` (with `-g`), `uninstall`, `grant`, `revoke`,
   `enable`, `disable`, `disable-user`, `hide`, `unhide`, `set-app-link`. No `set-home-activity`.

3. **What adb's shell may do.** `dumpsys package com.android.shell` grants it
   `SET_PREFERRED_APPLICATIONS`, `WRITE_SECURE_SETTINGS`, `GRANT_RUNTIME_PERMISSIONS`,
   `INSTALL_GRANT_RUNTIME_PERMISSIONS`, `MANAGE_DEVICE_ADMINS`, `MANAGE_USERS`,
   `CHANGE_CONFIGURATION`. It does **not** list `CHANGE_COMPONENT_ENABLED_STATE`, so
   `pm disable-user` of another app should fail with a SecurityException (untested: it would write).
   `SET_PREFERRED_APPLICATIONS` has no shell command on Android 6 to use it with.

4. **The home app today.**
   ```
   $ adb shell dumpsys activity activities | grep -e HOME -e mFocusedActivity
   intent={act=android.intent.action.MAIN cat=[android.intent.category.HOME] … cmp=com.nimrod.mirrorkiosk/.MainActivity}
   mFocusedActivity: ActivityRecord{… com.nimrod.mirrorkiosk/.MainActivity …}
   $ adb shell dumpsys package preferred-activities
   Preferred Activities User 0:
     …
     Non-Data Actions:
         android.intent.action.MAIN:
           … com.nimrod.mirrorkiosk/.MainActivity
            mMatch=0x100000 mAlways=true
             Selected from:
               com.nimrod.mirrorkiosk/.MainActivity
               com.android.launcher3/.Launcher
             Category: "android.intent.category.HOME"
   ```
   Two home apps: the community kiosk (`com.nimrod.mirrorkiosk`, third-party, versionName
   `0.10.12-screenoff-wifi`) and `com.android.launcher3`. The kiosk was picked with Always.

5. **Device admins.** `dumpsys device_policy` lists `com.nimrod.mirrorkiosk/.AdminReceiver`
   (force-lock). `dpm` has `set-active-admin` but no `remove-active-admin`.

6. **Room and props.** `df /data`: 4.0G free of 5.1G. `ro.board.platform=msm8916`,
   `ro.product.device=msm8916_64`, `ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi`,
   `ro.build.version.sdk=23`. `dumpsys package sh.edm.s1kiosk` prints nothing (not installed).

## Result

| Need | On Android 6, no root | How sure |
|---|---|---|
| Set the home app from adb | Not possible: no `cmd`, no `pm set-home-activity` | Verified |
| Hide the other home apps | `pm disable-user` needs a permission shell lacks; `pm hide` may work (`MANAGE_USERS`) but would leave launcher3 as the only fallback | Read; untested |
| Make S1 Kiosk home | Install it, then `am start -a MAIN -c HOME`. The set of home apps changed, so Android drops the old Always pick and shows its chooser. The person taps S1 Kiosk, then Always | Read from AOSP 6 `PackageManagerService.findPreferredActivity` ("Result set changed, dropping preferred activity"); untested on the unit |
| Know it worked | Read `dumpsys package preferred-activities` until the HOME entry with `mAlways=true` is S1 Kiosk | Format verified above |
| Undo | Ask the app to drop its admin (`--ez remove_admin true`), `pm uninstall`, then the chooser again for the old home app. The old one is kept in `/data/local/tmp/glassworks-previous-home` at install | Read; untested |

Kiln's Mirror recipe (`frames/lululemon-mirror/installer`) does this. Its fake (`test/fakes.ts`) is
built from the outputs above.

Not used: device owner (`dpm set-device-owner` from adb works on Android 6 when there are no
accounts) would let the app pin itself as home, but only the app can clear it again, and it blocks
uninstalling. That's a harder way back than a chooser tap.

## How to reproduce

Read-only. With several devices on one adb server, address the Mirror by serial (`adb -s <serial>`).

1. `adb shell ls -l /system/bin/cmd`
2. `adb shell dumpsys package preferred-activities | grep -B3 -A8 category.HOME`
3. `adb shell dumpsys package com.android.shell | grep -e COMPONENT -e PREFERRED -e MANAGE_USERS`
4. `adb shell dumpsys device_policy | head`

## Open questions

- Does the chooser appear on the Mirror after S1 Kiosk is installed? The Mirror has no touchscreen
  per #29, so who taps it, and how? (scrcpy, or a USB mouse.) The real Kiln run answers it.
- Does the community kiosk keep running (boot receiver, foreground service) once it isn't home?
- Android 7 (API 24–25) has `cmd package set-home-activity` and may skip the chooser; untested.
