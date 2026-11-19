---
topic: boot
evidence: verified
---

# Mirror root: can we get root on the lululemon Mirror dev unit, and what's the cheapest safe route?

**Status (2026-11-15, second pass): answered — root works, within narrow limits.** The planned `su`
route is a **dead end** (no `su` domain), the platform-signed app works with no flash (§6), and a
**`system` write re-enables `qcom-post-boot`, which runs our code as root in `qti_init_shell`**:
verified on the unit, with `id` reporting uid 0 and the firmware list it read back (§7). The
bootloader is locked (`secure: yes`, `Device unlocked: false`), which rules out boot writes and
RAM-booting our own image; `system` stays writable, so the published image can always go back. What
that root buys is a short path list — `/firmware/image`, the top-level files in `/persist` — and
nothing that needs a block device, `setenforce` or a root shell — and it cannot drive Android
either: `pm`, `svc` and `am` all fail on `app_process`, which is out of its reach (§7). Equipment:
eleven `system` flashes in all — the published image three times (first unchanged, then twice as the
restore), five hook builds and three cleanup builds — plus a few small files in `/persist`; no
bootloader unlock, no boot/modem/oem writes.

Device: lululemon Studio Mirror (MSM8916 / APQ8016E, Android 6.0.1), running the community
**Glass Panes `mirror-android-1`** image (`userdebug`, `test-keys`), stock signed `boot`
(`IFC-6309-2.0-MIR.S22Q401`, a `user`/`test-keys` build). The S1 panel and a tablet are on the same
Mac, so every command used `adb -s <serial>` (serial read from `/tmp/mirror-serial`, never printed).
Tools: `adb`, `fastboot`, Android SDK build-tools 35.0.0 (`aapt2`, `d8`, `apksigner`, `zipalign`),
`llvm-readelf`/`llvm-objcopy`, `magiskpolicy` from Magisk v28.1 (used off-device only, not committed;
see below), `setools`/`checkpolicy` in a Debian container, e2fsprogs 1.47 in a Debian container to
append to a file inside the system image without touching its selinux label.

## Question

Can we get root on this dev unit, by the cheapest safe route, writing `system` only (the maintainer's
2026-11-15 go-ahead covers `system`/`userdata`, not bootloader unlock or modem/oem/persist/boot)?
Root unblocks #40 (reading the stock vendor blobs: modem/wcnss firmware, Adreno, camera) and #41
(fastboot), and lets us back up the partitions we can't read today. If full root isn't cheap, what
does a lesser foothold (platform signing) already give us?

## What we tried

All on 2026-11-15 unless noted. Nothing was flashed and `system` was not touched. The only writes
were to `/data`: `magiskpolicy` pushed to `/data/local/tmp` (§3, deleted after), and a test app
installed to `/data/app` and uninstalled after (§6). Every other probe is read-only adb as `shell`.

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

### 5. Root-domain leads from the policy (reads; §7 later verified the second one)

What runs from `/system` (which we control — no dm-verity, `system` mounts `ro` but we can reflash
it) as a root-ish domain:

- **`qcom-post-boot` → `/system/etc/init.qcom.post_boot.sh`** (0644, `system_file`, world-readable;
  pulled). `init` transitions `shell_exec → qti_init_shell`. That domain holds
  `capability { chown dac_override dac_read_search fowner fsetid net_admin sys_module }` and may
  `execute_no_trans` `system_file`/`toolbox_exec`/`shell_exec`, write `sysfs`, and read/write
  `system_data_file` dirs. So replacing `post_boot.sh` runs our code **as root in `qti_init_shell`**.
  Limits: it fires on `boot_completed` (late), and the domain has **no** block-device access and
  **no** `setenforce`. Its `sys_module` cap + the §4 module could load a matching `.ko`. **Verified
  in §7**: this is the route that worked, with exactly those limits.
