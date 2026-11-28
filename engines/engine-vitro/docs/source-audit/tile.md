# Tile card: source audit

Home Assistant's `tile` card, read at frontend `20261028.5` (the build Clarity and the parity harness pin) and diffed
against `20261125.1` (the 2026.12 beta). Vitro draws it in `TileCardView.buildTile` (`cards/CoreCards.kt`), sizes it in
`HaGridSize` (`ui/HaGridLayout.kt`) and `HaCardSize` (`model/LovelaceModel.kt`), and builds it, or HA's error card in
its place, in `VitroView.buildCard`.

## Sources

| File (frontend `src/`) | What it holds |
|---|---|
| `panels/lovelace/cards/hui-tile-card.ts` | `setConfig`, `getCardSize`, `getGridOptions`, `willUpdate`, `render`, `_computeStateColor`, `_computeIconLabel`, the card's styles |
| `panels/lovelace/cards/tile/tile-card-helpers.ts` | Action defaults, `hasTileCardAction`, `hasTileIconAction`, `getTileImageUrl` |
| `panels/lovelace/cards/tile/tile-card-style.ts` | The focus ring and `--tile-icon-color` |
| `panels/lovelace/cards/tile/badges/*.ts` | `renderTileBadge` and the person, climate and humidifier badges |
| `components/tile/ha-tile-container.ts`, `ha-tile-icon.ts`, `ha-tile-info.ts`, `ha-tile-badge.ts` | The tile's parts: layout, the icon's shape and press, the two lines of text, the badge |
| `panels/lovelace/card-features/common/feature-layout.ts`, `hui-card-features.ts` | Where features go (`bottom` or `inline`), their rows and columns |
| `state-display/state-display.ts`, `common/entity/state_active.ts`, `state_color.ts`, `data/timer.ts` | The state line, active, the state color, `timerJustFinished` |

## Branches

Before: `exp/vitro` at 117d1026c. After: this branch.

### Config and lifecycle (`hui-tile-card.ts`, `tile-card-helpers.ts`)

