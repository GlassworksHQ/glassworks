// An Android device over USB debugging, outside React (issue 621): the engine's flow for a UsbFrame (kiln-core usb.ts).
// The person plugs it in with debugging still off, so Kiln names it from WebUSB (the frame's identifyUsb) or asks for
// the brand; shows that brand's steps to turn on USB debugging; connects over adb (Allow on the device, asked again
// while it refuses: adbAsk.ts); reads it (the frame's plan, nothing written); then installs and grants, reading each
// grant back, and hands the device to the standard flow. Undo removes the app.
//
// The APK: with ?apk=release, the newest release of the frame's line (its installer.json `device.apk`), checked against
// its .sha256 (release/apk.ts, as the device flows do); with ?apk=local, a file picked on this computer. Without either
// the page stays locked until the frame's frame.json makes it installable (usbOpen). The first screen downloads the
// line's default ABI (`device.apk.abi`) before Kiln can read the device; once it can, a device that runs another of the
// frame's ABIs (frame.json `abis`) gets that build from the same release, before anything is written (issue 700).
import type { PanelShell } from '@glassworks/adb';
import { apkPackage } from '@glassworks/app-adb';
import { OwnFrameRefused, Refused, cap, type OwnFrame, type UsbApp, type UsbFrame, type UsbIdentified, type UsbInstalled, type UsbPhase, type UsbGrant, type UsbPlan, type UsbSilentUpdates } from '@glassworks/kiln-core';
import { FRAMES } from '../frames/catalog';
import { askAdb, nothingPicked } from './adbAsk';
import type { DeviceProgress } from './imageSession';
import { NoRelease, apkAbi, apkMode, installable, releaseApk, type ApkFrom, type ListedRelease, type ReleaseApk, type ReleaseLine } from '../release/apk';
import { within } from './imageSession';

/** How long one read over adb may stay silent before Kiln stops it with Try again (the engine's stall watchdog). */
export const USB_READ_MS = 30_000;

/** The page is everyone's once the frame's frame.json makes it installable (release/apk.ts `installable`). */
export const usbOpen = (f: Pick<UsbFrame, 'id'>) => { const d = FRAMES.find((x) => x.id === f.id); return !!d && installable(d); };

/** Device (find, named, brand), Connect (debug, allow), Install (working), Done; `off` without ?apk=…. */
export type UsbScreen = 'off' | 'find' | 'named' | 'brand' | 'debug' | 'allow' | 'working' | 'done' | 'error';

export type Flow = 'install' | 'undo';

/** The APK the tester picked: what the screen shows of it. The bytes stay in the session. */
export interface PickedApk { name: string; size: number; sha256: string; package?: string; error?: string }

/** What WebUSB says about a device that has no adb yet. */
export interface UsbFacts { vendorId: number; productName?: string }

export type UsbResult = { flow: 'install'; installed: UsbInstalled } | { flow: 'undo'; removed: UsbApp[] };

export interface UsbState {
  screen: UsbScreen;
  /** An earlier page load wrote to the device (its `progress` says): an install or undo there didn't finish. */
  changed?: boolean;
  flow: Flow;
  apk?: PickedApk;
  /** With the APK from the release: where its download is. */
  release?: ReleaseApk;
  /** what Kiln can say about the device: from USB, the person's pick, then adb's read */
  identified?: UsbIdentified;
  /** the USB product name, when it said something */
  usbName?: string;
  /** the brand screen's choice before Continue; `other`: Android's general steps */
  brand?: string | 'other';
  /** where the brand screen's Back goes */
  brandFrom?: UsbScreen;
  /** where the debugging steps' Back goes: the brand list when a brand was picked there, else the named device */
  stepsFrom?: 'brand' | 'named';
  /** the browser's device list is open (the unclaimed device, or its adb interface and Allow) */
  finding?: boolean;
  findError?: string;
  connecting?: boolean;
  connectError?: string;
  /** read-only: what the device is, before anything is written */
  plan?: UsbPlan;
  /** the recipe's phases so far, the last one now */
  phases: UsbPhase[];
  /** each grant, read back from the device, once the recipe has them */
  granted?: { grants: UsbGrant[]; silentUpdates: UsbSilentUpdates };
  result?: UsbResult;
  error?: string;
  /** the device has a frame of its own: the error screen sends the person to its page */
  own?: OwnFrame;
  log: string[];
}