- **`flash_recovery` → `/system/bin/install-recovery.sh`** is **disabled only because the file is
  missing** (`dmesg`: `init: cannot find '/system/bin/install-recovery.sh', disabling
  'flash_recovery'`). `file_contexts` already maps that path → `install_recovery_exec`, and the
  `install_recovery` domain can **read `boot_block_device`** and **read/write
  `recovery_block_device`** (`allow install_recovery boot_block_device blk_file { read ... }`). So
  **adding** `/system/bin/install-recovery.sh` gives a boot-domain script that can dump `boot` from
  inside Android — which would hand us the stock boot image needed for the RAM-boot (`fastboot boot`)
  test, with no bootloader unlock.
  - **The service definition** (2026-11-15, read off-device): AOSP 6.0.1_r81
    `system/core/rootdir/init.rc` has it as a plain `service flash_recovery
    /system/bin/install-recovery.sh`, `class main`, `oneshot`, and **no `seclabel`** — the domain
    comes from the script's own label, through `type_transition init install_recovery_exec process
    install_recovery`. The unit's `dmesg` line names the same service and path, and the label and
    transition are in the unit's policy, so the two agree. What is still **unconfirmed** is the
    unit's own `init.rc`: it is `0750 root:root` (`cat`: `Permission denied`) and the policy has no
    `shell → rootfs` access at all, so it can only be read from a boot backup or a ramdisk.
  - **The dump's way out is the gap** (2026-11-15, read-only): `install_recovery` can create and
    write `cache_file`, and `/cache` is mounted ext4 with 247.7 MB free (`boot` is `mmcblk0p19`,
    32768 KiB). But **`shell` has no `cache_file` rule at all**, and `/cache` is `0770 system:cache`
    (`ls /cache` as `shell`: `opendir failed, Permission denied`), so `adb pull` cannot read the
    dump, whatever its mode. `install_recovery` cannot write anywhere `shell` can read either: it
    has no `/data` rules, so not even `/data/local/tmp`. A platform app (§6) reads `cache_file` but
    has no `sdcard`/`media_rw`/`fuse` and no `shell_data_file` access, so it cannot leave the bytes
    as a pullable file — only on a socket (`allow system_app system_app tcp_socket { … bind listen
    accept … }`), which means an `adb forward` client on the host. **So this route is one `system`
    flash plus one installed app, not a `system`-only change**. Superseded by §7: a `system` flash
    works, and with a locked bootloader a copy of `boot` has nothing left to prove, so this route
    was not built.

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
`WRITE_SECURE_SETTINGS` (rotation and other kiosk control) and a `/persist` listing (the WCNSS Wi-Fi NV
and calibration file names, part of #40; opening the files was not recorded). It does **not** reach `/firmware` (modem), `/oem`, or any block
device, so modem/vendor-firmware blobs and partition backups still need root or the §5 leads.

### 7. `system` write → root at boot (VERIFIED on the unit, 2026-11-15)

**The bootloader, asked read-only first.** This one answers `getvar` one value at a time: `getvar all`
returns `all:` and nothing else, so `oem device-info` and individual names were used.

```
$ fastboot oem device-info
	Device tampered: false
	Device unlocked: false
