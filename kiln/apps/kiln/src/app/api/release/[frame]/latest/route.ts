// /api/release/<line>/latest: a release line's newest published release and the files of it Kiln downloads, or
// `{ release: null }` before its first: a small JSON answer, the only release route left on Vercel. The files
// themselves come from Refract (refract.glassworks.dev/glassworks/<tag>/<name>, kiln-core releaseFileUrl).
// GitHub's REST API allows 60 unauthenticated calls an hour per address, and Vercel's functions share their addresses
// with other projects, so the route sends RELEASE_GITHUB_TOKEN when the deploy sets one (a read-only token: 5,000 calls an
// hour of its own, issue 848). Without it, or when GitHub turns it down (expired, revoked, out of calls), it asks
// unauthenticated as before. Either way the CDN keeps each answer 10 minutes.
import { RELEASES, REPO, type ReleaseLine } from '../../lines';

/**
 * `digests`: each listed file's SHA-256 as GitHub's API states it (its `digest`, for files uploaded since GitHub began
 * computing one). Kiln checks the download against it, so Refract, which serves the bytes, can't also vouch for them;
 * a file without one falls back to the release's `.sha256` (release/apk.ts).
 */
export interface LatestRelease { tag: string; version: string; assets: string[]; digests?: Record<string, string> }

/** 1,000 releases: far more than the repo has, and a bound on the calls one answer costs. Past it the route fails rather than guess. */
const MAX_PAGES = 10;

interface GitHubRelease { tag_name: string; draft: boolean; prerelease: boolean; assets: { name: string; digest?: string | null }[] }

const parts = (v: string) => v.split('.').map(Number);
const newer = (a: string, b: string) => {
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i]! > y[i]!;
  return false;
};

/** The newest of `releases` on `line` with at least one file the line serves. */
export function latestOf(line: ReleaseLine, releases: GitHubRelease[]): LatestRelease | null {
  let best: LatestRelease | null = null;
  for (const r of releases) {
    const version = line.tag.exec(r.tag_name)?.[1];
    if (version === undefined || r.draft || r.prerelease) continue;
    const allowed = line.assets(version);
    const files = r.assets.filter((a) => allowed.includes(a.name));
    const assets = files.map((a) => a.name);
    const digests = Object.fromEntries(files.flatMap((a) => {
      const hex = /^sha256:([0-9a-f]{64})$/i.exec(a.digest ?? '')?.[1];
      return hex ? [[a.name, hex.toLowerCase()]] : [];
    }));
    if (assets.length && (!best || newer(version, best.version))) {
      best = { tag: r.tag_name, version, assets, ...(Object.keys(digests).length ? { digests } : {}) };
    }
  }
  return best;
}

/** The token GitHub refused, so later pages and answers of this instance don't send it again. */
let refused: string | undefined;

/**
 * One page of the repo's releases: with RELEASE_GITHUB_TOKEN if there is one, and again without it whenever GitHub
 * turns the token down: 401 (expired or revoked: not sent again by this instance), or 403 and 429 (its quota spent, a
 * secondary limit, a permission). The address's own 60 calls an hour then still answer, as before the token.
 */
async function releasesPage(page: number): Promise<Response> {
  const url = `https://api.github.com/repos/${REPO}/releases?per_page=100&page=${page}`;
  const headers = { accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28' };
  const token = process.env.RELEASE_GITHUB_TOKEN?.trim();
  if (token && token !== refused) {
    const r = await fetch(url, { headers: { ...headers, authorization: `Bearer ${token}` } });
    if (r.status !== 401 && r.status !== 403 && r.status !== 429) return r;
    if (r.status === 401) refused = token;
    console.warn(`/api/release: GitHub turned down RELEASE_GITHUB_TOKEN (${r.status}), asking without it`);
    void r.body?.cancel().catch(() => {});
  }
  return fetch(url, { headers });
}

export async function GET(_req: Request, ctx: RouteContext<'/api/release/[frame]/latest'>): Promise<Response> {
  const line = RELEASES.get((await ctx.params).frame);
  if (!line) return new Response('not a frame with releases', { status: 404 });
  // Every page up to a short one: GitHub doesn't promise its list is in version order.
  const all: GitHubRelease[] = [];
  for (let page = 1; ; page++) {
    if (page > MAX_PAGES) return new Response(`more than ${MAX_PAGES * 100} releases to read`, { status: 502 });
    const upstream = await releasesPage(page);
    if (!upstream.ok) return new Response(`GitHub answered ${upstream.status}`, { status: 502 });
    const list = (await upstream.json()) as GitHubRelease[];
    all.push(...list);
    if (list.length < 100) break;
  }
  const release = latestOf(line, all);
  return Response.json({ release }, { headers: { 'cache-control': 'public, s-maxage=600, stale-while-revalidate=3600' } });
}
