// A wizard frame's app comes from its newest release when someone installs (installer.json `apk`, issue 848), never
// from a file Kiln's deploy staged; a file staged under the app's name (a local `npm run stage`) still wins.
import { describe, expect, it, vi } from 'vitest';
import { Assets, REFRACT, releaseFileUrl, toHex } from '@glassworks/kiln-core';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { frameAssets } from '../src/wizard/assets';
import { frame as s1 } from './s1-frame';

const APK = Uint8Array.of(7, 7, 7);
const STAGED = Uint8Array.of(1, 2);
const sum = async (b: Uint8Array) => toHex(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>));

/** Kiln's files: a manifest of `staged`, the line's release `v1.0.0` (or none), and its APK and .sha256 on Refract. */
function site(o: { staged?: Record<string, Uint8Array>; release?: 'out' | 'none' | 'down' | 'bad' } = {}) {
  const staged = o.staged ?? { uboot: STAGED };
  const apk = 's1-kiosk-1.0.0.apk';
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
      return Response.json({ release: o.release === 'none' ? null : { tag: 'v1.0.0', version: '1.0.0', assets: [apk] } });
    }
    if (url === releaseFileUrl(REFRACT, 'v1.0.0', `${apk}.sha256`)) return new Response(`${o.release === 'bad' ? 'ab'.repeat(32) : await sum(APK)}  ${apk}\n`);
    if (url === releaseFileUrl(REFRACT, 'v1.0.0', apk)) return new Response(APK as Uint8Array<ArrayBuffer>, { headers: { 'content-length': String(APK.length) } });
    return new Response('not found', { status: 404 });
  }) as typeof globalThis.fetch;
  const assets = frameAssets(s1, new Assets<string>('/assets/', fetch), fetch, { stallMs: 1000, sleep: async () => {} });
  return { assets, urls: () => [...calls] };
}

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

  it('not staged (kiln.glassworks.dev): the release\'s APK through Refract, checked against its .sha256, with progress', async () => {
    const { assets, urls } = site();
    expect(await assets.hasAsset('kiosk-apk')).toBe(true);
    const progress = vi.fn();
    expect(await assets.asset('kiosk-apk', progress)).toEqual(APK);
    expect(progress).toHaveBeenLastCalledWith(APK.length, APK.length);
    expect(urls()).toContain(releaseFileUrl(REFRACT, 'v1.0.0', 's1-kiosk-1.0.0.apk'));
    // once is enough for one session: a second ask doesn't download it again
    const asked = urls().length;
    expect(await assets.asset('kiosk-apk')).toEqual(APK);
    expect(urls().length).toBe(asked);
  });

  it('staged (a local npm run stage): that file, and no release is asked for', async () => {
    const { assets, urls } = site({ staged: { 'kiosk-apk': STAGED } });
    expect(await assets.asset('kiosk-apk')).toEqual(STAGED);
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
    ['bad', /^The downloaded file didn't match the published release/],
  ] as const)('a release that is %s: an error that says so, and Try again asks again', async (release, says) => {
    const { assets, urls } = site({ release });
    await expect(assets.asset('kiosk-apk')).rejects.toThrow(says);
    const asked = urls().filter((u) => u.startsWith('/api/release')).length;
    await expect(assets.asset('kiosk-apk')).rejects.toThrow(says);
    expect(urls().filter((u) => u.startsWith('/api/release')).length).toBe(asked + 1);
  });
});
