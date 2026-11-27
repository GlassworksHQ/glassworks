// What Kiln's engine knows of a frame: its words (design/installer.json `words`), its drawings, and the few steps of its
// real install only code can say. Each frame's installer package declares one (`kilnFrame`) and Kiln reads it through
// the frames registry (packages/frames), so Kiln names no frame (issue 621).
import type { PanelShell } from '@glassworks/adb';
import type { AppId } from '@glassworks/app-adb';
import type { AskKind } from './flow';
import type { Recipe, StepCredits } from './recipe';
import type { RecoveryData } from './recovery';

/** Done's Silent updates block for one state: a word in the grants list's colours (y on, n neutral, w attention), and one line. */
export interface SilentWords { word: string; tone: 'y' | 'n' | 'w'; line: string }

export interface AskCopy { title: string; body: string; pick: string }

/** What Kiln's screens say for a frame: its installer.json `words`. */
export interface FrameWords {
  name: string;
  short: string;
  /** how a sentence names it, with its article (`the panel`); `the <short>` when it doesn't say */
  the?: string;
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
  /**
   * How long things usually take, in words ("about 10 minutes"): `total`, the whole install, and `steps`, by the id of a
   * step in installer.json's install flow. Only what the frame's docs or measurements say: a step without one shows no time.
   */
  time?: FrameTimes;
  /**
   * Putting the device in recovery mode by hand, one physical step each, the action in `**bold**`. Connect numbers them
   * beside the drawing, to tick off. Without them, the drawing's chips alone.
   */
  recoverySteps?: string[];
}

/** How long a frame's install usually takes (FrameWords `time`). `why`: where the times come from. */
export interface FrameTimes { total?: string; steps?: Record<string, string>; why?: string }

/** The app's install phases, as the frame's setUp reports them: the rows "Installing" shows. `apk`: the frame's name for which APK. */
export type AppPhase =
  | { step: 'apk'; apk: string; stage: 'copy' | 'install' }
  | { step: 'home' }
  | { step: 'prefs' }
  | { step: 'start' };

/** What the person chose on the settings step: kept, so going back to the step shows them again. */
export interface AppSettings { locale?: string; timezone?: string; clock24: boolean; keyboard: boolean }

/** What the frame's setUp tells the wizard back. `switched.wifi` lost: the Wi-Fi step asks for it again. */
export interface AppResult { ip?: string; switched?: { wifi: 'kept' | 'resent' | 'lost'; ssid?: string } }

/** The steps of the frame's real install only code can say, from its installer package (kiln.ts). */
export interface FrameCode {
  recipe: Recipe<string, unknown, AppResult>;
  /** The app on the device. */
  appId: AppId;
  /** The recipe's setUp options from the person's settings. `confirm` asks the person; `onPhase` reports progress. */
  appOptions(s: AppSettings, confirm: (question: string) => boolean, onPhase: (p: AppPhase) => void): unknown;
  /** The Wi-Fi network the device is on, read-only. */
  wifiName(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }>;
  /** Whether updates install with nobody at the device: one of `updateStates`, and a line for the log. Read-only. */
  updates(p: PanelShell, signal?: AbortSignal): Promise<{ state: string; log: string }>;
  /** Every state `updates` can give: each needs its `words.silent`. */
  updateStates: readonly string[];
  /** Undo's `keep` depth: the device's own apps back, ours removed or disabled. */
  giveBack(p: PanelShell, on: (step: string, detail?: string) => void): Promise<{ app: 'removed' | 'disabled' | 'running'; note: string }>;
}

/**
 * ?demo=… and tests only: what only the frame knows of its simulated device (its installer's sim.ts), the recipe with
 * its hardware steps faked and what it logs. Never part of the frame Kiln installs; the demo path takes it beside it.
 */
export interface FrameSim {
  recipe: Recipe<string, unknown, AppResult>;
  /** getprop answers on a device whose software is current */
  props: Record<string, string>;
  /** the technical log of an install, for ?demo=install */
  installLog: string[];
  /** Done's backup line, for ?demo=done */
  backupInfo: string;
  /** The cards its simulated app's Panes step shows (the app's own, each pane's title and line), for ?demo=panes */
  panes?: { id: string; title: string; detail: string; on: boolean }[];
}

/** A frame and its simulated device, for ?demo=… and tests. */
export interface DemoFrame { frame: KilnFrame; sim: FrameSim }

export interface KilnFrame extends FrameWords, FrameCode {
  id: string;
  recovery: RecoveryData;
  photo: { src: string; width: number; height: number };
  /** The outside work each install phase uses (ids from frame.json `credits`). */
  credits: StepCredits;
  /**
   * installer.json `apk`: the recipe asset that is the app, taken at run time from the newest release of `line` (one of
   * the frame's `releases`), never from the files staged with Kiln's deploy. A file staged under that name (a local
   * `npm run stage`) still wins, for testing a build before its release.
   */
  apk?: { line: string; asset: string };
}

const TONES = new Set(['y', 'n', 'w']);

/**
 * installer.json's `words`, checked where they come in: a missing or misspelt one fails the build and the tests, not a
 * screen. `silent`: the states the frame's `updates` gives, each of which needs its words.
 */
export function frameWords(w: unknown, silent: readonly string[]): FrameWords {
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
  for (const k of silent) if (!o.silent?.[k]) throw new Error(`installer.json words.silent.${k}: missing`);
  for (const [k, s] of Object.entries(o.silent ?? {})) {
    need(s, `silent.${k}.`, ['word', 'line']);
    if (!TONES.has(s.tone)) throw new Error(`installer.json words.silent.${k}.tone: one of y, n, w`);
  }
  if (o.time !== undefined) {
    if (o.time.total !== undefined) need(o.time, 'time.', ['total']);
    need(o.time.steps ?? {}, 'time.steps.', Object.keys(o.time.steps ?? {}));
  }
  if (o.recoverySteps !== undefined && (!Array.isArray(o.recoverySteps) || !o.recoverySteps.length || o.recoverySteps.some((x) => typeof x !== 'string' || !x))) {
    throw new Error('installer.json words.recoverySteps: one non-empty line per step');
  }
  return o;
}
