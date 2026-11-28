# glassworks.dev

The Glassworks project site: the landing page, frames, panes, the docs, the blog and `/brand`. Kiln
(the installer) is a separate app at `kiln.glassworks.dev`. See `planning/site.md` for what goes
where.

## Develop

The site is an npm workspace of this repo. Install once, from the repo root, and run the scripts
with `-w site` (or `npm run <script>` inside `site/`):

```sh
npm install                       # from the repo root; never inside site/
npm run dev -w site               # http://localhost:3000
npm run build -w site             # every route is static
npm run lint -w site
npm run typecheck -w site
npm run brand -w site             # regenerate the mark files, badges and banners in public/brand/
npm run brand:raster -w site      # re-render the PNGs (app icon, GitHub avatar, social images) with headless Chrome
```

### The libraries' API reference

`npm run build` and `npm run dev` first run `scripts/api-docs.mjs`. For each library in `libs/` that is
published to Maven Central, it reads the latest release from `maven-metadata.xml`, downloads its
`-javadoc.jar` (Dokka HTML), checks it against Central's `.sha256` or `.sha1`, and unpacks it to
`public/docs/libraries/<name>/api/`. For each npm package (the ones `tools/release/npm-package.mjs`
publishes), it reads the version npm tags `latest`, downloads that tarball, checks it against npm's
`sha512` integrity, and runs TypeDoc on the type declarations it ships, one module per entry of its
`exports`, into the same folder. The site serves those pages as they are, with
`public/api-reference.css` and a breadcrumb back to the docs added to each. The library's docs page
and the sidebar link it with its version. It sits under the library's own page (`/docs/libraries/<name>`)
rather than at `/api/`, which reads like a server endpoint.

| The registry says | The library's page shows |
|---|---|
| a release | "API reference: <name> <version>", served here, and in the sitemap |
| 404 (not released yet) | "API reference: after the first release" |
| nothing (no network) | the cached jar or tarball if there is one, else a link to javadoc.io or jsdocs.io |
| a file whose checksum doesn't match | nothing, because the build fails |

Jars and tarballs are cached in `.api-docs/cache/`, one folder per repository. `MAVEN_REPO=<url>` reads another
Maven repository, e.g. a release in a local repository before Central has it; `NPM_REGISTRY=<url>` another npm
registry.

Opening `npm run dev` from another machine (a tailnet name, a LAN IP)? Next.js blocks its dev scripts
for unknown hosts, so the page renders but nothing interactive works. List the host in a git-ignored
`.env.local` and restart the dev server:

```sh
DEV_ORIGINS=my-mac.tailnet.example,192.0.2.20
```

Next.js 16 (App Router) with plain CSS: design tokens in `src/app/globals.css`, CSS modules per
page and component. There's no UI library on purpose; see "Design system" below.

## Layout

```
src/app/
  globals.css          design tokens + the .glass surface (lift into @glassworks/brand later)
  layout.tsx           Inter + JetBrains Mono, the sidebar shell, footer, shared SVG gradients, the theme pin
  page.tsx             landing page
  brand/page.tsx       design language and downloads
  icon.svg             favicon (small cut, follows OS theme)
  apple-icon.png       180px, full-bleed
src/components/
  Mark.tsx             the mark (three plates, the top one lit): variant lit | ember | mono, cut full | small
  Glyphs.tsx           Frame, Pane, Kiln and pane icons on a 24 grid
  GlassTile.tsx        the 2:1 night tile with the light sweep
  Manage.tsx           the panel's Manage page as site parts: cards, list rows, tiles, chips
  FrameRows.tsx, PaneRows.tsx  frames and panes as Manage rows
  shell/               the sidebar (desktop), the top bar and menu drawer (phones), the theme toggle
src/lib/site.ts        links and panes
src/lib/frames.ts      the frame catalog: every ../frames/<id>/design/frame.json in this repo
src/lib/frameText.ts   the site's words for that data: install ratings, pane support
src/lib/clarity.ts     each frame's Clarity grade, worked out from its clarity.json at build time
src/lib/release.ts     the latest app release, from ../CHANGELOG.md, at its tag on GitHub
src/lib/docs/          the docs: sources.ts (repo file → URL and nav group), render.ts (Markdown → HTML, GitHub's rules), index.ts (renders all, checks every link)
                       research.ts: the research notes by frame and topic (front matter words in notes.ts, summaries from docs/research/README.md)
src/app/docs/          /docs, each doc page, the panes and libraries indexes; the docs' own sidebar
src/components/search/ the ⌘K search over the Pagefind index that `npm run build` writes
blog/<YYYY-MM-DD>-<slug>/index.md  a post (front matter: title, date, author, summary, draft); src/lib/blog/posts.ts reads them
src/components/clarity/  the Clarity seal, the frame page's panel, the catalog line
scripts/brand/banner.mjs  the part banner every banner shares, the mark, each part's light
scripts/brand/frame.mjs   a frame's README banner, banner-frame-<id>, drawn from its frame.json
src/lib/frameBanners.ts   checks at build time that public/brand/ has each frame's current banner
src/app/frames/[slug]/render.webp/route.ts  each frame's render (frame.json `render`), served from ../frames for the card and page
scripts/brand/clarity.mjs the Clarity README badge, served per frame at /frames/<id>/clarity-on-*.svg
tests/clarity/         made-up Clarity summaries for working on the site (CLARITY_FIXTURES)
src/lib/libraries.json the standalone libraries, one banner each
src/lib/apiDocs.ts     each library's API reference, as scripts/api-docs.mjs found it on Maven Central or npm
scripts/brand/         `npm run brand` and `npm run brand:raster`: every brand file, from src/lib/markGeometry.ts
public/brand/          downloadable SVGs
```

