---
topic: access
evidence: verified
---

# Stock 1.24.0 Mirror probe: userdata-only code execution, reversible surgical ADB, and vendor inventory

**Status (2026-11-25): Phase 1, Phase 2 surgical USB ADB, and Step 4 full raw `system` + `boot` backup via the `dpmd` hook are all verified on an untouched stock `1.24.0` Mirror.**
- **Phase 1 (2026-11-22, verified):** Writing only `userdata` (leaving `system`, `boot`, `recovery`,
  `modem`, and `persist` untouched) runs our plain `untrusted_app` HTTP probe
  (`dev.glassworks.stockprobe`, `uid=10099`) automatically at `BOOT_COMPLETED` with zero ADB and
  zero touchscreen, reachable over stock's own `mirror-<serial>` setup SoftAP (`192.168.43.1:18765`).
  The probe extracted `/proc/config.gz`, ramdisk SELinux files, `init.svc.*`, `services.jar`,
  `pronto_wlan.ko`, `FS_IOC_FIEMAP` physical block extents, and the full 462-file Option C vendor
  audit including camera (**443/462** readable without root — 39 MB pulled cleanly; exact same 14
  `*_exec` daemons denied as predicted in [option-c.md](option-c.md)). Stock `1.24.0` has **no
  active `dm-verity`**, and although it runs a different kernel git hash
  (`3.10.49-perf-g4869d64961b`) than the dev unit (`gf46dad5260f`), its `__versions` symbol CRCs
  (`module_layout=0xa5b7f7dd`, `printk=0x27e1a049`, `__kmalloc=0xd2b09ce5`) match `gf46dad5260f`.
- **Phase 2 (2026-11-24, verified):** Confirmed **`services.odex` is absent** on stock `1.24.0` and
  `/system/framework/services.jar` (`2,411,806` B) occupies a single contiguous physical extent
  (`block 40597 × 589`). Flashing an exact-byte-length surgical sparse patch (`patch-services.sparse`,
  `DONT_CARE` everywhere outside those 589 blocks, with a byte-exact `restore-services.sparse` undo
  saved first) opened **authorized USB ADB (`uid=2000(shell)`, SELinux `Enforcing`) on untouched
  stock `1.24.0`** (`ro.build.version.mirror=1.24.0`, only **27** installed packages total).
  An attempted Step 4 raw partition dump via `init.qcom.post_boot.sh` (`qti_init_shell`) hit a dead
  end on stock `1.24.0` (`avc: denied { search }` on `/data/local/tmp`, and stock `1.24.0`'s
  `/sepolicy` does not grant `qti_init_shell` `capability:sys_module`).
- **Step 4 raw stock backup via `dpmd` hook (2026-11-25, verified):** Using `--backup-only` over USB
  (`adb forward tcp:18765 tcp:18765`, zero Wi-Fi swap), the tool pulled `/system/vendor/lib64/libdpmframework.so`
  (`223,232` B, `block 230356 × 55`), embedded a caller-supplied 2,560-byte module (`module_layout=0xa5b7f7dd`,
  `kallsyms_lookup_name=0xe007de41`), cleared the leftover `init.qcom.post_boot.sh` patch, and booted
  directly into `getenforce=Permissive` with `mmcblk0p22` and `mmcblk0p19` readable. Both raw
  partitions streamed cleanly in ~6 min (`system-stock.raw.img` = `1,288,491,008` B, clean ext4,
  all 3 previously touched files verified byte-for-byte untouched stock after scrub; `boot-stock.raw.img`
  = `33,554,432` B, valid `ANDROID!` image), and `restore-dpmd.sparse` restored `libdpmframework.so`
  back to stock automatically.
- **Track B offline forensics on the untouched stock `1.24.0` `boot` + `system` dumps (2026-11-25, read):**
  ramdisk `init` is a `user` build with **no `ALLOW_DISABLE_SELINUX`**; the `boot` image carries **48
  generic Qualcomm DTBs**, all with the `TC358775` LVDS bridge and a PWM backlight; there is **no
  native watchdog** (`mapd`/`cfgchkup` are harmless — the watchdog is the `com.mirror.watchdog.apk`
  app, not a daemon); `/system/media/bootanimation.zip` is `22,901` B, `1080×1920` portrait; the full
  [option-c-vendor-files.tsv](option-c-vendor-files.tsv) audit is **462/462 complete offline** (34
  match Glass Panes, 423 differ — including all 14 `*_exec` daemons that were SELinux-denied over
  HTTP — and 5 absent); and `pronto_wlan.ko`'s **262/262 `__versions` CRCs are identical** to the dev
  Mirror's (`gf46dad5260f`).
- **Track A live Stock-in-place verification on the dev Mirror (2026-11-25, verified):** `pm
  disable-user` throws `SecurityException` as `shell`, but **`pm hide` works on all 8 lululemon apps**
  and survives reboot (0 lululemon processes, **663 MB free RAM** — `337 MB` with Glassworks +
  GeckoView running); the backlight drops `200 → 0` on sleep; the panel is native `1080×1920`
  portrait; an **8-byte `services.jar` Device Owner patch** (`mHasFeature = true` + NOP
  `setBackupServiceActive`) lets `dpm set-device-owner` succeed with SELinux **Enforcing**, and a live
  **silent `PackageInstaller` self-update (`v1 → v2`, zero taps)** followed. USB MTP
  (`mUsbDataUnlocked=false` → 0 storages) and AOA v2 (`android.hardware.usb.accessory` feature absent)
  are dead ends; a live BLE GATT benchmark measured `MTU=185`, `6.8 KB/s` (~5.5 min for `2.41 MB`),
  with `bluetooth_on=1` by default.

Device: untouched stock lululemon Studio Mirror (`MSM8916`, `variant: SBC eMMC`, `secure: yes`,
`Device unlocked: false`, `ro.build.version.mirror=1.24.0`, `ro.build.hwver.mirror=mirror-v1`).
Host: Windows PC (community tester), connected over micro-USB for fastboot/ADB and Wi-Fi to stock's
`mirror-<serial>` SoftAP for HTTP extraction.
Tools: [`tools/mirror/stock-probe.ts`](../../../tools/mirror/stock-probe.ts),
[`tools/mirror/stock-probe-app/`](../../../tools/mirror/stock-probe-app/),
[`tools/mirror/stock-surgical-adb.ts`](../../../tools/mirror/stock-surgical-adb.ts),
Android SDK `fastboot` and `adb`, `@glassworks/ext4`, `@glassworks/bootpatch`.

## Question

[option-c.md](option-c.md) and [adbd-keys.md](adbd-keys.md) left four open questions for an
untouched stock `1.2x+` Mirror:

1. Does planting `/data/misc/adb/adb_keys` + `persist.sys.usb.config=adb` open USB ADB on stock
   `1.2x+`, or does stock's `adbd` reject user keys just like the dev unit?
