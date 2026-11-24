#!/usr/bin/env node
// Vitro parity harness, Home Assistant side (#793): renders every corpus case in HA's real frontend, in
// headless Chromium at each panel's CSS size and density, and writes the reference renders that
// VitroParityHarnessTest compares Vitro against.
//
// What it touches:
//   - Home Assistant, through the credentials in ha.json (VITRO_PARITY_HA, default
//     ~/.t3/glassworks-m1/vitro-parity/ha.json). It WRITES ONE DASHBOARD ONLY: `vitro-harness` (admin-only,
//     not in the sidebar), created if missing and overwritten on every run. It never changes an entity
//     state or any other dashboard; everything else is read-only (states, themes, config, render_template).
//   - The corpus folder (default engines/engine-vitro/src/test/parity, or --root): writes refs/<frame>/<case>.png
//     and .json, refs/shared.json, and assets/ (HA's own /static images a case shows). For --root pointing
//     at a local corpus, also dashboards/<url_path>.json (the real dashboards' configs).
//   - Chromium with a throwaway profile (playwright-core; Playwright's own Chromium if installed, else
//     Google Chrome, or CHROME=<path>).
// The token and the HA origin are never printed or written: logs go through redact(), and the files hold
// only scrubbed entity states, rendered templates and themes (see scrub.mjs).
//
//   node tools/vitro-parity/capture.mjs prepare [--root <dir>]          save vitro-harness, fetch real dashboards' configs and assets
//   node tools/vitro-parity/capture.mjs capture [--root <dir>] [--frames s1,tablet] [--cases a,b] [--templates <file>]
//
// tools/vitro-parity/run.sh runs both with the Gradle steps in between; see the README.
import { chromium } from 'playwright-core';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect, loadCredentials, redact } from './lib/ha.mjs';
import { referencedEntities, scrubStates, scrubText, checkDenylist, findPrivate } from './lib/scrub.mjs';
import { parseArgs, pick } from './lib/args.mjs';
import { assetDestination, canonicalPath } from './lib/assets.mjs';
import { applyEntityEvent } from './lib/states.mjs';
import { buildDisplay } from './lib/display.mjs';
import { HARNESS, HARNESS_META, MARKER_KEY, MARKER_VALUE, notOurs } from './lib/harness.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DEFAULT_CORPUS = join(ROOT, 'engines', 'engine-vitro', 'src', 'test', 'parity');
/** CSS px of page around each card in the reference crop, so an offset card still shows. */
const MARGIN = 8;

// The kiosk's own CSS (engines/engine-gecko/src/main/assets/extension/content.js, HUI_CSS): no header.
const HUI_CSS = ':host { --header-height: 0px !important; } .header { display: none !important; } #view { padding-top: 0 !important; min-height: 100vh !important; }';

const creds = loadCredentials();
const log = (...a) => console.log(redact(a.join(' '), creds));

function fail(msg) {
  console.error(redact(msg, creds));
  process.exit(1);
}

// Whatever escapes still goes through redact(): the token and the origin never reach stderr.
process.on('uncaughtException', (e) => fail(`capture.mjs: ${e?.stack || e}`));
process.on('unhandledRejection', (e) => fail(`capture.mjs: ${e?.stack || e}`));

const USAGE = 'usage: capture.mjs prepare|capture [--root <dir>] [--frames s1,tablet] [--cases a,b] [--templates <file>]';
let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (e) {
  fail(`${e.message}\n${USAGE}`);
}
if (!['prepare', 'capture'].includes(args.cmd)) fail(USAGE);
if (!creds) fail('no Home Assistant credentials (set VITRO_PARITY_HA or create ~/.t3/glassworks-m1/vitro-parity/ha.json); the CI-safe part needs none: ./gradlew :engine-vitro:testDebugUnitTest');

const corpusRoot = resolve(args.root || DEFAULT_CORPUS);
const committed = corpusRoot === DEFAULT_CORPUS;
const allFrames = JSON.parse(readFileSync(join(DEFAULT_CORPUS, 'frames.json'), 'utf8')).frames;
let frameIds;
try {
  frameIds = pick(args.frames, allFrames.map((f) => f.id), 'frame');
} catch (e) {
  fail(e.message);
}
const frames = allFrames.filter((f) => frameIds.has(f.id));

