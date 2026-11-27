// One install or undo, from the first click to Done: kiln-core's InstallFlow with a frame's recipe (FEL, fastboot, the
// app over adb), then the standard flow every frame runs after its install (standard/session.ts), which this extends.
// All hardware work is here; screens only read the store and call these methods. Port of kiln/src/app/ui.ts without
// the DOM.
import type { AdbKey, PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';
import {
  BackupRefused, InstallFlow, backupZip, checkRestorable, readBackupZip, restoreFromBackup,
  type BackupFile, type BackupMeta, type BackupStore, type Devices, type Recipe,
} from '@glassworks/kiln-core';
import type { AppPhase, AppResult, AppSettings, KilnFrame } from '@glassworks/kiln-core';
import { standardOf } from '../standard/frame';
import { StandardSession } from '../standard/session';
import { firstStart, initial, type Flow, type WizardState } from './state';

export { Store } from '../standard/session';

export interface SessionDeps {
  recipe: Recipe<string, unknown, AppResult>;
  frame: KilnFrame;
  devices: Devices;
  /** Must call navigator.usb.requestDevice() before its first await. */
  requestFel(): Promise<FelDevice>;
  asset(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: string): Promise<boolean>;
  /** The app from its release, fetched during the backup and checked before the first write (kiln-core FlowDeps). */
  fetchAhead?: () => Promise<unknown>;
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

export class Session extends StandardSession {
  private flow?: InstallFlow<string, unknown, AppResult>;
  private result?: { meta: BackupMeta; files: BackupFile[] };
  private undoFiles?: Map<string, Uint8Array>;
  /** The backup in this browser, when Undo uses it: Undo offers it as a download. */
  private undoBackup?: { meta: BackupMeta; files: BackupFile[] };

  constructor(private readonly d: SessionDeps, state: WizardState = initial()) {
    super({ standard: standardOf(d.frame), devices: d.devices, loadKey: d.loadKey, sleep: d.sleep, openTab: d.openTab, simulated: d.simulated }, state);
  }

  private getFlow(key: AdbKey) {
    this.flow ??= new InstallFlow<string, unknown, AppResult>(this.d.recipe, {
      asset: (n, p) => this.d.asset(n, p),
      hasAsset: (n) => this.d.hasAsset(n),
      fetchAhead: this.d.fetchAhead,
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
    this.setupPhase(firstStart(this.d.frame));
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
        title: 'The original software is back',
        body: this.s.undo.depth === 'keep'
          ? this.d.frame.undo.keep.restored
          : this.d.frame.undo.factory.restored,
      },
    });
  }

  // ---- the app on the panel: this frame's App step installs it ------------------------------------

  /** #/resume, #/wifi, #/home-assistant, #/radio (the standard flow's), and #/undo, #/install. */
  override async route(r: string): Promise<void> {
    switch (r) {
      case 'install': this.dispatch({ type: 'go', screen: 'connect', flow: 'install' }); return;
      case 'undo': return this.openUndo();
      default: return super.route(r);
    }
  }

  /** Undo with the backup, from Done or an error. */
  override get canUndo(): boolean { return true; }
  override undo(): Promise<void> { return this.click('undo', () => this.openUndo()); }

  /** The App step installs the app here: the install over FEL wrote the system, not the app. */
  protected override applies(): boolean { return false; }

  /** The App step's work: the app, installed (or updated) with the person's settings by the recipe's setUp. */
  protected override async appStep(opts: AppSettings, at: (p: AppPhase) => void): Promise<AppResult> {
    return this.getFlow(await this.keyReady).setUpApp(this.panel!, this.d.frame.appOptions(opts, (q) => (this.d.confirm ?? window.confirm)(q), at));
  }

  /** Done's download: the files from this session's run, or from this browser's store for an earlier session's. */
  override async downloadBackup(): Promise<void> {
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
      this.dispatch({ type: 'done', done: { ...cur, backup: { ...cur.backup, error: `Couldn't read the backup from this browser (${(e as Error).message}). Try again, or use a backup file on the Undo screen.` } } });
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
      const note = { kind: 'err' as const, title: 'Couldn\'t read the saved backup', sub: 'This browser couldn\'t open its stored copy. Choose a backup .zip file instead.' };
      // a file already in use stays; with none, the label must not say this browser has no backup
      this.dispatch({ type: 'undo', patch: this.s.undo.source ? { note } : { note, file: 'The backup stored in this browser couldn\'t be read.' } });
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
      this.dispatch({ type: 'undo', patch: { ready: true, checking: false, file: `${b.id} · ${b.files.size} partitions · verified` } });
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
      if (!key) { this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Almost ready', sub: 'Kiln is finishing browser setup. Click Continue again in a moment.' } } }); return Promise.resolve(); }
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
          title: e instanceof BackupRefused ? 'This backup can\'t undo the install' : 'Kiln couldn\'t verify this backup',
          sub: `${msg.charAt(0).toUpperCase()}${msg.slice(1)}. Nothing on the panel was changed.`,
          link: { href: this.d.frame.help.undo, text: 'Manual restore guide' },
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
      this.dispatch({ type: 'undo', patch: { appsBack: true, locked: true, note: { kind: 'ok', title: keep.back, sub: `${r.note} Click Continue to put the panel in recovery mode and restore your original software.` } } });
    } catch (e) {
      const msg = notPicked(e) ? 'No device was selected.' : (e as Error).message;
      this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Couldn\'t reach the panel over USB', sub: `${msg} Make sure it is powered on and plugged into this computer.` } } });
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
