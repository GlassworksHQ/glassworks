// @vitest-environment jsdom
// The Mirror's sessions in Kiln's registry, as the S1's is: they outlive a trip to the picker, the
// picker leads back to a running install, and LeaveGuard asks before the page is left mid-flash.
import { act, cleanup, render, screen } from '@testing-library/react';
import { SYSTEM_BYTES, USERDATA_BYTES, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => router, usePathname: () => null }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { LeaveGuard } from '../src/components/LeaveGuard';
import { Picker } from '../src/components/Picker';
import { DeviceActivity } from '../src/device/activity';
import { ImageSession, type ImageMaker } from '../src/device/imageSession';
import { DevicePage } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { registry } from '../src/wizard/registry';
import { Store } from '../src/wizard/session';
import { initial } from '../src/wizard/state';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); router.push.mockClear(); });

const leave = () => {
  const e = new Event('beforeunload', { cancelable: true });
  dispatchEvent(e);
  return e.defaultPrevented;
};

/** A Mirror install whose system write waits for `release()`: the page is mid-flash until then. */
async function midFlash() {
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  const images: ImageMaker = {
    async fetch(_u, p) { p(1); }, async download(_u, _f, p) { p(1); }, async file(n) { return new Blob([n]); }, async check() {}, async wifi() { return new Blob(); }, async adbKey() { return new Blob(); }, async systemKey() { return new Blob(); },
    async build() { return { system: new Blob(['S']), userdata: new Blob(['U']) }; },
    async discard() {},
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? SYSTEM_BYTES : USERDATA_BYTES; },
    async flashBlob(p) { if (p === 'system') await gate; },
    async reboot() {},
  };
  const mirror = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true);
  const flash = new ImageSession({ frame: mirrorFrame, simulated: true, images, refract: 'https://proxy.test', requestFastboot: async () => fb, requestAdb: async () => new FirstBootMirror(), adbKey: () => 'QAAA= kiln@glassworks', apk: () => mirror.apkBytes(), sleep: async () => {} });
  flash.pick(mirrorFrame.imageFlow.recipes[0]!.id); // the full image, not the recommended in-place way
  const kept = registry.keep(new DeviceActivity(MIRROR_BASE, mirrorFrame.name, { app: mirror, image: flash }, true));
  flash.choose();
  await flash.download();
  await mirror.pickApk('kiosk.apk', fakeMirrorApk());
  flash.toFastboot();
  await flash.connectFastboot();
  flash.understand(true);
  const writing = flash.flash();
  await act(() => new Promise((r) => setTimeout(r, 0)));
  return { kept, flash, done: async () => { release(); await act(() => writing); } };
}

describe('the Mirror in Kiln\'s registry', () => {
  it('asks before the page is left while it flashes, on any route, and not after', async () => {
    render(<LeaveGuard />); // as on the picker: the Mirror's page isn't mounted
    expect(leave()).toBe(false);
    const { flash, done } = await midFlash();
    expect(flash.state.screen).toBe('working');
    expect(leave()).toBe(true);
    await done();
    expect(flash.state.screen).toBe('adb');
    expect(leave()).toBe(false);
  });

  it('the picker says it is still working and leads back to it, Continue included', async () => {
    const { done } = await midFlash();
    render(<Picker />);
    expect(screen.getByText('Kiln is still working on your lululemon Studio Mirror.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Return to install' }).getAttribute('href')).toBe(`${MIRROR_BASE}?apk=local`);
    await done(); // flashed: the Mirror now waits for its first boot over adb, still a way back
    expect(screen.getByRole('link', { name: 'Return to install' }).getAttribute('href')).toBe(`${MIRROR_BASE}?apk=local`);
  });

  it('an S1 session and the Mirror\'s don\'t close each other, and either one writing guards the page', async () => {
    const { kept, done } = await midFlash();
    const store = new Store(initial());
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
    expect(registry.activity(MIRROR_BASE)).toBe(kept);
    render(<LeaveGuard />);
    expect(leave()).toBe(true);
    await done();
    expect(leave()).toBe(false);
  });

  it('the page keeps its sessions across a trip to the picker', async () => {
    history.replaceState(null, '', `${MIRROR_BASE}?apk=local`);
    const first = render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    const kept = registry.activity(MIRROR_BASE) as DeviceActivity;
    expect(kept).toBeInstanceOf(DeviceActivity);
    first.unmount(); // to the picker and back
    history.replaceState(null, '', MIRROR_BASE); // "Return to install" or Continue, without the switch
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(registry.activity(MIRROR_BASE)).toBe(kept);
  });

  it('the page keeps its session in the registry with or without ?apk=…: the Mirror is open (stable)', async () => {
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(registry.activities()).toHaveLength(1);
  });
});
