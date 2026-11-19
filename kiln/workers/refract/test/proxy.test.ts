// The Worker in workerd (Miniflare), with wrangler.json's settings and a fake GitHub behind it: the
// releases URL redirects to a file host that serves bytes, ranges and If-Range the way GitHub's does.
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { join } from 'node:path';
import { build } from 'esbuild';
import { Miniflare, type MiniflareOptions, type Request as MfRequest, Response as MfResponse } from 'miniflare';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import allowlist from '../allowlist.json';

const dir = join(__dirname, '..');
const config = JSON.parse(readFileSync(join(dir, 'wrangler.json'), 'utf8'));
const KILN = 'https://kiln.glassworks.dev';
const IMG = 'glass-panes/mirror-android-2/system.img';
const SUMS = 'glass-panes/mirror-android-2/SHA256SUMS';
const USERDATA = 'glass-panes/mirror-android-2/userdata.sparse';
const KEY = 'glass-panes/project-key/adbkey';
const SUMS_TEXT =
  'bdb6b8445296666162256affa968d590075f56a8ad2229930f8502b9703e35c1  system.img\n' +
  '74b1f57ac20ce7c56065083a2bc3ddd283493be29d851fd04efc156ec96cf908  userdata.sparse\n';
const ETAG = '"0xFAKE"';
/** Stands in for the published key: the test pins these bytes' hash in a copy of the allowlist entry. */
const KEY_BYTES = new TextEncoder().encode('-----BEGIN PRIVATE KEY-----\nnot a real key\n-----END PRIVATE KEY-----\n');
const TIMEOUT_MS = 300;
const limit = (name: string): number => config.ratelimits.find((r: { name: string }) => r.name === name).simple.limit;
const FULL = limit('FULL_DOWNLOADS');
const RESUMES = limit('RESUMES');
const DATE = 'Thu, 05 Nov 2026 00:58:46 GMT';

/** A release file the fake host serves: its length and the byte at each offset. */
interface FakeFile { size: number; byte: (i: number) => number }
const sums: FakeFile = { size: SUMS_TEXT.length, byte: (i) => SUMS_TEXT.charCodeAt(i) };
const pattern: FakeFile = { size: allowlist[IMG].size, byte: (i) => i % 251 };
const pick = (f: FakeFile, from: number, to: number) => Uint8Array.from({ length: to - from + 1 }, (_, k) => f.byte(from + k));
/** The bytes from..to, made 64 KiB at a time as they are read, so a whole image never sits in memory. */
function stream(f: FakeFile, from: number, to: number): ReadableStream<Uint8Array> {
  let at = from;
  return new ReadableStream({
    pull(c) {
      const end = Math.min(at + 65535, to);
      c.enqueue(pick(f, at, end));
      at = end + 1;
      if (at > to) c.close();
    },
  });
}

let files: Record<string, FakeFile>;
let honoursIfRange: boolean;
let releases: (req: MfRequest) => MfResponse | Promise<MfResponse> | undefined;
const seen: MfRequest[] = [];

/** What raw.githubusercontent.com sends for a repository file: the bytes, or a test's own answer. */
let raw: (req: MfRequest) => MfResponse;

async function github(req: MfRequest): Promise<MfResponse> {
  seen.push(req);
  const url = new URL(req.url);
  const hit = await releases(req);
  if (hit) return hit;
  if (url.host === 'raw.githubusercontent.com') return raw(req);
  if (url.host === 'github.com') {
    const name = url.pathname.split('/').pop()!;
    return new MfResponse(null, { status: 302, headers: { Location: `https://release-assets.githubusercontent.com/fake/${name}?sig=x` } });
  }
  const f = files[url.pathname.split('/').pop()!];
  if (url.host !== 'release-assets.githubusercontent.com' || !f) return new MfResponse('Not Found', { status: 404 });
  const common = { 'Content-Type': 'application/octet-stream', 'Accept-Ranges': 'bytes', ETag: ETAG };
  const ifRange = req.headers.get('If-Range');
  const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.get('Range') ?? '');
  if (!m || (honoursIfRange && ifRange !== null && ifRange !== ETAG)) {
    const body = req.method === 'HEAD' ? null : stream(f, 0, f.size - 1);
    return new MfResponse(body, { headers: { ...common, 'Content-Length': String(f.size) } });
  }
  const from = m[1] ? Number(m[1]) : f.size - Number(m[2]);
  const to = m[1] && m[2] ? Math.min(Number(m[2]), f.size - 1) : f.size - 1;
  if (from >= f.size) return new MfResponse(null, { status: 416, headers: { 'Content-Range': `bytes */${f.size}` } });
  const range = { 'Content-Range': `bytes ${from}-${to}/${f.size}`, 'Content-Length': String(to - from + 1) };
  return new MfResponse(req.method === 'HEAD' ? null : stream(f, from, to), { status: 206, headers: { ...common, ...range } });
}