2. Without ADB and without a touchscreen, can a `userdata`-only flash boot a plain `untrusted_app`
   and extract `/system` vendor files, kernel config, ramdisk SELinux files, and `FS_IOC_FIEMAP`
   physical block extents over stock's own network?
3. Is `dm-verity` active on stock `1.2x+` `/system`, and do `init.qcom.post_boot.sh` / `build.prop`
   have contiguous physical block extents usable for surgical sparse `system` patching?
4. How closely do stock `1.24.0`'s vendor binaries and kernel match Glass Panes (`mirror-android-2`)
   and our Option C inventory ([option-c-vendor-files.tsv](option-c-vendor-files.tsv))?

## How we got here

Before Phase 1, untouched stock `1.2x+` Mirrors looked completely sealed to read-only inspection:
fastboot has no partition read-back and no `fastboot boot`, there is no touchscreen to approve an
ADB prompt, stock `adbd` ignores `/data/misc/adb/adb_keys`, and flashing a replacement `system.img`
permanently destroys stock firmware. Four small Android 6 facts chained together to break that wall
without writing a single byte outside `userdata`:

1. **Pre-installing an APK in a fresh `userdata`:** Android 6's `PackageManagerService` scans
   `/data/app/<pkg>-1/base.apk` at boot and registers any properly signed APK it finds.
2. **Defeating Android's `stopped=true` lock with a 1-entry `packages.xml`:** Normally, a newly
   scanned APK starts in `stopped=true, notLaunched=true`, so Android refuses to deliver
   `BOOT_COMPLETED` until a user taps its icon or ADB launches it. By pre-populating a minimal
   1-entry `/data/system/packages.xml` (with Android 6's exact `<version sdkVersion="23"
   databaseVersion="3"/>` tags, `<permissions>` granting `RECEIVE_BOOT_COMPLETED` and `INTERNET`,
   and omitting `stopped="true"`), `PackageManagerService` treats the app as already launched and
   delivers `BOOT_COMPLETED` automatically on cold boot — with zero ADB and zero touchscreen.
3. **Piggybacking on stock's own `mirror-<serial>` SoftAP (`192.168.43.1`):** On a fresh `userdata`,
   stock's `MirrorApService` (`/system/bin/mapd`) automatically broadcasts `mirror-<serial>` for
   onboarding. Leaving Wi-Fi mode untouched whenever `--wifi-ssid` is omitted lets our tiny HTTP
   probe server (`port 18765`) answer straight over lululemon's own setup SoftAP — no Ethernet
   adapter and no Wi-Fi credentials needed.
4. **Physical eMMC block maps without root (`FS_IOC_FIEMAP`):** Even from an unprivileged
   `untrusted_app` (`uid=10099`), Linux's `FS_IOC_FIEMAP` ioctl on read-only `/system/` file
   descriptors is allowed by stock's SELinux policy. Combined with no active `dm-verity` and
   fastboot's sparse `DONT_CARE` chunks, knowing a file's exact 4 KiB physical extents unlocked
   Phase 2: byte-for-byte reversible surgical patching of individual `/system` files.

### Extending Phase 1 into Phase 2 (reversible surgical USB ADB)

Once Phase 1 showed that `/system` has no active `dm-verity` and `services.jar` contains an
un-odexed `classes.dex`, we asked whether we could open authorized USB ADB on untouched stock
`1.24.0` by overwriting **only** `services.jar`'s physical blocks—keeping the rest of `/system`
untouched, SELinux **Enforcing**, and rollback as simple as flashing back the original 589 blocks.
Three constraints had to be solved before touching hardware:

- **No `services.odex` shadow:** If stock pre-compiled `/system/framework/oat/arm64/services.odex`,
  ART would ignore edits to `services.jar`. We built a hard stop-gate in `stock-surgical-adb.ts`
  to check `/system/framework` and `/system/framework/oat` before writing anything; live preflight
  confirmed `services.odex` is absent.
- **Exact byte length (`2,411,806` B) across platforms:** An in-place sparse block write cannot grow
  a file without editing ext4 inode metadata. Node 24's bundled Chromium `node:zlib` level-9 deflate
  bloated `classes.dex` by +102 KB (`2,506,465` B, which would not fit). Switching to
  `@glassworks/bootpatch`'s `zlib-rs` WASM level-9 deflate compressed the patched `classes.dex`
  smaller than stock deterministically across macOS, Windows, and Linux, leaving `3,592` B of ZIP
  EOCD comment padding headroom to hit the exact original `2,411,806` B length.
- **Minimal DEX string-table churn:** Replacing the `"unable to start
  customAdbPublicKeyConfirmationComponent "` log string with a shorter or differently-sorted string
  shifted ~44,000 DEX string indices. Preserving the `"unable to start "` prefix and writing
  `"unable to start " + <47-char MD5> + "        "` (extracted via `substring(16, 63)`) shifted
  only ~5 string indices and passed both `dexdump -c` and real Android 6.0.1 ARM64 `dex2oat`
  off-device before flashing.

The first live Phase 2 run stopped cleanly at the Step 3 safety gate because the tool initially
compared `fastboot getvar partition-size:system` against Glass Panes' ext4 image size
(`1,288,413,184` B) instead of the Mirror's actual eMMC `system` partition (`0x4cccd000` =
`1,288,491,008` B, 77,824 B larger). Updating the default to `0x4cccd000` (and auto-rebuilding the
surgical sparse images to match the live `partition-size:system`) let Step 3 pass on the first flash.

Finally, we tested whether Step 4 could stream a raw `system` + `boot` backup over the new USB ADB
session by temporarily hooking `init.qcom.post_boot.sh` (20 blocks), based on [mirror-root.md](mirror-root.md) §5. That hit a clean dead end on stock `1.24.0`: live `dmesg` showed
`qti_init_shell` is denied `search` on `/data/local/tmp` (`shell_data_file`), and inspecting stock
`1.24.0`'s pulled `/sepolicy` showed that—unlike the older dev unit—`qti_init_shell` has no
`capability:sys_module` rule at all.

## What we tried

All runs on 2026-11-22 against one untouched stock `1.24.0` Mirror. Only `userdata` was ever
flashed; `system`, `boot`, `recovery`, `modem`, and `persist` were never written. Raw dumps stayed
outside the repository.

1. **Fastboot preflight & `userdata`-only ADB probe** (2026-11-22, verified)
   ```
   $ npx -y tsx@4.23.15 tools/mirror/stock-probe.ts
   product: MSM8916
   variant: SBC eMMC
   secure: yes
   Device unlocked: false
   partition-size:system: 0x4cccd000
   partition-size:userdata: 0x14cccd000
   ```
   Flashing a fresh `userdata` with `/data/misc/adb/adb_keys` (`system:shell 0640`,
   `adb_keys_file`) and `/data/property/persist.sys.usb.config=adb` turned USB ADB on
   (`not-seen` → `unauthorized`), and it stayed `unauthorized` after boot completed.
   This **confirms [adbd-keys.md](adbd-keys.md) on untouched stock `1.24.0`**: `/data/misc/adb/adb_keys`
   never authorizes a host key.

