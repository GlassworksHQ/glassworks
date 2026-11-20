// Development and preview only (<route>?demo=…): the app alone over adb on a device frame's simulated device (its
// `kilnSim`), behind the real AppSession, so each screen can be seen and captured without one. `run` waits for clicks
// like the real page; the other names go to one screen. The device answers set-up as Glassworks would (its setup
// channel and Improv, wizard/demo.ts). Nothing here taps the device.
import type { DeviceEntry } from '@glassworks/kiln-core';
import type { PanelShell } from '@glassworks/adb';
import { SETUP_SOCKET } from '@glassworks/setup-channel';
import { fakeReleaseRoute } from '../release/demo';
import { withAppStreams } from '../wizard/demo';
import { AppSession, releaseLine } from './appSession';

export const APP_DEMOS = ['run', 'pick', 'pick-release', 'pick-wrong', 'checks', 'checks-fail', 'installing', 'setup', 'setup-ha', 'setup-error', 'done', 'done-unconfirmed', 'undo-done', 'undo-pending', 'error'] as const;
export type AppDemo = (typeof APP_DEMOS)[number];

/** A device whose app stops answering on its setup channel once `cut()` (the open channel too). */
export function cuttable(p: PanelShell): { shell: PanelShell; cut(): void } {
  let cut = false;
  return {
    cut: () => { cut = true; },
    shell: {
      ...p,
      openStream: async (svc) => {
        if (svc !== SETUP_SOCKET) return p.openStream!(svc);
        if (cut) throw new Error('closed');
        const io = await p.openStream!(svc);
        return { onData: (cb) => io.onData(cb), close: () => io.close(), write: (d) => (cut ? Promise.reject(new Error('closed')) : io.write(d)) };
      },
    },
  };
}

/** `fast`: no delays (the tests). */
export async function appDemo({ frame, sim }: Pick<DeviceEntry, 'frame' | 'sim'>, name: AppDemo, fast = false): Promise<AppSession> {
  // setup: not on Wi-Fi yet, so set-up asks which network
  const streams = withAppStreams(await sim.appDevice(name, { fast }), { online: name !== 'setup', radio: false });
  // setup-error: the app stops answering on its setup channel after Home Assistant's step
  const cutting = cuttable(streams);
  const device = name === 'setup-error' ? cutting.shell : streams;
  const release = name === 'pick-release' ? { release: fakeReleaseRoute('out', sim.apk(), (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), releaseLine(frame).line) } : {};
  const s = new AppSession({ frame, connect: async () => device, simulated: true, ...release, ...(fast ? { sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms / 100)) } : {}) }, true);
  if (name === 'pick-wrong') { await s.pickApk(sim.wrongApkName, sim.wrongApk()); return s; }
  if (name === 'pick-release') return s; // the screen downloads it
  if (name !== 'run') await s.pickApk(sim.apkName, sim.apk());
  if (name === 'run' || name === 'pick') return s;
  await s.connect();
  if (name === 'checks' || name === 'checks-fail') return s;
  if (name === 'installing') { void s.install(); return s; } // the device's copy never ends
  if (name === 'undo-done') { await s.undo(); return s; }
  if (name === 'undo-pending') { await s.undo(); await s.again(); return s; }
  await s.install();
  if (name === 'setup' || s.state.screen !== 'setup') return s; // error: the install stopped
  await s.setUp.homeAssistant();
  if (name === 'setup-ha') return s;
  if (name === 'setup-error') { cutting.cut(); await s.setUp.skipHa(''); return s; }
  s.setUp.finish(); // no radio on this app: Home Assistant is set-up's last step
  return s;
}
