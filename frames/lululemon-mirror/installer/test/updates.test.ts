import type { PanelShell } from '@glassworks/adb';
import { describe, expect, it } from 'vitest';
import { describeMirrorUpdates, firstBoot, installMirror, isDeviceOwner, parseMirrorUpdates, readMirrorUpdates } from '../src';
import { GLASSWORKS } from '../src/app';
import { FakeMirror, FirstBootMirror, fakeMirrorApk, packageDump } from './fakes';

const PKG = GLASSWORKS.package;
const noSleep = async () => {};

/** Android 6's `dumpsys device_policy` with `pkg` as the Device Owner (stock has no such block). */
function deviceOwnerDump(pkg: string): string {
  return `Current Device Policy Manager state:\n  Device Owner: \n    admin=ComponentInfo{${pkg}/dev.glassworks.AdminReceiver}\n    name=\n    package=${pkg}\n  mUserData:\n`;
}

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

  it('passes the read its signal: a read cut off by it is not confirmed', async () => {
    let got: AbortSignal | undefined;
    const ac = new AbortController();
    const stalls: PanelShell = { push: async () => {}, run: (_cmd, signal) => { got = signal; return new Promise((_, reject) => signal?.addEventListener('abort', () => reject(new Error('the panel did not answer in time')))); } };
    const read = readMirrorUpdates(stalls, undefined, ac.signal);
    ac.abort();
    expect(await read).toEqual({ state: 'unknown', detail: 'the panel did not answer in time' });
    expect(got).toBe(ac.signal);
  });

  it('the adb install reads it back: off for a first install, on when it updates the image\'s copy', async () => {
    const first = await installMirror(new FakeMirror(), { apk: fakeMirrorApk(), sleep: noSleep });
    expect(first.updates).toEqual({ state: 'normal-app', privileged: false });
    const over = await installMirror(new FakeMirror({ installed: 500, privileged: true }), { apk: fakeMirrorApk(), sleep: noSleep });
    expect(over.updates).toEqual({ state: 'on', privileged: true });
  });

  it('recognizes a Device Owner as silent (the Stock-in-place path), even a normal /data app without INSTALL_PACKAGES', () => {
    // a plain app in /data (no priv-app, no INSTALL_PACKAGES) that is the Device Owner: updates are silent
    expect(parseMirrorUpdates(packageDump(PKG, 600), PKG, deviceOwnerDump(PKG)))
      .toEqual({ state: 'on', privileged: false, deviceOwner: true });
    // privileged AND Device Owner: still on, deviceOwner noted
    expect(parseMirrorUpdates(packageDump(PKG, 600, { privileged: true, granted: false }), PKG, deviceOwnerDump(PKG)))
      .toEqual({ state: 'on', privileged: true, deviceOwner: true });
    // a Device Owner block naming a different package does not count
    expect(parseMirrorUpdates(packageDump(PKG, 600), PKG, deviceOwnerDump('com.other.app')))
      .toEqual({ state: 'normal-app', privileged: false });
  });

  it('isDeviceOwner reads the Device Owner block from dumpsys device_policy and stops before Profile Owner', () => {
    expect(isDeviceOwner(deviceOwnerDump(PKG), PKG)).toBe(true);
    expect(isDeviceOwner(deviceOwnerDump('com.other.app'), PKG)).toBe(false);
    expect(isDeviceOwner('Current Device Policy Manager state:\n  Enabled Device Admins (User 0):\n', PKG)).toBe(false);
    expect(isDeviceOwner('', PKG)).toBe(false);
    const otherDoOurPo = [
      'Current Device Policy Manager state:',
      '  Device Owner:',
      '    admin=ComponentInfo{com.other.app/com.other.Admin}',
      '    package=com.other.app',
      '  Profile Owner (User 10):',
      `    admin=ComponentInfo{${PKG}/dev.glassworks.AdminReceiver}`,
      `    package=${PKG}`,
    ].join('\n');
    expect(isDeviceOwner(otherDoOurPo, PKG)).toBe(false);
    expect(parseMirrorUpdates(packageDump(PKG, 600), PKG, otherDoOurPo))
      .toEqual({ state: 'normal-app', privileged: false });
  });

  it('describeMirrorUpdates names the Device Owner path', () => {
    const u = parseMirrorUpdates(packageDump(PKG, 600), PKG, deviceOwnerDump(PKG));
    expect(describeMirrorUpdates(u)).toBe('silent updates: on (Device Owner)');
  });

  it('readMirrorUpdates reads dumpsys device_policy and reports a Device Owner as on', async () => {
    const shell = (pkgDump: string, policy: string): PanelShell => ({
      push: async () => {},
      run: async (cmd) => (cmd.startsWith('dumpsys device_policy')
        ? { out: policy, code: 0 }
        : { out: pkgDump, code: 0 }),
    });
    expect(await readMirrorUpdates(shell(packageDump(PKG, 600), deviceOwnerDump(PKG))))
      .toEqual({ state: 'on', privileged: false, deviceOwner: true });
    // device_policy read failing is best-effort: fall back to the package dump's answer
    const noPolicy: PanelShell = { push: async () => {}, run: async (cmd) => (cmd.startsWith('dumpsys device_policy') ? { out: '', code: 1 } : { out: packageDump(PKG, 600), code: 0 }) };
    expect(await readMirrorUpdates(noPolicy)).toEqual({ state: 'normal-app', privileged: false });
    // cancellation during device_policy read propagates as unknown instead of falling back
    const ac = new AbortController();
    const abortOnPolicy: PanelShell = {
      push: async () => {},
      run: async (cmd, signal) => {
        if (cmd.startsWith('dumpsys device_policy')) {
          ac.abort();
          throw new Error('aborted during policy read');
        }
        expect(signal).toBe(ac.signal);
        return { out: packageDump(PKG, 600), code: 0 };
      },
    };
    expect(await readMirrorUpdates(abortOnPolicy, undefined, ac.signal))
      .toEqual({ state: 'unknown', detail: 'aborted during policy read' });
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
