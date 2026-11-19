// What the wizard knows of a frame: its words (design/installer.json `words`), its drawings, and the few steps only
// code can say, from its installer package. The wizard names no frame (issue 621); a frame's route binds these together.
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import type { AskKind, Recipe, StepCredits } from '@glassworks/kiln-core';
import type { SilentWords } from '../components/SilentUpdates';
import type { RecoveryData } from '../recovery/player';

export interface AskCopy { title: string; body: string; pick: string }

/** What Kiln's screens say for a frame: its installer.json `words`. */
export interface FrameWords {
  name: string;
  short: string;
  /** the app's name as people see it */
  app: string;
  photoAlt: string;
  /** The name the browser's USB list shows at each "your turn". */
  asks: Record<AskKind, AskCopy>;
  /** The device in recovery mode, as the browser lists it. */
  felPick: string;
  drivers: { win: string; linux: string };
  help: { recovery: string; undo: string; undoCli: string; drivers: string; learn: string };
  /** Undo's two depths. `keep` gives the device back to its own apps over adb first. */
  undo: {
    factory: { title: string; detail: string; restored: string };
    keep: { title: string; detail: string; button: string; giving: string; back: string; stillOn: string; restored: string };
  };
  /** Done's Silent updates block, by the state `updates` reads. */
  silent: Record<string, SilentWords>;
}

/** The app's install phases, as the frame's setUp reports them: the rows "Installing" shows. */
export type AppPhase =
  | { step: 'apk'; apk: 'kiosk' | 'keyboard'; stage: 'copy' | 'install' }
  | { step: 'home' }
  | { step: 'prefs' }
  | { step: 'start' };

/** What the person chose on the settings step: kept, so going back to the step shows them again. */
export interface AppSettings { locale?: string; timezone?: string; clock24: boolean; keyboard: boolean }

/** What the frame's setUp tells the wizard back. `switched.wifi` lost: the Wi-Fi step asks for it again. */
export interface AppResult { ip?: string; switched?: { wifi: 'kept' | 'resent' | 'lost'; ssid?: string } }

/** The steps only code can say, from the frame's installer package. */
export interface FrameCode {
  recipe: Recipe<string, unknown, AppResult>;
  /** The app on the device. */
  appId: AppId;
  /** The recipe's setUp options from the person's settings. `confirm` asks the person; `onPhase` reports progress. */
  appOptions(s: AppSettings, confirm: (question: string) => boolean, onPhase: (p: AppPhase) => void): unknown;
  /** The Wi-Fi network the device is on, read-only. */
  wifiName(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }>;
  /** Whether updates install with nobody at the device: a key of `words.silent`, and a line for the log. Read-only. */
  updates(p: PanelShell, signal?: AbortSignal): Promise<{ state: string; log: string }>;
  /** Undo's `keep` depth: the device's own apps back, ours removed or disabled. */
  giveBack(p: PanelShell, on: (step: string, detail?: string) => void): Promise<{ app: 'removed' | 'disabled' | 'running'; note: string }>;
  /** A simulated device for ?demo=… and the tests. */
  sim: FrameSim;
}

/** What only the frame knows of its simulated device: the recipe with its hardware steps faked, and what it logs. */
export interface FrameSim {
  recipe: Recipe<string, unknown, AppResult>;
  /** getprop answers on a device whose software is current */
  props: Record<string, string>;
  /** the technical log of an install, for ?demo=install */
  installLog: string[];
  /** Done's backup line, for ?demo=done */
  backupInfo: string;
}

export interface KilnFrame extends FrameWords, FrameCode {
  id: string;
  recovery: RecoveryData;
  photo: { src: string; width: number; height: number };
  /** The outside work each install phase uses (ids from frame.json `credits`). */
  credits: StepCredits;
}

const TONES = new Set(['y', 'n', 'w']);

/** installer.json's `words`, checked where they come in: a missing or misspelt one fails the build and the tests, not a screen. */
export function frameWords(w: unknown): FrameWords {
  const need = (o: unknown, path: string, keys: string[]) => {
    for (const k of keys) {
      const v = (o as Record<string, unknown> | undefined)?.[k];
      if (typeof v !== 'string' || !v) throw new Error(`installer.json words.${path}${k}: missing`);
    }
  };
  need(w, '', ['name', 'short', 'app', 'photoAlt', 'felPick']);
  const o = w as FrameWords;
  for (const k of ['fastboot', 'adb', 'connect'] satisfies AskKind[]) need(o.asks?.[k], `asks.${k}.`, ['title', 'body', 'pick']);
  need(o.drivers, 'drivers.', ['win', 'linux']);
  need(o.help, 'help.', ['recovery', 'undo', 'undoCli', 'drivers', 'learn']);
  need(o.undo?.factory, 'undo.factory.', ['title', 'detail', 'restored']);
  need(o.undo?.keep, 'undo.keep.', ['title', 'detail', 'button', 'giving', 'back', 'stillOn', 'restored']);
  if (!o.silent || !Object.keys(o.silent).length) throw new Error('installer.json words.silent: missing');
  for (const [k, s] of Object.entries(o.silent)) {
    need(s, `silent.${k}.`, ['word', 'line']);
    if (!TONES.has(s.tone)) throw new Error(`installer.json words.silent.${k}.tone: one of y, n, w`);
  }
  return o;
}