let script: string;
let mf: Miniflare;
const worker = (outboundService: MiniflareOptions['outboundService'], vars: Record<string, string> = { ...config.vars, UPSTREAM_TIMEOUT_MS: String(TIMEOUT_MS) }) =>
  new Miniflare({
    modules: true,
    script,
    compatibilityDate: config.compatibility_date,
    bindings: vars,
    ratelimits: Object.fromEntries(
      (config.ratelimits as { name: string; namespace_id: string; simple: { limit: number; period: 10 | 60 } }[]).map((r) => [
        r.name,
        { namespace_id: r.namespace_id, simple: r.simple },
      ]),
    ),
    outboundService,
  });
beforeAll(async () => {
  const out = await build({ entryPoints: [join(dir, config.main)], bundle: true, format: 'esm', write: false });
  script = out.outputFiles[0]!.text;
  mf = worker(github);
  await mf.ready;
}, 60_000);
afterAll(() => mf?.dispose());
beforeEach(() => {
  files = { 'system.img': pattern, SHA256SUMS: sums };
  honoursIfRange = true;
  releases = () => undefined;
  raw = () => new MfResponse(KEY_BYTES, { headers: { 'Content-Length': String(KEY_BYTES.length), 'Content-Type': 'text/plain; charset=utf-8' } });
  seen.length = 0;
});

/** A request from Kiln, from its own fresh address unless `ip` says otherwise, so rate limits don't carry over. */
const get = (path: string, init: { method?: string; headers?: Record<string, string>; ip?: string } = {}, on = mf) =>
  on.dispatchFetch(`https://refract.glassworks.dev/${path}`, {
    method: init.method,
    headers: { Origin: KILN, 'CF-Connecting-IP': init.ip ?? `198.51.100.${randomUUID()}`, ...init.headers },
  });

describe('the allowlist', () => {
  it("streams an allowed asset from its release, following GitHub's redirect", async () => {
    const res = await get(SUMS);
    const body = Buffer.from(await res.arrayBuffer());
    expect(res.status).toBe(200);
    expect(createHash('sha256').update(body).digest('hex')).toBe(allowlist[SUMS].sha256);
    expect(res.headers.get('Content-Length')).toBe('159');
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="SHA256SUMS"');
    expect(seen.map((r) => new URL(r.url).host)).toEqual(['github.com', 'release-assets.githubusercontent.com']);
    expect(seen[0]!.url).toBe('https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/SHA256SUMS');
  });

  it("pins both images to the release's own SHA256SUMS", () => {
    expect(SUMS_TEXT).toContain(`${allowlist[IMG].sha256}  system.img`);
    // Kiln's Glass Panes install (#579) downloads userdata.sparse too
    expect(SUMS_TEXT).toContain(`${allowlist[USERDATA].sha256}  userdata.sparse`);
    expect(allowlist[USERDATA].size).toBe(154944572);
  });

  it.each(['nope', 'glass-panes/mirror-android-1/system.img', 'glass-panes/mirror-android-2/boot.img', 'https://example.com/x', '__proto__', 'toString', `${IMG}/`, IMG.toUpperCase()])(
    'answers 404 to %s and never asks GitHub',
    async (path) => {
      const res = await get(path);
      expect(res.status).toBe(404);
      await res.arrayBuffer();
      expect(seen).toEqual([]);
    },
  );

  it('refuses other methods', async () => {
    const res = await get(IMG, { method: 'POST' });
    expect(res.status).toBe(405);
    expect(res.headers.get('Allow')).toBe('GET, HEAD, OPTIONS');
    await res.arrayBuffer();
    expect(seen).toEqual([]);
  });
});

