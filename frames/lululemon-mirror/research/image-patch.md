# Mirror images: what Kiln changes in the community system image, and what a fresh userdata needs

**Status (2026-11-16): flashed on a real Mirror once, end to end.** Kiln patches the Glass Panes
`system.img` and builds a new `userdata` (`frames/lululemon-mirror/installer/src/image.ts`). The
root's first real flash (below) booted it, took the computer's adb key, and turned the screen with
`ro.sf.hwrotation`. The S1 Kiosk build it carried can't be the home screen on Android 6, so a usable
Mirror still waits on an app that runs on API 23.

Device: lululemon Mirror (MSM8916), community image Glass Panes `mirror-android-1`, and since
2026-11-16 `mirror-android-2` (below) (Android 6.0.1,
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
   end. The release notes say the project's shared adb key is in it.

## Result

### `system`: what Kiln changes

| Change | Why | How sure |
|---|---|---|
| `/priv-app/S1Kiosk/S1Kiosk.apk`, `root:root` 0644, `system_file`; its directory 0755 | A privileged system app: Android 6 grants privileged permissions (`WRITE_SECURE_SETTINGS`, `CHANGE_CONFIGURATION`) to `/system/priv-app` apps with no whitelist (that came in Android 8). It survives a factory reset, and S1 Kiosk's own updates install over it in `/data/app` | Read (AOSP 6 `PackageManagerService`). On the unit (2026-11-16) the APK is in `/system/priv-app/S1Kiosk`; its privileged permissions are untested (the test build doesn't run its main activity) |
| Its native libraries in `/priv-app/S1Kiosk/lib/arm64/` (or `lib/arm/`) | Android 6 doesn't unpack a bundled app's libraries: it looks for `lib/<abi>` next to the APK, as the image's own priv-apps have them. Only the primary ABI the APK has is unpacked (arm64-v8a first) | Read (the image's layout, AOSP 6 `setBundledAppAbisAndRoots`); untested |
| `/media/bootanimation.zip` (PR 5 makes it) | `bootanimation` reads `/system/media/bootanimation.zip` when there's no `/oem/media` or `/data/local` one, and the Mirror has neither | Read |
| `build.prop`: `ro.sf.hwrotation=270` | The panel is landscape (1920×1080), and the Mirror stands portrait. Today's portrait comes from `user_rotation=3`, which lives in `userdata`, so a fresh `userdata` would come up landscape. `ro.sf.hwrotation` turns SurfaceFlinger's whole output, the boot animation included, and makes the panel's natural orientation portrait. 270 is the same turn as `user_rotation=3` (both are SurfaceFlinger's `eOrientation270`) | **Verified** (2026-11-16, the first real flash): the boot animation runs portrait and upright. The image's SurfaceFlinger is CAF's (DragonBoard LA.BR.1.2.7), which reads `ro.sf.hwrotation` |
| Nothing removed | The community kiosk isn't in `system` | Verified (read 1) |

The patcher refuses, with nothing written, an image whose fingerprint isn't the one above, and an
APK that isn't `sh.edm.s1kiosk`. A second run gives the same image.

### `userdata`: what a fresh one needs

A new, empty ext4 the size of the partition, with only this:

| Path | Owner, mode | Label | Why |
|---|---|---|---|
| `/` | `system:system` 0771 | `system_data_file` | As `init.rc` sets it on `post-fs-data` |
| `/misc` | `system:misc` 01771 | `system_data_file` | `init.rc`: `mkdir /data/misc 01771 system misc` |
| `/misc/adb` | `system:shell` 02750 | `adb_keys_file` | `init.rc`: `mkdir /data/misc/adb 02750 system shell` |
| `/misc/adb/adb_keys` | `system:shell` 0640 | `adb_keys_file` | Android 6's `adbd` takes keys from `/adb_keys` (the signed ramdisk: out of reach) and `/data/misc/adb/adb_keys`, nowhere else. It runs as `shell`, so it reads it through the group |
| `/lost+found` | `root:root` 0770 | `system_data_file` | e2fsck expects it |

Everything else is made by init on the first boot (`mkdir` sets owner and mode even on a directory
that exists), and `restorecon_recursive /data` relabels anything mislabelled. The key is Kiln's own
for this browser, so there's no shared key (#29).

What a fresh `userdata` loses: the community kiosk (good), its shared adb key (good), the Mirror's
Wi-Fi, and the screen rotation (handled by `ro.sf.hwrotation` above). After the first boot, Kiln
gives S1 Kiosk the Wi-Fi (over its Improv socket) and the Home Assistant URL over adb, as it does on
the S1.

