// The image patcher on make_ext4fs-style images (as the Glass Panes system.img is), judged by
// e2fsprogs. With GW_MIRROR_SYSTEM_IMG=<path to the real system.img> it also patches the real image
// (sparse in, sparse out, then "flashed" onto junk) and checks it the same way. That file is the
// person's download: never commit it, and nothing in CI has it.
import { createReadStream, closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, writeFileSync, writeSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { HOME_ACTIVITY, app, fakeApk, filter, type FakeEl } from '@glassworks/app-adb/fakes';
import { Disk, Ext4, MemoryStore, Sha256, hex, readSparse, writeSparse, type Store } from '@glassworks/ext4';
import { clean, cleanup, debugfs, debugfsCat, dump, fsck, haveTools, load, mke2fs, scratch } from '@glassworks/ext4/testing';
import { axmlAttribute, zipEntry } from '@glassworks/app-adb';
import { APP_DIR, GLASS_PANES, SETTINGS_OVERLAY, buildUserdata, checkMirrorApk, patchSystem, setProp, wpaPsk, wpaSupplicantConf } from '../src';

const T = 1_700_000_000;
const MAKE_EXT4FS = ['-O', '^metadata_csum,^64bit,^flex_bg,^dir_index,^huge_file,^dir_nlink,^extra_isize,uninit_bg,resize_inode'];
const libs = { 'arm64-v8a': { 'libxul.so': new Uint8Array(randomBytes(300_000)), 'libmozglue.so': new Uint8Array(randomBytes(20_000)) }, 'armeabi-v7a': { 'libxul.so': new Uint8Array(10) } };
const APK = fakeApk('sh.edm.s1kiosk', { libs });
const ANIM = new Uint8Array(randomBytes(50_000));
/** frames/lululemon-mirror/overlay/GlassworksSettings.apk, as build.sh writes it */
const OVERLAY = new Uint8Array(readFileSync(join(__dirname, '../../overlay/GlassworksSettings.apk')));
const TEMPLATE = 'update_config=1\neapol_version=1\nap_scan=1\nfast_reauth=1\npmf=1\np2p_no_group_iface=1\n';

describe('checkMirrorApk: the APK the Mirror can run as its only screen', () => {
  const act = (filters: FakeEl[], attrs: FakeEl['attrs'] = {}): FakeEl => ({ el: 'activity', attrs: { name: '.MainActivity', ...attrs }, children: filters });
  it('takes S1 Kiosk with MAIN, HOME and DEFAULT in one filter', async () => {
    await expect(checkMirrorApk(fakeApk('sh.edm.s1kiosk', { minSdk: 23 }))).resolves.toMatchObject({ package: 'sh.edm.s1kiosk' });
  });
  it.each([
    ['HOME without DEFAULT (am start -c HOME resolves with MATCH_DEFAULT_ONLY)', app(act([filter(['MAIN'], ['HOME'])])), /no activity with MAIN, HOME and DEFAULT in one intent filter/],
    ['MAIN+HOME and MAIN+DEFAULT in separate filters', app(act([filter(['MAIN'], ['HOME']), filter(['MAIN'], ['DEFAULT'])])), /no activity with MAIN, HOME and DEFAULT/],
    ['a launcher activity, no HOME', app(act([filter(['MAIN'], ['LAUNCHER', 'DEFAULT'])])), /no activity with MAIN, HOME and DEFAULT/],
    ['MAIN, HOME and DEFAULT with a <data> scheme (the HOME intent carries no data)', app(act([{ el: 'intent-filter', children: [...filter(['MAIN'], ['HOME', 'DEFAULT']).children!, { el: 'data', attrs: { scheme: 'example' } }] }])), /no activity with MAIN, HOME and DEFAULT/],
    ['its application turned off', app(HOME_ACTIVITY(), { enabled: false }), /its home screen \(sh\.edm\.s1kiosk\.MainActivity\) is turned off in its manifest/],
    ['enabled from a resource', app(HOME_ACTIVITY({ enabled: { ref: 0x7f040001 } })), /depends on a resource Kiln doesn't read/],
  ])('refuses %s, with nothing changed', async (_, home, msg) => {
    await expect(checkMirrorApk(fakeApk('sh.edm.s1kiosk', { minSdk: 23, home }))).rejects.toThrow(msg);
    await expect(checkMirrorApk(fakeApk('sh.edm.s1kiosk', { minSdk: 23, home }))).rejects.toThrow(/Nothing was changed\.$/);
  });
  it('says a manifest it can\'t parse can\'t be read, never a RangeError', async () => {
    const apk = fakeApk('sh.edm.s1kiosk', { minSdk: 23 });
    const at = new TextDecoder('latin1').decode(apk).indexOf('AndroidManifest.xml') + 'AndroidManifest.xml'.length;
    new DataView(apk.buffer, apk.byteOffset).setUint32(at + 16, 0x00ffffff, true); // its string pool claims 16M strings
    await expect(checkMirrorApk(apk)).rejects.toThrow(/^this APK's AndroidManifest\.xml can't be read\. Nothing was changed\.$/);
  });
});

describe('setProp', () => {
  it('replaces every earlier assignment and keeps the rest', () => {
    expect(setProp('a=1\nro.sf.hwrotation=90\nb=2\n', 'ro.sf.hwrotation', '270')).toBe('a=1\nb=2\nro.sf.hwrotation=270\n');
    expect(setProp('a=1', 'x', 'y')).toBe('a=1\nx=y\n');
  });
});

describe('a first boot with nothing to tap: the settings overlay and the saved Wi-Fi', () => {
  it('the committed overlay is the pinned build, for SettingsProvider, with its five defaults', async () => {
    const sha = hex(new Sha256().update(OVERLAY).digest());
    expect(sha, 'rebuild with frames/lululemon-mirror/overlay/build.sh and update SETTINGS_OVERLAY.sha256').toBe(SETTINGS_OVERLAY.sha256);
    const manifest = (await zipEntry(OVERLAY, 'AndroidManifest.xml'))!;
    expect(axmlAttribute(manifest, 'manifest', 'package')).toBe('dev.glassworks.mirror.settings');
    expect(axmlAttribute(manifest, 'overlay', 'targetPackage')).toBe('com.android.providers.settings');
    expect(await zipEntry(OVERLAY, 'classes.dex')).toBeUndefined(); // resources only
    expect(await zipEntry(OVERLAY, 'META-INF/TESTKEY.RSA')).toBeDefined(); // v1-signed with AOSP's public test key (Android 6 reads no v2)
  });

  it('stores the WPA passphrase as its PSK (IEEE 802.11i test vectors), never the password itself', async () => {
    const enc = (s: string) => new TextEncoder().encode(s);
    expect(await wpaPsk('password', enc('IEEE'))).toBe('f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e');
    expect(await wpaPsk('ThisIsAPassword', enc('ThisIsASSID'))).toBe('0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af');
    const conf = await wpaSupplicantConf(TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' });
    expect(conf.startsWith(TEMPLATE)).toBe(true);
    expect(conf).toMatch(/\nnetwork=\{\n\tssid="Home Net"\n\tpsk=[0-9a-f]{64}\n\tkey_mgmt=WPA-PSK\n\tpriority=1\n\}\n$/);
    expect(conf).not.toContain('not-a-real-password');
  });

  it('writes an awkward SSID as hex, an open network without a key, and refuses a password WPA can\'t take', async () => {
    expect(await wpaSupplicantConf(TEMPLATE, { ssid: 'Café "5G"', password: '' })).toContain('\tssid=436166c3a92022354722\n\tkey_mgmt=NONE\n');
    expect(await wpaSupplicantConf(TEMPLATE, { ssid: 'x', password: 'b'.repeat(64) })).toContain(`\tpsk=${'b'.repeat(64)}\n`);
    for (const password of ['short', 'x'.repeat(64), 'tab\there!!']) await expect(wpaSupplicantConf(TEMPLATE, { ssid: 'x', password })).rejects.toThrow(/8 to 63/);
    await expect(wpaSupplicantConf(TEMPLATE, { ssid: '', password: '' })).rejects.toThrow(/1 to 32 bytes/);
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
    await patchSystem(fs, { apk: APK, bootAnimation: ANIM, hwrotation: 270, settingsOverlay: OVERLAY, time: T }, (l) => log.push(l));
    const after = { free: fs.freeBlocks, inodes: fs.freeInodes };
    await patchSystem(await Ext4.open(disk), { apk: APK, bootAnimation: ANIM, hwrotation: 270, settingsOverlay: OVERLAY, time: T }); // again: the same result
    const again = await Ext4.open(disk);
    expect({ free: again.freeBlocks, inodes: again.freeInodes }).toEqual(after);
    await dump(disk, img);

    const r = fsck(img);
    expect(clean(r), r.out).toBe(true);
    expect(Buffer.from(debugfsCat(img, `${APP_DIR}/S1Kiosk.apk`))).toEqual(Buffer.from(APK));
    expect(Buffer.from(debugfsCat(img, `${APP_DIR}/lib/arm64/libxul.so`))).toEqual(Buffer.from(libs['arm64-v8a']['libxul.so']));
    expect(debugfs(img, `ls ${APP_DIR}/lib`)).not.toContain('arm '); // only the primary ABI's
    expect(Buffer.from(debugfsCat(img, '/media/bootanimation.zip'))).toEqual(Buffer.from(ANIM));
    const prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    expect(prop.match(/ro\.sf\.hwrotation=/g)).toHaveLength(1);
    expect(prop).toMatch(/\nro\.lockscreen\.disable\.default=true\nro\.sf\.hwrotation=270\n$/);
    expect(Buffer.from(debugfsCat(img, SETTINGS_OVERLAY.path))).toEqual(Buffer.from(OVERLAY));
    for (const p of [APP_DIR, `${APP_DIR}/lib/arm64`, '/vendor/overlay']) {
      expect(debugfs(img, `stat ${p}`)).toMatch(/Mode:\s+0755[\s\S]*User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:system_file:s0');
    }
    for (const p of [`${APP_DIR}/S1Kiosk.apk`, `${APP_DIR}/lib/arm64/libmozglue.so`, '/media/bootanimation.zip', SETTINGS_OVERLAY.path]) {
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
    // no home screen Android can open: the Mirror would start to nothing (its first real flash, 2026-11-16)
    await expect(patchSystem(await Ext4.open(disk2), { apk: fakeApk('sh.edm.s1kiosk', { minSdk: 23, home: 'off' }) })).rejects.toThrow(/can't be the Mirror's home screen: its home screen \(sh\.edm\.s1kiosk\.MainActivity\) is turned off in its manifest.*Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { apk: fakeApk('sh.edm.s1kiosk', { minSdk: 23, home: 'none' }) })).rejects.toThrow(/it has no home screen \(no activity with MAIN, HOME and DEFAULT in one intent filter\)/);
    const tampered = OVERLAY.slice(); tampered[100]! ^= 1;
    await expect(patchSystem(await Ext4.open(disk2), { apk: APK, settingsOverlay: tampered })).rejects.toThrow(/settings overlay isn't the one Kiln builds.*Nothing was changed/);
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
    expect(clean(r), r.out).toBe(true);
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

  it('saves the person\'s Wi-Fi where Android 6\'s Wi-Fi expects it, owned and labelled as init and file_contexts make it', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 5000);
    const key = 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks';
    await buildUserdata(disk, { adbKey: key, time: T, wifi: { ssid: 'Home Net', password: 'not-a-real-password', template: TEMPLATE } });
    const img = join(dir, 'userdata-wifi.img');
    await dump(disk, img);
    const r = fsck(img);
    expect(clean(r), r.out).toBe(true);
    const conf = new TextDecoder().decode(debugfsCat(img, '/misc/wifi/wpa_supplicant.conf'));
    expect(conf).toBe(await wpaSupplicantConf(TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' }));
    expect(conf).not.toContain('not-a-real-password');
    expect(debugfs(img, 'stat /misc/wifi')).toMatch(/Mode:\s+0770[\s\S]*User:\s+1010\s+Group:\s+1010/);
    expect(debugfs(img, 'stat /misc/wifi/wpa_supplicant.conf')).toMatch(/Mode:\s+0660[\s\S]*User:\s+1000\s+Group:\s+1010/);
    for (const p of ['/misc/wifi', '/misc/wifi/wpa_supplicant.conf']) expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:wifi_data_file:s0');
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
      // every question e2fsck -n asks (Fix?, Clear?, Salvage?, …) answers itself "no", and every ignored checksum: compare them all
      const problems = (out: string) => out.split('\n').filter((l) => /\?\s+(no|yes)\s*$/.test(l) || /Fix\?|IGNORED|checksum is/i.test(l));
      const baseline = problems(fsck(join(dir, 'original.raw')).out);

      const real = await Ext4.open(disk);
      // the template buildImages puts the saved network after (read 2026-11-16 from image 2)
      expect(new TextDecoder().decode(await real.readFile('/etc/wifi/wpa_supplicant.conf'))).toBe(TEMPLATE);
      expect((await real.readdir('/vendor')).map((e) => e.name)).not.toContain('overlay'); // ours is the only overlay
      await patchSystem(real, { apk: APK, bootAnimation: ANIM, hwrotation: 270, settingsOverlay: OVERLAY });
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
      expect(problems(r.out)).toEqual(baseline); // the image's own make_ext4fs quirk, nothing new
      const img = join(dir, 'flashed.raw');
      expect(Buffer.from(debugfsCat(img, `${APP_DIR}/S1Kiosk.apk`))).toEqual(Buffer.from(APK));
      expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).toMatch(/\nro\.lockscreen\.disable\.default=true\nro\.sf\.hwrotation=270\n$/);
      expect(Buffer.from(debugfsCat(img, SETTINGS_OVERLAY.path))).toEqual(Buffer.from(OVERLAY));
      expect(debugfs(img, 'ls /app')).toContain('Provision');
    } finally { cleanup(dir); }
  }, 600_000);
});