2. **Dead end / Windows bring-up fixes on the HTTP probe app** (2026-11-22, verified)
   Three real-world issues surfaced during the live Windows session and were fixed in
   `tools/mirror/stock-probe-app/` and `tools/mirror/src/stock-probe-cli.ts`:
   - **Windows `spawn EFTYPE`:** `execFile('.../build.sh')` failed synchronously on Windows. Fixed
     by invoking `bash` and bundling prebuilt MIT-licensed `prebuilt/stock-probe.apk`, `cert.hex`,
     and arm64 `libfiemap.so` (~29 KiB total, no keystore) so Windows testers need no JDK/Android
     SDK/NDK.
   - **SoftAP race when `--wifi-ssid` is omitted:** Stock `1.24.0` boots OOBE straight into its own
     `mirror-<serial>` SoftAP at `192.168.43.1` (driven by `MirrorApService` → `/system/bin/mapd`).
     Our first probe APK unconditionally called `WifiManager.setWifiEnabled(true)` at
     `BOOT_COMPLETED`, which tore down / prevented the SoftAP when no `--wifi-ssid` was provided.
     Fixed by gating `maybeEnableWifiClient()` on an `enable_wifi` marker file next to `base.apk`
     (planted only when `--wifi-ssid` is passed).
   - **Windows TCP `RST` on unread HTTP request headers:** `ProbeServerService` initially read only
     the HTTP request line and closed the socket with unread request headers still in the kernel
     receive buffer. Linux sent TCP `RST`, which macOS `curl` tolerated on loopback but caused
     Windows Chrome (blank page) and Windows `curl.exe` / Node `fetch` (`Connection reset`) to
     abort. Fixed by draining up to 64 header lines until the blank line, replying with `HTTP/1.1`,
     and calling `socket.shutdownOutput()` before closing.
   - **First-boot timing & SoftAP idle timeout:** Cold boot after flashing a fresh `userdata` takes
     ~4–5 minutes (stock `dex2oat` on Cortex-A53 / 1 GiB RAM; ~10–12 min total including USB sparse
     flash). Subsequent reboots take ~45 s. Stock's `mirror-<serial>` SoftAP times out after ~10
     minutes of idle OOBE; power-cycling brings it right back.

3. **No-ADB HTTP probe extraction over stock's `mirror-<serial>` SoftAP** (2026-11-22, verified)
   Flashed `userdata` with `--with-probe-apk` (no `--wifi-ssid`), waited for `mirror-<serial>`
   SoftAP, joined it from Windows, confirmed `http://192.168.43.1:18765/status`, then ran:
   ```
   $ npx --prefer-offline tsx@4.23.15 tools/mirror/stock-probe.ts --http-only --probe-host 192.168.43.1
   Step 5/6: connected to HTTP probe app (uid=10099, fiemap=true)
   Step 6/6: extracted via http (58 services, 141 vendor paths, 2 fiemap)
   ```
   The non-camera pass completed in 51 s over the SoftAP (`--prefer-offline` avoids npm network
   lookups while the PC is connected to the offline SoftAP). A second pass immediately afterward
   with `--include-camera --pull-system-blobs` audited all 462 vendor paths and pulled all 443
   readable stock vendor files (39 MB uncompressed) locally outside the repo.

4. **Off-device inspection of pulled metadata and `services.jar`** (2026-11-22, read from pull)
   Inspected pulled `getprop.txt`, `kernel.config`, `partitions.txt`, `fiemap.json`,
   `vendor-audit.tsv`, `ramdisk/*`, `key-system/system/build.prop`,
   `key-system/system/etc/init.qcom.post_boot.sh`, `pronto_wlan.ko` strings, and `services.jar`
   strings off-device. Nothing from the pull is committed.

5. **Phase 2 surgical USB ADB on untouched stock `1.24.0`** (2026-11-24, verified)
   With the Mirror booted into the Phase 1 `mirror-<serial>` SoftAP and connected over micro-USB:
   ```
   $ npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts
   ```
   - **Step 1 (read-only preflight over SoftAP):** confirmed `74` entries in `/system/framework`,
     `services.odex detected: no`, `15` entries in `/system/app`, `10` entries in `/system/priv-app`,
     single contiguous `FIEMAP` extents for `services.jar` (`block 40597 × 589`) and
     `init.qcom.post_boot.sh` (`block 22517 × 20`), and wrote byte-exact `restore-services.sparse`
     and `restore-post-boot.sparse` under `<out>/backup/`.
   - **Step 2 (local build):** patched `UsbDebuggingManager.startConfirmation()` to authorize only
     the tester's host ADB key (`EOCD pad: 3592 B`, exact `2,411,806` B) and built
     `patch-services.sparse` + `userdata-adb.sparse`.
   - **Step 3 (surgical flash & ADB verification):** first attempt tripped the partition-size gate
     cleanly (`system=1288491008 expected 1288413184`, zero bytes written). After fixing the default
     to `0x4cccd000` (`1,288,491,008` B), flashing `patch-services.sparse` + `userdata-adb.sparse`
     and rebooting brought up **authorized USB ADB** (`uid=2000(shell)`, `getenforce=Enforcing`,
     `ro.build.version.mirror=1.24.0`, `pm list packages` count: `27`).

6. **Dead end: Step 4 raw backup via `init.qcom.post_boot.sh` (`qti_init_shell`)** (2026-11-24, verified)
   Temporarily hooking `init.qcom.post_boot.sh` (20 blocks) to load an external local test module
   from `/data/local/tmp/` left SELinux `Enforcing` and block nodes unreadable (`Permission denied`).
   Live `adb shell dmesg` recorded:
   ```
   avc: denied { search } for pid=1510 comm="sh" name="tmp" dev="mmcblk0p29" ino=212994 scontext=u:r:qti_init_shell:s0 tcontext=u:object_r:shell_data_file:s0 tclass=dir permissive=0
   ```
   Parsing the pulled stock `1.24.0` `/sepolicy` (version 30, 923 types, 9,061 avtab rules) confirmed
   that `qti_init_shell` has **no** `capability:sys_module` rule on stock `1.24.0`, ruling out
   `init.qcom.post_boot.sh` for loading kernel modules on `1.24.0`.

7. **Live ADB process & `/sepolicy` capability audit on stock `1.24.0`** (2026-11-24, verified / read)
   Checked `/sepolicy` for `capability:sys_module` and `blk_file:read`, and ran read-only `ps -Z`,
   `/proc/<pid>/status`, `/proc/<pid>/stat`, `ls -lZ`, and `/system/etc/dpm/dpm.conf` checks over
   the new USB ADB session (details in Result §7 below).

