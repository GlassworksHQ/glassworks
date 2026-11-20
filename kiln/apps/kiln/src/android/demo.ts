// Development and preview only (/android?demo=…): a simulated Android device behind the real recipe,
// so each screen can be seen and captured without one. `run` waits for clicks like the real page; the
// other names go to one screen. Addresses are RFC 5737 (192.0.2.x).
import { FakeAndroid, fakeGenericApk, type FakeAndroidOptions } from '@glassworks/generic-android-installer/fakes';
import { fakeReleaseRoute, type FakeRelease } from '../release/demo';
import type { GenericInstalled } from '@glassworks/generic-android-installer';
import { standardDemo } from '../standard/demo';
import type { StandardSession } from '../standard/session';
import { withAppStreams } from '../wizard/demo';
import { AndroidSession, type Device, type UsbFacts } from './session';

/** The cards Glassworks' Panes step shows on a tablet (each pane's PaneChoice), for ?demo=setup-panes. */
const PANES = [
  { id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true },
  { id: 'sendspin', title: 'Music', detail: 'A Sendspin speaker', on: true },
  { id: 'notifications', title: 'Notifications', detail: 'On-screen alerts', on: false },
  { id: 'camera', title: 'Camera', detail: 'Presence, and snapshots', on: false },
];

export const ANDROID_DEMOS = [
  'run', 'find', 'find-release', 'find-release-none', 'find-release-bad', 'undo-find', 'named', 'brand', 'debug', 'debug-generic', 'allow', 'installing', 'vendor', 'setup', 'setup-wifi', 'setup-ha', 'setup-panes', 'done', 'done-untested', 'done-old', 'done-refused', 'done-lock', 'undo-done', 'error', 'own-frame',
] as const;
export type AndroidDemo = (typeof ANDROID_DEMOS)[number];

const PIXEL: UsbFacts = { vendorId: 0x18d1, productName: 'Pixel Tablet' };
/** A no-name tablet that keeps its chip maker's vendor ID (Allwinner) */
const NO_NAME: UsbFacts = { vendorId: 0x1f3a, productName: 'Android' };

/** Holds one command forever, so the screen stays on the step that runs it. */
class Held extends FakeAndroid {
  constructor(o: FakeAndroidOptions, private readonly hold: RegExp, private readonly delayMs: number) { super(o); }
  override async run(cmd: string) {
    if (this.hold.test(cmd)) return new Promise<never>(() => {});
    if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
    return super.run(cmd);
  }
}

/**
 * `fast`: no delays (the tests). `handOver`: the page's, which gives the installed tablet to the standard flow (setup is
 * its App step, setup-wifi Wi-Fi, setup-ha Home Assistant, setup-panes Panes, done… its Done).
 */
export async function androidDemo(name: AndroidDemo, fast = false, handOver?: (p: Device, installed: GenericInstalled) => StandardSession | undefined): Promise<AndroidSession> {
  const untested = name === 'done-untested' || name === 'done-old' || name === 'brand' || name === 'debug-generic';
  const o: FakeAndroidOptions = untested
    ? { manufacturer: 'Teclast', model: 'P20HD', release: '13', sdk: 33, ip: '192.0.2.11', picksAfter: 3 }
    : { ip: '192.0.2.10', picksAfter: 3 };
  if (name === 'vendor') Object.assign(o, { refuseSetHome: true, refuseGrant: ['android.permission.WRITE_SECURE_SETTINGS'], picksAfter: undefined });
  if (name === 'undo-done') o.installed = 600;
  // issue 422's Done block: Android 9 (a tap each), and a build that keeps "Install unknown apps" off
  if (name === 'done-old') Object.assign(o, { release: '9', sdk: 28 });
  if (name === 'done-refused') o.keepInstallOpOff = true;
  // issue 522: a tablet with a PIN
  if (name === 'done-lock') o.lock = 'pin';
  // an S1 with USB debugging on, as it reads (AqaraS1Plus.kt, 2026-11-14)
  if (name === 'own-frame') Object.assign(o, { sdk: 29, release: '10', abilist: 'armeabi-v7a,armeabi', manufacturer: 'Allwinner', model: 'QUAD-CORE R818 EVB2', hardware: 'sun50iw10p1' });
  if (name === 'error') o.installFails = 'Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]';
  const hold = name === 'installing' ? /^cmd package set-home-activity / : /^$/;
  const held = new Held(o, hold, fast ? 0 : 60);
  // Glassworks answers set-up over its setup channel and Improv once it is on (setup-wifi: not on Wi-Fi yet)
  // (only with a page to hand over to: without, the install hands over to the tablet's own setup page, as the fake answers)
  const device: Device = handOver
    ? Object.assign(withAppStreams(held, { online: name !== 'setup-wifi', radio: false, ...(name === 'setup-panes' ? { panes: { cards: PANES } } : {}) }), { app: held.app })
    : held;
  const abi = name === 'own-frame' ? 'armeabi-v7a' : 'arm64-v8a';
  const apk = fakeGenericApk({ app: held.app, abis: [abi] });
  // find-release…: the APK from a stand-in generic-android release (?apk=release); the first screen downloads it
  const kind = ({ 'find-release': 'out', 'find-release-none': 'none', 'find-release-bad': 'bad' } as Partial<Record<AndroidDemo, FakeRelease>>)[name];
  let standard: StandardSession | undefined;
  const s = new AndroidSession({
    findUsb: async () => (untested ? NO_NAME : PIXEL),
    connect: () => (name === 'allow' ? new Promise(() => {}) : Promise.resolve(device)),
    sleep: (ms) => new Promise((r) => setTimeout(r, fast ? 0 : Math.min(ms, 400))),
    homeTimeoutMs: 10 * 60_000,
    setupTries: 2,
    ...(handOver ? { handOver: (p: Device, i: GenericInstalled) => { standard = handOver(p, i); } } : {}),
    ...(kind ? { release: fakeReleaseRoute(kind, apk, (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), 'generic-android') } : {}),
  }, true);
  if (kind) return s;
  await s.pickApk(`glassworks-generic-android-${abi}-test.apk`, apk);
  if (name === 'run' || name === 'find') return s;
  if (name === 'undo-done' || name === 'undo-find') s.setFlow('undo');
  if (name === 'undo-find') return s;
  await s.find();
  if (name === 'named' || name === 'brand') return s;
  if (untested) { s.pickBrand('other'); s.chooseBrand(); } else s.toSteps();
  if (name === 'debug' || name === 'debug-generic') return s;
  const done = s.connect();
  if (name === 'allow' || name === 'installing' || name === 'vendor') return s;
  await done;
  if (!standard) return s; // an undo, an error, or no page to hand over to (the tests)
  await standardDemo(standard, name === 'setup' ? 'app' : name === 'setup-wifi' ? 'wifi' : name === 'setup-ha' ? 'home-assistant' : name === 'setup-panes' ? 'panes' : 'done');
  return s;
}
