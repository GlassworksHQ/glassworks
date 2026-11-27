// The wizard's state: which screen, the "your turn" sheet, progress, logs, and each setup phase's
// data. Every change goes through `reduce`; the rail, the stack, the plain log and the chip are
// derived from it by the selectors below, so a screen never keeps its own copy.
import type { AskKind, BackupMeta, PartState, Phase, Progress } from '@glassworks/kiln-core';
import type { SetupState } from '@glassworks/setup-channel';
import type { Go } from '../components/Nav';
import { SET_UP, type SetUpStep } from './routes';
import type { SilentWords } from '../components/SilentUpdates';
import type { AppPhase, AppSettings, KilnFrame } from '@glassworks/kiln-core';

export type Flow = 'install' | 'undo';
/**
 * What an earlier step did to the panel: `written`, a run started writing; `apps`, the panel was (being)
 * given back to its own apps; `unknown`, this browser tab can't remember (its storage is blocked), so an
 * earlier page load may have done either.
 */
export type Changed = 'written' | 'apps' | 'unknown';
const OUTRANKS: Record<Changed, number> = { unknown: 0, apps: 1, written: 2 };
export type Screen = 'connect' | 'firing' | 'setup' | 'done' | 'undo' | 'error';

export interface Network { ssid: string; rssi: number; secure: boolean }

export type { AppSettings };

/** What the setup step shows, one phase at a time (Glassworks over adb). */
export type Setup =
  /** `typical`: how long it usually takes, in the frame's words ("1–2 minutes"), where they say */
  | { phase: 'waiting'; note: string; typical?: string }
  /** `installing`: the app is going in; provision's AppPhases so far, the last one now */
  /** `apply`: the app is on already (a frame whose install put it on): the step applies the settings, no install */
  | { phase: 'settings'; installing?: AppPhase[]; error?: string; update: boolean; apply?: true; chosen?: AppSettings }
  | {
      phase: 'wifi';
      /** the panel is on Wi-Fi already (the app's setup state has an address); `ssid` when Android says which */
      on?: { ip: string; ssid?: string };
      /** the person chose to use another network than `on` */
      changing?: boolean;
      /** undefined while the panel scans */
      networks?: Network[];
      status: string;
      hint?: string;
      unreachable?: boolean;
      joining?: { ssid: string; step: 'sent' | 'join' | 'ok' | 'err'; error?: string };
      /** `lan`: on the panel's own page, over its setup channel (issue 197). Kiln's wizard sends it over USB. */
      over?: 'lan';
      /** Once sent on the panel's own page: what happens next, as this page can't see the outcome. */
      joinNote?: string;
    }
  /** signingIn: a sign-in window is open (Kiln's), or the page is leaving for the sign-in (the panel's setup page), and the panel isn't signed in yet */
  | { phase: 'ha'; ha: SetupState['ha']; note?: string; error?: string; busy?: boolean; signingIn?: boolean }
  /** The Panes step: a switch per card the app offers (`SetupState.panes`), as the person set them. */
  | { phase: 'panes'; panes: NonNullable<SetupState['panes']>; error?: string; busy?: boolean }
  | { phase: 'radio'; radio: SetupState['radio']; note: string; error?: string; busy?: boolean }
  | { phase: 'radio-next'; plan: RadioPlan };

export interface RadioStep {
  title: string;
  body: string;
  kv?: [string, string, boolean?][];
  link?: string;
  /** the screen's one ember action: `link` as a button with this label (Finish is quieter then) */
  primary?: string;
  /** how to get what the step needs, under it */
  how?: { title: string; body: string; link: string; linkText: string };
}
export interface RadioPlan {
  title: string;
  lead: string;
  steps: RadioStep[];
  /** a way that isn't recommended (an older app's), behind "More help" */
  more?: RadioStep[];
}

