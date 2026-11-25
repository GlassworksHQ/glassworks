---
topic: display
evidence: verified
---

# Vitro: which dashboard cards still need a web island?

**Status (2026-11-24): measured, recommendation below.** After the native fast paths on `exp/vitro`, about a
quarter of the cards in typical community dashboards still go to a web island, and one card type, `custom:button-card`, is
nearly half of those. The rest is a long tail. Our own dashboards, once written as native cards, use no card type that
is routed to an island. This counts card types only: a native-routed card can still fall back for an option Vitro
doesn't draw (#795), and nothing here was rendered on a panel.

Tools: `tools/vitro-census/census.py` (read-only), `gh api` (GitHub code search, GraphQL for stars), the HA
websocket (`lovelace/dashboards/list`, `lovelace/config`, `lovelace/resources`; reads only). Vitro at `3c795f0ad`.

## Question

Issue #798: Vitro draws core and some HACS cards natively and sends every other custom card to a GeckoView island.
Which cards would still need an island? If few, add native fast paths. If many and varied, a light JS island engine
(QuickJS plus a minimal DOM) is worth a spike.

"Native" here means the card `type` is routed to a native view in the `when (card.type)` of `ui/VitroView.kt`
(**read**, 2026-11-24). It does not say every option of that card is drawn: a native card can still fall back when it
meets something it won't fake (#795).

Native today: the core `tile`, `entities`, `entity-filter`, `glance`, `button`, `heading`, `clock`, `sensor`,
`history-graph`, `gauge`, `thermostat`, `weather-forecast`, `markdown`, `picture`, `picture-entity`, `picture-glance`,
`picture-elements`, `media-control`, `entity`, `light`, `alarm-panel`, `todo-list`, `statistics-graph`, `area`,
`humidifier`, `calendar`, `logbook`, the stacks, `grid` and `conditional`; and from HACS the Mushroom cards (entity,
light, fan, cover, person, template, title, chips, climate, media-player, lock, alarm, vacuum, humidifier, number,
select), `bubble-card`, `mini-graph-card`, `mini-media-player`, `clock-weather-card`, `modern-circular-gauge`,
`bar-card`, `auto-entities`, `stack-in-card`, `vertical-stack-in-card` and `layout-card`.

## What we tried

1. **Our own dashboards** (2026-11-24, verified)
   ```
   $ node tools/vitro-census/ha-dashboards.mjs list              # 9 dashboards, all storage mode
   $ node tools/vitro-census/ha-dashboards.mjs get <url_path>    # each of them
   $ node tools/vitro-census/ha-dashboards.mjs get lovelace      # default dashboard: "No config found" (auto-generated)
   $ python3 tools/vitro-census/census.py <each>.json
   ```
   The default dashboard has no stored config (HA generates it), so it has no cards to count. Of the nine, the
   `map` dashboard is a bare `strategy: map`, and three (`vitro-parity-home`, `vitro-parity-states`, `vitro-preview`) are Vitro test dashboards of 21, 9 and 236 cards
   written to be native, all 100% native. They are left out of the table below.

   | Dashboard | Cards | Native | Island |
   |---|---|---|---|
   | Tablet home (original) | 25 | 24 (`tile` 21, `heading` 3) | 1 (`custom:tablet-home-card`, our own whole-screen card) |
   | Mirror home (original) | 1 | 0 | 1 (`custom:mirror-home-card`, our own whole-screen card) |
   | Tablet home, Vitro version | 116 | 116 | 0 |
   | Mirror home, Vitro version | 30 | 30 | 0 |
   | S1 home, Vitro version | 60 | 60 | 0 |

   The Vitro versions are rewrites of the two bespoke cards into core + Mushroom + `stack-in-card` + `markdown`
   (by card type: `tile` 45, `mushroom-template-card` 18, `mushroom-chips-card` 13, `stack-in-card` 13, `markdown` 11,
   `heading` 10, `mushroom-media-player-card` 3, one each of `picture-entity`, `alarm-panel`, `todo-list`, on the
   tablet). So our own dashboards are not a sample of community use: the maintainer's real screens were two bespoke
   custom cards, and the work was to replace them with cards Vitro draws. The only island in our tree is a card we wrote.

   Resources installed in HA (`lovelace/resources`): Mushroom, card-mod, auto-entities, stack-in-card, layout-card
   (all native or style-only), plus four that no stored dashboard uses today: `simple-plant-card`,
   `better-thermostat-ui-card`, the Aqara preset-favorites card and the Dreame vacuum cards. Those would be islands if used.

