// What goes on a Mirror over fastboot (#40, option A; issue 579), outside React. The person picks an
// image: Glassworks, first and the default, or a community image such as Glass Panes. Each is a
// recipe of data (frames/lululemon-mirror/installer/src/choices.ts) that this session runs step by
// step, inside Kiln's safety steps (kiln-core plan()). Kiln downloads every file through Refract and
// checks it against the release's SHA-256 before anything goes near the Mirror. A download Kiln can't
// serve is Kiln's bug: the screen says so, with Try again and Report this, and never asks the person
// to download a file. Glassworks is built from the checked image in a worker (images.ts) and finishes
// over adb with this browser's key; a community image goes on as its release has it.
// Behind ?apk=release or ?apk=local, like the rest of the Mirror's page, until MIRROR_OPEN (session.ts).
// A Mirror that runs Glassworks already can go through `keep` first (issue 580): Kiln reads Home Assistant's ESPHome key
// from the running app over adb and gives it back at the first start, as tools/mirror's flash-node does (PR 575).
import { AdbAuthTimeout, type PanelShell } from '@glassworks/adb';
import { apiKeyFingerprint, apiKeySocketUp, kioskVersion, readApiKey } from '@glassworks/app-adb';
import { imageFromLink, plan, writtenAsIs, type ImageFile, type ImageRecipe, type PlannedStep } from '@glassworks/kiln-core';
import {
  GLASSWORKS, GLASS_PANES_USERDATA, MIRROR_IMAGES, ProxyError, USERDATA_BYTES, WrongImage, checkFastboot, firstBoot, makeOnlyHome,
  type FastbootLike, type FastbootReport, type FirstBoot, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where the images get made and kept: a worker on OPFS in the browser, a fake in demos and tests. */
export interface ImageMaker {
  /** A file Glassworks patches, from Refract into the worker, checked as it arrives. Throws ProxyError or WrongImage. */
  fetch(url: string, onProgress: (x: number) => void): Promise<void>;
  /** A file written as published, from Refract into the worker's storage, checked as it arrives; a file that isn't the release's is deleted. */
  download(url: string, want: ImageFile, onProgress: (x: number) => void): Promise<void>;
  /** A file download() kept, as fastboot sends it: a snapshot that fails to read if the stored file changes. */
  file(name: string): Promise<Blob>;
  /** That Blob against its pinned size and SHA-256, right before it is flashed. Throws WrongImage. */
  check(blob: Blob, want: ImageFile, onProgress: (x: number) => void): Promise<void>;
  /** Builds both images from the fetched one; can run again (after a failed flash) until discard(). */
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; settingsOverlay?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
    deviceMac?: string; wifi?: { ssid: string; password: string };
    /** ro.glassworks.api_key.handover=1: the app waits for the key Kiln holds (issue 574). The flag only: the key never goes to the worker */
    apiKeyHandover?: boolean },
    onProgress: (step: PrepareStep, x: number) => void): Promise<{ system: Blob; userdata: Blob }>;
  /** Deletes what it kept (the downloads, the built copies). */
  discard(): Promise<void>;
}

export interface FlashDeps {
  images: ImageMaker;
  /** Refract, Kiln's download route (IMAGE_PROXY.base). None: every download fails, as a bug to report. */
  refract?: string;
  /** What this page can install, Glassworks first (MIRROR_IMAGES). */
  recipes?: readonly ImageRecipe[];
  /** The page's address query: `?image=<id>` opens that community image's install. */
  search?: string;
  /** The browser's device list for the Mirror's fastboot, inside the click. */
  requestFastboot(): Promise<FastbootLike>;
  /**
   * The browser's device list for the Mirror's adb, inside the click: after the first boot, and before the flash to
   * read Home Assistant's key (issue 580). With `authMs` (the read before the flash), throws AdbAuthTimeout when the Mirror
   * hasn't let this browser's key in by then; after the flash it waits as long as the Mirror takes.
   */
  requestAdb(o?: { authMs?: number }): Promise<PanelShell>;
  /**
   * adb again on the device requestAdb picked, with no list: while the Mirror is still starting it refuses this
   * browser's key (adbd answers before it reads the adb_keys in the new userdata), so Kiln asks again.
   */
  reopenAdb?(): Promise<PanelShell>;
  /** Whether the last requestAdb picked a device: one that failed before the list (no key yet, no WebUSB) isn't retried. */
  adbPicked?(): boolean;
  /** How many times Kiln asks again, 5 s apart (default 60: five minutes of the first start). */
  adbTries?: number;
  /**
   * Whether this browser has flashed a Mirror before, kept across visits (no secret: a flag). Only that browser's key
   * gets in over adb, so Continue opens the keep step for it (issue 580). None: never.
   */
  flashedHere?: { get(): boolean; set(): void };
  /**
   * Told when the tab starts and stops holding Home Assistant's key (issue 580), so the page can ask before it closes.
   * The session outlives its screen (Kiln's registry), so this follows the session, not the screen.
   */
  holdingKey?(on: boolean): void;

