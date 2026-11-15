<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Refract

Kiln's download window: a few named GitHub release files, streamed into the browser, kept nowhere.

Refract is a small Cloudflare Worker at `refract.glassworks.dev`. Kiln needs some files from other
projects' GitHub releases, such as the lululemon Mirror's community system image. GitHub's release
file host (`release-assets.githubusercontent.com`) sends no `Access-Control-Allow-Origin`, so a page
can't read those bytes (checked 2026-11-15 with an `Origin` header: the `github.com` 302 has no CORS
header and the final 200 has none either). Refract fetches the file from GitHub and streams it back
with CORS headers for Kiln. It serves only the files in [`allowlist.json`](allowlist.json).

## What it answers

| Request | Answer |
|---|---|
| `GET /` | JSON: every asset with its Refract URL, source URL, size, SHA-256 and credit |
| `GET` or `HEAD /<id>` | The file, streamed from its GitHub release. HEAD always describes the whole file and ignores `Range` and `If-Range` (RFC 9110 §14.2) |
| `GET /<id>` with one `Range` | 206 with that range, so Kiln can resume. `If-Range` is honoured |
| `GET /<id>` with a malformed or multi-part `Range` | 416, `Content-Range: bytes */<size>`, and GitHub isn't asked |
| `GET /<id>` with a range past the end | 416, `Content-Range: bytes */<size>`, as plain text |
| Too many downloads from one address | 429 with `Retry-After: 60` and a one-line reason |
| `OPTIONS /<id>` | The CORS preflight: `GET, HEAD`, the `Range` and `If-Range` headers |
| Any other path | 404, and GitHub is never asked |
| Another method | 405 |

The ids today:

| id | Source | Size |
|---|---|---|
| `glass-panes/mirror-android-1/system.img` | [git-nimrod/glass-panes `mirror-android-1`](https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-1) | 850,905,808 bytes |
| `glass-panes/mirror-android-1/SHA256SUMS` | the same release | 159 bytes |

So Kiln downloads the Mirror's image from
`https://refract.glassworks.dev/glass-panes/mirror-android-1/system.img`.

Each file answer carries:

- `Content-Length`, `Content-Range`, `Accept-Ranges: bytes` and GitHub's `ETag`.
- `Content-Disposition: attachment; filename="<name>"`.
- `Link: <project>; rel="author", <GitHub URL>; rel="canonical"`: who made it and where it comes from.
- `Cache-Control: no-store`.

## What it checks

- **The allowlist.** An id must match exactly. Refract never builds a GitHub URL from the request.
- **The redirect.** Refract follows GitHub's redirects itself (`redirect: "manual"`), up to three
  redirects (four requests), and only to `https://` on `github.com`, `release-assets.githubusercontent.com` or
  `objects.githubusercontent.com` on the default port. A redirect anywhere else is a 502, and the
  other host is never fetched. So it can't be turned into an open proxy, even by a changed release.
- **The size.** GitHub's `Content-Length` (for 200) or the total in `Content-Range` (for 206 and
  416) must equal the pinned `size`. If not, the release changed, and Refract answers 502 without
  sending a byte.
