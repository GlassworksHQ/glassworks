// The root's headless run (flash-node.ts) end to end on a fake Mirror: the real Fastboot class on
// FakeLk, the installer's adb fakes, and what lands on the fake partitions judged by e2fsck. The CLI's
// own --dry-run is the same code; the last tests run it from its arguments.
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { zipEntry } from '@glassworks/app-adb';
import { adbFingerprint } from '@glassworks/lululemon-mirror-installer';
import { clean, debugfs, debugfsCat, fsck, haveTools } from '@glassworks/ext4/testing';
import { FakeMirror, FirstBootMirror, fakeAdbKey, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { hostAdb } from '../src/adb';
import { UsageError, cli } from '../src/cli';
import { DRY_USERDATA, dryDevices, dryImage } from '../src/dry';

/** The id the app derives from the dry LK's serial (core's DeviceMacPinTest pins the same bytes). */
const DRY_MAC = 'E6:79:E6:E0:96:A3';
import { runInstall, runWayBack } from '../src/run';

const KEY = fakeAdbKey(9, 'root@host');
const APK = fakeMirrorApk({ libs: { 'arm64-v8a': { 'libgecko.so': new Uint8Array(5000).fill(7) } } });

let work: string;
beforeEach(() => { work = mkdtempSync(join(tmpdir(), 'gw-mirror-test-')); });
afterEach(() => rmSync(work, { recursive: true, force: true }));

/**
 * A zip of stored files, except `deflated`, which goes compressed: an APK whose .so is far bigger than
 * the file. (app-adb's `zip` fake stores every entry, so the APK's length would give the library's.)
 */
async function zipDeflating(files: [string, Uint8Array][], deflated: string): Promise<Uint8Array> {
  const enc = new TextEncoder();
  const locals: Uint8Array[] = [], centrals: Uint8Array[] = [];
  let off = 0;
  for (const [name, data] of files) {
    const n = enc.encode(name), compressed = name === deflated;
    const body = compressed
      ? new Uint8Array(await new Response(new Blob([data as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer())
      : data;
    const method = compressed ? 8 : 0;
    const local = new Uint8Array(30 + n.length + body.length), central = new Uint8Array(46 + n.length);
    const lv = new DataView(local.buffer), cv = new DataView(central.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(8, method, true);
    lv.setUint32(18, body.length, true); lv.setUint32(22, data.length, true); lv.setUint16(26, n.length, true);
    local.set(n, 30); local.set(body, 30 + n.length);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(10, method, true);
    cv.setUint32(20, body.length, true); cv.setUint32(24, data.length, true); cv.setUint16(28, n.length, true); cv.setUint32(42, off, true);
    central.set(n, 46);
    locals.push(local); centrals.push(central); off += local.length;
  }
  const cdSize = centrals.reduce((x, c) => x + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
  ev.setUint32(12, cdSize, true); ev.setUint32(16, off, true);
  const out = new Uint8Array(off + cdSize + 22);
  let at = 0;
  for (const b of [...locals, ...centrals, end]) { out.set(b, at); at += b.length; }
  return out;
}

async function install(o: { hideFails?: boolean; confirm?: (q: string) => boolean; stockAtStart?: boolean; adbKey?: string; apiKey?: string } = {}) {
  const img = await dryImage(work, APK.length);
  const lines: string[] = [];
  const d = dryDevices(img.bytes, (l) => lines.push(l), o);
  const asked: string[] = [];
  if (o.confirm) d.deps.confirm = async (q) => { asked.push(q); return o.confirm!(q); };
  const run = runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: o.adbKey ?? KEY, haUrl: 'http://homeassistant.local:8123', work }, d.deps);
  return { d, img, lines, asked, run };
}

describe('the headless install on a fake Mirror', () => {
  it('checks, builds, flashes system then userdata, reboots, and runs the first boot over adb', async () => {
    const { d, lines, run } = await install();
    const r = await run;
    expect(d.lk.commands.filter((c) => c.startsWith('flash:') || c === 'reboot')).toEqual(['flash:system', 'flash:userdata', 'reboot']);
    expect(d.state()).toBe('glassworks');
    expect(r.firstBoot?.home.result).toBe('set');
    expect(r.firstBoot?.hidden).toEqual(['com.android.launcher3']);
    // image.ts's own lines come through step 6 now (#557): the libraries, the unused apps, build.prop
    expect(lines).toContain('    1 native library for arm64-v8a in /priv-app/Glassworks/lib/arm64');
    expect(d.after.cmds).toContain('pm hide com.android.launcher3');
    expect(d.after.cmds.some((c) => c.includes('homeassistant.local'))).toBe(true);
    expect(d.before.cmds.every((c) => c.startsWith('getprop '))).toBe(true); // read-only on the stock side
    expect(lines.join('\n')).toMatch(/lululemon's software can't be restored/);
    expect(lines.filter((l) => /^\n\[\d+\/13\]/.test(l))).toHaveLength(13);
    if (!haveTools) return;
    const sys = join(work, 'flashed-system.raw'), ud = join(work, 'flashed-userdata.raw');
    writeFileSync(sys, d.lk.parts.get('system')!);
    writeFileSync(ud, d.lk.parts.get('userdata')!);
    for (const f of [sys, ud]) { const c = fsck(f); expect(clean(c), c.out).toBe(true); }
    expect(debugfs(sys, 'ls /priv-app')).toContain('Glassworks');
    expect(new TextDecoder().decode(debugfsCat(ud, '/misc/adb/adb_keys'))).toBe(`${KEY}\n`);
    // #523: the Mirror's id from its serial goes into build.prop, so a reflash keeps its Home Assistant device
    expect(new TextDecoder().decode(debugfsCat(sys, '/build.prop'))).toContain(`\nro.glassworks.device.mac=${DRY_MAC}\n`);
    expect(lines.join('\n')).not.toContain(DRY_MAC);
  }, 60_000);

  it('a reflash carries the ESPHome API key: read before, flagged in build.prop, handed over at the first boot, never printed (#574)', async () => {
    const API_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 3 * i + 7))); // made up
    const { d, lines, run } = await install({ apiKey: API_KEY });
    const r = await run;
    expect(r.firstBoot?.apiKey).toBe(true);
    expect(d.before.keys!.verbs).toEqual(['get']);
    expect(d.after.keys!.key).toBe(API_KEY);
    expect(lines.join('\n')).toMatch(/Home Assistant API key: carried over the flash \(fingerprint [0-9a-f]{4}\)/);
    expect(lines.join('\n')).not.toContain(API_KEY);
    expect([...d.before.cmds, ...d.after.cmds].join('\n')).not.toContain(API_KEY);
    if (!haveTools) return;
    const sys = join(work, 'flashed-system.raw');
    writeFileSync(sys, d.lk.parts.get('system')!);
    const prop = new TextDecoder().decode(debugfsCat(sys, '/build.prop'));
    expect(prop).toContain('\nro.glassworks.api_key.handover=1\n');
    expect(prop).not.toContain(API_KEY);
  }, 60_000);

  it('stops before the reboot when the app has a key it can\'t hand over, so the flash doesn\'t lose it (#575 review)', async () => {
    const API_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 5 * i + 1))); // made up
    const { d, run } = await install({ apiKey: API_KEY });
    d.before.keys!.unreadable = true;
    await expect(run).rejects.toThrow(/couldn't read the Home Assistant API key.*Nothing was written/);
    expect(d.state()).toBe('android');
    expect(d.lk.commands.filter((c) => c.startsWith('flash:'))).toEqual([]);
  }, 60_000);

  it('without a key to carry, says so and flags nothing', async () => {
    const { d, lines, run } = await install();
    const r = await run;
    expect(r.firstBoot?.apiKey).toBeUndefined();
    expect(lines.join('\n')).toContain('Home Assistant API key: none to carry');
    if (!haveTools) return;
    const sys = join(work, 'flashed-system.raw');
    writeFileSync(sys, d.lk.parts.get('system')!);
    expect(new TextDecoder().decode(debugfsCat(sys, '/build.prop'))).not.toContain('api_key');
  }, 60_000);

  it('takes repeated --adb-key: every line in adb_keys, only the first trusted in services.jar (#426 review)', async () => {
    const second = fakeAdbKey(10, 'second host');
    const { d, lines, run } = await install({ adbKey: `${KEY}\n${second}` });
    await run;
    expect(d.lk.commands.filter((c) => c.startsWith('flash:'))).toEqual(['flash:system', 'flash:userdata']);
    expect(lines.join('\n')).toContain(`adb key the image will let in: ${adbFingerprint(KEY)}`);
    if (!haveTools) return;
    const sys = join(work, 'flashed-system.raw'), ud = join(work, 'flashed-userdata.raw');
    writeFileSync(sys, d.lk.parts.get('system')!);
    writeFileSync(ud, d.lk.parts.get('userdata')!);
    expect(new TextDecoder().decode(debugfsCat(ud, '/misc/adb/adb_keys'))).toBe(`${KEY}\n${second}\n`);
    const dex = Buffer.from((await zipEntry(new Uint8Array(debugfsCat(sys, '/framework/services.jar')), 'classes.dex'))!).toString('latin1');
    expect(dex).toContain(adbFingerprint(KEY));
    expect(dex).not.toContain(adbFingerprint(second));
  }, 60_000);

  it('refuses a bad second --adb-key at the checks, before the Mirror restarts (#426 review)', async () => {
    const { d, run } = await install({ adbKey: `${KEY}\nQAAA= not@a-key` });
    await expect(run).rejects.toThrow(/adb key 2 of 2: that isn't an adb public key/);
    expect(d.state()).toBe('android');
    expect(d.lk.commands).toEqual([]);
  }, 60_000);

  it('stops where the person says no, and writes nothing before the flash step', async () => {
    const { d, asked, run } = await install({ confirm: (q) => !q.startsWith('Flash system') });
    await expect(run).rejects.toThrow(/stopped before: Flash system/);
    expect(asked.at(-1)).toMatch(/^Flash system \(WRITES the Mirror\)/);
    expect(d.lk.commands.some((c) => c.startsWith('download:') || c.startsWith('flash:'))).toBe(false);
  }, 60_000);

  it('refuses a system image that isn\'t the published one before it reboots the Mirror', async () => {
    const img = await dryImage(work);
    const d = dryDevices(img.bytes, () => {});
    await expect(runInstall({ image: { path: img.path }, want: { ...img.want, sha256: '0'.repeat(64) }, apk: APK, adbKey: KEY, work }, d.deps)).rejects.toThrow(/not the published/);
    expect(d.state()).toBe('android');
    expect(d.lk.commands).toEqual([]);
  });

  it('refuses a max-download-size LK didn\'t write in hex, before anything is written', async () => {
    const img = await dryImage(work);
    const lines: string[] = [];
    const d = dryDevices(img.bytes, (l) => lines.push(l), { maxDownloadReply: '\t16777216' });
    await expect(runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: KEY, work }, d.deps)).rejects.toThrow(/fastboot checks failed/);
    expect(lines.join('\n')).toMatch(/NO  Download size: not reported \(needs max-download-size reported in hex/);
    expect(d.lk.commands.some((c) => c.startsWith('download:') || c.startsWith('flash:'))).toBe(false);
  });

  it('a failed build ends the run before any write; the next run loads the image again', async () => {
    const img = await dryImage(work);
    const bad = fakeApk('com.example.other', { minSdk: 23 });
    const first = dryDevices(img.bytes, () => {});
    await expect(runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: bad, adbKey: KEY, work }, first.deps)).rejects.toThrow(/not Glassworks/);
    expect(first.lk.commands.some((c) => c.startsWith('flash:'))).toBe(false);
    const lines: string[] = [];
    const again = dryDevices(img.bytes, (l) => lines.push(l));
    await runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: KEY, work }, again.deps);
    expect(lines.some((l) => /read and checked 100%/.test(l))).toBe(true);
    expect(again.lk.commands.filter((c) => c.startsWith('flash:'))).toEqual(['flash:system', 'flash:userdata']);
  }, 60_000);

  it('refuses an APK with no home screen at the first step, before the image is read or the Mirror restarted', async () => {
    const img = await dryImage(work);
    const d = dryDevices(img.bytes, () => {});
    const off = fakeMirrorApk({ home: 'none' });
    await expect(runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: off, adbKey: KEY, work }, d.deps)).rejects.toThrow(/can't be the Mirror's home screen/);
    expect(d.state()).toBe('android');
    expect(d.before.cmds).toEqual([]);
    expect(d.lk.commands).toEqual([]);
  });

  it('refuses the S1 app\'s id, or an APK without a privileged permission, before the Mirror is touched', async () => {
    const img = await dryImage(work);
    for (const [apk, why] of [[fakeApk('sh.edm.s1kiosk', { minSdk: 23 }), /is sh\.edm\.s1kiosk, not Glassworks/], [fakeMirrorApk({ without: ['android.permission.REBOOT'] }), /doesn't ask for REBOOT/]] as const) {
      const d = dryDevices(img.bytes, () => {});
      await expect(runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk, adbKey: KEY, work }, d.deps)).rejects.toThrow(why);
      expect(d.state()).toBe('android');
      expect(d.before.cmds).toEqual([]);
      expect(d.lk.commands).toEqual([]);
    }
  });

  it('refuses a board that isn\'t the original Mirror, before anything else', async () => {
    const img = await dryImage(work);
    const d = dryDevices(img.bytes, () => {});
    const other = new FakeMirror({ platform: 'msm8953' });
    d.deps.adb = async () => other;
    await expect(runInstall({ image: { path: img.path }, want: img.want, apk: APK, adbKey: KEY, work }, d.deps)).rejects.toThrow(/msm8916/);
    expect(d.lk.commands).toEqual([]);
  });

  it('starts from fastboot when there is no adb, and hides the launcher again when HOME shows the chooser', async () => {
    const { d, lines, run } = await install({ hideFails: true, stockAtStart: false });
    const r = await run;
    expect(lines).toContain('    no adb: assuming it is in fastboot already');
    expect(r.firstBoot?.home.result).toBe('chooser');
    expect(d.after.cmds.filter((c) => c === 'pm hide com.android.launcher3')).toHaveLength(2);
    expect(lines.join('\n')).toMatch(/NOT FIXED: HOME opens .*ResolverActivity.*USB mouse/);
  }, 60_000);
});

