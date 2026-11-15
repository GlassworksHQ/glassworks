# glassworks.dev

The Glassworks project site: the landing page, frames, panes, the docs and `/brand`. Kiln
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

Opening `npm run dev` from another machine (a tailnet name, a LAN IP)? Next.js blocks its dev scripts
for unknown hosts, so the page renders but nothing interactive works. List the host in a git-ignored
`.env.local` and restart the dev server:

```sh
DEV_ORIGINS=my-mac.tailnet.example,192.168.1.20
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
src/lib/release.ts     the latest app release, from ../CHANGELOG.md
src/lib/docs/          the docs: sources.ts (repo file → URL and nav group), render.ts (Markdown → HTML, GitHub's rules), index.ts (renders all, checks every link)
src/app/docs/          /docs, each doc page, the panes and libraries indexes; the docs' own sidebar
src/components/search/ the ⌘K search over the Pagefind index that `npm run build` writes
src/components/clarity/  the Clarity seal, the frame page's panel, the catalog line
scripts/brand/banner.mjs  the part banner every banner shares, the mark, each part's light
scripts/brand/frame.mjs   a frame's README banner, banner-frame-<id>, drawn from its frame.json
src/lib/frameBanners.ts   checks at build time that public/brand/ has each frame's current banner
src/app/frames/[slug]/render.webp/route.ts  each frame's render (frame.json `render`), served from ../frames for the card and page
scripts/brand/clarity.mjs the Clarity README badge, served per frame at /frames/<id>/clarity-on-*.svg
tests/clarity/         made-up Clarity summaries for working on the site (CLARITY_FIXTURES)
src/lib/libraries.json the standalone libraries, one banner each
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
PRs get no preview. Until 2026-11-15 the site was its own repo, GlassworksHQ/glassworks.dev, and a
workflow there called a Vercel deploy hook on a push to `main`; that workflow did not move here.

### Deploying (the maintainer)

Build on your machine from a full clone, then upload the build. A full clone, because the docs' "Updated"
dates come from git history: a build without it (a shallow clone, or a plain `vercel deploy`, which
uploads files without `.git`) shows no dates. `vercel build` runs `vercel.json`'s install and build,
Pagefind included, as Vercel would.

```sh
git clone https://github.com/GlassworksHQ/glassworks.git glassworks-deploy   # no --depth
cd glassworks-deploy
git checkout origin/main                  # or the commit to deploy
npx vercel link                           # once per clone: pick the site's project (Root Directory: site)
npx vercel pull --yes --environment=production
npx vercel build --prod
npx vercel deploy --prebuilt --prod
```

A preview: `npx vercel pull --yes --environment=preview`, then
`npx vercel build` and `npx vercel deploy --prebuilt`, without `--prod`. Run every command from the
clone's root, where `vercel link` put `.vercel/`.
