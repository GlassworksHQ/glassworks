import type { FrameModel } from "@glassworks/frame-card";

/*
 * Made-up shapes for the home page's story (MorphStory): devices Glassworks could run on, drawn by the
 * same @glassworks/frame-card model as the real frames, so every beat is a 3D device. They are
 * illustrations, not frames: no frames/<id>/design/frame.json, and nothing on the site lists them.
 * Sizes in mm, as in frame.json's `model` (frames/README.md).
 */

const graphite = { dark: "#1b1d22", light: "#4a4e57" };

/** A wide display on a fabric speaker base, on a kitchen counter (Beat 1) */
export const WIDE_DISPLAY: FrameModel = {
  body: { shape: "rect", width: 220, height: 120, depth: 10, radius: 7 },
  bezel: 6,
  screen: { radius: 2 },
  sides: graphite,
  finish: "glass",
  mount: "desk",
  lean: 12,
  parts: [{ kind: "dock", width: 150, depth: 62, height: 72, gap: 4, sides: { dark: "#2a2c31", light: "#5b5f68" } }],
};

/** A square 4" wall switch (the finale) */
export const SQUARE_SWITCH: FrameModel = {
  body: { shape: "rect", width: 86, height: 86, depth: 9, radius: 4 },
  bezel: 5,
  screen: { radius: 2 },
  sides: graphite,
  finish: "glass",
  mount: "wall",
};

/** A round dial on the wall: a body as wide as its corners, so a circle (the finale) */
export const ROUND_DIAL: FrameModel = {
  body: { shape: "rect", width: 84, height: 84, depth: 12, radius: 42 },
  bezel: 6,
  screen: { radius: 36 },
  sides: graphite,
  finish: "glass",
  mount: "wall",
};

/** A bedside display, leaning back on a nightstand (the finale) */
export const BEDSIDE_DISPLAY: FrameModel = {
  body: { shape: "rect", width: 180, height: 90, depth: 14, radius: 9 },
  bezel: 6,
  screen: { radius: 3 },
  sides: graphite,
  finish: "glass",
  mount: "desk",
  lean: 16,
};
