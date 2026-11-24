/**
 * Generates the part badges and README banners into public/brand/.
 *
 *   npm run brand
 *
 * Badges (30 px, for a row of shields): core, pane, kiln, vitro, library.
 * Banners (640 × 180, for the top of a README): glassworks (the whole stack,
 * for the org profile and the main README), principles (the page of rules every
 * part follows), core, pane, kiln, vitro, one per library in src/lib/libraries.json, and
 * one per frame with a tagline in frames/ (frame.mjs). Each in -on-dark
 * and -on-light.
 *
 * Info badges (30 px, in place of shields.io): licence (MPL-2.0 for the core,
 * MIT for libraries), status (planned, under development, α alpha, β beta,
 * stable) and roadmap, from src/lib/info-badges.json.
 *
 * Part icons (34 × 33, transparent, for tables and lists): frame, core, pane,
 * kiln. A tiny stack whose solid plate says where the part sits. And vitro,
 * whose plates are cast into one: its own mark at the same size.
 *
 * The mark and everything built from it: mark-on-*, mark-mono, kiln-on-dark,
 * lockup-on-*, lockup-kiln-on-dark, app-icon, favicon (also src/app/icon.svg),
 * and Kiln's app-icon-kiln and favicon-kiln (the same, with the ember floor),
 * and Vitro's: vitro, app-icon-vitro and favicon-vitro (its cast slab, from
 * vitroMark in markGeometry.ts), with its badge, part icon and banner,
 * and the frame badge, "Built on Glassworks" (badge-on-*). The mark's shape
 * comes from src/lib/markGeometry.ts, the same file <Mark> draws from.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { measure, text } from "./text.mjs";
import { BH, BW, PART, VITRO_EDGE, cellGuides, markBody, markIcon, partBanner, partMark, vitroBody } from "./banner.mjs";
import { frameBanner } from "./frame.mjs";
import { existsSync, readdirSync } from "node:fs";
import {
  C,
  THEMES,
  backCorner,
  block,
  cellAt,
  drops,
  field,
  guide,
  plate,
  plateHeight,
  project,
  svg,
} from "./draw.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");
const out = join(root, "public/brand");
const readJson = (file) => JSON.parse(readFileSync(join(root, file), "utf8"));
const libraries = readJson("src/lib/libraries.json");
// every frame in the repo with a tagline gets a banner (frame.mjs)
const FRAMES = join(root, "../frames");
const frames = readdirSync(FRAMES, { withFileTypes: true })
  .filter((e) => e.isDirectory() && existsSync(join(FRAMES, e.name, "design/frame.json")))
  .map((e) => JSON.parse(readFileSync(join(FRAMES, e.name, "design/frame.json"), "utf8")))
  .filter((f) => f.tagline)
  .sort((a, b) => a.id.localeCompare(b.id));
const infoBadges = readJson("src/lib/info-badges.json");

const EMBER = "#f43f5e";

/* ---------- badges ---------- */

const CAPS = { font: "medium", size: 9.5, ls: 1.5 };
const CAPS_B = { font: "semibold", size: 9.5, ls: 1.5 };

const SZ = 12.7; // the badges' type size
const SPACE = () => measure(" ", { size: SZ }) + 0.6;


/*
 * Built on Glassworks: the finished-product badge. A plain pill in sentence
 * case, the mark and the words, and no construction: it marks a finished
 * product, not a part being built.
 */
function builtOnBadge(t) {
  const x0 = 39;
  const gap = 48.7 - measure("Built on", { size: SZ });
  const w = x0 + measure("Built on", { size: SZ }) + gap + measure("Glassworks", { font: "semibold", size: SZ }) + 12.4;
  const body =
    `<rect x=".5" y=".5" width="${w - 1}" height="29" rx="14.5" fill="${t.bg}" stroke="${t.edge}"/>` +
    markIcon(t, 10, 5, 20) +
    text("Built on", { size: SZ, x: x0, y: 19.73, fill: t.mu }) +
    text("Glassworks", { font: "semibold", size: SZ, x: x0 + measure("Built on", { size: SZ }) + gap, y: 19.73, fill: t.fg });
  return svg(w, 30, "Built on Glassworks", body, t);
}

/*
 * The part badges: a drawing cell for the mark (dot grid, diagonals), then
 * "Glassworks <Part>" on a sweep of the part's light. One 1 px edge runs all
 * the way round, neutral along the cell, catching the part's colour across the
 * text and brightening toward the end, like glass lit from inside.
 */