8. **`dpmd` constructor hook, off-device** (2026-11-24, read from the pulled ELF64 library; constructor
   control flow **verified** in an arm64 container; **untested** on the panel)
   `qti_init_shell` cannot load a module, so the next process that can is `dpmd`: uid 0,
   `CAP_SYS_MODULE`, and it links `libdpmframework.so`. That library is mode `0644` `vendor_file`,
   so the shell can read it. `/proc/<pid>/maps` is not: `shell` fails `ptrace_may_access` before
   SELinux is consulted, and this kernel leaves `startstack` at 0, so procfs does not answer
   bitness. The ELF header does. On the live stock `1.24.0` unit:
   ```
   /system/vendor/lib/libdpmframework.so    1
   /system/vendor/lib64/libdpmframework.so  2
   ```
   `e_ident[EI_CLASS]` `1` is ELFCLASS32 (149,256 B) and `2` is ELFCLASS64 (223,232 B). `dpmd` links
   the lib64 copy. The patcher refuses the other one.

   [`tools/mirror/src/dpmd-hook.ts`](../../../tools/mirror/src/dpmd-hook.ts) keeps the file the same
   length and changes two regions: the addend of the `R_AARCH64_RELATIVE` relocation for
   `.init_array[0]`, and a payload written on a CIE/FDE boundary in the tail of `.eh_frame` (file
   offset `0x2ebe0`, 21,008 bytes, already mapped read+execute). The payload is a raw-syscall
   constructor (`init_module`, then `fchmodat` mode `0444` on `mmcblk0p22` and `mmcblk0p19`, then a
   tail jump to the constructor it replaced) followed by the caller's `.ko`. There is no code cave
   in the executable LOAD segment, and `.eh_frame` is the RX window that is only read by the
   unwinder. A module that does not fit is refused before a sparse image is built. The `.ko` is
   never pushed to the device; nothing GPL is committed to this repo.

   `stock-surgical-adb.ts --backup` builds `patch-dpmd.sparse` and `restore-dpmd.sparse` for that
   library and does not patch `init.qcom.post_boot.sh`. The module it embeds is the pinned
   `selinux_permissive.ko`, fetched by URL + SHA-256 (`tools/mirror/device-bins.json`); see the
   device-bins note below. That fetch runs before the device steps and has no offline cache, so the
   default `--backup` needs the host to have internet access — the Phase 2 SoftAP link to the Mirror
   is not enough on its own. `--backup-module <path>` is the offline path: it overrides the fetch
   with a local `.ko` the operator already has.

   The constructor was executed off-device on 2026-11-24: the patched payload from the pulled
   lib64 file, with a 256-byte stand-in image in place of a `.ko`, was mapped in
   `arm64v8/debian:bookworm-slim` and the initialiser it chains to was replaced with `exit(42)`.
   `strace` showed `init_module` on the embedded image, `fchmodat(AT_FDCWD, "/dev/block/mmcblk0p22",
   0444)` and the same for `mmcblk0p19`, then `exited with 42`. An earlier encoding subtracted the
   payload-relative offset instead of `payloadVaddr` and branched to the wrong address; this run is
   the check that the bias is `load_address - payloadVaddr`. Before the live run, a code review
   caught one more Linux 3.10 detail: `init_module`'s third argument (`uargs`) was initially `NULL`
   (`xzr`), which fails `strndup_user(uargs, ...)` with `-EFAULT`; changing that word to `adr x2, 0`
   (pointing at the zeroed `.eh_frame` terminator at payload offset `0`) passed `""` without
   changing the 35-instruction layout.

   The module that image embeds is not in this repo. A built `.ko` is a GPL payload (Hard Rule 4), so
   its source and the build recipe for the hash-pinned binary live in `GlassworksHQ/glassworks-device-bins`
   (`frames/lululemon-mirror/selinux-permissive/`, GPL-2.0-only). The `v1` release serves that
   binary with its Corresponding Source, and this tool consumes it by pinned release URL + SHA-256
   (#817). **Published 2026-11-27** from device-bins `main` (run 46086813279): the CI-built `.ko`
   matched the pin byte for byte (2512 B, `ee7b39cf…`), and `tools/check-device-bins.py
   --verify-release` passed. A real-Mirror load test of that exact build is still **untested**; the
   backup path itself was verified 2026-11-25 with a module built the same way. Stock `1.24.0` only loads one
   whose ABI matches its own `pronto_wlan.ko` (**verified** 2026-11-24, pulled file, not committed):
   ```
   vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64
   module_layout=0xa5b7f7dd
   ```
   Linux 3.10 `same_magic()` with `CONFIG_MODVERSIONS=y` compares the tail after the first space, so
   the `-g4869d64961b` prefix is not the check. `shihanqu/lululemon-mirror-root` (`mod/mirror_root.c`,
   `docs/ABI.md`, **read**) builds that ABI by copying `module_layout` from the vendor driver,
   taking `kallsyms_lookup_name = 0xe007de41` from `genksyms` (a primitive signature), and laying out
   `struct module` as `0x220` bytes with `init` at `0x120` and `exit` at `0x210` from the DWARF of
   an unstripped `pronto_wlan.ko`. Their `init` writes `0` to `selinux_enforcing` through
   `kallsyms_lookup_name`. The symbol is `EXPORT_SYMBOL_GPL`, so the module's license string has to
   be `GPL`. For this hook the module only has to clear enforcing: the constructor already runs
   `fchmodat` on the two block nodes. Their root daemon is not required.

9. **Live on-panel `dpmd` hook & full raw `system` + `boot` backup (`--backup-only`)** (2026-11-25, verified)
   With authorized USB ADB already active from Step 3, ran `--backup-only` over USB (`adb forward
   tcp:18765 tcp:18765`, no Wi-Fi SoftAP swap) with an external 2,560-byte module on the host:
   ```sh
   npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts \
     --backup-only --backup-dir <prev-out>/backup --backup-module <external>/selinux_permissive.ko
   ```
   - Pulled `/system/vendor/lib64/libdpmframework.so` (`223,232` B, `block 230356 × 55`, SHA-256
     `a488d2ae22f2d448…`), saved `restore-dpmd.sparse`, and built `patch-dpmd.sparse` (`.init_array[0]`
     → `0x33338`, chaining to `0xfd70`, payload `2,752` B).
   - Flashed `restore-post-boot.sparse` (clearing the earlier Step 4 dead end) + `patch-dpmd.sparse`,
     and rebooted. On the first try, the Mirror came back with **`getenforce=Permissive`** and
     **`blockReadable=true`**, confirming both that `dpmd` runs `libdpmframework.so`'s constructor as
     `uid=0` with `CAP_SYS_MODULE` and that `kallsyms_lookup_name = 0xe007de41` matches stock
     `1.24.0`'s kernel.
   - Streamed raw `system` (`1,288,491,008` B) and raw `boot` (`33,554,432` B) over USB ADB,
     scrubbed `services.jar` and `libdpmframework.so` back to their original stock bytes inside
     `system-stock.raw.img`, and automatically flashed `restore-dpmd.sparse` back (total elapsed
     time: **5 min 58 s**).
   - Off-device verification of the saved dumps (outside git):
     - `boot-stock.raw.img` (`33,554,432` B, SHA-256 `054bf05a86309db3de83a9818d2d4cd71d26148a84b0f13e00b2b1d8a52bd20c`):
       valid `ANDROID!` header (`page_size=2048`, `kernel=15,523,970` B, `ramdisk=1,061,787` B, `dt=0`).
     - `system-stock.raw.img` (`1,288,491,008` B, SHA-256 `febe653d74cd15329d1b11761fdf7b72fddc8c27202be6f7e5167f2d28acba51`):
       clean ext4 (`magic=0xef53`, `state=1`, `4096` B × `312,086` blocks, `78,080` inodes, volume
       `'system'`). Reading back the physical extents for `services.jar`, `init.qcom.post_boot.sh`,
       and `libdpmframework.so` matched their untouched stock SHA-256s byte-for-byte.

## Result

### 1. Stock `1.24.0` identity, kernel, and boot configuration (verified)

| Field | Stock `1.24.0` value | Compare: dev unit (Glass Panes `mirror-android-2`) |
|---|---|---|
| `ro.build.version.mirror` | `1.24.0` | absent |
| `ro.build.hwver.mirror` | `mirror-v1` | absent |
| `ro.build.fingerprint` | `mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/892:user/release-keys` | `Android/msm8916_64/msm8916_64:6.0.1/MMB29M/…:userdebug/test-keys` |
| `ro.bootimage.build.fingerprint` | `mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/892:user/test-keys` | `mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys` |
| `ro.build.date` | `Mon Oct 31 21:06:52 UTC 2022` (boot `21:17:03`) | — |
| `ro.build.version.security_patch` | `2016-02-01` | `2016-02-01` |
| `ro.product.cpu.abilist` | `arm64-v8a,armeabi-v7a,armeabi` | `arm64-v8a,armeabi-v7a,armeabi` |
| `persist.panel.orientation` / `qemu.hw.mainkeys` | `270` / `1` | — |
| `/proc/version` | `Linux version 3.10.49-perf-g4869d64961b … Mon Oct 31 21:18:30 UTC 2022` | `3.10.49-perf-gf46dad5260f` |
| `/proc/modules` | `wlan` (`4315399` B, `(O)`), `smsc75xx` (`19519` B) | `wlan` (`4312975` B, `(O)`) |
| `ro.secure` / `ro.adb.secure` / `ro.debuggable` | `1` / `1` / `0` | `1` / `1` / `0` |
| `ro.crypto.state` | `unencrypted` | `unencrypted` |
| `persist.sys.usb.config` (at runtime) | `mtp,adb` | `adb` |

Kernel flags from `/proc/config.gz` (verified):

```
CONFIG_LOCALVERSION="-perf"
CONFIG_IKCONFIG_PROC=y
CONFIG_KALLSYMS_ALL=y
CONFIG_MODULES=y
CONFIG_MODVERSIONS=y
# CONFIG_MODULE_SIG is not set
CONFIG_MSM_SCM=y
CONFIG_DM_VERITY=y
CONFIG_USB_NET_SMSC75XX=m
CONFIG_FB_MSM_LVDS_CHIMEI_WXGA_PANEL=y
CONFIG_SECURITY_SELINUX=y
# CONFIG_PID_NS is not set
# CONFIG_USER_NS is not set
```

### 2. No `dm-verity` active, and `FS_IOC_FIEMAP` works as `untrusted_app` (verified)

- `/proc/partitions` lists only `zram0`, `mmcblk0`, `mmcblk0p1`..`mmcblk0p29`, and `mmcblk0rpmb`
  (`mmcblk0p22` = `1,258,292` KiB = `0x4cccd000` `system`; `mmcblk0p29` = `5,452,596` KiB =
  `0x14cccd000` `userdata`). **There is no `dm-0` device** — `CONFIG_DM_VERITY=y` is compiled in,
  but stock `1.24.0` does **not** mount `/system` through dm-verity.
- `FS_IOC_FIEMAP` via arm64 `libfiemap.so` succeeded from `uid=10099` (`untrusted_app`) with zero
  SELinux issues, returning single contiguous extents (`flags=1`, `FIEMAP_EXTENT_LAST`):

| Path | Size (B) | Inode | Physical 4 KiB block | Blocks | Byte offset |
|---|---:|---:|---:|---:|---:|
| `/system/etc/init.qcom.post_boot.sh` | 80,815 | 496 | `22517` | `20` | `92,229,632` |
| `/system/build.prop` | 7,101 | 382 | `21093` | `2` | `86,396,928` |
| `/system/framework/services.jar` | 2,411,806 | — | `40597` | `589` | `166,285,312` |
| `/system/vendor/lib64/libdpmframework.so` | 223,232 | — | `230356` | `55` | `943,538,176` |

Because `/system` is not dm-verity protected and `fastboot flash system` with Android sparse
`DONT_CARE` chunks skips untouched blocks, **surgical in-place patching of individual `/system`
files (such as `services.jar` at blocks `40597..41185`) works cleanly on untouched stock `1.24.0`**,
with a byte-exact rollback using the original blocks read over HTTP beforehand.

### 3. `pronto_wlan.ko` kernel git hash vs matching ABI CRCs (verified)

- Stock `1.24.0`'s `/system/lib/modules/pronto/pronto_wlan.ko` is **5,778,672 B** (stripped, SHA-256
  `1894373d68175b0f54fc7a0a51ede96c18b59b02b5b2935778821c73ef636356`), with:
  ```
  license=Dual BSD/GPL
  vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64
  __versions: module_layout=0xa5b7f7dd, printk=0x27e1a049, __kmalloc=0xd2b09ce5
  ```
