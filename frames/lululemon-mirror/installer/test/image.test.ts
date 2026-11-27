// The image patcher on make_ext4fs-style images (as the Glass Panes system.img is), judged by
// e2fsprogs. With GW_MIRROR_SYSTEM_IMG=<path to the real system.img> it also patches the real image
// (sparse in, sparse out, then "flashed" onto junk) and checks it the same way, with a fake APK, or with
// GW_MIRROR_APK=<a real legacy arm64 APK> that one, so the image's room for the real app is checked too. That file is the
// person's download: never commit it, and nothing in CI has it.
import { createReadStream, closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, writeFileSync, writeSync } from 'node:fs';
import { join } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { HOME_ACTIVITY, app, fakeApk, filter, zip, type FakeEl } from '@glassworks/app-adb/fakes';
import { Disk, Ext4, MemoryStore, Sha256, hex, readSparse, writeSparse, type Store } from '@glassworks/ext4';
import { clean, cleanup, debugfs, debugfsCat, dump, fsck, haveTools, load, mke2fs, scratch } from '@glassworks/ext4/testing';
import { axmlAttribute, zipEntry } from '@glassworks/app-adb';
import { GLASS_PANES, GLASS_PANES_KEY_FINGERPRINT, IMAGE_KIOSK, IMAGE_OWNER_FINGERPRINT, GLASSWORKS, KEPT_APPS, UNUSED_APPS, MIRROR_PROBE, PRIVILEGED, FRAMEWORK_OVERLAY, SETTINGS_OVERLAY, adbFingerprint, adbKeyLines, buildUserdata, checkMirrorApk, patchSystem, setProp, wpaPsk, wpaSupplicantConf } from '../src';
import { API_KEY_HANDOVER_PROP, DEVICE_MAC_PROP, MIRROR_FRAME } from '../src/image';
import { fakeAdbKey, fakeServicesJar } from './fakes';
import { DEXDUMP, disassemble } from './dexdump';

const PKG = GLASSWORKS.package, DIR = GLASSWORKS.dir;
const PERMS: FakeEl[] = PRIVILEGED.map((name) => ({ el: 'uses-permission', attrs: { name } }));
/** fakeApk with Glassworks' id and the privileged permissions it must ask for; `home` as fakeApk's */
function mirrorApk(o: Parameters<typeof fakeApk>[1] = {}): Uint8Array {
  const h = o.home ?? 'on';
  const tree = Array.isArray(h) ? h : h === 'none' ? [] : app(HOME_ACTIVITY(h === 'off' ? { enabled: false } : {}));
  return fakeApk(PKG, { ...o, home: [...PERMS, ...tree] });
}

const T = 1_700_000_000;
const MAC = 'E6:79:E6:E0:96:A3'; // made up: the id core's DeviceMacPinTest derives from its made-up serial
const MAKE_EXT4FS = ['-O', '^metadata_csum,^64bit,^flex_bg,^dir_index,^huge_file,^dir_nlink,^extra_isize,uninit_bg,resize_inode'];
const libs = { 'arm64-v8a': { 'libxul.so': new Uint8Array(randomBytes(300_000)), 'libmozglue.so': new Uint8Array(randomBytes(20_000)) }, 'armeabi-v7a': { 'libxul.so': new Uint8Array(10) } };
const APK = mirrorApk({ libs });
const ANIM = new Uint8Array(randomBytes(50_000));
/** frames/lululemon-mirror/overlay/GlassworksSettings.apk, as build.sh writes it */
const OVERLAY = new Uint8Array(readFileSync(join(__dirname, '../../overlay/GlassworksSettings.apk')));
/** frames/lululemon-mirror/overlay/GlassworksFramework.apk, as build.sh writes it */
const FRAMEWORK = new Uint8Array(readFileSync(join(__dirname, '../../overlay/GlassworksFramework.apk')));
/** the installer's key: the one the patched services.jar lets in */
const KEY = fakeAdbKey(11);
const TEMPLATE = 'update_config=1\neapol_version=1\nap_scan=1\nfast_reauth=1\npmf=1\np2p_no_group_iface=1\n';