/** The device over adb; `close` lets go of its USB link (Panel has it). */
export type Device = PanelShell & { close?(): Promise<void> };

export interface UsbDeps {
  frame: UsbFrame;
  /** WebUSB's device list with no filter. Called inside the click, before any await. */
  findUsb(): Promise<UsbFacts>;
  /** The adb interface: the browser's list, then Allow on the device. Called inside the click. */
  connect(): Promise<Device>;
  /**
   * The device `connect` picked, again with no list, after it refused this browser's key (Allow not tapped yet, or
   * adbd not ready): Kiln asks again every 5 s (adbAsk.ts). Without it, a refusal ends the connect.
   */
  reopen?(o?: { authMs?: number }): Promise<Device>;
  /** Whether the last `connect` picked a device, so its refusal came from the device. */
  picked?(): boolean;
  sleep?: (ms: number) => Promise<void>;
  homeTimeoutMs?: number;
  setupTries?: number;
  /** The APK from the newest release of the frame's line, through Refract; without it, a file the tester picks. */
  release?: typeof fetch;
  /**
   * The install is through: the standard flow takes the device from here (its App step first, issue 621), so the install
   * doesn't hand over to the app's setup page. The session lets go of the device and keeps the result for Done's lines.
   */
  handOver?(device: Device, installed: UsbInstalled): void;
  /** Where an install or undo got to, kept across page loads (device/imageSession.ts `DeviceProgress`). */
  progress?: DeviceProgress;
  /** How long one read over adb may stay silent (USB_READ_MS) */
  readMs?: number;
}

/** Whether the page is on and where its APK comes from (release/apk.ts `apkMode`). */
export const usbMode = (search: string, demosOn: boolean, demos: readonly string[], open: boolean) => apkMode(search, demosOn, demos, open);

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const cancelled = (e: unknown) => (e as { name?: string } | undefined)?.name === 'NotFoundError';

export class UsbSession {
  state: UsbState;
  private listeners = new Set<() => void>();
  private apk?: Uint8Array;
  /**
   * The adb connection, kept only while it is known to work: from Allow through a run, and after a refusal (the
   * device answered). Done and any other failure let it go, since the cable may be out by then and Panel can't
   * reconnect: the next run asks the browser for the device again, inside its click.
   */
  private device?: Device;
  private picks = 0;
  /** With the APK from the release: the ABI of the one `apk` holds, and the release it came from. */
  private releaseAbi?: string;
  private listed?: ListedRelease;

  constructor(private readonly d: UsbDeps, on: boolean) {
    this.state = { screen: on ? 'find' : 'off', flow: 'install', phases: [], log: [], ...(d.release ? { release: { state: 'waiting' } } : {}), ...(on && d.progress?.get() ? { changed: true } : {}) };
  }

  get from(): ApkFrom { return this.d.release ? 'release' : 'local'; }

