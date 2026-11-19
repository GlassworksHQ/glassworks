#!/usr/bin/env python3
"""Wraps an S1 boot image so the stock U-Boot's fastboot `boot` can run it from RAM (#139, plan D).

Host only: reads two local files and writes one. No USB, nothing on the panel. The output carries the
panel's full kernel command line (serial, MACs, vbmeta digest), so keep it with the backup, outside
the repo, and never paste it. Only the root RAM-boots it, with fastboot-usb.py.

    wrap-ram-boot.py BOOT_IMG CMDLINE_FILE OUT      build OUT, then check it
    wrap-ram-boot.py --canary BOOT_IMG CMDLINE_FILE OUT
                                                    the same, with the kernel's first instruction
                                                    replaced by an AArch64 `b .` (plan E)
    wrap-ram-boot.py --check OUT                    check an image built earlier

BOOT_IMG is the panel's own `boot` (Android header v2, page 2048). CMDLINE_FILE is /proc/cmdline from
a boot from flash. Why this layout (frame-loader.md, attempt 10, read from the stock U-Boot's code):
- Stock `boot` checks for a legacy uImage magic at buffer + 0x800, sets `bootargs` to the C string at
  buffer + 0x40 when it isn't empty, and runs `bootm buffer+0x800`. The buffer is 0x41000000.
- Stock `do_bootm` first reads the bytes at that address as an Android header and moves a "ramdisk"
  from it. At the uImage header, its ramdisk_size is ih_load, so ih_load must be 0. That rules out a
  kernel that bootm copies: the image is KERNEL_NOLOAD and the kernel runs where it was downloaded.
- arm64 wants the Image at a 2 MiB boundary plus text_offset (0x80000), so zero padding puts it at
  0x41080000. ih_ep is that padding: bootm adds it to the data start.
- The ramdisk goes after the kernel's whole image_size (its BSS), and the kernel finds it through
  `initrd=<addr>,<size>` on the command line.
- ih_arch is ARM: the stock U-Boot is 32-bit and accepts only its own arch. It hands the kernel to
  the 64-bit ATF either way.
"""
import struct
import sys
import zlib

BUF = 0x41000000              # stock fastboot download buffer (sunxi_fastboot_init, mov.w r3, #0x41000000)
MAX = 0x02000000              # max-download-size
UIMG = 0x800                  # stock `boot` looks for the uImage here
CMDLINE = 0x40                # and takes bootargs from here
UIMAGE_MAGIC = 0x27051956
IH_OS_LINUX, IH_ARCH_ARM, IH_TYPE_KERNEL_NOLOAD, IH_COMP_NONE = 5, 2, 14, 0
KERNEL_PHYS = 0x41080000      # 2 MiB aligned base 0x41000000 + text_offset 0x80000
RAMDISK_ALIGN = 0x100000
PAGE0_MAGIC = b"GW139RAM"     # not ANDROID!: this file must never be mistaken for a boot partition
MARKER = "androidboot.gw139=ram"
CANARY = 0x14000000           # AArch64 `b .`: the CPU spins at the entry point, nothing else runs


class WrapError(Exception):
    pass