  /** This browser's adb public key line, once loaded. */
  adbKey(): string | undefined;
  /** The app's APK (Glassworks' legacy build, from its release or a local file), checked by the page. */
  apk(): Uint8Array | undefined;
  /** Why there is no APK yet when it comes from the release (MirrorSession.releaseBlocked). */
  apkBlocked?(): string | undefined;
  /** The boot animation, when Kiln has one staged. */
  bootAnimation?(): Promise<Uint8Array | undefined>;
  /** GlassworksSettings.apk (frames/lululemon-mirror/overlay), when Kiln has it staged: a first boot with nothing to tap. */
  settingsOverlay?(): Promise<Uint8Array | undefined>;
  sleep?: (ms: number) => Promise<void>;
}

/** The browser's list was closed with nothing picked: nothing to ask again. */
const nothingPicked = (e: unknown) => (e as { name?: string } | undefined)?.name === 'NotFoundError' || (e as Error | undefined)?.message === 'no panel selected';

/** `image`: Glassworks' download and settings; `download`: a community image's. */
export type FlashScreen = 'choose' | 'image' | 'download' | 'keep' | 'fastboot' | 'confirm' | 'working' | 'adb' | 'done' | 'error';
export type WorkStep = PrepareStep | 'flash-system' | 'flash-userdata' | 'restart' | 'first-boot' | 'hand-back';
/**
 * Home Assistant's ESPHome key across a reflash (issue 580): read from the running app before the flash, given back at the
 * first start. The key itself never goes in the state or the log, only its 4-hex fingerprint; the session holds it in
 * memory until the hand-back.
 */
export type KeyStep =
  /** 0: Glassworks answers over USB, 1: the key is read, 2: the Mirror restarts into fastboot */
  | { state: 'reading'; at: 0 | 1 | 2 }
  /** read, and the Mirror is restarting into fastboot */
  | { state: 'held'; fingerprint: string }
  /** the app answered with no key (Home Assistant hasn't added this Mirror), or no Glassworks: nothing to keep */
  | { state: 'none' }
  /**
   * no adb (another browser installed it, or it doesn't start), or an app from before the hand-over. `restarted`: Kiln
   * restarted an old app's Mirror into fastboot itself
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

export interface FlashState {
  screen: FlashScreen;
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
  haUrl: string;
  wifi: { ssid: string; password: string };
  fastboot?: FastbootReport;
  connecting?: boolean;
  connectError?: string;
  /** The Mirror refused this browser's key so far: it is still starting, and Kiln asks again. */
  adbWaiting?: boolean;
  understood: boolean;
  work?: { step: WorkStep; x: number };
  firstBoot?: FirstBoot;
  key?: KeyStep;
  /** build zeros as RAW blocks: the retry for a bootloader that refused a FILL chunk */
  noFill?: boolean;
  /** the HOME fix on Done is running */
  fixing?: boolean;
  /** the error came while writing: offer the retry without FILL */
  flashFailed?: boolean;
  error?: string;
  log: string[];
}

/**
 * How long the keep step waits for the Mirror to let this browser's adb key in. A Mirror that trusts another browser's
 * key waits for a tap on a screen it can't take, so it never would.
 */
export const AUTH_MS = 20_000;

/** The person went on to fastboot by hand while the keep step's read ran. */
class Skipped extends Error {}

