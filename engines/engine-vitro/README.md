<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-vitro-on-dark.svg">
      <img alt="Glassworks Vitro" src="https://www.glassworks.dev/brand/banner-vitro-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Vitro</h1>

<p align="center">
  <b>Your Home Assistant dashboard, drawn natively on the panel.</b><br>
  An experimental renderer for Glassworks. Nothing to install in Home Assistant.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-vitro-on-dark.svg">
      <img alt="Glassworks Vitro" src="https://www.glassworks.dev/brand/badge-vitro-on-light.svg" height="30">
    </picture>
  </a>
  <a href="../../LICENSES/MPL-2.0.txt">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-on-dark.svg">
      <img alt="License: MPL-2.0" src="https://www.glassworks.dev/brand/badge-license-on-light.svg" height="30">
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

Vitro is an experimental renderer for Home Assistant dashboards. Instead of loading the dashboard in a browser, it reads the
dashboard you already have from Home Assistant and draws it with native Android views. It's built for low-power panels, such
as the Aqara Panel Hub S1 Plus (32-bit Android 10) and the lululemon Studio Mirror (Android 6 with under 1 GB of RAM), where
a full browser is the heaviest thing on the device. Dashboards are written in Home Assistant the same way as before, and
nothing gets installed there.

> **Experimental, and off by default.** Turn it on under **Settings → Labs → Vitro renderer**, on the panel or on its
> Manage page. The change takes effect after the app restarts. With Vitro off, the app runs on GeckoView alone, exactly as
> it did before Vitro existed. Developers can also use `adb shell am start ... --ez vitro true` (or `false`), which
> restarts the app.
>
> Vitro hasn't been benchmarked against GeckoView yet, so this page makes no speed or memory claims.

## How it works

Vitro moves the Home Assistant data plane out of the browser into Kotlin (`HaDashboardClient` over `WsHaSocket`) and splits every card across three automatic tiers:

| Tier | What it handles | How it renders |
|---|---|---|
| **Tier 1: Native core** | Layouts (`sections`, `masonry`, `panel`, `grid`, stacks, `visibility`) and 31 built-in Home Assistant cards, from `tile`, `entities`, `glance` and `button` to `thermostat`, `humidifier`, `calendar`, `logbook`, `picture-elements`, `history-graph` and `media-control` | Native Android views and Canvas drawing: no JavaScript and no browser process |
| **Tier 2: Native HACS fast-paths** | 26 popular HACS cards: the whole Mushroom suite (entity, light, fan, cover, climate, person, template, title, chips, media player, lock, alarm, vacuum, humidifier, number and select), Bubble Card with its sub-buttons, mini-graph-card, mini-media-player, clock-weather-card, modern-circular-gauge, bar-card, auto-entities, and the seamless stack-in-card, vertical-stack-in-card and layout-card containers | Native Kotlin renderers, with Home Assistant `render_template` subscriptions for Jinja2 templates |
| **Tier 3: Micro-Islands** | Any other `custom:*` card, and any custom card that uses something Vitro can't draw faithfully (see [What Vitro draws natively](#what-vitro-draws-natively)), including full-screen `type: panel` cards and wrapper cards that build their inner cards with `loadCardHelpers` | A small inline Lovelace shim (`hass.callWS`, `hass.callService`, `hass.callApi`, `hass.localize`, `hass.formatEntityState`, `hass.config`, `<ha-icon>`, and a `hass` proxy that tracks which entities a card reads) with the resolved theme variables, in GeckoView only |

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

## Fonts

Text draws in the font Home Assistant's page would use. Vitro reads a `font-family` list the way a browser does and uses the first family it can draw:

| Family | Where Vitro gets it |
|---|---|
| A font a Lovelace resource of type `css` declares with `@font-face` (also through `@import`, such as a Google Fonts stylesheet) | Downloaded from the same address the browser would use, then kept on the panel |
| Inter | Bundled with the app |
| Roboto, Noto, `sans-serif`, `system-ui` and the usual system names | Android's own sans-serif, which is Roboto |
| `serif`, `monospace` | Android's own serif and monospace |
| Anything else | Skipped, so the next family in the list is used, just as a browser skips a font it can't load |

The list comes from the theme's `--ha-font-family-body` (or `--primary-font-family`), from a card's own `theme:`, or from a card-mod `font-family` on the card (`ha-card { font-family: Lora, serif; }`), which every text in the card and in the cards stacked inside it inherits. Markdown follows its sheets' `font-family` element by element.

A few details keep this light on a small panel:

- **Nothing is downloaded until it's needed.** A dashboard with no `type: css` resources makes no request at all. Otherwise Vitro reads the stylesheets the first time it draws text, as the browser loads them with the dashboard, but fetches a font only once some text uses it.
- **Text never waits for a font.** While a font downloads, the text draws in the next family of the list, then draws again once the font arrives, as a browser does with `font-display: swap`. After a restart, fonts load from the panel's storage before the first view is drawn.
- **There are limits.** Vitro reads up to 8 stylesheets of 512 KB and loads up to 12 font files of 4 MB each, and keeps 24 MB on the panel. A font past a limit draws in its fallback, like a font that failed to load.
- **Formats:** TrueType, OpenType and WOFF. WOFF2 isn't decoded, so a face that offers only WOFF2 is skipped. Most stylesheets list a WOFF or TTF file next to it, and Google Fonts sends TrueType to Vitro.
- **Weights** are matched the way CSS matches them, and a missing bold or italic is synthesized, as a browser does. A variable font is drawn at the exact weight on Android 8 and later. On older versions, Vitro uses the family's nearest static face if the stylesheet has one, and otherwise the variable font's default weight.
- **Character ranges:** when a family is split into `unicode-range` subsets (as Google Fonts does for Latin, Greek or Cyrillic), Vitro uses the Latin subset. Characters outside it are drawn in Android's own fonts, not in the family's other subsets.

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

## What Vitro draws natively

Home Assistant's own render is the spec. Vitro draws a custom card natively only when it can match HA, and otherwise sends it to a web island on purpose, so that card looks the way HA draws it and only that card pays for a web engine. Built-in cards are the exception: the island can't draw them yet, so a built-in card Vitro draws stays native even when its config has something outside the supported set, and Vitro leaves that part out (see the end of this section). It makes the choice once for each card when it reads the dashboard, never while drawing.

The supported set lives in one place in the code, [`compat/VitroSupport.kt`](src/main/java/dev/glassworks/engine/vitro/compat/VitroSupport.kt), and [`compat/CardCompat.kt`](src/main/java/dev/glassworks/engine/vitro/compat/CardCompat.kt) checks each card against it.

| Part | Native | Falls back to an island |
|---|---|---|
| Card types | The built-in and HACS cards in the tier table above | Any other `custom:` card, and built-in cards Vitro doesn't have yet (such as `map` or `energy-*`) |
| Card options | Tile features `light-brightness`, `select-options`, `cover-open-close`, `climate-hvac-modes`, `target-temperature`, `fan-speed`, `cover-position`, `light-color-temp`, `media-player-volume-slider`, `toggle`, `media-player-playback`, `light-color-favorites`, `numeric-input` and `vacuum-commands`. Entity rows, `divider` and `section` rows. `picture-elements` icons, labels, badges and service buttons. Mushroom chips: entity, template, weather, light, action, menu, back, spacer and conditional. Bubble Card buttons and separators. layout-card's masonry, horizontal, vertical and grid layouts | Any other tile feature, entity row type (`buttons`, `attribute`, custom rows, ...), `picture-elements` element, chip, Bubble Card type or layout. Entities headers and footers |
| card-mod sheets | `"."` (or a plain string) on every card, `"ha-markdown$"` on Markdown cards and `"hui-clock-card-digital$"` on the clock | Any other path, such as `"mushroom-shape-icon$"`, and nested selector maps |
| The card's own rule | `ha-card { }`, `:host { }`, `:host ha-card { }` with backgrounds (colors and linear gradients), solid borders, `border-radius`, `box-shadow`, `opacity`, `padding`, `margin`, `height: 100%`, the opacity `transition`, a pinned card (`position: fixed` with offsets, `width`, and a `translate(-50%)` that centers it at `left: 50%` or `top: 50%`), `font-family` on every card (see [Fonts](#fonts)), and `font-size` on tile and Mushroom cards. A stack's `:host { }` with `padding`, `margin` and its gap variables | Background images, radial gradients, dashed borders, inset shadows, other transforms, filters, animations, fixed heights, `color` on cards other than Markdown and the clock |
| Selectors | Inside Markdown and the clock, the selectors listed under "Markdown sheets" in the table above, matched against the elements Vitro rebuilds | Selectors that reach inside a card Vitro doesn't rebuild (`ha-card .primary`), `::before` and `::after`, `:has()`, `:host()`, and `@media` or `@supports` rules |
| Markdown | Everything HA's sanitizer keeps, except the tags in the next column | `<ha-alert>`, `<ha-qr-code>`, `<ha-svg-icon>`, `<details>`, `<audio>`, `<video>`, `<picture>`, checkboxes and image maps |

