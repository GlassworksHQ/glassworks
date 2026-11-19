// /android outside React (issue 403): Glassworks onto any Android 6+ device over USB, with the recipe in
// frames/generic-android/installer. The person plugs it in with debugging still off, so Kiln names it
// from WebUSB (identifyUsb) or asks for the brand; shows that brand's steps to turn on USB debugging;
// connects over adb (Allow on the device); reads it (planInstall, nothing written); then installs and
// grants, reading each grant back (installGeneric). Undo removes the app (undoGeneric).
//
// The APK: with ?apk=release, the newest generic-android-v* release's arm64-v8a APK, checked against its .sha256
// (release/apk.ts, as the Mirror's page does); with ?apk=local, a file picked on this computer. Without either the
// page stays locked until generic-android's frame.json makes it installable (ANDROID_OPEN).
import type { PanelShell } from '@glassworks/adb';
import { apkPackage } from '@glassworks/app-adb';
import {
  APPS, OwnFrameRefused, Refused, identifyPicked, identifyUsb, installGeneric, planInstall, undoGeneric,
  type BrandId, type OwnFrame, type GenericApp, type GenericInstalled, type GenericPhase, type Grant, type Identified, type Plan, type SilentUpdatesResult,
} from '@glassworks/generic-android-installer';
import frame from '../../../../../frames/generic-android/design/frame.json';
import { NoRelease, apkMode, installable, releaseApk, type ApkFrom, type ReleaseApk, type ReleaseLine } from '../release/apk';

/**
 * Glassworks for generic Android (Android 8+), never its legacy build; arm64-v8a, the ABI of the Pixel Tablet and
 * most tablets on 8+. The recipe refuses a device that can't run it (planInstall) before anything is written.
 */
export const ANDROID_APK: ReleaseLine = { line: 'generic-android', abi: 'arm64-v8a' };

/** The page is everyone's once generic-android's frame.json makes it installable (release/apk.ts `installable`). */
export const ANDROID_OPEN = installable(frame);

/** Device (find, named, brand), Connect (debug, allow), Install (working), Done; `off` without ?apk=…. */
export type AndroidScreen = 'off' | 'find' | 'named' | 'brand' | 'debug' | 'allow' | 'working' | 'done' | 'error';

export type Flow = 'install' | 'undo';

/** The APK the tester picked: what the screen shows of it. The bytes stay in the session. */
export interface PickedApk { name: string; size: number; sha256: string; package?: string; error?: string }

/** What WebUSB says about a device that has no adb yet. */
export interface UsbFacts { vendorId: number; productName?: string }

export type AndroidResult = { flow: 'install'; installed: GenericInstalled } | { flow: 'undo'; removed: GenericApp[] };

export interface AndroidState {
  screen: AndroidScreen;
  flow: Flow;
  apk?: PickedApk;
  /** With the APK from the release: where its download is. */
  release?: ReleaseApk;
  /** what Kiln can say about the device: from USB, the person's pick, then adb's read */
  identified?: Identified;
  /** the USB product name, when it said something */
  usbName?: string;
  /** the brand screen's choice before Continue; `other`: Android's general steps */
  brand?: BrandId | 'other';
  /** where the brand screen's Back goes */
  brandFrom?: AndroidScreen;
  /** the browser's device list is open (the unclaimed device, or its adb interface and Allow) */
  finding?: boolean;
  findError?: string;
  connecting?: boolean;
  connectError?: string;
  /** read-only: what the device is, before anything is written */
  plan?: Plan;
  /** the recipe's phases so far, the last one now */
  phases: GenericPhase[];
  /** each grant, read back from the device, once the recipe has them */
  granted?: { grants: Grant[]; silentUpdates: SilentUpdatesResult };
  result?: AndroidResult;
  error?: string;
  /** the device has a frame of its own: the error screen sends the person to its page */
  own?: OwnFrame;
  log: string[];
}

/** The device over adb; `close` lets go of its USB link (Panel has it). */
export type Device = PanelShell & { close?(): Promise<void> };

export interface AndroidDeps {
  /** WebUSB's device list with no filter. Called inside the click, before any await. */
  findUsb(): Promise<UsbFacts>;
  /** The adb interface: the browser's list, then Allow on the device. Called inside the click. */
  connect(): Promise<Device>;
  sleep?: (ms: number) => Promise<void>;
  homeTimeoutMs?: number;
  setupTries?: number;
  /** The APK from the newest generic-android release, through Kiln's release route; without it, a file the tester picks. */
  release?: typeof fetch;
}