## Adding a device

Frames live in [`frames/`](../frames/README.md) in this repo: add its `frames/<id>/design/frame.json`,
run `npm run brand -w site`, and the frame's page comes in the same PR. See
[docs/adding-a-device.md](docs/adding-a-device.md).

## Clarity

Clarity is the Glassworks wall-panel performance grade, A+ to E ([docs/perf.md](../docs/perf.md)). A
frame stores its measurements only, in `frames/<id>/clarity/clarity.json` (Clarity's summary,
`glassworks.perf-summary/3`, published with its report); the site works the letter out at build time
with this repo's `tools/perf/grade.ts` and `rubric.json`, at the same commit. No
summary reads "Not measured yet"; a summary from another Clarity version or measuring protocol reads
"Re-measure". The report's notes show only when they judge by the pinned rules. Each frame also gets a README badge at `/frames/<id>/clarity-on-dark.svg` and
`-on-light.svg`, worked out the same way, so a README never stores the letter either. The frame page
has the snippet to copy.

To see the graded states before a frame has a summary: `CLARITY_FIXTURES=tests/clarity/sample npm run dev`
(see [tests/clarity/README.md](tests/clarity/README.md)). The samples are ignored on Vercel.

## Design system

The tokens, `<Mark>` and the glyphs are written so they can move unchanged into a shared
`@glassworks/brand` package (Kiln's copy, `kiln/packages/brand`, is synced from here). Until then
`site/` is their home.
Use unstyled primitives (Radix or Base UI) for anything that needs real accessibility work, styled
with the tokens, rather than a styled kit.

## Deploy

Static output on Vercel, with `glassworks.dev` pointed at it from Cloudflare DNS. The project's Root
Directory is `site`, with "Include files outside the root directory" on (the build reads the root's
`package.json` and lockfile, `packages/`, `frames/` and `tools/perf`). `vercel.json` installs and
builds from the repo root, the npm workspace root: `npm ci`, then `npm run build -w site`. Kiln's
project works the same way (`kiln/apps/kiln/vercel.json`).

Vercel does not build on pushes (`git.deploymentEnabled: false` in `vercel.json`), so branches and
PRs get no preview.

### Deploying

Production deploys from GitHub Actions: [`web-deploy.yml`](../.github/workflows/web-deploy.yml) runs on
every push to `main`, every 6 hours, and by hand from the Actions tab (**Web deploy** → Run workflow). On a
push or a scheduled run it deploys the site when something the site reads (the same paths as CI's site job)
changed since its last successful deploy, so a deploy that failed or was cancelled, for example at Vercel's
daily deploy limit, goes out on the next push or within 6 hours. When nothing changed, nothing deploys.
`tools/ci/web_deploy.py` decides, from the commit each successful deploy records as a GitHub deployment in
the `glassworks.dev` environment, and each run's summary says what it deployed and why. It builds the site on the runner from a full clone and uploads
the build, as below, with a Vercel token in the `VERCEL_SITE_TOKEN` secret. Don't let Vercel build it: on
Vercel's machines the build fails at the Pagefind step.

To deploy by hand, build on your machine from a full clone, then upload the build. A full clone, because the docs' "Updated"
dates come from git history: a build without it (a shallow clone, or a plain `vercel deploy`, which
uploads files without `.git`) shows no dates. `vercel build` runs `vercel.json`'s install and build,
Pagefind included, as Vercel would.

```sh
git clone https://github.com/GlassworksHQ/glassworks.git glassworks-deploy   # no --depth
cd glassworks-deploy
git checkout origin/main                  # or the commit to deploy
npx vercel@65.14.0 link                  # once per clone: pick the site's project (Root Directory: site)
npx vercel@65.14.0 pull --yes --environment=production
npx vercel@65.14.0 build --prod
npx vercel@65.14.0 deploy --prebuilt --prod --archive=tgz
```

`--archive=tgz` uploads the build as one archive. File by file, the prebuilt output (about 2,400 files) hits the free plan's upload limit.

A preview, which also shows draft blog posts: `npx vercel@65.14.0 pull --yes --environment=preview`, then
`npx vercel@65.14.0 build` and `npx vercel@65.14.0 deploy --prebuilt --archive=tgz`, without `--prod`. Run every command from the
clone's root, where `vercel link` put `.vercel/`.
