// An ext4 filesystem on a Disk: read it, and change it the way an image patcher needs to. Add and
// remove files and directories with their owner, mode and SELinux label, replace a file's contents.
// It writes metadata the way e2fsck expects: bitmaps, free counts, link counts, the group descriptor
// checksum (gdt_csum), and extents. It doesn't journal: it edits an image that isn't mounted.
//
// Supported: what make_ext4fs (Android's image tool) and mke2fs make without the newer checksums:
// 4 KiB (or other) blocks, extents, filetype, sparse_super, large_file, huge_file, gdt_csum,
// flex_bg, dir_nlink, extra_isize, a journal, a resize inode. Refused: 64bit, meta_bg, inline data,
// metadata_csum, bigalloc, encryption, and a journal that needs recovery.
import type { Disk } from './disk.ts';
import {
  BgFlag, Compat, EXTENT_MAGIC, EXTENT_MAX_LEN, EXT4_MAGIC, FileType, InodeFlag, Incompat, ROOT_INO, RoCompat, S_IFDIR, S_IFLNK,
  S_IFMT, S_IFREG, SB_OFFSET, SUPPORTED_INCOMPAT, SUPPORTED_RO, XATTR_MAGIC, XATTR_PREFIXES, XattrIndex, crc16, hasSuperBackup, xattrHash,
} from './layout.ts';

/** Owner, mode and label for something new. `mode` is the permission bits (e.g. 0o644). */
/** `xattrs`: more than the label (e.g. security.capability), kept in the inode. `time`: seconds since 1970, default now. */
export interface Attrs { mode: number; uid: number; gid: number; label?: string; time?: number; xattrs?: Xattr[] }

const now = () => Math.floor(Date.now() / 1000);

export interface Stat { ino: number; mode: number; uid: number; gid: number; size: number; links: number; isDir: boolean; isFile: boolean; isSymlink: boolean }
export interface DirEntry { name: string; ino: number; type: number }
export interface Xattr { index: number; name: string; value: Uint8Array }

/** `unwritten`: allocated but never written (reads as zeros); freed like any other. */
type Run = { logical: number; start: number; count: number; unwritten?: boolean };

const enc = new TextEncoder();
const dec = new TextDecoder();

class Group {
  constructor(readonly raw: Uint8Array) {}
  private get dv() { return new DataView(this.raw.buffer, this.raw.byteOffset, this.raw.byteLength); }
  get blockBitmap() { return this.dv.getUint32(0, true); }
  get inodeBitmap() { return this.dv.getUint32(4, true); }
  get inodeTable() { return this.dv.getUint32(8, true); }
  get freeBlocks() { return this.dv.getUint16(12, true); }
  set freeBlocks(v) { this.dv.setUint16(12, v, true); }
  get freeInodes() { return this.dv.getUint16(14, true); }
  set freeInodes(v) { this.dv.setUint16(14, v, true); }
  get usedDirs() { return this.dv.getUint16(16, true); }
  set usedDirs(v) { this.dv.setUint16(16, v, true); }
  get flags() { return this.dv.getUint16(18, true); }
  set flags(v) { this.dv.setUint16(18, v, true); }
  get itableUnused() { return this.dv.getUint16(28, true); }
  set itableUnused(v) { this.dv.setUint16(28, v, true); }
  set checksum(v: number) { this.dv.setUint16(30, v, true); }
}

/** One inode's bytes, with typed access to the fields used here. */
export class Inode {
  constructor(readonly ino: number, readonly raw: Uint8Array) {}
  get dv() { return new DataView(this.raw.buffer, this.raw.byteOffset, this.raw.byteLength); }
  get mode() { return this.dv.getUint16(0, true); }
  set mode(v) { this.dv.setUint16(0, v, true); }
  get uid() { return this.dv.getUint16(2, true) | (this.dv.getUint16(120, true) << 16); }
  set uid(v) { this.dv.setUint16(2, v & 0xffff, true); this.dv.setUint16(120, v >>> 16, true); }
  get gid() { return this.dv.getUint16(24, true) | (this.dv.getUint16(122, true) << 16); }
  set gid(v) { this.dv.setUint16(24, v & 0xffff, true); this.dv.setUint16(122, v >>> 16, true); }
  get size() { return this.dv.getUint32(4, true) + this.dv.getUint32(108, true) * 0x100000000; }
  set size(v) { this.dv.setUint32(4, v >>> 0, true); this.dv.setUint32(108, Math.floor(v / 0x100000000), true); }
  get links() { return this.dv.getUint16(26, true); }
  set links(v) { this.dv.setUint16(26, v, true); }
  get flags() { return this.dv.getUint32(32, true); }
  set flags(v) { this.dv.setUint32(32, v, true); }
  get fileAcl() { return this.dv.getUint32(104, true) + this.dv.getUint16(118, true) * 0x100000000; }
  set fileAcl(v) { this.dv.setUint32(104, v >>> 0, true); this.dv.setUint16(118, Math.floor(v / 0x100000000), true); }
  /** i_blocks in 512-byte sectors (huge_file inodes count fs blocks; not made here). */
  set sectors(v: number) { this.dv.setUint32(28, v >>> 0, true); this.dv.setUint16(116, Math.floor(v / 0x100000000), true); }
  setTimes(t: number) { for (const o of [8, 12, 16]) this.dv.setUint32(o, t, true); }
  set dtime(t: number) { this.dv.setUint32(20, t, true); }
  get extraIsize() { return this.raw.length > 128 ? this.dv.getUint16(128, true) : 0; }
  get iblock() { return this.raw.subarray(40, 100); }
  get isDir() { return (this.mode & S_IFMT) === S_IFDIR; }
}

export class Ext4 {
  readonly blockSize: number;
  readonly blocksPerGroup: number;
  readonly inodesPerGroup: number;
  readonly inodeSize: number;
  readonly groups: Group[] = [];
  private readonly gdtBlocks: number;
  private readonly descSize: number;
  private readonly dirty = new Set<number>();
  private readonly bitmaps = new Map<number, Uint8Array>();
  private readonly sbv: DataView;

