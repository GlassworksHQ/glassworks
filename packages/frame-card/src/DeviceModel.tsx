'use client';
// A frame's 3D mockup, as on glassworks.dev (`DeviceModel.tsx`, fbfd364): the front glass is one CSS-3D
// plane with the frame's render (or an example dashboard); the body's sides, any parts and a floor shadow are geometry.ts's
// projection of the frame's model, drawn as SVG behind the glass. Light moves, glass doesn't. A dock is drawn on its
// own, behind the body, so the body can lift off it on hover (--undock, DeviceModel.module.css).
import { useEffect, useMemo, useRef, type CSSProperties } from 'react';
import type { FrameModel } from './frame';
import { geometry, type Layout } from './geometry';
import { PAINT } from './paint';
import s from './DeviceModel.module.css';

const PAINT_VARS = {
  '--dev-glass': PAINT.glass, '--dev-mirror-a': PAINT.mirrorA, '--dev-mirror-b': PAINT.mirrorB, '--dev-mirror-c': PAINT.mirrorC,
} as CSSProperties;

/**
 * `aspect` is the stage's width / height: 2 by default, as on Kiln's cards; a taller stage, e.g. 0.8, suits a portrait frame's own page.
 * `screen` is the URL of the frame's render (frame.json `render`) to show on the glass; without it, an example dashboard.
 * The render loads lazily; `priority` loads it at once, for a model that is a page's largest paint.
 */
export function DeviceModel({ id, model, label, aspect = 2, screen, priority = false }: { id: string; model: FrameModel; label: string; aspect?: number; screen?: string; priority?: boolean }) {
  const stage = useRef<HTMLDivElement>(null);
  const g = useMemo(() => geometry(model, aspect), [model, aspect]);
  // --u = 1% of the stage width, for the depth and perspective lengths
  useEffect(() => {
    const el = stage.current;
    if (!el) return;
    const set = () => el.style.setProperty('--u', `${el.clientWidth / 100}px`);
    set();
    const ro = new ResizeObserver(set);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const blur = `frame-floor-${id}`;
  const weave = `frame-weave-${id}`;
  const heather = `frame-heather-${id}`;
  const { dock } = g;
  return (
    <div ref={stage} className={s.stage} style={{ ...PAINT_VARS, aspectRatio: aspect }} role="img" aria-label={label}>
      <div className={s.model} data-layout={g.layout} data-finish={g.finish} data-mount={g.mount} data-dock={dock ? '' : undefined} style={{ transform: g.transform, ...g.vars } as CSSProperties}>
        {g.mount === 'wall' && <div className={s.shadow} aria-hidden="true" />}
        {(g.floorShadow || dock) && (
          // what stays put: the floor's shadow, and a dock
          <svg className={s.sides} viewBox={`0 0 100 ${g.stageH}`} preserveAspectRatio="none" aria-hidden="true">
            <defs>
              {/* the blur's region is the stage's, not the shadow's: a flat floor shadow's own box would clip it */}
              <filter id={blur} filterUnits="userSpaceOnUse" x="-50" y={-g.stageH / 2} width="200" height={g.stageH * 2}><feGaussianBlur stdDeviation={dock ? 1.1 : 1.6} /></filter>
              {dock && (
                // the fabric: a fine diagonal twill, drawn as lines so it stays sharp at any size, over a faint heather
                <>
                  <pattern id={weave} width="0.5" height="0.5" patternUnits="userSpaceOnUse" patternTransform="rotate(-32)">
                    <rect width="0.5" height="0.2" fill="black" fillOpacity={0.055} />
                    <rect y="0.25" width="0.5" height="0.1" fill="white" fillOpacity={0.1} />
                  </pattern>
                  <filter id={heather} x="0" y="0" width="100%" height="100%">
                    <feTurbulence type="fractalNoise" baseFrequency="2.4 3.4" numOctaves={2} seed={7} />
                    <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  -0.5 0 0 0 0.25" />
                    <feComposite in2="SourceGraphic" operator="in" />
                    <feMerge><feMergeNode in="SourceGraphic" /><feMergeNode /></feMerge>
                  </filter>
                </>
              )}
            </defs>
            {g.floorShadow && <polygon points={g.floorShadow} fill="black" fillOpacity={dock ? 0.32 : 0.5} filter={`url(#${blur})`} />}
            {dock && <g filter={`url(#${heather})`}>{dock.faces.map((f, i) => <polygon key={`d${i}`} points={f.points} fill={f.fill} stroke={f.fill} strokeWidth="0.12" strokeLinejoin="round" />)}</g>}
            {dock && <g fill={`url(#${weave})`}>{dock.faces.map((f, i) => <polygon key={`w${i}`} points={f.points} />)}</g>}
            {dock?.plate && <polygon points={dock.plate.points} fill={dock.plate.fill} />}
            {dock?.pins?.map((p, i) => <circle key={`n${i}`} cx={p.cx} cy={p.cy} r={p.r} fill={PAINT.pin} />)}
          </svg>
        )}
        {/* the body, which lifts off a dock on hover (--undock) */}
        <div className={s.slab}>
          <svg className={s.sides} viewBox={`0 0 100 ${g.stageH}`} preserveAspectRatio="none" aria-hidden="true">
            {g.parts.map((p, i) => <line key={`p${i}`} x1={p.x1} y1={p.y1} x2={p.x2} y2={p.y2} stroke={p.color} strokeWidth={p.width} strokeLinecap="round" />)}
            {g.faces.map((f, i) => <polygon key={i} points={f.points} fill={f.fill} stroke={f.fill} strokeWidth="0.12" strokeLinejoin="round" />)}
          </svg>
          <div className={s.device} aria-hidden="true">
            <div className={s.front}>
              <div className={s.screen}>{screen ? <img className={s.render} src={screen} alt="" decoding="async" loading={priority ? 'eager' : 'lazy'} fetchPriority={priority ? 'high' : undefined} /> : <Dashboard layout={g.layout} />}</div>
              <div className={s.glare} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/* An example dashboard, in the Glass Tile anatomy. Illustrative values only. */
function Dashboard({ layout }: { layout: Layout }) {
  const tiles = [
    { name: 'Living room', value: '3 lights on', lit: true },
    { name: 'Hallway', value: '21.5°' },
    { name: 'Front door', value: 'Locked' },
    { name: 'Kitchen', value: 'Playing' },
  ];
  const shown = layout === 'square' ? tiles.slice(0, 2) : tiles;
  return (
    <div className={s.dash}>
      <div className={s.clock}>
        <span className={s.time}>21:42</span>
        <span className={s.date}>Wednesday 11 November</span>
        {layout !== 'square' && <span className={s.weather}>12° · Cloudy</span>}
      </div>
      <div className={s.tiles}>
        {shown.map((t) => (
          <div key={t.name} className={`${s.tile} ${t.lit ? s.tileLit : ''}`}>
            <span className={s.tileName}>{t.name}</span>
            <span className={s.tileValue}>{t.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