function loadCases() {
  const cases = loadCasesAll();
  let ids;
  try {
    ids = pick(args.cases, cases.map((c) => c.id), `case in ${join(corpusRoot, 'corpus')}:`);
  } catch (e) {
    fail(e.message);
  }
  return cases.filter((c) => ids.has(c.id));
}

/** The harness dashboard: one sections view per card case, one section holding the one card. */
function harnessConfig(cases) {
  return {
    title: HARNESS_META.title,
    [MARKER_KEY]: MARKER_VALUE,
    views: cases.filter((c) => c.card).map((c) => {
      const span = c.span || 1;
      return {
        title: c.id,
        path: c.id,
        type: 'sections',
        max_columns: span,
        ...(c.theme ? { theme: c.theme } : {}),
        sections: [{ type: 'grid', ...(span > 1 ? { column_span: span } : {}), cards: [c.card] }],
      };
    }),
  };
}

async function saveHarness(ha, config, caseIds) {
  const list = await ha.call({ type: 'lovelace/dashboards/list' });
  const entry = list.find((d) => d.url_path === HARNESS);
  if (!entry) {
    log(`creating dashboard ${HARNESS} (admin-only, hidden from the sidebar)`);
    await ha.call({ type: 'lovelace/dashboards/create', ...HARNESS_META });
  } else {
    // Never overwrite a dashboard of the same name that this tool didn't make.
    let current = null;
    try {
      current = await ha.call({ type: 'lovelace/config', url_path: HARNESS, force: true });
    } catch (e) {
      if (!/config_not_found/.test(String(e.message))) throw e;
    }
    const why = notOurs(entry, current, caseIds);
    if (why) fail(`refusing to overwrite the dashboard ${HARNESS}: ${why}. Rename or delete it in Home Assistant, or point this tool at another HA.`);
  }
  // The only write this tool makes to Home Assistant.
  await ha.call({ type: 'lovelace/config/save', url_path: HARNESS, config });
}

/** HA's own static images a card shows (`image: /static/...`), saved so Vitro's fixture server can serve them. */
function imagePaths(card) {
  const out = new Set();
  const walk = (o) => {
    if (Array.isArray(o)) o.forEach(walk);
    else if (o && typeof o === 'object') for (const [k, v] of Object.entries(o)) {
      if (/image$/.test(k) && typeof v === 'string' && v.startsWith('/')) out.add(v.split('?')[0]);
      else walk(v);
    }
  };
  walk(card);
  return [...out];
}

/** Saves an image HA serves at [path] (fetched from [url], default the path) under assets/. */
async function fetchAsset(path, url = path) {
  // Checked before the fetch: a path that leaves assets/ (or /static/, for the committed corpus) is never requested.
  const out = assetDestination(join(corpusRoot, 'assets'), path, committed);
  if ((url !== path && !url.startsWith(path + '?')) || canonicalPath(url) !== path) throw new Error(`asset ${path}: fetch URL is another path`);
  const res = await fetch(creds.origin + url, { headers: { Authorization: `Bearer ${creds.token}` } });
  if (!res.ok) throw new Error(`asset ${path}: HTTP ${res.status}`);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, Buffer.from(await res.arrayBuffer()));
  log(`asset ${path}`);
}

async function prepare() {
  const ha = await connect(creds);
  const cases = loadCases();
  const cardCases = cases.filter((c) => c.card);
  if (cardCases.length) {
    const all = loadCasesAll();
    await saveHarness(ha, harnessConfig(all), all.filter((c) => c.card).map((c) => c.id));
    log(`saved ${HARNESS}: ${cardCases.length} card views`);
  }
  for (const c of cases) for (const p of c.card ? imagePaths(c.card) : []) await fetchAsset(p);
  for (const c of cases.filter((c) => c.dashboard)) {
    if (committed) fail(`${c.id}: real dashboards belong in a local corpus (--root), never in the committed one`);
    const cfg = await ha.call({ type: 'lovelace/config', url_path: c.dashboard, force: true });
    mkdirSync(join(corpusRoot, 'dashboards'), { recursive: true });
    writeFileSync(join(corpusRoot, 'dashboards', `${c.dashboard}.json`), JSON.stringify(cfg, null, 1) + '\n');
    // The images the dashboard shows directly (`image: /local/...`), for Vitro's fixture server. A missing one
    // would make Vitro draw the card without its picture and count that as drift, so it fails the run instead.
    const missing = [];
    for (const p of imagePaths(cfg)) await fetchAsset(p).catch((e) => missing.push(scrubText(`${p}: ${e.message}`, creds)));
    if (missing.length) fail(`${c.id}: ${missing.length} dashboard image(s) couldn't be fetched, so the case can't be compared:\n  ${missing.join('\n  ')}`);
    log(`dashboard ${c.dashboard}`);
  }
  ha.close();
}

