// A frame Kiln installs on a device that runs Android, from the device's side: an image written over fastboot and
// Glassworks' first start over adb, or the app alone over adb. The engine's two flows run every step and every screen;
// the frame declares what differs as data and the few hooks only its code can say (issue 621).
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import type { ImageFile, ImageRecipe } from './images';
import type { AskCopy, FrameSim, KilnFrame } from './frame';
import type { StepCredits } from './recipe';
import type { RecoveryData } from './recovery';
import type { RestoredFile } from './restore';
import type { UsbEntry } from './usb';

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
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; settings?: Uint8Array; framework?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
    deviceMac?: string; wifi?: { ssid: string; password: string }; apiKeyHandover?: boolean },
    onProgress: (step: PrepareStep, x: number) => void): Promise<{ system: Blob; userdata: Blob }>;
  /**
   * A community image's `wifi` step: a copy of the stored userdata with the network saved in it. `made`: start from
   * what an earlier step made from that userdata (its `adb-key` step's copy) instead of the download itself.
   */
  wifi(o: { userdata: ImageFile; made?: string; system: ImageFile; makes: string; wifi: { ssid: string; password: string } }, onProgress: (x: number) => void): Promise<Blob>;
  /** A community image's `adb-key` step: a copy of the stored userdata with `adbKey` (this browser's) as its only adb key. */
  adbKey(o: { userdata: ImageFile; makes: string; adbKey: string }, onProgress: (x: number) => void): Promise<Blob>;
  /** A community image's `system-key` step: a copy of the stored system image that lets in `adbKey` (this browser's) in place of the key it hardcodes. */
  systemKey(o: { system: ImageFile; makes: string; adbKey: string }, onProgress: (x: number) => void): Promise<Blob>;
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

/**
 * The frame's code for an in-place recipe (images.ts `mode: 'in-place'`): the stock software stays, and only the
 * blocks the device's own agent reads back are written. Kiln runs the steps and checks every image this builds
 * (inplace.ts `checkInPlace`) before it is written.
 */
/** A file an in-place write changed, as Kiln reads it back over adb after the restart (InPlaceCode `build`'s `readBack`). */
export interface ReadBackFile {
  /** Its path on the running device, e.g. `/system/framework/services.jar`. */
  path: string;
  /** The SHA-256 (hex) of the bytes the write put there. */
  sha256: string;
  /** Its size in bytes, when known: a read that brings more is cut off there and doesn't match. */
  size?: number;
}

