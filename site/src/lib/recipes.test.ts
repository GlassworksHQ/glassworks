import { describe, expect, it } from "vitest";
import { frameById } from "./frames";
import { RECIPES, recipesForFrame } from "./recipes";

const s1 = frameById("aqara-s1-plus")!;
const ids = (frame: Parameters<typeof recipesForFrame>[0]) => recipesForFrame(frame).map((recipe) => recipe.id);

describe("dashboard starter prompts", () => {
  it.each([
    ["aqara-s1-plus", "wide-wall-switch"],
    ["google-pixel-tablet", "docked-tablet-hub"],
    ["lululemon-mirror", "two-way-mirror-presence"],
  ])("offers the matching recipe on the %s device page", (id, recipe) => {
    expect(ids(frameById(id)!)).toEqual([recipe]);
  });

  it.each([
    [119, ["compact-square-dial"]],
    [121, []],
    [124, []],
    [125, ["docked-tablet-hub"]],
    [169, ["docked-tablet-hub"]],
    [170, ["wide-wall-switch"]],
    [80, ["compact-square-dial"]],
    [79, []],
  ])("matches a %s:100 screen at the form factor boundaries", (width, expected) => {
    expect(ids({ ...s1, display: { width, height: 100 } })).toEqual(expected);
  });

  it("goes by the screen, not the body's outline", () => {
    // the S1's 2:1 screen, in a square body
    expect(ids({ ...s1, model: { ...s1.model, body: { ...s1.model.body, width: 100, height: 100 } } })).toEqual(["wide-wall-switch"]);
  });

  it("excludes a mirror even if its proportions qualify for the tablet recipe", () => {
    const mirror = frameById("lululemon-mirror")!;
    expect(ids({ ...mirror, display: { width: 160, height: 100 } })).toEqual(["two-way-mirror-presence"]);
  });

  it("offers every prompt to a frame whose screen isn't known, never one its illustration suggests", () => {
    expect(ids(frameById("generic-android")!)).toEqual(RECIPES.map((r) => r.id));
  });

  it("provides the same serializable recipe on the device page and in the global list", () => {
    const recipes = recipesForFrame(s1);
    expect(recipes).toHaveLength(1);
    expect(recipes[0]).toStrictEqual(RECIPES.find((recipe) => recipe.id === recipes[0].id));
    expect(JSON.parse(JSON.stringify(RECIPES))).toStrictEqual(RECIPES);
    expect(new Set(RECIPES.map((recipe) => recipe.id)).size).toBe(RECIPES.length);
    for (const { prompt } of RECIPES) expect(prompt.trim()).not.toBe("");
  });
});
