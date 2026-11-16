// The root's headless run (flash-node.ts) end to end on a fake Mirror: the real Fastboot class on
// FakeLk, the installer's adb fakes, and what lands on the fake partitions judged by e2fsck. The CLI's
// own --dry-run is the same code; the last tests run it from its arguments.
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { clean, debugfs, debugfsCat, fsck, haveTools } from '@glassworks/ext4/testing';
import { FakeMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import { hostAdb } from '../src/adb';
import { UsageError, cli } from '../src/cli';
import { DRY_USERDATA, dryDevices, dryImage } from '../src/dry';
import { runInstall, runWayBack } from '../src/run';

const KEY = 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= root@host';
const APK = fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'arm64-v8a': { 'libgecko.so': new Uint8Array(5000).fill(7) } } });

let work: string;
beforeEach(() => { work = mkdtempSync(join(tmpdir(), 'gw-mirror-test-')); });
afterEach(() => rmSync(work, { recursive: true, force: true }));

async function install(o: { hideFails?: boolean; confirm?: (q: string) => boolean; stockAtStart?: boolean } = {}) {
  const img = await dryImage(work, APK.length);
  const lines: string[] = [];
  const d = dryDevices(img.bytes, (l) => lines.push(l), o);
  const asked: string[] = [];
  if (o.confirm) d.deps.confirm = async (q) => { asked.push(q); return o.confirm!(q); };
  const run = runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: KEY, haUrl: 'http://homeassistant.local:8123', work }, d.deps);
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
    expect(debugfs(sys, 'ls /priv-app')).toContain('S1Kiosk');
    expect(new TextDecoder().decode(debugfsCat(ud, '/misc/adb/adb_keys'))).toBe(`${KEY}\n`);
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
    await expect(runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: bad, adbKey: KEY, work }, first.deps)).rejects.toThrow(/not S1 Kiosk/);
    expect(first.lk.commands.some((c) => c.startsWith('flash:'))).toBe(false);
    const lines: string[] = [];
    const again = dryDevices(img.bytes, (l) => lines.push(l));
    await runInstall({ image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, apk: APK, adbKey: KEY, work }, again.deps);
    expect(lines.some((l) => /read and checked 100%/.test(l))).toBe(true);
    expect(again.lk.commands.filter((c) => c.startsWith('flash:'))).toEqual(['flash:system', 'flash:userdata']);
  }, 60_000);

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
    expect(text).toMatch(/Done: S1 Kiosk 0\.5\.0-legacy, home set/);
    expect(readFileSync(join(work, 'after.png')).subarray(1, 4).toString()).toBe('PNG');
  }, 60_000);

  it('the way back too, and refuses a real run without its files', async () => {
    const out: string[] = [];
    expect(await cli(['--dry-run', '--yes', '--way-back', '--work', work], (l) => out.push(l))).toBe(0);
    expect(out.join('\n')).toMatch(/Done: the plain Glass Panes image is on the Mirror/);
    await expect(cli(['--way-back', '--work', work], () => {})).rejects.toBeInstanceOf(UsageError);
    await expect(cli(['--image', 'x.img', '--work', work], () => {})).rejects.toThrow(/--apk/);
  }, 60_000);
});