export interface InPlaceCode {
  /**
   * The `stage` step: a small userdata image, sized for the partition, that starts the frame's agent on the stock
   * software, trusts `adbKey`, signs what it sends with `secret`, and joins `wifi` when given. `assets`: the staged
   * files it names in `assets` (each checked by its SHA-256 before it comes here), by name.
   */
  stage(o: { adbKey: string; userdataBytes: number; secret: Uint8Array; wifi?: { ssid: string; password: string }; assets: Record<string, Uint8Array> }): Promise<Blob>;
  /** Kiln's staged assets the stage puts on the device, by asset name. */
  assets?: readonly string[];
  /** Kiln's staged assets the `in-place` build uses (`build`'s `assets`, each checked by its SHA-256), by asset name. */
  buildAssets?: readonly string[];
  /**
   * The `receive` step's check, after Kiln's own (bundle.ts `verifyGwsbBundle`): the bundle is what the agent read off
   * this device. `digest`: its id in the bundle cache; `extents`: where the file it holds sits on the partition, in
   * bytes; `info`: lines for the log. Throws, saying why in plain words, on a bundle Kiln mustn't build from. `kept`:
   * a copy this browser kept from an earlier run, whose signature was checked with that run's secret when it came: its
   * signature isn't checked again (the secret is never kept), everything else is.
   */
  /** `unsigned`: the person chose to go on with a bundle whose signature didn't match (Kiln warned); no signature check. */
  readBundle(bytes: Uint8Array, o: { secret: Uint8Array; serial: string; kept?: boolean; unsigned?: boolean }): Promise<{
    digest: string;
    extents: readonly { physical: number; length: number }[];
    info: readonly string[];
  }>;
  /**
   * Whether a bundle this browser kept from an earlier run still describes the device as its agent describes it now
   * (`agent`: the agent's own description, as it sent it): the same file can sit in other blocks after an update or a
   * reflash, and the writes are built from where the bundle says it sits. Without it, or when it says no, Kiln takes
   * the bundle again from the device.
   */
  keptMatches?(bundle: Uint8Array, agent: Record<string, unknown>): boolean;
  /**
   * The `in-place` step: the write images by name (the step's `makes`), and the restore image for every block the
   * surgical one changes. `manifest`: the restore point's description, kept with it and in its download. `files`: other
   * files the restore point keeps (the original file, say), by file name. `partitionBytes`: each partition's size as
   * fastboot reports it now. `assets`: the staged files `buildAssets` names, by name.
   */
  build(bundle: Uint8Array, o: { adbKey: string; userdataBytes: number; partitionBytes: Record<string, number>; serial: string; wifi?: { ssid: string; password: string }; assets?: Record<string, Uint8Array> }): Promise<{
    writes: Record<string, Blob>;
    restore: Blob;
    manifest?: Record<string, unknown>;
    files?: Record<string, Uint8Array>;
    /**
     * The files the writes change, as the running system shows them, each with the SHA-256 it must have. Fastboot
     * can't read a write back on every device, so Kiln checks it the next place it can: once the device has started
     * into what was written and lets this browser in over adb, Kiln reads each file back (adb's sync, hashed in the
     * browser) before the app's install, and stops there on a mismatch, with the way back. None: nothing to read back.
     */
    readBack?: readonly ReadBackFile[];
  }>;
  /**
   * The `app-install` step, over adb with this browser's key after the restart into what was written: the app
   * installed and started, the stock apps it replaces out of the way, and whether updates install by themselves.
   */
  appInstall(p: PanelShell, o: { apk: Uint8Array; onStep: Step; sleep: Sleep }): Promise<FirstStart>;
  /**
   * The way back's first part, over adb when Kiln still reaches the device: undoes what `appInstall` changed. Best
   * effort, and only on the device the restore point was made on (Kiln checks its serial first).
   */
  beforeRestore?(p: PanelShell, o: { onStep: Step }): Promise<void>;
  /**
   * The files a restore point's image puts back, read from its manifest (the one `build` made, or an earlier build's):
   * each one's path on the device, its original copy in the restore point, size, SHA-256 and blocks, and what the
   * install wrote there. Kiln checks the restore image against them before the way back writes anything, and the files
   * on the device too while adb still lets it in (restore.ts). Throws on a manifest it can't read. None: Kiln checks
   * only what the restore point records of itself.
   */
  restored?(manifest: Record<string, unknown>): readonly RestoredFile[];
  /**
   * The way back's other writes over fastboot, after the restore image: what the install changed outside the blocks
   * the restore point covers, put back as the device had it (a fresh userdata, say, so nothing `appInstall` set stays).
   * `partitions`: the partitions it writes, whose sizes Kiln reads from fastboot before it calls `build`, and before
   * anything is written. `what`: a line for the log.
   */
  wayBack?: {
    partitions: readonly string[];
    build(o: { partitionBytes: Record<string, number> }): Promise<{ writes: Record<string, Blob>; what: string }>;
  };
  /**
   * Read over adb, when the device lets this browser in before Kiln restarts it into fastboot: whether the Wi-Fi network
   * the device opens itself (`channels.lan.hotspot`) won't open on it any more, because an earlier install turned it
   * off. Then that run treats the device as one without a network of its own: the stage gets the person's network,
   * and the receive step reaches the agent over that network or Bluetooth. Without it, Kiln counts on the hotspot.
   */
  ownWifiGone?(p: PanelShell): Promise<boolean>;
  /**
   * Read over adb with `ownWifiGone`, when that says yes: the device's address on the person's network now, before
   * the restart. The agent joins the same network after the stage, so it is likely reached there again, a guess the
   * receive screen offers for the download by hand when Bluetooth can't say where the agent is. Undefined: not on it.
   */
  lanAddress?(p: PanelShell): Promise<string | undefined>;
  /** How the `receive` step reaches the agent: its HTTP port and path on the LAN, its Bluetooth GATT service and characteristics. */
  channels: {
    lan?: {
      port: number;
      path: string;
      pagePath?: string;
      /**
       * The Wi-Fi network the device opens itself while the agent runs, and its address there. `{serial}` in `ssid`
       * stands for the serial fastboot reported. With it, the receive step asks the person to join that network and
       * fetches the bundle from `host` directly, with no Bluetooth; and the person's own Wi-Fi goes only into the
       * final install, never the stage (a device that joins it might not open its own).
       */
      hotspot?: { ssid: string; host: string };
    };
    ble?: { service: string; meta: string; ctrl: string; data: string };
  };
  /** What the in-place screens say (installer.json `device.words`, the `inPlace.*` keys; inPlaceWords() checks them). */
  words: Record<InPlaceWord, string>;
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
  assets?: { bootAnimation?: string; settings?: string; framework?: string };
  /** An in-place recipe's code, for a frame that offers one. */
  inPlace?: InPlaceCode;
  /**
   * The browser storage this flow keeps across visits. Never rename any: browsers hold them. `inPlaceDb`: the
   * IndexedDB that keeps received bundles and restore images (with `inPlace`).
   */
  storage: { flashedFlag: string; inPlaceDb?: string };
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

/** Every word a frame with an in-place recipe gives (`imageFlow.inPlace`); inPlaceWords() checks each is there. */
export const IN_PLACE_WORDS = [
  'inPlace.stage.lede', 'inPlace.receive.lede', 'inPlace.receive.waiting', 'inPlace.receive.lan', 'inPlace.receive.ble',
  'inPlace.receive.file', 'inPlace.restorePoint.lede', 'inPlace.confirm.lede', 'inPlace.appInstall.lede', 'inPlace.wayBack',
  'inPlace.fastboot.again', 'inPlace.coverNote', 'inPlace.changes', 'inPlace.startOver',
] as const;
export type InPlaceWord = (typeof IN_PLACE_WORDS)[number];

/** An in-place frame's words, checked where they come in. Only for a frame with `imageFlow.inPlace`: others need none. */
export function inPlaceWords(w: Record<string, unknown>): Record<InPlaceWord, string> {
  for (const k of IN_PLACE_WORDS) if (typeof w[k] !== 'string' || !w[k]) throw new Error(`installer.json words.${k}: missing`);
  return w as Record<InPlaceWord, string>;
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
  /**
   * Its agent after the restart into an in-place stage. `bundle`: what it sends, signed with `secret`; `tampered`: a
   * bundle whose signature doesn't match, `otherDevice`: one read from another serial.
   */
  inPlace?: InPlaceSim;
}

/**
 * A simulated device for an in-place recipe, across its two fastboot sessions (demos and tests only). `code`: the
 * frame's in-place code with stand-ins where the real one needs the device's own files (the build), so a demo runs on
 * made-up bytes.
 */
export interface InPlaceSim {
  serial: string;
  bundle(o: { secret: Uint8Array; tampered?: boolean; otherDevice?: boolean }): Promise<Uint8Array>;
  /** The userdata partition's size, as its fastboot reports it. */
  userdataBytes: number;
  code: InPlaceCode;
  /**
   * One simulated device: its fastboot (`fastboot()` is the session connected now; after a restart the next
   * `fastboot()` is the next session), its agent on the network and over Bluetooth once the stage is written, and adb
   * after the install. `lan`: how the network behaves; `bluetooth: false`, no Bluetooth; `refusedBuild`: a build the frame's code refuses before any write;
   * `tampered`: a bundle whose signature doesn't match. `systemNotTaken`: fastboot says the second session's system
   * write is done, but the running system still has the old file (Kiln's read back over adb catches it);
   * `unreadable`: adb can't read the files back.
   */
  device(o: { lan?: 'ok' | 'lna-denied' | 'drop-midway' | 'offline'; bluetooth?: boolean; refusedBuild?: boolean; tampered?: boolean; tick?: Sleep;
    systemNotTaken?: boolean; unreadable?: boolean;
    /** time between Bluetooth notifications, so a demo's transfer stays on screen */
    bleFrameDelayMs?: number }): InPlaceSimDevice;
}

export interface InPlaceSimDevice {
  fastboot(): FastbootLike;
  /** The agent's HTTP server: a fetch that reaches only it. */
  fetch: typeof fetch;
  /** The agent's Bluetooth, as Web Bluetooth's `navigator.bluetooth` (structural: the app's channel types). */
  bluetooth: unknown;
  /** The device over adb after the install. */
  adb(): PanelShell;
  /** The device's agent is up (the stage was written and the device restarted into it). */
  staged(): boolean;
}

export type DeviceEntry = { kind: 'device'; frame: DeviceFrame; sim: DeviceSim };

/**
 * A frame as the registry loads it, with its simulated device beside it (?demo=… only). `kind` is the frame's: the
 * wizard (a recipe written over FEL and fastboot, then the app's setup) or the device flows (an image over fastboot, or
 * the app alone over adb). Kiln picks the page from it, never from a frame's id.
 */
export type KilnEntry = { kind: 'wizard'; frame: KilnFrame; sim: FrameSim } | DeviceEntry | UsbEntry;
