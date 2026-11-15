// @vitest-environment jsdom
// /lululemon-mirror: off for everyone without ?apk=local, a test install with it, and every demo
// screen on the real recipe behind a simulated Mirror.
import { act, cleanup, render, screen } from '@testing-library/react';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { FakeMirror, MIRROR_KIOSK } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { MIRROR_DEMOS, mirrorDemo, type MirrorDemo } from '../src/mirror/demo';
import { MirrorWizard, Screens } from '../src/mirror/MirrorWizard';
import { MirrorSession, mirrorMode } from '../src/mirror/session';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); history.replaceState(null, '', '/'); });

const until = async (f: () => boolean, ms = 10_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
  expect(f()).toBe(true);
};
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('the switch', () => {
  it('is off without ?apk=local, and demos only where demos run', () => {
    expect(mirrorMode('', true, MIRROR_DEMOS)).toEqual({ on: false });
    expect(mirrorMode('?apk=release', true, MIRROR_DEMOS)).toEqual({ on: false });
    expect(mirrorMode('?apk=local', false, MIRROR_DEMOS)).toEqual({ on: true });
    expect(mirrorMode('?demo=checks', true, MIRROR_DEMOS)).toEqual({ on: true, demo: 'checks' });
    expect(mirrorMode('?demo=checks', false, MIRROR_DEMOS)).toEqual({ on: false }); // a production build
    expect(mirrorMode('?demo=nope', true, MIRROR_DEMOS)).toEqual({ on: false });
  });

  it('without it, the page says the Mirror doesn\'t install yet and offers no file picker', async () => {
    render(<MirrorWizard />);
    await screen.findByText(/doesn.t install from Kiln yet/);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual([]);
  });

  it('with it, the APK comes from this computer, labelled test only', async () => {
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    render(<MirrorWizard />);
    await screen.findByText('Install a test build on the Mirror.');
    expect(screen.getByText('Test only')).toBeTruthy();
    expect(document.querySelector('input[type=file]')).not.toBeNull();
    expect(embers()).toEqual(['Connect']);
  });
});

describe('the test install', () => {
  it('reads the Mirror, installs, and undoes back to the home app it had', async () => {
    const m = new FakeMirror({ picks: (c) => c.find((x) => x.startsWith('sh.edm.s1kiosk/')) ?? c.find((x) => x === MIRROR_KIOSK) });
    const s = new MirrorSession({ connect: async () => m, sleep: async () => {} }, true);
    await s.connect();
    expect(s.state.screen).toBe('checks');
    expect(s.installBlocked()).toMatch(/APK/);
    const apk = fakeApk('sh.edm.s1kiosk');
    await s.pickApk('kiosk-legacy-debug.apk', apk);
    expect(s.state.apk).toMatchObject({ name: 'kiosk-legacy-debug.apk', size: apk.length, package: 'sh.edm.s1kiosk' });
    expect(s.state.apk?.sha256).toMatch(/^[0-9a-f]{64}$/);
    s.setHaUrl('homeassistant.local:8123');
    expect(s.installBlocked()).toMatch(/http/);
    s.setHaUrl('http://homeassistant.local:8123');
    await s.install();
    expect(s.state).toMatchObject({ screen: 'done', result: { flow: 'install', installed: { home: 'set', previousHome: MIRROR_KIOSK } } });
    expect(m.cmds).toContain(`am start -n sh.edm.s1kiosk/.MainActivity --es url 'http://homeassistant.local:8123'`);
    await s.again();
    expect(s.state.report?.app).toBeDefined();
    await s.undo();
    expect(s.state).toMatchObject({ screen: 'done', result: { flow: 'undo', removed: { removed: true, home: 'set' } } });
    expect(m.installed).toBeUndefined();
    expect(m.preferred?.comp).toBe(MIRROR_KIOSK);
  });

  it('the latest APK picked wins, however long the earlier one takes to read', async () => {
    const s = new MirrorSession({ connect: async () => new FakeMirror() }, true);
    let release!: (b: Uint8Array) => void;
    const slow = s.pickApk('big-old.apk', new Promise<Uint8Array>((r) => { release = r; }));
    await s.pickApk('kiosk-legacy-debug.apk', fakeApk('sh.edm.s1kiosk'));
    release(fakeApk('com.example.other'));
    await slow;
    expect(s.state.apk).toMatchObject({ name: 'kiosk-legacy-debug.apk', package: 'sh.edm.s1kiosk' });
  });

  it('a file that isn\'t S1 Kiosk is named and blocks Install; the inputs stay editable on Checks', async () => {
    const s = await mirrorDemo('pick-wrong', true);
    expect(s.state.apk).toMatchObject({ package: 'com.nimrod.mirrorkiosk', error: /not S1 Kiosk/ });
    await s.connect();
    expect(s.installBlocked()).toMatch(/Pick another file/);
    render(<Screens session={s} />);
    expect(document.querySelector('input[type=file]')).not.toBeNull(); // on Checks
    await act(() => s.pickApk('kiosk-legacy-debug.apk', fakeApk('sh.edm.s1kiosk')));
    expect(s.installBlocked()).toBeUndefined();
  });

  it('after an undo that couldn\'t confirm the home app, Checks offers undo again', async () => {
    const s = await mirrorDemo('undo-pending', true);
    expect(s.state).toMatchObject({ screen: 'checks', report: { pendingHome: MIRROR_KIOSK } });
    render(<Screens session={s} />);
    expect(screen.getByText('Undo: give the home screen back')).toBeTruthy();
  });

  it('a refused install ends on the error screen with the reason', async () => {
    const s = await mirrorDemo('error', true);
    expect(s.state).toMatchObject({ screen: 'error' });
    expect(s.state.error).toMatch(/legacy build/);
  });

  it('a failed connection stays on the first screen and says why', async () => {
    const s = new MirrorSession({ connect: async () => { throw new Error('another program holds the panel\'s adb connection'); } }, true);
    await s.connect();
    expect(s.state).toMatchObject({ screen: 'pick', connectError: /another program/ });
  });
});

describe('every demo screen', () => {
  it.each(MIRROR_DEMOS.filter((d) => d !== 'run'))('%s has at most one ember button', async (d) => {
    const s = await mirrorDemo(d as MirrorDemo, true);
    expect(s.state.screen).not.toBe('off');
    render(<Screens session={s} />);
    await until(() => !!document.querySelector('.card'));
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('an update with another app kept as home asks for Clear defaults first', async () => {
    render(<Screens session={await mirrorDemo('home-clear', true)} />);
    await screen.findByText('Choose Clear defaults.');
  });

  it('home waits for the person\'s tap on the Mirror', async () => {
    render(<Screens session={await mirrorDemo('home', true)} />);
    await screen.findByText(/Your turn, on the Mirror/);
    expect(screen.getByText(/then Always/).textContent).toBe('Choose S1 Kiosk, then Always.');
  });
});
