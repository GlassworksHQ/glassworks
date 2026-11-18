---
topic: boot
evidence: read
---

# Frame updates on the S1: the loader, signed bundles, and a reflash without opening the panel

**Status (2026-11-18): the loader is built and passes off-device tests; untested on a panel.** The
stock firmware answers both open questions in #139 on paper: `/data` is not encrypted, and the stock
U-Boot has a fastboot `boot` command. The no-opening reflash has a likely route, with two premises from
the issue corrected below. The loader is in `frames/aqara-s1-plus/tools/root/` (`glassworks_loader.sh`,
`glassworks.rc`), and both boot patchers make byte-identical images with it (attempt 7). Every panel
test is written out in [Panel tests](#panel-tests) and has not run yet.

Device: Aqara S1 Plus, firmware 4.5.80. Sources: the stock `boot`, `misc` and boot package
backups and Aqara's 4.5.80 OTA package, all outside the repo; AOSP `android-10.0.0_r41`; this repo at
`c8b0493f9`. Tools: `strings` (macOS), `brotli` 1.1, Python 3.11.

## Question

#139 makes the S1's boot image a thin loader that rarely changes. Everything else (the boot services,
the scripts, the boot animation, the radio stacks) moves into signed bundles that arrive over the air.
Every S1 in the field needs one more Kiln update to get the loader. Before building it we need to know:

1. Is `/data` readable at `post-fs-data`? The boot animation from a bundle has to be bind-mounted
   before `surfaceflinger` starts the player.
2. What may a bundle start? Init only reads `.rc` files at boot, so new services need fixed slots or a
   generic runner.
3. Can Kiln install the loader without opening the back cover (no FEL button)?
4. Which names change (#528), and what has to stay?

## What we tried

1. **The `/data` mount in the stock fstab** (2026-11-18, read). The fstab lives in `vendor`. Aqara's
   OTA ships `vendor` as a brotli block stream, so it was searched as a stream, without unpacking it to
   disk:
   ```
   $ unzip -p ota-4.5.80_0016.0017.bin vendor.new.dat.br | brotli -dc | strings -n 6 \
       | grep -E '^\S+\s+/data\s|fileencryption|forceencrypt|encryptable='
   /dev/block/by-name/UDISK  /data  f2fs  noatime,nosuid,nodev,discard,grpquota  wait,check,formattable,quota,reservedsize=33554432
   (the encryptable=footer hits are the USB and SD card lines, which vold mounts later)
   ```
   `/data` has no `fileencryption=`, `forceencrypt=` or `encryptable=` flag, so it is a plain f2fs
   volume. Android 10's `init.rc` mounts it with `mount_all` in `on fs` and starts `post-fs-data`
   only after that, so a root service can read `/data/glassworks` at `post-fs-data`. There is no
   second, decrypted `post-fs-data` trigger to wait for. The panel test confirms it with
   `getprop ro.crypto.state` (expected: `unencrypted`).

2. **The stock U-Boot's fastboot** (2026-11-18, read). `strings` over the stock boot package (U-Boot
   2018.05, read from eMMC sector 32800 on 2026-11-09):
   ```
   $ strings -n 3 -t d boot_package.bin | awk '$1>=519650 && $1<=522130'
   519694 reboot-bootloader     519731 reboot-fastboot     519784 reboot
   519799 erase:                521389 download:
   521572 Booting kernel..      521590 Image has cmdline:  521662 Booting raw image..
   521695 FAILinvalid boot image
   521718 getvar:               521837 oem                 521857 flashing
   521997 FAILability is 0. Permission denied for this command!
   522092 continue              522101 not supported fastboot cmd
   ```
   - The stock fastboot has `download`, `flash`, `erase`, `getvar`, `oem`, `flashing`, `continue`,
     the reboots, and a **`boot` handler** ("Booting kernel..", "FAILinvalid boot image"). So a RAM
     test with `fastboot boot` is in the binary.
   - It has **no `upload`**. That is our patch 0002 in the FEL U-Boot. So stock fastboot can't read a
     partition back, and neither the backup nor the read-back of a write can happen over it.
   - `FAILability is 0` sits next to `frp` and "GMS,fastboot unlock limited used": it is the
     `flashing unlock` gate, which reads the "OEM unlocking" byte from `frp`. Whether `flash` or
     `boot` is refused on this panel depends on the lock flag in secure storage, which findings.md
     found empty. Untested.
   - **Never run `fastboot flashing unlock` on an S1.** The strings next to it ("erase ... UDISK")
     suggest it wipes `/data`, which would take the Wi-Fi, the settings and the HA pairing with it.
     Untested, and not worth testing.

