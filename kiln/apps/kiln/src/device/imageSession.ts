// An image over fastboot, then Glassworks' first start over adb (issue 579), outside React: the engine's image flow
// for a frame that declares one (its `imageFlow`). The person picks an image: Glassworks, first and the default, or a
// community image. Each is a recipe of data (the frame's `imageFlow.recipes`) that this session runs step by step, inside
// Kiln's safety steps (kiln-core plan()). Kiln downloads every file through its download route and checks it against the
// release's SHA-256 before anything goes near the device. A download Kiln can't serve is Kiln's bug: the screen says
// so, with Try again and Report this, and never asks the person to download a file. Glassworks is built from the
// checked image by the frame's builder and finishes over adb with this browser's key; a community image goes on as its
// release has it. A device that runs Glassworks already can go through `keep` first (issue 580): Kiln reads Home
// Assistant's ESPHome key from the running app over adb and gives it back at the first start.
import { AdbAuthTimeout, AdbBusy, pemToPkcs8, type PanelShell } from '@glassworks/adb';
import { apiKeyFingerprint, apiKeySocketUp, kioskVersion, readApiKey } from '@glassworks/app-adb';
import { askAdb, nothingPicked } from './adbAsk';
import {
  ProxyError, WrongImage, adbKeyStep, cap, checkInPlace, imageFromLink, parseSparseWrites, pickInList, plan, readNamedBackupZip, sha256Hex, sourceFile, systemKeyStep, wifiStep,
  type DeviceFrame, type FastbootLike, type FastbootReport, type FirstStart, type ImageFile, type ImageMaker, type ImageRecipe, type InPlaceCode, type NamedPart,
  type PlannedStep, type PrepareStep,
} from '@glassworks/kiln-core';
import type { BluetoothLike } from './channels';
import { InPlaceReceiver, UnverifiedBundle, fallbackWords, fromHexString, hotspotUrls, memoryInPlaceStore, newSecret, restorePointZip, toHexString, type InPlaceStore, type Received } from './inPlace';
import type { ReceiveChannel, ReceiveProgress, ReceiveStage } from './InPlaceScreens';


export type { ImageMaker };

export interface ImageDeps {
  frame: DeviceFrame;
  images: ImageMaker;
  /** Kiln's download route (the frame's `imageFlow.proxy`). None: every download fails, as a bug to report. */
  refract?: string;
  /** What this page can install, Glassworks first (the frame's `imageFlow.recipes`). */
  recipes?: readonly ImageRecipe[];
  /** The page's address query: `?image=<id>` opens that community image's install. */
  search?: string;
  /** The browser's device list for the device's fastboot, inside the click. */
  requestFastboot(): Promise<FastbootLike>;
  /**
   * The browser's device list for the device's adb, inside the click: after the first boot, and before the flash to
   * read Home Assistant's key (issue 580). Throws AdbAuthTimeout when the device hasn't let this browser's key in within
   * `authMs`: the frame's `timings.authMs` for the read before the flash, `firstStartAuthMs` for the first start's first try.
   * `onPicked`: called when the list closes on a device, before it lets the key in.
   */
  requestAdb(o?: { authMs?: number; onPicked?: () => void }): Promise<PanelShell>;
  /**
   * adb again on the device requestAdb picked, with no list: while the device is still starting it refuses this
   * browser's key (adbd answers before it reads the adb_keys in the new userdata), so Kiln asks again. Each call gives
   * up after `timings.authMs`, closing the device, so a key left pending can't hold the loop.
   */
  reopenAdb?(o?: { authMs?: number }): Promise<PanelShell>;
  /** Whether the last requestAdb picked a device: one that failed before the list (no key yet, no WebUSB) isn't retried. */
  adbPicked?(): boolean;
  /** How many times Kiln asks again, 5 s apart (default 60). */
  adbTries?: number;
  /** How long Kiln keeps asking, from the first refusal (default five minutes of the first start); each try gets what's left. */
  adbWindowMs?: number;
  /**
   * Whether this browser has flashed a device before, kept across visits (no secret: a flag). Only that browser's key
   * gets in over adb, so Continue opens the keep step for it (issue 580). None: never.
   */
  flashedHere?: { get(): boolean; set(): void };
  /**
   * Told when the tab starts and stops holding Home Assistant's key (issue 580), so the page can ask before it closes.
   * The session outlives its screen (Kiln's registry), so this follows the session, not the screen.
   */
  holdingKey?(on: boolean): void;
  /**
   * Prepares a connection with another key (PKCS#8): a community image's own published key, for its
   * `wifi-on` step. Called when the key is read; the connector it returns opens the browser's device
   * list inside the Connect click. Used for that one connection; never stored.
   */
  adbWithKey?(pkcs8: Uint8Array): () => Promise<PanelShell>;
  /** This browser's adb public key line, once loaded. */
  adbKey(): string | undefined;
  /** The app's APK (Glassworks' legacy build, from its release or a local file), checked by the page. */
  apk(): Uint8Array | undefined;
  /** Why there is no APK yet when it comes from the release (AppSession.releaseBlocked). */
  apkBlocked?(): string | undefined;
  /** The boot animation, when Kiln has one staged. */
  bootAnimation?(): Promise<Uint8Array | undefined>;
  /** The settings APK the frame's image starts with (its `imageFlow.assets.settings`), when Kiln has it staged: a first boot with nothing to tap. */
  settings?(): Promise<Uint8Array | undefined>;
  sleep?: (ms: number) => Promise<void>;
  /** A simulated device needs no wait for the app's socket. */
  simulated?: boolean;
  /**
   * Glassworks' first start is through: the standard flow takes the device from here (its App step first). The session
   * keeps what the first start found, for Done's lines about the home screen and Home Assistant's key.
   */
  handOver?(panel: PanelShell): void;
  /**
   * Where the install got to, kept for this browser tab across page loads (Kiln's resume after a reload, issue 621):
   * `written`, a write started; `flashed`, the device restarted into what was written and its first start is left.
   * None: a reload forgets.
   */
  progress?: DeviceProgress;
  /**
   * An in-place recipe's storage (the frame's `storage.inPlaceDb`): its bundles and restore points, kept across visits.
   * None: kept in memory for this page load only (demos and tests).
   */
  inPlaceStore?: InPlaceStore;
  /** Web Bluetooth for the `receive` step (navigator.bluetooth by default; a simulated agent's in demos and tests). */
  bluetooth?: BluetoothLike;
  /** fetch for the agent's HTTP server on the local network (the page's by default). */
  agentFetch?: typeof fetch;
  /** The browser's local network permission, read before a LAN transfer. */
  localNetworkPermission?: () => Promise<PermissionState | null>;
  /** The staged assets an in-place stage needs (its `inPlace.assets`), each checked by its SHA-256. */
  stagedAssets?(names: readonly string[]): Promise<Record<string, Uint8Array>>;
  /** The stage's secret: this browser's in the app (browserStageSecret), a new one each run by default. */
  secret?(): Uint8Array;
  /** Bluetooth's stall watchdog for the receive step (tests shorten it). */
  bleStallMs?: number;
  /** The local network transfer's time limit (tests shorten it). */
  lanTimeoutMs?: number;
  /** How often the receive screen checks for the device's own Wi-Fi (tests shorten it). */
  hotspotPollMs?: number;
}

/**
 * A device install's mark across page loads (ImageDeps `progress`, AppDeps `progress`). `image`: the recipe it wrote. An
 * in-place recipe's: `staged`, the device restarted into its setup agent (with the run's `secret`, kept in this tab only
 * until the bundle is in, and the device's `serial`); `received`, the bundle is in this browser (`serial`, `digest`),
 * and `wrote`, the files of its second fastboot session already written, so a reload doesn't write them again.
 */
export type ProgressMark = { at: 'written' | 'flashed' | 'staged' | 'received'; image?: string; secret?: string; serial?: string; digest?: string; wrote?: string[]; started?: string[] };
const MARKS: readonly ProgressMark['at'][] = ['written', 'flashed', 'staged', 'received'];
export interface DeviceProgress { get(): ProgressMark | undefined; set(m: ProgressMark | undefined): void }

/** A mark in the tab's sessionStorage under `key`; a browser that blocks it forgets, as a reload without storage does. */
export function sessionProgress(key: string): DeviceProgress {
  return {
    get: () => {
      try {
        const v = JSON.parse(sessionStorage.getItem(key) ?? 'null') as ProgressMark | null;
        return v && MARKS.includes(v.at) ? v : undefined;
      } catch { return undefined; }
    },
    set: (m) => { try { if (m) sessionStorage.setItem(key, JSON.stringify(m)); else sessionStorage.removeItem(key); } catch { /* storage off */ } },
  };
}

/** `image`: Glassworks' download and settings; `download`: a community image's. */
/** `done`: a community image's end (Glassworks' is the standard flow's Done). */
/** An in-place recipe's: `receive`, the bundle from the device's agent; `restore-point`, kept before any system write; `undo`, its way back. */
export type ImageScreen = 'choose' | 'image' | 'download' | 'keep' | 'fastboot' | 'confirm' | 'working' | 'adb' | 'done' | 'error' | 'receive' | 'restore-point' | 'undo';
export type WorkStep = PrepareStep | 'system-key' | 'adb-key' | 'wifi' | 'flash-system' | 'flash-userdata' | 'restart' | 'first-boot' | 'hand-back' | 'wifi-on'
  | 'stage' | 'in-place' | 'app-install';

/** The receive step's screen (InPlaceScreens ReceiveScreen). */
export interface ReceiveView {
  stage: ReceiveStage;
  channel: ReceiveChannel | null;
  progress: ReceiveProgress | null;
  /** The agent's own page, where the bundle can be downloaded by hand. */
  pageUrl: string | null;
  /** Why Kiln moved to another channel, said calmly. */
  fallback: string | null;
  error: string | null;
  bluetooth: boolean;
  /** The device's own Wi-Fi network, when it opens one (the frame's `hotspot`): the person joins it for the transfer. */
  network: string | null;
  /** Whether the agent answers on that network: this computer has joined it. */
  joined: boolean;
  /** Why the last try over that network failed: the browser blocked the local network, or nothing answered. */
  wifiFailed?: 'lna-denied' | 'unreachable';
}

/** The restore point's screen (InPlaceScreens RestorePointScreen). */
export interface RestoreView { status: 'saving' | 'verifying' | 'ready' | 'error'; digest: string | null; downloaded: boolean; error: string | null; filename: string }

/** The way back from an in-place install (installer.json `wayBack.how` restore-blocks). */
export interface UndoView {
  at: 'start' | 'fastboot' | 'working' | 'done' | 'error';
  /** Where the restore point comes from: this browser, or a zip the person picked. None yet: neither. */
  source?: 'browser' | 'file';
  fileName?: string;
  /** Kiln restarted the device into fastboot over adb. */
  restarted?: boolean;
  error?: string;
  x?: number;
}
/**
 * Home Assistant's ESPHome key across a reflash (issue 580): read from the running app before the flash, given back at the
 * first start. The key itself never goes in the state or the log, only its 4-hex fingerprint; the session holds it in
 * memory until the hand-back.
 */
export type KeyStep =
  /** 0: Glassworks answers over USB, 1: the key is read, 2: the device restarts into fastboot */
  | { state: 'reading'; at: 0 | 1 | 2 }
  /** read, and the device is restarting into fastboot */
  | { state: 'held'; fingerprint: string }
  /** the app answered with no key (Home Assistant hasn't added this device), or no Glassworks: nothing to keep */
  | { state: 'none' }
  /**
   * no adb (another browser installed it, or it doesn't start), or an app from before the hand-over. `restarted`: Kiln
   * restarted an old app's device into fastboot itself
   */
  | { state: 'unreachable'; why: 'no-adb' | 'old-app'; app?: string; restarted?: boolean }
  /** after the first start */
  | { state: 'handed'; fingerprint: string }
  | { state: 'refused'; fingerprint: string };


/**
 * One file of the picked image. `error`: Kiln's route couldn't serve it; `bad`: the bytes came but
 * aren't the release's (deleted). Both are Kiln's bug, never the person's.
 */
export interface FileCheck { name: string; size: number; state: 'waiting' | 'downloading' | 'ok' | 'bad' | 'error'; progress: number; error?: string }

