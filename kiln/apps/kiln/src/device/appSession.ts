// The app alone over adb, outside React: the APK (the frame's newest release, or a file from this computer), the
// device over WebUSB adb, its read-only checks, then install or undo (the frame's `appFlow`). An install hands the
// device to the standard flow every frame runs after its install (standard/session.ts: App, Wi-Fi, Home Assistant,
// Panes, Radio, Done). Locked to the public until the frame's frame.json makes it installable (deviceOpen): until then
// ?apk=release and ?apk=local are test links.
import type { PanelShell } from '@glassworks/adb';
import { apkPackage } from '@glassworks/app-adb';
import { cap, type DeviceFrame, type DevicePhase, type DeviceReport, type Installed, type Removed } from '@glassworks/kiln-core';
import { FRAMES } from '../frames/catalog';
import { askAdb } from './adbAsk';
import type { DeviceProgress } from './imageSession';
import { NoRelease, apkMode, installable, releaseApk, type ApkFrom, type ReleaseApk, type ReleaseFetch, type ReleaseLine } from '../release/apk';

/** The frame's APK release line: one Refract serves (the frames' installer.json `releases`). */
export const releaseLine = (f: Pick<DeviceFrame, 'apk'>) => f.apk as ReleaseLine;

export type { ApkFrom };

/** The page is everyone's once the frame's frame.json makes it installable (release/apk.ts `installable`). */
export const deviceOpen = (f: Pick<DeviceFrame, 'id'>) => { const d = FRAMES.find((x) => x.id === f.id); return !!d && installable(d); };

/** `done`: an undo's end (an install's is the standard flow's Done). */
export type AppScreen = 'off' | 'pick' | 'checks' | 'working' | 'done' | 'error';

/** The APK the tester picked: what the screen shows of it. The bytes stay in the session. */
export interface PickedApk { name: string; size: number; sha256: string; package?: string; error?: string }

export type Result = { flow: 'install'; installed: Installed } | { flow: 'undo'; removed: Removed };

export interface AppState {
  screen: AppScreen;
  /** An earlier page load wrote to the device (its `progress` says): an install or undo there didn't finish. */
  changed?: boolean;
  apk?: PickedApk;
  /** With the APK from the release: where its download is. */
  release?: ReleaseApk;
  /** the browser's device list or the device's "Allow USB debugging?" is open */
  connecting?: boolean;
  connectError?: string;
  report?: DeviceReport;
  flow?: 'install' | 'undo';
  /** the recipe's phases so far, the last one now */
  phases: DevicePhase[];
  result?: Result;
  error?: string;
  log: string[];
}

export interface AppDeps {
  frame: DeviceFrame;
  /**
   * Opens the browser's device list and authenticates. Called inside the click, before any await:
   * WebUSB only shows its list during the click's user activation. With `authMs`, it gives up when the device hasn't
   * let this browser's key in by then.
   */
  connect(o?: { authMs?: number }): Promise<PanelShell>;
  /**
   * The device `connect` picked, again with no list, after it refused this browser's key (still starting, or Allow not
   * tapped yet): Kiln asks again every 5 s (adbAsk.ts). Without it, a refusal ends the connect.
   */
  reopen?(o?: { authMs?: number }): Promise<PanelShell>;
  /** Whether the last `connect` picked a device, so its refusal came from the device. */
  picked?(): boolean;
  sleep?: (ms: number) => Promise<void>;
  /** The APK from the frame's newest release, through Refract; without it, a file the tester picks. */
  release?: typeof fetch;
  /** How long Refract may stay silent, and how often a release download resumes (apk.ts ReleaseFetch); tests make it short. */
  releaseFetch?: ReleaseFetch;
  /** Opens Home Assistant's sign-in for set-up; the browser's own tab by default. */
  openTab?: (url: string) => Window | null;
  /**
   * The install is through: the standard flow takes the device from here (its App step first). The session lets go of
   * it, and keeps the result for Done's lines about the home screen.
   */
  handOver?(panel: PanelShell, result: Extract<Result, { flow: 'install' }>): void;
  /** Where an install or undo got to, kept across page loads (ImageDeps `progress`): `written` while it writes. */
  progress?: DeviceProgress;
}

/**
 * Whether the page is on and where its APK comes from: `?apk=release` or `?apk=local`, the release for everyone
 * once the frame is `open`, or a demo where demos run.
 */
export const deviceMode = (search: string, demosOn: boolean, demos: readonly string[], open: boolean) => apkMode(search, demosOn, demos, open);

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');

