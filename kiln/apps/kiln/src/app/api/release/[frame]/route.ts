// /api/release/<frame>?tag=…&name=…: streams one of a frame's release files from this repo's GitHub
// Releases through Kiln's own domain. Browsers can't fetch GitHub release downloads directly (no CORS
// headers), and an APK (~100 MB) is too big to ship as a static file. Only the frames below pass, and
// for each only its own tags and asset names; anything else is refused before GitHub is asked. The
// installer checks the SHA-256 published with the release.
const REPO = 'GlassworksHQ/glassworks';

/** A frame's release line: its tags (the version is the first group), and the asset names a tag may serve. */
interface ReleaseLine {
  tag: RegExp;
  assets: (version: string) => readonly string[];
}

// generic-android is not here yet: its release line (`generic-android-vX.Y.Z`,
// `glassworks-generic-android-<abi>-X.Y.Z.apk`) is in open PR 365, and no Kiln wizard installs it.
const RELEASES = new Map<string, ReleaseLine>([
  // Glassworks: `vX.Y.Z` (every release so far) or `aqara-s1-plus-vX.Y.Z`, and the S1's armeabi-v7a APK.
  ['aqara-s1-plus', { tag: /^(?:aqara-s1-plus-)?v(\d+\.\d+\.\d+)$/, assets: (v) => [`s1-kiosk-${v}.apk`] }],
]);

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
