# Vitro Native Card, Feature, and Theme Migration Reference

Vitro draws every card below natively, without GeckoView, when its config stays within the options listed (Vitro is experimental, so check the result on the panel). The exception is `iframe`: its view stays native, but the frame's own page runs in a GeckoView island, so leave it out of a dashboard that must run with no browser at all. The engine's own list is `engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/compat/VitroSupport.kt`; this page follows it.

Cards marked *(not yet compared with HA)* draw natively but haven't been checked against Home Assistant's own render in the parity harness. Prefer a compared card when either would do.

## Tier 1: Native Core Lovelace Cards (41)

- **Controls & summaries:** `tile`, `entities` (plain rows and the `divider`, `section`, `button`, `call-service`, `text`, `weblink`, `buttons` and `conditional` rows; no header or footer), `glance`, `button` (also as `entity-button`), `toggle-group`, `shortcut`, `alert`, `entity`, `light`, `thermostat` (a heat/cool range shows both targets, as in HA), `humidifier` *(not yet compared with HA)*, `alarm-panel`, `media-control`, `todo-list` (also as `shopping-list`), `area`, `plant-status`
- **Information & media:** `heading`, `clock`, `date`, `statistic`, `sensor`, `gauge` (severity, `segments`, `needle`), `weather-forecast` (daily, hourly and twice-daily), `history-graph`, `statistics-graph`, `calendar` (month grid and list views), `logbook`, `markdown` (rendered natively: headings, tables, lists, quotes, rules, `<font color>`, `<center>` and inline `<ha-icon>`, with HA server-side Jinja2 templates via `render_template`; `<ha-alert>`, `<ha-qr-code>`, `<details>`, `<audio>`, `<video>` and checkboxes draw nothing)
- **Images & floorplans:** `iframe` (its page in a browser island of its own), `picture`, `picture-entity`, `picture-glance`, `picture-elements` (`state-icon`, `state-label`, `state-badge`, `icon`, `service-button`; `conditional` and `image` elements are left out)
- **Layout & filtering:** `grid`, `horizontal-stack`, `vertical-stack`, `conditional`, `entity-filter` (plus the Tier 2 containers `custom:stack-in-card`, `custom:vertical-stack-in-card` and `custom:layout-card`, whose children are audited like any stack's)
- **Not native:** any other built-in card (`map`, `energy-*`, ...). Its whole view opens in Home Assistant's frontend instead of drawing natively. Replace it to keep the view native. `attribute` and `cast` rows aren't drawn either.

### Supported Inline `tile` Features (14)

- `light-brightness`
- `light-color-temp`
- `fan-speed`
- `cover-open-close`
- `cover-position`
- `climate-hvac-modes`
- `target-temperature`
- `select-options`
- `media-player-volume-slider`
- `toggle`
- `media-player-playback`
- `light-color-favorites`
- `numeric-input`
- `vacuum-commands`

### Supported Views & Conditions

- View types: `sections` (recommended), `masonry` (Home Assistant's columns and card placement), `sidebar`, `panel` (only if its card is Tier 1 or Tier 2!), and layout-card's `custom:masonry-layout`, `custom:grid-layout`, `custom:horizontal-layout` and `custom:vertical-layout`. Any other view type opens in Home Assistant's frontend
- View `badges` (rendered as compact top status pills) and `subview: true` (back-arrow navigation)
- `visibility:` on sections and cards (`state`, `numeric_state`, `and`, `or`, `not`, `screen`)

## Tier 2: Native HACS Fast-Path Cards (26)

- `custom:mushroom-entity-card`
- `custom:mushroom-light-card`
- `custom:mushroom-title-card`
- `custom:mushroom-chips-card` (entity, template, weather, light, action, menu, back, spacer and conditional chips; like Mushroom, a chip's own `visibility` and `card_mod` are ignored, and the chips card's own `card_mod` isn't applied yet). Any other chip type sends the card to an island
- `custom:mushroom-template-card` (templated `icon_color`, `badge_icon`, `badge_color`, `picture`, `layout`, `multiline_secondary`)
- `custom:mushroom-climate-card`
- `custom:mushroom-fan-card`
- `custom:mushroom-cover-card`
- `custom:mushroom-person-card` *(not yet compared with HA)*
- `custom:bubble-card` *(not yet compared with HA)* with `card_type: button` or `separator` only (`sub_button:` icon/state buttons with their own `entity`, `show_state`, `show_background`, `tap_action`, `hold_action`). A `pop-up` and the other card types stay GeckoView: don't keep one expecting it native
- `custom:mini-graph-card` *(not yet compared with HA)*
- `custom:clock-weather-card` *(not yet compared with HA)*
- `custom:modern-circular-gauge` *(not yet compared with HA)* (`gauge_type` `standard` / `half` / `full`, `min`, `max`, `segments`, `smooth_segments`, `start_from_zero`, `secondary`)
- `custom:bar-card` *(not yet compared with HA)* (`entity` or `entities`, `min`, `max`, `height`, `color`, `severity: [{from, to, color}]`, `positions: {icon, name, value}`)
- `custom:mushroom-media-player-card` (`use_media_info`, `show_volume_level`, `media_controls`, `volume_controls`)
- `custom:mushroom-lock-card` *(not yet compared with HA)* (lock / unlock / open)
- `custom:mushroom-alarm-control-panel-card` (`states:` arm pills, disarm)
- `custom:mushroom-vacuum-card` (`commands`: `on_off`, `start_pause`, `stop`, `locate`, `clean_spot`, `return_home`)
- `custom:mushroom-humidifier-card` *(not yet compared with HA)* (`show_target_humidity_control`, icon toggle)
- `custom:mushroom-number-card` (`display_mode: slider | buttons`)
- `custom:mushroom-select-card` (dropdown of the entity's options)
- `custom:mini-media-player` *(not yet compared with HA)* (`artwork: default | cover | full-cover | material | none`, `hide:`, `volume_stateless`, live progress)
- `custom:auto-entities` (`filter.include` / `exclude` on `domain`, `entity_id`, `state`, `name`, `attributes`, `group`, `not`/`or`/`and`; globs, `/regex/`, `< > <= >= != ==`; `sort`; `card`, `card_param`, `show_empty`, `unique`). Other filter keys (`area`, `device`, `integration`, `label`, `template`, ...) match nothing natively: list those entities explicitly instead
- `custom:stack-in-card` (`mode: vertical | horizontal`, `title`, `keep: {background, border_radius, margin}`)
- `custom:vertical-stack-in-card` *(not yet compared with HA)* (`title`, `horizontal`)
- `custom:button-card` *(not yet compared with HA)* without JavaScript templates (`[[[ ]]]`): layouts, `state:` rules, `color` / `color_type`, `size`, `aspect_ratio`, `show_*`, labels, `lock`, plain-text `custom_fields`, tap/hold/double-tap actions, and `styles` for card, icon, img_cell, name, state, label, grid and custom fields. `button_card_templates` are merged first. JavaScript, cards or HTML in it, `extra_styles`, the spinner, live streams, protected or icon actions and styles outside the subset send it to an island
- `custom:swipe-card` *(not yet compared with HA)* (`pagination` bullets or fraction, `slidesPerView`, `spaceBetween`, `centeredSlides`, `loop`, `autoplay`, `start_card`, `reset_after`). Other Swiper parameters send it to an island unless a built-in card sits inside it
- `custom:weather-card` *(not yet compared with HA)* (bramkragten's: current conditions and details; needs `entity`)
- `custom:mushroom-empty-card` (a spacer)
- `custom:layout-card` (`custom:masonry-layout`, the default; `custom:grid-layout` with `grid-template-columns`, `grid-gap`, `mediaquery`, `view_layout.grid-column`; `custom:horizontal-layout` with `max_cols` / `width`; `custom:vertical-layout` with `column_break` and `custom:layout-break`). Any other `layout_type` sends it to an island unless a built-in card sits inside it

## Tier 3 → Native Migration Map

| Tier 3 card (triggers GeckoView) | Vitro-native replacement | Notes |
|---|---|---|
| Full-screen custom JS panel (a `custom:*` card that draws the whole view) | `type: sections` + `clock`, `weather-forecast`, `tile`, `media-control`, `calendar`, `todo-list`, `markdown`, `custom:mushroom-chips-card` + YAML theme | Use `visibility:` conditions for presence-driven layout switching |
| `custom:button-card` with JavaScript templates (`[[[ ]]]`) | The same button-card without JavaScript (drawn natively), or `tile`, `button`, `custom:mushroom-template-card` | Move `[[[ ]]]` logic to `state:` rules, or to `custom:mushroom-template-card` / `markdown` for Jinja2 labels and icons |
| `custom:apexcharts-card` | `custom:mini-graph-card`, `history-graph`, or `statistics-graph` | All three render natively on Android Canvas |
| `custom:mod-card` / Shadow-DOM `card-mod` | HA YAML theme + card-level `card-mod` | Vitro natively applies backgrounds (colors, linear gradients), solid borders, `border-radius`, `box-shadow`, `opacity`, `padding`, `margin`, `font-family` and a pinned `position: fixed` on `ha-card`; `font-size` on tile and Mushroom cards |
| `map`, `energy-*` and other built-in cards Vitro doesn't draw | `picture-elements`, `picture-entity`, `tile`, `history-graph` or `statistics-graph` | The view holding one opens in Home Assistant's frontend, so replace it to keep the view native |
| `custom:ha-floorplan` | `picture-elements` | Supports `state-icon`, `state-label`, `state-badge`, `icon`, and `service-button` overlays |

## Supported Theme Variables (Native)

Vitro resolves `modes.light` / `modes.dark` and `var(--...)` chains for:
- `primary-color`, `accent-color`, `primary-background-color`, `secondary-background-color`, `card-background-color`, `ha-card-background`
- `primary-text-color`, `secondary-text-color`, `disabled-text-color`, `divider-color`
- `ha-card-border-color`, `ha-card-border-width`, `ha-card-border-radius`, `ha-card-box-shadow`
- `masonry-view-card-margin`, `ha-view-sections-column-gap`
- `lovelace-background` (solid color, `linear-gradient(...)`, `radial-gradient(...)`, `url(...)`)
- `state-<domain>-active-color`, `state-<domain>-on-color`, `state-active-color`, `state-icon-color`, `state-inactive-color`

## Example: Two-Way Mirror Theme + Presence-Aware Sections Dashboard

A two-way mirror is one example layout. The entity IDs below are placeholders: swap in the user's own entities and keep everything else the same.

### 1. Pure-Black Glass Mirror Theme (`themes/example_mirror.yaml`)

```yaml
Example Mirror:
  primary-background-color: "#000000"
  secondary-background-color: "#000000"
  lovelace-background: "#000000"
  card-background-color: "rgba(255, 255, 255, 0.06)"
  ha-card-background: "rgba(255, 255, 255, 0.06)"
  ha-card-border-color: "rgba(255, 255, 255, 0.14)"
  ha-card-border-width: "1px"
  ha-card-border-radius: "18px"
  ha-card-box-shadow: "none"
  primary-text-color: "#F5F7FA"
  secondary-text-color: "rgba(245, 247, 250, 0.65)"
  divider-color: "rgba(255, 255, 255, 0.12)"
  primary-color: "#38BDF8"
  state-light-active-color: "#FBBF24"
```

### 2. Presence-Aware Portrait View (Every Card Native)

```yaml
views:
  - path: mirror
    title: Mirror
    type: sections
    theme: Example Mirror
    max_columns: 2
    sections:
      # Full dashboard when no one is standing in front of the mirror
      - type: grid
        column_span: 2
        visibility:
          - condition: state
            entity: binary_sensor.example_presence
            state: "off"
        cards:
          - type: clock
            clock_style: digital
            time_format: "24"
            show_seconds: false
          - type: custom:clock-weather-card
            entity: weather.home
            temperature_sensor: sensor.outdoor_temperature
            forecast_rows: 5
          - type: media-control
            entity: media_player.bedroom
          - type: calendar
            entities:
              - calendar.personal
          - type: todo-list
            entity: todo.morning_routine
          - type: entity-filter
            title: Lights On
            state_filter:
              - "on"
            entities:
              - light.bedroom
              - light.bathroom
              - light.hallway

      # Minimal glanceable strip when someone steps up to the mirror
      - type: grid
        column_span: 2
        visibility:
          - condition: state
            entity: binary_sensor.example_presence
            state: "on"
        cards:
          - type: custom:mushroom-chips-card
            alignment: end
            chips:
              - type: weather
                entity: weather.home
                show_temperature: true
                show_conditions: false
              - type: template
                icon: mdi:lightbulb
                content: "{{ states.light | selectattr('state','eq','on') | list | count }}"
              - type: template
                icon: mdi:clock-outline
                content: "{{ now().strftime('%H:%M') }}"
```
