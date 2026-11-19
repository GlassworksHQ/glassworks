// Development and preview only (/lululemon-mirror?demo=flash-keep…, flash-done-…): keeping Home Assistant's key over a
// reflash (issue 580) against a simulated Mirror. Each state runs the real FlashSession: the app's key socket is the
// recipe's fake, holding a made-up key (never a real one). Two states that wait on the Mirror are set directly.
import { AdbAuthTimeout, type PanelShell } from '@glassworks/adb';
import { FakeKeySocket } from '@glassworks/app-adb/fakes';
import { FakeMirror, FirstBootMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import type { FlashSession } from './flash';
import { flashDemo } from './flashDemo';
import type { MirrorSession } from './session';

export const KEEP_DEMOS = ['flash-keep', 'flash-keep-reading', 'flash-keep-held', 'flash-keep-none', 'flash-keep-unreachable', 'flash-keep-old',
  'flash-keep-working', 'flash-keep-adb', 'flash-done-kept', 'flash-done-asks', 'flash-done-refused'] as const;
export type KeepDemo = (typeof KEEP_DEMOS)[number];

/** A made-up ESPHome key (32 bytes 1…32, base64): the fakes' stand-in for the one Home Assistant set. */
export const DEMO_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => i + 1)));
/** Another made-up key: the fresh app holds this one already, so it refuses DEMO_KEY. */
const OTHER_KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 64 + i)));

/** The running Mirror before the flash: Glassworks with the key socket (or without it, an app from before issue 574). */
function running(name: KeepDemo): PanelShell {
  const m = new FakeMirror({ installed: 600 });
  if (name !== 'flash-keep-old') m.withKeySocket(new FakeKeySocket(name === 'flash-keep-none' ? undefined : DEMO_KEY));
  return Object.assign(m, { rebootToBootloader: async () => {} });
}

/** The first start after the flash: the fresh app's socket, empty (or holding another key, so it refuses). */
function firstStart(name: KeepDemo): PanelShell {
  return new FirstBootMirror().withKeySocket(new FakeKeySocket(name === 'flash-done-refused' ? OTHER_KEY : undefined));
}

export async function keepDemo(name: KeepDemo, fast = false): Promise<{ flash: FlashSession; mirror: MirrorSession }> {
  const d = await flashDemo('flash-ready', fast, {
    // a browser that has flashed a Mirror before: Continue opens the keep step
    flashedHere: { get: () => true, set: () => {} },
    // with authMs: the read before the flash; without: after it
    requestAdb: async (o) => {
      if (o?.authMs === undefined) return firstStart(name);
      if (name === 'flash-keep-unreachable' || name === 'flash-done-asks') throw new AdbAuthTimeout();
      return running(name);
    },
  });
  const f = d.flash;
  f.toFastboot();
  if (name === 'flash-keep') return d;
  if (name === 'flash-keep-reading') { f.state = { ...f.state, key: { state: 'reading', at: 1 } }; return d; } // the read waits on the Mirror
  await f.readKey();
  if (['flash-keep-held', 'flash-keep-none', 'flash-keep-unreachable', 'flash-keep-old'].includes(name)) return d;
  if (name === 'flash-done-asks') f.skipKeep(); // Reflash anyway: fastboot by hand
  await f.connectFastboot();
  f.understand(true);
  await f.flash();
  if (name === 'flash-keep-adb') return d;
  if (name === 'flash-keep-working') { f.state = { ...f.state, screen: 'working', work: { step: 'hand-back', x: 0 } }; return d; } // the hand-back waits on the app
  await f.connectAdb();
  return d;
}
