#!/usr/bin/env node
// Vitro parity harness, Home Assistant side (#793): renders every corpus case in HA's real frontend, in
// headless Chromium at each panel's CSS size and density, and writes the reference renders that
// VitroParityHarnessTest compares Vitro against.
//
// Where its Home Assistant comes from:
//   - The committed corpus: the fake Home Assistant (tools/perf/fakeha.py with tools/vitro-parity/fakehome.py), started
//     here on 127.0.0.1 with a random token and stopped at the end. It serves HA's pinned frontend and answers from
//     each case's `fixture` and the corpus's home.json; nobody's real HA is read or written. The community cards it
//     serves are downloaded once, pinned by SHA-256 (cards.py), into a cache outside the repo.
//   - A local corpus (--root, real dashboards): the real HA in ha.json, as below. `--fakeha` makes a local corpus of
//     card cases with fixtures use the fake too; `--real-ha` is refused for the committed corpus.
//
// What it touches:
//   - Home Assistant (real HA mode only), through the credentials in ha.json (VITRO_PARITY_HA, default
//     ~/.t3/glassworks-m1/vitro-parity/ha.json). It WRITES ONE DASHBOARD ONLY: `vitro-harness` (admin-only,
//     not in the sidebar), created if missing and overwritten on every run. It never changes an entity
//     state or any other dashboard; everything else is read-only (states, themes, config, render_template, and
//     HA's own Roboto from /static, which every captured page gets as a font from its start, kept in memory). The
//     app's own Inter (core/src/main/res/font/inter.ttf, read only, checked by SHA-256) goes into every page the same way.
//   - A case's own stylesheets and fonts (`resources`, #811): served to HA's page from the corpus's fixtures/ folder
//     at /local/vitro-harness-fixtures/ (a route in the browser; the request never reaches HA) and put in the page
//     as HA's loadCSS does. Nothing is added to HA's Lovelace resources.
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
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { connect, loadCredentials, redact } from './lib/ha.mjs';
import { referencedEntities, scrubStates, scrubText, checkDenylist, findPrivate, hasMacFragment, scrubGraphCalls } from './lib/scrub.mjs';
import { parseArgs, pick } from './lib/args.mjs';
import { aliasPaths, assetDestination, canonicalPath, capturableImage, staleAssets } from './lib/assets.mjs';
import { applyEntityEvent, unmetExpectations } from './lib/states.mjs';
import { imageOutcomes, noteSaved } from './lib/images.mjs';
import { buildDisplay, referencedAreas } from './lib/display.mjs';
import { wsRecorder, snapshotCalls, pendingOneShots, pendingFirstEvents, restHistoryRequest, keepCall } from './lib/wsdata.mjs';
import { HARNESS, HARNESS_META, MARKER_KEY, MARKER_VALUE, notOurs } from './lib/harness.mjs';
import { FIXTURE_PREFIX, fixtureFile, fixtureResources } from './lib/fixtures.mjs';
import { afterGrow, captureMode, capturedWithTouch } from './lib/pointer.mjs';
import { launch } from './lib/browser.mjs';
import { topLevelViewCards } from './lib/slots.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DEFAULT_CORPUS = join(ROOT, 'engines', 'engine-vitro', 'src', 'test', 'parity');
/** CSS px of page around each card in the reference crop, so an offset card still shows. */
const MARGIN = 8;

// The kiosk's own CSS (engines/engine-gecko/src/main/assets/extension/content.js, HUI_CSS): no header.
const HUI_CSS = ':host { --header-height: 0px !important; } .header { display: none !important; } #view { padding-top: 0 !important; min-height: 100vh !important; }';

// The HA the capture talks to: ha.json's in real HA mode, the fake's (set by startFake) otherwise.
let creds = null;
const log = (...a) => console.log(redact(a.join(' '), creds));
/** The fake HA's process, while it runs: stopped on every exit. */
let fakeChild = null;
process.on('exit', () => { try { fakeChild?.kill(); } catch {} });

function fail(msg) {
  console.error(redact(msg, creds));
  process.exit(1);
}

// Whatever escapes still goes through redact(): the token and the origin never reach stderr.
process.on('uncaughtException', (e) => fail(`capture.mjs: ${e?.stack || e}`));
process.on('unhandledRejection', (e) => fail(`capture.mjs: ${e?.stack || e}`));

const USAGE = 'usage: capture.mjs prepare|capture [--root <dir>] [--frames s1,tablet] [--cases a,b] [--templates <file>] [--fakeha|--real-ha]';
let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (e) {
  fail(`${e.message}\n${USAGE}`);
}
if (!['prepare', 'capture'].includes(args.cmd)) fail(USAGE);

const corpusRoot = resolve(args.root || DEFAULT_CORPUS);
const committed = corpusRoot === DEFAULT_CORPUS;
// The committed corpus is always drawn from the fake HA: its references must not depend on anyone's real home.
if (committed && args['real-ha']) fail('--real-ha: the committed corpus is captured from the fake HA only (its fixtures); a real HA is for a local corpus (--root)');
const fakeMode = committed || Boolean(args.fakeha);
if (!fakeMode) {
  creds = loadCredentials();
  if (!creds) fail('no Home Assistant credentials (set VITRO_PARITY_HA or create ~/.t3/glassworks-m1/vitro-parity/ha.json); the CI-safe part needs none: ./gradlew :engine-vitro:testDebugUnitTest');
}
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

/**
 * The harness dashboard: one sections view per card case, one section holding the one card; a view case
 * (`view_config`: a whole view, for the view layouts) is its own view as written.
 */