- Compare our dev Mirror ([mirror-root.md](mirror-root.md) §4): `pronto_wlan.ko` is a 70 MB debug
  build for `3.10.49-perf-gf46dad5260f`, carrying the **exact same CRCs** (`module_layout=0xa5b7f7dd`,
  `printk=0x27e1a049`, `__kmalloc=0xd2b09ce5`).
- In Linux 3.10 (`kernel/module.c`), `same_magic(modmagic, vermagic, has_crcs)` skips the kernel
  version prefix before the first space whenever `CONFIG_MODVERSIONS=y` and compares only
  `SMP preempt mod_unload modversions aarch64` plus the `__versions` symbol CRCs. That explains why
  community reports show Glass Panes' `pronto_wlan.ko` loading cleanly on `1.24.0`'s `g4869d64961b`
  kernel despite the different git hash. Keeping `1.24.0`'s own 5.78 MB `pronto_wlan.ko` (or
  running stock-in-place) remains even cleaner and 64 MB smaller.

### 4. Stock `adbd`, `init.qcom.post_boot.sh`, `services.jar`, and `MirrorApService` (verified / read)

- **Line 1504 (last line) of stock `/system/etc/init.qcom.post_boot.sh`:**
  ```sh
  setprop persist.sys.usb.config mtp,adb
  ```
  Stock `1.24.0` already runs `qcom-post-boot` on boot (`init.svc.qcom-post-boot=stopped`) and
  enables `adbd` (`init.svc.adbd=running`), which is why `persist.sys.usb.config` was `mtp,adb`
  even after our `userdata` planted `adb`.
