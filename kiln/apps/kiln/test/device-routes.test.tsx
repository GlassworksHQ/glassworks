// @vitest-environment jsdom
// A device frame's addresses (#621): its install's steps from its installer.json, and the standard flow's after them.
// A load at a set-up step (a reload, a link) opens the standard flow there, connecting over adb; a load at an app flow's
// step opens the app alone; the install's tab mark brings a flashed Mirror straight to its first start.
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const path = vi.hoisted(() => ({ now: '/lululemon-mirror' }));
/** Every address the standard flow's router asked for (useRouteSync): a load that stays where it is asks for none. */
const navs = vi.hoisted(() => [] as string[]);
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: (u: string) => navs.push(`replace ${u}`), push: (u: string) => navs.push(`push ${u}`) }), usePathname: () => path.now }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { FakeMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { DeviceActivity } from '../src/device/activity';
import { AppSession } from '../src/device/appSession';
import { DevicePage, pageHandOver } from '../src/device/DevicePage';
import { ImageSession } from '../src/device/imageSession';
import { withAppStreams } from '../src/wizard/demo';
import { StandardSession } from '../src/standard/session';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: { getDevices: async () => [] } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); sessionStorage.clear(); history.replaceState(null, '', '/'); navs.length = 0; });

const at = (p: string) => { path.now = p; history.replaceState(null, '', `${p}?apk=local`); };

describe('a device frame\'s addresses', () => {
  it('a load at a set-up step opens the standard flow, and leaves the address there', async () => {
    at(`${MIRROR_BASE}/set-up/wifi`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await vi.waitFor(() => expect(registry.get()?.standard).toBe(true));
    expect(registry.get()!.session).toBeInstanceOf(StandardSession);
    expect(registry.get()!.base).toBe(MIRROR_BASE);
    expect(location.pathname).toBe(`${MIRROR_BASE}/set-up/wifi`); // never moved to the install's first screen
    expect(navs).toEqual([]); // nor by the router
    expect(screen.queryByText(/What goes on the Mirror/)).toBeNull();
  });

  it('a load at the app flow\'s step opens the app alone, its address kept', async () => {
    at(`${MIRROR_BASE}/app`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect(await screen.findByRole('button', { name: 'Connect' })).toBeTruthy();
    expect(location.pathname).toBe(`${MIRROR_BASE}/app`);
    expect(navs).toEqual([]);
  });

  it('a reload after the write and restart goes to the first start, at its address: no download, no fastboot', async () => {
    sessionStorage.setItem(`kiln:device:${MIRROR_BASE}:image`, JSON.stringify({ at: 'flashed', image: mirrorFrame.imageFlow.recipes[0]!.id }));
    at(`${MIRROR_BASE}/first-start`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect(await screen.findByRole('button', { name: 'Connect' })).toBeTruthy();
    expect(screen.queryByText(/Download the system image/)).toBeNull();
    expect(location.pathname).toBe(`${MIRROR_BASE}/first-start`);
  });

  it('a reload during the write says an earlier try changed the Mirror, on the image\'s first screen', async () => {
    sessionStorage.setItem(`kiln:device:${MIRROR_BASE}:image`, JSON.stringify({ at: 'written', image: mirrorFrame.imageFlow.recipes[0]!.id }));
    at(`${MIRROR_BASE}/install`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await vi.waitFor(() => expect(location.pathname).toBe(MIRROR_BASE)); // the address follows the screen it is on
  });
});

describe('sessions kept from an earlier visit', () => {
  it('hand over to today\'s page: the app alone\'s Done has its way back, and taking it lets go of the device', async () => {
    const m = new FakeMirror({ kiosk: 'app' });
    const app = new AppSession({ frame: mirrorFrame, connect: async () => withAppStreams(m, { online: true, radio: false }), sleep: async () => {}, handOver: pageHandOver(MIRROR_BASE) }, true);
    const image = new ImageSession({ frame: mirrorFrame, images: {} as never, requestFastboot: () => new Promise(() => {}), requestAdb: () => new Promise(() => {}), adbKey: () => undefined, apk: () => undefined });
    // made on a visit to the image path
    registry.keep(new DeviceActivity(MIRROR_BASE, mirrorFrame.name, { app, image }, true));
    at(`${MIRROR_BASE}/app`); // today: the app alone
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await app.connect();
    await app.pickApk('kiosk.apk', fakeMirrorApk());
    await app.install();
    const e = registry.get()!;
    expect(e.standard).toBe(true);
    expect(e.session.canUndo).toBe(true); // the app path's way back, not the image's (none on Done)
    await e.session.undo();
    expect(e.session.isClosed).toBe(true); // let go before the app's Connect opens it again
    await vi.waitFor(() => expect(location.pathname).toBe(`${MIRROR_BASE}/app`)); // back on the app's first screen
  });
});

describe('a community image after a reload at its last step', () => {
  it('comes back on it, with no rewrite: Connect says to switch Wi-Fi on in the image\'s Settings, its key gone with the restart', async () => {
    const community = mirrorFrame.imageFlow.recipes.find((r) => r.community)!;
    sessionStorage.setItem(`kiln:device:${MIRROR_BASE}:image`, JSON.stringify({ at: 'flashed', image: community.id }));
    const image = new ImageSession({ frame: mirrorFrame, images: {} as never, requestFastboot: () => new Promise(() => {}), requestAdb: () => new Promise(() => {}),
      adbKey: () => undefined, apk: () => undefined, progress: (await import('../src/device/imageSession')).sessionProgress(`kiln:device:${MIRROR_BASE}:image`) });
    expect(image.state).toMatchObject({ screen: 'adb', picked: community.id, changed: true });
    await image.connectAdb();
    expect(image.state.connectError).toMatch(/Turn Wi-Fi on in the Mirror's Settings once instead/);
  });
});
