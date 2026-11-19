// Installing Glassworks on a Mirror over fastboot (#40, option A), outside React. The person
// downloads the Glass Panes system.img and selects it. Kiln checks it, builds the two images in a
// worker (images.ts), and flashes them with the Mirror in fastboot. Then it finishes over adb with
// this browser's own key, which the patched image lets in without asking. Test only, behind ?apk=local, like the rest of the Mirror's page.
// A Mirror that runs Glassworks already can go through `keep` first (issue 580): Kiln reads Home Assistant's ESPHome key
// from the running app over adb and gives it back at the first start, as tools/mirror's flash-node does (PR 575).
import { AdbAuthTimeout, type PanelShell } from '@glassworks/adb';
import { apiKeyFingerprint, apiKeySocketUp, kioskVersion, readApiKey } from '@glassworks/app-adb';
import {
  GLASS_PANES, GLASS_PANES_USERDATA, GLASSWORKS, ProxyError, WrongImage, checkFastboot, firstBoot, flashImages, makeOnlyHome,
  type FastbootLike, type FastbootReport, type FirstBoot, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where the two images get made: a worker on OPFS in the browser, a fake in demos and tests. */
export interface ImageMaker {
  /** Reads and checks the person's system.img (size, then SHA-256 as it reads). Throws WrongImage. */
  load(file: Blob, onProgress: (x: number) => void): Promise<void>;
  /** The same from Kiln's release proxy, resuming with Range. Throws ProxyError when the proxy can't serve it. */
  fetch(url: string, onProgress: (x: number) => void): Promise<void>;
  /** Builds both images from the loaded one; can run again (after a failed flash) until discard(). */
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; settingsOverlay?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
    deviceMac?: string; wifi?: { ssid: string; password: string };
    /** ro.glassworks.api_key.handover=1: the app waits for the key Kiln holds (issue 574). The flag only: the key never goes to the worker */
    apiKeyHandover?: boolean },
    onProgress: (step: PrepareStep, x: number) => void): Promise<{ system: Blob; userdata: Blob }>;
  /** The way back: a downloaded Glass Panes file against its published size and SHA-256, off the page's thread. */
  check(file: Blob, which: 'system' | 'userdata', onProgress: (x: number) => void): Promise<void>;
  /** Deletes what it kept (the image, the built copies). */
  discard(): Promise<void>;
}

export interface FlashDeps {
  images: ImageMaker;
  /** system.img on Kiln's release proxy (IMAGE_PROXY); none: the person downloads and selects it. */
  imageUrl?: string;
  /** The browser's device list for the Mirror's fastboot, inside the click. */
  requestFastboot(): Promise<FastbootLike>;
  /**
   * The browser's device list for the Mirror's adb, inside the click: after the first boot, and before the flash to
   * read Home Assistant's key (issue 580). With `authMs` (the read before the flash), throws AdbAuthTimeout when the Mirror
   * hasn't let this browser's key in by then; after the flash it waits as long as the Mirror takes.
   */
  requestAdb(o?: { authMs?: number }): Promise<PanelShell>;
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
  /** The app's APK (Glassworks' legacy build, the test-only local file), checked by the page. */
  apk(): Uint8Array | undefined;
  /** The boot animation, when Kiln has one staged. */
  bootAnimation?(): Promise<Uint8Array | undefined>;
  /** GlassworksSettings.apk (frames/lululemon-mirror/overlay), when Kiln has it staged: a first boot with nothing to tap. */
  settingsOverlay?(): Promise<Uint8Array | undefined>;
  sleep?: (ms: number) => Promise<void>;
}

export type FlashScreen = 'image' | 'keep' | 'fastboot' | 'confirm' | 'working' | 'adb' | 'done' | 'back' | 'error';
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

export interface FileCheck { name: string; size: number; state: 'downloading' | 'checking' | 'ok' | 'bad'; progress: number; error?: string }

export interface FlashState {
  screen: FlashScreen;
  /** the way back: put the plain Glass Panes images back */
  back: boolean;
  image?: FileCheck;
  /** Why the one-button download didn't work: the screen then offers download-and-select. */
  proxyFailed?: string;
  userdataFile?: FileCheck;
  haUrl: string;
  wifi: { ssid: string; password: string };
  fastboot?: FastbootReport;
  connecting?: boolean;
  connectError?: string;
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

export class FlashSession {
  state: FlashState = { screen: 'image', back: false, haUrl: '', wifi: { ssid: '', password: '' }, understood: false, log: [] };
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
  private panel?: PanelShell;
  private files: { system?: Blob; userdata?: Blob } = {};
  /** Home Assistant's ESPHome key from before the flash (issue 580). A secret: never in the state, the log or an error */
  private apiKey?: string;
  /** an old app's Mirror, connected, for its restart into fastboot */
  private running?: PanelShell;
  /** The keep step's read in progress: skipKeep bumps `gen`, and the read then lets go of `p` and changes nothing */
  private read: { gen: number; p?: PanelShell; got?: { key: string; fingerprint: string } } = { gen: 0 };
  private picks = 0;