3. **How Android reaches that fastboot** (2026-11-18, read, inconclusive).
   - The stock env has `boot_fastboot=fastboot`, `bootdelay=0` and
     `bootcmd=run setargs_nand boot_normal`.
   - U-Boot prints `bootmode[%d]:0x%x` right before the strings `sprite_test`, `run sysrecovery`,
     `boot_recovery` and `boot_fastboot` (offsets 528907–528968). So some boot-mode value selects
     fastboot. Where that value comes from (an RTC register, the PMIC, or `misc`) isn't readable from
     strings alone.
   - The `misc` parser's strings (offsets 541007–541096) are `ir-or-key-recovery`, `boot-recovery`,
     `ir-factory`, `recovery`, `--wipe_data` and `ir-efex`. There is no separate `bootloader` string,
     but a linker can share one with the tail of `reboot-bootloader`, so that proves nothing.
   - The stock kernel (from `boot`, 20.5 MB, arm64 `Image`) and `vendor` show no reboot-mode driver:
     no `mode-` property prefix, no RTC `gpr_` properties, no Allwinner reboot strings. Android 10's
     init passes `reboot,bootloader` to the kernel's `reboot` call (`RESTART2`) and does nothing else
     on a non-A/B device. If no driver turns that into a boot-mode value, `adb reboot bootloader`
     just reboots into Android.
   - The panel's `misc` (backup of 2026-11-17) has an empty bootloader message: bytes 0–2047 are
     zero, and only the 4 bytes of an A/B control block magic at 2052 are set.

   So `adb reboot bootloader` may or may not land in fastboot, and the panel test decides. A
   `bootloader` command written to `misc` is the second thing to try (it needs root).

4. **The boot command line** (2026-11-18, read). The stock `boot` is header v2 with this command line:
   `selinux=1 androidboot.selinux=permissive androidboot.dtbo_idx=0,1,2 buildvariant=user`. A normal
   boot adds the env's `setargs_*` (console, `partitions=`, `snum`, MACs, `pstore`) on top. "Image has
   cmdline:" suggests that `fastboot boot` may use only the image's own line. Whether Android then
   comes up (partitions, serial, MAC addresses) is the first thing the RAM test shows. If it doesn't,
   a power cycle boots the flashed image again, because nothing was written.

