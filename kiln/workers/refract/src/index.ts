// Refract (refract.glassworks.dev) streams the GitHub release assets in allowlist.json, and nothing
// else, to Kiln. GitHub's release file host sends no Access-Control-Allow-Origin, so a page can't
// read those bytes itself. This Worker stores nothing: no Cache API, no KV or R2, and the subrequest
// skips Cloudflare's cache. Kiln still checks each file's SHA-256.
import allowlist from '../allowlist.json';

interface Asset {
  owner: string;
  repo: string;
  tag: string;
  name: string;
  size: number;
  sha256: string;
  credit: { name: string; url: string; project: string };
}

interface RateLimit {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

interface Env {
  /** Comma-separated origins that may read the bytes. */
  ALLOWED_ORIGINS: string;
  /** How long GitHub may take to answer with headers, in ms. The body may take as long as it needs. */
  UPSTREAM_TIMEOUT_MS: string;
  /** Per IP: GETs from the first byte (no Range, a suffix range, or `bytes=0-`). */
  FULL_DOWNLOADS: RateLimit;
  /** Per IP: GETs that resume from a later byte, so a dropped download can carry on. */
  RESUMES: RateLimit;
}

const BASE = 'https://refract.glassworks.dev';
const ASSETS: Readonly<Record<string, Asset>> = allowlist;
/** Where GitHub's release downloads redirect to. A redirect anywhere else is refused. */
const UPSTREAM_HOSTS = new Set(['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com']);
const MAX_HOPS = 3;

const SINGLE_RANGE = /^bytes=(?:(\d+)-\d*|-\d+)$/;
const CONTENT_RANGE = /^bytes (\d+)-(\d+)\/(\d+)$/;
const ETAG = /^(W\/)?"[^"]*"$/;

const source = (a: Asset) => `https://github.com/${a.owner}/${a.repo}/releases/download/${a.tag}/${a.name}`;

class Upstream extends Error {}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const origin = req.headers.get('Origin');
    const headers = new Headers({ 'Cache-Control': 'no-store', Vary: 'Origin' });
    if (origin && env.ALLOWED_ORIGINS.split(',').map((o) => o.trim()).includes(origin)) {
      headers.set('Access-Control-Allow-Origin', origin);
      headers.set('Access-Control-Expose-Headers', 'Content-Length, Content-Range, Accept-Ranges, ETag, Link, Retry-After');
    }

    if (req.method === 'OPTIONS') {
      headers.set('Access-Control-Allow-Methods', 'GET, HEAD');
      headers.set('Access-Control-Allow-Headers', 'Range, If-Range');
      headers.set('Access-Control-Max-Age', '86400');
      return new Response(null, { status: 204, headers });
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      headers.set('Allow', 'GET, HEAD, OPTIONS');
      return text(405, 'GET or HEAD only', headers);
    }

    const id = new URL(req.url).pathname.slice(1);
    if (id === '') return index(headers);
    const asset = Object.hasOwn(ASSETS, id) ? ASSETS[id] : undefined;
    if (!asset) return text(404, 'not on the allowlist', headers);
    return proxy(req, env, asset, headers);
  },
};