describe("GitHub's redirect", () => {
  it.each([
    ['another host', 'https://evil.example/system.img'],
    ['a look-alike host', 'https://release-assets.githubusercontent.com.evil.example/x'],
    ['plain http', 'http://release-assets.githubusercontent.com/fake/system.img'],
    ['another port', 'https://release-assets.githubusercontent.com:8443/fake/system.img'],
    ['no Location', null],
  ])('is refused when it points at %s, and nothing is fetched from there', async (_, location) => {
    releases = (req) =>
      new URL(req.url).host === 'github.com' ? new MfResponse(null, { status: 302, headers: location ? { Location: location } : {} }) : undefined;
    const res = await get(IMG, { headers: { Range: 'bytes=0-0' } });
    expect(res.status).toBe(502);
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
    await res.arrayBuffer();
    expect(seen.map((r) => new URL(r.url).host)).toEqual(['github.com']);
  });

  it('is followed to objects.githubusercontent.com', async () => {
    releases = (req) => {
      const u = new URL(req.url);
      if (u.host === 'github.com') return new MfResponse(null, { status: 302, headers: { Location: 'https://objects.githubusercontent.com/o/SHA256SUMS' } });
      if (u.host === 'objects.githubusercontent.com') return new MfResponse(SUMS_TEXT, { headers: { 'Content-Length': '159' } });
      return undefined;
    };
    const res = await get(SUMS);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(SUMS_TEXT);
  });

  it('is refused after three redirects', async () => {
    releases = (req) => new MfResponse(null, { status: 302, headers: { Location: `${req.url}x` } });
    const res = await get(SUMS);
    expect(res.status).toBe(502);
    expect(await res.text()).toBe('GitHub redirected too many times\n');
    expect(seen).toHaveLength(4);
  });
});

describe('ranges, If-Range and HEAD', () => {
  it('passes a Range through, so Kiln can resume', async () => {
    const res = await get(IMG, { headers: { Range: 'bytes=850905800-' } });
    expect(res.status).toBe(206);
    expect(res.headers.get('Content-Range')).toBe('bytes 850905800-850905807/850905808');
    expect(res.headers.get('Content-Length')).toBe('8');
    expect([...new Uint8Array(await res.arrayBuffer())]).toEqual([...pick(pattern, 850905800, 850905807)]);
    expect(seen.map((r) => r.headers.get('Range'))).toEqual(['bytes=850905800-', 'bytes=850905800-']);
  });

  it('answers a range past the end with 416 and a plain reason, not a file', async () => {
    const res = await get(IMG, { headers: { Range: 'bytes=900000000-' } });
    expect(res.status).toBe(416);
    expect(res.headers.get('Content-Range')).toBe('bytes */850905808');
    expect(res.headers.get('Content-Type')).toBe('text/plain; charset=utf-8');
    expect(res.headers.get('Content-Disposition')).toBeNull();
    expect(await res.text()).toBe('the range starts past the end of the file\n');
  });

  it.each(['bytes=0-1,5-6', 'bytes=abc', 'bytes 0-1', 'items=0-1', 'bytes=-'])(
    'answers 416 to Range %j without asking GitHub or sending the file',
    async (range) => {
      const res = await get(IMG, { headers: { Range: range } });
      expect(res.status).toBe(416);
      expect(res.headers.get('Content-Range')).toBe('bytes */850905808');
      expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
      expect((await res.arrayBuffer()).byteLength).toBeLessThan(100);
      expect(seen).toEqual([]);
    },
  );

  it('forwards If-Range, and resumes when the copy is the same', async () => {
    const res = await get(SUMS, { headers: { Range: 'bytes=100-', 'If-Range': ETAG } });
    expect(res.status).toBe(206);
    expect(await res.text()).toBe(SUMS_TEXT.slice(100));
    expect(seen.map((r) => r.headers.get('If-Range'))).toEqual([ETAG, ETAG]);
  });

  it('sends the whole file when If-Range names an older copy', async () => {
    const res = await get(SUMS, { headers: { Range: 'bytes=100-', 'If-Range': '"0xOLD"' } });
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(SUMS_TEXT);
  });

  it('sends the whole file for an older copy even if GitHub ignores If-Range', async () => {
    honoursIfRange = false;
    const res = await get(SUMS, { headers: { Range: 'bytes=100-', 'If-Range': '"0xOLD"' } });
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(SUMS_TEXT);
    expect(seen.map((r) => r.headers.get('Range'))).toEqual(['bytes=100-', 'bytes=100-', null, null]);
  });

  it('sends the whole file, without asking GitHub for a range, when If-Range is a date', async () => {
    const res = await get(SUMS, { headers: { Range: 'bytes=100-', 'If-Range': DATE } });
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(SUMS_TEXT);
    expect(seen.every((r) => !r.headers.has('Range') && !r.headers.has('If-Range'))).toBe(true);
  });

  it.each(['bytes=0-0', 'bytes=abc'])('answers HEAD for the whole file, ignoring Range %j and If-Range', async (range) => {
    const res = await get(IMG, { method: 'HEAD', headers: { Range: range, 'If-Range': ETAG } });
    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Length')).toBe('850905808');
    expect(res.headers.get('Content-Range')).toBeNull();
    expect(seen.every((r) => !r.headers.has('Range') && !r.headers.has('If-Range'))).toBe(true);
  });

  it('answers HEAD with the size and no body, from a HEAD upstream', async () => {
    const res = await get(IMG, { method: 'HEAD' });
    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Length')).toBe('850905808');
    expect(res.headers.get('Accept-Ranges')).toBe('bytes');
    expect(res.headers.get('ETag')).toBe(ETAG);
    expect((await res.arrayBuffer()).byteLength).toBe(0);
    expect(seen.map((r) => r.method)).toEqual(['HEAD', 'HEAD']);
  });
});

