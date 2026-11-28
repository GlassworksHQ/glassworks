// The gate (#621). Public is what the picker offers (Picker.tsx: a shipping status, alpha or later, and a `kiln` route in
// frame.json): a public frame needs its installer.json and a contract driver for each flow. A `stable` frame must pass
// the whole forgiveness contract, with no expected failures; an alpha or beta frame may still have some (each names
// the issue that fixes it, contract.test.ts). Every Kiln page has a contract too: a page with no installer.json, or a
// flow with no driver, fails here.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { ships, type FrameDesign } from '@glassworks/frame-card';
import { describe, expect, it } from 'vitest';
import { ENGINE, INSTALLERS } from '@glassworks/frames/catalog';
import { FRAMES, installsAs, kilnRoute } from '../../src/frames/catalog';
import { installers, type InstallerData } from './harness';
import { DRIVERS, MARKS } from './suite';
import type { Driver } from './harness';

const APP = join(__dirname, '../../src/app');

/** The picker's rule, word for word (Picker.tsx INSTALLABLE). */
const isPublic = (f: Pick<FrameDesign, 'id' | 'kind' | 'base' | 'status' | 'kiln'>) => ships(f.status) && !!kilnRoute(f);

/** Why each public frame can't be public at its status yet; empty when the gate passes. */
export function gate(frames: Pick<FrameDesign, 'id' | 'kind' | 'base' | 'status' | 'kiln'>[], data: Map<string, InstallerData>,
  drivers: Pick<Driver, 'id' | 'frame' | 'flow'>[], marks: Record<string, Record<string, string>>): string[] {
  const out: string[] = [];
  for (const f of frames.filter(isPublic)) {
    // a tested device installs as its base: the base's installer.json, flows and contract drivers are its own
    const id = installsAs(f);
    const route = kilnRoute(f);
    const d = data.get(id);
    if (!d) { out.push(`${f.id} is public in Kiln but has no design/installer.json`); continue; }
    if (route !== d.route) { out.push(`${f.id} is public in Kiln at ${route}, but its installer.json route is ${d.route}`); continue; }
    if (!Object.keys(d.flows).length) { out.push(`${f.id} is public in Kiln but its installer.json has no flows`); continue; }
    for (const flow of Object.keys(d.flows)) {
      const driver = drivers.find((x) => x.frame === id && x.flow === flow);
      if (!driver) { out.push(`${f.id} is public in Kiln but its ${flow} flow has no contract driver`); continue; }
      const m = Object.keys(marks[driver.id] ?? {});
      if (f.status === 'stable' && m.length) out.push(`${f.id} is stable but ${driver.id} fails the contract: ${m.join(', ')}`);
    }
  }
  return out;
}