export class AppSession {
  state: AppState;
  private listeners = new Set<() => void>();
  private apk?: Uint8Array;
  private panel?: PanelShell;
  /** Picks so far: a slower read of an earlier file never replaces a later one. */
  private picks = 0;
  /** The release list and Refract, while the APK comes from the release (switchApk changes it). */
  private release?: typeof fetch;
  /** Source changes so far: a release download started before the latest one no longer counts. */
  private sources = 0;

  /** The frame this session installs. */
  get frame() { return this.d.frame; }

  constructor(private readonly d: AppDeps, on: boolean) {
    this.release = d.release;
    this.state = { screen: on ? 'pick' : 'off', phases: [], log: [], ...(d.release ? { release: { state: 'waiting' } } : {}), ...(on && d.progress?.get() ? { changed: true } : {}) };
  }

  get from(): ApkFrom { return this.release ? 'release' : 'local'; }

  /**
   * `?apk=release` or `?apk=local` on a kept session that isn't installing: the APK comes from there now, and one
   * picked or downloaded before is dropped. The session itself stays, with the image worker its ImageSession holds.
   */
  switchApk(from: ApkFrom, get: typeof fetch): void {
    if (from === this.from || this.state.screen === 'working') return;
    this.release = from === 'release' ? get : undefined;
    this.sources++;
    this.apk = undefined;
    this.picks++;
    this.set({ apk: undefined, release: this.release ? { state: 'waiting' } : undefined });
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<AppState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }
  private log = (step: string, detail?: string) => this.set({ log: [...this.state.log, step || `  ${detail}`].slice(-400) });

