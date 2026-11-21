// @vitest-environment jsdom
// The Mirror runs the standard flow after its install, the S1's own steps on the S1's own screens (issue 621): the
// rail's Set up with App (its settings applied to the app the install put on), Wi-Fi, Home Assistant, Panes once the app
// offers it, and Radio only on a frame with one; then the same Done, with what the install itself left to say under it. A
// community image's install has no Set up: Kiln's part ends with it. The app alone over adb ends the same way.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { FakeMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { PanelShell } from '@glassworks/adb';
import { AppFlow, ImageFlow, appSteps } from '../src/device/DevicePage';
import { appDemo } from '../src/device/demo';
import { imageDemo } from '../src/device/imageDemo';
import { ImageOutcome, imageSteps } from '../src/device/ImageScreens';
import { StandardWizard } from '../src/standard/StandardWizard';
import type { StandardSession } from '../src/standard/session';
import { appOn, handTo, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
});
afterEach(() => cleanup());

const rail = () => [...document.querySelectorAll('.steps > li > span:not(.mk)')].map((x) => x.textContent);
const subs = () => [...document.querySelectorAll('.steps .sub li')].map((li) => li.textContent);
const settle = () => act(() => new Promise((r) => setTimeout(r, 1200)));
const show = (ss: StandardSession, before: readonly string[], outcome?: ReactNode) =>
  render(<StandardWizard session={ss} base="/lululemon-mirror" before={before} outcome={outcome} />);
const IMAGE = imageSteps(mirrorFrame, false);
const APP = appSteps(mirrorFrame);

