// @vitest-environment jsdom
// Each frame's recovery data (frames/<id>/design/recovery.json, fields in docs/design/kiln/README.md)
// is complete and drawable. The S1's design prototype keeps its own copy (with a `photo` path for its
// preview page); the two must not drift apart.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { LABELS, mount, timeline, type RecoveryData, type Screw } from '../src/recovery/player';

const repo = (p: string) => join(dirname(fileURLToPath(import.meta.url)), '../../../..', p);
const read = (p: string) => JSON.parse(readFileSync(repo(p), 'utf8')) as Record<string, unknown>;
const frames = readdirSync(repo('frames'), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
const withRecovery = frames.filter((id) => existsSync(join(repo('frames'), id, 'design', 'recovery.json')));
const data = (id: string) => read(`frames/${id}/design/recovery.json`) as unknown as RecoveryData;

const KEYS = ['frame', 'name', 'body', 'cover', 'stand', 'board', 'button', 'port', 'cord', 'power', 'steps', 'labels', 'ready'];
const MOVES = ['screws', 'cover', 'cord', 'hold', 'plug', 'power'] as const;
const STEP_NEEDS = { screws: 'cover', cover: 'cover', hold: 'button', plug: 'port', cord: 'cord', power: 'power' } as const;

it('the design copy of the S1 recovery data equals the frame\'s, photo aside', () => {
  const { photo, ...design } = read('docs/design/kiln/recovery/aqara-s1-plus.json');
  expect(photo).toBeTypeOf('string');
  expect(design).toEqual(read('frames/aqara-s1-plus/design/recovery.json'));
});

it('the recovery steps read as instructions (#141: "Back off" read as "back off!")', () => {
  expect(LABELS).toEqual({ screws: 'Take out the screws', cover: 'Remove the back cover', hold: 'Hold the button', plug: 'Plug in the USB cable', cord: 'Plug in the power cord', power: 'Switch the power on' });
});

it('the S1 and the Mirror have recovery data, and each frame.json names its file', () => {
  expect(withRecovery).toEqual(expect.arrayContaining(['aqara-s1-plus', 'lululemon-mirror']));
  for (const id of frames) expect(read(`frames/${id}/design/frame.json`).recovery, id).toBe(withRecovery.includes(id) ? 'recovery.json' : undefined);
});

describe.each(withRecovery)('%s recovery.json', (id) => {
  const f = data(id);
  const { w, h, thickness } = f.body;
  const inside = ([x, y]: Screw) => x >= 0 && x <= w && y >= 0 && y <= h;

  it('names its frame, uses known fields and moves, and has what each move needs', () => {
    expect(f.frame).toBe(id);
    expect(f.name).toBe(read(`frames/${id}/design/frame.json`).name);
    expect(Object.keys(f).filter((k) => !KEYS.includes(k)), 'unknown fields').toEqual([]);
    expect([w, h, thickness].every((n) => n > 0), 'body').toBe(true);
    expect(f.steps.length).toBeGreaterThan(0);
    expect(f.steps.filter((s) => !(MOVES as readonly string[]).includes(s)), 'unknown moves').toEqual([]);
    expect(new Set(f.steps).size, 'a move twice').toBe(f.steps.length);
    for (const s of f.steps) expect(f[STEP_NEEDS[s]], `${s} needs ${STEP_NEEDS[s]}`).toBeDefined();
    if (f.steps.includes('screws')) expect(f.cover?.screws.length).toBeGreaterThan(0);
    expect(Object.keys(f.labels ?? {}).filter((k) => !f.steps.includes(k as never)), 'labels for moves it doesn\'t make').toEqual([]);
    if (f.ready) expect(f.ready.seconds > 0 && /\S/.test(f.ready.says), 'ready').toBe(true);
    if (f.button) expect(f.button.what).toMatch(/\S/);
  });

  it('puts every screw, part, button and port on the device', () => {
    for (const s of f.cover?.screws ?? []) {
      expect(s.length === 2 || s.length === 3, `screw ${s}: [x, y] or [x, y, z]`).toBe(true);
      expect(inside(s), `screw ${s}`).toBe(true);
      const [x, y, z] = s;
      if (z === undefined) continue;
      expect(x === w || y === h, `screw ${s}: a side screw sits in a face the drawing shows (x = w or y = h)`).toBe(true);
      expect(z > 0 && z < thickness, `screw ${s}: z within the thickness`).toBe(true);
    }
    for (const p of [...(f.cover?.features ?? []), ...(f.board ?? [])]) {
      expect(inside(p.at), `${p.kind} at ${p.at}`).toBe(true);
      if ('size' in p) expect(inside([p.at[0] + p.size[0], p.at[1] + p.size[1]]), `${p.kind} at ${p.at}: its far corner`).toBe(true);
    }
    if (f.button) expect(inside(f.button.at), 'button').toBe(true);
    if (f.port) {
      expect(['top', 'bottom', 'left', 'right']).toContain(f.port.edge);
      expect(f.port.at).toBeGreaterThanOrEqual(0);
      expect(f.port.at).toBeLessThanOrEqual(['top', 'bottom'].includes(f.port.edge) ? w : h);
      expect(f.port.kind).toMatch(/\S/);
    }
    // A stand and a switch sit in a side face the drawing shows; a stand comes off with that face's screws.
    if (f.stand) {
      expect(['right', 'bottom']).toContain(f.stand.edge);
      expect(f.stand.depth !== undefined || f.stand.tube !== undefined, 'a bar, a tube or both').toBe(true);
      if (f.stand.depth !== undefined) expect(f.stand.depth).toBeGreaterThan(0);
      const inFace = (f.cover?.screws ?? []).filter(([x, y, z]) => z !== undefined && (f.stand!.edge === 'right' ? x === w : y === h));
      expect(inFace.length, 'the stand\'s screws').toBeGreaterThan(0);
    }
    if (f.cord) {
      expect(inside(f.cord.at), 'the power socket').toBe(true);
      expect(['right', 'bottom']).toContain(f.cord.toward);
    }
    if (f.power) {
      expect(['right', 'bottom']).toContain(f.power.edge);
      expect(f.power.at).toBeGreaterThanOrEqual(0);
      expect(f.power.at).toBeLessThanOrEqual(f.power.edge === 'bottom' ? w : h);
      expect(f.power.kind).toMatch(/\S/);
    }
  });

  it('keeps "keep holding" up for a hold over 2 s, in real time, before it says let go', () => {
    const tl = timeline(f);
    const seconds = f.ready?.seconds ?? 0;
    const powered = tl.flip ?? tl.plug; // the move that powers it on
    if (!tl.ready || !powered || !tl.hold || seconds <= 2) return;
    expect(tl.ready.start - powered.end).toBeGreaterThanOrEqual(seconds - 1e-9);
    expect(tl.hold.end).toBe(tl.ready.start);
  });

  it('draws and plays: one screw per screw, and no NaN at any moment', () => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const beats: number[] = [];
    const p = mount(svg, f, (b) => beats.push(b));
    expect(p.duration).toBe(timeline(f).T);
    expect([...svg.querySelectorAll('[id]')].filter((e) => /^kr-s\d+$/.test(e.id))).toHaveLength(f.cover?.screws.length ?? 0);
    for (let t = 0; t < p.duration; t += 0.1) p.draw(t);
    expect(svg.innerHTML).not.toMatch(/NaN|Infinity|undefined/);
    expect(Math.max(...beats), 'every step plays').toBe(f.steps.length - 1);
  });
});

