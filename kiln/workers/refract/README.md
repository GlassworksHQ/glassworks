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

Refract is a small Cloudflare Worker at `refract.glassworks.dev`. Kiln needs files from GitHub
releases: other projects' files, such as the lululemon Mirror's community system image, and
Glassworks' own APKs. GitHub's release file host (`release-assets.githubusercontent.com`) sends no
`Access-Control-Allow-Origin`, so a page can't read those bytes (checked 2026-11-15 with an `Origin`
header: the `github.com` 302 has no CORS header and the final 200 has none either). Refract fetches
the file from GitHub and streams it back with CORS headers for Kiln. It serves only the files in
[`allowlist.json`](allowlist.json), and this repo's release lines in [`releases.json`](releases.json).

Every large file Kiln downloads comes through Refract, none through a Vercel function. Until
2026-11-23 Kiln streamed its own APKs (about 100 MB each) through a function on kiln.glassworks.dev,
and that traffic (Vercel's Fast Origin Transfer) got the Vercel team paused. Cloudflare doesn't bill a
Worker's bandwidth.

## What it answers

| Request | Answer |
|---|---|
| `GET /` | JSON: every asset with its Refract URL, source URL, size, SHA-256 and credit, and the release lines |
| `GET` or `HEAD /<id>` | The file, streamed from its GitHub release. HEAD always describes the whole file and ignores `Range` and `If-Range` (RFC 9110 §14.2) |
| `GET` or `HEAD /glassworks/<tag>/<name>` | One of Glassworks' own release files, when [`releases.json`](#glassworks-own-releases) admits it |
| `GET /<id>` with one `Range` | 206 with that range, so Kiln can resume. `If-Range` is honoured |
| `GET /<id>` with a malformed or multi-part `Range` | 416, and GitHub isn't asked. `Content-Range: bytes */<size>` where the allowlist pins the size |
| `GET /<id>` with a range past the end | 416, `Content-Range: bytes */<size>`, as plain text |
| Too many downloads from one address | 429 with `Retry-After: 60` and a one-line reason |
| `OPTIONS /<id>` | The CORS preflight: `GET, HEAD`, the `Range` and `If-Range` headers |
| Any other path | 404, and GitHub is never asked |
| Another method | 405 |

The ids today:

| id | Source | Size |
|---|---|---|
| `glass-panes/mirror-android-2/system.img` | [git-nimrod/glass-panes `mirror-android-2`](https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-2) | 850,905,808 bytes |
| `glass-panes/mirror-android-2/userdata.sparse` | the same release | 154,944,572 bytes |
| `glass-panes/project-key/adbkey` | [the Glass Panes repository](https://github.com/git-nimrod/glass-panes/tree/8ea31141cecc9d3e42ceb72ec08f92bd8ed38801/mirror-app/android/project-key) at commit `8ea31141`: the project adb key its images trust, published on purpose | 1,704 bytes |
| `glass-panes/mirror-android-2/SHA256SUMS` | the same release | 159 bytes |

So Kiln downloads the Mirror's image from
`https://refract.glassworks.dev/glass-panes/mirror-android-2/system.img` (image 1's ids left the
allowlist on 2026-11-16, when Kiln moved to image 2). Kiln's Glass Panes install (#579) downloads
`userdata.sparse` too: Kiln writes both as published, the way back from Glassworks. Each id matches a
file in an image recipe (`frames/<id>/installer`), and the frame's tests check the size and SHA-256
are the same in both ([docs/kiln-images.md](../../../docs/kiln-images.md)).

Each file answer carries:

- `Content-Length`, `Content-Range`, `Accept-Ranges: bytes` and GitHub's `ETag`.
- `Content-Disposition: attachment; filename="<name>"`.
- `Link: <project>; rel="author", <GitHub URL>; rel="canonical"`: who made it and where it comes from.
- `Cache-Control: no-store`.

## Glassworks' own releases

Kiln installs Glassworks from this repo's releases: the S1 app, and the generic Android app and its
legacy build, one APK per ABI. A release can't be pinned before its tag is cut, so these aren't in
the allowlist. [`releases.json`](releases.json) admits them by **release line** instead:

```json
{
  "owner": "GlassworksHQ", "repo": "glassworks", "prefix": "glassworks",
  "lines": [
    { "line": "generic-android", "tag": "^generic-android-v(\\d+\\.\\d+\\.\\d+)$",
      "assets": ["glassworks-generic-android-arm64-v8a-{version}.apk", "glassworks-generic-android-armeabi-v7a-{version}.apk"] }
  ]
}
```

`GET /glassworks/<tag>/<name>` is served when a line's `tag` pattern matches the tag and the name is
one of that line's assets with `{version}` replaced by the tag's version, or such an APK's `.sha256`.
So `https://refract.glassworks.dev/glassworks/v0.4.4/s1-kiosk-0.4.4.apk` is the S1 app 0.4.4, and
`…/glassworks/generic-android-v0.2.0/glassworks-generic-android-arm64-v8a-0.2.0.apk.sha256` its
published hash. Anything else is a 404, and GitHub isn't asked: another repository, another line's
file, a tag no line has, a pre-release tag, a release's other files (the frame bundle), any `/`, `%`
or leading dot in a tag or name. The GitHub URL is always
`https://github.com/GlassworksHQ/glassworks/releases/download/<tag>/<name>`.

**The file is generated, never edited.** `refractReleases()` in
[`kiln/apps/kiln/src/app/api/release/lines.ts`](../../apps/kiln/src/app/api/release/lines.ts) writes it
from the frames' `installer.json` `releases` (`{abi}` expanded from each `frame.json`), the same list
Kiln's `/api/release/<line>/latest` reads. Kiln's test `refract-releases.test.ts` fails when the two
differ. After a change to a frame's `releases`, write it again and deploy Refract:

```sh
REFRACT_RELEASES=write npm test -w @glassworks/kiln-app -- refract-releases
```

What differs from an allowlisted file:

- **No pinned size.** GitHub must send a length, and at most 256 MiB (`MAX_RELEASE_BYTES`; an APK is
  about 100 MB). Kiln keeps the size the first answer gave and refuses a resume with another total.
- **The hash is Kiln's to check.** Kiln reads the APK's `.sha256` through Refract and checks the APK
  against it before it writes anything. Refract checks only that a `.sha256` is at most 1 KiB and
  starts with a SHA-256.
- **An APK is sent as one**: `Content-Type: application/vnd.android.package-archive`, so a tablet's
  browser offers to install it from Kiln's on-device page. A `.sha256` is `text/plain`.
- **The credit** is Glassworks: `Link: <https://github.com/GlassworksHQ/glassworks>; rel="author"`.

## Files from a repository

A few small files aren't release assets but live in a project's repository: Glass Panes' published
adb key, which Kiln uses for one `svc wifi enable` after its first start (#579 follow-up,
maintainer 2026-11-19). An allowlist entry for one has `commit` (all 40 hex digits, never a branch)
and `path` instead of `tag` and `name`:

```json
"glass-panes/project-key/adbkey": {
  "owner": "git-nimrod", "repo": "glass-panes",
  "commit": "8ea31141cecc9d3e42ceb72ec08f92bd8ed38801", "path": "mirror-app/android/project-key/adbkey",
  "size": 1704, "sha256": "89908fe2…38da2d", "credit": { … }
}
```

Refract fetches it from `raw.githubusercontent.com/<owner>/<repo>/<commit>/<path>`, refusing any
redirect. These files are at most 64 KiB, so Refract reads one whole and checks its size and SHA-256
itself. If either differs, it answers 502 and sends nothing. A `Range` is ignored, as HTTP allows:
the file is sent whole. It counts on `SMALL_FILES` for the rate limit, as a release APK's `.sha256` does.

## What it checks

- **The allowlist.** An id must match exactly. Refract never builds a GitHub URL from the request.
- **The redirect.** Refract follows GitHub's redirects itself (`redirect: "manual"`), up to three
  redirects (four requests), and only to `https://` on `github.com`, `release-assets.githubusercontent.com` or
  `objects.githubusercontent.com` on the default port. A redirect anywhere else is a 502, and the
  other host is never fetched. So it can't be turned into an open proxy, even by a changed release.
- **The size.** GitHub's `Content-Length` (for 200) or the total in `Content-Range` (for 206 and
  416) must equal the pinned `size`. If not, the release changed, and Refract answers 502 without
  sending a byte. A release line's file has no pinned size: GitHub must give one, at most 256 MiB.
- **The time.** GitHub must answer with headers within 30 s (`UPSTREAM_TIMEOUT_MS`, and 30 s if
  that's missing or not a number), or it's a 502. The body may take as long as it needs.
- **If-Range.** An ETag is passed to GitHub. If GitHub still sends a range of a newer copy than the
  ETag names, Refract fetches the whole file instead, as RFC 9110 asks. A date in `If-Range` can't
  be checked against GitHub's copy, so Refract sends the whole file.
- **Not the hash.** Refract streams; it never reads the whole file. Kiln checks the SHA-256 itself:
  against the allowlist's for another project's file, against the release's `.sha256` for an APK.

When GitHub answers anything but 200, 206 or 416, or can't be reached, Refract answers 502 with a
one-line reason. Kiln shows that as its own bug, with *Try again* and *Report this* (a prefilled
issue). There is no download-and-select: the person never fetches a file by hand (maintainer,
2026-11-19, #579).

## Rate limits

Three [Workers rate limiters](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/),
keyed on the caller's address (`CF-Connecting-IP`), in `wrangler.json`:

| Binding | Counts | Limit |
|---|---|---|
| `FULL_DOWNLOADS` | GETs that send the file from the first byte: no `Range`, `bytes=0-…`, a suffix range (`bytes=-n`), a date in `If-Range`, or a resume that became the whole file because its `If-Range` ETag was stale | 3 a minute |
| `RESUMES` | GETs that start at a later byte (`bytes=n-`, n > 0): Kiln resuming a dropped download | 8 a minute |
| `SMALL_FILES` | GETs of a file Refract reads whole: a repository file, a release APK's `.sha256` | 20 a minute |

HEAD, the index and preflights aren't counted. Kiln makes one full GET, then resumes with
`Range: bytes=<got>-` if the connection drops, so a normal install uses one full download. The most a
page asks for is two: the Mirror's Glassworks image and its APK, or on `/android` both ABIs' APKs when
the browser didn't say which the device runs. Their `.sha256` files count on `SMALL_FILES`, so they
never use up a download.

The limits are for abuse, not accounting. A resume from byte 1 carries nearly the whole file, so
`RESUMES` is kept low. Cloudflare counts per location and lets extra requests through while its
counts catch up. In production the limit is loose: on 2026-11-15 the first 429 came after about 20
full GETs in about 2 minutes, not after 3. Treat it as an abuse brake only. Two panels
behind one address (a carrier-grade NAT, a shared office) share the limits, so **Kiln must honour
`Retry-After`**: on a 429, wait that many seconds and try again rather than fail the install.

## It stores nothing

- No Cache API, no KV, R2, D1 or Durable Objects. `wrangler.json` binds two variables and the three
  rate limiters, and a test fails if anything else appears.
- The requests to GitHub use `cache: "no-store"`, which the Workers runtime turns into
  `cf.cacheLevel: "bypass"`, so the file never lands in Cloudflare's cache either. A test checks
  this on both hops of GitHub's redirect.
- Every answer says `Cache-Control: no-store`, so browsers and other caches don't keep it.
- Bytes go from GitHub to the person as they arrive. Refract never holds a whole release file. A
  repository file (at most 64 KiB) or a `.sha256` (at most 1 KiB) is held in memory while it's
  checked, then sent and dropped.
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
   "glass-panes/mirror-android-2/system.img": {
     "owner": "git-nimrod", "repo": "glass-panes", "tag": "mirror-android-2", "name": "system.img",
     "size": 850905808, "sha256": "bdb6b844…e35c1",
     "credit": { "name": "git-nimrod", "url": "https://github.com/git-nimrod/glass-panes", "project": "Glass Panes" }
   }
   ```
   `credit.name` is who made it, as they publish; `credit.url` is the project's page; `credit.project`
   is the project's own name.
3. Check the terms: may we send these bytes? Write the answer down next to the frame's research, as
   `frames/lululemon-mirror/research/community-image-terms.md` does for the Mirror.
4. Run the tests, then deploy.

Glassworks' own release lines don't go here: they come from the frames' `installer.json`
([above](#glassworks-own-releases)).

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

[`web-deploy.yml`](../../../.github/workflows/web-deploy.yml) deploys it from `main`, before Kiln, whenever
something it's built from changed: its code, `allowlist.json` or `releases.json`. Its tests and notes don't count. The
job runs `deploy.sh` with the repo secret `CLOUDFLARE_REFRACT_TOKEN` (the permissions below) and the variable
`CLOUDFLARE_ACCOUNT_ID`, then reads the live listing until it has every id in the allowlist. Kiln's job waits for it,
and Kiln doesn't deploy a commit with Refract files newer than Refract's last successful deploy. To deploy it again by hand from GitHub, run the workflow from the Actions tab
with `refract`.

From a computer, from the repo root:

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
- creates the rate limiters. Their `namespace_id`s (1001, 1002, 1003) only need to be unique in the
  account. A deploy that adds one (`SMALL_FILES`, 1003, with Glassworks' release lines) creates it.

`workers_dev` and preview URLs are off, so the Worker answers only at `refract.glassworks.dev`.

With an API token instead of `wrangler login`, put `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` in the environment, never in a file. The token needs:

| Scope | Permission |
|---|---|
| Account (the Glassworks account) | Workers Scripts: Edit |
| Zone `glassworks.dev` | Workers Routes: Edit |
| Zone `glassworks.dev` | DNS: Edit (the custom domain adds a DNS record) |

These come from Cloudflare's permission list and its custom domain docs (read 2026-11-15).

## Cost

About $0. Workers don't bill bandwidth, and a stream passed straight through uses about a
millisecond of CPU per request. The free plan allows 100,000 requests a day and 10 ms of CPU per
request. A Mirror install makes a handful of requests (a HEAD, then the file, maybe a few resumed
ranges); a Glassworks APK, a `.sha256` and one or two APKs. Measured 2026-11-15 after the first deploy: the full 850,905,808-byte image came through
in about 15 s with the right SHA-256, `Cache-Control: no-store` and no `cf-cache-status`, and a
`Range` resume answered 206. The rate limiters work on the Free plan.

## Licence decision

The maintainer decided on 2026-11-15 that Kiln downloads the Mirror's community image through
Refract, knowingly accepting that our Worker transmits the files. The image's release grants no
licence for its Qualcomm and Quectel vendor binaries. Refract streams them and stores nothing,
credits Glass Panes on every answer and at `/`. Kiln kept download-and-select as a fallback until
2026-11-19, when the maintainer dropped it (#579): a download Refract can't serve is Kiln's bug.
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
one does. It covers the allowlist, the release lines (what they admit and refuse, sizes, `.sha256`
checks), the redirect pinning, Range, If-Range and HEAD, the rate limits, CORS, size mismatches,
GitHub failing or stalling, caching and the credit. Kiln's `refract-releases.test.ts` keeps
`releases.json` in step with the frames.
@cloudflare/vitest-pool-workers isn't used because it needs Vitest 4 and the repo is on Vitest 5.