// prepare saves every card case so a later --cases capture still finds its view.
function loadCasesAll() {
  const dir = join(corpusRoot, 'corpus');
  return readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => ({ id: f.replace(/\.json$/, ''), ...JSON.parse(readFileSync(join(dir, f), 'utf8')) }));
}

function launchOptions() {
  if (process.env.CHROME) return { executablePath: process.env.CHROME };
  return {};
}

async function launch() {
  try {
    return await chromium.launch({ headless: true, ...launchOptions() });
  } catch (e) {
    if (process.env.CHROME) throw e;
    return chromium.launch({ headless: true, channel: 'chrome' });
  }
}

/** Runs in the page: the top-level card slots (grid cells of each grid section), in order, document px. */
const SLOTS_JS = () => {
  const all = [];
  const walk = (r) => r.querySelectorAll('*').forEach((e) => { all.push(e); if (e.shadowRoot) walk(e.shadowRoot); });
  walk(document);
  const slots = [];
  for (const sec of all.filter((e) => e.localName === 'hui-grid-section')) {
    for (const cell of sec.shadowRoot.querySelectorAll('.container > .card')) {
      if (cell.classList.contains('hidden')) continue;
      const card = cell.querySelector('hui-card');
      const inner = card && [...(card.children)].find((c) => c.localName !== 'style');
      let r = cell.getBoundingClientRect();
      let fixed = false;
      // A card card-mod pins (`position: fixed`) leaves its grid cell empty: its own box is the slot.
      if ((r.width < 1 || r.height < 1) && inner) {
        const ir = inner.getBoundingClientRect();
        const ha = inner.shadowRoot && inner.shadowRoot.querySelector('ha-card');
        r = ir.width >= 1 && ir.height >= 1 ? ir : (ha ? ha.getBoundingClientRect() : ir);
        fixed = true;
      }
      if (r.width < 1 || r.height < 1) continue;
      slots.push({ x: r.x + scrollX, y: r.y + scrollY, w: r.width, h: r.height, type: inner ? inner.localName : null, ...(fixed ? { fixed } : {}) });
    }
  }
  return { slots, docW: document.documentElement.scrollWidth, docH: document.documentElement.scrollHeight };
};

/** Runs in the page: the text a slot shows, with its box and font, relative to the slot (CSS px). */
const TEXTS_JS = (slot) => {
  const out = [];
  const within = (r) => r.width > 0 && r.height > 0 && r.right > slot.x - scrollX - 1 && r.left < slot.x - scrollX + slot.w + 1 && r.bottom > slot.y - scrollY - 1 && r.top < slot.y - scrollY + slot.h + 1;
  const visible = (el) => {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement || (e.getRootNode && e.getRootNode().host)) {
      const s = getComputedStyle(e);
      if (s.display === 'none' || s.visibility === 'hidden' || +s.opacity === 0) return false;
      // Visually hidden, for screen readers only (HA's select keeps its "Option" label this way): a 1x1 or
      // fully clipped box. Its text range still measures full size, so the element itself is what tells.
      if (e.classList.contains('visually-hidden') || e.classList.contains('sr-only')) return false;
      if (/^rect\(0(px)?,? 0(px)?,? 0(px)?,? 0(px)?\)$/.test(s.clip) || /^inset\((50|100)%\)$/.test(s.clipPath)) return false;
      const r = e.getBoundingClientRect();
      if (r.width <= 1 && r.height <= 1 && s.overflow !== 'visible') return false;
    }
    return true;
  };
  const walk = (root) => {
    for (const n of root.childNodes) {
      if (n.nodeType === 3) {
        const text = n.textContent.replace(/\s+/g, ' ').trim();
        if (!text || !n.parentElement) continue;
        const range = document.createRange();
        range.selectNodeContents(n);
        const r = range.getBoundingClientRect();
        if (!within(r) || !visible(n.parentElement)) continue;
        const s = getComputedStyle(n.parentElement);
        // HA cuts this text itself when an ancestor that hides its overflow is narrower than the text.
        let clipped = false;
        for (let e = n.parentElement; e && e.nodeType === 1 && !clipped; e = e.parentElement || (e.getRootNode && e.getRootNode().host)) {
          const es = getComputedStyle(e);
          if (es.overflowX === 'visible' && es.textOverflow !== 'ellipsis') continue;
          const er = e.getBoundingClientRect();
          if (r.left < er.left - 0.5 || r.right > er.right + 0.5) clipped = true;
          if (e.localName === 'ha-card') break;
        }
        out.push({ ...(clipped ? { clipped } : {}),
          text, x: r.x + scrollX - slot.x, y: r.y + scrollY - slot.y, w: r.width, h: r.height,
          size: parseFloat(s.fontSize), weight: +s.fontWeight || 400, color: s.color, transform: s.textTransform,
        });
      } else if (n.nodeType === 1) {
        if (n.localName === 'style' || n.localName === 'script') continue;
        if (n.shadowRoot) walk(n.shadowRoot);
        walk(n);
      }
    }
  };
  walk(document.body);
  return out.slice(0, 300);
};

