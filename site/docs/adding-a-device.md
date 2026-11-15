# Adding a device to the catalog

Each panel on [glassworks.dev/frames](https://www.glassworks.dev/frames) is a **frame** in this
repo's [`frames/`](../../frames/README.md). The site has no device list of its own: the landing page, the catalog, both comparison tables, the pane
pages and each frame's page (`/frames/<id>`) are built from the frames' `design/frame.json` files,
and the cards and 3D models are drawn by `@glassworks/frame-card` ([`packages/frame-card`](../../packages/frame-card/README.md)),
the same card as Kiln's picker. The site reads both at build time, so the frame and its page land in
one PR.

## 1. Add the frame

Add `frames/<id>/design/frame.json`. Its fields (name, status, specs, panes, the nine install
ratings, the 3D model in millimetres) are in [`frames/README.md`](../../frames/README.md).
Use `unknown` for anything nobody has checked on real hardware: the site shows it as "Not checked".
Set `unverified` when any of it comes from writeups rather than a real device.

Say where each fact comes from: your own device, the vendor's spec sheet, or a writeup (link it).
A listing requires the **Built on Glassworks** badge in your frame's README and on its About screen;
see [glassworks.dev/brand#badge](https://www.glassworks.dev/brand#badge).

Give it a `tagline` (one line, at most 50 characters) and, if its name starts with the maker's,
a `brand` (`Aqara` in `Aqara S1 Plus`). They draw the frame's README banner, `banner-frame-<id>`
(step 2). A frame without a tagline gets no banner.

Its `render`, a `.webp` of a dashboard next to `frame.json` (made by
`tools/showcase/render.mjs`), is what its card and page show on the device's screen; without one,
they show an example dashboard. The site serves it at `/frames/<id>/render.webp`.

Its Clarity grade comes from `frames/<id>/clarity/clarity.json` once the frame has been measured
([docs/perf.md](../../docs/perf.md#the-summary-glassworksperf-summary3)). Until then the site says "Not measured yet".

## 2. Draw its banner

Redraw the brand files: every frame with a tagline gets `public/brand/banner-frame-<id>-on-*.svg`,
its device drawn from `model` (`scripts/brand/frame.mjs`). Commit them with the frame; the
build fails if a frame's banner isn't the one its `frame.json` draws, or if its name or tagline
doesn't fit.

```sh
npm run brand -w site
git add site/public/brand
```

## 3. Check it

From the repo root, after `npm install` there:

```sh
npm run dev -w site     # then open /frames and /frames/<id>
npm run lint -w site
npm run build -w site   # the new frame's page must be listed as static, and its banner must match
```

Look at the frame's page in light and dark mode and at phone width (about 375 px). Nothing should
scroll sideways.

The site's own words for the data (the rating names and values, pane support, the difficulty
legend) are in [`src/lib/frameText.ts`](../src/lib/frameText.ts). A new rating value needs its words
there.
