// A device report from Kiln: the fields of .github/ISSUE_TEMPLATE/device-report.yml, and the issue they become. The same
// code runs in the page (its preview) and in the route (/api/report), so what the preview shows is what GitHub gets: the
// route rebuilds the title and body from these fields alone and never takes a body from the browser.
//
// Every field is whitelisted, length-limited and checked here. Text that looks like an address, a MAC, a serial, an email,
// a token or a URL with a password in it becomes "(removed)" before anything else sees it. User text then goes into
// GitHub's markdown escaped: no HTML, no links or images, no @mentions and no issue references that would ping or
// notify anyone.

export const REPO = 'GlassworksHQ/glassworks';
/** The issue template whose fields a report has, and its label (the template's `labels`). */
export const TEMPLATE = 'device-report.yml';
export const LABEL = 'hardware report';

/** How the device got Glassworks: the template's `flow` options, word for word. */
export const FLOWS = {
  usb: 'Kiln over USB from a computer',
  browser: 'Kiln in the device\'s own browser',
  sideload: 'Sideloaded the APK',
  other: 'Other',
} as const;
export type Flow = keyof typeof FLOWS;

/** Stock or custom Android: the template's `os_kind` options, word for word. */
export const OS_KINDS = {
  stock: 'Stock (the maker\'s own Android)',
  custom: 'Custom (LineageOS or another build)',
} as const;
export type OsKind = keyof typeof OS_KINDS;

/** How far Kiln got when the report was opened. Not a template field: the body says it under "From Kiln". */
export const STEPS = {
  done: 'Done: the install finished',
  stopped: 'The install stopped',
  device: 'Device: Kiln named it',
  get: 'Get the app, in the device\'s browser',
  setup: 'Open Glassworks, in the device\'s browser',
  kiln: 'Finish with Kiln, in the device\'s browser',
} as const;
export type Step = keyof typeof STEPS;

/** Clarity's grades, best first (docs/clarity.md). */
export const GRADES = ['A+', 'A', 'B', 'C', 'D', 'E'] as const;

export interface ReportFields {
  model: string;
  android: string;
  osKind: OsKind | '';
  customOs: string;
  abi: string;
  flow: Flow | '';
  step: Step | '';
  worked: string;
  didnt: string;
  clarity: string;
  appVersion: string;
}

/** What Kiln read and may fill in: never a serial, a MAC, an address, a network, Home Assistant's URL or a token. */
export type ReportPrefill = Partial<Pick<ReportFields, 'model' | 'android' | 'abi' | 'flow' | 'step' | 'appVersion' | 'osKind' | 'customOs'>>;

export const EMPTY: ReportFields = { model: '', android: '', osKind: '', customOs: '', abi: '', flow: '', step: '', worked: '', didnt: '', clarity: '', appVersion: '' };

/** Each field's longest value, in characters. */
export const LIMITS: Record<keyof ReportFields, number> = {
  model: 100, android: 40, osKind: 10, customOs: 100, abi: 80, flow: 10, step: 10, worked: 4000, didnt: 4000, clarity: 2, appVersion: 40,
};
/**
 * The longest request the route reads, in bytes: every field at its limit in the widest UTF-8 a JavaScript character can
 * take (3 bytes for each UTF-16 unit; JSON's escapes of cleaned text are no wider), and room for the keys and the token.
 */
export const MAX_BYTES = 3 * Object.values(LIMITS).reduce((a, b) => a + b, 0) + 2048;

