import { expect, it } from "vitest";
import { buildLlmsTxt } from "./llms";

it("includes live frames, panes, libraries, and technical overview links", () => {
  const txt = buildLlmsTxt();
  expect(txt).toContain("# Glassworks");
  expect(txt).toContain("https://www.glassworks.dev/docs/why-glassworks");
  expect(txt).toContain("https://www.glassworks.dev/frames/aqara-s1-plus");
  expect(txt).toContain("https://www.glassworks.dev/frames/lululemon-mirror");
  expect(txt).toContain("https://www.glassworks.dev/panes/assist");
  expect(txt).toContain("https://www.glassworks.dev/docs/libraries/esphome-api");
  expect(txt).toContain("Clarity D");
  expect(txt).toContain("https://github.com/GlassworksHQ/glassworks/issues/790");
});
