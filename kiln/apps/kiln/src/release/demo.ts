// Development, preview and tests only: one line's releases (Kiln's /api/release/<line>/latest) and its files on Refract
// (<refract>/glassworks/<tag>/<name>, with Range as Refract answers it), answered in the page with a stand-in release,
// so the ?apk=release screens can be seen without a real one.
//   out: <line>-v0.2.0 with its APKs, GitHub's SHA-256 of each, and .sha256   none: no release yet
//   bad: a SHA-256 (GitHub's and the .sha256) that isn't the APK's            stuck: the APK download stops part way
//   undigested: as out, but GitHub's list gives no SHA-256 of its own (only the .sha256 beside the file on Refract)

export type FakeRelease = 'out' | 'none' | 'bad' | 'stuck' | 'undigested';

/** The stand-in release of `line`: its tag and its arm64-v8a APK. */
export const demoRelease = (line: string) => ({ tag: `${line}-v0.2.0`, apk: `glassworks-${line}-arm64-v8a-0.2.0.apk` });

const sha256 = async (b: Uint8Array) =>
  [...new Uint8Array(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>))].map((x) => x.toString(16).padStart(2, '0')).join('');

/**
 * `apk`: the release's APK for every ABI, or one per ABI (the others then aren't in the release's files). Its APK for
 * an ABI is named as `demoRelease`'s arm64-v8a one, with that ABI.
 */
export function fakeReleaseRoute(kind: FakeRelease, apk: Uint8Array | Readonly<Record<string, Uint8Array>>, tick: (ms: number) => Promise<void>, line: string): typeof fetch {
  const { tag: DEMO_TAG, apk: DEMO_APK } = demoRelease(line);
  const byAbi: Record<string, Uint8Array> = ArrayBuffer.isView(apk) ? { 'arm64-v8a': apk as Uint8Array, 'armeabi-v7a': apk as Uint8Array } : { ...(apk as Readonly<Record<string, Uint8Array>>) };
  const named = Object.fromEntries(Object.entries(byAbi).map(([abi, bytes]) => [DEMO_APK.replace('arm64-v8a', abi), bytes]));
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'https://kiln.invalid');
    if (url.pathname === `/api/release/${line}/latest`) {
      const assets = Object.keys(named);
      // GitHub's own SHA-256 of each file, as the real route lists it (its `digest`): what Kiln checks the APK against
      const digests = Object.fromEntries(await Promise.all(Object.entries(named).map(async ([n, b]) => [n, kind === 'bad' ? 'ab'.repeat(32) : await sha256(b)] as const)));
      return Response.json({ release: kind === 'none' ? null : { tag: DEMO_TAG, version: '0.2.0', assets, ...(kind === 'undigested' ? {} : { digests }) } });
    }
    const [, prefix, tag, name, ...more] = url.pathname.split('/').map(decodeURIComponent);
    if (prefix !== 'glassworks' || tag !== DEMO_TAG || !name || more.length) return new Response('not on the allowlist', { status: 404 });
    const sum = name.endsWith('.sha256') ? name.slice(0, -'.sha256'.length) : undefined;
    if (sum && named[sum]) return new Response(`${kind === 'bad' ? 'ab'.repeat(32) : await sha256(named[sum])}  ${sum}\n`);
    const bytes = named[name];
    if (!bytes) return new Response('not on the allowlist', { status: 404 });
    const from = Number(/^bytes=(\d+)-$/.exec(new Headers(init?.headers).get('range') ?? '')?.[1] ?? 0);
    const step = Math.ceil(bytes.length / 8);
    let at = from;
    const body = new ReadableStream<Uint8Array>({
      async pull(c) {
        if (kind === 'stuck' && at >= step * 3) return new Promise(() => {});
        if (at >= bytes.length) { c.close(); return; }
        await tick(150);
        c.enqueue(bytes.slice(at, Math.min(at + step, bytes.length)));
        at += step;
      },
    });
    const size = String(bytes.length - from);
    return from
      ? new Response(body, { status: 206, headers: { 'content-length': size, 'content-range': `bytes ${from}-${bytes.length - 1}/${bytes.length}` } })
      : new Response(body, { headers: { 'content-length': size } });
  }) as typeof fetch;
}
