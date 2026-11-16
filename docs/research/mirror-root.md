# Mirror root: can we get root on the lululemon Mirror dev unit, and what's the cheapest safe route?

**Status (2026-11-15): partly answered, nothing flashed.** The cheapest planned route (an AOSP `su`
in `/system/xbin/su`) is a **dead end**: the signed boot image is a `user` build, SELinux is
enforcing, and its policy has no `su` domain and no `shell → su_exec` transition. But we found a
**verified no-flash, no-root lever**: the build is `test-keys`, so a platform-signed app runs as
uid 1000 (`system_app`) and that alone covers much of the kiosk control and some of #40. Three
`/system`-only root leads remain open (all untested, all need a `system` flash): a custom kernel
module, re-enabling `flash_recovery`, and hooking `init.qcom.post_boot.sh`. No bootloader unlock,
no modem/oem/persist/boot writes were done; the unit was left exactly as found.

Device: lululemon Studio Mirror (MSM8916 / APQ8016E, Android 6.0.1), running the community
**Glass Panes `mirror-android-1`** image (`userdebug`, `test-keys`), stock signed `boot`
(`IFC-6309-2.0-MIR.S22Q401`, a `user`/`test-keys` build). The S1 panel and a tablet are on the same
Mac, so every command used `adb -s <serial>` (serial read from `/tmp/mirror-serial`, never printed).
Tools: `adb`, `fastboot`, Android SDK build-tools 35.0.0 (`aapt2`, `d8`, `apksigner`, `zipalign`),
`llvm-readelf`/`llvm-objcopy`, `magiskpolicy` from Magisk v28.1 (used off-device only, not committed;
see below), `setools`/`checkpolicy` in a Debian container.

## Question

Can we get root on this dev unit, by the cheapest safe route, writing `system` only (the maintainer's
2026-11-15 go-ahead covers `system`/`userdata`, not bootloader unlock or modem/oem/persist/boot)?
Root unblocks #40 (reading the stock vendor blobs: modem/wcnss firmware, Adreno, camera) and #41
(fastboot), and lets us back up the partitions we can't read today. If full root isn't cheap, what
does a lesser foothold (platform signing) already give us?

## What we tried

All on 2026-11-15 unless noted. Nothing was written to the device; every probe is read-only adb as
`shell`, or an `adb install`/`uninstall` of a test app to `/data/app` (no `system` touched).

### 1. Unit identity and state (verified)

```
$ adb -s <serial> shell getprop ro.build.fingerprint
Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11041605:userdebug/test-keys
$ adb -s <serial> shell getprop ro.bootimage.build.fingerprint
mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys
$ adb -s <serial> shell dumpsys package com.nimrod.mirrorkiosk | grep versionName
    versionName=0.12.0
```

The **system** is `userdebug`; the **boot image** is a `user` build. That split is the whole story:
`init` and the SELinux policy live in the signed boot ramdisk, and they are `user`-grade.

### 2. The `shell` foothold, SELinux, and secure props (verified)

```
$ adb -s <serial> shell 'id; getenforce'
uid=2000(shell) ... context=u:r:shell:s0
Enforcing
$ adb -s <serial> shell 'for p in ro.debuggable ro.secure ro.adb.secure ro.boot.selinux; do echo $p=$(getprop $p); done'
ro.debuggable=0
ro.secure=1
ro.adb.secure=1
ro.boot.selinux=permissive          # on the cmdline, but a user build ignores it: getenforce=Enforcing
$ adb -s <serial> shell 'grep Cap /proc/self/status'
CapBnd: 00000000000000c0            # shell bounding set: only setgid+setuid, nothing useful
```

`shell` is uid 2000, enforcing SELinux, no capabilities. `/system` mounts `ro`, `/data` `rw,nosuid,nodev`.

### 3. The SELinux policy (verified, pulled and analysed off-device)

