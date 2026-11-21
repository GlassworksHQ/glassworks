// The 3D device mockup's geometry, ported from glassworks.dev `src/components/deviceGeometry.ts`
// (fbfd364), one copy for Kiln's frame cards and the site's. The site keeps its models in a
// table there; here each frame's model comes from its own `frames/<id>/design/frame.json` (the
// `model` section in planning's decisions.md, "frame.json describes the device's look in millimetres").
// The maths is the site's: fit the body to the stage, pose it for its mount, shade the sides that
// face the viewer, and give the CSS-3D front glass the same transform. A dock part (the Pixel
// Tablet dock) is built here, on the level floor, and drawn apart from the body so the body can lift off it.
import type { DockPart, FrameModel, TubePart } from './frame';
import { PAINT } from './paint';

type V3 = [number, number, number];

/* viewing angle per mount: wall panels from a little below eye level, floor mirrors nearly face-on */
const VIEWS = {
  wall: { rx: 9, ry: -22 },
  floor: { rx: 3, ry: -24 },
  lean: { rx: 3, ry: -24 },
  /* on a desk or counter, e.g. in a dock: from a little above */
  desk: { rx: 10, ry: -24 },
} as const;
const PERSPECTIVE = 170;

const rad = (deg: number) => (deg * Math.PI) / 180;

/* CSS "rotateX(rx) rotateY(ry) rotateX(lean)": p' = Rx(Ry(Rlean(p))). y down; positive lean tips the top away. */
function rotate([x0, y0, z0]: V3, view: { rx: number; ry: number }, lean = 0): V3 {
  const l = rad(lean);
  const x = x0;
  const y = Math.cos(l) * y0 - Math.sin(l) * z0;
  const z = Math.sin(l) * y0 + Math.cos(l) * z0;
  const a = rad(view.ry);
  const x1 = Math.cos(a) * x + Math.sin(a) * z;
  const z1 = -Math.sin(a) * x + Math.cos(a) * z;
  const b = rad(view.rx);
  return [x1, Math.cos(b) * y - Math.sin(b) * z1, Math.sin(b) * y + Math.cos(b) * z1];
}

/**
 * Segments per rounded corner: 6 for a corner that is small next to the body, up to 16 as it grows, so a
 * body that is all corner (a round dial) has 64 sides instead of 24 and its edge stays smooth.
 */
function cornerSteps(w: number, h: number, r: number) {
  return Math.min(16, Math.max(6, Math.round((32 * r) / Math.min(w, h))));
}

/* the rounded-rectangle outline, clockwise on screen, with outward normals */
function outline(w: number, h: number, r: number, steps = cornerSteps(w, h, r)) {
  const hw = w / 2;
  const hh = h / 2;
  const corners: [number, number, number][] = [[hw - r, -hh + r, -90], [hw - r, hh - r, 0], [-hw + r, hh - r, 90], [-hw + r, -hh + r, 180]];
  const pts: { x: number; y: number; nx: number; ny: number }[] = [];
  for (const [cx, cy, start] of corners) {
    for (let i = 0; i <= steps; i++) {
      const t = rad(start + (90 * i) / steps);
      pts.push({ x: cx + r * Math.cos(t), y: cy + r * Math.sin(t), nx: Math.cos(t), ny: Math.sin(t) });
    }
  }
  return pts;
}

const LIGHT: V3 = (() => {
  const v: V3 = [-0.45, -0.75, 0.5];
  const n = Math.hypot(...v);
  return [v[0] / n, v[1] / n, v[2] / n];
})();

/** Mixes two of the model's paint colours, as `#rrggbb`. */
function mix(a: string, b: string, t: number) {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `#${pa.map((v, i) => Math.round(v + (pb[i]! - v) * t).toString(16).padStart(2, '0')).join('')}`;
}

/** A colour in both looks: CSS light-dark() when a frame has a dark one, so the page's color-scheme picks it. */
const either = (light: string, dark?: string) => (dark && dark !== light ? `light-dark(${light}, ${dark})` : light);

const f3 = (n: number) => +n.toFixed(3);

/** no view: rotate(p, LEVEL, lean) takes a point from the body's frame to the level floor's (y down) */
const LEVEL = { rx: 0, ry: 0 } as const;

