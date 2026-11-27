// @vitest-environment jsdom
// A device frame's addresses (#621): its install's steps from its installer.json, and the standard flow's after them.
// A load at a set-up step (a reload, a link) opens the standard flow there, connecting over adb; a load at an app flow's
// step opens the app alone; the install's tab mark brings a flashed Mirror straight to its first start.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
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
    // the resume screen first: where it stopped, and Continue to the step the mark resumes on
    expect(await screen.findByRole('heading', { name: 'Pick up where you left off' })).toBeTruthy();
    expect(screen.getByText('Only its first start is left.')).toBeTruthy();
    expect(location.pathname).toBe(`${MIRROR_BASE}/first-start`);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
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

  it('a load at the in-place flow\'s fastboot step, with nothing to resume, opens that flow on its first screen', async () => {
    at(`${MIRROR_BASE}/stock/fastboot`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    const image = await vi.waitFor(() => { const e = registry.activity(MIRROR_BASE) as DeviceActivity | undefined; expect(e).toBeTruthy(); return e!.sessions.image; });
    expect(image.inPlace).toBe(true);
    // the flow's first screen, where its inputs are, on its route: never the Glassworks image's
    await vi.waitFor(() => expect(location.pathname).toBe(`${MIRROR_BASE}/stock`));
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

describe('the credits on set-up\'s Done', () => {
  it('are the ones the install stood on: none after an in-place install, the frame\'s otherwise, and a reload keeps them', async () => {
    const credits = () => (registry.get()!.session as unknown as { standard: { credits: unknown } }).standard.credits;
    at(MIRROR_BASE);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    const m = new FakeMirror({ kiosk: 'app' });
    await act(async () => { pageHandOver(MIRROR_BASE)(m, { credits: {} }); });
    expect(credits()).toEqual({}); // Glass Panes wasn't written
    expect(sessionStorage.getItem(`kiln:standard:${MIRROR_BASE}:credits`)).toBe('{}');
    await act(async () => { pageHandOver(MIRROR_BASE)(m); });
    expect(credits()).toEqual(mirrorFrame.credits);
    expect(sessionStorage.getItem(`kiln:standard:${MIRROR_BASE}:credits`)).toBeNull();
  });

  it('are right with the tab\'s storage off: the hand-over carries them, not storage', async () => {
    const credits = () => (registry.get()!.session as unknown as { standard: { credits: unknown } }).standard.credits;
    const off = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    const offGet = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    try {
      at(MIRROR_BASE);
      render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
      const m = new FakeMirror({ kiosk: 'app' });
      await act(async () => { pageHandOver(MIRROR_BASE)(m, { credits: {} }); });
      expect(credits()).toEqual({});
    } finally {
      off.mockRestore();
      offGet.mockRestore();
    }
  });
});

describe('a community image written as published', () => {
  it('leaves no mark once written: nothing over adb is left, so a reload has nothing to resume', async () => {
    const { imageDemo } = await import('../src/device/imageDemo');
    const marks: unknown[] = [];
    const progress = { get: () => marks.at(-1) as never, set: (m: unknown) => { marks.push(m); } };
    const { image } = await imageDemo(mirrorEntry, 'image-done', true, { progress });
    expect(image.state.screen).toBe('done');
    // never `flashed`, not even while the downloads are cleaned up: a reload then would wait for a step that isn't coming
    expect(marks.some((m) => (m as { at?: string } | undefined)?.at === 'flashed')).toBe(false);
    expect(marks.at(-1)).toBeUndefined();
  });
});

describe('a community image after a reload at its last step', () => {
  it('comes back on it, with no rewrite: Connect switches Wi-Fi on with this browser\'s key, which the install put in its userdata', async () => {
    const community = mirrorFrame.imageFlow.recipes.find((r) => r.community)!;
    sessionStorage.setItem(`kiln:device:${MIRROR_BASE}:image`, JSON.stringify({ at: 'flashed', image: community.id }));
    const ran: string[] = [];
    const shell = { async run(c: string) { ran.push(c); return { out: c === 'getprop sys.boot_completed' ? '1' : c === 'dumpsys wifi' ? 'Wi-Fi is enabled\n' : '', code: 0 }; }, async push() {} };
    const image = new ImageSession({ frame: mirrorFrame, images: {} as never, requestFastboot: () => new Promise(() => {}), requestAdb: async () => shell, sleep: async () => {},
      adbKey: () => undefined, apk: () => undefined, progress: (await import('../src/device/imageSession')).sessionProgress(`kiln:device:${MIRROR_BASE}:image`) });
    expect(image.state).toMatchObject({ screen: 'adb', picked: community.id, changed: true });
    await image.connectAdb();
    expect(ran).toContain('svc wifi enable');
    expect(image.state).toMatchObject({ screen: 'done', communityWifi: 'on' });
  });
});

describe('an install that ends with no page of its frame up', () => {
  it('waits for its page: another frame\'s session stays, and the frame\'s page takes the device when it opens again', async () => {
    const m = new FakeMirror({ kiosk: 'app' });
    const app = new AppSession({ frame: mirrorFrame, connect: async () => withAppStreams(m, { online: true, radio: false }), sleep: async () => {}, handOver: pageHandOver(MIRROR_BASE) }, true);
    const image = new ImageSession({ frame: mirrorFrame, images: {} as never, requestFastboot: () => new Promise(() => {}), requestAdb: () => new Promise(() => {}), adbKey: () => undefined, apk: () => undefined });
    registry.keep(new DeviceActivity(MIRROR_BASE, mirrorFrame.name, { app, image }, false));
    at(`${MIRROR_BASE}/app`);
    const page = render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await app.connect();
    await app.pickApk('kiosk.apk', fakeMirrorApk());
    page.unmount(); // the person went to another frame, which has its session now
    const other = new StandardSession({ standard: { ...(await import('../src/standard/frame')).standardOfDevice(mirrorFrame), id: 'another' }, devices: { knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) }, loadKey: () => new Promise(() => {}) });
    registry.set({ base: '/another', name: 'Another', session: other });
    await app.install();
    expect(registry.get()!.session).toBe(other); // not replaced
    expect(other.isClosed).toBe(false);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />); // back to the Mirror's page
    await vi.waitFor(() => expect(registry.get()?.base).toBe(MIRROR_BASE));
    expect(registry.get()!.standard).toBe(true);
    expect(registry.get()!.session.store.state.setup.phase).toBe('settings'); // its App step, on the device the install had
  });
});

describe('an install that ended with no page up, then a reload', () => {
  it('opens the frame\'s set-up, which connects to the device again: the tab kept only that set-up is next', async () => {
    sessionStorage.setItem(`kiln:standard:${MIRROR_BASE}:pending`, '1');
    at(MIRROR_BASE);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await vi.waitFor(() => expect(registry.get()?.standard).toBe(true));
    expect(location.pathname).toBe(`${MIRROR_BASE}/set-up`);
    expect(sessionStorage.getItem(`kiln:standard:${MIRROR_BASE}:pending`)).toBeNull();
  });

  it('on the app path keeps its way back: Done offers the return to Connect and Undo', async () => {
    sessionStorage.setItem(`kiln:standard:${MIRROR_BASE}:pending`, '1');
    at(`${MIRROR_BASE}/app/install`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await vi.waitFor(() => expect(registry.get()?.standard).toBe(true));
    expect(registry.get()!.session.canUndo).toBe(true);
  });
});
