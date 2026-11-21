// Development and preview only (<route>?demo=…): a UsbFrame's simulated device (its UsbSim) behind the real recipe, so
// each screen can be seen and captured without one. `run` waits for clicks like the real page; the other names go to
// one screen.
import type { UsbFrame, UsbInstalled, UsbSim } from '@glassworks/kiln-core';
import { fakeReleaseRoute, type FakeRelease } from '../release/demo';
import { standardDemo } from '../standard/demo';
import type { StandardSession } from '../standard/session';
import { withAppStreams } from '../wizard/demo';
import { UsbSession, type Device } from './usbSession';

export const USB_DEMOS = [
  'run', 'find', 'find-release', 'find-release-none', 'find-release-bad', 'undo-find', 'named', 'brand', 'debug', 'debug-generic', 'allow', 'installing', 'vendor', 'setup', 'setup-wifi', 'setup-ha', 'setup-panes', 'done', 'done-untested', 'done-old', 'done-refused', 'done-lock', 'done-32bit', 'undo-done', 'error', 'own-frame',
] as const;
export type UsbDemo = (typeof USB_DEMOS)[number];

/**
 * `fast`: no delays (the tests). `handOver`: the page's, which gives the installed device to the standard flow (setup is
 * its App step, setup-wifi Wi-Fi, setup-ha Home Assistant, setup-panes Panes, done… its Done).
 */
export async function usbDemo(frame: UsbFrame, sim: UsbSim, name: UsbDemo, fast = false, handOver?: (p: Device, installed: UsbInstalled) => StandardSession | undefined): Promise<UsbSession> {
  const d = sim.demo(name, { fast });
  // the app answers set-up over its setup channel and Improv once it is on (setup-wifi: not on Wi-Fi yet)
  // (only with a page to hand over to: without, the install hands over to the device's own setup page, as the fake answers)
  const device: Device = handOver
    ? Object.assign(withAppStreams(d.device, { online: name !== 'setup-wifi', radio: false, ...(name === 'setup-panes' && sim.panes ? { panes: { cards: sim.panes } } : {}) }), { app: d.device.app })
    : d.device;
  // find-release…: the APK from a stand-in release of the frame's line (?apk=release); the first screen downloads it.
  // done-32bit: the release on a device whose ABI isn't the line's default, through to Done (issue 700)
  const kind = ({ 'find-release': 'out', 'find-release-none': 'none', 'find-release-bad': 'bad', 'done-32bit': 'out' } as Partial<Record<UsbDemo, FakeRelease>>)[name];
  let standard: StandardSession | undefined;
  const s = new UsbSession({
    frame,
    findUsb: async () => d.usb,
    connect: () => (name === 'allow' ? new Promise(() => {}) : Promise.resolve(device)),
    sleep: (ms) => new Promise((r) => setTimeout(r, fast ? 0 : Math.min(ms, 400))),
    homeTimeoutMs: 10 * 60_000,
    setupTries: 2,
    ...(handOver ? { handOver: (p: Device, i: UsbInstalled) => { standard = handOver(p, i); } } : {}),
    ...(kind ? { release: fakeReleaseRoute(kind, d.release ?? d.apk, (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), frame.apk.line) } : {}),
  }, true);
  if (kind && name !== 'done-32bit') return s;
  if (kind) await s.loadRelease();
  else await s.pickApk(d.apkName, d.apk);
  if (name === 'run' || name === 'find') return s;
  if (name === 'undo-done' || name === 'undo-find') s.setFlow('undo');
  if (name === 'undo-find') return s;
  await s.find();
  if (name === 'named' || name === 'brand') return s;
  if (!d.known) { s.pickBrand('other'); s.chooseBrand(); } else s.toSteps();
  if (name === 'debug' || name === 'debug-generic') return s;
  const done = s.connect();
  if (name === 'allow' || name === 'installing' || name === 'vendor') return s;
  await done;
  if (!standard) return s; // an undo, an error, or no page to hand over to (the tests)
  await standardDemo(standard, name === 'setup' ? 'app' : name === 'setup-wifi' ? 'wifi' : name === 'setup-ha' ? 'home-assistant' : name === 'setup-panes' ? 'panes' : 'done');
  return s;
}
