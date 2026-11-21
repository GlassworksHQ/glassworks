import type { FrameModel, TubePart, View } from "@glassworks/frame-card";

/*
 * One device turning into the next, for the home page's story (MorphStory): a FrameModel part way between
 * two, so the 3D body itself morphs as it travels from beat to beat, its size, depth, corners, screen,
 * colours and lean. At t = 0 it draws exactly as `a`, at t = 1 exactly as `b`.
 */

const GLASS = "#07080b"; // the front of a model without a `face` (PAINT.glass in @glassworks/frame-card)

const clamp = (v: number) => Math.min(1, Math.max(0, v));
const smooth = (t: number) => t * t * (3 - 2 * t);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

function mixHex(a: string, b: string, t: number) {
  if (t <= 0 || a === b) return a;
  if (t >= 1) return b;
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `#${pa.map((v, i) => Math.round(v + (pb[i]! - v) * t).toString(16).padStart(2, "0")).join("")}`;
}

/** The screen as fractions of the body: its own box, or the face minus the bezel (frame-card's screenBox) */
function screenFractions(m: FrameModel) {
  const { width: w, height: h } = m.body;
  const box =
    m.screen.width && m.screen.height
      ? { w: m.screen.width, h: m.screen.height, top: m.screen.top ?? m.bezel }
      : { w: w - 2 * m.bezel, h: h - 2 * m.bezel, top: m.bezel };
  return { w: box.w / w, h: box.h / h, top: box.top / h };
}

const leanOf = (m: FrameModel) => (m.mount === "lean" || m.mount === "desk" ? (m.lean ?? 0) : 0);

/**
 * A tube of `m`'s stand on a body of another size: it keeps its place on the body (scaled with it, so it
 * stays attached), faded to `o` (a stand leaves with its device and arrives with the next)
 */
function fitTube(p: TubePart, m: FrameModel, body: FrameModel["body"], o: number): TubePart {
  if (o >= 0.999 && body.width === m.body.width && body.height === m.body.height) return p;
  const k = [body.width / m.body.width, body.height / m.body.height, body.depth / m.body.depth] as const;
  const at = (v: TubePart["from"]): TubePart["from"] => [v[0] * k[0], v[1] * k[1], v[2] * k[2]];
  const fade = (c: string) => `color-mix(in srgb, ${c} ${(o * 100).toFixed(1)}%, transparent)`;
  return { ...p, from: at(p.from), to: at(p.to), color: fade(p.color), ...(p.dark ? { dark: { color: fade(p.dark.color) } } : {}) };
}

export function blendModels(a: FrameModel, b: FrameModel, t: number): FrameModel {
  if (t <= 0) return a;
  if (t >= 1) return b;
  const near = t < 0.5 ? a : b;

  // the body and its screen, in mm and as fractions of the face
  const width = lerp(a.body.width, b.body.width, t);
  const height = lerp(a.body.height, b.body.height, t);
  const sa = screenFractions(a);
  const sb = screenFractions(b);

  // a wall panel has no lean; it takes the other's mount once it has left the wall (the ground under it
  // is hidden while it travels), and its lean grows from there, so nothing jumps
  const wallA = a.mount === "wall";
  const wallB = b.mount === "wall";
  const mount = t < 0.15 ? a.mount : t > 0.85 ? b.mount : wallA && !wallB ? b.mount : wallB && !wallA ? a.mount : near.mount;
  const lean = lerp(leanOf(a), leanOf(b), smooth(clamp((t - 0.15) / 0.7)));

  // colours: each look blends with the other's (a frame without a dark look keeps its light one there)
  const faced = a.face || a.dark?.face || b.face || b.dark?.face;
  const faceA = a.face ?? GLASS;
  const faceB = b.face ?? GLASS;
  const darkLook = a.dark || b.dark;
  const nightSides = (m: FrameModel) => m.dark?.sides ?? m.sides;

  const body: FrameModel["body"] = {
    shape: "rect",
    width,
    height,
    depth: lerp(a.body.depth, b.body.depth, t),
    radius: Math.min(lerp(a.body.radius, b.body.radius, t), width / 2, height / 2),
  };

  // parts: the stand of the device it leaves, fading out, then the next one's, fading in. A dock is drawn
  // apart, standing still (MorphStory), so the travelling model's own is hidden, but kept so it is fitted
  // to its stage as the beat's model is
  const owner = t < 0.5 ? a : b;
  const tubeO = t < 0.5 ? 1 - smooth(clamp(t / 0.18)) : smooth(clamp((t - 0.82) / 0.18));
  const parts = owner.parts?.map((p) => (p.kind === "tube" ? fitTube(p, owner, body, tubeO) : p));

  return {
    body,
    bezel: lerp(a.bezel, b.bezel, t),
    screen: {
      radius: lerp(a.screen.radius, b.screen.radius, t),
      width: lerp(sa.w, sb.w, t) * width,
      height: lerp(sa.h, sb.h, t) * height,
      top: lerp(sa.top, sb.top, t) * height,
    },
    sides: { dark: mixHex(a.sides.dark, b.sides.dark, t), light: mixHex(a.sides.light, b.sides.light, t) },
    finish: near.finish,
    ...(faced ? { face: mixHex(faceA, faceB, t) } : {}),
    mount,
    lean,
    ...(parts?.length ? { parts } : {}),
    ...(darkLook
      ? {
          dark: {
            ...(faced ? { face: mixHex(a.dark?.face ?? faceA, b.dark?.face ?? faceB, t) } : {}),
            sides: {
              dark: mixHex(nightSides(a).dark, nightSides(b).dark, t),
              light: mixHex(nightSides(a).light, nightSides(b).light, t),
            },
          },
        }
      : {}),
  };
}

export const blendViews = (a: View, b: View, t: number): View => ({ rx: lerp(a.rx, b.rx, t), ry: lerp(a.ry, b.ry, t) });
