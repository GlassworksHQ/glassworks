// The island page's watch for HA frontend elements a card draws with but the page lacks (frontend-watch.js, inlined
// by WebIslandCardView.buildIslandHtml), run in Node against a small fake DOM, a fake clock and a fake
// MutationObserver. Touches nothing. The same file in a real browser: island-detect.e2e.test.mjs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SOURCE = readFileSync(join(ROOT, 'engines', 'engine-vitro', 'src', 'main', 'resources', 'dev', 'glassworks', 'engine', 'vitro', 'island', 'frontend-watch.js'), 'utf8');

function load() {
  const ctx = vm.createContext({});
  vm.runInContext(SOURCE, ctx);
  return ctx;
}

// A tree just big enough for the scan: light DOM children, open shadow roots, parentNode and host.
class Node {
  constructor() { this.children = []; this.parentNode = null; }
  append(...nodes) { for (const n of nodes) { n.parentNode = this; this.children.push(n); } return this; }
  remove(n) { this.children = this.children.filter((c) => c !== n); n.parentNode = null; }
  querySelectorAll() {
    const out = [];
    const walk = (n) => n.children.forEach((c) => { out.push(c); walk(c); });
    walk(this);
    return out;
  }
}
class El extends Node {
  constructor(tag, opts = {}) {
    super();
    this.localName = tag;
    this.nodeType = 1;
    this.shadowRoot = null;
    if (opts.card) this.setConfig = () => {};
  }
  shadow(...nodes) {
    const r = new Node();
    r.nodeType = 11;
    r.host = this;
    this.shadowRoot = r;
    r.append(...nodes);
    return this;
  }
}
const el = (tag, opts) => new El(tag, opts);