function harnessConfig(cases) {
  return {
    title: HARNESS_META.title,
    [MARKER_KEY]: MARKER_VALUE,
    views: cases.filter((c) => c.card || c.view_config).map((c) => {
      if (c.view_config) return { ...c.view_config, title: c.id, path: c.id };
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


/** Path -> sha256 of the image this run saved there: a second, different one is a conflict (noteSaved). */
const savedDigests = new Map();

/** Asset paths this run saved or found unchanged; a full, clean refresh deletes every other file in assets/. */
const keptAssets = new Set();
/** Files a capture run writes, held until every capture succeeded ([stage]): [path, data]. */
const staged = new Map();
const stage = (path, data) => staged.set(path, Buffer.isBuffer(data) ? data : Buffer.from(data));

/** Every file under assets/, as the HA path it stands for (`/static/icons/x.png`). */
function existingAssets() {
  const root = join(corpusRoot, 'assets');
  if (!existsSync(root)) return [];
  const out = [];
  const walk = (dir, rel) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) walk(join(dir, e.name), `${rel}/${e.name}`);
      else out.push(`${rel}/${e.name}`);
    }
  };
  walk(root, '');
  return out;
}

/** Saves an image HA serves at [path] (fetched from [url], default the path) under assets/. */
async function fetchAsset(path, url = path) {
  // Checked before the fetch: a path that leaves assets/ (or /static/, for the committed corpus) is never requested.
  const out = assetDestination(join(corpusRoot, 'assets'), path, committed);
  if ((url !== path && !url.startsWith(path + '?')) || canonicalPath(url) !== path) throw new Error(`asset ${path}: fetch URL is another path`);
  const res = await fetch(creds.origin + url, { headers: { Authorization: `Bearer ${creds.token}` } });
  if (!res.ok) throw new Error(`asset ${path}: HTTP ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  // A reference already drew this path: a fetch with other bytes fails (its caller logs it) and the drawn image
  // stays. The fetch itself sets nothing a later drawn image is checked against: what a reference draws wins.
  const drawnDigest = savedDigests.get(path);
  if (drawnDigest && drawnDigest !== createHash('sha256').update(bytes).digest('hex')) {
    throw new Error(`asset ${path}: a reference drew another image at this path in this run`);
  }
  // Staged with the references: a failed refresh writes no picture either.
  stage(out, bytes);
  keptAssets.add(path);
  log(`asset ${path}`);
}

async function prepare() {
  if (fakeMode) {
    log('fake HA: nothing to prepare (the fake serves the harness dashboard from the corpus itself)');
    return;
  }
  const ha = await connect(creds);
  const cases = loadCases();
  const cardCases = cases.filter((c) => c.card || c.view_config);
  if (cardCases.length) {
    const all = loadCasesAll();
    const harnessIds = all.filter((c) => c.card || c.view_config).map((c) => c.id);
    await saveHarness(ha, harnessConfig(all), harnessIds);
    log(`saved ${HARNESS}: ${harnessIds.length} views (every corpus case)`);
  }
  for (const c of cases.filter((c) => c.dashboard)) {
    if (committed) fail(`${c.id}: real dashboards belong in a local corpus (--root), never in the committed one`);
    const cfg = await ha.call({ type: 'lovelace/config', url_path: c.dashboard, force: true });
    mkdirSync(join(corpusRoot, 'dashboards'), { recursive: true });
    writeFileSync(join(corpusRoot, 'dashboards', `${c.dashboard}.json`), JSON.stringify(cfg, null, 1) + '\n');
    log(`dashboard ${c.dashboard}`);
  }
  ha.close();
}

// prepare saves every card case so a later --cases capture still finds its view.
function loadCasesAll() {
  const dir = join(corpusRoot, 'corpus');
  return readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => ({ id: f.replace(/\.json$/, ''), ...JSON.parse(readFileSync(join(dir, f), 'utf8')) }));
}


/**
 * Runs in the page: the top-level card slots, in order, document px. A Sections view's are the grid cells of each grid
 * section; another view's are its top-level cards (`topLevelViewCards`, lib/slots.mjs, passed in as source: a
 * stack's rows are part of the stack's slot, never slots of their own).
 */
const slotsInPage = (topLevelViewCards) => {
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
  // The other view layouts (masonry, sidebar, layout-card's): every top-level card, in document order.
  if (!all.some((e) => e.localName === 'hui-grid-section')) {
    for (const card of topLevelViewCards(document)) {
      const inner = [...card.children].find((c) => c.localName !== 'style');
      const r = card.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      slots.push({ x: r.x + scrollX, y: r.y + scrollY, w: r.width, h: r.height, type: inner ? inner.localName : null });
    }
  }
  return { slots, docW: document.documentElement.scrollWidth, docH: document.documentElement.scrollHeight };
};
/** [slotsInPage] as the expression page.evaluate runs, with lib/slots.mjs's walk inlined. */
const SLOTS_JS = `(${slotsInPage})(${topLevelViewCards})`;

/**
 * Runs in the page: the URLs of the images drawn inside the given slots (document px): <img> sources, SVG <image>
 * hrefs and CSS background images, read from the rendered DOM (shadow roots included), never from configs. HA's
 * launch-screen logos and anything else the page loaded but doesn't show in a card are left out.
 */
const DRAWN_IMAGES_JS = (slots) => {
  const drawn = new Set();
  const broken = new Set();
  const inside = (r) => r.width > 0 && r.height > 0 && slots.some((s) =>
    r.right > s.x - scrollX && r.left < s.x - scrollX + s.w && r.bottom > s.y - scrollY && r.top < s.y - scrollY + s.h);
  // Drawn means shown: no hidden, transparent or undisplayed element on the way up (shadow hosts included).
  const visible = (el) => {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement || (e.getRootNode && e.getRootNode().host)) {
      const st = getComputedStyle(e);
      if (st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse' || +st.opacity === 0) return false;
    }
    return true;
  };
  const walk = (root) => root.querySelectorAll('*').forEach((e) => {
    if (e.shadowRoot) walk(e.shadowRoot);
    const r = e.getBoundingClientRect();
    if (!inside(r) || !visible(e)) return;
    if (e.localName === 'img' && e.currentSrc) {
      // Loaded but undecodable (a 200 text/html, a corrupt file): HA shows no picture there.
      if (e.complete && e.naturalWidth === 0) broken.add(e.currentSrc);
      else drawn.add(e.currentSrc);
    }
    if (e.localName === 'image') {
      const h = e.getAttribute('href') || e.getAttribute('xlink:href');
      if (h) drawn.add(new URL(h, location.href).href);
    }
    const bg = getComputedStyle(e).backgroundImage;
    if (bg && bg !== 'none') for (const m of bg.matchAll(/url\(["']?([^"')]+)["']?\)/g)) drawn.add(new URL(m[1], location.href).href);
  });
  walk(document);
  return { drawn: [...drawn].sort(), broken: [...broken].sort() };
};

/** Runs in the page: the text a slot shows, with its box and font, relative to the slot (CSS px). */
const TEXTS_JS = (slot) => {
  const out = [];
  // The element that lays [e] out: the <slot> it is assigned to (its box sits in the shadow root's flat tree, where
  // Mushroom's `.primary` cuts the line with an ellipsis), else its parent, else its shadow root's host.
  const up = (e) => e.assignedSlot || e.parentElement || (e.getRootNode && e.getRootNode().host);
  const within = (r) => r.width > 0 && r.height > 0 && r.right > slot.x - scrollX - 1 && r.left < slot.x - scrollX + slot.w + 1 && r.bottom > slot.y - scrollY - 1 && r.top < slot.y - scrollY + slot.h + 1;
  const visible = (el) => {
    for (let e = el; e && e.nodeType === 1; e = up(e)) {
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
        // The run's box from its first to its last non-space character: a leading space (" text." after a <b>) would
        // start HA's box a space before Vitro's, which measures from the text.
        const raw = n.textContent;
        const range = document.createRange();
        range.setStart(n, raw.length - raw.trimStart().length);
        range.setEnd(n, raw.trimEnd().length);
        const r = range.getBoundingClientRect();
        // A text node can be slotted itself; its box is then the slot's.
        const box = n.assignedSlot || n.parentElement;
        if (!within(r) || !visible(box)) continue;
        const s = getComputedStyle(box);
        // HA cuts this text itself when an ancestor that hides its overflow is narrower than the text. Ancestors in
        // the flat tree: a slotted span's own parent never clips it, the slot's container in the shadow root does.
        let clipped = false;
        for (let e = box; e && e.nodeType === 1 && !clipped; e = up(e)) {
          const es = getComputedStyle(e);
          if (es.overflowX === 'visible' && es.textOverflow !== 'ellipsis') continue;
          // Overflow clips at the padding box; an ellipsis takes the place of what runs past the content box (an
          // entity row's name has 8 px of right padding, so it is cut 8 px before its edge).
          const er = e.getBoundingClientRect();
          const px = (v) => parseFloat(v) || 0;
          const inL = px(es.borderLeftWidth) + (es.textOverflow === 'ellipsis' ? px(es.paddingLeft) : 0);
          const inR = px(es.borderRightWidth) + (es.textOverflow === 'ellipsis' ? px(es.paddingRight) : 0);
          if (r.left < er.left + inL - 0.5 || r.right > er.right - inR + 0.5) clipped = true;
          if (e.localName === 'ha-card') break;
        }
        // Both values: an explicit false outweighs the bounds the comparison would otherwise guess from.
        out.push({ clipped,
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
  // A More Info case (#885) reads the dialog's own text only: the card under its scrim isn't in the reference.
  if (slot.scope === 'more-info') {
    const find = (root) => { for (const e of root.querySelectorAll('*')) { if (e.localName === 'ha-more-info-dialog') return e; if (e.shadowRoot) { const f = find(e.shadowRoot); if (f) return f; } } return null; };
    const dialog = find(document);
    if (dialog && dialog.shadowRoot) walk(dialog.shadowRoot);
  } else {
    walk(document.body);
  }
  return out.slice(0, 300);
};

/**
 * Runs in the page: the open More Info dialog's surface (document px), as a slot: HA's `<dialog>` inside its wa-dialog
 * (a centred dialog) or wa-drawer (a bottom sheet), whichever `ha-adaptive-dialog` chose for the window. Null while
 * none is open.
 */
const DIALOG_JS = () => {
  const find = (root, name) => {
    for (const e of root.querySelectorAll('*')) {
      if (e.localName === name) return e;
      if (e.shadowRoot) { const f = find(e.shadowRoot, name); if (f) return f; }
    }
    return null;
  };
  const mi = find(document, 'ha-more-info-dialog');
  if (!mi || !mi.shadowRoot || !find(mi.shadowRoot, 'ha-more-info-info')) return null;
  for (const host of ['wa-dialog', 'wa-drawer']) {
    const w = find(mi.shadowRoot, host);
    const d = w && w.shadowRoot && w.shadowRoot.querySelector('dialog');
    if (!d) continue;
    const r = d.getBoundingClientRect();
    if (r.width >= 1 && r.height >= 1) return { x: r.x + scrollX, y: r.y + scrollY, w: r.width, h: r.height, type: 'ha-more-info-dialog', mode: host === 'wa-drawer' ? 'bottom-sheet' : 'dialog' };
  }
  return null;
};

/** Opens HA's More Info dialog for [entityId], as a card's tap does (`hass-more-info`), and waits for its surface. */
async function openMoreInfo(page, entityId) {
  if (await page.evaluate(DIALOG_JS)) return;
  await page.evaluate((id) => {
    document.querySelector('home-assistant').dispatchEvent(new CustomEvent('hass-more-info', { detail: { entityId: id }, bubbles: true, composed: true }));
  }, entityId);
  const t0 = Date.now();
  while (Date.now() - t0 < 10000) {
    if (await page.evaluate(DIALOG_JS)) return;
    await page.waitForTimeout(100);
  }
  throw new Error(`HA's More Info dialog for ${entityId} didn't open`);
}

/**
 * Follows HA's subscribe_entities stream on the page's own socket, so the states are exactly what it drew, and
 * records the history and statistics calls its graphs make, with their answers ([graphs], see wsdata.mjs).
 */
function recordStates(page, graphs) {
  const states = {};
  page.on('websocket', (ws) => {
    if (!ws.url().endsWith('/api/websocket')) return;
    // Per socket: message ids start again on every connection, and a new connection's first event replaces.
    const entitySubs = new Set();
    const seen = new Set();
    const graphSocket = graphs.socket();
    const each = (payload, fn) => {
      let m;
      try { m = JSON.parse(payload); } catch { return; }
      (Array.isArray(m) ? m : [m]).forEach(fn);
    };
    ws.on('framesent', (f) => each(f.payload, (m) => { if (m.type === 'subscribe_entities') entitySubs.add(m.id); graphSocket.sent(m); }));
    ws.on('framereceived', (f) => each(f.payload, (m) => {
      graphSocket.received(m);
      if (m.type !== 'event' || !entitySubs.has(m.id)) return;
      const first = !seen.has(m.id);
      seen.add(m.id);
      applyEntityEvent(states, m.event || {}, first);
    }));
  });
  return states;
}

/**
 * The images HA's page loads (capturableImage: /local/ and /static/ on HA's origin), path -> body, and the image
 * paths it asked for but didn't get. After each case, the ones drawn in its cards are saved (saveDrawnImages), so
 * whatever HA drew, Vitro's fixture server serves.
 */
function recordImages(page) {
  const images = imageOutcomes();
  const chain = (res) => {
    const urls = [res.url()];
    for (let r = res.request().redirectedFrom(); r; r = r.redirectedFrom()) urls.push(r.url());
    return urls;
  };
  const onOrigin = (urls) => urls.map((url) => {
    try { const u = new URL(url); return u.origin === creds.origin ? u.pathname : null; } catch { return null; }
  }).filter(Boolean);
  page.on('response', (res) => {
    const urls = chain(res);
    const paths = aliasPaths(urls, creds.origin, res.status(), res.headers()['content-type']);
    if (paths.length) {
      // One body under every path that led to it: a redirected <img> keeps the URL it asked for.
      images.success(paths, res.body().catch(() => null));
      return;
    }
    if (res.request().resourceType() !== 'image') return;
    // An image request that got an error, or a 2xx that isn't an image (HA shows nothing there either). A 2xx
    // image this harness can't keep (an /api/…_proxy picture, say) is not a failure: HA drew it, and Vitro drawing
    // nothing there is a gap in the input, not excused.
    const type = String(res.headers()['content-type'] || '').trim().toLowerCase();
    const ok = res.status() >= 200 && res.status() < 300;
    if (res.status() >= 400 || (ok && !type.startsWith('image/'))) images.failure(onOrigin(urls));
    else if (ok) for (const p of onOrigin(urls)) images.failed.delete(p);
  });
  page.on('requestfailed', (req) => {
    if (req.resourceType() !== 'image') return;
    const urls = [];
    for (let r = req; r; r = r.redirectedFrom()) urls.push(r.url());
    images.failure(onOrigin(urls));
  });
  return images;
}

/**
 * Writes into assets/ the images HA both loaded and drew in the case's cards ([drawn]: URLs from DRAWN_IMAGES_JS),
 * through assetDestination's checks; new or changed ones are logged. Returns the drawn image paths HA asked for
 * and didn't get.
 */
async function saveDrawnImages(loaded, drawn) {
  const paths = new Set();
  for (const url of drawn) {
    try {
      const u = new URL(url);
      if (u.origin === creds.origin) paths.add(u.pathname);
    } catch {}
  }
  for (const path of [...paths].sort()) {
    if (!loaded.has(path)) continue;
    // A fixture's own picture is in the corpus already (fixtures/): Vitro's fixture server hands it over from there.
    if (path.startsWith(FIXTURE_PREFIX)) continue;
    const bytes = await loaded.get(path);
    if (!bytes) throw new Error(`image ${path}: HA loaded it but its body couldn't be read`);
    const out = assetDestination(join(corpusRoot, 'assets'), path, committed);
    const conflict = noteSaved(savedDigests, path, createHash('sha256').update(bytes).digest('hex'));
    if (conflict) throw new Error(conflict);
    keptAssets.add(path);
    // Against what this run will write there (a picture fetched earlier in the run), else the file on disk: the
    // image HA drew is the one kept.
    const pending = staged.get(out);
    if (pending ? pending.equals(bytes) : existsSync(out) && readFileSync(out).equals(bytes)) continue;
    stage(out, bytes);
    log(`asset ${path} (HA drew it)`);
  }
}

/**
 * Roboto as the panels have it: a font the page can use from its first script. HA's `@font-face` asks for
 * `local("Roboto")` before its own woff2, and a panel (Android) has Roboto as a system font. A capture host without
 * it gets the woff2, which loads only once something asks for it, so HA's charts measure their axis labels
 * (measureTextWidth, on a canvas) in the fallback font, then draw them in Roboto: the axis came out ~2 px wider
 * than on a panel. HA's own files, from its `/static`, added to each page before HA's code runs; kept in memory.
 */
const ROBOTO_FACES = [['Light', '300'], ['Regular', '400'], ['Medium', '500'], ['Bold', '700']];
async function robotoFaces() {
  const faces = [];
  for (const [name, weight] of ROBOTO_FACES) {
    const res = await fetch(`${creds.origin}/static/fonts/roboto/Roboto-${name}.woff2`);
    if (!res.ok) fail(`HA's Roboto ${name}: HTTP ${res.status}`);
    faces.push({ family: 'Roboto', weight, data: Buffer.from(await res.arrayBuffer()).toString('base64') });
  }
  return faces;
}

/**
 * Inter for a theme that names it (`Vitro Glass`'s font family): the variable Inter 4.001 Vitro draws with, the
 * file the app bundles (`core/src/main/res/font/inter.ttf`, its license and source in THIRD-PARTY-NOTICES.md),
 * pinned by SHA-256. Without it the page takes whatever Inter the capture host has installed: a static Inter
 * collection resolves every weight under 400 to Inter Thin, and HA's light text came out hairline-thin. One face for
 * the whole weight axis; Chromium sets the optical size from the font size, as Vitro does (Inter.opticalSize).
 */
const INTER_FILE = join(ROOT, 'core', 'src', 'main', 'res', 'font', 'inter.ttf');
const INTER_SHA256 = '29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031';
function interFace() {
  const bytes = readFileSync(INTER_FILE);
  const digest = createHash('sha256').update(bytes).digest('hex');
  if (digest !== INTER_SHA256) fail(`Inter: ${INTER_FILE} isn't the pinned file (SHA-256 ${digest})`);
  return { family: 'Inter', weight: '100 900', data: bytes.toString('base64') };
}

async function newPage(browser, frame, dark, now, cardModUrl, tz, fonts) {
  const ctx = await browser.newContext({
    viewport: { width: frame.width, height: frame.height }, deviceScaleFactor: frame.density,
    // A touch frame's browser has a coarse pointer, which HA's chart legend styles for (40 px rows).
    hasTouch: frame.input === 'touch',
    colorScheme: dark ? 'dark' : 'light', timezoneId: tz, locale: 'en-US', reducedMotion: 'reduce',
  });
  // Every page starts as a fresh browser's: HA's frontend keeps the last language it applied in localStorage
  // (`selectedLanguage`), and a case with no language of its own must not inherit the one before it.
  await ctx.addInitScript(() => { try { localStorage.removeItem('selectedLanguage'); } catch {} });
  // Before anything else in the page (see robotoFaces and interFace). A face added from bytes is ready without a
  // request, and wins over a system font of the same name.
  await ctx.addInitScript((faces) => {
    for (const f of faces) {
      const bytes = Uint8Array.from(atob(f.data), (ch) => ch.charCodeAt(0));
      const face = new FontFace(f.family, bytes, { weight: f.weight, style: 'normal' });
      document.fonts.add(face);
    }
  }, fonts);
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
  // A case's fixture stylesheets and fonts: answered here, never by HA.
  const fixturesDir = join(corpusRoot, 'fixtures');
  await ctx.route((url) => url.origin === creds.origin && url.pathname.startsWith(FIXTURE_PREFIX), async (route) => {
    const hit = fixtureFile(fixturesDir, new URL(route.request().url()).pathname);
    if (!hit || !existsSync(hit.file)) return route.fulfill({ status: 404, body: '' });
    return route.fulfill({ status: 200, contentType: hit.type, body: readFileSync(hit.file) });
  });
  const page = await ctx.newPage();
  if (frame.input === 'touch') touchPages.add(page);
  page.on('pageerror', (e) => log(`  pageerror: ${scrubText(e.message, creds).slice(0, 200)}`));
  const graphs = wsRecorder();
  // HA's REST history (mini-graph-card reads it this way): recorded with the WebSocket calls, answered or not.
  const restCalls = new Map();
  page.on('request', (req) => {
    if (!req.url().startsWith(creds.origin + '/api/history/period')) return;
    const request = restHistoryRequest(req.url());
    if (request) restCalls.set(req, graphs.push({ request, events: [] }));
  });
  page.on('requestfinished', async (req) => {
    const call = restCalls.get(req);
    if (!call) return;
    restCalls.delete(req);
    try {
      const res = await req.response();
      if (res && res.ok()) call.result = await res.json();
      else call.error = { code: `http_${res ? res.status() : 'none'}` };
    } catch (e) {
      call.error = { code: 'unreadable', message: String(e.message || e) };
    }
  });
  page.on('requestfailed', (req) => {
    const call = restCalls.get(req);
    if (!call) return;
    restCalls.delete(req);
    call.error = { code: 'failed' };
  });
  const states = recordStates(page, graphs);
  const images = recordImages(page);
  return { ctx, page, states, images, graphs };
}

/** A deep copy of the states of [ids] (sorted), as the page holds them right now. */
function snapshotStates(states, ids) {
  return JSON.parse(JSON.stringify(Object.fromEntries([...ids].sort().filter((id) => states[id]).map((id) => [id, states[id]]))));
}

/** The element HA renders a card type as: hui-tile-card, or the custom element's own name. */
/**
 * The element HA's page shows for a card: its `hui-<type>-card`, a custom card's own tag, or HA's error card for a type
 * HA always loads but has no element for (`section`: `setConfig` on a plain element throws).
 */
const ERROR_CARD_TYPES = new Set(['section']);
const cardElement = (card) => (card.type.startsWith('custom:') ? card.type.slice(7)
  : ERROR_CARD_TYPES.has(card.type) ? 'hui-error-card' : `hui-${card.type}-card`);

/** The pages of touch frames (`hasTouch`), whose screenshots must keep HA's coarse-pointer styling. */
const touchPages = new WeakSet();

/**
 * A screenshot of the whole page, for the settle loop's comparisons. Playwright's `fullPage` draws beyond the window,
 * and that turns touch emulation off: HA's page would be drawn for a fine pointer (the chart legend's 24 px rows). On
 * a touch frame the window grows to the page for a plain screenshot instead, then goes back. The reference's own PNG
 * is taken by [captureShot], in the one layout its slots and texts are measured in.
 */
async function fullShot(page, opts) {
  if (!touchPages.has(page) || touchOff.has(page)) return page.screenshot({ ...opts, fullPage: true });
  const vp = page.viewportSize();
  const h = await page.evaluate(() => Math.ceil(document.documentElement.scrollHeight));
  const grow = h > vp.height;
  if (grow) await page.setViewportSize({ width: vp.width, height: h });
  try {
    return await page.screenshot(opts);
  } finally {
    if (grow) await page.setViewportSize(vp);
  }
}

/** The window each grown page had ([growTo]), to put back. */
const grownPages = new WeakMap();

/** Grows [page]'s window to [height] CSS px (`grow` capture mode), remembering the panel's own. */
async function growTo(page, height) {
  const vp = grownPages.get(page) ?? page.viewportSize();
  grownPages.set(page, vp);
  await page.setViewportSize({ width: vp.width, height });
}

/** Touch pages whose touch emulation is off for a pinned page's capture (`pinned` mode). */
const touchOff = new WeakSet();

/** One DevTools session per touch page, kept open: an override goes away with the session that set it. */
const touchSessions = new WeakMap();

/** Turns [page]'s touch emulation on or off (HA's coarse or fine pointer). */
async function setTouch(page, on) {
  let cdp = touchSessions.get(page);
  if (!cdp) {
    cdp = await page.context().newCDPSession(page);
    touchSessions.set(page, cdp);
  }
  await cdp.send('Emulation.setTouchEmulationEnabled', on ? { enabled: true, maxTouchPoints: 1 } : { enabled: false });
}

/**
 * Puts a page back the way the panel has it, once a case's reference is taken: a grown window back to the panel's
 * size, and touch emulation back on after a pinned page's full-page shot turned it off.
 */
async function restoreWindow(page) {
  const vp = grownPages.get(page);
  if (vp) {
    grownPages.delete(page);
    await page.setViewportSize(vp);
  }
  if (touchOff.has(page)) {
    touchOff.delete(page);
    await setTouch(page, true);
  }
}

/**
 * The reference's PNG, in the layout the case's slots and texts are measured in ([mode], `lib/pointer.mjs`). A touch
 * capture checks HA's page still has its coarse pointer and fails the case otherwise. A pinned page was settled again
 * with touch emulation off before anything was measured, so its slots, PNG and texts all describe the fine-pointer
 * layout its reference records.
 */
async function captureShot(page, mode, opts) {
  if (mode === 'mouse' || mode === 'pinned') return page.screenshot({ ...opts, fullPage: true });
  // An open dialog: the window as it shows it, never a full page (lib/pointer.mjs).
  if (mode === 'dialog') return page.screenshot(opts);
  if (!(await page.evaluate(() => matchMedia('(pointer: coarse)').matches))) {
    throw new Error('a touch frame\'s page lost its coarse pointer: HA would draw it for a mouse');
  }
  return page.screenshot(opts);
}

/** Waits until the slots exist and two screenshots 400 ms apart are the same (animations, images, fonts). */
/**
 * Stops the page's CSS animations, transitions and Web Animations, in the document and every shadow root: a finite
 * one jumps to its end, an infinite one is cancelled. Run before the card is measured and
 * again before each screenshot and text read, so the PNG, the slots and the texts show one still frame (the
 * screenshot's own `animations: 'disabled'` resumes them afterwards).
 */
const FREEZE_JS = () => {
  // The document's animations and every shadow root's (document.getAnimations() may leave those out), walked down
  // through each open shadow root.
  const roots = [document];
  for (let i = 0; i < roots.length; i++) {
    for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
  }
  const seen = new Set();
  for (const root of roots) {
    for (const a of root.getAnimations ? root.getAnimations() : []) {
      if (seen.has(a)) continue;
      seen.add(a);
      try {
        const t = a.effect && a.effect.getComputedTiming();
        if (t && t.iterations === Infinity) a.cancel(); else a.finish();
      } catch {}
    }
  }
};

/**
 * Runs in the page: HA's media-control cards whose artwork isn't as wide as the card is tall yet. The card measures
 * itself on its first draw and then on its ResizeObserver, which it attaches only if that first draw had an `ha-card`.
 * In the capture a card can draw before its entity's state arrives (an `hui-warning`, no `ha-card`), so its observer
 * never watches the card it draws once the state is there: the art keeps the first, wrong measure (165 px on a 201 px
 * card). A fresh HA page, which has the states before the card draws, re-measures. Such a card is attached again
 * once (`connectedCallback` attaches the observer to the drawn card, as when HA shows a cached view again), and the
 * capture waits for the measure. Returns the cards still waiting.
 */
const MEDIA_MEASURE_JS = () => {
  const waiting = [];
  const walk = (root) => {
    for (const el of root.querySelectorAll('*')) {
      if (el.shadowRoot) walk(el.shadowRoot);
      if (el.localName !== 'hui-media-control-card' || !el.shadowRoot) continue;
      const card = el.shadowRoot.querySelector('ha-card');
      const art = el.shadowRoot.querySelector('.image');
      if (!card || !art || !el.isConnected) continue;
      if (parseFloat(art.style.width) === card.offsetHeight) continue;
      waiting.push(`${card.offsetWidth}x${card.offsetHeight} art ${art.style.width}`);
      if (el.__gwMeasured) continue;
      el.__gwMeasured = true;
      const parent = el.parentNode;
      const next = el.nextSibling;
      parent.removeChild(el);
      parent.insertBefore(el, next);
    }
  };
  walk(document);
  return waiting;
};

/** Waits (5 s at most) until every media-control card measured the card it draws (MEDIA_MEASURE_JS). */
async function awaitMediaMeasure(page) {
  const t0 = Date.now();
  let waiting = await page.evaluate(MEDIA_MEASURE_JS);
  while (waiting.length) {
    if (Date.now() - t0 > 5000) throw new Error(`media-control card never measured its height: ${waiting.join(', ')}`);
    // The card's observer measures 250 ms after the last resize (HA's debounce).
    await page.waitForTimeout(300);
    waiting = await page.evaluate(MEDIA_MEASURE_JS);
  }
}

async function settle(page, expectType, timeoutMs, plain = false) {
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
  await awaitMediaMeasure(page);
  let prev = null;
  for (let i = 0; i < 8; i++) {
    await page.evaluate(FREEZE_JS);
    // CSS animations, transitions and Web Animations stopped at their end state, so a card that animates despite
    // reduced motion doesn't leave an arbitrary frame (timers and requestAnimationFrame still run).
    // [plain]: the window as it is (an open dialog's size follows the window, which a grown one would change).
    const shot = plain ? await page.screenshot({ animations: 'disabled' }) : await fullShot(page, { animations: 'disabled' });
    if (prev && shot.equals(prev)) {
      await page.evaluate(FREEZE_JS);
      return { info: await page.evaluate(SLOTS_JS), stable: true };
    }
    prev = shot;
    await page.waitForTimeout(400);
  }
  await page.evaluate(FREEZE_JS);
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

/**
 * Starts the fake HA (fakeha.py with fakehome.py) on a free port of 127.0.0.1 and hands it the corpus's shared inputs
 * (home.json), the harness dashboard and the fixtures folder. Sets [creds] to it (a random token: the fake accepts
 * any, and a random one never meets a word in a card that the redaction would then hide). Returns its "now" (ms).
 */
async function startFake(cases) {
  const tools = join(ROOT, 'tools');
  const script = `import sys; sys.path[:0] = ${JSON.stringify([join(tools, 'perf'), join(tools, 'vitro-parity')])}
import fakeha, fakehome
srv = fakeha.serve(0, harness=False, home=fakehome)
print("serving on", srv.server_address[1], flush=True)
srv.serve_forever()`;
  const child = spawn(process.env.PYTHON || 'python3', ['-c', script], { stdio: ['ignore', 'pipe', 'pipe'] });
  fakeChild = child;
  const port = await new Promise((ok, no) => {
    let out = '';
    const t = setTimeout(() => no(new Error(`the fake HA didn't start in 300 s (the first run fetches HA's frontend):\n${out}`)), 300000);
    const on = (d) => { out += d; const m = out.match(/serving on (\d+)/); if (m) { clearTimeout(t); ok(+m[1]); } };
    child.stdout.on('data', on);
    child.stderr.on('data', on);
    child.on('exit', (code) => { clearTimeout(t); no(new Error(`the fake HA exited (${code}):\n${out}`)); });
  });
  creds = { origin: `http://127.0.0.1:${port}`, token: randomBytes(16).toString('hex') };
  const homeFile = join(corpusRoot, 'home.json');
  if (!existsSync(homeFile)) fail(`no ${homeFile}: the fake HA's shared inputs (now, config, themes, user)`);
  const home = JSON.parse(readFileSync(homeFile, 'utf8'));
  fakeHomeProfile = home.user_data?.language ?? null;
  const res = await fakePost('/vitro-parity/home', { ...home, fixtures_dir: join(corpusRoot, 'fixtures'), dashboard: harnessConfig(cases) });
  log(`fake HA on 127.0.0.1, frontend pinned (tools/perf/hafrontend.py), now ${new Date(res.now * 1000).toISOString()}`);
  return Math.round(res.now * 1000);
}

async function fakePost(path, body) {
  const r = await fetch(creds.origin + path, { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } });
  if (!r.ok) throw new Error(`fake HA ${path}: HTTP ${r.status} ${await r.text()}`);
  return r.json();
}

/**
 * The `language` user data HA's page draws a case with (language, number_format, time_format, …), or null for a
 * user with no profile. On the fake, as fakehome.user_data answers it: the case fixture's `user_data.language` when
 * the case sets that key (null included), else home.json's. On a real HA, the signed-in account's own (read once).
 */
let realProfile = null;
let fakeHomeProfile = null;
function profileLocale(c) {
  const own = c.fixture?.user_data;
  const v = !fakeMode ? realProfile
    : (own && typeof own === 'object' && Object.prototype.hasOwnProperty.call(own, 'language') ? own.language : fakeHomeProfile);
  return v && typeof v === 'object' ? v : null;
}

/** Points the fake HA at one case: its states, registries and data come from the case's `fixture` from now on. */
async function fakeCase(c) {
  if (!c.fixture || typeof c.fixture !== 'object') throw new Error('no `fixture` in the case: the fake HA has nothing to serve it (see the README, "Fixtures")');
  await fakePost('/vitro-parity/case', { id: c.id, fixture: c.fixture });
}

const round = (v) => Math.round(v * 100) / 100;
const roundRect = (r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, typeof v === 'number' ? round(v) : v]));