function partBadge(t, id) {
  const p = PART[id];
  const light = t.name === "light";
  const cell = 44;
  const wa = measure("Glassworks", { size: SZ });
  const tw = 12 + wa + SPACE() + measure(p.name, { font: "semibold", size: SZ }) + 14;
  const w = cell + tw;
  const k = p.strength;
  const end = light ? 0.4 : 0.5;
  const [a, b] = p.sweep;
  const [ra, rb] = p.rim[t.name];
  const defs =
    `<linearGradient id="sweep" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${w}" y2="0"><stop offset="0" stop-color="${a}" stop-opacity="${0.08 * k}"/><stop offset=".55" stop-color="${a}" stop-opacity="${0.22 * k}"/><stop offset="1" stop-color="${b}" stop-opacity="${end * k}"/></linearGradient>` +
    `<linearGradient id="rim" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${w}" y2="0"><stop offset="0" stop-color="${ra}"/><stop offset="1" stop-color="${rb}"/></linearGradient>` +
    `<linearGradient id="ramp" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${w - 4}" y2="0"><stop offset="0" stop-color="#fff" stop-opacity=".3"/><stop offset="1" stop-color="#fff"/></linearGradient>` +
    `<mask id="lit-edge" maskUnits="userSpaceOnUse" x="${cell}" y="0" width="${tw}" height="30"><rect x="${cell}" width="${tw}" height="30" fill="url(#ramp)"/></mask>`;
  const pill = `x=".5" y=".5" width="${w - 1}" height="29" rx="14.5"`;
  const body =
    `<defs>${defs}</defs><clipPath id="clip"><rect x="1" y="1" width="${w - 2}" height="28" rx="14"/></clipPath>` +
    `<rect ${pill} fill="${t.bg}"/>` +
    `<g clip-path="url(#clip)"><rect width="${cell}" height="30" fill="url(#dot)"/>${guide(`M0 0L${cell} 30M0 30L${cell} 0M${cell / 2} 0V30M0 15H${cell}`, t, { op: 0.32 })}<rect x="${cell}" width="${tw}" height="30" fill="url(#sweep)"/></g>` +
    guide(`M${cell} 0V30`, t, { op: 0.6, w: 0.6, dash: "2 1.4" }) +
    `<rect ${pill} fill="none" stroke="${light ? "#c9ccd4" : "#3a3d46"}"/>` +
    `<rect ${pill} fill="none" stroke="url(#rim)" mask="url(#lit-edge)"/>` +
    partMark(t, id, cell / 2 - 10, 5, 20) +
    text("Glassworks", { size: SZ, x: cell + 12, y: 19.73, fill: t.mu }) +
    text(p.name, { font: "semibold", size: SZ, x: cell + 12 + wa + SPACE(), y: 19.73, fill: t.fg });
  return svg(w, 30, `Glassworks ${p.name}`, body, t);
}

/* ---------- the mark ---------- */

const LIT_STOPS = ["#38bdf8", "#a78bfa"];
const EMBER_STOPS = ["#fdba74", "#f43f5e"];
const grad = (id, [a, b]) =>
  `<linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient>`;

/** The mark's plates on its 120 × 120 canvas. ink: a colour, or a class name for themed files. */

const plainSvg = (w, h, label, defs, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${label}" viewBox="0 0 ${w.toFixed(2)} ${h.toFixed(2)}" width="${Math.round(w)}" height="${Math.round(h)}"><title>${label}</title>${defs ? `<defs>${defs}</defs>` : ""}${body}</svg>\n`;

/* the wordmark is kept exactly as drawn: read its outline from wordmark.svg */
const WORDMARK = (() => {
  const src = readFileSync(join(out, "wordmark.svg"), "utf8");
  const d = src.match(/ d="([^"]+)"/)[1];
  const [, w, h] = src.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
  const [, tx, ty] = src.match(/translate\(([\d.]+) ([\d.]+)\)/);
  return { d, w: +w, h: +h, tx: +tx, ty: +ty };
})();
// the wordmark's size in Inter Light, found from its width, so "Kiln" can match it
const WORD_SIZE = (() => {
  let size = 30;
  while (measure("Glassworks", { font: "light", size, ls: -0.025 * size }) < WORDMARK.w - 2 * WORDMARK.tx) size += 0.1;
  return size;
})();

function lockup(ink, { kiln = false } = {}) {
  // the stack is lighter than a solid shape, so the mark runs taller than the
  // wordmark; the wordmark sits centred on it
  const H = 52;
  const M = 52; // mark size
  const x = M + 12;
  const wy = (H - WORDMARK.h) / 2 + WORDMARK.ty;
  let body = `<g transform="scale(${(M / 120).toFixed(4)})">${markBody({ ink, lit: "url(#lit)", kiln })}</g>`;
  body += `<path transform="translate(${x + WORDMARK.tx} ${wy.toFixed(2)})" d="${WORDMARK.d}" fill="${ink}"/>`;
  let w = x + WORDMARK.w;
  if (kiln) {
    const kx = w + measure(" ", { font: "light", size: WORD_SIZE });
    body += text("Kiln", { font: "medium", size: WORD_SIZE, ls: -0.01 * WORD_SIZE, x: kx, y: wy, fill: ink });
    w = kx + measure("Kiln", { font: "medium", size: WORD_SIZE, ls: -0.01 * WORD_SIZE }) + 2;
  }
  return plainSvg(w, H, kiln ? "Glassworks Kiln" : "Glassworks", grad("lit", kiln ? EMBER_STOPS : LIT_STOPS), body);
}