describe('an APK with no home screen (the first real flash, 2026-11-16)', () => {
  it('hides no launcher, says so, and exits 1 without trying to hide again', async () => {
    const { d, lines, run } = await install();
    d.after.noHome = true;
    const r = await run;
    expect(r.firstBoot?.home).toEqual({ result: 'none', left: ['com.android.launcher3'] });
    expect(d.after.cmds.some((c) => c.startsWith('pm hide'))).toBe(false);
    expect(lines.join('\n')).toMatch(/NO HOME SCREEN IN THIS APK: .*com\.android\.launcher3 stays the home screen/);
    expect(lines.some((l) => /hiding again/.test(l))).toBe(false);
  }, 60_000);
});

describe('a first boot with nothing to tap', () => {
  it('flashes the overlays and lock-screen default into system, and the saved Wi-Fi into userdata', async () => {
    const overlay = new Uint8Array(readFileSync(resolve(__dirname, '../../../frames/lululemon-mirror/overlay/GlassworksSettings.apk')));
    const framework = new Uint8Array(readFileSync(resolve(__dirname, '../../../frames/lululemon-mirror/overlay/GlassworksFramework.apk')));
    const img = await dryImage(work, APK.length + overlay.length + framework.length);
    const lines: string[] = [];
    const d = dryDevices(img.bytes, (l) => lines.push(l));
    await runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: KEY, work,
      settingsOverlay: overlay, frameworkOverlay: framework, wifi: { ssid: 'Home Net', password: 'not-a-real-password' } }, d.deps);
    // the harness's log, shown and kept by whoever runs it: never the network's name or password (CLAUDE.md rule 2)
    expect(lines).toContain('    Wi-Fi: the network you entered');
    expect(lines.join('\n')).not.toContain('Home Net');
    expect(lines.join('\n')).not.toContain('not-a-real-password');
    if (!haveTools) return;
    const sys = join(work, 'fb-system.raw'), ud = join(work, 'fb-userdata.raw');
    writeFileSync(sys, d.lk.parts.get('system')!);
    writeFileSync(ud, d.lk.parts.get('userdata')!);
    for (const f of [sys, ud]) { const c = fsck(f); expect(clean(c), c.out).toBe(true); }
    expect(Buffer.from(debugfsCat(sys, '/vendor/overlay/GlassworksSettings.apk')).equals(Buffer.from(overlay))).toBe(true);
    expect(Buffer.from(debugfsCat(sys, '/vendor/overlay/GlassworksFramework.apk')).equals(Buffer.from(framework))).toBe(true);
    expect(new TextDecoder().decode(debugfsCat(sys, '/build.prop'))).toContain('\nro.lockscreen.disable.default=true\n');
    const conf = new TextDecoder().decode(debugfsCat(ud, '/misc/wifi/wpa_supplicant.conf'));
    expect(conf).toMatch(/^update_config=1\n[\s\S]*\nnetwork=\{\n\tssid="Home Net"\n\tpsk=[0-9a-f]{64}\n/);
    expect(conf).not.toContain('not-a-real-password');
  }, 60_000);
});

