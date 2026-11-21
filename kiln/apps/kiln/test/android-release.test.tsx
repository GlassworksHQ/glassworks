// @vitest-environment jsdom
// /android?apk=release: the APK from the newest generic-android-v* release (never the legacy build's line) through
// Kiln's release route, checked against the release's .sha256. No release is an error, never the file picker.
// Generic Android is beta (frame.json), so the page is open: without ?apk=…, the APK comes from the release. The
// locked page of a frame that isn't open: device-locked.test.tsx.
import { act, cleanup, render, screen } from '@testing-library/react';
import { fakeGenericApk } from '@glassworks/generic-android-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { demoRelease, fakeReleaseRoute, type FakeRelease } from '../src/release/demo';
import { registry } from '../src/wizard/registry';
import { ANDROID_BASE, ANDROID_OPEN, AndroidActivity, androidMode, AndroidSession, AndroidWizard } from './android-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

const APK = fakeGenericApk({ abis: ['arm64-v8a'] });
const { tag: TAG, apk: NAME } = demoRelease('generic-android');
const route = (kind: FakeRelease) => fakeReleaseRoute(kind, APK, async () => {}, 'generic-android');
const session = (release: typeof fetch) => new AndroidSession({ findUsb: async () => { throw new Error('unused'); }, connect: async () => { throw new Error('unused'); }, release }, true);
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
const until = async (f: () => boolean, ms = 5_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
  expect(f()).toBe(true);
};

describe('the switch', () => {
  it('opens to everyone: generic-android\'s frame.json makes it installable (beta, with its kiln route)', () => {
    expect(ANDROID_OPEN).toBe(true);
    expect(androidMode('', false, [])).toEqual({ on: true, apk: 'release' });
    expect(androidMode('', false, [], false)).toEqual({ on: false }); // a frame that isn't open: only the test links
  });

  it('?apk=release and ?apk=local are the test links; once open, the page takes the release by default', () => {
    expect(androidMode('?apk=release', false, [])).toEqual({ on: true, apk: 'release' });
    expect(androidMode('?apk=local', false, [])).toEqual({ on: true, apk: 'local' });
    expect(androidMode('', false, [], true)).toEqual({ on: true, apk: 'release' });
  });
});

describe('the release\'s APK', () => {
  it('downloads the newest generic-android release\'s arm64-v8a APK, never the legacy line\'s, and checks its SHA-256', async () => {
    const asked: string[] = [];
    const r = route('out');
    const s = session((async (u: RequestInfo | URL) => { asked.push(String(u)); return r(u); }) as typeof fetch);
    expect(s.blocked()).toBe('Kiln is still downloading Glassworks.');
    await s.loadRelease();
    expect(asked).toEqual([
      '/api/release/generic-android/latest',
      `/api/release/generic-android?tag=${TAG}&name=${NAME}.sha256`,
      `/api/release/generic-android?tag=${TAG}&name=${NAME}`,
    ]);
    expect(TAG).toBe('generic-android-v0.2.0');
    expect(s.state.release).toMatchObject({ state: 'ready', tag: TAG, name: NAME });
    expect(s.state.apk).toMatchObject({ name: NAME, package: 'dev.glassworks.generic_android' });
    expect(s.blocked()).toBeUndefined();
  });

  it('drops an APK whose SHA-256 isn\'t the release\'s, and says so', async () => {
    const s = session(route('bad'));
    await s.loadRelease();
    expect(s.state.release).toMatchObject({ state: 'error' });
    expect(s.state.release?.state === 'error' && s.state.release.why).toMatch(new RegExp(`^${NAME} isn't the file ${TAG} publishes`));
    expect(s.blocked()).toBe('Kiln couldn\'t get Glassworks from its release. Try again.');
  });

  it('a release APK the checks refuse is an error with Try again: a fixed release then goes through', async () => {
    let apk = fakeGenericApk({ package: 'com.example.other', abis: ['arm64-v8a'] });
    const s = session(((u: RequestInfo | URL) => fakeReleaseRoute('out', apk, async () => {}, 'generic-android')(u)) as typeof fetch);
    await s.loadRelease();
    expect(s.state.release).toMatchObject({ state: 'error', why: expect.stringMatching(/^Kiln can't install it: /) });
    expect(s.blocked()).toMatch(/^The release's APK can't go on the tablet: /);
    apk = APK; // a fixed release
    await s.loadRelease();
    expect(s.state.release).toMatchObject({ state: 'ready', tag: TAG });
    expect(s.blocked()).toBeUndefined();
  });

  it('no release is its own state', async () => {
    const s = session(route('none'));
    await s.loadRelease();
    expect(s.state.release).toEqual({ state: 'none' });
    expect(s.blocked()).toBe('There is no release of Glassworks for Android yet.');
  });

  it('undo needs no APK', async () => {
    const s = session(route('none'));
    s.setFlow('undo');
    expect(s.blocked()).toBeUndefined();
  });
});

describe('the page', () => {
  it('?apk=release: the release, checked, with no file picker', async () => {
    vi.stubGlobal('fetch', route('out'));
    history.replaceState(null, '', '/android?apk=release');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.queryByText('Test only')).toBeNull(); // beta (frame.json): open to everyone, not a test page
    await screen.findByText('Glassworks 0.2.0');
    expect(screen.getByText(/SHA-256 checks out/)).toBeTruthy();
    expect(screen.getByText(/^Android 8 or newer, over USB\./)).toBeTruthy(); // the release is the generic build, not the legacy one for 6 and 7
    expect(screen.getByRole('link', { name: NAME }).getAttribute('href')).toBe(`https://github.com/GlassworksHQ/glassworks/releases/tag/${TAG}`);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Find it']);
  });

  it('?apk=release with no release: a clear error, not the local picker', async () => {
    vi.stubGlobal('fetch', route('none'));
    history.replaceState(null, '', '/android?apk=release');
    render(<AndroidWizard />);
    await screen.findByText('There is no release of Glassworks for Android yet.');
    expect(screen.getByRole('alert').textContent).toMatch(/no generic-android release on GitHub\. Nothing was written to the tablet/);
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('?apk=local keeps the file picker and never asks the release route', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    history.replaceState(null, '', '/android?apk=local');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(document.querySelector('input[type=file]')).not.toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('without ?apk=…, the APK comes from the release, as for ?apk=release', async () => {
    vi.stubGlobal('fetch', route('out'));
    history.replaceState(null, '', '/android');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    await screen.findByText('Glassworks 0.2.0');
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Find it']);
  });

  it('?apk=release after ?apk=local starts a session on the release; a running one comes back to its own link', async () => {
    vi.stubGlobal('fetch', route('out'));
    history.replaceState(null, '', '/android?apk=local');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    const first = registry.activity(ANDROID_BASE) as AndroidActivity;
    expect(first.session.from).toBe('local');
    cleanup();
    history.replaceState(null, '', '/android?apk=release');
    render(<AndroidWizard />);
    await until(() => !!screen.queryByText('Glassworks 0.2.0'));
    const second = registry.activity(ANDROID_BASE) as AndroidActivity;
    expect(second).not.toBe(first);
    expect(second.session.from).toBe('release');
    second.session.state = { ...second.session.state, screen: 'working' };
    expect(second.running()).toBe('/android?apk=release');
  });
});
