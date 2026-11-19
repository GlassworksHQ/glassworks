import type { FrameDesign } from "@glassworks/frame-card";
import { describe, expect, it } from "vitest";
import { frames, heroPanel } from "./frames";

const fake = (id: string, extra: Partial<FrameDesign>) => ({ id, name: id, install: {}, ...extra }) as FrameDesign;

describe("heroPanel", () => {
  it("opens on the S1, with Kiln's page for it", () => {
    const hero = heroPanel(frames);
    expect(hero?.frame.id).toBe("aqara-s1-plus");
    expect(hero?.frame.name).toBe("Aqara S1 Plus");
    expect(hero?.href).toBe("https://kiln.glassworks.dev/aqara-s1-plus");
    expect(hero?.page).toBe("/frames/aqara-s1-plus");
  });

  it("needs both a render and a Kiln install", () => {
    const noRender = fake("no-render", { kiln: "/no-render" });
    const ownInstaller = fake("own-installer", { render: "render.webp", install: { href: "https://example.com/" } as FrameDesign["install"] });
    const both = fake("both", { render: "render.webp", kiln: "/both" });
    expect(heroPanel([noRender, ownInstaller, both])?.frame.id).toBe("both");
    expect(heroPanel([noRender, ownInstaller])).toBeUndefined();
  });
});
