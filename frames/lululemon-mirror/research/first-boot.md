---
topic: install
evidence: verified
---

# Mirror first boot: how a fresh userdata comes up with nothing to tap

**Status (2026-11-16): verified on the unit.** The root's Mirror run (below) found every claim true on
the glass, with nothing tapped. Kiln's images now set, before the first boot:

- Wi-Fi on, with the person's network saved;
- the screen staying on;
- no lock screen;
- no auto-rotate.

Each mechanism was read from Android 6.0.1's source and checked against the Glass Panes image 2
files. None has run on a Mirror yet. The root's test plan is at the end.

Device: lululemon Mirror (MSM8916), Glass Panes `mirror-android-2` (Android 6.0.1, `userdebug`,
`test-keys`, no pre-optimised odex). Tools: `@glassworks/ext4`, Android SDK build-tools 36.0.0 and platform android-36 (pinned in `build.sh`)
(`aapt2`, `apksigner`), AOSP source at `android-6.0.1_r81` (android.googlesource.com).

## Question

The Mirror has no touchscreen. A fresh Glass Panes userdata comes up with:

- Wi-Fi off and no saved network;
- a 60 s screen timeout;
- the swipe lock screen;
- auto-rotate on.

There is nothing to tap to fix any of it. Can Kiln's images set all of that before the first boot,
from `system` or the fresh `userdata` it already builds?

## What we tried

1. **Whether a pre-made settings file works** (2026-11-16, read: AOSP 6.0.1
   `packages/SettingsProvider/.../SettingsProvider.java:1577`). On first boot, SettingsProvider
   builds the default settings with `DatabaseHelper` **only when `settings_secure.xml` doesn't
   exist**, then writes the XML files. So a pre-made `settings_*.xml` replaces **every** default
   (device_provisioned, user_setup_complete and the rest), not just ours. A pre-made legacy
   `settings.db` at the current version skips `onCreate` the same way. **Ruled out**, because we
   would have to reproduce every default ourselves.

2. **System properties DatabaseHelper reads** (2026-11-16, read: `DatabaseHelper.java`, all
   `SystemProperties.get`). These exist:
   - `ro.lockscreen.disable.default` (line 2483), which sets `lockscreen.disabled`;
   - `ro.kernel.qemu`, which sets `stay_on_while_plugged_in`. It also makes Android behave as an
     emulator: **ruled out**;
   - `ro.allow.mock.location`, `ro.com.android.dataroaming`, `ro.com.android.mobiledata` and
     `ro.telephony.default_cdma_sub`.

   There is none for `wifi_on`, `screen_off_timeout` or rotation. The image's Wi-Fi service dex reads
   no property for them either (2026-11-16, `strings` on `wifi-service.jar`'s and `services.jar`'s
   `classes.dex`).

