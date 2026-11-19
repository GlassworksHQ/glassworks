// @vitest-environment jsdom
// "One last click." after the Mirror is flashed (Mirror run on #607, 2026-11-19): the Mirror refuses this browser's
// key while it is still starting, so Kiln asks again on its own; a first start that didn't finish (an error, a
// reload) can be finished again with no reflash; the screen says the glass stays dark; the log times each write.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { GLASSWORKS, SYSTEM_BYTES, USERDATA_BYTES, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { AdbBusy, type PanelShell } from '@glassworks/adb';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { AUTH_MS, FIRST_START_AUTH_MS, FlashSession, type ImageMaker } from '../src/mirror/flash';
import { Flash } from '../src/mirror/MirrorWizard';
import { MirrorSession } from '../src/mirror/session';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

/** What @yume-chan/adb throws when the Mirror refuses the key and the public-key fallback has nobody to approve it. */
const REFUSED = 'No authenticator can handle the request';

function setup(adb: { request: () => Promise<PanelShell>; reopen?: (o?: { authMs?: number }) => Promise<PanelShell>; tries?: number; picked?: () => boolean; windowMs?: number }) {
  const images: ImageMaker = {
    async fetch(_, onProgress) { onProgress(1); },
    async download() {},
    async file(name) { return new Blob([name]); },
    async check() {},
    async wifi() { throw new Error('not for Glassworks'); },
    async build(_, onProgress) { onProgress('patch', 1); onProgress('userdata', 1); return { system: new Blob(['SS']), userdata: new Blob(['U']) }; },
    async discard() {},
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? SYSTEM_BYTES : USERDATA_BYTES; },
    async flashBlob(_, __, onProgress = () => {}) { onProgress(1); },
    async reboot() {},
  };
  const mirror = new MirrorSession({ connect: async () => { throw new Error('unused'); } }, true);
  const f = new FlashSession({
    images, refract: 'https://proxy.test', requestFastboot: async () => fb, requestAdb: adb.request,
    ...(adb.reopen ? { reopenAdb: adb.reopen } : {}), ...(adb.tries !== undefined ? { adbTries: adb.tries } : {}), ...(adb.picked ? { adbPicked: adb.picked } : {}), ...(adb.windowMs !== undefined ? { adbWindowMs: adb.windowMs } : {}),
    adbKey: () => 'QAAA= kiln@glassworks', apk: () => mirror.apkBytes(), sleep: async () => {},
  });
  f.choose();
  return { f, mirror };
}

async function flashed(f: FlashSession, mirror: MirrorSession) {
  await f.download();
  await mirror.pickApk('kiosk.apk', fakeMirrorApk());
  f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
  expect(f.state.screen).toBe('adb');
}