describe('rate limits, per address', () => {
  it('allows FULL_DOWNLOADS full downloads a minute, then answers 429 with a reason Kiln can read', async () => {
    const ip = '198.51.100.7';
    for (let i = 0; i < FULL; i++) {
      const res = await get(SUMS, { ip });
      expect(res.status).toBe(200);
      await res.arrayBuffer();
    }
    const res = await get(SUMS, { ip });
    expect(res.status).toBe(429);
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
    expect(res.headers.get('Retry-After')).toBe('60');
    expect(await res.text()).toBe('too many downloads from your address; try again in a minute\n');
    expect(seen).toHaveLength(2 * FULL);
  });

  it('counts bytes=0- and a suffix range as full downloads', async () => {
    const ip = '198.51.100.8';
    for (const range of ['bytes=0-', 'bytes=-10', 'bytes=0-15']) await (await get(SUMS, { ip, headers: { Range: range } })).arrayBuffer();
    expect((await get(SUMS, { ip, headers: { Range: 'bytes=0-' } })).status).toBe(429);
  });

  it('keeps resumes working after the full downloads are used up', async () => {
    const ip = '198.51.100.9';
    for (let i = 0; i <= FULL; i++) await (await get(IMG, { ip, headers: { Range: 'bytes=0-0' } })).arrayBuffer();
    const res = await get(IMG, { ip, headers: { Range: 'bytes=850905800-' } });
    expect(res.status).toBe(206);
    await res.arrayBuffer();
  });

  it('allows RESUMES resumes a minute', async () => {
    const ip = '198.51.100.10';
    for (let i = 0; i < RESUMES; i++) {
      const res = await get(IMG, { ip, headers: { Range: `bytes=${850905800 - i}-` } });
      expect(res.status).toBe(206);
      await res.arrayBuffer();
    }
    const res = await get(IMG, { ip, headers: { Range: 'bytes=850905800-' } });
    expect(res.status).toBe(429);
    expect(await res.text()).toBe('too many resumed downloads from your address; try again in a minute\n');
  });

  it('counts a resume that becomes the whole file as a full download too', async () => {
    const ip = '198.51.100.14';
    for (let i = 0; i < FULL; i++) await (await get(SUMS, { ip })).arrayBuffer();
    const res = await get(SUMS, { ip, headers: { Range: 'bytes=100-', 'If-Range': '"0xOLD"' } });
    expect(res.status).toBe(429);
    expect(await res.text()).toBe('too many downloads from your address; try again in a minute\n');
  });

  it('counts a range with a date If-Range as a full download', async () => {
    const ip = '198.51.100.15';
    for (let i = 0; i < FULL; i++) await (await get(SUMS, { ip, headers: { Range: 'bytes=100-', 'If-Range': DATE } })).arrayBuffer();
    expect((await get(SUMS, { ip, headers: { Range: 'bytes=100-' } })).status).toBe(206);
    expect((await get(SUMS, { ip })).status).toBe(429);
  });

  it("doesn't count HEAD, the index or a preflight", async () => {
    const ip = '198.51.100.11';
    for (let i = 0; i < 5; i++) {
      for (const [path, method] of [[IMG, 'HEAD'], ['', 'GET'], [IMG, 'OPTIONS']] as const) await (await get(path, { ip, method })).arrayBuffer();
    }
    expect((await get(SUMS, { ip })).status).toBe(200);
  });

  it("keeps one address's limit off another's", async () => {
    for (let i = 0; i <= FULL; i++) await (await get(SUMS, { ip: '198.51.100.12' })).arrayBuffer();
    expect((await get(SUMS, { ip: '198.51.100.13' })).status).toBe(200);
  });
});

