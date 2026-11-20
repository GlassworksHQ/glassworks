// Development and preview only: where a device frame's demo stops in the standard flow, once its install handed the
// simulated device over (device/demo.ts, imageDemo.ts, keepDemo.ts). The standard flow starts at its App step.
import type { StandardSession } from './session';

/** The standard flow's demo stops, in order. */
export type StandardStop = 'app' | 'wifi' | 'home-assistant' | 'panes' | 'done';

/** Walks `s` from the App step to `to`, as the person's clicks would, without one on the device. */
export async function standardDemo(s: StandardSession | undefined, to: StandardStop): Promise<void> {
  if (!s || to === 'app') return;
  await s.click('wifi', () => s.wifi());
  if (to === 'wifi') return;
  await s.homeAssistant();
  if (to === 'home-assistant') return;
  if (to === 'panes') { await s.panes(); return; }
  s.finish();
}
