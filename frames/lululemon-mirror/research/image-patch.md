---
topic: install
evidence: verified
---

# Mirror images: what Kiln changes in the community system image, and what a fresh userdata needs

**Status (2026-11-23): flashed on a real Mirror end to end, with image 1 (2026-11-16), image 2 (2026-11-17) and image 3 from kiln.glassworks.dev (2026-11-23).**
Image 2's fully dark sleep is still to check. Kiln patches the Glass Panes
`system.img` and builds a new `userdata` (`frames/lululemon-mirror/installer/src/image.ts`). The
root's first real flash (below) booted it. Its adb came up, but **not through the key Kiln wrote**: the
image lets in one published key by itself, and its adbd ignores `adb_keys`
([Who the image lets in over adb](#who-the-image-lets-in-over-adb), 2026-11-16). Since then Kiln
patches `services.jar` to let in the installer's own key instead: on image 2 the Mirror let in only a throwaway key
(2026-11-17, #443's run). `ro.sf.hwrotation` turns
**neither the boot animation**, which rendered sideways, **nor Android's UI**: nothing on the image reads it, and
the first run with the real app came up landscape ([The rotation](#the-rotation-2026-11-17), 2026-11-17). Kiln
no longer sets it; the first boot locks `user_rotation=3` instead. The frames are now turned
themselves (#389), and on 2026-11-16 the turned animation ran upright on the glass (the maintainer's
photo; [first-boot.md](first-boot.md)). The S1 Kiosk build it carried can't be the home screen on Android 6, so a usable
Mirror still waits on an app that runs on API 23. Since 2026-11-16 Kiln installs the legacy build of
Glassworks for generic Android instead ([The app](#the-app-the-legacy-build-of-glassworks)). It runs on the Mirror:
the only home screen, upright, with no crashes (2026-11-17, #443's run).

**2026-11-22: Kiln pins image 3 (`mirror-android-3`)** ([Image 3](#image-3-mirror-android-3-2026-11-22)).
Read from its files and checked offline against the real image.

**2026-11-23: image 3 installed end to end on the Mirror from kiln.glassworks.dev (verified; the maintainer's
run, reported in the V1 status pass).** The deployed Kiln (main) took the Mirror through the whole install and the
standard setup to Done, with nothing done on the Mirror's own screen. Seen with Glassworks after it: the Glassworks
boot animation (after lululemon's bootloader logo, which stays), Glassworks as the only home screen with no vendor
screens, back after a reboot, the screen sleeping and waking, and an app update installed from Home Assistant with
no click on the Mirror. The setup hotspot had passed on the same unit that day (#197 part M). The run's step-by-step
log wasn't recorded here; `frame.json`'s `facts` now come from it (`panelRuns`, nothing `unverified`).

Device: lululemon Mirror (MSM8916), community image Glass Panes `mirror-android-1` (the real flash
below), and since 2026-11-16 `mirror-android-2` (below; on the unit since 2026-11-17), and since
2026-11-22 `mirror-android-3` in Kiln (below; flashed on the unit from Kiln on 2026-11-23) (Android 6.0.1,
`userdebug`, `test-keys`), stock signed `boot` (`IFC-6309-2.0-MIR.S22Q401`). Tools:
`@glassworks/ext4` (#353), e2fsprogs 1.47.4.

## Question

Option A (#40): a locked stock Mirror takes only `system` and `userdata` over fastboot, and has no
touchscreen. What must `system` gain, and what must a fresh `userdata` hold, so that the first boot
comes up as Glassworks and lets Kiln in over adb with its own key, with no shared key?

## What we read

1. **The image itself** (2026-11-15, verified: `system.img` downloaded for the test only, SHA-256
   `d13dd330…83ed5` as published, deleted after). Read with `@glassworks/ext4`:
   - Sparse v1.0, 4096-byte blocks, 314,554 blocks (1,288,413,184 bytes; the real Mirror's partition is
     19 blocks bigger, see below), 2,959
     chunks, 207,732 blocks of data, **no FILL chunks**. Its ext4 has 312,086 blocks: the last
     2,468 blocks of the image are outside the filesystem, and Kiln keeps whatever the image has
     there.
   - ext4 as `make_ext4fs` makes it: `has_journal ext_attr resize_inode filetype extent
     sparse_super large_file uninit_bg`, 256-byte inodes, 10 groups, 102,035 blocks free.
   - Every file is `root:root` with `u:object_r:system_file:s0`, in the inode.
   - `/app` (57) and `/priv-app` (40) don't include the community kiosk: no
     `com.nimrod.mirrorkiosk` in `system`.
   - Bundled apps with native code keep it next to the APK: `/priv-app/DefaultContainerService/lib/arm64/`,
     `/priv-app/FMRadio/lib/arm64/`.
   - `/media` holds only `audio`: no `bootanimation.zip`.
   - `build.prop`: `ro.build.fingerprint=Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11041605:userdebug/test-keys`,
     `persist.sys.usb.config=diag,serial_smd,rmnet_bam,adb`, no `ro.sf.hwrotation`.
   - `e2fsck -fn` on the unmodified image: `Padding at end of inode bitmap is not set`, and nothing
     else. That's a known `make_ext4fs` quirk the kernel ignores.

2. **The maintainer's Mirror, read-only adb** (2026-11-15, verified; serial left out). Each read
   is `adb shell <command>` exactly as written in the left column (for a file, `cat <path>`, with
   `grep -n` for one line). The right column quotes the output, cut to the lines that matter, and
   leaves out anything device-unique:

   | Read | Result |
   |---|---|
   | `pm path com.nimrod.mirrorkiosk`; `dumpsys package` | `/data/app/com.nimrod.mirrorkiosk-2`, no `SYSTEM` flag: it lives in `userdata` only |
   | `getprop ro.adb.secure`; `/default.prop` | `1`; the stock ramdisk sets `persist.sys.usb.config=none` |
   | `/system/build.prop:174` | `persist.sys.usb.config=…,adb`: loaded after `/default.prop`, so adb stays on with an empty `userdata` |
   | `/file_contexts` (0644, readable) | `/data(/.*)?` `system_data_file`, `/data/misc/adb(/.*)?` `adb_keys_file`, `/system(/.*)?` `system_file` |
   | `getprop ro.crypto.state` | `unencrypted`. `fstab.qcom` is 0640 root, unreadable |
   | `pm list packages` | `com.android.provision` present. Whether a fresh `userdata` then finishes setup by itself is **untested** until the first real flash |
   | `wm size`; `dumpsys display`; `dumpsys window` | Physical 1920×1080, base rotation 0, `ro.sf.hwrotation` unset; `mUserRotation=3`, locked (`user_rotation=3`, `accelerometer_rotation=0`), kept in `userdata`'s settings |

3. **The Glass Panes `userdata.sparse`** (2026-11-15, read: its first 2 MiB only). Its ext4 fills
   the whole partition, 1,363,149 blocks (5,583,458,304 bytes), so there is no crypto footer at the
   end. The release notes say the project's shared adb key is in it. The image's `services.jar`
   lets the same key in too ([below](#who-the-image-lets-in-over-adb)).

## Result

### `system`: what Kiln changes

| Change | Why | How sure |
|---|---|---|
| `/priv-app/Glassworks/Glassworks.apk` (until 2026-11-16 `/priv-app/S1Kiosk/S1Kiosk.apk`, which the probe still uses), `root:root` 0644, `system_file`; its directory 0755 | A privileged system app: Android 6 grants privileged permissions (`WRITE_SECURE_SETTINGS`, `CHANGE_CONFIGURATION`) to `/system/priv-app` apps with no whitelist (that came in Android 8). It survives a factory reset, and the app's own updates install over it in `/data/app` | Read (AOSP 6 `PackageManagerService`). On the unit (2026-11-16) the APK was in `/system/priv-app/S1Kiosk`; the probe there got its privileged permissions ([privileged-app.md](privileged-app.md)) |
| Its native libraries in `/priv-app/Glassworks/lib/arm64/` (or `lib/arm/`) | Android 6 doesn't unpack a bundled app's libraries: it looks for `lib/<abi>` next to the APK, as the image's own priv-apps have them. Only the primary ABI the APK has is unpacked (arm64-v8a first) | Read (the image's layout, AOSP 6 `setBundledAppAbisAndRoots`); untested |
| `/media/bootanimation.zip` (PR 5 makes it) | `bootanimation` reads `/system/media/bootanimation.zip` when there's no `/oem/media` one, and the Mirror has none. **Corrected 2026-11-25:** an earlier version also named `/data/local`; stock 1.24.0's `bootanimation` has no such path (`strings` over its system image, [stock-inplace-parity.md](stock-inplace-parity.md)) | Read |
| `build.prop`: `ro.glassworks.frame.id=lululemon-mirror` and `ro.glassworks.frame.version` (`MIRROR_FRAME` in `installer/src/image.ts`) | The frame version the app shows on the Device page and compares with the newest `frames/lululemon-mirror/v*` release (#139; the tags are in docs/releasing.md, *Frame releases*). A Mirror flashed before has neither, and the app names its image from `ro.build.fingerprint` | Off-device test (2026-11-18); not yet on a Mirror |
| `build.prop`: `ro.glassworks.device.mac` (#523), from `fastboot getvar serialno` with the app's own formula (`macFromSerial` in `installer/src/image.ts`, `PanelDevice.macFromSerial` in the core) | The app's Home Assistant identity when its data is new. A flash wipes userdata, and with it the MAC the app had saved, so before this every reflash made a new HA device | Off-device tests (2026-11-18). That LK answers `serialno`: read (Qualcomm LK publishes it), untested on this unit. Panel run pending |
| `build.prop`: `ro.glassworks.api_key.handover=1` (#574), only when the headless flash read an ESPHome API key from the running app before the flash. A flag, never the key | The flash wipes the key Home Assistant set, and HA, which still holds it, opens a re-authentication when the fresh app answers with no key (the root's run on 2026-11-18). On the flag the fresh app keeps its API server shut until the flash hands the key back at the first boot (up to 15 minutes), so HA never meets it keyless ([esphome.md](../../../docs/esphome.md#identity-across-reinstalls)). A rebuild without a key drops the flag | Off-device tests (2026-11-19). Panel run pending: one reflash with an app that has the key socket |
| ~~`build.prop`: `ro.sf.hwrotation=270`~~ **Dropped 2026-11-17**: nothing on the image reads it, and the UI stayed landscape with it ([below](#the-rotation-2026-11-17)). The first boot sets `user_rotation=3` over adb instead (`setPortrait`) | The panel is landscape (1920×1080), and the Mirror stands portrait. The stock portrait comes from `user_rotation=3`, locked, which lives in `userdata`, so a fresh `userdata` comes up landscape. The property was meant to turn Android's UI; on this image it turns nothing. The boot animation draws straight to the panel, so its frames are turned themselves (`boot.rotate`) | **Corrected 2026-11-17.** The property turns neither the boot animation (sideways on the first real flash, 2026-11-16, the maintainer's photo) nor the UI (landscape with the real app, 2026-11-17). An earlier version said the UI was portrait with it: that was the probe app asking for portrait itself. The turned boot animation is upright on the glass (2026-11-16, photo). The UI upright at `user_rotation=3`, set by Kiln's first boot: **verified** 2026-11-17 (the maintainer, on the glass) |
| `/framework/services.jar`: `UsbDebuggingManager` lets in the installer's key (the first `adbKey` line) in place of the key the image names (`IMAGE_OWNER_FINGERPRINT`); same mode, owner and label | The image's own way in over adb is a constant. Image 2's named the published project key, and its adbd ignored `adb_keys` ([below](#who-the-image-lets-in-over-adb)). Image 3's names `A1:EC:…`, a key that isn't published, and its framework also lets in `adb_keys` ([below](#image-3-a-new-constant-and-adb_keys)) | Verified offline: image 2 patched (2026-11-16), `dexdump` verifies the result and prints the same code; image 3 patched (2026-11-22, the real-image test). On the unit: the 2026-11-23 Kiln install from kiln.glassworks.dev got in over adb with the browser's key and finished (verified, [above](#mirror-images-what-kiln-changes-in-the-community-system-image-and-what-a-fresh-userdata-needs)) |
| `/priv-app/MirrorKiosk` removed (`IMAGE_KIOSK`), image 3 only | Glass Panes' own kiosk app, a persistent home app in image 3's `system`: kept, it would run beside Glassworks ([below](#image-3-a-new-constant-and-adb_keys)) | Read and left out by the real-image test (2026-11-22). On the unit (2026-11-23 Kiln install): Glassworks was the only home screen, with no other kiosk showing (verified). Whether its removal leaves anything behind that expects it is unchecked |
| 36 app folders removed from `/app` and `/priv-app` (`installer/src/unused.ts`, `UNUSED_APPS`): phone and SMS front ends, Qualcomm diagnostics, modem settings, RCS and tethering, demo apps and wallpapers, Bluetooth and ANT test tools. The phone stack (`TeleService`, `TelephonyProvider`, `Telecom`), the contacts provider, the keyboard, the display's post-processing apps (`PPPreference`, `SVIService`, `CABLService`, `colorservice`), WebView, Bluetooth, Settings and SystemUI stay (`KEPT_APPS`) | Under memory pressure the Qualcomm services died and restarted 53 to 55 times each in 43 minutes while the Mirror ran at "critical" (2026-11-18, the memory note in #519). The root hid the same set with `pm hide` on the unit (`pm disable-user` is refused from adb's shell on this image), and free RAM went from about 60 MB to 205 MB (one sample). The way back is the plain image | Folders read from image 2 with `tools/mirror/list-apps.ts` (2026-11-18); the real-image test checks each folder's APK names the listed package. Image 3 already lacks 24 of them, so 12 are removed there (2026-11-22). The removed image boots: **verified** on the unit in the 2026-11-23 Kiln install. Image 2's community kiosk was never in `system` (read 1); image 3's is (the row above) |

The patcher refuses, with nothing written, an image whose fingerprint isn't the one above, and an
APK that isn't the app the frame names. A second run gives the same image.

### The app: the legacy build of Glassworks

Since 2026-11-16 (#198) the app is the legacy build of Glassworks for generic Android, named in the
frame's data (`design/frame.json`, `app`):

| | |
|---|---|
| Package | `dev.glassworks.generic_android.legacy` (minSdk 23, GeckoView 144) |
| What adb starts | `dev.glassworks.MainActivity` (`sh.edm.s1kiosk.MainActivity` before #527). It takes `WRITE_SECURE_SETTINGS`, which adb's shell holds, so only adb's start can set the Home Assistant address through extras |
| HOME | the alias `dev.glassworks.generic.Launcher` (MAIN, HOME and DEFAULT). Android records the alias as the preferred home, and shows MainActivity as focused, so Kiln matches HOME by package |
| Folder | `/system/priv-app/Glassworks`. Android 6 keys a package by its manifest, not its folder, so the folder carries the product's name. The probe keeps `/priv-app/S1Kiosk`, where it was verified; Kiln removes both before it writes one, so an image has one app |

Kiln refuses, before anything is written, an APK that isn't this package (S1 Kiosk's
`sh.edm.s1kiosk` included), and one that doesn't ask for `INSTALL_PACKAGES`, `REBOOT` and
`WRITE_SECURE_SETTINGS`: Android 6 grants a privileged permission only to what the `/system` copy
asks for, and an update can't add one ([privileged-app.md](privileged-app.md)). The harness flashes
the probe (`dev.glassworks.mirror.probe`; `sh.edm.s1kiosk` before #527) only with `--probe`. **Read** (the #198 manifest); untested on a Mirror.

### `userdata`: what a fresh one needs

A new, empty ext4 the size of the partition, with only this:

| Path | Owner, mode | Label | Why |
|---|---|---|---|
| `/` | `system:system` 0771 | `system_data_file` | As `init.rc` sets it on `post-fs-data` |
| `/misc` | `system:misc` 01771 | `system_data_file` | `init.rc`: `mkdir /data/misc 01771 system misc` |
| `/misc/adb` | `system:shell` 02750 | `adb_keys_file` | `init.rc`: `mkdir /data/misc/adb 02750 system shell` |
| `/misc/adb/adb_keys` | `system:shell` 0640 | `adb_keys_file` | AOSP 6's `adbd` takes keys from `/adb_keys` (the signed ramdisk: out of reach) and `/data/misc/adb/adb_keys`. **Image 2's adbd ignores the file** (verified 2026-11-16, cause unknown, [below](#who-the-image-lets-in-over-adb)); image 3's framework lets in the keys it lists (read, [below](#image-3-a-new-constant-and-adb_keys)). Kiln writes this browser's key there: on image 3 that is a second way in for the same key, and Android appends to it itself |
| `/lost+found` | `root:root` 0770 | `system_data_file` | e2fsck expects it |

Everything else is made by init on the first boot (`mkdir` sets owner and mode even on a directory
that exists), and `restorecon_recursive /data` relabels anything mislabelled. On image 2 the key in it doesn't
let anyone in: `services.jar` does that (above). On image 3 it does, and it is this browser's key.

What a fresh `userdata` loses: the community kiosk (good), the shared key in its `adb_keys` (which
image 2's adbd ignores, and which image 3 would let in), the Mirror's
Wi-Fi, and the screen rotation (`user_rotation=3`, which the first boot sets again over adb, [below](#the-rotation-2026-11-17)). After the first boot, Kiln
gives the app the Wi-Fi (over its Improv socket) and the Home Assistant URL over adb, as it does on
the S1.

### The first boot: our app as the only home screen

The image ships Launcher3 (`com.android.launcher3`) as a home app, and the Mirror has no touchscreen
to answer Android's chooser. adb's shell can't disable another app on Android 6, but it holds
`MANAGE_USERS` (read 2026-11-15, [home-app.md](home-app.md)), which `pm hide` needs. So on the first
boot Kiln runs `pm hide com.android.launcher3` (and `com.cyanogenmod.trebuchet` if present), and our
app is the only home app left: Android opens it with no chooser. **Verified** on the unit in the 2026-11-23 Kiln
install from kiln.glassworks.dev: Glassworks came up as the only home screen, with no chooser. The way
back (the plain Glass Panes image) brings the launcher back, because it comes with a fresh userdata.

Kiln then checks what HOME opened (`dumpsys activity activities`, `mFocusedActivity`). If it isn't
our app (the chooser, or a launcher that survived `pm hide`), Done says so plainly. Without a
touchscreen, the Mirror would wait on that chooser after a restart. Done offers "Hide the other
launcher again", or a USB mouse to choose our app, then Always.

With a key read before the flash (#574, headless flash only), the first boot hands it back right after it starts the
app, on the app's adb-only socket `@glassworks_api_key`, before the home screen and the Wi-Fi. Kiln's page reaches the
Mirror only in fastboot before the flash, so it has no key to hand back. **Untested** on the unit.

The boot logo before Android stays lululemon's: it lives in the signed `aboot`/splash path that the
locked bootloader checks, and option A writes only `system` and `userdata`.

## Image 2 (`mirror-android-2`, 2026-11-16)

Kiln pins Glass Panes `mirror-android-2` (published 2026-11-16) from #29's follow-up. Read
2026-11-16 from GitHub's release API and the image itself (downloaded for the test only, deleted
after):

| | Image 1 | Image 2 |
|---|---|---|
| `system.img` | 850,905,808 bytes, `d13dd330…83ed5` | 850,905,808 bytes, `bdb6b844…e35c1` |
| `userdata.sparse` | 154,944,572 bytes, `74b1f57a…cf908` | the same file |
| `SHA256SUMS` | `74c4766d…3b4a4` | `c3cbc377…4d02a` |
| `ro.build.fingerprint` | `…/MMB29M/11041605:userdebug/test-keys` | `…/MMB29M/11120023:userdebug/test-keys` |
| What changed (release notes) | | "Fully dark sleep": the panel switches off completely in sleep |

`GW_MIRROR_SYSTEM_IMG=<image 2> npm test -w @glassworks/lululemon-mirror-installer` passed on
2026-11-16 in 25 s: the hash matches the pin, the patch applies, and e2fsck finds nothing in the
flashed result that the original doesn't have. The patch's checks read the fingerprint, so image 1
is refused from now on, and the way back (`checkDownload`) takes image 2's `system.img`.

## Image 3 (`mirror-android-3`, 2026-11-22)

Kiln pins Glass Panes `mirror-android-3` (published 2026-11-22), for Glassworks and for the Glass Panes
option. Read 2026-11-22 from GitHub's release API and the files (downloaded to `/tmp` for the
tests only, deleted after). Commands:

```sh
gh release view mirror-android-3 -R git-nimrod/glass-panes --json assets -q '.assets[]|"\(.name) \(.size) \(.digest)"'
npx -y tsx@4.23.15 tools/mirror/image-facts.ts system.img userdata.sparse   # read-only
npx -y tsx@4.23.15 tools/mirror/list-apps.ts system.img                     # read-only
dexdump -d services.dex                                                     # build-tools 37, on the jar's classes.dex
```

| | Image 2 | Image 3 |
|---|---|---|
| `system.img` | 850,905,808 bytes, `bdb6b844…e35c1` | 1,074,051,108 bytes, `bd7f2e33…ec19` (same 314,554 blocks of 4 KiB) |
| `userdata.sparse` | 154,944,572 bytes, `74b1f57a…cf908` | 154,952,764 bytes, `81eb5648…c521d` (same 1,363,149 blocks) |
| `SHA256SUMS` | `c3cbc377…4d02a` | `cf9660a2…7bd87` |
| `ro.build.fingerprint` | `…/MMB29M/11120023:userdebug/test-keys` | `…/MMB29M/11212248:userdebug/test-keys` |
| `build.prop` otherwise | | `service.adb.tcp.port=-1` gone; network adb is now `persist.adb.tcp.port` in userdata (`/property/persist.adb.tcp.port` = `0000 glass-panes-network-adb`, off) |
| Their kiosk app | in userdata, installed by hand | `/priv-app/MirrorKiosk` (`com.nimrod.mirrorkiosk`), `android:persistent`, a HOME app, with its GeckoView and TFLite libraries in `lib/arm64` |
| `services.jar` | the owner constant is the project key | a new owner constant, an `adb_keys` path, and default grants for the kiosk ([below](#image-3-a-new-constant-and-adb_keys)) |
| Apps | | 33 folders fewer: 24 of Kiln's 36 `UNUSED_APPS`, `/priv-app/Launcher3` (Trebuchet stays, also `com.android.launcher3`), `PicoTts`, `PrintSpooler`, `HTMLViewer`, the dreams and live wallpapers, `OpenWnn` |
| Overlays | none in `/vendor/overlay` | the same: Kiln's settings overlay is still the only one |

What Kiln changes for it (read, then checked offline):

- `patchSystem` refuses image 2 now (the fingerprint), and leaves out `/priv-app/MirrorKiosk`
  (`IMAGE_KIOSK`): kept, it would run beside Glassworks as a persistent second home app. Its
  default grants in `services.jar` name its package only, so they grant nothing once it's gone.
- `UNUSED_APPS` keeps all 36: `patchSystem` skips a folder that isn't there. 12 are still in image 3.
- Nothing in the Glassworks app needs the removed `PicoTts` (no `TextToSpeech` use, grep 2026-11-22).
- `GW_MIRROR_SYSTEM_IMG=<image 3> npx vitest run test/image.test.ts` passed on 2026-11-22 in 30 s: the
  hash, the 12 unused apps and the kiosk read and left out, `services.jar` retargeted, and e2fsck finds
  nothing in the flashed result that the original doesn't have. `GP_DIR=<image 3> npx vitest run
  test/glass-panes-files.test.ts` passed: Kiln's key in the release's userdata, then the network,
  one file each, `adb_keys` keeping 0640 1000:2000 `adb_keys_file`; `e2fsck -fn` clean before and
  after (16 → 18 inodes).

- The kiosk is the only thing of Glass Panes' own in image 3's `system` (read 2026-11-22): every APK
  in `/app` and `/priv-app` read by package name, and every file under 4 MiB that isn't an APK, jar,
  oat or library searched for `nimrod`, `mirrorkiosk` and `glass panes`. Besides
  `/priv-app/MirrorKiosk`, the only Glass Panes name is `com.nimrod.mirrorkiosk` in `services.jar`'s
  default grants (above). The other non-AOSP apps are stock: `com.dsi.ant.server` (ANT HAL) and
  `com.gd.mobicore.pa` (Trustonic's RootPA). No init script or binary is named for it.
- The Glass Panes option gets one change to `system` (the `system-key` step, `systemWithKey` in
  `installer/src/wifi.ts`): `services.jar` with this browser's fingerprint in place of `A1:EC:…`, by the
  same string move as Glassworks', nothing else. `GP_DIR=<image 3> GP_RAW=1 npx vitest run
  test/glass-panes-files.test.ts` passed on 2026-11-22 (36 s for both tests): the dex names this key
  and not `A1:EC:…`, `services.jar` keeps its mode, owner and label, `build.prop` and the kiosk are as
  released; `e2fsck -fn` on the image before and after reports the same one finding (the stock
  image's "Padding at end of inode bitmap is not set"), 23 blocks more in use (the re-deflated jar).

Not checked: whether the kiosk's removal leaves anything behind that expects it (a receiver, a job).
**Untested.** The Glassworks flash on a unit is covered by the 2026-11-23 entry at the top; a Glass Panes
(way back) flash of image 3 on a unit isn't.

**Room for the app (verified 2026-11-23, the root, offline).** The first Kiln run on a unit with image 3
stopped in the build: `no room for /priv-app/Glassworks/lib/arm64/libxul.so: it needs up to 35906 blocks,
18451 are free`. The `GW_MIRROR_SYSTEM_IMG` test above patches with a small fake APK, so it never saw this.
That test now takes a real APK too (`GW_MIRROR_APK`). Read on the real image 3 and the real legacy 0.3.0 arm64
APK (96,035,319 bytes; its 13 arm64 libraries unpack to 170 MB, `libxul.so` alone 147 MB):

```sh
curl -sSfLO https://refract.glassworks.dev/glass-panes/mirror-android-3/system.img
curl -sSfLO https://refract.glassworks.dev/glassworks/generic-android-legacy-v0.3.0/glassworks-generic-android-legacy-arm64-v8a-0.3.0.apk
unzip -lv glassworks-generic-android-legacy-arm64-v8a-0.3.0.apk 'lib/arm64-v8a/*'   # unpacked sizes
cd frames/lululemon-mirror/installer
GW_MIRROR_SYSTEM_IMG=<system.img> GW_MIRROR_APK=<the APK> npx vitest run test/image.test.ts   # e2fsprogs on PATH; ~45 s
```

The test prints the free blocks after the patch (`free after the patch: …`). The rows before it were read with
`Ext4.freeBlocks` from `@glassworks/ext4` after each `fs.remove` of the same folders, in a one-off script
that wasn't kept. Without the fix, the same test stops at `libxul.so` with the error above.

| | Free blocks (4 KiB) |
|---|---|
| Image 3 as released | 47,556 (186 MB) |
| `/priv-app/MirrorKiosk` left out | +63,091 (246 MB: its own GeckoView) |
| The 12 unused apps left out | +6,462 |
| After the whole patch (app and libraries, boot animation, settings overlay in) | 53,154 (208 MB), the test above |

`patchSystem` wrote the app before it left anything out, so the kiosk still held its 246 MB when the
libraries went in. It now leaves out the kiosk and the unused apps first. Image 2 had no kiosk in
`system` and about 437 MB free, so the order never mattered before.

## The first real flash (2026-11-16, verified by the root)

The root ran the install headless with `tools/mirror/flash-node.ts` (#370), the same installer
code as Kiln's page, on the maintainer's Mirror. The Mirror was left on the patched image (a dev
unit); the way back is ready.

| Step | What happened |
|---|---|
| Fastboot checks | `partition-size:system` is **1,288,491,008** bytes: 314,573 blocks, 19 more than the image. The exact-size check refused it, so the check now takes a partition at least the image's size (#357) |
| Flash `system` | Sent in 5 pieces (split to `max-download-size`), then `userdata` in 1. The first try stopped with "Cancelled" at about 20 %: the Node `usb` package gives every transfer a 1 s timeout. The harness now gives each transfer 120 s (#370). Kiln's page runs on Chrome's WebUSB, which has no such timeout |
| Restart | Android started the patched image. adb came up with no prompt. **Confounded** (2026-11-16): the root's computer carries the Glass Panes project key, which the image's `services.jar` lets in by itself, and this image's adbd ignores `adb_keys`. So this row doesn't show that the key Kiln wrote worked. A later flash with a throwaway key alone stayed `unauthorized` ([below](#who-the-image-lets-in-over-adb)) |
| Rotation | **Corrected:** the first report said the boot animation ran portrait and upright with `ro.sf.hwrotation=270`. The maintainer's photo showed it **sideways**: the property doesn't turn the boot animation. It doesn't turn the UI either (2026-11-17, [below](#the-rotation-2026-11-17)). S1 Kiosk is in `/system/priv-app/S1Kiosk` |
| HOME | **Didn't resolve to S1 Kiosk.** The legacy test APK (#346) ships `MainActivity`, `BootReceiver` and `KioskService` disabled, because the S1 app calls API 24+ and crashes on API 23; only `EngineCheckActivity` runs. The harness reported "NOT FIXED: HOME". This build can't be the Mirror's launcher: that needs the app on API 23 (the rest of #198, or the generic app on the legacy engine, #23) |

What it changed in the install:

- `makeOnlyHome` now asks Android whether S1 Kiosk answers HOME before it hides any launcher:
  `am start -a android.intent.action.MAIN -c android.intent.category.HOME sh.edm.s1kiosk` (a bare
  package as `am`'s last argument sets the intent's package; read from AOSP 6 `Am.makeIntent`). If
  the answer is "unable to resolve", nothing is hidden, so a Mirror with no touchscreen keeps
  Launcher3 as its home screen, and Kiln's Done screen says the build has no home screen. Before,
  Launcher3 was hidden either way. **Verified on the unit** (2026-11-16, by the root, on the Mirror
  with this APK): `am` answered `Error: Activity not started, unable to resolve Intent …
  pkg=sh.edm.s1kiosk`, so the guard hides nothing there.

## Who the image lets in over adb

**Status (2026-11-17): verified on the unit (the root) and in the image (read); Kiln's fix verified
offline, then on the unit: a first boot let in only the throwaway key (2026-11-17, #443's run).** A Mirror has no touchscreen, so "Allow USB debugging?" can't be answered.
The first real flash came up on adb, and we took that as Kiln's key in `adb_keys` working. It wasn't.

### What the image does

Read 2026-11-16 from image 2 (`bdb6b844…`), `/framework/services.jar` (2,404,175 bytes, `root:root`
0644, `system_file`), decompiled with jadx (verified by the root, and again here):

- The jar was rebuilt for the image: its entries are dated 2026-11-04, `classes.dex` (5,350,072
  bytes, dex 035) is deflated, with only `META-INF/MANIFEST.MF` beside it and no signature. Nothing
  under `/framework` is odexed (no `oat/` folder, no `.odex`), so ART compiles the jar on the first
  boot.
- `com.android.server.usb.UsbDebuggingManager` has a constant, `MIRROR_OWNER_ADB_FINGERPRINT`: a
  47-character key fingerprint in the form `getFingerprints` makes (the MD5 of the decoded key,
  uppercase hex pairs joined by colons).
- In `UsbDebuggingHandler.handleMessage`, `MESSAGE_ADB_CONFIRM` (case 5, adbd asking about a new
  computer): it sets `mFingerprints` to the computer's fingerprint, then, if that equals the
  constant, logs `Authorizing the Mirror owner's ADB key`, appends the key with `writeKey`, and
  answers `OK`. No dialog. Any other key gets AOSP's dialog (`startConfirmation`).
- The constant is the fingerprint of the Glass Panes project key, `mirror-app/android/project-key/adbkey.pub`
  in `git-nimrod/glass-panes` (commit `8ea31141`, 2026-11-04; checked 2026-11-16: its MD5 is the
  constant). That folder publishes the private half too, on purpose. Its README says: "Anyone with a
  USB cable plugged into a mirror that runs the ready-made image can control it."

### adbd ignores `adb_keys` (verified 2026-11-16, the root, on the unit)

| What | Result |
|---|---|
| A flash with `--adb-key <throwaway>` only, then `dumpsys usb` | User keys: the throwaway and the project key; one system key (the ramdisk's `/adb_keys`) |
| The throwaway key alone | `unauthorized`, and stays so |
| The project key, right after a restart | `unauthorized` until the framework runs and lets it in through the constant, although `adb_keys` held it 11 times |
| A key the probe approved with `allowUsbDebugging(true, key)` | In for that connection, and written to the file; the next connection `unauthorized` again ([privileged-app.md](privileged-app.md#the-run-on-the-unit-2026-11-16)) |

So adbd here doesn't read `/data/misc/adb/adb_keys` at all. **The cause is unknown**: adbd lives in
the boot partition, which we haven't dumped. What it rules out: any way in that writes `adb_keys`
(Kiln's fresh userdata, the probe's approval, a Manage page) lasts at most one connection on this
image.

What it meant for Kiln: the page's first boot waits for adb with the browser's key, which never gets
in, so a real install would stop at "Wait for adb". It blocked release.

### What Kiln does instead (`installer/src/services.ts`)

Kiln changes the constant to the installer's key fingerprint (the browser's key; the harness's first
`--adb-key`), so the image lets in that key, the way it let in the project key, and no longer lets in
the project key. It refuses the project key itself, and refuses, with nothing written, a jar or a dex it
doesn't know: the old value not found exactly once, more than one dex, a dex that isn't 035 or fails
its own checksum.

Swapping the 47 bytes in place doesn't work. A dex's string table must stay sorted, and ART refuses a
dex that isn't (`DexFileVerifier`, "Out-of-order string_ids"). In this dex the constant sits between
`D` and `D: `, so only a fingerprint starting `D0:` to `D9:` fits there, about 4 % of keys. Verified
2026-11-16 with build-tools 36's `dexdump`, which runs the same verifier: the swap to a `3A:…` value
gives `Failure to verify dex file … Out-of-order string_ids: 'D' then '3A:…'`, and a `D5:…` value
passes.

So Kiln moves the string to its sorted place and renumbers every reference to a string index: the
type, proto, field, method and class tables, `const-string`, debug info, static values and
annotations. Nothing changes size: a reference whose new index would need a longer or shorter
encoding is refused. Here a fingerprint can land anywhere from index 3,466 to 7,122 of 51,132, and no
index in that range is at a size boundary. Then it recomputes the dex's SHA-1 and Adler-32, rereads
the result (the same text at every reference, a sorted table), and writes the jar back deflated, with
the other entry and every header as they were.

| Check (2026-11-16, image 2's real `services.jar`) | Result |
|---|---|
| Four target fingerprints: before every string, after every string, a middle one, and one in the constant's own slot | `dexdump -d -a` (code, debug locals, annotations) prints each result exactly like the original, once string indexes and the fingerprint text are masked: 1,137,205 lines, 28,688 `const-string`s |
| `dexdump` verifier | Accepts all four |
| The rewritten jar | `unzip -t`: no errors |
| `GW_MIRROR_SYSTEM_IMG=<image 2> npm test -w @glassworks/lululemon-mirror-installer` | Passed in 56 s: hash, patch, e2fsck as before, and the `dexdump` comparison on the flashed result |

On the unit (2026-11-17, #443's run): flashed with a throwaway key, the first boot came up over it alone. Not
checked in that run: that the project key is refused.

### Image 3: a new constant, and `adb_keys`

**Read 2026-11-22** from image 3's `/framework/services.jar` (2,404,392 bytes, one `classes.dex`,
5,351,048 bytes), with build-tools 37's `dexdump -d`:

- `MIRROR_OWNER_ADB_FINGERPRINT` is still there, now `A1:EC:24:9E:F0:8B:80:55:C5:A7:0A:89:A4:F0:D5:8B`
  (`IMAGE_OWNER_FINGERPRINT`). It is **not** the project key (`D1:13:60:…`), and no key in the
  repository at tag `v0.13.0` has it (`mirror-app/android/project-key/` holds only the project key).
  Whose it is: **unknown**. `handleMessage` checks it first: a match logs `Authorizing the Mirror
  owner's ADB key`, writes the key and answers `OK`. So every unit on image 3 as released lets that
  computer in over USB, whatever key its userdata lists.
- Then a new branch: `isListedInUserKeyFile(key)` reads `/data/misc/adb/adb_keys` line by line, takes
  each line's first word, and on an equal key logs `Authorizing an ADB key listed in adb_keys` and
  answers `OK`. adbd still ignores the file; the framework now reads it.
- The release's userdata lists one key there, the project key (0640 1000:2000 `adb_keys_file`). Glass
  Panes' `install-mirror.ps1` swaps it, byte for byte, for the user's `adbkey.pub` before flashing.
- `DefaultPermissionGrantPolicy.grantDefaultSystemHandlerPermissions` names `com.nimrod.mirrorkiosk`
  (camera, microphone, location, per the release notes).
- The constant's neighbours in the string table are `A package name must be specified.` and
  `A2dp connection list not empty, purge it, size `, so the in-place swap fails for most keys, as on
  image 2; Kiln's string move handles it. The dex fixture (`test/fixtures/dex`) was rebuilt with the
  new constant.

What it means:

| | Image 3 as released | Kiln's Glassworks | Kiln's Glass Panes option |
|---|---|---|---|
| The owner constant | `A1:EC:…` | this browser's key (`services.ts`) | this browser's key (`system-key` step) |
| `adb_keys` | the project key | this browser's key (a fresh userdata) | this browser's key (`adb-key` step) |
| Who gets in | `A1:EC:…` and anyone with the published project key | this browser only | this browser only |

The app's Manage page hears both log lines as "let in by the image" (`UsbDebugging.heard`), and
still never writes `adb_keys` itself (`allowUsbDebugging(false, …)`), so Remove still takes a computer
out. Not checked on a unit: that image 3 lets in a key listed in `adb_keys` (read only), and that a
Glassworks flash refuses the project key. **Untested.**

## The rotation (2026-11-17)

**Status (2026-11-17): verified on the unit.** The cause read on the unit (the root, read-only); the
fix ran on the unit with the legacy build from #443: the first boot logged rotation 3, **the
maintainer saw the app upright on the glass**, and no crash (the results are on #439). A reboot
wasn't checked in that run: that the rotation holds after one is **untested**, though
`user_rotation` lives in `userdata` like every other setting.

The first flash with the real legacy build of Glassworks 0.1.0 (the patched pipeline, a throwaway
key) came up **sideways**: the maintainer saw landscape. Read on the unit afterwards:

| Read | Result |
|---|---|
| `build.prop` | `ro.sf.hwrotation=270` (Kiln set it) |
| `wm size` | 1920x1080 |
| `dumpsys display` | logical frame 1920x1080, orientation 0 |
| `dumpsys window` | user rotation mode 1 (locked), user rotation 0, current rotation 0 |
| `settings get system user_rotation`; `accelerometer_rotation` | null; 0 |

So `ro.sf.hwrotation=270` doesn't turn the UI on this image either. Read 2026-11-17 in image 2
(unsparsed, `debugfs rdump` of `/framework`, `/app`, `/priv-app`, `/vendor`): the string
`hwrotation` is in no file of the filesystem (a byte search of the whole raw image), and in no entry
of its 172 jars and APKs once inflated. SurfaceFlinger (`libsurfaceflinger.so`), the window manager
(`services.jar`), `framework.jar`, `bootanimation` and the HWC all live there, so nothing that could
act on the property reads it. Kiln stopped setting it.

The stock unit stood portrait through `user_rotation=3`, locked (read 1 above, 2026-11-15), which
lives in `userdata`. A fresh `userdata` starts at 0. The probe hid that: it asked for portrait
itself. The legacy app before #434 held the natural orientation, landscape here. Since #434 it
follows the system's rotation (`FULL_USER`).

**After an app update (2026-11-20, #686).** Android 6 sets `accelerometer_rotation=1` and
`user_rotation=0` in the same second an `adb install -r` of the app finishes (measured by the root,
reading both every 2 s; the writer isn't known). An app that follows the rotation turns sideways. So
the app now asks for `SCREEN_ORIENTATION_PORTRAIT` itself on the Mirror (`Orientation.PORTRAIT_FIXED`).
Read in AOSP 6.0.1 `PhoneWindowManager.java` (`setInitialDisplaySize`, `rotationForOrientationLw`): on a
display wider than tall, portrait is rotation 3 and upside down is 1, unless the image sets
`config_reverseDefaultRotation`; `PORTRAIT` ignores the sensor and auto-rotate, and only a rotation lock
on 1 would override it. `dumpsys window policy` prints `mPortraitRotation=` to check it on the unit:
**untested** on the Mirror. Kiln keeps locking rotation 3 for the boot and anything before the app.

**What Kiln does now** (`setPortrait` in `installer/src/flash.ts`, on the first boot, before the app
starts): `settings put system accelerometer_rotation 0`, `settings put system user_rotation 3`, then
`dumpsys window windows` once a second until its `mRotation=` is exactly 3. After 10 s it stops with
"the Mirror's screen didn't turn to portrait". Why that command (read in the image's `services.jar`,
`WindowManagerService`, by the #439 verifier, 2026-11-17):
- plain `dumpsys window` prints `mRotation=` only with `dumpAll` (`dumpWindowsNoHeaderLocked`), so it
  isn't there; its display's `cur=` size can't tell 3 from 1 (both 1080x1920);
- plain `dumpsys window` also prints the state saved at the last ANR first (`saveANRStateLocked`,
  kept 2 h), with its own display contents and an old `cur=`;
- `dumpsys window windows` calls `dumpWindowsLocked(pw, true, null)`: the live `mRotation=` every
  time, and no ANR block.

The first version (2026-11-17, before the verifier) read plain `dumpsys window` and fell back to the
size; the run passed, but it would have taken 1 as well.

**Why 3 and not 1.** Both are portrait; one is upside down. **3 is upright: seen on the glass by the
maintainer, 2026-11-17.** It agrees with what we read before:
- the stock unit stood upright at `user_rotation=3` (2026-11-15, the community kiosk in daily use);
- Kiln's boot animation, portrait art turned 90° counter-clockwise into the panel's framebuffer, is
  upright on the glass (2026-11-16, photo);
- AOSP 6.0.1 `core/java/android/view/Display.java:592–608`, `getRotation`'s javadoc: "The angle is
  the rotation of the drawn graphics on the screen, which is the opposite direction of the physical
  rotation of the device. For example, if the device is rotated 90 degrees counter-clockwise, to
  compensate rendering will be rotated by 90 degrees clockwise and thus the returned value here will
  be `Surface.ROTATION_90`." So `ROTATION_270` (`Surface.java:118`, `= 3`) draws the graphics turned
  270° clockwise, which is 90° counter-clockwise: the boot animation's turn.

To reproduce the string search: unsparse image 2, then
`LC_ALL=C grep -a -c hwrotation system.raw` (0), and the same over every entry of every zip under
`debugfs -R 'rdump /framework out'` (and `/app`, `/priv-app`, `/vendor`).

## How to reproduce

Off-device only:

1. Download `system.img` from the Glass Panes release and check it against the release's `SHA256SUMS`.
2. `GW_MIRROR_SYSTEM_IMG=/path/to/system.img npm test -w @glassworks/lululemon-mirror-installer`.
   It patches the image in a scratch folder, writes the sparse image Kiln would flash, "flashes" it
   onto a file full of other bytes, and needs e2fsck to report only the original's own quirk. Needs
   e2fsprogs. On 2026-11-15 it passed in under a minute on an M-series Mac. With the Android SDK's
   build-tools installed, it also runs `dexdump` on the patched `services.jar` and compares it with
   the original's.
3. To read `UsbDebuggingManager` yourself: unsparse the image (`simg2img system.img system.raw`),
   `debugfs -R 'dump /framework/services.jar services.jar' system.raw`, `jadx -d out services.jar`,
   and open `out/sources/com/android/server/usb/UsbDebuggingManager.java`.
4. Delete the download and the scratch folders. Never commit any of it.

## Open questions

- Why this image's adbd ignores `/data/misc/adb/adb_keys`. Needs the boot partition's `adbd`, not yet dumped.
- The first real flash with the patched `services.jar`: first boot over the installer's key, the project key refused.

- Does `ro.sf.hwrotation=270` turn the boot animation? **No** (2026-11-16, seen on the unit: sideways).
  The UI? **No** (2026-11-17): nothing on the image reads it, and the real app came up landscape.
- ~~Is `user_rotation=3` upright on the glass, not upside down (1)?~~ Yes (2026-11-17, the maintainer, on the glass).
  The frames are turned in the zip now, and run upright (2026-11-16, photo). Android's UI is portrait.
- ~~Does the Mirror's LK take sparse FILL chunks?~~ Yes (2026-11-16): the run used FILL (no
  `--no-fill`), and both `system` (5 pieces) and `userdata` (1) flashed. "Try again without FILL"
  (`--no-fill` in the harness) stays as the fallback for an LK that refuses them.
- The LK's exact `max-download-size` (`system` took 5 pieces), and how long the first boot takes
  while `dex2oat` optimises S1 Kiosk.
- Whether a fresh `userdata` finishes Android's setup by itself (`com.android.provision`).
- Whether the unit's `userdata` partition is the same 5,583,458,304 bytes as the Glass Panes
  `userdata.sparse` fills. Kiln reads it with `fastboot getvar partition-size:userdata` before it builds.
