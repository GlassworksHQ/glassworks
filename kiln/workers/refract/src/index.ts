// Refract (refract.glassworks.dev) streams the GitHub release assets in allowlist.json, and nothing
// else, to Kiln. GitHub's release file host sends no Access-Control-Allow-Origin, so a page can't
// read those bytes itself. This Worker stores nothing: no Cache API, no KV or R2, and the subrequest
// skips Cloudflare's cache. Kiln still checks each file's SHA-256.
//
// It also serves a few small files from a project's repository at a pinned commit (`commit` and
// `path` instead of `tag` and `name`), such as the Glass Panes image's published adb key (#579). Those
// are at most 64 KiB: Refract reads one whole, checks its size and SHA-256 against the allowlist, and
// sends it only if both match.
//
// And it serves Glassworks' own release APKs, which Kiln installs, with the .sha256 beside each
// (releases.json, written from the frames' installer.json `releases`). Those can't be pinned before the
// release exists, so releases.json admits them by line instead: this repository only, a line's own tag
// pattern and asset names, nothing else. Kiln checks each APK against its .sha256.
import allowlist from '../allowlist.json';
import releases from '../releases.json';

interface Common {
  owner: string;
  repo: string;
  size: number;
  sha256: string;
  credit: { name: string; url: string; project: string };
}
/** A release asset, streamed. */
interface Release extends Common { tag: string; name: string }
/** A file in the repository at a pinned commit (40 hex digits), at most REPO_FILE_MAX bytes, checked whole. */
interface RepoFile extends Common { commit: string; path: string }
type Asset = Release | RepoFile;

/** releases.json: this repository's release lines, by tag pattern (the version is the first group) and asset names. */
interface Releases {
  owner: string;
  repo: string;
  /** The first part of the path, before `<tag>/<name>`. */
  prefix: string;
  credit: Common['credit'];
  lines: { line: string; tag: string; assets: string[] }[];
}

/** One file Refract streams from a GitHub release: pinned by the allowlist, or admitted by a release line. */
interface Streamed {
  source: string;
  fileName: string;
  contentType: string;
  author: string;
  /** The allowlist's size: GitHub must send exactly this. Undefined for a release line's file. */
  size?: number;
  /** The most bytes a file without a pinned size may have. */
  max: number;
}

/** A file Refract reads whole and checks before it sends a byte: a repository file, or a release APK's .sha256. */
interface Small {
  source: string;
  fileName: string;
  contentType: string;
  author: string;
  max: number;
  /** A release file follows GitHub's redirects to its file hosts; a repository file answers directly. */
  redirects: boolean;
  /** Why this entry can't be served at all, before GitHub is asked. */
  broken?: string;
  /** Why the bytes can't be sent, or undefined when they can. */
  check(body: Uint8Array<ArrayBuffer>): Promise<string | undefined>;
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
  /** Per IP: GETs of a small file read whole (a repository file, a release APK's .sha256). */
  SMALL_FILES: RateLimit;
}

const BASE = 'https://refract.glassworks.dev';
const ASSETS: Readonly<Record<string, Asset>> = allowlist;
const RELEASES: Releases = releases;
const LINES = RELEASES.lines.map((l) => ({ ...l, re: new RegExp(l.tag) }));
/** The most a release line's file may be: an APK is about 100 MB today. */
const MAX_RELEASE_BYTES = 256 * 1024 * 1024;
/** The most a release APK's .sha256 may be: one line, the hash and the name. */
const MAX_SUM_BYTES = 1024;
/** A tag or asset name in a release path: no slash, no percent-encoding, no leading dot. */
const SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const SUM = /^[0-9a-f]{64}(\s|$)/i;
/** The largest repository file Refract serves: it reads the whole file to check it. */
const REPO_FILE_MAX = 64 * 1024;
const COMMIT = /^[0-9a-f]{40}$/;
const isRepoFile = (a: Asset): a is RepoFile => 'commit' in a;
/** Where GitHub's release downloads redirect to. A redirect anywhere else is refused. */
const UPSTREAM_HOSTS = new Set(['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com']);
/** Where a repository file comes from. It answers directly; a redirect is refused. */
const RAW_HOST = 'raw.githubusercontent.com';
/** Requests to GitHub per download: the releases URL and up to three redirects. */
const MAX_HOPS = 4;