`/sepolicy`, `/file_contexts`, `/seapp_contexts`, `/property_contexts`, `/service_contexts`,
`/default.prop`, `/selinux_version` are all world-readable in the ramdisk and were pulled to the
research dir (hashed). Current `setools` 4.5.1 and `checkpolicy` could not load this Android-6 binary
policy (`avtab_read_item: invalid specifier` — the old ioctl/xperm encoding). So the rules were
dumped on-device with `magiskpolicy`:

```
$ adb -s <serial> shell '/data/local/tmp/mp --load /sepolicy --print-rules' > sepolicy-rules.txt   # 10,038 rules
```

- **No `su` domain. No permissive domains. No `shell → su_exec` transition.** `grep` of the dump:
  `type su_exec { file_type exec_type }` exists (the label), and `file_contexts` maps
  `/system/xbin/su → su_exec`, but there is **no `su` type, no `permissive` line, and no
  `type_transition shell su_exec process`**. A setuid `su` would at best give uid 0 still inside the
  `shell` domain — useless under this policy.
- `dmesg` confirms the policy denies `shell` even reading the `su` exec: `avc: denied { ... }
  tcontext=u:object_r:su_exec:s0 tclass=file scontext=u:r:shell:s0`.

**So the planned cheapest route (AOSP `su` at `/system/xbin/su`) is ruled out.** `mirror-android-2`
changes only `system` (its boot/ramdisk/policy are the same signed image), so this applies there too.

### 4. Kernel config and the module lever (verified)

```
$ adb -s <serial> exec-out cat /proc/config.gz > config.gz && gunzip -c config.gz > kernel.config
# CONFIG_MODULE_SIG is not set         <- unsigned modules load
CONFIG_MODVERSIONS=y                   <- but vermagic + per-symbol CRCs must match exactly
CONFIG_MODULE_FORCE_LOAD not set       <- can't force a mismatched module
CONFIG_KALLSYMS_ALL=y
# CONFIG_KEXEC                         <- absent (3.10 arm64 has no kexec; see mirror-android.md)
# CONFIG_STRICT_MEMORY_RWX is not set  <- kernel text is patchable from a module
# CONFIG_CC_STACKPROTECTOR is not set
# CONFIG_DEVMEM / DEVKMEM / PROC_KCORE not set
$ adb -s <serial> shell cat /proc/modules
wlan 4312975 0 - Live 0x0000000000000000 (O)
```

The Wi-Fi driver is a loadable module. Pulled `/system/lib/modules/pronto/pronto_wlan.ko` (a 70 MB
debug build) and read its ELF (not committed):

```
vermagic = 3.10.49-perf-gf46dad5260f SMP preempt mod_unload modversions aarch64
license  = Dual BSD/GPL
__versions: module_layout=0xa5b7f7dd, printk=0x27e1a049, __kmalloc=0xd2b09ce5, ...
```

So a kernel module we build loads **only** if its `vermagic` and symbol CRCs match this kernel. That
needs the matching CAF `msm-3.10` source + this `.config` + the kernel's `Module.symvers` (or the
exact CRCs from this `.ko`). The module is loaded by the Wi-Fi HAL / `cnss-daemon` through the
`subsys-pil-tz` path (`dmesg`: `wcnss_wlan triggered by userspace` → `subsys-pil-tz ... pronto`), not
by an `insmod` in `init`, so a replacement must chain-load the original to keep Wi-Fi.

### 5. Root-domain leads from the policy (verified reads; the routes are untested)

What runs from `/system` (which we control — no dm-verity, `system` mounts `ro` but we can reflash
it) as a root-ish domain:

- **`qcom-post-boot` → `/system/etc/init.qcom.post_boot.sh`** (0644, `system_file`, world-readable;
  pulled). `init` transitions `shell_exec → qti_init_shell`. That domain holds
  `capability { chown dac_override dac_read_search fowner fsetid net_admin sys_module }` and may
  `execute_no_trans` `system_file`/`toolbox_exec`/`shell_exec`, write `sysfs`, and read/write
  `system_data_file` dirs. So replacing `post_boot.sh` runs our code **as root in `qti_init_shell`**.
  Limits: it fires on `boot_completed` (late), and the domain has **no** block-device access and
  **no** `setenforce`. Its `sys_module` cap + the §4 module could load a matching `.ko`.