- **Stock `services.jar`** (`2,411,806` B, SHA-256 `fe16a79876b4b7a3...`) is **un-odexed**
  (`classes.dex` inside, `5,372,396` B) and world-readable:
  - **0 hardcoded MD5 fingerprints** in `UsbDebuggingManager` (standard AOSP; only Glass Panes
    patched in a hardcoded owner fingerprint). Combined with Step 1's `unauthorized` result, this
    confirms stock `1.24.0` `adbd` rejects `/data/misc/adb/adb_keys` on its own.
  - Contains `com.android.server.MirrorApService`, which drives `/system/bin/mapd` (`running`) and
    `hostapd` to bring up the `mirror-<serial>` SoftAP at `192.168.43.1` (DHCP `192.168.43.2`–`254`).
    With Glassworks in place this hotspot gets in the way, so the in-place patch empties
    `MirrorApService.systemReady()`: [stock-softap.md](stock-softap.md).
- **Init services (`init.svc.*`, 58 entries):** confirms `qcom-post-boot` (`stopped`),
  `flash_recovery` (`stopped`), and Mirror-specific `mapd` (`running`) and `cfgchkup` (`stopped`).
  `file_contexts` under `device/mirror/mirror/sepolicy/file_contexts` labels `/system/bin/mapd`
  (`mapd_exec`), `/system/bin/cfgchkup` (`cfgchk_exec`), `/data/system/hostapd(/.*)?`
  (`hostapd_ctrl_file`), `/data/ota_package(/.*)?` (`ota_file`), `mmcblk0p19` (`boot_block_device`),
  and `mmcblk0p20` (`recovery_block_device`).

### 5. Option C vendor file audit — all 6 parts including camera (verified)

Compared all 462 paths from [option-c-vendor-files.tsv](option-c-vendor-files.tsv) over HTTP as
`uid=10099` (`untrusted_app`):

| Part | Total | Match Glass Panes | Differs (stock-specific) | Missing | Denied (SELinux/DAC) |
|---|---:|---:|---:|---:|---:|
| `gpu-display` | 40 | 4 | 35 | 0 | 1 |
| `platform` | 31 | 0 | 20 | 1 | 10 |
| `wifi-bt` | 17 | 0 | 14 | 1 | 2 |
| `audio` | 36 | 21 | 12 | 3 | 0 |
| `video` | 17 | 1 | 16 | 0 | 0 |
| `camera` | 321 | 8 | 312 | 0 | 1 |
| **Total (without camera)** | **141** | **26** | **97** | **5** | **13** |
| **Total (all parts)** | **462** | **34** | **409** | **5** | **14** |

- **443/462** (39 MB uncompressed; **123/141** non-camera, **320/321** camera) read cleanly over
  HTTP without root (`34` exact SHA-256 matches to Glass Panes — Adreno `a300`/`a330` microcode,
  all 21 `acdbdata` files, `venus.b04`, and 8 camera blobs; `409` stock-specific Qualcomm BSP
  builds).
- **14 denied** — the **exact 14 `*_exec` daemons** predicted by SELinux policy analysis in
  [option-c.md](option-c.md): `mm-pp-daemon`, `irsc_util`, `msm_irqbalance`, `pm-service`,
  `qcom-system-daemon`, `qmuxd`, `qseecomd`, `rmt_storage`, `tftp_server`, `time_daemon`,
  `thermal-engine`, `cnss-daemon`, `wcnss_service`, and `mm-qcamera-daemon`.
- **5 missing** on stock `1.24.0`: `/system/vendor/lib64/libthermalioctl.so`,
  `/system/vendor/lib/libqmi_client_qmux.so`, `/system/vendor/lib/libsurround_proc.so`,
  `/system/vendor/lib64/libacdbrtac.so`, `/system/vendor/lib64/libadiertac.so`.

### 6. Key `/system` and ramdisk hashes (verified)

| Path | Bytes | SHA-256 |
|---|---:|---|
| `/sepolicy` | 227,066 | `da9befd94097…` |
| `/file_contexts` | 47,492 | `e233b9c9a93a…` |
| `/seapp_contexts` | 2,187 | `4857ee6f986a…` |
| `/property_contexts` | 7,429 | `10a44885a682…` |
| `/service_contexts` | 13,780 | `20f720392cf5…` |
| `/default.prop` | 588 | `6085525992a8…` |
| `/system/build.prop` | 7,101 | `c42c33df06ca78faae12ad20ba29558f26472c2f2cb265d969fc1188a9dcf0f8` |
| `/system/etc/init.qcom.post_boot.sh` | 80,815 | `b7f44b33e82bc0df4277f9730fb7ded47a06c03c37bf564b0d7505c23ec3e1ab` |
| `/system/lib/modules/pronto/pronto_wlan.ko` | 5,778,672 | `1894373d68175b0f54fc7a0a51ede96c18b59b02b5b2935778821c73ef636356` |
| `/system/framework/services.jar` | 2,411,806 | `fe16a79876b4b7a36fe2865c6009705a06e0e267705dd9c5622bd73db8f63b0c` |
| `/system/vendor/lib64/libdpmframework.so` | 223,232 | `a488d2ae22f2d44896f7ff3e7d56ae44bebf3f6e85a90c595c27e275a219b8b0` |
| `boot` (`mmcblk0p19`, raw stock) | 33,554,432 | `054bf05a86309db3de83a9818d2d4cd71d26148a84b0f13e00b2b1d8a52bd20c` |
| `system` (`mmcblk0p22`, scrubbed raw stock) | 1,288,491,008 | `febe653d74cd15329d1b11761fdf7b72fddc8c27202be6f7e5167f2d28acba51` |