2. **Most-installed HACS frontend cards** (2026-11-24, verified)
   ```
   $ curl -sL https://raw.githubusercontent.com/hacs/default/master/plugin    # 1228 repositories
   $ gh api graphql ... stargazerCount                                         # stars, 100 repos per call
   ```
   Top by stars, from the HACS default `plugin` list, with what Vitro does today (stars ≈ 2026-11-24):

   | Stars | Repo | Vitro |
   |---|---|---|
   | 5.3k | `lovelace-mushroom` | native (the 16 card types Vitro routes; others in the pack are untested) |
   | 4.8k | `Bubble-Card` | native |
   | 3.9k | `mini-graph-card` | native |
   | 2.5k | `button-card` | **island** |
   | 2.0k | `xiaomi-vacuum-map-card` | **island** |
   | 1.9k | `apexcharts-card` | **island** |
   | 1.9k | `card-mod` | not a card; Vitro reads its CSS |
   | 1.8k | `auto-entities` | native |
   | 1.7k | `mini-media-player` | native |
   | 1.7k | `ha-floorplan` | **island** (an SVG floorplan runtime) |
   | 1.3k | `calendar-card-pro` | **island** |
   | 1.3k | `battery-state-card` | **island** |
   | 1.3k | `scheduler-card` | **island** |
   | 1.3k | `vacuum-card` | **island** |
   | 1.3k | `layout-card` | native |
   | 1.2k | `advanced-camera-card` | **island** (live video; would be a native camera fast path, not JS) |
   | 1.2k | `power-flow-card-plus` | **island** |
   | 1.0k | `vertical-stack-in-card` | native |
   | 0.9k | `clock-weather-card` | native |

   Not cards, so not counted: `custom-brand-icons`, `kiosk-mode`, `Helios` and other themes, entity-row plugins
   (`multiple-entity-row`, `slider-entity-row`, `fold-entity-row`) and strategies (`mushroom-strategy`). Entity rows
   live inside `entities` cards, which Vitro draws, but a custom row type inside one is a separate question (untested).

3. **Community dashboards** (2026-11-24, verified)
   ```
   $ gh api -X GET search/code -f q='"views:" "type: custom:" filename:ui-lovelace.yaml'        # 3 pages x 100
   $ gh api -X GET search/code -f q='"type: custom:" "cards:" path:dashboards extension:yaml'   # 3 pages
   $ gh api -X GET search/code -f q='"type: custom:" "views:" path:lovelace extension:yaml'     # 2 pages
   ```
   The three queries returned 429 distinct repositories. I shuffled them with a fixed seed (`random.seed(798)`),
   took the first 80, and downloaded up to four matching YAML files from each (80 repos, 94 files, 2 MB). A repository
   counts as one dashboard, with all its fetched files merged; I dropped those with fewer than five cards (fragments of
   `!include` trees) and kept **60 dashboards, 3,787 cards**. Each file was parsed with PyYAML, ignoring `!include`
   and other tags, and cards were found under `views[].cards`, `views[].sections[].cards` and nested `cards` / `card`.
   `tools/vitro-census/sample.py` does the search, the draw and the download; `sample-manifest.json` next to it lists
   the 80 repositories and pins each file to the commit GitHub returned, so the numbers below reproduce exactly.

   A first pass used a separate, quicker card walker and got 57 dashboards and 3,449 cards. It missed every card in
   three repositories whose files are storage-mode exports (the views sit under a top-level `config:` key), and a
   few nested cards in five more. All numbers here
   come from `census.py`'s walker, the one that ships.

   Caveats, so the numbers aren't over-read:
   - The queries ask for `type: custom:`, so every dashboard has at least one custom card. That is a selection on
     custom cards, not on island cards (some custom types route natively, some core types to an island), so how the
     share compares with dashboards in general is untested. It is the population this question is about.
   - GitHub code search finds public YAML files, so the sample is dashboards people publish as code: mostly
     hand-written YAML, plus the three storage-mode exports above. Dashboards edited only in the UI and never
     published are not in it. Whether those use custom cards more or less often is untested, so this sample says
     nothing about them.
   - `!include`d files that weren't matched are missing, so counts for those repos are partial.
   - The samples are not stratified by stars. Repos in the sample are real, personal setups.

