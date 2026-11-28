// Runs one island page as Vitro would serve it (written by IslandPageDumpTest) in headless Chromium, with the
// island's bridge socket answered here, and reports what the card drew and what it sent over the bridge. For
// island-probe.mjs and test/island-detect.e2e.test.mjs.
//
// A case whose scripts are under /hacsfiles/ (pinned community cards) runs on the parity harness's fake HA's origin,
// which serves them; one whose scripts are under /__island-test/ (the made-up cards in island-cases/cards/) needs no
// fake: those files are served from the repo, on a made-up origin no request leaves the browser for.
//
// Touches: a fake HA on 127.0.0.1 when a case needs one (stopped when the process exits) and a throwaway headless
// Chromium. Reads the island page from engines/engine-vitro/build/vitro-island/. Writes nothing but an optional PNG.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, join, normalize } from 'node:path';
import { launch } from './browser.mjs';
import { startFake } from './fake.mjs';

/** Where IslandPageDumpTest writes the page of [caseFile]. */
export function islandPagePath(root, caseFile) {
  return join(root, 'engines', 'engine-vitro', 'build', 'vitro-island', basename(caseFile).replace(/\.json$/, '') + '.html');
}

const TEST_PREFIX = '/__island-test/';

/**
 * Probes [caseFile]'s island page. Options: `frame` (frames.json id, default tablet), `waitMs` (how long the page runs
 * before it's read, default 9000: the island waits up to 4 s for the card's element, and its frontend watch decides
 * about 3 s after the card draws (a 0.4 s first scan and a 2.5 s grace), so a card defined late still gets its report), `click` ([x, y]), `shot` (a PNG path), `browser` (one to reuse), `evaluate` (a
 * function's source, called in the page with the card's element, or null when there is none), `snapshotDelayMs` (hold
 * the bridge's first snapshot back that long, so the card draws once with no states, as when its script loads before
 * the bridge connects).
 * Returns { card, before, bridge, tap, errors, evaluated }: `before` is what the card drew, `bridge` what the page
 * sent, `evaluated` what `evaluate` returned, `storage` the island's localStorage for a case with `storage`.
 */
