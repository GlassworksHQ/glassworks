// The frame's current session, outside React, so it outlives route changes and the picker can see
// a running install. Type-only imports: the picker reads this without loading the engine.
import type { StandardSession } from '../standard/session';
import type { Changed, Failure, SavedBackup, WizardState } from './state';

export interface Entry {
  /** the frame's route base, its installer.json `route` */
  base: string;
  name: string;
  /** The frame's session: the wizard's install session (a Session), or a standard flow's after a frame's own install. */
  session: StandardSession;
  /** ?demo=… states; never in a production build */
  demo?: string;
  demoEntered?: boolean;
  /** The standard flow alone, after a frame's own install (a device frame's page), not the wizard's install session. */
  standard?: boolean;
  /** An install handed this session its device: the page shows it until it has finished, on any of its routes. */
  handed?: boolean;
}

/**
 * The last run that stopped with an error, kept when the person moves on from the Error screen (Try
 * again, another route, a new session), so its details and log can still be read. Cleared when a new
 * run starts.
 */
export interface LastFailure { name: string; when: Date; failure: Failure; log: string[] }

/**
 * The panel's history (WizardState.changed) for this browser tab, per frame base. A new session (Back
 * to Kiln, then the frame again) starts from it, and so does a reload (sessionStorage), so neither
 * forgets that an earlier step changed the panel. Only a run reaching Done clears it.
 *
 * With storage blocked (site data off), a reload forgets: the first session of a page load then
 * starts from `unknown`, never from "nothing changed".
 */
const history = new Map<string, Changed>();
/** Bases this page load has had a session for: from then on `history` alone knows. */
const seen = new Set<string>();
const storageKey = (base: string) => `kiln:changed:${base}`;

/** sessionStorage, if this browser lets the page write it (Chrome with site data blocked throws). */
function storage(): Storage | undefined {
  try {
    sessionStorage.setItem('kiln:probe', '1');
    sessionStorage.removeItem('kiln:probe');
    return sessionStorage;
  } catch { return undefined; }
}

function recall(base: string): Changed | undefined {
  if (history.has(base)) return history.get(base);
  if (seen.has(base)) return undefined;
  const store = storage();
  if (!store) return 'unknown';
  try {
    const v = store.getItem(storageKey(base));
    return v === 'written' || v === 'apps' ? v : undefined;
  } catch { return 'unknown'; } // writable but not readable: still can't recall

}

function remember(base: string, c: Changed | undefined) {
  if (c) history.set(base, c); else history.delete(base);
  if (c === 'unknown') return; // nothing to keep: it is what a page load without storage starts from
  try {
    if (c) sessionStorage.setItem(storageKey(base), c); else sessionStorage.removeItem(storageKey(base));
  } catch { /* no storage: this page load still has `history` */ }
}

/**
 * The backup the tab's latest run saved (WizardState.backup), per frame base, kept the same way: a
 * later session's Done (set-up entered again, a reload) still offers its download. The files stay in
 * IndexedDB; this is only which backup it was.
 */
const backups = new Map<string, SavedBackup>();
const backupKey = (base: string) => `kiln:backup:${base}`;

function recallBackup(base: string): SavedBackup | undefined {
  if (backups.has(base) || seen.has(base)) return backups.get(base);
  try {
    const v = JSON.parse(sessionStorage.getItem(backupKey(base)) ?? 'null') as SavedBackup | null;
    return v && typeof v.id === 'string' && Array.isArray(v.files) && typeof v.bootWasPatched === 'boolean' ? v : undefined;
  } catch { return undefined; }
}

function rememberBackup(base: string, b: SavedBackup) {
  backups.set(base, b);
  try { sessionStorage.setItem(backupKey(base), JSON.stringify(b)); } catch { /* this page load still has `backups` */ }
}

/**
 * A frame whose session isn't this wizard's InstallFlow (a page with its own session, until issue 621 moves
 * it onto this one): what the picker and LeaveGuard need of it. It is kept beside `current`, one per
 * frame base, so it outlives route changes too, and two frames' sessions never close each other.
 */