## Result

**Community sample, 60 dashboards, 3,787 cards** (verified, `census.py --per-dir --min-cards 5`):

| | |
|---|---|
| Cards drawn natively | 2,915 (77.0%) |
| Cards that need an island | 872 (23.0%) |
| Dashboards with no island card | 6 of 60 |
| Dashboards with at most two island cards | 19 of 60 |
| Distinct island card types | 113 (99 custom, 14 core) |
| Island types seen in only one dashboard | 81 |

Island card types by how many dashboards use them (the 16 seen in at least three, then the notable ones):

| Dashboards | Cards | Type | Kind |
|---|---|---|---|
| 18 | 377 | `custom:button-card` | HACS, templated JS |
| 9 | 14 | `custom:weather-card` | HACS |
| 8 | 12 | `iframe` | core |
| 8 | 11 | `map` | core |
| 7 | 82 | `custom:apexcharts-card` | HACS, charts |
| 6 | 45 | `entity-button` | core (deprecated alias of `button`) |
| 6 | 17 | `custom:monster-card` | HACS, a filter card like `auto-entities` |
| 4 | 10 | `custom:thermostat-card` | HACS |
| 4 | 8 | `custom:calendar-card` | HACS |
| 3 | 20 | `custom:advanced-camera-card` | HACS, video |
| 3 | 18 | `custom:simple-thermostat` | HACS |
| 3 | 4 | `custom:horizon-card`, `custom:bignumber-card` | HACS |
| 3 | 3 | `energy-usage-graph`, `custom:battery-state-card`, `custom:tracker-card` | core, HACS |
| 2 | 7 | `custom:mushroom-empty-card` | HACS, a spacer |
| 2 | 6 | `custom:swipe-card` | HACS, a layout wrapper |

Concentration: `button-card` alone is 43% of island cards; with `apexcharts-card` and `entity-button` it is 58%; the top
25 types are 83%. Adding native paths in order of how many dashboards use them:

| Types added | Dashboards with no island | Island cards left |
|---|---|---|
| none | 6 of 60 | 872 |
| top 1 (`button-card`) | 7 | 495 |
| top 3 | 9 | 469 |
| top 5 | 12 | 376 |
| top 10 | 17 | 276 |
| top 20 | 24 | 220 |

Counting each file as its own dashboard instead (`census.py --min-cards 5`, no `--per-dir`) gives 72 files, 3,782
cards and 870 island cards (23.0%), with the same top of the list. It is five cards short of the per-repository total
because a few repositories have a file under five cards that only counts once merged.

**Combined with our own dashboards**, the cards that would still need an island after today's fast paths, ranked:

1. `custom:button-card`. Most common by far (18 of 60 dashboards, 377 cards). It is a template engine (JS templates in `[[[ ]]]`, styles, state-based styling), so a native path would
   draw the common subset and send anything with custom JS to an island.
2. `custom:apexcharts-card`. Charts, 7 dashboards, 82 cards; heavy and varied in config.
3. The core cards Vitro doesn't draw: `entity-button` (an alias of `button`, a one-line route), `iframe`, `map`, and the
   `energy-*` cards. The first is trivial. `iframe` and `map` are islands by nature (a web page, a tile map).
4. `custom:weather-card`, `custom:thermostat-card` and `custom:simple-thermostat`, `custom:calendar-card`,
   `custom:bignumber-card`, `custom:horizon-card`: each is simple and close to a card we already draw.
5. `custom:advanced-camera-card`, `custom:xiaomi-vacuum-map-card`, `custom:vacuum-card`, `custom:mushroom-empty-card`
   (a spacer), `custom:swipe-card` (a layout wrapper). Camera, map and video cards are islands by nature.

Our own two bespoke cards (`custom:tablet-home-card`, `custom:mirror-home-card`) and the four unused Aqara/Dreame/
plant/thermostat resources are the maintainer's tail, not the community's.

