// The Mirror's test install, outside React: an APK file from this computer, the Mirror over WebUSB
// adb, its read-only checks, then install or undo (@glassworks/lululemon-mirror-installer). Test only:
// it exists behind ?apk=local because there is no release of S1 Kiosk's legacy build, and there
// must not be one yet (issue 198). The release-asset route stays for releases only.
import type { PanelShell } from '@glassworks/adb';
import { PKG, apkPackage } from '@glassworks/app-adb';
import {
  checkMirror, installMirror, undoMirror, type Installed, type MirrorPhase, type MirrorReport, type Removed,
} from '@glassworks/lululemon-mirror-installer';

export type MirrorScreen = 'off' | 'pick' | 'checks' | 'working' | 'done' | 'error';

/** The APK the tester picked: what the screen shows of it. The bytes stay in the session. */
export interface PickedApk { name: string; size: number; sha256: string; package?: string; error?: string }

export type Result = { flow: 'install'; installed: Installed } | { flow: 'undo'; removed: Removed };

export interface MirrorState {
  screen: MirrorScreen;
  apk?: PickedApk;
  haUrl: string;
  /** the browser's device list or the Mirror's "Allow USB debugging?" is open */
  connecting?: boolean;
  connectError?: string;
  report?: MirrorReport;
  flow?: 'install' | 'undo';
  /** the recipe's phases so far, the last one now */
  phases: MirrorPhase[];
  result?: Result;
  error?: string;
  log: string[];
}

export interface MirrorDeps {
  /**
   * Opens the browser's device list and authenticates. Called inside the click, before any await:
   * WebUSB only shows its list during the click's user activation.
   */
  connect(): Promise<PanelShell>;
  sleep?: (ms: number) => Promise<void>;
  homeTimeoutMs?: number;
}

/** Whether the page is on, from its query: `?apk=local`, or a demo where demos run. */
export function mirrorMode(search: string, demosOn: boolean, demos: readonly string[]): { on: boolean; demo?: string } {
  const q = new URLSearchParams(search);
  const demo = q.get('demo');
  if (demosOn && demo && demos.includes(demo)) return { on: true, demo };
  return { on: q.get('apk') === 'local' };
}

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');

export class MirrorSession {
  state: MirrorState;
  private listeners = new Set<() => void>();
  private apk?: Uint8Array;
  private panel?: PanelShell;
  /** Picks so far: a slower read of an earlier file never replaces a later one. */
  private picks = 0;

  constructor(private readonly d: MirrorDeps, on: boolean) {
    this.state = { screen: on ? 'pick' : 'off', haUrl: '', phases: [], log: [] };
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<MirrorState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }
  private log = (step: string, detail?: string) => this.set({ log: [...this.state.log, step || `  ${detail}`].slice(-400) });

  /**
   * The APK file: hashed so the tester can match it to the build they meant, and its package read so
   * a file that isn't S1 Kiosk is refused here. Only the latest pick counts.
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
    if (pick !== this.picks) return;
    if (!error && pkg !== PKG) error = `this APK is ${pkg}, not S1 Kiosk (${PKG})`;
    this.apk = error ? undefined : bytes;
    this.set({ apk: { name, size: bytes.length, sha256, package: pkg, ...(error ? { error } : {}) } });
  }

  setHaUrl(haUrl: string) { this.set({ haUrl }); }

  /** The picked APK's bytes when it is S1 Kiosk (the flash path builds it into the image). */
  apkBytes(): Uint8Array | undefined { return this.apk; }

  /** Must run inside the click: the browser's device list opens before its first await. */
  async connect(): Promise<void> {
    this.set({ connecting: true, connectError: undefined });
    try {
      this.panel = await this.d.connect();
      this.set({ connecting: false });
      await this.check();
    } catch (e) {
      this.set({ connecting: false, connectError: (e as Error).message });
    }
  }

  private checking?: Promise<void>;
  /** Install and undo count up: a read of the Mirror that started before one is stale once it ends. */
  private ops = 0;

  /**
   * One read of the Mirror at a time: repeated clicks share it. Its report lands only if no install
   * or undo started since, so a slow read never replaces a running or finished one.
   */
  private check(): Promise<void> {
    const op = this.ops;
    this.checking ??= (async () => {
      try {
        const report = await checkMirror(this.panel!);
        if (op === this.ops) this.set({ screen: 'checks', report, error: undefined, result: undefined, phases: [] });
      } finally {
        this.checking = undefined;
      }
    })();
    return this.checking;
  }

  /** Why Install can't start, or undefined when it can. */
  installBlocked(): string | undefined {
    const s = this.state;
    if (!s.report?.ok) return 'This device doesn\'t pass the checks.';
    if (s.apk?.error) return `Pick another file: ${s.apk.error}.`;
    if (!this.apk) return 'Pick the APK file first.';
    if (s.haUrl && !/^https?:\/\/[^\s/]+/.test(s.haUrl)) return 'Home Assistant\'s address starts with http:// or https://.';
    return undefined;
  }

  async install(): Promise<void> {
    if (this.installBlocked()) return;
    await this.work('install', async (onPhase) => ({
      flow: 'install',
      installed: await installMirror(this.panel!, {
        apk: this.apk!, haUrl: this.state.haUrl || undefined, onStep: this.log, onPhase, sleep: this.d.sleep, homeTimeoutMs: this.d.homeTimeoutMs,
      }),
    }));
  }

  async undo(): Promise<void> {
    await this.work('undo', async (onPhase) => ({
      flow: 'undo',
      removed: await undoMirror(this.panel!, { onStep: this.log, onPhase, sleep: this.d.sleep, homeTimeoutMs: this.d.homeTimeoutMs }),
    }));
  }

  private async work(flow: 'install' | 'undo', run: (onPhase: (p: MirrorPhase) => void) => Promise<Result>): Promise<void> {
    if (!this.panel || this.state.screen === 'working') return;
    this.ops++;
    this.set({ screen: 'working', flow, phases: [], error: undefined, result: undefined });
    try {
      const result = await run((p) => this.set({ phases: [...this.state.phases, p] }));
      this.set({ screen: 'done', result });
    } catch (e) {
      this.log('Stopped', (e as Error).message);
      this.set({ screen: 'error', error: (e as Error).message });
    }
  }

  /** From Done or an error: read the Mirror again, or connect again if it went away. */
  async again(): Promise<void> {
    const op = this.ops;
    try { await this.check(); } catch (e) {
      if (op !== this.ops) return; // an install or undo started meanwhile: it owns the screen now
      this.panel = undefined;
      this.set({ screen: 'pick', report: undefined, flow: undefined, result: undefined, error: undefined, phases: [],
        connectError: `The Mirror went away: ${(e as Error).message}. Connect again.` });
    }
  }
}
