// @vitest-environment jsdom
// /lululemon-mirror?apk=release: the APK from the newest Mirror release through Kiln's release route, checked against
// the release's .sha256. No release is an error, never the file picker. The Mirror is beta (frame.json), so the page is
// open: without ?apk=…, the APK comes from the release. The locked page of a frame that isn't open: device-locked.test.tsx.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { StrictMode, type ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { DevicePage } from '../src/device/DevicePage';
import { releaseApk, type ReleaseApk } from '../src/release/apk';
import { demoRelease, fakeReleaseRoute as routeFor, type FakeRelease } from '../src/release/demo';
import { AppSession, deviceMode, deviceOpen, releaseLine } from '../src/device/appSession';
import { registry } from '../src/wizard/registry';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

/** The Mirror's line: its stand-in release and the route that serves it. */
const { tag: DEMO_TAG, apk: DEMO_APK } = demoRelease(releaseLine(mirrorFrame).line);
const fakeReleaseRoute = (kind: FakeRelease, apk: Uint8Array, tick: (ms: number) => Promise<void> = async () => {}) => routeFor(kind, apk, tick, releaseLine(mirrorFrame).line);
const APK = fakeMirrorApk();
const session = (kind: FakeRelease | typeof fetch) =>
  new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); }, release: typeof kind === 'function' ? kind : fakeReleaseRoute(kind, APK) }, true);
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('the switch', () => {
  it('opens to everyone: frame.json makes the Mirror installable (beta, with its kiln route)', () => {
    expect(deviceOpen(mirrorFrame)).toBe(true);
    expect(deviceMode('', false, [], deviceOpen(mirrorFrame))).toEqual({ on: true, apk: 'release' });
  });

  it('?apk=release and ?apk=local are the test links; once open, the page takes the release by default', () => {
    expect(deviceMode('?apk=release', false, [], false)).toEqual({ on: true, apk: 'release' });
    expect(deviceMode('?apk=local', false, [], false)).toEqual({ on: true, apk: 'local' });
    expect(deviceMode('', false, [], true)).toEqual({ on: true, apk: 'release' });
    expect(deviceMode('?apk=local', false, [], true)).toEqual({ on: true, apk: 'local' });
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
      `https://refract.glassworks.dev/glassworks/${DEMO_TAG}/${DEMO_APK}.sha256`,
      `https://refract.glassworks.dev/glassworks/${DEMO_TAG}/${DEMO_APK}`,
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
    expect(s.state.release?.state === 'error' && s.state.release.why).toMatch(/^The downloaded file didn't match the published release, so Kiln discarded it$/);
    expect(s.releaseBlocked()).toBe('Kiln couldn\'t get Glassworks from its release. Try again.');
    expect(s.state.log.at(-1)).toMatch(/^The release's APK: The downloaded file didn't match/);
  });

  it('a release APK that isn\'t the Mirror\'s app is refused before any write, and Try again fetches the release again', async () => {
    let apk = fakeApk('com.example.other');
    const s = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); }, release: ((u: RequestInfo | URL) => fakeReleaseRoute('out', apk)(u)) as typeof fetch }, true);
    await s.loadRelease();
    expect(s.state.release).toMatchObject({ state: 'error', why: expect.stringMatching(/^Kiln can't install it: /) });
    expect(s.apkBytes()).toBeUndefined();
    expect(s.releaseBlocked()).toMatch(/^The release's APK can't go on the Mirror: /);
    apk = fakeMirrorApk(); // a fixed release
    await s.loadRelease();
    expect(s.state.release?.state).toBe('ready');
    expect(s.apkBytes()).toBeDefined();
    expect(s.releaseBlocked()).toBeUndefined();
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

  it('a failing release list or a missing .sha256 is an error, not "no release"', async () => {
    const down = session((async () => new Response('', { status: 502 })) as typeof fetch);
    await down.loadRelease();
    expect(down.state.release).toEqual({ state: 'error', why: 'Couldn\'t check for releases: the release server answered 502' });
    const route = fakeReleaseRoute('out', APK);
    const noSum = session((async (u: RequestInfo | URL) => (String(u).endsWith('.sha256') ? new Response('', { status: 502 }) : route(u))) as typeof fetch);
    await noSum.loadRelease();
    expect(noSum.state.release).toEqual({ state: 'error', why: 'Couldn\'t verify the release checksum for 0.2.0 (the download proxy answered 502)' });
  });

  /** A request that never answers, until its signal aborts (the real fetch rejects then). */
  const silent = (init?: RequestInit) => new Promise<Response>((_, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));

  it('a .sha256 that never answers ends in Try again, not a row stuck on "Looking" (Mirror run, 2026-11-19)', async () => {
    const route = fakeReleaseRoute('out', APK);
    let hang = true;
    const s = new AppSession({ frame: mirrorFrame,
      connect: async () => { throw new Error('unused'); },
      release: (async (u: RequestInfo | URL, init?: RequestInit) => (hang && String(u).endsWith('.sha256') ? silent(init) : route(u, init))) as typeof fetch,
      releaseFetch: { stallMs: 50, retries: 1, sleep: async () => {} },
    }, true);
    await s.loadRelease();
    expect(s.state.release).toEqual({ state: 'error', why: 'Couldn\'t verify the release checksum for 0.2.0 (the download proxy sent nothing for 1 s)' });
    expect(s.releaseBlocked()).toBe('Kiln couldn\'t get Glassworks from its release. Try again.');
    hang = false;
    await s.loadRelease(); // Try again
    expect(s.state.release?.state).toBe('ready');
  });

  it('a release list that never answers ends in Try again too, not a row stuck on "Looking"', async () => {
    const route = fakeReleaseRoute('out', APK);
    let hang = true;
    const s = new AppSession({ frame: mirrorFrame,
      connect: async () => { throw new Error('unused'); },
      release: (async (u: RequestInfo | URL, init?: RequestInit) => (hang && String(u).endsWith('/latest') ? silent(init) : route(u, init))) as typeof fetch,
      releaseFetch: { stallMs: 50, retries: 0, sleep: async () => {} },
    }, true);
    await s.loadRelease();
    expect(s.state.release).toEqual({ state: 'error', why: 'Couldn\'t check for releases: the release server stopped responding after 0.05 seconds' });
    hang = false;
    await s.loadRelease(); // Try again
    expect(s.state.release?.state).toBe('ready');
  });

  it('updates the screen in whole-percent steps, not once per chunk', async () => {
    const big = new Uint8Array(1_000_000);
    const route = fakeReleaseRoute('out', big);
    const chunked = (async (u: RequestInfo | URL) => {
      if (!String(u).endsWith(DEMO_APK)) return route(u);
      let at = 0;
      const body = new ReadableStream<Uint8Array>({ pull(c) { if (at >= big.length) c.close(); else { c.enqueue(big.slice(at, at + 4000)); at += 4000; } } });
      return new Response(body, { headers: { 'content-length': String(big.length) } });
    }) as typeof fetch;
    const states: ReleaseApk[] = [];
    await releaseApk(releaseLine(mirrorFrame), chunked, (x) => states.push(x));
    const downloading = states.filter((x) => x.state === 'downloading');
    expect(downloading.length).toBeLessThanOrEqual(101); // 250 chunks
    expect(downloading.at(-1)).toMatchObject({ got: 1_000_000, size: 1_000_000 });
  });

  it('an APK download that stops part way ends in Try again too', async () => {
    const route = fakeReleaseRoute('stuck', APK);
    const s = new AppSession({ frame: mirrorFrame,
      connect: async () => { throw new Error('unused'); },
      release: (async (u: RequestInfo | URL, init?: RequestInit) => {
        const res = await route(u, init);
        if (!String(u).endsWith(DEMO_APK)) return res;
        const reader = res.body!.getReader();
        // the stand-in route stalls after three chunks; the real fetch's body errors when its signal aborts
        const body = new ReadableStream<Uint8Array>({
          start(c) { init?.signal?.addEventListener('abort', () => c.error(new DOMException('aborted', 'AbortError'))); },
          async pull(c) { const { done, value } = await reader.read(); if (done) c.close(); else c.enqueue(value); },
        });
        return new Response(body, { status: res.status, headers: res.headers });
      }) as typeof fetch,
      releaseFetch: { stallMs: 50, retries: 1, sleep: async () => {} },
    }, true);
    await s.loadRelease();
    // it resumed once where it stopped, and the stand-in stalled there again
    expect(s.state.release).toEqual({ state: 'error', why: 'Couldn\'t download Glassworks 0.2.0 (the download proxy sent nothing for 1 s)' });
    expect(s.apkBytes()).toBeUndefined();
  });

  it('an APK download that drops resumes with Range where it stopped, and the bytes still match the .sha256', async () => {
    const route = fakeReleaseRoute('out', APK);
    const ranges: (string | null)[] = [];
    let drops = 1;
    const s = new AppSession({ frame: mirrorFrame,
      connect: async () => { throw new Error('unused'); },
      release: (async (u: RequestInfo | URL, init?: RequestInit) => {
        const res = await route(u, init);
        if (!String(u).endsWith(DEMO_APK)) return res;
        ranges.push(new Headers(init?.headers).get('range'));
        if (drops-- <= 0) return res;
        // the connection drops after the first chunk
        const reader = res.body!.getReader();
        let sent = false;
        const body = new ReadableStream<Uint8Array>({
          async pull(c) { if (sent) { c.error(new TypeError('network error')); return; } const { value } = await reader.read(); c.enqueue(value!); sent = true; },
        });
        return new Response(body, { status: res.status, headers: res.headers });
      }) as typeof fetch,
      releaseFetch: { stallMs: 1000, sleep: async () => {} },
    }, true);
    await s.loadRelease();
    expect(s.state.release).toMatchObject({ state: 'ready', size: APK.length });
    expect(s.apkBytes()).toEqual(APK);
    expect(ranges).toEqual([null, `bytes=${Math.ceil(APK.length / 8)}-`]);
  });
});

describe('the page', () => {
  const until = async (f: () => boolean, ms = 5_000) => {
    const end = Date.now() + ms;
    while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
    expect(f()).toBe(true);
  };
  const toImage = async () => {
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(screen.queryByText('Test only')).toBeNull(); // beta (frame.json): open to everyone, not a test page
    fireEvent.click(screen.getByRole('radio', { name: 'Glassworks' })); // the full image, not the recommended in-place way
    fireEvent.click(screen.getByRole('button', { name: 'Continue with Glassworks' }));
    await screen.findByText('Install Glassworks on the Mirror');
  };

  it('?apk=release: Glassworks from its release, checked, with no file picker', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    await screen.findByText('Glassworks 0.2.0');
    expect(screen.getByText(/Download verified/)).toBeTruthy();
    expect(screen.getByRole('link', { name: DEMO_APK }).getAttribute('href')).toBe(`https://github.com/GlassworksHQ/glassworks/releases/tag/${DEMO_TAG}`);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Download the system image']);
  });

  it('?apk=release with no release: a clear error, not the local picker', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('none', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    await screen.findByText('No compatible Glassworks for the Mirror release yet');
    expect(screen.getByRole('alert').textContent).toMatch(/No compatible release package for this device is available on GitHub yet\. Nothing was written to the Mirror/);
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('?apk=release&mode=app: the app alone over adb, its APK from the release', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release&mode=app');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Install Glassworks on the Mirror');
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

  it('without ?apk=…, Glassworks comes from its release, as for ?apk=release', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror');
    await toImage();
    await screen.findByText('Glassworks 0.2.0');
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Download the system image']);
  });

  it('asks for no Wi-Fi and no Home Assistant on the image screen: those are set-up\'s, after the first start', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    expect(screen.queryByText(/Wi-Fi network/)).toBeNull();
    expect(screen.queryByText(/Wi-Fi password/)).toBeNull();
    expect(screen.queryByText(/Home Assistant \(optional\)/)).toBeNull();
  });

  it('release, file, release again on the kept session while the first lookup is still out (StrictMode, as next dev runs it)', async () => {
    const gates: (() => void)[] = [];
    const route = fakeReleaseRoute('out', APK);
    vi.stubGlobal('fetch', (async (u: RequestInfo | URL) => { if (String(u).endsWith('/latest')) await new Promise<void>((r) => gates.push(r)); return route(u); }) as typeof fetch);
    const page = async (q: string) => {
      cleanup();
      history.replaceState(null, '', `/lululemon-mirror?apk=${q}`);
      render(<StrictMode><DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} /></StrictMode>);
      await until(() => !!screen.queryByText('Choose what to install on the Mirror') || !!screen.queryByText('Install Glassworks on the Mirror'));
      if (!screen.queryByText('Install Glassworks on the Mirror')) {
        fireEvent.click(screen.getByRole('radio', { name: 'Glassworks' })); // the full image, not the recommended in-place way
        fireEvent.click(screen.getByRole('button', { name: 'Continue with Glassworks' }));
      }
      await screen.findByText('Install Glassworks on the Mirror');
    };
    await page('release');
    await until(() => gates.length === 1 && !!screen.queryByText('Checking for the latest release…'));
    await page('local');
    expect(document.querySelector('input[type=file]')).not.toBeNull();
    await page('release');
    await until(() => gates.length === 2);
    gates[0]!(); // the first lookup answers late: it no longer counts
    gates[1]!();
    await until(() => !!screen.queryByText('Glassworks 0.2.0'));
    expect(kept().sessions.app.state.release?.state).toBe('ready');
  });

  type Kept = { sessions: { app: AppSession; image: { state: { screen: string } } }; running(): string | undefined };
  const kept = () => registry.activity('/lululemon-mirror') as unknown as Kept;

  it('a running install comes back to its own link', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    expect(kept().sessions.app.from).toBe('release');
    kept().sessions.image.state = { ...kept().sessions.image.state, screen: 'working' };
    expect(kept().running()).toBe('/lululemon-mirror?apk=release');
  });

  it('?apk=release after ?apk=local keeps the one session and its image worker, and takes the APK from the release', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    const first = kept();
    await act(() => first.sessions.app.pickApk('local.apk', APK));
    cleanup();
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    await toImage();
    expect(kept()).toBe(first);
    expect(first.sessions.app.from).toBe('release');
    await screen.findByText('Glassworks 0.2.0');
    expect(first.sessions.app.state.apk?.name).toBe(DEMO_APK);
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('the app-only path takes ?apk=release on a kept session whose fastboot install is done', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    const first = kept();
    await act(() => first.sessions.app.pickApk('local.apk', APK));
    first.sessions.image.state = { ...first.sessions.image.state, screen: 'done' };
    cleanup();
    history.replaceState(null, '', '/lululemon-mirror?apk=release&mode=app');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect(kept()).toBe(first);
    expect(first.sessions.app.from).toBe('release');
    await screen.findByText('Glassworks 0.2.0');
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('a session past its download keeps the APK it has', async () => {
    vi.stubGlobal('fetch', fakeReleaseRoute('out', APK));
    history.replaceState(null, '', '/lululemon-mirror?apk=local');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    await screen.findByText('Choose what to install on the Mirror');
    const first = kept();
    await act(() => first.sessions.app.pickApk('local.apk', APK));
    first.sessions.image.state = { ...first.sessions.image.state, screen: 'fastboot' };
    cleanup();
    history.replaceState(null, '', '/lululemon-mirror?apk=release');
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect(kept()).toBe(first);
    expect(first.sessions.app.from).toBe('local');
    expect(first.sessions.app.apkBytes()).toEqual(APK);
  });

  it('switching back to the file drops the release\'s APK, and a download still running no longer counts', async () => {
    let finish!: () => void;
    const route = fakeReleaseRoute('out', APK);
    const slow = (async (u: RequestInfo | URL) => { if (!String(u).endsWith('.sha256') && !String(u).endsWith('latest')) await new Promise<void>((r) => { finish = r; }); return route(u); }) as typeof fetch;
    const s = session(slow);
    const loading = s.loadRelease();
    await until(() => s.state.release?.state === 'downloading' && !!finish);
    s.switchApk('local', slow);
    expect(s.state.release).toBeUndefined();
    finish();
    await loading;
    expect(s.state.release).toBeUndefined();
    expect(s.apkBytes()).toBeUndefined();
    expect(s.installBlocked()).toBe('This device doesn\'t pass the checks.');
  });

  it('release, file, release again: only the latest release download counts', async () => {
    const gates: (() => void)[] = [];
    const route = fakeReleaseRoute('out', APK);
    const gated = (async (u: RequestInfo | URL) => { if (String(u).endsWith('latest')) await new Promise<void>((r) => gates.push(r)); return route(u); }) as typeof fetch;
    const s = session(gated);
    const first = s.loadRelease();
    s.switchApk('local', gated);
    s.switchApk('release', gated);
    const second = s.loadRelease();
    await until(() => gates.length === 2);
    gates[1]!();
    await second;
    const ready = s.state.release;
    expect(ready?.state).toBe('ready');
    gates[0]!();
    await first;
    expect(s.state.release).toBe(ready);
    expect(s.state.log.filter((l) => l.includes('as the release publishes it'))).toHaveLength(1);
  });

  it('a switch to the file while the release\'s APK is being checked leaves no "ready" behind', async () => {
    const s = session('out');
    const pick = s.pickApk.bind(s);
    vi.spyOn(s, 'pickApk').mockImplementation(async (name, data) => { const p = pick(name, data); s.switchApk('local', fakeReleaseRoute('out', APK)); await p; });
    await s.loadRelease();
    expect(s.state.release).toBeUndefined();
    expect(s.from).toBe('local');
    expect(s.apkBytes()).toBeUndefined();
  });
});
