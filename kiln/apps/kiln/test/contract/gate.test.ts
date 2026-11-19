// The gate (#621): a frame is public in Kiln only when its flows pass the whole forgiveness contract. Public is what
// the picker offers (Picker.tsx: a shipping status and a `kiln` route in frame.json). Every Kiln page has a contract
// too: a page with no installer.json, or a flow with no driver, fails here.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { ships, type FrameDesign } from '@glassworks/frame-card';
import { describe, expect, it } from 'vitest';
import { ENGINE, INSTALLERS } from '@glassworks/frames/catalog';
import { FRAMES } from '../../src/frames/catalog';
import { installers, type InstallerData } from './harness';
import { DRIVERS, MARKS } from './suite';
import type { Driver } from './harness';

const APP = join(__dirname, '../../src/app');

/** The picker's rule, word for word (Picker.tsx INSTALLABLE). */
const isPublic = (f: Pick<FrameDesign, 'status' | 'kiln'>) => ships(f.status) && !!f.kiln;

/** Why each public frame can't be public yet; empty when the gate passes. */
export function gate(frames: Pick<FrameDesign, 'id' | 'status' | 'kiln'>[], data: Map<string, InstallerData>,
  drivers: Pick<Driver, 'id' | 'frame' | 'flow'>[], marks: Record<string, Record<string, string>>): string[] {
  const out: string[] = [];
  for (const f of frames.filter(isPublic)) {
    const d = data.get(f.id);
    if (!d) { out.push(`${f.id} is public in Kiln but has no design/installer.json`); continue; }
    if (f.kiln !== d.route) { out.push(`${f.id} is public in Kiln at ${f.kiln}, but its installer.json route is ${d.route}`); continue; }
    if (!Object.keys(d.flows).length) { out.push(`${f.id} is public in Kiln but its installer.json has no flows`); continue; }
    for (const flow of Object.keys(d.flows)) {
      const driver = drivers.find((x) => x.frame === f.id && x.flow === flow);
      if (!driver) { out.push(`${f.id} is public in Kiln but its ${flow} flow has no contract driver`); continue; }
      const m = Object.keys(marks[driver.id] ?? {});
      if (m.length) out.push(`${f.id} is public in Kiln but ${driver.id} fails the contract: ${m.join(', ')}`);
    }
  }
  return out;
}

describe('the gate', () => {
  it('reads public the way the picker does', () => {
    const picker = readFileSync(join(__dirname, '../../src/components/Picker.tsx'), 'utf8');
    expect(picker).toContain('FRAMES.filter((f) => ships(f.status) && f.kiln)');
  });

  it('passes: every frame public in Kiln today passes the whole contract', () => {
    expect(FRAMES.filter(isPublic).map((f) => f.id)).toContain('aqara-s1-plus');
    expect(gate(FRAMES, installers(), DRIVERS, MARKS)).toEqual([]);
  });

  it('refuses a frame made public while it has expected failures, or without a contract', () => {
    // fixed marks, so fixing a real one doesn't change this test
    const marks = { 'lululemon-mirror': { 'usb-drop': '#1: example' }, 'lululemon-mirror/app': { 'no-touch': '#1: example' } };
    const mirror = FRAMES.find((f) => f.id === 'lululemon-mirror')!;
    const opened = [{ ...mirror, status: 'alpha' as const, kiln: '/lululemon-mirror' }];
    expect(gate(opened, installers(), DRIVERS, marks)).toEqual([
      'lululemon-mirror is public in Kiln but lululemon-mirror fails the contract: usb-drop',
      'lululemon-mirror is public in Kiln but lululemon-mirror/app fails the contract: no-touch',
    ]);
    expect(gate(opened, installers(), DRIVERS, {})).toEqual([]);
    const flowless = new Map(installers()).set('lululemon-mirror', { ...installers().get('lululemon-mirror')!, flows: {} });
    expect(gate(opened, flowless, DRIVERS, {})).toEqual(['lululemon-mirror is public in Kiln but its installer.json has no flows']);
    expect(gate([{ id: 'new-frame', status: 'beta', kiln: '/new-frame' }], installers(), DRIVERS, MARKS))
      .toEqual(['new-frame is public in Kiln but has no design/installer.json']);
    // a public route that isn't the one its contract covers
    const s1 = FRAMES.find((f) => f.id === 'aqara-s1-plus')!;
    expect(gate([{ ...s1, kiln: '/lululemon-mirror' }], installers(), DRIVERS, MARKS))
      .toEqual(['aqara-s1-plus is public in Kiln at /lululemon-mirror, but its installer.json route is /aqara-s1-plus']);
  });
});

describe('every Kiln page has a contract', () => {
  const data = installers();
  // a route is a folder with an App Router page in it (page.tsx), not any folder; [frame] is every route of a frame on
  // the shared wizard (the registry's ENGINE)
  const folders = readdirSync(APP).filter((n) => statSync(join(APP, n)).isDirectory() && existsSync(join(APP, n, 'page.tsx')));
  const pages = folders.flatMap((n) => (n === '[frame]' ? ENGINE.map((id) => INSTALLERS[id]!.route) : [`/${n}`]));

  it('each frame page is the route of an installer.json, and each route has a page', () => {
    const routes = [...data.values()].map((d) => d.route).sort();
    expect(pages.sort()).toEqual(routes);
  });

  it('each flow has a driver, and each driver a flow', () => {
    const flows = [...data].flatMap(([frame, d]) => Object.keys(d.flows).map((flow) => `${frame} ${flow}`)).sort();
    expect(DRIVERS.map((d) => `${d.frame} ${d.flow}`).sort()).toEqual(flows);
  });

  it('a frame without touch declares no taps', () => {
    const taps = [...data].filter(([, d]) => !d.touch)
      .flatMap(([frame, d]) => Object.values(d.flows).flatMap((f) => f.steps.filter((s) => s.taps?.length).map((s) => `${frame} ${s.id}`)));
    expect(taps).toEqual([]);
  });

  it('each installer.json names steps once, with a known transport', () => {
    const bad: string[] = [];
    for (const [frame, d] of data) {
      for (const [flow, f] of Object.entries(d.flows)) {
        const ids = f.steps.map((s) => s.id);
        if (new Set(ids).size !== ids.length) bad.push(`${frame} ${flow}: a step id twice`);
        for (const s of f.steps) if (!['fel', 'fastboot', 'adb', 'usb', 'none'].includes(s.transport)) bad.push(`${frame} ${flow} ${s.id}: transport ${s.transport}`);
      }
    }
    expect(bad).toEqual([]);
  });
});
