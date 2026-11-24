---
topic: display
evidence: verified
---

# Vitro: which dashboard cards still need a web island?

**Status (2026-11-24): measured, recommendation below.** After the native fast paths on `exp/vitro`, about a
quarter of the cards in typical community dashboards still go to a web island, and one card type, `custom:button-card`, is
nearly half of those. The rest is a long tail. Our own dashboards need no island once they are written as native cards.

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
   $ node ws.mjs call '{"type":"lovelace/dashboards/list"}'     # 9 dashboards, all storage mode
   $ node ws.mjs get <url_path>                                  # each of them
   $ node ws.mjs call '{"type":"lovelace/config","force":true}'  # default dashboard: "No config found" (auto-generated)
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
   took the first 80, and downloaded up to four matching YAML files from each (80 repos, 2 MB). A repository counts as
   one dashboard, with all its fetched files merged; I dropped those with fewer than five cards (fragments of
   `!include` trees) and kept **57 dashboards, 3,449 cards**. Each file was parsed with PyYAML, ignoring `!include`
   and other tags, and cards were found under `views[].cards`, `views[].sections[].cards` and nested `cards` / `card`.

   Caveats, so the numbers aren't over-read:
   - The queries ask for `type: custom:`, so every dashboard has at least one custom card. This over-states the
     island share for dashboards in general, and is the right population for this question.
   - GitHub code search favours public, hand-written YAML. Storage-mode dashboards edited in the UI (most users)
     are not in it, and they skew toward core and Mushroom cards. Treat the island share as an upper estimate.
   - `!include`d files that weren't matched are missing, so counts for those repos are partial.
   - The samples are not stratified by stars. Repos in the sample are real, personal setups.

## Result

**Community sample, 57 dashboards, 3,449 cards** (verified):

| | |
|---|---|
| Cards drawn natively | 2,624 (76.1%) |
| Cards that need an island | 825 (23.9%) |
| Dashboards with no island card | 6 of 57 |
| Dashboards with at most two island cards | 20 of 57 |
| Distinct island card types | 109 (95 custom, 14 core) |
| Island types seen in only one dashboard | 80 |

Island card types by how many dashboards use them (the 14 seen in at least three, then the notable ones):

| Dashboards | Cards | Type | Kind |
|---|---|---|---|
| 17 | 376 | `custom:button-card` | HACS, templated JS |
| 8 | 12 | `iframe` | core |
| 7 | 10 | `map` | core |
| 7 | 9 | `custom:weather-card` | HACS |
| 6 | 78 | `custom:apexcharts-card` | HACS, charts |
| 6 | 45 | `entity-button` | core (deprecated alias of `button`) |
| 4 | 10 | `custom:thermostat-card` | HACS |
| 4 | 8 | `custom:calendar-card` | HACS |
| 3 | 20 | `custom:advanced-camera-card` | HACS, video |
| 3 | 18 | `custom:simple-thermostat` | HACS |
| 3 | 4 | `custom:horizon-card`, `custom:bignumber-card` | HACS |
| 3 | 3 | `energy-*` (usage graph, date selection, solar) | core |
| 3 | 3 | `custom:tracker-card` | HACS |
| 2 | 7 | `custom:mushroom-empty-card` | HACS, a spacer |

Concentration: `button-card` alone is 46% of island cards; with `apexcharts-card` and `entity-button` it is 60%; the top
25 types are 84%. Adding native paths in order of how many dashboards use them:

| Types added | Dashboards with no island | Island cards left |
|---|---|---|
| none | 6 of 57 | 825 |
| top 1 (`button-card`) | 7 | 449 |
| top 3 | 9 | 427 |
| top 5 | 13 | 340 |
| top 10 | 16 | 256 |
| top 20 | 24 | 213 |

A cross-check without merging files per repo (72 files of at least five cards: 3,782 cards, 870 island, 23.0%) gives the
same top of the list (`button-card` 19 files, `weather-card`, `apexcharts-card`, `iframe`, `map`, `entity-button`).

**Combined with our own dashboards**, the cards that would still need an island after today's fast paths, ranked:

1. `custom:button-card`. Most common by far (17 of 57 dashboards, 376 cards). It is a template engine (JS templates in `[[[ ]]]`, styles, state-based styling), so a native path would
   draw the common subset and send anything with custom JS to an island.
2. `custom:apexcharts-card`. Charts, 6 dashboards, 78 cards; heavy and varied in config.
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
   After `button-card` and `entity-button` alone, island cards in the sample fall from 825 to 449 (24% to about 13% of the sample's cards).
2. **Spike the light JS island engine afterwards, not instead**, but only if you want to remove islands for the tail:
   109 distinct types, 80 of them in a single dashboard, `button-card`'s JS templates and `apexcharts-card`'s config
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
node ws.mjs get <url_path> > ours/<url_path>.json                     # one per dashboard
python3 tools/vitro-census/census.py ours/*.json                      # native vs island, per card type
python3 tools/vitro-census/census.py --min-cards 5 comm/*/*.yaml      # community sample (one file = one dashboard)
```

`census.py` reads the native set out of `ui/VitroView.kt`, so it follows the code. The per-repository merge and the
random draw were a few lines of glue (shuffle with seed 798, first 80 of 429 repos, four files each); the file-level
run above is the reproducible version. To rerun the sample, repeat the three `search/code` queries in step 3.

## Open questions

- **Stratify by stars.** The sample is random over a biased query. A second run on the dashboards in the 50 most-starred
  repos that publish a `ui-lovelace.yaml` would show whether popular setups look different (untested).
- **UI-edited dashboards.** Storage-mode dashboards aren't searchable on GitHub. Ask on the Glassworks Discord for
  anonymised card-type lists (`census.py` output only) to check the island share against real users (untested).
- **Native isn't faithful.** A card routed natively can still fall back (#795: unsupported `card_mod` paths, unknown
  CSS, JS templates in Markdown). The census counts routing, not fidelity, so the real island share is higher.
- **Custom entity rows** inside `entities` cards (`multiple-entity-row`, `slider-entity-row`, `fold-entity-row`) are
  not counted here, and may be a larger gap than any card on this list (untested).
- **`button-card` subset.** Which options the 17 dashboards use (`styles`, `state:` rules, `[[[ ]]]` templates) decides how
  much of it a native path can cover. Count them from the same files before writing it (untested).