  constructor(private readonly d: FlashDeps) {}

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

  setHaUrl(haUrl: string) { this.set({ haUrl }); }
  setWifi(wifi: Partial<FlashState['wifi']>) { this.set({ wifi: { ...this.state.wifi, ...wifi } }); }
  understand(understood: boolean) { this.set({ understood }); }

  /** The way back (true) or the install (false); both start at the files. */
  wayBack(back: boolean) {
    this.files = {};
    this.set({ back, screen: back ? 'back' : 'image', image: undefined, userdataFile: undefined, fastboot: undefined, understood: false, error: undefined });
  }

  /**
   * One button: system.img from Kiln's release proxy straight into the worker, checked as it
   * arrives. When the proxy can't serve it, the screen falls back to download-and-select.
   */
  async downloadImage(): Promise<void> {
    const want = GLASS_PANES.system;
    if (!this.d.imageUrl) { this.set({ proxyFailed: 'Kiln has no download proxy here' }); return; }
    const pick = ++this.picks;
    this.files.system = undefined;
    const at = (state: FileCheck['state'], progress: number, error?: string) =>
      this.set({ image: { name: want.name, size: want.size, state, progress, ...(error ? { error } : {}) } });
    this.set({ proxyFailed: undefined });
    at('downloading', 0);
    try {
      await this.d.images.fetch(this.d.imageUrl, (x) => { if (pick === this.picks) at('downloading', x); });
      if (pick !== this.picks) return;
      at('ok', 1);
      this.log('', `${want.name} downloaded through the proxy and checked`);
    } catch (e) {
      if (pick !== this.picks) return;
      const why = e instanceof ProxyError || e instanceof WrongImage ? (e as Error).message : `the download stopped (${(e as Error).message})`;
      this.log('', `proxy download failed: ${why}`);
      this.set({ image: undefined, proxyFailed: why });
    }
  }

  /** The person's system.img: checked (and, for the install, read into the worker) right away. Only the latest pick counts. */
  async selectImage(file: Blob & { name?: string }): Promise<void> {
    const pick = ++this.picks;
    const name = file.name ?? GLASS_PANES.system.name;
    this.files.system = undefined;
    this.set({ image: { name, size: file.size, state: 'checking', progress: 0 } });
    const progress = (x: number) => { if (pick === this.picks) this.set({ image: { name, size: file.size, state: 'checking', progress: x } }); };
    try {
      if (this.state.back) await this.d.images.check(file, 'system', progress);
      else await this.d.images.load(file, progress);
      if (pick !== this.picks) return;
      this.files.system = file;
      this.set({ image: { name, size: file.size, state: 'ok', progress: 1 } });
    } catch (e) {
      if (pick !== this.picks) return;
      this.set({ image: { name, size: file.size, state: 'bad', progress: 0, error: e instanceof WrongImage ? e.message : `Kiln couldn't read this file: ${(e as Error).message}` } });
    }
  }

  /** The way back's second file: the Glass Panes userdata.sparse, as downloaded. */
  async selectUserdata(file: Blob & { name?: string }): Promise<void> {
    const name = file.name ?? GLASS_PANES_USERDATA.name;
    this.files.userdata = undefined;
    this.set({ userdataFile: { name, size: file.size, state: 'checking', progress: 0 } });
    try {
      await this.d.images.check(file, 'userdata', (x) => this.set({ userdataFile: { name, size: file.size, state: 'checking', progress: x } }));
      this.files.userdata = file;
      this.set({ userdataFile: { name, size: file.size, state: 'ok', progress: 1 } });
    } catch (e) {
      this.set({ userdataFile: { name, size: file.size, state: 'bad', progress: 0, error: (e as Error).message } });
    }
  }

  /** Why Continue can't go on to fastboot yet, or undefined when it can. */
  blocked(): string | undefined {
    const s = this.state;
    if (s.image?.state !== 'ok') return 'Select the downloaded system.img first.';
    if (s.back) return s.userdataFile?.state === 'ok' ? undefined : 'Select the downloaded userdata.sparse too.';
    if (!this.d.apk()) return 'Pick the Glassworks APK file first.';
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
    if (!this.state.back && !this.state.key && this.d.flashedHere?.get()) this.toKeep();
    else this.set({ screen: 'fastboot', connectError: undefined });
  }

  /** The step before the flash, for a Mirror that runs Glassworks (issue 580). A key read earlier stays until a new one is read. */
  toKeep() { if (!this.blocked()) this.set({ screen: 'keep', key: this.apiKey ? this.state.key : undefined, connectError: undefined }); }

