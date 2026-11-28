import type { FrameDesign } from "@glassworks/frame-card";

/*
 * Global, form-factor-driven Home Assistant dashboard starter prompts ("recipes").
 *
 * Glassworks displays your Home Assistant dashboard instead of shipping its own home UI
 * (docs/principles.md #3 and #4). Rather than maintaining bulky one-size-fits-all HACS cards,
 * these prompts give any LLM the exact Glassworks entity contracts and hardware constraints so
 * you can generate a single-file Lovelace card (in www/) tailored to your own rooms and entities.
 */
export type DashboardRecipe = {
  id: string;
  title: string;
  formFactor: string;
  summary: string;
  entities: readonly string[];
  tips: readonly string[];
  prompt: string;
};

/** A frame's screen, width over height, from its `display`: the body's outline says nothing about it */
type Screen = { ratio: number; finish: FrameDesign["model"]["finish"] };

type RecipeEntry = DashboardRecipe & {
  matches: (screen: Screen) => boolean;
};

const ENTRIES: readonly RecipeEntry[] = [
  {
    id: "two-way-mirror-presence",
    title: "Presence-aware mirror dashboard",
    formFactor: "Two-way mirrors",
    summary:
      "Shows your full dashboard when the room is empty, then fades cards out in place and reveals a slim glass capsule along the right edge when someone walks up so the mirror stays clear.",
    entities: [
      "binary_sensor.<panel>_camera_presence",
      "weather.<home>",
      "media_player.<room>",
      "light.*",
    ],
    tips: [
      "Use pure #000000 black as the page background so unlit areas stay fully reflective behind two-way glass.",
      "Fade cards out in place instead of sliding them so nothing distracts you when stepping in front of the mirror.",
      "Keep the presence capsule narrow along one edge with only time, weather, active music, and light counts.",
      "Camera presence sees movement, and clears 8 seconds after it stops: hold the capsule for a while after it clears, so standing still doesn't bring the dashboard back.",
    ],
    prompt: `Build a single-file Home Assistant custom Lovelace card (vanilla JS Web Component extending HTMLElement, no build step, placed in /config/www/mirror-dashboard-card.js) for a tall portrait two-way smart mirror running Glassworks.

Hardware & visual rules:
- Background must be pure #000000 so unlit pixels stay reflective mirror glass.
- Use calm, high-contrast typography (light weight for the clock, medium for titles) on translucent dark glass cards (rgba(255,255,255,0.06) with 1px rgba(255,255,255,0.12) borders).
- No touchscreen is required: the card is glanceable and driven by Home Assistant state.

Presence behavior:
- Read a configurable presence entity (default: binary_sensor.mirror_camera_presence). It is motion-triggered: it turns "on" when something moves in view and "off" 8 seconds after movement stops, even if someone is still standing there.
- Treat someone as present from presence turning "on" until it has been "off" for a configurable hold time (hold_seconds, default 60), so a person standing still doesn't bring the dashboard back. If I give an occupancy sensor (occupancy_entity, e.g. a mmWave sensor in the room), count someone as present while either is "on".
- When no one is present: show the full portrait dashboard (clock, weather forecast, active lights, media player, and upcoming calendar/to-do items).
- When someone is present: smoothly fade the main dashboard out in place (opacity 0, no layout shift) and fade in a slim translucent glass capsule docked along the right edge (~12% width) showing only the time, current temperature, a tiny equalizer when media is playing, and a count of lights currently on.

Make all entity IDs and the hold configurable via standard Lovelace card YAML (presence_entity, occupancy_entity, hold_seconds, weather_entity, media_player, lights, todo_entities) with sensible defaults and clean fallback text when an entity is unavailable.`,
    matches: (s) => s.finish === "mirror",
  },
  {
    id: "wide-wall-switch",
    title: "Compact 2:1 wall switch dashboard",
    formFactor: "Wide wall switches",
    summary:
      "A single-screen, zero-scroll Home Assistant layout tuned for wide 2:1 wall switch panels (like 1440×720) with large tap targets for room lights, climate, and synced music.",
    entities: [
      "light.<room>",
      "climate.<room>",
      "media_player.<panel>",
      "binary_sensor.<panel>_presence",
    ],
    tips: [
      "Avoid vertical scrolling so every everyday control is reachable in a single tap as you walk past.",
      "Keep interactive buttons at least 56 px tall with generous spacing for quick hallway taps.",
      "Skip Home Assistant's header and sidebar (Glassworks already hides them in kiosk mode).",
    ],
    prompt: `Create a Home Assistant dashboard (either native Sections YAML or a single-file custom Lovelace card in /config/www/wall-switch-card.js) designed for a wide 2:1 wall-mounted panel (1440×720, landscape) running Glassworks.

Layout & ergonomics:
- Must fit cleanly inside 100vw × 100vh with zero vertical scrolling.
- Dark, calm glass aesthetic (#0b0d13 background, subtle hairline borders, warm amber highlight for lit rooms, sky blue for active audio/climate).
- Left side (~60%): large clock and weather summary on top, with a 2×2 grid of large (>= 64px tall) room light and scene toggle tiles underneath.
- Right side (~40%): compact climate control (target temp +/-) and a Now Playing card bound to my Music Assistant / Sendspin media_player entity.
- Clicking a light tile toggles it immediately via hass.callService("light", "toggle", ...); holding or tapping a slider adjusts brightness.

Make all entity IDs configurable in YAML (weather_entity, climate_entity, media_player, and a lights array with name + entity_id).`,
    matches: (s) => s.ratio >= 1.7,
  },
  {
    id: "docked-tablet-hub",
    title: "Docked tablet hub dashboard",
    formFactor: "Docked & wall tablets",
    summary:
      "A multi-column tablet layout for 16:10 screens on a charging speaker dock or wall mount, pairing room controls with a live porch camera card and quick status chips.",
    entities: [
      "binary_sensor.<panel>_camera_presence",
      "camera.<front_door>",
      "light.*",
      "climate.*",
    ],
    tips: [
      "Use a 3-column desktop/tablet grid: quick room lights, climate/weather, and a live camera or media column.",
      "Keep card shadows subtle so the screen looks calm at night when dimmed by ambient light.",
      "Pair with a Home Assistant automation to show a cleaner clock view late at night.",
    ],
    prompt: `Build a Home Assistant Lovelace Sections dashboard YAML (using built-in Tile, Thermostat, Weather, and Picture Glance cards — no HACS required) tuned for an 11-inch 16:10 Android tablet (such as a docked Pixel Tablet) running Glassworks.

Structure:
- Top badge bar: indoor temperature, count of lights on, front door lock state, and alarm status.
- 3-column responsive grid that fits on one landscape screen without scrolling:
  1. Left column: Room light tiles with brightness sliders and a Quick Scenes row (Morning, Evening, Goodnight).
  2. Middle column: Weather card and a Thermostat card for the main HVAC zone.
  3. Right column: Live front door camera card (with a tap action to toggle the front door lock) and a Music Assistant media player card.

Keep the YAML clean, commented, and easy to swap in my own entity IDs.`,
    matches: (s) => s.finish !== "mirror" && s.ratio >= 1.25 && s.ratio < 1.7,
  },
  {
    id: "compact-square-dial",
    title: "Single-room square or round dial",
    formFactor: "Square & round dials",
    summary:
      "A focused single-room control screen built around one glanceable circular thermostat dial and two quick light switches for compact 1:1 or circular wall hardware.",
    entities: ["climate.<room>", "light.<room>", "sensor.<room>_temperature"],
    tips: [
      "Limit small screens to one primary control plus 2–3 quick switches so nothing feels cramped.",
      "Use large tabular numerals for temperature so you can read it from across the room.",
    ],
    prompt: `Build a single-file Home Assistant custom Lovelace card (vanilla JS Web Component in /config/www/room-dial-card.js, no build step) for a compact square (1:1) or circular wall display running Glassworks.

Design:
- Dark OLED-friendly background (#0a0b0f) with a single centered circular climate arc dial showing current and target temperature in large tabular light numerals.
- Tapping left/right (or +/- controls) steps the target temperature via climate.set_temperature.
- Below the dial (or tucked cleanly around the lower rim on square screens): up to 3 minimal pill toggles for room lights.
- Keep touch targets generous and readable from 2 meters away.

Accept climate_entity and an optional lights array in the Lovelace card config.`,
    matches: (s) => Math.abs(s.ratio - 1) <= 0.2,
  },
  {
    id: "vitro-native-rewrite",
    title: "Convert any dashboard to Vitro-native",
    formFactor: "Vitro native (zero browser)",
    summary:
      "Rewrites an existing custom JS or HACS Lovelace dashboard (including two-way mirror panels) into pure Vitro-native Sections YAML and a matching theme so GeckoView never has to boot.",
    entities: ["light.*", "weather.<home>", "climate.<room>", "media_player.<room>"],
    tips: [
      "Stick to built-in Lovelace cards plus Vitro's 26 native HACS cards (the whole Mushroom suite, Bubble Card, mini-graph-card, mini-media-player, clock-weather-card, modern-circular-gauge, bar-card, auto-entities, stack-in-card and layout-card) so zero browser processes start.",
      "Replace custom JS presence timers with Home Assistant visibility conditions backed by a binary_sensor with delay_off.",
      "Move visual polish (pure #000000 mirror black, translucent glass cards, shadows, accents) into a Home Assistant YAML theme instead of Shadow-DOM card-mod.",
    ],
    prompt: `Rewrite my attached Home Assistant Lovelace dashboard (and any custom JS card it uses) so it looks as close to the original as possible while being 100% Vitro-native (zero GeckoView / zero browser micro-islands).

Strict card rules (use ONLY these card types):
- Built-in Lovelace cards: tile (features: light-brightness, light-color-temp, fan-speed, cover-open-close, cover-position, climate-hvac-modes, target-temperature, select-options, media-player-volume-slider, toggle, media-player-playback, light-color-favorites, numeric-input, vacuum-commands), entities, glance, button, heading, clock, sensor, history-graph, statistics-graph, gauge, thermostat, humidifier, weather-forecast, markdown (Jinja2 templates), picture, picture-entity, picture-glance, picture-elements, media-control, alarm-panel, todo-list, calendar, logbook, area, entity, light, grid, horizontal-stack, vertical-stack, conditional, entity-filter.
- Native HACS cards (zero browser): custom:mushroom-entity-card, custom:mushroom-light-card, custom:mushroom-title-card, custom:mushroom-chips-card, custom:mushroom-template-card, custom:mushroom-climate-card, custom:mushroom-fan-card, custom:mushroom-cover-card, custom:mushroom-person-card, custom:bubble-card, custom:mini-graph-card, custom:clock-weather-card, custom:modern-circular-gauge, custom:bar-card, custom:mushroom-media-player-card, custom:mushroom-lock-card, custom:mushroom-alarm-control-panel-card, custom:mushroom-vacuum-card, custom:mushroom-humidifier-card, custom:mushroom-number-card, custom:mushroom-select-card, custom:mini-media-player, custom:auto-entities, custom:stack-in-card, custom:vertical-stack-in-card, custom:layout-card.
- Do NOT use any other custom:* card (no custom JS panel cards, button-card, apexcharts-card, or mod-card).

Layout, styling & behavior rules:
- Use HA Sections views (type: sections) with max_columns, column_span, grid, horizontal-stack, and vertical-stack to match the original layout and screen aspect ratio.
- Express all visual styling as a Home Assistant YAML theme (primary-background-color, lovelace-background including #000000 for two-way mirrors or CSS gradients/urls, rgba() ha-card-background, ha-card-border-color/width/radius, ha-card-box-shadow, and --state-<domain>-active-color). Avoid Shadow-DOM card-mod selectors ($: |, ::before, @keyframes).
- Replace JS presence or mode switching with native Lovelace visibility conditions on sections/cards. If presence needs a hold delay (e.g., 60 seconds after motion clears), include a clean Home Assistant template binary_sensor YAML snippet with delay_off.
- Keep every original entity ID, friendly label, and tap/hold action intact. Output both the complete Lovelace YAML and the companion YAML theme, plus a short list of any small visual trade-offs.`,
    matches: () => false,
  },
];

const toPublic = ({ matches: _matches, ...recipe }: RecipeEntry): DashboardRecipe => {
  void _matches;
  return recipe;
};

export const RECIPES: readonly DashboardRecipe[] = ENTRIES.map(toPublic);

/**
 * Starter prompts whose form factor matches a given frame's screen. A frame without a known screen (Generic
 * Android: tablets, phones and panels of every shape, its model only an illustration) gets them all.
 */
export function recipesForFrame(frame: FrameDesign): DashboardRecipe[] {
  if (!frame.display) return [...RECIPES];
  const screen: Screen = { ratio: frame.display.width / frame.display.height, finish: frame.model.finish };
  return ENTRIES.filter((r) => r.matches(screen)).map(toPublic);
}
