// @vitest-environment jsdom
// A dock part (the Pixel Tablet's speaker dock): geometry() draws it apart from the body, with its
// plate and contacts, and gives the body's lift off it; the model keeps it out of the part
// that lifts; the CSS lifts only a docked body, on hover or the card's focus, and never with reduced motion.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import mirrorJson from '../../../frames/lululemon-mirror/design/frame.json';
import s1Json from '../../../frames/aqara-s1-plus/design/frame.json';
import tabletJson from '../../../frames/google-pixel-tablet/design/frame.json';
import { DeviceModel, geometry, type DockPart, type FrameDesign, type FrameModel } from '../src';

// JSON imports widen tuples and string unions, so the casts go through unknown
const tablet = tabletJson as unknown as FrameDesign;
const s1 = s1Json as FrameDesign;
const mirror = mirrorJson as unknown as FrameDesign;
const dockOf = (m: FrameModel) => m.parts?.find((p): p is DockPart => p.kind === 'dock');

const render = (node: React.ReactElement) => {
  const el = document.createElement('div');
  el.innerHTML = renderToStaticMarkup(node);
  return el;
};
const css = (file: string) => readFileSync(join(__dirname, '../src', file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

describe('the Pixel Tablet on its dock', () => {
  it('is drawn at its real size, leaning back on its dock (169 × 94 × 70 mm, Google\'s dock spec page)', () => {
    const m = tablet.model;
    expect(m.body).toMatchObject({ width: 258, height: 169 });
    expect(m.mount).toBe('desk');
    expect(m.lean).toBeGreaterThanOrEqual(10);
    expect(m.lean).toBeLessThanOrEqual(15);
    const dock = dockOf(m)!;
    expect(dock.width / m.body.width).toBeGreaterThan(0.45);
    expect(dock.width / m.body.width).toBeLessThan(0.7);
  });

  it.each([2, 0.8])('draws the dock, its plate and contacts on a %s stage', (aspect) => {
    const g = geometry(tablet.model, aspect);
    expect(g.dock?.faces.length).toBeGreaterThan(10);
    expect(g.dock?.plate).toBeDefined();
    expect(g.dock?.pins).toHaveLength(4);
    expect(g.floorShadow).toBeDefined();
    expect(JSON.stringify(g)).not.toMatch(/NaN|Infinity/);
  });

  it('lifts the body up and a little towards the viewer', () => {
    const v = geometry(tablet.model, 2).vars;
    expect(parseFloat(v['--uy']!)).toBeLessThan(0);
    expect(parseFloat(v['--us']!)).toBeGreaterThan(0);
    expect(parseFloat(v['--us']!)).toBeLessThan(0.1);
  });

  it('draws the dock at any lean', () => {
    const dock = dockOf(tablet.model)!;
    for (const lean of [0, 25]) {
      const g = geometry({ ...tablet.model, lean, parts: [dock] }, 2);
      expect(g.dock?.faces.length).toBeGreaterThan(10);
      expect(JSON.stringify(g)).not.toMatch(/NaN|Infinity/);
    }
  });

  it('paints its white front from the frame\'s data, and black glass without', () => {
    expect(geometry({ ...tablet.model, dark: undefined }, 2).vars['--dev-face']).toBe(tablet.model.face);
    expect(geometry(s1.model, 2).vars['--dev-face']).toBeUndefined();
  });
});

describe('a view of its own (geometry view, DeviceModel view)', () => {
  it('turns the camera, and the sides, dock and glass pose follow; without one, the mount\'s', () => {
    const own = geometry(tablet.model, 1.6);
    const turned = geometry(tablet.model, 1.6, { rx: 8, ry: 16 });
    expect([own.vars['--rx'], own.vars['--ry']]).toEqual(['10deg', '-24deg']);
    expect([turned.vars['--rx'], turned.vars['--ry']]).toEqual(['8deg', '16deg']);
    expect(turned.faces.map((f) => f.points)).not.toEqual(own.faces.map((f) => f.points));
    expect(turned.dock?.faces.length).toBeGreaterThan(10);
    expect(JSON.stringify(turned)).not.toMatch(/NaN|Infinity/);
    const el = render(<DeviceModel id="pt-view" model={tablet.model} label="Pixel Tablet" aspect={1.6} view={{ rx: 8, ry: 16 }} />);
    expect(el.querySelector('[data-dock]')!.getAttribute('style')).toContain('--ry:16deg');
  });
});

describe('round corners', () => {
  it('draws a body that is all corner smooth, and a small corner as before', () => {
    const round = { ...s1.model, body: { ...s1.model.body, width: 84, height: 84, radius: 42 }, dark: undefined };
    const sides = (m: FrameModel) => geometry(m, 1).faces.length;
    // a circle's edge: 64 segments (16 a quarter); 26 face the viewer on a 2:1 stage (9 with the old 24)
    expect(geometry(round, 2).faces.length).toBe(26);
    expect(sides(round)).toBeGreaterThan(15);
    // the frames' small corners keep 6 segments: the same faces as before (checked byte for byte, 2026-11-20)
    expect(geometry(tablet.model, 2).faces.length).toBe(12);
    expect(geometry(mirror.model, 2).faces.length).toBe(12);
  });
});

describe('a dark look (model.dark)', () => {
  const lightDark = /^light-dark\(#[0-9a-f]{6}, #[0-9a-f]{6}\)$/;

  it('paints the Pixel Tablet Porcelain on a light page and Hazel on a dark one, dock included', () => {
    const g = geometry(tablet.model, 2);
    expect(g.vars['--dev-face']).toBe(`light-dark(${tablet.model.face}, ${tablet.model.dark!.face})`);
    for (const f of [...g.faces, ...g.dock!.faces, g.dock!.plate!]) expect(f.fill).toMatch(lightDark);
    // the light half is what the frame drew before it had a dark look
    const plain = geometry({ ...tablet.model, dark: undefined, parts: [{ ...dockOf(tablet.model)!, dark: undefined }] }, 2);
    expect(g.faces.map((f) => f.fill.slice(11, 18))).toEqual(plain.faces.map((f) => f.fill));
    expect(g.dock!.faces.map((f) => f.fill.slice(11, 18))).toEqual(plain.dock!.faces.map((f) => f.fill));
  });

  it('paints the S1 silver on a light page and in its dark finish on a dark one, behind the same black glass', () => {
    const g = geometry(s1.model, 2);
    for (const f of g.faces) expect(f.fill).toMatch(lightDark);
    expect(g.vars['--dev-face']).toBeUndefined();
  });

  it('leaves a frame without one as it was: plain colours', () => {
    const g = geometry(mirror.model, 2);
    expect([...g.faces.map((x) => x.fill), ...g.parts.map((x) => x.color)].filter((c) => c.startsWith('light-dark'))).toEqual([]);
  });

  it('draws a dark front over black glass when only the dark look has one', () => {
    const { face: _face, ...noFace } = tablet.model;
    expect(geometry({ ...noFace, dark: { face: '#2c2e2b' } }, 2).vars['--dev-face']).toBe('light-dark(#07080b, #2c2e2b)');
  });

  it('draws its colours as styles, which take light-dark(), not attributes', () => {
    const el = render(<DeviceModel id="pt-dark" model={tablet.model} label="Pixel Tablet" />);
    const polys = [...el.querySelectorAll('polygon[style]')];
    expect(polys.length).toBeGreaterThan(10);
    expect(polys.every((p) => p.getAttribute('style')!.includes('light-dark('))).toBe(true);
  });
});

describe('a frame without a dock', () => {
  it.each([['S1', s1], ['Mirror', mirror]])('%s: draws no dock and has no lift', (_, f) => {
    const g = geometry(f.model, 2);
    expect(g.dock).toBeUndefined();
    expect(g.vars['--ux']).toBeUndefined();
    expect(render(<DeviceModel id="x" model={f.model} label="x" />).querySelector('[data-dock]')).toBeNull();
  });
});

describe('the model', () => {
  const el = render(<DeviceModel id="pt" model={tablet.model} label="Pixel Tablet" />);
  const glass = el.querySelector('[aria-hidden="true"] > div')!.parentElement!;
  const slab = glass.parentElement!;

  it('marks a docked model, and keeps the dock out of the part that lifts', () => {
    expect(el.querySelector('[data-dock]')).not.toBeNull();
    const dockSvg = el.querySelector('circle')!.closest('svg')!;
    expect(slab.contains(dockSvg)).toBe(false);
    expect(slab.querySelector('svg polygon')).not.toBeNull();
  });

  it('gives each model its own filter and gradient ids', () => {
    const ids = [...el.querySelectorAll('[id]')].map((n) => n.id);
    expect(ids).toEqual(expect.arrayContaining(['frame-floor-pt', 'frame-weave-pt']));
  });
});

describe('the undock CSS', () => {
  it('lifts only a docked body, and not with reduced motion', () => {
    const model = css('DeviceModel.module.css');
    const motion = model.slice(model.indexOf('@media (prefers-reduced-motion: no-preference)'));
    expect(motion).toMatch(/^@media \(prefers-reduced-motion: no-preference\) \{\s*\.model\[data-dock\] \.slab \{[^}]*var\(--undock, 0\)/);
    // the body's transform is set nowhere else
    expect(model.match(/\.slab \{[^}]*transform:/g)).toHaveLength(1);
  });

  it('lifts on hover with a mouse, and on the card\'s hover and keyboard focus', () => {
    expect(css('DeviceModel.module.css')).toMatch(/@media \(hover: hover\) \{\s*\.stage:hover \{\s*--undock: 1;/);
    const card = css('FrameCard.module.css');
    expect(card).toMatch(/(^|\})\s*\.card:focus-visible \{\s*--undock: 1;\s*\}/);
    // hover only with a pointer that hovers: a tap on a touch screen emulates :hover
    expect(card).toMatch(/@media \(hover: hover\) \{\s*\.card:hover \{\s*--undock: 1;\s*\}\s*\}/);
    expect(card.match(/--undock: 1/g)).toHaveLength(2);
  });
});

describe('the card', () => {
  it('keeps a docked model on the ground: no float, bob or floating shadow, but the lean toward the pointer', () => {
    const card = css('FrameCard.module.css');
    // only without reduced motion, so the reduced-motion reset (transform: none) holds a docked model still too
    expect(card).toMatch(/@media \(prefers-reduced-motion: no-preference\) \{\s*\.card:hover \.float:has\(\[data-dock\]\),\s*\.card:focus-visible \.float:has\(\[data-dock\]\) \{\s*transform: rotateX\(calc\(var\(--tilt-x, 0deg\) \* 0\.5\)\) rotateY\(calc\(var\(--tilt-z, 0deg\) \* 0\.45\)\);\s*\}/);
    expect(card).toMatch(/\.card:hover \.bob:has\(\[data-dock\]\),\s*\.card:focus-visible \.bob:has\(\[data-dock\]\) \{\s*animation: none;/);
    expect(card).toMatch(/\.card:hover \.model:has\(\[data-dock\]\) > \.shadow,\s*\.card:focus-visible \.model:has\(\[data-dock\]\) > \.shadow \{\s*opacity: 0;/);
  });

  it('bobs a docked model\'s lifted body as the others bob, with `translate` so the lift holds, and not with reduced motion', () => {
    const card = css('FrameCard.module.css');
    expect(card).toMatch(/\.card:hover \.bob \[data-dock\] > \[data-body\],\s*\.card:focus-visible \.bob \[data-dock\] > \[data-body\] \{\s*animation: bodyBob/);
    expect(card).toMatch(/@keyframes bodyBob \{\s*50% \{\s*translate: 0 -5px;/);
    const reduced = card.slice(card.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toMatch(/\.card:focus-visible \.bob \[data-dock\] > \[data-body\] \{\s*animation: none;/);
    // the body is the part that lifts, apart from the dock
    const el = render(<DeviceModel id="pt-body" model={tablet.model} label="Pixel Tablet" />);
    const body = el.querySelector('[data-dock] > [data-body]')!;
    expect(body).not.toBeNull();
    expect(body.querySelector('circle')).toBeNull();
  });
});
