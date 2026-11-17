import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ASSET, fromAsset, isCore } from "./blueprints";

// The app's own list, as this commit has it: what the site reads from GitHub at a release.
const asset = readFileSync(join(import.meta.dirname, "../../..", ASSET), "utf8");

describe("blueprints", () => {
  it("lists the core's own blueprints, with links at the ref", () => {
    const core = fromAsset(asset, "v9.9.9").filter(isCore);
    const night = core.find((b) => b.name === "Panel night mode");
    expect(night).toEqual({
      pane: "screen",
      name: "Panel night mode",
      summary: expect.stringContaining("At night"),
      path: "core/blueprints/panel-night-mode.yaml",
      add: `https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=${encodeURIComponent(
        "https://raw.githubusercontent.com/GlassworksHQ/glassworks/v9.9.9/core/blueprints/panel-night-mode.yaml",
      )}`,
      source: "https://github.com/GlassworksHQ/glassworks/blob/v9.9.9/core/blueprints/panel-night-mode.yaml",
    });
  });

  it("keeps the panes' blueprints out of the core's", () => {
    const list = fromAsset(asset, "main");
    const panes = list.filter((b) => !isCore(b));
    expect(panes.map((b) => b.name)).toContain("Doorbell on Glassworks panels");
    expect(panes.every((b) => b.path.startsWith("panes/") || b.path.startsWith("frames/"))).toBe(true);
    expect(list.length).toBe(JSON.parse(asset).length);
  });
});
