// @vitest-environment jsdom
// /android after its install (#621): the tablet goes to the standard flow, the S1's own steps on the S1's own screens
// (App, Wi-Fi, Home Assistant, Panes once the app offers it; no Radio, it has none), then the same Done with what the
// install found under it (its frame card, the screen lock, what didn't apply). No hand-off to the tablet's setup page.
import { act, cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const path = vi.hoisted(() => ({ now: '/android' }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace() {}, push() {} }), usePathname: () => path.now }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { GENERIC } from '@glassworks/generic-android-installer';
import { AndroidWizard, androidStandard } from '../src/android/AndroidWizard';
import { androidDemo, type AndroidDemo } from '../src/android/demo';
import type { Device } from '../src/device/usbSession';
import type { UsbInstalled } from '@glassworks/kiln-core';
import { StandardSession } from '../src/standard/session';
import { StandardWizard } from '../src/standard/StandardWizard';
import { registry } from '../src/wizard/registry';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: { getDevices: async () => [] } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); sessionStorage.clear(); history.replaceState(null, '', '/'); path.now = '/android'; });

const rail = () => [...document.querySelectorAll('.steps > li > span:not(.mk)')].map((x) => x.textContent);
const subs = () => [...document.querySelectorAll('.steps .sub li')].map((li) => li.textContent);
const settle = () => act(() => new Promise((r) => setTimeout(r, 300)));

/** A demo with the page's hand-over: the tablet's standard flow, rendered as the page does. */
async function show(d: AndroidDemo) {
  let standard: StandardSession | undefined;
  const handOver = (p: Device, installed: UsbInstalled) => {
    standard = new StandardSession({ standard: androidStandard(installed.plan.app, installed), devices: { knownAdb: async () => p, requestAdb: async () => p },
      loadKey: async () => ({ publicKey: 'QAAA kiln@test' }) as never, sleep: async () => {}, simulated: true });
    void standard.handOver(p);
    return standard;
  };
  const s = await androidDemo(d, true, handOver);
  expect(standard, 'the install handed the tablet over').toBeDefined();
  render(<StandardWizard session={standard!} base="/android" before={['Device', 'Connect', 'Install']} />);
  await settle();
  return { s, standard: standard! };
}

describe('the tablet after its install', () => {
  it('starts at the App step: the S1\'s settings applied to the app on it, Continue, no keyboard to install', async () => {
    const { s } = await show('setup');
    expect(s.state.result?.flow).toBe('install');
    expect(s.state.phases.some((p) => p.step === 'handoff')).toBe(false); // no hand-off to its setup page
    expect(rail()).toEqual(['Device', 'Connect', 'Install', 'Set up', 'Done']);
    expect(subs()).toEqual(['App', 'Wi-Fi', 'Home Assistant']); // no Radio: frame.json says none
    expect(screen.getByRole('heading', { name: 'A few settings.' })).toBeTruthy();
    expect(screen.queryByText('On-screen keyboard')).toBeNull();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeTruthy();
  });

  it('then Wi-Fi, Home Assistant and Panes, the S1\'s screens', async () => {
    await show('setup-wifi');
    expect(screen.getByRole('heading', { name: 'Which Wi-Fi?' })).toBeTruthy();
    cleanup();
    await show('setup-ha');
    expect(screen.getByRole('heading', { name: 'Sign in to Home Assistant.' })).toBeTruthy();
    cleanup();
    await show('setup-panes');
    expect(subs()).toEqual(['App', 'Wi-Fi', 'Home Assistant', 'Panes']);
    expect(screen.getByRole('heading', { name: 'What should it do?' })).toBeTruthy();
  });

  it('Done is the S1\'s, with the tablet\'s Manage page', async () => {
    const { standard } = await show('done');
    await vi.waitFor(() => expect(standard.store.state.done?.managePage).toBeDefined());
    expect(screen.getByRole('heading', { name: 'Done.' })).toBeTruthy();
    expect(screen.getByText(/Change these later on the tablet's Manage page/)).toBeTruthy();
  });

  it('reads the frame from the catalog: the standard steps less Radio, touch on', () => {
    const f = androidStandard(GENERIC);
    expect(f.steps).toEqual(['set-up', 'wifi', 'home-assistant', 'panes']);
    expect(f.touch).toBe(true);
  });
});

describe('/android\'s addresses', () => {
  it('a load at a set-up step opens the standard flow there', async () => {
    path.now = '/android/set-up/wifi';
    history.replaceState(null, '', '/android/set-up/wifi?apk=local');
    render(<AndroidWizard />);
    await vi.waitFor(() => expect(registry.get()?.standard).toBe(true));
    expect(registry.get()!.base).toBe('/android');
    expect(location.pathname).toBe('/android/set-up/wifi');
  });
});

describe('a reload during the install', () => {
  it('says on the first screen that an earlier try changed the tablet', async () => {
    sessionStorage.setItem('kiln:device:/android:app', JSON.stringify({ at: 'written' }));
    history.replaceState(null, '', '/android?apk=local');
    render(<AndroidWizard />);
    expect(await screen.findByText('An earlier try already changed the tablet.')).toBeTruthy();
  });
});