5. **Root over adb on panels in the field** (2026-11-18, read: `installer/src/switch.ts` lines 10–13,
   [root.md](root.md#persistent-root-magisk--the-s1-kiosk-root-helper)). Panels have Magisk and our
   root init services, but **adb's shell has no `su`**. No Magisk policy exists, and with no Magisk
   app a request is refused. Only a dev boot image (`DEV_SU_HOURS`) or a hand-made policy gives adb
   root. That changes two things the issue assumed:
   - Kiln can't read `boot` from Android to back it up or to patch it. Stock fastboot can't read it
     either (no `upload`).
   - "Root writes `boot` from Android" (the fallback in #139) has no root to run as on a field panel.

   **The one root channel field panels do have is the signed radio bundle.** Since helper v4, the
   boot image's `s1kiosk_radio.sh` runs a newer helper from a bundle signed with the project key
   (`s1kiosk_bundle.sh --check`). The app installs the bundle it ships after every app update
   (`RadioBundle.kt`). So a new app can bring a helper with one more narrow, read-only action:
   **export `boot`** (copy the partition to the app-readable `/data/s1kiosk-pub/boot.img`, with its
   SHA-256 in a status property) when asked. That gives Kiln the backup and the input for the patch,
   without opening the panel and without adb root.

6. **Aqara's own updater writes `boot`** (2026-11-18, read: the OTA's `updater-script`).
   `package_extract_file("boot.img", "/dev/block/by-name/boot")`, plus `bootloader`, `env`, the
   three `vbmeta`s and `burnboot()`. An Aqara update would remove our loader, our splash and the
   `vbmeta` flags. `com.lumi.ota` stays disabled ([debloat.md](debloat.md)). That is the S1's
   vendor-updater decision.

7. **Re-patching an already-patched image** (2026-11-18, verified on a host). The Kiln goldens
   were made again with the loader's overlay: `patch-boot-magisk.sh` in its arm64 container over the
   stock 4.5.80 and older images, then `S1_WEB_GOLDEN=<dir> npm test -w @glassworks/bootpatch`.
   Re-patching the old radio-bundle image (`repatch-bundled`) did **not** give the same image as
   patching stock:
   ```
   --- overlay.d:   (repatch-bundled, before the fix)
   overlay.d/glassworks.rc
   overlay.d/s1kiosk_radio.rc
   overlay.d/sbin/glassworks_loader.sh
   …
   overlay.d/sbin/s1kiosk_radio.sh
   ```
   The Magisk restore only undoes the Magisk changes, so the overlay files of the earlier patch stay.
   Kiln's update path patches the panel's own, already-patched `boot`, so a 0.4.3 panel would have run
   `s1kiosk_radio` next to `glassworks_radio`, both driving the radio. Both patchers now drop every
   `overlay.d` entry but the Magisk ones before adding theirs. After the fix, `repatch-bundled` equals
   `stock-old` and every golden case matches byte for byte (61 tests). It also fixed an older slip:
   re-patching with `BOOT_ANIMATION=none` used to keep the animation of the earlier patch.
   `kiln/packages/bootpatch/test/repatch-overlay.test.ts` pins it in CI without the goldens.

## Result

### The open questions in #139

| Question | Answer | Evidence |
|---|---|---|
| Is `/data` readable at `post-fs-data`? | Yes: plain f2fs, mounted in `on fs`, no encryption | Read: vendor fstab (attempt 1). The panel check is `ro.crypto.state` |
| Fixed service slots or a generic runner? | Fixed, named slots, each run through the loader. Plus two spare slots for services that don't exist yet | Design below |
| No-opening route | Route 1 (stock fastboot's `boot`, then `flash`) is in the binary. The way into fastboot and its lock gate are untested | Attempts 2–4; panel tests T1–T4 |
| Root writes `boot` from Android | Not on field panels: adb has no root. Only on panels with adb root (the dev panel) | Attempt 5 |

### The loader

- **Fixed slots.** One `.rc` in the boot image (`overlay.d/glassworks.rc`) names every service the
  frame can run, and each slot runs `glassworks_loader.sh run <slot>`. The loader execs the slot's
  script from the active bundle, or the boot image's own copy when there is no valid bundle.
  - Slots: `glassworks_radio`, `glassworks_netwatch`, `glassworks_firstboot`, `glassworks_update`,
    `glassworks_bundle`, and two spares, `glassworks_svc1` and `glassworks_svc2`, which start at boot
    and exit unless the bundle has `services/svc1.sh` or `services/svc2.sh`.
  - A generic runner (one service that starts whatever a bundle lists) was ruled out. Init would
    supervise one process instead of each service, and `ctl.start`/`ctl.restart` and `init.svc.*`
    would stop working per service. The app uses both today.
- **Once per boot, at `post-fs-data`.** An `exec` step runs `glassworks_loader.sh boot`. It blocks
  init until it is done, so the result is there before the boot animation and the app start.
  1. If a bundle is staged in `/data/glassworks/bundle/next`, it is checked and promoted to
     `current`. A frame update applies on the reboot after its install.
  2. `current` is checked in full: the signature, the frame id, the version floor and every file's
     SHA-256 (the current `--check`). The result goes in `/dev/glassworks/active`, a tmpfs that
     only root can write and that is gone at the next boot. Every slot reads it there and doesn't
     hash the bundle again.
  3. The loader sets `ro.glassworks.frame.id=aqara-s1-plus` and `ro.glassworks.frame.version`: the
     version of the active bundle, or the boot image's own (`1.0.0` at V1) when it falls back. Owner A's
     version row and update notice read exactly these two properties.
  4. It bind-mounts the bundle's `bootanimation.zip` over `/system/media/bootanimation.zip`, or the
     ramdisk's copy when the bundle has none or is bad.
- **The installer is not a slot.** `glassworks_bundle` always runs the boot image's
  `glassworks_loader.sh install`, so a bundle can't replace the code that checks bundles.
- **Fallback.** A missing bundle, a failed check, or another frame's bundle means the boot image's
  copies run. A slot whose bundle script exits 3 times within a minute is marked bad, and that slot
  runs the boot copy from then on. That is the radio helper's rule today, applied to every slot.

### The bundle

The radio bundle's format ([root.md](root.md#radio-bundle-service)), extended:

```
GLASSWORKS-BUNDLE 1
frame aqara-s1-plus 1.0.1        the frame id and its version (semver); the loader refuses another frame id
version 1000001                  anti-rollback floor: major*1000000 + minor*1000 + patch, checked against the frame line
created <text>
file <mode> <size> <sha256> <path>
firmware <id> <path> <app> <label>
end
```

- **Signed per frame id.** The frame id is inside the signed manifest, and the loader compares it
  with its own (built into the boot image). A bundle signed for another frame is refused, even with
  a valid signature.
- **What it carries** at S1 frame 1.0.0: the slot scripts (`services/radio.sh`, `netwatch.sh`,
  `firstboot.sh`, `update.sh`), the radio helper's binaries and firmware (all of today's radio
  bundle), and `bootanimation.zip`.
- **The old `S1RADIO-BUNDLE 1` format** is refused cleanly by the new loader ("not a frame bundle").
  The app ships only the frame bundle from S1 frame 1.0.0 on.
- **Where it comes from.** The app ships the bundle in its assets, as it ships the radio bundle now.
  A frame update in HA stages it, starts `glassworks_bundle`, and restarts the panel.

### The names (#528)

The loader's own names are Glassworks names from the reflash on. Everything else moves later in a
bundle, once the loader makes that possible over the air.

| 0.4.3 boot image | S1 frame 1.0.0 | Notes |
|---|---|---|
| init services `s1kiosk_radio`, `_update`, `_bundle`, `_netwatch`, `_firstboot` | `glassworks_radio`, `_update`, `_bundle`, `_netwatch`, `_firstboot` | Plus `glassworks_svc1`, `_svc2` |
| `s1kiosk_devsu` (dev images) | `glassworks_devsu` | |
| `s1kiosk_verify`, `s1kiosk_bundle.pub`, `s1kiosk_adb_keys`, `s1kiosk_bootanimation.zip`, `s1kiosk_*.rc` | `glassworks_verify`, `glassworks_bundle.pub`, `glassworks_adb_keys`, `glassworks_bootanimation.zip`, `glassworks.rc` | In the ramdisk (`overlay.d`) |
| `sys.s1kiosk.update.*`, `sys.s1kiosk.bundle.*`, `sys.s1kiosk.radio.*`, `sys.s1kiosk.timezone.status` | `sys.glassworks.*`, same suffixes | |
| `persist.s1kiosk.radio_mode`, `radio_zigbee`, `radio_allow`, `timezone` | `persist.glassworks.*` | The radio helper copies each old value at its first start (where the new one is empty), then again only when the old value changes, so the radio mode and the time zone survive the reflash and a stopped update. Removed with the old names |
| `/data/s1kiosk` (bundle store, update copy, markers), `/data/s1kiosk-pub` | `/data/glassworks`, `/data/glassworks-pub` | |
| `/data/s1kiosk/radio` (dataset, Zigbee NVM, otbr's Thread settings in `run/thread`) | **stays** | `otbr-agent` has `/data/s1kiosk/radio/run` compiled in (`build-otbr.sh` line 127). It moves in a later bundle, with an otbr built for the new path and a one-time move of `run/thread` |
| `applicationId` `sh.edm.s1kiosk`, `files/update/pending.apk` | stays | The app id stays (maintainer). App-private paths sit under it |
| (none) | `ro.glassworks.frame.id`, `ro.glassworks.frame.version` | New |

The S1 frame 1.0.0 app reads and writes both sets of names for that one release. Which set it uses
depends on the boot image: `ro.glassworks.frame.id` is set on a loader image and missing on 0.4.3's.
That code is marked for removal. About a month later a release drops the old names, and a panel still
on a 0.4.3 boot image gets an update card that says to update with Kiln first.

### The Kiln update: no opening

Route 1, in this order. Steps 1–3 don't touch `boot` or any other partition, and step 4 is the first
partition write. Steps 1 and 3 do change `/data`: step 1 is an ordinary app update (it stays if the
route stops there, like any app update), and the RAM-booted loader in step 3 copies the old
`persist.s1kiosk.*` values to `persist.glassworks.*`. The 0.4.3 boot image ignores those copies, so
stopping after step 3 leaves the panel working as before.

1. **Over adb, on the 0.4.3 boot image.** Install the S1 frame 1.0.0 app with `pm install -r`.
   Its data stays: Wi-Fi, settings, the HA pairing. It installs its radio bundle, whose helper has the
   `boot` export. Kiln asks for the export, pulls `boot.img` twice, and checks both against the
   reported SHA-256. **That is the backup** (hard rule 1), kept like every Kiln backup. For that one
   release the app ships both bundles: the radio bundle for 0.4.3 boot images and the frame bundle
   for loader images.
2. **Patch** that image with the loader. The patch is the same as from the FEL path, byte for byte.
3. **`adb reboot bootloader`** (no root needed), then on the stock U-Boot's fastboot: `getvar product`,
   then **`fastboot boot` the new image, in RAM**. Kiln waits for adb, then checks the loader:
   `ro.glassworks.frame.version`, the slots running, the bundle state, and the radio mode carried over.
4. **Write.** Back to fastboot (`adb reboot bootloader`), `fastboot flash boot`, then `fastboot boot`
   the same image once more. From that system, the loader's `boot` export reads the partition back
   and Kiln compares hashes **before the panel ever boots from the new flash**. Then reboot.
   - **If the hashes differ**, Kiln doesn't reboot. From the same RAM system it goes back to
     fastboot and flashes step 1's backup, then reads it back the same way. If stock `flash` fails
     there too, the panel stays on the RAM system until it is powered off, and the way back is the FEL
     recovery: our U-Boot from RAM, `fastboot flash boot <backup>`, `fb-read-partition.sh boot` and a
     hash compare ([root.md](root.md#one-command-fel-installsh), `fel-install.sh --undo`).
   - **If the panel doesn't come up from flash** after a matching read-back, the same FEL recovery
     applies. The backup is Kiln's own, so its undo takes it.
5. **Load the frame bundle before calling it done.** The new boot image has no frame bundle yet, and
   some things live only in one: Silicon Labs' Zigbee runtime (`sisdk/`) above all. Until a bundle
   loads, a panel in `zigbee_tcp` with the Silicon Labs stack reports "needs a radio bundle with its
   runtime" and its coordinator is down. So Kiln has the app fetch and stage the frame release's bundle,
   restarts the panel once more, and calls the update done only when the loader's own record,
   `/data/glassworks-pub/frame-bundle`, reads `ok <the release's version>`. `ro.glassworks.frame.version`
   alone doesn't prove it: the boot image and the first bundle are both 1.0.0. The radio status is a
   separate check after that.

If the panel never reaches fastboot, or stock fastboot refuses `boot` or `flash`, the route stops
before any write, and the FEL button stays the way in. **Route 2** (root writes `boot` from Android,
reads it back, checks the hash, then reboots) only works where adb has root: the dev panel, or a panel
whose owner added a Magisk policy. On a field panel it would need a root writer in a signed bundle
that Kiln drives over adb. That is not built, and it needs the maintainer's call, because it puts a
`boot` writer in an over-the-air bundle.

## How to reproduce

All host-side and read-only. The images are local backups, never in the repo.

1. `/data` in the fstab: attempt 1's command, on Aqara's 4.5.80 OTA package (about 45 s, streamed,
   nothing written to disk).
2. The fastboot commands: `strings -n 3 -t d boot_package.bin`, with the offsets in attempt 2.
   `boot_package.bin` is the 4 MiB stock boot package at eMMC sector 32800.
3. The kernel and command line: the `boot` header (v2, page size 2048, command line at offset 64),
   and `strings` over the kernel at page 1.
4. `misc`: bytes 0–2047 of a `misc` backup are the bootloader message (all zero on the panel).

## Panel tests

None of these has run yet. The results go here, with dates. Each writes only bytes the panel already
has. They need a panel with adb root (a dev boot image) for the backup and for T5. The root's run
sheet with the same steps is outside the repo.

| Test | What it settles | Writes |
|---|---|---|
| T0 | `ro.crypto.state`, the running command line, who may read `/dev/block/by-name/boot` | Nothing |
| T1 | Does `adb reboot bootloader` reach the stock fastboot? Its USB IDs, `getvar` | Nothing |
| T2 | If T1 fails: does a `bootloader` command in `misc` reach fastboot? | `misc` bytes 0–31, put back |
| T3 | `fastboot boot` of the image the panel already runs: does Android come up from RAM? | Nothing |
| T4 | `fastboot flash boot` of that same image (no change), read back from Android | `boot`, same bytes |
| T5 | Route 2 on the dev panel: root writes the same image from Android, reads it back | `boot`, same bytes |

**Before any test.** `unset ANDROID_SERIAL` (fastboot reads it too). Back up `boot` and `misc`,
each read twice and compared, and keep the hashes:
```sh
B=<backup dir outside the repo>
ok=1
for p in boot misc; do
  adb exec-out "su -c 'cat /dev/block/by-name/$p'" > "$B/$p.img" || ok=0
  adb exec-out "su -c 'cat /dev/block/by-name/$p'" > "$B/$p.2.img" || ok=0
  cmp -s "$B/$p.img" "$B/$p.2.img" || ok=0
  rm -f "$B/$p.2.img"
done
[ "$(wc -c < "$B/boot.img")" -eq 33554432 ] || ok=0
[ "$(wc -c < "$B/misc.img")" -eq 16777216 ] || ok=0
[ "$ok" = 1 ] && (cd "$B" && shasum -a 256 boot.img misc.img > SHA256SUMS) || ok=0
[ "$(wc -l < "$B/SHA256SUMS" 2>/dev/null)" -eq 2 ] || ok=0
if [ "$ok" = 1 ]; then cat "$B/SHA256SUMS"; else rm -f "$B/SHA256SUMS"; echo "BACKUP FAILED: stop here, write nothing"; fi
```
The size checks matter: `adb exec-out` can succeed with an empty or short read when `su` fails.
Then make `boot-ram.img`: the same image with ` androidboot.gw139=ram` appended to the header's
command line (offset 64, 512 bytes, NUL-padded). A RAM boot then shows `ro.boot.gw139=ram`, and a
boot from flash doesn't. `vbmeta` has flags 3, so the changed header boots.

**Never run** `fastboot flashing unlock`, `fastboot erase` or `fastboot oem` on the stock U-Boot.

1. **T0** (read-only): `getprop ro.crypto.state` (expect `unencrypted`), `ls -lL /dev/block/by-name/boot`,
   `cat /dev/block/by-name/boot | head -c 8` as adb's shell (expect permission denied), and the keys
   of `/proc/cmdline` (`tr ' ' '\n' | cut -d= -f1`). The full line holds the serial and MAC addresses:
   keep it out of notes.
2. **T1**: `adb reboot bootloader`, then `fastboot devices` for a minute. Record the USB vendor and
   product IDs and `fastboot getvar product|version|max-download-size|secure|unlocked`. If Android
   boots instead, nothing was written: go to T2.
3. **T2** (only if T1 failed): `su -c 'printf bootloader | dd of=/dev/block/by-name/misc bs=32 count=1 conv=notrunc,sync'`,
   then `adb reboot`. Afterwards (from Android, or from T3's RAM system if U-Boot keeps landing in
   fastboot) put the backup's first 32 bytes back: `head -c 32 "$B/misc.img" > misc32.bin`,
   `adb push misc32.bin /data/local/tmp/`, then as root
   `dd if=/data/local/tmp/misc32.bin of=/dev/block/by-name/misc bs=32 count=1 conv=notrunc`, and compare
   the first 2048 bytes with the backup.
4. **T3**: in fastboot, `fastboot boot boot-ram.img`. Wait for `sys.boot_completed`, check
   `ro.boot.gw139=ram`, root, the radio helper, Wi-Fi, and compare the `/proc/cmdline` keys with T0's.
   If nothing comes up within 4 minutes, power-cycle: the panel boots from flash, unchanged.
5. **T4** (only if T3 passed): back in fastboot, `fastboot flash boot boot.img`, then `fastboot boot
   boot-ram.img`, and in that RAM system `su -c 'sha256sum /dev/block/by-name/boot'` must equal the
   backup. Then `adb reboot`. On a mismatch, don't boot from flash: rewrite from the RAM system with T5's
   commands, or use the FEL recovery.
6. **T5**: `adb push boot.img /data/local/tmp/`, then as root
   `dd if=/data/local/tmp/boot.img of=/dev/block/by-name/boot bs=1048576 conv=fsync && sync`,
   `echo 3 > /proc/sys/vm/drop_caches` and `sha256sum /dev/block/by-name/boot` (must equal the
   backup) before `adb reboot`. Delete the pushed copy.

**FEL recovery** (needs the back cover open): hold the PCB button while plugging in USB-C, then
`NO_BL31=1 frames/aqara-s1-plus/tools/fel/fel-boot.sh <u-boot build dir>`,
`fastboot flash boot "$B/boot.img"` (and `misc` if T2 ran),
`frames/aqara-s1-plus/tools/install/fb-read-partition.sh boot <file>`, compare the first 33554432
bytes with `SHA256SUMS`, and `fastboot reboot`.

### The loader on the panel (L1–L5)

These need a boot image built by `patch-boot-magisk.sh` from this branch, RAM-booted (T3's route, or
FEL with our U-Boot) or flashed with the usual backup. The app can stay on 0.4.3: the radio helper reads
`persist.s1kiosk.*` into the new names at its first start, so `thread_router` comes back by itself.

| Test | What it settles | Expect |
|---|---|---|
| L1 | `/data` at `post-fs-data`, and the `exec` step (on a store with no bundle: no `/data/glassworks/bundle/current` or `next`; with one, expect `ok <its version>` instead) | `getprop ro.glassworks.frame.id` = `aqara-s1-plus`, `ro.glassworks.frame.version` = `1.0.0`, `sys.glassworks.bundle.state` = `none`; `logcat -d -s glassworks_loader` |
| L2 | The slots under init | `init.svc.glassworks_radio`, `_netwatch` = `running`; `init.svc.glassworks_firstboot` = `stopped` (it exits at once: the 0.4.3 marker says the panel was provisioned); no `init.svc.s1kiosk_*` |
| L3 | The radio from the old setting. Starts from a known state: 0.4.3 running `thread_router` (step 0 of the run sheet checks it), no `/data/s1kiosk/radio/.settings-moved`, no frame bundle | `persist.glassworks.radio_mode` = `thread_router`, `sys.glassworks.radio.status` = `ok thread_router…`, `sys.glassworks.radio.source` = `boot`, otbr runs |
| L4 | The boot animation mount from a shell at `post-fs-data` | the Glassworks animation plays at boot (film it); `/proc/mounts` lists `bootanimation.zip` |
| L5 | The check's cost at `post-fs-data` | the `glassworks_loader` log lines' times, with and without a bundle (a bundle needs the bundles PR) |

## Open questions

- Which T1–T5 pass. Until then the whole no-opening route is untested.
- Does `fastboot boot` give Android the env's arguments (`partitions=`, `snum`, MACs)? T3 shows it.
- How long does the full bundle check take at `post-fs-data` on the panel? About 40 MB is hashed
  (otbr, the Zigbee runtime and the boot animation). The loader PR measures it.