  private constructor(readonly disk: Disk, readonly sb: Uint8Array, gdt: Uint8Array) {
    this.sbv = new DataView(sb.buffer, sb.byteOffset, sb.byteLength);
    this.blockSize = 1024 << this.sbv.getUint32(24, true);
    this.blocksPerGroup = this.sbv.getUint32(32, true);
    this.inodesPerGroup = this.sbv.getUint32(40, true);
    this.inodeSize = this.sbv.getUint32(76, true) >= 1 ? this.sbv.getUint16(88, true) : 128;
    this.descSize = this.incompat & Incompat.Bit64 ? this.sbv.getUint16(254, true) : 32;
    const n = this.groupCount;
    this.gdtBlocks = Math.ceil((n * this.descSize) / this.blockSize);
    for (let g = 0; g < n; g++) this.groups.push(new Group(gdt.subarray(g * this.descSize, (g + 1) * this.descSize)));
    this.gdt = gdt;
  }
  private readonly gdt: Uint8Array;

  /** Opens the ext4 on `disk`. Throws for a filesystem this package can't change safely. */
  static async open(disk: Disk): Promise<Ext4> {
    const head = await disk.store.read(SB_OFFSET, 1024);
    const dv = new DataView(head.buffer);
    if (dv.getUint16(56, true) !== EXT4_MAGIC) throw new Error('no ext4 filesystem here (bad superblock magic)');
    const bs = 1024 << dv.getUint32(24, true);
    if (bs !== disk.blockSize) throw new Error(`the filesystem's ${bs}-byte blocks don't match the image's ${disk.blockSize}`);
    const incompat = dv.getUint32(96, true), ro = dv.getUint32(100, true);
    const bad = (incompat & ~SUPPORTED_INCOMPAT & ~Incompat.Recover) | (ro & ~SUPPORTED_RO);
    if (bad) throw new Error(`this ext4 uses features this package doesn't handle (incompat 0x${(incompat & ~SUPPORTED_INCOMPAT).toString(16)}, ro_compat 0x${(ro & ~SUPPORTED_RO).toString(16)})`);
    if (incompat & Incompat.Recover) throw new Error('this ext4\'s journal needs recovery (it wasn\'t unmounted cleanly)');
    // what the writers produce: extent inodes and directory entries with a file type
    if ((incompat & (Incompat.Extents | Incompat.FileType)) !== (Incompat.Extents | Incompat.FileType)) throw new Error('this ext4 has no extents or no filetype: this package writes both, and doesn\'t handle the older formats');
    const blocks = dv.getUint32(4, true);
    if (blocks > disk.blocks) throw new Error(`the filesystem (${blocks} blocks) is bigger than the image (${disk.blocks})`);
    const first = dv.getUint32(20, true), bpg = dv.getUint32(32, true);
    const groups = Math.ceil((blocks - first) / bpg);
    const descSize = incompat & Incompat.Bit64 ? dv.getUint16(254, true) : 32;
    const gdt = await disk.read(first + 1, Math.ceil((groups * descSize) / bs));
    return new Ext4(disk, head, gdt);
  }

  get incompat() { return this.sbv.getUint32(96, true); }
  get roCompat() { return this.sbv.getUint32(100, true); }
  get compat() { return this.sbv.getUint32(92, true); }
  get blocksCount() { return this.sbv.getUint32(4, true); }
  get inodesCount() { return this.sbv.getUint32(0, true); }
  get firstDataBlock() { return this.sbv.getUint32(20, true); }
  get groupCount() { return Math.ceil((this.blocksCount - this.firstDataBlock) / this.blocksPerGroup); }
  get freeBlocks() { return this.sbv.getUint32(12, true); }
  get freeInodes() { return this.sbv.getUint32(16, true); }
  get firstIno() { return this.sbv.getUint32(76, true) >= 1 ? this.sbv.getUint32(84, true) : 11; }
  private get gdtCsum() { return !!(this.roCompat & RoCompat.GdtCsum); }
  private get reservedGdt() { return this.compat & Compat.ResizeInode ? this.sbv.getUint16(206, true) : 0; }

  // ---- groups, bitmaps --------------------------------------------------------------------------

  private groupStart(g: number) { return this.firstDataBlock + g * this.blocksPerGroup; }
  private groupBlocks(g: number) { return Math.min(this.blocksPerGroup, this.blocksCount - this.groupStart(g)); }

  private touch(g: number) {
    if (this.gdtCsum) {
      const raw = this.groups[g]!.raw;
      let c = crc16(0xffff, this.sb.subarray(104, 120));
      const gb = new Uint8Array(4); new DataView(gb.buffer).setUint32(0, g, true);
      c = crc16(c, gb);
      c = crc16(c, raw.subarray(0, 30));
      if (raw.length > 32) c = crc16(c, raw.subarray(32));
      this.groups[g]!.checksum = c;
    }
    this.dirty.add(g);
  }

  /** Blocks of group `g` that are metadata (superblock backup, descriptors, bitmaps, inode tables). */
  private metadataIn(g: number): number[] {
    const start = this.groupStart(g), end = start + this.groupBlocks(g);
    const out: number[] = [];
    if (hasSuperBackup(g, !!(this.roCompat & RoCompat.SparseSuper))) {
      for (let b = 0; b < 1 + this.gdtBlocks + this.reservedGdt; b++) out.push(start + b);
    }
    const itBlocks = Math.ceil((this.inodesPerGroup * this.inodeSize) / this.blockSize);
    for (const gd of this.groups) {
      for (const b of [gd.blockBitmap, gd.inodeBitmap]) if (b >= start && b < end) out.push(b);
      for (let b = gd.inodeTable; b < gd.inodeTable + itBlocks; b++) if (b >= start && b < end) out.push(b);
    }
    return out;
  }

