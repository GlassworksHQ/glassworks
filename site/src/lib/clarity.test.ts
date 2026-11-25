import { describe, expect, it } from "vitest";
import { clarityFor, frameGradeSummary, type ClaritySummary } from "./clarity";

describe("frameGradeSummary", () => {
  const web = { schema: "glassworks.perf-summary/3", frame: { id: "x", name: "X" } } as unknown as ClaritySummary;

  it("keeps the web engine's summary, with or without an engine field", () => {
    expect(frameGradeSummary(web)).toBe(web);
    const said = { ...web, engine: "web" };
    expect(frameGradeSummary(said)).toBe(said);
  });

  it("never shows another engine's summary as the frame's grade (Vitro is the maintainer's call)", () => {
    expect(frameGradeSummary({ ...web, engine: "vitro" })).toBeNull();
    expect(frameGradeSummary(null)).toBeNull();
  });

  it("leaves the published frames' grades as they are", () => {
    // The S1's clarity.json is the web engine's: still graded.
    expect(clarityFor("aqara-s1-plus").state).not.toBe("none");
  });
});
