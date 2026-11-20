// Development and preview only (<route>?demo=flash-keep…, flash-done-…): keeping Home Assistant's key over a reflash
// (issue 580) against a device frame's simulated device (its `kilnSim`). Each state runs the real ImageSession: the
// app's key socket is the frame's fake, holding a made-up key (never a real one). Two states that wait on the device are
// set directly.
import { AdbAuthTimeout, type PanelShell } from '@glassworks/adb';
import type { DeviceEntry } from '@glassworks/kiln-core';
import type { AppSession } from './appSession';
import type { ImageSession } from './imageSession';
import { imageDemo } from './imageDemo';
import { standardDemo } from '../standard/demo';
import type { StandardSession } from '../standard/session';
import { withAppStreams } from '../wizard/demo';

export const KEEP_DEMOS = ['flash-keep', 'flash-keep-reading', 'flash-keep-held', 'flash-keep-none', 'flash-keep-unreachable', 'flash-keep-old',
  'flash-keep-working', 'flash-keep-adb', 'flash-done-kept', 'flash-done-asks', 'flash-done-refused'] as const;
export type KeepDemo = (typeof KEEP_DEMOS)[number];

/** A made-up ESPHome key (32 bytes 1…32, base64): the fakes' stand-in for the one Home Assistant set. */
export const DEMO_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => i + 1)));
/** Another made-up key: the fresh app holds this one already, so it refuses DEMO_KEY. */
const OTHER_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 64 + i)));

/** `handOver`: the page's (imageDemo); flash-done-… end on the standard flow's Done, with the key's outcome under it. */
export async function keepDemo(d: Pick<DeviceEntry, 'frame' | 'sim'>, name: KeepDemo, fast = false, handOver?: (p: PanelShell) => StandardSession | undefined): Promise<{ image: ImageSession; app: AppSession }> {
  const { frame, sim } = d;
  /** the running device before the flash: Glassworks with the key socket (or without it, an app from before issue 574) */
  const running = (): PanelShell => sim.running({ socket: name !== 'flash-keep-old', key: name === 'flash-keep-none' ? undefined : DEMO_KEY });
  /** the first start after the flash: the fresh app's socket, empty (or holding another key, so it refuses) */
  const firstStart = (): PanelShell => sim.firstStart({ socket: true, key: name === 'flash-done-refused' ? OTHER_KEY : undefined });
  const out = await imageDemo(d, 'flash-ready', fast, {
    // a browser that has flashed this device before: Continue opens the keep step
    flashedHere: { get: () => true, set: () => {} },
    // authMs: the read before the flash; firstStartAuthMs: the first start after it
    requestAdb: async (o) => {
      if (o?.authMs !== frame.timings.authMs) return withAppStreams(firstStart(), { online: true, radio: false });
      if (name === 'flash-keep-unreachable' || name === 'flash-done-asks') throw new AdbAuthTimeout();
      return running();
    },
  }, handOver);
  const f = out.image;
  f.toFastboot();
  if (name === 'flash-keep') return out;
  if (name === 'flash-keep-reading') { f.state = { ...f.state, key: { state: 'reading', at: 1 } }; return out; } // the read waits on the device
  await f.readKey();
  if (['flash-keep-held', 'flash-keep-none', 'flash-keep-unreachable', 'flash-keep-old'].includes(name)) return out;
  if (name === 'flash-done-asks') f.skipKeep(); // Reflash anyway: fastboot by hand
  await f.connectFastboot();
  f.understand(true);
  await f.flash();
  if (name === 'flash-keep-adb') return out;
  if (name === 'flash-keep-working') { f.state = { ...f.state, screen: 'working', work: { step: 'hand-back', x: 0 } }; return out; } // the hand-back waits on the app
  await f.connectAdb();
  await standardDemo(out.standard?.(), 'done'); // set-up's steps are the standard flow's; these states are about the key
  return out;
}