describe('CORS', () => {
  it('lets Kiln read the bytes and the range headers', async () => {
    const res = await get(IMG, { method: 'HEAD' });
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
    expect(res.headers.get('Access-Control-Expose-Headers')).toBe('Content-Length, Content-Range, Accept-Ranges, ETag, Link, Retry-After');
    expect(res.headers.get('Vary')).toBe('Origin');
  });

  it.each(['http://localhost:5174', 'http://localhost:5173'])("allows Kiln's local dev server %s", async (origin) => {
    const res = await get(IMG, { method: 'HEAD', headers: { Origin: origin } });
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(origin);
  });

  it.each(['https://evil.example', 'https://kiln.glassworks.dev.evil.example', 'null'])('gives %s no CORS headers', async (origin) => {
    const res = await get(IMG, { method: 'HEAD', headers: { Origin: origin } });
    expect(res.headers.get('Access-Control-Allow-Origin')).toBeNull();
    expect(res.headers.get('Access-Control-Expose-Headers')).toBeNull();
  });

  it('answers a preflight for Range and If-Range from Kiln, without asking GitHub', async () => {
    const res = await get(IMG, { method: 'OPTIONS', headers: { 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'range' } });
    expect(res.status).toBe(204);
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
    expect(res.headers.get('Access-Control-Allow-Methods')).toBe('GET, HEAD');
    expect(res.headers.get('Access-Control-Allow-Headers')).toBe('Range, If-Range');
    expect(seen).toEqual([]);
  });

  it('answers a preflight from another origin without allowing it', async () => {
    const res = await get(IMG, { method: 'OPTIONS', headers: { Origin: 'https://evil.example' } });
    expect(res.headers.get('Access-Control-Allow-Origin')).toBeNull();
  });
});

describe('when the release changed or GitHub fails', () => {
  it('refuses a file whose size differs from the allowlist', async () => {
    files['system.img'] = { ...pattern, size: pattern.size + 1 };
    const res = await get(IMG, { method: 'HEAD' });
    expect(res.status).toBe(502);
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
  });

  it('refuses a range whose total differs from the allowlist', async () => {
    files['system.img'] = { ...pattern, size: pattern.size - 1 };
    const res = await get(IMG, { headers: { Range: 'bytes=0-15' } });
    expect(res.status).toBe(502);
    expect(await res.text()).toContain('the release changed');
  });

  it('refuses a body without a length', async () => {
    releases = () => new MfResponse(new ReadableStream({ start: (c) => (c.enqueue(new Uint8Array(3)), c.close()) }));
    const res = await get(SUMS);
    expect(res.status).toBe(502);
    await res.arrayBuffer();
  });

  it.each([404, 500, 503])('answers 502 when GitHub answers %i', async (status) => {
    releases = () => new MfResponse('upstream says no', { status });
    const res = await get(SUMS);
    expect(res.status).toBe(502);
    expect(await res.text()).toBe(`GitHub answered ${status}\n`);
  });

  it("answers 502 when GitHub can't be reached", async () => {
    const down = worker({ node: (_req: IncomingMessage, res: ServerResponse) => res.socket?.destroy() });
    try {
      const res = await get(SUMS, {}, down);
      expect(res.status).toBe(502);
      expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
      expect(await res.text()).toBe('GitHub could not be reached\n');
    } finally {
      await down.dispose();
    }
  });

  it('waits 30 s for GitHub when UPSTREAM_TIMEOUT_MS is missing, instead of failing every request', async () => {
    const { UPSTREAM_TIMEOUT_MS: _, ...vars } = config.vars;
    const bare = worker(github, vars);
    try {
      const res = await get(SUMS, {}, bare);
      expect(res.status).toBe(200);
      expect(await res.text()).toBe(SUMS_TEXT);
    } finally {
      await bare.dispose();
    }
  });

  it("answers 502 when GitHub doesn't send headers in time", async () => {
    releases = () => new Promise((resolve) => setTimeout(() => resolve(new MfResponse(SUMS_TEXT)), TIMEOUT_MS * 4));
    const res = await get(SUMS);
    expect(res.status).toBe(502);
    expect(await res.text()).toBe(`GitHub didn't answer within ${TIMEOUT_MS / 1000} s\n`);
  });

  it('lets a slow body run past the timeout once the headers are in', async () => {
    releases = (req) => {
      if (new URL(req.url).host === 'github.com') return undefined;
      const bytes = new TextEncoder().encode(SUMS_TEXT);
      return new MfResponse(
        new ReadableStream({
          async start(c) {
            c.enqueue(bytes.slice(0, 10));
            await new Promise((r) => setTimeout(r, TIMEOUT_MS * 3));
            c.enqueue(bytes.slice(10));
            c.close();
          },
        }),
        { headers: { 'Content-Length': '159' } },
      );
    };
    const res = await get(SUMS);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(SUMS_TEXT);
  });
});

