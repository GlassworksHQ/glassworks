import { describe, expect, it } from "vitest";
import type { GithubRelease } from "./blueprints";
import { latestRelease } from "./release";

const rel = (tag_name: string, published_at: string, more: Partial<GithubRelease> = {}): GithubRelease => ({
  tag_name,
  draft: false,
  prerelease: false,
  published_at,
  ...more,
});

const changelog = (...versions: string[]) =>
  ["# Changelog", "", "## [Unreleased]", "", ...versions.flatMap((v, i) => [`## [${v}] - 2026-11-${20 - i}`, "- a change", ""])].join("\n");

const REPO = "https://github.com/GlassworksHQ/glassworks";

// GitHub's list as it will look after the first release on the S1's line: other lines and tags around it.
const list = [
  rel("libs/improv/v0.2.0", "2026-12-09T00:00:00Z"),
  rel("frames/aqara-s1-plus/v1.0.0", "2026-12-08T00:00:00Z"),
  rel("generic-android-v0.6.0", "2026-12-07T00:00:00Z"),
  rel("aqara-s1-plus-legacy-v0.6.0", "2026-12-06T00:00:00Z"),
  rel("aqara-s1-plus-v0.6.0-rc.1", "2026-12-05T00:00:00Z", { prerelease: true }),
  rel("aqara-s1-plus-v0.5.0", "2026-12-01T00:00:00Z"),
  rel("v0.4.4", "2026-11-19T00:00:00Z"),
  rel("v0.4.3", "2026-11-11T00:00:00Z"),
];

describe("the latest release", () => {
  it("links a version after 0.4.4 at the S1 line's tag", () => {
    expect(latestRelease(changelog("0.5.0", "0.4.4", "0.4.3"), list)).toEqual({
      version: "0.5.0",
      date: "2026-11-20",
      tag: "aqara-s1-plus-v0.5.0",
      href: `${REPO}/releases/tag/aqara-s1-plus-v0.5.0`,
    });
  });

  it("links 0.4.4 and before at the plain v tag", () => {
    const r = latestRelease(changelog("0.4.4", "0.4.3"), list);
    expect(r.tag).toBe("v0.4.4");
    expect(r.href).toBe(`${REPO}/releases/tag/v0.4.4`);
  });

  it("skips a version that isn't released yet, and never takes another line's release of it", () => {
    // 0.6.0's section is in, but only the generic app and a pre-release have that version
    const r = latestRelease(changelog("0.6.0", "0.5.0", "0.4.4"), list);
    expect(r).toMatchObject({ version: "0.5.0", date: "2026-11-19", tag: "aqara-s1-plus-v0.5.0" });
    // a draft is not released either
    const drafted = [rel("aqara-s1-plus-v0.6.0", "2026-12-10T00:00:00Z", { draft: true }), ...list];
    expect(latestRelease(changelog("0.6.0", "0.5.0"), drafted).tag).toBe("aqara-s1-plus-v0.5.0");
  });

  it("takes the newest S1 release when no changelog version has one", () => {
    // 0.6.0 is out, but this changelog has an unreleased 0.7.0 and an older 0.5.0 the list no longer reaches
    const out = [rel("aqara-s1-plus-v0.6.0", "2026-12-10T08:00:00Z"), ...list.filter((r) => !r.tag_name.startsWith("aqara-s1-plus-v0.5"))];
    expect(latestRelease(changelog("0.7.0", "0.5.0"), out)).toEqual({
      version: "0.6.0",
      date: "2026-12-10",
      tag: "aqara-s1-plus-v0.6.0",
      href: `${REPO}/releases/tag/aqara-s1-plus-v0.6.0`,
    });
  });

  it("links the releases page when the list doesn't reach any S1 release", () => {
    // newer releases of other lines filled GitHub's first page
    const others = list.filter((r) => !r.tag_name.startsWith("aqara-s1-plus-v") && !/^v/.test(r.tag_name));
    expect(latestRelease(changelog("0.5.0", "0.4.4"), others)).toEqual({
      version: "0.5.0",
      date: "2026-11-20",
      tag: null,
      href: `${REPO}/releases`,
    });
  });

  it("stops the build when the changelog has no version", () => {
    expect(() => latestRelease(changelog(), list)).toThrow("no \"## [x.y.z] - YYYY-MM-DD\" release heading");
  });
});