/* kiln: Kiln's mark (the ember floor) on Kiln's room light, sky and ember, in place of sky and violet */
function appIcon({ kiln = false, vitro = false } = {}) {
  const [glow, glowOp] = kiln ? ["#f43f5e", ".18"] : ["#a78bfa", ".2"];
  const defs =
    grad("lit", kiln ? EMBER_STOPS : LIT_STOPS) +
    `<radialGradient id="sky" cx=".18" cy=".12" r=".7"><stop offset="0" stop-color="#38bdf8" stop-opacity=".22"/><stop offset="1" stop-color="#38bdf8" stop-opacity="0"/></radialGradient>` +
    `<radialGradient id="vio" cx=".86" cy=".9" r=".7"><stop offset="0" stop-color="${glow}" stop-opacity="${glowOp}"/><stop offset="1" stop-color="${glow}" stop-opacity="0"/></radialGradient>`;
  const body =
    `<rect width="512" height="512" rx="112" fill="#0a0b0f"/><rect width="512" height="512" rx="112" fill="url(#sky)"/><rect width="512" height="512" rx="112" fill="url(#vio)"/>` +
    `<rect x=".5" y=".5" width="511" height="511" rx="111.5" fill="none" stroke="#fff" stroke-opacity=".08"/>` +
    `<g transform="translate(96 96) scale(2.6667)">${vitro ? vitroBody() : markBody({ kiln })}</g>`;
  return plainSvg(512, 512, kiln ? "Glassworks Kiln" : vitro ? "Glassworks Vitro" : "Glassworks", defs, body);
}

/* the favicon follows the OS theme: ink is set by a style block. kiln: the small cut of Kiln's mark */
function favicon({ kiln = false, vitro = false } = {}) {
  // Vitro's mark has no outlined plates, so it needs no ink and looks the same on either theme
  if (vitro) return plainSvg(120, 120, "Glassworks Vitro", grad("lit", LIT_STOPS), vitroBody({ cut: "small" }));
  const style = `<style>.i{stroke:#0c0e13}@media (prefers-color-scheme:dark){.i{stroke:#f4f4f5}}</style>`;
  return plainSvg(120, 120, kiln ? "Glassworks Kiln" : "Glassworks", grad("lit", kiln ? EMBER_STOPS : LIT_STOPS),
    style + markBody({ inkClass: "i", cut: "small", kiln }));
}

/* ---------- info badges ---------- */

/*
 * Status colours only ever mean status (see /brand#colour). The marker matures
 * with the project: a dashed grey ring, an amber α, an amber β, a green dot.
 */
const STATUS_INK = {
  dark: { ready: "#34d399", working: "#fbbf24" },
  light: { ready: "#047857", working: "#b45309" },
};
const GREEK = { alpha: "α", beta: "β" };
const GREEK_SIZE = 12.5;

/** The stage marker at x: its SVG and how much room it takes before the value. */
function statusMarker(t, stage, x) {
  const ink = STATUS_INK[t.name];
  const at = `cx="${(x + 3).toFixed(2)}" cy="15.5"`;
  if (stage === "development")
    return { svg: `<circle ${at} r="2.6" fill="none" stroke="${t.mu}" stroke-width="1.1" stroke-dasharray="1.4 1.2"/>`, w: 11 };
  // planned: the same ring, unbroken, as the site's and the card's planned mark (a hollow ring)
  if (stage === "planned") return { svg: `<circle ${at} r="2.6" fill="none" stroke="${t.mu}" stroke-width="1.1"/>`, w: 11 };
  if (stage === "stable") return { svg: `<circle ${at} r="3" fill="${ink.ready}"/>`, w: 11 };
  const ch = GREEK[stage];
  if (!ch) throw new Error(`unknown status stage: ${stage}`);
  return {
    svg: text(ch, { font: "greek", size: GREEK_SIZE, x, y: 19.6, fill: ink.working }),
    w: measure(ch, { font: "greek", size: GREEK_SIZE }) + 6,
  };
}

/** LABEL VALUE on the same field as the part badges, with a stage marker for status. */
function infoBadge(t, { label, value, status }) {
  const x = 12;
  let vx = x + measure(label, CAPS) + 10;
  let marker = "";
  if (status) {
    const m = statusMarker(t, status, vx);
    marker = m.svg;
    vx += m.w;
  }
  const w = vx + measure(value, CAPS_B) + 13;
  const inner =
    text(label, { ...CAPS, x, y: 19, fill: t.mu }) + marker + text(value, { ...CAPS_B, x: vx, y: 19, fill: t.fg });
  return svg(w, 30, `${label.toLowerCase()}: ${value}`, field(w, 30, t, 15, inner), t);
}

/* ---------- part icons ---------- */

