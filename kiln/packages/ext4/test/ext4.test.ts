// Edits to ext4 images that e2fsprogs made, then e2fsck -fn and debugfs as the judges: the image must
// be clean, and what debugfs reads back must be what was written (bytes, owner, mode, label).
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Disk, Ext4, MemoryStore, mkfs, readSparse, writeSparse } from '../src';
import { cleanup, debugfs, debugfsCat, dump, fsck, haveTools, load, mke2fs, scratch } from './tools';

const T = 1_700_000_000;
const SYSTEM = { mode: 0o644, uid: 0, gid: 0, label: 'u:object_r:system_file:s0', time: T };
const DIR = { ...SYSTEM, mode: 0o755 };

// make_ext4fs (Android 6's system.img): no flex_bg, gdt_csum (uninit_bg), a resize inode, a journal
const MAKE_EXT4FS = ['-O', '^metadata_csum,^64bit,^flex_bg,^dir_index,^huge_file,^dir_nlink,^extra_isize,uninit_bg,resize_inode'];
// mke2fs's own ext4, minus what this package refuses
const MKE2FS = ['-O', '^metadata_csum,^64bit,^metadata_csum_seed,^orphan_file'];

describe.skipIf(!haveTools)('ext4, judged by e2fsprogs', () => {
  let dir: string;
  beforeAll(() => { dir = scratch(); });
  afterAll(() => cleanup(dir));

  function tree(): string {
    const root = join(dir, `tree-${Math.random().toString(36).slice(2)}`);
    mkdirSync(join(root, 'priv-app/Old'), { recursive: true });
    mkdirSync(join(root, 'media'), { recursive: true });
    writeFileSync(join(root, 'build.prop'), 'ro.build.type=userdebug\npersist.sys.usb.config=adb\n');
    writeFileSync(join(root, 'priv-app/Old/Old.apk'), randomBytes(300_000));
    for (let i = 0; i < 20; i++) writeFileSync(join(root, `media/f${i}`), randomBytes(5000 + i));
    return root;
  }

  it.each([['make_ext4fs-style', MAKE_EXT4FS], ['mke2fs ext4 (flex_bg, dir_index, huge_file)', MKE2FS]])(
    'adds, replaces and removes on a %s image, and e2fsck finds it clean', async (_, opts) => {
      const img = join(dir, `a-${Math.random().toString(36).slice(2)}.img`);
      mke2fs(img, 160, opts as string[], tree());
      expect(fsck(img).code).toBe(0);
      const disk = await load(img);
      const fs = await Ext4.open(disk);
      const apk = new Uint8Array(randomBytes(3_000_123));
      await fs.mkdir('/priv-app/S1Kiosk', DIR);
      await fs.writeFile('/priv-app/S1Kiosk/S1Kiosk.apk', apk, SYSTEM);
      await fs.remove('/priv-app/Old', { recursive: true, time: T });
      const prop = new TextDecoder().decode(await fs.readFile('/build.prop'));
      await fs.replaceFile('/build.prop', new TextEncoder().encode(`${prop}ro.sf.hwrotation=270\n`), T);
      await fs.writeFile('/media/bootanimation.zip', new Uint8Array(randomBytes(200_000)), SYSTEM);
      for (let i = 0; i < 300; i++) await fs.writeFile(`/media/n${String(i).padStart(3, '0')}-a-longish-name`, new Uint8Array(10), SYSTEM); // more directory blocks
      await fs.flush();
      await dump(disk, img);

      const r = fsck(img);
      expect(r.out).toMatch(/clean|\d+\/\d+ files/);
      expect(r.code, r.out).toBe(0);
      expect(Buffer.from(debugfsCat(img, '/priv-app/S1Kiosk/S1Kiosk.apk'))).toEqual(Buffer.from(apk));
      expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).toMatch(/hwrotation=270\n$/);
      expect(debugfs(img, 'ls /priv-app')).not.toMatch(/\bOld\b/);
      const st = debugfs(img, 'stat /priv-app/S1Kiosk/S1Kiosk.apk');
      expect(st).toMatch(/Mode:\s+0644/);
      expect(st).toMatch(/User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, 'ea_get /priv-app/S1Kiosk/S1Kiosk.apk security.selinux')).toContain('u:object_r:system_file:s0');
      expect(debugfs(img, 'stat /priv-app/S1Kiosk')).toMatch(/Mode:\s+0755/);
      // read back through this package too
      const again = await Ext4.open(await load(img));
      expect(await again.label('/media/bootanimation.zip')).toBe('u:object_r:system_file:s0');
      expect((await again.readdir('/media')).length).toBe(2 + 20 + 1 + 300);
    }, 60_000);

  it('writes a file in many pieces through an extent leaf block', async () => {
    const img = join(dir, 'frag.img');
    mke2fs(img, 64, MAKE_EXT4FS);
    const disk = await load(img);
    const fs = await Ext4.open(disk);
    // fill, then free every other file, so free space is in small holes
    for (let i = 0; i < 40; i++) await fs.writeFile(`/f${i}`, new Uint8Array(8 * 4096), SYSTEM);
    for (let i = 0; i < 40; i += 2) await fs.remove(`/f${i}`, { time: T });
    const free = fs.freeBlocks;
    const big = new Uint8Array(randomBytes((free - 10) * 4096));
    await fs.writeFile('/big', big, SYSTEM);
    await fs.flush();
    await dump(disk, img);
    const r = fsck(img);
    expect(r.code, r.out).toBe(0);
    expect(debugfs(img, 'stat /big')).toMatch(/\(ETB0\)|EXTENTS:[\s\S]*\(0-/);
    expect(Buffer.from(debugfsCat(img, '/big'))).toEqual(Buffer.from(big));
  }, 60_000);

  it('makes a fresh filesystem that e2fsck finds clean, with lost+found, a journal and labels', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 7000);
    const fs = await mkfs(disk, { label: 'userdata', time: T, uuid: new Uint8Array(16).fill(7), journalBlocks: 4096,
      root: { mode: 0o771, uid: 1000, gid: 1000, label: 'u:object_r:system_data_file:s0' } });
    await fs.mkdirs('/misc/adb', { mode: 0o2750, uid: 1000, gid: 2000, label: 'u:object_r:adb_keys_file:s0', time: T });
    await fs.writeFile('/misc/adb/adb_keys', new TextEncoder().encode('QAAAA-not-a-real-key kiln@glassworks\n'), { mode: 0o640, uid: 1000, gid: 2000, label: 'u:object_r:adb_keys_file:s0', time: T });
    await fs.flush();
    const img = join(dir, 'fresh.img');
    await dump(disk, img);
    const r = fsck(img);
    expect(r.code, r.out).toBe(0);
    expect(debugfs(img, 'stats')).toMatch(/Filesystem features:\s+has_journal ext_attr filetype extent sparse_super large_file extra_isize/);
    expect(debugfs(img, 'stat /misc/adb')).toMatch(/Mode:\s+02750/);
    expect(debugfs(img, 'stat /misc/adb/adb_keys')).toMatch(/User:\s+1000\s+Group:\s+2000/);
    expect(debugfs(img, 'ea_get / security.selinux')).toContain('system_data_file');
    expect(new TextDecoder().decode(debugfsCat(img, '/misc/adb/adb_keys'))).toContain('kiln@glassworks');
    expect(debugfs(img, 'ls /')).toContain('lost+found');
  }, 60_000);

  it('through a sparse image: what comes out flashes the same filesystem', async () => {
    const img = join(dir, 'sp.img');
    mke2fs(img, 96, MAKE_EXT4FS, tree());
    const src = await load(img);
    // as the patcher does: sparse in, edit, sparse out, and the result read back as a device would hold it
    const parts: Uint8Array[] = [];
    await writeSparse(src, (b) => { parts.push(b.slice()); });
    const { disk } = await readSparse((async function* () { yield* parts; })(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks));
    const fs = await Ext4.open(disk);
    await fs.writeFile('/new', new Uint8Array(randomBytes(50_000)), SYSTEM);
    await fs.flush();
    const out: Uint8Array[] = [];
    await writeSparse(disk, (b) => { out.push(b.slice()); });
    // a "device" whose partition held junk before: only the image's care blocks overwrite it
    const { disk: dev } = await readSparse((async function* () { yield* out; })(), async (h) => {
      const d = new Disk(new MemoryStore(), h.blockSize, h.blocks);
      for (let b = 0; b < h.blocks; b += 256) await d.store.write(b * 4096, new Uint8Array(randomBytes(Math.min(256, h.blocks - b) * 4096)));
      return d;
    });
    const flashed = join(dir, 'flashed.img');
    await dump(dev, flashed);
    const r = fsck(flashed);
    expect(r.code, r.out).toBe(0);
    expect(debugfs(flashed, 'ls /')).toContain('new');
  }, 60_000);
});

