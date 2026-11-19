// /api/release/<frame>: only a known frame's own release files pass, refused before GitHub is asked,
// and the body streams through. /api/release-asset, the S1's old address, redirects to it.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GET } from '../src/app/api/release/[frame]/route';
import { GET as oldGET } from '../src/app/api/release-asset/route';

const get = (frame: string, q: string) =>
  GET(new Request(`https://kiln.example/api/release/${frame}?${q}`), { params: Promise.resolve({ frame }) });
const apk = () => new Response(new Uint8Array([0x50, 0x4b, 3, 4]), { headers: { 'content-length': '4' } });
const refusedBefore = async (r: Promise<Response>, status: number) => {
  const fetch = vi.fn(apk);
  vi.stubGlobal('fetch', fetch);
  expect((await r).status).toBe(status);
  expect(fetch).not.toHaveBeenCalled();
};
afterEach(() => vi.unstubAllGlobals());

describe('the release route', () => {
  it.each([
    ['v0.4.3', 's1-kiosk-0.4.3.apk'],
    ['aqara-s1-plus-v0.4.3', 's1-kiosk-0.4.3.apk'],
    ['v1.12.0', 's1-kiosk-1.12.0.apk'],
  ])('streams the S1\'s %s %s from this repo, following GitHub\'s redirect', async (tag, name) => {
    const fetch = vi.fn(apk);
    vi.stubGlobal('fetch', fetch);
    const r = await get('aqara-s1-plus', `tag=${tag}&name=${name}`);
    expect(fetch).toHaveBeenCalledExactlyOnceWith(`https://github.com/GlassworksHQ/glassworks/releases/download/${tag}/${name}`, { redirect: 'follow' });
    expect(r.status).toBe(200);
    expect(Object.fromEntries(r.headers)).toEqual({
      'content-type': 'application/vnd.android.package-archive',
      'cache-control': 'public, max-age=31536000, immutable',
      'content-length': '4',
    });
    expect(new Uint8Array(await r.arrayBuffer())).toEqual(new Uint8Array([0x50, 0x4b, 3, 4]));
  });

  it.each([
    ['generic-android-legacy', 'generic-android-legacy-v0.2.0', 'glassworks-generic-android-legacy-arm64-v8a-0.2.0.apk'],
    ['generic-android-legacy', 'generic-android-legacy-v0.2.0', 'glassworks-generic-android-legacy-armeabi-v7a-0.2.0.apk'],
    ['aqara-s1-plus', 'v0.4.3', 's1-kiosk-0.4.3.apk'],
  ])('streams the .sha256 beside %s\'s %s %s, as text', async (frame, tag, apkName) => {
    const fetch = vi.fn(async () => new Response(`${'ab'.repeat(32)}  ${apkName}\n`));
    vi.stubGlobal('fetch', fetch);
    const r = await get(frame, `tag=${tag}&name=${apkName}.sha256`);
    expect(fetch).toHaveBeenCalledExactlyOnceWith(`https://github.com/GlassworksHQ/glassworks/releases/download/${tag}/${apkName}.sha256`, { redirect: 'follow' });
    expect(r.status).toBe(200);
    expect(r.headers.get('content-type')).toBe('text/plain; charset=utf-8');
    expect(await r.text()).toBe(`${'ab'.repeat(32)}  ${apkName}\n`);
  });

  it.each([
    ['the S1\'s tag', 'tag=v0.2.0&name=glassworks-generic-android-legacy-arm64-v8a-0.2.0.apk.sha256'],
    ['generic Android\'s tag', 'tag=generic-android-v0.2.0&name=glassworks-generic-android-legacy-arm64-v8a-0.2.0.apk.sha256'],
    ['another version\'s .sha256', 'tag=generic-android-legacy-v0.2.0&name=glassworks-generic-android-legacy-arm64-v8a-0.2.1.apk.sha256'],
    ['an ABI the line doesn\'t build', 'tag=generic-android-legacy-v0.2.0&name=glassworks-generic-android-legacy-x86_64-0.2.0.apk.sha256'],
  ])('refuses the Mirror\'s line %s with 400', (_, q) => refusedBefore(get('generic-android-legacy', q), 400));

  it('says so when GitHub fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('no', { status: 404 })));
    expect((await get('aqara-s1-plus', 'tag=v0.4.3&name=s1-kiosk-0.4.3.apk')).status).toBe(502);
  });

  it.each([
    'lululemon-mirror',
    'AQARA-S1-PLUS',
    'aqara-s1-plus ',
    '..',
    '__proto__',
    'constructor',
    '',
  ])('refuses the frame %j with 404', (frame) => refusedBefore(get(frame, 'tag=v0.4.3&name=s1-kiosk-0.4.3.apk'), 404));

  it.each([
    ['another file of the release', 'tag=v0.4.3&name=SHA256SUMS'],
    ['a .sha256 of no APK the line serves', 'tag=v0.4.3&name=s1-kiosk-0.4.4.apk.sha256'],
    ['a .sha256 beside no APK', 'tag=v0.4.3&name=s1-kiosk-0.4.3.sha256'],
    ['another ABI', 'tag=v0.4.3&name=s1-kiosk-arm64-v8a-0.4.3.apk'],
    ['any name', 'tag=v0.4.3&name=evil.apk'],
    ['another version\'s APK', 'tag=v0.4.3&name=s1-kiosk-0.4.4.apk'],
    ['another frame\'s tag', 'tag=generic-android-v0.4.3&name=s1-kiosk-0.4.3.apk'],
    ['a branch', 'tag=main&name=s1-kiosk-0.4.3.apk'],
    ['a pre-release', 'tag=v0.4.3-rc1&name=s1-kiosk-0.4.3-rc1.apk'],
    ['no tag or name', ''],
  ])('refuses %s with 400', (_, q) => refusedBefore(get('aqara-s1-plus', q), 400));

  it.each([
    ['in the tag', 'tag=v0.4.3/../x&name=s1-kiosk-0.4.3.apk'],
    ['in the tag, encoded', 'tag=v0.4.3%2F..%2Fx&name=s1-kiosk-0.4.3.apk'],
    ['in the name', 'tag=v0.4.3&name=../s1-kiosk-0.4.3.apk'],
    ['in the name, after it', 'tag=v0.4.3&name=s1-kiosk-0.4.3.apk/../../evil.apk'],
  ])('refuses a path trick %s with 400', (_, q) => refusedBefore(get('aqara-s1-plus', q), 400));

  it.each([
    ['through the tag', 'tag=../../../../evil/repo/releases/download/v0.4.3&name=s1-kiosk-0.4.3.apk'],
    ['through the name', 'tag=v0.4.3&name=../../../../../evil/repo/releases/download/v0.4.3/s1-kiosk-0.4.3.apk'],
    ['as a full URL in the name', 'tag=v0.4.3&name=https://github.com/evil/repo/releases/download/v0.4.3/s1-kiosk-0.4.3.apk'],
  ])('refuses another repo %s with 400', (_, q) => refusedBefore(get('aqara-s1-plus', q), 400));

  it.each([
    'repo=evil/repo',
    'owner=evil&repo=repo',
    'repository=evil/repo',
  ])('asks this repo only, whatever %s says', async (extra) => {
    const fetch = vi.fn(apk);
    vi.stubGlobal('fetch', fetch);
    const r = await get('aqara-s1-plus', `${extra}&tag=v0.4.3&name=s1-kiosk-0.4.3.apk`);
    expect(fetch).toHaveBeenCalledExactlyOnceWith('https://github.com/GlassworksHQ/glassworks/releases/download/v0.4.3/s1-kiosk-0.4.3.apk', { redirect: 'follow' });
    expect(r.status).toBe(200);
  });
});

describe('the S1\'s old release route', () => {
  it('redirects to the S1\'s release route with the same query', () => {
    const r = oldGET(new Request('https://kiln.example/api/release-asset?tag=aqara-s1-plus-v0.4.3&name=s1-kiosk-0.4.3.apk'));
    expect(r.status).toBe(308);
    expect(r.headers.get('location')).toBe('https://kiln.example/api/release/aqara-s1-plus?tag=aqara-s1-plus-v0.4.3&name=s1-kiosk-0.4.3.apk');
  });
});
