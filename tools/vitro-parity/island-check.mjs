#!/usr/bin/env node
// Can a custom card draw in a Vitro island? (#885) Renders the card in Home Assistant's real frontend, served by the
// parity harness's fake HA (tools/perf/fakeha.py with fakehome.py, on 127.0.0.1) from a case file's `card` and
// `fixture`, with the card's pinned script from cards.json loaded as a module. Then it lists every custom element in
// the card's shadow tree and which of them the island page would lack: elements HA's frontend defines (`ha-*`,
// `hui-*`, `state-*`, …) that are neither the card's own nor among the island's stand-ins (the `vitroDefine` names in
// WebIslandCardView.kt). A card that draws with one of those can't draw whole in an island: the island page finds that
// itself (frontend-watch.js) and sends the card's view to HA's frontend; this shows what HA's side draws.
//
// Touches: a fake HA on 127.0.0.1 (stopped at the end) and a throwaway headless Chromium. Reads the pinned cards from
// ~/.cache/glassworks-perf (downloads them on first use). Writes nothing but an optional --shot PNG. No real HA, no
// device.
//
//   node tools/vitro-parity/island-check.mjs <case.json> --card <cards.json name> [--frame tablet] [--shot out.png]
//
// <case.json> is a corpus-style case: `{"card": {...}, "fixture": {...}}` (see README.md, "Fixtures").
import { launch } from './lib/browser.mjs';
import { startFake } from './lib/fake.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CORPUS = join(ROOT, 'engines', 'engine-vitro', 'src', 'test', 'parity');
const ISLAND = join(ROOT, 'engines', 'engine-vitro', 'src', 'main', 'java', 'dev', 'glassworks', 'engine', 'vitro', 'cards', 'WebIslandCardView.kt');

const argv = process.argv.slice(2);
const opt = (name) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined; };
const caseFile = argv[0];
const cardName = opt('--card');
if (!caseFile || !cardName) {
  console.error('usage: island-check.mjs <case.json> --card <cards.json name> [--frame tablet] [--shot out.png]');
  process.exit(2);
}
const c = JSON.parse(readFileSync(caseFile, 'utf8'));
const pin = JSON.parse(readFileSync(join(ROOT, 'tools', 'vitro-parity', 'cards.json'), 'utf8')).cards.find((p) => p.name === cardName);
if (!pin) { console.error(`${cardName} isn't pinned in cards.json`); process.exit(2); }
const frame = JSON.parse(readFileSync(join(CORPUS, 'frames.json'), 'utf8')).frames.find((f) => f.id === (opt('--frame') || 'tablet'));
// The island's stand-ins for HA's elements: what an island page defines besides the card's own script.
const shims = new Set([...readFileSync(ISLAND, 'utf8').matchAll(/(?:vitroDefine|customElements\.define)\('([a-z0-9-]+)'/g)].map((m) => m[1]));

const { origin, home, now, path } = await startFake(ROOT, CORPUS, { cards: [c.card], fixture: c.fixture, id: 'island-check' });
const moduleUrl = `/hacsfiles/${pin.name}/${pin.main}?hacstag=${pin.version}`;
const tag = String(c.card.type || '').replace(/^custom:/, '');

const browser = await launch();
const ctx = await browser.newContext({ viewport: { width: frame.width, height: frame.height }, deviceScaleFactor: frame.density, colorScheme: 'dark', timezoneId: home.config.time_zone, locale: 'en-US' });
await ctx.addInitScript(([origin, now, url]) => {
  const RealDate = Date;
  class FixedDate extends RealDate { constructor(...a) { if (a.length) super(...a); else super(now); } static now() { return now; } }
  window.Date = FixedDate;
  localStorage.setItem('hassTokens', JSON.stringify({ access_token: 'island-check', token_type: 'Bearer', expires_in: 1e9, hassUrl: origin, clientId: origin + '/', expires: RealDate.now() + 1e12, refresh_token: 'x' }));
  localStorage.setItem('dockedSidebar', '"always_hidden"');
  // The card's script as a Lovelace resource would load it, before the dashboard asks for the card.
  const add = () => { const s = document.createElement('script'); s.type = 'module'; s.src = url; document.head.appendChild(s); };
  if (document.head) add(); else document.addEventListener('DOMContentLoaded', add);
}, [origin, now, moduleUrl]);
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
await page.goto(origin + path, { waitUntil: 'load' });
await page.waitForTimeout(6000);
const shot = opt('--shot');
if (shot) writeFileSync(shot, await page.screenshot());
const found = await page.evaluate((tag) => {
  const all = [];
  const walk = (r) => r.querySelectorAll('*').forEach((e) => { all.push(e); if (e.shadowRoot) walk(e.shadowRoot); });
  walk(document);
  const card = all.find((e) => e.localName === tag);
  // No instance on the page: the tag may be registered and still fail to draw (HA shows its error card).
  if (!card) return { defined: !!customElements.get(tag), instance: false, elements: [], error: all.some((e) => e.localName === 'hui-error-card') };
  // The elements the card's own templates create: inside its shadow root and those of its own elements (its tag's
  // prefix, or Mushroom's, which such cards ship), never inside one of HA's (an ha-icon-button's own ha-button).
  const ownTag = (t) => t === tag || t.startsWith(tag.split('-')[0] + '-') || t.startsWith('mushroom-');
  const inside = [];
  const w = (r) => r.querySelectorAll('*').forEach((e) => { inside.push(e); if (e.shadowRoot && ownTag(e.localName)) w(e.shadowRoot); });
  w(card.shadowRoot || card);
  const box = card.getBoundingClientRect();
  return { defined: true, instance: true, size: [Math.round(box.width), Math.round(box.height)], elements: [...new Set(inside.map((e) => e.localName).filter((t) => t.includes('-')))].sort() };
}, tag);
await browser.close();

if (!found.defined) {
  console.log(`HA's frontend: ${c.card.type} isn't defined by ${pin.name} ${pin.version}${found.error ? ' (HA shows its error card)' : ''}`);
  process.exit(1);
}
if (!found.instance) {
  // Registered, but HA's page holds no instance of it: its render failed (setConfig threw, say).
  console.log(`HA's frontend didn't draw ${c.card.type} (${pin.name} ${pin.version}): no instance on the page${found.error ? ', HA shows its error card' : ''}`);
  if (errors.length) console.log(`page errors: ${errors.join(' | ')}`);
  process.exit(1);
}
// The card's own elements: the ones its script defines (by its own tag prefix, or Mushroom's, which such cards ship).
const own = (t) => t === tag || t.startsWith(tag.split('-')[0] + '-') || t.startsWith('mushroom-');
const missing = found.elements.filter((t) => !own(t) && !shims.has(t));
console.log(`HA's frontend draws ${c.card.type} (${pin.name} ${pin.version}) ${found.size ? found.size.join('×') + ' px' : ''} with: ${found.elements.join(', ') || 'nothing'}`);
console.log(missing.length ? `An island lacks: ${missing.join(', ')}` : 'An island has every element it uses.');
if (errors.length) console.log(`page errors: ${errors.join(' | ')}`);
process.exit(0);