describe('checkMirrorApk: the APK the Mirror can run as its only screen', () => {
  const act = (filters: FakeEl[], attrs: FakeEl['attrs'] = {}): FakeEl => ({ el: 'activity', attrs: { name: '.MainActivity', ...attrs }, children: filters });
  it('takes Glassworks with MAIN, HOME and DEFAULT in one filter and the privileged permissions', async () => {
    await expect(checkMirrorApk(mirrorApk({ minSdk: 23 }))).resolves.toMatchObject({ package: PKG });
  });
  it.each([
    ['HOME without DEFAULT (am start -c HOME resolves with MATCH_DEFAULT_ONLY)', app(act([filter(['MAIN'], ['HOME'])])), /no activity with MAIN, HOME and DEFAULT in one intent filter/],
    ['MAIN+HOME and MAIN+DEFAULT in separate filters', app(act([filter(['MAIN'], ['HOME']), filter(['MAIN'], ['DEFAULT'])])), /no activity with MAIN, HOME and DEFAULT/],
    ['a launcher activity, no HOME', app(act([filter(['MAIN'], ['LAUNCHER', 'DEFAULT'])])), /no activity with MAIN, HOME and DEFAULT/],
    ['MAIN, HOME and DEFAULT with a <data> scheme (the HOME intent carries no data)', app(act([{ el: 'intent-filter', children: [...filter(['MAIN'], ['HOME', 'DEFAULT']).children!, { el: 'data', attrs: { scheme: 'example' } }] }])), /no activity with MAIN, HOME and DEFAULT/],
    ['its application turned off', app(HOME_ACTIVITY(), { enabled: false }), /its home screen \(dev\.glassworks\.generic_android\.legacy\.MainActivity\) is turned off in its manifest/],
    ['enabled from a resource', app(HOME_ACTIVITY({ enabled: { ref: 0x7f040001 } })), /depends on a resource Kiln doesn't read/],
  ])('refuses %s, with nothing changed', async (_, home, msg) => {
    await expect(checkMirrorApk(mirrorApk({ minSdk: 23, home }))).rejects.toThrow(msg);
    await expect(checkMirrorApk(mirrorApk({ minSdk: 23, home }))).rejects.toThrow(/Nothing was changed\.$/);
  });
  it.each(PRIVILEGED)('refuses an APK that doesn\'t ask for %s: an update could never add it later', async (perm) => {
    const home = [...PERMS.filter((e) => e.attrs!.name !== perm), ...app(HOME_ACTIVITY())];
    await expect(checkMirrorApk(fakeApk(PKG, { minSdk: 23, home }))).rejects.toThrow(
      new RegExp(`doesn't ask for ${perm.replace('android.permission.', '')}\\. Android 6 grants a privileged permission only to what the copy in /system asks for.*Nothing was changed\\.$`));
  });
  it('refuses a privileged permission Android 6 wouldn\'t grant (maxSdkVersion below 23) (#411 review)', async () => {
    const home = [...PERMS.map((e) => e.attrs!.name === 'android.permission.REBOOT' ? { ...e, attrs: { ...e.attrs, maxSdkVersion: '22' } } : e), ...app(HOME_ACTIVITY())];
    await expect(checkMirrorApk(fakeApk(PKG, { minSdk: 23, home }))).rejects.toThrow(/doesn't ask for REBOOT\./);
  });
  it('refuses the S1 app\'s id, and takes the probe\'s only as the probe (MIRROR_PROBE)', async () => {
    const s1 = fakeApk('sh.edm.s1kiosk', { minSdk: 23, home: [...PERMS, ...app(HOME_ACTIVITY())] });
    await expect(checkMirrorApk(s1)).rejects.toThrow(/this APK is sh\.edm\.s1kiosk, not Glassworks \(dev\.glassworks\.generic_android\.legacy\)/);
    await expect(checkMirrorApk(s1, MIRROR_PROBE)).rejects.toThrow(/not the Mirror probe \(dev\.glassworks\.mirror\.probe\)/);
    const probe = fakeApk('dev.glassworks.mirror.probe', { minSdk: 23, home: [...PERMS, ...app(HOME_ACTIVITY())] });
    await expect(checkMirrorApk(probe, MIRROR_PROBE)).resolves.toMatchObject({ package: 'dev.glassworks.mirror.probe' });
    await expect(checkMirrorApk(probe)).rejects.toThrow(/this APK is dev\.glassworks\.mirror\.probe, not Glassworks/);
    await expect(checkMirrorApk(mirrorApk({ minSdk: 23 }), MIRROR_PROBE)).rejects.toThrow(/not the Mirror probe \(dev\.glassworks\.mirror\.probe\)/);
  });
  it('says a manifest it can\'t parse can\'t be read, never a RangeError', async () => {
    const apk = mirrorApk({ minSdk: 23 });
    const at = new TextDecoder('latin1').decode(apk).indexOf('AndroidManifest.xml') + 'AndroidManifest.xml'.length;
    new DataView(apk.buffer, apk.byteOffset).setUint32(at + 16, 0x00ffffff, true); // its string pool claims 16M strings
    await expect(checkMirrorApk(apk)).rejects.toThrow(/^this APK's AndroidManifest\.xml can't be read\. Nothing was changed\.$/);
  });
  it('says a manifest that isn\'t binary XML isn\'t, with nothing changed (#385 delta verifier)', async () => {
    const apk = zip([['AndroidManifest.xml', new TextEncoder().encode('<xml')]]);
    await expect(checkMirrorApk(apk)).rejects.toThrow(/^this APK's AndroidManifest\.xml isn't binary XML\. Nothing was changed\.$/);
  });
  it('names an activity whose android:name is a resource plainly, not [object Object] (#385 delta verifier)', async () => {
    const home = app({ ...HOME_ACTIVITY(), attrs: { name: { ref: 0x7f0a0001 } } }, { enabled: false });
    await expect(checkMirrorApk(mirrorApk({ minSdk: 23, home }))).rejects.toThrow(/its home screen \(an activity named by a resource\) is turned off/);
  });
});

describe('UNUSED_APPS: the image\'s apps Kiln leaves out', () => {
  it('names each app once, in its own folder under /app or /priv-app, and never one that must stay', () => {
    const pkgs = UNUSED_APPS.map((u) => u.package), dirs = UNUSED_APPS.map((u) => u.dir);
    expect(new Set(pkgs).size).toBe(pkgs.length);
    expect(new Set(dirs).size).toBe(dirs.length);
    for (const d of dirs) expect(d).toMatch(/^\/(app|priv-app)\/[A-Za-z0-9_.-]+$/);
    expect(pkgs.filter((p) => KEPT_APPS.includes(p))).toEqual([]);
    expect(dirs).not.toContain(GLASSWORKS.dir);
  });
});

describe('MIRROR_FRAME', () => {
  it('is the frame\'s id and a SemVer version, as the app reads them (#139)', () => {
    const frame = JSON.parse(readFileSync(new URL('../../design/frame.json', import.meta.url), 'utf8')) as { id: string };
    expect(MIRROR_FRAME.id).toBe(frame.id);
    expect(MIRROR_FRAME.version).toMatch(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$/);
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

  it('the committed framework overlay is the pinned build, for "android", with config_screenBrightnessSettingMinimum 0', async () => {
    const sha = hex(new Sha256().update(FRAMEWORK).digest());
    expect(sha, 'rebuild with frames/lululemon-mirror/overlay/build.sh and update FRAMEWORK_OVERLAY.sha256').toBe(FRAMEWORK_OVERLAY.sha256);
    const manifest = (await zipEntry(FRAMEWORK, 'AndroidManifest.xml'))!;
    expect(axmlAttribute(manifest, 'manifest', 'package')).toBe('dev.glassworks.mirror.framework');
    expect(axmlAttribute(manifest, 'overlay', 'targetPackage')).toBe('android');
    expect(await zipEntry(FRAMEWORK, 'classes.dex')).toBeUndefined(); // resources only
    expect(await zipEntry(FRAMEWORK, 'META-INF/TESTKEY.RSA')).toBeDefined();
    // one resource: the name idmap matches against framework-res, and its value as a Res_value
    // (size 8, res0 0, TYPE_INT_DEC 0x10, data 0), as `aapt2 dump resources` shows it
    const arsc = Buffer.from((await zipEntry(FRAMEWORK, 'resources.arsc'))!);
    expect(arsc.includes('config_screenBrightnessSettingMinimum')).toBe(true);
    expect(arsc.includes(Buffer.from('integer', 'utf16le'))).toBe(true); // the type pool is UTF-16
    const value = Buffer.from([8, 0, 0, 0x10, 0, 0, 0, 0]);
    expect(arsc.indexOf(value)).toBeGreaterThan(0);
    expect(arsc.indexOf(value, arsc.indexOf(value) + 1)).toBe(-1);
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
  let jar: Uint8Array;
  beforeAll(async () => { dir = scratch(); jar = await fakeServicesJar(); });
  afterAll(() => cleanup(dir));

  function systemTree(fingerprint: string = GLASS_PANES.fingerprint, services: Uint8Array | null = jar): string {
    const root = join(dir, `sys-${Math.random().toString(36).slice(2)}`);
    for (const d of ['priv-app/Settings', 'app/Provision', 'media/audio', 'framework', 'app/Stk', 'priv-app/Dialer', 'priv-app/TeleService', 'priv-app/MirrorKiosk/lib/arm64']) mkdirSync(join(root, d), { recursive: true });
    // image 3's own kiosk app, as it builds it in: the APK and its unpacked libraries
    for (const a of ['app/Stk/Stk.apk', 'priv-app/Dialer/Dialer.apk', 'priv-app/TeleService/TeleService.apk', 'priv-app/MirrorKiosk/MirrorKiosk.apk', 'priv-app/MirrorKiosk/lib/arm64/libxul.so']) writeFileSync(join(root, a), randomBytes(20_000));
    mkdirSync(join(root, 'priv-app/Dialer/oat/arm64'), { recursive: true });
    writeFileSync(join(root, 'priv-app/Dialer/oat/arm64/Dialer.odex'), randomBytes(30_000));
    if (services) writeFileSync(join(root, 'framework/services.jar'), services);
    writeFileSync(join(root, 'build.prop'), `ro.build.fingerprint=${fingerprint}\npersist.sys.usb.config=diag,serial_smd,rmnet_bam,adb\n`);
    writeFileSync(join(root, 'priv-app/Settings/Settings.apk'), randomBytes(100_000));
    return root;
  }

  it('adds Glassworks as a privileged app with its libraries and the boot animation, and no ro.sf.hwrotation', async () => {
    const img = join(dir, 'system.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const owner = (out: string) => out.match(/Mode:\s+\d+|User:\s+\d+\s+Group:\s+\d+/g);
    const jarOwner = owner(debugfs(img, 'stat /framework/services.jar'));
    const disk = await load(img);
    const fs = await Ext4.open(disk);
    await fs.setLabel('/framework/services.jar', 'u:object_r:system_file:s0');
    const log: string[] = [];
    await patchSystem(fs, { adbKey: KEY, apk: APK, bootAnimation: ANIM, settingsOverlay: OVERLAY, frameworkOverlay: FRAMEWORK, deviceMac: MAC, time: T }, (l) => log.push(l));
    const after = { free: fs.freeBlocks, inodes: fs.freeInodes };
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, bootAnimation: ANIM, settingsOverlay: OVERLAY, frameworkOverlay: FRAMEWORK, deviceMac: MAC, time: T }); // again: the same result
    const again = await Ext4.open(disk);
    expect({ free: again.freeBlocks, inodes: again.freeInodes }).toEqual(after);
    await dump(disk, img);

    const r = fsck(img);
    expect(clean(r), r.out).toBe(true);
    expect(Buffer.from(debugfsCat(img, `${DIR}/Glassworks.apk`))).toEqual(Buffer.from(APK));
    expect(Buffer.from(debugfsCat(img, `${DIR}/lib/arm64/libxul.so`))).toEqual(Buffer.from(libs['arm64-v8a']['libxul.so']));
    expect(debugfs(img, `ls ${DIR}/lib`)).not.toContain('arm '); // only the primary ABI's
    expect(Buffer.from(debugfsCat(img, '/media/bootanimation.zip'))).toEqual(Buffer.from(ANIM));
    const prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    // nothing in the image reads ro.sf.hwrotation (2026-11-17): the first boot sets the rotation instead
    expect(prop).not.toContain('ro.sf.hwrotation');
    expect(prop).toMatch(/\nro\.lockscreen\.disable\.default=true\n$/);
    // the frame version the app shows (#139), once, after the second run too
    expect(prop.match(/^ro\.glassworks\.frame\.[a-z]+=.*$/gm)).toEqual(['ro.glassworks.frame.id=lululemon-mirror', `ro.glassworks.frame.version=${MIRROR_FRAME.version}`]);
    expect(log).toContain(`build.prop: ro.glassworks.frame.version=${MIRROR_FRAME.version}, ${DEVICE_MAC_PROP}, ro.lockscreen.disable.default=true`);
    // #523: the id the app takes when its data is new, once; the log names the key, never the device's value
    expect(prop.match(/^ro\.glassworks\.device\.[a-z]+=.*$/gm)).toEqual([`${DEVICE_MAC_PROP}=${MAC}`]);
    expect(log.join('\n')).not.toContain(MAC);
    expect(Buffer.from(debugfsCat(img, SETTINGS_OVERLAY.path))).toEqual(Buffer.from(OVERLAY));
    expect(Buffer.from(debugfsCat(img, FRAMEWORK_OVERLAY.path))).toEqual(Buffer.from(FRAMEWORK));
    expect(log).toContain(FRAMEWORK_OVERLAY.path);
    for (const p of [DIR, `${DIR}/lib/arm64`, '/vendor/overlay']) {
      expect(debugfs(img, `stat ${p}`)).toMatch(/Mode:\s+0755[\s\S]*User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:system_file:s0');
    }
    for (const p of [`${DIR}/Glassworks.apk`, `${DIR}/lib/arm64/libmozglue.so`, '/media/bootanimation.zip', SETTINGS_OVERLAY.path, FRAMEWORK_OVERLAY.path]) {
      expect(debugfs(img, `stat ${p}`)).toMatch(/Mode:\s+0644[\s\S]*User:\s+0\s+Group:\s+0/);
      expect(debugfs(img, `ea_get ${p} security.selinux`)).toContain('u:object_r:system_file:s0');
    }
    expect(log).toContain('2 native libraries for arm64-v8a in /priv-app/Glassworks/lib/arm64');
    // the unused apps are gone, folders and all; the phone stack stays
    expect(log).toContain('left out 2 unused apps: com.android.dialer, com.android.stk');
    // the image's own kiosk goes too, folder and libraries: Glassworks is the Mirror's app
    expect(log).toContain(`left out the image's own kiosk app: ${IMAGE_KIOSK.package}`);
    expect(debugfs(img, 'ls /priv-app')).not.toContain('MirrorKiosk');
    expect(debugfs(img, 'ls /app')).not.toContain('Stk');
    expect(debugfs(img, 'ls /priv-app')).not.toContain('Dialer');
    expect(debugfs(img, 'ls /priv-app')).toContain('TeleService');
    // the image lets in this key without asking, not the Glass Panes project key; the jar keeps its owner, mode and label
    const services = debugfsCat(img, '/framework/services.jar');
    const dex = Buffer.from((await zipEntry(new Uint8Array(services), 'classes.dex'))!).toString('latin1');
    expect(dex).toContain(adbFingerprint(KEY));
    expect(dex).not.toContain(IMAGE_OWNER_FINGERPRINT);
    expect(owner(debugfs(img, 'stat /framework/services.jar'))).toEqual(jarOwner);
    expect(debugfs(img, 'ea_get /framework/services.jar security.selinux')).toContain('u:object_r:system_file:s0');
    expect(log).toContain('/framework/services.jar: lets in this adb key without asking, not the image\'s own');
  }, 60_000);

  it('leaves out the image\'s kiosk and the unused apps before the app goes in: image 3 has room for Glassworks only after', async () => {
    // image 3's own kiosk took 246 MB of its 1.2 GB, and the app's libraries didn't fit beside it (Mirror run 2026-11-23)
    const root = systemTree();
    writeFileSync(join(root, 'priv-app/MirrorKiosk/lib/arm64/libbig.so'), randomBytes(2_000_000));
    const img = join(dir, 'system-full.img');
    mke2fs(img, 64, MAKE_EXT4FS, root);
    const disk = await load(img);
    const fs = await Ext4.open(disk);
    // fill the image until the app no longer fits beside the kiosk (the APK and its libraries need about 165 blocks)
    await fs.writeFile('/filler', new Uint8Array((fs.freeBlocks - 120) * 4096), { mode: 0o644, uid: 0, gid: 0, label: 'u:object_r:system_file:s0', time: T });
    expect(fs.freeBlocks).toBeLessThanOrEqual(120);
    await patchSystem(fs, { adbKey: KEY, apk: APK, time: T });
    await dump(disk, img);
    const r = fsck(img);
    expect(clean(r), r.out).toBe(true);
    expect(Buffer.from(debugfsCat(img, `${DIR}/lib/arm64/libxul.so`))).toEqual(Buffer.from(libs['arm64-v8a']['libxul.so']));
    expect(debugfs(img, 'ls /priv-app')).not.toContain('MirrorKiosk');
  });

  it('refuses, with nothing written, an image without services.jar or one Kiln can\'t retarget, and the project key', async () => {
    for (const [services, msg] of [[null, /has no \/system\/framework\/services\.jar/], [await fakeServicesJar({ dex: new TextEncoder().encode('dex\n035\0') }), /isn't dex 035|cut short/]] as const) {
      const img = join(dir, `nojar-${Math.random().toString(36).slice(2)}.img`);
      mke2fs(img, 32, MAKE_EXT4FS, systemTree(undefined, services));
      const disk = await load(img);
      const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
      await expect(patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, bootAnimation: ANIM, time: T })).rejects.toThrow(msg);
      expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
    }
    const img = join(dir, 'badkey.img');
    mke2fs(img, 32, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    await expect(patchSystem(await Ext4.open(disk), { adbKey: 'QAAA= kiln@glassworks', apk: APK, time: T })).rejects.toThrow(/isn't an adb public key.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
  }, 60_000);

  it('refuses another image or another APK, with nothing written', async () => {
    const img = join(dir, 'other.img');
    mke2fs(img, 32, MAKE_EXT4FS, systemTree('Android/other/other:6.0.1/X/1:user/release-keys'));
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    await expect(patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK })).rejects.toThrow(/isn't the Glass Panes Mirror image.*Nothing was changed/);
    const img2 = join(dir, 'right.img');
    mke2fs(img2, 32, MAKE_EXT4FS, systemTree());
    const disk2 = await load(img2);
    const before2 = hex(new Sha256().update(await disk2.read(0, disk2.blocks)).digest());
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: fakeApk('com.nimrod.mirrorkiosk') })).rejects.toThrow(/not Glassworks.*Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: mirrorApk({ minSdk: 26 }) })).rejects.toThrow(/needs Android API 26.*pick the legacy build of Glassworks for generic Android\. Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: mirrorApk({ minSdk: 23, libs: { x86_64: { 'libxul.so': new Uint8Array(4) } } }) })).rejects.toThrow(/native code only for x86_64.*Nothing was changed/);
    // no home screen Android can open: the Mirror would start to nothing (its first real flash, 2026-11-16)
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: mirrorApk({ minSdk: 23, home: 'off' }) })).rejects.toThrow(/can't be the Mirror's home screen: its home screen \(dev\.glassworks\.generic_android\.legacy\.MainActivity\) is turned off in its manifest.*Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: mirrorApk({ minSdk: 23, home: 'none' }) })).rejects.toThrow(/it has no home screen \(no activity with MAIN, HOME and DEFAULT in one intent filter\)/);
    const tampered = OVERLAY.slice(); tampered[100]! ^= 1;
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: APK, settingsOverlay: tampered })).rejects.toThrow(/settings overlay isn't the one Kiln builds.*Nothing was changed/);
    const tamperedFramework = FRAMEWORK.slice(); tamperedFramework[100]! ^= 1;
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: APK, frameworkOverlay: tamperedFramework })).rejects.toThrow(/framework overlay isn't the one Kiln builds.*Nothing was changed/);
    await expect(patchSystem(await Ext4.open(disk2), { adbKey: KEY, apk: APK, deviceMac: 'e6:79:e6:e0:96:a3' })).rejects.toThrow(/device id isn't a MAC.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
    expect(hex(new Sha256().update(await disk2.read(0, disk2.blocks)).digest())).toBe(before2);
  }, 60_000);

  it('drops an earlier build\'s device id when the next build has none: a rebuild may be for another Mirror (#560 review)', async () => {
    const img = join(dir, 'rebuild.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, deviceMac: MAC, time: T });
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, time: T });
    await dump(disk, img);
    expect(fsck(img).code).toBe(0);
    const prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    expect(prop).not.toContain(DEVICE_MAC_PROP);
    expect(prop).toContain(`\nro.glassworks.frame.version=${MIRROR_FRAME.version}\n`);
  });

  it('flags a carried API key in build.prop, never the key, and a rebuild without one drops the flag (#574)', async () => {
    const img = join(dir, 'apikey.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const log: string[] = [];
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, deviceMac: MAC, apiKeyHandover: true, time: T }, (l) => log.push(l));
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, deviceMac: MAC, apiKeyHandover: true, time: T }); // again: once
    await dump(disk, img);
    expect(fsck(img).code).toBe(0);
    let prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    expect(prop.match(/^ro\.glassworks\.api_key\.[a-z]+=.*$/gm)).toEqual([`${API_KEY_HANDOVER_PROP}=1`]);
    expect(log).toContain(`build.prop: ro.glassworks.frame.version=${MIRROR_FRAME.version}, ${DEVICE_MAC_PROP}, ${API_KEY_HANDOVER_PROP}=1`);

    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, deviceMac: MAC, time: T });
    await dump(disk, img);
    prop = new TextDecoder().decode(debugfsCat(img, '/build.prop'));
    expect(prop).not.toContain(API_KEY_HANDOVER_PROP);
    expect(prop).toContain(`\n${DEVICE_MAC_PROP}=${MAC}\n`);
  });

  it('takes the ABI that has libraries, not an empty lib/<abi>/ folder before it (#354 review)', async () => {
    const img = join(dir, 'abi.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const v7 = new Uint8Array([7, 7, 7]);
    // a directory entry for arm64-v8a, the real library under armeabi-v7a
    const apk = mirrorApk({ minSdk: 23, libs: { 'arm64-v8a': { '': new Uint8Array(0) }, 'armeabi-v7a': { 'libxul.so': v7 } } });
    const log: string[] = [];
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk, time: T }, (l) => log.push(l));
    await dump(disk, img);
    expect(fsck(img).code).toBe(0);
    expect(Buffer.from(debugfsCat(img, `${DIR}/lib/arm/libxul.so`))).toEqual(Buffer.from(v7));
    expect(log).toContain('1 native library for armeabi-v7a in /priv-app/Glassworks/lib/arm');
    // no settings overlay: the image's own first boot, so no lock-screen default either (--no-settings-overlay)
    expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).not.toContain('ro.lockscreen');
    expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).toContain(`\nro.glassworks.frame.version=${MIRROR_FRAME.version}\n`);
    expect(debugfs(img, 'ls /vendor')).not.toContain('overlay');
  }, 60_000);

  it('keeps one app per image: the probe goes to /priv-app/S1Kiosk, and Glassworks over it removes that folder', async () => {
    const img = join(dir, 'swap.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const probe = fakeApk('dev.glassworks.mirror.probe', { minSdk: 23, home: [...PERMS, ...app(HOME_ACTIVITY())] });
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: probe, app: MIRROR_PROBE, time: T });
    await dump(disk, img);
    expect(debugfs(img, 'ls /priv-app')).toContain('S1Kiosk');
    expect(debugfs(img, 'ls /priv-app')).not.toContain('Glassworks');
    await patchSystem(await Ext4.open(disk), { adbKey: KEY, apk: APK, time: T });
    await dump(disk, img);
    expect(fsck(img).code).toBe(0);
    expect(debugfs(img, 'ls /priv-app')).toContain('Glassworks');
    expect(debugfs(img, 'ls /priv-app')).not.toContain('S1Kiosk');
  }, 60_000);

  it('refuses a library it can\'t unpack before writing anything (#354 review)', async () => {
    const img = join(dir, 'badlib.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    const apk = mirrorApk({ minSdk: 23, libs: { 'armeabi-v7a': { 'libxul.so': new Uint8Array([1, 2, 3]) } } }).slice();
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
    await expect(patchSystem(await Ext4.open(disk), { adbKey: KEY, apk, time: T })).rejects.toThrow(/method 99.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
  }, 60_000);

  it('refuses an APK that lists the same library twice, before writing anything (#354 review)', async () => {
    const img = join(dir, 'duplib.img');
    mke2fs(img, 64, MAKE_EXT4FS, systemTree());
    const disk = await load(img);
    const before = hex(new Sha256().update(await disk.read(0, disk.blocks)).digest());
    const apk = mirrorApk({ minSdk: 23, libs: { 'armeabi-v7a': { 'libxul.so': new Uint8Array([1]) } }, extraEntries: [['lib/armeabi-v7a/libxul.so', new Uint8Array([2])]] });
    await expect(patchSystem(await Ext4.open(disk), { adbKey: KEY, apk, time: T })).rejects.toThrow(/libxul\.so more than once.*Nothing was changed/);
    expect(hex(new Sha256().update(await disk.read(0, disk.blocks)).digest())).toBe(before);
  }, 60_000);

  it('builds a fresh userdata that lets only this browser\'s adb key in', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 5000);
    const key = fakeAdbKey(12, 'kiln@glassworks');
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
    await expect(buildUserdata(disk, { adbKey: `${key}\nnot a key; rm -rf /` })).rejects.toThrow(/adb public key/);
  }, 60_000);

  it('writes every key line into adb_keys (repeated --adb-key, #426 review)', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 5000);
    const [a, b] = [fakeAdbKey(1, 'one@host'), fakeAdbKey(2, 'second host, a comment with spaces')];
    expect(adbKeyLines(`${a}\n${b}`)).toEqual([a, b]); // the preflight takes what buildUserdata writes
    await buildUserdata(disk, { adbKey: `${a}\n${b}\n`, time: T });
    const img = join(dir, 'userdata-two.img');
    await dump(disk, img);
    expect(new TextDecoder().decode(debugfsCat(img, '/misc/adb/adb_keys'))).toBe(`${a}\n${b}\n`);
  }, 60_000);

  it('saves the person\'s Wi-Fi where Android 6\'s Wi-Fi expects it, owned and labelled as init and file_contexts make it', async () => {
    const disk = new Disk(new MemoryStore(), 4096, 3 * 32768 + 5000);
    const key = fakeAdbKey(12, 'kiln@glassworks');
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
/** A real Glassworks legacy APK to patch in, in place of the fake one: the real app's libraries need most of the room. */
const REAL_APK = process.env.GW_MIRROR_APK;

/** A Store on a file: the real image is too big for memory. */
const fileStore = (fd: number): Store => ({
  async read(o, n) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; },
  async write(o, d) { writeSync(fd, d, 0, d.length, o); },
});

describe.skipIf(!REAL || !haveTools || !existsSync(REAL ?? ''))('the real Glass Panes system.img (GW_MIRROR_SYSTEM_IMG, GW_MIRROR_APK)', () => {
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
      // the template buildImages puts the saved network after (read 2026-11-16 from image 2, the same in image 3)
      expect(new TextDecoder().decode(await real.readFile('/etc/wifi/wpa_supplicant.conf'))).toBe(TEMPLATE);
      expect((await real.readdir('/vendor')).map((e) => e.name)).not.toContain('overlay'); // ours is the only overlay
      // each unused folder the pinned image still has holds the app the list names (image 3 leaves 24 of the 36 out itself)
      const packageIn = async (dir: string) => {
        const apk = (await real.readdir(dir)).find((e) => e.name.endsWith('.apk'))!;
        return axmlAttribute((await zipEntry(await real.readFile(`${dir}/${apk.name}`), 'AndroidManifest.xml'))!, 'manifest', 'package');
      };
      const present: string[] = [];
      for (const u of UNUSED_APPS) if (await real.exists(u.dir)) { expect(await packageIn(u.dir), u.dir).toBe(u.package); present.push(u.package); }
      expect(present).toHaveLength(12);
      // and its own kiosk, which Glassworks replaces
      expect(await packageIn(IMAGE_KIOSK.dir)).toBe(IMAGE_KIOSK.package);
      const apk = REAL_APK ? new Uint8Array(readFileSync(REAL_APK)) : APK;
      const log: string[] = [];
      await patchSystem(real, { adbKey: KEY, apk, bootAnimation: ANIM, settingsOverlay: OVERLAY, frameworkOverlay: FRAMEWORK }, (l) => log.push(l));
      console.info(`free after the patch: ${real.freeBlocks} blocks of 4 KiB; ${log.find((l) => l.includes('native librar')) ?? 'no native libraries'}`);
      for (const u of UNUSED_APPS) expect(await real.exists(u.dir), u.dir).toBe(false);
      expect(await real.exists(IMAGE_KIOSK.dir)).toBe(false);
      for (const d of ['/priv-app/TeleService', '/priv-app/TelephonyProvider', '/priv-app/Telecom', '/priv-app/ContactsProvider', '/app/LatinIME', '/app/PPPreference', '/app/SVIService', '/app/CABLService']) expect(await real.exists(d), d).toBe(true);
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
      // by hash: a real APK is ~96 MB, too big for toEqual's diff
      const sha256 = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');
      expect(sha256(debugfsCat(img, `${DIR}/Glassworks.apk`))).toBe(sha256(apk));
      expect(debugfs(img, `ls ${DIR}/lib/arm64`)).toContain('libxul.so');
      expect(new TextDecoder().decode(debugfsCat(img, '/build.prop'))).toMatch(/\nro\.lockscreen\.disable\.default=true\n$/);
      expect(Buffer.from(debugfsCat(img, SETTINGS_OVERLAY.path))).toEqual(Buffer.from(OVERLAY));
      expect(Buffer.from(debugfsCat(img, FRAMEWORK_OVERLAY.path))).toEqual(Buffer.from(FRAMEWORK));
      expect(debugfs(img, 'ls /app')).toContain('Provision');
      // services.jar: the installer's key in place of the project key, and (with the SDK's dexdump)
      // the same code, locals and annotations as the original, verified as ART does
      const original = (await zipEntry(new Uint8Array(debugfsCat(join(dir, 'original.raw'), '/framework/services.jar')), 'classes.dex'))!;
      const patched = (await zipEntry(new Uint8Array(debugfsCat(img, '/framework/services.jar')), 'classes.dex'))!;
      expect(Buffer.from(patched).toString('latin1')).toContain(adbFingerprint(KEY));
      expect(Buffer.from(patched).toString('latin1')).not.toContain(IMAGE_OWNER_FINGERPRINT);
      if (DEXDUMP) {
        const a = disassemble(original), b = disassemble(patched);
        expect(b.err).toBe('');
        expect(b.text === a.text, 'dexdump prints the patched services.jar like the original').toBe(true);
      }
    } finally { cleanup(dir); }
  }, 600_000);
});
