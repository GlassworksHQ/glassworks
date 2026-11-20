// The standard flow (issue 621): the steps every frame runs once its install is through, the same screens in the same
// order: App (the app's settings), Wi-Fi, Home Assistant, Panes, Radio, then Done. The engine owns them, their routes
// and their resume. A frame changes the list only in its data: a step its hardware can't use goes (no radio in its
// frame.json, no Radio step), and its installer.json `setUp.remove` names any other it leaves out, each with why.
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import { FRAMES, INSTALLERS } from '@glassworks/frames/catalog';
import type { AskCopy, DeviceFrame, KilnFrame, SilentWords, StepCredits } from '@glassworks/kiln-core';

/** The standard flow's steps, in order, by their routes' names (`set-up` is the App step). Done follows. */
export const STANDARD_STEPS = ['set-up', 'wifi', 'home-assistant', 'panes', 'radio'] as const;
export type StandardStep = (typeof STANDARD_STEPS)[number];

/** What the standard flow reads of a frame, whichever way its install went. */
export interface StandardFrame {
  id: string;
  name: string;
  short: string;
  /** how a sentence names it, with its article: `the panel` */
  the: string;
  /** the app's name as people see it */
  app: string;
  appId: AppId;
  /** Its screen takes a tap. Without one, no step offers "do it on the panel", and the App step has no on-screen keyboard. */
  touch: boolean;
  /** The steps it runs: STANDARD_STEPS less what its data leaves out (standardSteps). */
  steps: readonly StandardStep[];
  /** The browser's list when set-up connects to it over adb. */
  connect: AskCopy;
  credits: StepCredits;
  /** Done's Silent updates block, by the state `updates` reads. */
  silent: Record<string, SilentWords>;
  /** The Wi-Fi network it is on, read-only. */
  wifiName(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }>;
  /** Whether updates install with nobody at it: one of `silent`'s states, and a line for the log. Read-only. */
  updates(p: PanelShell, signal?: AbortSignal): Promise<{ state: string; log: string }>;
}

/** What a frame's data says of the standard flow: whether it has a radio (frame.json), and installer.json `setUp`. */
export interface StandardData { radio: boolean; setUp?: { remove?: Record<string, unknown> } }

/**
 * The steps a frame runs: every one, less Radio on a frame without a radio, less each step its installer.json
 * `setUp.remove` names. A name that isn't a step, or a removal that doesn't say why, is refused where it comes in.
 */
export function standardSteps(d: StandardData): StandardStep[] {
  const remove = d.setUp?.remove ?? {};
  for (const [k, why] of Object.entries(remove)) {
    if (!(STANDARD_STEPS as readonly string[]).includes(k)) throw new Error(`installer.json setUp.remove.${k}: not a step of the standard flow (${STANDARD_STEPS.join(', ')})`);
    if (typeof why !== 'string' || !why) throw new Error(`installer.json setUp.remove.${k}: say why`);
  }
  return STANDARD_STEPS.filter((s) => (s !== 'radio' || d.radio) && !(s in remove));
}

/** The frame's data the standard flow reads, from the catalog: its radio (frame.json `panes.radio`) and installer.json. */
function dataOf(id: string): { touch: boolean; steps: StandardStep[] } {
  const radio = (FRAMES.find((f) => f.id === id)?.panes?.radio?.support ?? 'no') !== 'no';
  const i = INSTALLERS[id];
  return { touch: i?.touch !== false, steps: standardSteps({ radio, setUp: i?.setUp }) };
}

/** A frame the wizard installs (KilnFrame: a recipe written over FEL and fastboot) as the standard flow reads it. */
export function standardOf(k: KilnFrame): StandardFrame {
  return {
    id: k.id, name: k.name, short: k.short, the: k.the ?? `the ${k.short}`, app: k.app, appId: k.appId,
    connect: k.asks.connect, credits: k.credits, silent: k.silent,
    wifiName: (p, signal) => k.wifiName(p, signal), updates: (p, signal) => k.updates(p, signal),
    ...dataOf(k.id),
  };
}

/** A device frame (an image over fastboot, or the app alone over adb) as the standard flow reads it. */
export function standardOfDevice(d: DeviceFrame): StandardFrame {
  return {
    id: d.id, name: d.name, short: d.short, the: d.the, app: d.app.name, appId: d.app,
    connect: d.connect, credits: d.credits, silent: d.silent,
    wifiName: (p, signal) => (d.wifiName ? d.wifiName(p, signal) : Promise.resolve({})),
    // a frame that doesn't say can't tell: its `silent` words for `unknown`
    updates: (p, signal) => (d.updates ? d.updates(p, signal) : Promise.resolve({ state: 'unknown', log: 'silent updates: not read' })),
    ...dataOf(d.id),
  };
}
