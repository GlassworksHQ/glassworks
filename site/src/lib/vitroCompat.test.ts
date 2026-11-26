import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { MATCHED_ROWS_NOTE, SAMPLE_DASHBOARD, VITRO_COMPAT, auditDashboard, parseYaml, stripCssComments, topLevelAtRules } from "./vitroCompat";
import { kotlin, kotlinCardModSheets, kotlinSet, kotlinSetMap, kotlinStringMap } from "./vitroSupportSource";

const repo = join(import.meta.dirname, "../../..");
const python = readFileSync(join(repo, ".agents/skills/vitro-native-dashboard/scripts/audit_vitro_dashboard.py"), "utf8");
const published = JSON.parse(readFileSync(join(import.meta.dirname, "../../public/vitro-compat.json"), "utf8"));
const engineCompat = join(repo, "engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/compat/native-options.json");
const nativeOptionsCases = JSON.parse(
  readFileSync(join(repo, "engines/engine-vitro/src/test/resources/dev/glassworks/engine/vitro/compat/native-options-cases.json"), "utf8"),
);

function pythonSet(name: string): string[] {
  const body = python.match(new RegExp(`^${name} = \\{([\\s\\S]*?)\\}`, "m"))?.[1];
  if (!body) throw new Error(`${name} not found in audit_vitro_dashboard.py`);
  return [...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

describe("the published compatibility manifest", () => {
  it("is what the checker uses", () => {
    expect(VITRO_COMPAT).toEqual(published);
    expect(published.version).toBe("0.0.3");
  });

  it("lists the card types the Vitro engine draws natively, verbatim", () => {
    expect(published.nativeCoreTypes).toEqual(kotlinSet("CORE_CARD_TYPES"));
    expect(published.nativeHacsTypes).toEqual(kotlinSet("HACS_CARD_TYPES"));
    expect(published.nativeCoreTypes).toHaveLength(41);
    expect(published.nativeHacsTypes).toHaveLength(30);
  });

  // Every list CardCompat routes with, so a card the engine learns to draw (or a new option, sheet or property)
  // can't reach the panel without reaching the site's checker too.
  it("holds every list the engine routes a card with, verbatim", () => {
    // The strict compat rule: what each native card draws exactly as HA does, the engine's own file verbatim.
    expect(published.nativeOptions).toEqual(JSON.parse(readFileSync(engineCompat, "utf8")));
    expect(published.stackTypes).toEqual(kotlinSet("STACK_TYPES"));
    expect(published.cardModSheets).toEqual(kotlinCardModSheets());
    expect(published.cardModOtherKeys).toEqual(kotlinStringMap("CARD_MOD_OTHER_KEYS"));
    expect(published.cardModNotApplied).toEqual(kotlinStringMap("CARD_MOD_NOT_APPLIED"));
    expect(published.fontSizeTypes).toEqual(kotlinSet("FONT_SIZE_TYPES"));
    expect(published.cardProperties).toEqual(kotlinSet("CARD_PROPERTIES"));
    expect(published.cardFontProperties).toEqual(kotlinSet("CARD_FONT_PROPERTIES"));
    expect(published.stackProperties).toEqual(kotlinSet("STACK_PROPERTIES"));
    expect(published.cardVariables).toEqual(kotlinSet("CARD_VARIABLES"));
    expect(published.cascadeProperties).toEqual(kotlinSet("CASCADE_PROPERTIES"));
    expect(published.markdownDisplay).toEqual(kotlinSet("MARKDOWN_DISPLAY"));
    expect(published.pseudoClasses).toEqual(kotlinSet("PSEUDO_CLASSES"));
    expect(published.benignPseudoClasses).toEqual(kotlinStringMap("BENIGN_PSEUDO_CLASSES"));
    expect(published.benignPseudoElements).toEqual(kotlinStringMap("BENIGN_PSEUDO_ELEMENTS"));
    expect(published.benignProperties).toEqual(kotlinStringMap("BENIGN_PROPERTIES"));
    expect(published.benignValues).toEqual(kotlinSetMap("BENIGN_VALUES"));
    expect(published.knownGaps).toEqual(kotlinStringMap("KNOWN_GAP_PROPERTIES"));
    expect(published.undrawnMarkdownTags).toEqual(kotlinStringMap("UNDRAWN_MARKDOWN_TAGS"));
  });

  it("matches the audit script's lists", () => {
    expect(new Set(published.nativeCoreTypes)).toEqual(new Set(pythonSet("NATIVE_CORE_TYPES")));
    expect(new Set(published.nativeHacsTypes)).toEqual(new Set(pythonSet("NATIVE_HACS_TYPES")));
    expect(new Set(published.containerTypes)).toEqual(new Set(pythonSet("CONTAINER_TYPES")));
    expect(new Set(Object.keys(published.undrawnMarkdownTags))).toEqual(new Set(pythonSet("UNDRAWN_MARKDOWN_TAGS")));
    // The audit script reads the engine's native-options.json itself.
    expect(python).toContain("engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/compat/native-options.json");
    expect(published.containerTypes).toHaveLength(8);
  });

  it("lists button-card's native subset and swipe-card's parameters as the engine does", () => {
    expect(published.buttonCard.layouts).toEqual(kotlinSet("BUTTON_CARD_LAYOUTS"));
    expect(published.buttonCard.actions).toEqual(kotlinSet("BUTTON_CARD_ACTIONS"));
    expect(published.buttonCard.styleParts).toEqual(kotlinSet("BUTTON_CARD_STYLE_PARTS"));
    expect(published.buttonCard.animations).toEqual(kotlinSet("BUTTON_CARD_ANIMATIONS"));
    expect(published.swipeCardParameters).toEqual(kotlinSet("SWIPE_CARD_PARAMETERS"));
    expect(new Set(published.buttonCard.unsupportedOptions)).toEqual(new Set(Object.keys(kotlinStringMap("BUTTON_CARD_UNSUPPORTED_OPTIONS"))));
    expect(new Set(published.buttonCard.unsupportedActionFields)).toEqual(new Set(Object.keys(kotlinStringMap("BUTTON_CARD_UNSUPPORTED_ACTION_FIELDS"))));
    expect(new Set(published.buttonCard.benignProperties)).toEqual(new Set(Object.keys(kotlinStringMap("BENIGN_PROPERTIES"))));
    const text = kotlinSet("BUTTON_CARD_TEXT");
    for (const [part, props] of Object.entries(published.buttonCard.styleProperties) as [string, string[]][]) {
      const body = kotlin.match(new RegExp(`"${part}" to (BUTTON_CARD_TEXT(?: \\+ setOf\\(([^)]*)\\))?|setOf\\(([\\s\\S]*?)\\),\\n)`));
      expect(body, part).toBeTruthy();
      const own = [...(body![2] ?? body![3] ?? "").matchAll(/"([^"]+)"/g)].map((m) => m[1]);
      expect(new Set(props), part).toEqual(new Set(body![1].startsWith("BUTTON_CARD_TEXT") ? [...text, ...own] : own));
    }
    expect(new Set(published.buttonCard.unsupportedOptions)).toEqual(new Set(pythonSet("BUTTON_CARD_UNSUPPORTED_OPTIONS")));
    expect(new Set(published.buttonCard.actions)).toEqual(new Set(pythonSet("BUTTON_CARD_ACTIONS")));
    expect(new Set(published.buttonCard.layouts)).toEqual(new Set(pythonSet("BUTTON_CARD_LAYOUTS")));
  });

  it("walks the same containers the engine flattens", () => {
    expect(published.containerTypes).toEqual(kotlinSet("CONTAINER_TYPES"));
    // The checker reads a container's `cards:` for all but `conditional`, whose child is its `card:`.
    expect(kotlinSet("CARDS_FIELD_TYPES")).toEqual(published.containerTypes.filter((t: string) => t !== "conditional"));
    expect(kotlin).toContain('"conditional" -> listOfNotNull(raw.optJSONObject("card")');
    expect(kotlin).toContain("c.type in CONTAINER_TYPES");
  });
});

// The strict compat rule's shared cases: NativeOptionsTest routes the same configs in the engine, so the checker and
// the panel can't disagree about one, nor word its first reason differently.
describe("the strict compat rule, case for case with the engine", () => {
  const FRONTEND = "Home Assistant's frontend draws this view: ";
  type Case = { what: string; route: string; reason?: string };
  const routeOf = (input: unknown): [string, string | null] => {
    const r = auditDashboard(JSON.stringify(input));
    expect(r.ok, r.error).toBe(true);
    // HA's frontend takes the whole view, whatever else in it would go to an island (WebFallback decides first).
    const reasons = r.triggers.map((t) => t.reasons[0]);
    const frontend = reasons.find((x) => x.startsWith(FRONTEND));
    if (frontend !== undefined) return ["frontend", frontend.slice(FRONTEND.length)];
    return reasons.length > 0 ? ["island", reasons[0]] : ["native", null];
  };
  const check = (c: Case, input: unknown) => {
    const [route, reason] = routeOf(input);
    expect(route, `${c.what}: ${reason}`).toBe(c.route);
    if (c.reason !== undefined) expect(reason, c.what).toBe(c.reason);
  };

  it("routes every card case as the engine does", () => {
    for (const c of nativeOptionsCases.cards as (Case & { card: unknown })[]) check(c, { views: [{ cards: [c.card] }] });
  });

  it("routes every view case as the engine does", () => {
    for (const c of nativeOptionsCases.views as (Case & { view: unknown })[]) check(c, { views: [c.view] });
  });
});

describe("auditDashboard and custom:button-card", () => {
  const audit = (cards: unknown[], extra: Record<string, unknown> = {}) => auditDashboard(JSON.stringify({ ...extra, views: [{ cards }] }));

  it("draws a button-card without JavaScript natively, and sends JavaScript templates to an island", () => {
    expect(audit([{ type: "custom:button-card", entity: "light.a", show_state: true, styles: { card: [{ height: "80px" }], name: [{ "font-size": "14px" }] } }]).totals)
      .toEqual({ tier1: 0, tier2: 1, tier3: 0 });
    const js = audit([{ type: "custom:button-card", entity: "light.a", name: "[[[ return 'x' ]]]" }]);
    expect(js.totals.tier3).toBe(1);
    expect(js.triggers[0].reasons.join()).toMatch(/JavaScript templates/);
    // Four brackets are an escape for text, not JavaScript.
    expect(audit([{ type: "custom:button-card", name: "[[[[x]]]]" }]).totals.tier3).toBe(0);
  });

  it("sends a timer's countdown to an island, as the engine does", () => {
    const timer = audit([{ type: "custom:button-card", entity: "timer.laundry", show_state: true }]);
    expect(timer.totals.tier3).toBe(1);
    expect(timer.triggers[0].reasons.join()).toMatch(/timer/);
    expect(audit([{ type: "custom:button-card", entity: "timer.laundry" }]).totals.tier3).toBe(0);
    // `show_state` as button-card reads it: the string "0" is on; false and 0 are off.
    expect(audit([{ type: "custom:button-card", entity: "timer.laundry", show_state: "0" }]).totals.tier3).toBe(1);
    expect(audit([{ type: "custom:button-card", entity: "timer.laundry", show_state: false }]).totals.tier3).toBe(0);
    expect(audit([{ type: "custom:button-card", entity: "timer.laundry", show_state: 0 }]).totals.tier3).toBe(0);
    expect(audit([{ type: "custom:button-card", entity: "timer.laundry", show_state: true, state_display: "Running" }]).totals.tier3).toBe(0);
    expect(audit([{ type: "custom:button-card", entity: "light.timer", show_state: true }]).totals.tier3).toBe(0);
  });

  it("merges the dashboard's button_card_templates before it checks", () => {
    const templates = { button_card_templates: { base: { styles: { card: [{ filter: "blur(2px)" }] } }, ok: { color_type: "card" } } };
    expect(audit([{ type: "custom:button-card", template: "ok" }], templates).totals.tier3).toBe(0);
    expect(audit([{ type: "custom:button-card", template: "base" }], templates).triggers[0].reasons.join()).toMatch(/filter/);
    expect(audit([{ type: "custom:button-card", template: "missing" }], templates).triggers[0].reasons.join()).toMatch(/button_card_templates/);
  });

  it("sends cards in custom_fields, HTML and styles it doesn't draw to an island", () => {
    expect(audit([{ type: "custom:button-card", custom_fields: { a: { card: { type: "tile" } } } }]).totals.tier3).toBe(1);
    expect(audit([{ type: "custom:button-card", name: "<b>x</b>" }]).totals.tier3).toBe(1);
    expect(audit([{ type: "custom:button-card", custom_fields: { a: "22°" } }]).totals.tier3).toBe(0);
    expect(audit([{ type: "custom:button-card", styles: { card: [{ border: "1px dashed red" }] } }]).totals.tier3).toBe(1);
    // A value the browser drops (a stray `;`) is dropped natively too.
    expect(audit([{ type: "custom:button-card", styles: { card: [{ padding: "0;" }] } }]).totals.tier3).toBe(0);
    // A quoted slash-star is text: it doesn't hide the dashed border after it.
    expect(audit([{ type: "custom:button-card", styles: { card: [{ border: "'/*' 1px dashed red '*/'" }] } }]).totals.tier3).toBe(1);
  });

  it("sends a shortcut whose default only HA's frontend knows, and an iframe of HA's own pages, to HA's frontend", () => {
    const frontend = (card: Record<string, unknown>) => {
      const r = audit([card]);
      return r.triggers.length === 1 && r.triggers[0].reasons.join().startsWith("Home Assistant's frontend draws this view:");
    };
    // WebFallback.shortcutReason: a dashboard or view path is named by that page, an action by the service.
    expect(frontend({ type: "shortcut", tap_action: { action: "navigate", navigation_path: "/lovelace/kitchen" } })).toBe(true);
    expect(frontend({ type: "shortcut", label: "Kitchen", tap_action: { action: "navigate", navigation_path: "/lovelace/kitchen" } })).toBe(true);
    expect(frontend({ type: "shortcut", tap_action: { action: "perform-action", perform_action: "light.turn_on" } })).toBe(true);
    expect(frontend({ type: "shortcut", label: "Ask", icon: "mdi:microphone", tap_action: { action: "assist" } })).toBe(true);
    // Both given, or a default Vitro knows (an area, a settings page, a URL, none): native.
    expect(audit([{ type: "shortcut", label: "Kitchen", icon: "mdi:fridge", tap_action: { action: "navigate", navigation_path: "/lovelace/kitchen" } }]).totals.tier3).toBe(0);
    expect(audit([{ type: "shortcut", tap_action: { action: "navigate", navigation_path: "/config/areas/area/kitchen" } }]).totals.tier3).toBe(0);
    expect(audit([{ type: "shortcut", tap_action: { action: "navigate", navigation_path: "/config/automation" } }]).totals.tier3).toBe(0);
    expect(audit([{ type: "shortcut", tap_action: { action: "url", url_path: "https://example.com" } }]).totals.tier3).toBe(0);
    expect(audit([{ type: "shortcut" }]).totals.tier3).toBe(0);
    // WebFallback.sameOriginIframe: HA's own page refuses a frame on the island's origin.
    expect(frontend({ type: "iframe", url: "/history" })).toBe(true);
    expect(audit([{ type: "iframe", url: "https://example.com/" }]).triggers[0].reasons.join()).toMatch(/GeckoView island/);
  });

  it("routes the other fast paths", () => {
    expect(audit([{ type: "entity-button", entity: "light.a" }]).totals).toEqual({ tier1: 1, tier2: 0, tier3: 0 });
    expect(audit([{ type: "custom:mushroom-empty-card" }, { type: "custom:weather-card", entity: "weather.home" }]).totals.tier2).toBe(2);
    expect(audit([{ type: "custom:weather-card" }]).totals.tier3).toBe(1);
    const swipe = (parameters: unknown, cards: unknown[]) => audit([{ type: "custom:swipe-card", parameters, cards }]).totals;
    expect(swipe({ pagination: { type: "bullets" }, loop: true }, [{ type: "custom:button-card" }])).toEqual({ tier1: 0, tier2: 1, tier3: 0 });
    expect(swipe({ effect: "cube" }, [{ type: "custom:button-card" }]).tier3).toBe(1);
    expect(swipe({ pagination: { type: "progressbar" } }, [{ type: "custom:button-card" }]).tier3).toBe(1);
    expect(swipe({ slidesPerView: 0 }, [{ type: "custom:button-card" }]).tier3).toBe(1);
    // A built-in card anywhere inside keeps it native: the island can't draw built-in cards.
    expect(swipe({ effect: "cube" }, [{ type: "tile" }])).toEqual({ tier1: 1, tier2: 0, tier3: 0 });
    expect(swipe({ effect: "cube" }, [{ type: "custom:swipe-card", cards: [{ type: "tile" }] }]).tier3).toBe(0);
  });
});

describe("auditDashboard with JSON", () => {
  it("passes a dashboard of native cards", () => {
    const r = auditDashboard(
      JSON.stringify({
        views: [{ title: "Home", cards: [{ type: "tile", entity: "light.a" }, { type: "custom:mushroom-chips-card" }] }],
      }),
    );
    expect(r.ok).toBe(true);
    expect(r.isFullyNative).toBe(true);
    expect(r.totals).toEqual({ tier1: 1, tier2: 1, tier3: 0 });
    expect(r.views[0]).toMatchObject({ name: "Home", type: "masonry", native: true });
  });

  it("counts an iframe card as a GeckoView page: its view stays native, but the frame is a browser page", () => {
    const r = auditDashboard(JSON.stringify({ views: [{ path: "a", cards: [{ type: "iframe", url: "https://example.com/" }] }] }));
    expect(r.isFullyNative).toBe(false);
    expect(r.totals.tier3).toBe(1);
    expect(r.triggers[0].reasons).toEqual(["its page runs in a GeckoView island (the view stays native)"]);
  });

  it("flags unknown cards with their path and reason, and reads a card with no type as entities, as the engine does", () => {
    const r = auditDashboard(
      JSON.stringify({ views: [{ path: "a", sections: [{ cards: [{ type: "tile" }, { type: "custom:apexcharts-card" }, { type: "map" }, {}] }] }] }),
    );
    expect(r.isFullyNative).toBe(false);
    expect(r.totals).toEqual({ tier1: 2, tier2: 0, tier3: 2 });
    expect(r.triggers).toEqual([
      { view: "a", path: "a/section[0]/card[1]", type: "custom:apexcharts-card", reasons: ["`custom:apexcharts-card` is a custom card with no native renderer"] },
      { view: "a", path: "a/section[0]/card[2]", type: "map", reasons: ["Home Assistant's frontend draws this view: the built-in `map` card"] },
    ]);
    expect(r.warnings).toEqual(["a/section[0]/card[3]: no `type`, so Vitro draws it as an entities card"]);
  });

  it("reads the lovelace/config websocket response and storage files", () => {
    const views = [{ cards: [{ type: "gauge" }] }];
    expect(auditDashboard(JSON.stringify({ id: 3, type: "result", success: true, result: { views } })).totals.tier1).toBe(1);
    expect(auditDashboard(JSON.stringify({ data: { config: { views } } })).totals.tier1).toBe(1);
    expect(auditDashboard(JSON.stringify({ config: { views } })).totals.tier1).toBe(1);
  });

  it("recurses into containers and a conditional's single card", () => {
    const r = auditDashboard(
      JSON.stringify({
        views: [
          {
            cards: [
              {
                type: "vertical-stack",
                cards: [
                  { type: "horizontal-stack", cards: [{ type: "light" }, { type: "custom:x" }] },
                  { type: "conditional", card: { type: "custom:mushroom-entity-card", entity: "light.a" } },
                ],
              },
            ],
          },
        ],
      }),
    );
    expect(r.totals).toEqual({ tier1: 1, tier2: 1, tier3: 1 });
    expect(r.triggers[0].path).toBe("view_0/card[0].vertical-stack[0].horizontal-stack[1]");
  });

  it("rejects input that isn't a dashboard", () => {
    expect(auditDashboard("").ok).toBe(false);
    expect(auditDashboard("[1, 2]").error).toMatch(/views/);
    expect(auditDashboard('{"title": "x"}').error).toMatch(/views/);
    expect(auditDashboard("strategy:\n  type: original-states").error).toMatch(/auto-generated/);
  });
});

describe("auditDashboard with YAML", () => {
  const yaml = `
# a raw configuration editor paste
title: Home # trailing comment
views:
  - title: Living room
    path: living   # the tab's URL
    type: sections
    sections:
      - type: grid
        cards:
          - type: heading
            heading: "Lights: main"
          - type: grid
            columns: 2
            cards:
              - type: tile
                entity: light.lamp
                features:
                  - type: light-brightness
                  - type: light-effect-list
              - type: conditional
                conditions:
                  - condition: state
                    entity: input_boolean.guests
                    state: "on"
                card:
                  type: markdown
                  content: |
                    # Not a comment inside a block
                    Guests are here.
  - title: Wall
    path: wall
    panel: true
    cards:
      - type: custom:apexcharts-card
        entity: light.lamp
        card_mod:
          style: |
            ha-card::before { content: ""; }
      - {type: tile, entity: light.b}
      - type: 'custom:mushroom-light-card'
`;

  it("audits sections, nested grids, conditionals, and a panel view with a custom card", () => {
    const r = auditDashboard(yaml);
    expect(r.ok).toBe(true);
    // A panel view builds only its first card, as the engine does.
    expect(r.views.map((v) => [v.name, v.type, v.tier1, v.tier2, v.tier3])).toEqual([
      ["living", "sections", 2, 0, 1],
      ["wall", "panel", 0, 0, 1],
    ]);
    expect(r.totals).toEqual({ tier1: 2, tier2: 0, tier3: 2 });
    expect(r.isFullyNative).toBe(false);
    expect(r.triggers).toMatchObject([
      { view: "living", path: "living/section[0]/card[1].grid[0]", type: "tile" },
      { view: "wall", path: "wall/card[0]", type: "custom:apexcharts-card" },
    ]);
  });

  it("sends a built-in card with a feature Vitro doesn't draw to HA's frontend, and notes the cards a panel view skips", () => {
    const r = auditDashboard(yaml);
    expect(r.triggers[0].reasons).toEqual([
      "Home Assistant's frontend draws this view: `tile` `features[1].type: light-effect-list` isn't drawn natively: a tile feature Vitro doesn't draw as HA does",
    ]);
    expect(r.warnings).toEqual(["wall: a panel view shows only its first card, so the other 2 aren't checked"]);
  });

  it("doesn't warn about plain card_mod styles or supported features", () => {
    const r = auditDashboard(
      "views:\n  - cards:\n      - type: tile\n        features: [{type: light-brightness}]\n        card_mod:\n          style: |\n            ha-card { opacity: .5; }\n",
    );
    expect(r.warnings).toEqual([]);
    expect(r.isFullyNative).toBe(true);
  });

  it("reads a card_mod style map's own entry as native, and warns on the rest", () => {
    const own = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ha-card { opacity: .5; }\n");
    expect(own.warnings).toHaveLength(0);
    const deep = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            ha-tile-icon$: |\n              .icon { color: red; }\n");
    expect(deep.warnings).toHaveLength(1);
    const pseudo = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ha-card::before { content: 'x'; }\n");
    expect(pseudo.warnings).toHaveLength(1);
    // The single-colon form is a pseudo-element too.
    const legacy = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ha-card:before { content: 'x'; }\n");
    expect(legacy.warnings).toHaveLength(1);
    // A root key counts only on its own card type: ha-markdown$ on a tile isn't read.
    const wrongRoot = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            ha-markdown$: |\n              p { color: red; }\n");
    expect(wrongRoot.warnings).toHaveLength(1);
    const rightRoot = auditDashboard("views:\n  - cards:\n      - type: markdown\n        content: x\n        card_mod:\n          style:\n            ha-markdown$: |\n              p { color: red; }\n");
    expect(rightRoot.warnings).toHaveLength(0);
    // The card's own entry reaching inside the card (its header) isn't applied natively: still a warning.
    const inside = auditDashboard("views:\n  - cards:\n      - type: entities\n        entities: [light.a]\n        card_mod:\n          style:\n            .: |\n              ha-card .card-header { color: red; }\n");
    expect(inside.warnings).toHaveLength(1);
    const hover = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ha-card:hover { opacity: .5; }\n");
    expect(hover.warnings).toHaveLength(0);
    // A root compound is the card itself; a descendant of one isn't.
    const style = (css: string) => auditDashboard(`views:\n  - cards:\n      - type: tile\n        card_mod:\n          style: |\n            ${css}\n`);
    expect(style("ha-card.compact { opacity: .5; }").warnings).toHaveLength(0);
    expect(style("ha-card[data-x] { opacity: .5; }").warnings).toHaveLength(0);
    expect(style("ha-card.compact .card-header { color: red; }").warnings).toHaveLength(1);
    // Jinja inside a declaration isn't read as a selector.
    expect(style("ha-card { opacity: {{ 0.5 }}; }").warnings).toHaveLength(0);
    // A structural pseudo-class isn't applied natively; an interaction state is fine.
    const map = (css: string) => auditDashboard(`views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ${css}\n`);
    expect(map("ha-card:nth-child(1) { opacity: .5; }").warnings).toHaveLength(1);
    expect(map("ha-card.compact:focus-visible { opacity: .5; }").warnings).toHaveLength(0);
    // A conditional at-rule isn't evaluated natively, even around the card's own rule.
    expect(map("@media (min-width: 1px) { ha-card { opacity: .5; } }").warnings).toHaveLength(1);
    expect(style("@supports (display: grid) { ha-card { opacity: .5; } }").warnings).toHaveLength(1);
    // An @ inside a quoted attribute value isn't an at-rule, in the engine's scan (CardCompat.topLevelAtRules) either.
    expect(map('ha-card[data-label="@media"] { opacity: .5; }').warnings).toHaveLength(0);
  });

  it("refuses a key: value pair inside a flow list instead of reading it as text", () => {
    const r = auditDashboard("views:\n  - cards: [type: custom:apexcharts-card]\n");
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/key: value pair/);
    // A quoted string with a colon stays a string; a map in braces is read.
    expect(auditDashboard('views:\n  - cards: [{type: "custom:apexcharts-card"}]\n').triggers).toHaveLength(1);
  });

  it("reads the sample, which has one custom card on purpose", () => {
    const r = auditDashboard(SAMPLE_DASHBOARD);
    expect(r.ok).toBe(true);
    expect(r.totals).toEqual({ tier1: 3, tier2: 1, tier3: 1 });
    expect(r.triggers).toHaveLength(1);
    expect(r.warnings).toEqual([]);
  });

  it("reports a YAML mistake with its line", () => {
    const r = auditDashboard("views:\n  - cards:\n\t- type: tile\n");
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/line 3/);
  });
});