function clock() {
  let t = 0;
  let id = 0;
  const timers = new Map();
  return {
    now: () => t,
    setTimeout: (fn, ms) => { timers.set(++id, { at: t + ms, fn }); return id; },
    clearTimeout: (i) => timers.delete(i),
    advance(ms) {
      const end = t + ms;
      for (;;) {
        const due = [...timers.entries()].filter(([, v]) => v.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        timers.delete(due[0]);
        t = due[1].at;
        due[1].fn();
      }
      t = end;
    },
    pending: () => timers.size,
  };
}

function observers() {
  const all = [];
  class MO {
    constructor(cb) { this.cb = cb; this.targets = []; this.on = true; all.push(this); }
    observe(target) { this.targets.push(target); }
    disconnect() { this.on = false; }
  }
  return { MO, all, mutate: () => all.filter((o) => o.on).forEach((o) => o.cb([])) };
}

// The island's stand-ins and HA's card shell: what an island page defines besides the card's own script.
const ISLAND = new Set(['ha-card', 'ha-icon', 'ha-state-icon', 'ha-svg-icon', 'ha-icon-button', 'action-handler', 'ha-ripple']);

function watch(card, { defined = ISLAND, ...rest } = {}) {
  const w = load();
  const c = clock();
  const o = observers();
  const reports = [];
  const stop = w.vitroWatchFrontend(card, {
    report: (tags, cards) => reports.push({ tags: [...tags], cards: [...cards] }),
    isDefined: (t) => defined.has(t) || t === card.localName,
    setTimeout: c.setTimeout, clearTimeout: c.clearTimeout, now: c.now, MutationObserver: o.MO, ...rest,
  });
  return { c, o, reports, stop };
}

test('a card that draws with an undefined HA element hands off once its grace is over', () => {
  const card = el('acme-thermostat-card', { card: true }).shadow(
    el('ha-card').append(el('ha-control-circular-slider'), el('ha-big-number'), el('ha-icon')),
  );
  const { c, reports } = watch(card);
  c.advance(1000);
  assert.equal(reports.length, 0, 'not before the grace: it may still be defined');
  c.advance(3000);
  assert.deepEqual(reports, [{ tags: ['ha-big-number', 'ha-control-circular-slider'], cards: ['acme-thermostat-card'] }]);
  c.advance(60000);
  assert.equal(reports.length, 1, 'reported once');
  assert.equal(c.pending(), 0, 'and stopped');
});

test('a card that uses only what the island defines stays an island, and the watch stops', () => {
  const card = el('acme-plain-card', { card: true }).shadow(el('ha-card').append(el('ha-state-icon'), el('ha-svg-icon'), el('div')));
  const { c, o, reports } = watch(card);
  for (let i = 0; i < 40; i++) { o.mutate(); c.advance(1000); }
  assert.equal(reports.length, 0);
  assert.equal(c.pending(), 0, 'capped: no scans after MAX_SCANS');
  assert.ok(o.all.every((x) => !x.on), 'the observer is disconnected');
});

test("a card's own ha-prefixed element is defined, so it never counts", () => {
  const card = el('acme-gauge-card', { card: true }).shadow(el('ha-card').append(el('ha-acme-gauge')));
  const { c, reports } = watch(card, { defined: new Set([...ISLAND, 'ha-acme-gauge']) });
  c.advance(30000);
  assert.equal(reports.length, 0);
});

test('an element the card defines within the grace never counts', () => {
  const defined = new Set(ISLAND);
  const card = el('acme-lazy-card', { card: true }).shadow(el('ha-acme-lazy'));
  const { c, reports } = watch(card, { defined });
  c.advance(1000);
  defined.add('ha-acme-lazy');
  c.advance(30000);
  assert.equal(reports.length, 0);
});

test('an element that appears later (an option, a state) is caught through the observer', () => {
  const inner = el('ha-card');
  const card = el('acme-option-card', { card: true }).shadow(inner);
  const { c, o, reports } = watch(card);
  c.advance(5000);
  assert.equal(reports.length, 0);
  assert.ok(o.all[0].targets.includes(card.shadowRoot), 'the shadow root is observed');
  inner.append(el('ha-big-number'));
  o.mutate();
  c.advance(5000);
  assert.deepEqual(reports, [{ tags: ['ha-big-number'], cards: ['acme-option-card'] }]);
});

test('an element that leaves the tree before its grace is over never counts', () => {
  const spinner = el('ha-circular-progress');
  const root = el('ha-card').append(spinner);
  const card = el('acme-loading-card', { card: true }).shadow(root);
  const { c, o, reports } = watch(card);
  c.advance(1000);
  root.remove(spinner);
  o.mutate();
  c.advance(30000);
  assert.equal(reports.length, 0);
});

test('harmless tags and names HA does not use are not looked at', () => {
  const card = el('acme-misc-card', { card: true }).shadow(
    el('ha-card').append(el('hui-warning'), el('ha-ripple'), el('mwc-button'), el('paper-tooltip'), el('acme-thing')),
  );
  const defined = new Set([...ISLAND].filter((t) => t !== 'ha-ripple'));
  const { c, reports } = watch(card, { defined });
  c.advance(30000);
  assert.equal(reports.length, 0);
});

test('every frontend prefix counts: hui-, state- and more-info- too', () => {
  for (const tag of ['hui-card-features', 'state-badge', 'more-info-climate']) {
    const card = el('acme-card', { card: true }).shadow(el(tag));
    const { c, reports } = watch(card);
    c.advance(30000);
    assert.deepEqual(reports.map((r) => r.tags), [[tag]], tag);
  }
});

test('a wrapper card is not blamed: the card inside it that draws the element is', () => {
  const inner = el('acme-thermostat-card', { card: true }).shadow(el('ha-card').append(el('ha-control-circular-slider')));
  const stack = el('acme-stack-card', { card: true }).shadow(el('div').append(inner, el('acme-light-card', { card: true }).shadow(el('ha-card'))));
  const { c, reports } = watch(stack, { defined: new Set([...ISLAND, 'acme-thermostat-card', 'acme-light-card']) });
  c.advance(30000);
  assert.deepEqual(reports, [{ tags: ['ha-control-circular-slider'], cards: ['acme-thermostat-card'] }]);
});

test('elements in nested shadow roots and the light DOM are found', () => {
  const card = el('acme-deep-card', { card: true }).shadow(el('acme-part').shadow(el('acme-sub').shadow(el('ha-tile-icon'))));
  card.append(el('ha-big-number'));
  const { c, reports } = watch(card, { defined: new Set([...ISLAND, 'acme-part', 'acme-sub']) });
  c.advance(30000);
  assert.deepEqual(reports.map((r) => r.tags), [['ha-big-number', 'ha-tile-icon']]);
});

test('a scan stops at maxNodes', () => {
  const w = load();
  const big = el('acme-big-card', { card: true }).shadow(...Array.from({ length: 50 }, () => el('div')), el('ha-big-number'));
  assert.deepEqual([...w.vitroFrontendScan(big, () => false, 20).tags], []);
  assert.deepEqual([...w.vitroFrontendScan(big, () => false, 100).tags], ['ha-big-number']);
});

test('the rule matches HA element names only', () => {
  const w = load();
  const re = vm.runInContext('VITRO_FRONTEND_TAG', w);
  for (const ok of ['ha-card', 'hui-card-features', 'state-badge', 'more-info-climate', 'ha-control-circular-slider']) assert.ok(re.test(ok), ok);
  for (const no of ['mushroom-card', 'mwc-button', 'hass-loading-screen', 'haunted-card', 'ha', 'ha-', 'more-info']) assert.ok(!re.test(no), no);
});
