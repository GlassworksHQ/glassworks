// A frame Kiln installs on a device that runs Android, from the device's side: an image written over fastboot and
// Glassworks' first start over adb, or the app alone over adb. The engine's two flows run every step and every screen;
// the frame declares what differs as data and the few hooks only its code can say (issue 621).
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import type { ImageFile, ImageRecipe } from './images';
import type { AskCopy, FrameSim, KilnFrame } from './frame';
import type { StepCredits } from './recipe';
import type { RecoveryData } from './recovery';

/** A download that Kiln's route couldn't serve: Kiln's bug, never the person's. */
export class ProxyError extends Error {}
/** Bytes that came but aren't the ones the release publishes. */
export class WrongImage extends Error {}

export interface DeviceCheck { what: string; found: string; ok: boolean; need: string }
export interface AppVersion { name?: string; code: number }

/** The device read over adb, read-only. `ok`: every check passed. */
export interface DeviceReport {
  checks: DeviceCheck[];
  ok: boolean;
  /** our app, when it is installed */
  app?: AppVersion;
  /** the app Android opens as the home screen ("Always"), when there is one */
  home?: string;
  /** an undo removed our app but the old home app wasn't picked again yet */
  pendingHome?: string;
}

/** A part of the app's install or undo: its id is a row in the frame's `appRows`. */
export interface DevicePhase { step: string; stage?: 'copy' | 'install' }

/** What Android opened for HOME. */
export interface HomeCheck { result: 'set' | 'chooser' | 'other' | 'none'; focused?: string; left: string[] }

/**
 * `heldHomes`: home apps Android won't hide, because each holds an active device admin. `updates.state`: a key of the
 * frame's `silent` words.
 */
export interface Installed { app: AppVersion; home: 'set' | 'unconfirmed'; homeCheck: HomeCheck; hidden: string[]; heldHomes: string[]; updates: { state: string } }
export interface Removed { removed: boolean; home: 'set' | 'unconfirmed'; previousHome?: string; focused?: string }

/** Glassworks' first start after the image was written. `apiKey`: whether the app took Home Assistant's key back. */
export interface FirstStart { app: AppVersion; hidden: string[]; home: HomeCheck; wifi?: boolean; updates: { state: string }; apiKey?: boolean }

export interface FastbootLike {
  getvar(name: string): Promise<string>;
  partitionSize(name: string): Promise<number>;
  flashBlob(partition: string, blob: Blob, onProgress?: (x: number) => void): Promise<void>;
  reboot(): Promise<void>;
}

/** The device's fastboot, read-only. `deviceMac`: its id in Home Assistant, the same on every reflash. */
export interface FastbootReport { checks: DeviceCheck[]; ok: boolean; userdataBytes: number; info: string[]; deviceMac?: string }

export type PrepareStep = 'check' | 'patch' | 'system' | 'userdata';

/** Where a frame's images get made and kept: a worker on OPFS in the browser, a fake in demos and tests. */
export interface ImageMaker {
  /** A file the frame patches, into the maker, checked as it arrives. Throws ProxyError or WrongImage. */
  fetch(url: string, onProgress: (x: number) => void): Promise<void>;
  /** A file written as published, checked as it arrives; one that isn't the release's is deleted. */
  download(url: string, want: ImageFile, onProgress: (x: number) => void): Promise<void>;
  /** A file download() kept, as fastboot sends it. */
  file(name: string): Promise<Blob>;
  /** That Blob against its pinned size and SHA-256, right before it is flashed. Throws WrongImage. */
  check(blob: Blob, want: ImageFile, onProgress: (x: number) => void): Promise<void>;
  /** Builds the frame's images from the fetched one; can run again until discard(). */
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; settings?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
    deviceMac?: string; wifi?: { ssid: string; password: string }; apiKeyHandover?: boolean },
    onProgress: (step: PrepareStep, x: number) => void): Promise<{ system: Blob; userdata: Blob }>;
  /** A community image's `wifi` step: a copy of the stored userdata with the network saved in it. */
  wifi(o: { userdata: ImageFile; system: ImageFile; makes: string; wifi: { ssid: string; password: string } }, onProgress: (x: number) => void): Promise<Blob>;
  /** Deletes what it kept. */
  discard(): Promise<void>;
}