/*
 * For tables and lists at about 24 px: three panel plates in bold 1.4 px lines,
 * spread apart, with the part's own plate filled. Where the filled plate sits
 * (top, middle, bottom) says where the part sits in the stack.
 */
function isoPlatePath(cx, top, W, D, r) {
  const p = (x, y) => [cx + C * (x - y), top + 0.5 * (x + y)];
  const pts = [];
  const corner = (ox, oy, a0) => {
    for (let i = 0; i <= 6; i++) {
      const a = a0 + (i * Math.PI) / 12;
      pts.push(p(ox + r * Math.cos(a), oy + r * Math.sin(a)));
    }
  };
  corner(W - r, r, -Math.PI / 2);
  corner(W - r, D - r, 0);
  corner(r, D - r, Math.PI / 2);
  corner(r, r, Math.PI);
  return "M" + pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join("L") + "Z";
}

const ICON = {
  frame: { label: "Glassworks Frame", level: 2 },
  core: { label: "Glassworks Core", level: 1 },
  pane: { label: "Glassworks Pane", level: 0 },
  kiln: { label: "Glassworks Kiln", level: 2 },
  vitro: { label: "Glassworks Vitro" },
};

function partIcon(t, id) {
  const { label, level } = ICON[id];
  // Vitro isn't a plate of the stack but the plates cast into one: its icon is its mark, at the same size
  if (id === "vitro") {
    const w = 2 + C * 32 + 2;
    const h = 34;
    return svg(w, h, label, vitroIconIn(w, h), t);
  }
  const W = 20, D = 12, r = 2.6, step = 7, top0 = 2, cx = 2 + C * D;
  const fill = { frame: t.fg, core: t.fg, pane: "url(#lit)", kiln: "url(#ember)" }[id];
  const paths = [];
  for (let lv = 2; lv >= 0; lv--) {
    const d = isoPlatePath(cx, top0 + lv * step, W, D, r);
    if (lv !== level) {
      paths.push(`<path d="${d}" fill="none" stroke="${t.fg}" stroke-opacity=".28" stroke-width="1.4" stroke-linejoin="round"/>`);
      continue;
    }
    if (id === "frame") paths.push(`<path d="${d}" fill="${fill}" fill-opacity=".85"/><path d="${d}" fill="none" stroke="${t.fg}" stroke-width="1.4" stroke-linejoin="round"/>`);
    else paths.push(`<path d="${d}" fill="${fill}" fill-opacity="${id === "core" ? 0.55 : 1}"/>`);
  }
  const w = cx + C * W + 2;
  const h = top0 + 2 * step + 0.5 * (W + D) + 2;
  return svg(w, h, label, paths.join(""), t);
}

/* ---------- banners (the part banner itself is in banner.mjs) ---------- */

/** Glassworks itself: finished, so no construction. The whole stack and the lockup. */
function glassworksBanner(t) {
  const light = t.name === "light";
  const s = 0.46;
  const ox = 74 + backCorner(s);
  const at = (i) => 14 + 14 * i; // 0 = panes (top) … 5 = Kiln (bottom)
  const [gx, gy] = project(ox, at(5), s)(100, 60);
  const [sx, sy] = project(ox, at(0), s)(100, 60);
  const box = `x=".5" y=".5" width="${BW - 1}" height="${BH - 1}" rx="16"`;
  const defs =
    `<radialGradient id="skyG"><stop offset="0" stop-color="#38bdf8" stop-opacity="${light ? 0.18 : 0.22}"/><stop offset="1" stop-color="#38bdf8" stop-opacity="0"/></radialGradient>` +
    `<radialGradient id="emG"><stop offset="0" stop-color="${EMBER}" stop-opacity="${light ? 0.2 : 0.3}"/><stop offset="1" stop-color="${EMBER}" stop-opacity="0"/></radialGradient>` +
    `<radialGradient id="vioG" cx=".9" cy=".1" r=".9"><stop offset="0" stop-color="#a78bfa" stop-opacity="${light ? 0.12 : 0.16}"/><stop offset="1" stop-color="#a78bfa" stop-opacity="0"/></radialGradient>`;
  const drawing =
    `<ellipse cx="${sx.toFixed(2)}" cy="${sy.toFixed(2)}" rx="150" ry="80" fill="url(#skyG)"/><ellipse cx="${gx.toFixed(2)}" cy="${(gy + 8).toFixed(2)}" rx="120" ry="44" fill="url(#emG)"/>` +
    plate(t, ox, at(5), s, "ember", { thick: 20, topStroke: EMBER, topOp: 0.8 }) +
    plate(t, ox, at(4), s, "chips", { thick: 16 }) +
    plate(t, ox, at(3), s, "core", { thick: 16 }) +
    plate(t, ox, at(2), s, "core", { thick: 16 }) +
    plate(t, ox, at(1), s, "sockets", { thick: 10 }) +
    plate(t, ox, at(0), s, "lit", { lit: [0, 2, 3, 5], thick: 16 });
  const x = 322;
  const mk = 44;
  const words =
    `<g transform="translate(${x} 52) scale(${(mk / 120).toFixed(4)})">${markBody({ ink: t.fg })}</g>` +
    text("Glassworks", { font: "light", size: 38, ls: -0.9, x: x + mk + 12, y: 89, fill: t.fg }) +
    text("The open core for Home Assistant wall panels.", { size: 12, x: x + 1, y: 124, fill: t.mu }) +
    text("glassworks.dev", { font: "mono", size: 11.5, x: x + 1, y: 143, fill: t.fa });
  return svg(
    BW,
    BH,
    "Glassworks: the open core for Home Assistant wall panels",
    `<defs>${defs}</defs><clipPath id="clip"><rect x="1" y="1" width="${BW - 2}" height="${BH - 2}" rx="15.5"/></clipPath><rect ${box} fill="${t.bg}"/><g clip-path="url(#clip)"><rect width="${BW}" height="${BH}" fill="url(#vioG)"/>${drawing}</g><rect ${box} fill="none" stroke="${t.edge}"/>` +
      words,
    t,
  );
}

