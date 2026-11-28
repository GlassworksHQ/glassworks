// The standard flow's session (issue 621): everything after a frame's install, for every frame. It connects to the
// device over adb (or takes the connection the install left), then runs the standard steps on the same screens: App
// (the app's settings), Wi-Fi, Home Assistant, Panes, Radio, then Done. Which of them a frame has is its data
// (frame.ts `steps`). The wizard's install session (wizard/session.ts) is this plus its install and undo; a device frame's
// page hands its device to one of these once its own install is through. No React; the screens read the store.
import type { AdbKey, PanelShell } from '@glassworks/adb';
import { IMPROV_SOCKET, Steps, applySettings, kioskVersion } from '@glassworks/app-adb';
import { fmtSize, type AppPhase, type AppResult, type AppSettings, type Devices } from '@glassworks/kiln-core';
import { openSignInWindow } from '@glassworks/setup-channel';
import { AdbSetUp } from '../setup/adbSetUp';
import type { SetupSteps, WifiSteps } from '../setup/channelSetup';
import { explain } from '../wizard/explain';
import { AskBridge, flowUi } from '../wizard/flowUi';
import { initial, reduce, type Action, type Setup, type WizardState } from '../wizard/state';
import type { StandardFrame } from './frame';

/** A tiny store: the session writes, React reads with useSyncExternalStore. */
export class Store {
  private listeners = new Set<() => void>();
  constructor(public state: WizardState) {}
  dispatch = (a: Action) => {
    this.state = reduce(this.state, a);
    for (const l of this.listeners) l();
  };
  subscribe = (l: () => void) => {
    this.listeners.add(l);
    return () => { this.listeners.delete(l); };
  };
  get = () => this.state;
}

/** How long one of the App step's settings commands may take before it counts as stalled (`am start` included). */
export const SETTINGS_MS = 15_000;

export interface StandardDeps {
  /** How the standard flow reads the frame (frame.ts standardOf, standardOfDevice). */
  standard: StandardFrame;
  /** The device over adb: one this browser was allowed before, or the browser's list. */
  devices: Pick<Devices, 'knownAdb' | 'requestAdb'>;
  loadKey(): Promise<AdbKey>;
  sleep?: (ms: number) => Promise<void>;
  /** Opens a tab; must run inside the click (popup blockers). */
  openTab?: (url: string) => Window | null;
  /** The simulated device needs no wait for the app's socket. */
  simulated?: boolean;
  /**
   * Done's way back: the frame's own (a device frame's page goes back to its install's start). Without it, Done offers
   * none. The wizard's install session has its Undo.
   */
  wayBack?: () => void;
}

export class StandardSession implements SetupSteps, WifiSteps {
  readonly store: Store;
  readonly asks: AskBridge;
  protected readonly ui;
  protected key?: AdbKey;
  protected keyReady: Promise<AdbKey>;
  protected panel?: PanelShell;
  /** Wi-Fi, Home Assistant, Panes and the radio over adb, once the app is on the device. */
  protected readonly setUp: AdbSetUp;
  /** what the person chose on the App step, shown again if they go back to it */
  protected chosen?: AppSettings;
  /** the app is on the device: the install put it there, or `pm path` found it */
  protected hasApp = false;
  protected readonly sleep: (ms: number) => Promise<void>;
  private running?: string;
  protected began = false;
  protected closed = false;

  constructor(private readonly sd: StandardDeps, state: WizardState = initial()) {
    this.store = new Store(state);
    this.asks = new AskBridge(this.store.dispatch);
    this.ui = flowUi(this.store.dispatch, this.asks);
    this.sleep = sd.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    // Loaded up front, so a later click can call requestDevice() without waiting on IndexedDB first.
    this.keyReady = sd.loadKey().then((k) => (this.key = k));
    this.keyReady.catch(() => {});
    const f = sd.standard;
    this.setUp = new AdbSetUp({
      panel: () => this.panel,
      phase: () => this.s.setup,
      show: (setup) => this.setupPhase(setup),
      panesOffered: (offered) => this.dispatch({ type: 'panes', offered }),
      has: (step) => f.steps.includes(step),
      here: () => !this.closed && this.s.screen === 'setup',
      done: () => this.finish(),
      unreachable: (e) => this.fail(3, e ?? new Error(`${f.app} isn't responding yet. Make sure it is installed and open on the panel, then try again.`), true),
      // which Glassworks is on the device decides how a Thread router gets its network (read-only)
      app: async () => {
        const v = await kioskVersion(this.panel!, this.lid(), f.appId);
        return { version: v?.name, code: v?.code };
      },
      appName: f.app,
      wifiName: (p, signal) => f.wifiName(p, signal),
      click: (name, run) => this.click(name, run),
      note: (line) => this.ui.note(line),
      lid: (ms) => this.lid(ms),
      sleep: (ms) => this.sleep(ms),
      openTab: (url) => (sd.openTab ? sd.openTab(url) : openSignInWindow()),
      simulated: sd.simulated,
      touch: f.touch,
      appPackage: f.appId.package,
    });
  }