const sub = (a: V3, b: V3): V3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a: V3, b: V3) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const unit = (v: V3): V3 => {
  const n = Math.hypot(...v) || 1;
  return [v[0] / n, v[1] / n, v[2] / n];
};

/** a level ellipse at height y, around z = zc, as points */
function ellipse(rx: number, rz: number, y: number, zc: number, n = 28): V3[] {
  return Array.from({ length: n }, (_, i): V3 => {
    const t = (i / n) * Math.PI * 2;
    return [Math.cos(t) * rx, y, zc + Math.sin(t) * rz];
  });
}

/**
 * A stadium (a rectangle with fully round ends), `len` long and `wide` across, as points [along, across]
 * by the direction of their outward normal, so two stadiums sampled alike can be joined point to point.
 */
function stadium(len: number, wide: number, n = 36): [number, number][] {
  const r = wide / 2;
  const half = Math.max(0, len / 2 - r);
  return Array.from({ length: n }, (_, i) => {
    const t = ((i + 0.5) / n) * Math.PI * 2;
    return [Math.sign(Math.cos(t)) * half + r * Math.cos(t), r * Math.sin(t)];
  });
}

/**
 * The dock on the level floor (mm, y down, z towards the viewer), after the Pixel Tablet's: a smooth
 * pill-shaped plate on the body's leaned back, wrapped in fabric that runs back and down from the
 * pill to a stadium-shaped foot on the floor. So its ends are cones and its side is a triangle with a
 * round top. `bodyFloor` is the body's lowest point; the floor is `gap` below it.
 */
function dockShape(dk: DockPart, body: FrameModel['body'], lean: number, bodyFloor: number) {
  const l = rad(lean);
  const back0 = rotate([0, body.height / 2, -body.depth / 2], LEVEL, lean);
  const floor = bodyFloor + dk.gap;
  const onBack = (y: number) => back0[2] + (y - back0[1]) * Math.tan(l);
  // the pill: up the body's back from just above the floor to the dock's height
  const foot = Math.min(6, dk.height / 10);
  const tall = (dk.height - foot) / Math.cos(l);
  const yc = floor - foot - (tall / 2) * Math.cos(l);
  const zc = onBack(yc);
  const onPlate = (x: number, v: number, out = 0): V3 => [x, yc - v * Math.cos(l) - out * Math.sin(l), zc - v * Math.sin(l) + out * Math.cos(l)];
  const pill = stadium(dk.width, tall).map(([x, v]) => onPlate(x, v));
  // the foot: as wide as the pill, `depth` deep, from where the body's back meets the floor. Its back
  // pairs with the pill's top, so the fabric slopes from the top down to the floor behind
  const front = onBack(floor);
  const back = front - dk.depth;
  const toe = stadium(dk.width, dk.depth).map(([x, b]): V3 => [x, floor, (front + back) / 2 - b]);
  const sides = pill.map((p, i) => {
    const j = (i + 1) % pill.length;
    return [p, pill[j]!, toe[j]!, toe[i]!];
  });
  // the smooth plate in the fabric's rim, and the four contacts near its foot
  const rim = Math.min(4, tall / 8);
  const plate = stadium(dk.width - 2 * rim, tall - 2 * rim).map(([x, v]) => onPlate(x, v, 0.4));
  const pins = [-1.5, -0.5, 0.5, 1.5].map((i) => onPlate(i * 5, -tall / 2 + rim + 7, 0.6));
  return { polygons: [...sides, pill, toe], plate, pins, floor, front, back, points: [...pill, ...toe] };
}

/** A face's normal (Newell's method, which a many-sided cap needs too), unit length, or undefined if it has no area. */
function normal(v: V3[]): V3 | undefined {
  const raw = v.reduce<V3>((s, p, i) => {
    const q = v[(i + 1) % v.length]!;
    return [s[0] + (p[1] - q[1]) * (p[2] + q[2]), s[1] + (p[2] - q[2]) * (p[0] + q[0]), s[2] + (p[0] - q[0]) * (p[1] + q[1])];
  }, [0, 0, 0]);
  return Math.hypot(...raw) < 1e-9 ? undefined : unit(raw);
}