const UA = 'glassworks-refract (+https://github.com/GlassworksHQ/glassworks)';
const SINGLE_RANGE = /^bytes=(?:(\d+)-\d*|-\d+)$/;
const CONTENT_RANGE = /^bytes (\d+)-(\d+)\/(\d+)$/;
const ETAG = /^(W\/)?"[^"]*"$/;

const releaseSource = (tag: string, name: string) => `https://github.com/${RELEASES.owner}/${RELEASES.repo}/releases/download/${tag}/${name}`;
const source = (a: Asset) => (isRepoFile(a)
  ? `https://${RAW_HOST}/${a.owner}/${a.repo}/${a.commit}/${a.path}`
  : `https://github.com/${a.owner}/${a.repo}/releases/download/${a.tag}/${a.name}`);
const fileName = (a: Asset) => (isRepoFile(a) ? a.path.split('/').pop()! : a.name);

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
    if (asset) return isRepoFile(asset) ? small(req, env, repoFile(asset), headers) : proxy(req, env, pinned(asset), headers);
    const rel = releaseFile(id);
    if (!rel) return text(404, 'not on the allowlist', headers);
    return rel.sum ? small(req, env, releaseSum(rel.tag, rel.name), headers) : proxy(req, env, releaseApk(rel.tag, rel.name), headers);
  },
};

/**
 * `<prefix>/<tag>/<name>` when releases.json admits it: a line whose tag pattern matches the tag, and the name one of
 * that line's assets for the tag's version, or such an APK's .sha256. Undefined for anything else.
 */
function releaseFile(id: string): { tag: string; name: string; sum: boolean } | undefined {
  const parts = id.split('/');
  if (parts.length !== 3 || parts[0] !== RELEASES.prefix) return undefined;
  const [, tag, name] = parts as [string, string, string];
  if (!SEGMENT.test(tag) || !SEGMENT.test(name)) return undefined;
  const sum = name.endsWith('.apk.sha256');
  const file = sum ? name.slice(0, -'.sha256'.length) : name;
  for (const l of LINES) {
    const version = l.re.exec(tag)?.[1];
    if (version !== undefined && l.assets.some((a) => a.replaceAll('{version}', version) === file)) return { tag, name, sum };
  }
  return undefined;
}

const pinned = (a: Release): Streamed => ({
  source: source(a), fileName: a.name, contentType: 'application/octet-stream', author: a.credit.url, size: a.size, max: a.size,
});

const releaseApk = (tag: string, name: string): Streamed => ({
  source: releaseSource(tag, name), fileName: name, contentType: 'application/vnd.android.package-archive', author: RELEASES.credit.url,
  max: MAX_RELEASE_BYTES,
});

const releaseSum = (tag: string, name: string): Small => ({
  source: releaseSource(tag, name), fileName: name, contentType: 'text/plain; charset=utf-8', author: RELEASES.credit.url,
  max: MAX_SUM_BYTES, redirects: true,
  check: async (body) => (SUM.test(new TextDecoder().decode(body)) ? undefined : `${name} doesn't start with a SHA-256`),
});