  /** The frame, as the standard flow reads it. */
  get standard(): StandardFrame { return this.sd.standard; }

  /**
   * Lets go of everything this session holds: the Home Assistant poll, the app's setup channel and
   * Improv client, and the device's adb connection. The registry calls it when a new session replaces
   * this one; nothing here may keep polling a device nobody looks at.
   */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.asking?.abort();
    this.setUp.close();
    void (this.panel as { close?: () => Promise<void> } | undefined)?.close?.().catch(() => {});
    this.panel = undefined;
  }

  get isClosed(): boolean { return this.closed; }

  /** A flow has begun on this session (FEL picked, or a device connected): a new one gets a new session. */
  get started(): boolean { return this.began; }

  protected get s() { return this.store.state; }
  protected dispatch(a: Action) { this.store.dispatch(a); }
  protected setupPhase(setup: Setup) { this.dispatch({ type: 'setup', setup }); }

  protected fail(at: number, e: unknown, installed = false) {
    console.error(e);
    // the set-up step that stopped: Try again goes back to it, never to an earlier one (Kiln's rule)
    this.stopped = this.s.screen === 'setup' ? this.s.setup.phase : undefined;
    const written = this.s.wrote ? 'verified' : this.s.touched ? 'maybe' : 'none';
    this.dispatch({ type: 'fail', failure: explain(e, at, written, installed, this.s.changed) });
  }

  /**
   * Every button that starts work calls through here: a second click while one action runs does
   * nothing (a double-clicked Connect used to start a second flow). `run` starts inside the click,
   * so a requestDevice() at its start still counts as part of it.
   */
  click(name: string, run: () => Promise<void> | void): Promise<void> {
    if (this.running) return Promise.resolve();
    this.running = name;
    this.dispatch({ type: 'busy', busy: name });
    let p: Promise<void>;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    return p.finally(() => {
      this.running = undefined;
      this.dispatch({ type: 'busy', busy: undefined });
    });
  }

  /**
   * A signal that cancels a read-only device command after `ms` (on this session's clock), so a wedged
   * command is stopped on the device, not only left behind. The caller falls back to the cautious answer.
   */
  protected lid(ms = 8000): AbortSignal {
    const ac = new AbortController();
    void this.sleep(ms).then(() => ac.abort(new Error('the panel did not answer in time')));
    return ac.signal;
  }

  // ---- the device: handed over by an install, or connected for a route --------------------------

  /**
   * A frame's own install is through and left the device connected with the app on it: the standard flow starts at its
   * first step. `changed`: the install wrote to the device, so a later failure must not say nothing was.
   */
  handOver(panel: PanelShell): Promise<void> {
    this.began = true;
    this.panel = panel;
    this.hasApp = true;
    this.dispatch({ type: 'changed', by: 'written' });
    this.dispatch({ type: 'go', screen: 'setup', flow: 'install' });
    return this.first();
  }

  /** The standard flow's first step on a device with the app on it: App when the frame has it, else the next it has. */
  protected async first(): Promise<void> {
    if (this.standard.steps.includes('set-up')) { this.setupPhase({ phase: 'settings', update: this.hasApp, ...this.applyMark(), chosen: this.chosen }); return; }
    await this.setUp.click('wifi', () => this.afterApp());
  }

  /** The step after App: Wi-Fi when the frame has it, else Home Assistant's, which passes over any it leaves out. */
  protected afterApp(): Promise<void> {
    return this.standard.steps.includes('wifi') ? this.setUp.wifi() : this.setUp.homeAssistant();
  }

  /** The App step applies settings to the app already on (true), or installs the app with them (the wizard's install session). */
  protected applies(): boolean { return true; }
  /** `apply` on the App step's state only when it applies: an install session's state stays as it was. */
  private applyMark(): { apply?: true } { return this.applies() ? { apply: true } : {}; }

  /** Connects to an installed, running device over adb; asks for a click if this browser hasn't been allowed. */
  async connectPanel(): Promise<boolean> {
    // Try again from an error: a device that went away (unplugged, restarted) is connected again, not used dead
    if (this.panel && this.s.screen === 'error' && !(await this.alive())) this.dropPanel();
    if (this.panel) return true;
    this.began = true;
    this.dispatch({ type: 'go', screen: 'setup', flow: 'install' });
    this.setupPhase({ phase: 'waiting', note: `Connecting to ${this.standard.the}. It should be running and plugged in.` });
    // the browser's list, cancelled when the session closes (the registry replaced it): nothing waits for a click then
    const asking = new AbortController();
    this.asking = asking;
    try {
      const key = await this.keyReady;
      if (this.closed) return false;
      const p = (await this.sd.devices.knownAdb(key).catch(() => undefined))
        ?? (this.closed ? undefined : await this.asks.open('connect', () => this.sd.devices.requestAdb(key), asking.signal));
      // closed meanwhile: a connection that came late is let go, not kept by a session nobody looks at
      if (this.closed) { void (p as { close?: () => Promise<void> } | undefined)?.close?.().catch(() => {}); return false; }
      this.panel = p;
      return true;
    } catch (e) {
      if (!this.closed) this.fail(3, e, true);
      return false;
    } finally {
      if (this.asking === asking) this.asking = undefined;
    }
  }

  /** The browser's list connectPanel opened, while it waits: close() calls it off. */
  private asking?: AbortController;

  /** The standard flow's routes: /set-up (and its old alias), /set-up/wifi, /home-assistant, /panes, /radio. */
  async route(r: string): Promise<void> {
    switch (r) {
      case 'resume': if (await this.connectPanel()) await this.resume(); return;
      case 'wifi': if (await this.connectPanel()) await this.afterApp(); return;
      case 'home-assistant': if (await this.connectPanel()) await this.homeAssistant(); return;
      case 'panes': if (await this.connectPanel()) await this.panes(); return;
      case 'radio': if (await this.connectPanel()) await this.radio(); return;
    }
  }

  /** The set-up step a failure stopped at (`fail`): Try again (`/set-up`) goes back to it. */
  private stopped?: Setup['phase'];

  /** `/set-up` on a connected device: the step that stopped, if one did; else its App step, or the next it has. */
  protected async resume(): Promise<void> {
    const at = this.stopped;
    this.stopped = undefined;
    // back on set-up's screen first: its channel steps show only there
    if (at && at !== 'settings' && at !== 'waiting') this.setupPhase({ phase: 'waiting', note: `Connecting to ${this.standard.the}. It should be running and plugged in.` });
    if (at === 'wifi') return this.wifi();
    if (at === 'ha') return this.homeAssistant();
    if (at === 'panes') return this.panes();
    if (at === 'radio' || at === 'radio-next') return this.radio();
    if (!this.standard.steps.includes('set-up')) { await this.afterApp(); return; }
    this.setupPhase({ phase: 'settings', update: await this.appInstalled(), ...this.applyMark(), chosen: this.chosen });
  }

  /**
   * Whether the app is on the device (an update, not a first install): known once it went in, else asked
   * (read-only). No answer counts as a first install.
   */
  protected async appInstalled(): Promise<boolean> {
    this.hasApp ||= (await this.panel!.run(`pm path ${this.standard.appId.package}`, this.lid()).catch(() => ({ out: '' }))).out.includes('package:');
    return this.hasApp;
  }

  /** The settings form's values as they change: what going back to the step shows. */
  keepSettings(opts: AppSettings): void { this.chosen = opts; }

  /**
   * The App step's work, reporting its phases. Here, on a frame whose install put the app on: the settings, applied to
   * it over adb (no reinstall). The wizard's install session installs the app with them instead.
   */
  protected async appStep(opts: AppSettings, at: (p: AppPhase) => void): Promise<AppResult> {
    at({ step: 'prefs' });
    // each command on a lid (the stall watchdog): a device that stops answering ends in an error with Try again
    const p = this.panel!;
    const bounded: PanelShell = { ...p, run: (cmd, signal) => p.run(cmd, signal ?? this.lid(SETTINGS_MS)) };
    // with this computer's time: a device that never reached a time server gets its clock from here (applySettings)
    await applySettings(new Steps(bounded, (step, detail) => this.ui.note(step || `  ${detail}`)), { ...opts, now: Date.now() }, this.standard.appId);
    at({ step: 'start' });
    return {};
  }

  /** The App step's button: the settings (and, where the App step installs it, the app), then Wi-Fi. */
  async installApp(opts: AppSettings): Promise<void> {
    this.chosen = opts;
    const seen: AppPhase[] = [];
    const apply = this.applyMark();
    let update = this.s.setup.phase === 'settings' && this.s.setup.update;
    const at = (p?: AppPhase) => {
      if (p) seen.push(p);
      this.setupPhase({ phase: 'settings', update, ...apply, chosen: opts, installing: [...seen] });
    };
    at(); // an APK downloads before the first phase
    try {
      // an update replaces a running app, which Android restarts; a first install has none running
      update = await this.appInstalled();
      at();
      const res = await this.appStep(opts, at);
      this.hasApp = true;
      this.setUp.ip = res.ip;
      const sw = res.switched;
      this.setUp.wifiHint = sw?.wifi === 'lost'
        ? `Switching versions made the panel forget ${sw.ssid ? `"${sw.ssid}"` : 'its Wi-Fi'}. Pick it again and type its password.`
        : undefined;
      // the app's start: done when it listens again (the Wi-Fi step would wait for it too)
      if (seen.at(-1)?.step !== 'start') at({ step: 'start' });
      if (!this.standard.steps.includes('wifi')) { await this.afterApp(); return; }
      if (!(await this.setUp.waitForSocket(IMPROV_SOCKET))) {
        this.setupPhase({ phase: 'wifi', networks: [], unreachable: true, status: `Couldn't reach ${this.standard.app} on the panel.` });
        return;
      }
      await this.setUp.wifi();
    } catch (e) {
      this.setupPhase({ phase: 'settings', update, ...apply, chosen: opts, error: (e as Error).message });
    }
  }

  // ---- set-up over adb (src/setup/adbSetUp.ts): Wi-Fi, then Home Assistant, Panes and the radio ----

  /** The app's setup channel (the tests reach it here). */
  protected get steps() { return this.setUp.steps; }
  /** The device has a touchscreen: the steps offer "do it on the panel" only then. */
  get touch(): boolean { return this.standard.touch; }
  /** The device as the steps name it (its frame's `the`). */
  get the(): string { return this.standard.the; }
  /** The Wi-Fi step. A device already on Wi-Fi says so first, with changing it as the other way. */
  async wifi(): Promise<void> {
    // Try again after the app couldn't be reached: a device that went off USB is connected again first, so a frame with
    // no touch (nothing to do on it) always has a way on from Kiln
    const s = this.s.setup;
    if (this.panel && s.phase === 'wifi' && s.unreachable && !(await this.alive())) this.dropPanel();
    if (!this.panel && !(await this.connectPanel())) return;
    return this.setUp.wifi();
  }

  /** Whether the device still answers over adb (one bounded read). */
  private alive(): Promise<boolean> {
    return this.panel!.run('true', this.lid(5000)).then(() => true, () => false);
  }

  /** Lets go of a device that went away: set-up's channels and the adb connection, so the next connect opens it fresh. */
  private dropPanel(): void {
    this.setUp.close();
    void (this.panel as { close?: () => Promise<void> } | undefined)?.close?.().catch(() => {});
    this.panel = undefined;
  }
  scanWifi(): Promise<void> { return this.setUp.scanWifi(); }
  joinWifi(ssid: string, psk: string): Promise<void> { return this.setUp.joinWifi(ssid, psk); }
  get panesDraft(): Record<string, boolean> | undefined { return this.setUp.panesDraft; }
  set panesDraft(v: Record<string, boolean> | undefined) { this.setUp.panesDraft = v; }
  get haAddress(): string | undefined { return this.setUp.haAddress; }
  set haAddress(v: string | undefined) { this.setUp.haAddress = v; }
  homeAssistant(): Promise<void> { return this.setUp.homeAssistant(); }
  signIn(url: string): void { this.setUp.signIn(url); }
  saveHa(url: string, dashboard?: string): Promise<void> { return this.setUp.saveHa(url, dashboard); }
  skipHa(url: string): Promise<void> { return this.setUp.skipHa(url); }
  panes(): Promise<void> { return this.setUp.panes(); }
  applyPanes(on: readonly string[]): Promise<void> { return this.setUp.applyPanes(on); }
  radio(): Promise<void> { return this.setUp.radio(); }
  applyRadio(mode: string): Promise<void> { return this.setUp.applyRadio(mode); }

  /** Done, with the backup to download when this tab's run saved one. */
  finish(): void {
    const run = ++this.doneRuns;
    const ip = this.steps.ip ?? this.setUp.ip;
    const b = this.s.backup;
    const wifi = this.setUp.wifiSsid;
    const the = this.standard.the;
    const The = the.charAt(0).toUpperCase() + the.slice(1);
    const total = b ? b.files.reduce((n, f) => n + f.size, 0) : 0;
    this.dispatch({
      type: 'done', done: {
        title: 'You\'re all set',
        body: wifi
          ? `${The} is on ${wifi}. Home Assistant finds it by itself.`
          // without touch nothing is done on the device itself: set-up's Wi-Fi step has no skip there
          : !this.standard.touch && ip ? `${The} is on Wi-Fi at ${ip}. Home Assistant finds it by itself.`
          : `On ${the}, join Wi-Fi and sign in to Home Assistant. Home Assistant then finds it${ip ? ` (${ip}, port 6053)` : ''}.`,
        backup: b && {
          name: `${b.id}.zip`,
          info: `${b.files.length} partitions · ${fmtSize(total)}`,
          hint: b.bootWasPatched
            ? 'This panel already had modified software, so this backup isn\'t the factory original. Keep the backup from your first install.'
            : 'Your original software is backed up. Save a copy somewhere safe in case you ever want to restore it.',
        },
      },
    });
    void this.readUpdates(run);
    void this.handOff(ip, run);
  }

  /** Each Done counts up: a read or a pairing that an earlier Done started lands on that Done only. */
  private doneRuns = 0;
  private current(run: number): boolean { return run === this.doneRuns && !this.closed && this.s.screen === 'done'; }

  /** Done's Silent updates block (issue 422): read back from the device, whatever route led to Done. */
  private async readUpdates(run: number): Promise<void> {
    const p = this.panel;
    if (!p) return;
    const u = await this.standard.updates(p, this.lid());
    this.ui.note(u.log);
    const d = this.s.done;
    if (!d || !this.current(run)) return;
    this.dispatch({ type: 'done', done: { ...d, updates: this.standard.silent[u.state] } });
  }

  /**
   * The hand-off to the device's own setup page (#34): a page on https can't reach the device's http, so Done gives
   * its address. Only for an app that has the page, which `pairing.arm` shows: it answers, and opens pairing for 30
   * minutes, so the page pairs even once Home Assistant has the device.
   */
  private async handOff(ip: string | undefined, run: number): Promise<void> {
    // The link is the address in the device's reply, not the one Done had: it can change in between (DHCP).
    const now = ip ? (await this.steps.armPairing())?.ip : undefined;
    // only the Done still current, and still on screen, lets go of the channel: a later set-up may be using it
    if (run !== this.doneRuns || this.s.screen !== 'done') return;
    this.steps.close();
    const d = this.s.done;
    if (!now || !d || !this.current(run)) return;
    this.dispatch({ type: 'done', done: { ...d, managePage: `http://${now}:8099/` } });
  }

  /** Done's download: only the wizard's install session has backups. */
  async downloadBackup(): Promise<void> {}

  /** Done and the error screen offer a way back: the frame's own here, when it has one (a device frame's page). */
  get canUndo(): boolean { return !!this.sd.wayBack; }
  /** The way back, from Done or an error. */
  undo(): Promise<void> { this.sd.wayBack?.(); return Promise.resolve(); }
}
