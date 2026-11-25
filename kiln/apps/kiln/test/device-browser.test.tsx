// @vitest-environment jsdom
// A browser without WebUSB stops on the first screen of every flow (issue 849), as the S1's wizard always did: the
// Mirror's image flow (which would otherwise download and build a 1.3 GB image before its first USB call), its app flow,
// and /android. `&browser=no-usb` shows that screen in Chrome where demos run, and nowhere else.
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { DevicePage } from '../src/device/DevicePage';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';
import { AndroidWizard } from './android-frame';

const usb = (on: boolean) => {
  if (on) Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  else delete (navigator as { usb?: unknown }).usb;
};

beforeAll(() => {
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
beforeEach(() => vi.stubGlobal('fetch', vi.fn(async () => Response.json({ release: null }))));
afterEach(() => { cleanup(); registry.clear(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); history.replaceState(null, '', '/'); });

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
const blocked = async () => {
  expect(await screen.findByRole('heading', { name: 'Use Chrome or Edge' })).toBeTruthy();
  expect(screen.getByText(/can’t talk to USB devices/)).toBeTruthy();
  // nothing to start: no button, and no way round it to the other flow
  expect(embers()).toEqual([]);
  expect(screen.queryByText(/Install just the app over USB|Install Glassworks over fastboot/)).toBeNull();
};

describe('a browser without WebUSB', () => {
  beforeEach(() => usb(false));

  it('the Mirror\'s image flow: the Browser screen, before any download or build', async () => {
    history.replaceState(null, '', MIRROR_BASE);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await blocked();
  });

  it('the Mirror\'s app flow (&mode=app)', async () => {
    history.replaceState(null, '', `${MIRROR_BASE}?mode=app`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await blocked();
  });

  it('/android', async () => {
    history.replaceState(null, '', '/android');
    render(<AndroidWizard />);
    await blocked();
  });
});

describe('a browser with WebUSB', () => {
  beforeEach(() => usb(true));

  it('gets the flow, not the Browser screen', async () => {
    history.replaceState(null, '', '/android');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.queryByRole('heading', { name: 'Use Chrome or Edge' })).toBeNull();
  });

  it('&browser=no-usb shows the Browser screen where demos run', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    history.replaceState(null, '', `${MIRROR_BASE}?browser=no-usb`);
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await blocked();
  });

  it('&browser=no-usb does nothing in a production build', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('NEXT_PUBLIC_KILN_DEMO', '');
    history.replaceState(null, '', '/android?browser=no-usb');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.queryByRole('heading', { name: 'Use Chrome or Edge' })).toBeNull();
  });
});
