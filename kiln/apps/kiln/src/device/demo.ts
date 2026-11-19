// Development and preview only (<route>?demo=…): the app alone over adb on a device frame's simulated device (its
// `kilnSim`), behind the real AppSession, so each screen can be seen and captured without one. `run` waits for clicks
// like the real page; the other names go to one screen. Nothing here taps the device.
import type { DeviceEntry } from '@glassworks/kiln-core';
import { fakeReleaseRoute } from '../release/demo';
import { AppSession, releaseLine } from './appSession';

export const APP_DEMOS = ['run', 'pick', 'pick-release', 'pick-wrong', 'checks', 'checks-fail', 'installing', 'done', 'done-unconfirmed', 'undo-done', 'undo-pending', 'error'] as const;
export type AppDemo = (typeof APP_DEMOS)[number];

/** `fast`: no delays (the tests). */
export async function appDemo({ frame, sim }: Pick<DeviceEntry, 'frame' | 'sim'>, name: AppDemo, fast = false): Promise<AppSession> {
  const device = await sim.appDevice(name, { fast });
  const release = name === 'pick-release' ? { release: fakeReleaseRoute('out', sim.apk(), (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), releaseLine(frame).line) } : {};
  const s = new AppSession({ frame, connect: async () => device, ...release, ...(fast ? { sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms / 100)) } : {}) }, true);
  if (name === 'pick-wrong') { await s.pickApk(sim.wrongApkName, sim.wrongApk()); return s; }
  if (name === 'pick-release') return s; // the screen downloads it
  if (name !== 'run') await s.pickApk(sim.apkName, sim.apk());
  if (name === 'run' || name === 'pick') return s;
  await s.connect();
  if (name === 'checks' || name === 'checks-fail') return s;
  if (name === 'installing') { void s.install(); return s; } // the device's copy never ends
  s.setHaUrl('http://homeassistant.local:8123');
  if (name === 'undo-done') { await s.undo(); return s; }
  if (name === 'undo-pending') { await s.undo(); await s.again(); return s; }
  await s.install();
  return s;
}
