---
topic: boot
evidence: read
---

# Frame updates on the S1: the loader, signed bundles, and a reflash without opening the panel

**Status (2026-11-18): read and designed, untested on a panel.** The stock firmware answers both open
questions in #139 on paper: `/data` is not encrypted, and the stock U-Boot has a fastboot `boot`
command. The no-opening reflash has a likely route, with two premises from the issue corrected below.
Every panel test is written out in [Panel tests](#panel-tests) and has not run yet.

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
| `persist.s1kiosk.radio_mode`, `radio_zigbee`, `radio_allow`, `timezone` | `persist.glassworks.*` | The radio helper copies each old value once when the new one is empty, so the radio mode and the time zone survive the reflash. Removed with the old names |
| `/data/s1kiosk` (bundle store, update copy, markers), `/data/s1kiosk-pub` | `/data/glassworks`, `/data/glassworks-pub` | |
| `/data/s1kiosk/radio` (dataset, Zigbee NVM, otbr's Thread settings in `run/thread`) | **stays** | `otbr-agent` has `/data/s1kiosk/radio/run` compiled in (`build-otbr.sh` line 127). It moves in a later bundle, with an otbr built for the new path and a one-time move of `run/thread` |
| `applicationId` `sh.edm.s1kiosk`, `files/update/pending.apk` | stays | The app id stays (maintainer). App-private paths sit under it |
| (none) | `ro.glassworks.frame.id`, `ro.glassworks.frame.version` | New |

The S1 frame 1.0.0 app reads and writes both sets of names for that one release. Which set it uses
depends on the boot image: `ro.glassworks.frame.id` is set on a loader image and missing on 0.4.3's.
That code is marked for removal. About a month later a release drops the old names, and a panel still
on a 0.4.3 boot image gets an update card that says to update with Kiln first.

### The Kiln update: no opening

Route 1, in this order. Steps 1–3 write nothing, and step 4 is the first write:

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

The root runs these from `~/.t3/glassworks-m1/139/panel-plan-b.md`. The results go here, with dates.

| Test | What it settles | Writes |
|---|---|---|
| T0 | `ro.crypto.state`, the running command line, who may read `/dev/block/by-name/boot` | Nothing |
| T1 | Does `adb reboot bootloader` reach the stock fastboot? Its USB IDs, `getvar` | Nothing |
| T2 | If T1 fails: does a `bootloader` command in `misc` reach fastboot? | `misc` bytes 0–31, put back |
| T3 | `fastboot boot` of the image the panel already runs: does Android come up from RAM? | Nothing |
| T4 | `fastboot flash boot` of that same image (no change), read back from Android | `boot`, same bytes |
| T5 | Route 2 on the dev panel: root writes the same image from Android, reads it back | `boot`, same bytes |

## Open questions

- Which T1–T5 pass. Until then the whole no-opening route is untested.
- Does `fastboot boot` give Android the env's arguments (`partitions=`, `snum`, MACs)? T3 shows it.
- How long does the full bundle check take at `post-fs-data` on the panel? About 40 MB is hashed
  (otbr, the Zigbee runtime and the boot animation). The loader PR measures it.
