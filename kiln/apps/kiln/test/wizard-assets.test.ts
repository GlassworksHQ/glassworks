// A wizard frame's app comes from its newest release when someone installs (installer.json `apk`, issue 848), never
// from a file Kiln's deploy staged; a file staged under the app's name (a local `npm run stage`) still wins. It is
// checked against GitHub's own SHA-256 only, and the tab keeps which release the install took, for a set-up after a
// reload.
import { describe, expect, it, vi } from 'vitest';
import { Assets, REFRACT, releaseFileUrl, toHex } from '@glassworks/kiln-core';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { frameAssets, pinKey } from '../src/wizard/assets';
import { frame as s1 } from './s1-frame';

const APK = Uint8Array.of(7, 7, 7);
const NEWER = Uint8Array.of(8, 8, 8, 8);
const STAGED = Uint8Array.of(1, 2);
const sum = async (b: Uint8Array) => toHex(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>));

/** A tab's sessionStorage. */
const tab = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); } }; };

interface Site {
  staged?: Record<string, Uint8Array>;
  /** what the release list answers: v1.0.0, v1.1.0 (NEWER), none yet, or a 502 */
  release?: 'out' | 'newer' | 'none' | 'down';
  /** GitHub's digest for the APK: its real one (default), another file's, or none */
  digest?: 'real' | 'wrong' | 'none';
}

/** Kiln's files: a manifest of `staged`, the line's newest release, and its APKs and .sha256 on Refract. */
function site(o: Site = {}, store: Pick<Storage, 'getItem' | 'setItem'> = tab()) {
  const staged = o.staged ?? { uboot: STAGED };
  const files: Record<string, { tag: string; bytes: Uint8Array }> = {
    's1-kiosk-1.0.0.apk': { tag: 'v1.0.0', bytes: APK },
    's1-kiosk-1.1.0.apk': { tag: 'v1.1.0', bytes: NEWER },
  };
  const calls: string[] = [];
  const fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    if (url === '/assets/manifest.json') {
      const assets = await Promise.all(Object.entries(staged).map(async ([name, b]) => ({ name, path: `blobs/${name}`, size: b.length, sha256: await sum(b), license: 'MIT', source: 'test' })));
      return Response.json({ generated: '', assets });
    }
    const blob = /^\/assets\/blobs\/(.+)$/.exec(url)?.[1];
    if (blob) return new Response(staged[blob] as Uint8Array<ArrayBuffer>);
    if (url === `/api/release/${s1.apk!.line}/latest`) {
      if (o.release === 'down') return new Response('GitHub answered 403', { status: 502 });
      if (o.release === 'none') return Response.json({ release: null });
      const [version, name] = o.release === 'newer' ? ['1.1.0', 's1-kiosk-1.1.0.apk'] : ['1.0.0', 's1-kiosk-1.0.0.apk'];
      const digest = o.digest === 'none' ? undefined : o.digest === 'wrong' ? 'cd'.repeat(32) : await sum(files[name]!.bytes);
      return Response.json({ release: { tag: `v${version}`, version, assets: [name], ...(digest ? { digests: { [name]: digest } } : {}) } });
    }
    for (const [name, f] of Object.entries(files)) {
      if (url === releaseFileUrl(REFRACT, f.tag, `${name}.sha256`)) return new Response(`${await sum(f.bytes)}  ${name}\n`);
      if (url === releaseFileUrl(REFRACT, f.tag, name)) return new Response(f.bytes as Uint8Array<ArrayBuffer>, { headers: { 'content-length': String(f.bytes.length) } });
    }
    return new Response('not found', { status: 404 });
  }) as typeof globalThis.fetch;
  const assets = frameAssets(s1, new Assets<string>('/assets/', fetch), fetch, { stallMs: 1000, sleep: async () => {} }, store);
  return { assets, store, urls: () => [...calls] };
}

const pin = (store: Pick<Storage, 'getItem'>) => JSON.parse(store.getItem(pinKey('aqara-s1-plus')) ?? 'null') as { tag: string } | null;