| # | Arm | HA | Vitro | Before | After | Checked by |
|---|---|---|---|---|---|---|
| T1 | `setConfig`, no `entity` | Throws "Specify an entity"; HA shows `hui-error-card` in the tile's place | `VitroView.buildCard`: `HaErrorCardView("Specify an entity")` | missing (drew a tile named "Tile") | match | `tile-no-entity` |
| T2 | `getTileActionDefaults`: `tap_action` | `more-info` | `ActionConfig.parse(tap_action, "more-info")` | match | match | `ActionDefaultsAndNamesTest` |
| T3 | `getTileActionDefaults`: `icon_tap_action` | `toggle` for `DOMAINS_TOGGLE`, `button`, `input_button`, `scene`; else `none` | `ICON_TOGGLE_DOMAINS`, `PRESS_DOMAINS` | match | match | `tile-light`, `tile-sensor` |
| T4 | No `hold_action` (none of the defaults sets one) | The action handler has no hold, so a long press is a tap | `buildTile`: `hold_action` defaults to `none`, so the card isn't long-clickable | missing (opened More Info) | match | `TileSourcePortTest.aLongPressIsATapWithoutAHoldAction` |
| T5 | `icon_hold_action` (only `none` is native) | The icon's long press is its tap | The icon gets no long-click listener | missing (ran the card's hold) | match | same |
| T6 | `getCardSize` | `1 + (vertical ? 1 : 0) + featureRows` | `HaCardSize`, `HaGridSize.tileFeatureLayout` | partial (inline counted no rows, a vertical tile's inline counted) | match | `TileSourcePortTest.gridOptionsAreClampedToTheTilesMinimums` |
| T7 | `getGridOptions`: `columns` 6, `rows` `1 + featureRows (+1 vertical)` | | `HaGridSize.defaults` | match | match | `tile-light`, `tile-toggle` |
| T8 | `getGridOptions`: `min_columns` 6, 12 with inline features, 3 vertical; `min_rows` = `rows` | A smaller `grid_options` is clamped up (`computeCardGridSize`) | `HaGridSize.minimums` | missing (no clamp) | match | `tile-grid-clamped`, unit test above |
| T9 | `willUpdate`: `timerJustFinished` | The icon blinks `--error-color` twice (`timer-finished-pulse`, 0.5 s × 2, ease-in-out); none under reduced motion | `blinkTimerFinished`: the color flips at 125 ms and 375 ms of each run, as an unregistered custom property does | missing | match | `TileSourcePortTest.timerJustFinishedIsHasRule` |
| T10 | `_computeIconLabel`, icon action `toggle` | "Press {name}" (`button`, `input_button`), "Activate {name}" (`scene`), else "Toggle {name}", localized | `iconToggleLabel` with `ui.card.tile.*` | partial ("Toggle", English only) | match | `TileSourcePortTest.theIconIsNamedForWhatItsToggleDoes` |
| T11 | `_computeIconLabel`, other action | The name | `contentDescription = name` | match | match | same |
| T12 | `_handleIconAction`: tap on an icon without its own tap action | The card's action | The icon isn't clickable, so the card takes the tap | match | match | `tile-sensor` (no icon action) |
| T13 | `_computeStateColor`: `color` set, entity active | `computeCssColor(color)` | `tileColor` → `HaStateColor.cssColor` | match | match | `tile-color`, `tile-color-brightness` |
| T14 | `color` set, entity inactive | `undefined` → `--state-inactive-color` | `tileColor` fallback | match | match | `tile-color-inactive` |
| T15 | `person`, `device_tracker` | `undefined` (the color is on the badge) | `tileColor` | match | match | `tile-person`, `tile-person-zone-picture` |
| T16 | `light` with `rgb_color` | HSV lifted for contrast (saturation < 0.1 → value 225, < 0.4 → 0.4) | `contrastLightColor` | match | match | `m-tile-light-on-color` |
| T17 | Else | `stateColorCss(entity)`, else `--state-icon-color` (active) or `--state-inactive-color` | `HaStateColor.of` | match | match | the `m-tile-*` state matrix |
| T18 | `_featurePosition` | `bottom` when vertical, else `features_position` or `bottom` | `buildTile` | match | match | `tile-toggle` |
| T19 | `render`, no state object | `hui-warning`: "Entity not found" (the icon alone for a non-admin) | `showMissing` | match | match | `tile-missing-entity`, `tile-missing-entity-user` |
| T20 | `name` | `formatEntityName(stateObj, name)` | `config.name(s)`, `HaDisplay.deviceEntityName` | match | match | `tile-name-parts` |
| T21 | `hide_state` | No secondary line | `renderState` | match | match | `tile-vertical-hide-state` |
| T22 | `state_content` | `state-display` with the content list | `HaStateDisplay` | match | match | `tile-state-content`, `tile-state-content-weather` |
| T23 | `time_format` | `hui-timestamp-display` in that format | — | routed | routed | `native-options-cases.json` ("a tile's time_format") |
| T24 | A timestamp domain's state (`button`, `input_button`, `scene`) | `hui-timestamp-display` relative, capitalized ("5 minutes ago") | `HaStateDisplay.of` | routed (`native-options.json` left the domains out) | match | `tile-button-relative`, `tile-input-button-relative`, `tile-scene-relative`, `tile-scene-never` |
| T25 | Other timestamp domains (`event`, `image`, ...) | Same | `HaStateDisplay.of` | routed | routed (their icons aren't compared yet) | `native-options-cases.json` |
| T26 | `show_entity_picture` | `getTileImageUrl` (`entity_picture_local`, else `entity_picture`; a camera's at 80 px) | `renderPicture` | match | match | `tile-person-zone-picture` |
| T27 | `fixedInfoHeight`: `layout === "grid"` and `grid_options.rows !== "auto"` | `.fixed-info-height` (see C6) | `buildTile`: `fixedInfo` | missing | match | `m-tile-vertical-*`, `tile-vertical-rows-auto`, `tile-vertical-in-stack` |
| T28 | `hasFeatures` | `role="group"`, labelled by the name (accessibility only) | — | missing | missing (no TalkBack group yet) | — |
| T29 | `hasTileCardAction` false (tap `none`, no hold) | No button role, ripple off | `buildTile`: `cardInteractive` | missing (always a button with a ripple) | match | `TileSourcePortTest.aTileWithNoActionIsNotAButton` |
| T30 | `hasTileIconAction` | The icon is a button with its shape | `iconInteractive` | match | match | `tile-sensor` vs `tile-light` |
| T31 | `features` below (`bottom`) | `hui-card-features`, one column | `buildTile`, `addExtraFeatures` | match for the six native features | match | `tile-light`, `tile-cover`, `tile-fan*`, `tile-select`, `tile-number`, `tile-toggle` |
| T32 | `features_position: inline` | The first feature beside the info (36 px), the rest below in up to two columns, a lone last one spanning both, a divider between columns | `buildTile` draws a first version (no `wide`, no divider, an 8 px column gap assumed) | routed | routed | `native-options-cases.json` |
| T33 | Any other feature | Its `hui-*-card-feature` | Several are drawn by `addExtraFeatures` but not compared | routed | routed | `route-tile-feature` |

### Badges (`tile-badge*.ts`)

| # | Arm | HA | Vitro | Before | After | Checked by |
|---|---|---|---|---|---|---|
| B1 | `unknown` | No badge | `renderTileBadge` | match | match | `m-tile-sensor-unknown` |
| B2 | `unavailable` | Orange (`--orange-color`) disc, `mdi:exclamation-thick` | `renderTileBadge` | match | match | `m-tile-*-unavailable` |
| B3 | `person`, `device_tracker` | The zone's icon (`mdi:home`, `mdi:home-export-outline`) on the state color | `renderTileBadge`, `followZones` | match (person) | match (person); `device_tracker` routed | `tile-person*` |
| B4 | `climate` | `hvac_action` icon on the color of its mode, nothing for `off` or none | `renderTileBadge` | match | match | `tile-climate` |
| B5 | `humidifier` | `action` icon on its mode's color | `renderTileBadge` | not compared | routed | — |
| B6 | Any other domain | No badge | `renderTileBadge` | match | match | the state matrix |

### Styles (`hui-tile-card.ts`, `tile-card-style.ts`, the tile parts)

| # | Arm | HA | Vitro | Before | After | Checked by |
|---|---|---|---|---|---|---|
| C1 | `:host` / `ha-card.active` | `--tile-color`: `--state-inactive-color`, active `--state-icon-color` | `tileColor` fallback | match | match | the state matrix |
| C2 | `ha-tile-badge` | `top: 3px; right: 3px` of the 48 px icon box (3 px past the 36 px shape), 16 px, 12 px white icon | `badge()` | match | match | `tile-climate`, `m-tile-*-unavailable` |
| C3 | Alarm `pending`, `arming`, `triggered`; lock `jammed` | `animation: pulse 1s infinite` (opacity 1 → 0 → 1, each half `ease`); no reduced-motion rule | `updatePulse`, `startPulse` (on screen only) | missing | match | `TileSourcePortTest.alarmsAndJammedLocksPulseOtherStatesDont` |
| C4 | `.image` radius | `--ha-tile-icon-border-radius`, else `--ha-picture-border-radius`; square for `update`; ≤ `--ha-border-radius-sm` for `media_player` | `renderPicture` (12, 0, 4 px) | match for the default theme | match | `tile-person-zone-picture`, `tile-update` |
| C5 | `.content` | `min-height: 56px; padding: 0 10px; gap: 10px`, centered | `buildTile` | match | match | `tile-light` |
| C6 | `.vertical` | `padding: 10px 8px`, centered column, `gap: 10px`; with `.fixed-info-height`: `gap: 2px`, info `min-height: 48px`, primary `min-height: 32px`, 2 px between the lines | `buildTile`: `tileVertical`, `fixedInfo` | missing (10 px padding, 8 px gap, no fixed height: the text sat about 4 px low) | match | `m-tile-vertical-*`, `tile-vertical-*`, `tile-scene-relative` |
| C7 | `ha-tile-info` | 14 px / 500 on 22.4 px, `0.1px`; 12 px / 400 on 14.4 px, `0.4px`; ellipsis | `buildTile` labels | match | match | every tile case |
| C8 | `ha-tile-icon` | 36 px shape, `--tile-icon-color` at 20%, 24 px icon, shape only when interactive and not an image | `updateBadge`, `tintBadge` | match | match | `tile-light`, `tile-sensor` |
| C9 | `ha-tile-icon[interactive]:active` | `transform: scale(1.2)` while pressed | — | missing | missing (press feedback only) | — |
| C10 | `ha-tile-container` ripple | `--tile-color`, 12% pressed | `applyHaRipple`: HA's neutral ripple | differs | differs (press feedback only) | — |
| C11 | Keyboard focus | `border-color` and a 1 px ring in `--tile-color` | Android's focus highlight | differs | differs | — |
| C12 | Color transitions | 180 ms on the icon and its shape | `tintBadge` | match | match | — |

## What changes in 2026.12 (`20261125.1`)

`hui-tile-card.ts`, its helpers, its badges, `ha-tile-container`, `ha-tile-info` and `ha-tile-badge` are unchanged.
What does change near the tile:

| File | Change | What Vitro needs |
|---|---|---|
| `components/tile/ha-tile-icon.ts` | The action handler is bound in `updated()`; `cursor: pointer`; slotted children don't take pointer events | Nothing: no change on screen or to what a tap does |
| `state-display/state-display.ts` | A timer's `finishes_at` content is a timestamp drawn as a date and time | `HaStateDisplay` when the pin moves (the `state_content: finishes_at` arm) |
| `common/entity/state_active.ts`, `state_color.ts` | A new `timer_list` domain: active while its state isn't `0`, state-colored | `HaStateActive`, `HaStateColor` when the pin moves; `native-options.json` doesn't list the domain, so its tiles fall back until then |
| `card-features/hui-fan-speed-card-feature.ts` | A `style` option (`buttons` or `slider`) for fans with 3 to 6 speeds, and a slider when the buttons don't fit (24 px each) | Port with the pin move. `style` falls back today (`native-options.json` allows only `type`); the fit check matters for narrow tiles |
| `card-features/hui-bar-gauge-card-feature.ts`, `hui-cover-tilt-position-card-feature.ts` | New options | Neither is native |
| `common/color/palette.ts` | `--ha-color-<name>-05` names padded to two digits | Nothing today: Vitro reads no generated palette names |

## Parity cases this audit added

`tile-button-relative`, `tile-input-button-relative`, `tile-scene-relative`, `tile-scene-never`, `tile-no-entity`,
`tile-vertical-in-stack`, `tile-vertical-hide-state`, `tile-vertical-rows-auto`, `tile-grid-clamped` and
`tile-color-inactive`, written by hand from the table above (no case generator was on `exp/vitro` when this started),
and captured from the harness's fake Home Assistant.

## Totals

| | Arms | Matching before | Matching after | Routed | Still different |
|---|---:|---:|---:|---:|---:|
| Config and lifecycle | 33 | 18 | 28 | 4 (T23, T25, T32, T33) | 1 (T28) |
| Badges | 6 | 5 | 5 | 1 (B5, and `device_tracker` in B3) | 0 |
| Styles | 12 | 7 | 9 | 0 | 3 (C9, C10, C11) |
| **All** | **51** | **30** | **42** | **5** | **4** |