// From the maintainer: #521, and their review of the drawing (2026-11-18, #561).
it('the Mirror: the stand\'s 4 screws first, then the back\'s 12; USB in, VOL−, then the power switch', () => {
  const f = data('lululemon-mirror');
  const { w, h } = f.body;
  const screws = f.cover!.screws;
  expect(f.stand?.edge, 'one stand along the feet\'s edge').toBe('right');
  expect(screws.slice(0, 4).every((s) => s.length === 3 && s[0] === w), 'four in the feet\'s edge, first').toBe(true);
  // The back's 12, then 2 more, one each side of the recess by the feet (maintainer, 2026-11-18).
  expect(screws.slice(4).filter((s) => s.length === 2)).toHaveLength(14);
  const recess = f.cover!.features.find((c) => c.kind === 'recess')!;
  const inRecess = ([x, y]: Screw) => x > recess.at[0] && x < recess.at[0] + recess.size[0] && y > recess.at[1] && y < recess.at[1] + recess.size[1];
  const [l, r] = screws.slice(-2) as [Screw, Screw];
  for (const [x] of [l, r]) expect(x > recess.at[0] && x < recess.at[0] + recess.size[0], 'beside the recess, along it').toBe(true);
  expect(l[1] < recess.at[1] && r[1] > recess.at[1] + recess.size[1], 'one each side, outside it').toBe(true);
  // The power cord goes back in, in that recess, once the back is off.
  expect(inRecess(f.cord!.at as Screw)).toBe(true);
  // One piece, with the two speaker grilles and the recess for the power cord near the feet.
  expect(f.cover!.features.map((c) => c.kind)).toEqual(['grille', 'grille', 'recess']);
  expect(f.cover!.features.every((c) => c.at[0] > w / 2), 'near the feet').toBe(true);
  expect(f.steps).toEqual(['screws', 'cover', 'cord', 'plug', 'hold', 'power']);
  // Side by side along the length; VOL− is the one nearer the top end's edge (x = 0).
  const others = (f.board ?? []).filter((b) => b.kind === 'button');
  expect(others).toHaveLength(1);
  expect(f.button!.at[1]).toBe(others[0]!.at[1]);
  expect(f.button!.at[0]).toBeLessThan(others[0]!.at[0]);
  expect(f.button!.what).toMatch(/^VOL− \(SW3\)/);
  // The micro-USB at the top end's far corner; the rocker switch in the feet's edge.
  expect(f.port).toMatchObject({ edge: 'left', kind: 'micro-usb' });
  expect(f.port!.at).toBeLessThan(h / 10);
  expect(f.power).toMatchObject({ edge: 'right', kind: 'rocker' });
  expect(f.power!.at).toBeGreaterThan(0);
  expect(f.power!.at).toBeLessThan(h);
});

