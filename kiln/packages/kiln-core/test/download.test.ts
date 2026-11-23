// downloadWhole and resumableBody without a pinned size (a release APK on Refract): the first answer's length is the
// size, a dropped connection resumes with Range against it, and a 429 is waited out. The pinned-size cases are tested
// with the Mirror's image (frames/lululemon-mirror/installer/test/download.test.ts).
import { describe, expect, it } from 'vitest';
import { ProxyError, REFRACT, downloadWhole, releaseFileUrl } from '../src';

const FILE = Uint8Array.from({ length: 50_000 }, (_, i) => (i * 13) % 256);

/** A Refract stand-in: `drop` cuts the first answer after that many bytes; `first` replaces the first answer. */
function refract(o: { drop?: number; first?: Response; total?: number } = {}) {
  const ranges: (string | null)[] = [];
  const f = (async (_u: RequestInfo | URL, init?: RequestInit) => {
    const range = new Headers(init?.headers).get('range');
    ranges.push(range);
    if (ranges.length === 1 && o.first) return o.first;
    const from = range ? Number(/^bytes=(\d+)-$/.exec(range)![1]) : 0;
    const rest = FILE.subarray(from);
    const cut = ranges.length === 1 ? o.drop : undefined;
    let sent = false;
    const stream = new ReadableStream<Uint8Array>({
      pull(c) {
        if (!sent) { c.enqueue(rest.slice(0, cut ?? rest.length)); sent = true; return; }
        if (cut === undefined) c.close(); else c.error(new TypeError('network error'));
      },
    });
    const total = o.total ?? FILE.length;
    return range
      ? new Response(stream, { status: 206, headers: { 'content-range': `bytes ${from}-${FILE.length - 1}/${total}`, 'content-length': String(rest.length) } })
      : new Response(stream, { headers: { 'content-length': String(FILE.length) } });
  }) as typeof fetch;
  return { f, ranges };
}

describe('downloadWhole', () => {
  it('takes the size from the first answer, and reports progress against it', async () => {
    const { f } = refract();
    const seen: [number, number | undefined][] = [];
    expect(await downloadWhole('https://refract.test/x', { fetch: f, onProgress: (got, size) => seen.push([got, size]) })).toEqual(FILE);
    expect(seen.at(-1)).toEqual([FILE.length, FILE.length]);
  });

  it('resumes a dropped connection where it stopped', async () => {
    const { f, ranges } = refract({ drop: 20_000 });
    expect(await downloadWhole('https://refract.test/x', { fetch: f, sleep: async () => {} })).toEqual(FILE);
    expect(ranges).toEqual([null, 'bytes=20000-']);
  });

  it('refuses a resume whose total differs from the first answer', async () => {
    const { f } = refract({ drop: 20_000, total: FILE.length + 1 });
    await expect(downloadWhole('https://refract.test/x', { fetch: f, sleep: async () => {} })).rejects.toThrow(/can't resume at byte 20000/);
  });

  it("waits out a 429's Retry-After", async () => {
    const { f, ranges } = refract({ first: new Response('busy', { status: 429, headers: { 'retry-after': '7' } }) });
    const waits: number[] = [];
    expect(await downloadWhole('https://refract.test/x', { fetch: f, sleep: async (ms) => { waits.push(ms); } })).toEqual(FILE);
    expect(waits).toEqual([7000]);
    expect(ranges).toEqual([null, null]);
  });

  it("fails with a ProxyError on Refract's 502, without trying again", async () => {
    const { f, ranges } = refract({ first: new Response('GitHub answered 503\n', { status: 502 }) });
    const e = await downloadWhole('https://refract.test/x', { fetch: f, sleep: async () => {} }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ProxyError);
    expect((e as Error).message).toBe('the download proxy answered 502: GitHub answered 503');
    expect(ranges).toHaveLength(1);
  });
});

describe('releaseFileUrl', () => {
  it("puts a Glassworks release file under Refract's glassworks prefix", () => {
    expect(releaseFileUrl(REFRACT, 'v1.2.3', 'app-1.2.3.apk')).toBe('https://refract.glassworks.dev/glassworks/v1.2.3/app-1.2.3.apk');
    expect(releaseFileUrl('http://localhost:8787/', 'generic-android-v1.0.0', 'a.apk.sha256')).toBe('http://localhost:8787/glassworks/generic-android-v1.0.0/a.apk.sha256');
  });
});
