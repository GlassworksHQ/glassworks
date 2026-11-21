// @vitest-environment jsdom
// The page of a frame its frame.json doesn't open yet (status `development` or `planned`): locked to the public, with
// ?apk=release and ?apk=local as test links, labelled Test only. Today every frame with a page is open, so the frames
// are made closed here: the Mirror's device page (DevicePage, deviceOpen) and /android (usbOpen).
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));
vi.mock('../src/device/appSession', async (orig) => ({ ...(await orig<typeof import('../src/device/appSession')>()), deviceOpen: () => false }));
vi.mock('../src/device/usbSession', async (orig) => {
  const m = await orig<typeof import('../src/device/usbSession')>();
  return { ...m, usbOpen: () => false };
});

import { DevicePage } from '../src/device/DevicePage';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';
import { AndroidWizard } from './android-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('a device frame that isn\'t open', () => {
  it('without ?apk=…, says it doesn\'t install yet, asks nothing and keeps nothing', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    history.replaceState(null, '', MIRROR_BASE);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText(/doesn.t install from Kiln yet/);
    expect(screen.getByText(/^Its installer is still being tested\./)).toBeTruthy();
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
    expect(registry.activities()).toEqual([]);
  });

  it('with ?apk=local, a test install, labelled Test only', async () => {
    history.replaceState(null, '', `${MIRROR_BASE}?apk=local&mode=app`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Install a test build on the Mirror');
    expect(screen.getByText('Test only')).toBeTruthy();
    expect(document.querySelector('input[type=file]')).not.toBeNull();
  });
});

describe('/android while generic-android isn\'t open', () => {
  it('without ?apk=…, says Kiln doesn\'t install on Android yet, and asks nothing', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    history.replaceState(null, '', '/android');
    render(<AndroidWizard />);
    await screen.findByText(/doesn.t install on Android devices yet/);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('with ?apk=local, a test install, labelled Test only', async () => {
    history.replaceState(null, '', '/android?apk=local');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.getByText('Test only')).toBeTruthy();
  });
});
