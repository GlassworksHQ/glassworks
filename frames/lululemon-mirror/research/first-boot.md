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
| Portrait, no auto-rotate | Overlay: `def_accelerometer_rotation` false. Rotation stays 0, the natural orientation, which `ro.sf.hwrotation=270` makes portrait. **No `user_rotation` is needed** | Overlay, `build.prop` | Read. `ro.sf.hwrotation` turning the UI upright still needs a look; it did **not** turn the boot animation |
| Our app as HOME | Unchanged: `makeOnlyHome` hides the image's launchers only once our app answers HOME, and Kiln refuses an APK without one (#385) | | Verified with the probe app (below). The legacy build of Glassworks, Kiln's app since 2026-11-16, has not run on a Mirror yet |

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
| Rotation | `accelerometer_rotation=0`, portrait. The boot animation (#389) upright on the glass (the maintainer's photo) |
| Lock screen | `mShowingLockscreen=false` at the first boot, after sleep and wake, and after a reboot |
| Home | the probe's `.MainActivity`; Launcher3 hidden once it answered HOME |

The run also found that the first boot's Wi-Fi step logged the network's name. It now reads "Wi-Fi:
the network you entered", and tests check that no log line holds the name or the password.

## The root's test plan (as run above)

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
5. **Portrait.** `adb shell settings get system accelerometer_rotation` gives 0 and
   `dumpsys window | grep mRotation` gives 0. **Photograph the app on the glass**: upright, not sideways.
6. **HOME.** `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` opens
   the app (in the run above, the probe).

If any step fails, write down what it showed. The way back is the plain Glass Panes image.

## Open questions

- ~~Whether the overlay applies to SettingsProvider, which runs in the system process.~~ Yes
  (2026-11-16): `wifi_on=1`, the timeout and rotation came from it.
- ~~Whether Android 6 keeps a pre-saved network enabled for auto-join with no `networkHistory.txt`.~~
  Yes (2026-11-16): it joined at the first boot and after a reboot.
- Whether the Mirror's power supply counts as AC for `stay_on_while_plugged_in`. Not checked; the
  timeout keeps the screen on either way.
