// @vitest-environment jsdom
// /android/tablet (#403, part 3): Glassworks from the tablet's own browser. Which screen comes first, which build
// and APK it offers through Kiln's release route, "not out yet" before a release, and every demo screen with at
// most one ember button.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { identifyBrowser } from '@glassworks/generic-android-installer';
import { BROWSERS } from '@glassworks/generic-android-installer/browsers';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { abiFor, buildFor, download, firstScreen, loadRelease, rail, SETTINGS_LINKS, type Line, type Release } from '../src/device/onDevice/model';
import { TABLET_DEMOS, TabletPage, TabletScreens, tabletDemo, type TabletDemo } from './android-frame';
import { lookOf } from '../src/device/onDevice/OnDevicePage';
import { FRAMES, RENDERS } from '../src/frames/catalog';

beforeAll(() => {
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  window.scrollTo = () => {};
});
afterEach(cleanup);

const on = (n: keyof typeof BROWSERS) => identifyBrowser(BROWSERS[n]);
const heading = () => document.querySelector('h2')?.textContent ?? '';
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
const rel = (line: Line, version = '1.0.0'): Extract<Release, { state: 'out' }> => ({
  state: 'out', tag: `${line}-v${version}`, version, assets: ['arm64-v8a', 'armeabi-v7a'].map((a) => `glassworks-${line}-${a}-${version}.apk`),
});

async function show(name: TabletDemo) {
  const d = tabletDemo(name)!;
  render(<TabletScreens on={d.on} start={d.screen} load={d.load} />);
  await act(() => new Promise((r) => setTimeout(r, 0)));
}

describe('where it starts', () => {
  it('on Android: the device; on a computer: open it on the tablet; below Android 6: no', () => {
    expect(firstScreen(on('PIXEL_DESKTOP'))).toBe('device');
    expect(firstScreen(on('MAC'))).toBe('elsewhere');
    expect(firstScreen(on('PI_TOUCH'))).toBe('elsewhere');
    expect(firstScreen(on('LOLLIPOP'))).toBe('too-old');
  });

  it('the rail: Device, Get the app, Set up; the Kiln finish stays on Set up', () => {
    expect(rail('get').map((s) => s.mark)).toEqual(['done', 'now', 'todo']);
    expect(rail('kiln').map((s) => s.mark)).toEqual(['done', 'done', 'now']);
    expect(rail('too-old').map((s) => s.mark)).toEqual(['bad', 'todo', 'todo']);
  });
});

describe('the build and APK', () => {
  it('legacy only where the version says 6 or 7', () => {
    expect(buildFor(on('NOUGAT_TAB'))).toBe('legacy');
    expect(buildFor(on('PIXEL_DESKTOP'))).toBe('generic');
    expect(buildFor(on('OLD_CHROME_DESKTOP'))).toBe('generic');
  });

  it('the ABI from what the browser runs as, else a tested model\'s one ABI, else 64-bit and unsure', () => {
    expect(abiFor(on('GALAXY_TAB'), FRAMES)).toEqual({ abi: 'arm64-v8a', sure: true });
    // issue 700: a 32-bit userspace on a 64-bit chip, in a browser that says so, and in Chrome, which doesn't
    expect(abiFor(on('GALAXY_TAB_A_2016_SAMSUNG'), FRAMES)).toEqual({ abi: 'armeabi-v7a', sure: true });
    expect(abiFor(on('GALAXY_TAB_A_2016'), FRAMES)).toEqual({ abi: 'armeabi-v7a', sure: true });
    expect(abiFor({ ...on('GALAXY_TAB_A_2016'), bits: undefined }, FRAMES)).toEqual({ abi: 'arm64-v8a', sure: false });
    expect(abiFor({ ...on('GALAXY_TAB'), bits: 32 }, FRAMES)).toEqual({ abi: 'armeabi-v7a', sure: true });
    expect(abiFor(on('PIXEL_DESKTOP'), FRAMES)).toEqual({ abi: 'arm64-v8a', sure: true });
    expect(abiFor(on('UNKNOWN_DESKTOP'), FRAMES)).toEqual({ abi: 'arm64-v8a', sure: false });
  });

  it('links the APK through Kiln\'s release route, and nothing before a release', () => {
    expect(download('generic-android', rel('generic-android'), 'armeabi-v7a')).toEqual({
      href: '/api/release/generic-android?tag=generic-android-v1.0.0&name=glassworks-generic-android-armeabi-v7a-1.0.0.apk',
      name: 'glassworks-generic-android-armeabi-v7a-1.0.0.apk', version: '1.0.0',
    });
    expect(download('generic-android', { state: 'none' }, 'arm64-v8a')).toBeUndefined();
    expect(download('generic-android', { ...rel('generic-android'), assets: [] }, 'arm64-v8a')).toBeUndefined();
  });

  it('reads the line\'s latest from Kiln\'s route', async () => {
    const answer = (body: unknown, status = 200) => vi.fn(async () => Response.json(body, { status })) as unknown as typeof fetch;
    const out = answer({ release: { tag: 'generic-android-v1.0.0', version: '1.0.0', assets: [] } });
    expect(await loadRelease('generic-android', out)).toEqual({ state: 'out', tag: 'generic-android-v1.0.0', version: '1.0.0', assets: [] });
    expect(out).toHaveBeenCalledWith('/api/release/generic-android/latest');
    expect(await loadRelease('generic-android', answer({ release: null }))).toEqual({ state: 'none' });
    expect(await loadRelease('generic-android', answer('no', 502))).toEqual({ state: 'error', why: 'Kiln\'s release route answered 502' });
  });
});