function coreBanner(t) {
  const s = 0.62;
  const ox = 50 + backCorner(s);
  const y = 30;
  const gap = 26;
  return partBanner(
    t,
    "core",
    "Glassworks Core",
    cellGuides(t, ox, s, [y, y + gap, y + gap * 2, y + gap + plateHeight(s)]) +
      plate(t, ox, y + gap * 2, s, "ghost") +
      plate(t, ox, y + gap, s, "core") +
      plate(t, ox, y, s, "ghost"),
    { kicker: "GLASSWORKS · CORE", nameA: "Glassworks", nameB: "Core", code: ":glassworks-core", desc: "What every panel needs. No device code." },
  );
}

/** A pane: the panes plate, with one lit pane lifted out of its socket. */
function paneBanner(t) {
  const s = 0.6;
  const ox = 52 + backCorner(s);
  const oy = 70;
  const lift = 70;
  const cell = cellAt(1);
  const top = project(ox, oy, s)(cell[0], cell[1], lift)[1];
  const sky = t.name === "dark" ? "#7dd3fc" : "#0369a1";
  const at = (z) => `matrix(${C * s} ${0.5 * s} ${-C * s} ${0.5 * s} ${ox} ${oy - z * s})`;
  const cellRect = (z, attrs) =>
    `<g transform="${at(z)}"><rect x="${cell[0]}" y="${cell[1]}" width="54" height="40" rx="5" ${attrs} vector-effect="non-scaling-stroke"/></g>`;
  return partBanner(
    t,
    "pane",
    "Glassworks Pane",
    cellGuides(t, ox, s, [top, oy, oy + plateHeight(s)]) +
      plate(t, ox, oy, s, "lit", { lit: [0, 3, 5] }) +
      cellRect(0.5, `fill="${t.bg}" stroke="${sky}" stroke-opacity=".8" stroke-width=".8" stroke-dasharray="3 2.4"`) +
      guide(drops(ox, oy, s, cell, 0, lift - 12), t, { op: 0.5, w: 0.7, dash: "3 2.4", color: sky }) +
      cellRect(lift - 12, `fill="#a78bfa" fill-opacity=".5"`) +
      cellRect(lift, `fill="url(#lit)"`),
    { kicker: "GLASSWORKS · PANE", nameA: "Glassworks", nameB: "Pane", code: "panes/<name>", desc: "A plugin. Declares the hardware it needs." },
  );
}

/** Every library gets the same drawing: one block lifted out of the core plate. */
function libraryBanner(t, lib) {
  const s = 0.6;
  const ox = 52 + backCorner(s);
  const oy = 74;
  const lift = 96;
  const cell = cellAt(2);
  const top = project(ox, oy, s)(cell[0], cell[1], lift)[1];
  return partBanner(
    t,
    "library",
    `${lib.name}, a Glassworks library`,
    cellGuides(t, ox, s, [top, oy, oy + plateHeight(s)]) +
      plate(t, ox, oy, s, "holed", { hole: 2 }) +
      guide(drops(ox, oy, s, cell, 0, lift - 14), t, { op: 0.55, w: 0.7, dash: "3 2.4" }) +
      block(t, ox, oy, s, cell, lift),
    { kicker: "GLASSWORKS · LIBRARY", nameA: lib.name, code: "Standalone · built for Glassworks", desc: lib.description },
  );
}

/**
 * The principles: the whole stack as a plan, with the pane sockets on top. The
 * shape every part has to fit, drawn before anything is lit. No ember: Kiln's
 * colour stays Kiln's.
 */
