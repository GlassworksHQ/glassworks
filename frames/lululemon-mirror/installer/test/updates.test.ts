import type { PanelShell } from '@glassworks/adb';
import { describe, expect, it } from 'vitest';
import { firstBoot, installMirror, parseMirrorUpdates, readMirrorUpdates } from '../src';
import { GLASSWORKS } from '../src/app';
import { FakeMirror, FirstBootMirror, fakeMirrorApk, packageDump } from './fakes';

const PKG = GLASSWORKS.package;
const noSleep = async () => {};

describe('silent updates on the Mirror, read back (#422)', () => {
  it('on for the image\'s privileged copy, which Android grants INSTALL_PACKAGES', () => {
    expect(parseMirrorUpdates(packageDump(PKG, 600, { privileged: true }), PKG)).toEqual({ state: 'on', privileged: true });
  });

  it('on for an update of that copy in /data, which keeps PRIVILEGED and the permission', () => {
    const updated = packageDump(PKG, 700, { privileged: true }).replace('codePath=/system/priv-app/Glassworks', `codePath=/data/app/${PKG}-1`);
    expect(parseMirrorUpdates(updated, PKG)).toEqual({ state: 'on', privileged: true });
  });

  it('reads the grant from the installed copy only, not the image\'s copy under Hidden system packages', () => {
    const image = packageDump(PKG, 600, { privileged: true }).replace('Packages:\n', '');
    const update = packageDump(PKG, 700, { privileged: true, granted: false }).replace('codePath=/system/priv-app/Glassworks', `codePath=/data/app/${PKG}-1`);
    expect(parseMirrorUpdates(`${update}\nHidden system packages:\n${image}`, PKG)).toEqual({ state: 'not-granted', privileged: true });
    const granted = packageDump(PKG, 700, { privileged: true }).replace('codePath=/system/priv-app/Glassworks', `codePath=/data/app/${PKG}-1`);
    expect(parseMirrorUpdates(`${granted}\nHidden system packages:\n${image}`, PKG)).toEqual({ state: 'on', privileged: true });
  });

  it('off for a normal app, installed over adb', () => {
    expect(parseMirrorUpdates(packageDump(PKG, 600), PKG)).toEqual({ state: 'normal-app', privileged: false });
  });

  it('not granted when the copy is privileged but Android didn\'t grant the permission', () => {
    expect(parseMirrorUpdates(packageDump(PKG, 600, { privileged: true, granted: false }), PKG)).toEqual({ state: 'not-granted', privileged: true });
  });

  it('unknown when the dump has no such package, the command fails, or adb drops', async () => {
    expect(parseMirrorUpdates('', PKG)).toEqual({ state: 'unknown', detail: `${PKG} isn't in dumpsys package` });
    const shell = (run: PanelShell['run']): PanelShell => ({ run, push: async () => {} });
    expect(await readMirrorUpdates(shell(async () => ({ out: 'Can\'t find service: package\n', code: 1 })))).toEqual({ state: 'unknown', detail: 'dumpsys exited 1' });
    expect(await readMirrorUpdates(shell(async () => { throw new Error('adb: device offline'); }))).toEqual({ state: 'unknown', detail: 'adb: device offline' });
  });

  it('the adb install reads it back: off for a first install, on when it updates the image\'s copy', async () => {
    const first = await installMirror(new FakeMirror(), { apk: fakeMirrorApk(), sleep: noSleep });
    expect(first.updates).toEqual({ state: 'normal-app', privileged: false });
    const over = await installMirror(new FakeMirror({ installed: 500, privileged: true }), { apk: fakeMirrorApk(), sleep: noSleep });
    expect(over.updates).toEqual({ state: 'on', privileged: true });
  });

  it('the first boot after the fastboot install reads it back, and logs what it read', async () => {
    const lines: string[] = [];
    const r = await firstBoot(new FirstBootMirror(), { onStep: (s, d) => lines.push(s || d || ''), sleep: noSleep });
    expect(r.updates).toEqual({ state: 'on', privileged: true });
    expect(lines).toContain('silent updates: on (privileged system app, INSTALL_PACKAGES granted)');
    const refused = new FirstBootMirror();
    refused.notGranted = true;
    expect((await firstBoot(refused, { sleep: noSleep })).updates).toEqual({ state: 'not-granted', privileged: true });
  });
});