it('the Mirror\'s stand tube is the one its 3D model draws (frame.json)', () => {
  const f = data('lululemon-mirror');
  const model = read('frames/lululemon-mirror/design/frame.json').model as {
    body: { width: number; height: number; depth: number };
    parts: { kind: string; from: number[]; to: number[]; diameter: number }[];
  };
  const tube = f.stand!.tube!;
  const ends = model.parts.flatMap((p) => [p.from, p.to]);
  // The model is centred and upright (y down); lying face down, its bottom edge is x = w here.
  expect(Math.max(...ends.map((e) => e[1]!)) - model.body.height / 2).toBeCloseTo(tube.reach, 0);
  expect(model.body.width / 2 - Math.max(...ends.map((e) => Math.abs(e[0]!)))).toBeCloseTo(tube.inset, 0);
  expect(ends[0]![2]! + model.body.depth / 2).toBeCloseTo(tube.z, 0);
  expect(model.parts.every((p) => p.kind === 'tube' && p.diameter === tube.diameter)).toBe(true);
  expect(f.stand!.depth, 'no block: the tube goes straight onto the Mirror').toBeUndefined();
  // Its top bar, the same tube, lies along the bottom edge; the 4 screws go through it (maintainer, 2026-11-18).
  expect(tube.closed).toBe(true);
  expect(f.cover!.screws.slice(0, 4).every((s) => s[2] === tube.z), 'the screws at the tube\'s height').toBe(true);
});

it('says ready after the later of plug and power, whichever order a frame has them in', () => {
  const f = data('lululemon-mirror');
  const plugLast = timeline({ ...f, steps: ['screws', 'cover', 'cord', 'hold', 'power', 'plug'] });
  expect(plugLast.ready!.start).toBeGreaterThanOrEqual(plugLast.plug!.end + f.ready!.seconds - 1e-9);
  expect(plugLast.hold!.end).toBe(plugLast.ready!.start);
  const powerLast = timeline(f);
  expect(powerLast.ready!.start).toBeGreaterThanOrEqual(powerLast.flip!.end + f.ready!.seconds - 1e-9);
});

it('keeps "Keep holding" in view while the camera is on the Mirror\'s switch, far from the button', () => {
  const f = data('lululemon-mirror');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const p = mount(svg, f);
  const tl = timeline(f);
  p.draw(tl.flip!.end - 0.05);
  expect(svg.querySelector('#kr-hold')!.getAttribute('opacity')).toBe('1');
  const [x, y] = svg.querySelector('#kr-hold')!.getAttribute('transform')!.match(/-?[\d.]+/g)!.map(Number);
  expect(x! >= 0 && x! + 132 <= 640 && y! - 16 >= 0 && y! + 16 <= 400, `at ${x}, ${y}`).toBe(true);
});

it('the Mirror\'s stand screws\' halos leave with its tube stand', () => {
  const f = data('lululemon-mirror');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const p = mount(svg, f);
  const stand = svg.querySelector('#kr-stand')!;
  expect(stand.querySelectorAll('.kr-halo')).toHaveLength(4);
  p.draw(timeline(f).stand!.end + 0.05);
  expect(stand.getAttribute('opacity')).toBe('0.000');
});