describe('the way back on a fake Mirror', () => {
  it('checks both files, then writes them as they are', async () => {
    const img = await dryImage(work);
    const d = dryDevices(img.bytes, () => {});
    await runWayBack({ system: img.path, userdata: img.path, systemWant: img.want, userdataWant: { ...img.want, name: 'userdata.sparse' } }, d.deps);
    expect(d.lk.commands.filter((c) => c.startsWith('flash:') || c === 'reboot')).toEqual(['flash:system', 'flash:userdata', 'reboot']);
    if (!haveTools) return;
    const sys = join(work, 'flashed.raw');
    writeFileSync(sys, d.lk.parts.get('system')!.subarray(0, img.bytes));
    const c = fsck(sys); expect(clean(c), c.out).toBe(true);
  });

  it('writes nothing when a file isn\'t the published one', async () => {
    const img = await dryImage(work);
    const d = dryDevices(img.bytes, () => {});
    await expect(runWayBack({ system: img.path, userdata: img.path, systemWant: img.want }, d.deps)).rejects.toThrow(/userdata\.sparse/);
    expect(d.lk.commands).toEqual([]);
  });
});

describe('the host\'s adb', () => {
  it('reads the exit code, and never shows the serial', async () => {
    const adb = join(work, 'adb');
    writeFileSync(adb, '#!/bin/sh\nshift 2\nif [ "$1" = shell ]; then echo hello; echo "__GWRC=3"; else echo "error: device FAKESERIAL123 not found" >&2; exit 1; fi\n');
    chmodSync(adb, 0o755);
    const p = hostAdb('FAKESERIAL123', adb);
    expect(await p.run('true')).toEqual({ out: 'hello\n', code: 3 });
    const e = await p.adb(['get-state']).then(() => undefined, (x: Error) => x);
    expect(e?.message).toContain('<serial>');
    expect(e?.message).not.toContain('FAKESERIAL123');
  });
});

