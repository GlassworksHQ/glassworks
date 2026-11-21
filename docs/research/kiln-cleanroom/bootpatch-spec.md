---
topic: install
evidence: read
---

# Clean-room spec: `kiln/packages/bootpatch`

**Status (2026-11-15): spec ready. The rewrite passes the vectors and the S1 goldens, and waits on a real panel (#355).** This page says what Kiln's boot
image patcher must do, in behaviour and formats. It is the input for an implementer who has not read
Magisk's source. Read [README.md](README.md) first: it says what you may open and what you must not.

## What the package does

Kiln roots the panel by patching its `boot` partition the way Magisk v31.1 does. The real CLI runs
Magisk's own tools in Docker (`frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh`), and the
browser must produce **the same bytes**. The input is a dump of the `boot` partition (an Android boot
image, header v2, gzip ramdisk, AVB footer). The output is an image of the same size, where:

- the ramdisk holds Magisk's three binaries, a backup of what they replaced, and a small config;
- Kiln's own overlay files are added to the ramdisk;
- the header, its id hash and the AVB footer are updated to match.

You write the boot image parser and repacker, the cpio reader and writer, the ramdisk patch, and the
WebAssembly module's source (`src/wasm/src/lib.rs`). The APK reader, the overlay, vbmeta and boot
logo code stay.

The reference to compare against is the CLI. You may run it (`patch-boot-magisk.sh`, Docker) and
read its shell, which is ours. You may also read Magisk's published documentation
(https://topjohnwu.github.io/Magisk/, "Magisk Tools" and "Installation"). Don't open Magisk's source.

## Public API to keep

`src/index.ts` must export these. Other code imports them by name.

| Export | Behaviour |
|---|---|
| `patchBoot(input: PatchInput): Promise<PatchResult>` | Take the binaries out of the APK with `extractMagiskPayload` (`apk.ts`, stays), then `patchBootImage` |
| `patchBootImage(input: PatchImageInput): Promise<PatchResult>` | The patch, below |
| `isMagiskPatched(boot: Uint8Array): Promise<boolean>` | True when the image's ramdisk is in the "Magisk" state (below) |
| `interface PatchInput` | `{ boot: Uint8Array; magiskApk: Uint8Array; overlay: OverlayFile[]; preinitDevice?: string }` |
| `interface PatchImageInput` | `{ boot: Uint8Array; magisk: MagiskBinaries; overlay: OverlayFile[]; preinitDevice?: string }`. `preinitDevice` defaults to `'cache'` |
| `interface PatchResult` | `{ image: Uint8Array; wasMagisk: boolean; magiskConfig: string; log: string[] }`. `log` lines are free text in your words |
| `interface OverlayFile` | `{ path: string; mode: number; data: Uint8Array }` |
| `magiskBusybox(magiskApk: Uint8Array): Promise<Uint8Array>` | `lib/armeabi-v7a/libbusybox.so` out of the APK, for a frame's overlay (the S1's `s1Overlay`) (`extractMagiskBusybox` in `apk.ts`). A zip read; it doesn't change the patch |
| re-exports | `vbmetaFlags`, `withVbmetaFlags` (`vbmeta.ts`), `readBootlogo`, `replaceBootlogo` (`bootlogo.ts`), `loadCompressor` (`compress.ts`), `zipEntries`, `type ZipEntry`, `type MagiskBinaries` (`apk.ts`) |

`MagiskBinaries` is `{ magiskinit, magisk, initLd }`: the three `armeabi-v7a` libraries from the APK
(`libmagiskinit.so`, `libmagisk.so`, `libinit-ld.so`).

## Formats

### Android boot image, header v0..v4

From AOSP: [Boot image header](https://source.android.com/docs/core/architecture/bootloader/boot-image-header)
and `include/bootimg/bootimg.h` in `platform/system/tools/mkbootimg`. All integers are
little-endian. For v0..v2 the image is in pages of `page_size`. The header starts page 0, and each
block starts on a new page in this order: kernel, ramdisk, second, then recovery DTBO (v1+), then DTB
(v2). v3 and v4 have a fixed 4096-byte page, a different header and only kernel and ramdisk blocks.

| Offset | Size | v0..v2 field | |
|---|---|---|---|
| 0 | 8 | magic `"ANDROID!"` | all versions |
| 8 | 4 | kernel_size | |
| 12 | 4 | kernel_addr | |
| 16 | 4 | ramdisk_size | |
| 20 | 4 | ramdisk_addr | |
| 24 | 4 | second_size | |
| 28 | 4 | second_addr | |
| 32 | 4 | tags_addr | |
| 36 | 4 | page_size | |
| 40 | 4 | header_version | all versions (v3/v4 have it here too) |
| 44 | 4 | os_version | |
| 48 | 16 | name | |
| 64 | 512 | cmdline | |
| 576 | 32 | id (a digest, see below) | |
| 608 | 1024 | extra_cmdline | |
| 1632 | 4 | recovery_dtbo_size | v1, v2 |
| 1636 | 8 | recovery_dtbo_offset (absolute, in bytes) | v1, v2 |
| 1644 | 4 | header_size (1648 for v1, 1660 for v2) | v1, v2 |
| 1648 | 4 | dtb_size | v2 |
| 1652 | 8 | dtb_addr | v2 |

v3 is `magic, kernel_size, ramdisk_size, os_version, header_size, reserved[4], header_version,
cmdline[1536]` (1580 bytes), and v4 adds a u32 signature_size (1584). Kiln refuses both, but the
vectors build them to check the refusal.

**id.** mkbootimg fills `id` with the SHA-1 (20 bytes, then zeros) of: the kernel, its size as a u32,
the ramdisk, its size, second, its size, and for v1+ the recovery DTBO and its size, and for v2 the DTB
and its size. Some tools write a SHA-256 there (32 bytes). Kiln treats the id as SHA-256 when any of
bytes 24..31 is non-zero, and SHA-1 otherwise, and writes the new id the same way.

### AVB footer

From AOSP's [libavb](https://android.googlesource.com/platform/external/avb/+/refs/heads/main/README.md)
(`libavb/avb_footer.h`). The last 64 bytes of the partition: magic `"AVBf"`, u32 version major, u32
version minor, then **big-endian** u64 `original_image_size` at 12, u64 `vbmeta_offset` at 20, u64
`vbmeta_size` at 28, then 28 reserved bytes. At `vbmeta_offset` sits the vbmeta block, which starts
with `"AVB0"`. Kiln copies the block as it is. It never parses or re-signs it, because the panel's
vbmeta flags turn verification off (`vbmeta.ts`).

### cpio "newc"

The ramdisk is a cpio archive in the "newc" format: [cpio(5)](https://man.freebsd.org/cgi/man.cgi?query=cpio&sektion=5),
and the Linux kernel's `Documentation/driver-api/early-userspace/buffer-format.rst`. Each entry is a
110-byte ASCII header: `"070701"` then 13 fields of 8 hex digits (ino, mode, uid, gid, nlink, mtime,
filesize, devmajor, devminor, rdevmajor, rdevminor, namesize, check). Then the name with its NUL,
padded so the next part starts on a multiple of 4, then the data, padded the same way. The archive
ends with an entry named `TRAILER!!!`.

### Compression

gzip (RFC 1952), xz ([the .xz format](https://tukaani.org/xz/xz-file-format.txt)) with its BCJ
filters, and raw DEFLATE (RFC 1951) for the APK. The exact encoder settings are under
[The WebAssembly module](#the-webassembly-module).

## Behaviour

### Reading the boot image

Refuse, with the substring in the table, anything that isn't a header v2 image of the shape the
panel uses. All refusals happen before any output.

| Check | Refuse with (must contain) |
|---|---|
| Magic `"ANDROID!"` at 0 | `not an Android boot image` |
| header_version is 2 | `header version` |
| page_size is 2048, 4096, 8192 or 16384 | `page size` |
| image at least one page long | `truncated` |
| image size a multiple of 4096 (a partition dump) | `multiple of 4096` |
| each block ends within the image (rounded up to 4096) | `Corrupted` |
| kernel is raw, not compressed (see below) | `kernel format` |
| kernel has no appended device tree (`d0 0d fe ed` anywhere with at least 40 bytes from there to the end) | `DTB` |
| ramdisk is not empty | `no ramdisk` |
| ramdisk is gzip, xz or raw cpio (below) | `ramdisk format` |
| no `"SEANDROIDENFORCE"` right after the last block (Samsung) | `SEANDROID` |
| no AVB 1.0 signature right after the last block (byte `0x30`, then a byte with bit 7 set) | `AVB1` |
| if there is an AVB footer and `"AVB0"` at its vbmeta offset, the vbmeta block ends within the image | `truncated` |
| every byte the repack would drop is zero: from the end of the last block to the vbmeta block (or, without AVB data, to the end of the image), and from the end of the vbmeta block to the footer | `unsupported` |

**Out of scope.** Kiln patches only the S1's Allwinner boot image, which the golden tests cover. Images
that need vendor-specific handling, such as Samsung kernels that Magisk binary-patches or LG "bump"
images, are not supported. The rewrite doesn't need to detect them by their bytes, and no vector
covers them. The "every byte the repack would drop is zero" check refuses such images generically,
because their extra data sits after the last block. On all of the S1's boot images in the golden set
(stock 4.5.80, the older stock image, the bundled Magisk image, and every patched output), those
ranges are all zeros (verified 2026-11-15 with the read-only `tools/kiln/boot-padding.py`, which reads
the ranges from the header and the AVB footer as described above):

```
$ python3 -I tools/kiln/boot-padding.py ~/src/aqara-panel-backups/web-golden/{in,out}/*.img
boot-4.5.80.img: gap 0 bytes, all zero True; tail 12090752 bytes, all zero True
boot-old.img: gap 2048 bytes, all zero True; tail 12090752 bytes, all zero True
s1-boot-magisk-v31.1-bundled.img: gap 0 bytes, all zero True; tail 10931584 bytes, all zero True
noanim-4.5.80.img: gap 0 bytes, all zero True; tail 10308992 bytes, all zero True
nokey-4.5.80.img: gap 0 bytes, all zero True; tail 6352256 bytes, all zero True
preinit-data-4.5.80.img: gap 0 bytes, all zero True; tail 6352256 bytes, all zero True
repatch-4.5.80.img: gap 0 bytes, all zero True; tail 6352256 bytes, all zero True
repatch-bundled.img: gap 2048 bytes, all zero True; tail 6352256 bytes, all zero True
repatch-noanim-from-anim.img: gap 0 bytes, all zero True; tail 6352256 bytes, all zero True
stock-4.5.80.img: gap 0 bytes, all zero True; tail 6352256 bytes, all zero True
stock-old.img: gap 2048 bytes, all zero True; tail 6352256 bytes, all zero True
```

("gap" runs from the last block to the vbmeta block, and "tail" from the vbmeta block to the footer.)
The code being replaced doesn't have this check, so no recorded vector covers it: add a test with a
synthetic image that has non-zero bytes there.

An AVB footer whose offset doesn't point at `"AVB0"` is ignored, and the image is treated as having
no AVB data.

**Format signatures**, for the kernel and ramdisk checks (they are the formats' own magic numbers):

| Format | Starts with |
|---|---|
| gzip | `1f 8b 08` or `1f 9e 08` |
| xz | `fd 37 7a 58 5a 00` |
| lzop | `89 4c 5a 4f` |
| bzip2 | `"BZh"` |
| lz4 | `04 22 4d 18` or `03 21 4c 18` |
| lz4 legacy | `02 21 4c 18` |
| MediaTek header | `88 16 88 58` |
| ARM zImage | `18 28 6f 01` at offset `0x24` |
| lzma (raw) | at least 13 bytes, first byte below 225, bytes 5..12 all `ff`, and the u32 at 1 a non-zero power of two |

A kernel that matches any of these is refused. A ramdisk must be gzip, xz, or neither of these and
start with `"070701"` (raw cpio). Anything else is refused.

### Reading the ramdisk

Decompress it (gzip: all members; xz: all streams; a corrupt xz is refused with any wording). Then
read the cpio:

- An entry keeps: name, mode, uid, gid, rdevmajor, rdevminor and data. ino, nlink, mtime, devmajor,
  devminor and check are dropped.
- Names are UTF-8. `.` and `..` are skipped. If a name repeats, the later entry wins.
- After a `TRAILER!!!` entry, reading goes on at the next `"070701"` found further on (the ramdisk may
  be several archives back to back, with padding between). If there is none, the archive ends.
- Refuse a damaged archive (a bad magic where a header should be, a name or data running past the
  end), with any wording.

### The ramdisk's state

| State | When | Checked |
|---|---|---|
| another root tool | any of `sbin/launch_daemonsu.sh`, `sbin/su`, `init.xposed.rc`, `boot/sbin/launch_daemonsu.sh` exists | first |
| Magisk | any of `.backup/.magisk`, `init.magisk.rc`, `overlay/init.magisk.rc` exists | second |
| stock | otherwise | |

`isMagiskPatched` is true only for "Magisk".

### The patch

Inputs: the boot image `I`, the binaries, the overlay files, `preinitDevice` (default `cache`). `S` is
the SHA-1 of all of `I`'s bytes, in lower-case hex.

1. **State.** If "another root tool": refuse, with a message that contains `stock boot image` ("This
   boot image was rooted by another tool: restore the stock boot image first."). If "Magisk" and there
   is no `.backup/.magisk`: refuse, with a message that contains `.backup/.magisk` ("This boot image
   has an old Magisk without .backup/.magisk: restore the stock boot image first.").
2. **Undo an earlier Magisk patch** (state "Magisk"). Keep the text of `.backup/.magisk` as the old
   config. Then restore:
   - take every entry under `.backup/` out of the ramdisk;
   - `.backup/.rmlist` gives a list of names (NUL-separated text; if it isn't valid UTF-8, ignore it);
   - `.backup/.magisk` is dropped;
   - any other `.backup/<name>` goes back as `<name>`. If `<name>` ends in `.xz`, is a regular file
     and decodes as xz, it goes back decoded and without the `.xz`. Otherwise it goes back as it is;
   - remove the `.backup` entry itself;
   - if there was no list text and nothing to put back, the ramdisk becomes empty. Otherwise remove
     each listed name (that exact entry, not what is under it), then put the restored entries back,
     replacing what is there.

   Kiln's overlay files from the earlier patch were never listed, so they survive this step. Step 7
   removes them (#139, 2026-11-18). Before that, a file the earlier patch added and the new overlay
   omitted stayed in the ramdisk.
3. **Config values.**
   - SHA1: from the old config if there was one (the first line starting `SHA1=`, CR stripped, value
     after `=`, maybe empty), else `S`.
   - PREINITDEVICE: from the old config if there was one (same rule, maybe empty), else
     `preinitDevice`.
4. **Add Magisk.** Keep a copy of the ramdisk as it is now (the *original*). Then set, replacing what
   is there:
   - `init`: regular file, mode `0750`, the `magiskinit` bytes;
   - `overlay.d`: directory, `0750`;
   - `overlay.d/sbin`: directory, `0750`;
   - `overlay.d/sbin/magisk.xz`: regular file, `0644`, `magisk` compressed with xz (BCJ detection on
     its first 4096 bytes);
   - `overlay.d/sbin/init-ld.xz`: regular file, `0644`, `initLd` compressed the same way.

   Entries Kiln adds always have uid 0, gid 0 and rdev 0:0. A mode given as permissions becomes
   `permissions & 07777` with the file-type bits for a regular file or a directory.
5. **Back up.** Compare the original (without anything under `.backup`) with the new ramdisk
   (likewise), walking both in name order:
   - add `.backup`: a directory with mode `040000` (no permission bits), uid and gid 0;
   - for each name in the original that is missing from the new ramdisk, or whose **data** differs
     (mode and owner are not compared), add `.backup/<name>` with the original entry's mode, uid,
     gid and rdev. If it is a regular file, its data is compressed with xz (BCJ detection on the whole
     data) and the name gets `.xz`;
   - list each name that is in the new ramdisk but not in the original, in name order, each followed
     by a NUL byte. If the list isn't empty, add it as `.backup/.rmlist`: a regular file, mode
     `0100000`, uid and gid 0.
6. **Config.** Add `.backup/.magisk`: a regular file, mode `0100000`, uid and gid 0, with this text:
   ```
   KEEPVERITY=true
   KEEPFORCEENCRYPT=true
   RECOVERYMODE=false
   VENDORBOOT=false
   PREINITDEVICE=<value>      (only when the value is not empty)
   SHA1=<value>               (only when the value is not empty)
   ```
   Each line ends with `\n`. `PatchResult.magiskConfig` is this text. Kiln always keeps verity and
   forced encryption, so nothing in `fstab*` files or `verity_key` changes.
7. **Overlay.** First remove every entry under `overlay.d/` except `overlay.d/sbin`,
   `overlay.d/sbin/magisk.xz` and `overlay.d/sbin/init-ld.xz` (step 4's), so the overlay files of an
   earlier patch go. Then add each overlay file: a regular file, mode `mode & 07777`, uid and gid 0,
   replacing any entry with that name. Empty path components are dropped (`/a//b/` is `a/b`). These
   go in after the backup, so they are neither backed up nor listed. So patching a patched image gives
   the same image as patching the stock one, whatever overlay the earlier patch had
   (`test/repatch-overlay.test.ts`). The CLI does the same, and the golden cases `repatch-bundled`
   (an image with older overlay files) and `repatch-noanim-from-anim` pin it on the real image.
8. **Refuse the DTB patches the CLI would make**, which Kiln doesn't do:
   - the DTB contains `"/system_root"` followed by a NUL: refuse, with a message that contains
     `patched by old` ("This boot image's DTB was patched by old Magisk versions.");
   - the DTB contains `"skip_initramfs"`: refuse, with `skip_initramfs`.
9. **Write the ramdisk** (below), then compress it the way it came. A gzip ramdisk becomes gzip
   (best compression). An xz ramdisk becomes xz (BCJ detection on the whole archive). A raw one stays
   raw.
10. **Repack** (below). `wasMagisk` is true when the state in step 1 was "Magisk".

### Writing the ramdisk

- Entries in byte order of their UTF-8 names (so `.backup` < `A` < `a` < `a-b` < `a.b` < `a/b` < `überdir`).
- ino starts at 300000 and goes up by one per entry. nlink 1, mtime 0, devmajor and devminor 0,
  check 0. mode, uid, gid, rdevmajor, rdevminor and data come from the entry. namesize counts the NUL.
- Hex digits in lower case.
- After the last entry, a `TRAILER!!!` entry: the next ino, mode `000755` (no file type), nlink 1,
  namesize 11, everything else 0.
- Header and name padded to a multiple of 4, data padded to a multiple of 4, and no padding at the end
  beyond that.

### Repacking

The output has the input's size. It starts as zeros, and then:

1. Copy the input's whole first page (the header page) to the start.
2. Lay out the blocks from page 1 on, each on a new page: kernel, ramdisk, second (only if non-empty),
   recovery DTBO (only if non-empty), DTB (only if non-empty). The kernel, second, recovery DTBO and
   DTB are the input's bytes, and the ramdisk is the new one.
3. In the header, set kernel_size, ramdisk_size, second_size, recovery_dtbo_size and dtb_size. Set
   recovery_dtbo_offset to the DTBO's new offset if there is a DTBO, else keep the input's value. Set
   header_size to 1660. Recompute the id (same digest kind as the input), with the unused bytes of
   the id zeroed.
4. If the input had AVB data, put its vbmeta block at the first 4096-byte boundary after the last
   block. Write the input's footer into the last 64 bytes, with `original_image_size` set to the end
   of the last block (page-aligned) and `vbmeta_offset` set to the new offset. The other footer fields
   stay as they were.
5. If the blocks (and the vbmeta block and the footer) don't fit in the input's size, refuse with a
   message that contains `does not fit`.

## The WebAssembly module

`src/compress.ts` (stays) loads `src/wasm/bootpatch.wasm` and calls it through a small C-like ABI.
You rewrite `src/wasm/src/lib.rs` and rebuild the module with `src/wasm/build-wasm.sh` (Docker,
pinned toolchain; `CHECK=1` checks that the build is reproducible). `Cargo.toml` and `Cargo.lock` stay.

**Why these crates.** The output must match the CLI byte for byte, so the module must use the same
compression code as the CLI's `magiskboot`. These are public crates, at the versions in `Cargo.lock`:
`flate2` 1.1.10 on the `zlib-rs` 0.6.8 backend, and `lzma-rust2` 0.21.0.

**The ABI** (what `compress.ts` calls; keep the names):

| Export | Does |
|---|---|
| `bp_alloc(len) -> ptr`, `bp_free(ptr, len)` | Allocate and free an input buffer in the module's memory |
| `bp_out_ptr()`, `bp_out_len()`, `bp_out_free()` | The last operation's output buffer |
| `bp_gzip(ptr, len) -> 0 or 1` | gzip, `flate2` `GzEncoder` at `Compression::best()` with the default header |
| `bp_gunzip(ptr, len)` | gunzip, all members (multi-member) |
| `bp_xz(ptr, len, sniff) -> 0 or 1` | xz: `lzma-rust2` preset 6, check type CRC32, and, when BCJ detection on the first `sniff` bytes finds a filter, that filter as a pre-filter with start offset 0 |
| `bp_unxz(ptr, len) -> 0 or 1` | xz decode, all streams. 1 on any error (`compress.ts` then returns `null`) |
| `bp_inflate(ptr, len)` | raw DEFLATE decode |
| `bp_bcj(ptr, len) -> n` | Which filter detection picks: 0 none, 1 x86, 2 ARM, 3 ARM-Thumb, 4 ARM64, 5 RISC-V |

Operations return 0 on success and leave the result in the output buffer. They return 1 on error.

**BCJ detection.** These rules, and the thresholds in them (the 256-byte window, the 64-byte minimum,
the 75%), are magiskboot's own choices, not part of the .xz format. Match them as written, because the
vectors pin byte-identical output, and don't re-derive them. Given some bytes:

1. If there are at least 20 bytes and they start with `7f 45 4c 46` (ELF): read e_machine at offset
   18, little-endian if byte 5 is 1, big-endian if it is 2, and if byte 5 is anything else, no filter.
   e_machine 3 or `0x3e` gives x86, `0x28` gives ARM-Thumb, `0xb7` ARM64, `0xf3` RISC-V, and any other
   value no filter. Stop here for ELF.
2. Else, if there are at least `0x3c` bytes and bytes `0x38..0x3b` are `"ARM\x64"` (an ARM64 Linux
   Image), ARM64.
3. Else, if there are at least `0x34` bytes and bytes `0x30..0x33` are `"RSC\x05"` (a RISC-V Linux
   Image), RISC-V.
4. Else look at the first 256 bytes (or fewer, if that is all there is). With fewer than 64 bytes, no
   filter. Otherwise split them into 32-bit little-endian words, dropping a partial word at the end.
   If `count * 100 / words` (integer division) is at least 75, where `count` is the number of words
   whose top 4 bits are `0xE` or `0xF` (the A32 condition field), ARM.
5. Else no filter.

Detection never looks past byte 256 or offset `0x3c`, so a sniff length of 4096 or of the whole
buffer gives the same answer for anything 256 bytes or longer.

## Test vectors

[`kiln/packages/bootpatch/test/vectors.test.ts`](../../../kiln/packages/bootpatch/test/vectors.test.ts)
and [`test/vectors/bootpatch.json`](../../../kiln/packages/bootpatch/test/vectors/bootpatch.json),
recorded on 2026-11-15 from the implementation being replaced. Run `npx vitest run` in
`kiln/packages/bootpatch`. The inputs are synthetic, built in
[`test/vectors/synth.ts`](../../../kiln/packages/bootpatch/test/vectors/synth.ts) from the formats
above, with no Aqara bytes. A first test checks that the inputs are byte for byte the recorded ones.

- **Six patch cases**: v2 with page 2048, a gzip ramdisk, an AVB footer and a SHA-1 id; the same with
  PREINITDEVICE `metadata`; the same with no overlay files; page 4096 with a raw cpio ramdisk, second
  and recovery DTBO blocks, a SHA-256 id and no AVB; an xz ramdisk; a ramdisk of two cpio archives
  back to back. Each case checks the output's SHA-256, its size, the config text, and the full listing
  of the output ramdisk (each entry's name, mode, uid, gid, rdev, size, data SHA-256 and ino). When a
  case fails, read the listing diff first: it names the entry that differs. Each case is then patched
  again, which must report `wasMagisk` and give the same image.
- **20 refusals**, each with its required substring. They cover each row of the table above, both
  ramdisk states, the two DTB strings, and an image too small for the patched ramdisk.
- **isMagiskPatched** on stock, patched, an old Magisk marker, and another root tool.
- **The compressors**: for 13 inputs (ELF for each machine, big-endian ELF, ARM64 and RISC-V Images,
  raw A32 code, 63 bytes of it, 70% A32 words, text, random, empty), the BCJ filter picked and the
  SHA-256 of gzip, of xz with sniff 4096, and of xz with sniff of the whole buffer. Plus round trips,
  multi-member gunzip, raw inflate, and `unxz` of garbage returning `null`.

**The real panel's images.** `test/bootpatch.test.ts` has golden tests against the CLI's output on
the S1's real boot images: stock 4.5.80, an older stock image, re-patches, no boot animation, no adb
key, PREINITDEVICE `metadata`. They run when the goldens are on the machine
(`S1_WEB_GOLDEN=~/src/aqara-panel-backups/web-golden`, the default path) and are skipped otherwise.
They hold Aqara's firmware, so they never go in the repo. Their hashes aren't in the repo either: they
are no use without the input images, and the repo keeps real partition hashes out (see
`kiln/test/wire-trace.test.ts`). The rewrite must pass them on a machine that has the goldens before
it merges.

**When a vector fails.** Never edit `bootpatch.json` or `synth.ts` to make it pass. If you think a
vector is wrong, say so in #355, and the spec owner checks it.

## Files

| File | What to do |
|---|---|
| `src/cpio.ts` | **Delete without opening** |
| `src/magisk.ts` | **Delete without opening** |
| `src/bootimg.ts` | **Delete without opening** |
| `src/index.ts` | **Delete without opening**. Write a new one with the exports above |
| `src/wasm/src/lib.rs` | **Delete without opening**. Write a new one with the ABI above, rebuild `bootpatch.wasm` |
| `test/legacy-internals.test.ts` | **Delete without opening**. The vectors replace it |
| `src/wasm/bootpatch.wasm` | Rebuilt by `build-wasm.sh`. Commit the new one |
| `src/wasm/THIRD_PARTY.txt` | Keep, and correct its last line once `lib.rs` is new |
| `src/apk.ts`, `src/compress.ts`, `src/vbmeta.ts`, `src/bootlogo.ts`, `src/wasm/Cargo.toml`, `src/wasm/Cargo.lock`, `src/wasm/build-wasm.sh` | Keep. You may read and change them |
| `test/bootpatch.test.ts`, `test/vectors.test.ts`, `test/vectors/*` | The contract. Read them, don't edit the vectors |
