// Development and preview only (/android?demo=…): a simulated Android device behind the real recipe,
// so each screen can be seen and captured without one. `run` waits for clicks like the real page; the
// other names go to one screen. Addresses are RFC 5737 (192.0.2.x).
import { FakeAndroid, fakeGenericApk, type FakeAndroidOptions } from '@glassworks/generic-android-installer/fakes';
import { AndroidSession, type UsbFacts } from './session';

export const ANDROID_DEMOS = [
  'run', 'find', 'undo-find', 'named', 'brand', 'debug', 'debug-generic', 'allow', 'installing', 'vendor', 'done', 'done-untested', 'done-old', 'done-refused', 'undo-done', 'error', 'own-frame',
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

/** `fast`: no delays (the tests). */
export async function androidDemo(name: AndroidDemo, fast = false): Promise<AndroidSession> {
  const untested = name === 'done-untested' || name === 'done-old' || name === 'brand' || name === 'debug-generic';
  const o: FakeAndroidOptions = untested
    ? { manufacturer: 'Teclast', model: 'P20HD', release: '13', sdk: 33, ip: '192.0.2.11', picksAfter: 3 }
    : { ip: '192.0.2.10', picksAfter: 3 };
  if (name === 'vendor') Object.assign(o, { refuseSetHome: true, refuseGrant: ['android.permission.WRITE_SECURE_SETTINGS'], picksAfter: undefined });
  if (name === 'undo-done') o.installed = 600;
  // issue 422's Done block: Android 9 (a tap each), and a build that keeps "Install unknown apps" off
  if (name === 'done-old') Object.assign(o, { release: '9', sdk: 28 });
  if (name === 'done-refused') o.keepInstallOpOff = true;
  // an S1 with USB debugging on, as it reads (AqaraS1Plus.kt, 2026-11-14)
  if (name === 'own-frame') Object.assign(o, { sdk: 29, release: '10', abilist: 'armeabi-v7a,armeabi', manufacturer: 'Allwinner', model: 'QUAD-CORE R818 EVB2', hardware: 'sun50iw10p1' });
  if (name === 'error') o.installFails = 'Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]';
  const hold = name === 'installing' ? /^cmd package set-home-activity / : /^$/;
  const device = new Held(o, hold, fast ? 0 : 60);
  const s = new AndroidSession({
    findUsb: async () => (untested ? NO_NAME : PIXEL),
    connect: () => (name === 'allow' ? new Promise(() => {}) : Promise.resolve(device)),
    sleep: (ms) => new Promise((r) => setTimeout(r, fast ? 0 : Math.min(ms, 400))),
    homeTimeoutMs: 10 * 60_000,
    setupTries: 2,
  }, true);
  const abi = name === 'own-frame' ? 'armeabi-v7a' : 'arm64-v8a';
  await s.pickApk(`glassworks-generic-android-${abi}-test.apk`, fakeGenericApk({ app: device.app, abis: [abi] }));
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
  return s;
}
