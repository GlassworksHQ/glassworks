import { describe, expect, it } from "vitest";
import { frames } from "./frames";
import { RECIPES, recipesForFrame } from "./recipes";

const ids = (id: string) => {
  const f = frames.find((x) => x.id === id);
  if (!f) throw new Error(`frames/${id} is missing`);
  return recipesForFrame(f).map((r) => r.id);
};

describe("recipesForFrame", () => {
  it("matches a frame by its screen", () => {
    expect(ids("aqara-s1-plus")).toEqual(["wide-wall-switch"]);
    expect(ids("google-pixel-tablet")).toEqual(["docked-tablet-hub"]);
    expect(ids("lululemon-mirror")).toEqual(["two-way-mirror-presence"]);
  });

  it("offers every prompt to a frame whose screen isn't known, never one its illustration suggests", () => {
    expect(ids("generic-android")).toEqual(RECIPES.map((r) => r.id));
  });
});
