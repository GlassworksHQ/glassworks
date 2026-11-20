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
    expect(geometry(tablet.model, 2).vars['--dev-face']).toBe(tablet.model.face);
    expect(geometry(s1.model, 2).vars['--dev-face']).toBeUndefined();
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
});