const centroid = (v: V3[]): V3 => v.reduce<V3>((s, p) => [s[0] + p[0] / v.length, s[1] + p[1] / v.length, s[2] + p[2] / v.length], [0, 0, 0]);

/**
 * The faces of a closed, roughly convex solid that face the viewer, shaded like the body's sides,
 * back to front. `lift` lightens them all, for a smoother material.
 */
function solidFaces(polygons: V3[][], centre: V3, sides: { dark: string; light: string }, view: { rx: number; ry: number }, k: number, Q: (p: V3) => [number, number], lift = 0, night?: { dark: string; light: string }): Face[] {
  const V = (p: V3) => rotate([p[0] * k, p[1] * k, p[2] * k], view);
  const mid = V(centre);
  const camera: V3 = [0, 0, PERSPECTIVE];
  const faces: { face: Face; z: number }[] = [];
  for (const q of polygons) {
    const v = q.map(V);
    const c = centroid(v);
    let n = normal(v);
    if (!n) continue;
    if (dot(n, sub(c, mid)) < 0) n = [-n[0], -n[1], -n[2]];
    if (dot(n, sub(camera, c)) <= 0) continue;
    const lit = Math.max(0, dot(n, LIGHT));
    const t = Math.min(1, 0.25 + lift + 0.75 * lit);
    faces.push({
      face: { points: q.map(Q).map(([x, y]) => `${f3(x)},${f3(y)}`).join(' '), fill: either(mix(sides.dark, sides.light, t), night && mix(night.dark, night.light, t)) },
      z: c[2],
    });
  }
  return faces.sort((a, b) => a.z - b.z).map((f) => f.face);
}

/** `fill` (and a Line's `color`) may be CSS light-dark(): draw it as a style, which takes CSS colours, not as an attribute */
export type Face = { points: string; fill: string };
export type Line = { x1: number; y1: number; x2: number; y2: number; width: number; color: string };
export type Layout = 'landscape' | 'square' | 'portrait';
/** A dock the body sits in: drawn behind the body and left in place when the body lifts off it. */
export type Dock = {
  /** its fabric's faces that face the viewer, back to front, shaded like the body's sides */
  faces: Face[];
  /** the smooth plate on its front, and the contacts on it, when the front faces the viewer */
  plate?: Face;
  pins?: { cx: number; cy: number; r: number }[];
};

export type Geometry = {
  /** stage height in stage units (width is 100) */
  stageH: number;
  faces: Face[];
  parts: Line[];
  floorShadow?: string;
  /** a dock part, drawn apart from the body so the body can lift off it */
  dock?: Dock;
  /** CSS custom properties for the front glass, and for a docked body its lift (--ux, --uy, --us) */
  vars: Record<string, string>;
  /** 2D shift that centres what's drawn */
  transform: string;
  layout: Layout;
  finish: FrameModel['finish'];
  mount: FrameModel['mount'];
};

/** Fit, pose and project a model for a stage of the given aspect ratio (width / height). */
/** Where the camera looks from, in degrees: `rx` from above, `ry` from the side (negative: from the right). */
export type View = { rx: number; ry: number };

/**
 * `view` overrides the mount's camera (VIEWS), e.g. to turn a model toward the text beside it; everything,
 * the sides, parts, dock, shadow and the glass's CSS pose, follows it.
 */
