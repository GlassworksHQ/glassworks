// @vitest-environment jsdom
// A device frame's addresses (#621): its install's steps from its installer.json, and the standard flow's after them.
// A load at a set-up step (a reload, a link) opens the standard flow there, connecting over adb; a load at an app flow's
// step opens the app alone; the install's tab mark brings a flashed Mirror straight to its first start.
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const path = vi.hoisted(() => ({ now: '/lululemon-mirror' }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace() {}, push() {} }), usePathname: () => path.now }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { DevicePage } from '../src/device/DevicePage';
import { StandardSession } from '../src/standard/session';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: { getDevices: async () => [] } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); sessionStorage.clear(); history.replaceState(null, '', '/'); });

const at = (p: string) => { path.now = p; history.replaceState(null, '', `${p}?apk=local`); };

describe('a device frame\'s addresses', () => {
  it('a load at a set-up step opens the standard flow, and leaves the address there', async () => {
    at(`${MIRROR_BASE}/set-up/wifi`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await vi.waitFor(() => expect(registry.get()?.standard).toBe(true));
    expect(registry.get()!.session).toBeInstanceOf(StandardSession);
    expect(registry.get()!.base).toBe(MIRROR_BASE);
    expect(location.pathname).toBe(`${MIRROR_BASE}/set-up/wifi`); // never moved to the install's first screen
    expect(screen.queryByText(/What goes on the Mirror/)).toBeNull();
  });

  it('a load at the app flow\'s step opens the app alone, its address kept', async () => {
    at(`${MIRROR_BASE}/app`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect(await screen.findByRole('button', { name: 'Connect' })).toBeTruthy();
    expect(location.pathname).toBe(`${MIRROR_BASE}/app`);
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
