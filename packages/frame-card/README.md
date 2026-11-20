<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-frame-card-on-dark.svg">
      <img alt="frame-card, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-frame-card-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">frame-card</h1>

<p align="center">
  <b>The frame card and 3D renderer, for Kiln and the site.</b><br>
  <code>@glassworks/frame-card</code>: one copy of the device card, shared by Kiln and glassworks.dev.
</p>

<p align="center">
  <a href="../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

A frame's card and its 3D device mockup, drawn from the frame's own data
([`frames/<id>/design/frame.json`](../../frames/README.md)). Kiln's picker uses it, and glassworks.dev is
to use the same copy in place of its own. MIT licensed, like the rest of this repo.

It came from glassworks.dev at `fbfd364` (the table in [frames/README.md](../../frames/README.md#what-came-from-glassworksdev)).
Kiln was the first to use it, and #114 moved it here, so there is one copy.

## What's in it

| Export | What |
|---|---|
| `FrameCardBody` | The card's contents: the model that rises and floats on hover, the name and stage, the summary, the legacy line of a frame that has one (`legacy`), the three install tags. Props: `f`, the frame; `idPrefix`, ids for the summary, the legacy line and the tags (for `aria-describedby`); `as`, `h2`, `h3` or `h4` to make the name a heading; `expected`, which tells screen readers the install tags are expected, not tested. `pill: false` leaves out the stage pill (Kiln's picker shows none for a `stable` frame). `expected` is on by default for a frame that doesn't ship yet (`!ships(status)`). It is hidden text at the start of the tags, so it is read in a link's name and in Kiln's `aria-describedby`; an `aria-label` there would hide the tags themselves. Without `as` the text is all spans, so it fits inside a button. Use `as` only in a link or another block |
| `frameCardClass`, `useTilt()` | For the card's outer element, which is the caller's (Kiln: a radio item; the site: a link). The class lifts the card on hover and doesn't underline a link's text; the hook leans the model toward a mouse pointer |
| `StatusPill` | The stage pill, as on the status badges |
| `DeviceModel` | The 3D mockup alone: the body's sides as SVG behind a CSS-3D front glass. Props: `id`, `model`, `label`, `aspect`, the stage's width / height, and `screen`. `aspect` is 2 (2:1, as on the cards) by default; a portrait frame's own page can ask for a taller stage, e.g. `0.8`. `screen` is the URL of the frame's render for the glass; without it, an example dashboard. A model with a dock part draws the dock on its own and lifts the body off it, up and a little forward, while the model is hovered with a mouse, or while an ancestor sets `--undock: 1` (the card does on hover and keyboard focus). Nothing moves with reduced motion |
| `geometry()` | The projection `DeviceModel` draws, as plain data. Pure, so tests can call it |
| `PANES`, `PANE_IDS`, `PaneInfo`, `paneById()`, `PANE_ALIASES`, `isPaneAlias()` | The pane catalog: every pane in the app, from its `pane.json`, with its `icon` (the SVG's markup). `paneById('base')` finds the wall base by its old id. `isPaneAlias()` says whether an id is an old alias, by own key only (not `constructor` or `toString`) |
| `paneSupport()`, `framePanes()`, `PaneSupport`, `CAPABILITIES`, `capabilityText()`, `isCapability()` | How a frame runs a pane: its own rating, or worked out from the pane's `requires` and the frame's `provides`, with the reason ([frames/README.md](../../frames/README.md#the-panes-a-frame-runs)) Both take an optional `frameName(id)`, so a frame pane's reason can name its frame. `isCapability()` says whether a word is a capability, by own key only |
| `FrameDesign`, `FrameModel`, `Stage`, `Support`, `RatingKey`, `cardTags()`, `STAGE_TEXT`, `ships()`, `RATING_KEYS` | The `frame.json` types and the card's words for them. `ships()` takes a frame's `Stage` or a pane's `Support`: true for alpha, beta and stable, false for the rest, including `depends`, `unknown` and `no` |
| `FrameCredit`, `creditProblems()`, `cardCredit()` | A frame's `credits`: the outside work it stands on ([frames/README.md](../../frames/README.md#credits)). `creditProblems()` lists what's wrong with them (a missing link, an empty `uses`, a repeated id), one line each; Kiln's tests and the site's build run it. `cardCredit()` is the card's line for the credit marked `card`, "Built on Glass Panes by git-nimrod"; `FrameCardBody` shows it under the summary as plain text |
| `FrameKind`, `FRAME_KINDS`, `KIND_TEXT`, `kindProblems()` | A frame's `kind`: full frame, device frame or tested device, and a tested device's `base` ([frames/README.md](../../frames/README.md#three-kinds-of-device)). `kindProblems()` lists what's wrong, one line each; Kiln's tests and the site's build run it. `FrameCardBody` shows `KIND_TEXT` on the line under the name, before `aka` |
| `AGENT_CAPABILITIES`, `AgentCapability`, `agentProblems()` | A frame's `agent`: what its frame agent does, in the words of [docs/frame-agent.md](../../docs/frame-agent.md#capabilities). `agentProblems()` lists what's wrong (an unknown word, a word twice, an agent on a tested device), one line each; the site's build runs it |
| `keyProblems()` | The top-level keys of a `frame.json` that aren't `FrameDesign` fields, one line each. A `Record` over `FrameDesign`'s keys holds the list, so the compiler fails when the type and the list differ. Kiln's tests and the site's build run it |
| `completeness()`, `factProblems()`, `factsOf()`, `FrameFacts`, `FrameRun`, `CHECKS`, `COUNTED`, `CHECK_TEXT`, `WORD_TEXT`, `WORD_MEANING` | How complete Glassworks is on a frame ([frames/README.md](../../frames/README.md#how-complete-it-is)). `completeness(f, { base, clarity })` is pure: from the frame's `facts` (a tested device: its `run`), its `kiln` route (a tested device: its `base`'s) and its Clarity letter, it gives the eight items, each `yes`, `no` or `na` with a reason, and the word, `full`, `good`, `basic` or `null` while the frame doesn't ship. `factProblems()` lists what's wrong with `facts` or `run`, one line each, and refuses a key the record doesn't have, so a serial or fingerprint pasted from Kiln's `--plan` fails the build; Kiln's tests and the site's build run it |

## The pane catalog

`src/panes.generated.ts` holds every pane's `pane.json` and `icon.svg`, which live next to each pane's
code in a Gradle module's `src/main/java` (`panes/<id>`, `frames/<id>/android` or
`frames/<id>/panes/<id>`). After you add or change one, run:

```sh
npm run panes -w @glassworks/frame-card
```

`test/panes.test.ts` fails when the generated file is stale. `PANES` lists general panes first, then
frame panes, each by stage (stable first) and then by name, as the frame catalog orders frames. The icons are 24 × 24, stroked with
`currentColor` at 1.6 with round caps and joins, and have no fills or colours. A page draws one by
putting `icon` in an element whose `color` it sets.

The page provides the Glassworks tokens (`--sky`, `--edge`, `--r-tile`, `--muted`, …) and the global
`glass` surface class: Kiln from `@glassworks/brand`, the site from its own `globals.css`. The only
literal colours are the device's paint in `src/paint.ts` (`test/no-hex.test.ts`). A caller's own
states go in the caller's styles; Kiln's ember ring on the picked card is in
`kiln/apps/kiln/src/components/FrameCard.module.css`.

```tsx
import { FrameCardBody, frameCardClass, useTilt, type FrameDesign } from '@glassworks/frame-card';

function Card({ f }: { f: FrameDesign }) {
  const tilt = useTilt<HTMLAnchorElement>();
  return <a {...tilt} href={`/frames/${f.id}`} className={`glass ${frameCardClass}`}><FrameCardBody f={f} as="h2" /></a>;
}
```

In a link, the card's text isn't underlined, and `as="h2"` makes the frame's name a heading for the
page's outline. Inside a `<button>` (Kiln's picker), leave `as` out: a heading isn't allowed there.

A frame's own page can draw the mockup alone, on a taller stage for a portrait frame:

```tsx
<DeviceModel id={`${f.id}-hero`} model={f.model} label={`${f.name} running Glassworks`} aspect={f.model.body.height > f.model.body.width ? 0.8 : 2} />
```

**The render.** Each `frame.json` names its screen's render (`render`, a `.webp` next to it; see
[frames/README.md](../../frames/README.md#the-render)). The card can't resolve that path itself,
because only the caller's bundler knows the file's URL. Import the file and pass its URL as `screen`
to `FrameCardBody` or `DeviceModel`. Without `screen`, the card draws the example dashboard as before.
An image import is `{ src }` under Next.js and a plain string under Vite (and so in Vitest), so take
the URL from either. Kiln does it in the frames registry's catalog (`packages/frames/src/catalog.ts`):

```tsx
import s1Render from '…/frames/aqara-s1-plus/design/render.webp';
const url = (m: string | { src: string }) => (typeof m === 'string' ? m : m.src);
<FrameCardBody f={f} screen={url(s1Render)} />
```

The source is TypeScript and CSS modules, not built. Next.js compiles it like the app's own code.

## How glassworks.dev takes it

The site lives in this repo, in [`site/`](../../site/README.md), so it takes both things it needs
from the same commit: this package, as an npm workspace dependency (`"@glassworks/frame-card":
"0.0.0"`), and each frame's `design/frame.json`, read from `frames/` at build time. The card never
draws data it wasn't written for.

The package ships TypeScript and CSS modules, not built JavaScript, so the site's Next config
compiles it: `transpilePackages: ['@glassworks/frame-card']`. Until 2026-11-15 the site was its own
repo and took this repo as a git submodule at a pinned commit.

When frames move to their own repos (#35), the site points at each frame's released `frame.json`
instead, and this package can move with the core.

## Commands

From the repo root: `npm test -w @glassworks/frame-card`, `npm run typecheck -w @glassworks/frame-card`.
`test/card.test.tsx` checks the props above; `ships()`'s type is checked by the typecheck.
Kiln's `test/frame-designs.test.ts` draws every frame with `geometry()`.