async function proxy(req: Request, env: Env, asset: Asset, headers: Headers): Promise<Response> {
  const range = req.headers.get('Range');
  const m = range === null ? null : SINGLE_RANGE.exec(range);
  if (range !== null && !m) {
    headers.set('Content-Range', `bytes */${asset.size}`);
    return text(416, 'one range only, as bytes=a-b, bytes=a- or bytes=-n', headers);
  }

  if (req.method === 'GET') {
    const resume = Number(m?.[1] ?? 0) > 0;
    const { success } = await (resume ? env.RESUMES : env.FULL_DOWNLOADS).limit({ key: req.headers.get('CF-Connecting-IP') ?? 'unknown' });
    if (!success) {
      headers.set('Retry-After', '60');
      return text(429, resume ? 'too many resumed downloads from your address; try again in a minute' : 'too many downloads from your address; try again in a minute', headers);
    }
  }

  const ask = new Headers({ 'User-Agent': 'glassworks-refract (+https://github.com/GlassworksHQ/glassworks)' });
  if (range !== null) ask.set('Range', range);
  const ifRange = req.headers.get('If-Range');
  if (range !== null && ifRange !== null) ask.set('If-Range', ifRange);

  let up: Response;
  try {
    up = await github(source(asset), req.method, ask, Number(env.UPSTREAM_TIMEOUT_MS));
    // If-Range names an older copy than GitHub has now, and GitHub sent the range anyway: the
    // client's partial file is stale, so it gets the whole file, as RFC 9110 asks.
    if (up.status === 206 && ifRange !== null && ETAG.test(ifRange) && up.headers.get('ETag') !== ifRange) {
      await up.body?.cancel();
      ask.delete('Range');
      ask.delete('If-Range');
      up = await github(source(asset), req.method, ask, Number(env.UPSTREAM_TIMEOUT_MS));
    }
  } catch (e) {
    return text(502, e instanceof Upstream ? e.message : 'GitHub could not be reached', headers);
  }

  const length = up.headers.get('Content-Length');
  const contentRange = up.headers.get('Content-Range');
  let total: number | undefined;
  if (up.status === 200) total = length === null ? undefined : Number(length);
  else if (up.status === 206) total = Number(CONTENT_RANGE.exec(contentRange ?? '')?.[3]);
  else if (up.status === 416) total = Number(/^bytes \*\/(\d+)$/.exec(contentRange ?? '')?.[1]);
  else {
    await up.body?.cancel();
    return text(502, `GitHub answered ${up.status}`, headers);
  }
  if (total !== asset.size) {
    await up.body?.cancel();
    return text(502, `the release changed: GitHub sends ${total ?? 'an unknown number of'} bytes, the allowlist pins ${asset.size}`, headers);
  }

  headers.set('Content-Type', 'application/octet-stream');
  headers.set('Content-Disposition', `attachment; filename="${asset.name}"`);
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Link', `<${asset.credit.url}>; rel="author", <${source(asset)}>; rel="canonical"`);
  for (const h of ['Content-Length', 'Content-Range', 'ETag', 'Last-Modified']) {
    const v = up.headers.get(h);
    if (v !== null) headers.set(h, v);
  }
  return new Response(up.body, { status: up.status, headers });
}

/** GitHub's answer for `url`, following its redirects only to its own hosts, within `timeoutMs` for the headers. */
async function github(url: string, method: string, headers: Headers, timeoutMs: number): Promise<Response> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  try {
    for (let hop = 0; hop < MAX_HOPS; hop++) {
      const r = await fetch(url, { method, headers, redirect: 'manual', cache: 'no-store', signal: abort.signal });
      if (r.status < 300 || r.status > 399 || r.status === 304) return r;
      await r.body?.cancel();
      const next = parse(r.headers.get('Location'), url);
      if (!next || next.protocol !== 'https:' || next.port !== '' || !UPSTREAM_HOSTS.has(next.hostname)) {
        throw new Upstream(`GitHub redirected to ${next?.host || 'nowhere'}, which isn't GitHub's file host`);
      }
      url = next.href;
    }
    throw new Upstream('GitHub redirected too many times');
  } catch (e) {
    if (abort.signal.aborted) throw new Upstream(`GitHub didn't answer within ${timeoutMs / 1000} s`);
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

function parse(location: string | null, base: string): URL | null {
  try {
    return location ? new URL(location, base) : null;
  } catch {
    return null;
  }
}

function index(headers: Headers): Response {
  const body = JSON.stringify(
    {
      about:
        'Refract: Glassworks Kiln downloads these GitHub release assets through this Worker, because GitHub sends no CORS headers. ' +
        'It streams them from their source and stores nothing. Each belongs to the people credited with it.',
      code: 'https://github.com/GlassworksHQ/glassworks/tree/main/kiln/workers/refract',
      assets: Object.entries(ASSETS).map(([id, a]) => ({
        id,
        url: `${BASE}/${id}`,
        source: source(a),
        size: a.size,
        sha256: a.sha256,
        credit: { ...a.credit, line: `Built by ${a.credit.name} as part of ${a.credit.project}.` },
      })),
    },
    null,
    2,
  );
  headers.set('Content-Type', 'application/json; charset=utf-8');
  return new Response(body, { headers });
}

function text(status: number, message: string, headers: Headers): Response {
  headers.set('Content-Type', 'text/plain; charset=utf-8');
  return new Response(`${message}\n`, { status, headers });
}
