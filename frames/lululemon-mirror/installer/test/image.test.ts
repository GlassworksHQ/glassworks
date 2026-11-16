// The image patcher on make_ext4fs-style images (as the Glass Panes system.img is), judged by
// e2fsprogs. With GW_MIRROR_SYSTEM_IMG=<path to the real system.img> it also patches the real image
// (sparse in, sparse out, then "flashed" onto junk) and checks it the same way. That file is the
// person's download: never commit it, and nothing in CI has it.
import { createReadStream, closeSync, existsSync, mkdirSync, openSync, readSync, writeFileSync, writeSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { Disk, Ext4, MemoryStore, Sha256, hex, readSparse, writeSparse, type Store } from '@glassworks/ext4';
import { cleanup, debugfs, debugfsCat, dump, fsck, haveTools, load, mke2fs, scratch } from '@glassworks/ext4/testing';
import { APP_DIR, GLASS_PANES, buildUserdata, patchSystem, setProp } from '../src';

const T = 1_700_000_000;
const MAKE_EXT4FS = ['-O', '^metadata_csum,^64bit,^flex_bg,^dir_index,^huge_file,^dir_nlink,^extra_isize,uninit_bg,resize_inode'];
const libs = { 'arm64-v8a': { 'libxul.so': new Uint8Array(randomBytes(300_000)), 'libmozglue.so': new Uint8Array(randomBytes(20_000)) }, 'armeabi-v7a': { 'libxul.so': new Uint8Array(10) } };
const APK = fakeApk('sh.edm.s1kiosk', { libs });
const ANIM = new Uint8Array(randomBytes(50_000));

describe('setProp', () => {
  it('replaces every earlier assignment and keeps the rest', () => {
    expect(setProp('a=1\nro.sf.hwrotation=90\nb=2\n', 'ro.sf.hwrotation', '270')).toBe('a=1\nb=2\nro.sf.hwrotation=270\n');
    expect(setProp('a=1', 'x', 'y')).toBe('a=1\nx=y\n');
  });
});

describe.skipIf(!haveTools)('the Mirror\'s images, judged by e2fsprogs', () => {
  let dir: string;
  beforeAll(() => { dir = scratch(); });
  afterAll(() => cleanup(dir));

  function systemTree(fingerprint: string = GLASS_PANES.fingerprint): string {
    const root = join(dir, `sys-${Math.random().toString(36).slice(2)}`);
    for (const d of ['priv-app/Settings', 'app/Provision', 'media/audio']) mkdirSync(join(root, d), { recursive: true });
    writeFileSync(join(root, 'build.prop'), `ro.build.fingerprint=${fingerprint}\npersist.sys.usb.config=diag,serial_smd,rmnet_bam,adb\n`);
    writeFileSync(join(root, 'priv-app/Settings/Settings.apk'), randomBytes(100_000));
    return root;
  }

  it('adds S1 Kiosk as a privileged app with its libraries, the boot animation and the rotation', async () => {
    const img = join(dir, 'system.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const fs = await Ext4.open(disk);
    const log: string[] = [];
    await patchSystem(fs, { apk: APK, bootAnimation: ANIM, hwrotation: 270, time: T }, (l) => log.push(l));
    const after = { free: fs.freeBlocks, inodes: fs.freeInodes };
    await patchSystem(await Ext4.open(disk), { apk: APK, bootAnimation: ANIM, hwrotation: 270, time: T }); // again: the same result
    const again = await Ext4.open(disk);
    expect({ free: again.freeBlocks, inodes: again.freeInodes }).toEqual(after);
    await dump(disk, img);

    const r = fsck(img);
    expect(r.code, r.out).toBe(0);
    expect(Buffer.from(debugfsCat(img, `${APP_DIR}/S1Kiosk.apk`))).toEqual(Buffer.from(APK));
    expect(Buffer.from(debugfsCat(img, `${APP_DIR}/lib/arm64/libxul.so`))).toEqual(Buffer.from(libs['arm64-v8a']['libxul.so']));
    expect(debugfs(img, `ls ${APP_DIR}/lib`)).not.toContain('arm '); // only the primary ABI's
    expect(Buffer.from(debugfsCat(img, '/media/bootanimation.zip'))).toEqual(Buffer.from(ANIM));
    const prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    expect(prop.match(/ro\.sf\.hwrotation=/g)).toHaveLength(1);
    expect(prop).toMatch(/\nro\.sf\.hwrotation=270\n$/);
    for (const p of [APP_DIR, `${APP_DIR}/lib/arm64`]) {
      expect(debugfs(img, `stat ${p}`)).toMatch(/Mode:\s+0755[\s\S]*User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:system_file:s0');
    }
    for (const p of [`${APP_DIR}/S1Kiosk.apk`, `${APP_DIR}/lib/arm64/libmozglue.so`, '/media/bootanimation.zip']) {
      expect(debugfs(img, `stat ${p}`)).toMatch(/Mode:\s+0644[\s\S]*User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:system_file:s0');
    }
    expect(log).toContain('2 native libraries for arm64-v8a in /priv-app/S1Kiosk/lib/arm64');
  }, 60_000);

  it('refuses another image or another APK, with nothing written', async () => {
    const img = join(dir, 'other.img');
    mke2fs(img, 32, MAKE_EXT4FS, systemTree('Android/other/other:6.0.1/X/1:user/release-keys'));
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    await expect(patchSystem(await Ext4.open(disk), { apk: APK })).rejects.toThrow(/isn't the Glass Panes Mirror image.*Nothing was changed/);
    const img2 = join(dir, 'right.img');
    mke2fs(img2, 32, MAKE_EXT4FS, systemTree());
    const disk2 = await load(img2);
    const before2 = hex(new Sha256().update(await disk2.read(0, disk2.blocks)).digest());
    await expect(patchSystem(await Ext4.open(disk2), { apk: fakeApk('com.nimrod.mirrorkiosk') })).rejects.toThrow(/not S1 Kiosk.*Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { apk: fakeApk('sh.edm.s1kiosk', { minSdk: 26 }) })).rejects.toThrow(/needs Android API 26.*pick S1 Kiosk's legacy build\. Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { apk: fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { x86_64: { 'libxul.so': new Uint8Array(4) } } }) })).rejects.toThrow(/native code only for x86_64.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
    expect(hex(new Sha256().update(await disk2.read(0, disk2.blocks)).digest())).toBe(before2);
  }, 60_000);

  it('takes the ABI that has libraries, not an empty lib/<abi>/ folder before it (#354 review)', async () => {
    const img = join(dir, 'abi.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const v7 = new Uint8Array([7, 7, 7]);
    // a directory entry for arm64-v8a, the real library under armeabi-v7a
    const apk = fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'arm64-v8a': { '': new Uint8Array(0) }, 'armeabi-v7a': { 'libxul.so': v7 } } });
    const log: string[] = [];
    await patchSystem(await Ext4.open(disk), { apk, time: T }, (l) => log.push(l));
    await dump(disk, img);
    expect(fsck(img).code).toBe(0);
    expect(Buffer.from(debugfsCat(img, `${APP_DIR}/lib/arm/libxul.so`))).toEqual(Buffer.from(v7));
    expect(log).toContain('1 native library for armeabi-v7a in /priv-app/S1Kiosk/lib/arm');
  }, 60_000);

  it('refuses a library it can\'t unpack before writing anything (#354 review)', async () => {
    const img = join(dir, 'badlib.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    const apk = fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'armeabi-v7a': { 'libxul.so': new Uint8Array([1, 2, 3]) } } }).slice();
    // mark the library's central directory entry as compressed with a method Kiln doesn't read (99)
    const dv = new DataView(apk.buffer);
    const name = new TextEncoder().encode('lib/armeabi-v7a/libxul.so');
    let found = false;
    for (let at = 0; at + 46 < apk.length; at++) {
      if (dv.getUint32(at, true) !== 0x02014b50) continue;
      const n = dv.getUint16(at + 28, true);
      if (n === name.length && apk.subarray(at + 46, at + 46 + n).every((b, i) => b === name[i])) { dv.setUint16(at + 10, 99, true); found = true; }
    }
    expect(found).toBe(true);
    await expect(patchSystem(await Ext4.open(disk), { apk, time: T })).rejects.toThrow(/method 99.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
  }, 60_000);

  it('refuses an APK that lists the same library twice, before writing anything (#354 review)', async () => {
    const img = join(dir, 'duplib.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    const apk = fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'armeabi-v7a': { 'libxul.so': new Uint8Array([1]) } }, extraEntries: [['lib/armeabi-v7a/libxul.so', new Uint8Array([2])]] });
    await expect(patchSystem(await Ext4.open(disk), { apk, time: T })).rejects.toThrow(/libxul\.so more than once.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
  }, 60_000);

  it('builds a fresh userdata that lets only this browser\'s adb key in', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 5000);
    const key = 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks';
    await buildUserdata(disk, { adbKey: key, time: T, uuid: new Uint8Array(16).fill(9) });
    const img = join(dir, 'userdata.img');
    await dump(disk, img);
    const r = fsck(img);
    expect(r.code, r.out).toBe(0);
    expect(new TextDecoder().decode(debugfsCat(img, '/misc/adb/adb_keys'))).toBe(`${key}\n`);
    expect(debugfs(img, 'stat /')).toMatch(/Mode:\s+0771[\s\S]*User:\s+1000\s+Group:\s+1000/);
    expect(debugfs(img, 'stat /misc')).toMatch(/Mode:\s+01771[\s\S]*User:\s+1000\s+Group:\s+9998/);
    expect(debugfs(img, 'stat /misc/adb')).toMatch(/Mode:\s+02750[\s\S]*User:\s+1000\s+Group:\s+2000/);
    expect(debugfs(img, 'stat /misc/adb/adb_keys')).toMatch(/Mode:\s+0640[\s\S]*User:\s+1000\s+Group:\s+2000/);
    expect(debugfs(img, 'ea_get /misc/adb/adb_keys security.selinux')).toContain('u:object_r:adb_keys_file:s0');
    expect(debugfs(img, 'ea_get / security.selinux')).toContain('u:object_r:system_data_file:s0');
    expect(debugfs(img, 'ls /')).toMatch(/lost\+found/);
    await expect(buildUserdata(disk, { adbKey: 'not a key; rm -rf /' })).rejects.toThrow(/adb public key/);
  }, 60_000);
});

const REAL = process.env.GW_MIRROR_SYSTEM_IMG;

/** A Store on a file: the real image is too big for memory. */
const fileStore = (fd: number): Store => ({
  async read(o, n) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; },
  async write(o, d) { writeSync(fd, d, 0, d.length, o); },
});

describe.skipIf(!REAL || !haveTools || !existsSync(REAL ?? ''))('the real Glass Panes system.img (GW_MIRROR_SYSTEM_IMG)', () => {
  it('checks its hash, patches it, and the flashed result has no e2fsck finding the original hasn\'t', async () => {
    const dir = scratch();
    try {
      const sha = new Sha256();
      const work = openSync(join(dir, 'work.raw'), 'w+');
      const { disk } = await readSparse(createReadStream(REAL!, { highWaterMark: 1 << 20 }), (h) => new Disk(fileStore(work), h.blockSize, h.blocks), { onBytes: (b) => sha.update(b) });
      expect(hex(sha.digest())).toBe(GLASS_PANES.system.sha256);
      await dump(disk, join(dir, 'original.raw'));
      // every question e2fsck -n asks (Fix?, Clear?, Salvage?, …) answers itself "no": compare them all
      const problems = (out: string) => out.split('\n').filter((l) => /\?\s+(no|yes)\s*$/.test(l) || /Fix\?/.test(l));
      const baseline = problems(fsck(join(dir, 'original.raw')).out);

      await patchSystem(await Ext4.open(disk), { apk: APK, bootAnimation: ANIM, hwrotation: 270 });
      const out = openSync(join(dir, 'out.img'), 'w');
      let pos = 0;
      await writeSparse(disk, (b) => { writeSync(out, b, 0, b.length, pos); pos += b.length; });
      closeSync(out);
      closeSync(work);

      // flash it onto a partition that held something else, as fastboot would
      const dev = openSync(join(dir, 'flashed.raw'), 'w+');
      const { disk: flashed } = await readSparse(createReadStream(join(dir, 'out.img'), { highWaterMark: 1 << 20 }), async (h) => {
        const d = new Disk(fileStore(dev), h.blockSize, h.blocks);
        for (let b = 0; b < h.blocks; b += 4096) await d.store.write(b * 4096, new Uint8Array(Math.min(4096, h.blocks - b) * 4096).fill(0xa5));
        return d;
      });
      closeSync(dev);
      void flashed;
      const r = fsck(join(dir, 'flashed.raw'));
      const findings = problems(r.out);
      expect(findings).toEqual(baseline); // the image's own make_ext4fs quirk, nothing new
      const img = join(dir, 'flashed.raw');
      expect(Buffer.from(debugfsCat(img, `${APP_DIR}/S1Kiosk.apk`))).toEqual(Buffer.from(APK));
      expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).toMatch(/\nro\.sf\.hwrotation=270\n$/);
      expect(debugfs(img, 'ls /app')).toContain('Provision');
    } finally { cleanup(dir); }
  }, 600_000);
});
