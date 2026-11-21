// How complete Glassworks is on a frame (issue 423): eight items, each ticked, not ticked or n/a with a short reason,
// and one word from the five that count. Worked out from frame.json (`facts`, or a tested device's `run`) at build
// time, so it can't drift from the data. The rules are in frames/README.md#how-complete-it-is.
import { ABIS, ships, type Abi, type FrameDesign } from './frame';
import { CAPABILITIES, PANES, isCapability, paneSupport } from './panes';

export const CHECKS = ['install', 'silentUpdates', 'hardware', 'kiosk', 'setup', 'recovery', 'branding', 'proof'] as const;
export type CheckKey = (typeof CHECKS)[number];
/** The items that decide the word (maintainer, 2026-11-17). Recovery, branding and proof are shown and don't count. */
export const COUNTED: readonly CheckKey[] = ['install', 'silentUpdates', 'hardware', 'kiosk', 'setup'];
export const CHECK_TEXT: Record<CheckKey, string> = {
  install: 'Install', silentUpdates: 'Silent updates', hardware: 'Hardware', kiosk: 'Kiosk',
  setup: 'Setup', recovery: 'Recovery', branding: 'Branding', proof: 'Proof',
};

/** What someone does on the device's own screen during an install, because Kiln can't do it over USB. */
export const DEVICE_STEPS = { 'usb-debugging': 'turn on USB debugging', 'home-pick': 'pick Glassworks as the home app' } as const;
export type DeviceStep = keyof typeof DEVICE_STEPS;
/** The ways it sets up. All but `computer` need no computer. */
export const SETUP_WAYS = { screen: 'on its own screen', hotspot: 'from a phone, on its setup hotspot', bluetooth: 'over Bluetooth', computer: 'from a computer' } as const;
export type SetupWay = keyof typeof SETUP_WAYS;
/** The kiosk's four checks, as seen on a real unit. */
export const KIOSK_CHECKS = { onlyHome: 'the only home screen', noVendorUi: 'no vendor screens', survivesReboot: 'back after a reboot', sleepsAndWakes: 'the screen sleeps and wakes' } as const;
export type KioskCheck = keyof typeof KIOSK_CHECKS;

/** A frame's facts, as its panels show them. Each one left out is "not checked". */
export interface FrameFacts {
  /** what someone does on the device's screen during the install; `[]` for nothing */
  install?: DeviceStep[];
  /** the panel's *Silent updates* sensor, word for word: "Yes: …" or "No: …" */
  silentUpdates?: string;
  /** what the device has that doesn't work in Glassworks yet, beyond a `provides` that no pane runs here; `[]` for nothing */
  hardware?: string[];
  kiosk?: Partial<Record<KioskCheck, boolean>>;
  setup?: SetupWay[];
  /** our boot animation is on it; `vendorLogo`: the maker's logo still shows after power-on */
  branding?: { bootAnimation: boolean; vendorLogo?: boolean };
  /** the frame's panel runs are done */
  panelRuns?: boolean;
  /** the items whose facts come from code or notes, not yet from a real unit */
  unverified?: CheckKey[];
  /** the shown-only items that don't apply here; each needs its reason in `notes`. An item that counts can't be n/a */
  na?: CheckKey[];
  /** a reason the data can't give, one line per item */
  notes?: Partial<Record<CheckKey, string>>;
}

export type GrantWhat = 'microphone' | 'camera' | 'secure-settings' | 'overlay' | 'device-admin';

/**
 * A tested device's run on a real unit, which its checklist comes from: Kiln's `--plan` and `--install` results
 * (frames/generic-android/installer/run-node.ts), what the panel reports after, and what the person saw. Never a
 * serial, a build fingerprint or an address.
 */
export interface FrameRun {
  /** the day it ran, YYYY-MM-DD. Without it the values are what we expect, and every item says unverified */
  on?: string;
  /** the app's version, or the test build, that ran */
  app?: string;
  /** `--plan`: the device as adb read it */
  plan: { model: string; android: string; sdk: number; abi: Abi; app: string };
  /** `--install`: its `grants`, `silentUpdates.state`, `home` and whether it handed off to the setup page */
  install: {
    grants: Partial<Record<GrantWhat, 'granted' | 'not-asked' | 'refused'>>;
    silentUpdates: 'allowed' | 'declined' | 'refused' | 'old-android' | 'not-asked';
    home: { how: 'set-home-activity' | 'chooser'; state: 'set' | 'unconfirmed' };
    setupPage: boolean;
  };
  /** what the panel reports after: its *Silent updates* sensor, word for word. Left out until someone reads it */
  panel?: { silentUpdates?: string };
  /** what the person saw on the unit that neither reports */
  seen: { hardware?: string[]; kiosk?: Partial<Record<KioskCheck, boolean>>; setup?: SetupWay[]; notes?: Partial<Record<CheckKey, string>> };
}

