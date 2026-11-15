<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# ext4 for Kiln

`@glassworks/ext4` lets Kiln change an Android partition image in the browser. The image is a
system image the person downloaded themselves, about a gigabyte. Kiln reads it as an Android sparse
image, edits its ext4 (adds and removes files with their owner, mode and SELinux label), or builds a
fresh ext4. It then writes a sparse image that `@glassworks/fastboot` flashes. Nothing is uploaded:
the bytes stay on the person's computer, in the browser's private storage (OPFS).

The Lululemon Mirror's recipe uses it first: Android 6's `system.img` from the Glass Panes release,
and a new `userdata` that carries Kiln's adb key (#29, #40). A later frame that brings its own system
image can reuse it (option C in #40).

## How it fits together

| File | What |
|---|---|
| `src/disk.ts` | `Disk`: an image as blocks, on a `Store` (OPFS through `syncHandleStore`, or `MemoryStore`). It keeps a map of the blocks that hold data ("care") and those known to be zeros |
| `src/sparse.ts` | `readSparse` streams a sparse image into a new `Disk`, a piece at a time, and can hash every byte on the way (`onBytes`). `writeSparse` streams a `Disk` back out: care blocks as RAW (split at 16 MiB) or FILL 0, the rest as DONT_CARE |
| `src/ext4.ts` | `Ext4`: open, read (`stat`, `readdir`, `readFile`, `label`), change (`mkdir`, `mkdirs`, `writeFile`, `replaceFile`, `remove`, `setLabel`), then `flush` |
| `src/mkfs.ts` | `mkfs`: an empty ext4 with lost+found and a journal, every metadata block written |
| `src/sha256.ts` | SHA-256 over a stream. WebCrypto hashes only a whole buffer |
| `src/layout.ts` | ext4's on-disk numbers (from the kernel's `fs/ext4/ext4.h` and e2fsprogs' `ext2_fs.h`), the descriptor checksum, the xattr hash |

```ts
import { Disk, Ext4, readSparse, syncHandleStore, writeSparse } from '@glassworks/ext4';

const { disk } = await readSparse(file.stream(), (h) => new Disk(syncHandleStore(handle), h.blockSize, h.blocks), { onBytes: (b) => sha.update(b) });
const fs = await Ext4.open(disk);
await fs.mkdir('/priv-app/S1Kiosk', { mode: 0o755, uid: 0, gid: 0, label: 'u:object_r:system_file:s0' });
await fs.writeFile('/priv-app/S1Kiosk/S1Kiosk.apk', apk, { mode: 0o644, uid: 0, gid: 0, label: 'u:object_r:system_file:s0' });
await fs.flush();
await writeSparse(disk, (piece) => out.write(piece));
```

## What it handles

It handles the ext4 that Android's `make_ext4fs` and e2fsprogs' `mke2fs` make without the newer
checksums:

- 4 KiB blocks, extents, filetype and sparse_super.
- large_file, huge_file and gdt_csum (uninit_bg), with uninitialised groups.
- flex_bg, dir_nlink and extra_isize.
- A journal and a resize inode.
- htree directories, which it changes as linear ones.

It refuses rather than half-handles anything else: 64bit, meta_bg, inline data, metadata_csum,
bigalloc, encryption, a journal that needs recovery, and block-mapped (non-extent) files. New files
get their xattrs inside the inode (256-byte inodes), as `make_ext4fs` writes them, or in an xattr
block when they don't fit (a label and `security.capability`, say). `replaceFile` keeps every xattr
the old file had. Times default to now: an inode freed with `dtime` 0 is an e2fsck error.

It doesn't journal. It edits an image that isn't mounted, as `make_ext4fs` and `e2fsdroid` do.
It writes the primary superblock and descriptors. The backups keep their counts, as the kernel's do.

## How it is tested

`e2fsprogs` is the judge. The tests make images with `mke2fs`: one in the `make_ext4fs` style (no
flex_bg, uninit_bg, a resize inode) and one as `mke2fs`'s own ext4. They change those images, then
require `e2fsck -fn` to find them clean: exit 0, and no "checksum is … IGNORED" (which `-n` lets pass). `debugfs` must read back the same bytes, owner, mode and
SELinux label.

Other cases:
- A file split over free space through an extent leaf block.
- A fresh `mkfs` with its labels and an adb key file.
- A sparse round trip onto a "device" full of junk, which `e2fsck` must find clean.
- The sparse format on its own: pieces of any size, FILL with a pattern, CRC32 chunks, a file cut short.

Without `e2fsprogs` the e2fsck tests skip, except in CI, where `GW_EXT4_TOOLS=require` makes them
run (`.github/workflows/pr.yml`, the `web` job). On a Mac: `brew install e2fsprogs`.

```sh
npm test -w @glassworks/ext4
```
