// The whole option-A path off-device: a system image (a small one made like the Glass Panes one) is
// checked, patched, written as sparse images, flashed through @glassworks/fastboot onto a fake LK, and
// what lands on its partitions must pass e2fsck. Then the first boot over adb, on a fake.
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { FakeKeySocket, fakeApk } from '@glassworks/app-adb/fakes';
import { MemoryStore, writeSparse } from '@glassworks/ext4';
import { clean, cleanup, debugfs, debugfsCat, fsck, haveTools, load, mke2fs, scratch } from '@glassworks/ext4/testing';
import { Fastboot } from '@glassworks/fastboot';
import { FakeLk } from '@glassworks/fastboot/fake-lk';
import { GLASSWORKS } from '../src/app';
import { FirstBootMirror, fakeAdbKey, fakeMirrorApk, fakeServicesJar } from './fakes';
import {
  GLASS_PANES, SYSTEM_BYTES, WrongImage, buildImages, displayRotation, makeOnlyHome, checkDownload, checkFastboot, firstBoot, flashImages, loadSystem, setPortrait, type FastbootLike,
} from '../src';

const T = 1_700_000_000;
const KEY = fakeAdbKey(5, 'kiln@glassworks');
const MAKE_EXT4FS = ['-O', '^metadata_csum,^64bit,^flex_bg,^dir_index,^huge_file,^dir_nlink,^extra_isize,uninit_bg,resize_inode'];

/** A fake LK with the variables Qualcomm's aboot publishes. */
function lk(vars: Record<string, string>): FastbootLike {
  return {
    async getvar(n) { if (n in vars) return vars[n]!; throw new Error(`getvar:${n}: GetVar Variable Not found`); },
    async partitionSize(n) { const v = vars[`partition-size:${n}`]; if (!v) throw new Error('no partition'); return Number.parseInt(v, 16); },
    async flashBlob() {}, async reboot() {},
  };
}

describe('fastboot checks (read-only)', () => {
  it('passes the partition sizes the image is made for, and shows the rest', async () => {
    const r = await checkFastboot(lk({ 'partition-size:system': `0x${SYSTEM_BYTES.toString(16)}`, 'partition-size:userdata': '0x14cc00000', product: 'MSM8916', 'max-download-size': '0x20000000' }));
    expect(r.ok).toBe(true);
    expect(r.checks.map((c) => c.what)).toEqual(['System partition', 'Userdata partition', 'Download size']);
    expect(r.userdataBytes).toBe(0x14cc00000);
    expect(r.info).toEqual(['product MSM8916', 'max-download-size 0x20000000', 'secure not reported', 'Home Assistant id: no serial reported, so the app picks a new one']);
    expect(r.deviceMac).toBeUndefined();
  });

  it('gives the Mirror the id the app derives from its serial, so every reflash keeps its Home Assistant device (#523)', async () => {
    const sizes = { 'partition-size:system': `0x${SYSTEM_BYTES.toString(16)}`, 'partition-size:userdata': '0x14cc00000' };
    // core's DeviceMacPinTest pins the same serial to the same bytes
    const r = await checkFastboot(lk({ ...sizes, serialno: '0123456789abcdef' }));
    expect(r.deviceMac).toBe('E6:79:E6:E0:96:A3');
    expect(r.info.join('\n')).not.toContain('E6:79');
    expect(r.info).toContain('Home Assistant id: from the serial, the same on every reflash');
    expect((await checkFastboot(lk({ ...sizes, serialno: 'unknown' }))).deviceMac).toBeUndefined();
  });

  it('refuses another system size, or none reported', async () => {
    expect((await checkFastboot(lk({ 'partition-size:system': '0x60000000', 'partition-size:userdata': '0x14cc00000' }))).ok).toBe(false);
    const none = await checkFastboot(lk({}));
    expect(none.ok).toBe(false);
    expect(none.checks.map((c) => c.found)).toEqual(['not reported', 'not reported', 'not reported']);
  });

  it('takes a system partition at least the image\'s size: the real Mirror\'s is 19 blocks bigger (2026-11-16)', async () => {
    const sizes = (system: number) => ({ 'partition-size:system': `0x${system.toString(16)}`, 'partition-size:userdata': '0x14cc00000', 'max-download-size': '0x10000000' });
    expect((await checkFastboot(lk(sizes(1_288_491_008)))).ok).toBe(true);
    expect((await checkFastboot(lk(sizes(SYSTEM_BYTES)))).ok).toBe(true);
    expect((await checkFastboot(lk(sizes(SYSTEM_BYTES - 4096)))).ok).toBe(false);
  });

  it('refuses a bootloader that doesn\'t report max-download-size, or reports less than a block (fastboot.js would assume 512 MiB)', async () => {
    const sizes = { 'partition-size:system': `0x${SYSTEM_BYTES.toString(16)}`, 'partition-size:userdata': '0x14cc00000' };
    expect((await checkFastboot(lk(sizes))).ok).toBe(false);
    expect((await checkFastboot(lk({ ...sizes, 'max-download-size': '0x800' }))).ok).toBe(false);
    expect((await checkFastboot(lk({ ...sizes, 'max-download-size': '0x10000000' }))).ok).toBe(true);
    // a bare number is ambiguous: @glassworks/fastboot would read 268435456 as hex, ~40 times too big
    expect((await checkFastboot(lk({ ...sizes, 'max-download-size': '268435456' }))).ok).toBe(false);
  });
});