export interface ImageState {
  screen: ImageScreen;
  /** An earlier page load wrote to the device (its `progress` says): the screens say so, and nothing claims it is untouched. */
  changed?: boolean;
  /** The picked image's id. */
  picked: string;
  /** The community images are shown on the choice. */
  open: boolean;
  /** The choice was opened from this image's way back. */
  from?: string;
  /** Arrived by `?image=<id>`: no choice step. */
  link: boolean;
  /** The picked image's files, in the order its recipe downloads them. */
  files: FileCheck[];
  /** A community image's Wi-Fi, saved in its userdata (Glassworks' Wi-Fi is set-up's, after the first start). */
  wifi: { ssid: string; password: string };
  fastboot?: FastbootReport;
  connecting?: boolean;
  connectError?: string;
  /** A device is picked and hasn't let this browser in yet: it is still starting, and Kiln waits and asks again. */
  adbWaiting?: boolean;
  understood: boolean;
  work?: { step: WorkStep; x: number };
  firstBoot?: FirstStart;
  key?: KeyStep;
  /** A community image's Wi-Fi: `saved` once the network is in its userdata, then `on` or `unconfirmed` after wifi-on. */
  communityWifi?: 'saved' | 'on' | 'unconfirmed';
  /** build zeros as RAW blocks: the retry for a bootloader that refused a FILL chunk */
  noFill?: boolean;
  /** the HOME fix on Done is running */
  fixing?: boolean;
  /** the device took the restart into fastboot over adb (restartToFastboot): Connect next */
  restarted?: boolean;
  /** the error came while writing: offer the retry without FILL */
  flashFailed?: boolean;
  /** the error came at a community image's wifi-on, after it was installed: offer Connect again, not a reinstall */
  wifiOnFailed?: boolean;
  /** An in-place recipe's fastboot session: 1 for the stage, 2 for the install after the bundle is in. */
  round?: 1 | 2;
  receive?: ReceiveView;
  /** The bundle came over the device's own Wi-Fi: the next screen says this computer can go back to its usual network. */
  leftHotspot?: boolean;
  restorePoint?: RestoreView;
  undo?: UndoView;
  /** An in-place install's Start over, asked: the screen shows what it resets before it does (startOver). */
  startingOver?: boolean;
  /**
   * This page load opened on an earlier one's mark (a reload, the tab reopened): the resume screen shows over the step
   * it resumes on, until Continue or another screen (docs/design/kiln/navigation.md).
   */
  resumed?: ProgressMark['at'];
  error?: string;
  log: string[];
}

/** The person went on to fastboot by hand while the keep step's read ran. */
class Skipped extends Error {}

/**
 * One adb command with a stall watchdog: a device that stops answering ends in an error after `ms`, not a wait forever
 * (Kiln's engine contract). The command's stream is closed through the signal.
 */
export async function within(p: PanelShell, cmd: string, ms: number, the: string): Promise<{ out: string; code: number }> {
  const c = new AbortController();
  const stalled = new Promise<never>((_, reject) => c.signal.addEventListener('abort', () => reject(c.signal.reason), { once: true }));
  const t = setTimeout(() => c.abort(new Error(`${the} didn't answer \`${cmd}\` in ${Math.round(ms / 1000)} s`)), ms);
  try {
    return await Promise.race([p.run(cmd, c.signal), stalled]);
  } finally {
    clearTimeout(t);
  }
}

/** `p` with each shell command bounded by the frame's `readMs` (its stall watchdog), for reads that pass no signal of their own. */
function bounded(p: PanelShell, f: DeviceFrame): PanelShell {
  return {
    run: (cmd, signal) => (signal ? p.run(cmd, signal) : within(p, cmd, f.timings.readMs, f.the)),
    push: (path, data, mode) => p.push(path, data, mode),
    ...(p.openStream ? { openStream: (service: string) => p.openStream!(service) } : {}),
  };
}

/** The device's board, read over adb before Kiln reads from it or restarts it: anything else isn't this frame. */
async function checkBoard(p: PanelShell, f: DeviceFrame): Promise<void> {
  const board = (await bounded(p, f).run('getprop ro.board.platform')).out.trim();
  if (board !== f.board) throw new Error(`This isn't ${f.the} (board ${board || 'unknown'}, needs ${f.board}). Nothing was changed.`);
}

const waiting = (r: ImageRecipe): FileCheck[] => r.files.map((f) => ({ name: f.name, size: f.size, state: 'waiting', progress: 0 }));

export class ImageSession {
  readonly recipes: readonly ImageRecipe[];
  state: ImageState;
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
  private panel?: PanelShell;
  /** What Kiln made (no prototype: a recipe's names never meet Object's own) */
  private made: Record<string, Blob> = Object.create(null);
  private runs = 0;
  /** A connector with a community image's published adb key, checked, held in memory for its wifi-on step only. */
  private imageConnect?: () => Promise<PanelShell>;
  /** Bumped when the picked image or the way to it changes: a fastboot check started before then is ignored. */
  private selected = 0;
  /** Home Assistant's ESPHome key from before the flash (issue 580). A secret: never in the state, the log or an error */
  private apiKey?: string;
  /** an old app's device, connected, for its restart into fastboot */
  private running?: PanelShell;
  /** The keep step's read in progress: skipKeep bumps `gen`, and the read then lets go of `p` and changes nothing */
  private read: { gen: number; p?: PanelShell; got?: { key: string; fingerprint: string } } = { gen: 0 };
  /** An in-place recipe's run: where it pauses for the person (an index into writes()), and what it holds meanwhile. */
  private cursor?: number;
  /** The stage's secret: in this tab only, until the bundle is in. Never in the state, the log or an error */
  private secret?: Uint8Array;
  /** A bundle that failed only its signature, waiting on the person's Continue anyway. */
  private unverified?: UnverifiedBundle;
  /** The device's fastboot serial: the bundle and the second session must be the same device's. Never in the log */
  private serial?: string;
  private bundle?: { bytes?: Uint8Array; digest: string; extents?: readonly { physical: number; length: number }[] };
  private restore?: { files: NamedPart[]; partition: string; sparse: string };
  /** An in-place run's second-session files written so far (the `received` mark's `wrote`): a resume skips them. */
  private wrote = new Set<string>();
  /** The second session's writes that began (wrote: that finished): once one has, Start over isn't honest. */
  private started = new Set<string>();
  /** The planned step that stopped with an error: Try again runs it again, not the one after it. */
  private stoppedAt?: number;
  /** The receive attempt running now: Cancel, or a newer attempt, aborts it, and nothing it finishes counts after that. */
  private receiving?: AbortController;
  private receiver?: InPlaceReceiver;
  /** The watch for the device's own Wi-Fi on the receive screen (watchHotspot). */
  private watching?: AbortController;
  private storeMemo?: InPlaceStore;
  /** The way back's restore point: the restore image, its partition and manifest. */
  private undoFiles?: { files: NamedPart[]; sparse: NamedPart; manifest?: Record<string, unknown> };

  /** The frame this session installs. */
  get frame() { return this.d.frame; }

