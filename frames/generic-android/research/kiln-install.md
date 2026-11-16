# Kiln on any Android device: which adb commands work on which Android

**Status (2026-11-16): untested on a device.** The recipe (`frames/generic-android/installer`) runs
against a fake device per API level. What each command does comes from Android's docs and source, read,
not run. The root's Pixel Tablet run checks it (panel plan in the #403 trail).

Device: none yet. Tools: Kiln's adb over WebUSB (`kiln/packages/adb`).

## Question

What can adb's shell, without root, do on an Android 6+ device that isn't ours, to install Glassworks,
grant it what it needs and make it the home screen? And what tells Kiln a device's brand before USB
debugging is on, so it can show that brand's steps?

## What we read

1. **Which app** (read, `frames/generic-android/android/build.gradle.kts`). `dev.glassworks.generic_android`
   has minSdk 26. The `legacy` build type adds `.legacy` and sets minSdk 23. The recipe picks by
   `ro.build.version.sdk` and refuses an APK of the other build before anything is written (#403).
2. **Install or update** (read). `pm install -r -g <file>` replaces an installed app with the same key
   and keeps its data; `-g` grants the runtime permissions the manifest asks for (Android 6+). A different
   key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and changes nothing.
3. **Grants** (read). `pm grant` takes runtime permissions (`RECORD_AUDIO`, `CAMERA`) and development
   permissions (`WRITE_SECURE_SETTINGS`). `appops set <pkg> SYSTEM_ALERT_WINDOW allow` gives the overlay.
   `dpm set-active-admin` adds the device admin the screen lock uses; it fails with "Admin is already
   added" when it is active. Some vendor builds refuse `pm grant` to the shell unless a vendor switch is
   on (e.g. an extra "USB debugging (Security settings)" toggle), answering a `SecurityException`. Untested
   on any vendor build here. So the recipe reads every grant back (`dumpsys package`, `appops get`,
   `dumpsys device_policy`) and reports what didn't apply, instead of trusting the exit code.
4. **Home screen** (read):

   | Android | API | How | Read back |
   |---|---|---|---|
   | 6 | 23 | No `cmd`. `am start -a MAIN -c HOME` opens Android's chooser; the person taps Glassworks and Always | The `mAlways=true` HOME entry in `dumpsys package preferred-activities`, as on the Mirror ([home-app.md](../../lululemon-mirror/research/home-app.md)) |
   | 7 to 9 | 24 to 28 | `cmd package set-home-activity <pkg>/<alias>`: sets the preferred HOME activity | `cmd package resolve-activity --brief -a MAIN -c HOME`, last line |
   | 10+ | 29+ | The same command; Android 10 moved the default home app to the home role, and the command sets the role holder | The same |

   The component is the Launcher alias (`dev.glassworks.generic.Launcher`), the one with MAIN+HOME+DEFAULT.
   The check matches the package, not the class, as #411 does: Android may name the alias or its target.
   When `set-home-activity` fails or doesn't take (a vendor launcher lock is possible, untested), the recipe
   falls back to the chooser.
5. **Hand-off** (read, `kiln/apps/kiln/src/wizard/session.ts` `handOff`). The app's setup channel over
   adb (`localabstract:s1kiosk_setup`) gives its Wi-Fi address; `pairing.arm` opens pairing, and Kiln links
   `http://<address>:8099/setup` (#34).
6. **Before USB debugging** (read). WebUSB's `USBDevice` gives `vendorId` and `productName` for a device
   in charging or file-transfer mode. Vendor IDs are from usb.ids (version 2026.06.26,
   [linux-usb.org/usb.ids](http://www.linux-usb.org/usb.ids)): Google `18d1`, Samsung `04e8`, Amazon (Lab126)
   `1949`, Lenovo `17ef` and others. A no-name tablet often keeps its chip maker's ID (Allwinner `1f3a`,
   Rockchip `2207`, MediaTek `0e8d`, Qualcomm `05c6`), which says nothing about the brand, so Kiln asks.
   The product names a device reports in each mode are untested.

## Result

| Step | Android 6 | Android 7 | Android 8+ |
|---|---|---|---|
| App | `.legacy` | `.legacy` | `dev.glassworks.generic_android` |
| Install | `pm install -r -g` | same | same |
| Grants | `pm grant`, `appops set`, `dpm set-active-admin`, each read back | same | same |
| Home | Chooser + Always | `set-home-activity`, chooser if refused | same |

The command sequences are pinned per API level (23, 25, 26, 29, 34) in
`installer/test/golden/generic-adb-commands.json`.

Each brand's "turn on USB debugging" steps cite their page in `installer/src/devices.ts`: Android
Developers for the general steps and LG, HTC and OnePlus; Samsung's and Amazon's own pages; the Pixel
Tablet's from #403 (read on the device, 2026-11-16). Lenovo's support pages refused automated reads
(HTTP 403, 2026-11-16), so Lenovo shows Android's general steps.

## How to reproduce

`npm test -w @glassworks/generic-android-installer`. Touches no device.

## Open questions

- Does `set-home-activity` take on the Pixel Tablet (Android 17), and does `resolve-activity` then name
  our alias? The root's run checks it.
- Which vendor builds refuse `pm grant WRITE_SECURE_SETTINGS` or lock the launcher (Fire OS is a likely
  one)? Each needs a real device.
- What `productName` a Pixel, a Galaxy Tab and a Fire report over WebUSB in charging mode.
