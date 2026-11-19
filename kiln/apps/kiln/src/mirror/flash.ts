// What goes on a Mirror over fastboot (#40, option A; issue 579), outside React. The person picks an
// image: Glassworks, first and the default, or a community image such as Glass Panes. Each is a
// recipe of data (frames/lululemon-mirror/installer/src/choices.ts) that this session runs step by
// step, inside Kiln's safety steps (kiln-core plan()). Kiln downloads every file through Refract and
// checks it against the release's SHA-256 before anything goes near the Mirror. A download Kiln can't
// serve is Kiln's bug: the screen says so, with Try again and Report this, and never asks the person
// to download a file. Glassworks is built from the checked image in a worker (images.ts) and finishes
// over adb with this browser's key; a community image goes on as its release has it.
// Test only, behind ?apk=local, like the rest of the Mirror's page.
import type { PanelShell } from '@glassworks/adb';
import { imageFromLink, plan, writtenAsIs, type ImageFile, type ImageRecipe, type PlannedStep } from '@glassworks/kiln-core';
import {
  MIRROR_IMAGES, ProxyError, WrongImage, checkFastboot, firstBoot, makeOnlyHome,
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
    deviceMac?: string; wifi?: { ssid: string; password: string } },
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
  /** The browser's device list for the Mirror's adb after the first boot, inside the click. */
  requestAdb(): Promise<PanelShell>;
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

/** `image`: Glassworks' download and settings; `download`: a community image's. */
export type FlashScreen = 'choose' | 'image' | 'download' | 'fastboot' | 'confirm' | 'working' | 'adb' | 'done' | 'error';
export type WorkStep = PrepareStep | 'flash-system' | 'flash-userdata' | 'restart' | 'first-boot';

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
  understood: boolean;
  work?: { step: WorkStep; x: number };
  firstBoot?: FirstBoot;
  /** build zeros as RAW blocks: the retry for a bootloader that refused a FILL chunk */
  noFill?: boolean;
  /** the HOME fix on Done is running */
  fixing?: boolean;
  /** the error came while writing: offer the retry without FILL */
  flashFailed?: boolean;
  error?: string;
  log: string[];
}

const waiting = (r: ImageRecipe): FileCheck[] => r.files.map((f) => ({ name: f.name, size: f.size, state: 'waiting', progress: 0 }));

export class FlashSession {
  readonly recipes: readonly ImageRecipe[];
  state: FlashState;
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
  private panel?: PanelShell;
  private made: Record<string, Blob> = {};
  private runs = 0;

  constructor(private readonly d: FlashDeps) {
    this.recipes = d.recipes ?? MIRROR_IMAGES;
    const linked = imageFromLink(this.recipes, d.search ?? '');
    const first = linked ?? this.recipes[0]!;
    this.state = { screen: linked ? 'download' : 'choose', picked: first.id, open: !!linked, link: !!linked, files: waiting(first),
      haUrl: '', wifi: { ssid: '', password: '' }, understood: false, log: [] };
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<FlashState>) { this.state = { ...this.state, ...patch }; for (const l of this.listeners) l(); }
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
    const g = this.recipes[0]!;
    void this.forget();
    this.set({ picked: g.id, files: waiting(g), screen: 'image', link: false, fastboot: undefined, understood: false, error: undefined });
  }

  /** Back to the choice from an image's first screen. */
  backToChoice() {
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
    this.set({ screen: 'choose', picked: r.id, from, open: r.community, link: false, files: waiting(r), fastboot: undefined, understood: false,
      work: undefined, firstBoot: undefined, error: undefined, flashFailed: false, noFill: false });
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
    if (linked.id !== this.state.picked) void this.forget();
    this.set({ screen: 'download', picked: linked.id, open: true, link: true, from: undefined,
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

  toFastboot() { if (!this.blocked()) this.set({ screen: 'fastboot', connectError: undefined }); }

  /** Inside the click: the browser's device list opens first, then Kiln's checks read the Mirror's fastboot (read-only). */
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
    const first = steps.find((x) => x.do !== 'backup');
    this.set({ screen: 'working', error: undefined, flashFailed: false, work: { step: first?.do === 'patch' ? 'patch' : first?.do === 'flash' ? `flash-${first.partition}` as WorkStep : 'restart', x: 0 } });
    let building = false;
    try {
      // the Mirror's LK can't read anything back: Kiln's backup step is the record of what it writes
      this.log('Backup: none possible, the Mirror\'s bootloader can\'t read anything back. What Kiln writes:');
      for (const f of writtenAsIs(r)) this.log('', `${f.name}: SHA-256 ${f.sha256}, as ${r.name}'s release publishes it`);
      for (const step of steps) {
        if (step.do === 'patch') {
          const adbKey = this.d.adbKey();
          if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
          this.log('Building the images');
          building = true;
          // the Wi-Fi goes into the new userdata (saved for Android, its password only as WPA's PSK), so the
          // Mirror joins on its first boot with nothing tapped; sending it to the app after the boot stays a fallback
          const wifi = s.wifi.ssid ? { ssid: s.wifi.ssid, password: s.wifi.password } : undefined;
          this.made = await this.d.images.build({
            apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), settingsOverlay: await this.d.settingsOverlay?.(),
            adbKey, userdataBytes: s.fastboot.userdataBytes, fill: !s.noFill, ...(s.fastboot.deviceMac ? { deviceMac: s.fastboot.deviceMac } : {}), ...(wifi ? { wifi } : {}),
          }, (step, x) => work(step, x));
          building = false;
          this.log('', `Kiln built: ${step.makes.join(', ')}`);
        } else if (step.do === 'flash') {
          const id = `flash-${step.partition}` as WorkStep;
          let blob = this.made[step.file];
          if (!blob) {
            // a downloaded file, checked again as the very Blob fastboot will send: nothing else can have changed it
            const want = r.files.find((f) => f.name === step.file)!;
            blob = await this.d.images.file(step.file);
            this.log('', `${step.file}: checking it again before writing`);
            work(id, 0);
            await this.d.images.check(blob, want, () => {});
          }
          this.log(`Flashing ${step.partition}${r.community ? ` with ${step.file} as published` : s.noFill ? ' (zeros as RAW, no FILL chunks)' : ''}`);
          this.set({ flashFailed: true }); // until the restart: a failure from here is the bootloader's
          work(id, 0);
          await fb.flashBlob(step.partition, blob, (x) => work(id, x));
        } else if (step.do === 'restart') {
          this.set({ flashFailed: false });
          work('restart', 1);
          this.log('Restarting the Mirror');
          await fb.reboot();
          this.fb = undefined;
          this.made = {};
          await this.d.images.discard();
          // nothing kept any more: another install downloads again
          this.set({ files: waiting(r) });
        }
      }
      this.set({ screen: steps.some((x) => x.do === 'first-start') ? 'adb' : 'done' });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      // a failed build dropped the fetched image (it may be patched halfway), and a stored file that no longer
      // checks out can't be written: either way, download again
      this.set({ screen: 'error', error: (e as Error).message, ...(building || e instanceof WrongImage ? { files: waiting(r) } : {}) });
    }
  }

  /** After the restart, inside the click: adb with this browser's key, then Kiln's first start (Glassworks). */
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
      const fb = await firstBoot(p, { haUrl: this.state.haUrl || undefined, wifi: ssid ? { ssid, password } : undefined, onStep: this.log, sleep });
      this.set({ screen: 'done', firstBoot: fb, work: { step: 'first-boot', x: 1 } });
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
