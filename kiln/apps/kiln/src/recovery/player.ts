// The recovery-mode player from docs/design/kiln/recovery/player.js, typed. It draws a frame's device
// face down at the brand's 30° angle and animates the steps into recovery mode from data alone, so
// every frame gets this without its own art. Moves: screws (in the back or a side face, and a stand
// that comes off with its screws), cover, hold, plug, power (a switch in a side face); between them it
// adds its own transitions (light over the board, a spotlight on the button, then on the port).
// Buttons, the plug and the switch are drawn to the device's scale. On a big device that makes them
// small, so the camera goes in close for them, and pulls back between two close-ups far apart.
import { MATERIAL as M } from './materials';

type Mm = [number, number];
interface Rect { kind: string; at: Mm; size: Mm }
/** `[x, y]` on the back, or `[x, y, z]` in a side face the drawing shows (`x = w` or `y = h`), `z` mm down from the back. */
export type Screw = [x: number, y: number, z?: number];
/** A side face the drawing shows: `right` is `x = w`, `bottom` is `y = h`. */
type Shown = 'right' | 'bottom';

export interface RecoveryData {
  frame: string;
  name: string;
  body: { w: number; h: number; thickness: number; radius?: number };
  cover?: { screws: Screw[]; features: Rect[] };
  /**
   * A stand fixed to a side face by the side screws in that face. It comes off once they're out.
   * `depth`: its bar on that face, mm out from it. `tube`: a U of tube from the bar's two ends, `inset` mm
   * in from the corners and `z` mm down from the back, out to `reach` mm from the face, its corners bent
   * to `bend` mm.
   */
  stand?: { edge: Shown; depth: number; tube?: { reach: number; inset: number; z: number; diameter: number; bend: number } };
  board?: (Rect | { kind: 'window'; at: Mm; r: number } | { kind: 'button'; at: Mm })[];
  button?: { at: Mm; what: string };
  port?: { edge: 'top' | 'bottom' | 'left' | 'right'; at: number; kind: string };
  /** A power switch in a side face, `at` mm along it. The `power` move flips it on. */
  power?: { edge: Shown; at: number; kind: string };
  steps: ('screws' | 'cover' | 'hold' | 'plug' | 'power')[];
  ready?: { seconds: number; says: string };
  labels?: Partial<Record<string, string>>;
}

export const LABELS: Record<string, string> = { screws: 'Take out the screws', cover: 'Remove the back cover', hold: 'Hold the button', plug: 'Plug in the USB cable', power: 'Switch the power on' };

const C = Math.cos(Math.PI / 6);
const SN = 0.5;
const S = 0.78;
const VB = { w: 640, h: 400 };
/** Pixels per mm at and above which parts draw at full size; the S1 draws at 2.36, about real size. */
const FULL = 2.3;

const clamp = (v: number) => Math.max(0, Math.min(1, v));
const seg = (t: number, a: number, b: number) => (b <= a ? (t >= b ? 1 : 0) : clamp((t - a) / (b - a)));
const ease = (x: number) => x * x * (3 - 2 * x);
const outBack = (x: number) => 1 + 2.5 * Math.pow(x - 1, 3) + 1.5 * Math.pow(x - 1, 2);
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;

/** The drawing's scale, `k` px per mm, and `g`, how much smaller than full size parts are drawn. */
function fit(f: RecoveryData) {
  const st = f.stand;
  const out = st ? Math.max(st.depth, st.tube?.reach ?? 0) : 0;
  const w = f.body.w + (st?.edge === 'right' ? out : 0), h = f.body.h + (st?.edge === 'bottom' ? out : 0);
  const k = Math.min(520 / w, 300 / h);
  return { k, g: Math.min(1, (k * S) / FULL) };
}

/** Whether a screw is one of the stand's: in the face the stand is fixed to. */
const onStand = (f: RecoveryData, [x, y, z]: Screw) => !!f.stand && z !== undefined && (f.stand.edge === 'right' ? x >= f.body.w : y >= f.body.h);

interface Span { start: number; end: number }
interface Timeline {
  beats: [number, number][];
  screws?: { starts: number[]; dur: number };
  stand?: Span;
  cover?: Span; reveal?: Span; focus?: Span; hold?: Span; toPort?: Span; plug?: Span; click?: Span;
  toSwitch?: Span; flip?: Span; back?: Span;
  ready?: { start: number };
  out: number;
  T: number;
}

