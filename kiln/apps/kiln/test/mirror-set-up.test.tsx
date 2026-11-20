// @vitest-environment jsdom
// The Mirror runs the wizard's set-up after its first start, as the S1 does after its app goes in: the rail's Set up
// (Wi-Fi, Home Assistant, and the radio only on a frame that has one; no App step, the install set the app up), then Done. A
// community image's install has no Set up: Kiln's part ends with it. The app alone over adb ends the same way.
import { act, cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { AppFlow, ImageFlow } from '../src/device/DevicePage';
import { appDemo } from '../src/device/demo';
import { imageDemo } from '../src/device/imageDemo';
import { mirrorEntry } from './mirror-frame';

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

  it('and Done after it, with the Mirror\'s own setup page', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'flash-done', true);
    render(<ImageFlow image={image} app={app} />);
    await settle();
    expect(image.state.screen).toBe('done');
    expect(screen.getByRole('heading', { name: 'Glassworks is on the Mirror.' })).toBeTruthy();
    expect(image.state.setupPage).toBe('http://192.0.2.50:8099/setup');
    expect(screen.getByText('http://192.0.2.50:8099/setup')).toBeTruthy();
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

  it('then Home Assistant, and Done with the Mirror\'s own setup page', async () => {
    const ha = await appDemo(mirrorEntry, 'setup-ha', true);
    render(<AppFlow session={ha} />);
    await settle();
    expect(screen.getByRole('heading', { name: 'Sign in to Home Assistant.' })).toBeTruthy();
    cleanup();
    const done = await appDemo(mirrorEntry, 'done', true);
    render(<AppFlow session={done} />);
    await settle();
    expect(done.state.screen).toBe('done');
    expect(done.state.setupPage).toBe('http://192.0.2.50:8099/setup');
    expect(screen.getByText('http://192.0.2.50:8099/setup')).toBeTruthy();
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