  constructor(private readonly d: ImageDeps) {
    this.recipes = d.recipes ?? d.frame.imageFlow.recipes;
    const linked = imageFromLink(this.recipes, d.search ?? '');
    const first = linked ?? this.recipes[0]!;
    this.state = { screen: linked ? 'download' : 'choose', picked: first.id, open: !!linked, link: !!linked, files: waiting(first),
      wifi: { ssid: '', password: '' }, understood: false, log: [] };
    // a reload: an image written and restarted goes straight to its last step over adb, with no download and no fastboot
    // (Glassworks' first start; a community image's Wi-Fi on, with this browser's key, or with a published key that went
    // with the restart's cleanup, so that step then says to switch Wi-Fi on in the image's own Settings). A write that
    // didn't finish starts again from the image, saying an earlier try changed the device
    const mark = d.progress?.get();
    const wrote = mark && this.recipes.find((r) => r.id === mark.image);
    // an in-place recipe resumes where it paused: on receive (its agent runs already), on its second fastboot (the bundle
    // is in this browser), or on its app's install
    const inPlace = wrote?.mode === 'in-place';
    const secret = inPlace && mark?.at === 'staged' && mark.secret ? fromHexString(mark.secret) : undefined;
    if (inPlace && mark?.at === 'staged' && secret && mark.serial) {
      this.secret = secret;
      this.serial = mark.serial;
      this.state = { ...this.state, screen: 'receive', picked: wrote.id, files: waiting(wrote), changed: true, round: 1, receive: this.receiveView('ready'), resumed: 'staged' };
      this.cursor = this.indexOf(wrote, 'receive');
      setTimeout(() => this.watchHotspot(), 0);
    } else if (inPlace && mark?.at === 'received' && mark.serial && mark.digest) {
      this.serial = mark.serial;
      this.bundle = { digest: mark.digest };
      this.wrote = new Set(Array.isArray(mark.wrote) ? mark.wrote.filter((x): x is string => typeof x === 'string') : []);
      this.started = new Set(Array.isArray(mark.started) ? mark.started.filter((x): x is string => typeof x === 'string') : []);
      this.state = { ...this.state, screen: 'fastboot', picked: wrote.id, files: waiting(wrote), changed: true, round: 2, resumed: 'received' };
      this.cursor = this.indexOf(wrote, 'fastboot');
    } else if (mark?.at === 'flashed' && wrote) {
      this.state = { ...this.state, screen: 'adb', picked: wrote.id, open: wrote.community, files: waiting(wrote), changed: true, resumed: 'flashed', ...(inPlace ? { round: 2 as const } : {}) };
      if (inPlace) this.cursor = this.indexOf(wrote, 'app-install');
    } else if (mark) this.state = { ...this.state, changed: true, resumed: 'written' };
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private held = false;
  /** Screen changes so far: a restart asked for on one visit to the fastboot screen doesn't run on a later one. */
  private visits = 0;
  /** Connections asked for so far (a restart, a key read, fastboot, adb): a restart changes the state only while it is the latest. */
  private connects = 0;
  private set(patch: Partial<ImageState>) {
    if (patch.screen !== undefined && patch.screen !== this.state.screen) {
      this.visits++;
      // another screen: the resume screen has done its job
      if (this.state.resumed && !('resumed' in patch)) patch = { ...patch, resumed: undefined };
    }
    this.state = { ...this.state, ...patch };
    const held = this.holdsKey();
    if (held !== this.held) { this.held = held; this.d.holdingKey?.(held); }
    for (const l of this.listeners) l();
  }
  private log = (step: string, detail?: string) => this.set({ log: [...this.state.log, step || `  ${detail}`].slice(-400) });

  /** The picked image's recipe. */
  get recipe(): ImageRecipe { return this.recipes.find((r) => r.id === this.state.picked) ?? this.recipes[0]!; }
  recipeOf(id: string): ImageRecipe | undefined { return this.recipes.find((r) => r.id === id); }

  setWifi(wifi: Partial<ImageState['wifi']>) { this.set({ wifi: { ...this.state.wifi, ...wifi } }); }
  understand(understood: boolean) { this.set({ understood }); }

  /** On the choice: picking writes nothing and downloads nothing. */
  pick(id: string) {
    const r = this.recipeOf(id);
    if (!r || this.state.screen !== 'choose' || r.id === this.state.picked) return;
    this.selected++;
    void this.forget();
    this.set({ picked: r.id, files: waiting(r) });
  }

  /** Shows or hides the community images; hiding them while one is picked goes back to Glassworks. */
  showCommunity(open: boolean) {
    if (!open && this.recipe.community) this.pick(this.recipes[0]!.id);
    this.set({ open });
  }

  /** Continue on the choice: Glassworks' screen, or the community image's download. */
  choose() {
    if (this.state.screen !== 'choose') return;
    this.set({ screen: this.recipe.community ? 'download' : 'image' });
  }

  /** "Install Glassworks instead", from a community image's screens: its own screen, nothing downloaded for it yet. */
  toGlassworks() {
    this.selected++;
    const g = this.recipes[0]!;
    void this.forget();
    this.set({ picked: g.id, files: waiting(g), screen: 'image', link: false, fastboot: undefined, understood: false, error: undefined });
  }

  /** Back to the choice from an image's first screen. */
  backToChoice() {
    this.selected++;
    this.set({ screen: 'choose', link: false, fastboot: undefined, understood: false, connectError: undefined });
  }

  /**
   * Done's way back: the choice again, with the other image picked and the community images shown
   * when it is one. A fresh install from the files on: nothing of the last one carries over.
   */
  wayBack(id: string) {
    const r = this.recipeOf(id);
    if (!r) return;
    const from = this.state.picked;
    this.panel = undefined;
    this.made = Object.create(null);
    this.selected++;
    // a fresh install: an earlier one's key outcome (handed, refused, none…) goes; a key still held for a hand-back stays
    const key = this.state.key?.state === 'held' ? this.state.key : undefined;
    this.set({ screen: 'choose', picked: r.id, from, open: r.community, link: false, files: waiting(r), fastboot: undefined, understood: false, communityWifi: undefined,
      work: undefined, firstBoot: undefined, key, error: undefined, flashFailed: false, noFill: false });
  }

  /**
   * Drops what an earlier pick downloaded: the worker keeps one image's files at a time. The worker
   * stops a download in flight and runs the discard before anything sent after it.
   */
  private async forget() {
    this.runs++;
    this.made = Object.create(null);
    if (this.state.files.some((f) => f.state !== 'waiting')) await this.d.images.discard().catch(() => {});
  }

  /**
   * `?image=<id>` on a page whose kept session is idle: open that image's install, as a new session
   * would. A session that is writing, or waiting for the first start, stays as it is.
   */
  followLink(search: string) {
    const linked = imageFromLink(this.recipes, search);
    if (!linked || this.state.screen === 'working' || this.state.screen === 'adb' || (this.state.link && this.state.picked === linked.id)) return;
    this.selected++; // a link that changes the install: a fastboot check from before it is ignored
    const key = this.endKeepRead(); // and a key read from before it stops (PR 596 review)
    if (linked.id !== this.state.picked) void this.forget();
    this.set({ screen: 'download', picked: linked.id, open: true, link: true, from: undefined, key, connecting: false,
      files: linked.id === this.state.picked ? this.state.files : waiting(linked), fastboot: undefined, understood: false, error: undefined });
  }

  /**
   * Downloads the picked image's files one after the other, each checked against its SHA-256 as it
   * arrives. Files already checked stay; Try again carries on from the first that isn't.
   */
  async download(): Promise<void> {
    const r = this.recipe;
    const run = ++this.runs;
    const patched = new Set(r.steps.flatMap((s) => (s.do === 'patch' ? [s.file] : [])));
    const at = (i: number, patch: Partial<FileCheck>) => {
      if (run !== this.runs) return;
      this.set({ files: this.state.files.map((f, k) => (k === i ? { ...f, ...patch } : k > i && f.state !== 'ok' ? { ...f, state: 'waiting', progress: 0, error: undefined } : f)) });
    };
    for (const step of r.steps) {
      if (step.do !== 'download') continue;
      const i = r.files.findIndex((f) => f.name === step.file);
      const want = r.files[i]!;
      if (this.state.files[i]?.state === 'ok') continue;
      at(i, { state: 'downloading', progress: 0, error: undefined });
      try {
        if (!this.d.refract) throw new ProxyError('Kiln has no download route on this page');
        const url = `${this.d.refract}/${want.refract}`;
        const progress = (x: number) => at(i, { state: 'downloading', progress: x });
        if (patched.has(want.name)) await this.d.images.fetch(url, progress);
        else await this.d.images.download(url, want, progress);
        if (run !== this.runs) return;
        at(i, { state: 'ok', progress: 1 });
        this.log('', `${want.name} downloaded through Refract, SHA-256 ${want.sha256} as published`);
      } catch (e) {
        if (run !== this.runs) return;
        const bad = e instanceof WrongImage;
        const why = (e as Error).message;
        this.log('', `${want.name}: ${why}`);
        at(i, { state: bad ? 'bad' : 'error', progress: 0, error: why });
        return;
      }
    }
  }

  /** A new issue on Glassworks with what Kiln saw: a download Kiln can't serve is Kiln's bug. */
  reportUrl(f: FileCheck): string {
    const r = this.recipe;
    const title = `Kiln couldn't download ${f.name} for ${r.name}`;
    const body = `Image: ${r.id}\nFile: ${f.name} (${f.size} bytes)\nWhat Kiln saw: ${f.error ?? (f.state === 'bad' ? 'its SHA-256 differs from the release' : 'unknown')}\n`;
    return `https://github.com/GlassworksHQ/glassworks/issues/new?title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`;
  }

  /** Why Continue can't go on to fastboot yet, or undefined when it can. */
  blocked(): string | undefined {
    const s = this.state;
    const left = s.files.find((f) => f.state !== 'ok');
    if (left) return `Download ${left.name} first.`;
    if (this.recipe.community) return this.recipe.steps.some((x) => x.do === 'wifi') ? this.wifiBlocked() : undefined;
    if (this.inPlace) { const w = this.wifiBlocked(); if (w) return w; }
    if (!this.d.apk()) return this.d.apkBlocked?.() ?? 'Pick the Glassworks APK file first.';
    return undefined;
  }

  /** The network as wpa_supplicant can save it, or why not: refused here, before anything is built (wpaSupplicantConf). */
  private wifiBlocked(): string | undefined {
    const { ssid, password } = this.state.wifi;
    if (!ssid !== !password && !ssid) return 'Give the Wi-Fi network\'s name too.';
    // the password is saved as WPA's PSK in userdata
    if (ssid && new TextEncoder().encode(ssid).length > 32) return 'A Wi-Fi network\'s name is at most 32 bytes.';
    if (ssid && password && !/^[\x20-\x7e]{8,63}$/.test(password) && !/^[0-9a-fA-F]{64}$/.test(password)) {
      return 'The Wi-Fi password must be 8 to 63 characters (letters, digits, symbols), or empty for an open network.';
    }
    return undefined;
  }

  /** The planned steps that will actually run: a community image's Wi-Fi steps only when the person gave a network. */
  private runningSteps(steps: PlannedStep[]): PlannedStep[] {
    return this.recipe.community && !this.state.wifi.ssid ? steps.filter((x) => x.do !== 'wifi' && x.do !== 'wifi-on') : steps;
  }

  /** Continue: fastboot, or the keep step first in a browser that has flashed a device before (it alone gets in over adb). */
  toFastboot() {
    if (this.blocked()) return;
    this.stoppedAt = undefined; // a run from its first screen: nothing of an earlier stop carries on
    if (!this.recipe.community && !this.inPlace && !this.state.key && this.d.flashedHere?.get()) this.toKeep();
    else this.set({ screen: 'fastboot', connectError: undefined, restarted: false, ...(this.inPlace ? { round: 1 as const } : {}) });
  }

  /** The step before the flash, for a device that runs Glassworks (issue 580). A key read earlier stays until a new one is read. */
  toKeep() { if (!this.blocked()) this.set({ screen: 'keep', key: this.apiKey ? this.state.key : undefined, connectError: undefined }); }

  /** Going on with fastboot by hand: a key read already stays, to give back after the flash; Done says what HA does. */
  skipKeep() {
    this.set({ screen: 'fastboot', key: this.endKeepRead(), connecting: false, connectError: undefined });
  }

  /**
   * Ends a keep-step read still running: its connection goes, and whatever it does next is dropped, so it can't restart
   * the device or store a key afterwards (PR 603 review). Gives the key state to go on with: a key it had read already
   * stays, held for the hand-back; a read with nothing yet leaves none, never `reading`.
   */
  private endKeepRead(): KeyStep | undefined {
    let k = this.state.key;
    const { got, p } = this.read;
    this.read = { gen: this.read.gen + 1 };
    void p?.close?.().catch(() => {});
    void this.running?.close?.().catch(() => {});
    this.running = undefined;
    if (k?.state === 'reading') {
      if (got) { this.apiKey = got.key; k = { state: 'held', fingerprint: got.fingerprint }; } else k = undefined;
    }
    return k;
  }

  /** Whether the tab holds a key that would be lost if it closed now (the page warns before it leaves). */
  holdsKey(): boolean { return !!this.apiKey && this.state.key?.state === 'held'; }

  /**
   * Inside the click, on the keep step: the device over adb with this browser's key, read-only, then Home Assistant's
   * key from the app (readApiKey, as flash-node does), then the restart into fastboot. Nothing is written. A key the app
   * has but can't hand over now stops here, before the restart: a flash would lose it.
   */
  async readKey(): Promise<void> {
    const req = this.d.requestAdb({ authMs: this.d.frame.timings.authMs });
    const gen = this.read.gen + 1;
    this.read = { gen };
    this.connects++;
    this.set({ connecting: true, connectError: undefined, key: { state: 'reading', at: 0 } });
    let p: PanelShell;
    try {
      p = await req;
    } catch (e) {
      if (gen !== this.read.gen) return; // skipped meanwhile
      if (e instanceof AdbAuthTimeout) {
        this.log('', `adb: ${this.d.frame.the} didn't let this browser's key in`);
        this.set({ connecting: false, key: { state: 'unreachable', why: 'no-adb' } });
      } else {
        this.set({ connecting: false, key: undefined, connectError: (e as Error).message });
      }
      return;
    }
    if (gen !== this.read.gen) { await p.close?.().catch(() => {}); return; }
    this.read.p = p;
    this.set({ connecting: false });
    const sleep = this.d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    /** after each wait on the device: stop if the person went on to fastboot by hand meanwhile */
    const live = () => { if (gen !== this.read.gen) throw new Skipped(); };
    const r = bounded(p, this.d.frame); // every read below ends in an error if the device stops answering; the catch lets go of p
    try {
      this.log('Reading Home Assistant\'s key from Glassworks');
      await checkBoard(r, this.d.frame);
      live();
      const app = await kioskVersion(r, undefined, this.d.frame.app);
      live();
      this.log('', app ? `${this.d.frame.app.name} ${app.name ?? app.code}` : `no ${this.d.frame.app.name} on ${this.d.frame.the}`);
      // the app opens its socket as it starts: a device still starting gets a little time, an app older than issue 574 never opens it
      let up = !!app && !!p.openStream && await apiKeySocketUp(r);
      for (let i = 0; app && p.openStream && !up && i < 10; i++) { await sleep(2000); live(); up = await apiKeySocketUp(r); }
      live();
      if (app && !up) {
        this.log('', 'no API key socket: an app from before the hand-over (issue 574)');
        this.running = p;
        this.read = { gen };
        this.set({ key: { state: 'unreachable', why: 'old-app', app: app.name ?? String(app.code) } });
        return;
      }
      this.set({ key: { state: 'reading', at: 1 } });
      // read as late as the app still runs, so a key HA changed meanwhile is the one carried
      // the socket listened just now: one gone by the read is an app that restarted, so stop rather than carry nothing
      const key = up ? await readApiKey(r, undefined, { socket: 'required' }) : undefined;
      const fingerprint = key ? await apiKeyFingerprint(key) : undefined;
      live(); // before it saves anything: a read skipped meanwhile must not write into the next one's record
      if (key && fingerprint) this.read.got = { key, fingerprint };
      this.log('', fingerprint ? `Home Assistant API key read, fingerprint ${fingerprint}, held in this tab only` : 'no Home Assistant API key to keep');
      this.set({ key: { state: 'reading', at: 2 } });
      await this.restart(p);
      live();
      this.read = { gen };
      this.apiKey = key;
      // restarted: "Fastboot by hand" from here (skipKeep) lands on Connect, not on another restart
      this.set({ key: fingerprint ? { state: 'held', fingerprint } : { state: 'none' }, restarted: true });
    } catch (e) {
      // skipped, or overtaken by a newer read: let go of this connection only, and leave the state to the newer one
      if (e instanceof Skipped || gen !== this.read.gen) { await p.close?.().catch(() => {}); return; }
      this.read = { gen };
      // the reply's first word at most (readApiKey): no key material in the message
      this.log('Stopped', (e as Error).message);
      // let go of the connection, so Try again starts a clean one on the same device
      await p.close?.().catch(() => {});
      this.set({ key: undefined, connectError: `Kiln couldn't read the key: ${(e as Error).message}. Nothing was written to ${this.d.frame.the}. Try again.` });
    }
  }

  /** An old app's device (no key to read): Kiln restarts it into fastboot, then the person connects fastboot. */
  async restartOld(): Promise<void> {
    const k = this.state.key;
    const p = this.running;
    if (k?.state !== 'unreachable' || !p) { this.skipKeep(); return; }
    this.connects++;
    this.set({ connecting: true, connectError: undefined });
    try {
      await this.restart(p);
      this.running = undefined;
      this.set({ connecting: false, key: { ...k, restarted: true }, restarted: true });
    } catch (e) {
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  /**
   * Inside the click, on the fastboot screen: a device that lets this browser's key in over adb (it runs Glassworks from
   * this browser) restarts into fastboot, with no button inside its case. Glassworks' own install goes through the keep
   * step instead (toKeep), which reads Home Assistant's key first and then restarts the same way.
   */
  async restartToFastboot(): Promise<void> {
    if (this.state.connecting) return;
    const req = this.d.requestAdb({ authMs: this.d.frame.timings.authMs });
    this.set({ connecting: true, connectError: undefined, restarted: false });
    // this restart counts only while it is the latest one and its visit to the screen lasts: an older one, or one whose
    // screen was left (Back, maybe Continue back to it), lets go of its connection and changes nothing
    const restart = ++this.connects;
    const visit = this.visits;
    const latest = () => restart === this.connects;
    const mine = () => latest() && visit === this.visits;
    let p: PanelShell | undefined;
    try {
      p = await req;
      if (!mine()) { void p.close?.().catch(() => {}); if (latest()) this.set({ connecting: false }); return; }
      await checkBoard(p, this.d.frame);
      if (!mine()) { void p.close?.().catch(() => {}); if (latest()) this.set({ connecting: false }); return; }
      await this.restart(p);
      if (latest()) this.set({ connecting: false, restarted: mine() });
    } catch (e) {
      void p?.close?.().catch(() => {}); // a refusal (not the device) or a failed restart: let go of the connection
      if (!mine()) { if (latest()) this.set({ connecting: false }); return; }
      this.set({ connecting: false, connectError: e instanceof AdbAuthTimeout ? this.d.frame.words['fastboot.notLetIn'] : (e as Error).message });
    }
  }

  /**
   * From an error while writing or building: the device is still in fastboot, so back to Connect, with nothing to
   * restart. Files the error made Kiln drop (a missing stored file, a build that lost its source) download again first.
   */
  reconnectFastboot() {
    if (this.state.files.some((x) => x.state !== 'ok')) { this.retry(); return; }
    this.set({ screen: 'fastboot', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false, restarted: true });
  }

  // ---- an in-place recipe (images.ts mode in-place: stage, receive, in-place, app-install) ----

  /** The picked recipe changes the stock software in place. */
  get inPlace(): boolean { return this.recipe.mode === 'in-place'; }

  /** The frame's in-place code: a recipe in-place mode on a frame without it is a frame bug. */
  private code(): InPlaceCode {
    const c = this.d.frame.imageFlow.inPlace;
    if (!c) throw new Error(`${cap(this.d.frame.the)} has no in-place code for ${this.recipe.name}.`);
    return c;
  }

  private store(): InPlaceStore { return this.d.inPlaceStore ?? (this.storeMemo ??= memoryInPlaceStore()); }

  /**
   * The `n`th (from 0) planned step of `kind` in `r`'s writes (plan() after the warning): the first session's fastboot
   * comes before the warning, so the second session's is the first one here.
   */
  private indexOf(r: ImageRecipe, kind: PlannedStep['do'], n = 0): number {
    const all = plan(r);
    const steps = all.slice(all.findIndex((x) => x.do === 'confirm') + 1);
    let seen = -1;
    return steps.findIndex((x) => x.do === kind && ++seen === n);
  }

  private receiveView(stage: ReceiveStage, patch: Partial<ReceiveView> = {}): ReceiveView {
    const h = this.hotspot();
    return {
      stage, channel: null, progress: null, pageUrl: this.state?.receive?.pageUrl ?? h?.page ?? null, fallback: null, error: null,
      bluetooth: this.state?.receive?.bluetooth ?? true, network: h?.ssid ?? null, joined: this.state?.receive?.joined ?? false, ...patch,
    };
  }

  /** The device's own Wi-Fi and the agent's addresses on it, when the frame declares one and the serial is known. */
  private hotspot() {
    return this.recipe && this.inPlace ? hotspotUrls(this.d.frame.imageFlow.inPlace?.channels.lan, this.serial) : null;
  }

  /**
   * While the receive screen waits for the person, checks every few seconds whether the agent answers on the device's
   * own Wi-Fi, so the screen can say this computer joined it. One watch at a time; it ends when the screen moves on.
   */
  private watchHotspot(): void {
    if (!this.hotspot() || this.watching) return;
    const watch = (this.watching = new AbortController());
    const waiting = () => this.state.screen === 'receive' && (this.state.receive?.stage === 'waiting-boot' || this.state.receive?.stage === 'ready');
    void (async () => {
      try {
        while (!watch.signal.aborted && this.state.screen === 'receive') {
          if (waiting() && this.serial) {
            const ok = await this.receiverOf(this.receiveStep().channels).hotspotReachable(this.serial, watch.signal);
            if (watch.signal.aborted) return;
            if (waiting() && ok !== this.state.receive!.joined) {
              this.log('', ok ? 'this computer reaches the setup agent on the device\'s own Wi-Fi' : 'the setup agent stopped answering on the device\'s own Wi-Fi');
              this.set({ receive: { ...this.state.receive!, joined: ok, ...(ok ? { stage: 'ready' as const } : {}) } });
            }
          }
          await new Promise((r) => setTimeout(r, this.d.hotspotPollMs ?? 3000));
        }
      } finally {
        if (this.watching === watch) this.watching = undefined;
      }
    })();
  }

  /**
   * Runs an in-place recipe's planned steps from `from` (an index into writes()), until one waits on the person: the
   * restart into the agent (receive), the second fastboot, the restore point, and the app's install over adb. Each
   * pause keeps its place (`cursor`); its screen's button carries on from the next step.
   */
  private async runFrom(from: number): Promise<void> {
    const r = this.recipe;
    const steps = this.writes();
    const work = (step: WorkStep, x: number) => this.set({ work: { step, x } });
    const network = this.state.wifi.ssid ? { ssid: this.state.wifi.ssid, password: this.state.wifi.password } : undefined;
    let i = from;
    this.stoppedAt = undefined;
    try {
      for (; i < steps.length; i++) {
        const step = steps[i]!;
        const fb = this.fb;
        switch (step.do) {
          case 'backup':
            this.log(`Backup: Kiln keeps a restore point of every block it changes in the system, before it changes any. App data starts fresh.`);
            break;
          case 'stage': {
            if (!fb || !this.state.fastboot) throw new Error(`${cap(this.d.frame.the)} isn't connected in fastboot mode.`);
            const adbKey = this.d.adbKey();
            if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
            const code = this.code();
            this.set({ screen: 'working', round: 1 });
            work('stage', 0);
            this.log('Preparing the setup agent');
            // a new run: the stage's secret, and a new receiver (what an earlier run's agent said is that run's, not this one's)
            this.receiving?.abort();
            this.receiving = undefined;
            this.receiver = undefined;
            this.restore = undefined;
            if (this.state.leftHotspot) this.set({ leftHotspot: undefined });
            this.wrote = new Set();
            this.secret = (this.d.secret ?? newSecret)();
            const assets = code.assets?.length ? await (this.d.stagedAssets?.(code.assets) ?? Promise.resolve({})) : {};
            // a device with its own Wi-Fi gets the person's network only in the final install: one that joins it might
            // not open its own, and the transfer goes over that one
            const stageNetwork = code.channels.lan?.hotspot ? undefined : network;
            this.made[step.makes] = await code.stage({ adbKey, userdataBytes: this.state.fastboot.userdataBytes, secret: this.secret, assets, ...(stageNetwork ? { wifi: stageNetwork } : {}) });
            work('stage', 1);
            this.log('', stageNetwork ? 'the setup agent, this browser\'s adb key, its signing secret and your network, on a fresh userdata' : 'the setup agent, this browser\'s adb key and its signing secret, on a fresh userdata');
            this.d.progress?.set({ at: 'written', image: r.id });
            break;
          }
          case 'flash': {
            if (!fb) throw new Error(`${cap(this.d.frame.the)} isn't connected in fastboot mode.`);
            // the second session's writes are checkpointed one by one: after a reload, one already written isn't written again
            const checkpointed = !!this.restore;
            if (checkpointed && this.wrote.has(step.file)) {
              this.log(`Flashing ${step.partition}`);
              this.log('', 'already written before this page reloaded, so not written again');
              break;
            }
            const blob = this.made[step.file];
            if (!blob) throw new Error(`Kiln has no ${step.file} to write. Nothing more was written.`);
            const id = `flash-${step.partition}` as WorkStep;
            this.log(`Flashing ${step.partition}${step.partition === this.restore?.partition ? ' (only the blocks the restore point covers)' : ''}`);
            this.set({ flashFailed: true, screen: 'working' });
            work(id, 0);
            // a write that stops partway leaves the partition changed: from its first byte on, Start over isn't offered,
            // after a reload too (the mark says so)
            if (checkpointed && this.serial && this.bundle) {
              this.started.add(step.file);
              this.d.progress?.set({ at: 'received', image: r.id, serial: this.serial, digest: this.bundle.digest, wrote: [...this.wrote], started: [...this.started] });
            }
            await fb.flashBlob(step.partition, blob, (x) => work(id, x));
            this.log('', `${step.partition}: ${(blob.size / 2 ** 20).toFixed(1)} MiB written`);
            if (checkpointed && this.serial && this.bundle) {
              this.wrote.add(step.file);
              this.d.progress?.set({ at: 'received', image: r.id, serial: this.serial, digest: this.bundle.digest, wrote: [...this.wrote], started: [...this.started] });
            }
            break;
          }
          case 'restart': {
            if (!fb) throw new Error(`${cap(this.d.frame.the)} isn't connected in fastboot mode.`);
            this.set({ flashFailed: false });
            work('restart', 1);
            this.log(`Restarting ${this.d.frame.the}`);
            await fb.reboot();
            this.fb = undefined;
            this.made = Object.create(null);
            const next = steps[i + 1]?.do;
            if (next === 'receive' && this.secret && this.serial) {
              this.d.progress?.set({ at: 'staged', image: r.id, secret: toHexString(this.secret), serial: this.serial });
            } else if (next === 'app-install') {
              this.restore = undefined;
              this.wrote = new Set();
              this.d.flashedHere?.set(); // this browser's key is in what it wrote: the keep step can read it next time
              this.d.progress?.set({ at: 'flashed', image: r.id });
            }
            break;
          }
          case 'receive':
            this.cursor = i;
            this.set({ screen: 'receive', work: undefined, receive: this.receiveView('waiting-boot', { joined: false, bluetooth: await this.receiverOf(step.channels).bluetoothAvailable().catch(() => false) }) });
            this.watchHotspot();
            return;
          case 'fastboot':
            this.cursor = i;
            this.set({ screen: 'fastboot', round: 2, work: undefined, fastboot: undefined, restarted: false, connectError: undefined });
            return;
          case 'in-place': {
            if (!fb || !this.serial) throw new Error(`${cap(this.d.frame.the)} isn't connected in fastboot mode.`);
            const adbKey = this.d.adbKey();
            if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
            this.set({ screen: 'working', round: 2 });
            work('in-place', 0);
            const bundle = await this.bundleBytes();
            this.log('Building the changes from the bundle');
            const flashes = steps.slice(i + 1).filter((x): x is Extract<PlannedStep, { do: 'flash' }> => x.do === 'flash');
            const partitionBytes: Record<string, number> = {};
            for (const x of flashes) partitionBytes[x.partition] ??= await fb.partitionSize(x.partition);
            const code = this.code();
            const assets = code.buildAssets?.length ? await (this.d.stagedAssets?.(code.buildAssets) ?? Promise.resolve({})) : {};
            const built = await code.build(bundle.bytes, { adbKey, userdataBytes: this.state.fastboot?.userdataBytes ?? partitionBytes.userdata ?? 0, partitionBytes, serial: this.serial, assets, ...(network ? { wifi: network } : {}) });
            for (const m of step.makes) if (!built.writes[m]) throw new Error(`the frame's build made no ${m}`);
            // Kiln's own check before any byte is written: the surgical write (the first one after the restore point) changes
            // only the blocks the bundle names, and the restore puts back exactly those
            const surgical = flashes.find((x) => step.makes.includes(x.file))!;
            const write = await blobBytes(built.writes[surgical.file]!);
            const restore = await blobBytes(built.restore);
            checkInPlace({ write, restore, extents: bundle.extents, partitionBytes: partitionBytes[surgical.partition]! });
            const touched = parseSparseWrites(write).ranges.reduce((n, x) => n + (x.end - x.start), 0);
            this.log('', `checked: the write changes ${touched} bytes of ${surgical.partition}, all inside the bundle's file, and the restore puts back exactly those`);
            this.made = Object.assign(Object.create(null) as Record<string, Blob>, built.writes);
            const enc = new TextEncoder();
            const sparse = `${step.restore}.sparse`;
            // the restore point names the device it is for (the SHA-256 of its fastboot serial, never the serial): the way
            // back writes it nowhere else
            const deviceIdHash = await sha256Hex(enc.encode(this.serial.trim()));
            if (built.manifest?.deviceIdHash !== undefined && built.manifest.deviceIdHash !== deviceIdHash) throw new Error('The frame\'s restore point names another device than this one. Nothing more was written.');
            const manifest = { ...built.manifest, deviceIdHash };
            this.restore = {
              partition: surgical.partition,
              sparse,
              files: [
                { filename: sparse, data: restore },
                { filename: 'manifest.json', data: enc.encode(`${JSON.stringify(manifest, null, 2)}\n`) },
                ...Object.entries(built.files ?? {}).map(([filename, data]) => ({ filename, data })),
              ],
            };
            work('in-place', 1);
            break;
          }
          case 'restore-point':
            this.cursor = i;
            this.set({ screen: 'restore-point', work: undefined, restorePoint: { status: 'saving', digest: null, downloaded: false, error: null, filename: `${this.restore?.sparse ?? `${step.restore}.sparse`}` } });
            void this.saveRestorePoint();
            return;
          case 'app-install':
            this.cursor = i;
            this.set({ screen: 'adb', work: undefined });
            return;
          case 'way-back':
            break;
          default:
            throw new Error(`Kiln can't run "${step.do}" in an in-place install.`);
        }
      }
    } catch (e) {
      this.cursor = i;
      this.stoppedAt = i;
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message, ...(this.state.work ? {} : { work: { step: steps[i]?.do === 'in-place' ? 'in-place' : 'stage', x: 0 } }) });
    }
  }

  /** The bundle, from memory or this browser's storage (a reload after it came). */
  private async bundleBytes(): Promise<{ bytes: Uint8Array; extents: readonly { physical: number; length: number }[] }> {
    const b = this.bundle;
    if (!b || !this.serial) throw new Error('Kiln has no bundle for this device. Start the install again from its first screen.');
    if (b.bytes && b.extents) return { bytes: b.bytes, extents: b.extents };
    const bytes = await this.store().getBundle(this.serial, b.digest);
    if (!bytes) throw new Error('This browser no longer has the bundle it received. Start the install again from its first screen.');
    // checked again as it comes out of storage: the secret is gone, so as a kept copy
    const r = await this.code().readBundle(bytes, { secret: new Uint8Array(32), serial: this.serial, kept: true });
    if (r.digest !== b.digest) throw new Error('The bundle this browser kept doesn\'t match the one it received. Start the install again from its first screen.');
    this.bundle = { bytes, digest: r.digest, extents: r.extents };
    return { bytes, extents: r.extents };
  }

  private receiverOf(channels: readonly ('lan' | 'ble' | 'file')[]): InPlaceReceiver {
    return this.receiver ??= new InPlaceReceiver({
      code: this.code(), store: this.store(), channels, bluetooth: this.d.bluetooth, fetchImpl: this.d.agentFetch,
      localNetworkPermission: this.d.localNetworkPermission, ...(this.d.bleStallMs ? { ble: { stallTimeoutMs: this.d.bleStallMs } } : {}),
      ...(this.d.lanTimeoutMs ? { lanTimeoutMs: this.d.lanTimeoutMs } : {}),
    });
  }

  private receiveStep(): Extract<PlannedStep, { do: 'receive' }> {
    return this.writes().find((x): x is Extract<PlannedStep, { do: 'receive' }> => x.do === 'receive')!;
  }

  private progressOf(p: { receivedBytes: number; totalBytes: number; bytesPerSecond: number; etaSeconds: number | null; resendPass?: number }): ReceiveProgress {
    return { receivedBytes: p.receivedBytes, totalBytes: p.totalBytes, bytesPerSecond: p.bytesPerSecond, etaSeconds: p.etaSeconds, ...(p.resendPass ? { resendPass: p.resendPass } : {}) };
  }

  /**
   * The receive step's button, inside the click (the browser's Bluetooth chooser): the agent's description over
   * Bluetooth, then the bundle from this browser's copy, the local network, or Bluetooth, checked before it is kept.
   */
  async receive(): Promise<void> {
    if (this.state.screen !== 'receive' || this.state.receive?.stage === 'connecting' || this.state.receive?.stage === 'transferring') return;
    const secret = this.stageSecret(), serial = this.serial;
    if (!serial) { this.set({ receive: this.receiveView('error', { error: 'This tab no longer knows which device it is installing on. Start the install again from its first screen.' }) }); return; }
    const step = this.receiveStep();
    const rec = this.receiverOf(step.channels);
    const { signal } = this.newReceive();
    const live = () => !signal.aborted;
    this.set({ receive: this.receiveView('connecting') });
    let fellBack: string | null = null;
    let lanDenied = false;
    try {
      const got = await rec.receive({
        secret, serial, signal,
        onMeta: (_m, pageUrl) => { if (live()) this.set({ receive: { ...this.state.receive!, pageUrl } }); },
        onChannel: (c) => { if (live()) this.set({ receive: { ...this.state.receive!, stage: c === 'cache' ? 'cache-hit' : 'transferring', channel: c, fallback: fellBack } }); },
        onProgress: (p) => { if (live()) this.set({ receive: { ...this.state.receive!, progress: this.progressOf(p) } }); },
        onFallback: (e) => { if (!live()) return; lanDenied = e.reason === 'lna-denied'; fellBack = fallbackWords(e); this.log('', fellBack); this.set({ receive: { ...this.state.receive!, fallback: fellBack } }); },
        onVerifying: () => { if (live()) this.set({ receive: { ...this.state.receive!, stage: 'verifying' } }); },
      });
      if (!this.endReceive(signal)) return; // cancelled, or another attempt took over: what this one brought doesn't count
      await this.received(got);
    } catch (e) {
      if (!this.endReceive(signal)) return;
      if (e instanceof UnverifiedBundle) { this.askUnverified(e); return; }
      const why = (e as Error).message;
      this.log('', `receive: ${why}`);
      this.set({ receive: this.receiveView(lanDenied ? 'lna-denied' : 'error', { error: why, channel: this.state.receive?.channel ?? null }) });
    }
  }

  /**
   * Continue on the join screen: the bundle straight from the agent on the device's own Wi-Fi. When nothing answers
   * there, the screen offers Bluetooth or another try; nothing switches over on its own.
   */
  async receiveWifi(): Promise<void> {
    if (this.state.screen !== 'receive' || this.state.receive?.stage === 'connecting' || this.state.receive?.stage === 'transferring') return;
    const secret = this.stageSecret(), serial = this.serial;
    if (!serial || !this.hotspot()) { this.set({ receive: this.receiveView('error', { error: 'This tab no longer knows which device it is installing on. Start the install again from its first screen.' }) }); return; }
    const rec = this.receiverOf(this.receiveStep().channels);
    const { signal } = this.newReceive();
    const live = () => !signal.aborted;
    this.set({ receive: this.receiveView('connecting', { channel: 'lan', wifiFailed: undefined }) });
    try {
      const got = await rec.receiveHotspot({
        secret, serial, signal,
        onChannel: (c) => { if (live()) this.set({ receive: { ...this.state.receive!, stage: 'transferring', channel: c } }); },
        onProgress: (p) => { if (live()) this.set({ receive: { ...this.state.receive!, progress: this.progressOf(p) } }); },
        onVerifying: () => { if (live()) this.set({ receive: { ...this.state.receive!, stage: 'verifying' } }); },
      });
      if (!this.endReceive(signal)) return;
      this.set({ leftHotspot: true });
      await this.received(got);
    } catch (e) {
      if (!this.endReceive(signal)) return;
      if (e instanceof UnverifiedBundle) { this.set({ leftHotspot: true }); this.askUnverified(e); return; }
      const kind = (e as { kind?: string }).kind;
      this.log('', `receive over the device's own Wi-Fi: ${(e as Error).message}`);
      if (kind === 'lna-denied' || kind === 'timeout' || kind === 'network-error') {
        this.set({ receive: this.receiveView('wifi-unreachable', { joined: false, wifiFailed: kind === 'lna-denied' ? 'lna-denied' : 'unreachable' }) });
        this.watchHotspot();
        return;
      }
      this.set({ receive: this.receiveView('error', { error: (e as Error).message, channel: 'lan' }) });
    }
  }

  /** A new receive attempt: any earlier one is aborted first, so only this one's outcome counts. */
  private newReceive(): AbortController {
    this.receiving?.abort();
    this.receiving = new AbortController();
    return this.receiving;
  }

  /** Whether the attempt with `signal` is still the one that counts (and lets go of it): false once cancelled or replaced. */
  private endReceive(signal: AbortSignal): boolean {
    if (signal.aborted || this.receiving?.signal !== signal) return false;
    this.receiving = undefined;
    return true;
  }

  /** A bundle file the person downloaded from the agent's own page. */
  async receiveFile(file: Blob): Promise<void> {
    if (this.state.screen !== 'receive') return;
    const secret = this.stageSecret(), serial = this.serial;
    if (!serial) { this.set({ receive: this.receiveView('error', { error: 'This tab no longer knows which device it is installing on. Start the install again from its first screen.' }) }); return; }
    const rec = this.receiverOf(this.receiveStep().channels);
    // a transfer still running over the network or Bluetooth stops: the file takes its place
    const { signal } = this.newReceive();
    const live = () => !signal.aborted;
    this.set({ receive: this.receiveView('transferring', { channel: 'file' }) });
    try {
      const got = await rec.receiveFile(file, {
        secret, serial, signal,
        onProgress: (p) => { if (live()) this.set({ receive: { ...this.state.receive!, progress: this.progressOf(p) } }); },
        onVerifying: () => { if (live()) this.set({ receive: { ...this.state.receive!, stage: 'verifying' } }); },
      });
      if (!this.endReceive(signal)) return;
      await this.received(got);
    } catch (e) {
      if (!this.endReceive(signal)) return;
      if (e instanceof UnverifiedBundle) { this.askUnverified(e); return; }
      this.set({ receive: this.receiveView('error', { error: (e as Error).message, channel: 'file' }) });
    }
  }

  /** The stage's secret: this run's, or (after a reload that lost it) the one the deps give, this browser's in the app. */
  private stageSecret(): Uint8Array {
    return this.secret ?? this.d.secret?.() ?? new Uint8Array(32);
  }

  /** A bundle that failed only its signature: the receive screen asks before Kiln goes on with it. */
  private askUnverified(u: UnverifiedBundle) {
    this.unverified = u;
    this.log('', `receive: ${u.message}; waiting for the person to decide`);
    this.set({ receive: this.receiveView('unverified', { channel: u.channel }) });
  }

  /** Continue anyway on the signature warning: every other check runs again, then the install goes on. */
  async receiveUnverified(): Promise<void> {
    const u = this.unverified, serial = this.serial;
    if (this.state.screen !== 'receive' || !u || !serial) return;
    this.unverified = undefined;
    const rec = this.receiverOf(this.receiveStep().channels);
    const { signal } = this.newReceive();
    this.set({ receive: this.receiveView('verifying', { channel: u.channel }) });
    try {
      const got = await rec.acceptUnverified(u, { secret: this.stageSecret(), serial, signal });
      if (!this.endReceive(signal)) return;
      this.log('', 'went on without the signature, as asked');
      await this.received(got, false);
    } catch (e) {
      if (!this.endReceive(signal)) return;
      this.set({ receive: this.receiveView('error', { error: (e as Error).message, channel: u.channel }) });
    }
  }

  /** The receive screen's file step, when there's no Bluetooth (or the person asks for it). */
  receiveByHand() { if (this.state.screen === 'receive') this.set({ receive: this.receiveView('file-fallback') }); }

  /** The receive screen's Try again: back to connecting. */
  receiveAgain() { if (this.state.screen === 'receive') { this.unverified = undefined; this.set({ receive: this.receiveView('ready') }); this.watchHotspot(); } }

  /** Cancel on the receive screen: stops a transfer in flight; the agent keeps running, so Connect starts it again. */
  async cancelReceive(): Promise<void> {
    // the attempt stops counting first, so nothing it finishes while the link winds down moves the install on
    this.receiving?.abort();
    this.receiving = undefined;
    this.set({ receive: this.receiveView('ready') });
    this.watchHotspot();
    await this.receiver?.cancel();
  }

  private async received(got: Received, signed = true): Promise<void> {
    // the secret has done its job: the tab lets go of it, and the mark keeps only where the bundle is
    this.secret = undefined;
    this.wrote = new Set();
    this.bundle = { bytes: got.bytes, digest: got.digest, extents: got.extents };
    this.log('Bundle received');
    this.log('', `over ${got.channel === 'cache' ? 'this browser\'s saved copy' : got.channel === 'lan' ? 'the local network' : got.channel === 'ble' ? 'Bluetooth' : 'a file'}, checked: its SHA-256, the device's serial${signed ? ' and its signature' : ''}`);
    for (const line of got.info) this.log('', line);
    this.d.progress?.set({ at: 'received', image: this.recipe.id, serial: this.serial, digest: got.digest });
    await this.runFrom((this.cursor ?? this.indexOf(this.recipe, 'receive')) + 1);
  }

  /** Saves the restore point in this browser and reads it back: Continue opens only once every byte checks out. */
  private async saveRestorePoint(): Promise<void> {
    const rp = this.restore, serial = this.serial, digest = this.bundle?.digest;
    const fail = (why: string) => this.set({ restorePoint: { ...this.state.restorePoint!, status: 'error', error: why } });
    if (!rp || !serial || !digest) { fail('Kiln has nothing to keep yet. Connect fastboot again.'); return; }
    try {
      const persisted = await this.store().persist();
      this.log('Saving the restore point');
      this.log('', persisted ? 'this browser keeps it until you clear this site\'s data' : 'this browser may clear it when space runs low: download a copy too');
      await this.store().putRestore(serial, digest, rp.files);
      this.set({ restorePoint: { ...this.state.restorePoint!, status: 'verifying' } });
      const back = await this.store().getRestore(serial, digest);
      for (const f of rp.files) {
        const got = back?.find((x) => x.filename === f.filename);
        if (!got || await sha256Hex(got.data) !== await sha256Hex(f.data)) throw new Error(`${f.filename} didn't read back as it was saved`);
      }
      const sum = await sha256Hex(rp.files[0]!.data);
      this.log('', `${rp.sparse}: saved and read back, SHA-256 ${sum}`);
      // a copy of this same restore point already downloaded from this browser (before a reload): Continue doesn't wait again
      const copied = await this.store().copied(serial, digest, sum).catch(() => false);
      if (copied) this.log('', 'a copy of it was downloaded from this browser before');
      this.set({ restorePoint: { ...this.state.restorePoint!, status: 'ready', digest: sum, error: null, downloaded: copied || this.state.restorePoint!.downloaded } });
    } catch (e) {
      this.log('', `restore point: ${(e as Error).message}`);
      fail((e as Error).message);
    }
  }

  /** The restore point screen's Try again. */
  retryRestorePoint() { if (this.state.screen === 'restore-point') { this.set({ restorePoint: { ...this.state.restorePoint!, status: 'saving', error: null } }); void this.saveRestorePoint(); } }

  /** The restore point as a zip, for the person to keep: its files and a SHA256SUMS. */
  async restoreZip(): Promise<{ blob: Blob; name: string }> {
    const files = this.restore?.files ?? (this.serial && this.bundle ? await this.store().getRestore(this.serial, this.bundle.digest) : undefined);
    if (!files) throw new Error('Kiln has no restore point to download yet.');
    const zip = await restorePointZip(this.recipe.id, files);
    const rp = this.state.restorePoint;
    if (rp) {
      this.set({ restorePoint: { ...rp, downloaded: true } });
      // kept with the restore point, so a reload that saves it again doesn't ask for the copy twice
      if (rp.digest && this.serial && this.bundle) await this.store().markCopied(this.serial, this.bundle.digest, rp.digest).catch(() => undefined);
      this.log('', 'a copy of the restore point downloaded to this computer');
    }
    return zip;
  }

  /** Continue on the restore point: the writes, once it is saved and checked and a copy is on the computer. */
  async continueInstall(): Promise<void> {
    const rp = this.state.restorePoint;
    if (this.state.screen !== 'restore-point' || rp?.status !== 'ready' || !rp.downloaded || this.cursor === undefined) return;
    this.set({ screen: 'working' });
    await this.runFrom(this.cursor + 1);
  }

  // ---- the way back from an in-place install: the restore point, written over the blocks it covers ----

  /** The way back's partition: the one the restore point is before (the recipe's), `system` when there's no recipe. */
  private restorePartition(): string {
    const steps = this.writes();
    const at = steps.findIndex((x) => x.do === 'restore-point');
    const next = steps.slice(at + 1).find((x): x is Extract<PlannedStep, { do: 'flash' }> => x.do === 'flash');
    return next?.partition ?? 'system';
  }

  private takeRestore(files: NamedPart[]): UndoView['source'] {
    const sparse = files.find((f) => f.filename.endsWith('.sparse'));
    if (!sparse) throw new Error('This restore point has no restore image (.sparse).');
    const m = files.find((f) => f.filename === 'manifest.json');
    let manifest: Record<string, unknown> | undefined;
    if (m) { try { manifest = JSON.parse(new TextDecoder().decode(m.data)) as Record<string, unknown>; } catch { throw new Error('The restore point\'s manifest.json isn\'t JSON.'); } }
    parseSparseWrites(sparse.data); // a whole sparse image, before anything else
    this.undoFiles = { files, sparse, ...(manifest ? { manifest } : {}) };
    return 'browser';
  }

  /** Whether this session is writing to the device, reaching it, or taking its bundle right now: the way back waits until it has stopped. */
  private busy(): boolean {
    return this.state.screen === 'working' || !!this.state.connecting || !!this.receiving || this.state.undo?.at === 'working';
  }

  /**
   * Opens the way back: the restore point this browser kept, if any. Nothing is written yet. Not while an install is
   * writing (an undo link opened meanwhile, say): that install keeps its screens, and Done or its error offers the way back.
   */
  async startUndo(): Promise<void> {
    if (this.busy()) return;
    const in_ = this.recipes.find((r) => r.mode === 'in-place');
    if (in_) this.set({ picked: in_.id });
    this.set({ screen: 'undo', undo: { at: 'start' }, error: undefined });
    try {
      const kept = await this.store().latestRestore();
      if (kept) { this.takeRestore(kept.files); this.set({ undo: { at: 'start', source: 'browser' } }); }
    } catch (e) {
      this.set({ undo: { at: 'start', error: (e as Error).message } });
    }
  }

  /**
   * The way back from a stop in the second fastboot session (a write that failed, a restart that didn't happen): this
   * run's restore point, saved and checked already, written from fastboot, where the device still is.
   */
  restoreAfterError(): void {
    const rp = this.restore;
    if (this.state.screen !== 'error' || !this.inPlace || this.state.restorePoint?.status !== 'ready' || !rp) return;
    try {
      this.takeRestore(rp.files);
      this.set({ screen: 'undo', undo: { at: 'fastboot', source: 'browser', restarted: false }, error: undefined, connectError: undefined });
    } catch (e) {
      this.set({ screen: 'undo', undo: { at: 'start', error: (e as Error).message } });
    }
  }

  /** A restore point zip the person picked (the copy they downloaded), checked against its SHA256SUMS. */
  async pickRestoreZip(file: File | Blob, name = (file as File).name ?? 'restore point'): Promise<void> {
    try {
      const { files } = await readNamedBackupZip(file);
      this.takeRestore(files);
      this.set({ undo: { at: 'start', source: 'file', fileName: name } });
    } catch (e) {
      this.set({ undo: { ...(this.state.undo ?? { at: 'start' }), error: (e as Error).message } });
    }
  }

  /** Inside the click: over adb, the frame's undo of the app's install (best effort), then the restart into fastboot. */
  async undoRestart(): Promise<void> {
    if (this.state.connecting) return;
    const req = this.d.requestAdb({ authMs: this.d.frame.timings.authMs });
    this.set({ connecting: true, connectError: undefined });
    let p: PanelShell | undefined;
    try {
      p = await req;
      await checkBoard(p, this.d.frame);
      // the frame's undo over adb runs only on the device this restore point was made on: its serial, hashed, against
      // the restore point's. Another device is refused outright; one Kiln can't tell gets nothing changed over adb
      const want = this.undoFiles?.manifest?.deviceIdHash;
      const serial = (await bounded(p, this.d.frame).run('getprop ro.serialno').catch(() => ({ out: '' }))).out.trim();
      const same = typeof want === 'string' && !!serial && await sha256Hex(new TextEncoder().encode(serial)) === want.toLowerCase();
      if (typeof want === 'string' && serial && !same) throw new Error('This restore point was made on another device. Connect that one. Nothing was changed.');
      const before = this.d.frame.imageFlow.inPlace?.beforeRestore;
      if (before && same) await before(bounded(p, this.d.frame), { onStep: this.log }).catch((e: Error) => this.log('', `undoing the app's changes: ${e.message}`));
      else if (before) this.log('', 'Kiln couldn\'t tell over adb that this is the device the restore point was made on, so it changed nothing over adb');
      await this.restart(p);
      this.set({ connecting: false, undo: { ...this.state.undo!, at: 'fastboot', restarted: true, error: undefined } });
    } catch (e) {
      void p?.close?.().catch(() => {});
      this.set({ connecting: false, connectError: e instanceof AdbAuthTimeout ? this.d.frame.words['fastboot.notLetIn'] : (e as Error).message });
    }
  }

  /** Fastboot by hand on the way back (the button inside the case). */
  undoByHand() { if (this.state.undo) this.set({ undo: { ...this.state.undo, at: 'fastboot', restarted: false }, connectError: undefined }); }

  /**
   * Inside the click: the device's fastboot, its partition and serial checked against the restore point's manifest, then
   * the restore image written over the blocks it covers, and a restart.
   */
  async undoFlash(): Promise<void> {
    const u = this.undoFiles;
    if (!u || this.state.connecting) return;
    const req = this.d.requestFastboot();
    this.set({ connecting: true, connectError: undefined });
    let fb: FastbootLike;
    try { fb = await req; } catch (e) { this.set({ connecting: false, connectError: (e as Error).message }); return; }
    const partition = this.restorePartition();
    try {
      const bytes = await fb.partitionSize(partition);
      const sparse = parseSparseWrites(u.sparse.data);
      if (sparse.totalBytes !== bytes) throw new Error(`This restore point is for a ${partition} partition of ${sparse.totalBytes} bytes; this device's is ${bytes}. Nothing was written.`);
      // a restore point is for one device: one that doesn't say which (or that fastboot can't name) isn't written
      const want = u.manifest?.deviceIdHash;
      if (typeof want !== 'string' || !/^[0-9a-f]{64}$/i.test(want)) throw new Error('This restore point doesn\'t say which device it was made on, so Kiln won\'t write it. Nothing was written.');
      const serial = (await fb.getvar('serialno').catch(() => '')).trim();
      if (!serial || await sha256Hex(new TextEncoder().encode(serial)) !== want.toLowerCase()) throw new Error('This restore point was made on another device. Nothing was written.');
      if (sparse.filled.length) throw new Error('This restore point fills blocks instead of restoring them, so Kiln won\'t write it. Nothing was written.');
      // what the install changed outside those blocks (the frame's way back: a fresh userdata, say), built before any write
      const wayBack = this.d.frame.imageFlow.inPlace?.wayBack;
      let after: { writes: Record<string, Blob>; what: string } | undefined;
      if (wayBack) {
        const partitionBytes: Record<string, number> = {};
        for (const x of wayBack.partitions) partitionBytes[x] = await fb.partitionSize(x);
        after = await wayBack.build({ partitionBytes });
        for (const x of wayBack.partitions) if (!after.writes[x]) throw new Error(`The way back made no ${x} to write. Nothing was written.`);
      }
      this.set({ connecting: false, undo: { ...this.state.undo!, at: 'working', x: 0 } });
      this.log(`Writing the restore point to ${partition}`);
      this.log('', `${u.sparse.filename}: ${sparse.ranges.reduce((n, x) => n + (x.end - x.start), 0)} bytes, only the blocks the install changed`);
      const parts = 1 + (wayBack?.partitions.length ?? 0);
      await fb.flashBlob(partition, new Blob([u.sparse.data as Uint8Array<ArrayBuffer>]), (x) => this.set({ undo: { ...this.state.undo!, x: x / parts } }));
      if (wayBack && after) {
        let n = 1;
        for (const x of wayBack.partitions) {
          this.log(`Writing ${x}`);
          const at = n++;
          await fb.flashBlob(x, after.writes[x]!, (y) => this.set({ undo: { ...this.state.undo!, x: (at + y) / parts } }));
        }
        this.log('', after.what);
      }
      this.log(`Restarting ${this.d.frame.the}`);
      await fb.reboot();
      this.d.progress?.set(undefined);
      this.set({ undo: { ...this.state.undo!, at: 'done', x: 1 } });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ connecting: false, undo: { ...this.state.undo!, at: this.state.undo?.at === 'working' ? 'error' : 'fastboot', error: (e as Error).message } });
    }
  }

  private async restart(p: PanelShell) {
    if (!p.rebootToBootloader) throw new Error(`this connection can't restart ${this.d.frame.the}: put it in fastboot by hand`);
    this.log(`Restarting ${this.d.frame.the} into fastboot`);
    await p.rebootToBootloader();
  }

  /** Inside the click: the browser's device list opens first, then Kiln's checks read the device's fastboot (read-only). */
  async connectFastboot(): Promise<void> {
    const req = this.d.requestFastboot();
    const sel = this.selected;
    this.connects++; // an older restart still out must not clear this one's state
    this.set({ connecting: true, connectError: undefined });
    try {
      const fb = await req;
      const fastboot = await this.d.frame.imageFlow.checkFastboot(fb);
      // another image was picked (or a link opened) while this ran: its checks belong to that install, not this one
      if (sel !== this.selected) return;
      this.fb = fb;
      const r = this.recipe;
      // a community image's userdata goes on as published, at its own size: the partition must hold it (Glassworks builds one to fit)
      // (with the network saved, the copy Kiln makes is the same size, so the same holds)
      const floor = this.d.frame.imageFlow.communityUserdata;
      const publishedUserdata = !!floor && r.steps.some((x) => x.do === 'flash' && x.partition === 'userdata' && sourceFile(r, x.file)?.name === floor.file);
      if (floor && r.community && publishedUserdata && fastboot.userdataBytes < floor.bytes) {
        fastboot.checks.push({ what: `Userdata partition for ${r.name}`, found: `${fastboot.userdataBytes} bytes`, ok: false, need: `at least ${floor.bytes} bytes, the size its userdata image expands to` });
        fastboot.ok = false;
      }
      for (const c of fastboot.checks) this.log('', `${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}`);
      fastboot.info.forEach((i) => this.log('', i));
      if (this.inPlace && fastboot.ok) {
        // the bundle and both sessions must be one device's: its serial, from its fastboot (kept in this tab, never logged)
        const serial = (await fb.getvar('serialno').catch(() => '')).trim();
        if (sel !== this.selected) return;
        if (!serial) { this.set({ connecting: false, connectError: `Kiln couldn't read ${this.d.frame.the}'s serial number over fastboot, so it can't tell its bundle from another device's. Nothing was written.` }); return; }
        // the second session goes on from its fastboot pause; a step that stopped with an error (in either session) runs
        // again itself, on the same device: a write that failed partway is written again, not skipped
        const stopped = this.stoppedAt;
        if (this.state.round === 2 || stopped !== undefined) {
          if (this.serial && serial !== this.serial) {
            this.set({ connecting: false, connectError: `This is another device than the one Kiln started the install on. Connect that one. Nothing was written.` });
            return;
          }
          this.serial = serial;
          this.set({ connecting: false, fastboot, screen: 'working', error: undefined });
          void this.runFrom(stopped ?? (this.cursor ?? this.indexOf(this.recipe, 'fastboot')) + 1);
          return;
        }
        this.serial = serial;
      }
      this.set({ connecting: false, fastboot, screen: fastboot.ok ? 'confirm' : 'fastboot' });
    } catch (e) {
      if (sel !== this.selected) return;
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  /** The planned steps from the first write on: what flash() runs, and what the install screen lists. */
  writes(): PlannedStep[] {
    const all = plan(this.recipe);
    return this.runningSteps(all.slice(all.findIndex((s) => s.do === 'confirm') + 1));
  }

  /**
   * Runs the picked recipe from the warning on: Kiln's backup step, then its writes in order. Only
   * after Kiln's checks passed and the person confirmed everything on the device is replaced.
   */
  async flash(): Promise<void> {
    const s = this.state;
    const fb = this.fb;
    if (!fb || !s.fastboot?.ok || !s.understood || s.screen === 'working') return;
    if (this.inPlace) return this.runFrom(0);
    const r = this.recipe;
    const steps = this.writes();
    const work = (step: WorkStep, x: number) => this.set({ work: { step, x } });
    const now = () => performance.now();
    const secs = (ms: number) => `${(ms / 1000).toFixed(1)} s`;
    const first = steps.find((x) => x.do !== 'backup');
    this.d.progress?.set({ at: 'written', image: r.id });
    this.set({ screen: 'working', error: undefined, flashFailed: false, communityWifi: undefined, firstBoot: undefined, work: { step: first?.do === 'patch' ? 'patch' : first?.do === 'flash' ? `flash-${first.partition}` as WorkStep : 'restart', x: 0 } });
    // what an earlier attempt made (Glassworks' images, a userdata with a network) is made again by its
    // own step, or not at all: a retry with the network cleared must write userdata as published
    this.made = Object.create(null);
    let building = false, reading = false;
    const network = s.wifi.ssid ? { ssid: s.wifi.ssid, password: s.wifi.password } : undefined;
    // what a flash step writes: with no network, a wifi step's file is what it would have started from
    const writtenName = (name: string) => (!network && wifiStep(r, name) ? wifiStep(r, name)!.file : name);
    try {
      // the device's LK can't read anything back: Kiln's backup step is the record of what it writes
      this.log(`Backup: none possible, ${this.d.frame.the}'s bootloader can't read anything back. What Kiln writes:`);
      for (const x of steps) {
        if (x.do !== 'flash') continue;
        const src = sourceFile(r, x.file);
        if (!src) continue; // built by Kiln (Glassworks' patch)
        // what Kiln changes in it on the way: one file each
        const name = writtenName(x.file);
        const changes = [...(systemKeyStep(r, name) ? ['this browser\'s adb key in place of the one it lets in without asking'] : []),
          ...(adbKeyStep(r, name) || adbKeyStep(r, wifiStep(r, name)?.file ?? '') ? ['this browser\'s adb key in place of the published one'] : []),
          ...(network && wifiStep(r, name) ? ['the Wi-Fi network saved in it'] : [])];
        this.log('', changes.length ? `${src.name}: SHA-256 ${src.sha256} as published, with ${changes.join(' and ')}` : `${src.name}: SHA-256 ${src.sha256}, as ${r.name}'s release publishes it`);
      }
      // every published file this install writes, opened and checked now, before the first write; those very Blobs go to
      // fastboot, so a file another tab changed can't leave one partition written and the other not. A userdata with the
      // network saved is made later by the wifi step, which hashes its source as it reads it.
      const verified: Record<string, Blob> = Object.create(null);
      reading = true;
      for (const x of steps) {
        if (x.do !== 'flash' || verified[x.file]) continue;
        const want = r.files.find((f) => f.name === writtenName(x.file));
        if (!want) continue; // made by Kiln: Glassworks' patch, or a copy the system-key, adb-key or wifi step hashes as it reads
        const blob = await this.d.images.file(want.name);
        this.log('', `${want.name}: checking it again before writing`);
        await this.d.images.check(blob, want, () => {});
        verified[x.file] = blob;
      }
      reading = false;
      for (const step of steps) {
        if (step.do === 'patch') {
          const adbKey = this.d.adbKey();
          if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
          this.log('Building the images');
          building = true;
          // each build step's time, in the log: where the minutes go on a real device
          let at = { step: '', t: now() };
          const built = (step: string) => { if (at.step && at.step !== step) this.log('', `${at.step}: built in ${secs(now() - at.t)}`); if (at.step !== step) at = { step, t: now() }; };
          this.made = Object.assign(Object.create(null) as Record<string, Blob>, await this.d.images.build({
            apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), settings: await this.d.settings?.(),
            adbKey, userdataBytes: s.fastboot.userdataBytes, fill: !s.noFill, ...(s.fastboot.deviceMac ? { deviceMac: s.fastboot.deviceMac } : {}),
            // the flag only: the key stays in this tab until the first start
            ...(this.apiKey ? { apiKeyHandover: true } : {}),
          }, (step, x) => { built(step); work(step, x); }));
          built('');
          building = false;
          this.log('', `Kiln built: ${step.makes.join(', ')}`);
        } else if (step.do === 'system-key') {
          const adbKey = this.d.adbKey();
          if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
          reading = true;
          this.log('Putting this browser\'s adb key in its system image');
          work('system-key', 0);
          this.made[step.makes] = await this.d.images.systemKey({ system: r.files.find((f) => f.name === step.file)!, makes: step.makes, adbKey }, (x) => work('system-key', x));
          reading = false;
          this.log('', `${step.file} letting in this browser's key without asking, in place of the one it hardcodes, and nothing else changed`);
        } else if (step.do === 'adb-key') {
          const adbKey = this.d.adbKey();
          if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
          reading = true;
          this.log('Putting this browser\'s adb key in its userdata');
          work('adb-key', 0);
          this.made[step.makes] = await this.d.images.adbKey({ userdata: r.files.find((f) => f.name === step.file)!, makes: step.makes, adbKey }, (x) => work('adb-key', x));
          reading = false;
          this.log('', `${step.file} with this browser's key in /misc/adb/adb_keys in place of the published one, and nothing else changed`);
        } else if (step.do === 'wifi') {
          if (!network) continue; // not planned without one (writes()): userdata goes on as published
          const key = steps.find((x): x is Extract<PlannedStep, { do: 'wifi-on' }> => x.do === 'wifi-on');
          reading = true;
          if (key?.key) {
            // the image's published key, checked now: the restart deletes the downloads, and it is needed after it
            this.imageConnect = this.d.adbWithKey?.(await readImageKey(this.d.images, r.files.find((f) => f.name === key.key)!));
          }
          this.log('Saving the Wi-Fi network you entered'); // never its name: logs end up in failure reports (CLAUDE.md rule 2)
          work('wifi', 0);
          const file = (n: string) => r.files.find((f) => f.name === n)!;
          // on the copy the adb-key step made, when it starts from one
          const made = adbKeyStep(r, step.file) ? step.file : undefined;
          this.made[step.makes] = await this.d.images.wifi({ userdata: sourceFile(r, step.file)!, ...(made ? { made } : {}), system: file(step.template), makes: step.makes, wifi: network }, (x) => work('wifi', x));
          reading = false;
          this.set({ communityWifi: 'saved' });
          this.log('', `${step.file} with the network saved in /misc/wifi/wpa_supplicant.conf, and nothing else changed`);
        } else if (step.do === 'flash') {
          const id = `flash-${step.partition}` as WorkStep;
          const name = writtenName(step.file);
          const blob = this.made[name] ?? verified[step.file];
          if (!blob) throw new Error(`Kiln has no checked ${step.file} to write. Nothing more was written.`);
          const asIs = r.community && !this.made[name];
          this.log(`Flashing ${step.partition}${asIs ? ` with ${name} as published` : r.community ? ` with ${name}, built by Kiln from a checked download` : s.noFill ? ' (zeros as RAW, no FILL chunks)' : ''}`);
          this.set({ flashFailed: true }); // until the restart: a failure from here is the bootloader's
          work(id, 0);
          // sending is the bar; after it the bootloader writes (and may erase) with nothing to report
          const t0 = now();
          let sent: number | undefined;
          await fb.flashBlob(step.partition, blob, (x) => { if (x >= 1) sent ??= now(); work(id, x); });
          const end = now();
          this.log('', `${step.partition}: sent ${(blob.size / 2 ** 20).toFixed(1)} MiB in ${secs((sent ?? end) - t0)}, then ${this.d.frame.the} wrote it in ${secs(end - (sent ?? end))}`);
        } else if (step.do === 'restart') {
          this.set({ flashFailed: false });
          work('restart', 1);
          this.log(`Restarting ${this.d.frame.the}`);
          await fb.reboot();
          this.fb = undefined;
          this.made = Object.create(null);
          if (!r.community) this.d.flashedHere?.set(); // this browser's key is in the Glassworks it wrote: the keep step can read it next time
          // a reload from here goes to the step over adb still to come (Glassworks' first start, a community image's Wi-Fi
          // on); with none left, the install ends here and there is nothing to resume
          const after = steps.some((x) => x.do === 'first-start' || x.do === 'wifi-on');
          this.d.progress?.set(after ? { at: 'flashed', image: r.id } : undefined);
          // nothing kept any more: another install downloads again. The device is restarting into what was written, so a
          // storage error here is only logged: it mustn't turn a finished install into an error
          this.set({ files: waiting(r) });
          await this.d.images.discard().catch((e: Error) => this.log('', `deleting the downloads failed: ${e.message}`));
        }
      }
      const more = steps.some((x) => x.do === 'first-start' || x.do === 'wifi-on');
      if (!more) this.d.progress?.set(undefined); // a community image written as published: Kiln's part is over
      this.set({ screen: more ? 'adb' : 'done', ...(more ? {} : { changed: undefined }) });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      // a failed build dropped the fetched image (it may be patched halfway), and a stored file that is gone or
      // no longer checks out can't be written: either way, download again. A refused write keeps the downloads.
      this.set({ screen: 'error', error: (e as Error).message, ...(building || reading ? { files: waiting(r) } : {}) });
    }
  }

  /**
   * A device already flashed with Glassworks whose first start didn't finish (an error, or the page was reloaded):
   * "One last click." again; set-up follows it, as after any first start.
   */
  finishFirstStart() {
    if (this.recipe.community || this.state.screen === 'working') return;
    // the connection of a first start that stopped: let go, so Connect opens one fresh (not awaited: Connect's list stays in its click)
    const stale = this.panel;
    this.panel = undefined;
    void stale?.close?.().catch(() => {});
    this.set({ screen: 'adb', error: undefined, connectError: undefined, adbWaiting: false, work: undefined, flashFailed: false, firstBoot: undefined });
  }

  /**
   * After the restart, inside the click: adb with this browser's key, then Kiln's first start (Glassworks). A device
   * still starting refuses the key: Kiln asks again every 5 s through the first start, and says so if it never lets in.
   * For a community image's wifi-on: this browser's key (or the image's own published one), then `svc wifi enable` and nothing else.
   */
  async connectAdb(): Promise<void> {
    if (this.recipe.community) return this.connectWifiOn();
    if (this.state.connecting) return;
    // every try has a deadline: the first `firstStartAuthMs`, each retry at most `authMs` (reopenAdb)
    const run = ++this.connects;
    // the screen says it waits from the pick on: the first try alone can take firstStartAuthMs (a real run, 2026-11-19)
    let pickedNow = false;
    const sleep = this.d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    const { reopenAdb, adbPicked } = this.d;
    const asking = askAdb({ request: (o) => this.d.requestAdb(o), ...(reopenAdb ? { reopen: (o) => reopenAdb.call(this.d, o) } : {}), ...(adbPicked ? { picked: () => adbPicked.call(this.d) } : {}) }, {
      firstAuthMs: this.d.frame.timings.firstStartAuthMs, authMs: this.d.frame.timings.authMs, sleep, tries: this.d.adbTries, windowMs: this.d.adbWindowMs,
      onPicked: () => { pickedNow = true; if (run === this.connects && this.state.connecting) this.set({ adbWaiting: true }); },
      onWait: (why) => {
        this.log('', `${cap(this.d.frame.the)} didn't let this browser in yet (${why}): it is still starting. Asking again in 5 s.`);
        this.set({ adbWaiting: true });
      },
    });
    this.set({ connecting: true, connectError: undefined, adbWaiting: pickedNow });
    const asked = await asking;
    if (!asked.panel) {
      const { last, picked, retried } = asked;
      const why = (last as Error).message;
      this.set({ connecting: false, adbWaiting: false, connectError: !picked && nothingPicked(last) ? `Nothing was picked. Click Connect and ${pickInList(this.d.frame)}.`
        : retried && !(last instanceof AdbBusy) ? `${cap(this.d.frame.the)} didn't let this browser in through its first start (${why}). It knows only the key of the browser that installed it: use that browser, then Connect again.` : why });
      return;
    }
    let p = asked.panel;
    this.panel = p;
    this.set({ connecting: false, adbWaiting: false, screen: 'working', work: { step: 'first-boot', x: 0 } });
    try {
      p = await this.untilStarted(p, run, sleep);
      this.panel = p;
      const k = this.state.key;
      // firstBoot hands the key back right after it starts the app (PR 575): the row turns to it there
      const onStep = (step: string, detail?: string) => {
        if (step.startsWith('Home Assistant API key')) this.set({ work: { step: 'hand-back', x: 0 } });
        this.log(step, detail);
      };
      // Home Assistant and the Wi-Fi are set-up's, next, over this same connection. An in-place recipe's app-install
      // installs the app itself (its image carries none)
      let fb: FirstStart;
      if (this.inPlace) {
        const apk = this.d.apk();
        if (!apk) throw new Error(this.d.apkBlocked?.() ?? 'Kiln has no Glassworks APK to install. Pick it again, then Connect.');
        this.set({ work: { step: 'app-install', x: 0 } });
        this.log(`Installing ${this.d.frame.app.name}`);
        fb = await this.code().appInstall(p, { apk, onStep, sleep });
      } else fb = await this.d.frame.imageFlow.firstBoot(p, { onStep, sleep, ...(this.apiKey ? { apiKey: this.apiKey } : {}) });
      const key: KeyStep | undefined = k?.state === 'held' && fb.apiKey !== undefined
        ? { state: fb.apiKey ? 'handed' : 'refused', fingerprint: k.fingerprint } : k;
      if (fb.apiKey !== undefined) this.apiKey = undefined; // given back or refused: the tab lets go of it
      // the standard flow has the device from here. This page, if it is opened again, starts from its first screen; what
      // the first start found stays for Done (the home screen, the key, the way back)
      this.d.progress?.set(undefined); // installed: the standard flow's routes resume from here
      this.set({ screen: this.state.link ? 'download' : 'choose', firstBoot: fb, key, changed: undefined, work: { step: this.state.work?.step ?? 'first-boot', x: 1 } });
      this.d.handOver?.(p);
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message });
    }
  }

  /**
   * Waits for Android to say it has started (`sys.boot_completed`), at most 6 minutes. A device can reset its USB
   * partway through its start, after Kiln is in (an Android 6 device did, 2026-11-25: "Failed to execute
   * 'transferOut' on 'USBDevice'"): the poll's next call fails, so Kiln lets go and connects to the same device again,
   * with no list, as the first connect asks again. In: the device as connected. Out: the device as connected now.
   */
  private async untilStarted(first: PanelShell, run: number, sleep: (ms: number) => Promise<void>): Promise<PanelShell> {
    let p = first;
    const { reopenAdb } = this.d;
    for (let i = 0; ; i++) {
      if (i > 180) throw new Error(`${this.d.frame.the} didn't finish starting in 6 minutes`);
      let started: boolean;
      try {
        started = (await p.run('getprop sys.boot_completed')).out.trim() === '1';
      } catch (e) {
        if (!reopenAdb || run !== this.connects) throw e;
        const why = (e as Error).message;
        this.log('', `${cap(this.d.frame.the)} dropped the USB connection while it starts (${why}). Connecting again.`);
        const stale = p;
        void stale.close?.().catch(() => {});
        p = await this.reconnect(reopenAdb, sleep, e);
        this.panel = p;
        continue;
      }
      if (started) return p;
      await sleep(2000);
    }
  }

  /** The device picked, again with no list, every 5 s until it lets in: as the first connect, at most `adbTries` times. */
  private async reconnect(reopen: NonNullable<ImageDeps['reopenAdb']>, sleep: (ms: number) => Promise<void>, first: unknown): Promise<PanelShell> {
    let last = first;
    for (let i = 0; i < (this.d.adbTries ?? 60); i++) {
      await sleep(5000);
      try { return await reopen.call(this.d, { authMs: this.d.frame.timings.authMs }); } catch (e) { last = e; }
      if (last instanceof AdbBusy) break;
    }
    throw last;
  }

  private async connectWifiOn(): Promise<void> {
    if (this.state.connecting) return;
    const step = this.recipe.steps.find((x): x is Extract<PlannedStep, { do: 'wifi-on' }> => x.do === 'wifi-on');
    // the image's published key (step.key), or this browser's, which the adb-key step put in its userdata
    const published = !!step?.key;
    const run = ++this.connects;
    let p: PanelShell;
    if (published) {
      const req = this.imageConnect ? this.imageConnect() : Promise.reject(new Error(`Kiln has no key for this image here. Turn Wi-Fi on in ${this.d.frame.the}'s Settings once instead.`));
      this.set({ connecting: true, connectError: undefined });
      try { p = await req; } catch (e) { this.set({ connecting: false, connectError: (e as Error).message }); return; }
    } else {
      // the device refuses this browser's key until its framework is up: ask again every 5 s, as the first start does
      const sleep = this.d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
      const { reopenAdb, adbPicked } = this.d;
      const asking = askAdb({ request: (o) => this.d.requestAdb(o), ...(reopenAdb ? { reopen: (o) => reopenAdb.call(this.d, o) } : {}), ...(adbPicked ? { picked: () => adbPicked.call(this.d) } : {}) }, {
        firstAuthMs: this.d.frame.timings.firstStartAuthMs, authMs: this.d.frame.timings.authMs, sleep, tries: this.d.adbTries, windowMs: this.d.adbWindowMs,
        onPicked: () => { if (run === this.connects && this.state.connecting) this.set({ adbWaiting: true }); },
        onWait: (why) => {
          this.log('', `${cap(this.d.frame.the)} didn't let this browser in yet (${why}): it is still starting. Asking again in 5 s.`);
          this.set({ adbWaiting: true });
        },
      });
      this.set({ connecting: true, connectError: undefined });
      const asked = await asking;
      if (!asked.panel) {
        const { last, picked, retried } = asked;
        const why = (last as Error).message;
        this.set({ connecting: false, adbWaiting: false, connectError: !picked && nothingPicked(last) ? `Nothing was picked. Click Connect and ${pickInList(this.d.frame)}.`
          : retried && !(last instanceof AdbBusy) ? `${cap(this.d.frame.the)} didn't let this browser in (${why}). It knows only the key of the browser that installed it: use that browser, then Connect again.` : why });
        return;
      }
      p = asked.panel;
    }
    this.set({ connecting: false, adbWaiting: false, screen: 'working', wifiOnFailed: false, error: undefined, work: { step: 'wifi-on', x: 0 } });
    try {
      this.log(published ? 'Switching Wi-Fi on, with the image\'s own published key: svc wifi enable' : 'Switching Wi-Fi on, with this browser\'s key: svc wifi enable');
      const on = await this.d.frame.imageFlow.wifiOn(p, this.d.sleep);
      this.imageConnect = undefined; // one connection only
      this.log('', on ? 'Android says Wi-Fi is on' : 'Android didn\'t confirm Wi-Fi is on');
      this.d.progress?.set(undefined);
      this.set({ screen: 'done', changed: undefined, communityWifi: on ? 'on' : 'unconfirmed', work: { step: 'wifi-on', x: 1 } });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message, wifiOnFailed: true }); // installed already: Connect again, not a reinstall
    } finally {
      // WebUSB holds the device until the connection is released: a retry opens a fresh one
      await (p as PanelShell & { close?(): Promise<void> }).close?.().catch(() => {});
    }
  }

  /**
   * Where the error screen's Try again lands: the step that stopped, on its own screen. A first start (or its key
   * hand-back) connects over adb again (`adb`); a write or a build, on a device still in fastboot, connects fastboot
   * again (`fastboot`), unless its files went; anything else is the image's first screen (`image`), to download again.
   */
  tryAgainLands(): 'adb' | 'fastboot' | 'image' {
    const step = this.state.work?.step;
    if (step === 'first-boot' || step === 'hand-back' || step === 'app-install') return 'adb';
    // an in-place recipe: a stop in either fastboot session (its restart too) reconnects that session, where the step
    // that stopped runs again; its stage, bundle and finished writes stay
    if (this.inPlace && step) return 'fastboot';
    return step && step !== 'restart' && step !== 'wifi-on' && this.state.files.every((x) => x.state === 'ok') ? 'fastboot' : 'image';
  }

  /** The error screen's Try again (tryAgainLands). */
  tryAgain() {
    const at = this.tryAgainLands();
    if (at === 'adb') this.finishFirstStart();
    else if (at === 'fastboot') this.reconnectFastboot();
    else this.retry();
  }

  /** From an error: back to the image's first screen, keeping what checked out (a fetched image builds again). */
  /**
   * Whether an in-place install can start over honestly from here: nothing running, and nothing written to the system
   * yet (the stage changed only the device's app data). On the receive and second fastboot screens, the restore point,
   * and an error before the second session's first write. Not once a system write began, nor after the restart into
   * what was written: the way back from there is the restore point.
   */
  canStartOver(): boolean {
    if (!this.inPlace || this.busy() || this.state.connecting || this.started.size || this.wrote.size) return false;
    const s = this.state.screen;
    if (s === 'receive' || s === 'restore-point') return true;
    if (s === 'fastboot') return this.state.round === 2;
    if (s === 'error') return !!this.state.round && this.tryAgainLands() === 'fastboot' && this.state.work?.step !== 'restart';
    return false;
  }

  /** Continue, on the resume screen: the step the mark resumed on, as it was. */
  continueResume() { if (this.state.resumed) this.set({ resumed: undefined }); }

  /**
   * What this install has written to the device so far, for the rail's locks and the resume screen: nothing; an
   * in-place install's stage (the setup agent, in the app data: Start over can still begin again); or the system.
   */
  written(): 'none' | 'stage' | 'system' {
    const s = this.state;
    if (this.inPlace) {
      if (this.started.size || this.wrote.size || s.screen === 'adb' || s.work?.step === 'app-install') return 'system';
      // the first session's fastboot and warning come before the stage's write: nothing is on the device yet
      if (s.round === 1 && !s.resumed && (s.screen === 'fastboot' || s.screen === 'confirm')) return 'none';
      return s.round || s.resumed ? 'stage' : 'none';
    }
    if (s.screen === 'working' || s.screen === 'adb' || (s.screen === 'error' && s.work) || s.resumed === 'flashed') return 'system';
    return 'none';
  }

  /** Whether the session is busy (a write, a connection, a transfer): no way back is offered meanwhile. */
  idle(): boolean { return !this.busy(); }

  askStartOver(ask = true) { if (this.canStartOver()) this.set({ startingOver: ask }); else if (this.state.startingOver) this.set({ startingOver: false }); }

  /**
   * Start over, once asked: the run's agent secret, serial, bundle and restore point go, and so does the mark a reload
   * resumes from, and the install opens on its first screen. What the person gave stays (the Wi-Fi, the APK), and the
   * bundle this browser kept stays too: a new run that finds the same blocks takes it again.
   */
  startOver(): void {
    // only once asked (askStartOver): the screen has said what it resets
    if (!this.state.startingOver || !this.canStartOver()) return;
    this.receiving?.abort();
    this.receiving = undefined;
    this.watching?.abort();
    this.watching = undefined;
    void this.receiver?.cancel().catch(() => {});
    this.receiver = undefined;
    this.secret = undefined;
    this.serial = undefined;
    this.bundle = undefined;
    this.restore = undefined;
    this.wrote = new Set();
    this.started = new Set();
    this.made = Object.create(null);
    this.cursor = undefined;
    this.fb = undefined;
    this.d.progress?.set(undefined);
    this.log('Started over', 'the setup agent stays in the app data until the next run writes it again');
    this.set({ startingOver: false, round: undefined, receive: undefined, restorePoint: undefined, leftHotspot: undefined, changed: true, connectError: undefined });
    this.retry();
  }

  retry() {
    this.stoppedAt = undefined;
    this.set({ wifiOnFailed: false, screen: this.recipe.community ? 'download' : 'image', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false });
  }

  /**
   * After a write the bootloader refused: build again with zeros as RAW blocks instead of FILL
   * chunks (whether the device's LK takes FILL is the open question), then fastboot again.
   */
  retryWithoutFill() { this.set({ noFill: true, screen: 'fastboot', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false, restarted: true }); }

  /** Done, with another launcher still a home app: hide it again and check HOME (the device can't answer a chooser). */
  async fixHome(): Promise<void> {
    if (!this.panel || !this.state.firstBoot || this.state.fixing) return;
    this.set({ fixing: true });
    try {
      const { home } = await this.d.frame.imageFlow.makeOnlyHome(this.panel, this.log, this.d.sleep);
      this.set({ firstBoot: { ...this.state.firstBoot, home } });
    } catch (e) {
      this.log('', `fixing the home screen failed: ${(e as Error).message}`);
    } finally {
      this.set({ fixing: false });
    }
  }
}