/** The characters a one-line field may hold (after the privacy pass). */
const ONE_LINE: Partial<Record<keyof ReportFields, RegExp>> = {
  model: /^[\p{L}\p{N} ()+.,'&/_-]*$/u,
  customOs: /^[\p{L}\p{N} ()+.,'&/_-]*$/u,
  android: /^[\p{L}\p{N} ().]*$/u,
  abi: /^[A-Za-z0-9_, -]*$/,
  appVersion: /^(?:\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)?$/,
};

const REMOVED = '(removed)';
/** What a one-line field can't hold: clean() drops it. */
const NOT_NAME = /[^\p{L}\p{N} ()+.,'&/_-]/gu;
const NOT_ANDROID = /[^\p{L}\p{N} ().]/gu;
const NOT_ABI = /[^A-Za-z0-9_, -]/g;

/** Hosts a link in a report may point at: the project's own. Any other URL may be someone's home, so it goes. */
export const LINK_HOSTS = ['github.com', 'glassworks.dev', 'www.glassworks.dev', 'kiln.glassworks.dev'];

/** A URL, with a scheme or starting www. */
const URL_RE = /\b(?:[a-z][a-z0-9+.-]*:\/\/|www\.)[^\s<>`\\]+/gi;

const hostOf = (u: string): string | undefined => {
  try { return new URL(/^www\./i.test(u) ? `https://${u}` : u).hostname.toLowerCase(); } catch { return undefined; }
};
/** A URL on one of LINK_HOSTS, over https, with no user, password or port. */
const linkable = (u: string) => {
  try {
    const x = new URL(/^www\./i.test(u) ? `https://${u}` : u);
    return x.protocol === 'https:' && !x.username && !x.password && !x.port && LINK_HOSTS.includes(hostOf(u) ?? '');
  } catch { return false; }
};

/** What looks private, most specific first, after URLs. Each match becomes "(removed)". */
const PRIVATE: RegExp[] = [
  // a local name (Home Assistant's own address), with its port and path
  /\b[a-z0-9.-]+\.(?:local|lan|home|internal|localdomain|home\.arpa)\b(?::\d+)?(?:\/\S*)?/gi,
  // Home Assistant's cloud addresses name one home
  /\b[a-z0-9-]+\.ui\.nabu\.casa\S*/gi,
  // an email
  /\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b/g,
  // a JWT (Home Assistant's long-lived tokens are one)
  /\beyJ[\w-]+\.[\w-]+\.[\w-]+/g,
  // a MAC or an EUI-64, with : or - between bytes, or a dotted one
  /\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}(?:(?:[:-][0-9a-f]{2}){2})?\b/gi,
  /\b[0-9a-f]{4}\.[0-9a-f]{4}\.[0-9a-f]{4}\b/gi,
  // an IPv4 address (with a port or a prefix)
  /\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+|\/\d+)?\b/g,
  // an IPv6 address: eight groups, or a group or more with :: (not C++'s std::string, not a lone ::)
  /(?<![\w:])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){7}|[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?|::[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)(?![\w:])/gi,
  // a long run of digits (an IMEI, a phone number)
  /\b\d{12,}\b/g,
  // a serial: 11 or more letters and digits with 4 digits or more (model codes and SoC names are shorter or have dashes)
  /\b(?=(?:[A-Za-z]*\d){4})(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{11,}\b/g,
];

/** The privacy pass: a URL off LINK_HOSTS, and every match of PRIVATE, becomes "(removed)". */
export function scrub(text: string): string {
  let t = text.replace(URL_RE, (u) => (linkable(u.replace(/[.,;:!?)]+$/, '')) ? u : REMOVED));
  for (const p of PRIVATE) t = t.replace(p, REMOVED);
  return t;
}

/** One line: no control characters, spaces collapsed, trimmed. */
const line = (s: string) => s.replace(/[\p{Cc}\p{Cf}\u2028\u2029]/gu, ' ').replace(/\s+/g, ' ').trim();
/** Several lines: \r\n to \n, no control characters but newlines and tabs, at most two blank lines in a row. */
const lines = (s: string) => s.replace(/\r\n?/g, '\n').replace(/[\p{Cf}\u2028\u2029]/gu, '').replace(/[^\P{Cc}\n\t]/gu, ' ').replace(/\n{3,}/g, '\n\n').trim();

/** A field as the report sends it: cleaned and scrubbed, but not escaped. */
export function clean(f: ReportFields): ReportFields {
  // until nothing changes: dropping a character can join two runs into something private (ACME:AB12345678), so
  // cleaning what's cleaned is a no-op and the preview is what the route sends
  const settle = (s: string, step: (x: string) => string) => { let t = step(s); for (let i = 0; i < 5; i++) { const n = step(t); if (n === t) break; t = n; } return t; };
  const one = (s: string, keep: RegExp) => settle(s, (x) => line(scrub(line(x)).replace(keep, '')));
  const text = (s: string) => settle(s, (x) => scrub(lines(x)));
  return {
    model: one(f.model, NOT_NAME), android: one(f.android, NOT_ANDROID), osKind: f.osKind, customOs: one(f.customOs, NOT_NAME),
    abi: one(f.abi, NOT_ABI).replace(/\s*,\s*/g, ','),
    flow: f.flow, step: f.step, worked: text(f.worked), didnt: text(f.didnt),
    clarity: line(f.clarity).toUpperCase(), appVersion: line(f.appVersion).replace(/^v/i, ''),
  };
}

export type Problems = Partial<Record<keyof ReportFields, string>>;

/** What's wrong with the fields (already cleaned), in Kiln's words, by field; empty when they can be sent. */
export function problems(f: ReportFields): Problems {
  const p: Problems = {};
  if (!f.model) p.model = 'Enter the device model.';
  if (!f.android) p.android = 'Enter the Android version it runs.';
  if (!f.osKind || !Object.hasOwn(OS_KINDS, f.osKind)) p.osKind = 'Choose stock or custom Android.';
  if (!f.flow || !Object.hasOwn(FLOWS, f.flow)) p.flow = 'Choose how you installed Glassworks.';
  if (f.step && !Object.hasOwn(STEPS, f.step)) p.step = 'Choose a step from the list, or leave it blank.';
  if (!f.worked) p.worked = 'Share what worked, even if nothing did yet.';
  if (!f.didnt) p.didnt = 'Share what didn\'t work, or write "Nothing so far".';
  if (f.clarity && !(GRADES as readonly string[]).includes(f.clarity)) p.clarity = 'Choose a grade from A+ to E, or leave it blank.';
  for (const k of Object.keys(LIMITS) as (keyof ReportFields)[]) {
    if (f[k].length > LIMITS[k]) p[k] = `Keep this under ${LIMITS[k]} characters.`;
    else if (ONE_LINE[k] && !ONE_LINE[k].test(f[k])) p[k] ??= k === 'appVersion' ? 'Use a version number like 0.2.0.' : 'Use only letters, numbers, and simple punctuation.';
  }
  return p;
}

/** The fields from untrusted JSON: only the known keys, only strings, everything else dropped. */
export function fieldsOf(raw: unknown): ReportFields | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const r = raw as Record<string, unknown>;
  const out = { ...EMPTY };
  for (const k of Object.keys(EMPTY) as (keyof ReportFields)[]) {
    const v = r[k];
    if (v === undefined) continue;
    if (typeof v !== 'string') return undefined;
    (out as Record<string, string>)[k] = v;
  }
  return out;
}

