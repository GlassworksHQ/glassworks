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

const KEYS = ['frame', 'name', 'body', 'cover', 'board', 'button', 'port', 'steps', 'labels', 'ready'];
const MOVES = ['screws', 'cover', 'hold', 'plug'] as const;
const STEP_NEEDS = { screws: 'cover', cover: 'cover', hold: 'button', plug: 'port' } as const;

it('the design copy of the S1 recovery data equals the frame\'s, photo aside', () => {
  const { photo, ...design } = read('docs/design/kiln/recovery/aqara-s1-plus.json');
  expect(photo).toBeTypeOf('string');
  expect(design).toEqual(read('frames/aqara-s1-plus/design/recovery.json'));
});

it('the recovery steps read as instructions (#141: "Back off" read as "back off!")', () => {
  expect(LABELS).toEqual({ screws: 'Take out the screws', cover: 'Remove the back cover', hold: 'Hold the button', plug: 'Plug in the USB cable' });
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

it('the Mirror: feet screws first, then the back\'s 12; VOL− is the outer of its two buttons', () => {
  const f = data('lululemon-mirror');
  const screws = f.cover!.screws;
  expect(screws.slice(0, 4).every((s) => s.length === 3 && s[0] === f.body.w), 'four in the feet\'s edge, first').toBe(true);
  expect(screws.slice(4).filter((s) => s.length === 2)).toHaveLength(12);
  const others = (f.board ?? []).filter((b) => b.kind === 'button');
  expect(others).toHaveLength(1);
  const toEdge = ([, y]: [number, number]) => Math.min(y, f.body.h - y);
  expect(toEdge(f.button!.at)).toBeLessThan(toEdge(others[0]!.at));
  expect(f.button!.what).toMatch(/^VOL− \(SW3\)/);
});
