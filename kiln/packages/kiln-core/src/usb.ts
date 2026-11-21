// An Android device over USB debugging (issue 621): the engine's flow for a frame whose device runs its own Android and
// takes our app as its home screen. The person plugs it in with USB debugging still off, so Kiln names it from WebUSB
// or asks for the brand; shows that brand's steps to turn USB debugging on; connects (Allow on the device); reads it,
// writing nothing; then installs and grants, reading each grant back, and hands the device to the standard flow. Undo
// removes the app. The frame's code is behind UsbFrame, in frames/<id>/installer; these types are what the engine reads.
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import type { AskCopy, SilentWords } from './frame';

/** An app the frame installs: its ids, and what the screens call it. */
export interface UsbApp extends AppId { name: string }

/** One place in Settings: the path to it, then what to do there. */
export interface UsbPlace { path: string[]; note?: string }

/** How to turn on USB debugging on a brand's devices. */
export interface UsbDebugSteps {
  /** where the person taps `tap` seven times to turn on developer options */
  unlock: UsbPlace & { tap: string };
  /** where USB debugging is then, the first that applies (some differ by Android version) */
  debugging: (UsbPlace & { when?: string })[];
  sources: { title: string; href: string }[];
}

/** What Kiln can say about a device. `pick`: the brand isn't known, so the person picks one. */
export interface UsbIdentified {
  brand?: { id: string; name: string };
  /** a model Kiln knows; `frame`: the frame page it has */
  model?: { name: string; frame?: string };
  steps: UsbDebugSteps;
  tested: boolean;
  pick: boolean;
  /** why it couldn't tell, for the brand picker */
  why?: string;
}

/** What adb reads off the device before anything changes. */
export interface UsbDeviceFacts { sdk: number; release: string; abis: string[]; manufacturer: string; model: string }

/** What the install will do, worked out read-only. */
export interface UsbPlan {
  device: UsbDeviceFacts;
  identified: UsbIdentified;
  app: UsbApp;
  /** the device ABI the APK's code runs as; undefined for an APK without native code */
  abi?: string;
  /** the same app, already there: the install updates it in place */
  installed?: { name?: string; code: number };
}

/** One permission or power the app asks for (the frame's words name each `what`), and whether adb could give it. */
export interface UsbGrant { what: string; result: 'granted' | 'not-asked' | 'refused'; detail?: string }

/** Whether updates install with nobody at the device, as Kiln left it; `line` is what the app's Device page says. */
export interface UsbSilentUpdates { state: 'allowed' | 'declined' | 'refused' | 'old-android' | 'not-asked'; line: string; detail?: string }

export interface UsbInstalled {
  plan: UsbPlan;
  grants: UsbGrant[];
  silentUpdates: UsbSilentUpdates;
  home: { how: 'set-home-activity' | 'chooser'; state: 'set' | 'unconfirmed' };
  /** the app's setup page on the LAN, or why there is none */
  handoff: { setupPage?: string; why?: string };
  /** `secure` with a PIN, pattern or password; undefined when Kiln can't tell */
  screenLock?: 'secure' | 'none';
}

/** The parts of the install the screen follows, in order. `home` with `pick`: waiting for the person's tap. */
export type UsbPhase =
  | { step: 'read' }
  | { step: 'apk'; stage: 'copy' | 'install' }
  | { step: 'grant' }
  | { step: 'updates' }
  | { step: 'granted'; grants: UsbGrant[]; silentUpdates: UsbSilentUpdates }
  | { step: 'start' }
  | { step: 'home'; how: 'set-home-activity' | 'chooser'; pick?: string }
  | { step: 'handoff' };

export interface UsbInstallOptions {
  apk: Uint8Array;
  onStep?: (step: string, detail?: string) => void;
  onPhase?: (phase: UsbPhase) => void;
  sleep?: (ms: number) => Promise<void>;
  /** how long to wait for the "Always" tap in Android's chooser */
  homeTimeoutMs?: number;
  /** how many times to try the app's setup channel after it starts */
  setupTries?: number;
  /** hand over to the app's setup page on the LAN at the end; off when the standard flow sets the device up next */
  handoff?: boolean;
}