export interface Failure {
  title: string;
  /** what happened and how to fix it, in one paragraph: for announcements and the failure report */
  body: string;
  /** the screen's two parts of `body` (explain.ts): what happened, then how to fix it, in its own callout */
  happened?: string;
  fix?: string;
  /** whether anything may have reached the panel's storage before it stopped */
  wrote: boolean;
  tips: [string, string][];
  raw: string;
  /** the rail step it failed on */
  at: number;
}

export interface UndoState {
  /** where the backup comes from; undefined until one is chosen and checked */
  source?: 'browser' | 'file';
  inBrowser?: string;
  file?: string;
  ready: boolean;
  /** a backup file is being checked */
  checking?: boolean;
  depth: 'factory' | 'keep';
  appsBack: boolean;
  /**
   * The apps were given back against this backup, so it is the one to restore: no other may be
   * picked in this session. Unlike `appsBack`, a depth change or a new Undo keeps it.
   */
  locked?: boolean;
  /** `link`: a way forward when undo here can't go on, e.g. undoing by hand */
  note?: { kind: 'busy' | 'ok' | 'err'; title: string; sub: string; link?: { href: string; text: string } };
}

export interface DoneState {
  title: string;
  body: string;
  /** The device's Manage page (`http://<address>:8099/`, #34), when the app on it has one: Done hands off to it. */
  managePage?: string;
  backup?: { name: string; info: string; hint: string; error?: string };
  /** Whether the app's updates install by themselves, as read back from the device (issue 422). */
  updates?: SilentWords;
}

/** A backup a run in this tab saved: what Done offers. The serial stays in IndexedDB only. */
export type SavedBackup = Omit<BackupMeta, 'serial'>;

export interface Part { state: PartState; size?: number; sha256?: string; pct?: number }

export interface WizardState {
  flow: Flow;
  screen: Screen;
  ask?: { kind: AskKind; busy: boolean; error?: string };
  progress: { overall: number; phase: Phase; title: string; sub: string; started: number };
  /** a write was flashed and verified: from here on, a failure has changed the panel */
  wrote: boolean;
  /**
   * a write may have started: the first flash or verify progress. A failure from here on may have
   * changed the panel, even before any write is verified (explain.ts says so).
   */
  touched: boolean;
  /**
   * an earlier step may have changed the panel, and no run has reached Done since: `written`, a run
   * started writing; `apps`, the panel was (being) given back to its own apps. Unlike `wrote` and
   * `touched`, which are this attempt's, a new attempt (go, start) keeps it, and the registry keeps it
   * for the browser tab across sessions: a later failure must not say nothing was written.
   */
  changed?: Changed;
  /**
   * The latest backup a run in this browser tab saved. Like `changed`, the registry keeps it for the tab
   * across sessions and reloads, so Done offers it after any session's set-up, not only the one that
   * installed.
   */
  backup?: SavedBackup;
  log: string[];
  parts: [string, Part][];
  setup: Setup;
  undo: UndoState;
  failure?: Failure;
  done?: DoneState;
  /** A clicked action is running; its buttons are off until it ends (Session.click). */
  busy?: string;
  /** The app on the device offers setup's Panes step (its setup state lists `panes`). Older apps don't: no Panes step. */
  panes?: boolean;
}

export type Action =
  | { type: 'go'; screen: Screen; flow?: Flow }
  | { type: 'start'; flow: Flow; now: number }
  | { type: 'progress'; p: Progress }
  | { type: 'log'; line: string }
  | { type: 'partition'; part: string; state: PartState; info: Omit<Part, 'state'> }
  | { type: 'ask'; kind: AskKind }
  | { type: 'ask-busy' }
  | { type: 'ask-error'; error: string }
  | { type: 'ask-end' }
  | { type: 'setup'; setup: Setup }
  | { type: 'undo'; patch: Partial<UndoState> }
  | { type: 'fail'; failure: Failure }
  | { type: 'done'; done: DoneState }
  | { type: 'busy'; busy?: string }
  | { type: 'changed'; by: Changed }
  | { type: 'backup'; backup: SavedBackup }
  | { type: 'panes'; offered: boolean };

export const LOG_LIMIT = 400;

