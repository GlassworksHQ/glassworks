// Installing Glassworks on a Mirror over fastboot (#40, option A), outside React. The person
// downloads the Glass Panes system.img and selects it. Kiln checks it, builds the two images in a
// worker (images.ts), and flashes them with the Mirror in fastboot. Then it finishes over adb with
// this browser's own key. Test only, behind ?apk=local, like the rest of the Mirror's page.
import type { PanelShell } from '@glassworks/adb';
import {
  GLASS_PANES, GLASS_PANES_USERDATA, WrongImage, checkDownload, checkFastboot, firstBoot, flashImages,
  type FastbootLike, type FastbootReport, type FirstBoot, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where the two images get made: a worker on OPFS in the browser, a fake in demos and tests. */
export interface ImageMaker {
  /** Reads and checks the person's system.img (size, then SHA-256 as it reads). Throws WrongImage. */
  load(file: Blob, onProgress: (x: number) => void): Promise<void>;
  build(o: { apk: Uint8Array; bootAnimation?: Uint8Array; adbKey: string; userdataBytes: number },
    onProgress: (step: PrepareStep, x: number) => void): Promise<{ system: Blob; userdata: Blob }>;
  /** Deletes what it kept (the image, the built copies). */
  discard(): Promise<void>;
}

export interface FlashDeps {
  images: ImageMaker;
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

export interface FileCheck { name: string; size: number; state: 'checking' | 'ok' | 'bad'; progress: number; error?: string }

export interface FlashState {
  screen: FlashScreen;
  /** the way back: put the plain Glass Panes images back */
  back: boolean;
  image?: FileCheck;
  userdataFile?: FileCheck;
  haUrl: string;
  wifi: { ssid: string; password: string };
  fastboot?: FastbootReport;
  connecting?: boolean;
  connectError?: string;
  understood: boolean;
  work?: { step: WorkStep; x: number };
  firstBoot?: FirstBoot;
  error?: string;
  log: string[];
}

export class FlashSession {
  state: FlashState = { screen: 'image', back: false, haUrl: '', wifi: { ssid: '', password: '' }, understood: false, log: [] };
  private listeners = new Set<() => void>();
  private fb?: FastbootLike;
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

  /** The person's system.img: checked (and, for the install, read into the worker) right away. Only the latest pick counts. */
  async selectImage(file: Blob & { name?: string }): Promise<void> {
    const pick = ++this.picks;
    const name = file.name ?? GLASS_PANES.system.name;
    this.files.system = undefined;
    this.set({ image: { name, size: file.size, state: 'checking', progress: 0 } });
    const progress = (x: number) => { if (pick === this.picks) this.set({ image: { name, size: file.size, state: 'checking', progress: x } }); };
    try {
      if (this.state.back) await checkDownload(file, GLASS_PANES.system, progress);
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
      await checkDownload(file, GLASS_PANES_USERDATA, (x) => this.set({ userdataFile: { name, size: file.size, state: 'checking', progress: x } }));
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
    this.set({ screen: 'working', error: undefined, work: { step: s.back ? 'flash-system' : 'patch', x: 0 } });
    try {
      let images: { system: Blob; userdata: Blob };
      if (s.back) {
        images = { system: this.files.system!, userdata: this.files.userdata! };
        this.log('Way back: flashing the Glass Panes images as downloaded');
      } else {
        const adbKey = this.d.adbKey();
        if (!adbKey) throw new Error('Kiln has no adb key for this browser yet. Reload the page and try again.');
        this.log('Building the images');
        images = await this.d.images.build({ apk: this.d.apk()!, bootAnimation: await this.d.bootAnimation?.(), adbKey, userdataBytes: s.fastboot.userdataBytes },
          (step, x) => work(step, x));
      }
      this.log('Flashing');
      await flashImages(this.fb, images, (part, x) => work(part === 'system' ? 'flash-system' : 'flash-userdata', x));
      work('restart', 1);
      this.log('Restarting the Mirror');
      this.fb = undefined;
      if (!s.back) await this.d.images.discard();
      this.set({ screen: s.back ? 'done' : 'adb' });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message });
    }
  }

  /** After the restart, inside the click: adb with this browser's key, then the first-boot steps. */
  async connectAdb(): Promise<void> {
    const req = this.d.requestAdb();
    this.set({ connecting: true, connectError: undefined });
    let p: PanelShell;
    try { p = await req; } catch (e) { this.set({ connecting: false, connectError: (e as Error).message }); return; }
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

  /** From an error: back to the files, keeping what checked out. */
  retry() { this.set({ screen: this.state.back ? 'back' : 'image', error: undefined, work: undefined, fastboot: undefined, understood: false }); }
}
