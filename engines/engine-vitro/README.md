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
> The one number measured so far: on an Aqara S1 Plus, the Vitro test app builds a view in about 1.6 to 1.8 seconds.
> Memory and startup haven't been compared with GeckoView yet, so this page makes no other speed or memory claims.

## When a card fails

A card that Vitro can't build shows Home Assistant's own error card in its place: the card tinted with the error color,
its icon, and "Configuration error" for an administrator. The other cards keep working, and the log gets one line for
each kind of failure rather than one per update.

The same goes for a card that throws while it measures, lays out, draws or handles a touch, whatever kind of card it is
and wherever it sits: in a sections, masonry or sidebar view, inside a stack, a layout-card or a swipe-card, or among
the cards auto-entities builds. Most cards are drawn on Vitro's card surface, which also catches a card that throws
while it updates. The few that draw without it (a heading, Mushroom's chips and title, and the stack, layout-card,
auto-entities and swipe-card containers) each sit inside a small guard that catches those failures and shows the error
card in their place. When one of those throws while it updates, the update is logged and skipped.

If something still gets past the cards and the app stops twice in a row within a minute of starting, the app turns
Vitro off for the next start, which runs on the browser engine. The panel says so once when it starts, the Labs page
explains why on the panel and on its Manage page, and the Manage overview's Engine tile points there. Turning Vitro on
again in Labs gives it a fresh start. A single crash, or a restart, never turns it off.

## Administrators and everyone else

Vitro shows each Home Assistant user what Home Assistant's own frontend shows them. Before it draws anything, it asks
Home Assistant who the panel's user is and which dashboards that user may open. It asks again whenever an
administrator changes the dashboards, so a dashboard made admin only leaves a non-admin's panel right away.

| | An administrator | Anyone else |
|---|---|---|
| A dashboard marked **Admin only**, in its settings or in the sidebar's | Drawn | Not drawn. Home Assistant's frontend takes the link and opens the user's default dashboard instead, which Vitro draws again if it can |
| The panel's list of dashboards | Every dashboard | The ones the user may open |
| A card that can't be built, or a card whose config Home Assistant rejects | The error card, titled "Configuration error" | The error card's icon alone |
| A tile or plant card whose entity Home Assistant doesn't have | The warning card, titled "Entity not found" | The warning card's icon alone |

## How it works

Vitro moves the Home Assistant data plane out of the browser into Kotlin (`HaDashboardClient` over `WsHaSocket`) and splits every card across three automatic tiers:

| Tier | What it handles | How it renders |
|---|---|---|
| **Tier 1: Native core** | Views (`sections`, `masonry`, `sidebar`, `panel`, and layout-card's `custom:masonry-layout`, `custom:grid-layout`, `custom:horizontal-layout` and `custom:vertical-layout`), layouts (`grid`, stacks, `visibility`) and 41 built-in Home Assistant cards, from `tile`, `entities`, `glance` and `button` (and `entity-button`, its old name) to `thermostat`, `humidifier`, `calendar`, `logbook`, `picture-elements`, `history-graph`, `media-control`, `statistic`, `date`, `plant-status`, `toggle-group`, `shortcut` and `alert`, and the legacy `shopping-list` and `section` names. The `iframe` card is native too, with its page in a browser island of its own | Native Android views and Canvas drawing: no JavaScript and no browser process |
| **Tier 2: Native HACS fast-paths** | 30 popular HACS cards: button-card (without JavaScript templates), the whole Mushroom suite (entity, light, fan, cover, climate, person, template, title, chips, media player, lock, alarm, vacuum, humidifier, number, select and the empty spacer), Bubble Card's separator and its switch, state and name buttons, mini-graph-card, mini-media-player, clock-weather-card, weather-card, modern-circular-gauge, bar-card, auto-entities, and the swipe-card, stack-in-card, vertical-stack-in-card and layout-card containers. A few of these renderers don't match their card yet, so the card goes to an island whenever its script is a Lovelace resource (see [HACS cards](#hacs-cards)) | Native Kotlin renderers, with Home Assistant `render_template` subscriptions for Jinja2 templates |
| **Tier 3: Micro-Islands** | Any other `custom:*` card, and any custom card that uses something Vitro can't draw faithfully (see [What Vitro draws natively](#what-vitro-draws-natively)), including full-screen `type: panel` cards and wrapper cards that build their inner cards with `loadCardHelpers` | A small inline Lovelace shim (`hass.callWS`, `hass.callService`, `hass.callApi`, `hass.localize`, `hass.formatEntityState`, `hass.config`, `<ha-icon>`, HA's entity, device, area and floor registries, and a `hass.states` proxy that tracks which entities a card reads) with the resolved theme variables, in GeckoView only |

An island's `hass` has what HA's frontend gives a card, but it only costs what the card reads. The registries (`hass.entities`, `hass.devices`, `hass.areas`, `hass.floors`) are fetched when a card first reads one. Every island shares that one feed, which follows HA's registry updates and stops when the last island goes. A card that walks `hass.states` (`Object.values`, `for…in`), as a to-do summary or an auto-entities-style filter does, makes its view follow every entity. A card that only looks entities up by id keeps the view's own short list. An island also sees a header height of 0, as HA's kiosk view does with its header hidden, so a panel card sized to `calc(100vh - var(--header-height))` fills the screen. Like HA's, every state change gives the card a new `hass.states` (an entity that didn't change keeps its object), so a card that compares the old and new state of its entity before drawing again sees the change. The island also has HA's `action-handler`, which many cards bind their tap, hold and double tap to, and `ha-svg-icon`. A card that draws with one of Home Assistant's own elements the island doesn't have sends its view to Home Assistant's frontend, as described below. A card whose script doesn't define its element (an old card type a newer release dropped, such as `custom:better-thermostat-ui-card`), or whose `setConfig` throws, shows Home Assistant's error card, as HA does, instead of an empty island.

Tier 3 never uses `android.webkit.WebView`. Each island is served from `VitroIslandServer`, a tiny HTTP server bound to IPv4 loopback (`127.0.0.1`), and loaded into GeckoView, so the Android system WebView, which is missing or too old on some of these panels, is not a dependency. A dashboard that stays in Tier 1 and Tier 2 never starts GeckoView at all.

Each island card gets one GeckoView page, which stays open only while the card is on the dashboard. A reconnect, a dashboard edit to other cards and a theme change all keep the page, and a new theme restyles it in place. When the card's own config changes or the card is removed, its page closes. Islands on a view that isn't on screen, or under one of Home Assistant's own pages, stop drawing until their view comes back.

Islands are kept to what the panel can run, and an island that can't draw says so instead of showing an empty card:

| Situation | What happens |
|---|---|
| A view would open more island pages than the device runs at once: 4 below about 1.5 GB of RAM, 6 on a 2 GB panel such as the S1 or the Mirror, 12 from 2 GiB (a tablet). Cards inside stacks, grids, swipe and layout cards count, and so do iframe cards | The view opens in Home Assistant's frontend, which draws all of it with one browser session |
| A card's script is slow to load | Home Assistant's error card shows while the page keeps waiting, as Home Assistant's own page does, and the card appears once its script defines it. After 2 minutes the page gives up |
| The panel's entity states don't reach the island within 8 seconds | The card's place shows "Connection lost. Reconnecting…" in Home Assistant's own words, and the card draws once the states arrive. A card is never drawn with no states |
| An island's page dies twice within 5 minutes | Vitro gives up on that card, and its views open in Home Assistant's frontend instead of reopening it again and again |
| A card keeps failing on the same missing part of `hass` in three updates in a row, or throws and draws nothing | Its view opens in Home Assistant's frontend |
| A card draws nothing at all | Its place collapses, as in Home Assistant. A very long card grows to at most three screens (and at least 1,200 CSS px) |
| A link or a `url` action in an island | Opens in the panel's page, with Back to return, instead of replacing the card |

An island stays connected to the app for as long as its card is on the dashboard, also while its view is hidden. The app pings it every 30 seconds, and the browser answers on its own, so a quiet card keeps its one connection and isn't sent the whole state again.

Many cards keep small things in the browser's `localStorage`, such as a snoozed row, a collapsed section or the selected tab. Every island shares one address on the panel, which stays the same from one app start to the next, so what a card stores there is still there after a restart. An island also starts from what Home Assistant's own web page keeps on this panel: whenever that page runs, the app keeps a copy of its storage (never sign-in tokens or anything else named like a credential), and each island takes it in before its card loads. A value Home Assistant's page changed since wins, and a value only the card changed in the island stays. It goes one way: what a card changes in an island doesn't reach Home Assistant's web page. Another app holding that address at start moves the islands to a new one, where their storage starts again from Home Assistant's copy.

Non-dashboard pages, such as `/auth/authorize` for panel sign-in, history, settings or the Blueprint import dialog, are handed to the wrapped fallback `Engine`, which loads them from Home Assistant itself. Their WebSocket and sign-in work as they do with Vitro off.

The way back from such a page always leads to the native dashboard:

| On a Home Assistant page | What happens |
|---|---|
| Back | Goes back within that page first, for example from `/config/energy` to `/energy`. After that, it returns to the dashboard view you came from |
| Reload dashboard (the panel menu, the Manage page or Home Assistant's button) | Returns to the dashboard view the panel last showed, or to the dashboard it's set to |
| A tap in Home Assistant's sidebar or a link to a dashboard | Vitro draws that dashboard natively. A dashboard with no saved config stays in Home Assistant's own frontend, which is the one that draws it |

Each page Vitro opens this way starts with no history of its own, and Home Assistant's frontend stops once the panel is back on the native view. While a page is open, the dashboard's islands stay hidden behind it.

Some dashboards and views go to that fallback too, so the panel shows them as Home Assistant draws them instead of an empty page:

- **Dashboards, views and sections made from a strategy**, such as the Areas or Map dashboards, or a custom strategy. Home Assistant's frontend builds their cards in JavaScript, and Home Assistant has no API that returns what it builds, so Vitro has no cards to draw. A strategy on the dashboard sends the whole dashboard; a strategy on a view or a section sends only that view.
- **Views with a built-in card Vitro doesn't draw yet**, such as `map` or the energy cards. Islands can't draw Home Assistant's built-in cards, so the view opens in Home Assistant's frontend, which draws all of it with one browser session. So does a view with a `shortcut` card that has no `label` or `icon` of its own and opens a dashboard, a view or a panel, or runs an action: Home Assistant names it after that page or action, which only its frontend knows. A `statistic` card that follows the energy dashboard's date picker goes there too.
- **Views with a built-in card whose config has something Vitro doesn't draw exactly as Home Assistant does**, such as an `entities` row of a domain whose control Vitro doesn't draw yet (a `cover` or `climate` row), an `attribute`, `cast` or `section` row, a tile feature Vitro doesn't have, an hourly forecast or a double-tap action. The same goes for a custom container Vitro draws (stack-in-card, layout-card) when an option it doesn't draw sits around a built-in card, and for the view's own header, footer, badges and sections. The rule is described under [What Vitro draws natively](#what-vitro-draws-natively).
- **Views with more islands than the device runs at once**, and views whose island card was given up on (see the table above).
- **Views with a custom card that draws with Home Assistant's own elements.** A few custom cards build their main control from elements of Home Assistant's frontend instead of shipping them, such as Better Thermostat's climate card, whose dial is Home Assistant's thermostat dial. An island has no Home Assistant frontend, so the card would show without them. The island notices this itself: once the card has drawn, it looks through everything the card drew, inside every shadow root and nested card, for elements named like Home Assistant's (`ha-…`, `hui-…`, `state-…`, `more-info-…`) that nothing on the page defines. When one is still undefined a few seconds later, the view opens in Home Assistant's frontend, and every view with that card type does the same for the rest of the session, until the dashboard's scripts change. No card is named in Vitro: Better Thermostat's mini card, which ships its own pieces, stays an island. The log line for the view names the card and the elements it missed.
- **Views with a layout Vitro doesn't draw**: any view `type` other than the ones in Tier 1, such as another custom layout. Vitro never guesses masonry for one.

The other views of the dashboard stay native. A view never goes back and forth: once it opens in Home Assistant's frontend for a card like that, Vitro doesn't take it back to draw the card again. The way back from one of these pages works as from any other Home Assistant page: Back, Reload dashboard and a tap on a dashboard return to the native view.

The island server only serves islands. Its proxy forwards what a card loads (`/api/`, `/local/`, `/hacsfiles/`, Home Assistant's static files and media, and the folders of the dashboard's own Lovelace resources), and only to an island page, whose address holds a random key, so other apps on the device can't use it. On the legacy engine, which loads only the dashboard's origin, the island server's loopback address is the one other origin it allows, and each page stays on its own origin: Home Assistant's pages never reach the island server, and islands never reach Home Assistant except through it.

## What works

"Compared with HA" means the [parity harness](#the-parity-harness) draws the card in Home Assistant's own frontend and in Vitro, at the size and density of the S1, a tablet and the Mirror, and checks they match. Cards marked "not yet" draw natively but haven't been checked that way, usually because the reference Home Assistant has no entity or no copy of the card to compare against.

### Built-in cards

| Card | Compared with HA | What to know |
|---|---|---|
| `tile` | Yes | Six tile features draw natively under the tile (listed under [What Vitro draws natively](#what-vitro-draws-natively)). Another feature, or `features_position: inline`, opens the view in Home Assistant's frontend. The climate, person, timer and update tiles show their badge, countdown or install status as Home Assistant does |
| `entities`, `entity-filter` | Yes | Entity rows of the domains Vitro draws with Home Assistant's own controls (toggles, `number` and `select` rows, script, scene, button and lock rows, and the plain state of sensors and the like), and the `divider`, `button`, `call-service`, `perform-action`, `text`, `weblink`, `buttons` and `conditional` rows. A row of another domain (such as `cover` or `climate`), a `section`, `attribute` or `cast` row, a custom row, or a header or footer opens the view in Home Assistant's frontend |
| `button`, `entity`, `glance`, `heading`, `light`, `area`, `alarm-panel` | Yes | |
| `entity-button` | Yes | Home Assistant's old name for `button`, drawn by the same card. Without an `entity`, it shows Home Assistant's error card, as Home Assistant does |
| `statistic`, `date`, `plant-status`, `toggle-group`, `shortcut`, `alert` | Yes | A `shortcut` named after the page or action it opens, and a `statistic` on the energy date picker, open their view in Home Assistant's frontend |
| `shopping-list`, `section` | Yes | Home Assistant's older names: the to-do list card on the shopping list, and its error card |
| `iframe` | No | Its page in a browser island of its own, sized and sandboxed as Home Assistant does. An iframe of one of Home Assistant's own pages opens the view in Home Assistant's frontend |
| `thermostat` | Yes | A heat/cool range shows both targets, as Home Assistant does |
| `humidifier` | Not yet | The reference Home Assistant has no humidifier to compare against |
| `gauge` | Yes | Severity colors, `segments` and `needle: true` |
| `sensor`, `history-graph`, `statistics-graph` | Yes | See [Graphs](#rendering-like-home-assistant-does) |
| `weather-forecast` | Yes | Current conditions and the daily forecast. An hourly or twice-daily forecast opens the view in Home Assistant's frontend |
| `clock`, `markdown` | Yes | The digital clock; the analog one opens the view in Home Assistant's frontend. A few Markdown tags draw nothing (see [What Vitro draws natively](#what-vitro-draws-natively)) |
| `picture`, `picture-entity`, `picture-glance` | Yes | With `camera_view: live`, a camera plays the way Home Assistant's player picks its stream: WebRTC, then HLS, then MJPEG, moving down the list when one fails, and showing stills when none plays. See [WebRTC cameras](#webrtc-cameras). Up to four cameras play video at once; any others play MJPEG until one stops. The cards' frame is compared with HA, the video itself isn't |
| `picture-elements` | Yes | State icons, labels and badges, icons, service buttons, images and `conditional` elements |
| `media-control` | Yes | |
| `todo-list`, `calendar`, `logbook` | Yes | Read live from Home Assistant, as its own cards read them. The calendar draws its month grid; a calendar that starts in its day or list view opens the view in Home Assistant's frontend |
| `grid`, `horizontal-stack`, `vertical-stack`, `conditional` | Yes | |
| Any other built-in card, such as `map` or the `energy-*` cards | No | Its view opens in Home Assistant's frontend |

### HACS cards

| Card | Compared with HA | What to know |
|---|---|---|
| Mushroom entity, light, fan, cover, climate, alarm, number, select, vacuum, template and title cards | Yes | |
| Mushroom chips card | Yes | Entity, template, weather, light, action, menu, back, spacer and conditional chips. Its own card-mod styles aren't applied yet |
| Mushroom person, lock, media player and humidifier cards | Yes, and they differ | Vitro's drawing doesn't match these yet, so they go to an island when Mushroom is a Lovelace resource |
| Bubble Card | Yes | The separator and the switch, state and name buttons, drawn from Bubble Card 3.4.3's own styles. Sub-buttons, the slider, `styles`, `state_content`, other card types, climate, humidifier, water heater and timer entities go to an island, and so does every Bubble card on a Home Assistant that uses Bubble Card modules |
| mini-graph-card, mini-media-player, clock-weather-card, modern-circular-gauge, bar-card, vertical-stack-in-card | Yes, and they differ | Vitro's drawing doesn't match these yet, so they go to an island when their script is a Lovelace resource |
| auto-entities | Yes | Filters by domain, entity id, name, state, attributes and group, with `not`, `or`, `and` and `sort`. Another filter key, such as `area` or `template`, sends the card to an island. The rows it matches are checked as they're found: one Vitro doesn't draw natively opens the view in Home Assistant's frontend |
| stack-in-card, layout-card | Yes | layout-card's masonry, horizontal, vertical and grid layouts. Any other layout goes to an island, or opens the view in Home Assistant's frontend when a built-in card sits inside it |
| button-card | Not yet | Drawn natively without JavaScript templates (`[[[ … ]]]`): its layouts, `state:` rules, colors, styles, actions and lock, with the dashboard's `button_card_templates` merged in. A config with JavaScript, a card or HTML in it, a timer's countdown, or a style outside the subset goes to an island (see [What Vitro draws natively](#what-vitro-draws-natively)) |
| swipe-card | Not yet | Its cards as swipeable pages with bullets or `1 / 3` pagination. Swiper effects, arrows and the scrollbar go to an island, unless a built-in card sits inside it |
| weather-card (bramkragten) | Not yet | The current conditions and the details. Its forecast reads an attribute Home Assistant no longer sets, so there's none, as in Home Assistant |
| Mushroom empty card | Not yet | Draws nothing and keeps its place in the grid |
| Any other custom card | — | Goes to an island, drawn by its own script |

To check a dashboard before you turn Vitro on, paste it into the [compatibility check on glassworks.dev](https://www.glassworks.dev/blueprints#vitro-check). It routes every card with the same rules as the panel.

## More-Info dialogs

Opening More-Info (a tap or hold on a tile, a button, an entity row or a Mushroom card) shows a native dialog styled after Home Assistant 2026, with no web view involved.
Like Home Assistant's, it's a centered dialog 580 px wide when the screen is wider than 870 px and taller than 500 px
(the Pixel Tablet), and a bottom sheet as wide as the screen otherwise (the S1 Plus, the Mirror). It draws on the
dialog color of your own theme, never on the view's theme, so a view with see-through cards still gets a solid dialog.
Its title names the entity on two lines, as Home Assistant does: the area and device over the entity's own name.

| Entity | Dialog |
|---|---|
| Light | Brightness dimmer, an HSV color wheel, a Kelvin color-temperature picker, and color swatches |
| Climate | A circular thermostat dial with mode buttons, and two thumbs for a heat/cool range |
| Cover | A top-down position slider |
| Fan, lock, media player, siren, script and scene | Their controls as in Home Assistant's dialog. A siren's tones, volume and duration, and a script that asks for fields, open Home Assistant's own dialog |
| Vacuum | Its status and battery, Home Assistant's robot posed for its state, the commands it supports and the fan speed. Cleaning by area opens Home Assistant's own dialog |
| Update | The installed and latest versions, the release notes, the progress while it installs, and Skip, Clear skipped and Update. An update with the backup option, a Supervisor update and one that installs itself open Home Assistant's own dialog |
| Sensor, binary sensor and the like | Its icon, name, last change and state, then the last 24 hours: Home Assistant's 5-minute statistics for a sensor with a state class ("5-minute aggregated"), its state history otherwise, and "Show more", which opens the history page in Home Assistant's frontend (Back returns to the dashboard). Under it, the Activity section lists the entity's recent changes and what caused them, as Home Assistant's does |
| Number and input number | Its slider or box, then the history and the Activity section |
| Camera | A live stream, chosen as on the picture cards (WebRTC, then HLS and MJPEG) |
| Switch | A toggle pill |
| Alarm panel | Its modes in a vertical selector, or while it's triggered, arming or pending, its pulsing status and Disarm. A mode that needs a code opens Home Assistant's code dialog: a keypad for a number code, a field for a text one |
| Select, input select | The options menu next to the entity's icon, then the history and the Activity section |
| Button, input button | Its icon, name and last press, a Press button, then the history and the Activity section |
| Alert | Its icon, name and last change, a toggle while the alert is active (turning it off acknowledges it), then the history and the Activity section |
| Event | Its icon and name, the time of the last event over its type, then the history and the Activity section |

Some details only Home Assistant's frontend has. Tapping an Activity entry, or an entry of a logbook card, opens Home Assistant's own More-Info dialog for that entity, where the entry's detail is one tap away. Closing it brings back the dashboard.

An entity whose dialog Vitro doesn't draw as Home Assistant does opens Home Assistant's own dialog over the same view, in its frontend: weather, timer, person, valve, water heater, automation, humidifier, text and the other domains with a dialog of their own, a group with members, and an entity with a custom More-Info element. Closing it, or Back, returns to the native view where it was.

Tiles can also carry six of the features Home Assistant adds under the card: `light-brightness`, `toggle`, `cover-open-close`, `fan-speed`, `select-options` and `numeric-input`. A tile with any other feature opens its view in Home Assistant's frontend.

A thermostat in `heat_cool` or `auto` with a low and a high target shows both, as Home Assistant does: on the
thermostat card and in More-Info, the dial gets two thumbs, and the − and + move whichever target you pick in the
middle of the dial. As in Home Assistant, that takes an entity that supports a target range (its
`TARGET_TEMPERATURE_RANGE` feature) and reports no single `temperature`; one that reports a single target too gets the
single control. Mushroom's climate card shows a pair of steppers
instead. The low target can't go above the high one.

## Accessibility

Vitro's controls work with TalkBack and with a keyboard or D-pad, the way Home Assistant's page works with a screen
reader:

- Controls are named after what they act on, and have the role Home Assistant gives them: a button, a switch (entity
  toggles), a check box (to-do items), a choice (HVAC modes, the tile's toggle), or a pressed button (the light's
  modes, the calendar's views).
- The thermostat dial and More-Info's brightness, speed, color temperature and position sliders read their value, and
  TalkBack's swipe up and down change it.
- With a keyboard or D-pad, Tab and the arrows move between controls and Enter presses them. On those dials and
  sliders, the left and right arrows change the value instead (page keys by a tenth, Home and End to the ends), and
  Enter switches between the two ends of a heat/cool range. Back or Escape closes More-Info, and focus stays in it
  while it's open.

## Themes

Vitro reads the themes Home Assistant already has, so a dashboard looks the same as it does in the browser:

- `frontend/get_themes` and `frontend/get_user_data` give the active theme, including the light and dark modes and a user's own theme choice.
- `var(--...)` chains resolve recursively, including `rgb-*` triples.
- Multi-stop linear and radial gradients, wallpapers and `box-shadow` elevation are drawn natively.
- Entity state colors follow the domain and the device class, such as amber for a light that is on.
- A view or a single card can set its own `theme:`, and `card_mod` styles apply on the container.

The resolved variables are also exported to Tier 3 islands, so native and custom cards match.

Vitro applies card-mod natively wherever it can draw the result the way Home Assistant does: the card's own surface (backgrounds, borders, corners, shadows, padding, a pinned position, fonts) and the inside of Markdown and clock cards. The details are under [Rendering like Home Assistant does](#rendering-like-home-assistant-does) and [What Vitro draws natively](#what-vitro-draws-natively).

## Fonts

Text draws in the font Home Assistant's page would use. Vitro reads a `font-family` list the way a browser does and uses the first family it can draw:

| Family | Where Vitro gets it |
|---|---|
| A font a Lovelace resource of type `css` declares with `@font-face` (also through `@import`, such as a Google Fonts stylesheet) | Downloaded from the same address the browser would use, then kept on the panel |
| Inter | Bundled with the app |
| Roboto, Noto, `sans-serif`, `system-ui` and the usual system names | Android's own sans-serif, which is Roboto |
| `serif`, `monospace`, `cursive` | Android's own serif, monospace and cursive fonts. Other generic names, such as `fantasy`, use its sans-serif |
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
| Graphs | The sensor card, the history graph and the statistics graph ask Home Assistant for the same history and statistics its frontend does and draw them its way: the sensor card's hourly averages as a smooth line along the bottom, and full charts for the other two, with time and value axes, gridlines, a legend, timeline bars for entities without a unit, and the statistics' min-to-max band. A thermostat, water heater or humidifier draws its current and target temperature or humidity, shaded while it heats, cools, dries or humidifies. A legend longer than two rows ends in HA's "More" chip, which expands it (or starts expanded with `expand_legend`). On a touch screen the legend has the taller rows HA gives a finger; on a panel without one, such as the Mirror, it keeps the compact ones. In a fixed `grid_options.rows` slot the chart shrinks so the chart and its legend fill it, as in HA. A long statistics window (`period: 5minute` over weeks) is thinned out the way HA's chart thins it, off the main thread, keeping its gaps and the band's edges. The paths are built when the data or the size changes, never while drawing. |
| Language and formats | Text, numbers, dates and times follow the signed-in user's profile in Home Assistant, the same way HA's frontend reads it: the language, the number format (including "none" and the system's), 12 or 24 hours, the date order, the first day of the week and the time zone (the panel's or the server's). Words HA's frontend shows ("Unavailable", "Today", "Add item") come from the signed-in Home Assistant's own translation files, kept per HA version and language so a panel that can't reach HA still has them, and state names come from the backend in the same language, with English for anything a language is missing. Numbers, dates, plurals and relative times ("5 minutes ago") follow the rules HA's page gets from the browser, from a copy bundled with Vitro so every language matches. Changing the profile in HA redraws the dashboard right away. "System" means the panel's Android locale, which is what HA's own page sees on the panel. Two known gaps: Persian dates use the Gregorian calendar, and calendar range titles in day-first languages lose a small mark ("24 – 30. Nov. 2026" instead of "24.–30. Nov. 2026"). |
| Cards | The clock reads `clock_size`, `time_format`, `time_zone`, `no_background` and the `--ha-clock-card-digital-font-size-*` variables. Mushroom chips honor `conditional` chips and ignore a chip's own `visibility` and `card_mod`, like Mushroom does. stack-in-card follows version 0.2.0. A card's `margin` insets it in its slot. |

## What Vitro draws natively

Home Assistant's own render is the spec. Vitro follows a strict rule: a card it draws claims a config only when it draws every option and value in it exactly as Home Assistant (or the custom card) does. Anything else falls back on purpose, so the card looks the way HA draws it. A custom card goes to a web island, so only that card pays for a web engine. A built-in card can't, because the island can't draw HA's own cards, so its whole view opens in Home Assistant's frontend instead. The same goes for a custom container Vitro draws (stack-in-card, layout-card) with a built-in card inside, and for the view's own header, footer, badges and sections. card-mod is the one exception: a built-in card keeps drawing natively with the card-mod parts Vitro can't draw left out, and the checker lists them as notes. Vitro makes the choice once for each card when it reads the dashboard, never while drawing.

The options and values each card supports live in one file, [`native-options.json`](src/main/resources/dev/glassworks/engine/vitro/compat/native-options.json), which [`compat/NativeOptions.kt`](src/main/java/dev/glassworks/engine/vitro/compat/NativeOptions.kt) checks every card's config against, with no code per card. card-mod's supported CSS lives in [`compat/VitroSupport.kt`](src/main/java/dev/glassworks/engine/vitro/compat/VitroSupport.kt), and button-card's subset in `ButtonCardCompat`. [`compat/CardCompat.kt`](src/main/java/dev/glassworks/engine/vitro/compat/CardCompat.kt) sends a custom card to its island, and `WebFallback` sends a view to Home Assistant's frontend.

| Part | Native | Falls back (an island for a custom card, Home Assistant's frontend for a built-in one) |
|---|---|---|
| Card types | The built-in and HACS cards in the tier table above | Any other `custom:` card. Built-in cards Vitro doesn't have yet (such as `map` or `energy-*`) send their view to Home Assistant's frontend instead |
| Views | Masonry views with Home Assistant's columns (one per 300, 600, 900 and 1200 px of screen, each card in the first column under 5 `getCardSize` units, else the shortest), laid out exactly as Home Assistant lays them out. A large view draws its first screen whole before the cards below it are built, and those follow in short steps that keep the dashboard responsive. sidebar views with `view_layout.position`, sections and panel views, and layout-card's four view layouts with their `layout:` and each card's `view_layout` | Any other view `type` opens in Home Assistant's frontend |
| Card options | Tile features `light-brightness`, `toggle`, `cover-open-close`, `fan-speed`, `select-options` and `numeric-input`. Entity rows of the domains Vitro draws with HA's controls, and the special rows `divider`, `button`, `call-service`, `perform-action`, `text`, `weblink`, `buttons` and `conditional` (with Home Assistant's visibility conditions). `picture-elements` state icons, labels and badges, icons, service buttons, images and conditional elements. Mushroom chips: entity, template, weather, light, action, menu, back, spacer and conditional. Bubble Card's separator and its switch, state and name buttons. layout-card's masonry, horizontal, vertical and grid layouts. Tap and hold actions | Any other tile feature, inline features, an entity row of another domain, `section`, `attribute`, `cast` and custom rows, entities headers and footers, any other `picture-elements` element, chip, Bubble Card type or layout, and double-tap actions. `native-options.json` gives the reason for each |
| card-mod sheets | `"."` (or a plain string) on every card, `"ha-markdown$"` on Markdown cards and `"hui-clock-card-digital$"` on the clock | Any other path, such as `"mushroom-shape-icon$"`, and nested selector maps |
| The card's own rule | `ha-card { }`, `:host { }`, `:host ha-card { }` with backgrounds (colors and linear gradients), solid borders, `border-radius`, `box-shadow`, `opacity`, `padding`, `margin`, `height: 100%`, the opacity `transition`, a pinned card (`position: fixed` with offsets, `width`, and a `translate(-50%)` that centers it at `left: 50%` or `top: 50%`), `font-family` on every card (see [Fonts](#fonts)), and `font-size` on tile and Mushroom cards. A stack's `:host { }` with `padding`, `margin` and its gap variables | Background images, radial gradients, dashed borders, inset shadows, other transforms, filters, animations, fixed heights, `color` on cards other than Markdown and the clock |
| Selectors | Inside Markdown and the clock, the selectors listed under "Markdown sheets" in the table above, matched against the elements Vitro rebuilds | Selectors that reach inside a card Vitro doesn't rebuild (`ha-card .primary`), `::before` and `::after`, `:has()`, `:host()`, and `@media` or `@supports` rules |
| button-card | Its layouts, `state:` rules, `color` and `color_type`, `size`, `aspect_ratio`, `show_*` options, labels, `lock`, `hidden`, plain-text `custom_fields`, its tap, hold and double-tap actions (toggle, more-info, navigate, url and service calls), and `styles` for the card, the icon and its cell, the name, state and label, the grid and the custom fields, with the dashboard's `button_card_templates` merged in first | JavaScript templates (`[[[ … ]]]`) anywhere in the config, cards or HTML in it, `extra_styles`, the spinner, the live stream, protected actions, actions on the icon, background images and other styles outside the subset (`ButtonCardCompat` gives the exact one) |
| swipe-card | Its cards as pages with Swiper's bullets or `1 / 3` pagination, `slidesPerView`, `spaceBetween`, `centeredSlides`, `loop`, `autoplay`, `start_card` and `reset_after` | Other Swiper parameters (effects, navigation arrows, a scrollbar, vertical pages) and `card_width` |
| Markdown | Everything HA's sanitizer keeps, except the tags in the next column | `<ha-alert>`, `<ha-qr-code>`, `<ha-svg-icon>`, `<details>`, `<audio>`, `<video>`, `<picture>`, checkboxes and image maps |

The same rules are published at [glassworks.dev/vitro-compat.json](https://www.glassworks.dev/vitro-compat.json), `native-options.json` included, and the [site's compatibility check](https://www.glassworks.dev/blueprints#vitro-check) routes cards with them. A test keeps both equal to the engine's.

Some things never send a card to an island:

- **What Home Assistant drops itself.** Declarations outside a rule and tags outside HA's sanitizer are dropped by HA too, so dropping them is what matching HA means.
- **`!important`**, which card-mod keeps and HA honors. On the card itself, Vitro lets an `!important` declaration win over the card's other card-mod declarations, as the browser does. In Markdown and clock sheets, Vitro keeps the value but not the priority, so a rule that beats HA's own styles only because of `!important` may lose to them in Vitro. The checker lists that as a note.
- **Things that don't change the look on a touch panel**, such as `cursor`, `transition`, `user-select`, `pointer-events: auto`, `will-change`, `-webkit-tap-highlight-color`, `outline: none`, and `:hover`, `:focus` and `:active` rules. Each one is listed with its reason in `VitroSupport`.
- **Known gaps** that Vitro still draws natively, because an island wouldn't do better or the native work is underway: `backdrop-filter` (an island can't blur the native view behind it either), `text-overflow` and `white-space: nowrap` (they only show when text doesn't fit), small `position: relative` nudges, the Mushroom chips card's own card-mod, and card variables a native card doesn't read. The checker lists these as notes.

A card goes to the island only when the island can draw it. The island runs the card's own code with a small stand-in for Home Assistant's frontend, and loads that code from the dashboard's Lovelace resources. That has three consequences:

- **Built-in cards never go to an island.** The island doesn't include Home Assistant's built-in cards, so a built-in card Vitro draws (a `tile`, say) with an option outside the supported set opens its view in Home Assistant's frontend instead. The same goes for a custom container Vitro draws, such as stack-in-card or layout-card, when a built-in card sits anywhere inside it, since that card would show as a plain summary in the island. A custom wrapper Vitro doesn't know that builds built-in cards through `loadCardHelpers` shows them as summaries too.
- **A custom card Vitro also draws natively** (Mushroom, Bubble Card, mini-graph-card and the rest of the HACS list) goes to the island only when a Lovelace resource provides its script, matched by file name (`mushroom.js`, `bubble-card.js`, ...). For a fallback caused by card-mod styles alone, card-mod has to be a resource too. Otherwise the card stays native, and the checker notes what it would have fallen back for and that the island has no script for it.
- **A custom card with no native renderer** goes to the island regardless, since that's its only chance to draw. The checker notes it when no resource seems to provide its script. Built-in cards Vitro has no renderer for, such as `map` or the `energy-*` cards, never go to an island: their view opens in Home Assistant's frontend.

### The compatibility checker

The checker reports every card Vitro builds in a view, whether it renders natively or in an island, and the exact reason for each island:

- **Logcat:** each time Vitro builds a view, it logs one `Island <where>: <why>` line per fallback under the `Glassworks.Vitro` tag.
- **The test app:** tap the HUD to expand it. It lists the islands in the current view.
- **From code or a test:** `CompatChecker.check(dashboard, IslandScripts.of(resources)).format()` gives the same report as text, starting with a line such as `116 cards: 116 native, 0 island (fully native)`. Pass the dashboard's Lovelace resources so the report knows which scripts the islands can load. Without them, it assumes every card's script and card-mod are there.

To check your own dashboards without a panel, save each Lovelace config (`lovelace/config` over the WebSocket API) as a `*-new.json` file in one folder and run:

```sh
VITRO_PARITY_DIR=/path/to/folder ./gradlew :engine-vitro:testDebugUnitTest --tests '*CompatCheckerLocal*' -i
```

The report covers the cards in the dashboard config, auto-entities' own `card:` included. The cards `auto-entities` builds at runtime for each matched entity aren't in it, so an island one of them opens doesn't show in the counts or the log. Its notes say a view with auto-entities is native only if the rows it matches are: on the panel, Vitro checks those rows when it finds them, and a row it doesn't draw natively sends the view to Home Assistant's frontend. This check reads only the exported configs, not the resource list, so it assumes every card's script and card-mod are Lovelace resources the islands can load. On the panel, a card that Vitro draws natively stays native when its script or card-mod isn't a resource, even though the check lists it as an island. A custom card Vitro has no native renderer for still goes to an island, and without its script that island has nothing to draw: a missing resource is never a native fallback for it.

## Key optimizations

- **One subscription per dashboard:** Vitro deep-scans every view of the dashboard (including nested custom-card configs) and subscribes once to the entities they reference, asking for the view on screen first and the rest after it, so the first view draws as soon as its own states arrive and a view switch asks Home Assistant for nothing. A dashboard edit adds only the new entities. A dashboard that references more than 2,000 entities subscribes view by view instead, and a view that follows every entity (auto-entities, an island that walks `hass.states`, an area card) does so only while it's on screen.
- **O(changed) selective reactivity:** `EntityStore` updates only the cards bound to each changed entity instead of cloning `hass.states` across the whole tree.
- **Keep-alive view cache:** Switching views toggles the visibility of pre-built native trees instead of tearing down and recreating cards. The cards of a hidden view stop drawing live changes and catch up from the store when the view shows again.
- **Startup warm-up:** As soon as the dashboard is parsed, a background thread decides each card's route (`CompatWarmup`), starting with the view that opens first, so the view being built finds the answers ready.

## The legacy build (Android 6)

The legacy build runs on Android 6 panels such as the lululemon Studio Mirror, with an older GeckoView that is locked to the dashboard's Home Assistant. Vitro draws dashboards there the same way, and islands load in that GeckoView.

With Vitro on, that GeckoView allows exactly two origins: your Home Assistant, and the app's own island server on `127.0.0.1`. Each page stays on its own origin. Home Assistant's pages, such as sign-in, history, the logbook and settings, load straight from Home Assistant, as they do with Vitro off, and never reach the island server. Islands reach Home Assistant only through the island server.

## Security: the island server

Islands load from a small web server inside the app, `VitroIslandServer`. It is built so that nothing outside the island can use it:

- **It never listens on the network.** The server is bound to `127.0.0.1`, so only apps on the panel itself can reach it, never another device on your network.
- **Other apps on the panel can't use it.** Every app on a device can reach `127.0.0.1`, so the server answers only requests that come from an island page. Each island page lives under a random key in its address, new every time the app starts, and so does everything the page loads from the server. When a card asks for one of Home Assistant's addresses directly, such as an entity picture, the browser names the island page as the request's referrer, which carries the key, and the server moves that request under the key. The browser never names an island page to another site or another app's port, so the key stays between the islands and the server. The key isn't a cookie, because the browser sends a cookie set on `127.0.0.1` to every app listening there, whatever its port. Each island's address is random as well. A request also has to name the server itself as its host, which stops a web page from reaching it through a look-alike address (DNS rebinding).
- **It only serves islands.** Home Assistant's own pages, such as sign-in and settings, load from Home Assistant itself, so their WebSocket and sign-in never pass through it.
- **Your token goes only to your Home Assistant.** The server forwards an island's requests to the one Home Assistant the panel is signed in to and adds the panel's token there. It forwards only the paths cards load (the API, `/local`, `/hacsfiles`, Home Assistant's static files and media, and the folders of the dashboard's own Lovelace resources), refuses paths that try to climb out of those folders, and follows redirects only within Home Assistant.
- **Answers made with the token aren't cached,** so they can't be served again after a sign-in change, and the memory proxied answers can use is capped.
- **Its logs leave out tokens, queries and the key,** and the compatibility checker's reasons leave out addresses.

## Known limits

- **Vitro is experimental.** Turn it off under **Settings → Labs** whenever something looks wrong; the app goes back to GeckoView alone.
- **Islands need GeckoView.** The standalone `:vitro-test` app has no GeckoView, so it shows a placeholder for every island.
- **A built-in card Vitro draws can't fall back to an island.** When its config has something Vitro doesn't draw, its whole view opens in Home Assistant's frontend, which costs a browser session for that view. Only card-mod parts are left out instead, and the compatibility checker lists each one as a note.
- **Cards not yet compared with Home Assistant** (listed under [What works](#what-works)) may differ from Home Assistant's look.
- **Small differences remain on 1.5x screens,** such as the S1 and the Mirror: some text and chart labels sit a fraction of a pixel off.

## The `Engine` seam

Vitro sits behind the same `Engine` interface as the GeckoView engine, and hands anything it can't draw to the engine it wraps. Today that fallback is GeckoView. The seam is deliberate: when a lighter web-component engine such as Servo or a purpose-built VitroView is ready, it can replace GeckoView for Tier 3 and for the fallback without touching the native tiers or the panes.

## WebRTC cameras

Cameras play over WebRTC the way Home Assistant's own `ha-web-rtc-player` does, through HA's `camera/webrtc`
WebSocket API, with candidates trickled both ways and libwebrtc decoding the picture (hardware H.264 where the
device has it). The player is the [ha-webrtc](../../libs/ha-webrtc/README.md) library. Its Vitro side lives in its
own module, `engines/engine-vitro-webrtc`, which registers the `WEB_RTC` player with Vitro's camera seam
(`camera/CameraStream.kt`) when an app calls `VitroWebRtc.register()`.

It's a module of its own because libwebrtc is big, adding about 4.6 MB to an `armeabi-v7a` APK and 6.3 MB to an
`arm64-v8a` one, so an app chooses to carry it. The S1 app and Glassworks for generic Android both do.

On Vitro, the player is muted, letterboxed in a view the card sizes to the picture, and closes its connection as
soon as it's paused (a hidden view, or HA's page in front), as the HLS player does. It reconnects when a connection
that was playing drops, and gives up after 20 seconds without video, so the stream choice falls back to HLS.

## Modules & testing

```sh
./gradlew :engine-vitro:testDebugUnitTest
./gradlew :engine-vitro-webrtc:testDebugUnitTest :ha-webrtc:testDebugUnitTest   # WebRTC cameras
./gradlew :vitro-test:assembleDebug
./gradlew test :kiosk:assembleDebug :generic-android:assembleDebug
python3 -m unittest tools/vitro/test_showcase_ha.py   # the parity showcase server
tools/vitro-parity/run.sh                             # the parity harness: Vitro vs Home Assistant, card by card
```

### The parity harness

The harness renders a corpus of cards in Home Assistant's real frontend and in Vitro at each panel's size and density, compares them (pixels, card bounds, text sizes), and writes a side-by-side report to `build/reports/vitro-parity/index.html`. Its committed references let it run in CI with no device and no Home Assistant. Besides the hand-written cases, a state matrix crosses every card Vitro draws natively with the entity states that change its drawing, and whole-dashboard cases check how the cards sit together. A case that drifts further than `src/test/parity/baseline.json` allows fails the build, and a known mismatch someone is fixing sits in `src/test/parity/open.json` until the fix lands. Run `tools/vitro-parity/run.sh --refresh` to capture new references from the harness's own made-up Home Assistant. Details are in [tools/vitro-parity](../../tools/vitro-parity/README.md).

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

The `hacs` view names Mushroom, Bubble Card, mini-graph-card and clock-weather-card, but the showcase doesn't ship their scripts. Without them Home Assistant's frontend can't draw those cards, so the side-by-side has nothing to compare there. To compare them, download each card's release bundle (the `.js` file from its GitHub release or your HACS folder) into one folder, then serve that folder and register each bundle as a resource:

```sh
python3 tools/vitro/showcase_ha.py --port 8091 --local-dir ~/hacs-bundles \
  --resource /local/mushroom.js --resource /local/bubble-card.js \
  --resource /local/mini-graph-card-bundle.js --resource /local/clock-weather-card.js
```

Use the file names the folder actually has. A `.css` resource is loaded as a stylesheet, and anything else as a module.

The standalone `:vitro-test` debug APK is Vitro on its own, with no GeckoView linked, so it draws Tier 1 and Tier 2 cards only. Point it at the showcase through `adb reverse`, so the device's loopback reaches this computer:

```sh
adb reverse tcp:8091 tcp:8091
adb shell am start -n dev.glassworks.vitro.test/.VitroTestActivity \
  --es url http://127.0.0.1:8091/vitro-parity/sections \
  --ez dark true --ez hud true
```

`--ez dark true` forces the dark theme (use `false` for light; the choice sticks until you pass it again or clear the app's data), and `--ez hud false` hides the metrics overlay, which is on by default. The same activity also runs against Clarity's `fakeha.py` or a real Home Assistant, by changing the URL.