describe('flash-node.ts --dry-run', () => {
  it('runs the whole install from its arguments on the fake Mirror', async () => {
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--no-boot-animation', '--work', work], (l) => out.push(l))).toBe(0);
    const text = out.join('\n');
    expect(text).toMatch(/^DRY RUN/);
    expect(text).toMatch(/LK got flash:system, flash:userdata, then reboot/);
    expect(text).toMatch(/Done: Glassworks 0\.5\.0-legacy, home set/);
    expect(readFileSync(join(work, 'after.png')).subarray(1, 4).toString()).toBe('PNG');
  }, 60_000);

  it('--probe installs the Mirror probe under its own id', async () => {
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--probe', '--no-boot-animation', '--work', work], (l) => out.push(l))).toBe(0);
    expect(out.join('\n')).toMatch(/Done: the Mirror probe 0\.5\.0-legacy, home set/);
  }, 60_000);

  it('the way back too, and refuses a real run without its files', async () => {
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--way-back', '--work', work], (l) => out.push(l))).toBe(0);
    expect(out.join('\n')).toMatch(/Done: the plain Glass Panes image is on the Mirror/);
    await expect(cli(['--way-back', '--work', work], () => {})).rejects.toBeInstanceOf(UsageError);
    await expect(cli(['--image', 'x.img', '--work', work], () => {})).rejects.toThrow(/--apk/);
  }, 60_000);

  // #557: the fake image was sized by the APK's own bytes, so a real APK failed at step 6: image.ts
  // unpacks lib/<abi>/*.so into the image, and libxul.so is 147 MB where the APK holds 62 MB
  it('makes room for the libraries it unpacks out of the APK, and prints the patch\'s own lines', async () => {
    // a 40 MiB library that deflates to a few KiB: the APK fits in the 32 MiB base image, the library can't
    const base = fakeMirrorApk();
    const apk = await zipDeflating([
      ['AndroidManifest.xml', (await zipEntry(base, 'AndroidManifest.xml'))!],
      ['classes.dex', (await zipEntry(base, 'classes.dex'))!],
      ['lib/arm64-v8a/libxul.so', new Uint8Array(40 * 1024 * 1024)],
    ], 'lib/arm64-v8a/libxul.so');
    expect(apk.length).toBeLessThan(1 * 1024 * 1024);
    const file = join(work, 'glassworks.apk');
    writeFileSync(file, apk);
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--apk', file, '--no-boot-animation', '--work', work], (l) => out.push(l))).toBe(0);
    const text = out.join('\n');
    expect(text).not.toMatch(/no room for/);
    expect(text).toMatch(/1 native library for arm64-v8a in \/priv-app\/Glassworks\/lib\/arm64/);
  }, 120_000);
});