const waiting = (r: ImageRecipe): FileCheck[] => r.files.map((f) => ({ name: f.name, size: f.size, state: 'waiting', progress: 0 }));

export class FlashSession {
  readonly recipes: readonly ImageRecipe[];
  state: FlashState;
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
  private panel?: PanelShell;
  private made: Record<string, Blob> = {};
  private runs = 0;
  /** Bumped when the picked image or the way to it changes: a fastboot check started before then is ignored. */
  private selected = 0;
  /** Home Assistant's ESPHome key from before the flash (issue 580). A secret: never in the state, the log or an error */
  private apiKey?: string;
  /** an old app's Mirror, connected, for its restart into fastboot */
  private running?: PanelShell;
  /** The keep step's read in progress: skipKeep bumps `gen`, and the read then lets go of `p` and changes nothing */
  private read: { gen: number; p?: PanelShell; got?: { key: string; fingerprint: string } } = { gen: 0 };

  constructor(private readonly d: FlashDeps) {
    this.recipes = d.recipes ?? MIRROR_IMAGES;
    const linked = imageFromLink(this.recipes, d.search ?? '');
    const first = linked ?? this.recipes[0]!;
    this.state = { screen: linked ? 'download' : 'choose', picked: first.id, open: !!linked, link: !!linked, files: waiting(first),
      haUrl: '', wifi: { ssid: '', password: '' }, understood: false, log: [] };
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private held = false;
  private set(patch: Partial<FlashState>) {
    this.state = { ...this.state, ...patch };
    const held = this.holdsKey();
    if (held !== this.held) { this.held = held; this.d.holdingKey?.(held); }
    for (const l of this.listeners) l();
  }
  private log = (step: string, detail?: string) => this.set({ log: [...this.state.log, step || `  ${detail}`].slice(-400) });

  /** The picked image's recipe. */
  get recipe(): ImageRecipe { return this.recipes.find((r) => r.id === this.state.picked) ?? this.recipes[0]!; }
  recipeOf(id: string): ImageRecipe | undefined { return this.recipes.find((r) => r.id === id); }

  setHaUrl(haUrl: string) { this.set({ haUrl }); }
  setWifi(wifi: Partial<FlashState['wifi']>) { this.set({ wifi: { ...this.state.wifi, ...wifi } }); }
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
    this.made = {};
    this.selected++;
    // a fresh install: an earlier one's key outcome (handed, refused, none…) goes; a key still held for a hand-back stays
    const key = this.state.key?.state === 'held' ? this.state.key : undefined;
    this.set({ screen: 'choose', picked: r.id, from, open: r.community, link: false, files: waiting(r), fastboot: undefined, understood: false,
      work: undefined, firstBoot: undefined, key, error: undefined, flashFailed: false, noFill: false });
  }

  /**
   * Drops what an earlier pick downloaded: the worker keeps one image's files at a time. The worker
   * stops a download in flight and runs the discard before anything sent after it.
   */
  private async forget() {
    this.runs++;
    this.made = {};
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
    if (this.recipe.community) return undefined;
    if (!this.d.apk()) return this.d.apkBlocked?.() ?? 'Pick the Glassworks APK file first.';
    return this.inputsBlocked();
  }

  /** Why Home Assistant's address or the Wi-Fi can't be used, or undefined. */
  inputsBlocked(): string | undefined {
    const s = this.state;
    if (s.haUrl && !/^https?:\/\/[^\s/]+/.test(s.haUrl)) return 'Home Assistant\'s address starts with http:// or https://.';
    if (!s.wifi.ssid !== !s.wifi.password && !s.wifi.ssid) return 'Give the Wi-Fi network\'s name too.';
    // the password is saved as WPA's PSK in the new userdata: refused here, before the build (wpaSupplicantConf)
    if (s.wifi.ssid && new TextEncoder().encode(s.wifi.ssid).length > 32) return 'A Wi-Fi network\'s name is at most 32 bytes.';
    if (s.wifi.ssid && s.wifi.password && !/^[\x20-\x7e]{8,63}$/.test(s.wifi.password) && !/^[0-9a-fA-F]{64}$/.test(s.wifi.password)) {
      return 'The Wi-Fi password must be 8 to 63 characters (letters, digits, symbols), or empty for an open network.';
    }
    return undefined;
  }