export function geometry(m: FrameModel, stageAspect: number, view: View = VIEWS[m.mount]): Geometry {
  const lean = m.mount === 'lean' || m.mount === 'desk' ? (m.lean ?? 0) : 0;
  const stageH = 100 / stageAspect;
  const cx = 50;
  const cy = stageH / 2;
  const project = ([x, y, z]: V3): [number, number] => {
    const s = PERSPECTIVE / (PERSPECTIVE - z);
    return [cx + x * s, cy + y * s];
  };

  const { width: w, height: h, depth: d, radius: r } = m.body;
  const ring = outline(w, h, r);
  const tubes = (m.parts ?? []).filter((p): p is TubePart => p.kind === 'tube');
  const dockPart = (m.parts ?? []).find((p): p is DockPart => p.kind === 'dock');
  const shell: V3[] = [
    ...ring.map((p): V3 => [p.x, p.y, d / 2]),
    ...ring.map((p): V3 => [p.x, p.y, -d / 2]),
  ];
  const dock = dockPart && dockShape(dockPart, m.body, lean, Math.max(...shell.map((p) => rotate(p, LEVEL, lean)[1])));
  const silhouette: V3[] = [
    ...shell,
    ...tubes.flatMap((p) => [p.from, p.to]),
    // the dock is built on the level floor; turn it back into the body's frame, which P leans
    ...(dock?.points ?? []).map((p) => rotate(p, LEVEL, -lean)),
  ];

  // scale mm to stage units so the drawing fills the stage with a margin; perspective makes this
  // slightly non-linear, so refine a few times
  const maxW = 72;
  const maxH = stageH * 0.86;
  let k = Math.min(maxW / w, maxH / h);
  const measure = () => {
    const pts = silhouette.map((p) => project(rotate([p[0] * k, p[1] * k, p[2] * k], view, lean)));
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    return { x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) };
  };
  let box = measure();
  for (let i = 0; i < 4; i++) {
    box = measure();
    k *= Math.min(maxW / (box.x1 - box.x0), maxH / (box.y1 - box.y0));
  }
  box = measure();
  const P = (p: V3) => project(rotate([p[0] * k, p[1] * k, p[2] * k], view, lean));

  // side faces that face the viewer
  const camera: V3 = [0, 0, PERSPECTIVE];
  const faces: Face[] = [];
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!;
    const b = ring[(i + 1) % ring.length]!;
    if (Math.hypot(b.x - a.x, b.y - a.y) < 1e-6) continue;
    const n = rotate([(a.nx + b.nx) / 2, (a.ny + b.ny) / 2, 0], view, lean);
    const mid = rotate([((a.x + b.x) / 2) * k, ((a.y + b.y) / 2) * k, 0], view, lean);
    const toCam: V3 = [camera[0] - mid[0], camera[1] - mid[1], camera[2] - mid[2]];
    if (n[0] * toCam[0] + n[1] * toCam[1] + n[2] * toCam[2] <= 0) continue;
    const quad: V3[] = [[a.x, a.y, d / 2], [b.x, b.y, d / 2], [b.x, b.y, -d / 2], [a.x, a.y, -d / 2]];
    const lit = Math.max(0, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
    faces.push({
      points: quad.map(P).map(([x, y]) => `${f3(x)},${f3(y)}`).join(' '),
      fill: either(mix(m.sides.dark, m.sides.light, 0.25 + 0.75 * lit), m.dark?.sides && mix(m.dark.sides.dark, m.dark.sides.light, 0.25 + 0.75 * lit)),
    });
  }

  // parts as thick projected lines, drawn back to front
  const parts: Line[] = tubes
    .map((p) => {
      const [x1, y1] = P(p.from);
      const [x2, y2] = P(p.to);
      const z = rotate([0, ((p.from[1] + p.to[1]) / 2) * k, ((p.from[2] + p.to[2]) / 2) * k], view, lean)[2];
      return { line: { x1: f3(x1), y1: f3(y1), x2: f3(x2), y2: f3(y2), width: f3(p.diameter * k), color: either(p.color, p.dark?.color) }, z };
    })
    .sort((a, b) => a.z - b.z)
    .map((p) => p.line);

  // a soft shadow on the level floor under a standing or leaning device, or under its dock
  let floorShadow: string | undefined;
  if (dock) {
    const { floor, front, back } = dock;
    const rx = dockPart!.width * 0.56 * k;
    const rz = (front - back) * 0.6 * k;
    const zc = ((front + back) / 2) * k;
    floorShadow = ellipse(rx, rz, floor * k, zc).map((p) => project(rotate(p, view))).map(([x, y]) => `${f3(x)},${f3(y)}`).join(' ');
  } else if (m.mount !== 'wall') {
    const leaned = silhouette.map((p) => rotate([p[0] * k, p[1] * k, p[2] * k], { rx: 0, ry: 0 }, lean));
    const floorY = Math.max(...leaned.map((p) => p[1]));
    const base = leaned.filter((p) => p[1] > floorY - 0.5);
    const zc = base.reduce((sum, p) => sum + p[2], 0) / base.length;
    const rx = w * 0.62 * k;
    const rz = 220 * k;
    floorShadow = Array.from({ length: 28 }, (_, i): V3 => {
      const t = (i / 28) * Math.PI * 2;
      return [Math.cos(t) * rx, floorY, zc - 60 * k + Math.sin(t) * rz];
    })
      .map((p) => project(rotate(p, view)))
      .map(([x, y]) => `${f3(x)},${f3(y)}`)
      .join(' ');
  }

  // the dock, in the level floor's frame: its faces that face the viewer, its plate and contacts
  let docked: Dock | undefined;
  const lift: Record<string, string> = {};
  if (dock && dockPart) {
    const Q = (p: V3) => project(rotate([p[0] * k, p[1] * k, p[2] * k], view));
    const centre = centroid(dock.points);
    const night = dockPart.dark?.sides;
    const faces = solidFaces(dock.polygons, centre, dockPart.sides, view, k, Q, 0, night);
    // the plate shows when the front of the fabric does: it lies on it
    const plate = solidFaces([dock.plate], centre, dockPart.sides, view, k, Q, 0.12, night)[0];
    docked = { faces };
    if (plate) {
      docked.plate = plate;
      const r = 0.9 * k;
      docked.pins = dock.pins.map(Q).map(([x, y]) => ({ cx: f3(x), cy: f3(y), r: f3(r) }));
    }
    // lifted off: up two fifths of the dock's height and a little towards the viewer, as a 2D move and
    // scale of what's drawn, about the body's centre (the stage's centre before the shift below)
    const up = rotate([0, -dockPart.height * 0.4 * k, dockPart.height * 0.16 * k], view);
    const [ux, uy] = project(up);
    Object.assign(lift, {
      '--ux': `${f3(ux - cx)}%`,
      '--uy': `${f3(((uy - cy) / stageH) * 100)}%`,
      '--us': String(f3(PERSPECTIVE / (PERSPECTIVE - up[2]) - 1)),
    });
  }

  const dx = cx - (box.x0 + box.x1) / 2;
  const dy = cy - (box.y0 + box.y1) / 2;
  const ar = w / h;
  const sw = w * k;
  const sh = h * k;
  const pct = (n: number) => `${f3(n)}%`;
  return {
    stageH,
    faces,
    parts,
    floorShadow,
    dock: docked,
    vars: {
      '--wp': pct(sw),
      '--ar': `${f3(ar)} / 1`,
      '--rad': `${pct(((r * k) / sw) * 100)} / ${pct(((r * k) / sh) * 100)}`,
      ...screenBox(m),
      '--du': String(f3(d * k)),
      '--sr': String(f3(m.screen.radius * k)),
      '--rx': `${view.rx}deg`,
      '--ry': `${view.ry}deg`,
      '--lean': `${lean}deg`,
      // the front around the screen; a dark look's own, or black glass, on a dark page
      ...(m.face || m.dark?.face ? { '--dev-face': either(m.face ?? PAINT.glass, m.dark?.face) } : {}),
      ...lift,
    },
    transform: `translate(${f3(dx)}%, ${f3((dy / stageH) * 100)}%)`,
    layout: ar >= 1.3 ? 'landscape' : ar <= 0.8 ? 'portrait' : 'square',
    finish: m.finish,
    mount: m.mount,
  };
}

/* The screen's box as % of the body: the given screen rectangle, or the face minus the bezel. */
function screenBox(m: FrameModel): Record<string, string> {
  const { width: w, height: h } = m.body;
  const sc = m.screen.width && m.screen.height
    ? { w: m.screen.width, h: m.screen.height, top: m.screen.top ?? m.bezel }
    : { w: w - 2 * m.bezel, h: h - 2 * m.bezel, top: m.bezel };
  const pct = (n: number) => `${f3(n)}%`;
  return {
    '--scr-x': pct(((w - sc.w) / 2 / w) * 100),
    '--scr-y': pct((sc.top / h) * 100),
    '--scr-w': pct((sc.w / w) * 100),
    '--scr-h': pct((sc.h / h) * 100),
  };
}