describe.skipIf(!haveTools)('image to partitions, judged by e2fsck', () => {
  let dir: string;
  beforeAll(() => { dir = scratch(); });
  afterAll(() => cleanup(dir));

  /** A small system image made like the Glass Panes one, as a sparse file, with its size and hash. */
  async function systemImage(): Promise<{ file: Blob; want: { name: string; size: number; sha256: string } }> {
    const tree = join(dir, 'tree');
    mkdirSync(join(tree, 'priv-app/Launcher3'), { recursive: true });
    mkdirSync(join(tree, 'media'), { recursive: true });
    mkdirSync(join(tree, 'framework'), { recursive: true });
    writeFileSync(join(tree, 'framework/services.jar'), await fakeServicesJar());
    writeFileSync(join(tree, 'build.prop'), `ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`);
    writeFileSync(join(tree, 'priv-app/Launcher3/Launcher3.apk'), randomBytes(50_000));
    const raw = join(dir, 'system.raw');
    mke2fs(raw, 48, MAKE_EXT4FS, tree);
    const parts: Uint8Array[] = [];
    await writeSparse(await load(raw), (b) => { parts.push(b.slice()); });
    const bytes = Buffer.concat(parts);
    return { file: new Blob([bytes]), want: { name: 'system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') } };
  }

  it('checks, patches, flashes, and both partitions pass e2fsck', async () => {
    const { file, want } = await systemImage();
    const seen: number[] = [];
    const loaded = await loadSystem(file, new MemoryStore(), (x) => seen.push(x), want);
    expect(loaded.sha256).toBe(want.sha256);
    expect(seen.at(-1)).toBe(1);

    const out = { system: [] as Uint8Array<ArrayBuffer>[], userdata: [] as Uint8Array<ArrayBuffer>[] };
    const steps = new Set<string>();
    const logs: string[] = [];
    const userdataBytes = 40 * 1024 * 1024;
    await buildImages(loaded, { apk: fakeMirrorApk({ libs: { 'arm64-v8a': { 'libxul.so': new Uint8Array(8) } } }), adbKey: KEY, userdataBytes, time: T },
      { system: (b) => { out.system.push(b.slice()); }, userdata: (b) => { out.userdata.push(b.slice()); } }, (s) => steps.add(s), (l) => logs.push(l));
    expect([...steps]).toEqual(['patch', 'system', 'userdata']);
    // patchSystem's own lines reach the caller's log (#557)
    expect(logs).toContain('1 native library for arm64-v8a in /priv-app/Glassworks/lib/arm64');

    // through the real Fastboot class onto Qualcomm LK as far as flashBlob uses it, 16 MiB per download
    const fake = new FakeLk(16 << 20, [['system', loaded.disk.blocks * 4096], ['userdata', userdataBytes]]);
    const fb = new Fastboot(fake);
    const progress: string[] = [];
    await flashImages(fb, { system: new Blob(out.system), userdata: new Blob(out.userdata) }, (p, x) => { if (x === 1) progress.push(p); });
    expect(fake.commands.filter((c) => c.startsWith('flash:'))[0]).toBe('flash:system');
    expect(fake.commands.at(-1)).toBe('reboot');
    expect(progress).toEqual(['system', 'userdata']);

    const sys = join(dir, 'flashed-system.raw'), data = join(dir, 'flashed-userdata.raw');
    writeFileSync(sys, fake.parts.get('system')!);
    writeFileSync(data, fake.parts.get('userdata')!);
    for (const img of [sys, data]) { const r = fsck(img); expect(clean(r), r.out).toBe(true); }
    expect(debugfs(sys, 'ls /priv-app')).toContain('Glassworks');
    expect(new TextDecoder().decode(debugfsCat(data, '/misc/adb/adb_keys'))).toBe(`${KEY}\n`);
  }, 120_000);

  it('a wrong or cut-short file is refused before anything else happens, with what to do', async () => {
    const { file, want } = await systemImage();
    const bytes = new Uint8Array(await file.arrayBuffer());
    await expect(loadSystem(new Blob([bytes.subarray(0, bytes.length - 100)]), new MemoryStore(), () => {}, want)).rejects.toThrow(/download stopped early.*Download system\.img again/);
    const damaged = bytes.slice(); damaged[damaged.length - 5000]! ^= 1;
    await expect(loadSystem(new Blob([damaged]), new MemoryStore(), () => {}, want)).rejects.toThrow(WrongImage);
    await expect(loadSystem(new Blob([damaged]), new MemoryStore(), () => {}, want)).rejects.toThrow(/SHA-256 is [0-9a-f]{64}, not the published/);
    await expect(loadSystem(new Blob([new Uint8Array(want.size)]), new MemoryStore(), () => {}, want)).rejects.toThrow(/isn't a readable system\.img/);
  }, 60_000);
});