/** Whether the page is on and where its APK comes from (release/apk.ts `apkMode`). */
export const androidMode = (search: string, demosOn: boolean, demos: readonly string[], open = ANDROID_OPEN) => apkMode(search, demosOn, demos, open);

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const cancelled = (e: unknown) => (e as { name?: string } | undefined)?.name === 'NotFoundError';

export class AndroidSession {
  state: AndroidState;
  private listeners = new Set<() => void>();
  private apk?: Uint8Array;
  /**
   * The adb connection, kept only while it is known to work: from Allow through a run, and after a refusal (the
   * device answered). Done and any other failure let it go, since the cable may be out by then and Panel can't
   * reconnect: the next run asks the browser for the device again, inside its click.
   */
  private device?: Device;
  private picks = 0;

  constructor(private readonly d: AndroidDeps, on: boolean) {
    this.state = { screen: on ? 'find' : 'off', flow: 'install', phases: [], log: [], ...(d.release ? { release: { state: 'waiting' } } : {}) };
  }

  get from(): ApkFrom { return this.d.release ? 'release' : 'local'; }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<AndroidState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }
  private log = (step: string, detail?: string) => this.set({ log: [...this.state.log, step || `  ${detail}`].slice(-400) });

  /** The APK file, hashed so the tester can match it to the build they meant. Only the latest pick counts. */
  async pickApk(name: string, data: Uint8Array | Promise<Uint8Array>): Promise<void> {
    const pick = ++this.picks;
    this.apk = undefined;
    this.set({ apk: undefined });
    let bytes: Uint8Array;
    try { bytes = await data; } catch (e) {
      if (pick === this.picks) this.set({ apk: { name, size: 0, sha256: '', error: `the file couldn't be read (${(e as Error).message})` } });
      return;
    }
    const sha256 = hex(await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>));
    let pkg: string | undefined, error: string | undefined;
    try { pkg = await apkPackage(bytes); } catch (e) { error = (e as Error).message; }
    if (pkg && !APPS.some((a) => a.package === pkg)) error = `this is ${pkg}, not Glassworks for Android`;
    if (pick !== this.picks) return;
    this.apk = error ? undefined : bytes;
    this.set({ apk: { name, size: bytes.length, sha256, package: pkg, ...(error ? { error } : {}) } });
  }

  /**
   * The newest generic-android release's APK, checked against its .sha256, then as a picked file is. Starts once, when
   * the first screen shows; again only after no release or an error (Try again).
   */
  async loadRelease(): Promise<void> {
    const r = this.state.release;
    if (!this.d.release || !r || !['waiting', 'none', 'error'].includes(r.state)) return;
    try {
      const { bytes, ready } = await releaseApk(ANDROID_APK, this.d.release, (release) => this.set({ release }));
      this.log(`${ready.name} from ${ready.tag}: SHA-256 ${ready.sha256}, as the release publishes it`);
      await this.pickApk(ready.name, bytes);
      // an APK the checks refuse is an error of the release, so Try again can fetch a fixed one
      const refused = this.state.apk?.error;
      this.set({ release: refused ? { state: 'error', why: `Kiln can't install it: ${refused}` } : ready });
    } catch (e) {
      const why = (e as Error).message;
      if (!(e instanceof NoRelease)) this.log(`The release's APK: ${why}`);
      this.set({ release: e instanceof NoRelease ? { state: 'none' } : { state: 'error', why } });
    }
  }

  /** Why the person can't go on from the device screen yet, or undefined. */
  blocked(): string | undefined {
    if (this.state.flow === 'undo') return undefined;
    const r = this.state.release;
    if (this.state.apk?.error) return r ? `The release's APK can't go on the tablet: ${this.state.apk.error}.` : `Pick another file: ${this.state.apk.error}.`;
    if (this.apk) return undefined;
    if (!r) return 'Pick the APK file first.';
    if (r.state === 'none') return 'There is no release of Glassworks for Android yet.';
    if (r.state === 'error') return 'Kiln couldn\'t get Glassworks from its release. Try again.';
    return 'Kiln is still downloading Glassworks.';
  }

  /** Must run inside the click: the browser's list opens before the first await. */
  async find(): Promise<void> {
    if (this.state.finding) return;
    const req = this.d.findUsb();
    this.set({ finding: true, findError: undefined });
    try {
      const usb = await req;
      const identified = identifyUsb(usb);
      const name = usb.productName?.trim();
      this.set({ finding: false, identified, ...(name ? { usbName: name } : {}), screen: identified.pick ? 'brand' : 'named', brand: identified.brand?.id, brandFrom: 'find' });
    } catch (e) {
      this.set({ finding: false, findError: cancelled(e) ? 'Nothing was picked. Plug the tablet in, then pick it in the list.' : (e as Error).message });
    }
  }

  /** The brand screen, from "Not right?", "Different on yours?" or a device Kiln can't name. */
  brands() { this.set({ screen: 'brand', brand: this.state.identified?.brand?.id, brandFrom: this.state.screen }); }
  /** Back from the brand screen, to where it was opened (the first screen when Kiln couldn't name the device). */
  back() { this.set({ screen: this.state.brandFrom ?? 'find' }); }
  pickBrand(brand: BrandId | 'other') { this.set({ brand }); }
  /** Continue on the brand screen: that brand's steps. */
  chooseBrand() {
    const b = this.state.brand;
    if (!b) return;
    this.set({ identified: identifyPicked(b === 'other' ? undefined : b), screen: 'debug' });
  }

  /** Continue on the named screen. */
  toSteps() { if (!this.blocked()) this.set({ screen: 'debug' }); }

  /** From the first screen: undo an install (or back to installing). Undo needs USB debugging too, so the same steps follow. */
  setFlow(flow: Flow) { this.set({ flow }); }

  /** Lets go of the device's USB link (a new session replaces this one, or the run ended). */
  close() { this.release(); }

  private release() {
    const d = this.device;
    this.device = undefined;
    void d?.close?.().catch(() => {});
  }

  /** Undo from Done: Kiln let go of the device there, so this asks for it again. Must run inside the click. */
  async undoFromDone(): Promise<void> {
    this.set({ flow: 'undo' });
    await this.connect();
  }

  /**
   * "It's on", or Try again: the adb interface and Allow, then read the device, then install or undo.
   * Must run inside the click. A device kept after a refusal is used again, without the list.
   */
  async connect(): Promise<void> {
    if (this.state.connecting || this.state.screen === 'working') return;
    const req = this.device ? Promise.resolve(this.device) : this.d.connect();
    this.set({ screen: 'allow', connecting: true, connectError: undefined, error: undefined });
    try {
      this.device = await req;
    } catch (e) {
      this.set({ connecting: false, connectError: cancelled(e) ? 'Nothing was picked. Pick the tablet in the browser\'s list, then tap Allow on it.' : (e as Error).message });
      return;
    }
    this.set({ connecting: false });
    await (this.state.flow === 'undo' ? this.undo() : this.install());
  }

  private async install(): Promise<void> {
    await this.work(async () => {
      const plan = await planInstall(this.device!, this.apk!);
      this.set({ plan, identified: plan.identified });
      const installed = await installGeneric(this.device!, {
        apk: this.apk!, onStep: this.log, sleep: this.d.sleep, homeTimeoutMs: this.d.homeTimeoutMs, setupTries: this.d.setupTries,
        onPhase: (p) => this.set({ phases: [...this.state.phases, p], ...(p.step === 'granted' ? { granted: { grants: p.grants, silentUpdates: p.silentUpdates } } : {}) }),
      });
      return { flow: 'install', installed };
    });
  }

  private async undo(): Promise<void> {
    await this.work(async () => ({ flow: 'undo', removed: (await undoGeneric(this.device!, { onStep: this.log, sleep: this.d.sleep })).removed }));
  }

  private async work(run: () => Promise<AndroidResult>): Promise<void> {
    if (this.state.screen === 'working') return;
    this.set({ screen: 'working', phases: [], granted: undefined, result: undefined, error: undefined, own: undefined });
    try {
      const result = await run();
      this.release();
      this.set({ screen: 'done', result });
    } catch (e) {
      if (!(e instanceof Refused)) this.release();
      this.log('Stopped');
      this.log('', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message, ...(e instanceof OwnFrameRefused ? { own: e.own } : {}) });
    }
  }
}
