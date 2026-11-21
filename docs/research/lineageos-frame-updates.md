---
topic: android
evidence: read
---

# LineageOS frames: OS updates from Home Assistant, and keeping our boot animation

**Status (2026-11-21): untested.** Nothing here has run on a device. It is a design read for a possible
future frame: a tablet that runs official LineageOS, where LineageOS keeps building, signing and serving
the OS updates and Glassworks only finds them, shows them in Home Assistant and starts the install. Every
claim is read from LineageOS 22.2 or AOSP source (links below), unless marked otherwise.

Device: none. Sources: LineageOS `lineage-22.2` branches of `android_packages_apps_Updater`,
`android_frameworks_base` and `android_vendor_lineage`, read 2026-11-21.

## Question

If a frame's base image is LineageOS (an old Samsung tablet, say), can it:

1. keep getting LineageOS's own OS updates, without us building or hosting them;
2. show those updates in Home Assistant, and install them from there;
3. not get in the way of our App and Frame updates, or they of it;
4. keep the Glassworks boot animation across every OS update?

## What we read

1. **How LineageOS's Updater finds a build** (read).
   `app/src/main/res/values/strings.xml:35`:
   ```
   <string name="updater_server_url" translatable="false">https://download.lineageos.org/api/v1/{device}/{type}/{incr}</string>
   ```
   `misc/Constants.java:41–50` names what fills it and what it compares:
   `ro.lineage.device`, `ro.lineage.releasetype`, `ro.build.version.incremental`, `ro.build.date.utc`,
   `ro.lineage.build.version`, `ro.build.ab_update`, and `lineage.updater.allow_major_upgrades` (major
   upgrades are off unless that is set). Glassworks can send the same request and compare the same
   build date. `Build.TIME` is `ro.build.date.utc` in milliseconds, readable by any app. **Untested:**
   whether an untrusted app can read the `ro.lineage.*` properties under SELinux; `Build.DEVICE` is the
   fallback for the codename.

2. **Who may install an OS update without a tap** (read).
   `DevicePolicyManagerService.installUpdateFromFile` (`lineage-22.2`, about line 20209) checks the
   caller with `Preconditions.checkCallAuthorization(... isDefaultDeviceOwner ... ||
   isProfileOwnerOfOrganizationOwnedDevice(caller))`. The public API is
   `DevicePolicyManager.installSystemUpdate(ComponentName admin, Uri, Executor, InstallSystemUpdateCallback)`,
   Android 10+, where `admin` is the device owner's `DeviceAdminReceiver`.
   It takes an OTA zip that the device's own keys must accept, so a LineageOS-signed zip from
   LineageOS's server qualifies. A/B devices install through `update_engine` in the background. Non-A/B
   devices copy the zip and reboot into recovery. Glassworks for generic Android already supports
   device-owner mode for hands-free app updates on Android 8–11, so this is the same setup.

3. **Where the boot animation comes from** (read).
   `cmds/bootanimation/BootAnimation.cpp`, `findBootAnimationFile()` (line 718) tries, in order:
   ```
   APEX_BOOTANIMATION_FILE, productBootanimationFile, OEM_BOOTANIMATION_FILE, SYSTEM_BOOTANIMATION_FILE
   ```
   that is `/apex/com.android.bootanimation/etc/`, `/product/media/`, `/oem/media/`, `/system/media/`.
   LineageOS's own animation is a `prebuilt_media` with `product_specific: true`
   (`android_vendor_lineage/bootanimation/Android.bp:50–53`), so it sits in `/product/media` and wins
   over `/oem` and `/system`. Ours has to replace the `/product/media` file. Putting it in `/oem` or
   `/system` won't do anything.

4. **How a file survives a LineageOS update: `addon.d`** (read).
   `config/common.mk:45–59` ships `backuptool.sh` (recovery, non-A/B) and `backuptool_ab.sh` with
   `backuptool_postinstall.sh` (A/B). Both run each `/system/addon.d/*.sh` script with backup and
   restore steps around the new system being written. Google apps use this to survive LineageOS
   updates. One guard matters (`backuptool.sh:45–56`): the restore runs only if the new
   `build.prop` has the same `ro.lineage.version` major and minor. Monthly builds keep it, while a
   major upgrade (21 to 22) skips every `addon.d` script.

## Result

| Need | How | Sure? |
|---|---|---|
| Find an OS update | Glassworks sends the Updater's request to `download.lineageos.org` and compares `ro.build.date.utc` | Read; the property access is untested |
| Show it in HA | An **OS update** entity next to App update and Frame update, with the installed and latest build, size, and LineageOS's changelog link | Design |
| Install, Glassworks as device owner | Download the signed zip, `installSystemUpdate`, then restart. Non-A/B means 5–10 minutes in recovery | Read |
| Install, Glassworks as a normal app | Open LineageOS's Updater on the panel, and someone taps Install | Read |
| Our app and settings | Live in `/data`, which an OS update doesn't touch | Read |
| Our boot animation | An `addon.d` script that Kiln installs once. It backs up and restores `/product/media/bootanimation.zip`, so the first boot after an update already shows ours | Read; untested on any device |
| Major upgrades (21 to 22) | Not offered by default, and `addon.d` skips them. HA shows "a new major version is available", and the upgrade is a manual step | Read |
| The bootloader's own logo | A separate bootloader area that LineageOS updates don't write. If Kiln replaces it once, it stays | Untested; depends on the device |