  /**
   * The APK file: hashed so the tester can match it to the build they meant, and its package read so
   * a file that isn't the build the frame installs is refused here. Only the latest pick counts.
   */
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
    // the frame's build, with what it needs (a home screen Android can open, its permissions): refused here, before any write
    if (!error) try { await this.d.frame.appFlow.checkApk(bytes); } catch (e) { error = (e as Error).message.replace(/\.? Nothing was changed\.$/, ''); }
    if (pick !== this.picks) return;
    this.apk = error ? undefined : bytes;
    this.set({ apk: { name, size: bytes.length, sha256, package: pkg, ...(error ? { error } : {}) } });
  }

  /**
   * The frame's newest release's APK, checked against its .sha256, then as a picked file is. Starts once, when a
   * screen first needs it; again only after no release or an error (Try again).
   */
  async loadRelease(): Promise<void> {
    const r = this.state.release;
    const get = this.release;
    if (!get || !r || !['waiting', 'none', 'error'].includes(r.state)) return;
    const source = this.sources;
    const current = () => this.sources === source;
    try {
      // only GitHub's own SHA-256 of the APK, never the .sha256 Refract serves beside it, as the wizard's (wizard/assets.ts):
      // the bytes and their checksum don't come from one place
      const { bytes, ready } = await releaseApk(releaseLine(this.d.frame), get, (release) => { if (current()) this.set({ release }); }, { ...this.d.releaseFetch, requireDigest: true });
      if (!current()) return;
      this.log(`${ready.name} from ${ready.tag}: SHA-256 ${ready.sha256}, as the release publishes it`);
      await this.pickApk(ready.name, bytes);
      // an APK the checks refuse is an error of the release, so Try again can fetch a fixed one
      const refused = this.state.apk?.error;
      if (current()) this.set({ release: refused ? { state: 'error', why: `Kiln can't install it: ${refused}` } : ready });
    } catch (e) {
      if (!current()) return;
      const why = (e as Error).message;
      if (!(e instanceof NoRelease)) this.log(`The release's APK: ${why}`);
      this.set({ release: e instanceof NoRelease ? { state: 'none' } : { state: 'error', why } });
    }
  }

  /** Why the release's APK can't go on yet, or undefined when it can (or the APK is a picked file). */
  releaseBlocked(): string | undefined {
    const r = this.state.release;
    if (!r) return undefined;
    if (this.state.apk?.error) return `The release's APK can't go on ${this.d.frame.the}: ${this.state.apk.error}.`;
    if (r.state === 'ready') return undefined;
    if (r.state === 'none') return `There is no release of Glassworks for ${this.d.frame.the} yet.`;
    if (r.state === 'error') return 'Kiln couldn\'t get Glassworks from its release. Try again.';
    return 'Kiln is still downloading Glassworks.';
  }

  /** The picked APK's bytes when it is the frame's build (the image flow builds it into the image). */
  apkBytes(): Uint8Array | undefined { return this.apk; }

  /** Must run inside the click: the browser's device list opens before its first await. */
  async connect(): Promise<void> {
    if (this.state.connecting) return;
    const { reopen, picked } = this.d;
    // inside the click: the device list opens first. A refusal is asked again, with no list and no click (adbAsk.ts)
    const asking = askAdb({ request: (o) => this.d.connect(o), ...(reopen ? { reopen: (o) => reopen.call(this.d, o) } : {}), ...(picked ? { picked: () => picked.call(this.d) } : {}) }, {
      // every try has a deadline, the first too: a claim or a key that never answers is asked again, not waited on for ever
      firstAuthMs: this.d.frame.timings.authMs, authMs: this.d.frame.timings.authMs, sleep: this.d.sleep ?? ((ms) => new Promise<void>((r) => setTimeout(r, ms))),
      onWait: (why) => this.log('', `${cap(this.d.frame.the)} didn't let this browser in yet (${why}). Asking again in 5 s.`),
    });
    this.set({ connecting: true, connectError: undefined });
    try {
      const asked = await asking;
      if (!asked.panel) throw asked.last;
      this.panel = asked.panel;
      await this.check(); // Connect stays off until the check settles: a second device can't slip in under this report
      this.set({ connecting: false });
    } catch (e) {
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  private checking?: Promise<void>;
  /** Install and undo count up: a read of the device that started before one is stale once it ends. */
  private ops = 0;

  /**
   * One read of the device at a time: repeated clicks share it. Its report lands only if no install
   * or undo started since, so a slow read never replaces a running or finished one.
   */
  private check(): Promise<void> {
    const op = this.ops;
    const panel = this.panel;
    // a read shared with repeated clicks is the read of one device: a device connected since gets a read of its own
    if (this.checking && this.checkingOn === panel) return this.checking;
    const read: Promise<void> = (async () => {
      if (!panel) throw new Error('no device connected');
      const report = await this.d.frame.appFlow.check(panel);
      if (op === this.ops && this.panel === panel) this.set({ screen: 'checks', report, error: undefined, result: undefined, phases: [] });
    })().finally(() => { if (this.checking === read) { this.checking = undefined; this.checkingOn = undefined; } });
    this.checking = read;
    this.checkingOn = panel;
    return read;
  }

  /** The device `checking` reads. */
  private checkingOn?: PanelShell;

  /** Lets go of the device: its adb connection and its USB claim (Panel.close), so Connect can open it again. */
  private drop(): void {
    const gone = this.panel as (PanelShell & { close?(): Promise<void> }) | undefined;
    this.panel = undefined;
    void gone?.close?.().catch(() => {});
  }

  /** Why Install can't start, or undefined when it can. */
  installBlocked(): string | undefined {
    const s = this.state;
    if (!s.report?.ok) return 'This device doesn\'t pass the checks.';
    if (s.apk?.error && !this.release) return `Pick another file: ${s.apk.error}.`;
    if (!this.apk) return this.releaseBlocked() ?? 'Pick the APK file first.';
    return undefined;
  }

  async install(): Promise<void> {
    if (this.installBlocked()) return;
    await this.work('install', async (onPhase) => ({
      flow: 'install',
      installed: await this.d.frame.appFlow.install(this.panel!, {
        apk: this.apk!, onStep: this.log, onPhase, sleep: this.d.sleep,
      }),
    }));
  }

  async undo(): Promise<void> {
    await this.work('undo', async (onPhase) => ({
      flow: 'undo',
      removed: await this.d.frame.appFlow.undo(this.panel!, { onStep: this.log, onPhase, sleep: this.d.sleep }),
    }));
  }

  private async work(flow: 'install' | 'undo', run: (onPhase: (p: DevicePhase) => void) => Promise<Result>): Promise<void> {
    if (!this.panel || this.state.screen === 'working') return;
    this.ops++;
    this.set({ screen: 'working', flow, phases: [], error: undefined, result: undefined });
    this.d.progress?.set({ at: 'written' });
    try {
      const result = await run((p) => this.set({ phases: [...this.state.phases, p] }));
      this.d.progress?.set(undefined);
      this.set({ changed: undefined });
      if (result.flow === 'undo') { this.set({ screen: 'done', result }); return; }
      // the install started the app: the standard flow has the device from here. This page, if it is opened again,
      // starts from Connect: it holds no device any more
      const panel = this.panel;
      this.panel = undefined;
      this.set({ screen: 'pick', report: undefined, phases: [], result });
      this.d.handOver?.(panel, result);
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message });
    }
  }

  /** From Done or an error: read the device again, or connect again if it went away. */
  async again(): Promise<void> {
    const op = this.ops;
    try { await this.check(); } catch (e) {
      if (op !== this.ops) return; // an install or undo started meanwhile: it owns the screen now
      this.drop();
      this.set({ screen: 'pick', report: undefined, flow: undefined, result: undefined, error: undefined, phases: [],
        connectError: `${cap(this.d.frame.the)} went away: ${(e as Error).message}. Connect again.` });
    }
  }
}