- **`flash_recovery` → `/system/bin/install-recovery.sh`** is **disabled only because the file is
  missing** (`dmesg`: `init: cannot find '/system/bin/install-recovery.sh', disabling
  'flash_recovery'`). `file_contexts` already maps that path → `install_recovery_exec`, and the
  `install_recovery` domain can **read `boot_block_device`** and **read/write
  `recovery_block_device`** (`allow install_recovery boot_block_device blk_file { read ... }`). So
  **adding** `/system/bin/install-recovery.sh` (a `system`-only change) gives a boot-domain script
  that can dump `boot` from inside Android — which would hand us the stock boot image needed for the
  RAM-boot (`fastboot boot`) test, with no bootloader unlock. Untested; verify the exact
  `flash_recovery` service definition in the boot `init.rc` first (it's `0750`, unreadable as shell —
  read it from the `mirror-android-1` boot ramdisk off-device or from AOSP 6).

Other `/system` daemons (`vold`, `debuggerd`, `qcom-system-daemon`, …) are unreadable as `shell`
(`Permission denied`), and their domains are narrower for our purpose. `vold` can touch block
devices but replacing it risks not booting.

### 6. Platform test-key app → uid system / `system_app` (VERIFIED, no flash, no root)

The build is `test-keys`, so the platform cert is the public AOSP test key. Confirmed the device's
`framework-res.apk` cert equals the AOSP `android-6.0.1_r81` `platform.x509.pem`:

```
device framework-res cert  SHA-256: C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8
AOSP platform.x509.pem     SHA-256: C8:A2:E9:BC:...:2A:B8   (identical)
```

`seapp_contexts`: `user=system seinfo=platform domain=system_app`. So a tiny APK with
`android:sharedUserId="android.uid.system"`, signed with the AOSP test platform key, was built
(`aapt2`/`d8`/`apksigner`) and plain-`adb install`ed (to `/data/app`, no `system` touched). A
broadcast made it run and report:

```
$ adb -s <serial> shell dumpsys package dev.glassworks.mirrorprobe | grep -E 'userId|sharedUser'
    userId=1000
    sharedUser=SharedUserSetting{... android.uid.system/1000}
# from logcat, the running receiver:
uid=1000(system)  context=u:r:system_app:s0
id: groups=1000(system),1013(media),1015(sdcard_rw),1023(media_rw),3001(net_bt_admin),3002,3003(inet),9997(everybody)
WRITE_SECURE_SETTINGS = 0 (GRANTED)
/persist         = readable (21 entries: WCNSS_qcom_cfg.ini, WCNSS_qcom_wlan_nv.bin, WCNSS_wlan_dictionary.dat, display, drm, sensors, ...)
/proc/config.gz  = readable (23968 bytes)
/sys/class/net   = readable (wlan0, p2p0, ...)
/firmware        = denied        (modem blobs — NOT reachable)
/oem             = empty
/dev/block, /dev/block/bootdevice/by-name = denied   (no partition reads)
/dev/block/.../modem = EACCES
/persist/drm, /persist/sensors, /data/misc/wifi = denied
/proc/kallsyms   = readable but zeroed (kptr_restrict)
```

