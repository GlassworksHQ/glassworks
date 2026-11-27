// @vitest-environment jsdom
// /android?apk=release: the APK from the newest generic-android-v* release (never the legacy build's line) through
// Kiln's release route, checked against the release's .sha256. No release is an error, never the file picker.
// Generic Android is stable (frame.json), so the page is open: without ?apk=…, the APK comes from the release. The
// locked page of a frame that isn't open: device-locked.test.tsx.
import { act, cleanup, render, screen } from '@testing-library/react';
import { FakeAndroid, fakeGenericApk } from '@glassworks/generic-android-installer/fakes';
import { SAMSUNG_32BIT } from '@glassworks/generic-android-installer/sim';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { apkAbi } from '../src/release/apk';
import { demoRelease, fakeReleaseRoute, type FakeRelease } from '../src/release/demo';
import { registry } from '../src/wizard/registry';
import { ANDROID_BASE, ANDROID_OPEN, AndroidActivity, androidDemo, androidMode, AndroidSession, AndroidWizard } from './android-frame';
import { Screens } from '../src/device/UsbPage';

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
  it('opens to everyone: generic-android\'s frame.json makes it installable (stable, with its kiln route)', () => {
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
      `https://refract.glassworks.dev/glassworks/${TAG}/${NAME}.sha256`,
      `https://refract.glassworks.dev/glassworks/${TAG}/${NAME}`,
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
    expect(s.state.release?.state === 'error' && s.state.release.why).toMatch(/didn't match the published release/);
    expect(s.blocked()).toBe('Kiln couldn\'t get Glassworks from its release. Try again.');
  });

  it('a release APK the checks refuse is an error with Try again: a fixed release then goes through', async () => {
    let apk = fakeGenericApk({ package: 'com.example.other', abis: ['arm64-v8a'] });
    const s = session(((u: RequestInfo | URL, init?: RequestInit) => fakeReleaseRoute('out', apk, async () => {}, 'generic-android')(u, init)) as typeof fetch);
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

describe('the release\'s APK for the device\'s ABI (issue 700)', () => {
  // the release's two builds, each with code for its own ABI only
  const BUILDS = { 'arm64-v8a': APK, 'armeabi-v7a': fakeGenericApk({ abis: ['armeabi-v7a'] }) };
  const NAME_32 = NAME.replace('arm64-v8a', 'armeabi-v7a');
  const run = async (tablet: FakeAndroid, builds: Record<string, Uint8Array> = BUILDS) => {
    const asked: string[] = [];
    const r = fakeReleaseRoute('out', builds, async () => {}, 'generic-android');
    const s = new AndroidSession({
      findUsb: async () => ({ vendorId: 0x04e8, productName: 'SAMSUNG_Android' }), connect: async () => tablet,
      release: (async (u: RequestInfo | URL) => { asked.push(String(u)); return r(u); }) as typeof fetch, sleep: async () => {}, setupTries: 1,
    }, true);
    await s.loadRelease();
    await s.find();
    s.toSteps();
    await s.connect();
    return { s, asked };
  };

  it('picks the first of the device\'s ABIs the frame ships, in the device\'s order', () => {
    const ships = ['arm64-v8a', 'armeabi-v7a'];
    expect(apkAbi(['armeabi-v7a', 'armeabi'], ships)).toBe('armeabi-v7a'); // a 64-bit chip, a 32-bit userspace
    expect(apkAbi(['arm64-v8a', 'armeabi-v7a', 'armeabi'], ships)).toBe('arm64-v8a');
    expect(apkAbi(['arm64-v8a'], ships)).toBe('arm64-v8a');
    expect(apkAbi(['x86_64', 'x86'], ships)).toBeUndefined();
  });

  it('a Galaxy Tab A 10.1 on Samsung\'s Android 8.1 (32-bit userspace) gets the armeabi-v7a build, and installs', async () => {
    const tablet = new FakeAndroid(SAMSUNG_32BIT);
    const { s, asked } = await run(tablet);
    expect(s.state.error).toBeUndefined();
    expect(s.state.screen).toBe('done');
    expect(s.state.plan).toMatchObject({ abi: 'armeabi-v7a', device: { abis: ['armeabi-v7a', 'armeabi'], sdk: 27 } });
    expect(s.state.apk).toMatchObject({ name: NAME_32 });
    expect(s.state.release).toMatchObject({ state: 'ready', name: NAME_32 });
    // the first screen's download, then the 32-bit build from the same release once Kiln could read the device
    expect(asked.filter((u) => !u.endsWith('/latest'))).toEqual([
      `https://refract.glassworks.dev/glassworks/${TAG}/${NAME}.sha256`, `https://refract.glassworks.dev/glassworks/${TAG}/${NAME}`,
      `https://refract.glassworks.dev/glassworks/${TAG}/${NAME_32}.sha256`, `https://refract.glassworks.dev/glassworks/${TAG}/${NAME_32}`,
    ]);
    // read before it wrote anything: the abilist came before the APK went on
    const first = tablet.cmds.findIndex((c) => c.startsWith('push '));
    expect(tablet.cmds.indexOf('getprop ro.product.cpu.abilist')).toBeLessThan(first);
    expect(s.state.log.join('\n')).toMatch(/The tablet runs armeabi-v7a, armeabi: the release's armeabi-v7a build/);
  });

  it('a 64-bit tablet keeps the first screen\'s download: no second one', async () => {
    const { s, asked } = await run(new FakeAndroid());
    expect(s.state.screen).toBe('done');
    expect(s.state.plan?.abi).toBe('arm64-v8a');
    expect(asked.filter((u) => u.includes(NAME_32))).toEqual([]);
  });

  it('a device the frame has no build for is refused before anything is written, without a download', async () => {
    const tablet = new FakeAndroid({ abilist: 'x86_64,x86' });
    const { s, asked } = await run(tablet);
    expect(s.state.screen).toBe('error');
    expect(s.state.error).toBe('This tablet runs x86_64, x86, and Glassworks for Android is built for arm64-v8a and armeabi-v7a only. Nothing was changed.');
    expect(asked.filter((u) => u.includes(NAME_32))).toEqual([]);
    expect(tablet.cmds.filter((c) => !c.startsWith('getprop'))).toEqual([]);
  });

  it('a release with no build for this device\'s ABI: an error with Try again, nothing written', async () => {
    const tablet = new FakeAndroid(SAMSUNG_32BIT);
    const { s } = await run(tablet, { 'arm64-v8a': APK });
    expect(s.state.screen).toBe('error');
    expect(s.state.error).toBe('Glassworks for Android 0.2.0 has no armeabi-v7a build, which this tablet needs. Nothing was changed.');
    expect(tablet.cmds.filter((c) => !c.startsWith('getprop'))).toEqual([]);
  });

  it('the 32-bit build comes from the release the first screen used, even when a newer one lands in between', async () => {
    const r = fakeReleaseRoute('out', BUILDS, async () => {}, 'generic-android');
    const asked: string[] = [];
    let latest = 0;
    const route = (async (u: RequestInfo | URL) => {
      asked.push(String(u));
      // after the first look, a newer release with no 32-bit build: never consulted
      if (String(u).endsWith('/latest') && latest++) return Response.json({ release: { tag: 'generic-android-v0.3.0', version: '0.3.0', assets: ['glassworks-generic-android-arm64-v8a-0.3.0.apk'] } });
      return r(u);
    }) as typeof fetch;
    const s = new AndroidSession({ findUsb: async () => ({ vendorId: 0x04e8 }), connect: async () => new FakeAndroid(SAMSUNG_32BIT), release: route, sleep: async () => {}, setupTries: 1 }, true);
    await s.loadRelease();
    await s.find();
    s.toSteps();
    await s.connect();
    expect(s.state.error).toBeUndefined();
    expect(s.state.plan?.abi).toBe('armeabi-v7a');
    expect(s.state.release).toMatchObject({ state: 'ready', tag: TAG, name: NAME_32 });
    expect(asked.filter((u) => u.endsWith('/latest'))).toHaveLength(1);
  });

  it('a device that stops answering while Kiln reads its ABIs: an error with Try again, not a wait forever', async () => {
    class Silent extends FakeAndroid {
      override async run(cmd: string, signal?: AbortSignal) {
        if (cmd === 'getprop ro.product.cpu.abilist') return new Promise<never>((_, reject) => signal?.addEventListener('abort', () => reject(signal.reason)));
        return super.run(cmd);
      }
    }
    const tablet = new Silent(SAMSUNG_32BIT);
    const s = new AndroidSession({
      findUsb: async () => ({ vendorId: 0x04e8 }), connect: async () => tablet, release: fakeReleaseRoute('out', BUILDS, async () => {}, 'generic-android'), readMs: 20,
    }, true);
    await s.loadRelease();
    await s.find();
    s.toSteps();
    await s.connect();
    expect(s.state.screen).toBe('error');
    expect(s.state.error).toBe('The tablet didn\'t answer `getprop ro.product.cpu.abilist` in 0 s');
    expect(tablet.cmds.filter((c) => !c.startsWith('getprop'))).toEqual([]);
  });

  it('?demo=done-32bit: the release flow on that tablet, through to Done', async () => {
    const s = await androidDemo('done-32bit', true);
    expect(s.state.screen).toBe('done');
    expect(s.state.plan?.abi).toBe('armeabi-v7a');
    render(<Screens session={s} />);
    expect(document.querySelector('h2')?.textContent).toBe('You\'re all set');
    expect(s.state.log.join('\n')).toMatch(/armeabi-v7a build/);
  });
});

describe('the page', () => {
  it('?apk=release: the release, checked, with no file picker', async () => {
    vi.stubGlobal('fetch', route('out'));
    history.replaceState(null, '', '/android?apk=release');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.queryByText('Test only')).toBeNull(); // stable (frame.json): open to everyone, not a test page
    await screen.findByText('Glassworks 0.2.0');
    expect(screen.getByText(/Download verified/)).toBeTruthy();
    expect(screen.getByText(/^Needs Android 8 or newer\./)).toBeTruthy(); // the release is the generic build, not the legacy one for 6 and 7
    expect(screen.getByRole('link', { name: NAME }).getAttribute('href')).toBe(`https://github.com/GlassworksHQ/glassworks/releases/tag/${TAG}`);
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(embers()).toEqual(['Find device']);
  });

  it('?apk=release with no release: a clear error, not the local picker', async () => {
    vi.stubGlobal('fetch', route('none'));
    history.replaceState(null, '', '/android?apk=release');
    render(<AndroidWizard />);
    await screen.findByText('No compatible Glassworks for Android release yet');
    expect(screen.getByRole('alert').textContent).toMatch(/No compatible release package for this device is available on GitHub yet\. Nothing was written to the tablet/);
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
    expect(embers()).toEqual(['Find device']);
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
