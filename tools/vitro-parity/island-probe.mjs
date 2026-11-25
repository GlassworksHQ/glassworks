#!/usr/bin/env node
// What a custom card draws in a Vitro island (#885): runs the island page Vitro would serve for a case's card (written
// by IslandPageDumpTest) in headless Chromium, on the parity harness's fake HA's origin so the card's pinned script
// loads from /hacsfiles/, with the island's bridge socket answered here: a snapshot of the fixture's states, empty
// registries, and every hass.callWS refused. It reports whether the card's element exists, how tall it drew, the
// custom elements in it that nothing defines, what the page sent over the bridge (cardError, resize, callWs, …) and
// any page errors. `--click x,y` taps the page there and reports what the tap sent.
//
// Touches: a fake HA on 127.0.0.1 (stopped at the end) and a throwaway headless Chromium. Reads the island page from
// engines/engine-vitro/build/vitro-island/ and the pinned cards from ~/.cache/glassworks-perf. Writes nothing but an
// optional --shot PNG. No real HA, no device.
//
//   ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=<case.json>
//   node tools/vitro-parity/island-probe.mjs <case.json> [--frame tablet] [--click x,y] [--shot out.png]
//
// <case.json>: `{"card": {...}, "resources": ["/hacsfiles/<card>/<file>.js?hacstag=<version>"], "fixture": {...}}`.
// The resource names a file cards.json pins; the fixture is a corpus fixture (README.md, "Fixtures"). An optional
// `"storage": {key: value}` is HA's localStorage on the panel: the island takes it in before its card, and the probe
// reports what the island's localStorage holds after it ran.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launch } from './lib/browser.mjs';
import { startFake } from './lib/fake.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CORPUS = join(ROOT, 'engines', 'engine-vitro', 'src', 'test', 'parity');
const argv = process.argv.slice(2);
const opt = (name) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined; };
const caseFile = argv[0];
if (!caseFile) {
  console.error('usage: island-probe.mjs <case.json> [--frame tablet] [--click x,y] [--shot out.png]');
  process.exit(2);
}
const c = JSON.parse(readFileSync(caseFile, 'utf8'));
const pageFile = join(ROOT, 'engines', 'engine-vitro', 'build', 'vitro-island', basename(caseFile).replace(/\.json$/, '') + '.html');
if (!existsSync(pageFile)) {
  console.error(`no island page at ${pageFile}: write it first with\n  ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=${resolve(caseFile)}`);
  process.exit(2);
}
const frame = JSON.parse(readFileSync(join(CORPUS, 'frames.json'), 'utf8')).frames.find((f) => f.id === (opt('--frame') || 'tablet'));
const tag = String(c.card?.type || '').replace(/^custom:/, '');

const { origin, now } = await startFake(ROOT, CORPUS, { cards: [], fixture: c.fixture, id: 'island-probe' });
// The fixture's states as HA's state objects: `last_changed` in seconds from now (or now).
const iso = (v) => new Date(typeof v === 'number' ? now + v * 1000 : now).toISOString();
const states = Object.fromEntries(Object.entries(c.fixture?.states || {}).map(([id, s]) => [id, {
  entity_id: id, state: String(s.state), attributes: s.attributes || {},
  last_changed: iso(s.last_changed), last_updated: iso(s.last_updated ?? s.last_changed), context: { id: 'island-probe' },
}]));

const browser = await launch();
// The island's width on the panel: a card in a section of the frame's width.
const ctx = await browser.newContext({ viewport: { width: Math.min(frame.width, 500), height: frame.height }, deviceScaleFactor: frame.density, colorScheme: 'dark' });
await ctx.route(origin + '/__vitro/island/probe', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: readFileSync(pageFile, 'utf8') }));
// The island server's icons: an empty one (this probe is about the card, not MDI).
await ctx.route((u) => u.pathname.startsWith('/__vitro/icon/'), (r) => r.fulfill({ status: 200, contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"></svg>' }));
const sent = [];
await ctx.routeWebSocket(/\/__vitro\/ws\//, (ws) => {
  ws.onMessage((m) => {
    for (const line of String(m).split('\n').filter(Boolean)) {
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      sent.push(msg);
      if (msg.type === 'registries') ws.send(JSON.stringify({ type: 'registries', registries: { entities: {}, devices: {}, areas: {}, floors: {} } }) + '\n');
      if (msg.type === 'callWs') ws.send(JSON.stringify({ type: 'wsReply', id: msg.id, ok: false, error: { code: 'not_supported', message: 'island-probe answers no calls' } }) + '\n');
    }
  });
  ws.send(JSON.stringify({ type: 'snapshot', states }) + '\n');
});
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 200)); });
await page.goto(origin + '/__vitro/island/probe', { waitUntil: 'load' });
// The island waits up to 4 s for the card's element before it reports one missing.
await page.waitForTimeout(6000);

const inspect = () => page.evaluate((tag) => {
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
if (c.storage) {
  const held = await page.evaluate(() => Object.fromEntries(Object.keys(localStorage).filter((k) => !k.startsWith('__vitro')).map((k) => [k, localStorage.getItem(k)])));
  console.log(`localStorage: ${JSON.stringify(held)}`);
}
const before = await inspect();
console.log(`island ${c.card.type}: ${JSON.stringify(before)}`);
console.log(`bridge: ${JSON.stringify(bridge(sent))}`);
const click = opt('--click');
if (click) {
  const [x, y] = click.split(',').map(Number);
  const n = sent.length;
  await page.mouse.click(x, y);
  await page.waitForTimeout(600);
  console.log(`tap at ${x},${y} sent: ${JSON.stringify(bridge(sent.slice(n)))}`);
}
const shot = opt('--shot');
if (shot) writeFileSync(shot, await page.screenshot());
if (errors.length) console.log(`page errors: ${errors.join(' | ')}`);
await browser.close();
process.exit(0);