describe('the gate', () => {
  it('reads public the way the picker does', () => {
    const picker = readFileSync(join(__dirname, '../../src/components/Picker.tsx'), 'utf8');
    expect(picker).toContain('FRAMES.filter((f) => ships(f.status) && kilnRoute(f))');
  });

  it('passes: every frame public in Kiln today has its contract, and a stable one passes all of it', () => {
    expect(FRAMES.filter(isPublic).map((f) => `${f.id} ${f.status}`).sort())
      .toEqual(['aqara-s1-plus stable', 'generic-android stable', 'google-pixel-tablet stable', 'lululemon-mirror stable', 'samsung-galaxy-tab-a-10-1-2016 stable']);
    expect(gate(FRAMES, installers(), DRIVERS, MARKS)).toEqual([]);
  });

  it('holds the S1, stable, to no expected failures', () => {
    const s1 = DRIVERS.filter((d) => d.frame === 'aqara-s1-plus').map((d) => d.id);
    expect(s1.length).toBeGreaterThan(0);
    for (const id of s1) expect(MARKS[id] ?? {}, id).toEqual({});
  });

  // fixed marks, so fixing a real one doesn't change these tests
  const marks = { 'lululemon-mirror': { 'usb-drop': '#1: example' }, 'lululemon-mirror/app': { 'no-touch': '#1: example' } };
  const mirror = FRAMES.find((f) => f.id === 'lululemon-mirror')!;
  const at = (status: FrameDesign['status']) => [{ ...mirror, status, kiln: '/lululemon-mirror' }];

  it.each(['alpha', 'beta'] as const)('lets a %s frame be public with expected failures', (status) => {
    expect(gate(at(status), installers(), DRIVERS, marks)).toEqual([]);
  });

  it('refuses a stable frame with expected failures', () => {
    expect(gate(at('stable'), installers(), DRIVERS, marks)).toEqual([
      'lululemon-mirror is stable but lululemon-mirror fails the contract: usb-drop',
      'lululemon-mirror is stable but lululemon-mirror/app fails the contract: no-touch',
    ]);
    expect(gate(at('stable'), installers(), DRIVERS, {})).toEqual([]);
  });

  it('checks a tested device as its base: the base\'s route, data and drivers, and nothing without a base Kiln installs', () => {
    const tested = { id: 'some-tablet', kind: 'tested' as const, base: 'lululemon-mirror', status: 'beta' as const };
    expect(gate([tested], installers(), DRIVERS, {})).toEqual([]);
    expect(gate([tested], installers(), DRIVERS.filter((d) => d.id !== 'lululemon-mirror/app'), {}))
      .toEqual(['some-tablet is public in Kiln but its app flow has no contract driver']);
    expect(gate([{ ...tested, base: 'no-such-frame' }], installers(), DRIVERS, {})).toEqual([]);
  });

  it.each(['planned', 'development'] as const)('a %s frame isn\'t public, whatever it has', (status) => {
    expect(gate([{ id: 'new-frame', kind: 'device', status, kiln: '/new-frame' }], installers(), DRIVERS, marks)).toEqual([]);
  });

  it('refuses a public frame, at any status, without a contract', () => {
    const opened = at('alpha');
    const flowless = new Map(installers()).set('lululemon-mirror', { ...installers().get('lululemon-mirror')!, flows: {} });
    expect(gate(opened, flowless, DRIVERS, {})).toEqual(['lululemon-mirror is public in Kiln but its installer.json has no flows']);
    expect(gate(at('beta'), installers(), DRIVERS.filter((d) => d.id !== 'lululemon-mirror/app'), {}))
      .toEqual(['lululemon-mirror is public in Kiln but its app flow has no contract driver']);
    expect(gate([{ id: 'new-frame', kind: 'device', status: 'beta', kiln: '/new-frame' }], installers(), DRIVERS, MARKS))
      .toEqual(['new-frame is public in Kiln but has no design/installer.json']);
    // a public route that isn't the one its contract covers
    const s1 = FRAMES.find((f) => f.id === 'aqara-s1-plus')!;
    expect(gate([{ ...s1, kiln: '/lululemon-mirror' }], installers(), DRIVERS, MARKS))
      .toEqual(['aqara-s1-plus is public in Kiln at /lululemon-mirror, but its installer.json route is /aqara-s1-plus']);
  });
});

describe('every Kiln page has a contract', () => {
  const data = installers();
  // a route is a folder with an App Router page in it (page.tsx, or in a route group of it: `(flow)/page.tsx`), not any
  // folder; [frame] is every route of a frame on the shared wizard (the registry's ENGINE)
  const hasPage = (dir: string) => existsSync(join(dir, 'page.tsx'))
    || readdirSync(dir).some((g) => /^\(.+\)$/.test(g) && existsSync(join(dir, g, 'page.tsx')));
  const folders = readdirSync(APP).filter((n) => statSync(join(APP, n)).isDirectory() && hasPage(join(APP, n)));
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
        for (const s of f.steps) if (!['fel', 'fastboot', 'adb', 'usb', 'agent', 'none'].includes(s.transport)) bad.push(`${frame} ${flow} ${s.id}: transport ${s.transport}`);
      }
    }
    expect(bad).toEqual([]);
  });
});