describe('ext4 without e2fsprogs', () => {
  it('reads back what it wrote on a filesystem it made', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 40000);
    const fs = await mkfs(disk, { time: T, journalBlocks: 1024 });
    await fs.mkdirs('/a/b', DIR);
    const data = new Uint8Array(randomBytes(70_000));
    await fs.writeFile('/a/b/c', data, SYSTEM);
    await fs.flush();
    const again = await Ext4.open(disk);
    expect(Buffer.from(await again.readFile('/a/b/c'))).toEqual(Buffer.from(data));
    expect(await again.stat('/a/b/c')).toMatchObject({ mode: 0o644, uid: 0, size: 70_000, isFile: true });
    expect(await again.label('/a/b')).toBe('u:object_r:system_file:s0');
    const before = again.freeBlocks;
    await again.remove('/a', { recursive: true, time: T });
    expect(again.freeBlocks).toBe(before + 2 + Math.ceil(70_000 / 4096));
    await expect(again.stat('/a')).rejects.toThrow(/doesn't exist/);
  });

  it('refuses features it doesn\'t handle', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 40000);
    await mkfs(disk, { time: T, journalBlocks: 0 });
    const b0 = await disk.read(0);
    const v = new DataView(b0.buffer);
    v.setUint32(1024 + 100, v.getUint32(1024 + 100, true) | 0x400, true); // metadata_csum
    await disk.write(0, b0);
    await expect(Ext4.open(disk)).rejects.toThrow(/features this package doesn't handle/);
    v.setUint32(1024 + 100, v.getUint32(1024 + 100, true) & ~0x400, true);
    v.setUint32(1024 + 96, v.getUint32(1024 + 96, true) | 0x4, true); // needs_recovery
    await disk.write(0, b0);
    await expect(Ext4.open(disk)).rejects.toThrow(/needs recovery/);
  });
});