  private async blockBitmap(g: number): Promise<Uint8Array> {
    const key = g * 2;
    let bm = this.bitmaps.get(key);
    if (bm) return bm;
    const gd = this.groups[g]!;
    if (gd.flags & BgFlag.BlockUninit) {
      bm = new Uint8Array(this.blockSize);
      const start = this.groupStart(g);
      for (const b of this.metadataIn(g)) bm[(b - start) >> 3]! |= 1 << ((b - start) & 7);
      for (let i = this.groupBlocks(g); i < this.blockSize * 8; i++) bm[i >> 3]! |= 1 << (i & 7);
    } else {
      bm = await this.disk.read(gd.blockBitmap);
    }
    this.bitmaps.set(key, bm);
    return bm;
  }

  private async inodeBitmap(g: number): Promise<Uint8Array> {
    const key = g * 2 + 1;
    let bm = this.bitmaps.get(key);
    if (bm) return bm;
    const gd = this.groups[g]!;
    if (gd.flags & BgFlag.InodeUninit) {
      bm = new Uint8Array(this.blockSize);
      for (let i = this.inodesPerGroup; i < this.blockSize * 8; i++) bm[i >> 3]! |= 1 << (i & 7);
    } else {
      bm = await this.disk.read(gd.inodeBitmap);
    }
    this.bitmaps.set(key, bm);
    return bm;
  }

  /** Writes back everything changed: bitmaps, descriptors, the superblock. Call when done. */
  async flush(): Promise<void> {
    for (const [key, bm] of this.bitmaps) {
      const g = key >> 1, gd = this.groups[g]!;
      if (!this.dirty.has(g)) continue;
      if (key & 1) { gd.flags &= ~BgFlag.InodeUninit; await this.disk.write(gd.inodeBitmap, bm); }
      else { gd.flags &= ~BgFlag.BlockUninit; await this.disk.write(gd.blockBitmap, bm); }
      this.touch(g);
    }
    // the primary descriptors and superblock; the backups keep their counts, as the kernel's do
    await this.disk.write(this.firstDataBlock + 1, this.gdt);
    if (this.blockSize === 1024) await this.disk.write(1, this.sb);
    else {
      const b0 = await this.disk.read(0);
      b0.set(this.sb, SB_OFFSET);
      await this.disk.write(0, b0);
    }
    this.dirty.clear();
  }

  private addFree(blocks: number, inodes: number) {
    this.sbv.setUint32(12, this.sbv.getUint32(12, true) + blocks, true);
    this.sbv.setUint32(16, this.sbv.getUint32(16, true) + inodes, true);
  }

  // ---- inodes -----------------------------------------------------------------------------------

  private inodeLoc(ino: number): { block: number; offset: number } {
    if (ino < 1 || ino > this.inodesCount) throw new Error(`inode ${ino} is out of range`);
    const g = Math.floor((ino - 1) / this.inodesPerGroup), i = (ino - 1) % this.inodesPerGroup;
    const byte = i * this.inodeSize;
    return { block: this.groups[g]!.inodeTable + Math.floor(byte / this.blockSize), offset: byte % this.blockSize };
  }

  async inode(ino: number): Promise<Inode> {
    const { block, offset } = this.inodeLoc(ino);
    const b = await this.disk.read(block);
    return new Inode(ino, b.slice(offset, offset + this.inodeSize));
  }

  async putInode(n: Inode): Promise<void> {
    const { block, offset } = this.inodeLoc(n.ino);
    const b = await this.disk.read(block);
    b.set(n.raw, offset);
    await this.disk.write(block, b);
  }

  private async allocInode(near: number, dir: boolean): Promise<number> {
    const start = Math.floor((near - 1) / this.inodesPerGroup);
    for (let k = 0; k < this.groupCount; k++) {
      const g = (start + k) % this.groupCount, gd = this.groups[g]!;
      if (!gd.freeInodes) continue;
      const bm = await this.inodeBitmap(g);
      for (let i = 0; i < this.inodesPerGroup; i++) {
        const ino = g * this.inodesPerGroup + i + 1;
        if (ino < this.firstIno || bm[i >> 3]! & (1 << (i & 7))) continue;
        bm[i >> 3]! |= 1 << (i & 7);
        gd.freeInodes--;
        if (dir) gd.usedDirs++;
        if (this.gdtCsum && i >= this.inodesPerGroup - gd.itableUnused) gd.itableUnused = this.inodesPerGroup - i - 1;
        if (gd.flags & BgFlag.InodeUninit) {
          // this group's table was never written: zero all of it once, now, and mark it so, or the
          // next allocation here would zero the inode just written
          await this.disk.zeroFill(gd.inodeTable, Math.ceil((this.inodesPerGroup * this.inodeSize) / this.blockSize));
          gd.flags = (gd.flags & ~BgFlag.InodeUninit) | BgFlag.ItableZeroed;
        }
        this.addFree(0, -1);
        this.touch(g);
        return ino;
      }
    }
    throw new Error('the filesystem has no free inodes');
  }

  private async freeInode(n: Inode, time: number): Promise<void> {
    const g = Math.floor((n.ino - 1) / this.inodesPerGroup), i = (n.ino - 1) % this.inodesPerGroup;
    const bm = await this.inodeBitmap(g);
    bm[i >> 3]! &= ~(1 << (i & 7));
    const gd = this.groups[g]!;
    gd.freeInodes++;
    if (n.isDir) gd.usedDirs--;
    n.links = 0;
    n.dtime = time;
    await this.putInode(n);
    this.addFree(0, 1);
    this.touch(g);
  }

  // ---- blocks -----------------------------------------------------------------------------------

