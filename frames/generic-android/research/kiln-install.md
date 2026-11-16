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
   and keeps its data. `-g` sets `INSTALL_GRANT_RUNTIME_PERMISSIONS`: Android 6.0.1 `Pm.java:1070-1071`
   (`runInstall`), 7.0 `Pm.java:467-468` (`makeInstallParams`), 10 `PackageManagerShellCommand.java:2406`.
   A different key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (6.0.1 `PackageManagerService.java:5981`)
   and changes nothing; the S1's key switch handles the same answer (`frames/aqara-s1-plus/installer/src/switch.ts:33`).
3. **Grants** (read).
   - `pm grant <pkg> <perm>` calls `grantRuntimePermission`: 6.0.1 `Pm.java:205` (the `grant` op),
     `Pm.java:1762` (`runGrantRevokePermission`), `Pm.java:1787` (the call); 7.0 `Pm.java:1254`, `:1277`;
     10 `PackageManagerShellCommand.java:229` (`case "grant"`), `:1933`, `:1955`.
   - Android takes runtime permissions (`RECORD_AUDIO`, `CAMERA`) and development permissions
     (`WRITE_SECURE_SETTINGS`) there, and refuses any other kind: 6.0.1 `PackageManagerService.java:3473`
     (`grantRuntimePermission`), `:3500` (the check), `:3459-3468`
     (`enforceDeclaredAsUsedAndRuntimeOrDevelopmentPermission`: "is not a changeable permission type"), and
     `:3516` (a development permission's own path).
   - `appops set <pkg> SYSTEM_ALERT_WINDOW allow` gives the overlay: 6.0.1 `AppOpsCommand.java:55` (`set`),
     `:176` (`setMode`).
   - `dpm set-active-admin <component>` adds the device admin the screen lock uses: 6.0.1 `Dpm.java:41`,
     `:101`, `:105` (prints "Success: Active admin set to component …"). An admin already active throws
     "Admin is already added" (6.0.1 `DevicePolicyManagerService.java:1949`, `:1972`).
   - Some vendor builds refuse `pm grant` to the shell unless a vendor switch is on, answering a
     `SecurityException`. Untested on any vendor build here.

   So the recipe reads every grant back (`dumpsys package`, `appops get`, `dumpsys device_policy`) and
   reports what didn't apply, instead of trusting the exit code. The admin is matched as `<package>/`, a
   component: the generic build's package is a prefix of the legacy build's.
4. **Home screen** (read):

   | Android | API | How | Read back |
   |---|---|---|---|
   | 6 | 23 | No `cmd` (verified on the Mirror's Android 6: `cmd: not found`, [home-app.md](../../lululemon-mirror/research/home-app.md)). `am start -a android.intent.action.MAIN -c android.intent.category.HOME` opens Android's chooser; the person taps Glassworks and Always | The `mAlways=true` HOME entry in `dumpsys package preferred-activities`, as on the Mirror |
   | 7 to 9 | 24 to 28 | `cmd package set-home-activity <pkg>/<alias>` sets the preferred HOME activity: 7.0 `PackageManagerShellCommand.java:126-127`, `:1076` (`runSetHomeActivity`), `:1101` (`setHomeActivity`). It prints nothing on success, only its exit code 0 | `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`, last line: 7.0 `PackageManagerShellCommand.java:114-115`, `:864` |
   | 10+ | 29+ | The same command; Android 10 makes the package the home role holder: 10 `PackageManagerShellCommand.java:259-260`, `:2506` (`runSetHomeActivity`), `:2542` (`addRoleHolderAsUser(ROLE_HOME, …)`), `:2549` ("Error: Failed to set default home.") | The same: 10 `PackageManagerShellCommand.java:160`, `:930` |

   AOSP sources read on 2026-11-16 at the tags `android-6.0.1_r1`, `android-7.0.0_r1` and `android-10.0.0_r1`
   of `platform/frameworks/base` (through its GitHub mirror, aosp-mirror/platform_frameworks_base):
   `cmds/pm/src/com/android/commands/pm/Pm.java`, `services/core/java/com/android/server/pm/PackageManagerShellCommand.java`,
   `services/core/java/com/android/server/pm/PackageManagerService.java`, `cmds/appops/src/com/android/commands/appops/AppOpsCommand.java`,
   `cmds/dpm/src/com/android/commands/dpm/Dpm.java` and
   `services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java`.

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