export function initial(flow: Flow = 'install'): WizardState {
  return {
    flow,
    screen: flow === 'undo' ? 'undo' : 'connect',
    progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: 0 },
    wrote: false,
    touched: false,
    log: [],
    parts: [],
    setup: { phase: 'waiting', note: '' },
    undo: { ready: false, depth: 'factory', appsBack: false },
  };
}

export function reduce(s: WizardState, a: Action): WizardState {
  switch (a.type) {
    case 'go': {
      // `wrote` and `touched` are this attempt's. Leaving the Error screen (Try again, Undo) starts the
      // next attempt clean; `changed` keeps the panel's history.
      const left = s.screen === 'error' ? { wrote: false, touched: false } : {};
      return { ...s, ...left, screen: a.screen, flow: a.flow ?? s.flow, ask: undefined };
    }
    case 'start':
      return {
        ...s, flow: a.flow, screen: 'firing', ask: undefined, wrote: false, touched: false, log: [], parts: [], failure: undefined,
        progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: a.now },
      };
    case 'progress': {
      // Progress never goes backwards, even when the flow's phases alternate.
      const overall = Math.max(s.progress.overall, Math.min(1, a.p.overall));
      // kiln-core reports flash and verify only from inside a write (flashVerified)
      const touched = s.touched || a.p.phase === 'flash' || a.p.phase === 'verify';
      return { ...s, touched, changed: touched ? 'written' : s.changed, progress: { ...s.progress, overall, phase: a.p.phase, title: a.p.title, sub: a.p.sub } };
    }
    case 'log': {
      const log = [...s.log, a.line].slice(-LOG_LIMIT);
      const wrote = s.wrote || /(flashed|restored) and verified/.test(a.line);
      return { ...s, log, wrote, changed: wrote ? 'written' : s.changed };
    }
    case 'partition': {
      const was = s.parts.find(([p]) => p === a.part)?.[1];
      const part: Part = { ...was, ...a.info, state: a.state };
      const parts = was ? s.parts.map(([p, x]): [string, Part] => [p, p === a.part ? part : x]) : [...s.parts, [a.part, part] as [string, Part]];
      return { ...s, parts };
    }
    case 'ask':
      return { ...s, ask: { kind: a.kind, busy: false } };
    case 'ask-busy':
      return s.ask ? { ...s, ask: { ...s.ask, busy: true, error: undefined } } : s;
    case 'ask-error':
      return s.ask ? { ...s, ask: { ...s.ask, busy: false, error: a.error } } : s;
    case 'ask-end':
      return { ...s, ask: undefined };
    case 'setup':
      return { ...s, screen: 'setup', setup: a.setup };
    case 'undo':
      return { ...s, undo: { ...s.undo, ...a.patch } };
    case 'fail':
      return { ...s, screen: 'error', ask: undefined, failure: a.failure };
    case 'done':
      return { ...s, screen: 'done', ask: undefined, done: a.done, changed: undefined };
    case 'busy':
      return { ...s, busy: a.busy };
    case 'backup':
      return { ...s, backup: a.backup };
    case 'panes': // unset and false are the same: an app without the step leaves the state as it was
      return !!s.panes === a.offered ? s : { ...s, panes: a.offered };
    case 'changed': // a write outranks the apps, and both outrank not knowing
      return { ...s, changed: s.changed && OUTRANKS[s.changed] > OUTRANKS[a.by] ? s.changed : a.by };
  }
}

// ---- derived views ------------------------------------------------------------------------------

export const STEPS: Record<Flow, readonly string[]> = {
  install: ['Panel', 'Connect', 'Install', 'Set up', 'Done'],
  undo: ['Backup', 'Connect', 'Restore', 'Done'],
};

export type RailMark = 'done' | 'now' | 'turn' | 'bad' | 'todo';

/** The rail's step for a screen; the picker (step 0 of an install) is its own page. */
export function railIndex(s: WizardState): number {
  if (s.screen === 'error') return s.failure?.at ?? 1;
  const at: Record<Flow, Partial<Record<Screen, number>>> = {
    install: { connect: 1, firing: 2, setup: 3, done: 4 },
    undo: { undo: 0, connect: 1, firing: 2, done: 3 },
  };
  return at[s.flow][s.screen] ?? 0;
}