export interface Activity {
  base: string;
  name: string;
  /** writes to the device now: leaving or reloading the page asks first */
  writing(): boolean;
  /** busy: the route back to it, for the picker; undefined when there is nothing to come back to */
  running(): string | undefined;
  subscribe(l: () => void): () => void;
}

let current: Entry | undefined;
const activities = new Map<string, Activity>();
let activityList: Activity[] = [];
let lastFailure: LastFailure | undefined;
let unwatch: (() => void) | undefined;
const listeners = new Set<() => void>();
const notify = () => { for (const l of listeners) l(); };

function keep(e: Entry, s: WizardState) {
  if (s.screen !== 'error' || !s.failure) return;
  lastFailure = { name: e.name, when: new Date(), failure: s.failure, log: s.log };
}

function watch(e: Entry) {
  unwatch?.();
  let prev = e.session.store.state;
  unwatch = e.session.store.subscribe(() => {
    const s = e.session.store.state;
    if (s.changed !== prev.changed) remember(e.base, s.changed);
    if (s.backup && s.backup !== prev.backup) rememberBackup(e.base, s.backup);
    if (prev.screen === 'error' && s.screen !== 'error') { keep(e, prev); notify(); }
    if (s.screen === 'firing' && prev.screen !== 'firing' && lastFailure) { lastFailure = undefined; notify(); }
    prev = s;
  });
}

export const registry = {
  get: (): Entry | undefined => current,
  /**
   * Makes `e` the current session (a new install, or the first visit), and closes the one it replaces. `quiet`: called
   * while a component renders (FrameWizard's snapshot, on a frame's first visit), so the other listeners (LeaveGuard,
   * the picker) hear of it right after that render instead of during it, which React refuses ("Cannot update a
   * component while rendering a different component").
   */
  set(e: Entry, o: { quiet?: boolean } = {}): Entry {
    if (current && current.session !== e.session) {
      keep(current, current.session.store.state);
      current.session.close();
    }
    current = e;
    const earlier = recall(e.base);
    const saved = recallBackup(e.base);
    seen.add(e.base);
    watch(e); // first, so `history` keeps what the session starts from
    if (earlier) e.session.store.dispatch({ type: 'changed', by: earlier });
    if (saved && !e.session.store.state.backup) e.session.store.dispatch({ type: 'backup', backup: saved });
    if (o.quiet) queueMicrotask(notify); else notify();
    return e;
  },
  /** Another frame's session, by its base. */
  activity: (base: string): Activity | undefined => activities.get(base),
  /** Every other frame's session; the same array until one is kept, for useSyncExternalStore. */
  activities: (): readonly Activity[] => activityList,
  /** Keeps `a` as its frame's session, in place of an earlier one. */
  keep<A extends Activity>(a: A): A {
    activities.set(a.base, a);
    activityList = [...activities.values()];
    notify();
    return a;
  },
  lastFailure: (): LastFailure | undefined => lastFailure,
  subscribe(l: () => void) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
  /** Tests only. */
  clear() {
    unwatch?.(); unwatch = undefined; current = undefined; lastFailure = undefined; seen.clear();
    activities.clear(); activityList = [];
    for (const base of history.keys()) remember(base, undefined);
    backups.clear();
    try { for (const k of Object.keys(sessionStorage)) if (/^kiln:(changed|backup):/.test(k)) sessionStorage.removeItem(k); } catch { /* none */ }
  },
};

/** The stopped run as a text file: what happened, then the technical log. */
export function failureLog(f: LastFailure): string {
  return [
    `Kiln, ${f.name}`,
    `Stopped ${f.when.toISOString()}: ${f.failure.title}.`,
    f.failure.body,
    ...(f.failure.raw ? [`Error: ${f.failure.raw}`] : []),
    '',
    'Technical log:',
    ...f.log,
    '',
  ].join('\n');
}