async function capture() {
  const fakeNow = fakeMode ? await startFake(loadCasesAll()) : null;
  const ha = await connect(creds);
  // A real HA's user draws in their own profile's language and formats (read only).
  if (!fakeMode) realProfile = (await ha.call({ type: 'frontend/get_user_data', key: 'language' }).catch(() => null))?.value ?? null;
  const [haConfig, themes, resources] = await Promise.all([
    ha.call({ type: 'get_config' }), ha.call({ type: 'frontend/get_themes' }), ha.call({ type: 'lovelace/resources' }),
  ]);
  const cardModUrl = resources.map((r) => r.url).find((u) => /card-mod\.js/.test(u)) || null;
  // The display data Vitro reads live (HaDisplay): fetched once, cut down per case.
  // The fake HA's registries are the current case's: fetched again for each case (loadRegistries).
  let registry, devices, areas;
  const loadRegistries = async () => {
    [registry, devices, areas] = await Promise.all([ha.call({ type: 'config/entity_registry/list_for_display' }), ha.call({ type: 'config/device_registry/list' }), ha.call({ type: 'config/area_registry/list' })]);
  };
  await loadRegistries();
  const displayCache = new Map();
  // Translations are in the case's language: the one its user's profile sets (fixture `user_data.language`), as
  // HA's page asks for them, else English.
  let language = 'en';
  const fetchDisplay = async (type, category, names) => {
    const lang = type === 'frontend/get_translations' ? language : '';
    const want = names.filter((n) => !displayCache.has(`${type}|${category}|${n}|${lang}`));
    if (want.length) {
      const res = await ha.call({ type, category, integration: want, ...(lang ? { language: lang } : {}) });
      for (const n of want) {
        let mine;
        if (type === 'frontend/get_icons') mine = res.resources?.[n];
        else mine = Object.fromEntries(Object.entries(res.resources || {}).filter(([k]) => k.startsWith(`component.${n}.`)));
        displayCache.set(`${type}|${category}|${n}|${lang}`, mine);
      }
    }
  };
  const displayFor = async (ids, shown, areaIds) => {
    const first = buildDisplay(ids, registry, devices, () => undefined, () => undefined);
    const { domains, platforms } = first.requested;
    await fetchDisplay('frontend/get_icons', 'entity_component', domains);
    await fetchDisplay('frontend/get_icons', 'entity', platforms);
    await fetchDisplay('frontend/get_translations', 'entity_component', domains);
    await fetchDisplay('frontend/get_translations', 'entity', platforms);
    const deviceClasses = Object.fromEntries(Object.entries(shown).map(([id, st]) => [id, st.a?.device_class]).filter(([, dc]) => dc));
    return buildDisplay(ids, registry, devices,
      (category, n) => displayCache.get(`frontend/get_icons|${category}|${n}|`),
      (category, n) => displayCache.get(`frontend/get_translations|${category}|${n}|${language}`), deviceClasses, areas, areaIds);
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
  // Every reference is drawn with these inputs (themes, config): a partial refresh can't change them under the
  // references it leaves alone. Written only after the whole capture succeeds.
  const sharedFile = join(refs, 'shared.json');
  const partial = Boolean(args.cases || args.frames);
  if (partial && existsSync(sharedFile) && readFileSync(sharedFile, 'utf8') !== sharedText + '\n') {
    fail('HA\'s shared inputs (version, config or themes) changed since the references were taken: run a full --refresh, without --cases or --frames');
  }

  const fonts = [...await robotoFaces(), interFace()];
  const browser = await launch();
  // The fake HA's fixed now (home.json); a real HA's pinned 10:08.
  const now = fakeNow ?? pinnedNow(haConfig.time_zone);
  let failures = 0;
  for (const frame of frames) {
    const mine = cases.filter((c) => !c.frames || c.frames.includes(frame.id));
    for (const dark of [true, false]) {
      const group = mine.filter((c) => (c.dark ?? true) === dark);
      if (!group.length) continue;
      const { ctx, page, states, images, graphs } = await newPage(browser, frame, dark, now, cardModUrl, haConfig.time_zone, fonts);
      let loaded = false;
      for (const c of group) {
        const t0 = Date.now();
        images.failed.clear();
        // The history and statistics this case's cards ask for, from its navigation on.
        graphs.reset();
        try {
          const path = c.card || c.view_config ? `/${HARNESS}/${c.id}` : `/${c.dashboard}/${c.view}`;
          const resources = fixtureResources(c);
          // The fake HA serves this case's fixture from now on; its page loads afresh (new states, new registries).
          if (fakeMode) {
            await fakeCase(c);
            await loadRegistries();
            loaded = false;
          }
          const userLocale = profileLocale(c);
          language = userLocale?.language || 'en';
          // A case with its own stylesheets gets a page of its own, and the next case a fresh one: what a sheet
          // declares (a font) stays in the page it was added to.
          // A More Info case too: its dialog stays open on the page, so it starts from a fresh one and leaves it.
          if (!loaded || c.dashboard || resources.length || c.more_info) {
            await page.goto(creds.origin + path, { waitUntil: 'load', timeout: 45000 });
            loaded = !c.dashboard && !resources.length && !c.more_info;
          } else {
            await page.evaluate((p) => { history.pushState(null, '', p); window.dispatchEvent(new CustomEvent('location-changed', { detail: { replace: false } })); scrollTo(0, 0); }, path);
          }
          if (resources.length) {
            // As HA's loadLovelaceResources does for `type: css`: a <link rel=stylesheet> in <head>, per resource.
            await page.evaluate(async (urls) => {
              await Promise.all(urls.map((href) => new Promise((ok, fail) => {
                const link = document.createElement('link');
                link.rel = 'stylesheet';
                link.type = 'text/css';
                link.onload = ok;
                link.onerror = () => fail(new Error(`fixture stylesheet ${href} didn't load`));
                link.href = href;
                document.head.appendChild(link);
              })));
            }, resources.map((r) => new URL(r.url, creds.origin).toString()));
          }
          const config = c.card ?? c.view_config ?? JSON.parse(readFileSync(join(corpusRoot, 'dashboards', `${c.dashboard}.json`), 'utf8'));
          const outDir = join(refs, frame.id);
          mkdirSync(outDir, { recursive: true });
          let info, stable, clip, slots, png, texts, templates, ids, drawnStates, drawn, display, drawnGraphs, loadedAtShot, mode;
          // The PNG, its texts and the states saved with them must be one instant: an entity that changes while
          // they're taken makes the pair disagree, so the capture starts over (three tries).
          for (let attempt = 1; ; attempt++) {
            // A window an earlier attempt (or a failed case) grew goes back to the panel's size first.
            await restoreWindow(page);
            ({ info, stable } = await settle(page, c.card ? cardElement(c.card) : null, c.dashboard ? 20000 : 15000, Boolean(c.more_info)));
            // A More Info case (#885): the dialog opens over the card's view, as a tap on the card opens it, and settles.
            if (c.more_info) {
              await openMoreInfo(page, c.more_info);
              ({ stable } = await settle(page, cardElement(c.card), 15000, true));
            }
            // A graph waits on one-shot answers (the statistics card asks for statistics after its metadata): a call
            // still unanswered means the card isn't drawn yet. Wait for them (10 s at most), then settle again.
            const pending = () => [...pendingOneShots(graphs.calls), ...pendingFirstEvents(graphs.calls)];
            if (pending().length) {
              const w0 = Date.now();
              while (pending().length && Date.now() - w0 < 10000) await page.waitForTimeout(100);
              const left = pending();
              if (left.length) throw new Error(`graph call(s) never answered: ${left.join(', ')}`);
              if (attempt >= 3) throw new Error('graph answers kept arriving during the capture (3 tries)');
              log(`${frame.id}/${c.id}: a graph answer arrived after the page settled: again`);
              continue;
            }
            // On a touch panel, a page taller than the window is measured and shot in a window grown to it: the PNG,
            // the slots and the texts below all describe that one layout (lib/pointer.mjs).
            mode = captureMode({ touch: touchPages.has(page), docHeight: info.docH, viewportHeight: page.viewportSize().height, slots: info.slots, dialog: Boolean(c.more_info) });
            if (mode === 'grow') {
              // The page may grow with the window (a height in vh): after it settles, it must fit the window it was
              // grown for, or the screenshot would cut it short. Grown again if not, a few times at most.
              for (let grows = 1; ; grows++) {
                await growTo(page, Math.ceil(info.docH));
                ({ info, stable } = await settle(page, c.card ? cardElement(c.card) : null, c.dashboard ? 20000 : 15000));
                if (afterGrow({ docHeight: info.docH, viewportHeight: page.viewportSize().height, grows }) === 'fits') break;
              }
            }
            if (mode === 'pinned') {
              // The whole capture with a fine pointer, as its reference will say: touch off before anything is
              // measured, and on again once the case is done (restoreWindow).
              log(`${frame.id}/${c.id}: a pinned card on a page taller than the window: captured with a fine pointer`);
              touchOff.add(page);
              await setTouch(page, false);
              ({ info, stable } = await settle(page, c.card ? cardElement(c.card) : null, c.dashboard ? 20000 : 15000));
            }
            if (!info.slots.length && c.card) throw new Error('no card on the page');
            if (!info.slots.length) log(`${frame.id}/${c.id}: HA shows no card (all hidden?): comparing the whole page`);
            if (c.more_info) {
              // The one slot is the dialog's surface; the crop around it stays inside the window.
              const d = await page.evaluate(DIALOG_JS);
              if (!d) throw new Error('the More Info dialog closed during the capture');
              slots = [{ ...d, scope: 'more-info' }];
              const vp = page.viewportSize();
              clip = clipFor(d, { docW: vp.width, docH: vp.height });
            } else if (c.card) {
              slots = [info.slots[0]];
              clip = clipFor(slots[0], info);
            } else {
              slots = info.slots;
              clip = { x: 0, y: 0, width: info.docW, height: info.docH };
            }
            ids = referencedEntities(config, states);
            if (c.more_info) ids.add(c.more_info);
            // The entities a case expects a state of are part of its snapshot even when HA has no state for them yet:
            // one that appears during the capture then starts it over, like any other change.
            for (const id of Object.keys(c.expect || {})) ids.add(id);
            drawnStates = snapshotStates(states, ids);
            // The history and statistics the graphs drew, as of the screenshot (an answer after it would be data HA
            // never showed: the same check as the states).
            drawnGraphs = snapshotCalls(graphs.calls).filter((call) => keepCall(call, fakeMode, config, c.more_info));
            // The images the screenshot shows, read just before it and checked again after (bracketed like the
            // states): saved for Vitro's fixture server after the checks.
            drawn = await page.evaluate(DRAWN_IMAGES_JS, slots);
            // The bodies those images came with, as of the screenshot: a later response for the same URL must not
            // be saved beside a picture of the earlier one.
            loadedAtShot = new Map(images.loaded);
            // Kept in memory until every check passes: a failed case leaves no PNG behind.
            await page.evaluate(FREEZE_JS);
            png = await captureShot(page, mode, { clip, animations: 'disabled' });
            await page.evaluate(FREEZE_JS);
            texts = [];
            for (const [i, s] of slots.entries()) for (const t of await page.evaluate(TEXTS_JS, s)) texts.push({ slot: i, ...roundRect(t) });
            if (c.more_info) slots = slots.map(({ scope, ...rest }) => rest);
            // The templates Vitro asks for, rendered now.
            templates = {};
            for (const tpl of wanted[c.id] || []) {
              const r = await ha.renderTemplate(tpl);
              templates[tpl] = r.error ? { error: String(r.error) } : { result: typeof r.result === 'string' ? r.result : JSON.stringify(r.result) };
            }
            display = await displayFor(ids, drawnStates, referencedAreas(config));
            // One instant for the PNG, its states and its images: anything that moved meanwhile starts it over.
            const sameStates = JSON.stringify(snapshotStates(states, ids)) === JSON.stringify(drawnStates);
            const sameImages = JSON.stringify(await page.evaluate(DRAWN_IMAGES_JS, slots)) === JSON.stringify(drawn);
            const sameGraphs = JSON.stringify(graphs.calls.filter((call) => keepCall(call, fakeMode, config, c.more_info))) === JSON.stringify(drawnGraphs);
            // Only the drawn images' bodies: another card loading a picture meanwhile changes nothing here.
            const drawnPaths = drawn.drawn.map((url) => { try { const u = new URL(url); return u.origin === creds.origin ? u.pathname : null; } catch { return null; } }).filter(Boolean);
            const sameBodies = drawnPaths.every((p) => images.loaded.get(p) === loadedAtShot.get(p));
            if (sameStates && sameImages && sameGraphs && sameBodies) break;
            if (attempt >= 3) throw new Error('entity states, drawn images or graph data kept changing during the capture (3 tries)');
            log(`${frame.id}/${c.id}: ${!sameStates ? 'an entity' : !sameImages ? 'a drawn image' : 'graph data'} changed during the capture: again`);
          }
          await restoreWindow(page);
          // A case that covers one state (a light on, an alarm disarmed) keeps its reference when HA isn't in it.
          const unmet = unmetExpectations(c.expect, drawnStates);
          if (unmet.length) throw new Error(`not in the state the case expects (${unmet.join('; ')}): neither its PNG nor its JSON is written`);
          // The screenshot shows what the JSON scrubs: check the raw text HA drew and the templates before writing.
          if (committed) {
            const shown = findPrivate(JSON.stringify({ texts, templates }), creds);
            if (shown.length) throw new Error(`the card shows ${shown.join(', ')}: neither its PNG nor its JSON is written`);
          }
          const shownStates = drawnStates; // the states the PNG shows
          // The fake's states are the case's own made-up fixture: kept whole (artwork, media titles, a person's user),
          // so Vitro gets what HA drew. A real HA's lose what scrubStates drops.
          const caseStates = scrubStates(shownStates, creds, { keepPictures: !committed || fakeMode, synthetic: fakeMode });
          // A local corpus also keeps the pictures its entities show (plants, artwork), for Vitro's fixture server.
          if (!committed) {
            for (const id of ids) {
              const pic = states[id]?.a?.entity_picture;
              if (typeof pic !== 'string') continue;
              if (pic.startsWith('/')) await fetchAsset(pic.split('?')[0], pic).catch((e) => log(`  picture: ${scrubText(String(e.message), creds)}`));
            }
          }
          // Device names can carry a MAC fragment (an ESPHome default like "AirDot 4c83f8"): check the block too.
          if (committed) {
            const inDisplay = findPrivate(JSON.stringify(display), creds);
            if (inDisplay.length) throw new Error(`the display data holds ${inDisplay.join(', ')}: neither file is written`);
          }
          // Drawn images HA asked for and didn't get, or got and couldn't decode: Vitro must not get them either.
          const onHa = (url) => { try { const u = new URL(url); return u.origin === creds.origin ? u.pathname : null; } catch { return null; } };
          const failedDrawn = [...new Set([
            ...drawn.drawn.map(onHa).filter((p) => p && images.failed.has(p)),
            ...drawn.broken.map(onHa).filter(Boolean),
          ])].sort();
          // Whether HA's page drew for an administrator (`hass.user.is_admin`): HA's error and warning cards title
          // themselves for one only. Written only for a user who isn't, so every other reference stays as it is.
          const pageAdmin = await page.evaluate(() => document.querySelector('home-assistant')?.hass?.user?.is_admin === true);
          const ref = {
            case: c.id, frame: frame.id, haVersion: haConfig.version, now, stable,
            viewport: { width: frame.width, height: frame.height, density: frame.density, ...(capturedWithTouch(mode) ? { touch: true } : {}) },
            clip, docSize: { width: info.docW, height: info.docH }, slots: slots.map(roundRect), texts,
            states: caseStates, templates, display,
            // The user's language and formats HA's page drew with (its `language` user data), for Vitro's same
            // settings. Only written when the case sets some, so an English reference doesn't change.
            ...(userLocale ? { locale: userLocale } : {}),
            ...(pageAdmin ? {} : { user: { is_admin: false } }),
            // Image paths HA's page asked for and didn't get: Vitro missing them too is no capture gap. Only
            // written when there are some, so a clean reference doesn't change.
            ...(failedDrawn.length ? { imagesHaMissing: failedDrawn } : {}),
            // The history and statistics calls the card's graphs made and what came back, for Vitro's same calls.
            // Only written when there are some.
            ...(drawnGraphs.length ? { ws: fakeMode ? drawnGraphs : scrubGraphCalls(drawnGraphs) } : {}),
          };
          if (!committed) {
            // Pictures that templates point at (a plant's image entity inside Markdown, say), unredacted from the raw text.
            const raw = JSON.stringify({ templates, states: shownStates });
            for (const m of new Set(raw.match(/\/api\/[a-z_]+_proxy\/[^"'\s)\\]+/g) || [])) {
              await fetchAsset(m.split('?')[0], m.replace(/&amp;/g, '&')).catch((e) => log(`  picture: ${scrubText(String(e.message), creds)}`));
            }
          }
          const text = scrubText(JSON.stringify(ref, null, 1), creds);
          if (committed) {
            checkDenylist(text, `refs/${frame.id}/${c.id}`);
            // scrubText replaces whole addresses, not the end of a MAC in a name: check everything that's written
            // (states, texts, templates, display data), not just what the card draws.
            if (hasMacFragment(text)) throw new Error('the reference holds a MAC fragment (a device or entity name?): neither file is written');
          }
          await saveDrawnImages(loadedAtShot, drawn.drawn.filter((url) => !failedDrawn.includes(onHa(url))));
          stage(join(outDir, `${c.id}.png`), png);
          stage(join(outDir, `${c.id}.json`), text + '\n');
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
  if (fakeMode) {
    // Commands the fake didn't know: the frontend or a card asked for something no fixture answers.
    const st = await (await fetch(creds.origin + '/vitro-parity/state')).json();
    if (st.unknown.length) log(`fake HA: commands it doesn't answer (the pages got an error for them): ${st.unknown.join(', ')}`);
  }
  ha.close();
  // One generation: the cases' files, their images and the shared inputs are written together, and only when every
  // selected capture succeeded. A failure leaves the corpus as it was (a retry then compares against the same inputs).
  if (failures) fail(`${failures} capture(s) failed: nothing was written`);
  for (const [path, data] of staged) {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, data);
  }
  writeFileSync(sharedFile, sharedText + '\n');
  // Only a full refresh that captured everything knows what no reference uses anymore: an image HA stopped loading
  // or drawing must not stay in assets/, where the fixture server would keep handing it to Vitro.
  if (!args.cases && !args.frames) {
    for (const p of staleAssets(existingAssets(), keptAssets)) {
      rmSync(assetDestination(join(corpusRoot, 'assets'), p, false));
      log(`asset ${p} removed: no reference draws it anymore`);
    }
  }
}

try {
  if (args.cmd === 'prepare') await prepare();
  else await capture();
} catch (e) {
  fail(`capture.mjs ${args.cmd}: ${e?.stack || e}`);
}
process.exit(0);
