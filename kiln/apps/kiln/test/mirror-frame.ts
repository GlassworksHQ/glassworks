// The Mirror as the tests use it: its declaration and simulated device from its installer package, as the frames
// registry loads them (packages/frames), its route, and its timings.
import type { PanelShell } from '@glassworks/adb';
import type { DeviceEntry } from '@glassworks/kiln-core';
import { kilnFrame } from '@glassworks/lululemon-mirror-installer/kiln';
import { kilnSim } from '@glassworks/lululemon-mirror-installer/sim';
import { AppSession, type AppDeps } from '../src/device/appSession';
import { withAppStreams } from '../src/wizard/demo';

export const mirrorFrame = kilnFrame;
export const mirrorEntry: DeviceEntry = { kind: 'device', frame: kilnFrame, sim: kilnSim };
export const MIRROR_BASE = '/lululemon-mirror';
export const { authMs: AUTH_MS, firstStartAuthMs: FIRST_START_AUTH_MS, readMs: READ_MS } = kilnFrame.timings;

/**
 * A simulated Mirror for the app alone over adb, answering set-up as Glassworks would (its setup channel and Improv),
 * and a session on it that needs no wait for the app's socket. `setUp()`: set-up through Home Assistant to Done.
 */
export function appOn(m: PanelShell, o: Partial<AppDeps> = {}) {
  const s = new AppSession({ frame: kilnFrame, connect: async () => withAppStreams(m, { online: true, radio: false }), sleep: async () => {}, simulated: true, ...o }, true);
  return { s, setUp: async () => { await s.setUp.homeAssistant(); s.setUp.finish(); } };
}
