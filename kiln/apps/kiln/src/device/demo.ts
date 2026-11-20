// Development and preview only (<route>?demo=…): the app alone over adb on a device frame's simulated device (its
// `kilnSim`), behind the real AppSession, so each screen can be seen and captured without one. `run` waits for clicks
// like the real page; the other names go to one screen. The device answers set-up as Glassworks would (its setup
// channel and Improv, wizard/demo.ts). Nothing here taps the device.
import type { DeviceEntry } from '@glassworks/kiln-core';
import type { PanelShell } from '@glassworks/adb';
import { IMPROV_SOCKET } from '@glassworks/app-adb';
import { SETUP_SOCKET } from '@glassworks/setup-channel';
import { fakeReleaseRoute } from '../release/demo';
import { withAppStreams } from '../wizard/demo';
import { standardDemo } from '../standard/demo';
import type { StandardSession } from '../standard/session';
import { AppSession, releaseLine } from './appSession';

export const APP_DEMOS = ['run', 'pick', 'pick-release', 'pick-wrong', 'checks', 'checks-fail', 'installing', 'setup', 'setup-wifi', 'setup-ha', 'setup-panes', 'setup-panes-refused', 'setup-error', 'done', 'done-unconfirmed', 'undo-done', 'undo-pending', 'error'] as const;
export type AppDemo = (typeof APP_DEMOS)[number];

/**
 * A device whose app stops answering on its sockets (the setup channel and Improv, the open ones too) once `cut()`, until
 * `heal()`: the app came back, so Try again can get through.
 */
export function cuttable(p: PanelShell): { shell: PanelShell; cut(): void; heal(): void } {
  let cut = false;
  return {
    cut: () => { cut = true; },
    heal: () => { cut = false; },
    shell: {
      ...p,
      openStream: async (svc) => {
        if (svc !== SETUP_SOCKET && svc !== IMPROV_SOCKET) return p.openStream!(svc);
        if (cut) throw new Error('closed');
        const io = await p.openStream!(svc);
        return { onData: (cb) => io.onData(cb), close: () => io.close(), write: (d) => (cut ? Promise.reject(new Error('closed')) : io.write(d)) };
      },
    },
  };
}

/**
 * `fast`: no delays (the tests). `handOver`: the page's, which gives the installed device to the standard flow (setup…
 * and done go on there: setup is its App step, setup-wifi Wi-Fi, setup-ha Home Assistant, setup-panes Panes).
 */
export async function appDemo({ frame, sim }: Pick<DeviceEntry, 'frame' | 'sim'>, name: AppDemo, fast = false, handOver?: (p: PanelShell) => StandardSession | undefined): Promise<AppSession> {
  // setup-wifi: not on Wi-Fi yet, so set-up asks which network
  // setup-panes: the Panes mockup (issue 621, for approval), an app that offers the step
  const panes = name.startsWith('setup-panes')
    ? { cards: sim.panes ?? [], refuse: name === 'setup-panes-refused' ? 'Voice couldn\'t open the microphone. Try again, or leave Voice off.' : undefined }
    : undefined;
  const streams = withAppStreams(await sim.appDevice(name, { fast }), { online: name !== 'setup-wifi', radio: false, panes });
  // setup-error: the app stops answering on its sockets after the App step (below)
  const cutting = cuttable(streams);
  const device = name === 'setup-error' ? cutting.shell : streams;
  const release = name === 'pick-release' ? { release: fakeReleaseRoute('out', sim.apk(), (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), releaseLine(frame).line) } : {};
  let standard: StandardSession | undefined;
  const s = new AppSession({ frame, connect: async () => device, handOver: (p) => { standard = handOver?.(p); }, ...release, ...(fast ? { sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms / 100)) } : {}) }, true);
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
  if (s.state.screen === 'error') return s; // error: the install stopped
  // setup-error: the app stops answering after the App step, so Wi-Fi can't reach it ("Couldn't reach Glassworks", Try
  // again). It is back once that shows, so Try again goes through (test/mirror-set-up)
  if (name === 'setup-error' && standard) {
    cutting.cut();
    await standard.installApp({ clock24: true, keyboard: false });
    cutting.heal();
    return s;
  }
  await standardDemo(standard, name === 'setup' ? 'app' : name === 'setup-wifi' ? 'wifi' : name === 'setup-ha' ? 'home-assistant' : panes ? 'panes' : 'done');
  return s;
}
