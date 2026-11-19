// The contract's drivers, its cases and the marks of today's expected failures, shared by contract.test.ts and
// gate.test.ts (#621). A new Kiln page needs a driver here: gate.test.ts fails without one.
import marks from './expected-failures.json';
import { android } from './android';
import { flowData, type Driver, type Knobs } from './harness';
import { mirrorApp, mirrorFastboot } from './mirror';
import { s1 } from './s1';

export const DRIVERS: Driver[] = [s1, mirrorFastboot, mirrorApp, android];

export type CaseKind = 'clean' | 'auth-refused-while-booting' | 'usb-drop' | 'reload' | 'stalled-download' | 'no-touch';
export interface Case { id: string; kind: CaseKind; knobs: Knobs; what: string; step?: string }

/** The cases a flow runs, from its installer.json: a reload in each step, a drop in its first write, a stall if it downloads. */
export function cases(d: Driver): Case[] {
  const f = flowData(d.frame, d.flow);
  const write = f.steps.find((s) => s.writes);
  const out: Case[] = [
    { id: 'clean', kind: 'clean', knobs: {}, what: 'nothing goes wrong: it reaches Done' },
    { id: 'auth-refused-while-booting', kind: 'auth-refused-while-booting', knobs: { refuseAuth: 3 },
      what: 'adb refuses 3 times as the device comes up: it keeps asking and reaches Done, with no extra click' },
    ...(write ? [{ id: 'usb-drop', kind: 'usb-drop' as const, knobs: { dropUsbIn: write.id }, step: write.id,
      what: `USB drops in ${write.id}'s first write: an error, Try again on that step's screen (or the one that enters its transport), then Done without redoing a finished step` }] : []),
    ...f.steps.map((s): Case => ({ id: `reload@${s.id}`, kind: 'reload', knobs: { reloadIn: s.id }, step: s.id,
      what: `the page reloads during ${s.id}: it knows a write may have happened, and reaches Done without redoing a finished step` })),
    ...(f.steps.some((s) => s.downloads?.length) ? [{ id: 'stalled-download', kind: 'stalled-download' as const, knobs: { stall: true },
      what: 'the first download stops sending: a watchdog ends it with an error, and Try again reaches Done' }] : []),
    { id: 'no-touch', kind: 'no-touch', knobs: { noTouch: true },
      what: `nobody touches the device beyond the taps the flow declares (${f.steps.flatMap((s) => s.taps ?? []).join(', ') || 'none'}): it reaches Done` },
  ];
  return out;
}


export const MARKS = marks as unknown as Record<string, Record<string, string>>;
/** The expected-failure mark of a case, if it has one. */
export const mark = (d: Driver, c: Case): string | undefined => MARKS[d.id]?.[c.id];
