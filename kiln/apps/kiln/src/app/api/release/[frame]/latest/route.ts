// /api/release/<line>/latest: a release line's newest published release and the files of it Kiln downloads, or
// `{ release: null }` before its first: a small JSON answer, the only release route left on Vercel. The files
// themselves come from Refract (refract.glassworks.dev/glassworks/<tag>/<name>, kiln-core releaseFileUrl).
// GitHub's REST API allows 60 unauthenticated calls an hour per address, so the CDN keeps each answer 10 minutes.
import { RELEASES, REPO, type ReleaseLine } from '../../lines';

export interface LatestRelease { tag: string; version: string; assets: string[] }

/** 1,000 releases: far more than the repo has, and a bound on the calls one answer costs. Past it the route fails rather than guess. */
const MAX_PAGES = 10;

interface GitHubRelease { tag_name: string; draft: boolean; prerelease: boolean; assets: { name: string }[] }

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
    const assets = r.assets.map((a) => a.name).filter((n) => allowed.includes(n));
    if (assets.length && (!best || newer(version, best.version))) best = { tag: r.tag_name, version, assets };
  }
  return best;
}

export async function GET(_req: Request, ctx: RouteContext<'/api/release/[frame]/latest'>): Promise<Response> {
  const line = RELEASES.get((await ctx.params).frame);
  if (!line) return new Response('not a frame with releases', { status: 404 });
  // Every page up to a short one: GitHub doesn't promise its list is in version order.
  const all: GitHubRelease[] = [];
  for (let page = 1; ; page++) {
    if (page > MAX_PAGES) return new Response(`more than ${MAX_PAGES * 100} releases to read`, { status: 502 });
    const upstream = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100&page=${page}`, {
      headers: { accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28' },
    });
    if (!upstream.ok) return new Response(`GitHub answered ${upstream.status}`, { status: 502 });
    const list = (await upstream.json()) as GitHubRelease[];
    all.push(...list);
    if (list.length < 100) break;
  }
  const release = latestOf(line, all);
  return Response.json({ release }, { headers: { 'cache-control': 'public, s-maxage=600, stale-while-revalidate=3600' } });
}