/** A Blob's bytes: its own arrayBuffer, or through FileReader (jsdom's Blob, in the tests, has none). */
function blobBytes(b: Blob): Promise<Uint8Array> {
  if (typeof b.arrayBuffer === 'function') return b.arrayBuffer().then((x) => new Uint8Array(x));
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer));
    r.onerror = () => reject(r.error ?? new Error('Kiln couldn\'t read an image it built'));
    r.readAsArrayBuffer(b);
  });
}

/** A small Blob's text, through FileReader (in every browser, and in jsdom for the tests). */
function blobText(b: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error ?? new Error('Kiln couldn\'t read the image\'s key'));
    r.readAsText(b);
  });
}

/**
 * A recipe's published adb key (a community image's project key), read from storage and checked against its
 * pin as the very Blob it reads, as PKCS#8 bytes: the one key loader for every step that uses it
 * (wifi-on here; restarting a device on a community image into fastboot next). Held in memory by the caller,
 * never stored. Throws WrongImage when it isn't the pinned key.
 */
export async function readImageKey(images: Pick<ImageMaker, 'file' | 'check'>, want: ImageFile): Promise<Uint8Array> {
  const blob = await images.file(want.name);
  await images.check(blob, want, () => {});
  return pemToPkcs8(await blobText(blob));
}
