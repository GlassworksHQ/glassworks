// The contract's drivers, its cases and the marks of today's expected failures, shared by contract.test.ts and
// gate.test.ts (#621). A new Kiln page needs a driver here: gate.test.ts fails without one.
import marks from './expected-failures.json';
import { android } from './android';
import { flowData, type Driver, type Knobs } from './harness';
import { mirrorApp, mirrorFastboot, mirrorInPlace } from './mirror';
import { s1 } from './s1';

export const DRIVERS: Driver[] = [s1, mirrorFastboot, mirrorInPlace, mirrorApp, android];

export type CaseKind = 'clean' | 'auth-refused-while-booting' | 'usb-drop' | 'reload' | 'stalled-download' | 'no-touch' | 'channel-drop';
export interface Case { id: string; kind: CaseKind; knobs: Knobs; what: string; step?: string }

/** The cases a flow runs, from its installer.json: a reload in each step, a drop in its first write, a stall if it downloads. */
export function cases(d: Driver): Case[] {
  const f = flowData(d.frame, d.flow);
  const write = f.steps.find((s) => s.writes);
  const download = f.steps.find((s) => s.downloads?.length);
  const agent = f.steps.find((s) => s.transport === 'agent');
  const out: Case[] = [
    { id: 'clean', kind: 'clean', knobs: {}, what: 'nothing goes wrong: it reaches Done' },
    { id: 'auth-refused-while-booting', kind: 'auth-refused-while-booting', knobs: { refuseAuth: 3 },
      what: 'adb refuses 3 times as the device comes up: it keeps asking and reaches Done, with no extra click' },
    ...(write ? [{ id: 'usb-drop', kind: 'usb-drop' as const, knobs: { dropUsbIn: write.id }, step: write.id,
      what: `USB drops in ${write.id}'s first write: an error, Try again on that step's screen (or the one that enters its transport), then Done without redoing a finished step's writes` }] : []),
    ...f.steps.map((s): Case => ({ id: `reload@${s.id}`, kind: 'reload', knobs: { reloadIn: s.id }, step: s.id,
      what: `the page reloads during ${s.id}: it knows a write may have happened, and reaches Done without redoing a finished step's writes` })),
    ...(download ? [{ id: 'stalled-download', kind: 'stalled-download' as const, knobs: { stall: true }, step: download.id,
      what: `the first download (in ${download.id}) stops sending: a watchdog ends it, and it resumes by itself or ends with an error whose Try again is on that step's screen (or the one before), then Done` }] : []),
    ...(agent ? [{ id: 'channel-drop', kind: 'channel-drop' as const, knobs: { channelDrop: true }, step: agent.id,
      what: `the local network drops halfway through ${agent.id}'s transfer: Kiln reaches Done over Bluetooth with no error screen, by itself or (on a device with its own Wi-Fi) from the screen that offers it` }] : []),
    { id: 'no-touch', kind: 'no-touch', knobs: { noTouch: true },
      what: `nobody touches the device beyond the taps the flow declares (${f.steps.flatMap((s) => s.taps ?? []).join(', ') || 'none'}): it reaches Done` },
  ];
  return out;
}


export const MARKS = marks as unknown as Record<string, Record<string, string>>;
/** The expected-failure mark of a case, if it has one. */
export const mark = (d: Driver, c: Case): string | undefined => MARKS[d.id]?.[c.id];