**What conflicts with this:** anything a frame changes in the OS itself, such as a Magisk-patched boot
image like the S1's. On non-A/B devices an OS update replaces the boot image, which removes the patch.
Such a frame would need its agent to re-apply the patch after an update, or it would have to block OS updates.
A frame that only runs the app, plus the `addon.d` animation, has no conflict.

**Licences:** this route ships no OS bytes, so hard rules 3 and 4 aren't touched. Kiln installs the
image the user downloads from LineageOS, and the `addon.d` script and animation are ours.

**Fallback for the animation:** if `addon.d` can't write on a device (read-only system images such as
EROFS), copying the file back doesn't work either: root can't write a read-only filesystem. What works
there is a bind mount of our zip from `/data` over `/product/media/bootanimation.zip` early in every boot,
which is how the S1 tried its animation (`frames/aqara-s1-plus/research/boot-animation.md`). That needs
root and a hook that makes the mount before `bootanimation` starts, on every boot: a bind mount doesn't
survive a reboot, and one made after the animation starts doesn't change what plays. The S1 test also
logged a SELinux `mounton` denial and only worked because SELinux was permissive there, so under
LineageOS's enforcing policy the hook needs a policy rule or a context that allows it. Untested on
LineageOS. A Magisk module that overlays the file would also work, but on non-A/B
devices it goes away with Magisk at the first update, so it's the weakest choice.

## How to reproduce

Nothing to run yet. The first device test, in order, on a spare LineageOS tablet:

1. Read-only: `adb shell getprop ro.lineage.device`, then the same from an app process (`Build.DEVICE`,
   `SystemProperties`), to confirm what an app may read.
2. Read-only: fetch `https://download.lineageos.org/api/v1/<device>/<type>/<incr>` and compare its
   `datetime` with `ro.build.date.utc`.
3. Writes storage: make a test app the device owner (`dpm set-device-owner`, no accounts on the
   device), call `installSystemUpdate` with the latest LineageOS zip, and time the restart. Undo:
   `dpm remove-active-admin` on a test-only admin, or a factory reset.
4. Writes `/system`: only on a device where `adb remount` writes the partitions in place. On devices
   with dynamic partitions, `adb remount` sets up OverlayFS instead, `update_engine` may refuse an OTA
   while it is active, and `adb enable-verity` to clear it also drops the test files, so the test can't
   run there. On a device where it writes in place: with "Rooted debugging", `adb root`, then `adb disable-verity`
   and a reboot if verity is on (the remount fails until then), then `adb remount`,
   add a test `addon.d` script that backs up and restores `/product/media/bootanimation.zip` plus a
   replaced zip, and install the next monthly update. Undo: delete the script, restore the original
   zip, and `adb enable-verity` if you disabled it.

## Open questions

- Does `backuptool_ab.sh` restore files in `/product` on devices with a separate `product` partition,
  or only `/system`? The script lists paths relative to `$S`. Check `backuptool_ab.functions`.
- Which LineageOS builds ship read-only system images (EROFS) or remount through OverlayFS, and how does
  Kiln put an `addon.d` script in place there (recovery, or not at all)?
- Does LineageOS's Updater honour a device owner's `setSystemUpdatePolicy` (a maintenance window), or
  do we schedule installs only from Glassworks?

## Sources

- LineageOS Updater:
  [`strings.xml`](https://github.com/LineageOS/android_packages_apps_Updater/blob/lineage-22.2/app/src/main/res/values/strings.xml),
  [`Constants.java`](https://github.com/LineageOS/android_packages_apps_Updater/blob/lineage-22.2/app/src/main/java/org/lineageos/updater/misc/Constants.java)
- Boot animation:
  [`BootAnimation.cpp`](https://github.com/LineageOS/android_frameworks_base/blob/lineage-22.2/cmds/bootanimation/BootAnimation.cpp),
  [`bootanimation/Android.bp`](https://github.com/LineageOS/android_vendor_lineage/blob/lineage-22.2/bootanimation/Android.bp)
- `addon.d`:
  [`backuptool.sh`](https://github.com/LineageOS/android_vendor_lineage/blob/lineage-22.2/prebuilt/common/bin/backuptool.sh),
  [`config/common.mk`](https://github.com/LineageOS/android_vendor_lineage/blob/lineage-22.2/config/common.mk)
- Device owner install:
  [`DevicePolicyManager.installSystemUpdate`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#installSystemUpdate(android.content.ComponentName,%20android.net.Uri,%20java.util.concurrent.Executor,%20android.app.admin.DevicePolicyManager.InstallSystemUpdateCallback))