describe('stores nothing', () => {
  it.each([
    ['an asset', SUMS, {}],
    ['a range', IMG, { headers: { Range: 'bytes=0-0' } }],
    ['HEAD', IMG, { method: 'HEAD' }],
    ['a miss', 'nope', {}],
    ['the index', '', {}],
    ['a preflight', IMG, { method: 'OPTIONS' }],
  ] as const)('marks %s Cache-Control: no-store', async (_, path, init) => {
    const res = await get(path, init);
    expect(res.headers.get('Cache-Control')).toBe('no-store');
    await res.arrayBuffer();
  });

  it("asks GitHub with Cloudflare's cache bypassed, on both hops", async () => {
    await (await get(IMG, { headers: { Range: 'bytes=0-0' } })).arrayBuffer();
    expect(seen).toHaveLength(2);
    for (const r of seen) expect(r.cf).toMatchObject({ cacheLevel: 'bypass', cacheControl: 'no-store' });
  });

  it('binds no storage (KV, R2, D1, Durable Objects, caches) in wrangler.json', () => {
    expect(Object.keys(config).sort()).toEqual(
      ['$schema', 'compatibility_date', 'main', 'name', 'preview_urls', 'ratelimits', 'routes', 'vars', 'workers_dev'].sort(),
    );
  });
});

describe('credit', () => {
  it('names the project with each file', async () => {
    const res = await get(IMG, { method: 'HEAD' });
    expect(res.headers.get('Link')).toBe(
      '<https://github.com/git-nimrod/glass-panes>; rel="author", ' +
        '<https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/system.img>; rel="canonical"',
    );
  });

  it("lists every asset with its source and credit at /, with Refract's own address whatever the request's host", async () => {
    const res = await mf.dispatchFetch('http://localhost:8787/');
    expect(res.headers.get('Content-Type')).toBe('application/json; charset=utf-8');
    const index = (await res.json()) as { assets: { id: string; url: string; source: string; size: number; sha256: string; credit: { line: string } }[] };
    expect(index.assets.map((a) => a.id)).toEqual(Object.keys(allowlist));
    expect(index.assets[0]).toMatchObject({
      url: `https://refract.glassworks.dev/${IMG}`,
      source: 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/system.img',
      size: 850905808,
      sha256: allowlist[IMG].sha256,
      credit: { name: 'git-nimrod', project: 'Glass Panes', line: 'Built by git-nimrod as part of Glass Panes.' },
    });
    expect(seen).toEqual([]);
  });

  it('answers HEAD / without a body', async () => {
    const res = await get('', { method: 'HEAD' });
    expect(res.status).toBe(200);
    expect((await res.arrayBuffer()).byteLength).toBe(0);
  });
});

