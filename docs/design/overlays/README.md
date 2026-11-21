<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Overlays: themes, Assist and timers (design record)

Design mockups for #691: a theme for every native overlay, the Assist overlay as a reworked orb or an
edge glow, and the timers. Kept as the design record: what was decided, and what was tried and
dropped (the round 1 glow, Sheen and Pour).

**Status (2026-11-20): built.** Everything in [Decided](#decided) shipped, in these PRs:

| PR | What |
|---|---|
| #702 | The theme model (any palette, contrast-derived text) and the Appearance page, on the panel and on Manage |
| #703 | The voice orb in the theme |
| #704 | Spark's edge glow as a second Assist look |
| #705 | The thinking swirl stays round in software |
| #706 | The Custom theme: six colors, swatches on the panel, a color picker on Manage |
| #707 | The timers' ring, dock and done screen in the theme |
| #713 | The timer board (three or more timers) |
| #721 | The timer done screen on a portrait, no-touch screen (the Mirror) |
| #724 | Follow Home Assistant's dark mode (the switch under the cards) |
| #726 | The Home Assistant theme, from the dashboard's own colors |

The built screens are the source of truth now; their Robolectric mockups are `VoiceThemeMockups`,
`GlowThemeMockups`, `CustomThemeMockups`, `TimerThemeMockups` and `SettingsMockups`. Each PR has a
panel plan in its body.

| File | What |
|---|---|
| [mockups.html](mockups.html) | Every screen, one per URL (`?scene=…&frame=…&mode=…&theme=…`). The parameters are listed at the top of the file |
| [edge.js](edge.js) | The round 2 edge glow: its timeline, its states and its three entrances, drawn as the A133 would draw it |
| [glow.html](glow.html) | The motion preview: the edge glow's three entrances (and round 1 and the orb, to compare), a full run or the entrance and exit at quarter speed, in any theme |
| [render.sh](render.sh) | Renders every screen to PNG at the panels' pixel sizes with headless Chrome. `MOTION=1` also makes the round 1 GIFs |
| [motion.cjs](motion.cjs) | Records the round 2 edge glow frame by frame, then makes a GIF and an MP4 |

To look at them, open the files in Chrome (no server needed), or render them:

```sh
docs/design/overlays/render.sh /tmp/overlays                 # every still, ~110 PNGs
MOTION=1 docs/design/overlays/render.sh /tmp/overlays none   # the round 1 GIFs only
# round 2: one GIF per call. Needs playwright-core (npm i playwright-core in any folder) and ffmpeg.
NODE_PATH=<folder>/node_modules node docs/design/overlays/motion.cjs /tmp/overlays s1-spark-cycle \
  "scene=lit&variant=spark&state=cycle&frame=s1&mode=dark&theme=glassworks" 960 480 0.75 0 16300 1 15
```

The screens are sized in dp, as the app is: the S1 at 960×480 dp (density 1.5, 1440×720 px) and the
Mirror at 540×960 dp (density 2, 1080×1920 px). The dashboard behind them is a stand-in with
placeholder data.

## Why themes

Principle 3: the dashboard is the UI. The overlays are the only native screens, and they sit on top of
someone's own dashboard, so one fixed look can clash with it. A few named themes, each with a light
and a dark variant, fix that without a colour picker (principle 10).

## Themes

Every theme is two lights and a ground. Light **a** comes from the top left and light **b** from the
bottom right, as the brand's glass is lit. The ground is the scrim and the settings' night or day.

| Theme | Dark: a → b | Light: a → b | Dark ground | Light ground |
|---|---|---|---|---|
| **Glassworks** (default) | `#38bdf8` → `#a78bfa` | `#0ea5e9` → `#8b5cf6` | `#0a0b0f` | `#eceef2` |
| Aurora | `#5eead4` → `#c4b5fd` | `#14b8a6` → `#a855f7` | `#06100f` | `#e7f0ee` |
| Dusk | `#818cf8` → `#f0abfc` | `#6366f1` → `#d946ef` | `#0c0a14` | `#eeecf5` |
| Dune | `#f1d6ae` → `#c39a78` | `#b88a5c` → `#8c5f45` | `#110e0b` | `#f3efe8` |
| Graphite | `#f4f4f5` → `#8b8b94` | `#52525b` → `#a1a1aa` | `#0b0b0c` | `#ededee` |
| Home Assistant | The active HA theme's `primary-color`, and its dark mode | | | |

Rules:

- **Status colours stay status** in every theme: green for done, amber for a timer's last 10 seconds,
  rose for errors. No theme uses them as its lights.
- **No Ember.** Ember (peach to rose) is Kiln's colour, so no theme is warm-to-rose. Dune is a sand
  neutral, not an orange.
- **Follow Home Assistant** reads the active HA theme's `primary-color` and dark mode over the
  connection the panel already has. Nothing is installed in HA (principle 4). Its picker badge is a
  half sun, half moon, because HA decides light or dark.
- The dashboard keeps its own theme. The overlay theme covers voice, timers, notifications, setup and
  the panel's settings.

## The picker

On the panel, a new **Appearance** page under *Panel*. The same section is on Manage, so a no-touch
frame (the Mirror) is set from there (Manage/panel parity).

- One card per theme: two orbs (light and dark) under the theme's name. Tap an orb to pick that
  theme in that variant.
- The active orb is ringed and carries a sun (light) or moon (dark) badge.
- **Follow Home Assistant's dark mode**, a switch under the cards: a named theme switches between its
  light and dark with the dashboard. The active orb's badge is then half sun, half moon. With the
  Home Assistant theme it's always on, so the switch shows on and greyed.
- **Assist**: Orb or Edge glow. A segmented control on the panel, two choice cards on Manage.

## Assist: the orb (reworked)

The same stage as today (orb centre at 160 dp, label at 262 dp, the main text in Inter Light), with
the theme's two lights inside the glass instead of fixed amber and sky:

| State | The light |
|---|---|
| Listening | Light a leads and swells with the mic level. One ripple goes out on wake |
| Thinking | A conic swirl of a and b turns inside the sphere |
| Speaking | Light b leads. Two slow ripples follow the voice |
| Error | Rose core and ring (a status colour) |

The scrim is the theme's ground at 90 %, lit from top left (a) and bottom right (b), in light and dark.
Every layer is still a baked bitmap that only changes alpha, scale and rotation, so it stays as cheap
as today on the A133 (no live blur on Android 10).

## Assist: the edge glow, round 1 (superseded)

Round 1 put the theme's two lights on the border as a soft band ("two lights"). The maintainer's
review (2026-11-20): it looks fine but doesn't feel special yet, and it has no entrance; it jumps
straight into listening. Its mockups stay in `mockups.html` (`scene=glow-*`) to compare.

## Assist: the edge glow, round 2: edge-lit glass

The screen behaves like a thick pane of glass whose edges catch the light. The brand's glass is lit
from the top left (light **a**) and the bottom right (light **b**). Here those two lights enter the
pane, run along its edges, and make a bright **caustic** where they meet. A thin bevel line just
inside the edge shows the pane's thickness. The dashboard stays in view under a light dim, with the
glass caption at the bottom.

### Entrance and exit: Spark (decided)

**Spark** is the entrance and exit (the maintainer, 2026-11-20). Sheen and Pour were the alternatives;
they are rejected and stay in `glow.html` and below only for reference. The states are the same for
all three; the variant sets how the light arrives, leaves, and where it rests between moments.

| Variant | Coming on | Going away | Rests |
|---|---|---|---|
| **Spark (chosen)** | The light ignites at the frame's mic, splits both ways round the edge (a one way, b the other) and flares where the two halves meet, opposite the mic. 650 ms | It runs back into the mic and blinks out. 400 ms | Evenly round the edge |
| Sheen (rejected) | A sheen of light crosses the whole pane diagonally from the top left. Each edge catches it as it passes; the top left corner flares in a, the bottom right in b. 600 ms | A second, faster sheen switches the edges off behind it. 350 ms | In the two lit corners |
| Pour (rejected) | White-hot light pours in at the bottom, runs up both sides, meets at the top in a flare and cools into a and b as it goes. 700 ms | It cools and drains away to the bottom. 450 ms | Pooled along the bottom |

The caption rises in as the entrance lands and leaves first on the way out.

### The states

| State | The edge |
|---|---|
| Listening | Light gathers at the mic and swells with the voice level. Each word sends a ripple both ways along the edge from the mic: a towards the top left, b towards the bottom right |
| Thinking | Light trapped in the glass: a and b run round the edge in opposite directions, with tails, and flare where they cross (about every 1.3 s). Small glints catch here and there |
| Speaking | Calm, so the reply can be read: a soft a-to-b light rests in the two corners (a top left, b bottom right) and breathes slowly with the voice. No pulses and no flares. The bottom edge, under the caption, stays dimmer |
| Error | One rose pulse (a status colour) leaves the mic both ways, then the edge holds a dim rose until it goes |

### The mic, as frame data

Spark and the listening ripples start at the mic. That needs a new field in each frame's
`design/frame.json`, for example `"mic": { "edge": "top", "at": 0.5 }` (which screen edge, and how far
along it). The S1's and the Mirror's mics are at the top, so both use the middle of the top edge
(`"edge": "top", "at": 0.5`), confirmed by the maintainer (2026-11-20). A frame without the field
falls back to the middle of the top edge.

### What it costs on the A133

No blur runs per frame in any variant. The mockup draws it the way the build would, so the cost below
is what the mockup does.

| Piece | Baked once | Per frame |
|---|---|---|
| The edge light (all variants, all states) | Three white sprites: a 4 dp core, a 20 dp glow, a 64 dp haze | About 1,240 stamp points round the S1's edge (a core every 4 dp, a glow every 8, a haze every 18), each drawn with an alpha into the one or two colour layers it touches: roughly 2,000 to 2,500 bitmap draws. Each layer is then tinted (SRC_IN) and added to the screen. Alternative if that's too many draws: per edge, one rect with a baked falloff bitmap times a `LinearGradient` whose stops hold the light along that edge |
| Caustics and glints | One flare sprite per colour (a, b, white, rose) | A few draws with scale and alpha |
| Sheen | One diagonal band bitmap | A translation and an alpha (only during Sheen's entrance and exit) |
| Bevel line | Nothing | One stroked rounded rect with a gradient and an alpha |
| Dim and caption | The dim gradient | Alpha and a small translation |

To measure before the build: whether a full-screen additive layer at 60 fps fits on the S1 next to
GeckoView. The sprites only need redrawing while the overlay is on screen; idle, it is gone, as the
voice overlay is today.

## Timers

| Piece | What changes |
|---|---|
| Ring | The theme's two lights run down the ring (a to b). Paused: dimmed ring and a pause mark. The last 10 seconds turn amber (status) |
| Dock | The same orbs and placement as today, themed, in light and dark glass |
| Board | New: with three or more timers, the group becomes one glass card that lists them (mini ring, name, length, time left). "Hide" on touch frames only |
| Done | The voice stage with the theme's lights and chime ripples, "Timer done", the name, the length and what's still running |

## No touch (the Mirror)

The Mirror has no touch input (`Input.NONE`). Its mockups show no tap targets: no "Tap anywhere to
cancel", no "Tap anywhere to dismiss", no "Hide" on the board. Its theme and Assist style are set on
Manage.

## Decided

The maintainer, 2026-11-20, on round 1:

1. A new **Appearance** page on the panel, and the same section on Manage.
2. Keep all five named themes (Glassworks, Aurora, Dusk, Dune, Graphite), plus Home Assistant.
3. The timer done screen uses the theme's colours, as drawn.
4. The timer board is wanted.
5. A named theme can also follow Home Assistant's dark mode: the switch under the theme cards, on the
   panel and on Manage.

The edge glow waited for round 2, above.

The maintainer, 2026-11-20, on round 2:

1. **Spark** is the edge glow's entrance and exit. Sheen and Pour are rejected; they stay in the
   mockups, labelled, for reference only.
2. The motion while speaking was distracting, so speaking is now calm (no pulses, no flares, a slow
   breath).
3. The mic is at the top on the S1 and the Mirror: both use the middle of the top edge. The proposed
   `mic` field in `frame.json` stays.

Later, while building: custom themes with colors people pick, light and dark, on the panel and on
Manage, with readable text from contrast (this reversed the earlier "no free color picker").

## What the S1 prototype measured

A native prototype (a throwaway test build, never merged) ran Spark on the S1, the Pixel Tablet and the
Mirror, on scripted loops and on real Assist runs. Drawn on the RenderThread from baked bitmaps, the
glow matched the orb on the S1 (60 fps on real runs) and passed on the tablet; on the Mirror both
looks ran at about 30 fps, the glow no worse than the orb.