/** Set up's own steps on the rail: routes.ts SET_UP, named. */
const SET_UP_NAMES: Record<(typeof SET_UP)[number], string> = { 'set-up': 'App', wifi: 'Wi-Fi', 'home-assistant': 'Home Assistant', panes: 'Panes', radio: 'Radio' };
const SET_UP_AT: Record<Setup['phase'], number> = { waiting: 0, settings: 0, wifi: 1, ha: 2, panes: 3, radio: 4, 'radio-next': 4 };

export interface RailStep {
  name: string;
  mark: RailMark;
  /** during set-up, its own steps; `route` is the step's (routes.ts), for going back to it */
  sub?: { name: string; mark: RailMark; route: (typeof SET_UP)[number]; go?: Go }[];
  /** A done step the person can return to, and how (docs/design/kiln/navigation.md). Only while nothing runs. */
  go?: Go;
  /** A done step behind a write: no way back to it (the shell's `lock` says why). */
  lock?: boolean;
}

/**
 * Set up's own steps on the rail, marked by where set-up is: `steps`, the ones this frame's set-up has (the standard
 * flow's, less what the frame's data leaves out; standard.ts).
 */
export function setUpSub(setup: Setup, steps: readonly SetUpStep[] = SET_UP): NonNullable<RailStep['sub']> {
  const at = SET_UP_AT[setup.phase];
  return SET_UP.map((route, j) => ({ name: SET_UP_NAMES[route], route, mark: j < at ? 'done' as const : j > at ? 'todo' as const : 'now' as const }))
    .filter((x) => steps.includes(x.route));
}

/** The set-up steps this session shows: the frame's (`steps`, its data), Panes once the app offers it. */
export const setUpSteps = (s: Pick<WizardState, 'panes'>, steps: readonly SetUpStep[] = SET_UP): SetUpStep[] =>
  steps.filter((x) => x !== 'panes' || !!s.panes);

/** `steps`: the frame's set-up steps (standard/frame.ts). */
export function rail(s: WizardState, steps: readonly SetUpStep[] = SET_UP): RailStep[] {
  const now = railIndex(s);
  const all = s.screen === 'done';
  return STEPS[s.flow].map((name, i) => ({
    name,
    mark: all || i < now ? 'done' : i > now ? 'todo' : s.screen === 'error' ? 'bad' : s.ask ? 'turn' : 'now',
    ...(s.flow === 'install' && s.screen === 'setup' && i === now ? { sub: setUpSub(s.setup, setUpSteps(s, steps)) } : {}),
  }));
}

/**
 * The rail of a frame whose install ran on its own screens (a device frame's page): those steps, done, then Set up
 * with the frame's set-up steps, and Done. An error here is set-up's.
 */
export function standardRail(s: WizardState, before: readonly string[], steps: readonly SetUpStep[]): RailStep[] {
  const names = [...before, 'Set up', 'Done'];
  const all = s.screen === 'done';
  const now = names.length - 2;
  return names.map((name, i) => ({
    name,
    mark: all || i < now ? 'done' : i > now ? 'todo' : s.screen === 'error' ? 'bad' : s.ask ? 'turn' : 'now',
    ...(s.screen === 'setup' && i === now ? { sub: setUpSub(s.setup, setUpSteps(s, steps)) } : {}),
  }));
}

/**
 * How many layers of the stack have set above Kiln's floor: 1 once the panel runs our installer, 2
 * once the new software is written, 3 with the app in, 4 when done.
 */
export function stackLevel(s: WizardState): number {
  const fired = s.progress.overall >= 0.6 || s.wrote ? 2 : s.progress.overall > 0 ? 1 : 0;
  switch (s.screen) {
    case 'done': return 4;
    case 'setup': return 3;
    case 'firing':
    case 'error': return fired;
    default: return 0;
  }
}