  /** @internal Allocates `count` blocks, as few runs as it can, starting the search near `goal`. */
  async allocBlocks(count: number, goal: number): Promise<{ start: number; count: number }[]> {
    if (count > this.freeBlocks) throw new Error(`the filesystem has ${this.freeBlocks} free blocks, ${count} needed`);
    const out: { start: number; count: number }[] = [];
    let left = count;
    const gStart = Math.max(0, Math.min(this.groupCount - 1, Math.floor((goal - this.firstDataBlock) / this.blocksPerGroup)));
    // first pass: a group with one free run long enough; second: take runs wherever they are
    for (const whole of [true, false]) {
      for (let k = 0; k < this.groupCount && left; k++) {
        const g = (gStart + k) % this.groupCount, gd = this.groups[g]!;
        if (!gd.freeBlocks || (whole && gd.freeBlocks < Math.min(left, this.groupBlocks(g)))) continue;
        const bm = await this.blockBitmap(g);
        const n = this.groupBlocks(g);
        for (let i = 0; i < n && left;) {
          if (bm[i >> 3]! & (1 << (i & 7))) { i++; continue; }
          let j = i;
          while (j < n && j - i < left && !(bm[j >> 3]! & (1 << (j & 7)))) j++;
          if (whole && j - i < Math.min(left, n)) { i = j; continue; }
          for (let x = i; x < j; x++) bm[x >> 3]! |= 1 << (x & 7);
          gd.freeBlocks -= j - i;
          this.addFree(-(j - i), 0);
          this.touch(g);
          out.push({ start: this.groupStart(g) + i, count: j - i });
          left -= j - i;
          i = j;
        }
      }
      if (!left) break;
    }
    if (left) throw new Error('the filesystem ran out of free blocks');
    return out;
  }

  private async freeBlocks_(start: number, count: number): Promise<void> {
    for (let b = start; b < start + count;) {
      const g = Math.floor((b - this.firstDataBlock) / this.blocksPerGroup);
      const gs = this.groupStart(g), n = Math.min(start + count, gs + this.groupBlocks(g)) - b;
      const bm = await this.blockBitmap(g);
      for (let x = b - gs; x < b - gs + n; x++) {
        if (!(bm[x >> 3]! & (1 << (x & 7)))) throw new Error(`block ${gs + x} is freed twice`);
        bm[x >> 3]! &= ~(1 << (x & 7));
      }
      this.groups[g]!.freeBlocks += n;
      this.addFree(n, 0);
      this.touch(g);
      b += n;
    }
  }

  // ---- extents ----------------------------------------------------------------------------------

  /** The inode's mapping: logical runs to physical blocks, and the tree's own blocks. */
  async extents(n: Inode): Promise<{ runs: Run[]; tree: number[] }> {
    const runs: Run[] = [], tree: number[] = [];
    if (!(n.flags & InodeFlag.Extents)) {
      if ((n.mode & S_IFMT) === S_IFLNK && n.size < 60) return { runs, tree }; // a fast symlink keeps its target in i_block
      if (n.dv.getUint32(40, true) === 0 && n.size === 0) return { runs, tree };
      throw new Error(`inode ${n.ino} uses block maps, not extents: not supported`);
    }
    const walk = async (node: Uint8Array) => {
      const dv = new DataView(node.buffer, node.byteOffset, node.byteLength);
      if (dv.getUint16(0, true) !== EXTENT_MAGIC) throw new Error(`inode ${n.ino} has a broken extent tree`);
      const entries = dv.getUint16(2, true), depth = dv.getUint16(6, true);
      for (let e = 0; e < entries; e++) {
        const o = 12 + e * 12;
        if (depth === 0) {
          let len = dv.getUint16(o + 4, true);
          const unwritten = len > EXTENT_MAX_LEN;
          if (unwritten) len -= EXTENT_MAX_LEN; // still allocated, but reads as zeros
          runs.push({ logical: dv.getUint32(o, true), start: dv.getUint16(o + 6, true) * 0x100000000 + dv.getUint32(o + 8, true), count: len, ...(unwritten ? { unwritten } : {}) });
        } else {
          const leaf = dv.getUint32(o + 4, true) + dv.getUint16(o + 8, true) * 0x100000000;
          tree.push(leaf);
          await walk(await this.disk.read(leaf));
        }
      }
    };
    await walk(n.iblock);
    return { runs, tree };
  }

  /** @internal Points the inode at `runs` (sorted, logical from 0), with a leaf block when they don't fit in it. Returns the tree's block count. */
  async setExtents(n: Inode, runs: Run[], goal: number): Promise<number> {
    const split: Run[] = [];
    for (const r of runs) {
      for (let o = 0; o < r.count; o += EXTENT_MAX_LEN) split.push({ logical: r.logical + o, start: r.start + o, count: Math.min(EXTENT_MAX_LEN, r.count - o) });
    }
    const leafMax = (this.blockSize - 12) / 12;
    const ib = n.iblock;
    ib.fill(0);
    const hv = new DataView(ib.buffer, ib.byteOffset, 60);
    const put = (dv: DataView, o: number, r: Run) => {
      dv.setUint32(o, r.logical, true); dv.setUint16(o + 4, r.count, true);
      dv.setUint16(o + 6, Math.floor(r.start / 0x100000000), true); dv.setUint32(o + 8, r.start >>> 0, true);
    };
    hv.setUint16(0, EXTENT_MAGIC, true); hv.setUint16(4, 4, true);
    n.flags = (n.flags | InodeFlag.Extents) & ~InodeFlag.InlineData;
    if (split.length <= 4) {
      hv.setUint16(2, split.length, true); hv.setUint16(6, 0, true);
      split.forEach((r, i) => put(hv, 12 + i * 12, r));
      return 0;
    }
    if (split.length > leafMax) throw new Error(`a file in ${split.length} pieces needs a deeper extent tree than this package writes`);
    const [leafRun] = await this.allocBlocks(1, goal);
    const leaf = new Uint8Array(this.blockSize);
    const lv = new DataView(leaf.buffer);
    lv.setUint16(0, EXTENT_MAGIC, true); lv.setUint16(2, split.length, true); lv.setUint16(4, leafMax, true); lv.setUint16(6, 0, true);
    split.forEach((r, i) => put(lv, 12 + i * 12, r));
    await this.disk.write(leafRun!.start, leaf);
    hv.setUint16(2, 1, true); hv.setUint16(6, 1, true);
    hv.setUint32(12, 0, true); hv.setUint32(16, leafRun!.start >>> 0, true); hv.setUint16(20, Math.floor(leafRun!.start / 0x100000000), true);
    return 1;
  }