Note: `/system/lib/modules/adsprpc.ko` and `/system/xbin/su` are both `missing` (so the `adsprpc.ko`
slot from [community-root-and-network-adb.md](community-root-and-network-adb.md) is also free on
stock `1.24.0`). `dt-panel.txt` returned empty (`0` bytes) because `untrusted_app` could not list
`/proc/device-tree`.

### 7. Phase 2 surgical USB ADB, stock app inventory, and `/sepolicy` audit (verified / read)

- **Authorized USB ADB on stock `1.24.0` (2026-11-24, verified):**
  ```
  USB ADB authorized: YES
  id: uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),1011(adb),1015(sdcard_rw),1028(sdcard_r),3001(net_bt_admin),3002(net_bt),3003(inet),3006(net_bw_stats) context=u:r:shell:s0
  getenforce: Enforcing
  ro.build.version.mirror: 1.24.0
  pm list packages count: 27
  ```
- **No `services.odex` (2026-11-24, verified):** `/system/framework` has `74` entries and no
  `services.odex` anywhere under `/system/framework` or `/system/framework/oat`.
- **Lean stock `1.24.0` app inventory (2026-11-24, verified):**
  - `/system/app` (`15` entries): `Bluetooth`, `CertInstaller`, `KeyChain`, `NetworkSetting`,
    `Remover`, `co.mirror.cw`, `co.mirror.datacap`, `co.mirror.elevator`, `com.mirror.launcher`,
    `com.mirror.mirrorupdater`, `com.mirror.services`, `com.mirror.watchdog`, `fastdormancy`,
    `shutdownlistener`, `telresources`.
  - `/system/priv-app` (`10` entries): `DefaultContainerService`, `DownloadProvider`,
    `ExternalStorageProvider`, `FusedLocation`, `MediaProvider`, `PackageInstaller`, `Settings`,
    `SettingsProvider`, `Shell`, `qcrilmsgtunnel`.
- **Kernel command line & SELinux policy audit (2026-11-24, verified / read):**
  - Live `dmesg` confirms stock `1.24.0`'s kernel command line includes
    `androidboot.selinux=permissive` and `androidboot.authorized_kernel=true` (like the dev unit in
    [mirror-root.md](mirror-root.md), the `user` build's `init` still enforces SELinux once
    `/sepolicy` loads).
  - Parsing the pulled stock `1.24.0` `/sepolicy` (version 30, 923 types, 9,061 avtab rules) found:
    - Only 5 concrete domains hold `capability:sys_module`: `init`, `dpmd`, `qlogd`, `mmi`, and
      `system_server`. `qti_init_shell` does **not** hold `capability:sys_module` and cannot search
      `shell_data_file` (`/data/local/tmp`), ruling out `init.qcom.post_boot.sh` for raw partition
      dumps on stock `1.24.0`.
    - Only `install_recovery` holds `blk_file:read` on `boot_block_device` (`mmcblk0p19`), and
      `rmt_storage` holds `blk_file:read` on `root_block_device` (`mmcblk0`), though `ps -Z` shows
      `rmt_storage` runs unprivileged as `nobody`.
  - Live `ps -Z` and `/proc/<pid>/status` on stock `1.24.0` observed `/system/bin/dpmd` running at
    boot as `u:r:dpmd:s0` (`uid=0`, `CapEff: 0000001fffffffff`) with a forked `system` child, and
    five `u:object_r:system_file:s0` DPM libraries present in both `/system/vendor/lib64/` and
    `/system/vendor/lib/` (`libdpmctmgr.so`, `libdpmfdmgr.so`, `libdpmframework.so`, `libdpmnsrm.so`,
    `libdpmtcm.so`). For daily Glassworks operation on stock `1.24.0`, root is **not** needed: the
    `services.jar` USB ADB hook stays SELinux `Enforcing` and is MIT-licensed.
- **Which `libdpmframework.so` is 64-bit (2026-11-24, verified on the unit):**
  `od -An -tu1 -j4 -N1` of the two copies returned `1` for `/system/vendor/lib/libdpmframework.so`
  (ELFCLASS32, 149,256 B) and `2` for `/system/vendor/lib64/libdpmframework.so` (ELFCLASS64,
  223,232 B). The hook targets the lib64 file. File size is not the check; `EI_CLASS` is.

## How to reproduce

### Phase 1 — Read-only `userdata`-only probe (`stock-probe.ts`)

Using [`tools/mirror/stock-probe.ts`](../../../tools/mirror/stock-probe.ts):

1. **Enter fastboot** (hold `VOL−` / `SW3` while switching power on for ~5 s) and flash `userdata`
   with the prebuilt probe APK (writes `userdata` **only**; wipes stock cloud pairing/onboarding,
   leaves `system` and all other partitions untouched):
   ```sh
   npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --with-probe-apk
   ```
   Accept reusing `tools/mirror/stock-probe-app/prebuilt/` when prompted.
2. **Wait ~4–5 minutes** on first boot for Android 6 `dex2oat` and `MirrorApService` to broadcast
   `mirror-<serial>`. Join `mirror-<serial>` from your computer (gateway `192.168.43.1`).
3. **Extract (read-only over HTTP)** — use `--prefer-offline` while on the offline SoftAP:
   ```sh
   npx --prefer-offline tsx@4.23.15 tools/mirror/stock-probe.ts --http-only --probe-host 192.168.43.1
   ```
   Add `--include-camera --pull-system-blobs` to include the 321 camera files and save readable
   blobs locally outside the repo.
4. **Undo / return to clean stock `userdata`:** re-enter fastboot and run
   `fastboot erase userdata && fastboot format userdata` (or flash an empty ext4 `userdata`), which
   removes the probe APK while leaving untouched stock `system` intact. Note that stock cloud
   onboarding was already wiped in Step 1.

### Phase 2 — Reversible surgical USB ADB (`stock-surgical-adb.ts`)

With the Phase 1 probe app reachable on `mirror-<serial>` (`192.168.43.1:18765`) and micro-USB
connected:

```sh
# Read-only preflight + save byte-exact restore-*.sparse undo images
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --preflight-only

# Steps 1-3: enable reversible authorized USB ADB (SELinux Enforcing, keeps ADB active)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --keep-adb

# Revert system back to 100% untouched stock anytime (add --erase-userdata for factory reset)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --undo --backup-dir <out>/backup

# Re-apply the saved USB ADB hook later from the backup folder without Wi-Fi AP
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --apply-adb --backup-dir <out>/backup

# Step 4 raw system + boot backup over USB only (when authorized USB ADB is already active; uses adb forward)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --backup-only --backup-dir <out>/backup --backup-module <external-ko>
```

## Open questions