export interface Milestone { text: string; done: boolean }

const ORDER: Phase[] = ['boot', 'backup', 'patch', 'flash', 'verify'];

/** The plain log: what is done and what is happening, in words, not partition names. */
export function milestones(s: WizardState, panelName: string): Milestone[] {
  const { overall, phase } = s.progress;
  const at = ORDER.indexOf(phase);
  const restarting = overall >= 0.98;
  const rows: [string, boolean, boolean, string?][] = s.flow === 'undo'
    ? [
        [`Found your ${panelName}`, overall > 0, overall >= 0.01],
        ['Started the installer in memory', overall >= 0.01, at >= 3],
        ['Restoring your original software', at >= 3, restarting],
        ['Restarting the panel', restarting, false],
      ]
    : [
        [`Found your ${panelName}`, overall > 0, overall >= 0.01],
        ['Started the installer in memory', overall >= 0.01, at >= 1],
        ['Backed up and verified the copy', at >= 1, at >= 2],
        ['Prepared the new software', at >= 2, at >= 3],
        ['Writing and verifying the new software', at >= 3, restarting],
        ['Restarting the panel', restarting, false],
      ];
  return rows.filter(([, reached]) => reached).map(([text, , done]) => ({ text, done }));
}

/**
 * Every step of the firing, the ones still to come too, in plain present-tense words: the install screen's list
 * (components/Kit Progress). `done` and `reached` as milestones() has them.
 */
/**
 * Set-up's wait for the device's first start after the install, with how long it usually takes where the frame's words
 * say (`time.steps.first-boot`).
 */
export function firstStart(f: Pick<KilnFrame, 'time' | 'the' | 'short'>): Extract<Setup, { phase: 'waiting' }> {
  const the = f.the ?? `the ${f.short}`;
  const typical = f.time?.steps?.['first-boot'];
  return { phase: 'waiting', note: `${the.charAt(0).toUpperCase()}${the.slice(1)} is starting its new software`, ...(typical ? { typical } : {}) };
}

/** `step`: the install flow's step a row belongs to, where it maps to one (installer.json), so its time can be said. */
export function firingSteps(s: WizardState, panelName: string): { text: string; reached: boolean; done: boolean; step?: string }[] {
  const { overall, phase } = s.progress;
  const at = ORDER.indexOf(phase);
  const restarting = overall >= 0.98;
  const rows: [string, boolean, boolean, string?][] = s.flow === 'undo'
    ? [
        [`Find your ${panelName}`, overall > 0, overall >= 0.01],
        ['Start the installer in its memory', overall >= 0.01, at >= 3],
        ['Put your original software back and check it', at >= 3, restarting],
        ['Restart the panel', restarting, false],
      ]
    : [
        [`Find your ${panelName}`, overall > 0, overall >= 0.01],
        ['Start the installer in its memory', overall >= 0.01, at >= 1],
        ['Back up your original software and check the copy', at >= 1, at >= 2],
        ['Prepare the new software', at >= 2, at >= 3],
        ['Write the new software and check every write', at >= 3, restarting],
        ['Restart the panel', restarting, false, 'first-boot'],
      ];
  return rows.map(([text, reached, done, step]) => ({ text, reached, done, ...(step ? { step } : {}) }));
}

/** "68% · ~3 min": the estimate once there is enough to go on. */
export function eta(s: WizardState, now: number): string {
  const { overall, started } = s.progress;
  const pct = `${Math.floor(overall * 100)}%`;
  const elapsed = (now - started) / 1000;
  if (overall < 0.08 || overall >= 0.97 || elapsed < 20) return pct;
  const left = (elapsed / overall) * (1 - overall);
  return left > 90 ? `${pct} · ~${Math.round(left / 60)} min` : left > 15 ? `${pct} · under a minute` : pct;
}

/** Whether leaving the page now could interrupt a write (the leave-page warning). */
export const busyWriting = (s: WizardState) => s.screen === 'firing' && s.progress.phase !== 'boot';