function principlesBanner(t) {
  const s = 0.5;
  const ox = 70 + backCorner(s);
  const y = 26;
  const gap = 22;
  return partBanner(
    t,
    "principles",
    "Glassworks Principles",
    cellGuides(t, ox, s, [y, y + gap * 3, y + gap * 3 + plateHeight(s)]) +
      plate(t, ox, y + gap * 3, s, "ghost", { op: 0.3 }) +
      plate(t, ox, y + gap * 2, s, "ghost", { op: 0.4, cells: true }) +
      plate(t, ox, y + gap, s, "core", { thick: 14 }) +
      plate(t, ox, y, s, "sockets", { thick: 10 }),
    {
      kicker: "GLASSWORKS · PRINCIPLES",
      nameA: "Glassworks",
      nameB: "Principles",
      code: "docs/principles.md",
      desc: "Home Assistant is the brain. The panel is the hardware.",
    },
  );
}

function kilnBanner(t) {
  const s = 0.55;
  const ox = 56 + backCorner(s);
  const y = 12;
  const gap = 20;
  const [gx, gy] = project(ox, y + gap * 3, s)(100, 60);
  return partBanner(
    t,
    "kiln",
    "Glassworks Kiln",
    cellGuides(t, ox, s, [y, y + gap * 3, y + gap * 3 + plateHeight(s)], "#fb7185") +
      `<ellipse cx="${gx.toFixed(2)}" cy="${(gy + 10).toFixed(2)}" rx="90" ry="34" fill="${EMBER}" opacity=".4" filter="url(#softL)"/>` +
      plate(t, ox, y + gap * 3, s, "ember", { thick: 26, topStroke: EMBER, topOp: 0.8 }) +
      plate(t, ox, y + gap * 2, s, "ghost", { op: 0.45, cells: true }) +
      plate(t, ox, y + gap, s, "ghost", { op: 0.35 }) +
      plate(t, ox, y, s, "ghost", { op: 0.25 }),
    { kicker: "GLASSWORKS · INSTALLER", nameA: "Glassworks", nameB: "Kiln", code: "kiln.glassworks.dev", desc: "Installs a frame over USB, from the browser.", haze: "emberHaze" },
  );
}

/** Vitro's mark (the small cut) centred in a w × h box. */
function vitroIconIn(w, h) {
  const k = Math.min(w, h) / 120;
  return `<g transform="translate(${((w - 120 * k) / 2).toFixed(2)} ${((h - 120 * k) / 2).toFixed(2)}) scale(${k.toFixed(4)})">${vitroBody({ cut: "small" })}</g>`;
}

/** A plate's rounded rect in page coordinates, at height z. */
function isoRect(p, x0, y0, w, h, r, z = 0, seg = 6) {
  const pts = [];
  const corner = (ox, oy, a0) => {
    for (let i = 0; i <= seg; i++) {
      const a = a0 + (i * Math.PI) / 2 / seg;
      pts.push(p(ox + r * Math.cos(a), oy + r * Math.sin(a), z));
    }
  };
  corner(x0 + w - r, y0 + r, -Math.PI / 2);
  corner(x0 + w - r, y0 + h - r, 0);
  corner(x0 + r, y0 + h - r, Math.PI / 2);
  corner(x0 + r, y0 + r, Math.PI);
  return pts;
}
const ptsPath = (pts) => `M${pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join("L")}Z`;
function hull2(points) {
  const P = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lo = [];
  const up = [];
  for (const q of P) {
    while (lo.length >= 2 && cross(lo.at(-2), lo.at(-1), q) <= 0) lo.pop();
    lo.push(q);
  }
  for (const q of P.reverse()) {
    while (up.length >= 2 && cross(up.at(-2), up.at(-1), q) <= 0) up.pop();
    up.push(q);
  }
  return lo.slice(0, -1).concat(up.slice(0, -1));
}

/** A stack plate cast solid: a lit face with the dashboard's cards set in it, on a thick edge. */
function castPlate(ox, oy, s, thick) {
  const p = project(ox, oy, s);
  const face = isoRect(p, 0, 0, 200, 120, 9, 0, 8);
  const edge = hull2([...face, ...isoRect(p, 0, 0, 200, 120, 9, -thick, 8)]);
  const cards = [
    [10, 10, 87, 100, 0.42],
    [103, 10, 87, 47, 0.26],
    [103, 63, 87, 47, 0.26],
  ];
  return (
    `<defs>${VITRO_EDGE}</defs><path d="${ptsPath(edge)}" fill="url(#vitroEdge)"/><path d="${ptsPath(face)}" fill="url(#lit)"/>` +
    cards.map(([x, y, w, h, o]) => `<path d="${ptsPath(isoRect(p, x, y, w, h, 6))}" fill="#fff" fill-opacity="${o}"/>`).join("")
  );
}