  /** Going on with fastboot by hand: a key read already stays, to give back after the flash; Done says what HA does. */
  skipKeep() {
    let k = this.state.key;
    // a read still running stops: its connection goes, and whatever it does next is dropped (PR 603 review)
    const { got, p } = this.read;
    this.read = { gen: this.read.gen + 1 };
    void p?.close?.().catch(() => {});
    void this.running?.close?.().catch(() => {});
    this.running = undefined;
    if (k?.state === 'reading') {
      // a key it read already stays, to give back after the flash
      if (got) { this.apiKey = got.key; k = { state: 'held', fingerprint: got.fingerprint }; } else k = undefined;
    }
    this.set({ screen: 'fastboot', key: k, connecting: false, connectError: undefined });
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
      if (key && fingerprint) this.read.got = { key, fingerprint };
      live();
      this.log('', fingerprint ? `Home Assistant API key read, fingerprint ${fingerprint}, held in this tab only` : 'no Home Assistant API key to keep');
      this.set({ key: { state: 'reading', at: 2 } });
      await this.restart(p);
      live();
      this.read = { gen };
      this.apiKey = key;
      this.set({ key: fingerprint ? { state: 'held', fingerprint } : { state: 'none' } });
    } catch (e) {
      if (e instanceof Skipped) { await p.close?.().catch(() => {}); return; }
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

  /** Inside the click: the browser's device list opens first, then Kiln reads the Mirror's fastboot (read-only). */
  async connectFastboot(): Promise<void> {
    const req = this.d.requestFastboot();
    this.set({ connecting: true, connectError: undefined });
    try {
      this.fb = await req;
      const fastboot = await checkFastboot(this.fb);
      for (const c of fastboot.checks) this.log('', `${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}`);
      fastboot.info.forEach((i) => this.log('', i));
      this.set({ connecting: false, fastboot, screen: fastboot.ok ? 'confirm' : 'fastboot' });
    } catch (e) {
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  /** Builds the images (install) and flashes them. Only after the person confirmed lululemon's software is gone for good. */
  async flash(): Promise<void> {
    const s = this.state;
    if (!this.fb || !s.fastboot?.ok || !s.understood || s.screen === 'working') return;
    const work = (step: WorkStep, x: number) => this.set({ work: { step, x } });
    this.set({ screen: 'working', error: undefined, flashFailed: false, work: { step: s.back ? 'flash-system' : 'patch', x: 0 } });
    let building = false;
    try {
      let images: { system: Blob; userdata: Blob };
      if (s.back) {
        images = { system: this.files.system!, userdata: this.files.userdata! };
        this.log('Way back: flashing the Glass Panes images as downloaded');
      } else {
        const adbKey = this.d.adbKey();
        if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
        this.log('Building the images');
        building = true;
        // the Wi-Fi goes into the new userdata (saved for Android, its password only as WPA's PSK), so the
        // Mirror joins on its first boot with nothing tapped; sending it to the app after the boot stays a fallback
        const wifi = s.wifi.ssid ? { ssid: s.wifi.ssid, password: s.wifi.password } : undefined;
        images = await this.d.images.build({
          apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), settingsOverlay: await this.d.settingsOverlay?.(),
          adbKey, userdataBytes: s.fastboot.userdataBytes, fill: !s.noFill, ...(s.fastboot.deviceMac ? { deviceMac: s.fastboot.deviceMac } : {}), ...(wifi ? { wifi } : {}),
          // the flag only: the key stays in this tab until the first start
          ...(this.apiKey ? { apiKeyHandover: true } : {}),
        }, (step, x) => work(step, x));
        building = false;
      }
      this.log(`Flashing${s.noFill ? ' (zeros as RAW, no FILL chunks)' : ''}`);
      this.set({ flashFailed: true }); // until the restart: a failure from here is the bootloader's
      await flashImages(this.fb, images, (part, x) => work(part === 'system' ? 'flash-system' : 'flash-userdata', x));
      this.set({ flashFailed: false });
      work('restart', 1);
      this.log('Restarting the Mirror');
      this.fb = undefined;
      if (!s.back) { this.d.flashedHere?.set(); await this.d.images.discard(); }
      this.set({ screen: s.back ? 'done' : 'adb' });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      // a failed build dropped the loaded image (it may be patched halfway): select or download it again
      this.set({ screen: 'error', error: (e as Error).message, ...(building ? { image: undefined } : {}) });
    }
  }

  /** After the restart, inside the click: adb with this browser's key, then the first-boot steps. */
  async connectAdb(): Promise<void> {
    const req = this.d.requestAdb();
    this.set({ connecting: true, connectError: undefined });
    let p: PanelShell;
    try { p = await req; } catch (e) { this.set({ connecting: false, connectError: (e as Error).message }); return; }
    this.panel = p;
    this.set({ connecting: false, screen: 'working', work: { step: 'first-boot', x: 0 } });
    try {
      const sleep = this.d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
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

  /** From an error: back to the files, keeping what checked out (a loaded image builds again). */
  retry() { this.set({ screen: this.state.back ? 'back' : 'image', error: undefined, work: undefined, fastboot: undefined, understood: false, flashFailed: false }); }

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