/** Follows HA's subscribe_entities stream on the page's own socket, so the states are exactly what it drew. */
function recordStates(page) {
  const states = {};
  page.on('websocket', (ws) => {
    if (!ws.url().endsWith('/api/websocket')) return;
    // Per socket: message ids start again on every connection, and a new connection's first event replaces.
    const entitySubs = new Set();
    const seen = new Set();
    const each = (payload, fn) => {
      let m;
      try { m = JSON.parse(payload); } catch { return; }
      (Array.isArray(m) ? m : [m]).forEach(fn);
    };
    ws.on('framesent', (f) => each(f.payload, (m) => { if (m.type === 'subscribe_entities') entitySubs.add(m.id); }));
    ws.on('framereceived', (f) => each(f.payload, (m) => {
      if (m.type !== 'event' || !entitySubs.has(m.id)) return;
      const first = !seen.has(m.id);
      seen.add(m.id);
      applyEntityEvent(states, m.event || {}, first);
    }));
  });
  return states;
}

async function newPage(browser, frame, dark, now, cardModUrl, tz) {
  const ctx = await browser.newContext({
    viewport: { width: frame.width, height: frame.height }, deviceScaleFactor: frame.density,
    colorScheme: dark ? 'dark' : 'light', timezoneId: tz, locale: 'en-US', reducedMotion: 'reduce',
  });
  await ctx.addInitScript(([origin, token, dark, now, cardModUrl, css]) => {
    // Vitro's clock gets the same instant (refs/*.json "now"): the page's clock stands still.
    const RealDate = Date;
    class FixedDate extends RealDate {
      constructor(...a) { if (a.length) super(...a); else super(now); }
      static now() { return now; }
    }
    window.Date = FixedDate;
    // Everything below is for HA's own page: never a frame a dashboard embeds (an iframe or webpage card).
    if (window.top !== window || location.origin !== origin) return;
    try {
      localStorage.setItem('hassTokens', JSON.stringify({ access_token: token, token_type: 'Bearer', expires_in: 1e9, hassUrl: origin, clientId: origin + '/', expires: RealDate.now() + 1e12, refresh_token: 'x' }));
      localStorage.setItem('dockedSidebar', '"always_hidden"');
      localStorage.setItem('selectedTheme', JSON.stringify({ theme: 'default', dark }));
    } catch (e) {}
    // card-mod first, as `frontend: extra_module_url` loads it on the panels: otherwise styling races.
    if (cardModUrl) {
      const add = () => { const sc = document.createElement('script'); sc.type = 'module'; sc.src = cardModUrl; (document.head || document.documentElement).appendChild(sc); };
      if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
    }
    setInterval(() => {
      const sr = (el) => el && el.shadowRoot;
      const ha = document.querySelector('home-assistant');
      const main = sr(ha) && sr(ha).querySelector('home-assistant-main');
      const panel = sr(main) && sr(main).querySelector('ha-panel-lovelace');
      const hui = sr(sr(panel) && sr(panel).querySelector('hui-root'));
      if (hui && !hui.querySelector('style#gw-kiosk')) {
        const st = document.createElement('style'); st.id = 'gw-kiosk'; st.textContent = css; hui.appendChild(st);
      }
    }, 100);
  }, [creds.origin, creds.token, dark, now, cardModUrl, HUI_CSS]);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => log(`  pageerror: ${scrubText(e.message, creds).slice(0, 200)}`));
  const states = recordStates(page);
  return { ctx, page, states };
}