/** Vitro: the plates above come down and cast into one lit slab, the dashboard drawn natively. */
function vitroBanner(t) {
  const s = 0.55;
  const ox = 56 + backCorner(s);
  const y = 6;
  const gap = 20;
  const slab = y + gap * 3;
  const [gx, gy] = project(ox, slab, s)(100, 60);
  const p = project(ox, 0, s);
  const corners = [[0, 0], [200, 0], [200, 120], [0, 120]];
  const drop = corners
    .map(([a, b]) => {
      const [x0, y0] = p(a, b);
      return `M${x0.toFixed(2)} ${(y0 + y + gap).toFixed(2)}L${x0.toFixed(2)} ${(y0 + slab - 2).toFixed(2)}`;
    })
    .join("");
  return partBanner(
    t,
    "vitro",
    "Glassworks Vitro",
    cellGuides(t, ox, s, [y, slab, slab + plateHeight(s)]) +
      `<ellipse cx="${gx.toFixed(2)}" cy="${(gy + 6).toFixed(2)}" rx="96" ry="38" fill="#a78bfa" opacity=".35" filter="url(#softL)"/>` +
      plate(t, ox, y, s, "ghost", { op: 0.25 }) +
      plate(t, ox, y + gap, s, "ghost", { op: 0.4 }) +
      guide(drop, t, { op: 0.45, w: 0.7, dash: "3 2.4", color: t.name === "dark" ? "#7dd3fc" : "#0369a1" }) +
      castPlate(ox, slab, s, 22),
    { kicker: "GLASSWORKS · ENGINE", nameA: "Glassworks", nameB: "Vitro", code: "engines/engine-vitro", desc: "Home Assistant dashboards, drawn natively.", haze: "litHaze" },
  );
}

const DISCORD_PATH =
  "M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z";

/** Glassworks-themed Discord invite card (580 × 94) for READMEs. */
function discordCard(t) {
  const W = 580;
  const H = 94;
  const cell = 96;
  const tw = W - cell;
  const light = t.name === "light";
  const [ra, rb] = PART.pane.rim[t.name];
  const defs =
    `<linearGradient id="sweep" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${W}" y2="0"><stop offset="0" stop-color="#38bdf8" stop-opacity="${light ? 0.04 : 0.06}"/><stop offset=".55" stop-color="#38bdf8" stop-opacity="${light ? 0.09 : 0.13}"/><stop offset="1" stop-color="#a78bfa" stop-opacity="${light ? 0.2 : 0.28}"/></linearGradient>` +
    `<linearGradient id="rim" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${W}" y2="0"><stop offset="0" stop-color="${ra}"/><stop offset="1" stop-color="${rb}"/></linearGradient>` +
    `<linearGradient id="ramp" gradientUnits="userSpaceOnUse" x1="${cell}" y1="0" x2="${W - 6}" y2="0"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset="1" stop-color="#fff"/></linearGradient>` +
    `<mask id="lit-edge" maskUnits="userSpaceOnUse" x="${cell}" y="0" width="${tw}" height="${H}"><rect x="${cell}" width="${tw}" height="${H}" fill="url(#ramp)"/></mask>` +
    `<radialGradient id="cellGlow" cx=".5" cy=".5" r=".6"><stop offset="0" stop-color="#38bdf8" stop-opacity="${light ? 0.18 : 0.24}"/><stop offset=".6" stop-color="#a78bfa" stop-opacity="${light ? 0.08 : 0.12}"/><stop offset="1" stop-color="#a78bfa" stop-opacity="0"/></radialGradient>`;

  const box = `x=".5" y=".5" width="${W - 1}" height="${H - 1}" rx="16"`;
  // Left cell: dot grid, faint construction diagonals, glow, glass tile with lit Discord mark
  const tile =
    `<rect x="22" y="21" width="52" height="52" rx="14" fill="${t.bg}" fill-opacity="${light ? 0.92 : 0.88}" stroke="${light ? "#cbd5e1" : "#3a3d46"}"/>` +
    `<rect x="22.5" y="21.5" width="51" height="51" rx="13.5" fill="url(#lit)" fill-opacity="${light ? 0.08 : 0.1}"/>` +
    `<g transform="translate(34 33) scale(1.1667)"><path d="${DISCORD_PATH}" fill="url(#lit)"/></g>`;

  // Middle text block
  const tx = cell + 22;
  const words =
    markIcon(t, tx, 17, 13) +
    text("GLASSWORKS · COMMUNITY", { font: "medium", size: 8.5, ls: 1.7, x: tx + 18, y: 27, fill: t.fa }) +
    text("Join us on Discord", { font: "semibold", size: 17.5, ls: -0.3, x: tx, y: 52, fill: t.fg }) +
    text("Install help, hardware bring-up, Clarity & showcase", { size: 11.5, x: tx, y: 72, fill: t.mu });

  // Right CTA pill
  const ctaLabel = "Join server";
  const ctaTextW = measure(ctaLabel, { font: "medium", size: 11.5 });
  const ctaW = Math.round(16 + ctaTextW + 8 + 10 + 14);
  const ctaX = W - 20 - ctaW;
  const ctaY = 30;
  const ctaH = 34;
  const arrowX = ctaX + 16 + ctaTextW + 8;
  const arrowY = ctaY + ctaH / 2;
  const cta =
    `<rect x="${ctaX}" y="${ctaY}" width="${ctaW}" height="${ctaH}" rx="17" fill="${t.bg}" fill-opacity="${light ? 0.92 : 0.72}" stroke="url(#lit)" stroke-opacity="${light ? 0.85 : 0.9}"/>` +
    text(ctaLabel, { font: "medium", size: 11.5, x: ctaX + 16, y: ctaY + 21, fill: t.fg }) +
    `<path d="M${arrowX.toFixed(1)} ${arrowY}h8.5m-3.2-3.2 3.2 3.2-3.2 3.2" fill="none" stroke="url(#lit)" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>`;

  const body =
    `<defs>${defs}</defs><clipPath id="clip"><rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="15.5"/></clipPath>` +
    `<rect ${box} fill="${t.bg}"/>` +
    `<g clip-path="url(#clip)">` +
    `<rect width="${cell}" height="${H}" fill="url(#dotL)"/>` +
    guide(`M0 0L${cell} ${H}M0 ${H}L${cell} 0M${cell / 2} 0V${H}M0 ${H / 2}H${cell}`, t, { op: 0.24, w: 0.6, dash: "3 3" }) +
    `<rect width="${cell}" height="${H}" fill="url(#cellGlow)"/>` +
    `<rect x="${cell}" width="${tw}" height="${H}" fill="url(#sweep)"/>` +
    `</g>` +
    guide(`M${cell} 0V${H}`, t, { op: 0.55, w: 0.7, dash: "4 3" }) +
    `<rect ${box} fill="none" stroke="${light ? "#c9ccd4" : "#3a3d46"}"/>` +
    `<rect ${box} fill="none" stroke="url(#rim)" mask="url(#lit-edge)"/>` +
    tile +
    words +
    cta;

  return svg(W, H, "Join the Glassworks Discord community", body, t);
}