  // ---- reading ----------------------------------------------------------------------------------

  async readInodeData(n: Inode): Promise<Uint8Array> {
    if ((n.mode & S_IFMT) === S_IFLNK && !(n.flags & InodeFlag.Extents) && n.size < 60) return n.iblock.slice(0, n.size);
    const out = new Uint8Array(n.size);
    const { runs } = await this.extents(n);
    for (const r of runs) {
      const at = r.logical * this.blockSize;
      if (at >= out.length || r.unwritten) continue; // an unwritten extent reads as zeros, whatever its blocks hold
      for (let o = 0; o < r.count; o += 256) {
        const c = Math.min(256, r.count - o);
        const data = await this.disk.read(r.start + o, c);
        const pos = at + o * this.blockSize;
        if (pos >= out.length) break;
        out.set(data.subarray(0, Math.min(data.length, out.length - pos)), pos);
      }
    }
    return out;
  }

  private async dirBlocks(dir: Inode): Promise<{ block: number; data: Uint8Array }[]> {
    const out: { block: number; data: Uint8Array }[] = [];
    for (const r of (await this.extents(dir)).runs) {
      for (let i = 0; i < r.count; i++) out.push({ block: r.start + i, data: await this.disk.read(r.start + i) });
    }
    return out;
  }

  private static *entries(data: Uint8Array): Generator<{ off: number; ino: number; rec: number; name: string; type: number; prev: number }> {
    const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
    let prev = -1;
    for (let off = 0; off < data.length;) {
      const rec = dv.getUint16(off + 4, true);
      if (rec < 8 || off + rec > data.length) throw new Error('a directory block is corrupt');
      const len = data[off + 6]!;
      yield { off, ino: dv.getUint32(off, true), rec, name: dec.decode(data.subarray(off + 8, off + 8 + len)), type: data[off + 7]!, prev };
      prev = off;
      off += rec;
    }
  }

  async readdir(path: string): Promise<DirEntry[]> {
    const dir = await this.inode(await this.resolve(path));
    if (!dir.isDir) throw new Error(`${path} is not a directory`);
    const out: DirEntry[] = [];
    for (const { data } of await this.dirBlocks(dir)) {
      for (const e of Ext4.entries(data)) if (e.ino) out.push({ name: e.name, ino: e.ino, type: e.type });
    }
    return out;
  }

  private async lookup(dirIno: number, name: string): Promise<number | undefined> {
    const dir = await this.inode(dirIno);
    if (!dir.isDir) throw new Error(`inode ${dirIno} is not a directory`);
    for (const { data } of await this.dirBlocks(dir)) {
      for (const e of Ext4.entries(data)) if (e.ino && e.name === name) return e.ino;
    }
    return undefined;
  }

  private static parts(path: string): string[] {
    const p = path.split('/').filter(Boolean);
    if (p.some((x) => x === '.' || x === '..')) throw new Error(`use a plain path, not ${path}`);
    return p;
  }

  /** The inode at `path` ("/" is the filesystem's root). Throws if it doesn't exist. */
  async resolve(path: string): Promise<number> {
    let ino = ROOT_INO;
    for (const name of Ext4.parts(path)) {
      const next = await this.lookup(ino, name);
      if (next === undefined) throw new Error(`${path} doesn't exist`);
      ino = next;
    }
    return ino;
  }

  async exists(path: string): Promise<boolean> {
    try { await this.resolve(path); return true; } catch { return false; }
  }

  async stat(path: string): Promise<Stat> {
    const n = await this.inode(await this.resolve(path));
    const t = n.mode & S_IFMT;
    return { ino: n.ino, mode: n.mode & 0o7777, uid: n.uid, gid: n.gid, size: n.size, links: n.links, isDir: t === S_IFDIR, isFile: t === S_IFREG, isSymlink: t === S_IFLNK };
  }

  async readFile(path: string): Promise<Uint8Array> {
    const n = await this.inode(await this.resolve(path));
    if (n.isDir) throw new Error(`${path} is a directory`);
    return this.readInodeData(n);
  }

  // ---- xattrs -----------------------------------------------------------------------------------

  private ibodyRegion(n: Inode): { start: number; end: number } | undefined {
    if (this.inodeSize <= 128) return undefined;
    const start = 128 + n.extraIsize;
    return start + 4 <= this.inodeSize ? { start, end: this.inodeSize } : undefined;
  }

  private static parseXattrs(buf: Uint8Array, first: number, valueBase: number, limit: number): Xattr[] {
    const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const out: Xattr[] = [];
    for (let o = first; o + 4 <= limit && dv.getUint32(o, true) !== 0;) {
      const nameLen = buf[o]!, index = buf[o + 1]!, voff = dv.getUint16(o + 2, true), vsize = dv.getUint32(o + 8, true);
      out.push({ index, name: dec.decode(buf.subarray(o + 16, o + 16 + nameLen)), value: buf.slice(valueBase + voff, valueBase + voff + vsize) });
      o += (16 + nameLen + 3) & ~3;
    }
    return out;
  }

  async xattrs(n: Inode): Promise<Xattr[]> {
    const out: Xattr[] = [];
    const r = this.ibodyRegion(n);
    if (r && new DataView(n.raw.buffer, n.raw.byteOffset).getUint32(r.start, true) === XATTR_MAGIC) {
      out.push(...Ext4.parseXattrs(n.raw, r.start + 4, r.start + 4, r.end));
    }
    if (n.fileAcl) {
      const b = await this.disk.read(n.fileAcl);
      if (new DataView(b.buffer).getUint32(0, true) === XATTR_MAGIC) out.push(...Ext4.parseXattrs(b, 32, 0, b.length));
    }
    return out;
  }