export type CheckState = 'yes' | 'no' | 'na';
export interface CheckItem {
  key: CheckKey;
  label: string;
  state: CheckState;
  /** a short sentence: why */
  reason: string;
  /** the frame's own words for what the data can't say */
  note?: string;
  /** it decides the word */
  counts: boolean;
  /** its facts aren't from a real unit yet */
  unverified: boolean;
}

export type Word = 'full' | 'good' | 'basic';
/** The words for the word; `none` while the frame doesn't ship. */
export const WORD_TEXT: Record<Word | 'none', string> = { full: 'Full', good: 'Good', basic: 'Basic', none: 'Not rated yet' };
export const WORD_MEANING: Record<Word | 'none', string> = {
  full: 'Everything that counts is there.',
  good: 'It installs from Kiln and runs, and something that counts is still missing.',
  basic: 'The app runs on it as it is.',
  none: "It doesn't ship yet: its status is planned or under development.",
};

export interface Completeness {
  /** null while the frame doesn't ship */
  word: Word | null;
  /** a counted item behind the word isn't from a real unit yet */
  unverified: boolean;
  /** a tested device whose run has no day: nothing here has run on a real unit. A dated run marked unverified isn't one */
  noRun: boolean;
  items: CheckItem[];
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const list = (xs: readonly string[]) => (xs.length < 2 ? (xs[0] ?? '') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`);

/** A tested device's facts, from its run. */
function fromRun(r: FrameRun): FrameFacts {
  // what the person saw later supersedes what Kiln saw at install: a grant refused then and given by hand after
  // shows as working in `seen.hardware`, a refusal that stayed is written there too
  const seen = r.seen.hardware;
  const refused = seen ? [] : (['microphone', 'camera'] as const).filter((g) => r.install.grants[g] === 'refused').map((g) => `the ${g}: Android refused it`);
  return {
    install: ['usb-debugging', ...(r.install.home.how === 'chooser' ? (['home-pick'] as const) : [])],
    silentUpdates: r.panel?.silentUpdates,
    hardware: seen ?? (refused.length ? refused : undefined),
    // as with hardware, what the person saw last wins: Always tapped on Android's chooser after Kiln said `unconfirmed`
    kiosk: { onlyHome: r.install.home.state === 'set', ...r.seen.kiosk },
    setup: r.seen.setup,
    panelRuns: r.on !== undefined,
    notes: r.seen.notes,
  };
}

/** Where its facts come from: `facts`, or a tested device's `run`. */
export const factsOf = (f: Pick<FrameDesign, 'kind' | 'facts' | 'run'>): FrameFacts => (f.kind === 'tested' ? (f.run ? fromRun(f.run) : {}) : (f.facts ?? {}));

type Verdict = { state: CheckState; reason: string };
const no = (...why: string[]): Verdict => ({ state: 'no', reason: why.join(' ') });
const yes = (reason: string): Verdict => ({ state: 'yes', reason });
const NOT_CHECKED = 'Not checked on a real unit yet.';

/** One rule per item. `route`: Kiln's route it installs from; a tested device uses its base's. */
const RULES: Record<CheckKey, (f: FrameDesign, x: FrameFacts, o: { route?: string; clarity?: string }) => Verdict> = {
  install: (f, x, o) => {
    const why: string[] = [];
    if (!o.route) why.push('Not in Kiln yet.');
    if (!['yes', 'partly'].includes(f.ratings.reversible.value)) why.push('No way back.');
    if (!x.install) why.push('Taps on the device not checked yet.');
    else if (x.install.length) why.push(`On the device: ${list(x.install.map((s) => DEVICE_STEPS[s]))}.`);
    if (why.length) return no(...why);
    // a button inside the case (the S1's FEL) is a step with the hands, which the install ratings show; not a tap
    const hands = f.ratings.opensDevice?.value === 'yes' ? ' Opening it to press a button is a physical step, not a tap on its screen.' : '';
    return yes(`From Kiln, with nothing to tap on the device's screen and a way back.${hands}`);
  },
  silentUpdates: (_, x) => {
    const m = /^(Yes|No): (.+)$/.exec(x.silentUpdates ?? '');
    if (!m) return no('No panel has reported it yet.');
    return { state: m[1] === 'Yes' ? 'yes' : 'no', reason: `${cap(m[2]!)}.` };
  },
  hardware: (f, x) => {
    // a device frame without `provides` covers many devices; a tested device is one, and its run says
    if (!f.provides && !x.hardware) return no(f.kind === 'tested' ? NOT_CHECKED : 'Depends on the device.');
    // hardware it lists that no pane runs on it yet
    const idle = (f.provides ?? []).filter((c) => !PANES.some((p) => p.requires.includes(c) && ships(paneSupport(f, p).support)));
    const missing = [...idle.map((c) => (isCapability(c) ? CAPABILITIES[c]! : c)), ...(x.hardware ?? [])];
    if (missing.length) return no(`Not yet: ${list(missing)}.`);
    return x.hardware ? yes('Everything it has works.') : no(NOT_CHECKED);
  },
  kiosk: (_, x) => {
    const k = x.kiosk ?? {};
    const keys = Object.keys(KIOSK_CHECKS) as KioskCheck[];
    const fails = keys.filter((c) => k[c] === false).map((c) => KIOSK_CHECKS[c]);
    const open = keys.filter((c) => k[c] === undefined).map((c) => KIOSK_CHECKS[c]);
    if (!fails.length && !open.length) return yes('The only home screen, with no vendor screens; it sleeps, wakes and comes back after a reboot.');
    return no(...(fails.length ? [`Not yet: ${list(fails)}.`] : []), ...(open.length ? [`Not checked: ${list(open)}.`] : []));
  },
  setup: (_, x) => {
    if (!x.setup) return no(NOT_CHECKED);
    const own = x.setup.filter((w) => w !== 'computer');
    return own.length ? yes(`${cap(own.map((w) => SETUP_WAYS[w]).join(', or '))}.`) : no('Only from a computer.');
  },
  recovery: (f) => {
    const r = f.ratings.reversible;
    if (r.value === 'yes' || r.value === 'partly') return yes(r.detail ? `${r.detail}.` : r.value === 'yes' ? 'Back to stock.' : 'Back to a working state.');
    return no(r.value === 'no' ? 'No way back.' : 'Not known yet.');
  },
  branding: (f, x) => {
    if (f.kind === 'tested') return no("The maker's boot screens stay: a tested device has no recipe of its own.");
    const b = x.branding;
    if (!b) return no(f.boot ? NOT_CHECKED : "The maker's boot screens stay.");
    if (!b.bootAnimation) return no("The maker's boot animation.");
    if (b.vendorLogo === undefined) return no("Our boot animation; the logo at power-on isn't checked yet.");
    return b.vendorLogo ? no("Our boot animation, but the maker's logo shows at power-on.") : yes("Our boot animation and splash, with no maker's logo.");
  },
  proof: (_, x, o) => {
    const why = [...(o.clarity ? [] : ['Clarity not graded on a real unit yet.']), ...(x.panelRuns ? [] : ['Panel runs not done yet.'])];
    return why.length ? no(...why) : yes(`Clarity ${o.clarity}, and its panel runs are done.`);
  },
};

