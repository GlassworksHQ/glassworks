// @vitest-environment jsdom
// /lululemon-mirror?apk=release: the APK from the newest Mirror release through Kiln's release route, checked against
// the release's .sha256. No release is an error, never the file picker. Without ?apk=…, the page stays locked.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { MirrorWizard } from '../src/mirror/MirrorWizard';
import { DEMO_APK, DEMO_TAG, fakeReleaseRoute, type FakeRelease } from '../src/mirror/releaseDemo';
import { MIRROR_OPEN, MirrorSession, mirrorMode } from '../src/mirror/session';
import { registry } from '../src/wizard/registry';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

const APK = fakeMirrorApk();
const session = (kind: FakeRelease | typeof fetch) =>
  new MirrorSession({ connect: async () => { throw new Error('unused'); }, release: typeof kind === 'function' ? kind : fakeReleaseRoute(kind, APK) }, true);
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('the switch', () => {
  it('stays locked to the public: frame.json doesn\'t make the Mirror installable yet', () => {
    expect(MIRROR_OPEN).toBe(false);
    expect(mirrorMode('', false, [])).toEqual({ on: false });
  });

  it('?apk=release and ?apk=local are the test links; once open, the page takes the release by default', () => {
    expect(mirrorMode('?apk=release', false, [])).toEqual({ on: true, apk: 'release' });
    expect(mirrorMode('?apk=local', false, [])).toEqual({ on: true, apk: 'local' });
    expect(mirrorMode('', false, [], true)).toEqual({ on: true, apk: 'release' });
    expect(mirrorMode('?apk=local', false, [], true)).toEqual({ on: true, apk: 'local' });
  });
});

describe('the release\'s APK', () => {
  it('downloads the newest release\'s arm64-v8a APK, checks its SHA-256, and passes it as a picked file', async () => {
    const asked: string[] = [];
    const route = fakeReleaseRoute('out', APK);
    const s = session((async (u: RequestInfo | URL) => { asked.push(String(u)); return route(u); }) as typeof fetch);
    expect(s.state.release).toEqual({ state: 'waiting' });
    expect(s.installBlocked()).toBe('This device doesn\'t pass the checks.');
    await s.loadRelease();
    expect(asked).toEqual([
      '/api/release/generic-android-legacy/latest',
      `/api/release/generic-android-legacy?tag=${DEMO_TAG}&name=${DEMO_APK}.sha256`,
      `/api/release/generic-android-legacy?tag=${DEMO_TAG}&name=${DEMO_APK}`,
    ]);
    expect(s.state.release).toMatchObject({ state: 'ready', tag: DEMO_TAG, version: '0.2.0', name: DEMO_APK, size: APK.length });
    expect(s.apkBytes()).toEqual(APK);
    expect(s.state.apk).toMatchObject({ name: DEMO_APK, package: GLASSWORKS.package });
    expect(s.releaseBlocked()).toBeUndefined();
    await s.loadRelease(); // once is enough
    expect(asked).toHaveLength(3);
  });

  it('drops an APK whose SHA-256 isn\'t the release\'s, and says so', async () => {
    const s = session('bad');
    await s.loadRelease();
    expect(s.apkBytes()).toBeUndefined();
    expect(s.state.release).toMatchObject({ state: 'error' });
    expect(s.state.release?.state === 'error' && s.state.release.why).toMatch(new RegExp(`^${DEMO_APK} isn't the file ${DEMO_TAG} publishes: its SHA-256 is [0-9a-f]{64}, the release says (ab){32}`));
    expect(s.releaseBlocked()).toBe('Kiln couldn\'t get Glassworks from its release. Try again.');
    expect(s.state.log.at(-1)).toMatch(/^The release's APK: .* isn't the file/);
  });

  it('a release APK that isn\'t the Mirror\'s app is refused before any write', async () => {
    const s = new MirrorSession({ connect: async () => { throw new Error('unused'); }, release: fakeReleaseRoute('out', fakeApk('com.example.other')) }, true);
    await s.loadRelease();
    expect(s.state.release?.state).toBe('ready');
    expect(s.apkBytes()).toBeUndefined();
    expect(s.releaseBlocked()).toMatch(/^The release's APK can't go on the Mirror: /);
  });

  it('no release is its own state, and Try again looks again', async () => {
    let kind: FakeRelease = 'none';
    const s = session(((u: RequestInfo | URL) => fakeReleaseRoute(kind, APK)(u)) as typeof fetch);
    await s.loadRelease();
    expect(s.state.release).toEqual({ state: 'none' });
    expect(s.apkBytes()).toBeUndefined();
    expect(s.releaseBlocked()).toBe('There is no release of Glassworks for the Mirror yet.');
    kind = 'out';
    await s.loadRelease();
    expect(s.state.release?.state).toBe('ready');
  });

  it('a release without the arm64-v8a APK is no release for the Mirror', async () => {
    const s = session((async () => Response.json({ release: { tag: DEMO_TAG, version: '0.2.0', assets: [DEMO_APK.replace('arm64-v8a', 'armeabi-v7a')] } })) as typeof fetch);
    await s.loadRelease();
    expect(s.state.release).toEqual({ state: 'none' });
  });

  it('a failing release route or a missing .sha256 is an error, not "no release"', async () => {
    const down = session((async () => new Response('', { status: 502 })) as typeof fetch);
    await down.loadRelease();
    expect(down.state.release).toEqual({ state: 'error', why: 'Kiln couldn\'t read the releases: Kiln\'s release route answered 502' });
    const route = fakeReleaseRoute('out', APK);
    const noSum = session((async (u: RequestInfo | URL) => (String(u).endsWith('.sha256') ? new Response('', { status: 502 }) : route(u))) as typeof fetch);
    await noSum.loadRelease();
    expect(noSum.state.release).toEqual({ state: 'error', why: `Kiln couldn't read ${DEMO_APK}.sha256 from ${DEMO_TAG} (its release route answered 502)` });
  });
});

