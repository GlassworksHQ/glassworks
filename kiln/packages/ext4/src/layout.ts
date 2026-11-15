// ext4's on-disk numbers, from the kernel's fs/ext4/ext4.h and e2fsprogs' lib/ext2fs/ext2_fs.h.

export const SB_OFFSET = 1024;
export const EXT4_MAGIC = 0xef53;
export const ROOT_INO = 2;
export const JOURNAL_INO = 8;
export const LOST_FOUND_INO = 11;

export const Compat = { HasJournal: 0x4, ExtAttr: 0x8, ResizeInode: 0x10, DirIndex: 0x20 } as const;
export const Incompat = { FileType: 0x2, Recover: 0x4, JournalDev: 0x8, MetaBg: 0x10, Extents: 0x40, Bit64: 0x80, Mmp: 0x100, FlexBg: 0x200, EaInode: 0x400, InlineData: 0x8000, Encrypt: 0x10000 } as const;
export const RoCompat = { SparseSuper: 0x1, LargeFile: 0x2, HugeFile: 0x8, GdtCsum: 0x10, DirNlink: 0x20, ExtraIsize: 0x40, Quota: 0x100, Bigalloc: 0x200, MetadataCsum: 0x400 } as const;

/** What this package reads and writes. Anything else is refused rather than half-handled. */
export const SUPPORTED_INCOMPAT = Incompat.FileType | Incompat.Extents | Incompat.FlexBg;
export const SUPPORTED_RO = RoCompat.SparseSuper | RoCompat.LargeFile | RoCompat.HugeFile | RoCompat.GdtCsum | RoCompat.DirNlink | RoCompat.ExtraIsize;

export const BgFlag = { InodeUninit: 0x1, BlockUninit: 0x2, ItableZeroed: 0x4 } as const;

export const S_IFMT = 0o170000, S_IFDIR = 0o040000, S_IFREG = 0o100000, S_IFLNK = 0o120000;
export const InodeFlag = { HugeFile: 0x40000, Extents: 0x80000, Index: 0x1000, InlineData: 0x10000000 } as const;
export const FileType = { Unknown: 0, Reg: 1, Dir: 2, Chr: 3, Blk: 4, Fifo: 5, Sock: 6, Symlink: 7 } as const;

export const EXTENT_MAGIC = 0xf30a;
export const EXTENT_MAX_LEN = 32768;
export const XATTR_MAGIC = 0xea020000;
export const XattrIndex = { User: 1, Trusted: 4, Security: 6, System: 7 } as const;

/** A group holds a superblock backup: group 0, 1, and powers of 3, 5 and 7, with sparse_super. */
export function hasSuperBackup(g: number, sparseSuper: boolean): boolean {
  if (!sparseSuper || g <= 1) return true;
  for (const p of [3, 5, 7]) { let x = p; while (x < g) x *= p; if (x === g) return true; }
  return false;
}

/** e2fsprogs' crc16 (CRC-16/ARC, reflected 0x8005), for the group descriptor checksum (gdt_csum). */
const CRC16 = (() => {
  const t = new Uint16Array(256);
  for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xa001 : c >>> 1; t[i] = c; }
  return t;
})();
export function crc16(crc: number, b: Uint8Array): number {
  for (const x of b) crc = (crc >>> 8) ^ CRC16[(crc ^ x) & 0xff]!;
  return crc & 0xffff;
}

/** The hash e2fsck checks on an xattr entry (ext2fs_ext_attr_hash_entry). */
export function xattrHash(name: Uint8Array, value: Uint8Array): number {
  let h = 0;
  for (const c of name) h = ((h << 5) ^ (h >>> 27) ^ c) >>> 0;
  const padded = new Uint8Array((value.length + 3) & ~3);
  padded.set(value);
  const dv = new DataView(padded.buffer);
  for (let i = 0; i < padded.length; i += 4) h = ((h << 16) ^ (h >>> 16) ^ dv.getUint32(i, true)) >>> 0;
  return h;
}

export const XATTR_PREFIXES: [number, string][] = [[XattrIndex.User, 'user.'], [XattrIndex.Trusted, 'trusted.'], [XattrIndex.Security, 'security.'], [XattrIndex.System, 'system.']];