3. **`ro.lockscreen.disable.default` on this image** (2026-11-16, read: the image's dex). Both
   `SettingsProvider.apk`'s `classes.dex` and `services.jar`'s contain the string. In AOSP 6.0.1,
   `LockSettingsService` (lines 103–112) writes `lockscreen.disabled=1` into a new `locksettings.db`
   when it is true, and `DatabaseHelper` (line 2483) seeds it too. `LockPatternUtils.isLockScreenDisabled`
   honours it when no PIN or pattern is set. The only migration that clears it does so with more
   than one switchable user (`LockSettingsService.java:233`–`255`, the clearing at about line 245). A
   fresh userdata has one.

4. **A static resource overlay for SettingsProvider** (2026-11-16, read: AOSP 6.0.1
   `PackageManagerService.java:2066` and `:5588`, `rootdir/init.rc:31`). Android 6 scans
   `/vendor/overlay` before any app. It trusts an overlay by where it sits, not by its key: there is
   no signature match with the target, only `mTrustedOverlay` from that folder. It builds an idmap
   that matches resources by type and name. `init.rc` links `/vendor` to `/system/vendor` on
   devices without a vendor partition. This image has `/system/vendor` (with `lib`, `lib64`, `bin`)
   and no `overlay` folder.

   The image's SettingsProvider has these resources (`aapt2 dump resources`):
   - `bool/def_wifi_on` false;
   - `integer/def_screen_off_timeout` 60000;
   - `bool/def_stay_on_while_plugged_in` false;
   - `bool/def_lockscreen_disabled` false;
   - `bool/def_accelerometer_rotation` true.

5. **The Wi-Fi network in userdata** (2026-11-16, read: AOSP 6.0.1 `libhardware_legacy/wifi/wifi.c:377`,
   `init.rc:325`, `sepolicy/file_contexts:251`). The Wi-Fi HAL uses an existing
   `/data/misc/wifi/wpa_supplicant.conf` as it is. It copies the template
   (`/system/etc/wifi/wpa_supplicant.conf`) only when the file doesn't exist.
   - `init.rc` makes `/data/misc/wifi` 0770 `wifi:wifi`.
   - The HAL makes the file 0660 `system:wifi`.
   - `file_contexts` labels both `u:object_r:wifi_data_file:s0`.
   - The image's template (read 2026-11-16) is `update_config=1`, `eapol_version=1`, `ap_scan=1`,
     `fast_reauth=1`, `pmf=1`, `p2p_no_group_iface=1`.
   - The image's own `file_contexts` is in its signed boot ramdisk, which Kiln doesn't read, so the
     labels are AOSP's.

## Result

| What | How Kiln sets it | Where | Evidence |
|---|---|---|---|
| No lock screen | `ro.lockscreen.disable.default=true` | `system`'s `build.prop` | Read: AOSP 6.0.1, and the string in the image's dex |
| Wi-Fi on | Overlay: `def_wifi_on` true | `/system/vendor/overlay/GlassworksSettings.apk` | Read: AOSP 6.0.1 PMS and DatabaseHelper |
| The person's network | `network={ ssid psk key_mgmt=WPA-PSK priority=1 }` after the template; the PSK is PBKDF2-HMAC-SHA1 (passphrase, SSID, 4096, 256 bits), never the password | `userdata`'s `/misc/wifi/wpa_supplicant.conf`, 0660 1000:1010, `wifi_data_file` | Read: AOSP 6.0.1 wifi.c; tests on the IEEE 802.11i vectors |
| Screen stays on | Overlay: `def_screen_off_timeout` 2147483647 (no timeout in practice) and `def_stay_on_while_plugged_in` true. DatabaseHelper turns that into **1** (AC only), not 7: there is no default that gives 7 | Overlay | Read: `DatabaseHelper.java:2580` |
| Portrait, no auto-rotate | Overlay: `def_accelerometer_rotation` false. The first boot over adb: `settings put system accelerometer_rotation 0`, `settings put system user_rotation 3`, then `dumpsys window windows` until `mRotation=3` exactly; Kiln stops after 10 s otherwise (`setPortrait`) | Overlay, the first boot | **Corrected 2026-11-17.** This row said rotation stays 0 and `ro.sf.hwrotation=270` makes it portrait, with no `user_rotation` needed. Wrong: the real legacy app came up **sideways** (the maintainer saw landscape), and nothing on the image reads `ro.sf.hwrotation` ([image-patch.md](image-patch.md#the-rotation-2026-11-17)). 3 is upright: **verified** 2026-11-17 at the first boot, the maintainer on the glass. After a reboot: **untested** (the run's record on #439 lists it as not checked; the setting lives in `userdata`, so it should hold) |
| Our app as HOME | Unchanged: `makeOnlyHome` hides the image's launchers only once our app answers HOME, and Kiln refuses an APK without one (#385) | | Verified with the probe app (below), and with the legacy build of Glassworks, Kiln's app since 2026-11-16: it was the only home screen on the Mirror (2026-11-17, #443's run) |

The overlay is `frames/lululemon-mirror/overlay` (`AndroidManifest.xml`, `res/values/defaults.xml`).
`build.sh` builds `GlassworksSettings.apk`: `aapt2`, then v1 signing with AOSP's public test key
(checked by SHA-256), since Android 6 reads no v2. Two builds give the same bytes. Kiln pins it by
SHA-256 (`SETTINGS_OVERLAY`), and `patchSystem` refuses any other file.

Kiln's Wi-Fi form now fills the network into the image. Sending the network to the app after the
boot stays as a fallback.

## How to reproduce

Offline, nothing touches a device:

1. `ANDROID_HOME=~/Library/Android/sdk frames/lululemon-mirror/overlay/build.sh`: the SHA-256 it
   prints must equal `SETTINGS_OVERLAY.sha256`.
2. `npm test -w @glassworks/lululemon-mirror-installer`. The image tests check the overlay and the
   saved Wi-Fi with e2fsprogs: owners, modes, SELinux labels, and no plain password.
3. With the real image: `GW_MIRROR_SYSTEM_IMG=<image 2 system.img> npm test -w @glassworks/lululemon-mirror-installer`.
   On 2026-11-16 it passed: the overlay and `ro.lockscreen.disable.default` are in, and e2fsck finds
   nothing new.

## The run on the Mirror (2026-11-16, verified by the root)

A local build of #395 (3af4c89b) merged with main (a72c4702): image 2, #389's turned boot animation,
and #398's probe app as the APK (an app with MAIN, HOME and DEFAULT on API 23). Flashed with
`tools/mirror/flash-node.ts` and a Wi-Fi network from a file. Nothing was tapped on the glass. The
full table is on #395.

| Check | Result |
|---|---|
| `/vendor` | `vendor -> /system/vendor`; `GlassworksSettings.apk` in `/vendor/overlay`, applied |
| Wi-Fi | `wifi_on=1`, connected on its own at the first boot and again after a reboot |
| Screen | `screen_off_timeout=2147483647`, `stay_on_while_plugged_in=1`; it doesn't sleep |
| Rotation | `accelerometer_rotation=0`. The boot animation (#389) upright on the glass (the maintainer's photo). "Portrait" here was the probe app asking for portrait itself, not the system: **corrected 2026-11-17**, the system stayed at rotation 0, landscape ([image-patch.md](image-patch.md#the-rotation-2026-11-17)) |
| Lock screen | `mShowingLockscreen=false` at the first boot, after sleep and wake, and after a reboot |
| Home | the probe's `.MainActivity`; Launcher3 hidden once it answered HOME |

The run also found that the first boot's Wi-Fi step logged the network's name. It now reads "Wi-Fi:
the network you entered", and tests check that no log line holds the name or the password.

## The root's test plan

Steps 1–4 and 6 ran on 2026-11-16 (above). Step 5 changed on 2026-11-17 and passed that day on the
root's run of #439 at the first boot: rotation 3, the app upright on the glass. Its "repeat after a
reboot" part is **untested** (not checked in that run). The run above (2026-11-16) saw rotation 0.

Flash with this build (Kiln or `tools/mirror/flash-node.ts`), with the Wi-Fi name and password
given, a Home Assistant address, and an APK that has a home screen on API 23. Then, touching nothing
on the Mirror:

1. **Wi-Fi.** After the first boot, `adb shell dumpsys wifi | grep -m3 -E "Wi-Fi is|mNetworkInfo"`
   shows Wi-Fi enabled and connected to the network. `adb shell settings get global wifi_on` gives
   1. If it's 0, the overlay wasn't applied: check `adb shell ls /vendor/overlay` and
   `adb shell ls /data/resource-cache`, which should hold an idmap for SettingsProvider.
2. **Network saved, not the password.** `adb shell su -c cat /data/misc/wifi/wpa_supplicant.conf`
   (if root exists) shows `psk=` followed by 64 hex digits.
3. **No lock screen.** Let the screen sleep (`adb shell input keyevent KEYCODE_SLEEP`), then wake it
   (`KEYCODE_WAKEUP`). It must show the app directly, with no swipe screen. Repeat after a reboot.
   `adb shell dumpsys window policy | grep -i keyguard` should show it isn't showing.
4. **Screen stays on.** `adb shell settings get system screen_off_timeout` gives 2147483647 and
   `settings get global stay_on_while_plugged_in` gives 1. Leave it 10 minutes: the screen stays on.
5. **Portrait.** `adb shell settings get system accelerometer_rotation` gives 0,
   `settings get system user_rotation` gives 3, and `dumpsys window windows | grep mRotation=` gives
   `mRotation=3` (until 2026-11-17 this step expected rotation 0, which is landscape; plain
   `dumpsys window` doesn't print `mRotation` on Android 6).
   **Photograph the app on the glass**: upright, not sideways.
6. **HOME.** `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` opens
   the app (in the run above, the probe).

If any step fails, write down what it showed. The way back is the plain Glass Panes image.

## A community image's Wi-Fi (Glass Panes, 2026-11-19)

The maintainer asked (2026-11-19) for Kiln to set up Glass Panes' Wi-Fi too. Glass Panes has no
Improv, and its guide has you open Settings through scrcpy to switch Wi-Fi on and join
(`docs/mirror-app/install-android-lululemon-studio.md` in git-nimrod/glass-panes, the steps after
"5. Flash", read 2026-11-19).

1. **Where its userdata keeps Wi-Fi** (read 2026-11-19, the published `userdata.sparse`, SHA-256
   `74b1f57a…cf908` checked, with `@glassworks/ext4`, read-only). Its root has only `lost+found` and
   `misc`. `/misc` is 01771 1000:9998 and holds only `adb`. There is **no `/misc/wifi`**, so the Wi-Fi
   HAL copies the template at first start, as on Kiln's own userdata (point 5 above). Kiln writes
   the same file in the same place: `/misc/wifi/wpa_supplicant.conf`, 0660 1000:1010,
   `wifi_data_file`, in a `/misc/wifi` 0770 1010:1010.
2. **The template, from its own `system.img`** (verified 2026-11-19). Kiln reads
   `/etc/wifi/wpa_supplicant.conf` from the checked `system.img` where it lies (`sparseStore` in
   `@glassworks/ext4`: the chunk headers once, then only the blocks ext4 asks for). It took 160 ms
   for the 850 MB image on this Mac, and its lines are those of point 5. No bytes of it are in the repo.
3. **The real file, through Kiln's code** (verified 2026-11-19 with a made-up network:
   `frames/lululemon-mirror/installer/test/glass-panes-files.test.ts`, which runs only with
   `GP_DIR` set to the downloaded files, since they can't be committed; the exact commands and what came back are under "How the Glass Panes
   checks were run" below). `userdataWithWifi` took 5.3 s and grew the sparse file by 8 KiB.
   `e2fsck -fn` on both, expanded: clean, 14 → 16 inodes and 37,828 → 37,830 blocks (the folder and
   the file). The file had a 64-hex `psk=`, never the password.
4. **Wi-Fi stays off without help.** The image's SettingsProvider has `def_wifi_on` false (point 4).
   No *safe* userdata change can turn it on. A pre-made `settings_global.xml` (or `settings.db`) could
   hold `wifi_on=1`, but SettingsProvider then skips building every other default
   (`device_provisioned`, `user_setup_complete` and the rest), so it's ruled out for that reason
   (point 1 of "What we tried"). No property seeds `wifi_on` (point 2). That leaves a `system` change
   (an overlay, as Glassworks has) or a command after the first start. Changing Glass Panes' `system`
   would break "as its project publishes it".
5. **Wi-Fi on, with the image's own key.** Glass Panes publishes the private half of the one adb
   key its images trust (`mirror-app/android/project-key/adbkey`, PKCS#8 PEM, 1,704 bytes, SHA-256
   `89908fe2…38da2d` at commit `8ea31141`), and its guide has every user adopt it. The maintainer
   decided on 2026-11-19 that Kiln may use it for one `svc wifi enable` after the first start. Kiln
   fetches it through Refract (a pinned repository file), checks it, holds it in memory for that one
   connection, and never stores it. **Untested on a Mirror**: that `svc wifi enable` from the shell
   user switches Wi-Fi on here, and that the saved network then joins. Android 6 joined a pre-saved
   network without `networkHistory.txt` on Glassworks' image (2026-11-16, below), which is the same
   Android. The panel plan checks both.
6. **Since image 3 (2026-11-22): this browser's key, not the project key.** Image 3's framework lets
   in a key listed in `/data/misc/adb/adb_keys`, and its userdata lists the project key there
   ([image-patch.md](image-patch.md#image-3-a-new-constant-and-adb_keys), read). Its own installer
   swaps that for the user's key. Kiln does the same (the `adb-key` step: one file, owner, mode and
   label kept, verified offline on the real file 2026-11-22), puts the network on that copy, and runs
   `svc wifi enable` with this browser's key. The published key isn't downloaded any more. It also
   puts this browser's key in place of the one image 3's `services.jar` hardcodes (the `system-key`
   step), so the unpublished `A1:EC:…` key doesn't get in either. **Untested on a Mirror**: that
   image 3 lets in a listed key at first start.

### How the Glass Panes checks were run (2026-11-19, on the maintainer's Mac, read-only)

```sh
mkdir -p /tmp/gp && cd /tmp/gp
for f in system.img userdata.sparse; do
  curl -sfL -o $f https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/$f
done
shasum -a 256 system.img userdata.sparse
# bdb6b8445296666162256affa968d590075f56a8ad2229930f8502b9703e35c1  system.img
# 74b1f57ac20ce7c56065083a2bc3ddd283493be29d851fd04efc156ec96cf908  userdata.sparse

# userdata's layout, the template, and the network saved (writes /tmp/gp/with-wifi.sparse only)
cd <repo>/frames/lululemon-mirror/installer && GP_DIR=/tmp/gp npx vitest run test/glass-panes-files.test.ts
#  ✓ the template comes out of system.img in place, and the network goes into userdata as one file
#  (it asserts /misc holds adb and wifi, and the conf is 0660 1000:1010 wifi_data_file with a 64-hex
#   psk and no password; a first run with timings showed 160 ms for the template, 5.3 s for the save)

# the same, also expanding both to raw files (the test's expand(): readSparse, holes kept)
GP_DIR=/tmp/gp GP_RAW=1 npx vitest run test/glass-panes-files.test.ts   # writes /tmp/gp/before.raw, after.raw
# then, read-only:
/opt/homebrew/opt/e2fsprogs/sbin/e2fsck -fn /tmp/gp/before.raw
#  userdata: 14/341376 files (0.0% non-contiguous), 37828/1363149 blocks
/opt/homebrew/opt/e2fsprogs/sbin/e2fsck -fn /tmp/gp/after.raw
#  userdata: 16/341376 files (0.0% non-contiguous), 37830/1363149 blocks
```

On 2026-11-19 the expansion first ran from a scratch copy of the same `expand()`, then again through
the committed `GP_RAW=1` path: the same two result lines both times. Both `e2fsck` runs went through
passes 1 to 5 with nothing to fix. The first look at userdata's
layout (`/` holds `lost+found` and `misc`; `/misc` is 01771 1000:9998 with only `adb`) came from
`Ext4.readdir` and `stat` on the same file, which the test above now asserts.

## The backlight floor: a framework overlay (2026-11-24, corrected 2026-11-26)

**The problem.** The app's backlight-off sleep asks for a dark screen the way Android allows a window
to: `WindowManager.LayoutParams.screenBrightness = BRIGHTNESS_OVERRIDE_OFF` (`0f`). It keeps the
display powered (the signed kernel's TC358775 LVDS bridge comes back with lines across the panel after
a real screen-off, so turning the display off isn't an option). On the Mirror the backlight still
glowed faintly at that setting. Stock 1.24.0 goes fully black at the same request.

**How we got here.** The override reaches the backlight through two clamps in AOSP `android-6.0.1_r81`,
and the first version of this overlay (2026-11-24) only knew about the second:

1. `PowerManagerService.updateDisplayPowerStateLocked`
   (`services/core/java/com/android/server/power/PowerManagerService.java:1889-1910`) takes the window's
   override as the brightness, then clamps it to `[mScreenBrightnessSettingMinimum, mScreenBrightnessSettingMaximum]`.
   That minimum is `PowerManager.getMinimumScreenBrightnessSetting()`, i.e.
   `config_screenBrightnessSettingMinimum`. The same clamp holds the system slider's value.
2. `DisplayPowerController.clampScreenBrightness` (`services/core/java/com/android/server/display/DisplayPowerController.java:809`)
   clamps again, to `mScreenBrightnessRangeMinimum = min(config_screenBrightnessSettingMinimum,
   config_screenBrightnessDim, config_screenBrightnessDark)`.

The first version lowered `config_screenBrightnessDark` to 0. That drops the second floor to 0 but leaves
the first at 10, so the override still lands at 10/255. An Android 6 emulator showed it (below) before
any Mirror was flashed. So the value that matters is `config_screenBrightnessSettingMinimum`, which is
also what stock 1.24.0 sets to 0. We weighed:

| Path | Why not / why |
|---|---|
| The app writes the sysfs backlight (`/sys/class/leds/lcd-backlight/brightness`) | Needs root or `system` uid and fights `LightsService`, which writes it back on the next change |
| Lower `config_screenBrightnessDark` (the first version) | Feeds only DisplayPowerController's floor; PowerManagerService's clamp still holds 10. **Verified** on the emulator |
| Lower `config_screenBrightnessDim` | Changes what dimming does before a timeout, and PowerManagerService's clamp still holds 10 |
| **Lower `config_screenBrightnessSettingMinimum` to 0** | Both floors become 0, as on stock. The system slider can then reach 0 too; the app already handles that, because stock allows it (below) |

The slider reaching 0 was the reason the first version avoided this value: on a Mirror there's no
touchscreen to bring a black screen back. Glassworks already guards it, though, because stock allows it.
On a frame whose sleep is `SleepMode.BACKLIGHT_OFF` (the Mirror), `ScreenPolicy.awakeBrightness` lifts the
awake window to `VISIBLE_FLOOR` (0.1) whenever the system value is below it
(`core/src/main/java/dev/glassworks/screen/ScreenPolicy.kt`). So an awake Mirror is never darker than about 25/255.

The framework overlay is a static RRO in `/vendor/overlay`, like `GlassworksSettings.apk`, but
Android 6 loads one on `android` (framework-res) by its own path. The zygote's first system
AssetManager runs `verifySystemIdmaps()` (`core/jni/android_util_AssetManager.cpp:122`), which forks
`/system/bin/idmap --scan /vendor/overlay android /data/resource-cache /system/framework/framework-res.apk`.
`idmap_scan` (`cmds/idmap/scan.cpp`) keeps every APK whose `<overlay>` names `android`, writes
`/data/resource-cache/vendor@overlay@<name>.apk@idmap` (resources matched by type and name), and lists
them in `/data/resource-cache/overlays.list`, which `AssetManager::addSystemOverlays` reads
(`libs/androidfw/AssetManager.cpp`). It checks no signature. `idmap_scan` reads `targetPackage` with
`getAttributeStringValue`, which needs the attribute's raw string. `aapt2 dump xmltree` shows aapt2 keeps it
(`targetPackage="android" (Raw: "android")`).

**What the image has.** Read 2026-11-24 with `aapt2 dump resources` from a `framework-res.apk` pulled
off the dev unit on 2026-11-15 (outside the repo, an earlier Glass Panes image):

| Resource | Image | AOSP 6.0.1 default |
|---|---|---|
| `integer/config_screenBrightnessSettingMinimum` | 10 | 10 |
| `integer/config_screenBrightnessDim` | 10 | 10 |
| `integer/config_screenBrightnessDark` | 1 | 1 |
| `integer/config_screenBrightnessDoze` | 17 | 1 |
| `bool/config_automatic_brightness_available` | true | false |
| `array/config_autoBrightnessLcdBacklightValues` | 10 … 255 (8 values) | none |

So the override lands at `max(0, 10) = 10` in PowerManagerService. Image 3 has the same three values (**read** by the
maintainer's session; not re-read here). `config_screenBrightnessSettingMinimum` is an `integer` and a
`java-symbol` in AOSP 6.0.1's `core/res/res/values/symbols.xml`, so an overlay of that name and type maps onto it.

**The emulator run (2026-11-26, verified).** `system-images;android-23;default;arm64-v8a` (AOSP 6.0
framework-res, same defaults as the table), started with `-writable-system`. The overlay was pushed to
`/system/vendor/overlay/GlassworksFramework.apk` (0644 root, `system_file`, as Kiln writes it), then the emulator
rebooted. A throwaway test app (one activity that sets `screenBrightness` from an intent extra, not committed)
stood in for the app's sleep:

```sh
adb shell cat /data/resource-cache/overlays.list
# /vendor/overlay/GlassworksFramework.apk /data/resource-cache/vendor@overlay@GlassworksFramework.apk@idmap
adb shell idmap --inspect /data/resource-cache/vendor@overlay@GlassworksFramework.apk@idmap | tail -1
#              entry        0x00000000 integer/config_screenBrightnessSettingMinimum
adb shell 'settings put system screen_brightness 102; am start -n t.b/.A --ef v 0.0'
adb shell dumpsys power | grep -E 'SettingMinimum|OverrideFromWindowManager'
#   mScreenBrightnessSettingMinimum=0
#   mScreenBrightnessOverrideFromWindowManager=0
adb shell dumpsys display | grep -E '^  mScreenBrightness=|RangeMinimum'
#   mScreenBrightnessRangeMinimum=0
#   mScreenBrightness=0
```

| Overlay | Window override | `mScreenBrightness` |
|---|---|---|
| None (stock emulator) | 0 | 10 (PowerManagerService's floor) |
| First version, `config_screenBrightnessDark` 0 | 0 | 10: `RangeMinimum` went 1 → 0, but the request was already 10 |
| This version, `config_screenBrightnessSettingMinimum` 0 | 0 | **0** |
| This version | 0.1 (the app's visible floor) | 25 |
| This version | none (system value 102) | 102 |

**What Kiln writes.** `frames/lululemon-mirror/overlay/framework/` (`AndroidManifest.xml`,
`res/values/config.xml`): package `dev.glassworks.mirror.framework`, `targetPackage="android"`,
one resource, `config_screenBrightnessSettingMinimum` 0. `build.sh` builds it next to the settings overlay, the
same way (pinned build tools, v1 signing with AOSP's public test key); the settings overlay's bytes
don't change. Kiln pins it by SHA-256 (`FRAMEWORK_OVERLAY`), stages it as `mirror-framework-overlay`,
and `patchSystem` writes it to `/system/vendor/overlay/GlassworksFramework.apk`, 0644 root,
`system_file`. Only the Glassworks image gets it: the Glass Panes option's kiosk has no visible floor of its own.

```sh
ANDROID_HOME=~/Library/Android/sdk frames/lululemon-mirror/overlay/build.sh
# 4a8796d33b41da6a7ad44c065d57a5bfcad860794006d52b9977ff19f5f1c342  GlassworksSettings.apk  (unchanged)
# ea1c0b32fff5841adccaedffa6d9165d56edd0b37ebd4c169402318bfcf6f04f  GlassworksFramework.apk
aapt2 dump resources frames/lululemon-mirror/overlay/GlassworksFramework.apk
# Package name=dev.glassworks.mirror.framework id=7f
#   type integer id=01 entryCount=1
#     resource 0x7f010000 integer/config_screenBrightnessSettingMinimum
#       () 0
```

**How sure we are.** The two clamps: **read** (AOSP 6.0.1 source) and **verified** on the Android 6 emulator,
along with the overlay loading on `android` through `idmap --scan` and a window override of 0 reaching
brightness 0 (2026-11-26). The APK's contents: **verified** off-device (above, and `image.test.ts`). On the
Mirror itself: **untested**. Its USB link is broken, so no image could be flashed. What could still differ there is
a vendor change to PowerManagerService or the lights HAL treating 0 differently. What the root's run checks after a flash:

1. `ls -l /system/vendor/overlay/` lists both APKs, `/data/resource-cache/overlays.list` names
   `GlassworksFramework.apk`, and `idmap --inspect /data/resource-cache/vendor@overlay@GlassworksFramework.apk@idmap`
   maps `integer/config_screenBrightnessSettingMinimum`.
2. `dumpsys power | grep mScreenBrightnessSettingMinimum` and `dumpsys display | grep mScreenBrightnessRangeMinimum`
   both print 0 (were 10 and 1).
3. With the app's backlight-off sleep on, `cat /sys/class/leds/lcd-backlight/brightness` reads 0 and
   the glass is dark; waking brings the old brightness back. With the system slider all the way down, the
   awake screen stays visible (the app's floor, about 25).

## Open questions

- ~~Whether the overlay applies to SettingsProvider, which runs in the system process.~~ Yes
  (2026-11-16): `wifi_on=1`, the timeout and rotation came from it.
- ~~Whether Android 6 keeps a pre-saved network enabled for auto-join with no `networkHistory.txt`.~~
  Yes (2026-11-16): it joined at the first boot and after a reboot.
- Whether the framework overlay applies on the Mirror (idmap on `android`) and the backlight reaches 0.
  Verified on an Android 6 emulator (2026-11-26); untested on the Mirror. Steps above.
- Whether the Mirror's power supply counts as AC for `stay_on_while_plugged_in`. Not checked; the
  timeout keeps the screen on either way.
