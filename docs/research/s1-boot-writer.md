---
topic: boot
evidence: verified
---

# S1 boot writer: putting the frame loader into a 0.4.3 panel's boot image over the air

**Status (2026-11-21): verified on the dev S1.** The shared patch gives byte-identical images whether the CLI's
container or the panel's settings run it (verified on a host, four cases), and the writer's flow and every refusal
pass the off-device shell tests. The hard rule 5 run on the dev S1 passed on 2026-11-19 (#651
issuecomment-7024290415): from 0.4.3's dev boot image and radio mode `thread_router`, the app downloaded Magisk
v31.1 and checked it, the writer patched `boot` in 31 s, a forced readback mismatch wrote the backup back with no
restart, the real write restarted the panel once, the loader ran (`ro.glassworks.frame.id` = `aqara-s1-plus`),
`thread_router` was kept, and the undo put the dev boot back, read back equal. On 2026-11-21 the dev S1 got the
loader the same way from main (`f95de89a6`) before #744's Frame update run (#744 issuecomment-7059817374): one
restart, `boot: done: frame 1.0.0, bundle none` in 0.47 s, radio mode kept (verified).

Device: Aqara S1 Plus, firmware 4.5.80, S1 Kiosk 0.4.3 boot image. Tools: Magisk v31.1 (`boot_patch.sh`,
`util_functions.sh`, `magiskboot`), Android 10 toybox, the repo's `patch-boot-magisk.sh` and `patch-ramdisk.sh`.

## Question

#139 route 2 (maintainer, 2026-11-19; the spec is #139 issuecomment-7020084611): S1s in the field run 0.4.3's boot
image, without the frame loader. Can the signed radio bundle, which a normal app update brings and which already
runs as root, put the loader into that panel's own boot image, with no case opening and no cable, using the same patch
the FEL install uses? And what does it take to make that safe enough?

## What we tried

1. **Where the patch can run** (2026-11-19, read). The FEL install's patch is Magisk's own `boot_patch.sh` plus our
   `overlay.d` files (`patch-boot-magisk.sh`, which ran it in an arm64 container). On the panel it needs Magisk's
   `magiskboot`, `magiskinit`, `magisk`, `init-ld` and `busybox` for `armeabi-v7a`. A 0.4.3 panel has none of them in
   a usable form: the ramdisk holds only `magiskinit` and `overlay.d/sbin/magisk.xz`, and the Magisk app (which would
   put them in `/data/adb/magisk`) was never installed. AGENTS.md rule 4 forbids shipping them in our bundle. So the
   app downloads the official `Magisk-v31.1.apk` from Magisk's GitHub release, and the writer takes it with
   `s1kiosk_verify --copy` and accepts it only with the SHA-256 pinned in `kiln/assets.lock.json`
   (`89cefac0…c6e7`, 10679283 bytes). That is the pattern Silicon Labs' zigbeed already uses (`SisdkZigbeed.kt`).

2. **Running `boot_patch.sh` on a live Android** (2026-11-19, read: Magisk v31.1 `assets/util_functions.sh`).
   - `util_functions.sh` sets `BOOTMODE=true` when it sees zygote, unless `BOOTMODE` is set (lines 747–749).
   - With `BOOTMODE=false` (the container), `abort` runs `recovery_cleanup`, which does `umount -l /system /vendor
     /persist /metadata` (lines 76–81, 177–205). On a running panel that would pull the system out from under
     Android. **So the panel runs it with `BOOTMODE=true`.**
   - With `BOOTMODE=true`, `boot_patch.sh` no longer reads `PREINITDEVICE` from an already-patched image's config
     (`if ! $BOOTMODE; then PREINITDEVICE=$(grep_prop PREINITDEVICE config.orig)`), and asks
     `magisk --preinit-device` when it is empty. To give the same config either way, `patch-ramdisk.sh` reads the
     image's own `PREINITDEVICE` itself when `BOOTMODE=true`, the way `boot_patch.sh` does outside Android.
   - It runs under Magisk's busybox with `ASH_STANDALONE=1`, as the Magisk app runs it, so `dos2unix`, `awk` and the
     rest are busybox's applets on both sides.

3. **One copy of the patch** (2026-11-19, verified on a host). The container script inside `patch-boot-magisk.sh`
   moved into `frames/aqara-s1-plus/tools/root/patch-ramdisk.sh`, which reads its overlay files from an
   `overlay.list`. The CLI runs it in the container; the writer runs it on the panel. Old script against new, on the
   golden inputs (Docker arm64, OrbStack), `cmp` of the output images:

   | Case | Input | Old = new |
   |---|---|---|
   | stock | `boot-4.5.80.img` | identical |
   | re-patch | the 0.4.3-style `s1-boot-magisk-v31.1-bundled.img` | identical |
   | dev image | `boot-4.5.80.img`, `DEV_SU_HOURS=8760` | identical |
   | no animation | `boot-4.5.80.img`, `BOOT_ANIMATION=none` | identical |
   | stock, `BOOTMODE=true` | `boot-4.5.80.img` | identical to the container's default |
   | re-patch, `BOOTMODE=true` | the 0.4.3-style image | identical (the pre-init partition read in step 2) |

   So the panel's settings (`BOOTMODE=true`, the pre-init read) don't change the image. Kiln's TypeScript patcher
   is checked against the CLI by its goldens, which this refactor doesn't change.