describe('the page', () => {
  const until = async (f: () => boolean, ms = 5_000) => {
    const end = Date.now() + ms;
    while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
    expect(f()).toBe(true);
  };
  const toImage = async () => {
    render(<MirrorWizard />);
    await screen.findByText('What goes on the Mirror?');
    expect(screen.getByText('Test only')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Continue with Glassworks' }));
    await screen.findByText('Install Glassworks on the Mirror.');
  };

  it('?apk=release: Glassworks from its release, checked, with no file picker', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    await screen.findByText('Glassworks 0.2.0');
    expect(screen.getByText(/Checks out: SHA-256 as the release publishes it/)).toBeTruthy();
    expect(screen.getByRole('link', { name: DEMO_APK }).getAttribute('href')).toBe(`https://github.com/GlassworksHQ/glassworks/releases/tag/${DEMO_TAG}`);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Download the system image']);
  });

  it('?apk=release with no release: a clear error, not the local picker', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('none', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    await screen.findByText('There is no release of Glassworks for the Mirror yet.');
    expect(screen.getByRole('alert').textContent).toMatch(/found none\. Nothing was written to the Mirror/);
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('?apk=release&mode=app: the app alone over adb, its APK from the release', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release&mode=app');
    render(<MirrorWizard />);
    await screen.findByText('Install Glassworks on the Mirror.');
    await until(() => !!screen.queryByText('Glassworks 0.2.0'));
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Connect']);
  });

  it('?apk=local keeps the file picker and never asks the release route', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    await toImage();
    expect(document.querySelector('input[type=file]')).not.toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('without ?apk=…, the page stays locked and asks nothing', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    history.replaceState(null, '', '/lululemon-mirror');
    render(<MirrorWizard />);
    await screen.findByText(/doesn.t install from Kiln yet/);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('a running install comes back to its own link', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    render(<MirrorWizard />);
    await screen.findByText('What goes on the Mirror?');
    const kept = registry.activity('/lululemon-mirror') as unknown as { sessions: { mirror: MirrorSession; flash: { state: { screen: string } } }; running(): string | undefined };
    expect(kept.sessions.mirror.from).toBe('release');
    kept.sessions.flash.state = { ...kept.sessions.flash.state, screen: 'working' };
    expect(kept.running()).toBe('/lululemon-mirror?apk=release');
  });
});