$ fastboot getvar secure            secure: yes
$ fastboot getvar product           product: MSM8916
$ fastboot getvar variant           variant: SBC eMMC
$ fastboot getvar partition-type:system     ext4
$ fastboot getvar partition-size:system     0x4cccd000   (1,288,491,008 bytes)
$ fastboot getvar partition-size:userdata   0x14cccd000
$ fastboot getvar max-download-size         0x10000000   (256 MiB)
```

So the lock covers the boot chain, and `fastboot boot` of anything we build is refused before it
starts (the same `bootimage: incomplete or not signed` lk2nd got). A copy of the stock `boot` would
only let us boot the stock `boot`: **the `fastboot boot` test and the whole boot-image errand are
dead**, and §5's `flash_recovery` lead with them.

**`system` is writable anyway.** The published image was flashed back unchanged: 4 sparse chunks,
76 s, then the kiosk focused 25 s after boot. That is both the writability proof and the way back,
and the partition is 19 blocks larger than the image, as [image-patch.md](image-patch.md) measured.

**The hook.** `/system/etc/init.qcom.post_boot.sh` is 0644 `system_file`. A block was appended to it
inside the image (mounted read-write in a container so the inode, mode and `security.selinux` label
survive; `e2fsck -fn` afterwards shows only the `make_ext4fs` inode-bitmap quirk the unmodified image
also has). It runs on every boot, after `sys.boot_completed=1`:

```
init: Boot completed init: Starting service 'qcom-post-boot'...
init: Service 'qcom-post-boot' (pid 1567) exited with status 0
```

and what it reported about itself:

```
uid=0(root) gid=0(root) groups=0(root) context=u:r:qti_init_shell:s0
```

That is the domain §5 predicted from `type_transition init shell_exec process qti_init_shell`; the
service execs `/system/bin/sh`, so the script's own `system_file` label only has to stay readable.

The hook's body and the exact flash commands are deliberately not in this note. It is a recipe for
writing a persistent `system` image on a locked device, and on a stock unit that is the one-way door
described below, so a note that anyone can copy-paste is the wrong place for it. What a maintainer
needs is here: the file, its mode and label, that appending in place keeps both, how the image is
expanded and re-sparsified, and that `e2fsck -fn` afterwards shows only the `make_ext4fs` inode-bitmap
quirk the untouched image also has. The appended blocks live with the other working files, outside
the repo; the sparse step needs no new tool, because `writeSparse` in
[`kiln/packages/ext4`](../../../kiln/packages/ext4/src/sparse.ts) already writes that format and is
what Kiln uses.

**First attempt, and the one rule that mattered.** Creating a directory failed:

```
avc: denied { create } for pid=1621 comm="mkdir" name="glassworks"
  scontext=u:r:qti_init_shell:s0 tcontext=u:object_r:persist_file:s0 tclass=dir
```

The domain has `add_name` and `write` on `persist_file` dirs but **no `create`**; on `persist_file`
files it has `create`. So results go straight into `/persist` as `gw-*.txt` files, 0644, no
subdirectory. Every other denial in `dmesg` predates this work.

**The way out needs no app and no `/cache`.** `allow shell persist_file file { read … }` and
`persist_file dir { search }` mean `adb shell` reads a 0644 file in `/persist` directly. That is the
whole channel, and it is why §5's `/cache` idea could never have worked: `shell` has no `cache_file`
rule at all.

**What this root can and cannot do** (policy, cross-checked by the run above):
| Path | Root in `qti_init_shell` |
|---|---|
| `/firmware/image` (`firmware_file`) | read, list |
| `/persist` top-level files (`persist_file`) | `{ ioctl read write create getattr setattr lock append unlink rename open }` — read, write, create, rename, delete, chmod |
| `/persist` subdirs (`drm`, `display`, `misc`, `hlos_rfs`, `rfs`, `data`) | **denied**, each has its own label |
| `/data` (`system_data_file`) | dirs only, no files |
| Block devices (`block_device`, `boot_block_device`, …) | **denied** — no partition dumps |
| `setenforce`, policy load | **denied** |
| Sockets `adbd` can reach | none (`adbd → appdomain` covers apps, not this domain) |

**Android's own tools are out of reach, and that fixes what this root is good for.** `pm`, `svc` and
`am` are one-line scripts that `exec app_process`, which is `zygote_exec` in the policy. The hook
asked it directly (2026-11-15):

```
$ PATH=/system/bin:$PATH pm list packages
/system/bin/pm[6]: app_process: not found          # exit 127, and not the word "denied"
avc: denied { getattr } for pid=1617 comm="sh" path="/system/bin/app_process64"
  scontext=u:r:qti_init_shell:s0 tcontext=u:object_r:zygote_exec:s0 tclass=file
$ PATH=/system/bin:$PATH am get-current-user
/system/bin/am[8]: app_process: not found          # exit 127
$ PATH=/system/bin:$PATH app_process /system/bin com.android.commands.pm.Pm list packages
/system/etc/init.qcom.post_boot.sh[1523]: app_process: not found     # exit 127, straight at it
$ svc wifi enable                                  # same, exit 127
$ setprop debug.gw.test 1; echo $?; getprop debug.gw.test
0
                                                   # exit 0, and the property stays empty