/**
 * The checklist and the word. `base`: a tested device's base frame, whose Kiln route it installs from. `clarity`:
 * the frame's Clarity letter, when it is graded.
 */
export function completeness(f: FrameDesign, o: { base?: Pick<FrameDesign, 'kiln'>; clarity?: string } = {}): Completeness {
  const x = factsOf(f);
  const route = f.kiln ?? (f.kind === 'tested' ? o.base?.kiln : undefined);
  const noRun = f.kind === 'tested' && f.run?.on === undefined;
  const expected = f.unverified === true || noRun;
  const items = CHECKS.map((key): CheckItem => {
    const na = !COUNTED.includes(key) && x.na?.includes(key);
    const v = na ? { state: 'na' as const, reason: x.notes?.[key] ?? '' } : RULES[key](f, x, { route, clarity: o.clarity });
    return {
      key, label: CHECK_TEXT[key], ...v,
      note: na ? undefined : x.notes?.[key],
      counts: COUNTED.includes(key),
      unverified: expected || (x.unverified?.includes(key) ?? false),
    };
  });
  const counted = items.filter((i) => i.counts);
  const word: Word | null = !ships(f.status) ? null : counted.every((i) => i.state !== 'no') ? 'full' : route ? 'good' : 'basic';
  return { word, unverified: word !== null && counted.some((i) => i.unverified), noRun, items };
}

