# Vitro parity harness

The harness checks how closely Vitro draws each card compared with Home Assistant. Home Assistant's own render is the spec, and the harness turns "looks off" into a list. It renders every case in a corpus twice:

- **Home Assistant:** HA's real frontend, the build Clarity pins, served by a fake Home Assistant that answers from the case's own made-up fixture (see [Where Home Assistant comes from](#where-home-assistant-comes-from)), in headless Chromium at the panel's CSS size and density, with card-mod loaded before the dashboard (as `frontend: extra_module_url` does) and the kiosk's header hidden. A panel with a touch screen (`"input": "touch"` in `frames.json`: the S1 and the tablet) gets a touch screen in Chromium too, so HA's page has the coarse pointer it has on the panel; the Mirror has none.
- **Vitro:** `VitroView` under Robolectric's native graphics, at the same size and density, fed the entity states HA's page held when it drew the reference, HA's themes, the templates HA rendered, and the history and statistics HA's graphs got.

It then compares each card: a perceptual pixel diff, the worst small region's color, the card's bounds, and every text HA drew (found in Vitro's views or not, and at what font size). It writes a report with HA, Vitro and the diff side by side, and fails when a card drifts past its threshold.

## Commands

```sh
tools/vitro-parity/run.sh                    # compare with the committed references: no HA, no device (CI runs this)
tools/vitro-parity/run.sh --refresh          # capture new references from the fake HA, then compare (no real HA)
tools/vitro-parity/run.sh --local --refresh  # the real dashboards, from a local corpus that never enters the repo
```

`run.sh` without `--refresh` is the same test CI already runs in `./gradlew test`:

```sh
./gradlew :engine-vitro:testDebugUnitTest --tests '*VitroParityHarnessTest*'
```

Options: `--frames s1,tablet`, `--cases tile-light,clock`, `--out <dir>`, and `--update-baseline`, which rewrites `baseline.json` with the drift found now. The Gradle properties behind them are `-PvitroParityFrames`, `-PvitroParityCases`, `-PvitroParityOut`, `-PvitroParityUpdateBaseline=true`, `-PvitroParityLocal=<dir>` and `-PvitroParityCollect=true`.

The capture tool's helpers (options, asset paths, scrubbing, which cards are slots) have their own tests, which need nothing but Node: `npm test --prefix tools/vitro-parity`. The fake HA's parity home, the corpus's fixtures and the state matrix have theirs in Python: `python3 -m unittest discover -s tools/vitro-parity/test`.

The report is `engines/engine-vitro/build/reports/vitro-parity/index.html`, with `report.md` and `report.json` next to it for scripts and issues.

## Where Home Assistant comes from

The committed corpus never depends on anyone's real Home Assistant. `capture.mjs` starts Clarity's fake HA (`tools/perf/fakeha.py`) on 127.0.0.1 with this folder's home module, `fakehome.py`, and drives it case by case:

- **HA's frontend** is the pinned build Clarity serves (`tools/perf/hafrontend.py`, the `home-assistant-frontend` wheel, checked by SHA-256 and cached in `~/.cache/glassworks-perf`). It ships its own Roboto (`/static/fonts/roboto/`), which the fake serves as HA does and every captured page gets as a font from its first script.
- **Inter** is pinned the same way, for themes that name it (the Vitro Glass theme does): every captured page gets the variable Inter 4.001 that Vitro draws with, the file the app bundles (`core/src/main/res/font/inter.ttf`), checked by SHA-256 before it's used. Without it, the page would take whatever Inter the capture host has installed, and a static Inter collection draws every weight under 400 as Inter Thin.
- **Each case's fixture** (the `fixture` block of `corpus/<case>.json`) is everything HA would know: entity states, the entity, device and area registries, users, history, statistics, to-do items, logbook entries, forecasts and calendar events. Before each case the capture hands it to the fake, and the page loads afresh.
- **The shared inputs** come from `home.json`: the fixed instant every page and Vitro's clock show, the config (time zone, units), the themes and the signed-in user.
- **HA core's words and icons** for states ("Heat/Cool", "Armed away") come from `hacore-2026.11.0.json`, which `hacore.py` cuts from the `homeassistant` wheel of the same release (pinned by SHA-256). Run it again for a newer HA.
- **Templates** (Markdown, Mushroom, conditions) are rendered by the fake with jinja2 and the HA helpers the corpus uses (`states`, `is_state`, `state_attr`, `now`, …). A template that needs a helper the fake lacks fails the case's capture.
- **Community cards** are Lovelace resources at `/hacsfiles/`, as HACS serves them: Mushroom, card-mod, stack-in-card, layout-card, auto-entities, mini-graph-card, mini-media-player, Bubble Card, vertical-stack-in-card, bar-card, clock-weather-card and modern-circular-gauge. `cards.json` pins each one's release, its license and the SHA-256 of every file it loads, and `cards.py` downloads them into `~/.cache/glassworks-perf/cards` on first use. They're never committed. A card pinned `on_demand` (Better Thermostat UI, which is AGPL-3.0 and bundles its own Mushroom elements, and battery-state-card, scheduler-card and calendar-card-pro, for the island checks) is served at `/hacsfiles/` too but isn't a Lovelace resource, so no case's page loads it unless a probe adds it.

Templates render in jinja2's sandbox: the fake listens on 127.0.0.1, but anything that reaches its port may send one. The fake logs any WebSocket command it doesn't answer, and the capture lists them at the end: a newer frontend or card that asks for something new can't pass unnoticed.

### Fixtures

A fixture uses HA's own shapes, so a new case is mostly copied from another:

```json
"fixture": {
  "states": { "light.desk_lamp": { "state": "on", "last_changed": "-35m", "attributes": { "brightness": 166, "friendly_name": "Desk Lamp" } } },
  "entities": [{ "ei": "light.desk_lamp", "pl": "zha", "tk": "light", "di": "dev-desk-lamp", "np": "device" }],
  "devices": [{ "id": "dev-desk-lamp", "name": "Desk Lamp" }],
  "history": { "sensor.living_room_temperature": { "wave": { "min": 19.6, "max": 23.4, "period": "24h", "step": "10m", "decimals": 2 } } }
}
```

| Key | What |
|---|---|
| `states` | Entity id → `state`, `attributes`, `last_changed` (and `last_updated`), like HA's state objects |
| `entities`, `devices`, `areas`, `floors`, `users`, `persons` | The registries: `entities` in `list_for_display`'s short form (`ei`, `pl`, `di`, `ai`, `np`, `en`, `ic`, `tk`, `dp`), the rest as HA lists them. `np: "device"` puts the device's name before the entity's own, as HA does for an integration's entities; without it HA shows the entity's own name alone, which Vitro doesn't do yet, so every corpus entry on a device sets it |
| `history` | Entity id → `wave` (`min`, `max`, `period`, `step`, `decimals`, `quantum`, `phase`: a value sampled at each step), or `points`/`steps` (`[[time, state]]` or `[[time, state, attributes]]`). One timeline: the entity's live state, last change, last update (and a series' own attributes) come from the series at now, and statistics are computed from the same series for entities with a state class. After editing a series, run `python3 tools/vitro-parity/fakehome.py sync` so the fixture's `states` say the same (a test checks it) |
| `todo`, `forecast`, `calendar` | Entity id → items; → `{daily: [...]}`; → events (`summary`, `start`, `end`) |
| `logbook` | Entries as HA's `logbook/event_stream` sends them, with `when` as a time |
| `release_notes` | Entity id → an update entity's release notes in Markdown, as `update/release_notes` answers |
| `user_data` | The signed-in user's frontend profile, by key: `{"language": {"language": "de", "number_format": "decimal_comma", "time_format": "24"}}` (HA's `frontend/get_user_data`, which the page subscribes to). The fake answers translations in that language, with English for every key it lacks, as HA core does (German and French are in `hacore-<version>.json`: `LANGUAGES` in `hacore.py`). The reference records the profile as `locale`, and Vitro draws the case with it. Without it the user has no profile: English and HA's defaults |
| `admin` | `false` signs the page in as a user who isn't an administrator (the same user, `is_admin` off), for what HA shows such a user: its error and warning cards' icon alone. The reference records it as `user`, and Vitro draws the case for the same user. Without it the user is the home's owner and an administrator |
| `templates` | Not needed: the fake renders templates itself |

Times are relative to `home.json`'s "now", so a capture is the same on any day: a number is seconds from now, `"-2h30m"` an offset, and a string `"@-2h"`, `"@D+1"` (a local date) or `"@D+1 14:30"` (a local time) is resolved where HA would send a date. A fixture's own pictures (album art, a person's photo) sit in `fixtures/images/`, drawn by `fixture_images.py`.

## What a refresh does

1. Real HA only (a local corpus): `capture.mjs prepare` saves the corpus to one dashboard, `vitro-harness` (admin only, hidden from the sidebar), as one sections view per card. It's the only thing the harness writes to Home Assistant: it never changes an entity or another dashboard. Each save is marked (`glassworks_vitro_harness` in the config), and a `vitro-harness` dashboard the tool didn't make (not hidden and admin-only, or a config without the marker) stops the run instead of being overwritten. One exception: a config saved by an older version of the tool, before the marker, is still taken as the tool's own when it's titled `Vitro harness` and every view path is a corpus case ID. Such a config is replaced.
2. A collect run of the Robolectric test lists the templates Vitro asks Home Assistant to render for each case (Markdown content, Mushroom fields, card-mod sheets). They differ from the frontend's own, so they're rendered separately.
3. `capture.mjs capture` opens each case on each panel, waits until two screenshots in a row are identical, then crops the card plus 8 CSS px around it (a view case keeps the whole page). A view's card slots are its top-level cards: a Sections view's grid cells, and in any other view each card that sits in the view and in no other card (`lib/slots.mjs`). A stack is one slot and its rows aren't slots of their own, as Vitro counts them, so the two sides' cards pair up in order. It records the card's bounds, the text runs and their fonts, and the states of the entities the card reads, taken from the page's own `subscribe_entities` stream, so they're exactly what HA drew. It also records the templates Vitro asked for, rendered right then. The images HA drew in the card (an `<img>`, an SVG image or a CSS background inside the card, read from the rendered page) go into `assets/` when HA loaded them from its own `/local/` or `/static/`, so nothing is guessed from configs or Markdown. For the committed corpus, only `/static` is allowed. If Vitro later asks the fixture server for an image the capture didn't save, that case fails as a missing input instead of counting as drift. Along with the states, it saves the display data Vitro reads live from HA for those entities, so Vitro formats values the way HA does (display precision, icons, state labels, device names). That data is the entity registry's display entries, their devices' names, the areas the case's area cards name (name, icon, picture, temperature and humidity sensors), and the icons and English state labels of their domains and integrations. It's cut down to what Vitro reads, and HA's device ids are replaced by `device-1`, `device-2` and so on. Every page gets HA's own Roboto (its `/static` files, kept in memory) and the app's Inter before HA's code runs, as a panel has Roboto as a system font: HA's font face asks for `local("Roboto")` first, and on a capture host without it HA's charts would measure their axis labels in the fallback font while the web font loads. The page's clock is frozen at `home.json`'s instant (a real HA's: the most recent 10:08 in its time zone), and Vitro's clock, its relative times, media progress and graph windows read that same instant (`VitroCardContext.now`). Graphs get the same treatment: the capture records every `history/*` and `recorder/*` call the card's page makes (and the REST history mini-graph-card reads), with what came back (the reference's `ws`), and Vitro's cards get those answers for the same calls at the same instant. A call of Vitro's that matches none of HA's, field for field (its times included), fails the case: Vitro asked for other data than HA drew.
   The capture writes nothing until every selected case has succeeded. Then the references, their images and `refs/shared.json` (HA's version, config and themes) are written together. A refresh of only some cases (`--cases`, `--frames`) stops if HA's shared inputs changed since the last full refresh: the references it leaves alone were drawn with the old ones.
4. The comparison run writes the report.

A local corpus's real HA comes from `~/.t3/glassworks-m1/vitro-parity/ha.json` (`{"origin": …, "token": …}`, or `VITRO_PARITY_HA=<file>`). The token and origin are never printed or written.

## Corpus

| Path | What |
|---|---|
| `engines/engine-vitro/src/test/parity/frames.json` | The panels: S1 960×480 CSS at 1.5, Pixel Tablet 1280×800 at 2, Mirror 720×1280 at 1.5, and whether each has a touch screen |
| `…/corpus/<case>.json` | One card per file: `card`, and optionally `title`, `frames`, `dark` (default true), `theme` (a view theme), `span` (section columns), `threshold`, `masks` (CSS px regions relative to the card, each with a `why`; a negative `x` counts from the card's right edge), `more_info` (an entity whose More Info dialog opens over the card's view: the dialog is what's compared, see below), `resources` (Lovelace resources of type `css` from `fixtures/`, see below), `expect` (`{entity_id: state}`: a refresh writes no reference for the case while an entity is in another state, so a case about a light that is on never quietly becomes one about a light that is off), and `fixture` (what the fake HA serves for it, above). A view layout's case has `view_config` (a whole view: a sidebar view, a masonry view, a layout-card view) instead of `card`: it's saved as it is, and compared card by card and as a page. `route` says where Vitro's compat rule sends the case's card on a panel whose Lovelace resources include every community card's script (see [The route key](#the-route-key)), and `generated` marks a case `matrix.py` writes |
| `…/refs/<frame>/<case>.png` and `.json` | Home Assistant's render and what came with it |
| `…/refs/shared.json` | HA's version, the config fields Vitro reads, and the themes |
| `…/home.json` | The fake HA's shared inputs: the fixed instant, the config, the themes and the signed-in user |
| `…/assets/` | Images the fixture server hands Vitro, standing in for HA's `/static` |
| `…/fixtures/` | A case's own pictures (`images/`), stylesheets and fonts, at `/local/vitro-harness-fixtures/` on HA's origin. The capture answers those requests in the browser and adds each sheet to HA's page the way HA loads a `type: css` resource, so nothing is added to Home Assistant. Vitro gets the same files from the fixture server |
| `…/baseline.json` | Known drift per `frame/case`, with a `reason` |
| `…/open.json` | Known mismatches per `frame/case`, each with the `owner` of its fix and a `reason` (see [Open bugs](#open-bugs-openjson)) |

A local corpus (`--local [dir]`, default `~/.t3/glassworks-m1/vitro-parity/harness-local`) has the same layout. Its cases name a real dashboard view instead of a card, `{"dashboard": "s1-home-vitro", "view": "home", "frames": ["s1"]}`, and compare the whole view: every card slot in order, plus the full page.

### The state matrix and whole dashboards

Hand-written cases show the state someone was fixing, so `matrix.py` generates the rest of the corpus: every card Vitro draws natively, crossed with the entity states its HA or Mushroom source reads (on and off, unavailable, unknown, brightness, color, color temperature, position, percentage, unit, `entity_picture`, a long name), and never with states the card ignores. It writes two kinds of case, each marked `"generated": "tools/vitro-parity/matrix.py"`:

| Cases | What | Frames |
|---|---|---|
| `m-<card>-<state>` | One card in one entity state, or one group of states for the multi-entity cards (`entities`, `glance`, `toggle-group`) and every Mushroom chip type | The S1 only: states draw the same on every frame, and the S1 is the smallest landscape frame, where a cut or a misplaced control shows first |
| `d-<name>` | A whole view: a sections home, a masonry home, a dense Mushroom view, and Bubble Card buttons and separators in a masonry and a sections view | Every frame, because layout bugs show up between cards |

```sh
python3 tools/vitro-parity/matrix.py           # write the generated cases (and delete the ones it no longer makes)
python3 tools/vitro-parity/matrix.py --check   # exit 1 when the committed cases differ from what it makes
python3 tools/vitro-parity/matrix.py --list    # print the case ids
```

After a change to `matrix.py`, run it, then capture the new or changed cases' references (`run.sh --refresh --cases <ids>`). `test/test_matrix.py` checks that the committed cases are what the generator makes and that each has its references. It never overwrites a hand-written case with a generated case's name. When it retires a case, it names it: remove its `open.json` and `baseline.json` entries (and its refs) before refreshing the rest.

### The route key

Since the strict compat rule, a card Vitro draws claims only the options it draws exactly as HA does, and anything else falls back. A case's `route` says where that rule sends the case's card, as a panel would with every community card's script a Lovelace resource:

| `route` | Means | What the harness compares |
|---|---|---|
| `native` (the default) | Vitro draws the card | Vitro's card with HA's |
| `island` | A custom card that goes to its island on such a panel | The committed corpus loads no community scripts as resources, so Vitro's native card is still compared, and its differences usually sit in `baseline.json` |
| `frontend` | Its view opens in HA's frontend | Nothing: HA draws it on the panel too. The case reports a match with a note saying why |

The harness works out the route itself before it renders a case. A case whose route differs from what it says fails, because that's a change in what Vitro claims to draw. An `open.json` entry on a `frontend` case fails too, since nothing would ever check the bug it names.

### More Info cases

A case with `more_info` opens HA's More Info dialog for that entity over the card's view, as a tap on the card would (`hass-more-info`), and compares the dialog instead of the card: its surface is the one slot (HA's `<dialog>`, a centered dialog or a bottom sheet as `ha-adaptive-dialog` picks for the window), its texts are the dialog's own, and its PNG is the window as it shows the dialog (never grown or shot full page, because the sheet's height follows the window). Vitro opens its sheet over the same view and crops its surface. The history and statistics calls the dialog makes are recorded and answered like a graph card's. HA's Settings and menu buttons are admin-only pages Vitro's sheet doesn't offer, so the `more-info-*` cases mask them.

### Can a custom card draw in an island?

`island-check.mjs` renders one custom card in HA's frontend on the fake HA, with its pinned script from `cards.json`, and lists the custom elements it draws with that an island page lacks: HA's own elements that are neither the card's nor among the island's stand-ins. The island page finds such elements itself when the card draws (`frontend-watch.js` in the engine's resources) and sends `needsFrontend` over its bridge, and Vitro sends the card's view to HA's frontend. This tool shows what HA's side draws with. It writes nothing.

```sh
node tools/vitro-parity/island-check.mjs <case.json> --card better-thermostat-ui-card [--frame tablet] [--shot out.png]
```

`island-probe.mjs` runs the other side: the island page Vitro would serve for the card (written by `IslandPageDumpTest`), with the card's pinned script and the island's bridge answered by the probe, and reports what the card drew, which elements it found undefined, and what it sent over the bridge (a `needsFrontend` when the card draws with HA elements the island lacks). Example cases are in `island-cases/`: Better Thermostat's cards, battery-state-card, scheduler-card and calendar-card-pro (pinned `on_demand`), and made-up cards (`test-*.json`, with their script in `island-cases/cards/`) that need no fake HA.

```sh
./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=$PWD/tools/vitro-parity/island-cases/better-thermostat-mini-climate.json
node tools/vitro-parity/island-probe.mjs tools/vitro-parity/island-cases/better-thermostat-mini-climate.json [--click x,y] [--shot out.png]
```

`island-probe.mjs` also takes `--eval '<function>'` (called in the page with the card's element) and `--snapshot-delay ms` (the card draws once with no states first). `island-before.sh <commit> <case.json> [-- probe options]` runs the probe on the island page as an older commit served it, in a throwaway worktree it removes after, to reproduce what a card drew before a fix.

`test/island-detect.e2e.test.mjs` runs every made-up case and checks that only the ones that draw an HA element the island lacks send `needsFrontend`. It needs their pages and Chromium, so CI's Node step skips it (the watch's logic runs there in `test/island-frontend.test.mjs`, against a fake DOM):

```sh
./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=$(ls $PWD/tools/vitro-parity/island-cases/test-*.json | paste -sd, -)
GW_VITRO_ISLAND_E2E=1 node --test tools/vitro-parity/test/island-detect.e2e.test.mjs
```

### Cases with their own stylesheets

Home Assistant is read-only for the harness, apart from its own dashboard, so a case that needs a stylesheet (a web font, say) doesn't add a Lovelace resource. It lists the sheet instead: `"resources": [{"type": "css", "url": "/local/vitro-harness-fixtures/fonts/cardmod.css"}]`, with the file in `fixtures/fonts/cardmod.css`. Only `type: css` under that path is accepted. Such a case gets a page of its own in the capture, and the next case a fresh one, because what a sheet declares stays in the page. The font cases (`font-*`) use fonts under the SIL Open Font License; their licenses sit next to them.

### Adding a case

Write `corpus/<case>.json` with its card and its fixture (copy one from a case with the same domains), then run `tools/vitro-parity/run.sh --refresh --cases <case>`. Make the names up (rooms, lights, people), never take them from a real home. Commit the case, its `refs/*/<case>.*` files, and any new `assets/` or `fixtures/images/`.

### Languages and formats

Vitro writes text, numbers, dates and times the way HA's frontend does for the user's profile (language, `number_format`, `time_format`, `date_format`, first weekday, time zone). Its UI words come from the signed-in HA at run time, as HA's page loads them. It has no browser, so what HA's frontend asks the browser's `Intl` for comes from a bundled table:

```sh
node tools/vitro-parity/locale-data.mjs
```

It asks headless Chromium's `Intl` (the browser the references are captured with) about every language the pinned frontend ships, writing `engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/locale/`. For the tests and this harness only, it also copies HA's English, German and French for the frontend keys Vitro's code names (any `"ui.…"` or `"state.…"` key) into `src/test/resources/…/locale/strings-<lang>.json`. Rerun it after a frontend or Chromium update, or after adding a key to Vitro's code: it fails on a key the frontend doesn't have. The `locale-*` cases draw German and French profiles.

## Thresholds and the baseline

| Check | Default | What it measures |
|---|---|---|
| Pixels | 2% | Share of the crop that differs, at one sample per CSS px (both images box-filtered first, which irons out font antialiasing), with pixelmatch's YIQ color distance at 0.1. A pixel that matches a neighbour within 1 CSS px counts as a shift, not a difference |
| Region | 0.10 | The worst small region: the color distance (pixelmatch's YIQ, 0 to 1) between HA's and Vitro's mean color over a 16 CSS px square, with Vitro's square allowed to sit up to 2 px away, sampled every 4 px. A share of the whole crop can't see a small element in the wrong color (a 24 px icon green where HA's is white is 1% of a chips card), while a mean over a square ignores anti-aliasing and sub-pixel text but not a color. Squares that touch a mask are left out, and the diff image outlines the worst one in magenta |
| Bounds | 2 CSS px | The largest offset of any edge of the card's slot |
| Font | 1 CSS px | The largest font size difference among the texts found |
| Texts | 0 missing | HA texts not found in any of Vitro's TextViews. Text Vitro draws straight on a canvas (gauges, the thermostat dial) shows up here as missing. HA text that is visually hidden (a 1×1 or clipped screen-reader label, `.visually-hidden`, `.sr-only`) isn't counted |

A refresh from the fake HA is deterministic: the same fixtures, frontend and cards give the same references, so a refresh with no change to them leaves the scores where they were. After a refresh that changes inputs, run `run.sh --update-baseline` and commit the references and the baseline together (the reasons stay).

> **An update records whatever drifts now, regressions included.** `--update-baseline` rewrites the entry of every case it measures: a real regression becomes "known drift", and the next run stops reporting it. Before you commit `baseline.json`, read its diff: every entry that got worse must be a change you mean to accept, not a Vitro bug. Keep the entry of a case with an open regression as it is (run the update with `--cases` for the others).

The report (`index.html`, `report.md`) lists every case with its status, frame, pixels, region Δ, bounds Δ, font Δ, the texts found, and why it failed or what it's known for.

A case over its threshold is a **known drift** if `baseline.json` lists it and it hasn't got worse, with 2% pixels, 0.02 region, 1 px bounds and 0.5 px font of slack. A different number of cards than the entry records is worse too. Otherwise it's a **regression** and the test fails. A listed case that comes back within its threshold gets a note to drop it from the baseline. The parity audit works through the baseline until each case either matches or carries a reason.

### Open bugs: `open.json`

The baseline is for drift Vitro can't remove because it comes from the two text engines, such as text metrics and anti-aliasing. A case Vitro draws wrong goes in the corpus's `open.json` instead, even before anyone fixes it, keyed `frame/case` with its `owner` (the PR that fixes it, or `#799` for one filed on the exp/vitro list with no fixer yet) and a `reason` that says what's wrong:

- **Over its threshold, listed in `open.json`:** the run reports it as **open**, not as a regression, and doesn't fail on it.
- **Back within its threshold, still listed:** the run **fails** with "matches HA now: drop it from open.json". The fix's PR removes the entry, and from then on the case guards the fix.
- **Listed in both files**, or listed for a frame or case the corpus doesn't run: the run fails. An open bug is never drift, and `--update-baseline` never records an open case.

Some older baseline entries describe cards whose look Vitro doesn't match yet (bar-card, clock-weather-card and the other cards the compat rule sends to an island). Their reasons say so; such an entry moves to `open.json` when someone takes its fix.

An `open.json` entry for a frame or case the corpus doesn't run (a typo, a retired case) fails the run too, whatever frames and cases it selects.

## Privacy

The committed corpus is made up: its fixtures name no real person, room, device or media, and its pictures are drawn by `fixture_images.py`. Committed files hold no tokens, origins, IPs (except RFC 5737), MACs, serials, locations or media lists; for a real HA (a local corpus) `lib/scrub.mjs` drops them before anything is written. Every output is also checked against a local denylist (`~/.t3/glassworks-m1/vitro-parity/deny.txt`, one name per line, never committed), and `ParityHarnessSelfTest` scans the committed JSON in CI. Real dashboards stay in the local corpus.

The screenshot shows what the JSON scrubs, so for the committed corpus the capture also checks the raw text HA drew in the card and the rendered templates (`findPrivate`), and writes neither file when they show an address, a token or a denylisted word.

Some checks stay manual, because no tool reads pixels or image metadata:

- **Look at every new or changed PNG** in `refs/` before you commit it: a picture, a camera still or a chart can show what no text check sees.
- **Inspect every image added to `assets/`**, its pixels and its metadata (`exiftool <file>`), even one from HA's `/static`.
- Keep the committed corpus to synthetic or harmless entities, and leave real cameras and personal media out of it. A camera case uses one of the fake HA's own `clarity_*` cameras, and its still is a picture `fixture_images.py` draws.

## Limits

- Robolectric draws with the host's Skia, not the panel's GPU, so a device can still differ by a few pixels. The 1 px shift tolerance and the baseline slack absorb most of it. Rendering on macOS and on CI's Linux may also differ slightly.
- A live camera's video isn't compared. In the capture HA shows the camera's still (its poster), because headless Chromium doesn't play the fake HA's H.264, and Vitro under Robolectric shows the same still with no player. The case's still is its `entity_picture` from `fixtures/images/`, which the fake also serves as the camera's `/api/camera_proxy` picture, so both draw one image. The video's area is masked anyway, so a capture that does play video can't fail on its frames. What's compared is the card's frame: its size, its bar, its title and its icons. The page's `camera/capabilities` answer is recorded, so Vitro picks its stream from what HA offered.
- A reference records whether its page had a touch screen (`viewport.touch`), and Vitro draws that case with the same pointer, so a reference taken before touch capture still compares like for like until it is refreshed. Only HA's chart legend looks different with a coarse pointer (40 px rows) at rest. Playwright's full-page screenshot turns touch emulation off for a page taller than the window, so on a touch panel the capture makes the window as tall as the page instead, lets the page settle again, and takes the screenshot, the card bounds and the texts in that one layout before it puts the window back. A case whose page has lost its coarse pointer stops. A page with a pinned card (`position: fixed`) that is taller than the window is the exception: growing the window would move the card, so it is captured with a fine pointer and its reference says so.
- Chart labels (ECharts draws them on a canvas) aren't text runs, so the text check doesn't see them; the pixel diff does.
- The pixel diff only sees the card's crop in HA's position. A card that renders taller or lower in Vitro shows up in the bounds check.
