// The whole option-A path off-device: a system image (a small one made like the Glass Panes one) is
// checked, patched, written as sparse images, flashed through @glassworks/fastboot onto a fake LK, and
// what lands on its partitions must pass e2fsck. Then the first boot over adb, on a fake.
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { fakeApk } from '@glassworks/app-adb/fakes';
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
    expect(r.info).toEqual(['product MSM8916', 'max-download-size 0x20000000', 'secure not reported']);
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
    const userdataBytes = 40 * 1024 * 1024;
    await buildImages(loaded, { apk: fakeMirrorApk(), adbKey: KEY, userdataBytes, time: T },
      { system: (b) => { out.system.push(b.slice()); }, userdata: (b) => { out.userdata.push(b.slice()); } }, (s) => steps.add(s));
    expect([...steps]).toEqual(['patch', 'system', 'userdata']);

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
    expect(r).toEqual({ app: { name: '0.5.0-legacy', code: 600 }, hidden: ['com.android.launcher3'], rotation: 'rotation 3, 1080x1920',
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
      `dpm set-active-admin ${pkg}/sh.edm.s1kiosk.AdminReceiver`,
      'settings put system accelerometer_rotation 0',
      'settings put system user_rotation 3',
      'dumpsys window',
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
      .rejects.toThrow(/screen didn't turn to portrait: after user_rotation=3, Android still reports rotation 0, 1920x1080 \(10 s\)/);
    expect(waited).toEqual(Array(10).fill(1000));
    expect(p.cmds.filter((c) => c === 'dumpsys window')).toHaveLength(10);
    expect(p.cmds.some((c) => c.startsWith('am start'))).toBe(false);
  });

  it('takes rotation 3, or a 1080x1920 display when no rotation is printed; refuses rotation 1 (portrait, upside down)', async () => {
    const p = new FirstBootMirror();
    p.noRotationLine = true;
    expect(await setPortrait(p, () => {}, async () => {})).toBe('rotation ?, 1080x1920');
    const flipped = { cmds: [] as string[], async run(c: string) { this.cmds.push(c); return { out: c === 'dumpsys window' ? '    init=1920x1080 240dpi cur=1080x1920 app=1080x1920\n  mRotation=1 mAltOrientation=false\n' : '', code: 0 }; }, async push() {} };
    await expect(setPortrait(flipped, () => {}, async () => {}, 3)).rejects.toThrow(/still reports rotation 1, 1080x1920 \(3 s\)/);
  });

  it('reads the live rotation, not the one saved at the last ANR, which Android 6 prints first (#439 review)', async () => {
    const shell = (out: string) => ({ async run() { return { out, code: 0 }; }, async push() {} });
    const live = 'WINDOW MANAGER POLICY STATE (dumpsys window policy)\n    mUserRotationMode=1 mUserRotation=3\n'
      + 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n    init=1920x1080 240dpi cur=1080x1920 app=1080x1920\n'
      + 'WINDOW MANAGER WINDOWS (dumpsys window windows)\n  mRotation=3 mAltOrientation=false\n';
    const saved = 'WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n  ANR time: 17-Nov-2026\n    init=1920x1080 240dpi cur=1920x1080 app=1920x1080\n  mRotation=0 mAltOrientation=false\n\n';
    expect(await displayRotation(shell(saved + live))).toEqual({ rotation: 3, size: '1080x1920' });
    expect(await displayRotation(shell('WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n  <no ANR has occurred since boot>\n\n' + live))).toEqual({ rotation: 3, size: '1080x1920' });
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

  it('without the app\'s Improv socket, says the Wi-Fi wasn\'t confirmed, and never logs the network\'s name or password', async () => {
    const lines: string[] = [];
    const r = await firstBoot(new FirstBootMirror(), { wifi: { ssid: 'Made-up Home Net', password: 'not-a-real-password' }, onStep: (s, d) => lines.push(s || d || ''), sleep: async () => {} });
    expect(r.wifi).toBe(false);
    expect(lines).toContain('Wi-Fi: the network you entered');
    expect(lines.join('\n')).not.toContain('Made-up Home Net');
    expect(lines.join('\n')).not.toContain('not-a-real-password');
  });
});