/**
 * User text as GitHub markdown that renders as the words typed: HTML as text, links and images as text, and each
 * @mention, issue reference and URL in a code span, where GitHub neither notifies, links nor fetches anything.
 */
export function escapeMarkdown(text: string): string {
  return text.split('\n').map((l) => {
    const spans: string[] = [];
    const keep = (s: string) => { spans.push(s); return `\u0000${spans.length - 1}\u0000`; };
    // the person's own backticks become apostrophes, so the only code spans are the ones made here
    let t = l.replace(/`/g, '\'')
      .replace(/\b(?:[a-z][a-z0-9+.-]*:\/\/|www\.)[^\s<>\\]+/gi, (u) => keep(u))
      // every @name, whatever is before it: what comes before can change once the text around it is escaped
      .replace(/@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\/[A-Za-z0-9._-]+)?/g, (m) => keep(m))
      .replace(/(?:\b[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+)?#\d+\b|\bGH-\d+\b/gi, (m) => keep(m));
    t = t
      .replace(/[\\*_[\]<>|~]/g, (c) => (c === '<' ? '&lt;' : c === '>' ? '&gt;' : `\\${c}`))
      .replace(/&(?![lg]t;)/g, '&amp;')
      .replace(/^(\s*)([#=+-]|\d+[.)])/, '$1\\$2');
    // two spans side by side would read as one run of two backticks: a space keeps them apart
    return t.replace(/\u0000(\d+)\u0000/g, (_, i: string) => `\`${spans[Number(i)]!}\``).replace(/``/g, '` `');
  }).join('\n');
}

const NONE = '_No response_';
const val = (s: string) => (s ? escapeMarkdown(s) : NONE);