### The first boot: S1 Kiosk as the only home screen

The image ships Launcher3 (`com.android.launcher3`) as a home app, and the Mirror has no touchscreen
to answer Android's chooser. adb's shell can't disable another app on Android 6, but it holds
`MANAGE_USERS` (read 2026-11-15, [home-app.md](home-app.md)), which `pm hide` needs. So on the first
boot Kiln runs `pm hide com.android.launcher3` (and `com.cyanogenmod.trebuchet` if present), and S1
Kiosk is the only home app left: Android opens it with no chooser. **Untested** on the unit. The way
back (the plain Glass Panes image) brings the launcher back, because it comes with a fresh userdata.

Kiln then checks what HOME opened (`dumpsys activity activities`, `mFocusedActivity`). If it isn't
S1 Kiosk (the chooser, or a launcher that survived `pm hide`), Done says so plainly. Without a
touchscreen, the Mirror would wait on that chooser after a restart. Done offers "Hide the other
launcher again", or a USB mouse to choose S1 Kiosk, then Always.

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
| `SHA256SUMS` | `74c4766d…4b4a4` | `c3cbc377…4d02a` |
| `ro.build.fingerprint` | `…/MMB29M/11041605:userdebug/test-keys` | `…/MMB29M/11120023:userdebug/test-keys` |
| What changed (release notes) | | "Fully dark sleep": the panel switches off completely in sleep |

`GW_MIRROR_SYSTEM_IMG=<image 2> npm test -w @glassworks/lululemon-mirror-installer` passed on
2026-11-16 in 25 s: the hash matches the pin, the patch applies, and e2fsck finds nothing in the
flashed result that the original doesn't have. The patch's checks read the fingerprint, so image 1
is refused from now on, and the way back (`checkDownload`) takes image 2's `system.img`.

## The first real flash (2026-11-16, verified by the root)

The root ran the install headless with `tools/mirror/flash-node.ts` (#370), the same installer
code as Kiln's page, on the maintainer's Mirror. The Mirror was left on the patched image (a dev
unit); the way back is ready.

| Step | What happened |
|---|---|
| Fastboot checks | `partition-size:system` is **1,288,491,008** bytes: 314,573 blocks, 19 more than the image. The exact-size check refused it, so the check now takes a partition at least the image's size (#357) |
| Flash `system` | Sent in 5 pieces (split to `max-download-size`), then `userdata` in 1. The first try stopped with "Cancelled" at about 20 %: the Node `usb` package gives every transfer a 1 s timeout. The harness now gives each transfer 120 s (#370). Kiln's page runs on Chrome's WebUSB, which has no such timeout |
| Restart | Android started the patched image. adb came up with no prompt: the computer's `adbkey.pub` in the new `userdata`'s `adb_keys` worked |
| Rotation | The boot animation ran **portrait and upright** with `ro.sf.hwrotation=270`. S1 Kiosk is in `/system/priv-app/S1Kiosk` |
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

## How to reproduce

Off-device only:

1. Download `system.img` from the Glass Panes release and check it against the release's `SHA256SUMS`.
2. `GW_MIRROR_SYSTEM_IMG=/path/to/system.img npm test -w @glassworks/lululemon-mirror-installer`.
   It patches the image in a scratch folder, writes the sparse image Kiln would flash, "flashes" it
   onto a file full of other bytes, and needs e2fsck to report only the original's own quirk. Needs
   e2fsprogs. On 2026-11-15 it passed in under a minute on an M-series Mac.
3. Delete the download and the scratch folder. Never commit either.

## Open questions

- ~~Does `ro.sf.hwrotation=270` turn the boot animation upright?~~ Yes (2026-11-16). S1 Kiosk's own
  screen waits on a build that runs on Android 6.
- ~~Does the Mirror's LK take sparse FILL chunks?~~ Yes (2026-11-16): the run used FILL (no
  `--no-fill`), and both `system` (5 pieces) and `userdata` (1) flashed. "Try again without FILL"
  (`--no-fill` in the harness) stays as the fallback for an LK that refuses them.
- The LK's exact `max-download-size` (`system` took 5 pieces), and how long the first boot takes
  while `dex2oat` optimises S1 Kiosk.
- Whether a fresh `userdata` finishes Android's setup by itself (`com.android.provision`).
- Whether the unit's `userdata` partition is the same 5,583,458,304 bytes as the Glass Panes
  `userdata.sparse` fills. Kiln reads it with `fastboot getvar partition-size:userdata` before it builds.