describe('connecting while the Mirror is still starting', () => {
  it('a refused key means "not ready yet": Kiln asks again on the device it picked until it lets in', async () => {
    const adb = new FirstBootMirror();
    let refusals = 3;
    const reopen = vi.fn(async () => { if (refusals-- > 0) throw new Error(REFUSED); return adb; });
    const { f, mirror } = setup({ request: async () => { throw new Error(REFUSED); }, reopen });
    await flashed(f, mirror);
    const seen: boolean[] = [];
    f.subscribe(() => seen.push(!!f.state.adbWaiting));
    f.setHaUrl('http://homeassistant.local:8123');
    await f.connectAdb();
    expect(reopen).toHaveBeenCalledTimes(4);
    expect(seen).toContain(true); // the screen said it is still starting
    expect(f.state).toMatchObject({ screen: 'done', adbWaiting: false });
    expect(adb.cmds).toContain(`am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`);
    expect(f.state.log.filter((l) => l.includes('still starting. Asking again in 5 s.'))).toHaveLength(4);
  });

  it('a Mirror that never lets in ends with a clear message, and Connect works again', async () => {
    const reopen = vi.fn(async (): Promise<PanelShell> => { throw new Error(REFUSED); });
    const { f, mirror } = setup({ request: async () => { throw new Error(REFUSED); }, reopen, tries: 3 });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(reopen).toHaveBeenCalledTimes(3);
    expect(f.state).toMatchObject({ screen: 'adb', connecting: false, adbWaiting: false });
    expect(f.state.connectError).toBe(`The Mirror didn't let this browser in through its first start (${REFUSED}). It knows only the key of the browser that installed it: use that browser, then Connect again.`);
    render(<Flash flash={f} mirror={mirror} />);
    expect((screen.getByRole('button', { name: 'Connect' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('a click before the list opened (this browser\'s key still loading) isn\'t retried: Connect works again at once', async () => {
    const reopen = vi.fn();
    const KEY = 'Kiln was still making its key for this browser. Click Connect again.';
    const { f, mirror } = setup({ request: async () => { throw new Error(KEY); }, reopen, picked: () => false });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(reopen).not.toHaveBeenCalled();
    expect(f.state).toMatchObject({ connecting: false, adbWaiting: false, connectError: KEY });
  });

  it('a device another program holds isn\'t retried as "still starting": its own advice, at once', async () => {
    const reopen = vi.fn();
    const { f, mirror } = setup({ request: async () => { throw new AdbBusy(); }, reopen });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(reopen).not.toHaveBeenCalled();
    expect(f.state).toMatchObject({ connecting: false, adbWaiting: false });
    expect(f.state.connectError).toMatch(/another program holds the panel's adb connection: run "adb kill-server"/);
  });


  it('keeps asking only for its time window, each try given at most what is left of it', async () => {
    const asked: (number | undefined)[] = [];
    const reopen = vi.fn(async (o?: { authMs?: number }): Promise<PanelShell> => {
      asked.push(o?.authMs);
      await new Promise((r) => setTimeout(r, 30)); // a try that takes its time
      throw new Error(REFUSED);
    });
    const { f, mirror } = setup({ request: async () => { throw new Error(REFUSED); }, reopen, windowMs: 100 });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(reopen.mock.calls.length).toBeGreaterThan(0);
    expect(reopen.mock.calls.length).toBeLessThan(10); // the window ends it, not the 60 tries
    expect(asked.every((ms) => ms !== undefined && ms > 0 && ms <= Math.min(AUTH_MS, 100))).toBe(true);
    expect(f.state).toMatchObject({ connecting: false, adbWaiting: false });
    expect(f.state.connectError).toMatch(/didn't let this browser in through its first start/);
  });

  it('a picked Mirror briefly off USB (restarting) is asked again, and the first try has its own deadline', async () => {
    const adb = new FirstBootMirror();
    const request = vi.fn(async (): Promise<PanelShell> => { throw new Error('the device isn\'t on USB right now (it may be restarting)'); });
    const reopen = vi.fn(async () => adb as PanelShell);
    const { f, mirror } = setup({ request, reopen, picked: () => true });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(request).toHaveBeenCalledWith({ authMs: FIRST_START_AUTH_MS });
    expect(reopen).toHaveBeenCalledTimes(1);
    expect(f.state.screen).toBe('done');
  });

  it('a closed list is not retried', async () => {
    const reopen = vi.fn();
    const { f, mirror } = setup({ request: async () => { throw Object.assign(new Error('No device selected.'), { name: 'NotFoundError' }); }, reopen });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(reopen).not.toHaveBeenCalled();
    expect(f.state.connectError).toBe('Nothing was picked. Click Connect and pick the Mirror (Android) in the browser\'s list.');
  });

  it('says the glass stays dark during the first start', async () => {
    const { f, mirror } = setup({ request: async () => new FirstBootMirror() });
    await flashed(f, mirror);
    render(<Flash flash={f} mirror={mirror} />);
    expect(screen.getByText(/Its glass stays dark for a few minutes while it does: that.s expected, so leave it plugged in\./)).toBeTruthy();
  });
});

describe('finishing a first start that didn\'t finish', () => {
  it('from the image screen after a reload: no download, no fastboot, the same inputs', async () => {
    const adb = new FirstBootMirror();
    const { f, mirror } = setup({ request: async () => adb });
    render(<Flash flash={f} mirror={mirror} />);
    f.setHaUrl('not an address');
    expect(f.inputsBlocked()).toMatch(/http/);
    f.finishFirstStart();
    expect(f.state.screen).toBe('image'); // refused until the address is right
    f.setHaUrl('http://homeassistant.local:8123');
    fireEvent.click(await screen.findByRole('button', { name: 'Finish its first start' }));
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    expect(f.state.screen).toBe('done');
    expect(adb.cmds).toContain(`am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`);
  });

  it('from the error screen after a first start that stopped', async () => {
    const adb = new FirstBootMirror();
    let fail = true;
    const close = vi.fn(async () => {});
    const broken = Object.assign(Object.create(adb), { close, run: async (cmd: string) => { if (fail && cmd.startsWith('getprop')) throw new Error('the device was disconnected'); return adb.run(cmd); } }) as PanelShell;
    const { f, mirror } = setup({ request: async () => (fail ? broken : adb) });
    await flashed(f, mirror);
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'error', error: 'the device was disconnected' });
    render(<Flash flash={f} mirror={mirror} />);
    fail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try the first start again' }));
    expect(f.state.screen).toBe('adb');
    expect(close).toHaveBeenCalledTimes(1); // the stopped first start's connection, let go before Connect opens a new one
    await f.connectAdb();
    expect(f.state.screen).toBe('done');
  });
});

describe('a first start that stopped during the key hand-back', () => {
  it('offers the first start again, never fastboot: the Mirror runs Android', async () => {
    const { f, mirror } = setup({ request: async () => new FirstBootMirror() });
    await flashed(f, mirror);
    f.state = { ...f.state, screen: 'error', error: 'USB transfer failed', work: { step: 'hand-back', x: 0 } };
    render(<Flash flash={f} mirror={mirror} />);
    expect(screen.getByRole('button', { name: 'Try the first start again' })).toBeTruthy();
  });
});

describe('the writes, timed', () => {
  it('logs how long each build step, each send and each write took', async () => {
    const { f, mirror } = setup({ request: async () => new FirstBootMirror() });
    await flashed(f, mirror);
    const log = f.state.log.join('\n');
    expect(log).toMatch(/patch: built in \d+\.\d s/);
    expect(log).toMatch(/userdata: built in \d+\.\d s/);
    expect(log).toMatch(/system: sent 0\.0 MiB in \d+\.\d s, then the Mirror wrote it in \d+\.\d s/);
    expect(log).toMatch(/userdata: sent 0\.0 MiB in \d+\.\d s, then the Mirror wrote it in \d+\.\d s/);
  });
});
