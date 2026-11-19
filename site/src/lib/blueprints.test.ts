import { readFileSync } from "node:fs";
import { join } from "node:path";
import { PANES } from "@glassworks/frame-card";
import { describe, expect, it } from "vitest";
import { ASSET, appReleases, fetchReleases, findBlueprints, fromAsset, isCore, releaseName, type GithubRelease } from "./blueprints";

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
    // every pane blueprint belongs to a pane the site knows, or the page would have nowhere to show it
    const known = new Set(PANES.map((p) => p.id));
    expect(panes.filter((b) => !known.has(b.pane)).map((b) => b.path)).toEqual([]);
  });
});

const rel = (tag_name: string, published_at: string, more: Partial<GithubRelease> = {}): GithubRelease => ({
  tag_name,
  draft: false,
  prerelease: false,
  published_at,
  ...more,
});

// GitHub as the build sees it: the releases list, and the asset at the tags in [has] (404 elsewhere).
function github(list: GithubRelease[], has: string[]) {
  const asked: string[] = [];
  const get = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    asked.push(url);
    if (url.startsWith("https://api.github.com/")) {
      expect(url).toBe("https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=100");
      return new Response(JSON.stringify(list), { headers: init?.headers as HeadersInit });
    }
    const ref = has.find((t) => url === `https://raw.githubusercontent.com/GlassworksHQ/glassworks/${t}/${ASSET}`);
    return ref ? new Response(asset) : new Response("404: Not Found", { status: 404 });
  }) as typeof fetch;
  return { get, asked };
}

describe("the release the blueprints come from", () => {
  const list = [
    rel("libs/improv/v0.2.0", "2026-12-09T00:00:00Z"),
    rel("frames/lululemon-mirror/v1.1.0", "2026-12-08T00:00:00Z"),
    rel("generic-android-legacy-v0.3.0", "2026-12-07T00:00:00Z", { draft: true }),
    rel("aqara-s1-plus-v0.6.0-rc.1", "2026-12-06T00:00:00Z", { prerelease: true }),
    rel("other-thing-v9.0.0", "2026-12-05T00:00:00Z"),
    rel("aqara-s1-plus-v0.5.0", "2026-12-01T00:00:00Z"),
    rel("generic-android-v0.2.0", "2026-12-03T00:00:00Z"),
    rel("generic-android-legacy-v0.2.0", "2026-12-02T00:00:00Z"),
    rel("v0.4.4", "2026-11-19T00:00:00Z"),
    rel("v0.4.3", "2026-11-01T00:00:00Z"),
    rel("installer-files-3", "2026-10-01T00:00:00Z"),
  ];

  it("takes every frame's app line and the old plain tags, newest published first", () => {
    expect(appReleases(list)).toEqual([
      "generic-android-v0.2.0",
      "generic-android-legacy-v0.2.0",
      "aqara-s1-plus-v0.5.0",
      "v0.4.4",
      "v0.4.3",
    ]);
  });

  it("lists them from the newest release whose tree has the app's list", async () => {
    const { get } = github(list, ["aqara-s1-plus-v0.5.0", "v0.4.4"]);
    const found = await findBlueprints(get);
    expect(found.ref).toBe("aqara-s1-plus-v0.5.0");
    expect(found.list.length).toBeGreaterThan(0);
    expect(found.list[0]!.source).toContain("/blob/aqara-s1-plus-v0.5.0/");
    expect(releaseName(found.ref!)).toBe("Aqara S1 Plus release 0.5.0");
  });

  it("takes a newer release of another frame's app when it has them", async () => {
    const { get } = github(list, ["generic-android-v0.2.0", "aqara-s1-plus-v0.5.0"]);
    expect((await findBlueprints(get)).ref).toBe("generic-android-v0.2.0");
  });

  it("still reads an old plain v tag", async () => {
    const { get } = github(list, ["v0.4.4"]);
    const found = await findBlueprints(get);
    expect(found.ref).toBe("v0.4.4");
    expect(found.list[0]!.add).toContain(encodeURIComponent("/glassworks/v0.4.4/"));
  });

  it("has none until a release carries them", async () => {
    const { get, asked } = github(list, []);
    expect(await findBlueprints(get)).toEqual({ ref: null, list: [] });
    // every app release was tried, nothing else
    expect(asked.filter((u) => u.startsWith("https://raw."))).toHaveLength(5);
  });

  it("reads BLUEPRINTS_REF without asking for the releases", async () => {
    const { get, asked } = github(list, ["97f81f5"]);
    expect((await findBlueprints(get, "97f81f5")).ref).toBe("97f81f5");
    expect(asked.some((u) => u.startsWith("https://api.github.com/"))).toBe(false);
  });

  it("sends GITHUB_TOKEN when it is set, and stops the build when GitHub refuses", async () => {
    let auth: string | null = null;
    const get = (async (_: unknown, init?: RequestInit) => {
      auth = new Headers(init?.headers).get("authorization");
      return new Response("rate limited", { status: 403 });
    }) as typeof fetch;
    await expect(fetchReleases(get, "t0ken")).rejects.toThrow("HTTP 403");
    expect(auth).toBe("Bearer t0ken");
    await expect(findBlueprints(get)).rejects.toThrow("set GITHUB_TOKEN");
  });

  it("names the release by its frame", () => {
    expect(releaseName("aqara-s1-plus-v0.5.0")).toBe("Aqara S1 Plus release 0.5.0");
    expect(releaseName("v0.4.4")).toBe("Aqara S1 Plus release 0.4.4");
    expect(releaseName("generic-android-v0.2.0")).toBe("Generic Android release 0.2.0");
    expect(releaseName("generic-android-legacy-v0.2.0")).toBe("Generic Android legacy release 0.2.0");
    expect(releaseName("97f81f5c0ffee")).toBe("commit 97f81f5");
    expect(releaseName("main")).toBe("main");
  });
});