type Step = (step: string, detail?: string) => void;
type Sleep = (ms: number) => Promise<void>;

/** The app alone over adb, on a device already on a community image. */
export interface AppOverAdb {
  /** Throws, saying why, unless the APK is the build this frame installs. Nothing written. */
  checkApk(apk: Uint8Array): Promise<void>;
  /** The device, read-only. */
  check(p: PanelShell): Promise<DeviceReport>;
  /** Installs the APK and starts the app. Home Assistant and the Wi-Fi are set-up's, next, over the app's channel. */
  install(p: PanelShell, o: { apk: Uint8Array; onStep: Step; onPhase: (p: DevicePhase) => void; sleep?: Sleep }): Promise<Installed>;
  undo(p: PanelShell, o: { onStep: Step; onPhase: (p: DevicePhase) => void; sleep?: Sleep }): Promise<Removed>;
}

/** An image over fastboot, then Glassworks' first start over adb. */
export interface ImageOverFastboot {
  /** What this page can install, Glassworks first. */
  recipes: readonly ImageRecipe[];
  /** The browser's device list for the device's fastboot. */
  fastbootFilter: USBDeviceFilter;
  /** Its fastboot, read-only: the partitions the images are made for. */
  checkFastboot(fb: FastbootLike): Promise<FastbootReport>;
  /** A community image's userdata goes on at its published size: the partition must hold `bytes`. */
  communityUserdata?: { file: string; bytes: number };
  /** Glassworks' first start: the app started, its key handed back. Home Assistant and the Wi-Fi are set-up's, next. */
  firstBoot(p: PanelShell, o: { onStep: Step; sleep: Sleep; apiKey?: string }): Promise<FirstStart>;
  /** Hides the other launchers again and checks HOME. */
  makeOnlyHome(p: PanelShell, onStep: Step, sleep?: Sleep): Promise<{ home: HomeCheck }>;
  /** A community image's last step: Wi-Fi on, with its own key. */
  wifiOn(p: PanelShell, sleep?: Sleep): Promise<boolean>;
  /** Where the images are made: the frame's builder, in a worker. */
  images(): ImageMaker;
  /** Kiln's download route for the images. */
  proxy: string;
  /** Kiln's staged assets the build adds, by asset name. */
  assets?: { bootAnimation?: string; settings?: string };
  /** The browser storage this flow keeps across visits. Never rename either: browsers hold them. */
  storage: { flashedFlag: string };
}

/** A frame installed on an Android device by image and adb. */
export interface DeviceFrame {
  kind: 'device';
  id: string;
  /** its full name, as the picker shows it */
  name: string;
  /** its short name: the rail's first step, and `a <short>` in the words */
  short: string;
  /** how a sentence names it, with its article: `the <short>` */
  the: string;
  /** The app: its id, and what the screens call it. */
  app: AppId & { name: string };
  /** The Wi-Fi network the device is on, read-only, for set-up's Wi-Fi step. None: the step shows no name. */
  wifiName?(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }>;
  /** The browser's list when set-up connects to the device again over adb (a reload, a route): its "your turn" sheet. */
  connect: AskCopy;
  /** The name the browser's USB list shows the device by, when it isn't the one Kiln calls it: `Android`. */
  listed?: string;
  /** Whether updates install with nobody at the device, read back: one of `silent`'s states, and a line for the log. */
  updates(p: PanelShell, signal?: AbortSignal): Promise<{ state: string; log: string }>;
  /** The board `getprop ro.board.platform` gives: anything else isn't this device. */
  board: string;
  /** The APK's release line and ABI. */
  apk: { line: string; abi: string };
  /** What the screens say. Templates fill `{name}`; `` `x` `` is code, `**x**` bold (rich()). */
  words: Record<DeviceWord, string>;
  /** The rows the app's install and undo show, by DevicePhase step (or `copy`/`install` for the APK). */
  appRows: { install: { id: string; text: string }[]; undo: { id: string; text: string }[]; level3: string[] };
  /** Done's Silent updates block, by `updates.state`. */
  silent: Record<string, { word: string; tone: 'y' | 'n' | 'w'; line: string }>;
  recovery: RecoveryData;
  credits: StepCredits;
  appFlow: AppOverAdb;
  imageFlow: ImageOverFastboot;
  /** How long Kiln waits for the device to let this browser's adb key in, and for one read over adb. */
  timings: { authMs: number; firstStartAuthMs: number; readMs: number };
}

