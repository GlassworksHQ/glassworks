---
topic: access
evidence: verified
---

# Stock 1.24.0 Mirror probe: userdata-only code execution, vendor inventory, and `FS_IOC_FIEMAP`

**Status (2026-11-22): verified on an untouched stock `1.24.0` Mirror.** Writing only `userdata`
(leaving `system`, `boot`, `recovery`, `modem`, and `persist` untouched) runs our plain
`untrusted_app` HTTP probe (`dev.glassworks.stockprobe`, `uid=10099`) automatically at
`BOOT_COMPLETED` with zero ADB and zero touchscreen, reachable over stock's own `mirror-<serial>`
setup SoftAP (`192.168.43.1:18765`). The probe extracted `/proc/config.gz`, ramdisk SELinux files,
`init.svc.*`, `services.jar`, `pronto_wlan.ko`, `FS_IOC_FIEMAP` physical block extents, and the
full 462-file Option C vendor audit including camera (**443/462** readable without root — 39 MB
pulled cleanly; exact same 14 `*_exec` daemons denied as predicted in [option-c.md](option-c.md)).
Two high-impact findings: stock `1.24.0` has **no active `dm-verity`** (single contiguous `FIEMAP`
extents for `/system/etc/init.qcom.post_boot.sh` and `/system/build.prop`), and it runs a
**different kernel build** (`3.10.49-perf-g4869d64961b`) than the dev unit (`gf46dad5260f`), so
Wi-Fi on a `1.24.0` boot image requires `1.24.0`'s own `pronto_wlan.ko`.

Device: untouched stock lululemon Studio Mirror (`MSM8916`, `variant: SBC eMMC`, `secure: yes`,
`Device unlocked: false`, `ro.build.version.mirror=1.24.0`, `ro.build.hwver.mirror=mirror-v1`).
Host: Windows PC (community tester), connected over micro-USB for fastboot and Wi-Fi to stock's
`mirror-<serial>` SoftAP for HTTP extraction.
Tools: [`tools/mirror/stock-probe.ts`](../../../tools/mirror/stock-probe.ts),
[`tools/mirror/stock-probe-app/`](../../../tools/mirror/stock-probe-app/), Android SDK `fastboot`
and `adb`, `@glassworks/ext4`.

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

Because `/system` is not dm-verity protected and `fastboot flash system` with Android sparse
`DONT_CARE` chunks skips untouched blocks, **surgical in-place patching of
`/system/etc/init.qcom.post_boot.sh` (blocks `22517..22536`) is feasible on untouched stock
`1.24.0`**, with a byte-exact rollback using the original 20 blocks already read over HTTP.

### 3. `pronto_wlan.ko` kernel mismatch across Mirror stock revisions (verified)

- Stock `1.24.0`'s `/system/lib/modules/pronto/pronto_wlan.ko` is **5,778,672 B** (stripped, SHA-256
  `1894373d68175b0f54fc7a0a51ede96c18b59b02b5b2935778821c73ef636356`), with:
  ```
  license=Dual BSD/GPL
  vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64
  ```
- Compare our dev Mirror ([mirror-root.md](mirror-root.md) §4): `pronto_wlan.ko` is a 70 MB debug
  build for `3.10.49-perf-gf46dad5260f`.
- Because `CONFIG_MODVERSIONS=y` and `CONFIG_MODULE_FORCE_LOAD` is unset, **a `pronto_wlan.ko`
  built for `gf46dad5260f` will not load on a `1.24.0` Mirror's `g4869d64961b` kernel**. For
  Option C (or any custom `system.img` targeting diverse stock `boot` partitions), pulling the
  device's own `/system/lib/modules/pronto/pronto_wlan.ko` (which is world-readable) is required to
  keep Wi-Fi working across stock boot revisions.

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

Note: `/system/lib/modules/adsprpc.ko` and `/system/xbin/su` are both `missing` (so the `adsprpc.ko`
slot from [community-root-and-network-adb.md](community-root-and-network-adb.md) is also free on
stock `1.24.0`). `dt-panel.txt` returned empty (`0` bytes) because `untrusted_app` could not list
`/proc/device-tree`.

## How to reproduce

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

## Open questions

1. **Reading the 14 SELinux-denied daemons (and full `system` / `boot` backup):** `untrusted_app`
   and `shell` cannot read the 14 `*_exec` daemons ([option-c.md](option-c.md)), and `qti_init_shell`
   cannot read `*_exec` or block devices either ([mirror-root.md](mirror-root.md) §5, §7). Because
   `FS_IOC_FIEMAP` now gives us exact physical blocks for `/system/etc/init.qcom.post_boot.sh` and
   `CONFIG_MODULE_SIG` is off, the two next paths to test on stock are:
   - **Surgical `services.jar` + `post_boot.sh` sparse patch** (blocks from `FIEMAP`, fully
     reversible by flashing back the original blocks) to get authorized USB ADB + `qti_init_shell`
     on untouched stock without replacing `system` (and test running Glassworks directly on stock).
   - **Loading a kernel module built against `g4869d64961b`'s `vermagic`/CRCs** (or testing
     `flash_recovery` → `/system/bin/install-recovery.sh` for `boot`) to read the 14 denied daemons
     or take a full bit-for-bit `system` + `boot` backup before flashing Glassworks.
2. **Does Glass Panes `mirror-android-2` / `mirror-android-3` break Wi-Fi on `1.24.0` units?**
   Stock `1.24.0`'s boot kernel is `3.10.49-perf-g4869d64961b`, whereas Glass Panes ships a
   `pronto_wlan.ko` for `gf46dad5260f`. Verify whether `1.24.0` units need their own `pronto_wlan.ko`
   transplanted into `system.img` during Kiln install (or whether Glass Panes image 3 already
   handles multiple `vermagic` builds).
