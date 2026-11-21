// Generic Android as the tests use it: its declaration and simulated device from its installer package, as the frames
// registry loads them (packages/frames), on the engine's USB-debugging page and session (src/device/usb*).
import type { UsbApp, UsbInstalled } from '@glassworks/kiln-core';
import { kilnFrame } from '@glassworks/generic-android-installer/kiln';
import { kilnSim } from '@glassworks/generic-android-installer/sim';
import { UsbPage, usbStandard } from '../src/device/UsbPage';
import { UsbActivity, keptUsb } from '../src/device/usbActivity';
import { USB_DEMOS, usbDemo, type UsbDemo } from '../src/device/usbDemo';
import { UsbSession, usbMode, usbOpen, type Device, type UsbDeps } from '../src/device/usbSession';
import type { StandardSession } from '../src/standard/session';

export const android = kilnFrame;
export const androidSim = kilnSim;
export const ANDROID_BASE = '/android';
export const ANDROID_OPEN = usbOpen(android);
export const ANDROID_DEMOS = USB_DEMOS;
export type AndroidDemo = UsbDemo;
export const AndroidActivity = UsbActivity;
export type AndroidActivity = UsbActivity;
export const SCREEN_LOCK_HELP = android.screenLock.help;
export const SCREEN_LOCK_LINE = android.screenLock.line;

/** The page at /android, as [frame] renders it for generic Android. */
export const AndroidWizard = () => <UsbPage frame={android} demo={{ sim: androidSim }} base={ANDROID_BASE} />;

/** A session on generic Android's frame. */
export class AndroidSession extends UsbSession {
  constructor(d: Omit<UsbDeps, 'frame'>, on: boolean) { super({ frame: android, ...d }, on); }
}
export const androidMode = (s: string, demosOn: boolean, demos: readonly string[], open = ANDROID_OPEN) => usbMode(s, demosOn, demos, open);
export const androidDemo = (name: UsbDemo, fast = false, handOver?: (p: Device, installed: UsbInstalled) => StandardSession | undefined) => usbDemo(android, androidSim, name, fast, handOver);
export const keptAndroid = (make: () => UsbSession, from?: UsbSession['from']) => keptUsb(ANDROID_BASE, android.name, make, from);
export const androidStandard = (app: UsbApp, installed?: UsbInstalled, model?: string) => usbStandard(android, app, installed, model);