describe('the screens', () => {
  it('a Pixel Tablet in desktop mode: named a guess, with its frame card', async () => {
    await show('pixel');
    expect(heading()).toBe('This looks like a Pixel Tablet');
    expect(document.body.textContent).toContain('Tested device · Android 8 or newer');
    expect(embers()).toEqual(['Continue']);
  });

  it('the device as a 3D model: a model with a frame of its own is that frame, on its dock; any other tablet is the page\'s frame; a phone none (the maintainer, issue 672)', async () => {
    const model = (name: string) => screen.getByRole('img', { name });
    const src = (el: HTMLElement) => el.querySelector('img')?.getAttribute('src');
    await show('pixel');
    expect(src(model('Google Pixel Tablet'))).toBe(RENDERS['google-pixel-tablet']);
    expect(model('Google Pixel Tablet').querySelector('[data-dock]')).not.toBeNull();
    cleanup();
    await show('galaxy');
    expect(src(model('An Android tablet'))).toBe(RENDERS['generic-android']);
    cleanup();
    await show('phone');
    expect(screen.queryByRole('img', { name: /Android phone/ })).toBeNull();
    // the frame's own model, so its finish is the frame card's: light-dark() follows the page's theme
    expect(lookOf(on('PIXEL_DESKTOP'), 'generic-android')?.model).toBe(FRAMES.find((f) => f.id === 'google-pixel-tablet')!.model);
    expect(lookOf(on('UNKNOWN_DESKTOP'), 'generic-android')?.id).toBe('generic-android');
    expect(lookOf(on('NOUGAT_TAB'), 'generic-android')?.id).toBe('generic-android');
    expect(lookOf(on('GALAXY_PHONE'), 'generic-android')).toBeUndefined();
  });

  it('a model read from client hints: named, with its version and model code', async () => {
    await show('galaxy');
    expect(heading()).toBe('This is a Galaxy Tab A8');
    expect(document.querySelector('.kv')?.textContent).toContain('Galaxy Tab A8 (SM-X200)');
  });

  it('a computer: open it on the tablet, or install from this computer', async () => {
    await show('computer');
    expect(heading()).toBe('Open this page on the tablet');
    expect(screen.getByText('Install from this computer').getAttribute('href')).toBe('/android');
    fireEvent.click(screen.getByText('This is the tablet'));
    expect(heading()).toBe('This is an Android tablet');
  });

  it('Get: the latest release\'s APK through Kiln\'s route, as a download', async () => {
    await show('get');
    const a = screen.getByText('Download');
    expect(a.getAttribute('href')).toBe('/api/release/generic-android?tag=generic-android-v1.0.0&name=glassworks-generic-android-arm64-v8a-1.0.0.apk');
    expect(a.getAttribute('download')).toBe('glassworks-generic-android-arm64-v8a-1.0.0.apk');
    expect(embers()).toEqual(['Download']);
    expect(screen.queryByText(/Get that build/)).toBeNull();
  });

  it('Get never asks 32 or 64-bit: one Download, the build Kiln picked (issue 700)', async () => {
    // a 32-bit userspace on a 64-bit chip, in Chrome (WebAssembly's bound) and in Samsung Internet (its platform)
    for (const b of ['GALAXY_TAB_A_2016', 'GALAXY_TAB_A_2016_SAMSUNG'] as const) {
      render(<TabletScreens on={on(b)} start="get" load={async () => rel('generic-android')} />);
      const a = await screen.findByText('Download');
      expect(a.getAttribute('href')).toBe('/api/release/generic-android?tag=generic-android-v1.0.0&name=glassworks-generic-android-armeabi-v7a-1.0.0.apk');
      expect(document.querySelectorAll('a[download]')).toHaveLength(1);
      expect(document.body.textContent).not.toMatch(/(32|64)-bit/);
      expect(screen.getByText(/Tell us on GitHub/)).toBeTruthy();
      cleanup();
    }
    await show('get-32bit');
    expect(screen.getByText('Download').getAttribute('href')).toContain('armeabi-v7a');
  });

  it('Get where the browser says nothing at all: the 64-bit build, and the other one only if Android refuses it', async () => {
    await show('get-unsure');
    expect(screen.getByText('Download').getAttribute('href')).toContain('glassworks-generic-android-arm64-v8a-1.0.0.apk');
    expect(embers()).toEqual(['Download']);
    const other = screen.getByText('Download the other build');
    expect(other.getAttribute('href')).toContain('glassworks-generic-android-armeabi-v7a-1.0.0.apk');
    expect(other.getAttribute('download')).toBe('glassworks-generic-android-armeabi-v7a-1.0.0.apk');
    expect(document.body.textContent).not.toMatch(/(32|64)-bit/);
  });

  it('a release without the build Kiln picked: says so, offers nothing else', async () => {
    const only64 = { ...rel('generic-android'), assets: ['glassworks-generic-android-arm64-v8a-1.0.0.apk'] };
    render(<TabletScreens on={on('GALAXY_TAB_A_2016')} start="get" load={async () => only64} />);
    await screen.findByText('Glassworks 1.0.0 has no build for this tablet yet.');
    expect(document.querySelector('a[download]')).toBeNull();
  });

  it('Get on Android 7: the legacy build, and Unknown sources first', async () => {
    await show('get-legacy');
    expect(screen.getByText('Download').getAttribute('href')).toContain('tag=generic-android-legacy-v1.0.0');
    expect(screen.getByText('Open Settings').getAttribute('href')).toBe(SETTINGS_LINKS.settings);
  });

  it('Get with the version hidden on an older Chrome: either build', async () => {
    await show('get-either');
    expect(screen.getByText('Download').getAttribute('href')).toContain('tag=generic-android-v1.0.0');
    fireEvent.click(screen.getByText('Android 6 or 7? Get the legacy build'));
    await act(() => new Promise((r) => setTimeout(r, 0)));
    expect(screen.getByText('Download').getAttribute('href')).toContain('tag=generic-android-legacy-v1.0.0');
  });

  it('Get before the first release: not out yet, and no download', async () => {
    await show('get-none');
    expect(heading()).toBe('Glassworks for Android isn\'t out yet');
    expect(screen.getByText('Not released yet')).toBeTruthy();
    expect(document.querySelector('a[download]')).toBeNull();
  });

  it('not out yet with the version hidden: the legacy build is still one tap away', async () => {
    const load = vi.fn(async (line: Line): Promise<Release> => (line === 'generic-android' ? { state: 'none' } : rel(line)));
    render(<TabletScreens on={on('OLD_CHROME_DESKTOP')} start="get" load={load} />);
    await screen.findByText('Glassworks for Android isn\'t out yet');
    fireEvent.click(screen.getByText('Android 6 or 7? Get the legacy build'));
    expect((await screen.findByText('Download')).getAttribute('href')).toContain('tag=generic-android-legacy-v1.0.0');
  });

  it('Get when the releases can\'t be read: says so, and tries again', async () => {
    const load = vi.fn(async (): Promise<Release> => ({ state: 'error', why: 'GitHub answered 502' }));
    render(<TabletScreens on={on('PIXEL_DESKTOP')} start="get" load={load} />);
    await screen.findByText(/couldn.t read the releases: GitHub answered 502/);
    load.mockResolvedValueOnce(rel('generic-android'));
    fireEvent.click(screen.getByText('Try again'));
    await screen.findByText('Download');
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('Set up: the app\'s steps, and the one setting that needs a computer', async () => {
    await show('setup');
    expect(heading()).toBe('Open Glassworks');
    expect([...document.querySelectorAll('.num > li > b')].map((b) => b.textContent)).toEqual(['Home Assistant', 'Panes', 'This device', 'Silent updates']);
    fireEvent.click(screen.getByText('Finish with Kiln'));
    expect(heading()).toBe('Turn on USB debugging');
  });

  it('Kiln: the brand\'s steps, About tablet a link, Developer options not', async () => {
    await show('kiln');
    expect(screen.getByText('Open it').getAttribute('href')).toBe(SETTINGS_LINKS.about);
    expect(document.querySelectorAll('a[href^="intent:"]')).toHaveLength(1);
    expect(document.body.textContent).toContain('Developer options');
  });

  it('Kiln on an unknown tablet: Android\'s steps, and its brand to pick', async () => {
    await show('kiln-unknown');
    expect(document.querySelectorAll('.when > li')).toHaveLength(3);
    fireEvent.click(screen.getByText('Pick your brand'));
    fireEvent.click(screen.getByRole('radio', { name: 'Amazon Fire' }));
    expect(document.body.textContent).toContain('About Fire Tablet');
  });

  it('says it is reading the device until the browser answers', async () => {
    render(<TabletPage />);
    expect(screen.getByRole('status').textContent).toBe('Reading this device…');
    await screen.findByRole('heading');
  });

  it.each(Object.keys(TABLET_DEMOS) as TabletDemo[])('demo %s has a heading and at most one ember button', async (name) => {
    await show(name);
    expect(heading()).not.toBe('');
    expect(embers().length).toBeLessThanOrEqual(1);
  });
});
