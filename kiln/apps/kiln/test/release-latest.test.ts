// /api/release/<line>/latest: a line's newest published release with the files Kiln serves, or null before its
// first; refused before GitHub is asked for a line Kiln doesn't serve.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GET as getAsset } from '../src/app/api/release/[frame]/route';
import { GET, latestOf } from '../src/app/api/release/[frame]/latest/route';
import { RELEASES } from '../src/app/api/release/lines';

const latest = (frame: string) => GET(new Request(`https://kiln.example/api/release/${frame}/latest`), { params: Promise.resolve({ frame }) });
const r = (tag: string, assets: string[], o: { draft?: boolean; prerelease?: boolean } = {}) => ({ tag_name: tag, draft: false, prerelease: false, ...o, assets: assets.map((name) => ({ name })) });
const apks = (stem: string, v: string) => [`glassworks-${stem}-arm64-v8a-${v}.apk`, `glassworks-${stem}-armeabi-v7a-${v}.apk`];
const GENERIC = RELEASES.get('generic-android')!;
afterEach(() => vi.unstubAllGlobals());

describe('the latest release of a line', () => {
  it('the newest by version, not by list order, published only', () => {
    expect(latestOf(GENERIC, [
      r('generic-android-v1.2.0', apks('generic-android', '1.2.0')),
      r('generic-android-v1.10.0', apks('generic-android', '1.10.0')),
      r('generic-android-v2.0.0', apks('generic-android', '2.0.0'), { draft: true }),
      r('generic-android-v1.11.0', apks('generic-android', '1.11.0'), { prerelease: true }),
      r('generic-android-legacy-v3.0.0', apks('generic-android-legacy', '3.0.0')),
      r('v0.4.3', ['s1-kiosk-0.4.3.apk']),
    ])).toEqual({ tag: 'generic-android-v1.10.0', version: '1.10.0', assets: apks('generic-android', '1.10.0') });
  });

  it('only the files the line serves, and none is no release', () => {
    expect(latestOf(GENERIC, [r('generic-android-v1.0.0', [...apks('generic-android', '1.0.0'), 'glassworks-generic-android-arm64-v8a-1.0.0.apk.sha256'])])?.assets)
      .toEqual(apks('generic-android', '1.0.0'));
    expect(latestOf(GENERIC, [r('generic-android-v1.0.0', ['notes.txt'])])).toBeNull();
    expect(latestOf(GENERIC, [])).toBeNull();
  });

  it('answers null before the first release, cached at the CDN', async () => {
    const fetch = vi.fn(async () => Response.json([r('v0.4.3', ['s1-kiosk-0.4.3.apk'])]));
    vi.stubGlobal('fetch', fetch);
    const res = await latest('generic-android');
    expect(await res.json()).toEqual({ release: null });
    expect(res.headers.get('cache-control')).toBe('public, s-maxage=600, stale-while-revalidate=3600');
    expect(fetch).toHaveBeenCalledExactlyOnceWith('https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=100&page=1', expect.anything());
  });

  it('reads further pages until one holds a release of the line, and no further', async () => {
    const others = Array.from({ length: 100 }, (_, i) => r(`v0.${i}.0`, [`s1-kiosk-0.${i}.0.apk`]));
    const pages = [others, [r('generic-android-v1.0.0', apks('generic-android', '1.0.0')), ...others.slice(1)], others];
    const fetch = vi.fn(async (url: string) => Response.json(pages[Number(new URL(url).searchParams.get('page')) - 1]));
    vi.stubGlobal('fetch', fetch);
    expect((await (await latest('generic-android')).json()).release?.tag).toBe('generic-android-v1.0.0');
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('stops at a short page', async () => {
    const fetch = vi.fn(async () => Response.json([r('v0.4.3', ['s1-kiosk-0.4.3.apk'])]));
    vi.stubGlobal('fetch', fetch);
    expect(await (await latest('generic-android')).json()).toEqual({ release: null });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('says so when GitHub fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('rate limited', { status: 403 })));
    expect((await latest('generic-android')).status).toBe(502);
  });

  it.each(['lululemon-mirror', '__proto__', ''])('refuses %j with 404 before GitHub is asked', async (frame) => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    expect((await latest(frame)).status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('the release route serves the generic lines', () => {
  const asset = (frame: string, q: string) => getAsset(new Request(`https://kiln.example/api/release/${frame}?${q}`), { params: Promise.resolve({ frame }) });

  it.each([
    ['generic-android', 'generic-android-v1.0.0', 'glassworks-generic-android-arm64-v8a-1.0.0.apk'],
    ['generic-android', 'generic-android-v1.0.0', 'glassworks-generic-android-armeabi-v7a-1.0.0.apk'],
    ['generic-android-legacy', 'generic-android-legacy-v1.0.0', 'glassworks-generic-android-legacy-arm64-v8a-1.0.0.apk'],
  ])('%s: %s %s', async (frame, tag, name) => {
    const fetch = vi.fn(async () => new Response(new Uint8Array([0x50, 0x4b])));
    vi.stubGlobal('fetch', fetch);
    expect((await asset(frame, `tag=${tag}&name=${name}`)).status).toBe(200);
    expect(fetch).toHaveBeenCalledExactlyOnceWith(`https://github.com/GlassworksHQ/glassworks/releases/download/${tag}/${name}`, { redirect: 'follow' });
  });

  it.each([
    ['generic-android', 'tag=generic-android-legacy-v1.0.0&name=glassworks-generic-android-legacy-arm64-v8a-1.0.0.apk'],
    ['generic-android', 'tag=generic-android-v1.0.0&name=glassworks-generic-android-x86_64-1.0.0.apk'],
    ['generic-android', 'tag=v0.4.3&name=s1-kiosk-0.4.3.apk'],
    ['generic-android-legacy', 'tag=generic-android-v1.0.0&name=glassworks-generic-android-arm64-v8a-1.0.0.apk'],
  ])('%s refuses another line\'s or ABI\'s file: %s', async (frame, q) => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    expect((await asset(frame, q)).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });
});