describe("parseYaml", () => {
  it("reads scalars", () => {
    expect(parseYaml("a: 1\nb: -2.5\nc: true\nd: ~\ne: null\nf: 'it''s'\ng: \"a\\nb\"\nh: 12:30\ni: light.kitchen\nj:\n")).toEqual({
      a: 1,
      b: -2.5,
      c: true,
      d: null,
      e: null,
      f: "it's",
      g: "a\nb",
      h: "12:30",
      i: "light.kitchen",
      j: null,
    });
  });

  it("reads lists at the key's own indent, nested lists, and dash-only items", () => {
    expect(parseYaml("a:\n- 1\n- 2\nb:\n  -\n    x: 1\n  - - 3\n    - 4\n")).toEqual({ a: [1, 2], b: [{ x: 1 }, [3, 4]] });
  });

  it("reads maps inside list items with sibling keys", () => {
    expect(parseYaml("- a: 1\n  b:\n    c: 2\n- a: 3\n")).toEqual([{ a: 1, b: { c: 2 } }, { a: 3 }]);
  });

  it("reads flow collections, also across lines", () => {
    expect(parseYaml("a: [1, 'two', {b: 3}]\nc: {d: [x, y], e: }\nf: [\n  1,\n  2\n]\n")).toEqual({
      a: [1, "two", { b: 3 }],
      c: { d: ["x", "y"], e: null },
      f: [1, 2],
    });
  });

  it("reads block scalars with chomping", () => {
    expect(parseYaml("a: |\n  one\n  two\n\nb: |-\n  x\nc: >\n  folded\n  text\n\n  para\nd: end\n")).toEqual({
      a: "one\ntwo\n",
      b: "x",
      c: "folded text\npara\n",
      d: "end",
    });
  });

  it("keeps # inside quotes and URLs, and strips real comments", () => {
    expect(parseYaml("a: 'x # y'\nb: http://h/#frag\nc: v # gone\nd: \"q\" # gone\n")).toEqual({
      a: "x # y",
      b: "http://h/#frag",
      c: "v",
      d: "q",
    });
  });

  it("folds a plain scalar that continues on the next line, and quoted keys", () => {
    expect(parseYaml('"my key": a\nb: one\n  two\n')).toEqual({ "my key": "a", b: "one two" });
  });

  it("supports anchors, aliases and merge keys", () => {
    expect(parseYaml("base: &b\n  x: 1\n  y: 2\nuse:\n  <<: *b\n  y: 3\n")).toEqual({ base: { x: 1, y: 2 }, use: { x: 1, y: 3 } });
  });

  it("refuses an alias inside a flow collection rather than reading it as text", () => {
    expect(() => parseYaml("base: &b\n  type: custom:button-card\nviews:\n  - cards: [*b]\n")).toThrow(/alias \*b/);
    expect(() => parseYaml("k: &k type\nviews:\n  - cards: [{*k : custom:button-card}]\n")).toThrow(/alias \*k/);
    expect(() => parseYaml("k: &k type\nviews:\n  - cards: [*k : custom:button-card]\n")).toThrow(/alias \*k/);
    expect(() => parseYaml("k: &k custom:button-card\nviews:\n  - cards: [type: *k]\n")).toThrow(/alias/);
  });

    it("reads only the first document and ignores a leading marker", () => {
    expect(parseYaml("---\na: 1\n---\nb: 2\n")).toEqual({ a: 1 });
  });

  it("handles Windows line endings", () => {
    expect(parseYaml("a:\r\n  - 1\r\n  - 2\r\n")).toEqual({ a: [1, 2] });
  });

  it("refuses tabs and stray indentation", () => {
    expect(() => parseYaml("a:\n\t- 1\n")).toThrow(/tabs/);
    expect(() => parseYaml("a: 1\n  b: 2\n")).toThrow(/YAML line 2/);
  });
});