/* ---------- checking frame.json ---------- */

const isText = (v: unknown) => typeof v === 'string' && /\S/.test(v) && !/\n/.test(v);
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const SENSOR = /^(Yes|No): \S[^\n]*$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const PACKAGE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;

function words<T extends string>(at: string, v: unknown, ok: readonly T[], out: string[], nonEmpty = false) {
  if (!Array.isArray(v) || v.some((w) => !ok.includes(w)) || (nonEmpty && !v.length)) out.push(`${at}: should be a list of ${ok.join(', ')}`);
}
function texts(at: string, v: unknown, out: string[]) {
  if (!Array.isArray(v) || !v.every(isText)) out.push(`${at}: should be a list of one-line texts`);
}
function bools(at: string, v: unknown, keys: readonly string[], out: string[]) {
  if (!isObj(v) || Object.entries(v).some(([k, b]) => !keys.includes(k) || typeof b !== 'boolean')) out.push(`${at}: should map ${keys.join(', ')} to true or false`);
}
function notes(at: string, v: unknown, out: string[]) {
  if (!isObj(v) || Object.entries(v).some(([k, t]) => !(CHECKS as readonly string[]).includes(k) || !isText(t))) out.push(`${at}: should map items (${CHECKS.join(', ')}) to one line of text`);
}
/** Only the record's own keys: a serial or fingerprint pasted from `--plan` never reaches a page. */
function only(at: string, v: Record<string, unknown>, keys: readonly string[], out: string[]) {
  for (const k of Object.keys(v)) if (!keys.includes(k)) out.push(`${at}.${k}: not part of the record (no serials, fingerprints or addresses)`);
}

function factProblemsOf(x: unknown, out: string[]) {
  if (!isObj(x)) return out.push('facts: should be an object');
  only('facts', x, ['install', 'silentUpdates', 'hardware', 'kiosk', 'setup', 'branding', 'panelRuns', 'unverified', 'na', 'notes'], out);
  if (x.install !== undefined) words('facts.install', x.install, Object.keys(DEVICE_STEPS), out);
  if (x.silentUpdates !== undefined && (typeof x.silentUpdates !== 'string' || !SENSOR.test(x.silentUpdates))) out.push('facts.silentUpdates: the panel\'s Silent updates sensor, word for word ("Yes: …" or "No: …")');
  if (x.hardware !== undefined) texts('facts.hardware', x.hardware, out);
  if (x.kiosk !== undefined) bools('facts.kiosk', x.kiosk, Object.keys(KIOSK_CHECKS), out);
  if (x.setup !== undefined) words('facts.setup', x.setup, Object.keys(SETUP_WAYS), out, true);
  const b = x.branding;
  if (b !== undefined && (!isObj(b) || typeof b.bootAnimation !== 'boolean' || (b.vendorLogo !== undefined && typeof b.vendorLogo !== 'boolean') || Object.keys(b).some((k) => k !== 'bootAnimation' && k !== 'vendorLogo'))) {
    out.push('facts.branding: should be { bootAnimation: true or false, vendorLogo?: true or false }');
  }
  if (x.panelRuns !== undefined && typeof x.panelRuns !== 'boolean') out.push('facts.panelRuns: should be true or false');
  if (x.unverified !== undefined) words('facts.unverified', x.unverified, CHECKS, out);
  if (x.na !== undefined) words('facts.na', x.na, CHECKS, out);
  // an n/a counts as ticked, so on an item that counts it would give a word the data never earned
  if (Array.isArray(x.na)) for (const k of x.na) if (COUNTED.includes(k)) out.push(`facts.na: ${k} decides the word, so it can't be n/a`);
  if (x.notes !== undefined) notes('facts.notes', x.notes, out);
  if (Array.isArray(x.na)) for (const k of x.na) if (typeof k === 'string' && !(isObj(x.notes) && isText(x.notes[k]))) out.push(`facts.na: ${k} needs its reason in facts.notes.${k}`);
}

