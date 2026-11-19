---
topic: boot
evidence: read
---

# Magisk modules: why none loaded on our panels, and the busybox Magisk needs first

**Status (2026-11-19): read and built, waiting on a real panel.** Magisk v31.1 loads no module, and runs
no `post-fs-data.d` or `service.d` script, until `/data/adb/magisk/busybox` exists. The Magisk app puts it
there; our installers never ran that step, so no panel we installed loaded a module. The S1's boot image now
carries Magisk's own busybox and copies it in place at `post-fs-data`, just before Magisk checks. The panel run
that proves it is pending.

Device: Aqara S1 Plus, Android 10, our Magisk v31.1 boot patch (Kiln or `patch-boot-magisk.sh`). Tools: Magisk
v31.1 source (tag `v31.1`, commit `0972f67`), AOSP `android10-release`.

## Question

The S1's setup hotspot name (#430) ships as a Magisk module: a static overlay under
`/data/adb/modules/s1kiosk_hotspot_name/system/product/overlay/`. On the dev S1 the module sat in place, but
after a restart nothing was mounted. Why, and what's the smallest fix that reaches every panel?

## What we tried

1. **The panel run of #430** (2026-11-19, verified by the root on the dev S1). The module was in place, root-owned,
   755/644. After the restart `/product/overlay/S1HotspotName` was missing and `cmd overlay list` was empty.
   `/cache/magisk.log`:
   ```
   ** post-fs-data mode running
   * Initializing Magisk environment
   * Magisk environment incomplete, abort
   ```
   `/data/adb/magisk/` was empty.

2. **Why Magisk stops** (read, Magisk v31.1).
   - `setup_magisk_env` makes `/data/adb/magisk`, `/data/adb/modules`, `post-fs-data.d` and `service.d`, then
     returns false when `/data/adb/magisk/busybox` is missing (`native/src/core/bootstages.rs:76-79`).
   - `post_fs_data` logs "Magisk environment incomplete, abort" and returns before the safe-mode check, the
     `post-fs-data.d` scripts and `handle_modules()` (`:132-134`). It returns "safe mode" for that boot, so
     `late_start` (the `service.d` scripts) is skipped too (`:203-218`).
   - Only `busybox` is checked. Magisk copies it into its tmpfs and installs its applets there (`:81-95`).
     `magisk32` and `magiskpolicy` are optional (`:97-113`).
   - The Magisk app fills `/data/adb/magisk`: `fix_env` (`scripts/app_functions.sh:49-57`) with the files
     `MagiskInstaller.kt:124-150` takes out of its own APK, `lib/<abi>/libbusybox.so` among them. Kiln and
     `patch-boot-magisk.sh` patch the boot image from the same APK but never run that step, and our panels have
     no Magisk app.
   - `/data/adb` itself: `post_fs_data` aborts with "`/data/adb` is not present" when it's missing (`:120-123`),
     and `boot_complete` makes it (`:181-185`). AOSP's Android 10 `init.rc` doesn't make it.

3. **Where our own step can run** (read, Magisk v31.1 and AOSP `android10-release`).
   - `magiskinit` appends every `overlay.d/*.rc` to `init.rc`, then its own `on post-fs-data` with
     `exec ... magisk --post-fs-data` after them (`native/src/init/rootdir.cpp:96-104`, `rootdir.rs:20-22`).
   - init queues the actions of one trigger in the order it parsed them (`init/action_manager.cpp:56-66`), so an
     `on post-fs-data` in our overlay runs before Magisk's.
   - `copy` is an init built-in (`init/builtins.cpp:925-935`). It reads with `O_NOFOLLOW` and refuses a group- or
     world-writable source (`init/util.cpp:159-174`), and writes the target `0600` (`:200-210`).
   - `mkdir` gives a new **top-level** `/data` directory an encryption policy on a file-encrypted device, and boots
     into recovery with a wipe prompt if that fails (`init/builtins.cpp:345-349`). Second-level paths are skipped
     (`system/extras` `libfscrypt/fscrypt_init_extensions.cpp:72-78`). So `mkdir /data/adb/magisk` is safe and
     `mkdir /data/adb` from init is not.
   - The boot animation already runs at `on post-fs-data` from our overlay (`s1kiosk_branding.rc`), with
     `${MAGISKTMP}` paths. That is verified on the S1 since #63.