describe('the standard flow after the Mirror\'s image install', () => {
  it('starts at its App step: the S1\'s settings, applied to the app the install put on, with no on-screen keyboard', async () => {
    const h = handTo();
    const { image } = await imageDemo(mirrorEntry, 'flash-setup', true, {}, h.handOver);
    expect(image.state.firstBoot).toBeDefined();
    show(h.standard(), IMAGE);
    await settle();
    expect(rail()).toEqual(['Mirror', 'Choose', 'Download', 'Fastboot', 'Install', 'Set up', 'Done']);
    // App first, as on the S1; no Panes until the app offers it, no Radio (frame.json: none)
    expect(subs()).toEqual(['App', 'Wi-Fi', 'Home Assistant']);
    expect(screen.getByRole('heading', { name: 'Language and time' })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Time zone' })).toBeTruthy();
    expect(screen.queryByText('On-screen keyboard')).toBeNull(); // no touch: its hardware can't use one
    expect(screen.queryByText('About a minute.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeTruthy();
  });

  it('Continue applies the settings over adb, reinstalls nothing, then asks which Wi-Fi', async () => {
    const h = handTo();
    const ran: string[] = [];
    await imageDemo(mirrorEntry, 'flash-setup', true, {}, (p: PanelShell) => {
      const spy: PanelShell = { ...p, run: (cmd, signal) => { ran.push(cmd); return p.run(cmd, signal); } };
      return h.handOver(spy);
    });
    const ss = h.standard();
    await ss.installApp({ locale: 'en-GB', timezone: 'Europe/London', clock24: true, keyboard: false });
    expect(ran).toEqual(expect.arrayContaining([
      'settings put global auto_time_zone 0', 'service call alarm 3 s16 \'Europe/London\'', 'settings put system system_locales \'en-GB\'',
      'settings put system time_12_24 24', `am start -n ${mirrorFrame.app.activity} --es set_locale 'en-GB'`,
    ]));
    expect(ran.filter((c) => c.startsWith('pm install'))).toEqual([]);
    expect(ss.store.state.setup.phase).toBe('wifi');
  });

  it('then Wi-Fi, Home Assistant and Done, the S1\'s screens', async () => {
    const wifi = handTo();
    await imageDemo(mirrorEntry, 'flash-setup-wifi', true, {}, wifi.handOver);
    show(wifi.standard(), IMAGE);
    await settle();
    expect(screen.getByRole('heading', { name: 'Which Wi-Fi?' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: /Home Net 5G/ })).toBeTruthy();
    expect(screen.queryByText(/on the panel/)).toBeNull(); // no touch: nothing to do on the Mirror
    cleanup();
    const ha = handTo();
    await imageDemo(mirrorEntry, 'flash-setup-ha', true, {}, ha.handOver);
    show(ha.standard(), IMAGE);
    await settle();
    expect(screen.getByRole('heading', { name: 'Sign in to Home Assistant' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Skip, sign in later' })).toBeTruthy();
  });

  it('Done is the S1\'s, with the Manage page, and the image\'s own lines under it: the key, the way back', async () => {
    const h = handTo();
    const { image } = await imageDemo(mirrorEntry, 'flash-done', true, {}, h.handOver);
    const away = vi.fn();
    show(h.standard(), IMAGE, <ImageOutcome f={image} s={image.state} away={away} />);
    await settle();
    expect(h.standard().store.state.screen).toBe('done');
    expect(screen.getByRole('heading', { name: 'You\'re all set' })).toBeTruthy();
    expect(screen.getByText('The Mirror is on Wi-Fi at 192.0.2.50. Home Assistant finds it by itself.')).toBeTruthy();
    expect(screen.getByText('http://192.0.2.50:8099/')).toBeTruthy();
    expect(screen.getByText('Change these later on the Mirror\'s Manage page. It asks for a code the Mirror shows.')).toBeTruthy();
    expect(screen.getByText('The way back')).toBeTruthy();
    expect(rail().at(-1)).toBe('Done');
    // the image's way back leaves Done for the install's first screen
    fireEvent.click(screen.getByRole('button', { name: /^Install .* instead$/ }));
    expect(away).toHaveBeenCalled();
    expect(image.state.screen).toBe('choose');
  });

  it('a community image has no Set up: its install ends Kiln\'s part', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'image-done', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(rail()).not.toContain('Set up');
  });
});

describe('the standard flow after the app alone', () => {
  it('starts at its App step with the app path\'s rail', async () => {
    const h = handTo();
    await appDemo(mirrorEntry, 'setup', true, h.handOver);
    show(h.standard(), APP);
    await settle();
    expect(rail()).toEqual(['Mirror', 'Connect', 'Check', 'Install', 'Set up', 'Done']);
    expect(subs()).toEqual(['App', 'Wi-Fi', 'Home Assistant']);
    expect(screen.getByRole('heading', { name: 'Language and time' })).toBeTruthy();
  });

  it('Panes once the app offers it, the S1\'s step: its cards as switches', async () => {
    const h = handTo();
    await appDemo(mirrorEntry, 'setup-panes', true, h.handOver);
    show(h.standard(), APP);
    await settle();
    expect(subs()).toEqual(['App', 'Wi-Fi', 'Home Assistant', 'Panes']);
    expect(screen.getByRole('heading', { name: 'What should it do?' })).toBeTruthy();
    expect(screen.getAllByRole('switch').length).toBe(mirrorEntry.sim.panes!.length);
  });

  it('Done with the Mirror\'s Manage page', async () => {
    const h = handTo();
    await appDemo(mirrorEntry, 'done', true, h.handOver);
    show(h.standard(), APP);
    await settle();
    expect(h.standard().store.state.done?.managePage).toBe('http://192.0.2.50:8099/');
    expect(screen.getByText('http://192.0.2.50:8099/')).toBeTruthy();
  });

  it('asks for no Home Assistant address before the install, and an undo has no Set up', async () => {
    const s = await appDemo(mirrorEntry, 'checks', true);
    render(<AppFlow session={s} />);
    expect(document.querySelector('input[type=url]')).toBeNull();
    expect(screen.queryByText(/Home Assistant/)).toBeNull();
    cleanup();
    render(<AppFlow session={await appDemo(mirrorEntry, 'undo-done', true)} />);
    expect(rail()).toEqual(['Mirror', 'Connect', 'Check', 'Undo', 'Done']);
  });

  it('a set-up that stops keeps the install: Try again goes back to its App step and installs nothing again', async () => {
    const m = new FakeMirror({ kiosk: 'none' });
    const { s, standard } = appOn(m);
    await s.connect();
    await s.pickApk('kiosk.apk', fakeMirrorApk());
    await s.install();
    const installs = () => m.cmds.filter((c) => c.startsWith('pm install')).length;
    expect(installs()).toBe(1);
    const ss = standard();
    // the app stops answering: set-up's error screen, the standard flow's
    (ss as unknown as { fail(at: number, e: unknown, installed: boolean): void }).fail(3, new Error('Glassworks isn\'t answering.'), true);
    show(ss, APP);
    expect(ss.store.state.screen).toBe('error');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await settle();
    expect(ss.store.state).toMatchObject({ screen: 'setup', setup: { phase: 'settings', apply: true } });
    expect(installs()).toBe(1);
  });
});

describe('the setup-error demo', () => {
  it('Wi-Fi can\'t reach the app, and its Try again recovers once the app is back: no tap on the Mirror needed', async () => {
    const h = handTo();
    await appDemo(mirrorEntry, 'setup-error', true, h.handOver);
    const ss = h.standard();
    expect(ss.store.state.setup).toMatchObject({ phase: 'wifi', unreachable: true });
    show(ss, APP);
    expect(screen.getByText(/Couldn't reach Glassworks on the panel\. Try again\./)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await settle();
    expect((ss.store.state.setup as { unreachable?: boolean }).unreachable).toBeFalsy();
    expect(ss.store.state.setup.phase).toBe('wifi');
  });
});