function runProblemsOf(r: unknown, out: string[]) {
  if (!isObj(r)) return out.push('run: should be an object');
  only('run', r, ['on', 'app', 'plan', 'install', 'panel', 'seen'], out);
  if (r.on !== undefined && (typeof r.on !== 'string' || !DAY.test(r.on) || Number.isNaN(Date.parse(r.on)))) out.push('run.on: should be the day it ran, YYYY-MM-DD');
  if (r.app !== undefined && !isText(r.app)) out.push('run.app: should be the app\'s version');
  const p = r.plan;
  if (!isObj(p)) out.push('run.plan: should be what Kiln\'s --plan read: model, android, sdk, abi, app');
  else {
    only('run.plan', p, ['model', 'android', 'sdk', 'abi', 'app'], out);
    if (!isText(p.model)) out.push('run.plan.model: should be the model adb read');
    if (!isText(p.android)) out.push('run.plan.android: should be the Android version, e.g. 14');
    if (!Number.isInteger(p.sdk) || (p.sdk as number) < 23) out.push('run.plan.sdk: should be the API level, 23 or more');
    if (!(ABIS as readonly unknown[]).includes(p.abi)) out.push(`run.plan.abi: should be one of ${ABIS.join(', ')}`);
    if (typeof p.app !== 'string' || !PACKAGE.test(p.app)) out.push('run.plan.app: should be the package Kiln installed');
  }
  const i = r.install;
  if (!isObj(i)) out.push('run.install: should be what Kiln\'s --install reported: grants, silentUpdates, home, setupPage');
  else {
    only('run.install', i, ['grants', 'silentUpdates', 'home', 'setupPage'], out);
    const grants = ['microphone', 'camera', 'secure-settings', 'overlay', 'device-admin'];
    if (!isObj(i.grants) || Object.entries(i.grants).some(([k, v]) => !grants.includes(k) || !['granted', 'not-asked', 'refused'].includes(v as string))) {
      out.push(`run.install.grants: should map ${grants.join(', ')} to granted, not-asked or refused`);
    }
    if (!['allowed', 'declined', 'refused', 'old-android', 'not-asked'].includes(i.silentUpdates as string)) out.push('run.install.silentUpdates: should be --install\'s silentUpdates.state');
    const h = i.home;
    if (isObj(h)) only('run.install.home', h, ['how', 'state'], out);
    if (!isObj(h) || !['set-home-activity', 'chooser'].includes(h.how as string) || !['set', 'unconfirmed'].includes(h.state as string)) out.push('run.install.home: should be { how: set-home-activity or chooser, state: set or unconfirmed }');
    if (typeof i.setupPage !== 'boolean') out.push('run.install.setupPage: should be true or false');
  }
  if (r.panel !== undefined) {
    if (!isObj(r.panel)) out.push('run.panel: should be what the panel reports, { silentUpdates }');
    else {
      only('run.panel', r.panel, ['silentUpdates'], out);
      if (r.panel.silentUpdates !== undefined && (typeof r.panel.silentUpdates !== 'string' || !SENSOR.test(r.panel.silentUpdates))) out.push('run.panel.silentUpdates: the panel\'s Silent updates sensor, word for word ("Yes: …" or "No: …")');
    }
  }
  const s = r.seen;
  if (!isObj(s)) return out.push('run.seen: should be an object, {} when nothing else was seen');
  only('run.seen', s, ['hardware', 'kiosk', 'setup', 'notes'], out);
  if (s.hardware !== undefined) texts('run.seen.hardware', s.hardware, out);
  if (s.kiosk !== undefined) bools('run.seen.kiosk', s.kiosk, Object.keys(KIOSK_CHECKS), out);
  if (s.setup !== undefined) words('run.seen.setup', s.setup, Object.keys(SETUP_WAYS), out, true);
  if (s.notes !== undefined) notes('run.seen.notes', s.notes, out);
}

/**
 * What's wrong with a frame's `facts` or `run`, one line per problem; empty when it's fine.
 * glassworks.dev's build, Kiln's frame-designs test and this package's tests run it. A tested device has a `run` and no `facts`; any other kind has `facts` and no `run`.
 * A device the community tested (`tested.by`) may have no run: its report is the record, and every item stays unverified.
 */
export function factProblems(f: Pick<FrameDesign, 'kind'> & { facts?: unknown; run?: unknown; tested?: { by?: unknown } }): string[] {
  const out: string[] = [];
  if (f.kind === 'tested') {
    if (f.facts !== undefined) out.push('facts: a tested device\'s come from its run on a real unit; record them in run');
    if (f.run !== undefined) runProblemsOf(f.run, out);
    else if (f.tested?.by !== 'community') out.push('run: a tested device records its run on a real unit (frames/README.md#a-tested-devices-run)');
  } else {
    if (f.run !== undefined) out.push('run: only a tested device has one; record what this frame\'s panels show in facts');
    if (f.facts !== undefined) factProblemsOf(f.facts, out);
  }
  return out;
}
