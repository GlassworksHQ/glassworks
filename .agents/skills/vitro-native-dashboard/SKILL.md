---
name: vitro-native-dashboard
description: >-
  Audit and rewrite a Home Assistant Lovelace dashboard (including full-screen
  custom JS panel cards, HACS cards, and complex card-mod layouts) into a
  Vitro-native YAML dashboard and theme, aiming for no card that needs GeckoView.
  Vitro is an experimental Labs engine, so verify the result on the panel. Use when the user asks to convert, migrate, or rebuild a dashboard to
  be Vitro-native, remove GeckoView triggers, or reduce panel RAM usage while
  keeping the same look and layout.
---

# Rewrite a Home Assistant Dashboard to Be Vitro-Native

Glassworks' **Vitro** engine renders **41 built-in Lovelace card types (Tier 1)** and **30 popular HACS cards (Tier 2)** directly in native Android Views, without a browser. Any unrecognized `custom:*` card triggers a **Tier 3 GeckoView micro-island**, which starts the full browser engine for that card. So does a HACS card Vitro draws when its config has something Vitro can't draw (a Bubble Card pop-up, an unsupported chip, card-mod that reaches inside the card). A custom card that holds built-in cards is the exception: an option it doesn't draw (a `custom:layout-card` layout Vitro doesn't draw, around a tile) sends the whole view to Home Assistant's frontend, because the island can't draw the built-in cards inside it. Vitro's memory use hasn't been measured against GeckoView yet, so don't promise the user a number.

A built-in card Vitro draws natively never goes to an island, because the island can't run Home Assistant's own cards. (A built-in card Vitro has no renderer for, such as `map`, doesn't go to an island either: its whole view opens in Home Assistant's frontend.) Vitro follows a strict rule: a card it draws claims a config only when it draws every option and value in it exactly as Home Assistant does. When a built-in card has something Vitro doesn't draw (an unsupported tile feature, an entities header, a `climate` row, a double-tap action), the whole view opens in Home Assistant's frontend instead. The auditor reports these as `TIER 3 [HA FRONTEND]` lines. Its card-mod check is different and coarse: a built-in card stays native for its card-mod and gets a `WARN` line, so read the site checker's notes for those too.

Use this workflow to rewrite an existing dashboard into a **Vitro-native** dashboard and theme that keeps the same visual layout, aesthetic, entities, and actions, with the goal of **zero Tier 3 triggers**. Vitro is an experimental engine (Settings → Labs), and the auditor and the site checker follow its routing rules but can't promise how a given panel draws: a clean audit means the dashboard should stay native, and the panel check in step 5 is what confirms it.

Sometimes those goals conflict. A custom JS panel can do something no native card does (a drawn animation, its own interactions, a layout no grid reproduces). When no supported card can faithfully replace part of it, say so to the user: name what would be lost and let them choose between dropping that behavior and keeping that one card as a Tier 3 island. Don't remove the island silently and don't claim the rewrite is faithful.

## Workflow

### 1. Back up and audit the current dashboard

Never overwrite a user's active dashboard without preserving a backup or writing to a new dashboard slug first (for example, `home-vitro` alongside `home`).

Run the bundled auditor on the dashboard YAML or storage JSON:

```sh
python3 .agents/skills/vitro-native-dashboard/scripts/audit_vitro_dashboard.py path/to/dashboard.yaml
```

The auditor routes cards the way the engine does and prints every view with its counts, each `TIER 3 [GECKOVIEW]` card and `TIER 3 [HA FRONTEND]` view with the reason, and a `WARN` line for each card-mod part Vitro leaves out. It exits `0` only when it finds **zero Tier 3 triggers**.

Its card-mod check is coarse: it flags selectors that reach inside a card, pseudo-elements, keyframes and unknown sheet paths, but not each CSS property. For the property-level verdict, paste the dashboard into the site's checker at <https://www.glassworks.dev/blueprints#vitro-check> (it runs in the browser and uses the engine's full rules), or read the panel's own report: with Vitro on, logcat's `Glassworks.Vitro` tag logs one `Island <where>: <why>` line per island.