describe('--first-boot: the install\'s last steps on a Mirror already on the image (#577)', () => {
  it('waits for adb, runs the first boot, the HOME check and the screenshot, and sends LK nothing', async () => {
    const lines: string[] = [];
    const d = dryDevices(0, (l) => lines.push(l));
    d.deps.waitForAdb = async () => d.after;
    const r = await runInstall({ haUrl: 'http://homeassistant.local:8123', work }, d.deps);
    expect(lines.filter((l) => /^\n\[\d+\/\d+\]/.test(l))).toEqual([
      '\n[1/4] Wait for adb (first start takes a few minutes)',
      '\n[2/4] First boot: permissions, portrait, Home Assistant, Wi-Fi, the only home screen',
      '\n[3/4] HOME check',
      '\n[4/4] Screenshot',
    ]);
    expect(d.lk.commands).toEqual([]);
    expect(d.before.cmds).toEqual([]);
    expect(r.firstBoot?.home.result).toBe('set');
    expect(r.firstBoot?.rotation).toBe(3);
    expect(d.after.cmds).toContain('pm hide com.android.launcher3');
    expect(d.after.cmds.some((c) => c.includes('homeassistant.local'))).toBe(true);
    expect(r.screenshot).toBe(join(work, 'after.png'));
  });

  it('stops at another image\'s fingerprint, or without the app, before it changes anything', async () => {
    for (const [mirror, why] of [[new FirstBootMirror('other/image/fingerprint'), /runs other\/image\/fingerprint, not the image Kiln wrote/],
      [new FirstBootMirror(undefined, false), /isn't on the Mirror after the install/]] as const) {
      const d = dryDevices(0, () => {});
      d.deps.waitForAdb = async () => mirror;
      await expect(runInstall({ work }, d.deps)).rejects.toThrow(why);
      expect(mirror.cmds.filter((c) => /^(settings put|pm (grant|hide)|am start|appops)/.test(c))).toEqual([]);
    }
  });

  it('runs from its arguments without --image or --apk, and refuses --way-back with it', async () => {
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--first-boot', '--work', work], (l) => out.push(l))).toBe(0);
    const text = out.join('\n');
    expect(text).toMatch(/LK got 0\./);
    expect(text).toMatch(/Done: Glassworks 0\.5\.0-legacy, home set, rotation 3/);
    expect(text).not.toMatch(/WRITES/);
    await expect(cli(['--first-boot', '--way-back', '--work', work], () => {})).rejects.toThrow(/pick one/);
  });

  /** A host adb on a fake Mirror, as `adb -s <serial>` sees it: `devices` lists it as `state`; the shell answers getprop. */
  function fakeHostAdb(state: string, fingerprint = 'other/image/fingerprint') {
    const adb = join(work, 'adb'), calls = join(work, 'calls'), serial = join(work, 'serial');
    writeFileSync(adb, `#!/bin/sh
echo "$*" >> '${calls}'
shift 2
case "$1" in
  devices) printf 'List of devices attached\\nFAKESERIAL123\\t${state}\\n' ;;
  shell) case "$2" in *sys.boot_completed*) echo 1 ;; *ro.build.fingerprint*) echo '${fingerprint}' ;; esac; echo "__GWRC=0" ;;
  *) exit 1 ;;
esac
`);
    chmodSync(adb, 0o755);
    writeFileSync(serial, 'FAKESERIAL123\n');
    writeFileSync(calls, '');
    vi.stubEnv('ADB', adb); // never the host's real adb: hostAdb runs $ADB
    return { calls: () => readFileSync(calls, 'utf8'), args: ['--first-boot', '--yes', '--serial-file', serial, '--work', work] };
  }
  afterEach(() => { vi.unstubAllEnvs(); vi.useRealTimers(); });

  it('a real run reads the serial file and goes over adb only: it stops at the fingerprint, having written nothing', async () => {
    const host = fakeHostAdb('device');
    const out: string[] = [];
    await expect(cli(host.args, (l) => out.push(l))).rejects.toThrow(/runs other\/image\/fingerprint, not the image Kiln wrote/);
    expect(out[0]).toBe('The first boot only, over adb: no partition is written.');
    expect(host.calls()).toMatch(/shell getprop ro\.build\.fingerprint/);
    expect(host.calls()).not.toMatch(/reboot|push|install|settings put|pm hide/);
  });

  it('when adb never comes, the error names --first-boot (no device, or unauthorized)', async () => {
    const real = setTimeout;
    for (const [state, why] of [['offline', /didn't come up on adb in 6 minutes\. Once `adb devices` lists it .*run only the first boot with --first-boot/],
      ['unauthorized', /stayed unauthorized for 6 minutes: .*then run only the first boot with --first-boot: no reflash needed/]] as const) {
      const host = fakeHostAdb(state);
      vi.useFakeTimers({ toFake: ['setTimeout'] }); // the 6-minute wait, in fake time; adb is still a real process
      let settled = false;
      const run = cli(host.args, () => {}).finally(() => { settled = true; });
      run.catch(() => {});
      while (!settled) { await vi.advanceTimersByTimeAsync(2000); await new Promise((r) => real(r, 5)); }
      await expect(run).rejects.toThrow(why);
      vi.useRealTimers();
    }
  }, 60_000);
});