describe('the app from its release', () => {
  it('the S1 declares its app as the newest release of its own line', () => {
    expect(s1.apk).toEqual({ line: 'aqara-s1-plus', asset: 'kiosk-apk' });
  });

  it('every frame with an `apk`: a line it releases, and an asset one of its flows downloads', () => {
    for (const [id, i] of Object.entries(INSTALLERS)) {
      const apk = (i as { apk?: { line: string; asset: string } }).apk;
      if (!apk) continue;
      expect(i.releases?.map((r) => r.line), id).toContain(apk.line);
      const downloads = Object.values((i as unknown as { flows: Record<string, { steps: { downloads?: string[] }[] }> }).flows)
        .flatMap((f) => f.steps.flatMap((s) => s.downloads ?? []));
      expect(downloads, id).toContain(apk.asset);
    }
  });

  it('not staged (kiln.glassworks.dev): the release\'s APK through Refract, checked against GitHub\'s digest, with progress', async () => {
    const { assets, urls } = site();
    expect(await assets.hasAsset('kiosk-apk')).toBe(true);
    const progress = vi.fn();
    expect(await assets.asset('kiosk-apk', progress)).toEqual(APK);
    expect(progress).toHaveBeenLastCalledWith(APK.length, APK.length);
    expect(urls()).toContain(releaseFileUrl(REFRACT, 'v1.0.0', 's1-kiosk-1.0.0.apk'));
    // Refract carries the bytes only: its .sha256 is never asked for
    expect(urls().some((u) => u.endsWith('.sha256'))).toBe(false);
    // once is enough for one session: a second ask doesn't download it again
    const asked = urls().length;
    expect(await assets.asset('kiosk-apk')).toEqual(APK);
    expect(urls().length).toBe(asked);
  });

  it('a digest that isn\'t the file\'s: refused, even though Refract\'s .sha256 would match', async () => {
    const { assets } = site({ digest: 'wrong' });
    await expect(assets.asset('kiosk-apk')).rejects.toThrow(/^The downloaded file didn't match the published release/);
  });

  it('no digest from GitHub: refused before the download, never Refract\'s .sha256 instead', async () => {
    const { assets, urls } = site({ digest: 'none' });
    await expect(assets.asset('kiosk-apk')).rejects.toThrow(/^Couldn't verify the release checksum for 1\.0\.0/);
    expect(urls().some((u) => u.includes('s1-kiosk'))).toBe(false);
  });

  it('fetchAhead: the same download the set-up uses, once', async () => {
    const { assets, urls } = site();
    expect(await assets.fetchAhead!()).toEqual(APK);
    const asked = urls().length;
    expect(await assets.asset('kiosk-apk')).toEqual(APK);
    expect(urls().length).toBe(asked);
  });

  describe('a reload between the install and the set-up', () => {
    it('installs the release the install took, without a new look-up, even once a newer one is out', async () => {
      const before = site();
      await before.assets.fetchAhead!();
      expect(pin(before.store)).toMatchObject({ tag: 'v1.0.0', version: '1.0.0' });
      // the page reloads: a new page, the same tab; the release list now names v1.1.0
      const after = site({ release: 'newer' }, before.store);
      expect(await after.assets.asset('kiosk-apk')).toEqual(APK);
      expect(after.urls().some((u) => u.startsWith('/api/release'))).toBe(false);
    });

    it('and while the release list is down', async () => {
      const before = site();
      await before.assets.fetchAhead!();
      const after = site({ release: 'down' }, before.store);
      expect(await after.assets.asset('kiosk-apk')).toEqual(APK);
    });

    it('a new install in the tab looks up the newest again', async () => {
      const before = site();
      await before.assets.fetchAhead!();
      const again = site({ release: 'newer' }, before.store);
      expect(await again.assets.fetchAhead!()).toEqual(NEWER);
      expect(pin(again.store)).toMatchObject({ tag: 'v1.1.0' });
    });

    it('a tab with nothing kept (or storage blocked) looks it up', async () => {
      const blocked = { getItem: (): string | null => { throw new Error('SecurityError'); }, setItem: () => { throw new Error('SecurityError'); } };
      expect(await site({}, blocked).assets.fetchAhead!()).toEqual(APK);
      expect(await site({ release: 'newer' }, blocked).assets.asset('kiosk-apk')).toEqual(NEWER);
    });
  });

  it('staged (a local npm run stage): that file, and no release is asked for', async () => {
    const { assets, urls } = site({ staged: { 'kiosk-apk': STAGED } });
    expect(await assets.asset('kiosk-apk')).toEqual(STAGED);
    expect(await assets.fetchAhead!()).toEqual(STAGED);
    expect(urls().some((u) => u.startsWith('/api/release'))).toBe(false);
  });

  it('every other file comes from the staged files, as before', async () => {
    const { assets, urls } = site();
    expect(await assets.asset('uboot')).toEqual(STAGED);
    expect(await assets.hasAsset('keyboard-apk')).toBe(false);
    expect(urls().some((u) => u.startsWith('/api/release'))).toBe(false);
  });

  it.each([
    ['none', /^There's no Glassworks release to install yet$/],
    ['down', /^Couldn't check for releases: the release server answered 502$/],
  ] as const)('a release that is %s: an error that says so, and Try again asks again', async (release, says) => {
    const { assets, urls } = site({ release });
    await expect(assets.fetchAhead!()).rejects.toThrow(says);
    const asked = urls().filter((u) => u.startsWith('/api/release')).length;
    await expect(assets.fetchAhead!()).rejects.toThrow(says);
    expect(urls().filter((u) => u.startsWith('/api/release')).length).toBe(asked + 1);
  });
});