/** A device with a frame of its own, which Kiln installs from that frame's page. */
export interface OwnFrame { frame: string; name: string; route: string; hardware: string }

/** Refused before anything changed on the device. */
export class Refused extends Error {
  constructor(why: string) { super(`${why} Nothing was changed.`); }
}

/** Refused because the device has a frame of its own, which installs from its own page in Kiln; `why` is the frame's words. */
export class OwnFrameRefused extends Refused {
  constructor(readonly own: OwnFrame, why: string) { super(why); }
}

/** What the engine's USB-debugging flow needs from a frame: its words and release line in its data, its code here. */
export interface UsbFrame {
  kind: 'usb';
  id: string;
  /** its name, as the picker and the tab's title say it */
  name: string;
  /** its short name: `the <short>` in the words */
  short: string;
  /** how a sentence names it, with its article */
  the: string;
  /** what the screens call the app it installs */
  app: string;
  /** The APK's release line and ABI (Kiln's release route). */
  apk: { line: string; abi: string };
  /** The apps it installs: a picked APK must be one of them. */
  apps: readonly UsbApp[];
  /** The Android it needs, as the first screen says it: with the release's APK, and with a picked file (any build). */
  needs: { release: string; local: string };
  /** The page while it is locked to the public: its heading, and where to follow its work. */
  off: { title: string; follow: string };
  /** The brands the brand screen offers, in order; "Other" follows them. */
  brands: { id: string; name: string }[];
  /** Each grant's name and what is missing without it, by UsbGrant `what`. */
  grants: Record<string, { name: string; without: string } | undefined>;
  /** The standard flow's "your turn" sheet when set-up connects to it again (its `pick`: the model, when known). */
  connect: AskCopy;
  /** Done's screen-lock block, for a device with a PIN, pattern or password: what it means, and where to change it. */
  screenLock: { line: string; help: string };
  /** Done's Silent updates block for what the install read back; without one (a reload), what Kiln can say. Null: say nothing. */
  silent(u: UsbSilentUpdates | undefined, release: string): SilentWords | null;
  /** An API level's Android version, for a device that doesn't say its release. */
  androidName(sdk: number): string;
  /** From WebUSB, before USB debugging is on. */
  identifyUsb(d: { vendorId: number; productName?: string }): UsbIdentified;
  /** The person's pick of a brand, or none ("Other"): its steps. */
  identifyPicked(brand?: string): UsbIdentified;
  /** Read-only: what the install would do. Throws Refused (or OwnFrameRefused) for a device it can't take. */
  plan(p: PanelShell, apk: Uint8Array): Promise<UsbPlan>;
  install(p: PanelShell, o: UsbInstallOptions): Promise<UsbInstalled>;
  undo(p: PanelShell, o: { onStep?: (step: string, detail?: string) => void; sleep?: (ms: number) => Promise<void> }): Promise<{ removed: UsbApp[] }>;
}

/** A UsbFrame's simulated device, for ?demo=… and tests only: never part of the frame Kiln installs. */
export interface UsbSim {
  /**
   * The device for a demo (Kiln's demo names, kiln/apps/kiln/src/device/usbDemo.ts), set up as that demo needs it: the
   * APK that matches it, what WebUSB says of it, and whether Kiln knows its model. `fast`: no delays (the tests).
   */
  demo(name: string, o: { fast: boolean }): { device: PanelShell & { app?: UsbApp }; apk: Uint8Array; apkName: string; usb: { vendorId: number; productName?: string }; known: boolean };
  /** The cards its simulated app's Panes step shows, for ?demo=setup-panes. */
  panes?: { id: string; title: string; detail: string; on: boolean }[];
}

export type UsbEntry = { kind: 'usb'; frame: UsbFrame; sim: UsbSim };