export async function probeIsland(root, caseFile, opts = {}) {
  const corpus = join(root, 'engines', 'engine-vitro', 'src', 'test', 'parity');
  const c = JSON.parse(readFileSync(caseFile, 'utf8'));
  const pageFile = islandPagePath(root, caseFile);
  if (!existsSync(pageFile)) throw new Error(`no island page at ${pageFile}: write it with IslandPageDumpTest first`);
  const frame = JSON.parse(readFileSync(join(corpus, 'frames.json'), 'utf8')).frames.find((f) => f.id === (opts.frame || 'tablet'));
  const tag = String(c.card?.type || '').replace(/^custom:/, '');
  const needsFake = (c.resources || []).some((r) => !r.startsWith(TEST_PREFIX));
  let origin = 'http://island.test';
  let now = Date.now();
  if (needsFake) ({ origin, now } = await startFake(root, corpus, { cards: [], fixture: c.fixture, id: 'island-probe' }));
  // The fixture's states as HA's state objects: `last_changed` in seconds from now (or now).
  const iso = (v) => new Date(typeof v === 'number' ? now + v * 1000 : now).toISOString();
  const states = Object.fromEntries(Object.entries(c.fixture?.states || {}).map(([id, s]) => [id, {
    entity_id: id, state: String(s.state), attributes: s.attributes || {},
    last_changed: iso(s.last_changed), last_updated: iso(s.last_updated ?? s.last_changed), context: { id: 'island-probe' },
  }]));

  const browser = opts.browser || await launch();
  // The island's width on the panel: a card in a section of the frame's width.
  const ctx = await browser.newContext({ viewport: { width: Math.min(frame.width, 500), height: frame.height }, deviceScaleFactor: frame.density, colorScheme: 'dark' });
  try {
    await ctx.route(origin + '/__vitro/island/probe', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: readFileSync(pageFile, 'utf8') }));
    // The island server's icons: an empty one (this probe is about the card, not MDI).
    await ctx.route((u) => u.pathname.startsWith('/__vitro/icon/'), (r) => r.fulfill({ status: 200, contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"></svg>' }));
    // HA's strings for the island's localize: none here (a miss is "", as before HA's files load).
    await ctx.route((u) => u.pathname.startsWith('/__vitro/strings/'), (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
    // The made-up cards, from island-cases/cards/ only.
    const testDir = join(root, 'tools', 'vitro-parity', 'island-cases', 'cards');
    await ctx.route((u) => u.pathname.startsWith(TEST_PREFIX), (r) => {
      const rel = normalize(new URL(r.request().url()).pathname.slice(TEST_PREFIX.length));
      const file = join(testDir, rel.replace(/^cards\//, ''));
      if (rel.includes('..') || !file.startsWith(testDir) || !existsSync(file)) return r.fulfill({ status: 404, body: '' });
      return r.fulfill({ status: 200, contentType: 'text/javascript', body: readFileSync(file, 'utf8') });
    });
    // Without a fake HA, nothing else on the made-up origin answers.
    if (!needsFake) await ctx.route((u) => u.origin === origin && !u.pathname.startsWith('/__vitro/') && !u.pathname.startsWith(TEST_PREFIX), (r) => r.fulfill({ status: 404, body: '' }));
    const sent = [];
    await ctx.routeWebSocket(/\/__vitro\/ws\//, (ws) => {
      ws.onMessage((m) => {
        for (const line of String(m).split('\n').filter(Boolean)) {
          let msg;
          try { msg = JSON.parse(line); } catch { continue; }
          sent.push(msg);
          if (msg.type === 'registries') ws.send(JSON.stringify({ type: 'registries', registries: { entities: {}, devices: {}, areas: {}, floors: {} } }) + '\n');
          if (msg.type === 'callWs') ws.send(JSON.stringify({ type: 'wsReply', id: msg.id, ok: false, error: { code: 'not_supported', message: 'island-probe answers no calls' } }) + '\n');
          // hass.connection.subscribeMessage: refused too, as HA refuses a command of an integration it doesn't have.
          if (msg.type === 'subscribe') ws.send(JSON.stringify({ type: 'subError', sid: msg.sid, error: { code: 'not_supported', message: 'island-probe answers no subscriptions' } }) + '\n');
        }
      });
      // As the panel's seed: where HA's connection is (a page asks for its subscriptions once it hears), then states.
      ws.send(JSON.stringify({ type: 'conn', connected: true, epoch: 1 }) + '\n');
      // A card a stack-in-card holds (`in_stack`: its `keep` options): the panel says so in its theme message, as Vitro
      // does for an island in a native stack-in-card. No css: the page keeps the theme it was written with.
      if (c.in_stack) ws.send(JSON.stringify({ type: 'theme', dark: c.dark !== false, name: '', stack: c.in_stack }) + '\n');
      const snapshot = () => ws.send(JSON.stringify({ type: 'snapshot', states }) + '\n');
      if (opts.snapshotDelayMs) setTimeout(snapshot, opts.snapshotDelayMs); else snapshot();
    });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 200)); });
    await page.goto(origin + '/__vitro/island/probe', { waitUntil: 'load' });
    await page.waitForTimeout(opts.waitMs ?? 9000);

    const before = await page.evaluate((tag) => {
      const mount = document.getElementById('mount');
      const card = mount && [...mount.children].find((e) => e.localName === tag);
      if (!card) return { instance: false, defined: !!customElements.get(tag), mountHeight: Math.round(mount ? mount.getBoundingClientRect().height : 0) };
      // The card's own elements and those of the elements it ships (its tag's prefix, or Mushroom's), never inside HA's.
      const own = (t) => t === tag || t.startsWith(tag.split('-')[0] + '-') || t.startsWith('mushroom-');
      const inside = [];
      const walk = (r) => r.querySelectorAll('*').forEach((e) => { inside.push(e); if (e.shadowRoot && own(e.localName)) walk(e.shadowRoot); });
      walk(card.shadowRoot || card);
      const tags = [...new Set(inside.map((e) => e.localName).filter((t) => t.includes('-')))].sort();
      return { instance: true, defined: !!customElements.get(tag), height: Math.round(card.getBoundingClientRect().height), elements: tags, undefined: tags.filter((t) => !customElements.get(t)) };
    }, tag);
    const bridge = (list) => list.filter((m) => !['watch', 'watchAll', 'registries'].includes(m.type));
    // A case with HA's localStorage (`storage`): what the island's localStorage holds after it ran.
    const storage = c.storage
      ? await page.evaluate(() => Object.fromEntries(Object.keys(localStorage).filter((k) => !k.startsWith('__vitro')).map((k) => [k, localStorage.getItem(k)])))
      : undefined;
    const out = { card: c.card.type, before, bridge: bridge(sent), tap: null, errors, storage };
    if (opts.evaluate) {
      out.evaluated = await page.evaluate(([src, tag]) => {
        const mount = document.getElementById('mount');
        const card = mount && [...mount.children].find((e) => e.localName === tag);
        return (0, eval)('(' + src + ')')(card || null);
      }, [opts.evaluate, tag]);
    }
    if (opts.click) {
      const [x, y] = opts.click;
      const n = sent.length;
      await page.mouse.click(x, y);
      await page.waitForTimeout(600);
      out.tap = bridge(sent.slice(n));
    }
    // shotTransparent: the page's own transparency kept (an island is a transparent page over Vitro's view).
    if (opts.shot) writeFileSync(opts.shot, await page.screenshot({ omitBackground: !!opts.shotTransparent }));
    return out;
  } finally {
    await ctx.close();
    if (!opts.browser) await browser.close();
  }
}