  /** e.g. "security.selinux" → the value, or undefined. */
  async getXattr(path: string, fullName: string): Promise<Uint8Array | undefined> {
    const n = await this.inode(await this.resolve(path));
    const x = (await this.xattrs(n)).find((a) => fullNameOf(a) === fullName);
    return x?.value;
  }

  /** The SELinux label without its trailing NUL, e.g. u:object_r:system_file:s0. */
  async label(path: string): Promise<string | undefined> {
    const v = await this.getXattr(path, 'security.selinux');
    return v ? dec.decode(v).replace(/\0+$/, '') : undefined;
  }

  /** An image that gets xattrs says so (COMPAT_EXT_ATTR). */
  private declareXattrs() {
    if (!(this.compat & Compat.ExtAttr)) this.sbv.setUint32(92, this.compat | Compat.ExtAttr, true);
  }

  /** @internal Rewrites the inode's in-inode xattrs as `list`. Throws if they don't fit (no xattr blocks are written). */
  writeIbodyXattrs(n: Inode, list: Xattr[]) {
    const r = this.ibodyRegion(n);
    if (!r) { if (list.length) throw new Error('these inodes have no room for xattrs'); return; }
    const area = n.raw.subarray(r.start, r.end);
    area.fill(0);
    if (!list.length) return;
    this.declareXattrs();
    const dv = new DataView(area.buffer, area.byteOffset, area.byteLength);
    dv.setUint32(0, XATTR_MAGIC, true);
    let eo = 4, vend = area.length;
    for (const x of list) {
      const name = enc.encode(x.name);
      const esize = (16 + name.length + 3) & ~3, vsize = (x.value.length + 3) & ~3;
      vend -= vsize;
      if (eo + esize + 4 > vend) throw new Error('the xattrs don\'t fit in the inode');
      area[eo] = name.length; area[eo + 1] = x.index;
      dv.setUint16(eo + 2, vend - 4, true); dv.setUint32(eo + 4, 0, true); dv.setUint32(eo + 8, x.value.length, true);
      dv.setUint32(eo + 12, xattrHash(name, x.value), true);
      area.set(name, eo + 16);
      area.set(x.value, vend);
      eo += esize;
    }
  }

