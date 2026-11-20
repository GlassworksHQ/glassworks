<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Overlays: themes, Assist and timers (mockups)

Design mockups for #691: a theme for every native overlay, the Assist overlay as a reworked orb or an
edge glow, and the timers. **Proposal, not approved.** Nothing here is built. Once the maintainer
approves a direction, the build follows in small PRs (theme plumbing and settings, then Assist, then
timers), each with Robolectric mockups of the real views.

| File | What |
|---|---|
| [mockups.html](mockups.html) | Every screen, one per URL (`?scene=…&frame=…&mode=…&theme=…`). The parameters are listed at the top of the file |
| [glow.html](glow.html) | The motion preview: one voice run on a loop, as the edge glow or the orb, in any theme |
| [render.sh](render.sh) | Renders every screen to PNG at the panels' pixel sizes with headless Chrome. `MOTION=1` also makes the GIFs |

To look at them, open the files in Chrome (no server needed), or render them:

```sh
docs/design/overlays/render.sh /tmp/overlays                 # every still, ~110 PNGs
MOTION=1 docs/design/overlays/render.sh /tmp/overlays none   # the motion GIFs only
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

## Assist: the edge glow

Our take on an edge glow. We call it **two lights**. Light a and light b live on the screen's border,
and the dashboard stays in view under a light dim that deepens towards the bottom, where a glass
caption shows what was heard and the reply.

| State | The border |
|---|---|
| Listening | The two lights gather along the bottom edge and swell with the voice |
| Thinking | Two short arcs, a and b, chase each other round the border |
| Speaking | The whole border lights up, a to b, and breathes with the reply |
| Error | Rose wash, then the overlay goes |

How it would draw on the A133: three bands (a 2.5 dp line, a 12 dp glow and a 40 dp haze), each a
`SweepGradient` stroke round the screen. The blurred bands are baked once per theme, and per frame
only the sweep's rotation, alpha and band scale change. Whether a full-screen alpha layer holds 60 fps
on the S1 needs measuring before the build.

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

## Open questions for the maintainer

1. Is **Appearance** the right page, or should the theme sit on *Screen* and the Assist style on
   *Voice*?
2. Five named themes plus Home Assistant: keep all five, or drop one (Graphite or Dune)?
3. Timer done: the theme's lights (as drawn) or amber, as today, because a finished timer wants
   attention?
4. Is the timer **board** wanted at all, or should three or more timers stay as orbs?
5. With a named theme, should light or dark also be able to follow HA's dark mode? (Not drawn: one
   more row.)
