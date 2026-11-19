// Development, preview and tests only: Kiln's release route for the Mirror's line (/api/release/generic-android-legacy),
// answered in the page with a stand-in release, so the ?apk=release screens can be seen without a real one.
//   out: generic-android-legacy-v0.2.0 with its APK and .sha256   none: no release yet
//   bad: a .sha256 that isn't the APK's                          stuck: the APK download stops part way
export type FakeRelease = 'out' | 'none' | 'bad' | 'stuck';

export const DEMO_TAG = 'generic-android-legacy-v0.2.0';
export const DEMO_APK = 'glassworks-generic-android-legacy-arm64-v8a-0.2.0.apk';

const sha256 = async (b: Uint8Array) =>
  [...new Uint8Array(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>))].map((x) => x.toString(16).padStart(2, '0')).join('');

export function fakeReleaseRoute(kind: FakeRelease, apk: Uint8Array, tick: (ms: number) => Promise<void> = async () => {}): typeof fetch {
  return (async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'https://kiln.invalid');
    if (url.pathname === '/api/release/generic-android-legacy/latest') {
      const assets = [DEMO_APK, DEMO_APK.replace('arm64-v8a', 'armeabi-v7a')];
      return Response.json({ release: kind === 'none' ? null : { tag: DEMO_TAG, version: '0.2.0', assets } });
    }
    const name = url.searchParams.get('name');
    if (url.pathname !== '/api/release/generic-android-legacy' || url.searchParams.get('tag') !== DEMO_TAG) return new Response('not a release asset of this frame', { status: 400 });
    if (name === `${DEMO_APK}.sha256`) return new Response(`${kind === 'bad' ? 'ab'.repeat(32) : await sha256(apk)}  ${DEMO_APK}\n`);
    if (name !== DEMO_APK) return new Response('not a release asset of this frame', { status: 400 });
    const step = Math.ceil(apk.length / 8);
    let at = 0;
    const body = new ReadableStream<Uint8Array>({
      async pull(c) {
        if (kind === 'stuck' && at >= step * 3) return new Promise(() => {});
        if (at >= apk.length) { c.close(); return; }
        await tick(150);
        c.enqueue(apk.slice(at, at + step));
        at += step;
      },
    });
    return new Response(body, { headers: { 'content-length': String(apk.length) } });
  }) as typeof fetch;
}