function repoFile(a: RepoFile): Small {
  return {
    source: source(a), fileName: fileName(a), contentType: 'application/octet-stream', author: a.credit.url, max: REPO_FILE_MAX, redirects: false,
    ...(!COMMIT.test(a.commit) || a.size > REPO_FILE_MAX ? { broken: 'this allowlist entry isn\'t a pinned small file' } : {}),
    async check(body) {
      const sha = [...new Uint8Array(await crypto.subtle.digest('SHA-256', body))].map((b) => b.toString(16).padStart(2, '0')).join('');
      return body.length !== a.size || sha !== a.sha256 ? `the file changed: GitHub sends ${body.length} bytes with another SHA-256 than the allowlist pins` : undefined;
    },
  };
}

/**
 * A small file, read whole and checked before a byte is sent, then sent whole (a Range is ignored, as HTTP allows): a
 * repository file at a pinned commit from raw.githubusercontent.com (no redirect), or a release APK's .sha256 (GitHub's
 * redirects to its file hosts). Counts on SMALL_FILES for the rate limit.
 */
async function small(req: Request, env: Env, file: Small, headers: Headers): Promise<Response> {
  if (file.broken) return text(500, file.broken, headers);
  const ip = req.headers.get('CF-Connecting-IP') ?? 'unknown';
  if (req.method === 'GET' && !(await env.SMALL_FILES.limit({ key: ip })).success) return tooMany(false, headers);
  const timeoutMs = Number(env.UPSTREAM_TIMEOUT_MS) || 30_000;
  const ask = new Headers({ 'User-Agent': UA, 'Accept-Encoding': 'identity' });
  let body: Uint8Array<ArrayBuffer>;
  try {
    // the timeout is for the headers: the body may take as long as it needs
    const up = file.redirects ? await github(file.source, 'GET', ask, timeoutMs) : await direct(file.source, ask, timeoutMs);
    if (up.status !== 200) { await up.body?.cancel(); return text(502, `GitHub answered ${up.status}`, headers); }
    const read = await whole(up, file.max);
    if (!read) return text(502, `GitHub sends more than the ${file.max} bytes Refract reads of ${file.fileName}`, headers);
    body = read;
  } catch (e) {
    return text(502, e instanceof Upstream ? e.message : 'GitHub could not be reached', headers);
  }
  const why = await file.check(body);
  if (why) return text(502, why, headers);
  headers.set('Content-Type', file.contentType);
  headers.set('Content-Disposition', `attachment; filename="${file.fileName}"`);
  headers.set('Content-Length', String(body.length));
  headers.set('Link', `<${file.author}>; rel="author", <${file.source}>; rel="canonical"`);
  return new Response(req.method === 'HEAD' ? null : body, { status: 200, headers });
}