/** The issue a report opens: its title and body, from the cleaned fields only, in the issue form's layout. */
export function issueOf(f: ReportFields): { title: string; body: string } {
  const sections: [string, string][] = [
    ['Device model', val(f.model)],
    ['Android version', val(f.android)],
    ['Stock or custom Android?', f.osKind ? OS_KINDS[f.osKind] : NONE],
    ['Which custom OS?', val(f.customOs)],
    ['ABI', val(f.abi)],
    ['How did you install it?', f.flow ? FLOWS[f.flow] : NONE],
    ['What worked', val(f.worked)],
    ['What didn\'t work', val(f.didnt)],
    ['Clarity grade', val(f.clarity)],
    ['Glassworks version', val(f.appVersion)],
  ];
  const from = `Sent from Kiln, without a GitHub account${f.step ? `. Kiln had reached: ${STEPS[f.step]}` : ''}.`;
  const body = `${sections.map(([h, v]) => `### ${h}\n\n${v}`).join('\n\n')}\n\n---\n\n_${from}_\n`;
  // the title is plain text on GitHub: the model as cleaned, nothing escaped
  return { title: `Tested: ${f.model || 'an Android device'}`.slice(0, 120), body };
}

/** The longest address GitHub's form opens from, in characters as sent: under GitHub's limit, with room for headers. */
export const FORM_URL_MAX = 7500;

/** How a value is sent in the address (URLSearchParams: UTF-8, percent-encoded, spaces as +). */
const encoded = (k: string, v: string) => new URLSearchParams({ [k]: v }).toString().length;

/** The longest start of `v` whose encoding fits `budget`, never ending in half of a surrogate pair. */
function fit(k: string, v: string, budget: number): string {
  if (encoded(k, v) <= budget) return v;
  let lo = 0, hi = v.length;
  while (lo < hi) { const mid = Math.ceil((lo + hi) / 2); if (encoded(k, v.slice(0, mid)) <= budget) lo = mid; else hi = mid - 1; }
  const end = lo > 0 && /[\uD800-\uDBFF]/.test(v[lo - 1]!) ? lo - 1 : lo;
  return v.slice(0, end);
}

/**
 * The template on GitHub with the fields filled in, for someone who'd rather send it themselves or when Kiln can't send it:
 * the same cleaned fields, unescaped (GitHub's form shows them before anything is posted). "Kiln got to" stays out: the
 * template has no field for it. What worked and what didn't are cut, as encoded, to keep the whole address under
 * FORM_URL_MAX; `cut` says whether either lost anything, and the sheet then says so.
 */
export function githubForm(raw: ReportFields): { url: string; cut: boolean } {
  const f = clean(raw);
  const q = new URLSearchParams({ template: TEMPLATE, title: issueOf(f).title });
  const put = (k: string, v: string) => { if (v) q.set(k, v); };
  put('model', f.model);
  put('android', f.android);
  if (f.osKind) q.set('os_kind', OS_KINDS[f.osKind]);
  put('custom_os', f.customOs);
  put('abi', f.abi);
  if (f.flow) q.set('flow', FLOWS[f.flow]);
  put('clarity', f.clarity);
  put('app_version', f.appVersion);
  const base = `https://github.com/${REPO}/issues/new?${q.toString()}`;
  // what's left for the two answers (each with its & before it), shared: one that needs less leaves the rest to the other
  const left = FORM_URL_MAX - base.length;
  const need = (k: string, v: string) => (v ? encoded(k, v) + 1 : 0);
  const wBudget = Math.max(Math.floor(left / 2), left - need('didnt', f.didnt));
  const worked = f.worked ? fit('worked', f.worked, wBudget - 1) : '';
  const didnt = f.didnt ? fit('didnt', f.didnt, left - need('worked', worked) - 1) : '';
  const p = new URLSearchParams();
  if (worked) p.set('worked', worked);
  if (didnt) p.set('didnt', didnt);
  const tail = p.toString();
  return { url: tail ? `${base}&${tail}` : base, cut: worked !== f.worked || didnt !== f.didnt };
}

export const githubUrl = (raw: ReportFields): string => githubForm(raw).url;
/** Whether GitHub's form gets less of what worked or what didn't than the person wrote. */
export const githubCuts = (raw: ReportFields): boolean => githubForm(raw).cut;

/** The report's fields from what Kiln read, with the rest empty. */
export const prefilled = (p: ReportPrefill): ReportFields => ({ ...EMPTY, ...Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined)) });
