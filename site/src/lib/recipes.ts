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

type RecipeEntry = DashboardRecipe & {
  matches: (frame: FrameDesign) => boolean;
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
    ],
    prompt: `Build a single-file Home Assistant custom Lovelace card (vanilla JS Web Component extending HTMLElement, no build step, placed in /config/www/mirror-dashboard-card.js) for a tall portrait two-way smart mirror running Glassworks.

Hardware & visual rules:
- Background must be pure #000000 so unlit pixels stay reflective mirror glass.
- Use calm, high-contrast typography (light weight for the clock, medium for titles) on translucent dark glass cards (rgba(255,255,255,0.06) with 1px rgba(255,255,255,0.12) borders).
- No touchscreen is required: the card is glanceable and driven by Home Assistant state.

Presence behavior:
- Read a configurable presence entity (default: binary_sensor.mirror_camera_presence).
- When presence is "off": show the full portrait dashboard (clock, weather forecast, active lights, media player, and upcoming calendar/to-do items).
- When presence turns "on": smoothly fade the main dashboard out in place (opacity 0, no layout shift) and fade in a slim translucent glass capsule docked along the right edge (~12% width) showing only the time, current temperature, a tiny equalizer when media is playing, and a count of lights currently on.

Make all entity IDs configurable via standard Lovelace card YAML (presence_entity, weather_entity, media_player, lights, todo_entities) with sensible defaults and clean fallback text when an entity is unavailable.`,
    matches: (f) => f.model.finish === "mirror",
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
    matches: (f) => f.model.body.width / f.model.body.height >= 1.7,
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
    matches: (f) => {
      const ratio = f.model.body.width / f.model.body.height;
      return f.model.finish !== "mirror" && ratio >= 1.25 && ratio < 1.7;
    },
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
    matches: (f) => Math.abs(f.model.body.width / f.model.body.height - 1) <= 0.2,
  },
];

const toPublic = ({ matches: _matches, ...recipe }: RecipeEntry): DashboardRecipe => {
  void _matches;
  return recipe;
};

export const RECIPES: readonly DashboardRecipe[] = ENTRIES.map(toPublic);

/** Starter prompts whose form factor matches a given frame. */
export function recipesForFrame(frame: FrameDesign): DashboardRecipe[] {
  return ENTRIES.filter((r) => r.matches(frame)).map(toPublic);
}
