/*
 * The Glassworks mark: three plates of the stack, seen from its isometric angle
 * (both axes at 30°), with the top plate lit. Shared by <Mark> and the SVG
 * generator (scripts/brand), so every copy of the mark is the same drawing.
 *
 * Everything is on a 120 × 120 canvas. Plain TypeScript with no imports, so Node
 * can run it directly from the generator.
 */

const C = Math.cos(Math.PI / 6); // 0.866
const S = 0.5;

export type MarkCut = "full" | "small";

/** One plate: a square of `side` with corner radius `r`, its back corner at (cx, top). */
export function platePath(cx: number, top: number, side: number, r: number): string {
  const p = (x: number, y: number) => [cx + C * (x - y), top + S * (x + y)];
  const pts: number[][] = [];
  const seg = 8;
  const corner = (ox: number, oy: number, a0: number) => {
    for (let i = 0; i <= seg; i++) {
      const a = a0 + (i * Math.PI) / 2 / seg;
      pts.push(p(ox + r * Math.cos(a), oy + r * Math.sin(a)));
    }
  };
  corner(side - r, r, -Math.PI / 2);
  corner(side - r, side - r, 0);
  corner(r, side - r, Math.PI / 2);
  corner(r, r, Math.PI);
  return `M${pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join("L")}Z`;
}

export type MarkPlate = {
  d: string;
  /** the lit plate is filled; the others are outlined */
  lit: boolean;
  /** outline opacity for the plates below */
  opacity: number;
  strokeWidth: number;
};

/*
 * Full cut: three plates, 19 units apart, the lower two outlined at fading
 * strength. Small cut (under 40 px): two plates and a heavier line, so it holds
 * at 16 px.
 */
export const MARK_SPEC = {
  full: { side: 60, r: 9, step: 19, top: 11, plates: 3, stroke: 5, fade: [0.45, 0.2] },
  small: { side: 64, r: 10, step: 24, top: 18, plates: 2, stroke: 9, fade: [0.55] },
} as const;

export function markPlates(cut: MarkCut = "full"): MarkPlate[] {
  const m = MARK_SPEC[cut];
  const out: MarkPlate[] = [];
  // bottom first, so the lit plate is drawn last, on top
  for (let i = m.plates - 1; i >= 0; i--) {
    out.push({
      d: platePath(60, m.top + i * m.step, m.side, m.r),
      lit: i === 0,
      opacity: i === 0 ? 1 : m.fade[i - 1],
      strokeWidth: m.stroke,
    });
  }
  return out;
}

/*
 * Kiln's mark: the same plates, but the fired one is the bottom plate, the
 * floor everything is fired on (as in the stack on the home page). The plates
 * above it are outlined, the top one strongest.
 */
export function kilnPlates(cut: MarkCut = "full"): MarkPlate[] {
  const m = MARK_SPEC[cut];
  const above = cut === "full" ? [0.7, 0.45] : [0.7]; // top first
  const out: MarkPlate[] = [];
  for (let i = m.plates - 1; i >= 0; i--) {
    const bottom = i === m.plates - 1;
    out.push({
      d: platePath(60, m.top + i * m.step, m.side, m.r),
      lit: bottom,
      opacity: bottom ? 1 : above[i],
      strokeWidth: m.stroke,
    });
  }
  return out;
}

/*
 * Vitro's mark, "Cast": one plate of glass, thick and lit, with the dashboard's
 * cards set into its face. Where the Glassworks mark shows the plates apart,
 * Vitro's are cast into one piece: the dashboard drawn natively, with nothing
 * stacked on top of it. The small cut (under 40 px) is a little larger and
 * thicker, with wider gaps between the cards, so they hold at 16 px.
 */
export type VitroMark = {
  /** the slab's edge: the outline of the face and its copy `thick` lower */
  edge: string;
  /** the lit face */
  face: string;
  /** the cards: a tall one on the left, two stacked on the right, each a sheet of lighter glass */
  cards: { d: string; opacity: number }[];
};

export const VITRO_SPEC = {
  full: { side: 66, r: 10, thick: 13, margin: 9, gap: 6, cardR: 3.5 },
  small: { side: 68, r: 10.5, thick: 14, margin: 9.5, gap: 7, cardR: 4 },
} as const;

/** The points of a rounded rect in a plate's plane: x along the right axis, y along the left. */
function isoRoundRect(cx: number, top: number, x0: number, y0: number, w: number, h: number, r: number, seg = 8): number[][] {
  const p = (x: number, y: number) => [cx + C * (x - y), top + S * (x + y)];
  const pts: number[][] = [];
  const corner = (ox: number, oy: number, a0: number) => {
    for (let i = 0; i <= seg; i++) {
      const a = a0 + (i * Math.PI) / 2 / seg;
      pts.push(p(ox + r * Math.cos(a), oy + r * Math.sin(a)));
    }
  };
  corner(x0 + w - r, y0 + r, -Math.PI / 2);
  corner(x0 + w - r, y0 + h - r, 0);
  corner(x0 + r, y0 + h - r, Math.PI / 2);
  corner(x0 + r, y0 + r, Math.PI);
  return pts;
}

/** Convex hull (monotone chain): the slab's outline is the hull of its face and the face moved down. */
function hull(points: number[][]): number[][] {
  const pts = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cross = (o: number[], a: number[], b: number[]) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower: number[][] = [];
  const upper: number[][] = [];
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop();
    lower.push(p);
  }
  for (const p of pts.reverse()) {
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop();
    upper.push(p);
  }
  return lower.slice(0, -1).concat(upper.slice(0, -1));
}

const pathOf = (pts: number[][]) => `M${pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join("L")}Z`;

export function vitroMark(cut: MarkCut = "full"): VitroMark {
  const m = VITRO_SPEC[cut];
  const top = (120 - (m.side + m.thick)) / 2; // centred on the canvas, edge included
  const face = isoRoundRect(60, top, 0, 0, m.side, m.side, m.r);
  const edge = hull([...face, ...face.map(([x, y]) => [x, y + m.thick])]);
  const inner = m.side - 2 * m.margin;
  const wide = (inner - m.gap) / 2;
  const half = (inner - m.gap) / 2;
  const right = m.margin + wide + m.gap;
  return {
    edge: pathOf(edge),
    face: pathOf(face),
    cards: [
      { d: pathOf(isoRoundRect(60, top, m.margin, m.margin, wide, inner, m.cardR, 6)), opacity: 0.42 },
      { d: pathOf(isoRoundRect(60, top, right, m.margin, wide, half, m.cardR, 6)), opacity: 0.26 },
      { d: pathOf(isoRoundRect(60, top, right, m.margin + half + m.gap, wide, half, m.cardR, 6)), opacity: 0.26 },
    ],
  };
}
