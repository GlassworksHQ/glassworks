// @vitest-environment jsdom
// The forgiveness contract (#621, docs/design/kiln/frames-as-data.md#the-contract): every frame's Kiln flow, through the
// same failures on a fake device, must end where the S1's does. A case a flow fails today is marked in
// expected-failures.json with the issue that fixes it: it runs as `it.fails`, so the suite is green now and turns red
// the moment someone fixes the flow without removing the mark. gate.test.ts refuses a public frame with any mark.
//
// Each run has two tests: "harness" checks the failure was really injected (never marked, so a broken fake can't hide
// behind a mark), and "contract" checks what the flow did about it.
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { drive, flowData, type Driver, type Stop, type World, type Write } from './harness';
import { DRIVERS, MARKS, cases, mark, type Case } from './suite';

vi.spyOn(console, 'error').mockImplementation(() => {});

interface Result { stops: Stop[]; writes: Write[]; fired: World['fired']; partial: () => string[]; wroteBeforeReload?: boolean; remembered?: boolean; landed?: string }

async function run(d: Driver, c: Case): Promise<Result> {
  const w = d.world(c.knobs);
  let page = await w.load();
  const r: Result = { stops: [], writes: w.writes, fired: w.fired, partial: () => [...w.partial] };
  try {
    let stop = await drive(w, page);
    r.stops.push(stop);
    if (stop.end === 'reload') {
      r.wroteBeforeReload = w.writes.length > 0;
      page.close();
      w.reloadNow = false;
      page = await w.load();
      r.remembered = page.remembers();
      r.stops.push(stop = await drive(w, page));
    } else if (stop.end === 'error' && (c.kind === 'usb-drop' || c.kind === 'stalled-download')) {
      await page.retry();
      r.landed = page.step();
      r.stops.push(await drive(w, page));
    }
  } finally {
    page.close();
  }
  return r;
}

const memo = new Map<string, Promise<Result>>();
const result = (d: Driver, c: Case) => {
  const key = `${d.id} ${c.id}`;
  if (!memo.has(key)) memo.set(key, run(d, c));
  return memo.get(key)!;
};

/** How many writes each step made. */
const perStep = (ws: Write[]) => ws.reduce((m, x) => m.set(x.step, (m.get(x.step) ?? 0) + 1), new Map<string, number>());

/** Throws unless the run reached Done with the device set up. */
function reachedDone(r: Result) {
  const last = r.stops.at(-1)!;
  expect(last, 'the run ends on Done').toMatchObject({ end: 'done' });
  expect(last.goal, 'Done with the device set up (the app in, and the home screen)').toBe(true);
  expect(r.partial(), 'no partition left partly written').toEqual([]);
}

/**
 * Throws if a step that had finished before `step` wrote again. Writes, not steps: running a read-only step again
 * (a download, a loader in RAM) is allowed, as the S1 boots FEL again after a drop.
 */
async function noFinishedStepRedone(d: Driver, step: string, r: Result) {
  const steps = flowData(d.frame, d.flow).steps.map((s) => s.id);
  const base = perStep((await result(d, cases(d)[0]!)).writes);
  const got = perStep(r.writes);
  for (const s of steps.slice(0, steps.indexOf(step))) expect(got.get(s) ?? 0, `writes in ${s}, a step that had finished`).toBe(base.get(s) ?? 0);
}

/** Try again lands on the step that stopped, or the one before it that enters its transport: never an earlier input screen, never past it. */
function landsOn(steps: string[], step: string, landed: string | undefined) {
  const at = steps.indexOf(step);
  expect(steps.slice(Math.max(0, at - 1), at + 1), `Try again lands on ${step}'s screen or the one before it, not on ${landed}`).toContain(landed);
}

async function contract(d: Driver, c: Case) {
  const r = await result(d, c);
  const steps = flowData(d.frame, d.flow).steps.map((s) => s.id);
  switch (c.kind) {
    case 'clean':
    case 'auth-refused-while-booting':
    case 'no-touch':
      reachedDone(r);
      expect(r.stops.length, 'no error screen on the way').toBe(1);
      if (c.kind === 'auth-refused-while-booting') expect(r.fired.refused, 'every refusal was asked through').toBe(c.knobs.refuseAuth);
      return;
    case 'usb-drop':
      expect(r.stops[0]!.end, 'an error screen, not a hang or a silent Done').toBe('error');
      landsOn(steps, c.step!, r.landed);
      reachedDone(r);
      await noFinishedStepRedone(d, c.step!, r);
      return;
    case 'stalled-download':
      expect(r.stops[0]!.end, 'the stall ends with an error the person can retry, not a page that waits for ever').toBe('error');
      landsOn(steps, c.step!, r.landed);
      reachedDone(r);
      return;
    case 'reload':
      if (r.wroteBeforeReload) expect(r.remembered, 'after the reload, the page knows the device may have been changed').toBe(true);
      reachedDone(r);
      await noFinishedStepRedone(d, c.step!, r);
      return;
  }
}

function harness(c: Case, r: Result) {
  switch (c.kind) {
    case 'auth-refused-while-booting': expect(r.fired.refused).toBeGreaterThan(0); break;
    case 'usb-drop': expect(r.fired.dropped).toBe(true); break;
    case 'stalled-download': expect(r.fired.stalled).toBe(true); break;
    case 'reload': expect(r.fired.reloaded).toBe(true); expect(r.stops[0]!.end).toBe('reload'); break;
    default: break;
  }
}

beforeEach(() => { try { sessionStorage.clear(); } catch { /* none */ } });
afterAll(() => { memo.clear(); });

for (const d of DRIVERS) {
  describe(`${d.id} (${d.flow})`, () => {
    for (const c of cases(d)) {
      it(`harness: ${c.id} injects its failure`, async () => harness(c, await result(d, c)), 20_000);
      const why = mark(d, c);
      if (why) it.fails(`contract: ${c.id}: ${c.what} [expected failure: ${why}]`, () => contract(d, c), 20_000);
      else it(`contract: ${c.id}: ${c.what}`, () => contract(d, c), 20_000);
    }
  });
}

describe('the marks', () => {
  it('each names a driver and a case it runs, and the issue that fixes it', () => {
    const known = new Map(DRIVERS.map((d) => [d.id, new Set(cases(d).map((c) => c.id))]));
    const stray: string[] = [];
    for (const [id, m] of Object.entries(MARKS)) {
      if (id.startsWith('$')) continue;
      for (const [c, why] of Object.entries(m)) {
        if (!known.get(id)?.has(c)) stray.push(`${id} ${c}`);
        if (!/#\d+/.test(why)) stray.push(`${id} ${c}: no issue in "${why}"`);
      }
    }
    expect(stray).toEqual([]);
  });

  it('the S1, the reference, has none', () => {
    expect(MARKS['aqara-s1-plus'] ?? {}).toEqual({});
  });
});