/** The timeline, built from the frame's steps. Each move gets a window; transitions are folded in. */
export function timeline(f: RecoveryData): Timeline {
  const tl: Timeline = { beats: [], out: 0, T: 0 };
  const far = fit(f).g < 1;
  let close = false; // the camera is in close on something
  const pan = () => (far && close ? 1.4 : 0.8);
  const powers = f.steps.includes('power') ? 'power' : 'plug';
  // A short wait plays faster, as the S1's always has; a longer hold plays in real time, so the
  // animation never says "let go" before the device is ready (the Mirror's 5 s).
  const seconds = f.ready?.seconds ?? 2;
  const wait = seconds <= 2 ? seconds * 0.4 + 0.2 : seconds;
  let t = 0.3;
  for (const step of f.steps) {
    const start = t;
    if (step === 'screws') {
      const screws = f.cover?.screws ?? [];
      const last = screws.map((s) => onStand(f, s)).lastIndexOf(true);
      let shift = 0;
      if (f.stand) {
        const from = last >= 0 ? t + last * 0.32 + 0.6 : t;
        tl.stand = { start: from, end: from + 0.8 };
        shift = tl.stand.end - (t + (last + 1) * 0.32);
      }
      tl.screws = { starts: screws.map((_, i) => t + i * 0.32 + (i > last ? shift : 0)), dur: 0.6 };
      t += screws.length * 0.32 + 0.6 + shift;
    } else if (step === 'cover') {
      tl.cover = { start: t, end: t + 1.6 };
      tl.reveal = { start: t + 1.4, end: t + 2.2 };
      t += 1.8;
    } else if (step === 'hold') {
      const d = pan();
      tl.focus = { start: t, end: t + d };
      tl.hold = { start: t + d - 0.2, end: 0 };
      t += d + 1.8;
      close = true;
    } else if (step === 'plug') {
      const d = pan();
      tl.toPort = { start: t, end: t + d };
      tl.plug = { start: t + d, end: t + d + 0.7 };
      tl.click = { start: t + d + 0.65, end: t + d + 1.2 };
      t += d + 0.7;
      close = true;
    } else if (step === 'power') {
      const d = pan();
      tl.toSwitch = { start: t, end: t + d };
      tl.flip = { start: t + d, end: t + d + 0.5 };
      t += d + 0.5;
      close = true;
      // Back to the button that's still held, while the device starts.
      if (far && f.button) tl.back = { start: t + 0.4, end: t + 1.8 };
    }
    if (step === powers) {
      tl.ready = { start: t + wait };
      t += wait + 1.3;
    }
    tl.beats.push([start, t]);
  }
  if (tl.hold) tl.hold.end = tl.ready ? tl.ready.start : t;
  tl.out = t;
  tl.T = t + 0.6;
  return tl;
}

export interface Player { draw(t: number): void; play(): void; stop(): void; duration: number; beat(t: number): number }

/** Where the camera looks: `f` (pre-zoom) shows at `a` on screen, zoomed `z`; a spotlight of radius `r`, dimming `dim`. */
interface Cam { f: Mm; a: Mm; z: number; r: number; dim: number }

