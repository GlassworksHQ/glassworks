#!/usr/bin/env node
// Vitro's locale tables (claim 4d of the exp/vitro review): what HA's frontend gets from the browser to show
// numbers, dates and times in the signed-in user's language and formats, captured once from the browser the parity
// harness renders HA with.
//
// HA's frontend formats everything with the browser's `Intl` (formatNumber, formatDateTime, relativeTime, useAmPm,
// firstWeekday). Vitro draws natively, so it gets the same answers from a table (TableIntlSource, the maintainer's
// choice over the panel's own ICU, 2026-11-25): for every language the pinned frontend ships (plus the number locales
// HA's number_format picks, "en-US" and "de-CH"), this script asks Chromium's Intl for
//   - numbers: decimal and group marks, grouping sizes, minimum grouping digits, digits, minus sign;
//   - plural categories of the integers 0-199 (Intl.PluralRules), for relative times;
//   - relative times (Intl.RelativeTimeFormat, numeric "auto", HA's default "long" style) and unit amounts;
//   - every Intl.DateTimeFormat option set HA's common/datetime functions use, as a template of fields and
//     literals, with the month, weekday and day-period names each format uses (shared in one list per locale);
//   - useAmPm's test and the first day of the week (Intl.Locale weekInfo).
//
// The tables are Unicode CLDR data (through Chromium's and Node's ICU), under the Unicode License v3
// (LICENSES/Unicode-3.0.txt). They ship in the APKs, so THIRD-PARTY-NOTICES.md names them ("Vitro's locale tables")
// with the Chromium version languages.json records: update that entry when a new capture changes the browser. The
// test string files are HA frontend strings (Apache-2.0), listed there too.
//
// HA's UI words aren't bundled: Vitro reads them from the signed-in HA (HaFrontend). For the tests only, this script
// also copies HA's English, German and French for every frontend key Vitro's code names from the pinned frontend
// (core, then the fragments) into engines/engine-vitro/src/test/resources/…/locale/strings-<lang>.json, and fails on
// a key the frontend doesn't have.
//
// Touches: reads the pinned frontend (tools/perf/hafrontend.py fetches it into ~/.cache/glassworks-perf on first
// use), reads engines/engine-vitro's Kotlin sources, launches headless Chromium (playwright-core; CHROME=<path>, or
// Google Chrome) on about:blank with no network, and writes
// engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/locale/ (<tag>.json per locale and
// languages.json) and the three test string files. No Home Assistant, no device.
//
//   node tools/vitro-parity/locale-data.mjs
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const OUT = join(ROOT, 'engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/locale');
const SRC = join(ROOT, 'engines/engine-vitro/src/main/java');
const TEST_OUT = join(ROOT, 'engines/engine-vitro/src/test/resources/dev/glassworks/engine/vitro/locale');
// The languages the tests and the parity corpus read in.
const TEST_LANGUAGES = ['en', 'de', 'fr'];
const FRONTEND = process.env.GW_PERF_FRONTEND
  || execFileSync('python3', [join(ROOT, 'tools/perf/hafrontend.py')], { encoding: 'utf8' }).trim();
const FRONTEND_VERSION = (readFileSync(join(ROOT, 'tools/perf/hafrontend.py'), 'utf8').match(/VERSION = "([^"]+)"/) || [])[1];

// The number locales formatNumber picks for a number_format other than "language" (numberFormatToLocale).
const NUMBER_TAGS = ['en-US', 'de-CH', 'it', 'sv', 'cs'];

function translationFiles(dir) {
  const out = {};
  for (const f of readdirSync(dir)) {
    const m = f.match(/^(.+)-[0-9a-f]{32}\.json$/);
    if (m) out[m[1]] = join(dir, f);
  }
  return out;
}

function flatten(tree, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(tree)) {
    if (v && typeof v === 'object') flatten(v, `${prefix}${k}.`, out);
    else out[`${prefix}${k}`] = v;
  }
  return out;
}

function kotlinFiles(dir, out = []) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) kotlinFiles(p, out);
    else if (f.endsWith('.kt')) out.push(p);
  }
  return out;
}