describe("auditDashboard and the cards built from a configuration", () => {
  it("draws Bubble Card's separator and plain buttons, sends the rest to its island, and an unknown layout to the island or HA's frontend by what it holds", () => {
    const r = auditDashboard(
      JSON.stringify({
        views: [{ cards: [
          { type: "custom:bubble-card", card_type: "pop-up" },
          { type: "custom:bubble-card", card_type: "separator" },
          { type: "custom:bubble-card" },
          { type: "custom:layout-card", layout_type: "custom:grid-layout", cards: [{ type: "tile", entity: "light.a" }] },
        ] }],
      }),
    );
    // Vitro draws Bubble Card 3.4.3's separator and its switch, state and name buttons; a pop-up goes to the island.
    expect(r.triggers.map((t) => [t.type, t.reasons[0].split(": ")[0]])).toEqual([
      ["custom:bubble-card", "`custom:bubble-card` `card_type"],
    ]);
    // An unknown layout with a built-in card inside sends the view to HA's frontend: the island would draw the tile as
    // a summary.
    const builtIn = auditDashboard(
      JSON.stringify({ views: [{ cards: [{ type: "custom:layout-card", layout_type: "custom:my-layout", cards: [{ type: "tile", entity: "light.a" }] }] }] }),
    );
    expect(builtIn.triggers.map((t) => t.reasons)).toEqual([
      ["Home Assistant's frontend draws this view: `custom:layout-card` `layout_type: custom:my-layout` isn't drawn natively: a layout Vitro doesn't draw (without a layout_type, layout-card uses HA's own masonry view)"],
    ]);
    const custom = auditDashboard(
      JSON.stringify({ views: [{ cards: [{ type: "custom:layout-card", layout_type: "my-layout", cards: [{ type: "custom:mushroom-entity-card", entity: "light.a" }] }] }] }),
    );
    expect(custom.triggers.map((t) => t.type)).toEqual(["custom:layout-card"]);
    // The island draws the cards inside it, so they aren't listed on their own.
    expect(custom.totals).toEqual({ tier1: 0, tier2: 0, tier3: 1 });
  });

  it("treats a type named like an inherited property as an unknown card", () => {
    const r = auditDashboard(JSON.stringify({ views: [{ cards: [{ type: "constructor" }, { type: "__proto__" }] }] }));
    expect(r.ok).toBe(true);
    expect(r.totals.tier3).toBe(2);
  });

  it("inspects what auto-entities builds: its inner card and explicit card entries", () => {
    const island = auditDashboard(
      JSON.stringify({ views: [{ cards: [{ type: "custom:auto-entities", card: { type: "custom:apexcharts-card" } }] }] }),
    );
    expect(island.isFullyNative).toBe(false);
    const explicit = auditDashboard(
      JSON.stringify({ views: [{ cards: [{
        type: "custom:auto-entities", card_param: "cards", card: { type: "grid" },
        entities: ["light.kitchen", { entity: "light.lamp", type: "custom:apexcharts-card" }],
      }] }] }),
    );
    expect(explicit.triggers.map((t) => t.path)).toEqual(["view_0/card[0].card.entities[1]"]);
    // The default inner card (entities) and plain entries stay native.
    const plain = auditDashboard(
      JSON.stringify({ views: [{ cards: [{ type: "custom:auto-entities", filter: { include: [{ domain: "light" }] } }] }] }),
    );
    expect(plain.isFullyNative).toBe(true);
  });

  it("says an auto-entities view is native if the rows it matches at run time are, as the engine's report does", () => {
    const plain = auditDashboard(
      JSON.stringify({ views: [{ cards: [{ type: "custom:auto-entities", filter: { include: [{ domain: "light" }] } }] }] }),
    );
    expect(plain.warnings).toContain(`view_0/card[0]: ${MATCHED_ROWS_NOTE}`);
    // One wording in the three checkers: the engine's constant (its literals joined) and the audit script's.
    const literals = (src: string, name: string) => {
      const body = src.match(new RegExp(`${name}\\s*=\\s*\\(?([\\s\\S]*?)(?:\\)\\n|\\n\\n|\\n\\s*/\\*\\*)`))?.[1] ?? "";
      return [...body.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]).join("");
    };
    const engine = readFileSync(join(repo, "engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/compat/WebFallback.kt"), "utf8");
    expect(literals(engine, "const val MATCHED_ROWS_NOTE")).toBe(MATCHED_ROWS_NOTE);
    expect(literals(python, "MATCHED_ROWS_NOTE")).toBe(MATCHED_ROWS_NOTE);
  });
});

