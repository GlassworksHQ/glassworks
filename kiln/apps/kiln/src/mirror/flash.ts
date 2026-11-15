// Installing Glassworks on a Mirror over fastboot (#40, option A), outside React. The person
// downloads the Glass Panes system.img and selects it. Kiln checks it, builds the two images in a
// worker (images.ts), and flashes them with the Mirror in fastboot. Then it finishes over adb with
// this browser's own key. Test only, behind ?apk=local, like the rest of the Mirror's page.
import type { PanelShell } from '@glassworks/adb';
import {
  GLASS_PANES, GLASS_PANES_USERDATA, ProxyError, WrongImage, checkFastboot, firstBoot, flashImages, makeOnlyHome,
  type FastbootLike, type FastbootReport, type FirstBoot, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where the two images get made: a worker on OPFS in the browser, a fake in demos and tests. */
export interface ImageMaker {
  /** Reads and checks the person's system.img (size, then SHA-256 as it reads). Throws WrongImage. */
  load(file: Blob, onProgress: (x: number) => void): Promise<void>;
  /** The same from Kiln's release proxy, resuming with Range. Throws ProxyError when the proxy can't serve it. */
  fetch(url: string, onProgress: (x: number) => void): Promise<void>;
  /** Builds both images from the loaded one; can run again (after a failed flash) until discard(). */
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean },
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
  /** The browser's device list for the Mirror's adb after the first boot, inside the click. */
  requestAdb(): Promise<PanelShell>;
  /** This browser's adb public key line, once loaded. */
  adbKey(): string | undefined;
  /** S1 Kiosk's APK (the test-only local file), checked by the page. */
  apk(): Uint8Array | undefined;
  /** The boot animation, when Kiln has one staged. */
  bootAnimation?(): Promise<Uint8Array | undefined>;
  sleep?: (ms: number) => Promise<void>;
}

export type FlashScreen = 'image' | 'fastboot' | 'confirm' | 'working' | 'adb' | 'done' | 'back' | 'error';
export type WorkStep = PrepareStep | 'flash-system' | 'flash-userdata' | 'restart' | 'first-boot';

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
  /** build zeros as RAW blocks: the retry for a bootloader that refused a FILL chunk */
  noFill?: boolean;
  /** the HOME fix on Done is running */
  fixing?: boolean;
  /** the error came while writing: offer the retry without FILL */
  flashFailed?: boolean;
  error?: string;
  log: string[];
}

export class FlashSession {
  state: FlashState = { screen: 'image', back: false, haUrl: '', wifi: { ssid: '', password: '' }, understood: false, log: [] };
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
  private panel?: PanelShell;
  private files: { system?: Blob; userdata?: Blob } = {};
  private picks = 0;

  constructor(private readonly d: FlashDeps) {}

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<FlashState>) { this.state = { ...this.state, ...patch }; for (const l of this.listeners) l(); }
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
    if (!this.d.apk()) return 'Pick S1 Kiosk\'s APK file first.';
    if (s.haUrl && !/^https?:\/\/[^\s/]+/.test(s.haUrl)) return 'Home Assistant\'s address starts with http:// or https://.';
    if (!s.wifi.ssid !== !s.wifi.password && !s.wifi.ssid) return 'Give the Wi-Fi network\'s name too.';
    return undefined;
  }

  toFastboot() { if (!this.blocked()) this.set({ screen: 'fastboot', connectError: undefined }); }

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
        images = await this.d.images.build({ apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), adbKey, userdataBytes: s.fastboot.userdataBytes, fill: !s.noFill },
          (step, x) => work(step, x));
        building = false;
      }
      this.log(`Flashing${s.noFill ? ' (zeros as RAW, no FILL chunks)' : ''}`);
      this.set({ flashFailed: true }); // until the restart: a failure from here is the bootloader's
      await flashImages(this.fb, images, (part, x) => work(part === 'system' ? 'flash-system' : 'flash-userdata', x));
      this.set({ flashFailed: false });
      work('restart', 1);
      this.log('Restarting the Mirror');
      this.fb = undefined;
      if (!s.back) await this.d.images.discard();
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
      const fb = await firstBoot(p, { haUrl: this.state.haUrl || undefined, wifi: ssid ? { ssid, password } : undefined, onStep: this.log, sleep });
      this.set({ screen: 'done', firstBoot: fb, work: { step: 'first-boot', x: 1 } });
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