- **The time.** GitHub must answer with headers within 30 s (`UPSTREAM_TIMEOUT_MS`, and 30 s if
  that's missing or not a number), or it's a 502. The body may take as long as it needs.
- **If-Range.** An ETag is passed to GitHub. If GitHub still sends a range of a newer copy than the
  ETag names, Refract fetches the whole file instead, as RFC 9110 asks. A date in `If-Range` can't
  be checked against GitHub's copy, so Refract sends the whole file.
- **Not the hash.** Refract streams; it never reads the whole file. Kiln checks the SHA-256 itself.

When GitHub answers anything but 200, 206 or 416, or can't be reached, Refract answers 502 with a
one-line reason. Kiln then falls back to download-and-select: the person downloads the file from
the release and picks it.

## Rate limits

Two [Workers rate limiters](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/),
keyed on the caller's address (`CF-Connecting-IP`), in `wrangler.json`:

| Binding | Counts | Limit |
|---|---|---|
| `FULL_DOWNLOADS` | GETs that send the file from the first byte: no `Range`, `bytes=0-…`, a suffix range (`bytes=-n`), a date in `If-Range`, or a resume that became the whole file because its `If-Range` ETag was stale | 3 a minute |
| `RESUMES` | GETs that start at a later byte (`bytes=n-`, n > 0): Kiln resuming a dropped download | 8 a minute |

HEAD, the index and preflights aren't counted. Kiln makes one full GET, then resumes with
`Range: bytes=<got>-` if the connection drops, so a normal install uses one full download.

The limits are for abuse, not accounting. A resume from byte 1 carries nearly the whole file, so
`RESUMES` is kept low. Cloudflare counts per location and lets extra requests through while its
counts catch up. In production the limit is loose: on 2026-11-15 the first 429 came after about 20
full GETs in about 2 minutes, not after 3. Treat it as an abuse brake only. Two panels
behind one address (a carrier-grade NAT, a shared office) share the limits, so **Kiln must honour
`Retry-After`**: on a 429, wait that many seconds and try again rather than fail the install.

## It stores nothing

- No Cache API, no KV, R2, D1 or Durable Objects. `wrangler.json` binds two variables and the two
  rate limiters, and a test fails if anything else appears.
- The requests to GitHub use `cache: "no-store"`, which the Workers runtime turns into
  `cf.cacheLevel: "bypass"`, so the file never lands in Cloudflare's cache either. A test checks
  this on both hops of GitHub's redirect.
- Every answer says `Cache-Control: no-store`, so browsers and other caches don't keep it.
- Bytes go from GitHub to the person as they arrive. Refract never holds a whole file.
- It logs nothing itself. Cloudflare still counts requests in its analytics, as for any Worker. The
  rate limiters keep a per-address counter for a minute.

## CORS

`Access-Control-Allow-Origin` goes only to the origins in `ALLOWED_ORIGINS` (`wrangler.json`):
`https://kiln.glassworks.dev` and Kiln's dev servers, `http://localhost:5174` (Next.js) and
`http://localhost:5173` (the Vite page). Other origins get the same answer without CORS headers, so
their pages can't read it. `Expose-Headers` lists `Content-Length, Content-Range, Accept-Ranges,
ETag, Link, Retry-After`.

## Adding an asset

1. Read the release's size and SHA-256 from GitHub (or its own `SHA256SUMS`):
   ```sh
   gh api repos/<owner>/<repo>/releases/tags/<tag> --jq '.assets[] | "\(.name) \(.size) \(.digest)"'
   ```
2. Add an entry to `allowlist.json`. The key is the id, which becomes the URL path. Use
   `<project>/<tag>/<name>`:
   ```json
   "glass-panes/mirror-android-1/system.img": {
     "owner": "git-nimrod", "repo": "glass-panes", "tag": "mirror-android-1", "name": "system.img",
     "size": 850905808, "sha256": "d13dd330…83ed5",
     "credit": { "name": "git-nimrod", "url": "https://github.com/git-nimrod/glass-panes", "project": "Glass Panes" }
   }
   ```
   `credit.name` is who made it, as they publish; `credit.url` is the project's page; `credit.project`
   is the project's own name.
3. Check the terms: may we send these bytes? Write the answer down next to the frame's research, as
   `frames/lululemon-mirror/research/community-image-terms.md` does for the Mirror.
4. Run the tests, then deploy.

## Its own package

Refract isn't one of the repo root's npm workspaces. It has its own `package.json` and
`package-lock.json`, so wrangler, Miniflare and workerd (about 260 MB) stay out of the root
`npm ci`, and its own CI job (`refract` in `pr.yml`) runs only when `kiln/workers/` changes. Write
its lockfile with npm 11.19.1, the npm CI uses: older npm leaves esbuild's platform packages out.

```sh
npm ci --prefix kiln/workers/refract
npm run typecheck --prefix kiln/workers/refract
npm test --prefix kiln/workers/refract
```

wrangler is pinned to 4.116.0 with Miniflare 4.20260730.0, the newest pair where Miniflare is a
stable release (later wranglers use the Miniflare 5 betas). `compatibility_date` stays at or before
that workerd's date (2026-07-30), so the tests run the runtime production runs.

## Deploying

The root thread deploys, never a PR. From the repo root:

```sh
npm ci --prefix kiln/workers/refract
kiln/workers/refract/node_modules/.bin/wrangler login   # once on this machine (OAuth), or set the two variables below
kiln/workers/refract/deploy.sh                          # typecheck, tests, then wrangler deploy
kiln/workers/refract/deploy.sh --dry-run --outdir /tmp/refract   # builds only, needs no login
```

`deploy.sh` uses the wrangler in its own `node_modules` and refuses any version but the pinned
one. It turns off wrangler's telemetry. The first deploy:

- creates the custom domain `refract.glassworks.dev`: Cloudflare adds the DNS record and the
  certificate. The hostname must not have a CNAME record already (it had no records on 2026-11-15).
- creates the two rate limiters. Their `namespace_id`s (1001, 1002) only need to be unique in the
  account.

`workers_dev` and preview URLs are off, so the Worker answers only at `refract.glassworks.dev`.

With an API token instead of `wrangler login`, put `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` in the environment, never in a file. The token needs:

| Scope | Permission |
|---|---|
| Account (the Glassworks account) | Workers Scripts: Edit |
| Zone `glassworks.dev` | Workers Routes: Edit |
| Zone `glassworks.dev` | DNS: Edit (the custom domain adds a DNS record) |

These come from Cloudflare's permission list and its custom domain docs (read 2026-11-15). They
have not been tried yet.

## Cost

About $0. Workers don't bill bandwidth, and a stream passed straight through uses about a
millisecond of CPU per request. The free plan allows 100,000 requests a day and 10 ms of CPU per
request. A Mirror install makes a handful of requests (a HEAD, then the file, maybe a few resumed
ranges). Measured 2026-11-15 after the first deploy: the full 850,905,808-byte image came through
in about 15 s with the right SHA-256, `Cache-Control: no-store` and no `cf-cache-status`, and a
`Range` resume answered 206. The rate limiters work on the Free plan.

## Licence decision

The maintainer decided on 2026-11-15 that Kiln downloads the Mirror's community image through
Refract, knowingly accepting that our Worker transmits the files. The image's release grants no
licence for its Qualcomm and Quectel vendor binaries. Refract streams them and stores nothing,
credits Glass Panes on every answer and at `/`, and Kiln keeps download-and-select as the fallback.
The reasoning and the earlier decision are in
`frames/lululemon-mirror/research/community-image-terms.md`.

Refract's own code is MIT, like the rest of Kiln. It ships no third-party code: wrangler, Miniflare
and esbuild are only used to build, test and deploy it.

`npm audit` reports 4 high findings (2026-11-15), all in dev-only tooling: wrangler 4.116.0 →
Miniflare 4.20260730.0 → sharp (libheif, librsvg) and undici. None of it is in the deployed
Worker, which is our own 8 KiB. They go away with a wrangler on the Miniflare 5 line once one is
stable.

## Tests

`test/proxy.test.ts` runs the Worker in workerd (Miniflare) with `wrangler.json`'s settings and rate
limiters, in front of a fake GitHub that redirects and serves ranges and `If-Range` the way the real
one does. It covers the allowlist, the redirect pinning, Range, If-Range and HEAD, the rate limits,
CORS, size mismatches, GitHub failing or stalling, caching and the credit.
@cloudflare/vitest-pool-workers isn't used because it needs Vitest 4 and the repo is on Vitest 5.