/* ---------- write ---------- */

mkdirSync(out, { recursive: true });
const written = [];
const write = (file, body) => {
  if (body.includes("NaN")) throw new Error(`${file}: drawing produced NaN coordinates`);
  writeFileSync(join(out, file), body);
  written.push(file);
};

for (const t of Object.values(THEMES)) {
  const on = `on-${t.name}`;
  write(`badge-${on}.svg`, builtOnBadge(t));
  for (const id of Object.keys(PART)) write(`badge-${id}-${on}.svg`, partBadge(t, id));
  for (const b of infoBadges) write(`badge-${b.id}-${on}.svg`, infoBadge(t, b));
  for (const id of Object.keys(ICON)) write(`icon-${id}-${on}.svg`, partIcon(t, id));
  write(`card-discord-${on}.svg`, discordCard(t));
  write(`banner-glassworks-${on}.svg`, glassworksBanner(t));
  write(`banner-core-${on}.svg`, coreBanner(t));
  write(`banner-pane-${on}.svg`, paneBanner(t));
  write(`banner-kiln-${on}.svg`, kilnBanner(t));
  write(`banner-vitro-${on}.svg`, vitroBanner(t));
  write(`banner-principles-${on}.svg`, principlesBanner(t));
  for (const lib of libraries) write(`banner-library-${lib.name}-${on}.svg`, libraryBanner(t, lib));
  for (const f of frames) write(`banner-frame-${f.id}-${on}.svg`, frameBanner(t, f));
}

// A frame that was removed, or lost its tagline, keeps its old banner files: READMEs may still link them.

// the mark's own files
const ink = { dark: "#f4f4f5", light: "#0c0e13" };
write("mark-on-dark.svg", plainSvg(120, 120, "Glassworks", grad("lit", LIT_STOPS), markBody({ ink: ink.dark })));
write("mark-on-light.svg", plainSvg(120, 120, "Glassworks", grad("lit", LIT_STOPS), markBody({ ink: ink.light })));
write("mark-mono.svg", plainSvg(120, 120, "Glassworks", "", markBody({ ink: "currentColor", lit: "currentColor" })));
write("kiln-on-dark.svg", plainSvg(120, 120, "Glassworks Kiln", grad("lit", EMBER_STOPS), markBody({ ink: ink.dark, kiln: true })));
write("lockup-on-dark.svg", lockup(ink.dark));
write("lockup-on-light.svg", lockup(ink.light));
write("lockup-kiln-on-dark.svg", lockup(ink.dark, { kiln: true }));
// Vitro's mark has no outlined plates, so one file serves both themes
write("vitro.svg", plainSvg(120, 120, "Glassworks Vitro", grad("lit", LIT_STOPS), vitroBody()));
write("app-icon.svg", appIcon());
write("app-icon-kiln.svg", appIcon({ kiln: true }));
write("app-icon-vitro.svg", appIcon({ vitro: true }));
write("favicon.svg", favicon());
write("favicon-kiln.svg", favicon({ kiln: true }));
write("favicon-vitro.svg", favicon({ vitro: true }));
writeFileSync(join(root, "src/app/icon.svg"), favicon());
written.push("../../src/app/icon.svg");

console.log(`Wrote ${written.length} files to public/brand/:\n  ${written.join("\n  ")}`);
