import { describe, expect, it } from "vitest";
import { geometry, type FrameModel, type TubePart } from "@glassworks/frame-card";
import { blendModels, blendViews } from "./storyBlend";
import { BEDSIDE_DISPLAY, ROUND_DIAL, SQUARE_SWITCH, WIDE_DISPLAY } from "./storyModels";

describe("the home story's device transitions", () => {
  it("keeps the original models at the holds, including their dock and theme", () => {
    for (const t of [-0.1, 0]) expect(blendModels(WIDE_DISPLAY, ROUND_DIAL, t)).toBe(WIDE_DISPLAY);
    for (const t of [1, 1.1]) expect(blendModels(WIDE_DISPLAY, ROUND_DIAL, t)).toBe(ROUND_DIAL);
  });

  it("blends the screen's proportion of the front, including an asymmetric inset", () => {
    const wall: FrameModel = {
      ...SQUARE_SWITCH,
      body: { shape: "rect", width: 100, height: 100, depth: 8, radius: 5 },
      bezel: 10,
    };
    const desk: FrameModel = {
      ...BEDSIDE_DISPLAY,
      body: { shape: "rect", width: 200, height: 100, depth: 12, radius: 15 },
      screen: { width: 180, height: 60, top: 30, radius: 4 },
    };
    const mid = blendModels(wall, desk, 0.5);
    expect(mid.body).toEqual({ shape: "rect", width: 150, height: 100, depth: 10, radius: 10 });
    expect(mid.screen.width).toBeCloseTo(127.5);
    expect(mid.screen).toMatchObject({ height: 70, top: 20, radius: 3 });
  });

  it("delays lean until the panel leaves the wall and settles before the next hold", () => {
    const wall = { ...SQUARE_SWITCH, lean: 30 }; // wall mounts ignore a lean angle
    expect(blendModels(wall, BEDSIDE_DISPLAY, 0.1)).toMatchObject({ mount: "wall", lean: 0 });
    expect(blendModels(wall, BEDSIDE_DISPLAY, 0.5)).toMatchObject({ mount: "desk", lean: 8 });
    expect(blendModels(BEDSIDE_DISPLAY, wall, 0.9)).toMatchObject({ mount: "wall", lean: 0 });
  });

  it("uses a device's light sides as its dark palette when no dark palette is supplied", () => {
    const a = { ...SQUARE_SWITCH, face: undefined, dark: undefined, sides: { dark: "#000000", light: "#ffffff" } };
    const b = {
      ...SQUARE_SWITCH, face: "#ffffff", sides: { dark: "#000000", light: "#ffffff" },
      dark: { face: "#000000", sides: { dark: "#ffffff", light: "#000000" } },
    };
    const mid = blendModels(a, b, 0.5);
    expect(mid.face).toBe("#838485");
    expect(mid.dark).toEqual({ face: "#040406", sides: { dark: "#808080", light: "#808080" } });
    expect(blendModels(a, a, 0.5)).not.toHaveProperty("dark");
  });

  it("keeps a fading stand attached as the body scales, in either theme", () => {
    const part: TubePart = {
      kind: "tube", from: [10, 20, 3], to: [10, 40, 6], diameter: 2,
      color: "#ffffff", dark: { color: "#000000" },
    };
    const a = { ...BEDSIDE_DISPLAY, parts: [part] };
    const b = { ...a, parts: undefined, body: { ...a.body, width: 2 * a.body.width } };
    const tube = blendModels(a, b, 0.09).parts?.[0] as TubePart;
    expect(tube.from[0]).toBeCloseTo(10.9);
    expect(tube.to[0]).toBeCloseTo(10.9);
    expect(tube).toMatchObject({
      from: [expect.any(Number), 20, 3],
      to: [expect.any(Number), 40, 6],
      color: "color-mix(in srgb, #ffffff 50.0%, transparent)",
      dark: { color: "color-mix(in srgb, #000000 50.0%, transparent)" },
    });
    expect(blendModels(a, b, 0.5).parts).toBeUndefined();
    expect(blendModels(b, a, 0.91).parts?.[0]).toMatchObject({
      color: "color-mix(in srgb, #ffffff 50.0%, transparent)",
    });
  });

  it("moves both camera axes and projects a round, screen-filling dial at the hold", () => {
    expect(blendViews({ rx: 10, ry: -20 }, { rx: -10, ry: 20 }, 0.25)).toEqual({ rx: 5, ry: -10 });
    const dial = geometry(blendModels(BEDSIDE_DISPLAY, ROUND_DIAL, 1), 1);
    expect(dial.layout).toBe("square");
    expect(dial.vars["--rad"]).toBe("50% / 50%");
    expect(dial.dock).toBeUndefined();
  });
});