describe('the way back\'s downloads', () => {
  it('checks size and SHA-256', async () => {
    const b = new Uint8Array(randomBytes(70_000));
    const want = { name: 'userdata.sparse', size: b.length, sha256: createHash('sha256').update(b).digest('hex') };
    await checkDownload(new Blob([b]), want);
    await expect(checkDownload(new Blob([b.subarray(1)]), want)).rejects.toThrow(/download stopped early/);
    const c = b.slice(); c[0]! ^= 1;
    await expect(checkDownload(new Blob([c]), want)).rejects.toThrow(/not the published/);
  });
});

describe('first boot', () => {
  it('checks the image, grants, hides the other launcher, starts Glassworks through its main activity with Home Assistant', async () => {
    const p = new FirstBootMirror();
    const r = await firstBoot(p, { haUrl: 'http://homeassistant.local:8123', sleep: async () => {} });
    expect(r).toEqual({ app: { name: '0.5.0-legacy', code: 600 }, hidden: ['com.android.launcher3'], rotation: 3,
      home: { result: 'set', focused: GLASSWORKS.activity, left: [] } });
    const at = (c: string) => p.cmds.findIndex((x) => x.startsWith(c));
    expect(at('pm grant')).toBeLessThan(at(`am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`));
    expect(at(`am start -n ${GLASSWORKS.activity} --es url`)).toBeLessThan(at('pm hide com.android.launcher3'));
    expect(at('pm hide com.android.launcher3')).toBeLessThan(at('dumpsys activity activities'));
    expect(p.cmds).not.toContain('pm hide com.cyanogenmod.trebuchet'); // not on this image
  });

  it('runs these commands, in this order: the rotation locked to 3 and checked before the app starts (golden, 2026-11-17)', async () => {
    const p = new FirstBootMirror();
    await firstBoot(p, { haUrl: 'http://homeassistant.local:8123', sleep: async () => {} });
    const pkg = GLASSWORKS.package;
    expect(p.cmds).toEqual([
      'getprop ro.build.fingerprint',
      `dumpsys package ${pkg}`,
      `pm grant ${pkg} android.permission.RECORD_AUDIO`,
      `pm grant ${pkg} android.permission.CAMERA`,
      `pm grant ${pkg} android.permission.WRITE_SECURE_SETTINGS`,
      `appops set ${pkg} SYSTEM_ALERT_WINDOW allow`,
      `pm grant ${pkg} android.permission.CHANGE_CONFIGURATION`,
      `dpm set-active-admin ${pkg}/dev.glassworks.AdminReceiver`,
      'settings put system accelerometer_rotation 0',
      'settings put system user_rotation 3',
      'dumpsys window windows',
      `am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`,
      `am start -a android.intent.action.MAIN -c android.intent.category.HOME ${pkg}`,
      'pm list packages com.android.launcher3',
      'pm hide com.android.launcher3',
      'pm list packages com.cyanogenmod.trebuchet',
      'pm list packages com.android.launcher3',
      'pm list packages com.cyanogenmod.trebuchet',
      'am start -a android.intent.action.MAIN -c android.intent.category.HOME',
      'dumpsys activity activities',
    ]);
    expect(p.cmds.some((c) => c.includes('hwrotation'))).toBe(false);
  });

  it('refuses when the screen doesn\'t turn to portrait, before the app starts (2026-11-17: it came up sideways)', async () => {
    const p = new FirstBootMirror();
    p.rotationStuck = true;
    const waited: number[] = [];
    await expect(firstBoot(p, { haUrl: 'http://homeassistant.local:8123', sleep: async (ms) => { waited.push(ms); } }))
      .rejects.toThrow(/screen didn't turn to portrait: after user_rotation=3, Android still reports rotation 0 \(10 s\)/);
    expect(waited).toEqual(Array(10).fill(1000));
    expect(p.cmds.filter((c) => c === 'dumpsys window windows')).toHaveLength(10);
    expect(p.cmds.some((c) => c.startsWith('am start'))).toBe(false);
  });

  it('takes rotation 3 only: refuses 1 (portrait, upside down) and a dump with no mRotation, whatever the size (#439 verifier)', async () => {
    const p = new FirstBootMirror();
    p.userRotation = 1; // what settings put would leave if Android ignored our 3
    const flip = { run: async (c: string) => ({ out: c === 'dumpsys window windows' ? (await p.run('dumpsys window windows')).out.replace(/mRotation=\d/, 'mRotation=1') : '', code: 0 }), push: async () => {} };
    await expect(setPortrait(flip, () => {}, async () => {}, 3)).rejects.toThrow(/still reports rotation 1 \(3 s\)/);
    const none = { run: async (c: string) => ({ out: c === 'dumpsys window windows' ? '  mScreenRect=[0,0][1080,1920]\n  mUserRotation=3\n    init=1920x1080 240dpi cur=1080x1920 app=1080x1920\n' : '', code: 0 }), push: async () => {} };
    await expect(setPortrait(none, () => {}, async () => {}, 2)).rejects.toThrow(/still reports rotation unknown \(2 s\)/);
  });

  it('reads mRotation from Android 6\'s dumpsys window windows, not the plain dump (#439 verifier)', async () => {
    const p = new FirstBootMirror();
    p.accelerometer = 0; p.userRotation = 3;
    expect(await displayRotation(p)).toBe(3);
    expect(p.cmds).toEqual(['dumpsys window windows']);
    p.userRotation = 0;
    expect(await displayRotation(p)).toBe(0);
  });

  it('says when Launcher3 survives and HOME shows the chooser, and the same step fixes it once hiding works', async () => {
    const p = new FirstBootMirror();
    p.hideFails = true;
    const r = await firstBoot(p, { sleep: async () => {} });
    expect(r.home).toEqual({ result: 'chooser', focused: 'android/com.android.internal.app.ResolverActivity', left: ['com.android.launcher3'] });
    p.hideFails = false;
    expect((await makeOnlyHome(p, () => {}, async () => {})).home.result).toBe('set');
  });

  it('hides nothing when the app has no home screen of its own, so the Mirror keeps one (2026-11-16)', async () => {
    const p = new FirstBootMirror();
    p.noHome = true;
    const r = await firstBoot(p, { sleep: async () => {} });
    expect(r.hidden).toEqual([]);
    expect(r.home).toEqual({ result: 'none', left: ['com.android.launcher3'] });
    expect(p.cmds.some((c) => c.startsWith('pm hide'))).toBe(false);
    expect(p.hidden.size).toBe(0);
  });

  it('asks whether the app answers HOME before it hides any launcher', async () => {
    const p = new FirstBootMirror();
    await makeOnlyHome(p, () => {}, async () => {});
    const ask = p.cmds.indexOf(`am start -a android.intent.action.MAIN -c android.intent.category.HOME ${GLASSWORKS.package}`);
    expect(ask).toBeGreaterThanOrEqual(0);
    expect(ask).toBeLessThan(p.cmds.indexOf('pm hide com.android.launcher3'));
  });

  it('stops if the Mirror didn\'t start the image Kiln wrote, or the app is missing', async () => {
    await expect(firstBoot(new FirstBootMirror('Android/other/x:6.0.1/X/1:user/release-keys'), {})).rejects.toThrow(/not the image Kiln wrote/);
    await expect(firstBoot(new FirstBootMirror(GLASS_PANES.fingerprint, false), {})).rejects.toThrow(/isn't on the Mirror/);
  });

  it('hands the API key from before the flash to the fresh app right after it starts, and never logs it (#574)', async () => {
    const KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 200 - i))); // made up
    const p = new FirstBootMirror().withKeySocket();
    const lines: string[] = [];
    const r = await firstBoot(p, { haUrl: 'http://homeassistant.local:8123', apiKey: KEY, onStep: (s, d) => lines.push(s || d || ''), sleep: async () => {} });
    expect(r.apiKey).toBe(true);
    expect(p.keys!.key).toBe(KEY);
    expect(p.keys!.verbs).toEqual(['set']);
    expect(lines).toContain('Home Assistant API key: the one from before the flash');
    expect(lines.join('\n')).not.toContain(KEY);
    expect(p.cmds.join('\n')).not.toContain(KEY);
    // after the start that runs the app, before the home screen and the Wi-Fi
    const start = p.cmds.findIndex((c) => c.startsWith(`am start -n ${GLASSWORKS.activity}`));
    const grep = p.cmds.findIndex((c) => c.includes('@glassworks_api_key'));
    expect(start).toBeGreaterThanOrEqual(0);
    expect(grep).toBeGreaterThan(start);
    expect(p.cmds.findIndex((c) => c.startsWith('pm hide'))).toBeGreaterThan(grep);

    // without a key, nothing is asked of the socket
    const q = new FirstBootMirror().withKeySocket();
    expect((await firstBoot(q, { sleep: async () => {} })).apiKey).toBeUndefined();
    expect(q.keys!.verbs).toEqual([]);
  });

  it('says so when the fresh app doesn\'t take the key, and goes on', async () => {
    const lines: string[] = [];
    const r = await firstBoot(new FirstBootMirror().withKeySocket(new FakeKeySocket(btoa('y'.repeat(32)))),
      { apiKey: btoa('x'.repeat(32)), onStep: (s, d) => lines.push(s || d || ''), sleep: async () => {} });
    expect(r.apiKey).toBe(false);
    expect(r.home.result).toBe('set');
    expect(lines.join('\n')).toMatch(/didn't take it.*re-authenticate once/);
  });

  it('without the app\'s Improv socket, says the Wi-Fi wasn\'t confirmed, and never logs the network\'s name or password', async () => {
    const lines: string[] = [];
    const r = await firstBoot(new FirstBootMirror(), { wifi: { ssid: 'Made-up Home Net', password: 'not-a-real-password' }, onStep: (s, d) => lines.push(s || d || ''), sleep: async () => {} });
    expect(r.wifi).toBe(false);
    expect(lines).toContain('Wi-Fi: the network you entered');
    expect(lines.join('\n')).not.toContain('Made-up Home Net');
    expect(lines.join('\n')).not.toContain('not-a-real-password');
  });
});

