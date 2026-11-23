// A fresh, empty ext4, the way mke2fs lays one out without the newer features: 4 KiB blocks,
// 32768 blocks per group, 256-byte inodes, extents, filetype, sparse_super, large_file, extra_isize,
// a journal, and lost+found. For a new userdata image: the partition's size, nothing in it but what
// the caller adds after. Every metadata block is written (inode tables as zeros), so nothing left on
// the partition from before is read as metadata.
import type { Disk } from './disk.ts';
import { Ext4, Inode, selinux } from './ext4.ts';
import {
  Compat, EXT4_MAGIC, EXTENT_MAGIC, FileType, InodeFlag, Incompat, JOURNAL_INO, ROOT_INO, RoCompat, S_IFDIR, S_IFREG, SB_OFFSET,
  hasSuperBackup,
} from './layout.ts';
import type { Attrs } from './ext4.ts';

export interface MkfsOptions {
  /** The filesystem's size in blocks; default the whole disk. */
  blocks?: number;
  /** Volume name, up to 16 bytes. */
  label?: string;
  /** 16 bytes; random by default. */
  uuid?: Uint8Array;
  /** Seconds since 1970 for every timestamp; default now. */
  time?: number;
  /** Journal size in blocks; 0 for none. Default 32768 (128 MiB at 4 KiB) on a big enough filesystem. */
  journalBlocks?: number;
  /** The root directory's owner, mode and label; default root:root 0755. */
  root?: Partial<Attrs>;
  /** lost+found's; default root:root 0700. */
  lostFound?: Partial<Attrs>;
}

const BPG = 32768, INODE_SIZE = 256, INODE_RATIO = 16384, EXTRA_ISIZE = 32;

