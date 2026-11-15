<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

# Agent guide: glassworks.dev (`site/`)

The Glassworks project site: the landing page, `/frames`, `/panes`, `/brand` and `/trademarks`. The
repo-wide rules in the root [AGENTS.md](../AGENTS.md) apply here too; these are the site's own.

## Commands

From the repo root, after one `npm install` there (the site is an npm workspace; there is no
install inside `site/`):

```sh
npm run dev -w site          # http://localhost:3000
npm run lint -w site
npm run typecheck -w site
npm run build -w site        # every route is static; this is what Vercel runs
npm run check:seo -w site    # after a build: every page has a title, description, canonical and preview
npm run brand -w site        # regenerate public/brand/ after a frame.json or libraries.json change
```

CI's `site` job runs lint, typecheck and build when `site/`, `packages/`, `frames/`, `docs/`,
`tools/perf/grade.ts`, `tools/perf/page/rubric.json` or the root package files change.

## Rules

1. **The brand URLs never change.** READMEs here and elsewhere link to
   `https://www.glassworks.dev/brand/<file>-on-{dark,light}.svg` and
   `/frames/<id>/clarity-on-{dark,light}.svg`. Add files; don't rename or remove one that a README
   may use. `tools/check-brand-images.py` checks this repo's Markdown against the live site.
2. **No data is written by hand in `site/`.** Frames come from `../frames/<id>/design/frame.json`,
   panes from `@glassworks/frame-card` (each pane's `pane.json`), Clarity's grade from
   `../frames/<id>/clarity/clarity.json` with `../tools/perf/grade.ts` and its rubric. A data change
   and the page that shows it go in one PR. The site's own words for that data are in
   `src/lib/frameText.ts`.
3. **Brand files are generated.** `public/brand/` comes from `scripts/brand/` (`npm run brand`).
   Don't draw or edit an SVG by hand. The build fails when a frame's banner isn't what its
   `frame.json` draws.
4. **Don't deploy, and don't change Vercel settings.** Production is deployed by the maintainer.
   `vercel.json` keeps Vercel's Git builds off.
5. **Plain CSS with the tokens in `src/app/globals.css`**, CSS modules per page and component, no UI
   kit. A new web-only layout needs a mockup the maintainer approves first.
6. **Every page in the sitemap uses `pageMetadata`** from `src/lib/seo.ts` for its title, description, canonical
   and link preview, and is listed by a source in `src/lib/routes.ts` (the sitemap). Docs and blog
   pages add their source there and pass `type: "article"`. `check:seo` fails the build otherwise.
   Titles and descriptions are short and plain: what the page is, no filler. The 404
   (`app/not-found.tsx`) has no address of its own, so it sets a plain title and no canonical.
7. **ESLint runs on TypeScript 6's API.** typescript-eslint can't use the root's TypeScript 7, so
   `site/package.json` has `typescript@6`, nested in `site/node_modules`, and `eslint.config.mjs`
   points typescript-eslint at it. Don't use the `@typescript/typescript6` alias: its dependency
   hoists to the root and takes every workspace's `tsc`. A bare `tsc` inside `site/` is 6; the
   `typecheck` script runs the root's TypeScript 7 by path. Don't add a script that calls bare `tsc`.