// The cases of the engine's CompatCheckerTest.kt, card by card: the site's checker must route each one as the engine
// does. A built-in card stays native (its reason becomes a note); a custom card goes to an island with the reason.
describe("auditDashboard routes cards as the engine's CompatCheckerTest does", () => {
  const one = (card: object) => auditDashboard(JSON.stringify({ views: [{ path: "v", cards: [card] }] }));
  const style = (css: string, type = "tile", extra: object = {}) => ({ type, entity: "light.a", ...extra, card_mod: { style: css } });
  const native = (card: object) => {
    const r = one(card);
    expect(r.ok, r.error).toBe(true);
    expect(r.triggers, JSON.stringify(r.triggers)).toEqual([]);
    return r.warnings;
  };
  const island = (card: object, part: string) => {
    const r = one(card);
    expect(r.triggers).toHaveLength(1);
    expect(r.triggers[0].reasons.join(" | ")).toContain(part);
  };
  /** The strict rule: a built-in card with something Vitro doesn't draw as HA does sends its view to HA's frontend. */
  const frontend = (card: object, part: string) => {
    const r = one(card);
    expect(r.triggers, JSON.stringify(r.triggers)).toHaveLength(1);
    expect(r.triggers[0].reasons[0].startsWith("Home Assistant's frontend draws this view: ")).toBe(true);
    expect(r.triggers[0].reasons.join(" | ")).toContain(part);
  };
  const noted = (card: object, part: string) => {
    const warnings = native(card);
    expect(warnings.join(" | ")).toContain(part);
    expect(warnings.join(" | ")).toContain("built-in");
  };

  it("keeps the Mirror, tablet and S1 idioms native", () => {
    expect(
      native({
        type: "markdown",
        text_only: true,
        content: "{{ 'Good morning' }}",
        card_mod: {
          style: {
            ".": "ha-card { background: none; border: none; box-shadow: none; transition: opacity 0.8s; }",
            "ha-markdown$": "ha-markdown-element { font-size: 16px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; opacity: 0.65; }",
          },
        },
      }),
    ).toEqual([]);
    native({
      type: "grid",
      cards: [{ type: "markdown", content: "x" }],
      card_mod: { style: ":host { margin: 3px 36px 0; --grid-card-gap: 11.6px; transition: opacity 0.8s; } ha-card { background: none; border: none; box-shadow: none; }" },
    });
    native({
      type: "markdown",
      content: "**21°**",
      card_mod: {
        style: {
          ".": "ha-card { position: fixed; top: 50%; right: 20px; transform: translateY(-50%); width: 108px; background: rgba(255,255,255,0.05); border-radius: 21.8px; --card-text-align: center; } ha-card ha-markdown { padding: 0 } :host ha-card { height: auto }",
          "ha-markdown$": "ha-markdown-element p { margin: 0; line-height: 1.2 }",
        },
      },
    });
    native(
      style(
        "{% if is_state(entity, 'on') %}ha-card { background: linear-gradient(150deg, rgba(251,191,36,0.22), rgba(251,191,36,0.05)); }{% else %}ha-card { background: rgba(255,255,255,0.045); }{% endif %} ha-card { border-radius: 24px; padding: 6px 4px; box-shadow: none; }",
        "custom:mushroom-template-card",
      ),
    );
    native({
      type: "custom:stack-in-card",
      cards: [{ type: "custom:mushroom-template-card", primary: "a" }],
      card_mod: { style: "ha-card { height: 100%; box-sizing: border-box; background: none; border: none; padding: 16px 20px; --vertical-stack-card-gap: 8px; }" },
    });
  });

  it("notes the known gaps of a native card without sending it to an island", () => {
    expect(
      native({
        type: "custom:mushroom-chips-card",
        chips: [{ type: "conditional", conditions: [], chip: { type: "template", content: "Door" } }],
        card_mod: { style: "ha-card { --chip-background: rgba(255,255,255,0.08); padding: 6px 0 0 8px; }" },
      }).join(" "),
    ).toContain("the chips card doesn't apply its card_mod yet");
    expect(native(style("ha-card { backdrop-filter: blur(12px); }")).join(" ")).toContain("backdrop-filter");
    // What HA drops, benign properties and interaction states aren't notes at all.
    expect(native(style("background: red; ha-card { background: none !important; border-radius: 12px !important }"))).toEqual([]);
    expect(
      native(
        style(
          "ha-card { cursor: pointer; transition: all 0.3s ease; user-select: none; will-change: opacity; outline: none; animation: none } ha-card:hover { background: rgba(255,255,255,0.1) } ha-card:active { opacity: 0.8 } ha-card::-webkit-scrollbar { display: none }",
        ),
      ),
    ).toEqual([]);
    expect(native(style("ha-card { font-family: Lobster, cursive }"))).toEqual([]);
  });

  // CompatCheckerTest.aQuotedCommentOpenerDoesNotHideTheRulesAfterIt and ThemeResolverTest.aQuotedCommentOpenerIsTextNotAComment.
  it("doesn't let a quoted comment opener hide the rules after it", () => {
    const mushroom = (css: string) => ({ type: "custom:mushroom-template-card", primary: "x", card_mod: { style: css } });
    island(mushroom('ha-card { --label: "/*"; } @media (max-width: 1px) { ha-card { padding: 0 } } /* note */'), "`@media`");
    island(mushroom("ha-card { --label: '/*'; } ha-card .primary { font-size: 20px } /* note */"), "reaches inside the card");
    expect(native(mushroom("/* @media (max-width: 1px) { } */ ha-card { opacity: 0.5 }"))).toEqual([]);
    // Native, and the rule is read (its custom property is noted): the quoted brace doesn't swallow it.
    expect(native(mushroom('ha-card { --label: "/* { */"; opacity: 0.5 }')).join(" | ")).toContain("`--label`");
    expect(native(mushroom('ha-card[data-label="a;b{"] { opacity: 0.5 }'))).toEqual([]);
    island(mushroom('ha-card { --label: "{"; } ha-card .primary { font-size: 20px }'), "reaches inside the card");
    expect(stripCssComments('a { content: "/*" } /* x */ b { c: d }')).toBe('a { content: "/*" }   b { c: d }');
    expect(stripCssComments("a { content: '*/ /*' } /* never closed")).toBe("a { content: '*/ /*' }  ");
    expect(stripCssComments('a { content: "\\" /*" }')).toBe('a { content: "\\" /*" }');
    expect(stripCssComments('a { content: "x\n /**/ b { c: d }')).toBe('a { content: "x\n   b { c: d }');
  });

  // CompatCheckerTest.anAtSignCountsOnlyWhereARuleCanStart.
  it("counts an @ as an at-rule only where a rule can start", () => {
    const mushroom = (css: string) => ({ type: "custom:mushroom-template-card", primary: "x", card_mod: { style: css } });
    expect(native(mushroom('ha-card[data-label="@media"] { opacity: 0.5 }'))).toEqual([]);
    expect(native(mushroom("ha-card[data-label='@supports'] { opacity: 0.5 }"))).toEqual([]);
    expect(native(mushroom("--art: url(/local/a@home.png); ha-card { opacity: 0.5 }"))).toEqual([]);
    island(mushroom("@media (max-width: 1px) { ha-card { padding: 0 } }"), "`@media`");
    island(mushroom("ha-card { opacity: 0.5 }\n  @supports (display: grid) { ha-card { padding: 0 } }"), "`@supports`");
    island(mushroom('@charset "utf-8"; @layer base { ha-card { padding: 0 } }'), "`@layer`");
    expect(native(mushroom('@charset "utf-8"; ha-card { opacity: 0.5 }'))).toEqual([]);
    expect(topLevelAtRules("a { b: url(x@y) } @media x { @supports y { } } c[d='@e'] { }")).toEqual(["media"]);
  });

  it("falls back for unknown cards and for card_mod Vitro can't draw on a custom card", () => {
    island({ type: "custom:acme-floorplan-card" }, "custom card with no native renderer");
    frontend({ type: "map" }, "the built-in `map` card");
    const mushroom = (css: string | object) => ({ type: "custom:mushroom-template-card", card_mod: { style: css } });
    island(mushroom({ "mushroom-shape-icon$": ".shape { --icon-size: 64px }" }), "card_mod path `mushroom-shape-icon$`");
    island(mushroom({ ".": { "ha-card$": "x" } }), "nests further");
    island(mushroom("ha-card .primary { font-size: 20px }"), "reaches inside the card");
    island(mushroom("ha-card { background: url('/local/wood.jpg') center / cover }"), "background images");
    island(mushroom("ha-card { background: radial-gradient(circle, #fff, #000) }"), "only linear gradients");
    island(mushroom("ha-card { transform: scale(0.9) }"), "transform");
    island(mushroom("ha-card { animation: pulse 2s infinite } @keyframes pulse { 50% { opacity: 0.5 } }"), "animation");
    island(mushroom("@media (max-width: 600px) { ha-card { padding: 0 } }"), "`@media`");
    island(mushroom("ha-card { filter: grayscale(1) }"), "filter");
    island(mushroom("ha-card { border: 2px dashed #fff }"), "only solid borders");
    island(mushroom("ha-card { height: 120px }"), "height");
    island(mushroom("ha-card { width: 50% }"), "only applies to pinned");
    island(mushroom("ha-card { box-shadow: inset 0 0 4px #000 }"), "inset");
    island(mushroom("ha-card { color: red }"), "`color: red` isn't supported here");
    island(mushroom("ha-card::before { content: '' }"), "pseudo-element");
    island(mushroom("ha-card { pointer-events: none }"), "pointer-events");
    island(mushroom("ha-card { contain: size }"), "contain");
    island(mushroom("ha-card { background: linear-gradient(red, blue), linear-gradient(blue, red) }"), "layered backgrounds");
    island(mushroom("ha-card { position: fixed; left: 10px; right: 10px }"), "stretch it between them");
    // font-size is read on tile and Mushroom cards.
    native(mushroom("ha-card { font-size: 18px }"));
    // A pinned card needs a width above zero, as the engine reads it.
    island(mushroom("ha-card { position: fixed; top: 0; left: 0; width: 0px }"), "a pinned width of zero");
    native(mushroom("ha-card { position: fixed; top: 0; left: 0; width: 120px }"));
  });

  it("keeps a built-in card native for its card_mod and notes what it would have fallen back for", () => {
    noted(style("ha-card .primary { font-size: 20px }"), "reaches inside the card");
    noted({ type: "tile", entity: "light.a", card_mod: { style: { "ha-tile-icon$": ".container { border-radius: 4px }" } } }, "card_mod path `ha-tile-icon$`");
    noted({ type: "vertical-stack", cards: [], card_mod: { style: "hui-tile-card { margin: 4px }" } }, "reaches inside the stack");
    // Tags HA's sanitizer escapes show as text there too, so they don't count.
    expect(native({ type: "markdown", content: '<script>x</script> <iframe src="x"></iframe>' })).toEqual([]);
    // The same `color` that sends a custom card to an island flows into a Markdown card's text.
    expect(native({ type: "markdown", content: "x", card_mod: { style: "ha-card { color: red; font-size: 18px }" } })).toEqual([]);
  });

  it("sends the view of a built-in card with an option or content Vitro doesn't draw to HA's frontend", () => {
    frontend({ type: "tile", entity: "light.a", features: [{ type: "climate-preset-modes" }] }, "`features[0].type: climate-preset-modes`");
    frontend({ type: "entities", entities: [{ type: "custom:slider-entity-row", entity: "light.a" }] }, "`entities[0].type: custom:slider-entity-row`");
    frontend({ type: "entities", entities: [{ type: "cast", dashboard: "lovelace" }] }, "`entities[0].type: cast`");
    frontend({ type: "entities", entities: ["light.a"], footer: { type: "graph", entity: "sensor.a" } }, "`footer`");
    frontend({ type: "picture-elements", image: "/local/p.png", elements: [{ type: "conditional", conditions: [], elements: [] }] }, "conditional elements aren't drawn");
    frontend({ type: "markdown", content: '<ha-alert alert-type="warning">Door open</ha-alert>' }, "`<ha-alert>`");
    frontend({ type: "markdown", content: "<details><summary>More</summary>x</details>" }, "`<details>`");
    // auto-entities builds an entities card: a filter it doesn't evaluate sends the view to HA's frontend.
    frontend({ type: "custom:auto-entities", filter: { include: [{ domain: "light", area: "kitchen" }] } }, "`filter.include[0].area`");
  });

  it("routes the options of the custom cards Vitro draws", () => {
    island({ type: "custom:mushroom-chips-card", chips: [{ type: "alarm-control-panel", entity: "alarm_control_panel.a" }] }, "`chips[0].type: alarm-control-panel`");
    island({ type: "custom:mushroom-chips-card", chips: [{ type: "conditional", chip: { type: "conditional" } }] }, "`chips[0]");
    island({ type: "custom:bubble-card", card_type: "pop-up", hash: "#k" }, "`card_type: pop-up`");
    island({ type: "custom:mushroom-entity-card", entity: "light.a", icon_color: "amber" }, "`icon_color`");
    island({ type: "custom:layout-card", layout_type: "custom:foo-layout", cards: [] }, "`layout_type: custom:foo-layout`");
    native({ type: "custom:layout-card", layout_type: "custom:vertical-layout", cards: [{ type: "tile" }, { type: "custom:layout-break" }, { type: "tile" }] });
  });

  it("counts a vertical layout's breaks as no card", () => {
    const r = one({ type: "custom:layout-card", layout_type: "vertical-layout", cards: [{ type: "tile" }, { type: "custom:layout-break" }, { type: "tile" }] });
    expect(r.totals).toEqual({ tier1: 2, tier2: 0, tier3: 0 });
  });

  it("takes a panel view's first card among the objects in its cards, as the engine parses them", () => {
    const r = auditDashboard(JSON.stringify({ views: [{ path: "p", panel: true, cards: ["x", { type: "custom:apexcharts-card" }, { type: "tile" }] }] }));
    expect(r.triggers.map((t) => t.path)).toEqual(["p/card[1]"]);
    expect(r.warnings).toEqual(["p: a panel view shows only its first card, so the other 1 aren't checked"]);
  });

  it("reads `panel` as the engine's optBoolean does, the text \"true\" included", () => {
    const view = (panel: unknown) => auditDashboard(JSON.stringify({ views: [{ panel, cards: [{ type: "tile" }, { type: "tile" }] }] })).views[0].type;
    expect(view(true)).toBe("panel");
    expect(view("True")).toBe("panel");
    expect(view("false")).toBe("masonry");
  });

  it("leaves addresses out of the values it shows, as the engine's report does", () => {
    const r = one({ type: "custom:mushroom-entity-card", card_mod: { style: "ha-card { background: image-set('/api/image/a?token=abc' 1x) }" } });
    expect(r.triggers[0].reasons.join(" ")).toContain('image-set("…" 1x)');
    expect(r.triggers[0].reasons.join(" ")).not.toContain("token");
    const u = one({ type: "custom:mushroom-entity-card", card_mod: { style: "ha-card { background: url('https://example.invalid/a.png?sig=x') }" } });
    expect(u.triggers[0].reasons.join(" ")).toContain("url(…)");
  });

  it("walks only the cards a view builds: a sections view's sections, else its cards", () => {
    const r = auditDashboard(
      JSON.stringify({ views: [{ path: "s", type: "sections", sections: [{ cards: [{ type: "tile" }] }], cards: [{ type: "custom:apexcharts-card" }] }] }),
    );
    expect(r.isFullyNative).toBe(true);
    expect(r.totals.tier1).toBe(1);
  });
});