4. **The busybox itself** (read). `lib/armeabi-v7a/libbusybox.so` in the official Magisk v31.1 APK (sha256
   `89cefac0…c6e7`, the one the installers pin): 1,282,828 bytes, sha256 `7edf6024…1b6d`. BusyBox 1.36.1, built by
   Magisk from [topjohnwu/ndk-busybox](https://github.com/topjohnwu/ndk-busybox) (branch `android-ndk-1.36.1`) and
   fetched by Magisk's build as `busybox-1.36.1.1.zip` (`app/build-logic/src/main/java/Setup.kt:103-106`).
   GPL-2.0.

5. **Room in `boot`** (verified on the host, 2026-11-19, Kiln's `patchBoot` over the stock 4.5.80 `boot`).
   | | Ramdisk (gzip) | Left in the 32 MiB `boot` | Left before the DTB's load address |
   |---|---|---|---|
   | Before (Glass Tile animation of #63, 8.72 MB) | 10,799,772 | 2,050,048 | |
   | With Magisk's busybox | 11,628,162 | 1,222,656 | 5,149,054 (ramdisk at `0x43000000`, DTB at `0x44000000`) |

   Both installers refuse an image that no longer fits, so it can't go over silently.

6. **The two installers agree** (verified on the host, 2026-11-19). `patch-boot-magisk.sh` from the branch over the
   eight golden inputs of `kiln/packages/bootpatch/test/bootpatch.test.ts` (stock, re-patched, no animation, no key,
   preinit on `metadata`), then Kiln's `patchBoot` over the same inputs: byte-identical in all eight. The
   goldens hold Aqara's boot images and live outside the repo.

## Result

| Option | Verdict |
|---|---|
| (a) The installer fills `/data/adb/magisk` over adb | No: a fresh panel has no root after the flash. Kiln's adb session is uid 2000 |
| **(b) The boot image carries busybox and copies it at `post-fs-data`** | **Built.** `s1kiosk_magiskenv.rc`: `mkdir /data/adb/magisk`, `copy ${MAGISKTMP}/magisk-busybox`, `chmod 0755`. init built-ins only, so nothing can hang the boot. Rewritten every boot, so a torn write heals on the next one |
| (c) Mount the overlay ourselves, no Magisk modules | Also a boot-image change, plus a hand-rolled magic mount before the package manager scans `/product/overlay` |
| (d) The app downloads Magisk and the helper extracts busybox | A runtime download of a GPL binary, and more code. Only worth it if field panels must get it without a Kiln visit |

What changes on a panel with (b):

- **Which boot loads modules.** Right after an install `/data/adb` doesn't exist yet: on the first boot the rc's
  `mkdir` and `copy` fail (init logs them, nothing else happens) and Magisk aborts as before. Magisk makes
  `/data/adb` at `boot_complete`. From the second boot on, busybox is in place before Magisk checks, and modules load.
  On a panel that already ran (any panel installed before this), the first boot with the new image is enough.
- **Magisk's bootloop guard turns on.** With its setup complete, `post_fs_data` counts boots that don't reach
  `boot_complete`. At the third in a row it writes a `disable` mark into every module (`bootstages.rs:137-155`,
  `native/src/core/module.rs:668-679`). On the S1 that means a panel that lost power twice during boot comes up with the
  hotspot name module off. #430's helper keeps a `disable` mark and says
  `android: the module is disabled in Magisk`; turning the name off and on again (Manage) makes the module fresh.
  The volume-down key also triggers safe mode (`native/src/core/utils.cpp:87-111`); the S1 has no volume keys.
- **Scripts.** `/data/adb/post-fs-data.d` and `service.d` now run. Ours are empty.
- **Undo.** Flashing the stock `boot` back (Kiln's restore, `fel-install.sh --undo`) removes Magisk; the file in
  `/data/adb/magisk` stays and nothing reads it. With this boot image, removing `/data/adb/magisk/busybox` does
  nothing: init copies it back on the next boot, before Magisk checks. To stop one module, leave a `disable` file in
  its directory (what Magisk's app does), or, for #430's, set the name back to Android. To stop every module, go
  back to a boot image without `s1kiosk_magiskenv.rc`, then remove the file.

## How to reproduce

- **Read-only, on a panel with root:** `cat /cache/magisk.log` (look for `environment incomplete`),
  `ls -la /data/adb/magisk`.
- **The probe without a new boot image (root only, writes `/data/adb/magisk`):** copy the APK's
  `lib/armeabi-v7a/libbusybox.so` to `/data/adb/magisk/busybox` (root, 0755) and restart. Undo: remove it and restart.
  The S1's panel plan for #430 and this change has the exact steps.
- **The goldens (host only):** run `patch-boot-magisk.sh` over the eight inputs (as
  `~/src/aqara-panel-backups/web-golden/gen.sh` does), copy `s1kiosk_magiskenv.rc` into `fixtures/overlay/`, then
  `S1_WEB_GOLDEN=<dir> npx vitest run` in `kiln/packages/bootpatch`. On macOS with OrbStack the CLI can't `cat`
  the mode-000 `magisk.cfg` it extracts (root in the container can't override the host's permissions); that line only
  prints the config, so a local `chmod 644 magisk.cfg` before it leaves the image bytes as they are.

## Open questions

- The panel run: does the copy happen before Magisk's check on the S1 (`/cache/magisk.log` past "Initializing Magisk
  environment", and the module mounted)? Untested.
- Is the S1's `/data` file-encrypted (`ro.crypto.type`)? The rc avoids the one path where it matters, so the answer
  doesn't change the fix.
- With the frame loader (#549), the three lines move into `glassworks.rc`'s `on post-fs-data` block, before the
  loader's `exec`.
