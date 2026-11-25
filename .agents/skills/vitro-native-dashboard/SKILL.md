---
name: vitro-native-dashboard
description: >-
  Audit and rewrite any Home Assistant Lovelace dashboard (including custom JS
  panel cards like smart-mirror dashboards, HACS cards, and complex card-mod
  layouts) into a 100% Vitro-native YAML dashboard and theme so GeckoView never
  boots. Use when the user asks to convert, migrate, or rebuild a dashboard to
  be Vitro-native, remove GeckoView triggers, or reduce panel RAM usage while
  keeping the same look and layout.
---

# Rewrite a Home Assistant Dashboard to Be 100% Vitro-Native

Glassworks' **Vitro** engine renders **31 built-in Lovelace cards (Tier 1)** and **26 popular HACS cards (Tier 2)** directly in native Android Views, without a browser. Any unrecognized `custom:*` card triggers a **Tier 3 GeckoView micro-island**, which starts the full browser engine for that card. Vitro's memory use hasn't been measured against GeckoView yet, so don't promise the user a number.

Use this workflow to rewrite an existing dashboard into a **100% Vitro-native** dashboard and theme that keeps the same visual layout, aesthetic, entities, and actions with **zero Tier 3 GeckoView triggers**.

Sometimes those goals conflict. A custom JS panel can do something no native card does (a drawn animation, its own interactions, a layout no grid reproduces). When no supported card can faithfully replace part of it, say so to the user: name what would be lost and let them choose between dropping that behavior and keeping that one card as a Tier 3 island. Don't remove the island silently and don't claim the rewrite is faithful.

## Workflow

### 1. Back up and audit the current dashboard

Never overwrite a user's active dashboard without preserving a backup or writing to a new dashboard slug first (for example, `mirror-home-vitro` alongside `mirror-home`).

Run the bundled auditor on the dashboard YAML or storage JSON:

```sh
python3 .agents/skills/vitro-native-dashboard/scripts/audit_vitro_dashboard.py path/to/dashboard.yaml
```

The auditor prints every view and card with its Vitro tier (`TIER 1`, `TIER 2`, or `TIER 3 [GECKOVIEW]`), flags unsupported Tile features or deep Shadow-DOM `card-mod` rules, and exits `0` only when **zero Tier 3 triggers** remain.

### 2. Inspect any custom JS cards being replaced

If the dashboard uses a custom JS panel card (for example `/config/www/mirror-dashboard-card.js` or an inline `data:` module):

1. Read the JS source to inventory:
   - **Visual style:** background color (`#000000` for two-way mirrors), card fill (`rgba(...)` glass, solid, or transparent), border color/width/radius, shadows, typography hierarchy, and accent colors.
   - **Dynamic behavior:** presence-triggered fading or hiding, conditional sections, entity counts, formatted templates, and tap/hold actions.
   - **Layout structure:** column counts, full-width vs half-width cards, portrait vs 2:1 landscape fit.
2. Check [references/card-migration-map.md](./references/card-migration-map.md) for the exact Tier 1 and Tier 2 native equivalents.

### 3. Rewrite layout, cards, and theme natively

Follow these rules strictly so GeckoView never loads:

- **Use ONLY Tier 1 core cards and Tier 2 native HACS cards.** Every supported type is listed in [references/card-migration-map.md](./references/card-migration-map.md).
- **Replace custom layouts with HA Sections (`type: sections`).** Use `max_columns`, `column_span`, `grid_options`, `grid`, `horizontal-stack`, `vertical-stack`, and `heading` instead of full-screen custom panel JS. `custom:layout-card`, `custom:stack-in-card` and `custom:vertical-stack-in-card` render natively too, so an existing one can stay.
- **Replace JS presence/mode switching with native `visibility:` conditions.** Both sections and individual cards support HA `visibility:` (`condition: state`, `numeric_state`, `and`, `or`, `not`, `screen`).
  - For a **two-way mirror presence hold** (where camera motion clears after 8 seconds): back the visibility condition with a Home Assistant `binary_sensor` / `input_boolean` or template helper with `delay_off: "00:01:00"` (HA stays the brain — no custom JS timer required).
  - Put the full room-empty layout in sections with `visibility: [{ condition: state, entity: binary_sensor.mirror_occupied, state: "off" }]`, and the slim occupied status strip in a section with `state: "on"`.
- **Replace custom HTML/CSS styling with a native HA YAML theme.** Vitro resolves `modes.light`/`modes.dark`, `var(--...)` chains, hex/`rgb()`/`rgba()`/`hsl()`, `linear-gradient(...)`, `radial-gradient(...)`, `url(...)` backgrounds, `ha-card-border-*`, `ha-card-box-shadow`, `--state-<domain>-active-color`, and container-level `card-mod` (background, border-radius, border, opacity). Avoid Shadow-DOM `card-mod` selectors (`$: |`), `::before`/`::after`, and `@keyframes`.
- **Keep entity IDs, names, and actions intact.** Preserve every `tap_action`, `hold_action`, `navigate`, `call-service`, and `more-info` behavior from the original dashboard.

### 4. Validate zero GeckoView triggers

Re-run the auditor on the rewritten dashboard:

```sh
python3 .agents/skills/vitro-native-dashboard/scripts/audit_vitro_dashboard.py path/to/rewritten-dashboard.yaml
```

Confirm:
1. Exit code is `0` (**0 Tier 3 cards** across every view).
2. No deep `card-mod` warnings remain that would break layout readability.
3. No real secrets, tokens, or personal credentials were written into shared repo files.

### 5. Visually verify

If a device or emulator is available, open the rewritten dashboard in Glassworks with Vitro enabled (`Settings → Labs → Vitro renderer` or `--ez vitro true`; either takes effect after the app restarts) and check:
- Visual parity against the original dashboard at the target frame's real aspect ratio (portrait for mirrors, 2:1 `1440×720` for S1 Plus, 16:10 for docked tablets).
- `adb shell ps -A | grep -E 'sh\.edm\.s1kiosk|dev\.glassworks\.generic_android'` (the S1 app's id, or the generic app's) shows the app's main process first; then read the rest: **no `:sandboxed_tab` or `:gpu` GeckoView processes** may be running.
