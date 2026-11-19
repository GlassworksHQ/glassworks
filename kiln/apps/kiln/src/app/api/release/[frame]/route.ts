// /api/release/<line>?tag=…&name=…: streams one of a release line's files from this repo's GitHub Releases
// through Kiln's own domain. Browsers can't fetch GitHub release downloads directly (no CORS headers), and an
// APK (~100 MB) is too big to ship as a static file. Only the lines in ../lines.ts pass, and for each only its
// own tags and asset names; anything else is refused before GitHub is asked. The installer checks the SHA-256
// published with the release.
import { RELEASES, REPO } from '../lines';

export async function GET(req: Request, ctx: RouteContext<'/api/release/[frame]'>): Promise<Response> {
  const line = RELEASES.get((await ctx.params).frame);
  if (!line) return new Response('not a frame with releases', { status: 404 });
  const q = new URL(req.url).searchParams;
  const tag = q.get('tag') ?? '';
  const name = q.get('name') ?? '';
  const version = line.tag.exec(tag)?.[1];
  if (version === undefined || !line.assets(version).includes(name)) return new Response('not a release asset of this frame', { status: 400 });
  const upstream = await fetch(`https://github.com/${REPO}/releases/download/${tag}/${name}`, { redirect: 'follow' });
  if (!upstream.ok || !upstream.body) return new Response(`GitHub answered ${upstream.status}`, { status: 502 });
  const headers = new Headers({
    'content-type': 'application/vnd.android.package-archive',
    'cache-control': 'public, max-age=31536000, immutable', // a tag's asset never changes
  });
  const len = upstream.headers.get('content-length');
  if (len) headers.set('content-length', len);
  return new Response(upstream.body, { status: 200, headers });
}
