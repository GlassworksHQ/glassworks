// The Mirror as the tests use it: its declaration and simulated device from its installer package, as the frames
// registry loads them (packages/frames), its route, and its timings.
import type { PanelShell } from '@glassworks/adb';
import type { DeviceEntry } from '@glassworks/kiln-core';
import { kilnFrame } from '@glassworks/lululemon-mirror-installer/kiln';
import { kilnSim } from '@glassworks/lululemon-mirror-installer/sim';
import { AppSession, type AppDeps } from '../src/device/appSession';
import { standardOfDevice } from '../src/standard/frame';
import { StandardSession } from '../src/standard/session';
import { withAppStreams } from '../src/wizard/demo';

export const mirrorFrame = kilnFrame;
export const mirrorEntry: DeviceEntry = { kind: 'device', frame: kilnFrame, sim: kilnSim };
export const MIRROR_BASE = '/lululemon-mirror';
export const { authMs: AUTH_MS, firstStartAuthMs: FIRST_START_AUTH_MS, readMs: READ_MS } = kilnFrame.timings;

/** The Mirror's standard flow on `p`, as its page makes it at the hand-over: no wait for the app's socket. */
export function standardOn(p: PanelShell, o: { sleep?: (ms: number) => Promise<void> } = {}): StandardSession {
  return new StandardSession({
    standard: standardOfDevice(kilnFrame), devices: { knownAdb: async () => p, requestAdb: async () => p },
    loadKey: async () => ({ publicKey: 'QAAAAtest kiln@test' }) as never, sleep: o.sleep ?? (async () => {}), simulated: true,
  });
}

/**
 * A simulated Mirror for the app alone over adb, answering set-up as Glassworks would (its setup channel and Improv),
 * and a session on it. The install hands the device to the standard flow (`standard()`, as the page does); `setUp()`
 * walks it through Home Assistant to Done.
 */
export function appOn(m: PanelShell, o: Partial<AppDeps> = {}) {
  let standard: StandardSession | undefined;
  const s = new AppSession({
    frame: kilnFrame, connect: async () => withAppStreams(m, { online: true, radio: false }), sleep: async () => {},
    handOver: (p) => { standard = standardOn(p); void standard.handOver(p); }, ...o,
  }, true);
  return { s, standard: () => standard!, setUp: async () => { await standard!.homeAssistant(); standard!.finish(); } };
}

/** The page's hand-over for a demo or a test: the Mirror's standard flow on the device, started (`standard()` after it). */
export function handTo() {
  let standard: StandardSession | undefined;
  return {
    handOver: (p: PanelShell) => { standard = standardOn(p); void standard.handOver(p); return standard; },
    standard: () => standard!,
  };
}
