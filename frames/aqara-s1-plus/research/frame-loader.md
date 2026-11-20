---
topic: boot
evidence: read
---

# Frame updates on the S1: the loader, signed bundles, and a reflash without opening the panel

**Status (2026-11-19): partly verified.** On paper, the stock firmware answers both open questions in
#139: `/data` is not encrypted, and the stock U-Boot has a fastboot `boot` command. On the dev panel,
`adb reboot bootloader` reaches the stock fastboot with no button (attempt 7). But the stock `boot`
runs only a legacy uImage, not an Android boot image (attempt 8), so the RAM test in the no-opening
route needs a wrapped image (attempt 10). Panel tests T0, T1, T3 and T5 have run. T3 with the
wrapped image (attempt 11) took the download and the `boot`, and then the board reset into its flash
boot within seconds. The canary of plan E (attempt 12) reset the same way. That places the reset
before our code runs, in the stock U-Boot or ATF, only if no watchdog is armed at the handoff, and
that premise is unread (the SCP firmware and boot0). Attempt 13 reads the U-Boot path: no step read
so far explains the reset. Plan F (attempt 13) then reset like E1 without ever leaving U-Boot, so the
reset comes before PREP. Attempt 14 reads the steps plan F named, and none explains it. All of it goes through [`fastboot-usb.py`](../tools/fel/fastboot-usb.py), not the
`fastboot` CLI.