## Recommendation

The list is **short at the head and long in the tail**, so do both halves of the checklist in that order:

1. **Native fast paths first**, because three changes cover most of the head:
   - route `entity-button` to the `button` view (one line);
   - `custom:button-card`, for its common subset (name, icon, state, `tap_action`, `styles` for the card and icon, a
     few `state:` rules), with the JS-template forms (`[[[ ]]]`) falling back to an island on purpose (#795);
   - `custom:mushroom-empty-card`, a spacer, then `custom:weather-card` and `custom:swipe-card` (a layout wrapper),
     which are the next cheapest.
   Full native coverage of `button-card` and `entity-button` would take island cards in the sample from 872 to 450
   (23% to about 12% of the sample's cards). That's a lower bound: the `button-card` subset sends its JS-template
   forms to an island on purpose, and how many cards use them is not counted yet (untested).
2. **Spike the light JS island engine afterwards, not instead**, but only if you want to remove islands for the tail:
   113 distinct types, 81 of them in a single dashboard, `button-card`'s JS templates and `apexcharts-card`'s config
   language will not be covered by any small set of native views. Two concrete test cards for the spike: a plain Lit
   card (`mini-graph-card`'s data model without its renderer) and `button-card` with `[[[ ]]]` templates, which are
   JS that runs against `states`, `entity` and `hass`.
3. Keep the island for `iframe`, `map`, the camera and video cards and the `energy-*` cards for good: they are web
   content or video, and a native version is a different project.

Order of work if you start tomorrow: `entity-button`; `button-card` subset; `apexcharts-card` only if charts are
a stated goal (Vitro already draws `history-graph`, `statistics-graph` and `mini-graph-card`); the JS-engine spike last.

## How to reproduce

Read-only throughout: the HA websocket helper only calls `lovelace/*` reads, and GitHub is read through the public API.

```
export HA_URL=https://homeassistant.local:8123 HA_TOKEN=...                      # a long-lived token; read-only use
# Plain http:// to another host sends the token unencrypted: the helper refuses it unless HA_ALLOW_INSECURE=1.
mkdir -p ours && node tools/vitro-census/ha-dashboards.mjs get <url_path> > ours/<url_path>.json   # one per dashboard
python3 tools/vitro-census/census.py ours/*.json                                  # native vs island, per card type
OUT=$(mktemp -d)                                                                   # a new, empty folder
python3 tools/vitro-census/sample.py fetch tools/vitro-census/sample-manifest.json "$OUT"     # the pinned sample
python3 tools/vitro-census/census.py --per-dir --min-cards 5 "$OUT"/comm/*/*.yaml            # the numbers above
```

Because an `http://` `HA_URL` means `ws://`, which is unencrypted, anyone observing the network path can read the
token: use it only on a trusted local network, or connect over `https://` (`wss://`) if Home Assistant serves HTTPS.
The helper prints a warning when the WebSocket URL it builds isn't `wss://`.

`census.py` reads the native set out of `ui/VitroView.kt`, so it follows the code: rerun it after a new fast path to
see the island share drop. `sample.py search` and `draw` make a fresh sample (code search results change over time).

## Open questions

- **Stratify by stars.** The sample is random over a biased query. A second run on the dashboards in the 50 most-starred
  repos that publish a `ui-lovelace.yaml` would show whether popular setups look different (untested).
- **UI-edited dashboards.** Storage-mode dashboards aren't searchable on GitHub. Ask on the Glassworks Discord for
  anonymised card-type lists (`census.py` output only) to check the island share against real users (untested).
- **Native isn't faithful.** A card routed natively can still fall back (#795: unsupported `card_mod` paths, unknown
  CSS, JS templates in Markdown). The census counts routing, not fidelity, so the real island share is higher.
- **Custom entity rows** inside `entities` cards (`multiple-entity-row`, `slider-entity-row`, `fold-entity-row`) are
  not counted here, and may be a larger gap than any card on this list (untested).
- **`button-card` subset.** Which options the 18 dashboards use (`styles`, `state:` rules, `[[[ ]]]` templates) decides how
  much of it a native path can cover. Count them from the same files before writing it (untested).