/** A deep copy of the states of [ids] (sorted), as the page holds them right now. */
function snapshotStates(states, ids) {
  return JSON.parse(JSON.stringify(Object.fromEntries([...ids].sort().filter((id) => states[id]).map((id) => [id, states[id]]))));
}

/** The element HA renders a card type as: hui-tile-card, or the custom element's own name. */
const cardElement = (card) => (card.type.startsWith('custom:') ? card.type.slice(7) : `hui-${card.type}-card`);

/** Waits until the slots exist and two screenshots 400 ms apart are the same (animations, images, fonts). */
async function settle(page, expectType, timeoutMs) {
  const t0 = Date.now();
  let info;
  while (Date.now() - t0 < timeoutMs) {
    info = await page.evaluate(SLOTS_JS);
    // After an in-app navigation the last view's card can linger for a moment: wait for this case's card.
    if (info.slots.length && (!expectType || info.slots[0].type === expectType)) break;
    // A real dashboard can legitimately show no card (everything hidden by conditions, e.g. a night mode).
    if (!expectType && Date.now() - t0 > 8000) break;
    await page.waitForTimeout(250);
  }
  if (expectType && info.slots[0]?.type !== expectType) throw new Error(`expected <${expectType}>, the page shows <${info.slots[0]?.type}>`);
  await page.evaluate(async () => { await document.fonts.ready; });
  await page.waitForTimeout(800);
  let prev = null;
  for (let i = 0; i < 8; i++) {
    const shot = await page.screenshot({ fullPage: true });
    if (prev && shot.equals(prev)) return { info: await page.evaluate(SLOTS_JS), stable: true };
    prev = shot;
    await page.waitForTimeout(400);
  }
  return { info: await page.evaluate(SLOTS_JS), stable: false };
}

function clipFor(slot, info) {
  const x = Math.max(0, Math.floor(slot.x - MARGIN));
  const y = Math.max(0, Math.floor(slot.y - MARGIN));
  const r = Math.min(info.docW, Math.ceil(slot.x + slot.w + MARGIN));
  const b = Math.min(info.docH, Math.ceil(slot.y + slot.h + MARGIN));
  return { x, y, width: r - x, height: b - y };
}

/**
 * The instant the page's clock (and Vitro's clock card) shows: the most recent 10:08:00 in HA's time zone. A fixed
 * time of day keeps the clock's digits, and so its score, the same from one capture to the next.
 */
function pinnedNow(timeZone) {
  const t = Date.now();
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone, hour: 'numeric', minute: 'numeric', second: 'numeric', hourCycle: 'h23' })
    .formatToParts(new Date(t)).filter((p) => p.type !== 'literal').map((p) => [p.type, +p.value]));
  const sinceMidnight = (parts.hour * 60 + parts.minute) * 60 + parts.second;
  const back = (sinceMidnight - (10 * 60 + 8) * 60 + 86400) % 86400;
  return t - (t % 1000) - back * 1000;
}

const round = (v) => Math.round(v * 100) / 100;
const roundRect = (r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, typeof v === 'number' ? round(v) : v]));