def android_parts(boot):
    if boot[:8] != b"ANDROID!":
        raise WrapError("BOOT_IMG is not an Android boot image")
    kernel_size, _, ramdisk_size, _, second_size = struct.unpack_from("<5I", boot, 8)
    page = struct.unpack_from("<I", boot, 36)[0]
    if page != 2048:
        raise WrapError(f"page size {page}, expected 2048")
    k0 = page
    r0 = k0 + -(-kernel_size // page) * page
    kernel, ramdisk = boot[k0:k0 + kernel_size], boot[r0:r0 + ramdisk_size]
    if len(kernel) != kernel_size or len(ramdisk) != ramdisk_size or not ramdisk_size:
        raise WrapError("BOOT_IMG is truncated, or has no ramdisk")
    if kernel[0x38:0x3C] != b"ARMd":
        raise WrapError("the kernel is not a raw arm64 Image")
    return kernel, ramdisk


def arm64_layout(kernel):
    text_offset, image_size, flags = struct.unpack_from("<QQQ", kernel, 8)
    if text_offset != KERNEL_PHYS & 0x1FFFFF:
        raise WrapError(f"text_offset {text_offset:#x}, expected {KERNEL_PHYS & 0x1FFFFF:#x}")
    if not flags & 0x8:
        raise WrapError("the kernel doesn't allow placement anywhere in RAM (Image flags bit 3)")
    return image_size


def uimage_header(data, ep):
    fields = [UIMAGE_MAGIC, 0, 0, len(data), 0, ep, zlib.crc32(data)]
    tail = bytes([IH_OS_LINUX, IH_ARCH_ARM, IH_TYPE_KERNEL_NOLOAD, IH_COMP_NONE]) + b"gw139 ram".ljust(32, b"\0")
    hdr = struct.pack(">7I", *fields) + tail
    hcrc = zlib.crc32(hdr)
    return hdr[:4] + struct.pack(">I", hcrc) + hdr[8:]


def build(boot, cmdline, canary=False):
    kernel, ramdisk = android_parts(boot)
    image_size = arm64_layout(kernel)
    cmdline = cmdline.strip()
    if not cmdline or "\n" in cmdline or "\r" in cmdline:
        raise WrapError("CMDLINE_FILE must hold one line")
    if "initrd=" in cmdline or "gw139" in cmdline:
        raise WrapError("CMDLINE_FILE already has initrd= or the gw139 marker")
    rd_phys = -(-(KERNEL_PHYS + image_size) // RAMDISK_ALIGN) * RAMDISK_ALIGN
    total = rd_phys - BUF + len(ramdisk)
    if total > MAX:
        raise WrapError(f"wrapped image is {total} bytes, over max-download-size {MAX}")
    line = f"{cmdline} {MARKER} initrd={rd_phys:#x},{len(ramdisk):#x}".encode("ascii")
    if CMDLINE + len(line) >= UIMG:
        raise WrapError(f"command line is {len(line)} bytes, at most {UIMG - CMDLINE - 1} fit")
    out = bytearray(total)
    out[:8] = PAGE0_MAGIC
    out[CMDLINE:CMDLINE + len(line)] = line
    k = KERNEL_PHYS - BUF
    out[k:k + len(kernel)] = kernel
    if canary:
        out[k:k + 4] = struct.pack("<I", CANARY)
    r = rd_phys - BUF
    out[r:r + len(ramdisk)] = ramdisk
    data_start = UIMG + 64
    out[UIMG:data_start] = uimage_header(bytes(out[data_start:]), k - data_start)
    return bytes(out)


def check(img, boot=None, canary=False):
    """Re-reads the image the way the stock U-Boot and the kernel will. Returns a summary without the command line."""
    if not UIMG + 64 < len(img) <= MAX:
        raise WrapError(f"size {len(img)} is outside 2112..{MAX}")
    hdr = bytearray(img[UIMG:UIMG + 64])
    magic, hcrc, _, size, load, ep, dcrc = struct.unpack_from(">7I", hdr, 0)
    os_, arch, typ, comp = hdr[28:32]
    hdr[4:8] = b"\0\0\0\0"
    data = img[UIMG + 64:]
    problems = []
    if magic != UIMAGE_MAGIC:
        problems.append("no uImage magic at 0x800")
    if zlib.crc32(bytes(hdr)) != hcrc:
        problems.append("bad header CRC")
    if size != len(data) or zlib.crc32(data) != dcrc:
        problems.append("data size or CRC wrong")
    if (os_, arch, typ, comp) != (IH_OS_LINUX, IH_ARCH_ARM, IH_TYPE_KERNEL_NOLOAD, IH_COMP_NONE):
        problems.append(f"os/arch/type/comp {os_}/{arch}/{typ}/{comp}")
    if load != 0:
        problems.append("ih_load isn't 0: stock do_bootm would read it as a ramdisk size")
    entry = BUF + UIMG + 64 + ep
    if entry != KERNEL_PHYS or img[entry - BUF + 0x38:entry - BUF + 0x3C] != b"ARMd":
        problems.append(f"entry {entry:#x} isn't an arm64 Image at {KERNEL_PHYS:#x}")
    nul = img.find(b"\0", CMDLINE, UIMG)
    if nul <= CMDLINE:
        problems.append("no NUL-terminated command line before 0x800")
        line = ""
    else:
        line = img[CMDLINE:nul].decode("ascii", "replace")
    initrd = [w for w in line.split() if w.startswith("initrd=")]
    if MARKER not in line.split() or len(initrd) != 1:
        problems.append("command line lacks the marker or exactly one initrd=")
    else:
        rd_phys, rd_len = (int(x, 16) for x in initrd[0][7:].split(","))
        image_size = struct.unpack_from("<Q", img, entry - BUF + 16)[0]
        if rd_len == 0:
            problems.append("initrd= gives an empty ramdisk")
        elif rd_phys < entry + image_size or rd_phys - BUF + rd_len > len(img):
            problems.append("ramdisk overlaps the kernel's image_size or runs past the end")
        if boot is not None:
            kernel, ramdisk = android_parts(boot)
            if canary:
                kernel = struct.pack("<I", CANARY) + kernel[4:]
            k = KERNEL_PHYS - BUF
            if img[k:k + len(kernel)] != kernel or img[rd_phys - BUF:rd_phys - BUF + rd_len] != ramdisk:
                problems.append("kernel or ramdisk bytes differ from BOOT_IMG")
    if problems:
        raise WrapError("; ".join(problems))
    spin = struct.unpack_from("<I", img, entry - BUF)[0] == CANARY
    return (f"ok: {len(img)} bytes, kernel at {KERNEL_PHYS:#x}, {initrd[0].split(',')[0]}, "
            f"command line {len(line)} bytes (not shown)" + (", canary: `b .` at the entry" if spin else ""))


def main(argv):
    try:
        if len(argv) == 3 and argv[1] == "--check":
            print(check(open(argv[2], "rb").read()))
            return 0
        canary = argv[1:2] == ["--canary"]
        args = argv[2:] if canary else argv[1:]
        if len(args) != 3:
            sys.stderr.write(__doc__.split("\n\n")[2] + "\n")
            return 1
        boot = open(args[0], "rb").read()
        img = build(boot, open(args[1], encoding="ascii").read(), canary)
        print(check(img, boot, canary))
        with open(args[2], "wb") as f:
            f.write(img)
        print(f"wrote {args[2]}")
        return 0
    except (WrapError, OSError, UnicodeDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