/**
 * Every key Vitro's code asks HaText for: any string literal shaped like a frontend key (`"ui.card.lock.lock"`,
 * `"state.default.unknown"`), wherever it is passed from. A literal that isn't one of HA's keys fails the run below.
 */
function usedKeys() {
  const keys = new Set();
  for (const f of kotlinFiles(SRC)) {
    const s = readFileSync(f, 'utf8');
    for (const m of s.matchAll(/"((?:state|state_badge|ui)\.[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+)+)"/g)) keys.add(m[1]);
  }
  return [...keys].sort();
}

// Runs in the page: everything Vitro needs from Intl for one tag.
function capture(tag) {
  const out = { tag };
  // Numbers.
  const nf = new Intl.NumberFormat(tag, { maximumFractionDigits: 2 });
  const parts = nf.formatToParts(-1234567.5);
  const ro = nf.resolvedOptions();
  out.locale = ro.locale;
  const zero = new Intl.NumberFormat(tag, { useGrouping: false }).format(0);
  const groups = parts.filter((p) => p.type === 'integer').map((p) => p.value.length);
  out.number = {
    decimal: parts.find((p) => p.type === 'decimal')?.value ?? '.',
    group: parts.find((p) => p.type === 'group')?.value ?? ',',
    minus: parts.find((p) => p.type === 'minusSign')?.value ?? '-',
    // 1,234,567: the last group is the primary size, the one before it the secondary (12,34,567 in Hindi).
    primary: groups[groups.length - 1] ?? 3,
    secondary: groups.length > 2 ? groups[groups.length - 2] : (groups[groups.length - 1] ?? 3),
    // "1234" in Spanish and Polish: grouping only from five digits.
    minGrouping: nf.formatToParts(1234).some((p) => p.type === 'group') ? 1 : 2,
    zero,
    // Literals around a negative number other than the minus sign (bidi marks in Arabic).
    prefix: parts.slice(0, parts.findIndex((p) => p.type === 'integer')).filter((p) => p.type === 'literal').map((p) => p.value).join(''),
  };
  // Plural categories of 0..199 (z one t f m o).
  const pr = new Intl.PluralRules(tag);
  const letter = { zero: 'z', one: '1', two: '2', few: 'f', many: 'm', other: 'o' };
  out.plural = Array.from({ length: 200 }, (_, n) => letter[pr.select(n)]).join('');
  const cats = [...new Set(Array.from({ length: 200 }, (_, n) => pr.select(n)))];
  const sample = (cat) => { for (let n = 2; n < 200; n++) if (pr.select(n) === cat) return n; for (let n = 0; n < 2; n++) if (pr.select(n) === cat) return n; return 5; };
  const pattern = (ps) => {
    let s = '';
    let inNumber = false;
    for (const p of ps) {
      if (['integer', 'group', 'decimal', 'fraction'].includes(p.type)) { if (!inNumber) s += '{0}'; inNumber = true; } else { s += p.value; inNumber = false; }
    }
    return s;
  };
  // Relative times: numeric "auto" phrases for -2..2 and the past/future pattern per plural category.
  const units = ['second', 'minute', 'hour', 'day', 'week', 'month', 'quarter', 'year'];
  out.relative = {};
  for (const style of ['long']) {
    const auto = new Intl.RelativeTimeFormat(tag, { numeric: 'auto', style });
    const always = new Intl.RelativeTimeFormat(tag, { numeric: 'always', style });
    const byUnit = {};
    for (const unit of units) {
      const special = {};
      for (const v of [-2, -1, 0, 1, 2]) {
        const a = auto.format(v, unit);
        if (a !== always.format(v, unit)) special[v] = a;
      }
      // -0 and +0 differ in numeric "always" ("0 seconds ago" vs "in 0 seconds"); auto gives one "now"-like phrase.
      const past = {}; const future = {};
      for (const c of cats) {
        const n = sample(c);
        past[letter[c]] = pattern(always.formatToParts(-n, unit));
        future[letter[c]] = pattern(always.formatToParts(n, unit));
      }
      byUnit[unit] = { special, past, future };
    }
    out.relative = byUnit;
  }
  // Durations without tense (relativeTime's includeTense false): Intl.NumberFormat style "unit".
  out.unit = {};
  for (const style of ['long']) {
    const byUnit = {};
    for (const unit of ['second', 'minute', 'hour', 'day', 'week', 'month', 'year']) {
      const f = new Intl.NumberFormat(tag, { style: 'unit', unit, unitDisplay: style });
      const byCat = {};
      for (const c of cats) byCat[letter[c]] = pattern(f.formatToParts(sample(c)));
      byUnit[unit] = byCat;
    }
    out.unit = byUnit;
  }
  // useAmPm's test (time_format "language") and the first weekday (first_weekday "language").
  out.amPm = new Date('January 1, 2023 22:00:00').toLocaleString(tag).includes('10');
  try {
    const loc = new Intl.Locale(tag);
    const info = typeof loc.getWeekInfo === 'function' ? loc.getWeekInfo() : loc.weekInfo;
    out.firstDay = info ? info.firstDay % 7 : 1;
  } catch { out.firstDay = 1; }
  // Dates: HA's option sets (common/datetime/format_date.ts, format_time.ts, format_date_time.ts).
  const dateOpts = {
    weekdayMonthDay: { weekday: 'long', month: 'long', day: 'numeric' },
    date: { year: 'numeric', month: 'long', day: 'numeric' },
    dateShort: { year: 'numeric', month: 'short', day: 'numeric' },
    dateNumeric: { year: 'numeric', month: 'numeric', day: 'numeric' },
    dateVeryShort: { day: 'numeric', month: 'short' },
    monthYear: { month: 'long', year: 'numeric' },
    month: { month: 'long' },
    monthShort: { month: 'short' },
    year: { year: 'numeric' },
    weekday: { weekday: 'long' },
    weekdayShort: { weekday: 'short' },
    weekdayNarrow: { weekday: 'narrow' },
    weekdayShortDate: { weekday: 'short', month: 'short', day: 'numeric' },
    weekdayShortDateYear: { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' },
    dayNumeric: { day: 'numeric' },
    // The clock card's date parts (clock-date-format.ts), each its own Intl.DateTimeFormat.
    day2: { day: '2-digit' },
    monthNumeric: { month: 'numeric' },
    month2: { month: '2-digit' },
    year2: { year: '2-digit' },
  };
  const timeOpts = (h12) => ({
    time: { hour: 'numeric', minute: '2-digit' },
    timeSeconds: { hour: h12 ? 'numeric' : '2-digit', minute: '2-digit', second: '2-digit' },
    weekdayTime: { weekday: 'long', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit' },
    hour: { hour: 'numeric' },
    dateTime: { year: 'numeric', month: 'long', day: 'numeric', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit' },
    dateTimeShortYear: { year: 'numeric', month: 'short', day: 'numeric', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit' },
    shortDateTime: { month: 'short', day: 'numeric', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit' },
    shortDateTimeSeconds: { month: 'short', day: 'numeric', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit', second: '2-digit' },
    dateTimeSeconds: { year: 'numeric', month: 'long', day: 'numeric', hour: h12 ? 'numeric' : '2-digit', minute: '2-digit', second: '2-digit' },
  });
  const dtf = new Intl.DateTimeFormat(tag);
  out.calendar = dtf.resolvedOptions().calendar;
  // A date whose day, month, hour, minute and second all have one digit tells numeric from 2-digit: 2023-01-05
  // 09:07:03 UTC (a Thursday), or in the Persian calendar 2023-03-22 (2 Farvardin 1402).
  const probe = out.calendar === 'persian' ? Date.UTC(2023, 2, 22, 9, 7, 3) : Date.UTC(2023, 0, 5, 9, 7, 3);
  out.dateZero = new Intl.NumberFormat(tag, { numberingSystem: dtf.resolvedOptions().numberingSystem, useGrouping: false }).format(0);
  const toAscii = (s) => s.replace(/\p{Nd}/gu, (d) => String((d.codePointAt(0) - out.dateZero.codePointAt(0) + 10) % 10));
  const template = (opts) => {
    const f = new Intl.DateTimeFormat(tag, { ...opts, timeZone: 'UTC' });
    const tokens = [];
    for (const p of f.formatToParts(probe)) {
      const v = toAscii(p.value);
      switch (p.type) {
        case 'literal': tokens.push(p.value.replace(/\{/g, '{{')); break;
        case 'year': tokens.push(opts.year === '2-digit' ? '{yy}' : '{y}'); break;
        case 'month': tokens.push(/^\d+$/.test(v) ? (v.length === 2 ? '{MM}' : '{M}') : '{Mt}'); break;
        case 'day': tokens.push(v.length === 2 ? '{dd}' : '{d}'); break;
        case 'weekday': tokens.push('{E}'); break;
        case 'hour': tokens.push(f.resolvedOptions().hourCycle === 'h12' || f.resolvedOptions().hourCycle === 'h11'
          ? (v.length === 2 ? '{hh}' : '{h}') : (v.length === 2 ? '{HH}' : '{H}')); break;
        case 'minute': tokens.push('{mm}'); break;
        case 'second': tokens.push('{ss}'); break;
        case 'dayPeriod': tokens.push('{a}'); break;
        // The era (Thai "พ.ศ."): the same for every date Vitro shows, so its text is part of the template.
        case 'era': tokens.push(p.value.replace(/\{/g, '{{')); break;
        default: throw new Error(`${tag}: the ${p.type} field of ${JSON.stringify(opts)} has no template token`);
      }
    }
    const fmt = { t: tokens.join('') };
    const names = (type, dates) => dates.map((d) => f.formatToParts(d).find((p) => p.type === type)?.value ?? '');
    // Month names by the locale calendar's own month: Gregorian, or Persian (1402 began on 2023-03-21; its first
    // six months have 31 days, the next five 30), where Vitro computes the Persian date itself.
    const monthDates = out.calendar === 'persian'
      ? Array.from({ length: 12 }, (_, m) => Date.UTC(2023, 2, 21 + (m < 6 ? m * 31 : 186 + (m - 6) * 30) + 10, 12))
      : Array.from({ length: 12 }, (_, m) => Date.UTC(2023, m, 15, 12));
    if (tokens.includes('{Mt}')) fmt.months = names('month', monthDates);
    // 2023-01-01 was a Sunday.
    if (tokens.includes('{E}')) fmt.weekdays = names('weekday', Array.from({ length: 7 }, (_, d) => Date.UTC(2023, 0, 1 + d, 12)));
    if (tokens.includes('{a}')) fmt.dayPeriods = names('dayPeriod', [Date.UTC(2023, 0, 5, 9), Date.UTC(2023, 0, 5, 21)]);
    const hc = f.resolvedOptions().hourCycle;
    if (hc) fmt.hc = hc;
    return fmt;
  };
  out.date = {};
  for (const [name, opts] of Object.entries(dateOpts)) out.date[name] = template(opts);
  for (const h12 of [true, false]) {
    for (const [name, opts] of Object.entries(timeOpts(h12))) out.date[`${name}.${h12 ? 'h12' : 'h23'}`] = template({ ...opts, hourCycle: h12 ? 'h12' : 'h23' });
  }
  return out;
}

const browser = await (async () => {
  const opts = process.env.CHROME ? { executablePath: process.env.CHROME } : {};
  try { return await chromium.launch({ headless: true, ...opts }); } catch (e) {
    if (process.env.CHROME) throw e;
    return chromium.launch({ headless: true, channel: 'chrome' });
  }
})();
const page = await browser.newPage();
await page.route('**/*', (r) => r.abort());
const browserVersion = browser.version();

const core = translationFiles(join(FRONTEND, 'static/translations'));
const fragments = readdirSync(join(FRONTEND, 'static/translations'), { withFileTypes: true })
  .filter((d) => d.isDirectory()).map((d) => d.name).sort();
const languages = Object.keys(core).sort();
const tags = [...new Set([...languages, ...NUMBER_TAGS])].sort();


const tables = {};
// A table stored under a tag holds that tag's own answers. Chromium's ICU leaves some languages out (it resolves
// them to en-US, or gives root data such as "M01" months); those come from Node's full ICU instead, the same
// capture, and the table says so. A language neither has fails the run.
// The whole tag must resolve to itself (canonical forms compared, and their likely-subtag expansions, so an alias such
// as "iw" for "he" still matches): an en-US capture is no en-GB table. Root data ("M01" months) is no table either.
const same = (a, b) => {
  const [ca, cb] = [Intl.getCanonicalLocales(a)[0], Intl.getCanonicalLocales(b)[0]];
  if (ca === cb) return true;
  try { return new Intl.Locale(ca).maximize().toString() === new Intl.Locale(cb).maximize().toString(); } catch { return false; }
};
const wrong = (t, tag) => !same(t.locale, tag)
  || Object.values(t.date).some((f) => (f.months || []).some((m) => /^M\d\d$/.test(m)));
const fromNode = [];
const rejected = [];
for (const tag of tags) {
  let t = await page.evaluate(capture, tag);
  if (wrong(t, tag)) {
    t = capture(tag);
    t.source = 'node';
    fromNode.push(tag);
    if (wrong(t, tag)) { rejected.push(`${tag} (resolved to ${t.locale})`); continue; }
  }
  tables[tag] = t;
}
// A tag neither runtime has gets no table and isn't in languages.json's tags: Vitro falls back to English for it
// openly (TableIntlSource.resolve), never through a table holding another locale's data.
if (rejected.length) console.error(`no Intl data in Chromium or Node, so no table (English serves them):\n  ${rejected.join('\n  ')}`);
rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
if (fromNode.length) console.log(`from Node's ICU ${process.versions.icu} (Chromium lacks them): ${fromNode.join(', ')}`);
// One list of month, weekday and day-period names per locale: a format points into it.
for (const t of Object.values(tables)) {
  const names = [];
  for (const f of Object.values(t.date)) {
    for (const k of ['months', 'weekdays', 'dayPeriods']) {
      if (!f[k]) continue;
      let i = names.findIndex((n) => JSON.stringify(n) === JSON.stringify(f[k]));
      if (i < 0) { names.push(f[k]); i = names.length - 1; }
      f[k] = i;
    }
  }
  t.names = names;
}

const keys = usedKeys();
const missing = new Set(keys);
mkdirSync(TEST_OUT, { recursive: true });
for (const lang of TEST_LANGUAGES) {
  const flat = flatten(JSON.parse(readFileSync(core[lang], 'utf8')));
  for (const frag of fragments) {
    const file = translationFiles(join(FRONTEND, 'static/translations', frag))[lang];
    if (file) for (const [k, v] of Object.entries(flatten(JSON.parse(readFileSync(file, 'utf8'))))) if (!(k in flat)) flat[k] = v;
  }
  const strings = {};
  for (const k of keys) if (typeof flat[k] === 'string') { strings[k] = flat[k]; if (lang === 'en') missing.delete(k); }
  writeFileSync(join(TEST_OUT, `strings-${lang}.json`), JSON.stringify(strings, Object.keys(strings).sort(), 1) + '\n');
}
for (const tag of Object.keys(tables)) writeFileSync(join(OUT, `${tag}.json`), JSON.stringify(tables[tag]) + '\n');
if (missing.size) {
  console.error(`keys Vitro uses that HA's frontend ${FRONTEND_VERSION} doesn't have:\n  ${[...missing].join('\n  ')}`);
  process.exitCode = 1;
}

writeFileSync(join(OUT, 'languages.json'), JSON.stringify({
  frontend: FRONTEND_VERSION,
  browser: browserVersion,
  // Where the tables marked "source": "node" came from (Chromium's own ICU version isn't visible to a page).
  node: { icu: process.versions.icu, cldr: process.versions.cldr },
  // findAvailableLanguage's list: the languages the frontend has translations for.
  languages,
  tags: Object.keys(tables).sort(),
}, null, 1) + '\n');
await browser.close();
console.log(`${tags.length} locales, ${keys.length} strings, from frontend ${FRONTEND_VERSION} and Chromium ${browserVersion}: ${OUT}`);