### 2. Inspect any custom JS cards being replaced

If the dashboard uses a custom JS panel card (for example a `.js` file under `/config/www/` loaded as a Lovelace resource, or an inline `data:` module):

1. Read the JS source to inventory:
   - **Visual style:** background color (often `#000000` on a two-way mirror), card fill (`rgba(...)` glass, solid, or transparent), border color/width/radius, shadows, typography hierarchy, and accent colors.
   - **Dynamic behavior:** presence-triggered fading or hiding, conditional sections, entity counts, formatted templates, and tap/hold actions.
   - **Layout structure:** column counts, full-width vs half-width cards, portrait vs 2:1 landscape fit.
2. Check [references/card-migration-map.md](./references/card-migration-map.md) for the exact Tier 1 and Tier 2 native equivalents.

### 3. Rewrite layout, cards, and theme natively

Follow these rules strictly to keep every card off GeckoView:

- **Use ONLY Tier 1 core cards and Tier 2 native HACS cards.** Every supported type is listed in [references/card-migration-map.md](./references/card-migration-map.md).
- **Replace custom layouts with HA Sections (`type: sections`).** Use `max_columns`, `column_span`, `grid_options`, `grid`, `horizontal-stack`, `vertical-stack`, and `heading` instead of full-screen custom panel JS. `custom:layout-card`, `custom:stack-in-card` and `custom:vertical-stack-in-card` render natively too, so an existing one can stay.
- **Replace JS presence/mode switching with native `visibility:` conditions.** Both sections and individual cards support HA `visibility:` (`condition: state`, `numeric_state`, `and`, `or`, `not`, `screen`).
  - For a **presence hold** (for example on a two-way mirror, where a motion or presence sensor clears quickly): back the visibility condition with a Home Assistant `binary_sensor` / `input_boolean` or template helper with a `delay_off` (HA stays the brain, so no custom JS timer is needed).
  - Put the full room-empty layout in sections with `visibility: [{ condition: state, entity: binary_sensor.<presence_helper>, state: "off" }]`, and the slim occupied status strip in a section with `state: "on"`. Use the user's own helper; `binary_sensor.<presence_helper>` is a placeholder.
- **Replace custom HTML/CSS styling with a native HA YAML theme.** Vitro resolves `modes.light`/`modes.dark`, `var(--...)` chains, hex/`rgb()`/`rgba()`/`hsl()`, `linear-gradient(...)`, `radial-gradient(...)`, `url(...)` backgrounds, `ha-card-border-*`, `ha-card-box-shadow` and `--state-<domain>-active-color` in themes.
- **Keep card-mod on the card itself.** On any card, Vitro draws a `ha-card { }` / `:host { }` rule with backgrounds (colors and linear gradients), solid borders, `border-radius`, `box-shadow`, `opacity`, `padding`, `margin`, `height: 100%`, `font-family`, and a card pinned with `position: fixed`. Tile and Mushroom cards also take `font-size`. Inside Markdown cards (`ha-markdown$`) and the clock (`hui-clock-card-digital$`), it runs a real CSS cascade. Avoid selectors that reach inside other cards (`ha-card .primary`, `mushroom-shape-icon$`), background images and radial gradients on a card, `transform`, `filter`, `animation`, `@media`, `::before`/`::after`, and `color` on cards other than Markdown and the clock.
- **Fonts carry over.** A theme's `--ha-font-family-body`, a card-mod `font-family` and fonts from a `type: css` Lovelace resource (`@font-face`, Google Fonts) all draw natively. WOFF2-only faces don't: list a WOFF or TTF too.
- **Keep entity IDs, names, and actions intact.** Preserve every `tap_action`, `hold_action`, `navigate`, `call-service`, and `more-info` behavior from the original dashboard.

### 4. Validate zero Tier 3 triggers

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