4. **A helper restart during the write** (2026-11-19, read: AOSP 10 `system/core/libprocessgroup`,
   `init/service.cpp`). init kills a service's whole process group, found by its cgroup
   (`/acct/uid_0/pid_<pid>`), when it stops or restarts it. Any app can ask for that (SELinux is permissive), and the
   app does on a radio mode change. A writer killed in the middle of `dd` leaves a torn `boot`. The writer moves itself
   to the root cgroup (`/acct/cgroup.procs`) just before the write, so a restart can't reach it. **Untested on the
   panel:** the panel plan checks `/proc/<pid>/cgroup` during the run.

5. **The writer, off the panel** (2026-11-19, verified in CI's armhf shell tests). A stand-in Magisk APK (a fake
   `magiskboot` over a toy image format, a fake `boot_patch.sh`) with the real `patch-ramdisk.sh` and the real writer.
   Covered: the whole flow, two exports that differ, a patch that changes the kernel, three unknown images (stock, a
   Magisk image without the 0.4.3 services, not an image at all), low space, no Magisk APK or a wrong one, a readback
   that differs (the backup goes back, checked, no restart), the backup reading back wrong too (stops for good), the
   root-made test switch that forces a mismatch, a boot that already has the loader, a second start after the restart,
   resuming after each cut (before the write, just before it began, after it), the run lock, the hook in the bundle's
   helper, and the agent's check after the restart.

## Result

| Step | What the writer does | Evidence |
|---|---|---|
| Trigger | The radio bundle's `s1kiosk_radio.sh` starts `bootwriter/glassworks_bootwriter.sh` when this boot runs no loader. An app update from the update card and one from Kiln over USB both install that bundle. No other path | shell tests ("a bundle's helper starts the writer") |
| Magisk | The app's `MagiskFetch` downloads the pinned APK only while the record says `need-magisk`, and deletes it once the loader is in. The writer waits for it in the same run, with no deadline (a sleeping writer costs nothing; a helper restart starts the next one) | `BootWriterTest`, shell tests |
| Checks | 256 MiB free on `/data`; two byte-identical exports; a Magisk image (`cpio test` = 1) with the 0.4.3 services; the patch's kernel, dtb and header equal the panel's | shell tests |
| Backup | The first export, `$DIR/boot-writer/backup.img` (root 0600), kept for good | shell tests |
| Write | `dd` with `conv=fsync`, caches dropped, read back from the device; a mismatch writes the backup back and reads that back; no restart | shell tests, including a forced mismatch |
| Restart | Once (`sys.powerctl`). A second start on the old system says so and never restarts again | shell tests |
| After | `glassworks_agent.sh` on the new image checks the loader ran (`ro.glassworks.frame.id`), `glassworks_agent` and `glassworks_netwatch` are up, boot still holds the image written, and `persist.glassworks.radio_mode` is the mode from before the write | shell tests |
| Manage | `/data/glassworks-pub/boot-writer` (root 0644: `<state> <epoch> <detail>`), shown on the Device page's *Frame loader* row on the panel and on Manage. No HA entity | `DevicePageTest`, `BootWriterTest` |

Carried from the old image into the new one: the installer's adb key (`s1kiosk_adb_keys` → `glassworks_adb_keys`), its
boot animation (or none), and a dev image's `s1kiosk_devsu` service, unchanged. The verifier is the running image's
own `s1kiosk_verify` (the pinned build), and otbr-agent and ot-ctl come from the bundle.

## How to reproduce

- **The byte-identity check (host only, writes nothing to a device):** run the old and the new
  `patch-boot-magisk.sh` over the same input with the same `ADB_PUBKEY`, `BOOT_ANIMATION` and `DEV_SU_HOURS`, and
  `cmp` the outputs. For `BOOTMODE=true`, add `-e BOOTMODE=true` to the script's `docker run`. Inputs: the goldens
  in `~/src/aqara-panel-backups/web-golden/in/` (outside the repo).
- **The off-device tests:** `frames/aqara-s1-plus/tools/radio/test/run.sh` (CI's `root` job; owners don't run the
  armhf container locally).
- **On the dev S1:** `~/.t3/glassworks-m1/139/panel-plan-writer.md` (the root's run sheet). It writes `boot`. The undo
  writes the backup back with the same route and reads it back.
- **The forced mismatch:** as root, `touch /data/s1kiosk/radio/boot-writer/force-readback-mismatch` (root-owned; one
  the app made is ignored) before the writer runs. The real write happens, its readback is treated as wrong, and the
  real restore runs. The switch is used up.

## Open questions

- Whether `/acct/cgroup.procs` takes the writer out of reach of init's process-group kill on this firmware. Partly
  answered: the writer's cgroup is `cpuacct:/` before and after the write to `/acct/cgroup.procs` (verified on the
  dev S1, 2026-11-19). A helper restart during `dd` hasn't been tried, so the kill path itself stays open (step 4
  above).
- ~~How long the patch takes on the A133 under busybox.~~ 31 s, and 70 s for the whole forced-mismatch round
  (verified on the dev S1, 2026-11-19).
- A power cut during the roughly 2 s of `dd` leaves a torn `boot`: recovery is FEL, as for any boot change. Nothing
  here shortens that window further.
