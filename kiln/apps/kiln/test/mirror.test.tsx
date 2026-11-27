// @vitest-environment jsdom
// /lululemon-mirror: off for everyone without ?apk=local (or ?apk=release, test/mirror-release.test.tsx), a test install with it, and every demo
// screen on the real recipe behind a simulated Mirror.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { FakeMirror, MIRROR_KIOSK, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { APP_DEMOS, appDemo, type AppDemo } from '../src/device/demo';
import { DevicePage, AppFlow, keyedConnect } from '../src/device/DevicePage';
import { AppSession, deviceMode, deviceOpen } from '../src/device/appSession';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, appOn, mirrorEntry, mirrorFrame } from './mirror-frame';
import { showApp } from './device-page';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); });

const until = async (f: () => boolean, ms = 10_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
  expect(f()).toBe(true);
};
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('the switch', () => {
  it('is on for everyone, the release by default (beta), and demos only where demos run', () => {
    expect(deviceMode('', true, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' });
    expect(deviceMode('?apk=other', true, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' });
    expect(deviceMode('?apk=release', false, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' });
    expect(deviceMode('?apk=local', false, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'local' });
    expect(deviceMode('?demo=checks', true, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, demo: 'checks' });
    expect(deviceMode('?demo=checks', false, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' }); // a production build
    expect(deviceMode('?demo=nope', true, APP_DEMOS, deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' });
  });

  it('without it, the page opens on the choice, for everyone (the locked page: device-locked.test.tsx)', async () => {
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(screen.queryByText(/doesn.t install from Kiln yet/)).toBeNull();
    expect(screen.queryByText('Test only')).toBeNull();
  });

  it('with it, the choice starts on the recommended way (on the stock software), and the full image over fastboot is one click away', async () => {
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(screen.queryByText('Test only')).toBeNull(); // beta (frame.json): open to everyone, not a test page
    expect(embers()).toEqual(['Continue with Glassworks on the stock software']);
    fireEvent.click(screen.getByRole('radio', { name: 'Glassworks' }));
    expect(embers()).toEqual(['Continue with Glassworks']);
    fireEvent.click(screen.getByRole('button', { name: 'Continue with Glassworks' }));
    await screen.findByText('Install Glassworks on the Mirror');
    expect(embers()).toEqual(['Download the system image']);
  });

  it('with &mode=app, the app alone over adb, its APK from this computer, not labelled test only', async () => {
    history.replaceState(null, '', '/lululemon-mirror?apk=local&mode=app');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Install a test build on the Mirror');
    expect(screen.queryByText('Test only')).toBeNull(); // beta (frame.json): open to everyone, not a test page
    expect(document.querySelector('input[type=file]')).not.toBeNull();
    expect(embers()).toEqual(['Connect']);
  });
});

describe('the test install', () => {
  it('reads the Mirror, installs, sets up, and undoes back to the home app it had', async () => {
    const m = new FakeMirror({ kiosk: 'app' }); // the community kiosk with its device admin off, so Android hides it
    const { s, setUp, standard } = appOn(m);
    await s.connect();
    expect(s.state.screen).toBe('checks');
    expect(s.installBlocked()).toMatch(/APK/);
    const apk = fakeMirrorApk();
    await s.pickApk('kiosk-legacy-debug.apk', apk);
    expect(s.state.apk).toMatchObject({ name: 'kiosk-legacy-debug.apk', size: apk.length, package: GLASSWORKS.package });
    expect(s.state.apk?.sha256).toMatch(/^[0-9a-f]{64}$/);
    await s.install();
    // then the standard flow, as every frame's: its App step first, on the Mirror the install handed over
    expect(s.state).toMatchObject({ screen: 'pick', result: { flow: 'install', installed: { home: 'set', previousHome: MIRROR_KIOSK } } });
    expect(standard().store.state).toMatchObject({ screen: 'setup', setup: { phase: 'settings', apply: true } });
    expect(m.cmds).toContain(`am start -n ${GLASSWORKS.activity}`);
    expect(m.cmds.filter((c) => c.includes('--es url'))).toEqual([]); // Home Assistant is set-up's, not the install's
    await setUp();
    expect(standard().store.state.screen).toBe('done');
    await vi.waitFor(() => expect(standard().store.state.done?.managePage).toBe('http://192.0.2.50:8099/'));
    // Done's way back: the app path's first screen, Connect, then Undo on the checks
    await s.connect();
    expect(s.state.report?.app).toBeDefined();
    await s.undo();
    expect(s.state).toMatchObject({ screen: 'done', result: { flow: 'undo', removed: { removed: true, home: 'set' } } });
    expect(m.installed).toBeUndefined();
    expect(m.focused).toBe(MIRROR_KIOSK);
  });

  it('the latest APK picked wins, however long the earlier one takes to read', async () => {
    const s = new AppSession({ frame: mirrorFrame, connect: async () => new FakeMirror() }, true);
    let release!: (b: Uint8Array) => void;
    const slow = s.pickApk('big-old.apk', new Promise<Uint8Array>((r) => { release = r; }));
    await s.pickApk('kiosk-legacy-debug.apk', fakeMirrorApk());
    release(fakeApk('com.example.other'));
    await slow;
    expect(s.state.apk).toMatchObject({ name: 'kiosk-legacy-debug.apk', package: GLASSWORKS.package });
  });

  it('a file that isn\'t Glassworks is named and blocks Install; the inputs stay editable on Checks', async () => {
    const s = await appDemo(mirrorEntry, 'pick-wrong', true);
    expect(s.state.apk).toMatchObject({ package: 'com.nimrod.mirrorkiosk', error: expect.stringMatching(/not Glassworks/) });
    // a build with no home screen (the first real flash, 2026-11-16, had it off): refused at the picker, before any write
    await s.pickApk('kiosk-legacy-test.apk', fakeMirrorApk({ home: 'none' }));
    expect(s.state.apk).toMatchObject({ package: GLASSWORKS.package, error: expect.stringMatching(/can't be the Mirror's home screen: it has no home screen.*pick the legacy build of Glassworks for generic Android$/) });
    expect(s.apkBytes()).toBeUndefined();
    await s.connect();
    expect(s.installBlocked()).toMatch(/Pick another file/);
    render(<AppFlow session={s} />);
    expect(document.querySelector('input[type=file]')).not.toBeNull(); // on Checks
    await act(() => s.pickApk('kiosk-legacy-debug.apk', fakeMirrorApk()));
    expect(s.installBlocked()).toBeUndefined();
  });

  it('after an undo that couldn\'t confirm the home app, Checks offers undo again', async () => {
    const s = await appDemo(mirrorEntry, 'undo-pending', true);
    expect(s.state).toMatchObject({ screen: 'checks', report: { pendingHome: MIRROR_KIOSK } });
    render(<AppFlow session={s} />);
    expect(screen.getByText('Give the home screen back')).toBeTruthy();
  });

  it('Connect asks for the device inside the click, before anything is awaited', () => {
    const connect = vi.fn(() => new Promise<never>(() => {}));
    const s = new AppSession({ frame: mirrorFrame, connect }, true);
    void s.connect();
    expect(connect).toHaveBeenCalledTimes(1);
  });

  it('the first try has the frame\'s deadline too, and the Mirror is asked again after it (#621)', async () => {
    const connect = vi.fn(async (_o?: { authMs?: number }) => { throw new Error('the device didn\'t let this browser\'s adb key in'); });
    const reopen = vi.fn(async () => withApp());
    const withApp = () => new FakeMirror({ kiosk: 'none' });
    const s = new AppSession({ frame: mirrorFrame, connect, reopen, picked: () => true, sleep: async () => {} }, true);
    await s.connect();
    expect(connect).toHaveBeenCalledWith(expect.objectContaining({ authMs: mirrorFrame.timings.authMs }));
    expect(reopen).toHaveBeenCalledTimes(1);
    expect(s.state).toMatchObject({ screen: 'checks', connecting: false });
  });

  it('the key loads with the page, so the click goes straight to the device list', async () => {
    const connect = vi.fn(() => new Promise<never>(() => {}));
    const go = keyedConnect(async () => ({}) as never, connect);
    await expect(go()).rejects.toThrow(/finishing its one-time browser setup/); // clicked before the key was ready: says so
    const broken = keyedConnect(async () => { throw new Error('indexedDB is not defined'); }, connect);
    await new Promise((r) => setTimeout(r, 0)); // no unhandled rejection while nobody has clicked
    await expect(broken()).rejects.toThrow(/indexedDB is not defined/);
    void go();
    expect(connect).toHaveBeenCalledTimes(1); // no await in front of the device request
  });

  it('an APK that can\'t be read says so', async () => {
    const s = new AppSession({ frame: mirrorFrame, connect: async () => new FakeMirror() }, true);
    await s.pickApk('gone.apk', Promise.reject(new Error('NotFoundError')));
    expect(s.state.apk).toMatchObject({ name: 'gone.apk', error: expect.stringMatching(/couldn't be read \(NotFoundError\)/) });
  });

  it('reading the Mirror again when it went away goes back to Connect and says why', async () => {
    const m = new FakeMirror({ installed: 600 });
    const s = new AppSession({ frame: mirrorFrame, connect: async () => m, sleep: async () => {} }, true);
    await s.connect();
    await s.undo();
    m.run = async () => { throw new Error('USB transfer failed'); };
    await s.again();
    expect(s.state).toMatchObject({ screen: 'pick', flow: undefined, result: undefined, connectError: expect.stringMatching(/went away: USB transfer failed/) });
  });

  it('reading the Mirror again twice reads it once, and never lands on a running install', async () => {
    const m = new FakeMirror();
    const { s } = appOn(m);
    await s.connect();
    await s.pickApk('kiosk.apk', fakeMirrorApk());
    const reads = () => m.cmds.filter((c) => c === 'getprop ro.build.version.sdk').length;
    const before = reads();
    await Promise.all([s.again(), s.again()]);
    expect(reads() - before).toBe(1);
    const late = s.again(); // still reading when the install starts
    const install = s.install();
    await late;
    expect(s.state.screen).toBe('working');
    await install;
    expect(s.state).toMatchObject({ screen: 'pick', result: { flow: 'install' } }); // handed to the standard flow
  });

  it('a slow read of the Mirror that ends after a whole install doesn\'t replace its hand-over, even when it fails', async () => {
    for (const fails of [false, true]) {
      const m = new FakeMirror();
      const { s, setUp, standard } = appOn(m);
      await s.connect();
      await s.pickApk('kiosk.apk', fakeMirrorApk());
      let release!: () => void;
      const gate = new Promise<void>((r) => { release = r; });
      const run = m.run.bind(m);
      let held = false;
      m.run = async (cmd: string) => {
        if (!held && cmd === 'getprop ro.build.version.sdk') { held = true; await gate; if (fails) throw new Error('USB transfer failed'); }
        return run(cmd);
      };
      const reread = s.again();
      await s.install();
      await setUp();
      expect(standard().store.state.screen).toBe('done');
      release();
      await reread;
      expect(s.state).toMatchObject({ screen: 'pick', result: { flow: 'install' } });
      expect(s.state.connectError).toBeUndefined();
      expect(standard().store.state.screen).toBe('done');
    }
  });

  it('Connect stays off until the Mirror\'s check settles; a second click does nothing meanwhile', async () => {
    const m = new FakeMirror();
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const run = m.run.bind(m);
    m.run = async (cmd: string) => { if (cmd === 'getprop ro.build.version.sdk') await gate; return run(cmd); };
    const connect = vi.fn(async () => m);
    const s = new AppSession({ frame: mirrorFrame, connect }, true);
    const first = s.connect();
    await new Promise((r) => setTimeout(r, 0));
    expect(s.state.connecting).toBe(true);
    await s.connect();
    expect(connect).toHaveBeenCalledTimes(1);
    release();
    await first;
    expect(s.state).toMatchObject({ connecting: false, screen: 'checks' });
  });

  it('a refused install ends on the error screen with the reason', async () => {
    const s = await appDemo(mirrorEntry, 'error', true);
    expect(s.state).toMatchObject({ screen: 'error' });
    expect(s.state.error).toMatch(/legacy build/);
  });

  it('a failed connection stays on the first screen and says why', async () => {
    const s = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('another program holds the panel\'s adb connection'); } }, true);
    await s.connect();
    expect(s.state).toMatchObject({ screen: 'pick', connectError: expect.stringMatching(/another program/) });
  });
});

describe('every demo screen', () => {
  it.each(APP_DEMOS.filter((d) => d !== 'run'))('%s has at most one ember button', async (d) => {
    const s = await appDemo(mirrorEntry, d as AppDemo, true);
    expect(s.state.screen).not.toBe('off');
    render(<AppFlow session={s} />);
    await until(() => !!document.querySelector('.card'));
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('no screen asks for a tap on the Mirror: it has no touchscreen (Mirror run, 2026-11-19)', async () => {
    for (const d of APP_DEMOS.filter((x) => x !== 'run')) {
      await showApp(d as AppDemo);
      await until(() => !!document.querySelector('.card'));
      expect(document.body.textContent, d).not.toMatch(/Your turn|then Always|Clear defaults/);
      cleanup();
    }
  }, 60_000);

  it('Done is the standard flow\'s; with a device admin\'s home app Android won\'t hide, it says to install over fastboot', async () => {
    await showApp('done');
    await screen.findByText('The Mirror is on Wi-Fi at 192.0.2.50. Home Assistant finds it by itself.');
    expect(screen.queryByText(/device administrator/)).toBeNull();
    cleanup();
    await showApp('done-unconfirmed');
    await screen.findByText(/^com\.nimrod\.mirrorkiosk is a device administrator, so Glassworks can.t be the only home screen over adb\. Install Glassworks over fastboot instead/);
  });
});