async function capture() {
  const ha = await connect(creds);
  const [haConfig, themes, resources] = await Promise.all([
    ha.call({ type: 'get_config' }), ha.call({ type: 'frontend/get_themes' }), ha.call({ type: 'lovelace/resources' }),
  ]);
  const cardModUrl = resources.map((r) => r.url).find((u) => /card-mod\.js/.test(u)) || null;
  // The display data Vitro reads live (HaDisplay): fetched once, cut down per case.
  const [registry, devices] = await Promise.all([ha.call({ type: 'config/entity_registry/list_for_display' }), ha.call({ type: 'config/device_registry/list' })]);
  const displayCache = new Map();
  const fetchDisplay = async (type, category, names) => {
    const want = names.filter((n) => !displayCache.has(`${type}|${category}|${n}`));
    if (want.length) {
      const res = await ha.call({ type, category, integration: want, ...(type === 'frontend/get_translations' ? { language: 'en' } : {}) });
      for (const n of want) {
        let mine;
        if (type === 'frontend/get_icons') mine = res.resources?.[n];
        else mine = Object.fromEntries(Object.entries(res.resources || {}).filter(([k]) => k.startsWith(`component.${n}.`)));
        displayCache.set(`${type}|${category}|${n}`, mine);
      }
    }
  };
  const displayFor = async (ids) => {
    const first = buildDisplay(ids, registry, devices, () => undefined, () => undefined);
    const { domains, platforms } = first.requested;
    await fetchDisplay('frontend/get_icons', 'entity_component', domains);
    await fetchDisplay('frontend/get_icons', 'entity', platforms);
    await fetchDisplay('frontend/get_translations', 'entity_component', domains);
    await fetchDisplay('frontend/get_translations', 'entity', platforms);
    return buildDisplay(ids, registry, devices,
      (category, n) => displayCache.get(`frontend/get_icons|${category}|${n}`),
      (category, n) => displayCache.get(`frontend/get_translations|${category}|${n}`));
  };
  if (!cardModUrl) log('card-mod is not a Lovelace resource: card_mod styles will not apply in HA');
  const cases = loadCases();
  const wantedFile = args.templates || join(ROOT, 'engines', 'engine-vitro', 'build', 'vitro-parity', committed ? 'templates-wanted.json' : 'templates-wanted-local.json');
  const wanted = existsSync(wantedFile) ? JSON.parse(readFileSync(wantedFile, 'utf8')) : {};
  if (!existsSync(wantedFile)) log(`no ${wantedFile}: Vitro's templates stay unrendered (run.sh runs the collect step first)`);

  const refs = join(corpusRoot, 'refs');
  mkdirSync(refs, { recursive: true });
  const shared = {
    _comment: 'What every reference shares: HA version, the config fields Vitro reads, and the themes (scrubbed). Written by tools/vitro-parity/capture.mjs.',
    haVersion: haConfig.version,
    haConfig: { time_zone: haConfig.time_zone, unit_system: haConfig.unit_system, language: haConfig.language, location_name: 'Home' },
    themes,
  };
  const sharedText = scrubText(JSON.stringify(shared, null, 1), creds);
  if (committed) checkDenylist(sharedText, 'refs/shared.json');
  writeFileSync(join(refs, 'shared.json'), sharedText + '\n');

  const browser = await launch();
  const now = pinnedNow(haConfig.time_zone);
  let failures = 0;
  for (const frame of frames) {
    const mine = cases.filter((c) => !c.frames || c.frames.includes(frame.id));
    for (const dark of [true, false]) {
      const group = mine.filter((c) => (c.dark ?? true) === dark);
      if (!group.length) continue;
      const { ctx, page, states } = await newPage(browser, frame, dark, now, cardModUrl, haConfig.time_zone);
      let loaded = false;
      for (const c of group) {
        const t0 = Date.now();
        try {
          const path = c.card ? `/${HARNESS}/${c.id}` : `/${c.dashboard}/${c.view}`;
          if (!loaded || c.dashboard) {
            await page.goto(creds.origin + path, { waitUntil: 'load', timeout: 45000 });
            loaded = !c.dashboard;
          } else {
            await page.evaluate((p) => { history.pushState(null, '', p); window.dispatchEvent(new CustomEvent('location-changed', { detail: { replace: false } })); scrollTo(0, 0); }, path);
          }
          const config = c.card ?? JSON.parse(readFileSync(join(corpusRoot, 'dashboards', `${c.dashboard}.json`), 'utf8'));
          const outDir = join(refs, frame.id);
          mkdirSync(outDir, { recursive: true });
          let info, stable, clip, slots, png, texts, templates, ids, drawnStates;
          // The PNG, its texts and the states saved with them must be one instant: an entity that changes while
          // they're taken makes the pair disagree, so the capture starts over (three tries).
          for (let attempt = 1; ; attempt++) {
            ({ info, stable } = await settle(page, c.card ? cardElement(c.card) : null, c.dashboard ? 20000 : 15000));
            if (!info.slots.length && c.card) throw new Error('no card on the page');
            if (!info.slots.length) log(`${frame.id}/${c.id}: HA shows no card (all hidden?): comparing the whole page`);
            if (c.card) {
              slots = [info.slots[0]];
              clip = clipFor(slots[0], info);
            } else {
              slots = info.slots;
              clip = { x: 0, y: 0, width: info.docW, height: info.docH };
            }
            ids = referencedEntities(config, states);
            drawnStates = snapshotStates(states, ids);
            // Kept in memory until every check passes: a failed case leaves no PNG behind.
            png = await page.screenshot({ clip, fullPage: true });
            texts = [];
            for (const [i, s] of slots.entries()) for (const t of await page.evaluate(TEXTS_JS, s)) texts.push({ slot: i, ...roundRect(t) });
            // The templates Vitro asks for, rendered now.
            templates = {};
            for (const tpl of wanted[c.id] || []) {
              const r = await ha.renderTemplate(tpl);
              templates[tpl] = r.error ? { error: String(r.error) } : { result: typeof r.result === 'string' ? r.result : JSON.stringify(r.result) };
            }
            if (JSON.stringify(snapshotStates(states, ids)) === JSON.stringify(drawnStates)) break;
            if (attempt >= 3) throw new Error('entity states kept changing during the capture (3 tries)');
            log(`${frame.id}/${c.id}: an entity changed during the capture: again`);
          }
          // The screenshot shows what the JSON scrubs: check the raw text HA drew and the templates before writing.
          if (committed) {
            const shown = findPrivate(JSON.stringify({ texts, templates }), creds);
            if (shown.length) throw new Error(`the card shows ${shown.join(', ')}: neither its PNG nor its JSON is written`);
          }
          const shownStates = drawnStates; // the states the PNG shows
          const caseStates = scrubStates(shownStates, creds, { keepPictures: !committed });
          // A local corpus also keeps the pictures its entities show (plants, artwork), for Vitro's fixture server.
          if (!committed) {
            for (const id of ids) {
              const pic = states[id]?.a?.entity_picture;
              if (typeof pic !== 'string') continue;
              if (pic.startsWith('/')) await fetchAsset(pic.split('?')[0], pic).catch((e) => log(`  picture: ${scrubText(String(e.message), creds)}`));
            }
          }
          const display = await displayFor(ids);
          const ref = {
            case: c.id, frame: frame.id, haVersion: haConfig.version, now, stable,
            viewport: { width: frame.width, height: frame.height, density: frame.density },
            clip, docSize: { width: info.docW, height: info.docH }, slots: slots.map(roundRect), texts,
            states: caseStates, templates, display,
          };
          if (!committed) {
            // Pictures that templates point at (a plant's image entity inside Markdown, say), unredacted from the raw text.
            const raw = JSON.stringify({ templates, states: shownStates });
            for (const m of new Set(raw.match(/\/api\/[a-z_]+_proxy\/[^"'\s)\\]+/g) || [])) {
              await fetchAsset(m.split('?')[0], m.replace(/&amp;/g, '&')).catch((e) => log(`  picture: ${scrubText(String(e.message), creds)}`));
            }
          }
          const text = scrubText(JSON.stringify(ref, null, 1), creds);
          if (committed) checkDenylist(text, `refs/${frame.id}/${c.id}`);
          writeFileSync(join(outDir, `${c.id}.png`), png);
          writeFileSync(join(outDir, `${c.id}.json`), text + '\n');
          log(`${frame.id}/${c.id}: ${slots.length} card(s), ${texts.length} texts, ${Object.keys(caseStates).length} states, ${Object.keys(templates).length} templates${stable ? '' : ' (still moving)'} in ${Date.now() - t0} ms`);
        } catch (e) {
          failures++;
          log(`${frame.id}/${c.id}: FAILED ${scrubText(String(e.message || e), creds).slice(0, 300)}`);
        }
      }
      await ctx.close();
    }
  }
  await browser.close();
  ha.close();
  if (failures) fail(`${failures} capture(s) failed`);
}

try {
  if (args.cmd === 'prepare') await prepare();
  else await capture();
} catch (e) {
  fail(`capture.mjs ${args.cmd}: ${e?.stack || e}`);
}
process.exit(0);
