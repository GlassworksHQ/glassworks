# Mirror images: what Kiln changes in the community system image, and what a fresh userdata needs

**Status (2026-11-16): flashed on a real Mirror once, end to end, with image 1.** Image 2 (below) is
checked offline only: its run on the unit, and its fully dark sleep, are still to do. Kiln patches the Glass Panes
`system.img` and builds a new `userdata` (`frames/lululemon-mirror/installer/src/image.ts`). The
root's first real flash (below) booted it. Its adb came up, but **not through the key Kiln wrote**: the
image lets in one published key by itself, and its adbd ignores `adb_keys`
([Who the image lets in over adb](#who-the-image-lets-in-over-adb), 2026-11-16). Since then Kiln
patches `services.jar` to let in the installer's own key instead (checked offline; not yet flashed). `ro.sf.hwrotation` turns
Android's UI but **not the boot animation**, which rendered sideways. The frames are now turned
themselves (#389), and on 2026-11-16 the turned animation ran upright on the glass (the maintainer's
photo; [first-boot.md](first-boot.md)). The S1 Kiosk build it carried can't be the home screen on Android 6, so a usable
Mirror still waits on an app that runs on API 23. Since 2026-11-16 Kiln installs the legacy build of
Glassworks for generic Android instead ([The app](#the-app-the-legacy-build-of-glassworks)); it doesn't
run on a Mirror yet.

Device: lululemon Mirror (MSM8916), community image Glass Panes `mirror-android-1` (the real flash
below), and since 2026-11-16 `mirror-android-2` (below, offline only, not yet on the unit) (Android 6.0.1,
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
| `/media/bootanimation.zip` (PR 5 makes it) | `bootanimation` reads `/system/media/bootanimation.zip` when there's no `/oem/media` or `/data/local` one, and the Mirror has neither | Read |
| `build.prop`: `ro.sf.hwrotation=270` | The panel is landscape (1920×1080), and the Mirror stands portrait. Today's portrait comes from `user_rotation=3`, which lives in `userdata`, so a fresh `userdata` would come up landscape. `ro.sf.hwrotation` turns Android's UI (SurfaceFlinger's display transform) and makes the panel's natural orientation portrait. It doesn't turn the boot animation, which draws straight to the panel, so its frames are turned themselves (`boot.rotate`). 270 is the same turn as `user_rotation=3` (both are SurfaceFlinger's `eOrientation270`) | **Corrected 2026-11-16:** it does **not** turn the boot animation. On the first real flash the portrait 1080x1920 animation rendered sideways (the maintainer's photo). An earlier version of this note said "verified upright": that came from the property, not from looking. The boot animation's frames are now turned themselves (`branding/lululemon-mirror`, `boot.rotate`): upright on the glass (2026-11-16, the maintainer's photo). Android's UI with it is portrait (2026-11-16, [first-boot.md](first-boot.md)) |
| `/framework/services.jar`: `UsbDebuggingManager` lets in the installer's key (the first `adbKey` line) in place of the Glass Panes project key; same mode, owner and label | The image's own way in over adb is a constant naming the published project key, and its adbd ignores `adb_keys` ([below](#who-the-image-lets-in-over-adb)) | Verified offline (2026-11-16): image 2 patched, `dexdump` verifies the result and prints the same code. Untested on the unit |
| Nothing removed | The community kiosk isn't in `system` | Verified (read 1) |

The patcher refuses, with nothing written, an image whose fingerprint isn't the one above, and an
APK that isn't the app the frame names. A second run gives the same image.

### The app: the legacy build of Glassworks

Since 2026-11-16 (#198) the app is the legacy build of Glassworks for generic Android, named in the
frame's data (`design/frame.json`, `app`):

| | |
|---|---|
| Package | `dev.glassworks.generic_android.legacy` (minSdk 23, GeckoView 144) |
| What adb starts | `sh.edm.s1kiosk.MainActivity`. It takes `WRITE_SECURE_SETTINGS`, which adb's shell holds, so only adb's start can set the Home Assistant address through extras |
| HOME | the alias `dev.glassworks.generic.Launcher` (MAIN, HOME and DEFAULT). Android records the alias as the preferred home, and shows MainActivity as focused, so Kiln matches HOME by package |
| Folder | `/system/priv-app/Glassworks`. Android 6 keys a package by its manifest, not its folder, so the folder carries the product's name. The probe keeps `/priv-app/S1Kiosk`, where it was verified; Kiln removes both before it writes one, so an image has one app |

Kiln refuses, before anything is written, an APK that isn't this package (S1 Kiosk's
`sh.edm.s1kiosk` included), and one that doesn't ask for `INSTALL_PACKAGES`, `REBOOT` and
`WRITE_SECURE_SETTINGS`: Android 6 grants a privileged permission only to what the `/system` copy
asks for, and an update can't add one ([privileged-app.md](privileged-app.md)). The harness flashes
the probe (`sh.edm.s1kiosk`) only with `--probe`. **Read** (the #198 manifest); untested on a Mirror.

### `userdata`: what a fresh one needs

A new, empty ext4 the size of the partition, with only this:

| Path | Owner, mode | Label | Why |
|---|---|---|---|
| `/` | `system:system` 0771 | `system_data_file` | As `init.rc` sets it on `post-fs-data` |
| `/misc` | `system:misc` 01771 | `system_data_file` | `init.rc`: `mkdir /data/misc 01771 system misc` |
| `/misc/adb` | `system:shell` 02750 | `adb_keys_file` | `init.rc`: `mkdir /data/misc/adb 02750 system shell` |
| `/misc/adb/adb_keys` | `system:shell` 0640 | `adb_keys_file` | AOSP 6's `adbd` takes keys from `/adb_keys` (the signed ramdisk: out of reach) and `/data/misc/adb/adb_keys`. **This image's adbd ignores the file** (verified 2026-11-16, cause unknown, [below](#who-the-image-lets-in-over-adb)). Kiln still writes it: harmless, and Android appends to it itself |
| `/lost+found` | `root:root` 0770 | `system_data_file` | e2fsck expects it |

Everything else is made by init on the first boot (`mkdir` sets owner and mode even on a directory
that exists), and `restorecon_recursive /data` relabels anything mislabelled. The key in it doesn't
let anyone in on this image: `services.jar` does that (above).

What a fresh `userdata` loses: the community kiosk (good), the shared key in its `adb_keys` (which
this adbd ignores anyway), the Mirror's
Wi-Fi, and the screen rotation (handled by `ro.sf.hwrotation` above). After the first boot, Kiln
gives the app the Wi-Fi (over its Improv socket) and the Home Assistant URL over adb, as it does on
the S1.

### The first boot: our app as the only home screen

The image ships Launcher3 (`com.android.launcher3`) as a home app, and the Mirror has no touchscreen
to answer Android's chooser. adb's shell can't disable another app on Android 6, but it holds
`MANAGE_USERS` (read 2026-11-15, [home-app.md](home-app.md)), which `pm hide` needs. So on the first
boot Kiln runs `pm hide com.android.launcher3` (and `com.cyanogenmod.trebuchet` if present), and our
app is the only home app left: Android opens it with no chooser. **Untested** on the unit. The way
back (the plain Glass Panes image) brings the launcher back, because it comes with a fresh userdata.

Kiln then checks what HOME opened (`dumpsys activity activities`, `mFocusedActivity`). If it isn't
our app (the chooser, or a launcher that survived `pm hide`), Done says so plainly. Without a
touchscreen, the Mirror would wait on that chooser after a restart. Done offers "Hide the other
launcher again", or a USB mouse to choose our app, then Always.

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

## The first real flash (2026-11-16, verified by the root)

The root ran the install headless with `tools/mirror/flash-node.ts` (#370), the same installer
code as Kiln's page, on the maintainer's Mirror. The Mirror was left on the patched image (a dev
unit); the way back is ready.

| Step | What happened |
|---|---|
| Fastboot checks | `partition-size:system` is **1,288,491,008** bytes: 314,573 blocks, 19 more than the image. The exact-size check refused it, so the check now takes a partition at least the image's size (#357) |
| Flash `system` | Sent in 5 pieces (split to `max-download-size`), then `userdata` in 1. The first try stopped with "Cancelled" at about 20 %: the Node `usb` package gives every transfer a 1 s timeout. The harness now gives each transfer 120 s (#370). Kiln's page runs on Chrome's WebUSB, which has no such timeout |
| Restart | Android started the patched image. adb came up with no prompt. **Confounded** (2026-11-16): the root's computer carries the Glass Panes project key, which the image's `services.jar` lets in by itself, and this image's adbd ignores `adb_keys`. So this row doesn't show that the key Kiln wrote worked. A later flash with a throwaway key alone stayed `unauthorized` ([below](#who-the-image-lets-in-over-adb)) |
| Rotation | **Corrected:** the first report said the boot animation ran portrait and upright with `ro.sf.hwrotation=270`. The maintainer's photo showed it **sideways**: the property doesn't turn the boot animation. S1 Kiosk is in `/system/priv-app/S1Kiosk` |
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

**Status (2026-11-16): verified on the unit (the root) and in the image (read); Kiln's fix verified
offline, not yet flashed.** A Mirror has no touchscreen, so "Allow USB debugging?" can't be answered.
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

Still to do on the unit: the root's plan (flash with a throwaway key, first boot over it, the
project key refused).

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
  The frames are turned in the zip now, and run upright (2026-11-16, photo). Android's UI is portrait.
- ~~Does the Mirror's LK take sparse FILL chunks?~~ Yes (2026-11-16): the run used FILL (no
  `--no-fill`), and both `system` (5 pieces) and `userdata` (1) flashed. "Try again without FILL"
  (`--no-fill` in the harness) stays as the fallback for an LK that refuses them.
- The LK's exact `max-download-size` (`system` took 5 pieces), and how long the first boot takes
  while `dex2oat` optimises S1 Kiosk.
- Whether a fresh `userdata` finishes Android's setup by itself (`com.android.provision`).
- Whether the unit's `userdata` partition is the same 5,583,458,304 bytes as the Glass Panes
  `userdata.sparse` fills. Kiln reads it with `fastboot getvar partition-size:userdata` before it builds.