```

It cannot even `getattr` the launcher, so no package list, no Wi-Fi toggle, no broadcast, no
`dumpsys`. This root works through files: `/persist`, plus the write-only rules the domain holds on
`sysfs` and `/proc` (`allow qti_init_shell sysfs file { write setattr append open }`,
`allow qti_init_shell proc file { write }` — `sysfs_graphics` and `sysfs_thermal` are the two it may
also read). It cannot ask Android to do anything, which is the honest description: a boot-time root
for copying firmware, not a way to drive the system.

**What `/firmware/image` holds** (names and sizes only; the bytes stay outside the repo):

| Group | Files | Size |
|---|---|---|
| Modem | `modem.b00`–`modem.b27`, `modem.mdt` | ~9.9 MB (`modem.b17` alone 5.6 MB) |
| Wi-Fi | `wcnss.b00`–`wcnss.b11` | ~4.2 MB (`wcnss.b06` 3.3 MB) |
| Trusted components | `cmnlib`, `keymaster`, `playread`, `widevine`, `sampleap`, `isdbtmm` (`*.b0x` + `.mdt`) | ~1.1 MB |
| Boot helper | `mba.mbn` | 230 KB |

53 files, about 15 MB. `/persist` has 27.5 MB total and 27.3 MB free, which is why the copy is done
in batches: `qti_init_shell` may write there, but the space is calibration storage, not scratch.

**Reversibility, split in two.** On this unit the change is `/system` only, the published image is on
disk with its hash, and reflashing takes about a minute — so any point in this work can be undone.
That was **run, not assumed** at the end of this pass, in this order: one boot on a cleanup build
(the same append, with `rm -f /persist/gw-*` in place of the copy) deleted the `gw-*` files, then the
published image went back, and the unit came up with `post_boot.sh` at its original 80502 bytes
ending in `esac`, `/persist` at 220.0K used with no `gw-*` left, the kiosk focused and rotation 3.
Deleting those files is a permission the domain has (`unlink` and `rename` are both in the rule
above), and it is what cleared each batch between boots. On a **stock, virgin**
Mirror it is a one-way door: the hook needs a system image to write, only the community image can be
written (the vendor's system cannot be read out, so it cannot be restored), and the read is worth the
same either way. Anyone reaching for this on an untouched unit should know that before, not after.

## Result

| Route | Verdict | Evidence |
|---|---|---|
| AOSP `su` at `/system/xbin/su` (the plan) | **Dead end** | No `su` domain, no permissive domain, no `shell→su_exec` transition (§3) |
| Platform-signed app → uid system / `system_app` | **Works, no flash, no root** | §6: uid 1000, `WRITE_SECURE_SETTINGS`, `/persist` listable; `/firmware`/`/oem`/block devices denied |
| Custom kernel module (`.ko`) | Open, untested, kernel code | §4: unsigned modules load, but `MODVERSIONS` needs an exact-matching build; `wlan` must be chain-loaded |
| `system` write on this locked unit | **Works** | §7: published image flashed back over itself, booted, kiosk back in 25 s |
| Re-enable `flash_recovery` (add `/system/bin/install-recovery.sh`) | **Superseded** | §5: reads `boot` into `/cache`, which `shell` cannot read; and a boot copy is useless with a locked bootloader (§7) |
| Hook `init.qcom.post_boot.sh` → `qti_init_shell` | **Verified: root, narrow** | §7: uid 0, reads `/firmware/image` and top-level `/persist`; no block devices, no `setenforce`, no root shell |
| `fastboot boot` of an image we build | **Ruled out** | §7: `secure: yes`, `Device unlocked: false`; the LK refuses anything not vendor-signed |

Confidence: §1–§4, §6 and §7 are **verified** on the unit (2026-11-15). §5's routes were **read**
from the policy, `dmesg` and AOSP 6.0.1_r81; `flash_recovery` stays untested and is now superseded.

## How to reproduce

- §1–§4 and §5 reads: read-only adb as `shell`; §3 also pushes `magiskpolicy` to
  `/data/local/tmp` (delete it after). Address the unit with `adb -s $(cat /tmp/mirror-serial)`.
- §5's service definition and the pull gap: AOSP `system/core/rootdir/init.rc` at tag
  `android-6.0.1_r81` (googlesource), compared with the unit's `dmesg | grep install-recovery`, then
  the rules for `install_recovery`, `shell`, `system_app` and `cache_file` in the §3 dump. The
  read-only device checks were `ls -l /system/bin/install-recovery.sh` (no such file, the disabled
  service), `ls -la /cache`, `ls -l /dev/block/bootdevice/by-name/boot`, `ls -l /init.rc` (all three
  `Permission denied`, which is the point of §5) and `cat /proc/partitions`.
- Off-device analysis used `magiskpolicy --print-rules` (GPL; see below) and the SDK build-tools.
  The platform test key is public: AOSP `build` repo, tag `android-6.0.1_r81`,
  `target/product/security/platform.{pk8,x509.pem}`.
- §6 probe (writes `/data/app` only): build an APK whose manifest sets
  `android:sharedUserId="android.uid.system"` and has a broadcast receiver that logs `id`, the
  `WRITE_SECURE_SETTINGS` check and a directory listing of each path in §6. Build it with
  `aapt2`/`d8`, `zipalign` it, sign it with `apksigner` and the platform key above, then
  `adb install` it, send the broadcast with `adb shell am broadcast`, and read `logcat`. Undo:
  `adb uninstall dev.glassworks.mirrorprobe`. The probe source is not in the repo.
- §7, the bootloader half (read-only): `adb -s $(cat /tmp/mirror-serial) reboot bootloader`, then
  `fastboot oem device-info` and one `fastboot getvar <name>` per value (`secure`, `product`,
  `variant`, `partition-type:system`, `partition-size:system`), then `fastboot reboot`.
- §7, the hook: the published `system.img` (851 MB sparse, expands to 1,288,413,184 bytes) was
  expanded, mounted read-write in a `debian:bookworm` container, a block appended to
  `/system/etc/init.qcom.post_boot.sh`, `sync`, unmounted, `e2fsck -fn`, then turned back into a
  sparse image (4 KiB blocks, zero runs as `DONT` chunks) and `fastboot flash system`ed. Appending
  in place is what keeps the inode, the 0644 mode and `security.selinux=u:object_r:system_file:s0`.
  The block itself, and the batching rules, are described in §7; they are not committed.
- §7, undo, in this order: flash a cleanup build (the same append, with `rm -f /persist/gw-*`) and
  boot it once, so the `gw-*` files go — `shell` may read `/persist` but not write it, so the device
  needs a hook to delete them; then `fastboot flash system` with the published image, which removes
  the hook itself.
- Nothing here needs undoing except §7, and §7's undo is the verified published image.

## Housekeeping / provenance

- **`magiskpolicy` is GPL** and was used as a local tool only; it is **not** committed. It came from
  the official Magisk v28.1 release, `Magisk-v28.1.apk`
  SHA-256 `8bfd3346b3da5814f82eff6f1b1b5fedd0ad585f39a25709b23eb54aac45691d`, `libmagiskpolicy.so`
  (arm64) extracted and pushed to `/data/local/tmp/mp` for the dump, then deleted.
- No Glass Panes or vendor bytes, no serials/MACs/keys are in this note or the repo. The pulled
  ramdisk files, the `.ko`, `config.gz`, `dmesg`, the Magisk apk and the test APK live outside the
  repo in `~/src/mirror-root-research/` (hashed where it matters) and must stay there.
- The unit was found with Glass Panes `mirror-android-1` system, kiosk 0.12.0 as the focused HOME app,
  `com.android.launcher3` hidden, `user_rotation=3`, `accelerometer_rotation=0`, and every boot of it
  since still comes up that way (§7's flashes were checked against exactly those five things).
- §7's writes, for the record: **eleven `system` flashes** and nothing else. The published image
  three times (once unchanged as the writability test, twice as the restore at the end), five hook
  builds (the failed directory attempt, the inventory, the firmware batch copy, and two
  `pm`/`svc`/`setprop` test builds) and three cleanup builds. No `boot`, `recovery`, `userdata`,
  `modem`, `oem` or partition-table write, and no unlock attempt.
- §7 leaves files in `/persist` while it runs: the `gw-*` batch, plus `gw-done.txt` recording what
  was copied. They are firmware copies and must be deleted by a boot on a cleanup build and never
  committed.
  `/persist` itself also holds device-unique values — `boardserial.txt`, `bt.txt`, `bt_mac.pst`,
  `eth.txt`, `deviceid.txt` — which is why only the listing, never the contents, is in this note.
- The firmware copies live outside the repo, in `~/.t3/glassworks-m1/29-mirror/` (`firmware/` plus
  a manifest of size and md5 per file), like the rest of the pulled material in
  `~/src/mirror-root-research/`. Nothing from `/firmware` or `/persist` is committed.
- §5's pull analysis (2026-11-15) was done with **reads only**: no push, no install, no flash. Every
  adb command in it is `ls`/`cat`/`dmesg` as `shell`, and the off-device work is AOSP source plus
  the policy dump from §3.

## Open questions

- **Custom `.ko`:** can we reproduce this kernel's `vermagic`/CRCs from CAF `msm-3.10` + this
  `.config`? `qti_init_shell` holds `sys_module`, so the hook could `insmod` one. It is the one
  route this note found past the domain's limits (no block devices, no `setenforce`), **untested**,
  and nothing here rules out another. A community project has since built this route and reported it
  working on two units, recovering the ABI from the vendor `pronto_wlan.ko`'s DWARF rather than a
  kernel build, and loading at the free `adsprpc.ko` init slot instead of chain-loading `wlan` — see
  [community-root-and-network-adb.md](community-root-and-network-adb.md). Our unit's `module_layout`
  CRC already matches theirs (`0xa5b7f7dd`); only the vermagic string differs. Still **untested** on
  our kernel, and a shipped module is a GPL payload (rule 4).
- **`qti_init_shell` root via `post_boot.sh`:** done (§7). It is enough to read `/firmware/image` and
  the top-level `/persist` files, and it holds `sys_module`. It is not enough for partition dumps.
- **Is platform signing enough for the kiosk?** For #40's modem/vendor blobs, no (no `/firmware`).
  For kiosk control and a `/persist` listing (Wi-Fi NV file names), yes, with no flash and no root. Reading those files is untested.

## Next steps

Where the work stands, in order, as of 2026-11-15:

- **Copy the firmware out.** `/firmware/image` is 53 files, ~15 MB, and `/persist` has 27.3 MB free,
  so it goes in batches: each boot copies at most 12 MB into `/persist` as `gw-fw-*`, `adb pull`
  takes them, the next boot clears them and copies the next batch (`gw-done.txt` remembers what
  went). Then a boot on a cleanup build deletes the `gw-*` files, and `fastboot flash system` with the
  published image removes the hook. The firmware itself stays outside the repo.
- **Custom `.ko`:** reproduce this kernel's `vermagic`/CRCs from CAF `msm-3.10` + this `.config`;
  a replacement `wlan` must chain-load the original (§4). It is the one route this note found past
  §7's limits (`sys_module` is in the domain's capability set) — **untested**, and nothing here
  rules out another — and the only step here that can damage the panel: a wrong regulator or panel
  value is physical damage, and there is no working EDL loader for this chip to fall back on.
  **This note stops here**: the firmware copy and #41 are what this pass set out to get, and the
  kernel route is a separate decision, not a follow-on.
- **Kiosk work, no flash at all:** the platform test-key app (§6) for Wi-Fi, rotation and lock screen.
  That is a different thread's job and needs none of the above.

**Not root, but useful:** a platform test-key app already runs as uid 1000 / `system_app` with
`WRITE_SECURE_SETTINGS` and a `/persist` listing, with no flash (§6). It does not reach `/firmware`,
`/oem` or block devices.
