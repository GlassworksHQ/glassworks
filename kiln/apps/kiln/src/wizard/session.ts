// One install or undo, from the first click to Done: kiln-core's InstallFlow with a frame's recipe,
// then the app's setup over adb. All hardware work is here; screens only read the store and call
// these methods. Port of kiln/src/app/ui.ts without the DOM.
import type { AdbKey, PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import { IMPROV_SOCKET, kioskVersion } from '@glassworks/app-adb';
import type { FelDevice } from '@glassworks/fel';
import {
  BackupRefused, InstallFlow, backupZip, checkRestorable, fmtSize, readBackupZip, restoreFromBackup,
  type BackupFile, type BackupMeta, type BackupStore, type Devices, type Recipe,
} from '@glassworks/kiln-core';
import { openSignInWindow } from '@glassworks/setup-channel';
import { AdbSetUp } from '../setup/adbSetUp';
import type { SetupSteps } from '../setup/channelSetup';
import { explain } from './explain';
import { AskBridge, flowUi } from './flowUi';
import type { AppPhase, AppResult, AppSettings, KilnFrame } from '@glassworks/kiln-core';
import { initial, reduce, type Action, type Flow, type Setup, type WizardState } from './state';

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

export interface SessionDeps {
  recipe: Recipe<string, unknown, AppResult>;
  frame: KilnFrame;
  devices: Devices;
  /** Must call navigator.usb.requestDevice() before its first await. */
  requestFel(): Promise<FelDevice>;
  asset(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: string): Promise<boolean>;
  backups: Pick<BackupStore, 'save' | 'original' | 'files'>;
  loadKey(): Promise<AdbKey>;
  sleep?: (ms: number) => Promise<void>;
  /** Opens a tab; must run inside the click (popup blockers). */
  openTab?: (url: string) => Window | null;
  confirm?: (text: string) => boolean;
  download?: (name: string, blob: Blob) => void;
  /** The simulated panel needs no wait for the app's socket. */
  simulated?: boolean;
}


const notPicked = (e: unknown) => e instanceof DOMException && e.name === 'NotFoundError';

export class Session implements SetupSteps {
  readonly store: Store;
  readonly asks: AskBridge;
  private readonly ui;
  private key?: AdbKey;
  private keyReady: Promise<AdbKey>;
  private flow?: InstallFlow<string, unknown, AppResult>;
  private result?: { meta: BackupMeta; files: BackupFile[] };
  private panel?: PanelShell;
  /** Wi-Fi, Home Assistant and the radio over adb, once the app is on the panel. */
  private readonly setUp: AdbSetUp;
  private undoFiles?: Map<string, Uint8Array>;
  /** The backup in this browser, when Undo uses it: Undo offers it as a download. */
  private undoBackup?: { meta: BackupMeta; files: BackupFile[] };
  /** what the person chose on the settings step, shown again if they go back to it */
  private chosen?: AppSettings;
  /** the app is on the panel: provision put it there, or `pm path` found it */
  private hasApp = false;
  private readonly sleep: (ms: number) => Promise<void>;
  private running?: string;
  private began = false;
  private closed = false;

  /**
   * Lets go of everything this session holds: the Home Assistant poll, the app's setup channel and
   * Improv client, and the panel's adb connection. The registry calls it when a new session replaces
   * this one; nothing here may keep polling a panel nobody looks at.
   */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.setUp.close();
    void (this.panel as { close?: () => Promise<void> } | undefined)?.close?.().catch(() => {});
    this.panel = undefined;
  }

  get isClosed(): boolean { return this.closed; }

  /** A flow has begun on this session (FEL picked, or a panel connected): a new one gets a new session. */
  get started(): boolean { return this.began; }

  constructor(private readonly d: SessionDeps, state: WizardState = initial()) {
    this.store = new Store(state);
    this.asks = new AskBridge(this.store.dispatch);
    this.ui = flowUi(this.store.dispatch, this.asks);
    this.sleep = d.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    // Loaded up front, so a later click can call requestDevice() without waiting on IndexedDB first.
    this.keyReady = d.loadKey().then((k) => (this.key = k));
    this.keyReady.catch(() => {});
    this.setUp = new AdbSetUp({
      panel: () => this.panel,
      phase: () => this.s.setup,
      show: (setup) => this.setupPhase(setup),
      here: () => !this.closed && this.s.screen === 'setup',
      done: () => this.finish(),
      unreachable: (e) => this.fail(3, e ?? new Error(`${this.d.frame.app} isn't answering. Is it installed and running?`), true),
      // which Glassworks is on the panel decides how a Thread router gets its network (read-only)
      app: async () => {
        const v = await kioskVersion(this.panel!, this.lid(), this.d.frame.appId);
        return { version: v?.name, code: v?.code };
      },
      appName: this.d.frame.app,
      wifiName: (p, signal) => this.d.frame.wifiName(p, signal),
      click: (name, run) => this.click(name, run),
      note: (line) => this.ui.note(line),
      lid: (ms) => this.lid(ms),
      sleep: (ms) => this.sleep(ms),
      openTab: (url) => (this.d.openTab ? this.d.openTab(url) : openSignInWindow()),
      simulated: d.simulated,
    });
  }

  private get s() { return this.store.state; }
  private dispatch(a: Action) { this.store.dispatch(a); }
  private setupPhase(setup: Setup) { this.dispatch({ type: 'setup', setup }); }

  private getFlow(key: AdbKey) {
    this.flow ??= new InstallFlow<string, unknown, AppResult>(this.d.recipe, {
      asset: (n, p) => this.d.asset(n, p),
      hasAsset: (n) => this.d.hasAsset(n),
      backups: {
        // safe in this browser from here on: the tab's record, so any later Done offers it
        save: async (meta, files) => {
          await this.d.backups.save(meta, files);
          const { serial: _, ...backup } = meta;
          this.dispatch({ type: 'backup', backup });
        },
        original: () => this.d.backups.original(),
      },
      key,
      devices: this.d.devices,
      sleep: this.d.sleep,
    }, this.ui);
    return this.flow;
  }

  private fail(at: number, e: unknown, installed = false) {
    console.error(e);
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

  // ---- connect, install, restore --------------------------------------------------------------

  /** The Connect button. The browser's picker opens first, inside the click. */
  connectFel(): Promise<void> {
    const picked = this.d.requestFel();
    return this.afterFel(picked);
  }

  private async afterFel(picked: Promise<FelDevice>) {
    let fel: FelDevice;
    try {
      fel = await picked;
    } catch (e) {
      this.fail(1, e);
      return;
    }
    const flowKind: Flow = this.s.flow;
    this.began = true;
    this.dispatch({ type: 'start', flow: flowKind, now: Date.now() });
    try {
      const flow = this.getFlow(await this.keyReady);
      await flow.bootFromFel(fel);
      const fb = await flow.connectFastboot();
      if (flowKind === 'undo') return await this.restore(fb);
      this.result = await flow.install(fb);
      void this.waitForPanel();
    } catch (e) {
      this.fail(2, e);
    }
  }

  private async waitForPanel() {
    this.setupPhase({ phase: 'waiting', note: 'The panel is starting its new software. The first start takes a minute or two.' });
    try {
      this.panel = await this.flow!.connectAdb();
      this.setupPhase({ phase: 'settings', update: false });
    } catch (e) {
      this.fail(3, e, true);
    }
  }

  private async restore(fb: Fastboot) {
    const files = this.undoFiles!;
    const parts = this.d.recipe.backup.restore.filter((p) => files.has(p));
    const total = parts.reduce((n, p) => n + files.get(p)!.length, 0);
    const before = (part: string) => parts.slice(0, parts.indexOf(part)).reduce((n, p) => n + files.get(p)!.length, 0);
    await restoreFromBackup(this.d.recipe.backup, fb, files, (l) => this.ui.note(l), (part, phase, done, t) => {
      const at = before(part);
      const write = (at + (phase === 'write' ? done : t)) / total;
      const verify = (at + (phase === 'verify' ? done : 0)) / total;
      this.ui.progress({
        overall: 0.05 + 0.93 * (write + verify) / 2, phase: phase === 'write' ? 'flash' : 'verify',
        phasePct: phase === 'write' ? write : verify, title: 'Putting the original software back',
        sub: phase === 'write' ? `Writing ${part}` : `Reading ${part} back to check it`, bars: { flash: write, verify },
      });
    });
    this.ui.progress({ overall: 0.98, phase: 'verify', phasePct: 1, title: 'Restarting the panel', sub: '' });
    this.result = undefined;
    this.dispatch({
      type: 'done', done: {
        title: 'The original software is back.',
        body: this.s.undo.depth === 'keep'
          ? this.d.frame.undo.keep.restored
          : this.d.frame.undo.factory.restored,
      },
    });
  }

  // ---- an installed panel: routes and the app's setup ------------------------------------------

  /** Connects to an installed, running panel over adb; asks for a click if this browser hasn't been allowed. */
  async connectPanel(): Promise<boolean> {
    if (this.panel) return true;
    this.began = true;
    this.dispatch({ type: 'go', screen: 'setup', flow: 'install' });
    this.setupPhase({ phase: 'waiting', note: 'Connecting to the panel. It should be running and plugged in.' });
    try {
      const key = await this.keyReady;
      this.panel = (await this.d.devices.knownAdb(key).catch(() => undefined))
        ?? (await this.asks.open('connect', () => this.d.devices.requestAdb(key), new AbortController().signal));
      return true;
    } catch (e) {
      this.fail(3, e, true);
      return false;
    }
  }

  /** #/resume, #/wifi, #/home-assistant, #/radio, #/undo, #/install. */
  async route(r: string): Promise<void> {
    switch (r) {
      case 'install': this.dispatch({ type: 'go', screen: 'connect', flow: 'install' }); return;
      case 'undo': return this.openUndo();
      case 'resume': if (await this.connectPanel()) this.setupPhase({ phase: 'settings', update: await this.appInstalled(), chosen: this.chosen }); return;
      case 'wifi': if (await this.connectPanel()) await this.wifi(); return;
      case 'home-assistant': if (await this.connectPanel()) await this.homeAssistant(); return;
      case 'radio': if (await this.connectPanel()) await this.radio(); return;
    }
  }

  /**
   * A signal that cancels a read-only panel command after `ms` (on this session's clock), so a wedged
   * command is stopped on the panel, not only left behind. The caller falls back to the cautious answer.
   */
  private lid(ms = 8000): AbortSignal {
    const ac = new AbortController();
    void this.sleep(ms).then(() => ac.abort(new Error('the panel did not answer in time')));
    return ac.signal;
  }

  /**
   * Whether the app is on the panel (an update, not a first install): known once it went in, else asked
   * (read-only). No answer counts as a first install.
   */
  private async appInstalled(): Promise<boolean> {
    this.hasApp ||= (await this.panel!.run(`pm path ${this.d.frame.appId.package}`, this.lid()).catch(() => ({ out: '' }))).out.includes('package:');
    return this.hasApp;
  }

  /** The settings form's values as they change: what going back to the step shows. */
  keepSettings(opts: AppSettings): void { this.chosen = opts; }

  /** Settings → install (or update) the app. */
  async installApp(opts: AppSettings): Promise<void> {
    this.chosen = opts;
    const seen: AppPhase[] = [];
    let update = this.s.setup.phase === 'settings' && this.s.setup.update;
    const at = (p?: AppPhase) => {
      if (p) seen.push(p);
      this.setupPhase({ phase: 'settings', update, chosen: opts, installing: [...seen] });
    };
    at(); // the APK downloads before provision's first phase
    try {
      // an update replaces a running app, which Android restarts; a first install has none running
      update = await this.appInstalled();
      at();
      const res = await this.getFlow(await this.keyReady).setUpApp(this.panel!, this.d.frame.appOptions(opts, (q) => (this.d.confirm ?? window.confirm)(q), at));
      this.hasApp = true;
      const sw = res.switched;
      this.setUp.ip = res.ip;
      this.setUp.wifiHint = sw?.wifi === 'lost'
        ? `Switching versions made the panel forget ${sw.ssid ? `"${sw.ssid}"` : 'its Wi-Fi'}. Pick it again and type its password.`
        : undefined;
      // the first start: done when the app listens again (the Wi-Fi step would wait for it too)
      if (seen.at(-1)?.step !== 'start') at({ step: 'start' });
      if (!(await this.setUp.waitForSocket(IMPROV_SOCKET))) {
        this.setupPhase({ phase: 'wifi', networks: [], unreachable: true, status: `Couldn't reach ${this.d.frame.app} on the panel.` });
        return;
      }
      await this.setUp.wifi();
    } catch (e) {
      this.setupPhase({ phase: 'settings', update, chosen: opts, error: (e as Error).message });
    }
  }

  // ---- set-up over adb (src/setup/adbSetUp.ts): Wi-Fi, then Home Assistant and the radio --------

  /** The app's setup channel (the tests reach it here). */
  private get steps() { return this.setUp.steps; }
  /** The Wi-Fi step. A panel already on Wi-Fi says so first, with changing it as the other way. */
  wifi(): Promise<void> { return this.setUp.wifi(); }
  scanWifi(): Promise<void> { return this.setUp.scanWifi(); }
  joinWifi(ssid: string, psk: string): Promise<void> { return this.setUp.joinWifi(ssid, psk); }
  get haAddress(): string | undefined { return this.setUp.haAddress; }
  set haAddress(v: string | undefined) { this.setUp.haAddress = v; }
  homeAssistant(): Promise<void> { return this.setUp.homeAssistant(); }
  signIn(url: string): void { this.setUp.signIn(url); }
  saveHa(url: string): Promise<void> { return this.setUp.saveHa(url); }
  skipHa(url: string): Promise<void> { return this.setUp.skipHa(url); }
  radio(): Promise<void> { return this.setUp.radio(); }
  applyRadio(mode: string): Promise<void> { return this.setUp.applyRadio(mode); }

  /** Done, with the backup to download. */
  finish(): void {
    const ip = this.steps.ip ?? this.setUp.ip;
    const b = this.s.backup;
    const wifi = this.setUp.wifiSsid;
    const total = b ? b.files.reduce((n, f) => n + f.size, 0) : 0;
    this.dispatch({
      type: 'done', done: {
        title: 'Done.',
        body: wifi
          ? `The panel is on ${wifi}. Home Assistant finds it by itself.`
          : `On the panel, join Wi-Fi and sign in to Home Assistant. Home Assistant then finds it${ip ? ` (${ip}, port 6053)` : ''}.`,
        backup: b && {
          name: `${b.id}.zip`,
          info: `${b.files.length} partitions · ${fmtSize(total)}`,
          hint: b.bootWasPatched
            ? 'This panel already had the software, so this backup isn\'t the original. Keep the one from your first install.'
            : 'Original software, backed up. Keep it.',
        },
      },
    });
    void this.readUpdates();
    void this.handOff(ip);
  }

  /** Done's Silent updates block (issue 422): the root update service read back from the panel, whatever route led to Done. */
  private async readUpdates(): Promise<void> {
    const p = this.panel;
    if (!p) return;
    const u = await this.d.frame.updates(p, this.lid());
    this.ui.note(u.log);
    const d = this.s.done;
    if (!d || this.closed || this.s.screen !== 'done') return;
    this.dispatch({ type: 'done', done: { ...d, updates: this.d.frame.silent[u.state] } });
  }

  /**
   * The hand-off to the panel's own setup page (#34): a page on https can't reach the panel's http, so Done gives
   * its address. Only for an app that has the page, which `pairing.arm` shows: it answers, and opens pairing for 30
   * minutes, so the page pairs even once Home Assistant has the panel.
   */
  private async handOff(ip: string | undefined): Promise<void> {
    // The link is the address in the panel's reply, not the one Done had: it can change in between (DHCP).
    const now = ip ? (await this.steps.armPairing())?.ip : undefined;
    this.steps.close();
    const d = this.s.done;
    if (!now || !d || this.closed || this.s.screen !== 'done') return;
    this.dispatch({ type: 'done', done: { ...d, setupPage: `http://${now}:8099/setup` } });
  }

  /** Done's download: the files from this session's run, or from this browser's store for an earlier session's. */
  async downloadBackup(): Promise<void> {
    const b = this.s.backup;
    const shown = this.s.done;
    if (!b) return;
    try {
      const files = this.result?.meta.id === b.id ? this.result.files : await this.d.backups.files(b);
      (this.d.download ?? saveBlob)(`${b.id}.zip`, backupZip(b, files));
    } catch (e) {
      // Only on the Done that asked, by its backup: after Undo or a new run, a late error must not bring it back.
      // Done itself can be redrawn meanwhile (the hand-off adds the setup page's link), so the error goes into it as it is now.
      const cur = this.s.done;
      if (this.closed || this.s.screen !== 'done' || !cur?.backup || cur.backup.name !== shown?.backup?.name) return;
      this.dispatch({ type: 'done', done: { ...cur, backup: { ...cur.backup, error: `Couldn't read it from this browser: ${(e as Error).message}` } } });
    }
  }

  // ---- undo -----------------------------------------------------------------------------------

  async openUndo(): Promise<void> {
    if (this.s.undo.locked) {
      // the apps were given back against the backup in use: keep it, and keep its label
      this.dispatch({ type: 'go', screen: 'undo', flow: 'undo' });
      this.dispatch({ type: 'undo', patch: { checking: false } });
      return;
    }
    const mine = ++this.choice;
    this.undoFiles = undefined;
    this.undoBackup = undefined;
    this.dispatch({ type: 'go', screen: 'undo', flow: 'undo' });
    this.dispatch({ type: 'undo', patch: { source: undefined, inBrowser: undefined, file: undefined, ready: false, checking: false, appsBack: false, note: undefined } });
    let orig: BackupMeta | undefined;
    try { orig = await this.d.backups.original(); } catch { /* no IndexedDB */ }
    if (!orig || mine !== this.choice) return;
    this.dispatch({ type: 'undo', patch: { inBrowser: `${orig.id} · ${orig.files.length} partitions` } });
    await this.useBrowserBackup(orig, mine);
  }

  /** `mine`: the choice this load is for. A file picked while it reads is newer, so this load then lands nowhere. */
  private async useBrowserBackup(meta: BackupMeta, mine: number) {
    let files: BackupFile[];
    try {
      files = await this.d.backups.files(meta);
    } catch (e) {
      if (mine !== this.choice) return;
      const msg = String((e as Error)?.message ?? e);
      const note = { kind: 'err' as const, title: 'Couldn\'t read the backup in this browser.', sub: `${msg}${/[.!?]$/.test(msg) ? '' : '.'} Use a backup file instead.` };
      // a file already in use stays; with none, the label must not say this browser has no backup
      this.dispatch({ type: 'undo', patch: this.s.undo.source ? { note } : { note, file: 'This browser\'s backup can\'t be read.' } });
      return;
    }
    if (mine !== this.choice) return;
    this.undoBackup = { meta, files };
    this.undoFiles = new Map(files.map((f) => [f.part, f.data]));
    this.dispatch({ type: 'undo', patch: { source: 'browser', ready: true } });
  }

  /** "Download this backup" on Undo: the browser's backup as the same zip Done offers. */
  downloadUndoBackup(): void {
    const b = this.undoBackup;
    if (!b) return;
    (this.d.download ?? saveBlob)(`${b.meta.id}.zip`, backupZip(b.meta, b.files));
  }

  /** Only the latest backup choice may land: an earlier file's check that ends later is dropped. */
  private choice = 0;
  private givingBack = false;

  async undoFromFile(f: File): Promise<void> {
    // locked: the apps were given back against the backup in use; givingBack: they are being, against it
    if (this.s.undo.locked || this.givingBack) return;
    const mine = ++this.choice;
    this.undoFiles = undefined;
    this.dispatch({ type: 'undo', patch: { source: 'file', ready: false, checking: true, file: `Checking ${f.name}…` } });
    try {
      const b = await readBackupZip(f);
      if (mine !== this.choice) return;
      this.undoBackup = undefined;
      this.undoFiles = b.files;
      this.dispatch({ type: 'undo', patch: { ready: true, checking: false, file: `${b.id} · ${b.files.size} partitions · checked` } });
    } catch (e) {
      if (mine !== this.choice) return;
      this.dispatch({ type: 'undo', patch: { ready: false, checking: false, file: (e as Error).message } });
    }
  }

  async undoFromBrowser(): Promise<void> {
    if (this.s.undo.locked || this.givingBack) return;
    const mine = ++this.choice;
    const orig = await this.d.backups.original().catch(() => undefined);
    if (orig && mine === this.choice) await this.useBrowserBackup(orig, mine);
  }

  /**
   * Continue: "keep its settings" first gives the panel back to its own apps over adb (the picker
   * opens inside this click), then both depths go to recovery mode.
   */
  undoContinue(): Promise<void> {
    const u = this.s.undo;
    if (!this.undoFiles) return Promise.resolve();
    if (u.depth === 'keep' && !u.appsBack) {
      const key = this.key;
      if (!key) { this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Not ready yet.', sub: 'Click Continue again in a moment.' } } }); return Promise.resolve(); }
      const picked = this.d.devices.requestAdb(key);
      // the give-back takes the choice: a backup read still pending can no longer land
      ++this.choice;
      this.givingBack = true;
      return this.giveBack(picked, this.undoFiles).finally(() => { this.givingBack = false; });
    }
    this.dispatch({ type: 'go', screen: 'connect', flow: 'undo' });
    return Promise.resolve();
  }

  /**
   * Giving the panel back to its own apps removes or disables Glassworks, so it only happens for a
   * backup undo can restore (kiln-core's checkRestorable, the check the restore itself runs). A backup
   * that can't be restored stops here, with nothing on the panel changed.
   */
  private async giveBack(picked: Promise<PanelShell>, files: ReadonlyMap<string, Uint8Array>) {
    const keep = this.d.frame.undo.keep;
    const busy = (sub: string) => this.dispatch({ type: 'undo', patch: { note: { kind: 'busy', title: keep.giving, sub } } });
    busy(`In the browser's list, pick ${this.d.frame.asks.connect.pick}.`);
    let panel: PanelShell | undefined;
    try {
      panel = await picked;
      try {
        await checkRestorable(this.d.recipe.backup, files);
      } catch (e) {
        // Refused backup, or a broken plan in the frame's recipe: either way a backup or recipe
        // problem, not USB, and nothing on the panel has changed. Undoing by hand is the way on.
        const msg = String((e as Error)?.message ?? e);
        this.dispatch({ type: 'undo', patch: { note: {
          kind: 'err',
          title: e instanceof BackupRefused ? 'This backup can\'t undo the install.' : 'Kiln can\'t check this backup.',
          sub: `${msg.charAt(0).toUpperCase()}${msg.slice(1)}. This step changed nothing on the panel.`,
          link: { href: this.d.frame.help.undo, text: 'Undo by hand' },
        } } });
        return;
      }
      this.dispatch({ type: 'changed', by: 'apps' }); // before it starts: a give-back that stops partway has changed the panel too
      const r = await this.d.frame.giveBack(panel, (s, d) => busy(s || d || ''));
      // Still on: the apps are back against this backup (locked), but the step isn't done.
      if (r.app === 'running') {
        this.dispatch({ type: 'undo', patch: { locked: true, note: { kind: 'err', title: keep.stillOn, sub: `${r.note} Try again, or choose "${this.d.frame.undo.factory.title}".` } } });
        return;
      }
      this.dispatch({ type: 'undo', patch: { appsBack: true, locked: true, note: { kind: 'ok', title: keep.back, sub: `${r.note} Next, put the original software back: Continue, then recovery mode.` } } });
    } catch (e) {
      const msg = notPicked(e) ? 'Nothing was picked.' : (e as Error).message;
      this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Couldn\'t reach the panel over adb.', sub: `${msg} Is it running and plugged in?` } } });
    } finally {
      // each give-back picks the panel again: let go of this one's USB first
      await (panel as { close?: () => Promise<void> } | undefined)?.close?.().catch(() => {});
    }
  }
}

function saveBlob(name: string, blob: Blob) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
}
