# Vitro (`engines/engine-vitro`)

> **Experimental (`0.0.1`).** Off by default (`KioskPrefs.vitroEnabled`). Enable via **Settings → Home Assistant & dashboard → Vitro renderer** or `adb shell am start ... --ez vitro true`.

Vitro is an experimental native Android Lovelace rendering engine for Glassworks, built behind [`dev.glassworks.engine.Engine`](../../core/src/main/java/dev/glassworks/engine/Engine.kt). Its goal is to deliver native Android startup speed, frame pacing, and ~35–50 MB RAM footprints on low-powered hardware (such as the 952 MB Android 6 lululemon Studio Mirror and the 32-bit Aqara Panel Hub S1 Plus) **without changing how dashboards are authored in Home Assistant and without installing anything in Home Assistant**.

## How it works

Vitro moves the Home Assistant data plane out of the browser into Kotlin (`HaDashboardClient` over `WsHaSocket`) and splits every card across three automatic tiers:

| Tier | What it handles | How it renders |
|---|---|---|
| **Tier 1: Native core** | Layouts (`sections`, `masonry`, `panel`, `grid`, stacks, `visibility`) and 31 built-in Home Assistant cards, from `tile`, `entities`, `glance` and `button` to `thermostat`, `humidifier`, `calendar`, `logbook`, `picture-elements`, `history-graph` and `media-control` | Native Android views and Canvas drawing: no JavaScript and no browser process |
| **Tier 2: Native HACS fast-paths** | 26 popular HACS cards: the whole Mushroom suite (entity, light, fan, cover, climate, person, template, title, chips, media player, lock, alarm, vacuum, humidifier, number and select), Bubble Card with its sub-buttons, mini-graph-card, mini-media-player, clock-weather-card, modern-circular-gauge, bar-card, auto-entities, and the seamless stack-in-card, vertical-stack-in-card and layout-card containers | Native Kotlin renderers, with Home Assistant `render_template` subscriptions for Jinja2 templates |
| **Tier 3: Micro-Islands** | Any other `custom:*` card, including full-screen `type: panel` cards and wrapper cards that build their inner cards with `loadCardHelpers` | A small inline Lovelace shim (`hass.callWS`, `hass.callService`, `hass.callApi`, `hass.localize`, `hass.formatEntityState`, `hass.config`, `<ha-icon>`, and a `hass` proxy that tracks which entities a card reads) with the resolved theme variables, in GeckoView only |

Tier 3 never uses `android.webkit.WebView`. Each island is served from `VitroIslandServer`, a tiny HTTP server bound to IPv4 loopback (`127.0.0.1`), and loaded into GeckoView, so the Android system WebView, which is missing or too old on some of these panels, is not a dependency. A dashboard that stays in Tier 1 and Tier 2 never starts GeckoView at all.

Non-dashboard pages, such as `/auth/authorize` for panel sign-in or the Blueprint import dialog, are handed to the wrapped fallback `Engine`.

## More-Info dialogs

Tapping or holding a tile opens a native More-Info dialog styled after Home Assistant 2026, with no web view involved:

| Entity | Dialog |
|---|---|
| Light | Brightness dimmer, an HSV color wheel, a Kelvin color-temperature picker, and color swatches |
| Climate | A circular thermostat dial with mode buttons |
| Cover | A top-down position slider |
| Sensor | A 24-hour stepped history graph |
| Camera | A live stream |
| Switch | A toggle pill |
| Alarm panel | A PIN pad |

Tiles can also carry the inline features Home Assistant adds under the card: `climate-hvac-modes`, `target-temperature`, `fan-speed`, `cover-position`, `light-color-temp`, `media-player-volume-slider`, `toggle`, `media-player-playback`, `light-color-favorites`, `numeric-input` and `vacuum-commands`, next to the brightness, select and open/close controls.

## Themes

Vitro reads the themes Home Assistant already has, so a dashboard looks the same as it does in the browser:

- `frontend/get_themes` and `frontend/get_user_data` give the active theme, including the light and dark modes and a user's own theme choice.
- `var(--...)` chains resolve recursively, including `rgb-*` triples.
- Multi-stop linear and radial gradients, wallpapers and `box-shadow` elevation are drawn natively.
- Entity state colors follow the domain and the device class, such as amber for a light that is on.
- A view or a single card can set its own `theme:`, and `card_mod` styles apply on the container.

The resolved variables are also exported to Tier 3 islands, so native and custom cards match.

## Rendering like Home Assistant does

Vitro follows Home Assistant's own rules, so a dashboard never looks right only in Vitro:

| Part | What Vitro does |
|---|---|
| Markdown | Markdown follows HA's sanitizer: inline `style` and `class` attributes are dropped, and tags outside HA's whitelist show as text. Style the rendered Markdown through card-mod sheets instead. Content goes through the same steps as in the browser (`marked` with line breaks, then the sanitizer), and every element gets its style from the browser defaults, HA's markdown styles and the card's sheets. |
| card-mod | `card_mod.style` is a string or a selector map. The `"."` sheet styles the card (`ha-card { … }`, `:host { … }`) and `ha-markdown`; `"ha-markdown$"` styles the rendered Markdown and `"hui-clock-card-digital$"` the clock's digits. Declarations outside a rule are ignored, as they are in the browser. HA's own styles win specificity ties, so write `ha-markdown-element h2 { … }` to beat HA's `h2`. Custom properties set on the card, such as `--card-text-align`, `--mdc-icon-size` or `--vertical-stack-card-gap`, reach everything inside it. |
| Markdown sheets | Selectors by element, descendant and child, `:first-child`, `:last-child`, `:nth-child()`, `:first-of-type`, `:last-of-type`, `:nth-of-type()` (including `2n+1` forms), `:not()` and attribute selectors such as `td[width="0%"]`. Properties for typography, color and opacity, margins (they collapse like CSS), padding, width and height, backgrounds, border-radius and borders. |
| HTML tables | `<table role="presentation">` rows and proportional bars: `<td width="N%" align valign>`, `border-spacing`, `table-layout: fixed`, cell backgrounds, and a rounded `overflow: hidden` table. |
| Images | `<img width height>`, block images (`display: block`, `width: 100%`), and `data:image/svg+xml` images built with Jinja, drawn natively from a small SVG subset. |
| Sections | HA's grid: the `--ha-view-sections-*` gaps and column widths, 12 columns per section with `--ha-section-grid-*` gaps, and each card's `grid_options` (or the size its type reports, such as a large clock's 6 x 2). Hidden cards and sections leave no gap. |
| Cards | The clock reads `clock_size`, `time_format`, `time_zone`, `no_background` and the `--ha-clock-card-digital-font-size-*` variables. Mushroom chips honor `conditional` chips and ignore a chip's own `visibility` and `card_mod`, like Mushroom does. stack-in-card follows version 0.2.0. A card's `margin` insets it in its slot. |

## Key optimizations

- **View-scoped entity subscriptions:** Deep-scans the active view's config (including nested custom-card config objects) and passes only those `entity_id`s to `subscribe_entities`.
- **O(changed) selective reactivity:** `EntityStore` updates only the cards bound to each changed entity instead of cloning `hass.states` across the whole tree.
- **Keep-alive view cache:** Switching views toggles the visibility of pre-built native trees instead of tearing down and recreating cards.

## The `Engine` seam

Vitro sits behind the same `Engine` interface as the GeckoView engine, and hands anything it can't draw to the engine it wraps. Today that fallback is GeckoView. The seam is deliberate: when a lighter web-component engine such as Servo or a purpose-built VitroView is ready, it can replace GeckoView for Tier 3 and for the fallback without touching the native tiers or the panes.

## Modules & testing

```sh
./gradlew :engine-vitro:testDebugUnitTest
./gradlew :vitro-test:assembleDebug
./gradlew test :kiosk:assembleDebug :generic-android:assembleDebug
python3 -m unittest tools/vitro/test_showcase_ha.py   # the parity showcase server
```

## Try it: the parity showcase

`tools/vitro/showcase_ha.py` is a made-up Home Assistant that serves one dashboard, `vitro-parity`, to Vitro and to Home Assistant's real frontend, so the two render side by side. It runs on `127.0.0.1` only and keeps everything in memory.

```sh
python3 tools/vitro/showcase_ha.py --port 8091
open http://127.0.0.1:8091/vitro-parity/sections    # Home Assistant's own Lovelace
```

The dashboard has seven views:

| View | What it shows |
|---|---|
| `sections` | Core cards: tiles with features, thermostat, weather, gauge, media control, conditional cards, markdown, alarm panel and a to-do list |
| `room-detail` | A subview with entities, glance, history, cameras and a button stack |
| `themed` | A glass theme with a 4-hop `var()` chain, a per-card `theme:` and `card_mod` |
| `hacs` | The Tier 2 cards |
| `panel-custom` | A full-screen custom card in a Tier 3 island |
| `mixed-island` | Native cards next to a custom card served from `/local` |
| `advanced` | A floorplan with `picture-elements`, all six inline tile features, a humidifier, a calendar, a logbook, and a HACS-style wrapper card |

The standalone `:vitro-test` debug APK is Vitro on its own, with no GeckoView linked, so it draws Tier 1 and Tier 2 cards only. Point it at the showcase through `adb reverse`, so the device's loopback reaches this computer:

```sh
adb reverse tcp:8091 tcp:8091
adb shell am start -n dev.glassworks.vitro.test/.VitroTestActivity \
  --es url http://127.0.0.1:8091/vitro-parity/sections \
  --ez dark true --ez hud true
```

`--ez dark true` forces the dark theme (use `false` for light; the choice sticks until you pass it again or clear the app's data), and `--ez hud false` hides the metrics overlay, which is on by default. The same activity also runs against Clarity's `fakeha.py` or a real Home Assistant, by changing the URL.