export async function mkfs(disk: Disk, o: MkfsOptions = {}): Promise<Ext4> {
  const bs = disk.blockSize;
  if (bs !== 4096) throw new Error('mkfs here makes 4 KiB blocks only');
  const time = o.time ?? Math.floor(Date.now() / 1000);
  let blocks = o.blocks ?? disk.blocks;
  if (blocks > disk.blocks) throw new Error('the filesystem is bigger than the disk');
  const ipg = Math.min(bs * 8, Math.ceil((BPG * bs) / INODE_RATIO / (bs / INODE_SIZE)) * (bs / INODE_SIZE));
  const itBlocks = (ipg * INODE_SIZE) / bs;
  let groups = Math.ceil(blocks / BPG);
  const gdtBlocks = () => Math.ceil((groups * 32) / bs);
  const overhead = (g: number) => (hasSuperBackup(g, true) ? 1 + gdtBlocks() : 0) + 2 + itBlocks;
  // a last group too small for its own metadata is left out, as mke2fs does
  const lastSize = blocks - (groups - 1) * BPG;
  if (groups > 1 && lastSize < overhead(groups - 1) + 50) { blocks -= lastSize; groups--; }
  if (blocks < overhead(0) + 64) throw new Error('the filesystem is too small');
  const groupBlocks = (g: number) => Math.min(BPG, blocks - g * BPG);
  const journal = o.journalBlocks ?? (blocks >= 4 * BPG ? BPG : 0);

  // ---- the superblock ----
  const sb = new Uint8Array(1024);
  const v = new DataView(sb.buffer);
  const uuid = o.uuid ?? crypto.getRandomValues(new Uint8Array(16));
  v.setUint32(0, groups * ipg, true);
  v.setUint32(4, blocks, true);
  v.setUint32(20, 0, true); // first data block
  v.setUint32(24, 2, true); v.setUint32(28, 2, true); // 4 KiB blocks and clusters
  v.setUint32(32, BPG, true); v.setUint32(36, BPG, true); v.setUint32(40, ipg, true);
  v.setUint32(44, 0, true); v.setUint32(48, time, true);
  v.setUint16(52, 0, true); v.setUint16(54, 0xffff, true);
  v.setUint16(56, EXT4_MAGIC, true); v.setUint16(58, 1, true); v.setUint16(60, 1, true);
  v.setUint32(64, time, true); v.setUint32(72, 0, true); v.setUint32(76, 1, true);
  v.setUint32(84, 11, true); v.setUint16(88, INODE_SIZE, true);
  v.setUint32(92, Compat.ExtAttr | (journal ? Compat.HasJournal : 0), true);
  v.setUint32(96, Incompat.FileType | Incompat.Extents, true);
  v.setUint32(100, RoCompat.SparseSuper | RoCompat.LargeFile | RoCompat.ExtraIsize, true);
  sb.set(uuid, 104);
  sb.set(new TextEncoder().encode((o.label ?? '').slice(0, 16)), 120);
  v.setUint32(264, time, true);
  v.setUint16(348, EXTRA_ISIZE, true); v.setUint16(350, EXTRA_ISIZE, true);

  // ---- descriptors, bitmaps, inode tables ----
  const gdt = new Uint8Array(gdtBlocks() * bs);
  const gv = new DataView(gdt.buffer);
  const layout: { bb: number; ib: number; it: number; firstFree: number }[] = [];
  let freeBlocks = 0;
  for (let g = 0; g < groups; g++) {
    const start = g * BPG;
    const meta = hasSuperBackup(g, true) ? 1 + gdtBlocks() : 0;
    const bb = start + meta, ib = bb + 1, it = ib + 1;
    layout.push({ bb, ib, it, firstFree: it + itBlocks });
    const used = meta + 2 + itBlocks;
    const free = groupBlocks(g) - used;
    freeBlocks += free;
    const o2 = g * 32;
    gv.setUint32(o2, bb, true); gv.setUint32(o2 + 4, ib, true); gv.setUint32(o2 + 8, it, true);
    gv.setUint16(o2 + 12, free, true); gv.setUint16(o2 + 14, g === 0 ? ipg - 10 : ipg, true);
    gv.setUint16(o2 + 16, 0, true);
  }

  // root's directory block: the first free block of group 0
  const rootBlock = layout[0]!.firstFree;
  gv.setUint16(12, gv.getUint16(12, true) - 1, true);
  gv.setUint16(16, 1, true); // used dirs
  freeBlocks -= 1;
  v.setUint32(12, freeBlocks, true);
  v.setUint32(16, groups * ipg - 10, true);

  for (let g = 0; g < groups; g++) {
    const start = g * BPG, { bb, ib, it } = layout[g]!;
    const bm = new Uint8Array(bs);
    for (let b = start; b < layout[g]!.firstFree; b++) bm[(b - start) >> 3]! |= 1 << ((b - start) & 7);
    for (let i = groupBlocks(g); i < bs * 8; i++) bm[i >> 3]! |= 1 << (i & 7);
    if (g === 0) bm[(rootBlock - start) >> 3]! |= 1 << ((rootBlock - start) & 7);
    await disk.write(bb, bm);
    const im = new Uint8Array(bs);
    if (g === 0) im[0] = 0xff, im[1] = 0x03; // inodes 1-10, reserved
    for (let i = ipg; i < bs * 8; i++) im[i >> 3]! |= 1 << (i & 7);
    await disk.write(ib, im);
    await disk.zeroFill(it, itBlocks);
    if (hasSuperBackup(g, true)) {
      const copy = sb.slice();
      new DataView(copy.buffer).setUint16(90, g, true);
      if (g === 0) {
        const b0 = new Uint8Array(bs);
        b0.set(copy, SB_OFFSET);
        await disk.write(start, b0);
      } else {
        const b = new Uint8Array(bs);
        b.set(copy, 0);
        await disk.write(start, b);
      }
      await disk.write(start + 1, gdt);
    }
  }

  // root, inode 2
  const rootAttrs = { mode: 0o755, uid: 0, gid: 0, ...o.root };
  const dirBlock = new Uint8Array(bs);
  const dv = new DataView(dirBlock.buffer);
  dv.setUint32(0, ROOT_INO, true); dv.setUint16(4, 12, true); dirBlock[6] = 1; dirBlock[7] = FileType.Dir; dirBlock[8] = 0x2e;
  dv.setUint32(12, ROOT_INO, true); dv.setUint16(16, bs - 12, true); dirBlock[18] = 2; dirBlock[19] = FileType.Dir; dirBlock[20] = 0x2e; dirBlock[21] = 0x2e;
  await disk.write(rootBlock, dirBlock);

  const fs = await Ext4.open(disk);
  const root = new Inode(ROOT_INO, new Uint8Array(INODE_SIZE));
  root.mode = S_IFDIR | rootAttrs.mode; root.uid = rootAttrs.uid; root.gid = rootAttrs.gid; root.setTimes(time);
  root.dv.setUint16(128, EXTRA_ISIZE, true);
  root.links = 2; root.size = bs; root.sectors = bs / 512;
  root.flags = InodeFlag.Extents;
  const rb = root.iblock;
  const rv = new DataView(rb.buffer, rb.byteOffset, 60);
  rv.setUint16(0, EXTENT_MAGIC, true); rv.setUint16(2, 1, true); rv.setUint16(4, 4, true);
  rv.setUint32(12, 0, true); rv.setUint16(16, 1, true); rv.setUint32(20, rootBlock, true);
  fs.writeIbodyXattrs(root, rootAttrs.label ? [selinux(rootAttrs.label)] : []);
  await fs.putInode(root);

  await fs.mkdir('/lost+found', { mode: 0o700, uid: 0, gid: 0, time, ...o.lostFound });

  if (journal) {
    const runs = await fs.allocBlocks(journal, Math.floor(groups / 2) * BPG);
    if (runs.length > 4) throw new Error('no room for a contiguous journal');
    const j = new Inode(JOURNAL_INO, new Uint8Array(INODE_SIZE));
    j.mode = S_IFREG | 0o600; j.setTimes(time); j.links = 1;
    j.dv.setUint16(128, EXTRA_ISIZE, true);
    let logical = 0;
    const mapped = runs.map((r) => { const m = { logical, start: r.start, count: r.count }; logical += r.count; return m; });
    await fs.setExtents(j, mapped, runs[0]!.start);
    j.size = journal * bs; j.sectors = journal * (bs / 512);
    await fs.putInode(j);
    for (const r of runs) await disk.zeroFill(r.start, r.count);
    const jsb = new Uint8Array(bs);
    const jv = new DataView(jsb.buffer); // big-endian, as jbd2 keeps it
    jv.setUint32(0, 0xc03b3998); jv.setUint32(4, 4); jv.setUint32(8, 0);
    jv.setUint32(12, bs); jv.setUint32(16, journal); jv.setUint32(20, 1); jv.setUint32(24, 1); jv.setUint32(28, 0);
    jsb.set(uuid, 0x30);
    jv.setUint32(0x40, 1);
    await disk.write(runs[0]!.start, jsb);
    fs.sb.set(j.iblock, 268); // s_jnl_blocks: i_block, then i_size_high and i_size
    new DataView(fs.sb.buffer, fs.sb.byteOffset).setUint32(268 + 15 * 4, 0, true);
    new DataView(fs.sb.buffer, fs.sb.byteOffset).setUint32(268 + 16 * 4, journal * bs, true);
    fs.sb[253] = 1; // EXT3_JNL_BACKUP_BLOCKS
    new DataView(fs.sb.buffer, fs.sb.byteOffset).setUint32(224, JOURNAL_INO, true);
  }
  await fs.flush();
  return fs;
}