/** Every word a DeviceFrame gives; deviceWords() checks each is there. */
export const DEVICE_WORDS = [
  'adb.firstStart', 'app.connectHelp', 'app.pick.local', 'app.pick.release', 'app.toImage',
  'community.ifVendor', 'confirm.lede', 'confirm.title', 'confirm.understand', 'done.noHomeFix',
  'done.noTouch', 'error.noFill', 'fastboot.byHandNote', 'fastboot.byHandSteps', 'fastboot.notLetIn',
  'fastboot.overUsb', 'fastboot.restarted', 'image.apkLabel', 'image.lede', 'image.vendor',
  'keep.steps',
] as const;
export type DeviceWord = (typeof DEVICE_WORDS)[number];

/** A DeviceFrame's words, checked where they come in. */
export function deviceWords(w: Record<string, unknown>): Record<DeviceWord, string> {
  for (const k of DEVICE_WORDS) if (typeof w[k] !== 'string' || !w[k]) throw new Error(`installer.json words.${k}: missing`);
  return w as Record<DeviceWord, string>;
}

/**
 * "pick the hub, listed as “Android”, in the browser's list": the device as the browser's list shows it, when its
 * data says (`listed`). Without, "pick the <short> in the browser's list".
 */
export const pickInList = (f: Pick<DeviceFrame, 'the' | 'listed'>) => `pick ${f.the}${f.listed ? `, listed as “${f.listed}”,` : ''} in the browser's list`;

/** A sentence's first word, capitalised: `the <short>` at its start. */
export const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** A DeviceFrame's simulated device, for ?demo=… and tests only: never part of the frame Kiln installs. */
export interface DeviceSim {
  /** The cards its simulated app's Panes step shows (the app's own, each pane's title and line), for ?demo=setup-panes */
  panes?: { id: string; title: string; detail: string; on: boolean }[];
  /** The device over adb for an app-alone demo (Kiln's demo names), set up for it. `fast`: no delays (the tests). */
  appDevice(demo: string, o: { fast: boolean }): Promise<PanelShell>;
  /** A stand-in for the APK this frame installs, and its file name. */
  apk(): Uint8Array;
  apkName: string;
  /** An APK that isn't it, and its file name. */
  wrongApk(): Uint8Array;
  wrongApkName: string;
  /** The system partition's size, right and wrong. */
  systemBytes: number;
  wrongSystemBytes: number;
  /** Its fastboot. `fails`: a write the bootloader refuses; `stuck`: a write that never ends. */
  fastboot(system: number, o: { fails?: boolean; stuck?: boolean; tick: Sleep }): FastbootLike;
  /** The device at its first start after the flash. `socket`: the app's key socket, holding `key`. */
  firstStart(o: { hideFails?: boolean; noHome?: boolean; socket?: boolean; key?: string }): PanelShell;
  /** The device before the flash, running Glassworks: with its key socket holding `key`, or none (an older app). */
  running(o: { socket: boolean; key?: string }): PanelShell;
}

export type DeviceEntry = { kind: 'device'; frame: DeviceFrame; sim: DeviceSim };

/**
 * A frame as the registry loads it, with its simulated device beside it (?demo=… only). `kind` is the frame's: the
 * wizard (a recipe written over FEL and fastboot, then the app's setup) or the device flows (an image over fastboot, or
 * the app alone over adb). Kiln picks the page from it, never from a frame's id.
 */
export type KilnEntry = { kind: 'wizard'; frame: KilnFrame; sim: FrameSim } | DeviceEntry;