Some things never send a card to an island:

- **What Home Assistant drops itself.** Declarations outside a rule and tags outside HA's sanitizer are dropped by HA too, so dropping them is what matching HA means.
- **`!important`**, which card-mod keeps and HA honors. On the card itself, Vitro lets an `!important` declaration win over the card's other card-mod declarations, as the browser does. In Markdown and clock sheets, Vitro keeps the value but not the priority, so a rule that beats HA's own styles only because of `!important` may lose to them in Vitro. The checker lists that as a note.
- **Things that don't change the look on a touch panel**, such as `cursor`, `transition`, `user-select`, `pointer-events: auto`, `will-change`, `-webkit-tap-highlight-color`, `outline: none`, and `:hover`, `:focus` and `:active` rules. Each one is listed with its reason in `VitroSupport`.
- **Known gaps** that Vitro still draws natively, because an island wouldn't do better or the native work is underway: `backdrop-filter` (an island can't blur the native view behind it either), `text-overflow` and `white-space: nowrap` (they only show when text doesn't fit), small `position: relative` nudges, inline tile features, the Mushroom chips card's own card-mod, and card variables a native card doesn't read. The checker lists these as notes.

A card goes to the island only when the island can draw it. The island runs the card's own code with a small stand-in for Home Assistant's frontend, and loads that code from the dashboard's Lovelace resources. That has three consequences:

- **Built-in cards stay native.** The island doesn't include Home Assistant's built-in cards, so a built-in card Vitro draws (a `tile`, say) stays native even when its config has something outside the supported set, and the checker notes what it would have fallen back for. The same goes for a custom container Vitro draws, such as stack-in-card or layout-card, when a built-in card sits anywhere inside it, since that card would show as a plain summary in the island. A custom wrapper Vitro doesn't know that builds built-in cards through `loadCardHelpers` shows them as summaries too.
- **A custom card Vitro also draws natively** (Mushroom, Bubble Card, mini-graph-card and the rest of the HACS list) goes to the island only when a Lovelace resource provides its script, matched by file name (`mushroom.js`, `bubble-card.js`, ...). For a fallback caused by card-mod styles alone, card-mod has to be a resource too. Otherwise the card stays native, and the checker notes what it would have fallen back for and that the island has no script for it.
- **A custom card with no native renderer** goes to the island regardless, since that's its only chance to draw. The checker notes it when no resource seems to provide its script. Built-in cards Vitro has no renderer for, such as `map` or the `energy-*` cards, go to the island too.

### The compatibility checker

The checker reports every card Vitro builds in a view, whether it renders natively or in an island, and the exact reason for each island:

- **Logcat:** each time Vitro builds a view, it logs one `Island <where>: <why>` line per fallback under the `Glassworks.Vitro` tag.
- **The test app:** tap the HUD to expand it. It lists the islands in the current view.
- **From code or a test:** `CompatChecker.check(dashboard, IslandScripts.of(resources)).format()` gives the same report as text, starting with a line such as `116 cards: 116 native, 0 island (fully native)`. Pass the dashboard's Lovelace resources so the report knows which scripts the islands can load. Without them, it assumes every card's script and card-mod are there.

To check your own dashboards without a panel, save each Lovelace config (`lovelace/config` over the WebSocket API) as a `*-new.json` file in one folder and run:

```sh
VITRO_PARITY_DIR=/path/to/folder ./gradlew :engine-vitro:testDebugUnitTest --tests '*CompatCheckerLocal*' -i
```

The report covers the cards in the dashboard config, auto-entities' own `card:` included. The cards `auto-entities` builds at runtime for each matched entity aren't in it, so an island one of them opens doesn't show in the counts or the log. This check reads only the exported configs, not the resource list, so it assumes every card's script and card-mod are Lovelace resources the islands can load. On the panel, a card whose script or card-mod isn't a resource stays native even though the check lists it as an island.

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
tools/vitro-parity/run.sh                             # the parity harness: Vitro vs Home Assistant, card by card
```

### The parity harness

The harness renders a corpus of cards in Home Assistant's real frontend and in Vitro at each panel's size and density, compares them (pixels, card bounds, text sizes), and writes a side-by-side report to `build/reports/vitro-parity/index.html`. Its committed references let it run in CI with no device and no Home Assistant. A case that drifts further than `src/test/parity/baseline.json` allows fails the build. Run `tools/vitro-parity/run.sh --refresh` to capture new references when Home Assistant is reachable. Details are in [tools/vitro-parity](../../tools/vitro-parity/README.md).

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