  /** Continue: fastboot, or the keep step first in a browser that has flashed a Mirror before (it alone gets in over adb). */
  toFastboot() {
    if (this.blocked()) return;
    if (!this.recipe.community && !this.state.key && this.d.flashedHere?.get()) this.toKeep();
    else this.set({ screen: 'fastboot', connectError: undefined });
  }

  /** The step before the flash, for a Mirror that runs Glassworks (issue 580). A key read earlier stays until a new one is read. */
  toKeep() { if (!this.blocked()) this.set({ screen: 'keep', key: this.apiKey ? this.state.key : undefined, connectError: undefined }); }

  /** Going on with fastboot by hand: a key read already stays, to give back after the flash; Done says what HA does. */
  skipKeep() {
    this.set({ screen: 'fastboot', key: this.endKeepRead(), connecting: false, connectError: undefined });
  }

  /**
   * Ends a keep-step read still running: its connection goes, and whatever it does next is dropped, so it can't restart
   * the Mirror or store a key afterwards (PR 603 review). Gives the key state to go on with: a key it had read already
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
   * Inside the click, on the keep step: the Mirror over adb with this browser's key, read-only, then Home Assistant's
   * key from the app (readApiKey, as flash-node does), then the restart into fastboot. Nothing is written. A key the app
   * has but can't hand over now stops here, before the restart: a flash would lose it.
   */
  async readKey(): Promise<void> {
    const req = this.d.requestAdb({ authMs: AUTH_MS });
    const gen = this.read.gen + 1;
    this.read = { gen };
    this.set({ connecting: true, connectError: undefined, key: { state: 'reading', at: 0 } });
    let p: PanelShell;
    try {
      p = await req;
    } catch (e) {
      if (gen !== this.read.gen) return; // skipped meanwhile
      if (e instanceof AdbAuthTimeout) {
        this.log('', 'adb: the Mirror didn\'t let this browser\'s key in');
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
    /** after each wait on the Mirror: stop if the person went on to fastboot by hand meanwhile */
    const live = () => { if (gen !== this.read.gen) throw new Skipped(); };
    try {
      this.log('Reading Home Assistant\'s key from Glassworks');
      const board = (await p.run('getprop ro.board.platform')).out.trim();
      live();
      if (board !== 'msm8916') throw new Error(`This isn't the Mirror (board ${board || 'unknown'}, needs msm8916). Nothing was changed.`);
      const app = await kioskVersion(p, undefined, GLASSWORKS);
      live();
      this.log('', app ? `${GLASSWORKS.name} ${app.name ?? app.code}` : `no ${GLASSWORKS.name} on the Mirror`);
      // the app opens its socket as it starts: a Mirror still starting gets a little time, an app older than issue 574 never opens it
      let up = !!app && !!p.openStream && await apiKeySocketUp(p);
      for (let i = 0; app && p.openStream && !up && i < 10; i++) { await sleep(2000); live(); up = await apiKeySocketUp(p); }
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
      const key = up ? await readApiKey(p, undefined, { socket: 'required' }) : undefined;
      const fingerprint = key ? await apiKeyFingerprint(key) : undefined;
      live(); // before it saves anything: a read skipped meanwhile must not write into the next one's record
      if (key && fingerprint) this.read.got = { key, fingerprint };
      this.log('', fingerprint ? `Home Assistant API key read, fingerprint ${fingerprint}, held in this tab only` : 'no Home Assistant API key to keep');
      this.set({ key: { state: 'reading', at: 2 } });
      await this.restart(p);
      live();
      this.read = { gen };
      this.apiKey = key;
      this.set({ key: fingerprint ? { state: 'held', fingerprint } : { state: 'none' } });
    } catch (e) {
      // skipped, or overtaken by a newer read: let go of this connection only, and leave the state to the newer one
      if (e instanceof Skipped || gen !== this.read.gen) { await p.close?.().catch(() => {}); return; }
      this.read = { gen };
      // the reply's first word at most (readApiKey): no key material in the message
      this.log('Stopped', (e as Error).message);
      // let go of the connection, so Try again starts a clean one on the same device
      await p.close?.().catch(() => {});
      this.set({ key: undefined, connectError: `Kiln couldn't read the key: ${(e as Error).message}. Nothing was written to the Mirror. Try again.` });
    }
  }

  /** An old app's Mirror (no key to read): Kiln restarts it into fastboot, then the person connects fastboot. */
  async restartOld(): Promise<void> {
    const k = this.state.key;
    const p = this.running;
    if (k?.state !== 'unreachable' || !p) { this.skipKeep(); return; }
    this.set({ connecting: true, connectError: undefined });
    try {
      await this.restart(p);
      this.running = undefined;
      this.set({ connecting: false, key: { ...k, restarted: true } });
    } catch (e) {
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  private async restart(p: PanelShell) {
    if (!p.rebootToBootloader) throw new Error('this connection can\'t restart the Mirror: put it in fastboot by hand');
    this.log('Restarting the Mirror into fastboot');
    await p.rebootToBootloader();
  }

  /** Inside the click: the browser's device list opens first, then Kiln's checks read the Mirror's fastboot (read-only). */
  async connectFastboot(): Promise<void> {
    const req = this.d.requestFastboot();
    const sel = this.selected;
    this.set({ connecting: true, connectError: undefined });
    try {
      const fb = await req;
      const fastboot = await checkFastboot(fb);
      // another image was picked (or a link opened) while this ran: its checks belong to that install, not this one
      if (sel !== this.selected) return;
      this.fb = fb;
      const r = this.recipe;
      // a community image's userdata goes on as published, at its own size: the partition must hold it (Glassworks builds one to fit)
      if (r.community && writtenAsIs(r).some((f) => f.name === GLASS_PANES_USERDATA.name) && fastboot.userdataBytes < USERDATA_BYTES) {
        fastboot.checks.push({ what: `Userdata partition for ${r.name}`, found: `${fastboot.userdataBytes} bytes`, ok: false, need: `at least ${USERDATA_BYTES} bytes, the size its userdata image expands to` });
        fastboot.ok = false;
      }
      for (const c of fastboot.checks) this.log('', `${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}`);
      fastboot.info.forEach((i) => this.log('', i));
      this.set({ connecting: false, fastboot, screen: fastboot.ok ? 'confirm' : 'fastboot' });
    } catch (e) {
      if (sel !== this.selected) return;
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  /** The planned steps from the first write on: what flash() runs, and what the install screen lists. */
  writes(): PlannedStep[] {
    const all = plan(this.recipe);
    return all.slice(all.findIndex((s) => s.do === 'confirm') + 1);
  }

  /**
   * Runs the picked recipe from the warning on: Kiln's backup step, then its writes in order. Only
   * after Kiln's checks passed and the person confirmed everything on the Mirror is replaced.
   */
  async flash(): Promise<void> {
    const s = this.state;
    const fb = this.fb;
    if (!fb || !s.fastboot?.ok || !s.understood || s.screen === 'working') return;
    const r = this.recipe;
    const steps = this.writes();
    const work = (step: WorkStep, x: number) => this.set({ work: { step, x } });
    const now = () => performance.now();
    const secs = (ms: number) => `${(ms / 1000).toFixed(1)} s`;
    const first = steps.find((x) => x.do !== 'backup');
    this.set({ screen: 'working', error: undefined, flashFailed: false, work: { step: first?.do === 'patch' ? 'patch' : first?.do === 'flash' ? `flash-${first.partition}` as WorkStep : 'restart', x: 0 } });
    let building = false, reading = false;
    try {
      // the Mirror's LK can't read anything back: Kiln's backup step is the record of what it writes
      this.log('Backup: none possible, the Mirror\'s bootloader can\'t read anything back. What Kiln writes:');
      for (const f of writtenAsIs(r)) this.log('', `${f.name}: SHA-256 ${f.sha256}, as ${r.name}'s release publishes it`);
      // every published file this install writes, opened and checked now, before the first write; those very Blobs go to
      // fastboot, so a file another tab changed can't leave one partition written and the other not
      const verified: Record<string, Blob> = {};
      reading = true;
      for (const x of steps) {
        const want = x.do === 'flash' ? r.files.find((f) => f.name === x.file) : undefined;
        if (!want || verified[want.name]) continue;
        const blob = await this.d.images.file(want.name);
        this.log('', `${want.name}: checking it again before writing`);
        await this.d.images.check(blob, want, () => {});
        verified[want.name] = blob;
      }
      reading = false;
      for (const step of steps) {
        if (step.do === 'patch') {
          const adbKey = this.d.adbKey();
          if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
          this.log('Building the images');
          building = true;
          // each build step's time, in the log: where the minutes go on a real Mirror
          let at = { step: '', t: now() };
          const built = (step: string) => { if (at.step && at.step !== step) this.log('', `${at.step}: built in ${secs(now() - at.t)}`); if (at.step !== step) at = { step, t: now() }; };
          // the Wi-Fi goes into the new userdata (saved for Android, its password only as WPA's PSK), so the
          // Mirror joins on its first boot with nothing tapped; sending it to the app after the boot stays a fallback
          const wifi = s.wifi.ssid ? { ssid: s.wifi.ssid, password: s.wifi.password } : undefined;
          this.made = await this.d.images.build({
            apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), settingsOverlay: await this.d.settingsOverlay?.(),
            adbKey, userdataBytes: s.fastboot.userdataBytes, fill: !s.noFill, ...(s.fastboot.deviceMac ? { deviceMac: s.fastboot.deviceMac } : {}), ...(wifi ? { wifi } : {}),
            // the flag only: the key stays in this tab until the first start
            ...(this.apiKey ? { apiKeyHandover: true } : {}),
          }, (step, x) => { built(step); work(step, x); });
          built('');
          building = false;
          this.log('', `Kiln built: ${step.makes.join(', ')}`);
        } else if (step.do === 'flash') {
          const id = `flash-${step.partition}` as WorkStep;
          const blob = this.made[step.file] ?? verified[step.file];
          if (!blob) throw new Error(`Kiln has no checked ${step.file} to write. Nothing more was written.`);
          this.log(`Flashing ${step.partition}${r.community ? ` with ${step.file} as published` : s.noFill ? ' (zeros as RAW, no FILL chunks)' : ''}`);
          this.set({ flashFailed: true }); // until the restart: a failure from here is the bootloader's
          work(id, 0);
          // sending is the bar; after it the bootloader writes (and may erase) with nothing to report
          const t0 = now();
          let sent: number | undefined;
          await fb.flashBlob(step.partition, blob, (x) => { if (x >= 1) sent ??= now(); work(id, x); });
          const end = now();
          this.log('', `${step.partition}: sent ${(blob.size / 2 ** 20).toFixed(1)} MiB in ${secs((sent ?? end) - t0)}, then the Mirror wrote it in ${secs(end - (sent ?? end))}`);
        } else if (step.do === 'restart') {
          this.set({ flashFailed: false });
          work('restart', 1);
          this.log('Restarting the Mirror');
          await fb.reboot();
          this.fb = undefined;
          this.made = {};
          if (!r.community) this.d.flashedHere?.set(); // this browser's key is in the Glassworks it wrote: the keep step can read it next time
          // nothing kept any more: another install downloads again. The Mirror is restarting into what was written, so a
          // storage error here is only logged: it mustn't turn a finished install into an error
          this.set({ files: waiting(r) });
          await this.d.images.discard().catch((e: Error) => this.log('', `deleting the downloads failed: ${e.message}`));
        }
      }
      this.set({ screen: steps.some((x) => x.do === 'first-start') ? 'adb' : 'done' });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      // a failed build dropped the fetched image (it may be patched halfway), and a stored file that is gone or
      // no longer checks out can't be written: either way, download again. A refused write keeps the downloads.
      this.set({ screen: 'error', error: (e as Error).message, ...(building || reading ? { files: waiting(r) } : {}) });
    }
  }

  /**
   * A Mirror already flashed with Glassworks whose first start didn't finish (an error, or the page was reloaded):
   * "One last click." again, with Home Assistant's address and the Wi-Fi as given on the image screen.
   */
  finishFirstStart() {
    if (this.recipe.community || this.inputsBlocked() || this.state.screen === 'working') return;
    // the connection of a first start that stopped: let go, so Connect opens one fresh (not awaited: Connect's list stays in its click)
    const stale = this.panel;
    this.panel = undefined;
    void stale?.close?.().catch(() => {});
    this.set({ screen: 'adb', error: undefined, connectError: undefined, adbWaiting: false, work: undefined, flashFailed: false });
  }

  /**
   * After the restart, inside the click: adb with this browser's key, then Kiln's first start (Glassworks). A Mirror
   * still starting refuses the key: Kiln asks again every 5 s through the first start, and says so if it never lets in.
   */
  async connectAdb(): Promise<void> {
    if (this.state.connecting) return;
    const req = this.d.requestAdb();
    this.set({ connecting: true, connectError: undefined, adbWaiting: false });
    const sleep = this.d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    let p: PanelShell | undefined;
    let last: unknown;
    try { p = await req; } catch (e) { last = e; }
    const tries = this.d.reopenAdb && !nothingPicked(last) && (this.d.adbPicked?.() ?? true) ? this.d.adbTries ?? 60 : 0;
    for (let i = 0; !p && i < tries; i++) {
      this.log('', `The Mirror didn't let this browser in yet (${(last as Error).message}): it is still starting. Asking again in 5 s.`);
      this.set({ adbWaiting: true });
      await sleep(5000);
      try { p = await this.d.reopenAdb!(); } catch (e) { last = e; }
    }
    if (!p) {
      const why = (last as Error).message;
      this.set({ connecting: false, adbWaiting: false, connectError: nothingPicked(last) ? 'Nothing was picked. Click Connect and pick the Mirror (Android) in the browser\'s list.'
        : tries ? `The Mirror didn't let this browser in through its first start (${why}). It knows only the key of the browser that installed it: use that browser, then Connect again.` : why });
      return;
    }
    this.panel = p;
    this.set({ connecting: false, adbWaiting: false, screen: 'working', work: { step: 'first-boot', x: 0 } });
    try {
      for (let i = 0; (await p.run('getprop sys.boot_completed')).out.trim() !== '1'; i++) {
        if (i > 180) throw new Error('the Mirror didn\'t finish starting in 6 minutes');
        await sleep(2000);
      }
      const { ssid, password } = this.state.wifi;
      const k = this.state.key;
      // firstBoot hands the key back right after it starts the app (PR 575): the row turns to it there
      const onStep = (step: string, detail?: string) => {
        if (step.startsWith('Home Assistant API key')) this.set({ work: { step: 'hand-back', x: 0 } });
        this.log(step, detail);
      };
      const fb = await firstBoot(p, { haUrl: this.state.haUrl || undefined, wifi: ssid ? { ssid, password } : undefined, onStep, sleep,
        ...(this.apiKey ? { apiKey: this.apiKey } : {}) });
      const key: KeyStep | undefined = k?.state === 'held' && fb.apiKey !== undefined
        ? { state: fb.apiKey ? 'handed' : 'refused', fingerprint: k.fingerprint } : k;
      if (fb.apiKey !== undefined) this.apiKey = undefined; // given back or refused: the tab lets go of it
      this.set({ screen: 'done', firstBoot: fb, key, work: { step: this.state.work?.step ?? 'first-boot', x: 1 } });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message });
    }
  }

  /** From an error: back to the image's first screen, keeping what checked out (a fetched image builds again). */
  retry() {
    this.set({ screen: this.recipe.community ? 'download' : 'image', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false });
  }

  /**
   * After a write the bootloader refused: build again with zeros as RAW blocks instead of FILL
   * chunks (whether the Mirror's LK takes FILL is the open question), then fastboot again.
   */
  retryWithoutFill() { this.set({ noFill: true, screen: 'fastboot', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false }); }

  /** Done, with another launcher still a home app: hide it again and check HOME (the Mirror can't answer a chooser). */
  async fixHome(): Promise<void> {
    if (!this.panel || !this.state.firstBoot || this.state.fixing) return;
    this.set({ fixing: true });
    try {
      const { home } = await makeOnlyHome(this.panel, this.log, this.d.sleep);
      this.set({ firstBoot: { ...this.state.firstBoot, home } });
    } catch (e) {
      this.log('', `fixing the home screen failed: ${(e as Error).message}`);
    } finally {
      this.set({ fixing: false });
    }
  }
}