/** One request that must answer itself, within `timeoutMs` for the headers: a redirect is GitHub's answer, refused. */
async function direct(url: string, headers: Headers, timeoutMs: number): Promise<Response> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  try {
    return await fetch(url, { headers, redirect: 'manual', cache: 'no-store', signal: abort.signal });
  } catch (e) {
    if (abort.signal.aborted) throw new Upstream('GitHub didn\'t answer in time');
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

/** The whole body, or undefined (and the rest let go) once it passes `max` bytes. */
async function whole(r: Response, max: number): Promise<Uint8Array<ArrayBuffer> | undefined> {
  if (Number(r.headers.get('Content-Length') ?? 0) > max) { await r.body?.cancel(); return undefined; }
  if (!r.body) return new Uint8Array(0);
  const reader = r.body.getReader();
  const parts: Uint8Array[] = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    got += value.length;
    if (got > max) { await reader.cancel(); return undefined; }
    parts.push(value);
  }
  const out = new Uint8Array(got);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

async function proxy(req: Request, env: Env, asset: Streamed, headers: Headers): Promise<Response> {
  // Range and If-Range mean something only on GET (RFC 9110 §14.2); HEAD describes the whole file.
  const range = req.method === 'GET' ? req.headers.get('Range') : null;
  const m = range === null ? null : SINGLE_RANGE.exec(range);
  if (range !== null && !m) {
    // the size is known before GitHub is asked only when the allowlist pins it
    if (asset.size !== undefined) headers.set('Content-Range', `bytes */${asset.size}`);
    return text(416, 'one range only, as bytes=a-b, bytes=a- or bytes=-n', headers);
  }
  const ifRange = range === null ? null : req.headers.get('If-Range');
  // Only an ETag can be checked against GitHub's copy. With a date, the whole file is the safe answer.
  const partial = m !== null && (ifRange === null || ETAG.test(ifRange));
  const resume = partial && Number(m?.[1] ?? 0) > 0;
  const ip = req.headers.get('CF-Connecting-IP') ?? 'unknown';

  if (req.method === 'GET' && !(await (resume ? env.RESUMES : env.FULL_DOWNLOADS).limit({ key: ip })).success) {
    return tooMany(resume, headers);
  }

  const ask = new Headers({ 'User-Agent': UA });
  if (partial) ask.set('Range', range!);
  if (partial && ifRange !== null) ask.set('If-Range', ifRange);
  const timeoutMs = Number(env.UPSTREAM_TIMEOUT_MS) || 30_000;

  let up: Response;
  try {
    up = await github(asset.source, req.method, ask, timeoutMs);
    // If-Range names an older copy than GitHub has now, and GitHub sent the range anyway: the
    // client's partial file is stale, so it gets the whole file, as RFC 9110 asks.
    if (up.status === 206 && ifRange !== null && up.headers.get('ETag') !== ifRange) {
      await up.body?.cancel();
      ask.delete('Range');
      ask.delete('If-Range');
      up = await github(asset.source, req.method, ask, timeoutMs);
    }
  } catch (e) {
    return text(502, e instanceof Upstream ? e.message : 'GitHub could not be reached', headers);
  }
  // A resume that became the whole file costs a full download too.
  if (resume && up.status === 200 && !(await env.FULL_DOWNLOADS.limit({ key: ip })).success) {
    await up.body?.cancel();
    return tooMany(false, headers);
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
  if (asset.size !== undefined && total !== asset.size) {
    await up.body?.cancel();
    return text(502, `the release changed: GitHub sends ${total ?? 'an unknown number of'} bytes, the allowlist pins ${asset.size}`, headers);
  }
  if (total === undefined || !Number.isSafeInteger(total) || total > asset.max) {
    await up.body?.cancel();
    return text(502, `GitHub sends ${Number.isSafeInteger(total) ? total : 'an unknown number of'} bytes for ${asset.fileName}, and Refract serves at most ${asset.max}`, headers);
  }
  if (up.status === 416) {
    await up.body?.cancel();
    headers.set('Content-Range', `bytes */${total}`);
    return text(416, 'the range starts past the end of the file', headers);
  }

  headers.set('Content-Type', asset.contentType);
  headers.set('Content-Disposition', `attachment; filename="${asset.fileName}"`);
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Link', `<${asset.author}>; rel="author", <${asset.source}>; rel="canonical"`);
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

function tooMany(resume: boolean, headers: Headers): Response {
  headers.set('Retry-After', '60');
  return text(429, resume ? 'too many resumed downloads from your address; try again in a minute' : 'too many downloads from your address; try again in a minute', headers);
}

function index(headers: Headers): Response {
  const body = JSON.stringify(
    {
      about:
        'Refract: Glassworks Kiln downloads these GitHub release assets, and Glassworks\' own release files, through this Worker, because GitHub sends no CORS headers. ' +
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
      releases: {
        about: `${RELEASES.credit.project}'s own release files: each line's assets for any tag its pattern matches ({version} is the tag's), and each APK's .sha256.`,
        url: `${BASE}/${RELEASES.prefix}/<tag>/<name>`,
        source: `https://github.com/${RELEASES.owner}/${RELEASES.repo}/releases`,
        lines: RELEASES.lines,
        credit: { ...RELEASES.credit, line: `Built by ${RELEASES.credit.name}.` },
      },
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