1. **Full raw `system` / `boot` backup from untouched stock `1.24.0` — solved (2026-11-25, verified):**
   The `dpmd` constructor hook in [`tools/mirror/src/dpmd-hook.ts`](../../../tools/mirror/src/dpmd-hook.ts)
   (driven by `--backup-module <ko>` or USB-only `--backup-only`) loaded the external module on the
   first boot (`getenforce=Permissive`, `blockReadable=true`), streamed raw `system` (`1,288,491,008` B)
   and `boot` (`33,554,432` B) cleanly, scrubbed all patched extents back to verified untouched stock
   hashes, and restored `libdpmframework.so`. Both raw images and the external `.ko` stay strictly
   outside the repo.
2. **Does Glass Panes `mirror-android-2` / `mirror-android-3` break Wi-Fi on `1.24.0` units?**
   Even though `__versions` CRCs match (`module_layout=0xa5b7f7dd`, `printk=0x27e1a049`,
   `__kmalloc=0xd2b09ce5`) and Linux 3.10's `same_magic()` ignores the `-perf-g...` prefix under
   `CONFIG_MODVERSIONS=y`, diff every `__versions` CRC between `1.24.0`'s `pronto_wlan.ko` and
   Glass Panes' `pronto_wlan.ko` (or test on a `1.24.0` unit) to be 100% certain.
3. **Zero-Wi-Fi-swap transport & Stock-in-place Kiln checks (next on a stock `1.24.0` unit with live
   USB ADB):**
   - **MTP over the existing micro-USB cable — ruled out (2026-11-25, verified):** stock `1.24.0`
     sets `persist.sys.usb.config=mtp,adb` and `persist.fuse_sdcard=true`, and `MtpService` starts
     when `device_provisioned=1`, but `UsbDeviceManager` keeps `mUsbDataUnlocked=false` until a
     screen tap, so `GetStorageIDs` returns **0 storages** on untouched stock OOBE.
   - **Home Wi-Fi fallback (untested end to end):** pre-saving the user's home Wi-Fi in Stage 1 `userdata` +
     `enable_wifi` marker to see if stock joins home Wi-Fi cleanly instead of `mirror-<serial>`
     SoftAP. Kiln now takes this path on a reinstall over Glassworks, whose patched services.jar has no
     SoftAP. Checked without USB on the dev Mirror (2026-11-26, Glassworks installed in place, network adb):
     ```
     $ adb shell 'id -u; pm list packages dev.glassworks.generic_android.legacy; echo "exit=$?"; ps | grep -c hostapd'
     2000
     package:dev.glassworks.generic_android.legacy
     exit=0
     0
     $ adb shell cat /system/etc/wifi/wpa_supplicant.conf
     update_config=1
     eapol_version=1
     ap_scan=1
     fast_reauth=1
     pmf=1
     p2p_no_group_iface=1
     ```
     - **Verified:** as `shell` (uid 2000), the frame's `ownWifiGone` command prints exactly the one
       `package:` line it looks for, with exit 0.
     - **Verified:** no `hostapd` runs (count 0).
     - **Verified:** the template is the stage's `DEFAULT_WIFI_TEMPLATE` line for line; `inplace.test.ts`
       keeps this file as `MIRROR_WIFI_TEMPLATE` and fails if the constant changes.
     - **Read:** the agent's `CHANGE_WIFI_STATE`, its `wlan*` address for `lanIp`, and the CORS and
       `Access-Control-Allow-Private-Network` headers Chrome's local network access needs.
     - **Still untested on a Mirror:** the agent joining the network on the stage's first boot, and
       Kiln's local network fetch to it. Bluetooth stays the fallback for the transfer.
   - **Stock-in-place viability — verified (2026-11-25):** `/system/framework/oat/arm64/services.odex`
     is absent (2026-11-24), and the live Track A run on the dev Mirror answered the rest. (a) `pm
     disable-user` throws `SecurityException` as `shell`, but **`pm hide` works on all 8 lululemon
     apps** (`co.mirror.cw`, `co.mirror.datacap`, `co.mirror.elevator`, `com.mirror.launcher`,
     `com.mirror.mirrorupdater`, `com.mirror.remover`, `com.mirror.services`, `com.mirror.watchdog`),
     survives reboot, and leaves **663 MB free RAM** (`337 MB` with Glassworks + GeckoView running).
     (b) The backlight drops `200 → 0` on sleep. (c) `/system/media/bootanimation.zip` is `22,901` B,
     `1080×1920` portrait (Track B). (d) **Silent updates come from a Device Owner, not a priv-app
     slot:** an 8-byte `services.jar` patch (`mHasFeature = true` + NOP `setBackupServiceActive`) lets
     `dpm set-device-owner` succeed with SELinux **Enforcing**, and a live `PackageInstaller`
     self-update (`v1 → v2`) ran with zero taps. The browser-safe core for this —
     `patchStockServicesJar` with the Device Owner fixes, `buildSurgicalSparseImage`, the `GWSB` Stage 1
     bundle, and `hideStockBloat`/`unhideStockBloat` — lives in
     [`frames/lululemon-mirror/installer/src/stock.ts`](../installer/src/stock.ts), shared by Kiln and
     the Node tool. USB MTP (`mUsbDataUnlocked=false` → 0 storages) and AOA v2 are dead ends, so the
     Stage 1 transport stays the SoftAP HTTP probe (or USB `adb forward`), not MTP.
   - **Kiln's way back to stock (untested on a unit, 2026-11-24):** everything Kiln's app install
     changes (the APK in `/data/app`, `device_owner.xml`, the `pm hide` state in
     `package-restrictions.xml`, the preferred home activity, `user_rotation`) lives in `userdata`, so
     Kiln's undo writes `restore-services.sparse` and then an empty ext4 `userdata` (root `0771
     system:system`, `lost+found` only), the browser's version of `--undo --erase-userdata`. The
     install's own fresh `userdata` is built the same way plus `/misc/adb`, so stock should take it
     on first boot as after a factory reset, but no unit has booted Kiln's empty one yet.
4. **Trust model for binding the SoftAP probe to the fastboot flash target — partially closed
   (2026-11-25, read):** a compatible but different Mirror in fastboot could otherwise receive
   writes computed from another unit's FIEMAP offsets (CodeRabbit, #769). `stock-surgical-adb.ts`
   now hashes `androidboot.serialno` out of `/proc/cmdline` over the Step 1 SoftAP session (never
   the raw serial, per Hard Rule 2) and checks it against `fastboot getvar serialno` before Step 3
   flashes, and `--undo`/`--apply-adb` check the same hash against the manifest before the first
   restore write. This degrades to no check when `/proc/cmdline` isn't readable over HTTP or the
   bootloader doesn't answer `getvar serialno` — a stronger binding (e.g. a signed probe response)
   is future work. Separately, Step 3 always flashes a fresh `userdata` over Phase 1's probe
   `userdata` (#759); there is no "precious" prior userdata to preserve by Phase 2 (Phase 1 already
   replaced the factory one), so `--undo` intentionally does not restore `userdata` — only `--erase-userdata`
   touches it, as a factory-reset option, not a restore.
