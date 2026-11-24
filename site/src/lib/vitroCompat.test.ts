import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { SAMPLE_DASHBOARD, VITRO_COMPAT, auditDashboard, parseYaml } from "./vitroCompat";

const repo = join(import.meta.dirname, "../../..");
const kotlin = readFileSync(join(repo, "engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/model/LovelaceModel.kt"), "utf8");
const python = readFileSync(join(repo, ".agents/skills/vitro-native-dashboard/scripts/audit_vitro_dashboard.py"), "utf8");
const published = JSON.parse(readFileSync(join(import.meta.dirname, "../../public/vitro-compat.json"), "utf8"));

function kotlinSet(name: string): string[] {
  const body = kotlin.match(new RegExp(`val ${name} = setOf\\(([\\s\\S]*?)\\n\\s*\\)`))?.[1];
  if (!body) throw new Error(`${name} not found in LovelaceModel.kt`);
  return [...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

function pythonSet(name: string): string[] {
  const body = python.match(new RegExp(`^${name} = \\{([\\s\\S]*?)\\}`, "m"))?.[1];
  if (!body) throw new Error(`${name} not found in audit_vitro_dashboard.py`);
  return [...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

describe("the published compatibility manifest", () => {
  it("is what the checker uses", () => {
    expect(VITRO_COMPAT).toEqual(published);
    expect(published.version).toBe("0.0.1");
  });

  it("lists the core types the Vitro engine draws natively, verbatim", () => {
    expect(published.nativeCoreTypes).toEqual(kotlinSet("NATIVE_CORE_TYPES"));
    expect(published.nativeCoreTypes).toHaveLength(31);
  });

  it("lists the HACS cards the Vitro engine draws natively, verbatim", () => {
    expect(published.nativeHacsTypes).toEqual(kotlinSet("NATIVE_HACS_TYPES"));
    expect(published.nativeHacsTypes).toHaveLength(12);
  });

  it("matches the audit script's lists", () => {
    expect(new Set(published.nativeCoreTypes)).toEqual(new Set(pythonSet("NATIVE_CORE_TYPES")));
    expect(new Set(published.nativeHacsTypes)).toEqual(new Set(pythonSet("NATIVE_HACS_TYPES")));
    expect(new Set(published.supportedTileFeatures)).toEqual(new Set(pythonSet("SUPPORTED_TILE_FEATURES")));
    expect(new Set(published.containerTypes)).toEqual(new Set(pythonSet("CONTAINER_TYPES")));
    expect(published.supportedTileFeatures).toHaveLength(9);
    expect(published.containerTypes).toHaveLength(4);
  });

  it("walks the same containers the engine flattens", () => {
    expect(kotlin).toContain('setOf("grid", "horizontal-stack", "vertical-stack", "conditional")');
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

  it("flags unknown custom cards with their path", () => {
    const r = auditDashboard(
      JSON.stringify({ views: [{ path: "a", sections: [{ cards: [{ type: "tile" }, { type: "custom:button-card" }, {}] }] }] }),
    );
    expect(r.isFullyNative).toBe(false);
    expect(r.totals.tier3).toBe(2);
    expect(r.triggers).toEqual([
      { view: "a", path: "a/section[0]/card[1]", type: "custom:button-card" },
      { view: "a", path: "a/section[0]/card[2]", type: "<missing>" },
    ]);
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
                  { type: "conditional", card: { type: "custom:mini-graph-card" } },
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
      - type: custom:button-card
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
    expect(r.views.map((v) => [v.name, v.type, v.tier1, v.tier2, v.tier3])).toEqual([
      ["living", "sections", 3, 0, 0],
      ["wall", "masonry", 1, 1, 1],
    ]);
    expect(r.totals).toEqual({ tier1: 4, tier2: 1, tier3: 1 });
    expect(r.isFullyNative).toBe(false);
    expect(r.triggers).toEqual([{ view: "wall", path: "wall/card[0]", type: "custom:button-card" }]);
  });

  it("warns about unsupported tile features and complex card_mod", () => {
    const r = auditDashboard(yaml);
    expect(r.warnings).toHaveLength(2);
    expect(r.warnings[0]).toContain("living/section[0]/card[1].grid[0]");
    expect(r.warnings[0]).toContain("light-effect-list");
    expect(r.warnings[1]).toContain("wall/card[0]");
    expect(r.warnings[1]).toMatch(/card_mod/);
  });

  it("doesn't warn about plain card_mod styles or supported features", () => {
    const r = auditDashboard(
      "views:\n  - cards:\n      - type: tile\n        features: [{type: fan-speed}]\n        card_mod:\n          style: |\n            ha-card { opacity: .5; }\n",
    );
    expect(r.warnings).toEqual([]);
    expect(r.isFullyNative).toBe(true);
  });

  it("treats a card_mod style map (Shadow-DOM selectors) as complex", () => {
    const r = auditDashboard("views:\n  - cards:\n      - type: tile\n        card_mod:\n          style:\n            .: |\n              ha-card { color: red; }\n");
    expect(r.warnings).toHaveLength(1);
  });

  it("reads the sample, which has one custom card on purpose", () => {
    const r = auditDashboard(SAMPLE_DASHBOARD);
    expect(r.ok).toBe(true);
    expect(r.totals).toEqual({ tier1: 3, tier2: 1, tier3: 1 });
    expect(r.triggers).toHaveLength(1);
    expect(r.warnings.length).toBeGreaterThan(0);
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
