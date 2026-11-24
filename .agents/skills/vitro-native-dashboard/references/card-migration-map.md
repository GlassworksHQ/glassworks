# Vitro Native Card, Feature, and Theme Migration Reference

Every card below renders 100% natively in Vitro with **zero GeckoView**.

## Tier 1: Native Core Lovelace Cards (31)

- **Controls & summaries:** `tile`, `entities`, `glance`, `button`, `entity`, `light`, `thermostat`, `humidifier`, `alarm-panel`, `media-control`, `todo-list`, `area`
- **Information & media:** `heading`, `clock`, `sensor`, `gauge`, `weather-forecast`, `history-graph`, `statistics-graph`, `calendar`, `logbook`, `markdown` (rendered natively: headings, tables, lists, quotes, rules, `<font color>`, `<center>` and inline `<ha-icon>`, with HA server-side Jinja2 templates via `render_template`)
- **Images & floorplans:** `picture`, `picture-entity`, `picture-glance`, `picture-elements` (`state-icon`, `state-label`, `state-badge`, `icon`, `service-button`)
- **Layout & filtering:** `grid`, `horizontal-stack`, `vertical-stack`, `conditional`, `entity-filter`

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

- View types: `sections` (recommended), `masonry`, `panel` (only if its card is Tier 1 or Tier 2!)
- View `badges` (rendered as compact top status pills) and `subview: true` (back-arrow navigation)
- `visibility:` on sections and cards (`state`, `numeric_state`, `and`, `or`, `not`, `screen`)

## Tier 2: Native HACS Fast-Path Cards (12)

- `custom:mushroom-entity-card`
- `custom:mushroom-light-card`
- `custom:mushroom-title-card`
- `custom:mushroom-chips-card` (entity, template, weather, conditional and per-chip `visibility`)
- `custom:mushroom-template-card` (templated `icon_color`, `badge_icon`, `badge_color`, `picture`, `layout`, `multiline_secondary`)
- `custom:mushroom-climate-card`
- `custom:mushroom-fan-card`
- `custom:mushroom-cover-card`
- `custom:mushroom-person-card`
- `custom:bubble-card`
- `custom:mini-graph-card`
- `custom:clock-weather-card`

## Tier 3 → Native Migration Map

| Tier 3 card (triggers GeckoView) | Vitro-native replacement | Notes |
|---|---|---|
| Full-screen custom JS panel (`custom:mirror-home-card`, `custom:tablet-home-card`) | `type: sections` + `clock`, `weather-forecast`, `tile`, `media-control`, `calendar`, `todo-list`, `markdown`, `custom:mushroom-chips-card` + YAML theme | Use `visibility:` conditions for presence-driven layout switching |
| `custom:button-card` | `tile`, `button`, `custom:mushroom-entity-card`, `custom:mushroom-template-card`, or `custom:bubble-card` | Use `custom:mushroom-template-card` or `markdown` for Jinja2 labels/icons |
| `custom:mini-media-player` | `media-control` or `tile` with `media-player-volume-slider` | Native `media-control` includes artwork backdrop, transport, volume, and live progress |
| `custom:apexcharts-card` | `custom:mini-graph-card`, `history-graph`, or `statistics-graph` | All three render natively on Android Canvas |
| `custom:auto-entities` | `entity-filter` or `entities` / `sections` with `visibility:` | `entity-filter` dynamically hides/shows rows matching `state_filter` |
| `custom:layout-card`, `custom:stack-in-card`, `custom:vertical-stack-in-card` | `type: sections` (`max_columns`, `column_span`) + `grid` / `horizontal-stack` / `vertical-stack` | Native Sections handles responsive multi-column grids cleanly |
| `custom:mod-card` / Shadow-DOM `card-mod` | HA YAML theme + container-level `card-mod` | Vitro natively applies `background`, `border-radius`, `border`, `opacity` on `ha-card` |
| `custom:ha-floorplan` | `picture-elements` | Supports `state-icon`, `state-label`, `state-badge`, `icon`, and `service-button` overlays |

## Supported Theme Variables (Native)

Vitro resolves `modes.light` / `modes.dark` and `var(--...)` chains for:
- `primary-color`, `accent-color`, `primary-background-color`, `secondary-background-color`, `card-background-color`, `ha-card-background`
- `primary-text-color`, `secondary-text-color`, `disabled-text-color`, `divider-color`
- `ha-card-border-color`, `ha-card-border-width`, `ha-card-border-radius`, `ha-card-box-shadow`
- `masonry-view-card-margin`, `ha-view-sections-column-gap`
- `lovelace-background` (solid color, `linear-gradient(...)`, `radial-gradient(...)`, `url(...)`)
- `state-<domain>-active-color`, `state-<domain>-on-color`, `state-active-color`, `state-icon-color`, `state-inactive-color`

## Example: Two-Way Smart Mirror Theme + Presence-Aware Sections Dashboard

### 1. Pure-Black Glass Mirror Theme (`themes/vitro_mirror.yaml`)

```yaml
Vitro Mirror:
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

### 2. Presence-Aware Portrait View (Zero GeckoView)

```yaml
views:
  - path: mirror
    title: Mirror
    type: sections
    theme: Vitro Mirror
    max_columns: 2
    sections:
      # Full dashboard when no one is standing in front of the mirror
      - type: grid
        column_span: 2
        visibility:
          - condition: state
            entity: binary_sensor.mirror_occupied
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
            entity: binary_sensor.mirror_occupied
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