**The loader ran on the dev S1 on 2026-11-19 (verified; #651's boot writer run, #651 issuecomment-7024290415).**
It is in `frames/aqara-s1-plus/tools/root/` (`glassworks_loader.sh`, `glassworks.rc`), and both boot patchers
make byte-identical images with it (attempt 14). Of its panel tests ([Panel tests](#panel-tests)), L1–L4
passed. L5 was inconclusive: the loader's lines were in neither logcat nor dmesg, because it logged with `log -t`
before logd runs. It now writes them to the kernel log too, and L5 waits on the next S1 session.

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
     test with `fastboot boot` is in the binary. Attempt 8 narrows it: it runs only a legacy uImage.
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

7. **Stock fastboot from a Mac** (2026-11-19, verified by the root on the dev panel; #139's comments).
   - `adb reboot bootloader` reaches the stock fastboot with no button. It enumerates as `1f3a:1010`,
     interface `ff/42/03`, two bulk endpoints, once macOS's "Allow accessory to connect?" is
     approved. Every bootloader start is a new accessory, so it asks every time.
   - The device comes up with **no active configuration**, so `fastboot devices` doesn't list it.
     After pyusb sent `set_configuration()`, it did.
   - Over pyusb: `version` 0.5, `max-download-size` 0x02000000 (32 MiB), `secure` yes.
     `version-bootloader`, `unlocked` and `slot-count` answer "not supported". **`product` answers
     the panel's serial.**
   - After that pyusb session, the platform `fastboot getvar` calls came back empty.
   - `fastboot boot boot-ram.img` (exactly 32 MiB) printed `Sending 'boot.img' (32768 KB)` and
     never finished in 120 s. Nothing was written.

8. **Why, from the stock fastboot's source** (2026-11-19, read). The stock U-Boot is Allwinner's
   BSP U-Boot 2018 with its own fastboot gadget (`drivers/sunxi_usb/usb_fastboot.c`,
   `usb_base_platform.c`, `usbc_dev.c`). The copy read is in `CrealityTech/sonic_pad_os` at
   `7f37a7fbf`, under `lichee/brandy-2.0/u-boot-2018`, from a T800 board (the A133 family). It is
   the panel's code: `strings` over the panel's boot package shows the same strings in the same
   order, offsets 521389–522129 (`Starting download of %d BYTES`, `FAILdownload: data > buffer`,
   `DATA%08x`, `start addr %s`, `Booting kernel..`, `Image has cmdline:`,
   `ERROR : bootting failed`, `Booting raw image..`, `do_go fail`, `FAILinvalid boot image`,
   `0.5`, `Android Fastboot`, `20080411`). The code itself wasn't disassembled.
   - **`product` is the serial.** `sunxi_fastboot_init` copies the env's `snum` into
     `sunxi_usb_fastboot_dev[2]` (line 1517), which is the product string. `getvar:product` returns
     that same string. The USB product descriptor carries it too. `serialno` is the fixed `20080411`.
   - **`secure: yes` means nothing.** `__get_var` (line 1078) answers `yes` for every board. The real
     gate is `sunxi_fastboot_status()`, and it guards `getvar`, `download`, `boot`, `flash` and
     `erase` alike. `getvar` answered, so none of them is refused on this panel. That makes stock
     `flash` and `erase` live, and `oem`, `flashing` and `reboot-fastboot` (which writes `misc`)
     aren't gated at all.
   - **`download` needs the exact byte count.** `__try_to_download` (line 957) refuses only sizes
     above 32 MiB, so exactly 32 MiB is accepted: no off-by-one. The bulk OUT endpoint (512-byte
     packets) is read packet by packet in the interrupt handler (`eprx_recv_op`), and the transfer
     ends only when the write pointer **equals** the buffer start plus the size. One lost packet,
     or extra bytes, means OKAY never comes. Any host transfer size works.
   - **A zero-length packet after the data is a hazard, but the CLI doesn't send one.** If it
     arrives after the transfer has ended, the gadget takes it as an empty command and answers
     `FAIL`, which the host then reads as the reply to its next command. macOS fastboot's ZLP code
     is `#if 0` in AOSP (`usb_osx.cpp`), and it applies only to protocol 01 anyway. libusb and WebUSB
     don't add one.
   - **The likely cause of the hang: a data-toggle mismatch.** `SET_CONFIGURATION` resets both bulk
     endpoints, and each reset clears the data toggle (`USBC_Dev_ConfigEp`). `CLEAR_FEATURE` isn't
     handled: it is commented out in `sunxi_fastboot_standard_req_op`. macOS fastboot calls
     `ClearPipeStallBothEnds` on both pipes when it opens a device (AOSP `usb_osx.cpp`). That
     resets the host's toggles, but not the device's. After the pyusb session left the device's
     toggles at an odd count, the device took the CLI's next packet for a retry, acknowledged it and
     threw it away. A lost command hangs the CLI. A lost data packet leaves `download` 512 bytes
     short. This is inferred: no USB capture was taken.
   - **Stock `boot` won't boot an Android image.** `__boot` (line 1000) sends no reply. It waits
     1 s, then looks for a legacy uImage header (`0x27051956`) at a fixed offset of 2048 in the
     buffer. If one is there, it runs `bootm` on it, with the boot image header's command line as
     the whole `bootargs`. If not, it prints "Booting raw image..", "do_go fail" and returns to the
     command loop, still in fastboot. The panel's `boot` holds an arm64 `Image` at 2048 (its
     `ARMd` magic, read 2026-11-19 from the plan B backup), so T3 as written won't boot. Expect no
     reply, with the panel still answering `getvar`.
   - **Kiln.** `Fastboot.bootBlob` waits up to 120 s for the `boot` reply and then fails, and
     `WebUsbBulk.open` selects configuration 1 only when none is active. Both are fine for the first
     session after a bootloader start. Neither is changed here.

   **Tool.** [`fastboot-usb.py`](../tools/fel/fastboot-usb.py) talks to the stock fastboot over pyusb.
   It sends `SET_CONFIGURATION` every run, so both toggles start at zero. It never clears a halt,
   sends no ZLP, and checks the `DATA` size. It redacts `product` and `serialno`. After `boot` it
   asks `getvar:version` once, to tell "came back without booting" from "USB dropped". It sends
   only `getvar`, `download`, `boot` and a plain `reboot` (the stock handler restarts the board and
   writes nothing), and refuses everything else before the wire, `reboot-fastboot` included: that
   one writes `misc`. Its tests
   (`tools/fel/test/`) run it against a fake of this state machine.

   The first idea for a wrapped image (a multi-file uImage loaded above the buffer) is replaced by
   attempt 10. `flash` without a RAM test stays the other way, if the maintainer prefers it. A
   read-back can then happen only from Android, after the panel boots the new image.

9. **Plan C: the tool against the stock fastboot** (2026-11-19, verified by the root on the dev
   panel, with `fastboot-usb.py` at `a4a88b29a`; the platform `fastboot` CLI wasn't run).
   - `getvar`, twice in a row: both runs gave the same answers as attempt 7 (`version` 0.5,
     `max-download-size` 0x02000000, `secure` yes, three "not supported"). `product` and `serialno`
     printed as `<redacted>`. So back-to-back sessions work once each sends `SET_CONFIGURATION`.
   - `boot` of the marked 32 MiB `boot-ram.img`: `download: OKAY, 33554432 bytes in 1.3 s
     (26.4 MB/s)`. The 32 MiB hang is gone. Then `boot: no reply in 10 s`, and U-Boot answered
     `getvar:version` again: it didn't boot the image, as attempt 8 predicted. Nothing was written.

10. **The stock U-Boot's own code, and a RAM image it can boot** (2026-11-19, read: disassembly of
    the panel's boot package backup, U-Boot item at file offset 0x800, linked at `0x4A000000`,
    Thumb-2; `llvm-objdump --triple=thumbv7`). Code addresses below are U-Boot addresses. The
    commands, host only, on a backup outside the repo:
    ```sh
    P=<backup dir>/bootloader/boot_package.bin       # the stock boot package, eMMC sector 32800
    python3 -I - "$P" <<'EOF'                         # item table: 0x170-byte entries from 0x40
    import struct, sys
    b = open(sys.argv[1], 'rb').read()
    for i in range(4):
        o = 0x40 + i * 0x170
        off, size = struct.unpack_from('<2I', b, o + 64)
        print(b[o:o + 64].split(b'\0')[0], hex(off), size, hex(struct.unpack_from('<I', b, off)[0]))
    EOF
    dd if="$P" of=/tmp/uboot.bin bs=2048 skip=1 count=336   # the u-boot item: offset 0x800, 688128 bytes
    O=/opt/homebrew/opt/llvm/bin
    $O/llvm-objcopy -I binary -O elf32-littlearm /tmp/uboot.bin /tmp/uboot.elf
    $O/llvm-objdump -D --triple=thumbv7-none-eabi --start-address=0x4bcb8 --stop-address=0x4bd34 /tmp/uboot.elf
    ```
    `llvm-objdump` prints offsets in `uboot.bin`. A U-Boot address is `0x4A000000` plus that offset.
    Each function was found from a string it prints: the string's U-Boot address shows up as a
    little-endian word in the literal pool right after the function.
    ```sh
    python3 -I -c 'import re, struct, sys; u = open(sys.argv[1], "rb").read(); a = 0x4A000000 + u.find(sys.argv[2].encode()); print([hex(m.start()) for m in re.finditer(re.escape(struct.pack("<I", a)), u)])' /tmp/uboot.bin 'recv addr 0x%lx'
    ```
    The ranges read, as offsets: `sunxi_fastboot_init` 0x4bcb8–0x4bd34, `boot` 0x4c6ec–0x4c75e,
    `do_bootm` 0x6ce0–0x6d8e, its verify step 0x6520–0x671a, `android_image_get_ramdisk` 0x102a4.
    Delete `/tmp/uboot.*` afterwards: they are Aqara's code.
    - **U-Boot is 32-bit.** The `u-boot` item starts with an ARM branch (`0xea00018e`), and the
      `monitor` item (ATF) with an AArch64 one (`0x1400003e`). U-Boot starts the 64-bit kernel through
      ATF (`ARM_SVC_RUNNSOS`).
    - **The download buffer is `0x41000000`.** `sunxi_fastboot_init` at `0x4A04BCB8` does
      `mov.w r3, #0x41000000` into the receive buffer. It also copies 0x18 bytes of `snum` into the
      product string, which is why `getvar:product` answers the serial.
    - **`boot` (`0x4A04C6EC`)** waits about 1 s, compares the word at buffer + 0x800 with the uImage
      magic (stored as `0x56190527`), and on a match sets `bootargs` to the C string at buffer + 0x40
      if it isn't empty. Then it calls `do_bootm` with `"bootm", "0x41000800"`. It never sends its
      `OKAY`.
    - **`do_bootm` (`0x4A006CE0`)** first runs a verified-boot step (`0x4A006520`). With
      `gd->securemode` 0 it returns 0. This panel's command line has `androidboot.secure_os_exist=0`
      and no `androidboot.verifiedbootstate`, which fits that branch. Then it **reads the bytes at
      the bootm address as an Android boot header**. It calls `android_image_get_ramdisk`
      (`0x4A0102A4`), moves that "ramdisk" to the header's `ramdisk_addr`, sets `initrd_start` and
      `initrd_end`, and when `ramdisk_size` isn't 0, calls `fdt_initrd`. At a uImage header,
      `ramdisk_size` is the bytes of `ih_load` and `ramdisk_addr` those of `ih_ep`. So `ih_load` must
      be 0, or U-Boot moves memory to an address made of the entry point's bytes.
    - The DT the kernel gets is U-Boot's own (`gd->fdt_blob`, used in place with `fdt_high`
      0xffffffff). The stock strings have none of mainline's "Flattened Device Tree" messages, and
      the boot package's `dtb` has the same size as the boot image's (129829 bytes). Its
      `/memreserve/`s are `0x48000000` (16 MiB) and `0x48100000`, clear of the buffer.
    - **The image that fits all of this** ([`wrap-ram-boot.py`](../tools/fel/wrap-ram-boot.py)):
      | Offset in the download | Physical | What |
      |---|---|---|
      | 0x0 | 0x41000000 | `GW139RAM`, then at 0x40 the command line: the flash `/proc/cmdline`, ` androidboot.gw139=ram`, `initrd=<addr>,<size>` |
      | 0x800 | 0x41000800 | uImage header: Linux, arch ARM, `KERNEL_NOLOAD`, uncompressed, `ih_load` 0, `ih_ep` 0x7f7c0 |
      | 0x80000 | 0x41080000 | The panel's arm64 `Image`, unchanged. Its flags allow any 2 MiB-aligned base, `text_offset` 0x80000 |
      | after `image_size`, 1 MiB aligned | 0x42500000 for the panel's image | The panel's ramdisk, unchanged |
      `KERNEL_NOLOAD` runs the kernel where it was downloaded: bootm adds `ih_ep` to the data start,
      so the entry is 0x41080000, and nothing is copied. With `ih_load` 0, `do_bootm`'s Android step
      moves nothing. The kernel takes the ramdisk from `initrd=`, which arm64 parses before it reserves
      memory. The panel's image comes to 32825011 bytes, under the 32 MiB limit. U-Boot 2026.10's
      `mkimage -l` reads the header as "ARM Linux Kernel Image (no loading done) (uncompressed)".
    - Untested on the panel: whether this kernel boots with arch ARM in the header, how U-Boot treats
      the DT overlays (`androidboot.dtbo_idx`) on this path, and whether Android comes up. A failure
      before the jump leaves U-Boot in fastboot, as in attempt 9. A failure after it needs a power
      cycle. Either way nothing is written. Plan D (the root's) runs it.

11. **Plan D: the wrapped image on the panel** (2026-11-19 00:43 local, verified by the root with the
    maintainer at the S1; tools at `e1caa92e`; S1 Kiosk 0.4.3, `thread_router`, `boot` equal to the
    backup).
    - Build and `--check`: `ok: 32825011 bytes, kernel at 0x41080000, initrd=0x42500000, command line
      1234 bytes`. `mkimage -l`: `ARM Linux Kernel Image (no loading done) (uncompressed)`, load
      `00000000`, entry `0007f7c0`.
    - `getvar`: `0.5`, `0x02000000`. `fastboot-usb.py boot`: `download: OKAY, 32825011 bytes in
      1.3 s`, then on `boot`: `USB error: [Errno 5] Input/Output Error`, exit 1. The tool at
      `e1caa92e` had no case for that. It now reads an I/O error on the `boot` reply as "USB
      dropped" (exit 0), and says to check `ro.boot.gw139`.
    - The panel then **booted from flash**, not from RAM. Its kernel started about 6 s after `boot`
      (from `/proc/uptime`), and `sys.boot_completed` came about 22 s after it. `ro.boot.gw139` was
      empty, and `/proc/cmdline` had the flash boot's keys (no `androidboot.gw139`, no `initrd`). The
      maintainer saw a normal boot to the dashboard.
    - As found afterwards (0.4.3, `thread_router`, otbr, root, Wi-Fi, `boot` hash). Nothing was
      written.

12. **What resets the board after `bootm` takes that image** (2026-11-19, read: the same
    disassembly as attempt 10, and the kernel's own config).
    - **The arch byte is right, and ARM64 would be wrong.** The legacy header check at
      `0x4A00D12C` is `cmp r1, #0x2`: only `IH_ARCH_ARM` passes. An `IH_ARCH_ARM64` header prints
      "Unsupported Architecture" and `bootm` returns to fastboot, so that variant would come back as
      exit 2, with no boot.
    - **`KERNEL_NOLOAD` runs in place.** `bootm_find_os` (inlined at `0x4A00D1EA`–`0x4A00D27C`)
      copies type, comp, os, arch and load out of the header, then, when type is 0xe, sets
      `load = image_start` and `ep += image_start`. So nothing is copied to address 0, and the entry
      is 0x41080000.
    - **The jump ignores the arch.** `boot_jump_linux` (`0x4A001470`) runs the DT overlay merge
      (`androidboot.dtbo_idx`), copies the DT to `0x44000000` (where a normal boot has it), and then
      calls ATF with `smc 0x8000ff04` (`RUNNSOS`): entry, DT address, and a constant 1. A normal
      Android boot takes the same call, so the handoff differs only in the entry address.
    - **Nothing in the kernel reboots on its own.** Its config (`IKCFG_ST` in the `Image`, Linux
      4.9.170) has `CONFIG_PANIC_TIMEOUT=0`, `# CONFIG_PANIC_ON_OOPS is not set`, and no `panic=` on the
      command line. A kernel panic hangs. pstore is `pstore_blk` on the `pstore` partition, and keeps
      panics and oopses only (`MAX_REASON=2`, no console log).
    - **The stock U-Boot has no watchdog** (no `wdt` or `watchdog` strings). Its exception handlers
      ("data abort", "prefetch abort", "undefined instruction") end in "Resetting CPU ..." at
      `0x4A001640`, which resets the board at once.
    - So three places are left: a U-Boot abort during `bootm` (the FDT, overlay or initrd work), ATF
      at the handoff, or something after the jump (the kernel, or firmware reacting to it). The panel's flash
      boot after plan D carries the answer in `bootreason=` on its command line and in Android's
      `sys.boot.reason`, both read-only. That is plan E's first step.
    - **The next variant: a canary, not a new header.** `wrap-ram-boot.py --canary` builds the same
      image with one change: the kernel's first instruction is an AArch64 `b .` (`0x14000000`).
      Everything U-Boot reads (header, command line, sizes) is the same as in plan D, apart from the
      two CRCs. If the board still resets, the reset comes before our code runs (U-Boot or ATF), unless
      a watchdog armed at the handoff fires while the canary spins (see the result below).
      If it stays in place for minutes with the fastboot screen frozen and USB enumerated but silent
      (the tool's "no reply ... USB is still up"), the handoff works and the reset in plan D came from
      the kernel. Untested. Prediction: it stays in place (a guess; about 60 %). U-Boot's path is the
      one a normal boot takes, apart from the image format, and the image passed every check the
      code makes.
    - **Result (E1, 2026-11-19, verified by the root):** the canary reset like plan D. The board
      was back in its flash boot within seconds, with `ro.boot.gw139` empty and pstore empty. The
      prediction was wrong. **This places the reset before our first instruction (in the stock
      U-Boot or ATF) only if no watchdog is armed at the handoff.** A watchdog that fires while the
      canary spins would look the same. What is known (attempt 13): the stock U-Boot touches the SoC
      watchdog only in `reset_cpu`. ATF's `RUNNSOS` arms none (read). The panel sat about 15 s idle
      in fastboot before `boot` without a reset, so a watchdog already running would need a longer
      period (verified, E1 timeline). The SCP firmware, which references the R_WDOG at `0x07020400`,
      and boot0 are **unread**. Until they are, "before our code runs" is the likely reading, not a
      settled one.

13. **Where the stock `bootm` path can reset, read for this image** (2026-11-19, read: the same
    U-Boot disassembly as attempt 10, plus the boot package's `monitor` item (ATF) at file offset
    0xa8800, linked at `0x48000000`, read with `llvm-objdump --triple=aarch64`, and the stock `env`
    backup). Host only. The extraction is attempt 10's. The ATF item is cut out the same way, at
    the offset and size its table entry gives. Addresses are U-Boot (`0x4A…`) or ATF (`0x48…`)
    addresses. Delete the extracted files afterwards: they are Aqara's code.

    **The flash boot takes the same code.** The stock env has
    `boot_normal=sunxi_flash read 45000000 boot;bootm 45000000`. A normal boot runs `do_bootm` on
    the Android image, and fastboot `boot` runs it on our uImage. The two paths split only where
    the code branches on the format. Each step below is marked with how sure it is.

    | Step | What it does with our image | How sure |
    |---|---|---|
    | `update_bootargs` (`0x4A0051F8`), called from every `do_bootm` (`0x4A006DFC`) | `strcpy`s `bootargs` into a 2048-byte stack buffer and appends the sunxi keys (`androidboot.mode`, `serialno`, `hardware`, `boot_type`, `trustchain`, `gpt=1`, `uboot_message`, `disp_reserve`, `bootreason`). Only the three MAC keys are skipped when present. Ours already holds all of them, so they appear twice: 1234 + 292 bytes, 1527 with the NUL. That fits, so there's **no stack overflow**. The kernel would see each key twice | read; lengths measured on the backup |
    | Android pre-step in `do_bootm` | `ramdisk_size` (our `ih_load`) is 0: it moves 0 bytes (`memmove` with length 0 is a no-op, `0x4A058CEA`) and sets `initrd_start`/`initrd_end` to 0 | read |
    | `do_bootm_states(0x70f)`: no RAMDISK or FDT state | `bootm_find_images` (`0x4A00CEEC`) only takes `gd->fdt_blob` and its size. `bootm_decomp_image` (`0x4A00CF18`) skips the copy and the size check when `load == image_start`. `CONFIG_SYS_BOOTM_LEN` is 32 MiB. The overlap check is skipped for an uncompressed in-place image. **No decompression or overlap reset** | read |
    | `image_setup_libfdt` (`0x4A0104BC`) | `fdt_chosen`, the arch and board fixups. Every failure here prints and ends in `hang()` (`0x4A058338`, a `b .`), which **does not reset** | read |
    | DTO merge in `boot_jump_linux` (`0x4A0014A6`) | It runs when the first word of a 10-entry index table (`0x4A098D24`, in BSS) isn't `0xf5f5f5f5`. Only `android_image_get_kernel` (`0x4A01009C`) fills that table, from the Android header's `androidboot.dtbo_idx=0,1,2`. Our path never calls it, so the table keeps its start-up value (0, or `0x17` if BSS isn't cleared). The merge then applies overlay 0 and stops at the next index, with "merge fdt fail". The flash boot applies 0, 1 and 2. Both outcomes print and go on, so this is **not a reset**, but plan D's kernel would get the wrong DT | read; how libfdt fails on the second apply is from its source of that era, untested |
    | AArch64 or AArch32 | `0x4A002354` tests a byte that boot0 sets in U-Boot's head (`+0x4e9`), not the image's arch. Both paths take the `smc` | read |
    | ATF `RUNNSOS` (`0x8000ff04` → `0x4800170C` → `0x480013C4`) | Fills the non-secure entry (EL1h, all exceptions masked, `x0` = DT), clears two words at `0x08110000`, turns off the EL1 MMU and caches, and exits. **It checks nothing about the entry or the DT**, so it behaves the same for both images | read |

    **How this U-Boot resets.** Only `reset_cpu` (`0x4A0019B4`) does. It arms the SoC watchdog at
    `0x030090A0` (config 1, mode 1, about 0.5 s) and spins. It is reached from `do_reset`, which
    is called by `panic()` (the abort handlers' "Resetting CPU ..." goes through `panic`), and by
    `do_bootm_states` when a step returns -1. That covers one more case: `boot_selected_os`
    (`0x4A00D7BC`) returns -1 whenever `boot_jump_linux` comes back. Nothing else in U-Boot touches
    a watchdog. A `hang()` spins with interrupts off, so it would look like E1's expected spin, not
    like a reset.

    **E0's `bootreason=button` says nothing.** U-Boot builds that key from a PMIC power-on code in
    `gd` (`0x4A006C40`, table: button, irq, usb, battery, unknow). A watchdog reset doesn't power-cycle
    the PMIC, so the latched code stays at its last cold start (read).

    **What's left.** No step read so far makes our image fault where the flash boot doesn't. If the
    reset is in U-Boot (E1's premise: no watchdog armed at the handoff, see attempt 12), it is an abort in code not read in full (`fdt_chosen` and the sunxi board fixups, the
    overlay apply, the steps before LOADOS), or `boot_jump_linux` returning. The steps up to LOADOS
    and the steps after it can be told apart without a debugger:

    - **Plan F, step F1: `wrap-ram-boot.py --stop-os`.** This is E1's canary with one more change:
      `ih_os` = 1 (OpenBSD). The stock `boot_os` table (`0x4A0601D0`) has no entry for 1. So
      `do_bootm_states` runs everything through LOADOS (update_bootargs, the pre-step, FINDOS with
      both CRCs, the in-place load), then prints "booting os ... is not supported", turns interrupts
      back on and returns 1, not -1: no reset. Fastboot keeps running. Against the E1 image only the
      header CRC (0x804–0x807) and byte 0x81c differ (`cmp` on the backup).
      - **The tool exits 2** (U-Boot answers `getvar` again): everything through LOADOS is fine.
        The reset is in PREP (`image_setup_libfdt`) or in GO (the DTO merge, the PMIC hook at
        `0x4A0027B0`, `cleanup_before_linux`, the `smc`). The next step is reading those, host only.
      - **It resets like E1**: the reset comes before PREP, in `do_bootm`'s first steps or in
        FINDOS/LOADOS. Read `0x4A0067DC` (called at the end of update_bootargs) and the LOADOS
        cache flush next.
      - **What F1 can't settle:** it never jumps, so it can't tell a watchdog at the handoff from a
        U-Boot reset after LOADOS. If F1 exits 2, the open premise above (a watchdog
        armed at the handoff) still has to be ruled out before PREP and GO are blamed. Reading the
        SCP firmware's R_WDOG use is the host-only way to do that.
      - Prediction: exit 2 (a guess, about 65 %). The steps up to LOADOS read clean, and most
        image-specific work (the DT, the merge) comes later.
    - **For any later real boot (not a cause of the reset):** strip the keys update_bootargs adds
      from the command line before wrapping, and carry `androidboot.dtbo_idx` in a way the legacy
      path honours. As read, it doesn't parse that key at all, so the DT overlays are wrong on this
      path whatever we pass. Untested.
    - **Result (F1, 2026-11-19, verified by the root):** it reset like E1. `boot` ended with the
      USB dropped (exit 0), the flash kernel was up about 8 s later, `ro.boot.gw139` was empty and
      nothing was written. F1 never leaves U-Boot, so this reset is in U-Boot, before PREP, and the
      watchdog-at-handoff premise doesn't bear on it. The prediction was wrong.

14. **The steps plan F named, read** (2026-11-19, read: the same disassembly as attempt 10). Host
    only. All three read clean for our image:

    | Step | What it does | How sure |
    |---|---|---|
    | `0x4A0067DC`, called at the end of `update_bootargs` | Does work only when `gd+0xc0` is 1. That is the same flag that gates the one `update_bootargs` key missing from the flash command line, so it is clear on this panel, and the function returns 0 at once | read |
    | FINDOS's CRC checks | Header: `crc32` (`0x4A0582AC`) over a 64-byte stack copy of the header. Data: `crc32_wd(0, data, size, 0x10000)` (`0x4A0582B8`), which here is the same plain table-driven `crc32`, with no chunking and no watchdog. It only reads 0x41000840–0x42F4DEB3, DRAM that the download has just written | read |
    | The LOADOS cache flush | `flush_cache(start & ~0x3f, size)` (`0x4A001834`) → `flush_dcache_range` (`0x4A000BC0`): a misalignment warning check, then a clean-and-invalidate by address (`c7, c14, 1`) per cache line over the same range. Nothing else | read |

    Also read on the way: `do_reset` (`0x4A00180C`) prints "resetting ...", waits 50 ms, turns off
    interrupts, calls the board hook (`0x4A0027DA`) and then `reset_cpu`.

    **No cause found.** No step read between the stock `boot` handler and the end of LOADOS makes
    our image fault, or return -1. What is left before PREP, and not read in full:
    - the `boot` handler's `env_set("bootargs")` of our 1234-byte line;
    - `do_bootm`'s first step (`0x4A006520`): attempt 10 says it returns 0 on this panel;
    - START's LMB setup, `image_print_contents` (`0x4A00FD4C`) and LOADOS's `lmb_reserve`.

    The fault could also be something the reading can't see, like a watchdog that only a long stay
    in `bootm` trips. The one image-specific cost before PREP is the 31 MiB CRC pass, which the
    Android path doesn't have. That is a guess, untested. There is no plan G yet: the next step is
    the maintainer's call (see the open questions).

14. **Re-patching an already-patched image** (2026-11-18, verified on a host). The Kiln goldens
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
    `s1kiosk_radio` next to the new radio slot, both driving the radio. Both patchers now drop every
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
| No-opening route | `adb reboot bootloader` reaches stock fastboot, and its gate lets `download`, `boot` and `flash` through. Stock `boot` only runs a legacy uImage, so the RAM test needs a wrapped image | Attempts 2–4, 7–14; panel tests T1, T3 unwrapped, T3 wrapped, the canary and plan F (verified: all three reset into flash; F1 shows the reset is in U-Boot before PREP), cause not found |
| Root writes `boot` from Android | Not on field panels: adb has no root. Only on panels with adb root (the dev panel) | Attempt 5 |

### The loader

- **Fixed slots.** One `.rc` in the boot image (`overlay.d/glassworks.rc`) names every service the
  frame can run, and each slot runs `glassworks_loader.sh run <slot>`. The loader execs the slot's
  script from the active bundle, or the boot image's own copy when there is no valid bundle.
  - Slots: `glassworks_agent` (the frame agent, [principles](../../../docs/principles.md#the-frame-agent)), `glassworks_netwatch`, `glassworks_firstboot`, `glassworks_update`,
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
  copies run. In the long-running slots (`agent`, `netwatch`, `svc1`, `svc2`), a bundle script whose runs
  each last under 60 s, 3 runs in a row, is marked bad for that bundle version, and that slot runs the boot
  copy from then on. The run-once slots (`firstboot`, `update`) have no retry loop: their bundle script runs
  once per start. That was the 0.4.3 radio helper's rule, applied to the long-running slots.

### What the app reads

| What | Where | Values |
|---|---|---|
| Frame id and version (what runs) | `ro.glassworks.frame.id`, `ro.glassworks.frame.version` | `aqara-s1-plus`; the loaded bundle's version, else the boot image's (`1.0.0`) |
| Bundle state, and a staged bundle ("staged, restart to apply") | `/data/glassworks-pub/frame-bundle`, root 0644, written at boot (deleted first, so an old one never stands for this boot's) and when an install stages a bundle; an install that finds the bundle already loaded leaves it as it is | `<ok\|bad\|none> <loaded frame version\|-> <floor> <staged frame version\|->` |
| The same, for display and logs | `sys.glassworks.bundle.state`, `sys.glassworks.bundle.staged` | `ok <version>` \| `bad: <why>` \| `none`; the staged version, empty when none |
| The last install | `sys.glassworks.bundle.status` | `<running\|ok\|error>: <detail>` |

Decisions come from the file. Any app can set `sys.*` properties under permissive SELinux, so those are for
display only. "Staged" is not an `ro.*` property, because `ro.*` can be set only once per boot (a second
install in the same boot couldn't update it) and any app could set it first.

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
- **What it carries** at S1 frame 1.0.0: the slot scripts (`services/agent.sh`, `netwatch.sh`,
  `firstboot.sh`, `update.sh`), the frame agent's binaries and firmware (all of today's radio
  bundle, and its `hotspot-name.apk`), and `bootanimation.zip`.
- **The old `S1RADIO-BUNDLE 1` format** is refused cleanly by the new loader ("not a frame bundle").
  The app ships only the frame bundle from S1 frame 1.0.0 on.
- **Where it comes from.** The app ships the bundle in its assets, as it ships the radio bundle now.
  A frame update in HA stages it, starts `glassworks_bundle`, and restarts the panel.

### The names (#528)

The loader's own names are Glassworks names from the reflash on. Everything else moves later in a
bundle, once the loader makes that possible over the air.

| 0.4.3 boot image | S1 frame 1.0.0 | Notes |
|---|---|---|
| init services `s1kiosk_radio`, `_update`, `_bundle`, `_netwatch`, `_firstboot` | `glassworks_agent`, `_update`, `_bundle`, `_netwatch`, `_firstboot` | The radio helper does far more than the radio now, so it is the frame agent (maintainer, 2026-11-19). Plus `glassworks_svc1`, `_svc2` |
| `s1kiosk_devsu` (dev images) | `glassworks_devsu` | |
| `s1kiosk_verify`, `s1kiosk_bundle.pub`, `s1kiosk_adb_keys`, `s1kiosk_bootanimation.zip`, `s1kiosk_*.rc` | `glassworks_verify`, `glassworks_bundle.pub`, `glassworks_adb_keys`, `glassworks_bootanimation.zip`, `glassworks.rc` | In the ramdisk (`overlay.d`) |
| `sys.s1kiosk.update.*`, `sys.s1kiosk.bundle.*`, `sys.s1kiosk.radio.*`, `sys.s1kiosk.timezone.status`, `sys.s1kiosk.portal.status`, `sys.s1kiosk.hotspot_name.status` | `sys.glassworks.*`, same suffixes, except the agent's own two: `sys.glassworks.agent.version`, `sys.glassworks.agent.source` (were `radio.version`, `radio.source`) | |
| `persist.s1kiosk.radio_mode`, `radio_zigbee`, `radio_allow`, `timezone`, `hotspot_name` | `persist.glassworks.*` | The frame agent copies each old value at its first start (where the new one is empty), then again only when the old value changes, so the radio mode and the time zone survive the reflash and a stopped update. Removed with the old names |
| `/data/s1kiosk` (bundle store, update copy, markers), `/data/s1kiosk-pub` | `/data/glassworks`, `/data/glassworks-pub` | |
| Magisk module `s1kiosk_hotspot_name`, overlay package `sh.edm.s1kiosk.overlay.hotspotname` | **stay** | A 0.4.3 helper made the module; a second id would leave two, one the undo can't see |
| `/data/s1kiosk/radio` (dataset, Zigbee NVM, otbr's Thread settings in `run/thread`) | **stays** | `otbr-agent` has `/data/s1kiosk/radio/run` compiled in (`build-otbr.sh` line 127). It moves in a later bundle, with an otbr built for the new path and a one-time move of `run/thread` |
| `applicationId` `sh.edm.s1kiosk`, `files/update/pending.apk` | stays | The app id stays (maintainer). App-private paths sit under it |
| (none) | `ro.glassworks.frame.id`, `ro.glassworks.frame.version` | New |

The S1 frame 1.0.0 app reads and writes both sets of names for that one release. Which set it uses
depends on the boot image: `ro.glassworks.frame.id` is set on a loader image and missing on 0.4.3's.
That code is marked for removal. About a month later a release drops the old names, and a panel still
on a 0.4.3 boot image gets an update card that says to update with Kiln first.

### The Kiln update: no opening

> **2026-11-19: route 2 is the one chosen** (#139 issuecomment-7020084611): a boot writer in the signed radio bundle
> puts the loader into the panel's own boot image over the air, with the FEL install's patch. It is
> [s1-boot-writer.md](../../../docs/research/s1-boot-writer.md). The route below stays as the research it was.

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
3. **`adb reboot bootloader`** (no root needed), then on the stock U-Boot's fastboot: `getvar product`
   (it answers the serial: never log it), then **`fastboot boot` the new image, in RAM**. Attempt 8:
   stock `boot` runs only a legacy uImage, so this step needs the image wrapped as one (untested). Kiln waits for adb, then checks the loader:
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
   `/data/glassworks-pub/frame-bundle` (`<state> <loaded version> <floor> <staged version>`, e.g.
   `ok 1.0.1 1000001 -`), has `ok` in its first field and the release's version in its second. The boot step deletes that file
   before it starts, so a record there was written by the reboot Kiln asked for. `ro.glassworks.frame.version`
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

T0, T1 and T5 ran on 2026-11-19 (attempt 7 and #139's comments). T1 passed once macOS allowed the
accessory, T5 passed, and T2 wasn't needed. T3 ran twice on 2026-11-19 (verified): with the plain
image, stock `boot` returned to fastboot without booting (attempt 9). With the wrapped image, the
download and `boot` went through, and the board reset into its flash boot within about 6 s, with
nothing written (attempt 11). Run T3 with `fastboot-usb.py`, not the `fastboot` CLI
(attempt 8). T4 needs `fastboot flash`, which the tool refuses on purpose, so T4 stays pending until
a write procedure for the stock fastboot is written and reviewed. The results go here, with dates.
Each test writes only bytes the panel already has, except T2: it writes `bootloader` into `misc`
bytes 0–31, then puts the backup's bytes back. They need a panel with adb root (a dev boot image) for the backup and for T5. The root's run
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
   of `/proc/cmdline` (`tr ' ' '\n' | cut -d= -f1`). Save the full line, read as root, to
   `$B/cmdline-flash.txt` with the backup (T3's wrapped image needs it):
   `adb shell su -c 'cat /proc/cmdline' > "$B/cmdline-flash.txt"`. It holds the serial and MAC
   addresses: keep it in `$B`, out of the repo and out of notes.
2. **T1**: `adb reboot bootloader`, approve the Mac's accessory prompt, then `fastboot-usb.py getvar`
   (not the CLI: attempt 8). Record the USB vendor and product IDs and the getvars. `product` is the
   panel's serial: the tool prints it as `<redacted>`, and the note records it that way. If Android
   boots instead, nothing was written: go to T2.
3. **T2** (only if T1 failed): `su -c 'printf bootloader | dd of=/dev/block/by-name/misc bs=32 count=1 conv=notrunc,sync'`,
   then `adb reboot`. Afterwards (from Android, or from T3's RAM system if U-Boot keeps landing in
   fastboot) put the backup's first 32 bytes back: `head -c 32 "$B/misc.img" > misc32.bin`,
   `adb push misc32.bin /data/local/tmp/`, then as root
   `dd if=/data/local/tmp/misc32.bin of=/dev/block/by-name/misc bs=32 count=1 conv=notrunc`, and compare
   the first 2048 bytes with the backup.
4. **T3**: in fastboot, `fastboot-usb.py boot "$B/boot-ram.img"`. **Expected with this image: no boot.**
   `boot-ram.img` is an Android image with a raw arm64 kernel at offset 2048, and stock `boot` runs
   only a legacy uImage there (attempt 8). The tool then exits 2 ("did not boot"), and the panel stays
   in fastboot. That confirms attempt 8 (it did, attempt 9). Leave with `fastboot-usb.py reboot`, or
   power-cycle; nothing was written. A T3 pass needs the wrapped image (attempt 10). It ran on
   2026-11-19 (attempt 11): `boot` gave a USB I/O error, and the board reset into its flash boot
   within about 6 s, with `ro.boot.gw139` empty. So it doesn't pass yet. The canary of attempt 12
   (plan E) and plan F (attempt 13, `--stop-os`) reset the same way, and attempt 14 found no cause. Don't
   repeat it until one is found. To repeat it, build it on the host:
   `wrap-ram-boot.py "$B/boot.img" "$B/cmdline-flash.txt" "$B/boot-ram-uimage.img"`. Once Android is
   up again, go back to fastboot (`adb reboot bootloader`, then approve the Mac's accessory prompt),
   and run `fastboot-usb.py boot "$B/boot-ram-uimage.img"`. Only if a boot does start (the tool exits
   0, "USB dropped"): wait for `sys.boot_completed`, check
   `ro.boot.gw139=ram`, root, the radio helper, Wi-Fi, and compare the `/proc/cmdline` keys with T0's.
   If nothing comes up within 4 minutes, power-cycle: the panel boots from flash, unchanged.
5. **T4** (only if T3 passed; pending, see above): back in fastboot, `fastboot flash boot boot.img`,
   then a RAM boot of the uImage-wrapped image that passed T3 (not `boot-ram.img`, which stock `boot`
   doesn't start). In that RAM system, `su -c 'sha256sum /dev/block/by-name/boot'` must equal the
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
| L1 | `/data` at `post-fs-data`, and the `exec` step (on a store with no bundle: no `/data/glassworks/bundle/current`, `next` or `next.old`; with one, expect `ok <its version>` instead) | `getprop ro.glassworks.frame.id` = `aqara-s1-plus`, `ro.glassworks.frame.version` = `1.0.0`, `sys.glassworks.bundle.state` = `none`; the boot step's lines in the kernel log, as in L5 (not logcat: logd isn't up at `post-fs-data`) |
| L2 | The slots under init | `init.svc.glassworks_agent`, `_netwatch` = `running`; `init.svc.glassworks_firstboot` = `stopped` (it exits at once: the 0.4.3 marker says the panel was provisioned); no `init.svc.s1kiosk_*` |
| L3 | The radio from the old setting. Starts from a known state: 0.4.3 running `thread_router` (step 0 of the run sheet checks it), no `/data/s1kiosk/radio/.settings-moved`, no frame bundle | `persist.glassworks.radio_mode` = `thread_router`, `sys.glassworks.radio.status` = `ok thread_router…`, `sys.glassworks.agent.source` = `boot`, otbr runs |
| L4 | The boot animation mount from a shell at `post-fs-data` | the Glassworks animation plays at boot (film it); `/proc/mounts` lists `bootanimation.zip` |
| L5 | The check's cost at `post-fs-data` | `dmesg \| grep glassworks_loader`: the times of `boot: checking the frame bundle` and `boot: done: …`, with and without a bundle (a bundle needs the bundles PR). Not logcat: at `post-fs-data` logd isn't running, and the S1 run of 2026-11-19 found nothing there; the loader writes every line to `/dev/kmsg` too |

Results, 2026-11-19 (dev S1, the loader image written by #651's boot writer; #651 issuecomment-7024290415):

| Test | Result |
|---|---|
| L1 | **Pass:** `ro.glassworks.frame.id` = `aqara-s1-plus`, `ro.glassworks.frame.version` = `1.0.0` |
| L2 | **Pass:** `glassworks_agent` and `glassworks_netwatch` running, `glassworks_firstboot` stopped; of the old services only `s1kiosk_devsu` (the dev image's, carried over on purpose) |
| L3 | **Pass:** `persist.glassworks.radio_mode` = `thread_router`; otbr-agent up within 4 min, `sys.glassworks.radio.status` = `ok thread_router: joined as router` |
| L4 | **Pass:** the boot animation plays as before (watched), `bootanimation.zip` mounted |
| L5 | **Inconclusive:** no `glassworks_loader` line in `logcat -b all` or `dmesg` (`log -t` before logd). Fixed by the kernel-log lines; rerun pending |

## Open questions

- What resets the board after the wrapped image's `boot` (attempts 11–14): a U-Boot step before
  PREP (F1, verified), but no step read so far explains it.
- What next (maintainer's call): keep reading the stock U-Boot (attempt 14 lists what is left
  before PREP), or drop the RAM test from the no-opening route. T4 as written needs a T3 pass,
  because its read-back runs from a RAM-booted system. Without one, a stock `flash` could only be
  read back from Android after a boot from that same flash. That isn't T4, and it would need its own
  reviewed procedure. Until a RAM boot of Android works, the RAM test in the no-opening route is
  untested.
- The legacy `bootm` path doesn't read `androidboot.dtbo_idx` (attempt 13), so a RAM boot through it
  gets the wrong DT overlays. How to give the kernel the right DT on this path is open.
- Does `fastboot boot` give Android the env's arguments (`partitions=`, `snum`, MACs)? From the
  source, no: a non-empty header command line replaces `bootargs` (attempt 8).
- How long does the full bundle check take at `post-fs-data` on the panel? About 40 MB is hashed
  (otbr, the Zigbee runtime and the boot animation). The loader PR measures it.