/** Draws `f` into `svg`. `onBeat` hears which step is playing (for the step chips). */
export function mount(svg: SVGSVGElement, f: RecoveryData, onBeat?: (i: number) => void): Player {
  const W = f.body.w, H = f.body.h;
  const { k, g } = fit(f);
  const s = S;
  const u = (mm: number) => mm * k;
  const Wu = u(W), Hu = u(H);
  const st = f.stand;
  const Du = st ? u(st.depth) : 0;
  const Ou = st ? u(Math.max(st.depth, st.tube?.reach ?? 0)) : 0;
  const Wx = Wu + (st?.edge === 'right' ? Ou : 0), Hx = Hu + (st?.edge === 'bottom' ? Ou : 0);
  const depth = f.body.thickness * k * s * 0.4;
  const proj0 = (x: number, y: number) => [C * s * (x - y), SN * s * (x + y)] as const;
  const cs = [proj0(0, 0), proj0(Wx, 0), proj0(Wx, Hx), proj0(0, Hx)];
  const xs = cs.map((c) => c[0]), ys = cs.map((c) => c[1]);
  const OX = VB.w / 2 - (Math.min(...xs) + Math.max(...xs)) / 2;
  const OY = VB.h / 2 + 14 - (Math.min(...ys) + Math.max(...ys) + depth) / 2;
  const P = (x: number, y: number): Mm => [OX + C * s * (x - y), OY + SN * s * (x + y)];
  const MX = `matrix(${C * s} ${SN * s} ${-C * s} ${SN * s} ${OX} ${OY})`;
  const R = u(f.body.radius ?? 3);
  // A side face's own axes on screen, per mm: along the face, and down from the back.
  const face = (edge: Shown) => (edge === 'right' ? [-C * s * k, SN * s * k] : [C * s * k, SN * s * k]) as Mm;
  const down = 0.4 * s * k;

  const BTN = f.button ? (f.button.at.map(u) as Mm) : null;
  // Each screw's place on screen, and which way it comes out: up from the back, or out of its side face
  // (a stand's screws from the stand's outer face).
  const SCREWS = (f.cover?.screws ?? []).map((sc) => {
    const [x, y, z] = sc;
    const out = onStand(f, sc) ? Du : 0;
    const [X, Y] = P(u(x) + (st?.edge === 'right' ? out : 0), u(y) + (st?.edge === 'bottom' ? out : 0));
    if (z === undefined) return { back: [u(x), u(y)] as Mm, at: [X, Y] as Mm, out: [0, -16] as Mm, stand: false };
    const [nx, ny] = x >= W ? [1, 0] : [0, 1];
    return { at: [X, Y + u(z) * s * 0.4] as Mm, out: [16 * C * (nx - ny), 16 * SN * (nx + ny)] as Mm, stand: out > 0 };
  });

  let PORT: { top: Mm; at: Mm; angle: number; visible: boolean } | null = null;
  if (f.port) {
    const a = u(f.port.at);
    const edge = { top: [a, 0, -30, false], bottom: [a, Hu, 150, true], left: [0, a, -150, false], right: [Wu, a, 30, true] }[f.port.edge] as [number, number, number, boolean];
    const [x, y, angle, visible] = edge;
    const [px, py] = P(x, y);
    PORT = { top: [px, py], at: [px, py + depth / 2], angle, visible };
  }
  const SW = f.power ? (() => {
    const [px, py] = f.power.edge === 'right' ? P(Wu, u(f.power.at)) : P(u(f.power.at), Hu);
    return [px, py + depth / 2] as Mm;
  })() : null;

  const rect = (r: Rect, extra: string) => `<rect x="${u(r.at[0])}" y="${u(r.at[1])}" width="${u(r.size[0])}" height="${u(r.size[1])}" rx="${u(1)}" ${extra}/>`;
  const cap = ([x, y]: Mm, id = '') => `<rect x="${x - 6 * g}" y="${y - 6 * g}" width="${12 * g}" height="${12 * g}" rx="${2 * g}" fill="${M.buttonCap}"/><circle ${id} cx="${x}" cy="${y}" r="${3.6 * g}" style="fill:${M.button}"/>`;
  const board = (f.board ?? []).map((b) => {
    if (b.kind === 'button') return cap(b.at.map(u) as Mm);
    if (b.kind === 'window' && 'r' in b) return `<circle cx="${u(b.at[0])}" cy="${u(b.at[1])}" r="${u(b.r)}" fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".14"/>`;
    const r = b as Rect;
    if (r.kind === 'shield') return rect(r, `style="fill:var(--s-fg);stroke:var(--s-fg)" fill-opacity=".1" stroke-opacity=".15"`);
    if (r.kind === 'speaker') return rect(r, `fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".1"`);
    return rect(r, `style="fill:var(--s-fg)" fill-opacity=".08"`);
  }).join('');
  const edge = 'style="stroke:var(--s-fg)" stroke-opacity=".1"';
  const coverArt = (f.cover?.features ?? []).map((c) => {
    if (c.kind === 'ribs') return rect(c, 'fill="url(#kr-rib)"');
    if (c.kind === 'plate') return rect(c, `fill="${M.plate}" ${edge}`);
    if (c.kind === 'hole') return `<ellipse cx="${u(c.at[0])}" cy="${u(c.at[1])}" rx="${u(c.size[0]) / 2}" ry="${u(c.size[1]) / 2}" fill="${M.hole}" ${edge}/>`;
    if (c.kind === 'clip') return rect(c, `fill="${M.clip}"`);
    if (c.kind === 'grille') return rect(c, `fill="url(#kr-grille)" ${edge}`);
    if (c.kind === 'recess') return rect(c, `fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".2"`);
    return '';
  }).join('');

  const plug = PORT
    ? `<g transform="translate(${PORT.at[0]} ${PORT.at[1]}) rotate(${PORT.angle})${g < 1 ? ` scale(${g})` : ''}"><clipPath id="kr-out"><rect x="0" y="-60" width="400" height="120"/></clipPath>
       <path id="kr-guide" d="M8 0H8" style="stroke:var(--ember-a)" stroke-opacity=".55" stroke-width="1" stroke-dasharray="4 4" fill="none"/>
       <g clip-path="url(#kr-out)"><g id="kr-plug">
         <path d="M42 0 C 87 0, 112 26, 182 40" stroke="${M.cable}" stroke-width="9" fill="none" stroke-linecap="round"/>
         <path d="M30 -4.5 L42 -3.6 L42 3.6 L30 4.5 Z" fill="${M.boot}"/>
         <rect x="-10" y="-3.2" width="11" height="6.4" rx="1.6" fill="${M.pins}"/>
         <rect x="0" y="-6.5" width="31" height="13" rx="3.5" fill="url(#kr-plugG)" style="stroke:var(--s-fg)" stroke-opacity=".25" stroke-width=".6"/>
       </g></g></g>`
    : '';
  const sideHalo = ([X, Y]: Mm) => `<circle class="kr-halo" cx="${X}" cy="${Y}" r="9" fill="none" style="stroke:var(--ember-b)" stroke-width="1.5"/>`;
  // The stand: a block on its face, as deep as the body, drawn like the body (top, and its side below).
  const standRect = st ? (st.edge === 'right' ? `x="${Wu}" y="0" width="${Du}" height="${Hu}"` : `x="0" y="${Hu}" width="${Wu}" height="${Du}"`) : '';
  // Its tube, as a line through points on screen: out along the face's normal, across along the face.
  const tube = st?.tube ? (() => {
    const { reach, inset, z, diameter, bend } = st.tube!;
    const across = st.edge === 'right' ? H : W;
    const at = (o: number, a: number): Mm => {
      const [x, y] = st.edge === 'right' ? P(Wu + u(o), u(a)) : P(u(a), Hu + u(o));
      return [x, y + u(z) * s * 0.4];
    };
    const arc = (co: number, ca: number, from: number, to: number) => Array.from({ length: 9 }, (_, i) => {
      const th = from + ((to - from) * i) / 8;
      return at(co + bend * Math.cos(th), ca + bend * Math.sin(th));
    });
    const pts = [
      at(st.depth, inset), ...arc(reach - bend, inset + bend, -Math.PI / 2, 0),
      ...arc(reach - bend, across - inset - bend, 0, Math.PI / 2), at(st.depth, across - inset),
    ];
    const d = 'M' + pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join('L');
    const wd = u(diameter) * s;
    return `<path d="${d}" fill="none" stroke="${M.tube}" stroke-width="${wd}" stroke-linejoin="round" stroke-linecap="round"/>
      <path d="${d}" fill="none" style="stroke:var(--s-fg)" stroke-opacity=".22" stroke-width="${wd * 0.3}" transform="translate(0 ${-wd * 0.25})" stroke-linejoin="round"/>`;
  })() : '';
  const stand = st
    ? `<g id="kr-stand"><g transform="translate(0 ${depth})"><g transform="${MX}"><rect ${standRect} rx="${R}" fill="${M.caseSide}" style="stroke:var(--s-fg)" stroke-opacity=".16"/></g></g>
       <g transform="${MX}"><rect ${standRect} rx="${R}" fill="${M.stand}" style="stroke:var(--s-fg)" stroke-opacity=".3"/></g>${tube}
       ${SCREWS.filter((x) => x.stand).map((x) => sideHalo(x.at)).join('')}</g>`
    : '';
  // A rocker switch in its face, drawn in mm on the face's own axes; its raised half moves when it flips.
  const switchArt = SW && f.power
    ? (() => {
      const [ax, ay] = face(f.power.edge);
      return `<g transform="matrix(${ax} ${ay} 0 ${down} ${SW[0]} ${SW[1]})">
        <rect x="-11" y="-8" width="22" height="16" rx="2" fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".3" stroke-width=".8"/>
        <rect x="-9" y="-6" width="18" height="12" rx="1.5" fill="${M.button}"/>
        <rect id="kr-rocker" x="-9" y="-6" width="9" height="12" rx="1.5" fill="${M.buttonCap}"/></g>`;
    })()
    : '';

  const [bx, by] = BTN ? P(...BTN) : [VB.w / 2, VB.h / 2];
  const says = f.ready?.says ?? 'Ready.';
  svg.setAttribute('viewBox', `0 0 ${VB.w} ${VB.h}`);
  svg.innerHTML = `
<defs>
  <linearGradient id="kr-case" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${M.caseA}"/><stop offset="1" stop-color="${M.caseB}"/></linearGradient>
  <pattern id="kr-rib" width="5" height="10" patternUnits="userSpaceOnUse"><rect width="5" height="10" fill="${M.rib}"/><rect width="1.6" height="10" fill="${M.ribLine}"/></pattern>${f.cover?.features.some((c) => c.kind === 'grille') ? `
  <pattern id="kr-grille" width="${u(7)}" height="${u(7)}" patternUnits="userSpaceOnUse"><rect width="${u(7)}" height="${u(7)}" fill="${M.plate}"/><circle cx="${u(3.5)}" cy="${u(3.5)}" r="${u(2)}" fill="${M.dark}"/></pattern>` : ''}
  <linearGradient id="kr-plugG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${M.plugA}"/><stop offset=".5" stop-color="${M.plugB}"/><stop offset="1" stop-color="${M.plugC}"/></linearGradient>
  <linearGradient id="kr-sweepG" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${M.sweep}" stop-opacity="0"/><stop offset=".5" stop-color="${M.sweep}" stop-opacity=".09"/><stop offset="1" stop-color="${M.sweep}" stop-opacity="0"/></linearGradient>
  <radialGradient id="kr-spot" gradientUnits="userSpaceOnUse" cx="${bx}" cy="${by}" r="80"><stop offset=".45" style="stop-color:var(--night)" stop-opacity="0"/><stop offset="1" style="stop-color:var(--night)" stop-opacity=".78"/></radialGradient>
  <radialGradient id="kr-warm" cx=".5" cy=".5" r=".5"><stop offset="0" style="stop-color:var(--ember-b)" stop-opacity=".14"/><stop offset="1" style="stop-color:var(--ember-b)" stop-opacity="0"/></radialGradient>
  <clipPath id="kr-board"><rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}"/></clipPath>
</defs>
<rect width="${VB.w}" height="${VB.h}" style="fill:var(--night)"/>
<ellipse cx="${VB.w / 2}" cy="${VB.h * 0.58}" rx="300" ry="170" fill="url(#kr-warm)"/>
<g id="kr-all"><g id="kr-zoom">
  ${PORT && !PORT.visible ? plug : ''}
  <g transform="translate(0 ${depth})"><g transform="${MX}"><rect width="${Wu}" height="${Hu}" rx="${R}" fill="${M.caseSide}" style="stroke:var(--s-fg)" stroke-opacity=".16"/></g></g>
  <g transform="${MX}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" style="stroke:var(--s-fg)" stroke-opacity=".22"/>
    <rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}" fill="${M.boardBed}" style="stroke:var(--s-fg)" stroke-opacity=".08"/>
    <g clip-path="url(#kr-board)">${board}<rect id="kr-sweep" x="-200" y="-60" width="160" height="${Hu + 120}" fill="url(#kr-sweepG)" transform="rotate(20 ${Wu / 2} ${Hu / 2})"/></g>
    ${BTN ? `${[0, 1, 2].map((i) => `<circle id="kr-r${i}" cx="${BTN[0]}" cy="${BTN[1]}" r="${10 * g}" fill="none" style="stroke:var(--ember-b)" stroke-width="${2 * g}"/>`).join('')}
    ${cap(BTN, 'id="kr-btn"')}` : ''}
  </g>
  ${switchArt}
  ${PORT ? `<ellipse id="kr-portGlow" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="${16 * g}" ry="${9 * g}" fill="none" style="stroke:var(--ember-b)" stroke-width="${1.6 * g}"/>
  <ellipse id="kr-click" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="${9 * g}" ry="${5 * g}" fill="none" style="stroke:var(--ember-a)" stroke-width="${1.5 * g}"/>` : ''}
  ${SW ? `<ellipse id="kr-swGlow" cx="${SW[0]}" cy="${SW[1]}" rx="${16 * g}" ry="${9 * g}" fill="none" style="stroke:var(--ember-b)" stroke-width="${1.6 * g}" opacity="0"/>
  <ellipse id="kr-swClick" cx="${SW[0]}" cy="${SW[1]}" rx="${9 * g}" ry="${5 * g}" fill="none" style="stroke:var(--ember-a)" stroke-width="${1.5 * g}" opacity="0"/>` : ''}
  ${PORT && PORT.visible ? plug : ''}
  ${f.cover ? `<g id="kr-cover"><g transform="${MX}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" style="stroke:var(--s-fg)" stroke-opacity=".28"/>${coverArt}
    ${SCREWS.map(({ back }) => back ? `<circle class="kr-halo" cx="${back[0]}" cy="${back[1]}" r="12" fill="none" style="stroke:var(--ember-b)" stroke-width="1.5"/>` : '').join('')}
  </g></g>
  ${stand}
  <g>${SCREWS.map((x) => x.back || x.stand ? '' : sideHalo(x.at)).join('')}${SCREWS.map(({ at: [X, Y] }, i) => `<g id="kr-s${i}"><ellipse cx="${X}" cy="${Y}" rx="6.5" ry="4" fill="${M.screw}" stroke="${M.screwEdge}"/><path id="kr-slot${i}" d="M${X - 4} ${Y}H${X + 4}" stroke="${M.slot}" stroke-width="1.4"/></g>`).join('')}</g>` : stand}
  <rect id="kr-spotRect" x="${-2 * VB.w}" y="${-2 * VB.h}" width="${5 * VB.w}" height="${5 * VB.h}" fill="url(#kr-spot)"/>
</g>
<g id="kr-hold" opacity="0"><rect x="0" y="-16" width="132" height="32" rx="16" style="fill:var(--night);stroke:var(--ember-a)" fill-opacity=".92" stroke-opacity=".45"/><circle cx="16" cy="0" r="4" style="fill:var(--ember-a)"/><text x="28" y="4.5" font-family="var(--f-sans)" font-size="12.5" style="fill:var(--s-fg)">Keep holding</text></g>
<g id="kr-ready" opacity="0"><rect x="0" y="-16" width="${36 + 6.6 * says.length}" height="32" rx="16" style="fill:var(--night);stroke:var(--green)" fill-opacity=".92" stroke-opacity=".5"/><circle cx="16" cy="0" r="4" style="fill:var(--green)"/><text x="28" y="4.5" font-family="var(--f-sans)" font-size="12.5" style="fill:var(--s-fg)">${says}</text></g>
</g>`;

  const tl = timeline(f);
  const $ = (id: string) => svg.querySelector('#kr-' + id);
  const set = (id: string, a: string, v: string | number) => { $(id)?.setAttribute(a, String(v)); };
  const never: Span = { start: 1e9, end: 1e9 };
  const beat = (t: number) => tl.beats.findIndex(([a, e]) => t >= a && t < e);
  let lastBeat = -2;

  // The camera: where it starts, then each move to a close-up, in order. Full-size parts zoom a little
  // around what they look at (the S1); small ones zoom in to full size and put it in the middle.
  const CEN: Mm = [VB.w / 2, VB.h / 2];
  const zoom = 1 / g;
  const closeUp = (p: Mm, z: number, r: number): Cam => (g < 1 ? { f: p, a: CEN, z: z * zoom, r: r * g, dim: 1 } : { f: p, a: p, z, r, dim: 1 });
  const cam0: Cam = { f: [bx, by], a: [bx, by], z: 1, r: 700, dim: 0 };
  const moves: { span: Span; to: Cam }[] = [];
  if (tl.focus) moves.push({ span: tl.focus, to: closeUp([bx, by], 1.16, 90) });
  if (tl.toPort && PORT) moves.push({ span: tl.toPort, to: closeUp(g < 1 || !BTN ? PORT.top : [(bx + PORT.top[0]) / 2, (by + PORT.top[1]) / 2], 1.1, 190) });
  if (tl.toSwitch && SW) moves.push({ span: tl.toSwitch, to: closeUp(SW, 1.1, 150) });
  if (tl.back) moves.push({ span: tl.back, to: closeUp([bx, by], 1.16, 90) });
  moves.sort((a, b) => a.span.start - b.span.start);
  const last = moves.length ? moves[moves.length - 1]!.to : cam0;
  moves.push({ span: { start: tl.out - 0.8, end: tl.out }, to: { ...last, a: last.f, z: 1, dim: 0 } });
  const mix = (c: Cam, d: Cam, e: number): Cam => {
    // Between two close-ups far apart, pull back halfway, so the move reads as a move.
    const pull = g < 1 && c.z > 1 && d.z > 1 ? 1 - (1 - 1.4 / ((c.z + d.z) / 2)) * Math.sin(Math.PI * e) : 1;
    return {
      f: [lerp(c.f[0], d.f[0], e), lerp(c.f[1], d.f[1], e)], a: [lerp(c.a[0], d.a[0], e), lerp(c.a[1], d.a[1], e)],
      z: lerp(c.z, d.z, e) * pull, r: lerp(c.r, d.r, e), dim: lerp(c.dim, d.dim, e),
    };
  };
  const camera = (t: number) => moves.reduce((c, m) => mix(c, m.to, ease(seg(t, m.span.start, m.span.end))), cam0);

  function draw(t: number) {
    const sc = tl.screws;
    SCREWS.forEach(({ at: [X, Y], out }, i) => {
      const kk = sc ? ease(seg(t, sc.starts[i]!, sc.starts[i]! + sc.dur)) : 1;
      set('s' + i, 'transform', `translate(${out[0] * kk} ${out[1] * kk})`);
      set('s' + i, 'opacity', (1 - kk).toFixed(3));
      set('slot' + i, 'transform', `rotate(${-540 * kk} ${X} ${Y})`);
    });
    if (st) {
      const e = ease(seg(t, (tl.stand ?? never).start, (tl.stand ?? never).end));
      const [dx, dy] = st.edge === 'right' ? [C, SN] : [-C, SN];
      set('stand', 'transform', `translate(${dx * 60 * e} ${dy * 60 * e})`);
      set('stand', 'opacity', (1 - e).toFixed(3));
    }
    const coverStart = tl.cover ? tl.cover.start : 0;
    svg.querySelectorAll('.kr-halo').forEach((h) => h.setAttribute('opacity', sc && t < coverStart ? '0.8' : '0'));
    if (tl.cover) {
      const c = ease(seg(t, tl.cover.start, tl.cover.end));
      set('cover', 'transform', `translate(${40 * c} ${-110 * c})`);
      set('cover', 'opacity', (1 - c).toFixed(3));
    }
    const rv = tl.reveal ?? never;
    set('sweep', 'x', lerp(-200, Wu + 40, ease(seg(t, rv.start, rv.end))).toFixed(1));
    const cam = camera(t);
    const { f: [fx, fy], a: [ax, ay], z } = cam;
    const spot = $('spot')!;
    spot.setAttribute('cx', fx.toFixed(1)); spot.setAttribute('cy', fy.toFixed(1));
    spot.setAttribute('r', cam.r.toFixed(1));
    set('spotRect', 'opacity', cam.dim.toFixed(3));
    set('zoom', 'transform', `translate(${ax} ${ay}) scale(${z.toFixed(4)}) translate(${-fx} ${-fy})`);
    const hd = tl.hold ?? never;
    const holding = t > hd.start && t < hd.end;
    [0, 1, 2].forEach((i) => {
      const ph = ((((t - hd.start) / 1.5 + i / 3) % 1) + 1) % 1; // also before the hold starts, so r stays positive
      set('r' + i, 'r', ((8 + 30 * ph) * g).toFixed(1));
      set('r' + i, 'opacity', holding ? (0.9 * (1 - ph)).toFixed(3) : 0);
    });
    ($('btn') as SVGElement | null)?.style.setProperty('fill', holding ? 'var(--ember-b)' : M.button);
    const tp = tl.toPort ?? never;
    if (PORT && tl.plug && tl.click) {
      const gd = ease(seg(t, tp.start + 0.1, tp.end));
      set('guide', 'd', `M8 0H${(8 + 122 * gd).toFixed(1)}`);
      const pl = tl.plug;
      set('guide', 'opacity', t > tp.start && t < pl.start + 0.5 ? 1 - seg(t, pl.start + 0.2, pl.start + 0.5) : 0);
      set('portGlow', 'opacity', t > tp.start + 0.1 && t < pl.start + 0.55 ? (0.35 + 0.6 * (Math.sin((t - tp.start) * 7) + 1) / 2).toFixed(3) : 0);
      const pp = seg(t, pl.start, pl.end);
      const d = t < pl.start - 0.3 ? 170 : t < pl.start ? lerp(170, 120, ease(seg(t, pl.start - 0.3, pl.start))) : 120 * (1 - outBack(pp));
      set('plug', 'transform', `translate(${d.toFixed(2)} 0)`);
      const out = ease(seg(t, tl.out - 0.8, tl.out));
      set('plug', 'opacity', (t > pl.start - 0.3 && t < tl.T ? ease(seg(t, pl.start - 0.3, pl.start + 0.3)) * (1 - out) : 0).toFixed(3));
      const kc = seg(t, tl.click.start, tl.click.end);
      const [cx, cy] = PORT.top, scl = 1 + 2.4 * kc;
      set('click', 'transform', `translate(${cx} ${cy}) scale(${scl}) translate(${-cx} ${-cy})`);
      set('click', 'opacity', t > tl.click.start && t < tl.click.end ? (1 - kc).toFixed(3) : 0);
    }
    if (SW && tl.toSwitch && tl.flip) {
      const ts = tl.toSwitch, fl = tl.flip;
      set('swGlow', 'opacity', t > ts.start + 0.1 && t < fl.start + 0.1 ? (0.35 + 0.6 * (Math.sin((t - ts.start) * 7) + 1) / 2).toFixed(3) : 0);
      const on = ease(seg(t, fl.start, fl.start + 0.15));
      set('rocker', 'x', (-9 + 9 * on).toFixed(2));
      ($('rocker') as SVGElement | null)?.style.setProperty('fill', on > 0.5 ? 'var(--ember-b)' : M.buttonCap);
      const kc = seg(t, fl.start + 0.1, fl.end);
      const [cx, cy] = SW, scl = 1 + 2.4 * kc;
      set('swClick', 'transform', `translate(${cx} ${cy}) scale(${scl}) translate(${-cx} ${-cy})`);
      set('swClick', 'opacity', t > fl.start + 0.1 && t < fl.end ? (1 - kc).toFixed(3) : 0);
    }
    const lx = ax + (bx - fx) * z + 46, ly = ay + (by - fy) * z - 56;
    set('hold', 'transform', `translate(${lx} ${ly})`);
    set('ready', 'transform', `translate(${lx} ${ly})`);
    set('hold', 'opacity', holding ? 1 : 0);
    set('ready', 'opacity', tl.ready && t >= tl.ready.start && t < tl.out - 0.2 ? 1 : 0);
    set('all', 'opacity', (t < 0.4 ? seg(t, 0, 0.4) : 1 - seg(t, tl.out, tl.T)).toFixed(3));
    const b = beat(t);
    if (b !== lastBeat) { lastBeat = b; onBeat?.(b); }
  }

  let raf = 0;
  /** Loops; with reduced motion it shows one still frame (the ready moment). */
  function play() {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) { draw(tl.ready ? tl.ready.start - 0.4 : tl.T / 2); return; }
    const start = performance.now();
    const loop = (now: number) => { draw(((now - start) / 1000) % tl.T); raf = requestAnimationFrame(loop); };
    raf = requestAnimationFrame(loop);
  }
  draw(0.5);
  return { draw, play, stop: () => cancelAnimationFrame(raf), duration: tl.T, beat };
}
