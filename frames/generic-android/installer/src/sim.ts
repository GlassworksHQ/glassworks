// Glassworks for generic Android's simulated device for Kiln, for ?demo=… and tests only (#621): a tablet behind the
// real recipe, set up as each of the engine's demos needs it (kiln/apps/kiln/src/device/usbDemo.ts). Never part of the
// frame Kiln installs (kiln.ts). Addresses are RFC 5737 (192.0.2.x).
import type { UsbSim } from '@glassworks/kiln-core';
import { BROWSERS } from '../test/browsers';
import { FakeAndroid, fakeGenericApk, type FakeAndroidOptions } from '../test/fakes';

/** A Pixel Tablet over WebUSB, before USB debugging is on. */
const PIXEL = { vendorId: 0x18d1, productName: 'Pixel Tablet' };
/** A no-name tablet that keeps its chip maker's vendor ID (Allwinner): Kiln asks for its brand */
const NO_NAME = { vendorId: 0x1f3a, productName: 'Android' };

/** Holds one command forever, so the screen stays on the step that runs it. */
class Held extends FakeAndroid {
  constructor(o: FakeAndroidOptions, private readonly hold: RegExp, private readonly delayMs: number) { super(o); }
  override async run(cmd: string) {
    if (this.hold.test(cmd)) return new Promise<never>(() => {});
    if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
    return super.run(cmd);
  }
}

export const kilnSim: UsbSim = {
  demo(name, { fast }) {
    const unknown = name === 'done-untested' || name === 'done-old' || name === 'brand' || name === 'debug-generic';
    const o: FakeAndroidOptions = unknown
      ? { manufacturer: 'Teclast', model: 'P20HD', release: '13', sdk: 33, ip: '192.0.2.11', picksAfter: 3 }
      : { ip: '192.0.2.10', picksAfter: 3 };
    if (name === 'vendor') Object.assign(o, { refuseSetHome: true, refuseGrant: ['android.permission.WRITE_SECURE_SETTINGS'], picksAfter: undefined });
    if (name === 'undo-done') o.installed = 600;
    // issue 422's Done block: Android 9 (a tap each), and a build that keeps "Install unknown apps" off
    if (name === 'done-old') Object.assign(o, { release: '9', sdk: 28 });
    if (name === 'done-refused') o.keepInstallOpOff = true;
    // issue 522: a tablet with a PIN
    if (name === 'done-lock') o.lock = 'pin';
    // a device with a frame of its own and USB debugging on, as the S1 reads (AqaraS1Plus.kt, 2026-11-14)
    if (name === 'own-frame') Object.assign(o, { sdk: 29, release: '10', abilist: 'armeabi-v7a,armeabi', manufacturer: 'Allwinner', model: 'QUAD-CORE R818 EVB2', hardware: 'sun50iw10p1' });
    if (name === 'error') o.installFails = 'Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]';
    const device = new Held(o, name === 'installing' ? /^cmd package set-home-activity / : /^$/, fast ? 0 : 60);
    const abi = name === 'own-frame' ? 'armeabi-v7a' : 'arm64-v8a';
    return {
      device, apk: fakeGenericApk({ app: device.app, abis: [abi] }), apkName: `glassworks-generic-android-${abi}-test.apk`,
      usb: unknown ? NO_NAME : PIXEL, known: !unknown,
    };
  },
  // the on-device page's demo roles, each one of the detection tests' browsers
  browsers: {
    known: BROWSERS.PIXEL_DESKTOP, branded: BROWSERS.GALAXY_TAB, phone: BROWSERS.GALAXY_PHONE, unknown: BROWSERS.UNKNOWN_DESKTOP,
    legacy: BROWSERS.NOUGAT_TAB, either: BROWSERS.OLD_CHROME_DESKTOP, 'too-old': BROWSERS.LOLLIPOP, computer: BROWSERS.MAC,
  },
  /** The cards Glassworks' Panes step shows on a tablet (each pane's PaneChoice). */
  panes: [
    { id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true },
    { id: 'sendspin', title: 'Music', detail: 'A Sendspin speaker', on: true },
    { id: 'notifications', title: 'Notifications', detail: 'On-screen alerts', on: false },
    { id: 'camera', title: 'Camera', detail: 'Presence, and snapshots', on: false },
  ],
};