describe('a file from a repository at a pinned commit', () => {
  // the Worker under test pins the real key's size and hash; this one pins KEY_BYTES' instead
  const pinned = { ...allowlist[KEY], size: KEY_BYTES.length, sha256: createHash('sha256').update(KEY_BYTES).digest('hex') };
  let fake: Miniflare;
  beforeAll(async () => {
    const out = await build({
      entryPoints: [join(dir, config.main)], bundle: true, format: 'esm', write: false,
      plugins: [{ name: 'allowlist', setup(b) { b.onLoad({ filter: /allowlist\.json$/ }, () => ({ loader: 'json', contents: JSON.stringify({ ...allowlist, [KEY]: pinned }) })); } }],
    });
    fake = new Miniflare({ modules: true, script: out.outputFiles[0]!.text, compatibilityDate: config.compatibility_date,
      bindings: { ...config.vars, UPSTREAM_TIMEOUT_MS: String(TIMEOUT_MS) },
      ratelimits: Object.fromEntries((config.ratelimits as { name: string; namespace_id: string; simple: { limit: number; period: 10 | 60 } }[]).map((r) => [r.name, { namespace_id: r.namespace_id, simple: r.simple }])),
      outboundService: github });
    await fake.ready;
  }, 60_000);
  afterAll(() => fake?.dispose());

  it('the allowlist pins it at a full commit, small, with its hash', () => {
    expect(allowlist[KEY]).toMatchObject({ owner: 'git-nimrod', repo: 'glass-panes', commit: expect.stringMatching(/^[0-9a-f]{40}$/), path: 'mirror-app/android/project-key/adbkey', size: 1704 });
    expect(allowlist[KEY].sha256).toMatch(/^[0-9a-f]{64}$/);
  });

  it('sends it whole once its size and SHA-256 match, from raw.githubusercontent.com at the commit, a Range ignored', async () => {
    const res = await get(KEY, { headers: { Range: 'bytes=5-' } }, fake);
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(KEY_BYTES);
    expect(res.headers.get('Content-Length')).toBe(String(KEY_BYTES.length));
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="adbkey"');
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe(KILN);
    expect(res.headers.get('Cache-Control')).toBe('no-store');
    expect(seen.map((r) => r.url)).toEqual([`https://raw.githubusercontent.com/git-nimrod/glass-panes/${pinned.commit}/${pinned.path}`]);
    expect(seen[0]!.headers.get('Range')).toBeNull();
  });

  it('refuses a file that changed, a redirect, and a GitHub error, with nothing sent', async () => {
    raw = () => new MfResponse('-----BEGIN PRIVATE KEY-----\nswapped\n', { headers: { 'Content-Length': '35' } });
    let res = await get(KEY, {}, fake);
    expect(res.status).toBe(502);
    expect(await res.text()).toMatch(/the file changed/);
    raw = () => new MfResponse(null, { status: 302, headers: { Location: 'https://example.com/adbkey' } });
    res = await get(KEY, {}, fake);
    expect(res.status).toBe(502);
    expect(seen.some((r) => new URL(r.url).host === 'example.com')).toBe(false);
    raw = () => new MfResponse('nope', { status: 404 });
    expect((await get(KEY, {}, fake)).status).toBe(502);
  });

  it('a body that arrives after the header timeout is still sent: the timeout is for the headers only', async () => {
    // the first half at once, so the headers go out in time; the rest after twice the header timeout
    const half = KEY_BYTES.length >> 1;
    let step = 0;
    raw = () => new MfResponse(new ReadableStream({
      async pull(c) {
        if (step === 0) c.enqueue(KEY_BYTES.subarray(0, half));
        else if (step === 1) { await new Promise((r) => setTimeout(r, TIMEOUT_MS * 2)); c.enqueue(KEY_BYTES.subarray(half)); } else c.close();
        step++;
      },
    }, { highWaterMark: 0 }), { headers: { 'Content-Length': String(KEY_BYTES.length) } });
    const res = await get(KEY, {}, fake);
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(KEY_BYTES);
  });

  it('is listed at / with its source at the commit', async () => {
    const index = (await (await fake.dispatchFetch('http://localhost:8787/')).json()) as { assets: { id: string; source: string }[] };
    expect(index.assets.find((a) => a.id === KEY)?.source).toBe(`https://raw.githubusercontent.com/git-nimrod/glass-panes/${pinned.commit}/mirror-app/android/project-key/adbkey`);
  });
});
