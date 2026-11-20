// @vitest-environment jsdom
// The Mirror runs the wizard's set-up after its first start, as the S1 does after its app goes in: the rail's Set up
// (Wi-Fi, Home Assistant, and the radio only on a frame that has one; no App step, the install set the app up), then Done. A
// community image's install has no Set up: Kiln's part ends with it. The app alone over adb ends the same way.
import { act, cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { AppFlow, ImageFlow } from '../src/device/DevicePage';
import type { PanelShell } from '@glassworks/adb';
import { FakeMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { AppSession } from '../src/device/appSession';
import { appDemo, cuttable } from '../src/device/demo';
import { withAppStreams } from '../src/wizard/demo';
import { imageDemo } from '../src/device/imageDemo';
import { mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
});
afterEach(() => cleanup());

const rail = () => [...document.querySelectorAll('.steps > li > span:not(.mk)')].map((x) => x.textContent);
const subs = () => [...document.querySelectorAll('.steps .sub li')].map((li) => li.textContent);
const settle = () => act(() => new Promise((r) => setTimeout(r, 1200)));

describe('set-up on the Mirror', () => {
  it('follows the first start: Set up on the rail, Wi-Fi first, the networks the Mirror can see', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'flash-setup', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(image.state.screen).toBe('setup');
    expect(rail()).toEqual(['Mirror', 'Choose', 'Download', 'Fastboot', 'Install', 'Set up', 'Done']);
    expect(subs()).toEqual(['Wi-Fi', 'Home Assistant']); // no App (the install set the app up), no Radio (frame.json: none)
    expect(screen.getByRole('heading', { name: 'Which Wi-Fi?' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: /Home Net 5G/ })).toBeTruthy();
  });

  it('then Home Assistant, over the app\'s setup channel', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'flash-setup-ha', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(image.state.setup.phase).toBe('ha');
    expect(screen.getByRole('heading', { name: 'Sign in to Home Assistant.' })).toBeTruthy();
  });

  it('and Done after it, with the Mirror\'s Manage page', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'flash-done', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(image.state.screen).toBe('done');
    expect(screen.getByRole('heading', { name: 'Glassworks is on the Mirror.' })).toBeTruthy();
    expect(image.state.managePage).toBe('http://192.0.2.50:8099/');
    expect(screen.getByText('http://192.0.2.50:8099/')).toBeTruthy();
    expect(screen.getByText('Change these later on the Mirror\'s Manage page. It asks for a code the Mirror shows.')).toBeTruthy();
    expect(rail().at(-1)).toBe('Done');
  });

  it('a community image has no Set up: its install ends Kiln\'s part', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'image-done', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(rail()).not.toContain('Set up');
  });
});