  /** The frame this session installs. */
  get frame(): UsbFrame { return this.d.frame; }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<UsbState>) {
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
    if (pkg && !this.d.frame.apps.some((a) => a.package === pkg)) error = `this is ${pkg}, not ${this.d.frame.app}`;
    if (pick !== this.picks) return;
    this.apk = error ? undefined : bytes;
    this.set({ apk: { name, size: bytes.length, sha256, package: pkg, ...(error ? { error } : {}) } });
  }

  /**
   * The newest release's APK of the frame's line, checked against its .sha256, then as a picked file is. Starts once, when
   * the first screen shows; again only after no release or an error (Try again).
   */
  async loadRelease(): Promise<void> {
    const r = this.state.release;
    if (!this.d.release || !r || !['waiting', 'none', 'error'].includes(r.state)) return;
    try {
      // only GitHub's own SHA-256 of the APK, never the .sha256 Refract serves beside it, as the wizard's (wizard/assets.ts)
      const { bytes, ready, release } = await releaseApk(this.d.frame.apk as ReleaseLine, this.d.release, (release) => this.set({ release }), { requireDigest: true });
      this.log(`${ready.name} from ${ready.tag}: SHA-256 ${ready.sha256}, as the release publishes it`);
      await this.pickApk(ready.name, bytes);
      this.releaseAbi = this.apk ? this.d.frame.apk.abi : undefined;
      this.listed = this.apk ? release : undefined;
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
    if (this.state.apk?.error) return r ? `The release's APK can't go on ${this.d.frame.the}: ${this.state.apk.error}.` : `Pick another file: ${this.state.apk.error}.`;
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
      const identified = this.d.frame.identifyUsb(usb);
      const name = usb.productName?.trim();
      this.set({ finding: false, identified, ...(name ? { usbName: name } : {}), screen: identified.pick ? 'brand' : 'named', brand: identified.brand?.id, brandFrom: 'find' });
    } catch (e) {
      this.set({ finding: false, findError: cancelled(e) ? `Nothing was picked. Plug ${this.d.frame.the} in, then pick it in the list.` : (e as Error).message });
    }
  }

  /** The brand screen, from "Not right?", "Different on yours?" or a device Kiln can't name. */
  brands() { this.set({ screen: 'brand', brand: this.state.identified?.brand?.id, brandFrom: this.state.screen }); }
  /** Back from the brand screen, to where it was opened (the first screen when Kiln couldn't name the device). */
  back() { this.set({ screen: this.state.brandFrom ?? 'find' }); }
  pickBrand(brand: string | 'other') { this.set({ brand }); }
  /** Continue on the brand screen: that brand's steps. */
  chooseBrand() {
    const b = this.state.brand;
    if (!b) return;
    this.set({ identified: this.d.frame.identifyPicked(b === 'other' ? undefined : b), screen: 'debug', stepsFrom: 'brand' });
  }

  /** Continue on the named screen. */
  toSteps() { if (!this.blocked()) this.set({ screen: 'debug', stepsFrom: 'named' }); }

  /** Back from the named screen to the first one: the device picked goes, and Find asks the browser's list again. */
  restart() { if (this.state.finding) return; this.release(); this.set({ screen: 'find', identified: undefined, usbName: undefined, brand: undefined, stepsFrom: undefined, findError: undefined }); }

  /** Back from the steps, to the screen that led there: the brand list, or the device Kiln named. */
  backFromSteps() { this.set({ screen: this.state.stepsFrom ?? (this.state.identified?.pick ? 'brand' : 'named') }); }

  /** Back from Tap Allow to the steps, once the ask has stopped (a refusal, nothing picked): not while it still waits for the tap. */
  backToSteps() { if (!this.state.connecting && this.state.screen === 'allow') this.set({ screen: 'debug', connectError: undefined }); }

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
   * Must run inside the click. A device kept after a refusal is used again, without the list. One that refuses this
   * browser's key is asked again, with no list and no click (adbAsk.ts).
   */
  async connect(): Promise<void> {
    if (this.state.connecting || this.state.screen === 'working') return;
    const kept = this.device;
    const { reopen, picked } = this.d;
    const asking = kept ? Promise.resolve({ panel: kept }) : askAdb({
      request: () => this.d.connect(), ...(reopen ? { reopen: (o) => reopen.call(this.d, o) } : {}), ...(picked ? { picked: () => picked.call(this.d) } : {}),
    }, {
      // each try waits for Allow as long as the window has left, as the first one does: a deadline would close the
      // device and redraw its dialog under the person's finger
      authMs: Number.POSITIVE_INFINITY, sleep: this.d.sleep ?? ((ms) => new Promise<void>((r) => setTimeout(r, ms))),
      onWait: (why) => this.log('', `${cap(this.d.frame.the)} didn't let this browser in yet (${why}). Asking again in 5 s.`),
    });
    this.set({ screen: 'allow', connecting: true, connectError: undefined, error: undefined });
    const asked = await asking;
    if (!asked.panel) {
      const e = asked.last;
      this.set({ connecting: false, connectError: nothingPicked(e) && !asked.picked ? `Nothing was picked. Pick ${this.d.frame.the} in the browser's list, then tap Allow on it.` : (e as Error).message });
      return;
    }
    this.device = asked.panel as Device;
    this.set({ connecting: false });
    await (this.state.flow === 'undo' ? this.undo() : this.install());
  }

  private async install(): Promise<void> {
    await this.work(async () => {
      if (this.d.release) await this.releaseFor(this.device!);
      const plan = await this.d.frame.plan(this.device!, this.apk!);
      this.set({ plan, identified: plan.identified });
      const installed = await this.d.frame.install(this.device!, {
        apk: this.apk!, onStep: this.log, sleep: this.d.sleep, homeTimeoutMs: this.d.homeTimeoutMs, setupTries: this.d.setupTries,
        // the standard flow sets it up next, over this USB: no hand-over to its setup page
        ...(this.d.handOver ? { handoff: false } : {}),
        onPhase: (p) => this.set({ phases: [...this.state.phases, p], ...(p.step === 'granted' ? { granted: { grants: p.grants, silentUpdates: p.silentUpdates } } : {}) }),
      });
      return { flow: 'install', installed };
    });
  }

  /**
   * With the APK from the release: the build for the ABIs this device runs (`ro.product.cpu.abilist`, preferred
   * first), from the release the first screen downloaded from, when it isn't that ABI. Reads only, each read bounded.
   */
  private async releaseFor(p: Device): Promise<void> {
    const abis = (await within(p, 'getprop ro.product.cpu.abilist', this.d.readMs ?? USB_READ_MS, cap(this.d.frame.the))).out.split(',').map((a) => a.trim()).filter(Boolean);
    const ships = FRAMES.find((x) => x.id === this.d.frame.id)?.abis ?? [];
    const abi = apkAbi(abis, ships);
    if (!abi) {
      if (abis.length && ships.length) throw new Refused(`This ${this.d.frame.short} runs ${abis.join(', ')}, and ${this.d.frame.app} is built for ${ships.join(' and ')} only.`);
      return; // the recipe reads the device itself, and says what it can't take
    }
    if (abi === this.releaseAbi && this.apk) return;
    this.log(`${cap(this.d.frame.the)} runs ${abis.join(', ')}: the release's ${abi} build`);
    const got = await releaseApk({ line: this.d.frame.apk.line, abi, ...(this.listed ? { from: this.listed } : {}) }, this.d.release!, (release) => this.set({ release }), { requireDigest: true }).catch((e: unknown) => {
      if (!(e instanceof NoRelease)) throw e;
      this.set({ release: { state: 'none' } });
      throw new Refused(`${this.d.frame.app} ${this.listed?.version ?? ''} has no ${abi} build, which this ${this.d.frame.short} needs.`.replace('  ', ' '));
    });
    const { bytes, ready } = got;
    this.log(`${ready.name} from ${ready.tag}: SHA-256 ${ready.sha256}, as the release publishes it`);
    await this.pickApk(ready.name, bytes);
    const refused = this.state.apk?.error;
    this.set({ release: refused ? { state: 'error', why: `Kiln can't install it: ${refused}` } : ready });
    if (refused || !this.apk) throw new Refused(`The release's ${abi} build can't go on ${this.d.frame.the}: ${refused ?? 'it couldn\'t be read'}.`);
    this.releaseAbi = abi;
  }

  private async undo(): Promise<void> {
    await this.work(async () => ({ flow: 'undo', removed: (await this.d.frame.undo(this.device!, { onStep: this.log, sleep: this.d.sleep })).removed }));
  }

  private async work(run: () => Promise<UsbResult>): Promise<void> {
    if (this.state.screen === 'working') return;
    this.set({ screen: 'working', phases: [], granted: undefined, result: undefined, error: undefined, own: undefined });
    this.d.progress?.set({ at: 'written' });
    try {
      const result = await run();
      this.d.progress?.set(undefined);
      if (result.flow === 'install' && this.d.handOver && this.device) {
        // the standard flow has the device from here; this page, opened again, starts from its first screen
        const device = this.device;
        this.device = undefined;
        this.set({ screen: 'find', changed: undefined, result, phases: [] });
        this.d.handOver(device, result.installed);
        return;
      }
      this.release();
      this.set({ screen: 'done', changed: undefined, result });
    } catch (e) {
      if (!(e instanceof Refused)) this.release();
      this.log('Stopped');
      this.log('', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message, ...(e instanceof OwnFrameRefused ? { own: e.own } : {}) });
    }
  }
}