Then: `adb uninstall dev.glassworks.mirrorprobe` → `Success`. **This is the practical lever that
needs neither a flash nor root**: platform signing gives uid 1000 / `system_app` with
`WRITE_SECURE_SETTINGS` (rotation and other kiosk control) and `/persist` reads (the WCNSS Wi-Fi NV
and calibration, part of #40). It does **not** reach `/firmware` (modem), `/oem`, or any block
device, so modem/vendor-firmware blobs and partition backups still need root or the §5 leads.

## Result

| Route | Verdict | Evidence |
|---|---|---|
| AOSP `su` at `/system/xbin/su` (the plan) | **Dead end** | No `su` domain, no permissive domain, no `shell→su_exec` transition (§3) |
| Platform-signed app → uid system / `system_app` | **Works, no flash, no root** | §6: uid 1000, `WRITE_SECURE_SETTINGS`, `/persist` readable; `/firmware`/`/oem`/block devices denied |
| Custom kernel module (`.ko`) | Open, untested, kernel code | §4: unsigned modules load, but `MODVERSIONS` needs an exact-matching build; `wlan` must be chain-loaded |
| Re-enable `flash_recovery` (add `/system/bin/install-recovery.sh`) | Open, untested, `system`-only | §5: domain can read `boot`/`recovery` block devices → a path to a boot backup + the RAM-boot test |
| Hook `init.qcom.post_boot.sh` → `qti_init_shell` | Open, untested, `system`-only | §5: runs as root (caps incl. `sys_module`) at `boot_completed`; no block-device/`setenforce` access |
| `fastboot boot` of an unsigned RAM image | **Not attempted** | We have no stock boot image to send. mirror-android.md (§3) read that the LK refuses unsigned boot (`bootimage: incomplete or not signed`). A boot backup via the `flash_recovery` lead would let us test it RAM-only |

Confidence: everything in §1–§4 and §6 is **verified** on the unit (2026-11-15). The §5 routes are
**read** from the policy and `dmesg`; building and flashing them is **untested**.

## How to reproduce

- §1–§4, §6 reads: read-only adb as `shell`, plus an `adb install`/`uninstall` of a `/data/app`
  test APK (nothing in `system` touched). Address the unit with `adb -s $(cat /tmp/mirror-serial)`.
- Off-device analysis used `magiskpolicy --print-rules` (GPL; see below) and the SDK build-tools.
  The platform test key is public: AOSP `build` repo, tag `android-6.0.1_r81`,
  `target/product/security/platform.{pk8,x509.pem}`.
- Nothing here needs undoing. The test APK was uninstalled; `/data/local/tmp` was cleared.

## Housekeeping / provenance

- **`magiskpolicy` is GPL** and was used as a local tool only; it is **not** committed. It came from
  the official Magisk v28.1 release, `Magisk-v28.1.apk`
  SHA-256 `8bfd3346b3da5814f82eff6f1b1b5fedd0ad585f39a25709b23eb54aac45691d`, `libmagiskpolicy.so`
  (arm64) extracted and pushed to `/data/local/tmp/mp` for the dump, then deleted.
- No Glass Panes or vendor bytes, no serials/MACs/keys are in this note or the repo. The pulled
  ramdisk files, the `.ko`, `config.gz`, `dmesg`, the Magisk apk and the test APK live outside the
  repo in `~/src/mirror-root-research/` (hashed where it matters) and must stay there.
- The unit was left in its found state: Glass Panes `mirror-android-1` system, kiosk 0.12.0 as the
  focused HOME app (verified running), `com.android.launcher3` hidden, `user_rotation=3`,
  `accelerometer_rotation=0`.

## Open questions

- **Boot backup without unlock:** does adding `/system/bin/install-recovery.sh` re-enable
  `flash_recovery` and let the `install_recovery` domain dump `boot`? Read the real `flash_recovery`
  service definition from the `mirror-android-1` boot ramdisk first.
- **Custom `.ko`:** can we reproduce this kernel's `vermagic`/CRCs from CAF `msm-3.10` + this
  `.config`? That decides whether any kernel-code route (incl. the old kexec idea in
  [mirror-android.md](../../frames/lululemon-mirror/research/mirror-android.md)) is buildable.
- **`qti_init_shell` root via `post_boot.sh`:** enough for userspace root at `boot_completed`;
  combined with a matching `.ko`, enough for kernel code. Both need a `system` flash and a way back.
- **Is platform signing enough for the kiosk?** For #40's modem/vendor blobs, no (no `/firmware`).
  For kiosk control and `/persist` (Wi-Fi NV), likely yes, with no flash and no root.