describe('set-up after the app alone', () => {
  it('follows the install: Set up on the rail, Wi-Fi first', async () => {
    const s = await appDemo(mirrorEntry, 'setup', true);
    render(<AppFlow session={s} />);
    await settle();
    expect(s.state.screen).toBe('setup');
    expect(rail()).toEqual(['Mirror', 'Connect', 'Check', 'Install', 'Set up', 'Done']);
    expect(subs()).toEqual(['Wi-Fi', 'Home Assistant']);
    expect(screen.getByRole('heading', { name: 'Which Wi-Fi?' })).toBeTruthy();
  });

  it('then Home Assistant, and Done with the Mirror\'s Manage page', async () => {
    const ha = await appDemo(mirrorEntry, 'setup-ha', true);
    render(<AppFlow session={ha} />);
    await settle();
    expect(screen.getByRole('heading', { name: 'Sign in to Home Assistant.' })).toBeTruthy();
    cleanup();
    const done = await appDemo(mirrorEntry, 'done', true);
    render(<AppFlow session={done} />);
    await settle();
    expect(done.state.screen).toBe('done');
    expect(done.state.managePage).toBe('http://192.0.2.50:8099/');
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
});

describe('a set-up that stops after the app alone', () => {
  it('keeps the install: Try again goes back to set-up, not to Checks, and writes nothing again', async () => {
    const s = await appDemo(mirrorEntry, 'setup-error', true);
    await vi.waitFor(() => expect(s.state.screen).toBe('error'), { timeout: 10_000 });
    render(<AppFlow session={s} />);
    expect(screen.getByRole('heading', { name: 'Set-up stopped.' })).toBeTruthy();
    expect(rail()).toEqual(['Mirror', 'Connect', 'Check', 'Install', 'Set up', 'Done']);
    expect(document.querySelector('.steps li.bad')?.textContent).toBe('Set up');
    const starts = () => s.state.log.filter((l) => l === 'Start Glassworks').length; // the install's last step
    const phases = s.state.phases;
    expect(starts()).toBe(1);
    await act(() => s.setUpAgain());
    expect(s.state).toMatchObject({ screen: 'setup', setup: { phase: 'wifi' }, result: { flow: 'install' } });
    expect(starts()).toBe(1);
    expect(s.state.phases).toBe(phases);
  });

  it('a read from Back to the checks that ends after Try again doesn\'t replace set-up', async () => {
    const s = await appDemo(mirrorEntry, 'setup-error', true);
    await vi.waitFor(() => expect(s.state.screen).toBe('error'), { timeout: 10_000 });
    const panel = (s as unknown as { panel: PanelShell }).panel;
    const run = panel.run.bind(panel);
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    let held = false;
    panel.run = async (cmd, signal) => { if (!held && cmd === 'getprop ro.build.version.sdk') { held = true; await gate; } return run(cmd, signal); };
    const back = s.again(); // Back to the checks, still reading
    await s.setUpAgain(); // then Try again
    release();
    await back;
    expect(s.state).toMatchObject({ screen: 'setup', result: { flow: 'install' } });
  });

  it('while Try again reads the Mirror, nothing else starts: a second Try again or Back to the checks does nothing', async () => {
    const s = await appDemo(mirrorEntry, 'setup-error', true);
    await vi.waitFor(() => expect(s.state.screen).toBe('error'), { timeout: 10_000 });
    const panel = (s as unknown as { panel: PanelShell }).panel;
    const run = panel.run.bind(panel);
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    panel.run = async (cmd, signal) => { if (cmd === 'true') await gate; return run(cmd, signal); };
    const first = s.setUpAgain();
    expect(s.state.busy).toBe('set-up-again');
    const reads = () => s.state.log.length;
    const before = reads();
    await s.setUpAgain();
    await s.again();
    expect(s.state.screen).toBe('error');
    expect(reads()).toBe(before);
    release();
    await first;
    expect(s.state).toMatchObject({ screen: 'setup', result: { flow: 'install' } });
  });

  it('with the Mirror gone, Try again connects again, then set-up resumes: the install isn\'t run again', async () => {
    const m = new FakeMirror({ kiosk: 'app' });
    const c = cuttable(withAppStreams(m, { online: true, radio: false }));
    let gone = false;
    const shell: PanelShell = { ...c.shell, run: (cmd, signal) => (gone ? Promise.reject(new Error('USB transfer failed')) : c.shell.run(cmd, signal)) };
    const s = new AppSession({ frame: mirrorFrame, connect: async () => shell, sleep: async () => {}, simulated: true }, true);
    await s.connect();
    await s.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    await s.install();
    await s.setUp.homeAssistant();
    c.cut();
    await s.setUp.skipHa('');
    await vi.waitFor(() => expect(s.state.screen).toBe('error'));
    const installs = () => m.cmds.filter((x) => x.startsWith('pm install')).length;
    expect(installs()).toBe(1);
    gone = true;
    await s.setUpAgain();
    expect(s.state).toMatchObject({ screen: 'pick', connectError: expect.stringMatching(/went away: USB transfer failed/) });
    gone = false;
    await s.connect();
    expect(s.state).toMatchObject({ screen: 'setup', setup: { phase: 'wifi' }, result: { flow: 'install' } });
    expect(installs()).toBe(1);
  });
});

describe('a set-up that stops after the image\'s first start', () => {
  it('says Set-up stopped, and Try again goes back to set-up without running the first start again', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'flash-setup-ha', true);
    // the app stops answering on its setup channel: what set-up's unreachable does
    act(() => (image.setUp as unknown as { d: { unreachable(e: Error): void } }).d.unreachable(new Error('Glassworks didn\'t start again within a minute. Try again.')));
    render(<ImageFlow image={image} app={app} />);
    expect(image.state.screen).toBe('error');
    expect(screen.getByRole('heading', { name: 'Set-up stopped.' })).toBeTruthy();
    expect(screen.queryByText('Try the first start again')).toBeNull();
    const starts = () => image.state.log.filter((l) => l === 'Start Glassworks').length;
    expect(starts()).toBe(1);
    await act(() => image.setUpAgain());
    expect(image.state).toMatchObject({ screen: 'setup', setup: { phase: 'wifi' } });
    expect(image.state.firstBoot).toBeDefined();
    expect(starts()).toBe(1);
  });

  it('with the Mirror gone, Try again connects again, then set-up resumes: the first start isn\'t run again', async () => {
    const { image } = await imageDemo(mirrorEntry, 'flash-setup-ha', true);
    act(() => (image.setUp as unknown as { d: { unreachable(e: Error): void } }).d.unreachable(new Error('the app stopped answering')));
    const panel = (image as unknown as { panel: PanelShell }).panel;
    panel.run = async () => { throw new Error('USB transfer failed'); };
    await act(() => image.setUpAgain());
    expect(image.state.screen).toBe('adb'); // "One last click.": Connect
    expect(image.state.firstBoot).toBeDefined();
    const starts = () => image.state.log.filter((l) => l === 'Start Glassworks').length;
    await act(() => image.connectAdb()); // the demo's device, connected again
    expect(image.state).toMatchObject({ screen: 'setup', setup: { phase: 'wifi' } });
    expect(starts()).toBe(1);
  });
});