  /**
   * @internal The inode's xattrs in a block of their own (EXT4_XATTR_MAGIC, refcount 1): entries
   * sorted as the kernel searches them (index, name length, name), values from the end, with the entry
   * and block hashes e2fsck checks. Adds the block to i_file_acl and i_blocks.
   */
  async writeXattrBlock(n: Inode, list: Xattr[]): Promise<void> {
    this.declareXattrs();
    const sorted = [...list].sort((a, b) => a.index - b.index || a.name.length - b.name.length || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    const b = new Uint8Array(this.blockSize);
    const dv = new DataView(b.buffer);
    dv.setUint32(0, XATTR_MAGIC, true); dv.setUint32(4, 1, true); dv.setUint32(8, 1, true);
    let eo = 32, vend = b.length, blockHash = 0;
    for (const x of sorted) {
      const name = enc.encode(x.name);
      const esize = (16 + name.length + 3) & ~3, vsize = (x.value.length + 3) & ~3;
      vend -= vsize;
      if (eo + esize + 4 > vend) throw new Error('the xattrs don\'t fit in a block');
      const h = xattrHash(name, x.value);
      b[eo] = name.length; b[eo + 1] = x.index;
      dv.setUint16(eo + 2, vend, true); dv.setUint32(eo + 4, 0, true); dv.setUint32(eo + 8, x.value.length, true); dv.setUint32(eo + 12, h, true);
      b.set(name, eo + 16);
      b.set(x.value, vend);
      blockHash = ((blockHash << 16) ^ (blockHash >>> 16) ^ h) >>> 0;
      eo += esize;
    }
    dv.setUint32(12, blockHash, true);
    const [r] = await this.allocBlocks(1, this.groups[Math.floor((n.ino - 1) / this.inodesPerGroup)]!.inodeTable);
    await this.disk.write(r!.start, b);
    n.fileAcl = r!.start;
  }

  /** Sets the SELinux label of an existing file (in the inode). */
  async setLabel(path: string, label: string): Promise<void> {
    const n = await this.inode(await this.resolve(path));
    const list = (await this.xattrs(n)).filter((x) => !(x.index === XattrIndex.Security && x.name === 'selinux'));
    if (n.fileAcl) throw new Error(`${path} keeps its xattrs in a block: not supported`);
    this.writeIbodyXattrs(n, [...list, selinux(label)]);
    await this.putInode(n);
  }

  // ---- changing ---------------------------------------------------------------------------------

  private async addEntry(dirIno: number, name: string, ino: number, type: number): Promise<void> {
    const nb = enc.encode(name);
    if (!nb.length || nb.length > 255 || name.includes('/')) throw new Error(`bad file name ${name}`);
    const need = (8 + nb.length + 3) & ~3;
    const dir = await this.inode(dirIno);
    if (dir.flags & InodeFlag.Index) { dir.flags &= ~InodeFlag.Index; await this.putInode(dir); } // an htree is also a valid linear directory
    const write = (data: Uint8Array, off: number, rec: number) => {
      const dv = new DataView(data.buffer, data.byteOffset);
      dv.setUint32(off, ino, true); dv.setUint16(off + 4, rec, true); data[off + 6] = nb.length; data[off + 7] = type;
      data.set(nb, off + 8);
    };
    for (const { block, data } of await this.dirBlocks(dir)) {
      for (const e of Ext4.entries(data)) {
        const used = e.ino ? (8 + enc.encode(e.name).length + 3) & ~3 : 0;
        if (e.rec - used < need) continue;
        if (e.ino) {
          new DataView(data.buffer, data.byteOffset).setUint16(e.off + 4, used, true);
          write(data, e.off + used, e.rec - used);
        } else {
          write(data, e.off, e.rec);
        }
        await this.disk.write(block, data);
        return;
      }
    }
    // no room: one more block for the directory
    const { runs } = await this.extents(dir);
    const last = runs.at(-1);
    const [nr] = await this.allocBlocks(1, last ? last.start + last.count : this.groups[0]!.inodeTable);
    const data = new Uint8Array(this.blockSize);
    write(data, 0, this.blockSize);
    await this.disk.write(nr!.start, data);
    const logical = Math.ceil(dir.size / this.blockSize);
    const merged = last && last.start + last.count === nr!.start && last.logical + last.count === logical
      ? [...runs.slice(0, -1), { ...last, count: last.count + 1 }] : [...runs, { logical, start: nr!.start, count: 1 }];
    const old = await this.extents(dir);
    for (const t of old.tree) await this.freeBlocks_(t, 1);
    const treeBlocks = await this.setExtents(dir, merged, nr!.start);
    dir.size = (logical + 1) * this.blockSize;
    dir.sectors = ((logical + 1) + treeBlocks + (dir.fileAcl ? 1 : 0)) * (this.blockSize / 512);
    await this.putInode(dir);
  }

  private async removeEntry(dirIno: number, name: string): Promise<void> {
    const dir = await this.inode(dirIno);
    for (const { block, data } of await this.dirBlocks(dir)) {
      for (const e of Ext4.entries(data)) {
        if (!e.ino || e.name !== name) continue;
        const dv = new DataView(data.buffer, data.byteOffset);
        if (e.prev >= 0) dv.setUint16(e.prev + 4, dv.getUint16(e.prev + 4, true) + e.rec, true);
        else dv.setUint32(e.off, 0, true);
        await this.disk.write(block, data);
        return;
      }
    }
    throw new Error(`${name} isn't in directory inode ${dirIno}`);
  }

  private async parentOf(path: string): Promise<{ dir: number; name: string }> {
    const p = Ext4.parts(path);
    const name = p.pop();
    if (!name) throw new Error('the root has no parent');
    return { dir: await this.resolve(`/${p.join('/')}`), name };
  }

  private async newInode(dir: number, attrs: Attrs, type: number): Promise<Inode> {
    const ino = await this.allocInode(dir, type === S_IFDIR);
    const n = new Inode(ino, new Uint8Array(this.inodeSize));
    n.mode = type | (attrs.mode & 0o7777);
    n.uid = attrs.uid; n.gid = attrs.gid;
    n.setTimes(attrs.time ?? now());
    const list = [...(attrs.xattrs ?? []).filter((x) => !isLabel(x)), ...(attrs.label ? [selinux(attrs.label)] : [])];
    if (this.inodeSize > 128) n.dv.setUint16(128, Math.min(this.inodeSize - 128, Math.max(this.sbv.getUint16(350, true), this.sbv.getUint16(348, true), 28)) & ~3, true);
    try {
      this.writeIbodyXattrs(n, list);
    } catch {
      // more than fits in the inode (a label and security.capability, say): all of them in an xattr block
      this.writeIbodyXattrs(n, []);
      await this.writeXattrBlock(n, list);
    }
    return n;
  }

  /** Creates a directory. Its parent must exist; it must not. */
  async mkdir(path: string, attrs: Attrs): Promise<number> {
    const { dir, name } = await this.parentOf(path);
    if (await this.lookup(dir, name) !== undefined) throw new Error(`${path} exists already`);
    this.needs(2, 1, path);
    const n = await this.newInode(dir, attrs, S_IFDIR);
    const [r] = await this.allocBlocks(1, this.groups[Math.floor((n.ino - 1) / this.inodesPerGroup)]!.inodeTable);
    const data = new Uint8Array(this.blockSize);
    const dv = new DataView(data.buffer);
    dv.setUint32(0, n.ino, true); dv.setUint16(4, 12, true); data[6] = 1; data[7] = FileType.Dir; data[8] = 0x2e;
    dv.setUint32(12, dir, true); dv.setUint16(16, this.blockSize - 12, true); data[18] = 2; data[19] = FileType.Dir; data[20] = 0x2e; data[21] = 0x2e;
    await this.disk.write(r!.start, data);
    await this.setExtents(n, [{ logical: 0, start: r!.start, count: 1 }], r!.start);
    n.size = this.blockSize; n.links = 2; n.sectors = (1 + (n.fileAcl ? 1 : 0)) * (this.blockSize / 512);
    await this.putInode(n);
    await this.addEntry(dir, name, n.ino, FileType.Dir);
    const parent = await this.inode(dir);
    parent.links++;
    await this.putInode(parent);
    return n.ino;
  }

  /** Creates the directories on `path` that don't exist yet, each with `attrs`. */
  async mkdirs(path: string, attrs: Attrs): Promise<void> {
    let at = '';
    for (const p of Ext4.parts(path)) {
      at += `/${p}`;
      if (!(await this.exists(at))) await this.mkdir(at, attrs);
    }
  }

  /** Creates a regular file with `data`. Its parent must exist; it must not. */
  async writeFile(path: string, data: Uint8Array, attrs: Attrs): Promise<number> {
    const { dir, name } = await this.parentOf(path);
    if (await this.lookup(dir, name) !== undefined) throw new Error(`${path} exists already`);
    this.needs(this.blocksFor(data.length), 1, path);
    const n = await this.newInode(dir, attrs, S_IFREG);
    const count = Math.ceil(data.length / this.blockSize);
    let tree = 0;
    if (count) {
      const runs = await this.allocBlocks(count, this.groups[Math.floor((n.ino - 1) / this.inodesPerGroup)]!.inodeTable);
      let logical = 0;
      const mapped: Run[] = [];
      for (const r of runs) {
        const chunk = new Uint8Array(r.count * this.blockSize);
        chunk.set(data.subarray(logical * this.blockSize, (logical + r.count) * this.blockSize));
        await this.disk.write(r.start, chunk);
        mapped.push({ logical, start: r.start, count: r.count });
        logical += r.count;
      }
      tree = await this.setExtents(n, mapped, runs[0]!.start);
    } else {
      await this.setExtents(n, [], 0);
    }
    n.size = data.length; n.links = 1; n.sectors = (count + tree + (n.fileAcl ? 1 : 0)) * (this.blockSize / 512);
    if (data.length >= 2 ** 31 && !(this.roCompat & RoCompat.LargeFile)) throw new Error('a file over 2 GiB needs large_file');
    await this.putInode(n);
    await this.addEntry(dir, name, n.ino, FileType.Reg);
    return n.ino;
  }

  /** Replaces a file's contents, keeping its owner, mode and every xattr (the label, security.capability, …). */
  async replaceFile(path: string, data: Uint8Array, time?: number): Promise<void> {
    const old = await this.inode(await this.resolve(path));
    const { runs, tree } = await this.extents(old); // also refuses a block-mapped file before anything changes
    const unlinkOnly = old.links > 1; // remove keeps a hard-linked file's blocks and inode
    const freed = unlinkOnly ? 0 : runs.reduce((a, r) => a + r.count, 0) + tree.length + (old.fileAcl ? 1 : 0);
    this.needs(this.blocksFor(data.length) - freed, unlinkOnly ? 1 : 0, path); // the old file stays unless the new one fits
    const xattrs = await this.xattrs(old);
    const label = xattrs.find(isLabel);
    const attrs: Attrs = { mode: old.mode & 0o7777, uid: old.uid, gid: old.gid, label: label ? dec.decode(label.value).replace(/\0+$/, '') : undefined, time, xattrs: xattrs.filter((x) => !isLabel(x)) };
    await this.remove(path, { time });
    await this.writeFile(path, data, attrs);
  }

  /**
   * Blocks a new file of `bytes` takes at most: its data, an extent leaf, an xattr block, and a new
   * block for the directory entry.
   */
  private blocksFor(bytes: number): number {
    return Math.ceil(bytes / this.blockSize) + 3;
  }

  /** Refuses, before anything is allocated, an operation the free blocks or inodes can't hold. */
  private needs(blocks: number, inodes: number, path: string) {
    if (blocks > this.freeBlocks) throw new Error(`no room for ${path}: it needs up to ${blocks} blocks, ${this.freeBlocks} are free. Nothing was changed.`);
    if (inodes > this.freeInodes) throw new Error(`no room for ${path}: no free inodes. Nothing was changed.`);
  }

  /** Throws, before anything changes, for a tree this package can't free (block-mapped files). */
  private async checkRemovable(path: string, n: Inode): Promise<void> {
    await this.extents(n);
    if (!n.isDir) return;
    for (const k of await this.readdir(path)) {
      if (k.name === '.' || k.name === '..') continue;
      await this.checkRemovable(`${path.replace(/\/$/, '')}/${k.name}`, await this.inode(k.ino));
    }
  }

  /** Removes a file, symlink or (with `recursive`, or when empty) directory. */
  async remove(path: string, o: { recursive?: boolean; time?: number; checked?: boolean } = {}): Promise<void> {
    const { dir, name } = await this.parentOf(path);
    const ino = await this.lookup(dir, name);
    if (ino === undefined) throw new Error(`${path} doesn't exist`);
    const n = await this.inode(ino);
    if (n.isDir && !o.recursive && (await this.readdir(path)).some((e) => e.name !== '.' && e.name !== '..')) throw new Error(`${path} isn't empty`);
    if (!o.checked) await this.checkRemovable(path, n); // the whole tree, before the first unlink
    o = { ...o, checked: true };
    if (n.isDir) {
      const kids = (await this.readdir(path)).filter((e) => e.name !== '.' && e.name !== '..');
      if (kids.length && !o.recursive) throw new Error(`${path} isn't empty`);
      for (const k of kids) await this.remove(`${path.replace(/\/$/, '')}/${k.name}`, o);
    }
    await this.removeEntry(dir, name);
    const time = o.time ?? now();
    if (n.isDir) {
      const parent = await this.inode(dir);
      parent.links--;
      await this.putInode(parent);
      await this.release(await this.inode(ino), time);
      return;
    }
    const fresh = await this.inode(ino);
    fresh.links--;
    if (fresh.links > 0) { await this.putInode(fresh); return; }
    await this.release(fresh, time);
  }

  /** Frees an unlinked inode's blocks, its xattr block, and the inode. */
  private async release(n: Inode, time: number): Promise<void> {
    const { runs, tree } = await this.extents(n);
    for (const r of runs) await this.freeBlocks_(r.start, r.count);
    for (const t of tree) await this.freeBlocks_(t, 1);
    if (n.fileAcl) {
      const b = await this.disk.read(n.fileAcl);
      const dv = new DataView(b.buffer);
      const refs = dv.getUint32(4, true);
      if (refs <= 1) await this.freeBlocks_(n.fileAcl, 1);
      else { dv.setUint32(4, refs - 1, true); await this.disk.write(n.fileAcl, b); }
      n.fileAcl = 0;
    }
    await this.freeInode(n, time);
  }
}

const isLabel = (x: Xattr) => x.index === XattrIndex.Security && x.name === 'selinux';

/** The security.selinux xattr for `label` (stored with its NUL, as libselinux writes it). */
export function selinux(label: string): Xattr {
  const v = enc.encode(`${label}\0`);
  return { index: XattrIndex.Security, name: 'selinux', value: v };
}

export function fullNameOf(x: Xattr): string {
  const p = XATTR_PREFIXES.find(([i]) => i === x.index)?.[1];
  return p ? p + x.name : `#${x.index}.${x.name}`;
}
