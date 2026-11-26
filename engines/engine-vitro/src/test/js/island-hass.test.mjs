// The island page's `hass` (WebIslandCardView.buildIslandHtml's bridge module), run under node against a small fake
// page: a fake bridge socket, a fake card and fake timers where they matter. IslandHassContractTest writes the module
// out of a real island page and runs this file with:
//   ISLAND_MODULE  the module script's path
//   HASS_TYPES     engines/engine-vitro/src/test/ha-frontend/hass-types.ts (HA's HomeAssistant and Connection)
// Touches nothing: no network, no browser.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const SOURCE = readFileSync(process.env.ISLAND_MODULE, 'utf8');
// The same page made before the panel had Home Assistant's config (`hass.config` empty).
const SOURCE_NO_CONFIG = process.env.ISLAND_MODULE_NO_CONFIG ? readFileSync(process.env.ISLAND_MODULE_NO_CONFIG, 'utf8') : null;
// The states the panel's bridge sends when it opens (its snapshot), unless a test says otherwise.
const LAMP = { 'light.lounge': { entity_id: 'light.lounge', state: 'on', attributes: { friendly_name: 'Lounge' } } };
// The page's own plain script that the module reads durations with (inlined before it, as the page has it).
const DURATION = readFileSync(new URL('../../main/resources/dev/glassworks/engine/vitro/island/format-duration.js', import.meta.url), 'utf8');
const TYPES = readFileSync(process.env.HASS_TYPES, 'utf8');
const TAG = 'acme-island-card';
const STRINGS = {
  'ui.card.weather.attributes.humidity': 'Humidity',
  'ui.components.calendar.event.count': '{count, plural, =0 {No events} one {# event} other {# events}}',
  'ui.common.greeting': "Hello {name}, it''s {day, select, sat {the weekend} other {a weekday}}",
  'component.light.entity_component._.state.on': 'On',
  'component.binary_sensor.entity_component.door.state.on': 'Open',
  'state.default.unavailable': 'Unavailable',
  'ui.test.quoted': "Use '{name}' for {name}",
  'ui.test.pound': "{n, plural, other {# of '#' items, #}}",
  'ui.test.doubled': "{n, plural, other {''{name} #}}",
  'ui.test.nested': '{outer, plural, other {# groups, {inner, plural, other {# items}}}}',
};

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));


async function settle() { for (let i = 0; i < 5; i++) await tick(); }

/**
 * One island page: the module run in its own context, with [card] as the custom element (a class-like factory:
 * (el) => void sets up el). Returns the bridge and the page's globals.
 */
async function openPage({ card, strings = STRINGS, height = 40, mountHeight, snapshot = LAMP, source = SOURCE, epoch = () => 1, connected = () => true, defineLater = false } = {}) {
  const listeners = {};
  const sockets = [];
  let defined = !defineLater;
  let define = () => {};
  const definedPromise = defineLater ? new Promise((r) => { define = () => { defined = true; r(); }; }) : Promise.resolve();
  let cardEl = null;
  const mount = {
    children: [],
    appendChild(c) { this.children.push(c); c.isConnected = true; if (c.connectedCallback) c.connectedCallback(); },
    // A full-viewport island holds #mount at 100vh whether the card drew or not.
    getBoundingClientRect() { return { height: mountHeight !== undefined ? mountHeight : (cardEl && cardEl.drawn ? height : 0) }; },
  };
  class FakeSocket {
    static OPEN = 1;
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.sent = [];
      sockets.push(this);
      setTimeout(() => {
        this.readyState = 1;
        if (this.onopen) this.onopen();
        // What the panel sends a page whose bridge opens: the states it has (null: none yet).
        // The panel's seed: where HA's connection is (and which one: its epoch), then the states.
        this.push({ type: 'conn', connected: connected(), epoch: epoch() });
        if (snapshot) this.push({ type: 'snapshot', states: snapshot });
      }, 0);
    }
    send(line) { for (const l of String(line).split('\n').filter(Boolean)) this.sent.push(JSON.parse(l)); }
    push(obj) { this.onmessage({ data: JSON.stringify(obj) + '\n' }); }
    drop() { this.readyState = 3; this.onclose && this.onclose(); }
  }
  const sandbox = {
    console, Promise, Intl, URL, JSON, Math, Object, Array, Map, Set, String, Number, Error, TypeError,
    setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
    location: { protocol: 'http:', host: '127.0.0.1:5', origin: 'http://127.0.0.1:5', pathname: '/', search: '' },
    WebSocket: FakeSocket,
    fetch: async (url) => {
      if (String(url).startsWith('/__vitro/strings/')) return { ok: true, status: 200, json: async () => strings };
      // The bridge's slot is still there: the page connects again.
      if (String(url).startsWith('/__vitro/ws/')) return { ok: true, status: 200, json: async () => ({}), text: async () => '' };
      return { ok: false, status: 404, json: async () => ({}), text: async () => '' };
    },
    // [defineLater]: the card's script defines it only when the test says (page.define()), as a slow one does.
    customElements: {
      get: (t) => (t === TAG && defined ? function () {} : undefined),
      whenDefined: (t) => (t === TAG ? definedPromise : new Promise(() => {})),
    },
    document: {
      getElementById: (id) => (id === 'mount' ? mount : mount.children.find((c) => c.id === id) || null),
      createElement: (t) => {
        const el = {
          localName: t, isConnected: false, drawn: false, children: [], attributes: {},
          setAttribute(k, v) { this.attributes[k] = v; },
          remove() { mount.children = mount.children.filter((c) => c !== this); this.isConnected = false; },
        };
        el.getBoundingClientRect = () => ({ height: el.drawn ? height : 0 });
        if (t === TAG) { cardEl = el; card(el); }
        return el;
      },
    },
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    vitroWatchFrontend() {},
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  const ws = () => sockets[sockets.length - 1];
  const page = {
    sandbox, sockets, ws, listeners, card: () => cardEl, define: () => define(),
    hass: () => sandbox.__vitroHass,
    sent: (type) => sockets.flatMap((s) => s.sent).filter((m) => !type || m.type === type),
    // An error the page's handlers hear, as a browser reports an uncaught one.
    throwInPage(err) { for (const fn of listeners.error || []) fn({ error: err }); },
    rejectInPage(err) { for (const fn of listeners.unhandledrejection || []) fn({ reason: err }); },
  };
  vm.runInContext(DURATION, sandbox, { filename: 'format-duration.js' });
  vm.runInContext(source, sandbox, { filename: 'island-module.js' });
  await settle();
  return page;
}

/** A card that keeps every hass it gets and draws on the first. */
const plainCard = (el) => {
  el.got = [];
  el.setConfig = () => {};
  // It draws what it shows as text (the blank check reads content, not the host's box).
  Object.defineProperty(el, 'hass', { set(h) { el.got.push(h); el.drawn = true; el.textContent = 'Lounge'; }, get() { return el.got[el.got.length - 1]; } });
};

// ---- The contract: HA's HomeAssistant and Connection, member by member ----

/** The members of `export interface <name>` (or `class`) in [src]: name, kind, optional. */
function members(src, name) {
  const start = src.search(new RegExp(`export (?:declare )?(?:interface|class) ${name}\\b`));
  assert.ok(start >= 0, `${name} is in hass-types.ts`);
  const open = src.indexOf('{', start);
  let depth = 0;
  let end = open;
  for (; end < src.length; end++) {
    if (src[end] === '{') depth++;
    else if (src[end] === '}' && --depth === 0) break;
  }
  const out = [];
  for (const line of src.slice(open + 1, end).split('\n')) {
    // A member starts at two spaces; deeper lines continue a signature, `//` lines are comments, `)` closes one.
    if (!/^ {2}\S/.test(line) || /^ {2}(\/\/|\)|\})/.test(line)) continue;
    let m;
    if ((m = line.match(/^ {2}(?:readonly )?(?:get )?(\w+)(\?)?\s*(?:<[^>]*>)?\(/))) out.push({ name: m[1], optional: !!m[2], kind: line.includes('get ') ? 'getter' : 'function' });
    else if ((m = line.match(/^ {2}(?:readonly )?(\w+)(\?)?:\s*(.+?);?\s*$/))) out.push({ name: m[1], optional: !!m[2], kind: kindOf(m[3]) });
    // A declaration this can't read fails the test, so a new kind of member is never skipped.
    else assert.fail(`${name}: can't read the member declaration ${JSON.stringify(line)}`);
  }
  return out;
}

/** What a TypeScript member type is at run time: a function, a primitive or an object (nullable when `| null`). */
function kindOf(type) {
  const t = type.replace(/\s*\|\s*null\b/, '');
  if (/=>/.test(t) || /^(LocalizeFunc|CallWS)\b/.test(t)) return 'function';
  if (/^(string|boolean|number)\b/.test(t) || /^"/.test(t)) return 'primitive' + (t !== type ? '?' : '');
  return 'object' + (t !== type ? '?' : '');
}

/** The interfaces HomeAssistant extends, then its own members. */
function homeAssistantMembers() {
  const decl = TYPES.match(/export interface HomeAssistant\s+extends([\s\S]*?)\{/);
  assert.ok(decl, 'HomeAssistant extends its parts');
  const parts = decl[1].split(',').map((s) => s.trim()).filter(Boolean);
  return [...parts.flatMap((p) => members(TYPES, p)), ...members(TYPES, 'HomeAssistant')];
}

/**
 * HA members an island's hass doesn't have yet, each with why. A member here that the island gains fails the test
 * too, so the list never goes stale.
 */
const KNOWN_GAPS = {};

function kindMatches(value, kind) {
  switch (kind.replace('?', '')) {
    case 'function': return typeof value === 'function';
    case 'primitive': return (kind.endsWith('?') && value === null) || ['string', 'boolean', 'number'].includes(typeof value);
    case 'object': return (kind.endsWith('?') && value === null) || (typeof value === 'object' && value !== null);
    default: return false;
  }
}

test('the island hass has every member of HA\'s HomeAssistant, of the same kind', async () => {
  const page = await openPage({ card: plainCard });
  const hass = page.hass();
  assert.ok(hass, 'the card got a hass');
  const all = homeAssistantMembers();
  assert.ok(all.length > 40, `HomeAssistant has its members (${all.length})`);
  const wrong = [];
  for (const m of all) {
    if (KNOWN_GAPS[m.name]) {
      if (m.name in hass) wrong.push(`${m.name} is in the island now: take it off KNOWN_GAPS`);
      continue;
    }
    if (!(m.name in hass)) { wrong.push(`missing: ${m.name}`); continue; }
    if (!kindMatches(hass[m.name], m.kind)) wrong.push(`${m.name}: ${typeof hass[m.name]}, HA's is ${m.kind}`);
  }
  for (const name of Object.keys(KNOWN_GAPS)) {
    if (!all.some((m) => m.name === name)) wrong.push(`${name} is no longer in HA's HomeAssistant: take it off KNOWN_GAPS`);
  }
  assert.deepEqual(wrong, []);
});

test('hass.connection has every public member of HA\'s Connection', async () => {
  const page = await openPage({ card: plainCard });
  const conn = page.hass().connection;
  const wrong = [];
  for (const m of members(TYPES, 'Connection')) {
    const v = conn[m.name];
    if (m.kind === 'function' && typeof v !== 'function') wrong.push(`${m.name}: ${typeof v}, HA's is a method`);
    if (m.kind === 'getter' && typeof v !== 'boolean') wrong.push(`${m.name}: ${typeof v}`);
    if (m.kind.startsWith('primitive') && typeof v !== 'string') wrong.push(`${m.name}: ${typeof v}`);
  }
  assert.deepEqual(wrong, []);
  assert.equal(conn.haVersion, '2026.11.0', 'haVersion is HA\'s version');
  assert.equal(conn.connected, true);
  assert.equal(page.hass().connection, conn, 'one connection object for every hass');
});

// ---- Strings ----

test('localize is HA\'s strings, with placeholders, plurals and selects, and "" for a key HA doesn\'t have', async () => {
  const page = await openPage({ card: plainCard });
  const { localize } = page.hass();
  assert.equal(localize('ui.card.weather.attributes.humidity'), 'Humidity');
  assert.equal(localize('ui.components.calendar.event.count', { count: 0 }), 'No events');
  assert.equal(localize('ui.components.calendar.event.count', 'count', 1), '1 event');
  assert.equal(localize('ui.components.calendar.event.count', { count: 1200 }), '1,200 events');
  assert.equal(localize('ui.common.greeting', { name: 'Ana', day: 'sat' }), "Hello Ana, it's the weekend");
  assert.equal(localize('component.weather.entity_component._.state.partlycloudy'), '', 'a miss is empty, so the card falls back');
  assert.equal(localize('component.weather.entity_component._.state.partlycloudy') || 'Partly cloudy', 'Partly cloudy');
  assert.equal(localize(''), '');
  // ICU's quotes: a quoted brace or # is text.
  assert.equal(localize('ui.test.quoted', { name: 'Ada' }), 'Use {name} for Ada');
  assert.equal(localize('ui.test.pound', { n: 3 }), '3 of # items, 3');
  assert.equal(localize('ui.test.doubled', { n: 2, name: 'Ada' }), "'Ada 2", "'' is one apostrophe, even before a brace");
  assert.equal(localize('ui.test.nested', { outer: 2, inner: 5 }), '2 groups, 5 items', 'each plural has its own #');
});

test('formatEntityState takes HA\'s translated state labels', async () => {
  const page = await openPage({ card: plainCard });
  const { formatEntityState } = page.hass();
  assert.equal(formatEntityState({ entity_id: 'binary_sensor.front_door', state: 'on', attributes: { device_class: 'door' } }), 'Open');
  assert.equal(formatEntityState({ entity_id: 'light.lounge', state: 'on', attributes: {} }), 'On');
  assert.equal(formatEntityState({ entity_id: 'light.lounge', state: 'unavailable', attributes: {} }), 'Unavailable');
});

test('loadBackendTranslation asks HA once, merges what it gives and resolves with a new localize', async () => {
  const page = await openPage({ card: plainCard });
  const before = page.hass().localize;
  const oldResources = page.hass().resources.en;
  const pending = page.hass().loadBackendTranslation('services', ['scheduler']);
  await settle();
  const asks = page.sent('callWs').filter((m) => m.msg.type === 'frontend/get_translations');
  assert.equal(asks.length, 1);
  assert.deepEqual(asks[0].msg, { type: 'frontend/get_translations', language: 'en', category: 'services', integration: ['scheduler'] });
  page.ws().push({ type: 'wsReply', id: asks[0].id, ok: true, result: { resources: { 'component.scheduler.services.add.name': 'Add schedule' } } });
  const localize = await pending;
  assert.equal(localize('component.scheduler.services.add.name'), 'Add schedule');
  assert.notEqual(localize, before, 'a new localize, as HA\'s');
  assert.equal(page.card().hass.localize, localize, 'the card got a hass with it');
  assert.equal(page.card().hass.resources.en['component.scheduler.services.add.name'], 'Add schedule');
  assert.equal(oldResources['component.scheduler.services.add.name'], undefined, 'an earlier hass keeps the strings it had');
  // The same request again (scheduler-card asks on every update): no second ask, no new hass.
  const count = page.card().got.length;
  await page.hass().loadBackendTranslation('services', 'scheduler');
  await settle();
  assert.equal(page.sent('callWs').filter((m) => m.msg.type === 'frontend/get_translations').length, 1);
  assert.equal(page.card().got.length, count);
});

test('loadBackendTranslation resolves with localize when HA can\'t answer', async () => {
  const page = await openPage({ card: plainCard });
  const pending = page.hass().loadBackendTranslation('title');
  await settle();
  const ask = page.sent('callWs').find((m) => m.msg.type === 'frontend/get_translations');
  page.ws().push({ type: 'wsReply', id: ask.id, ok: false, error: { code: 'unknown_error', message: 'no' } });
  assert.equal(typeof (await pending), 'function');
});

// ---- Connection ----

test('subscribeMessage starts a subscription on the panel, gets its events and ends it', async () => {
  const page = await openPage({ card: plainCard });
  const events = [];
  const pending = page.hass().connection.subscribeMessage((ev) => events.push(ev), { type: 'scheduler/subscribe' });
  await settle();
  const sub = page.sent('subscribe').at(-1);
  assert.deepEqual(sub.msg, { type: 'scheduler/subscribe' });
  assert.equal(sub.resubscribe, true);
  page.ws().push({ type: 'subAck', sid: sub.sid });
  const unsub = await pending;
  page.ws().push({ type: 'subEvent', sid: sub.sid, event: { event: 'item_updated' } });
  assert.deepEqual(events, [{ event: 'item_updated' }]);
  await unsub();
  assert.deepEqual(page.sent('unsubscribe'), [{ type: 'unsubscribe', sid: sub.sid }]);
  page.ws().push({ type: 'subEvent', sid: sub.sid, event: { late: true } });
  assert.equal(events.length, 1, 'nothing after it ended');
});

test('subscribeEvents is subscribe_events, and a refused subscription rejects', async () => {
  const page = await openPage({ card: plainCard });
  const pending = page.hass().connection.subscribeEvents(() => {}, 'calendar_event');
  await settle();
  const sub = page.sent('subscribe').at(-1);
  assert.deepEqual(sub.msg, { type: 'subscribe_events', event_type: 'calendar_event' });
  page.ws().push({ type: 'subError', sid: sub.sid, error: { code: 'unauthorized', message: 'Unauthorized' } });
  await assert.rejects(pending, (e) => e.code === 'unauthorized');
});

test('a subscription that doesn\'t resubscribe stays ended when HA\'s connection changed while the bridge was down', async () => {
  let epoch = 1;
  const page = await openPage({ card: plainCard, epoch: () => epoch });
  const once = page.hass().connection.subscribeMessage(() => {}, { type: 'render_template', template: '{{ 1 }}' }, { resubscribe: false });
  const kept = page.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' });
  await settle();
  const [a, b] = page.sent('subscribe').slice(-2);
  page.ws().push({ type: 'subAck', sid: a.sid });
  page.ws().push({ type: 'subAck', sid: b.sid });
  await once;
  await kept;
  page.ws().drop();
  epoch = 2; // HA went down and came back meanwhile
  await tick(80);
  await settle();
  assert.equal(page.sockets.length, 2);
  assert.deepEqual(page.sockets[1].sent.filter((m) => m.type === 'subscribe').map((m) => m.sid), [b.sid], 'only the resubscribing one');
});

test('a subscription that doesn\'t resubscribe ends when the bridge comes back while HA is down', async () => {
  // HA's epoch only moves on a connect: a bridge that reopens while HA is down sees the last one's. The one-shot
  // subscription still ended with HA's connection, as in HA's client; the resubscribing one is asked for again.
  let up = true;
  const page = await openPage({ card: plainCard, connected: () => up });
  const once = page.hass().connection.subscribeMessage(() => {}, { type: 'render_template', template: '{{ 1 }}' }, { resubscribe: false });
  const kept = page.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' });
  await settle();
  const [a, b] = page.sent('subscribe').slice(-2);
  page.ws().push({ type: 'subAck', sid: a.sid });
  page.ws().push({ type: 'subAck', sid: b.sid });
  await once;
  await kept;
  page.ws().drop();
  up = false; // same epoch, HA down
  await tick(80);
  await settle();
  assert.equal(page.sockets.length, 2);
  assert.deepEqual(page.sockets[1].sent.filter((m) => m.type === 'subscribe').map((m) => m.sid), [b.sid], 'only the resubscribing one');
});

test('a new bridge (the page handed to the next build) asks for every subscription again', async () => {
  const page = await openPage({ card: plainCard });
  const pending = page.hass().connection.subscribeMessage(() => {}, { type: 'render_template', template: '{{ 1 }}' }, { resubscribe: false });
  await settle();
  const first = page.sent('subscribe').at(-1);
  assert.equal(first.resubscribe, false);
  page.ws().push({ type: 'subAck', sid: first.sid });
  await pending;
  page.ws().drop();
  await tick(80);
  await settle();
  assert.equal(page.sockets.length, 2, 'the bridge connected again');
  assert.deepEqual(page.sockets[1].sent.filter((m) => m.type === 'subscribe').map((m) => m.sid), [first.sid]);
});

test('connection events and hass.connected follow the panel\'s connection', async () => {
  const page = await openPage({ card: plainCard });
  const conn = page.hass().connection;
  const heard = [];
  const onReady = (c) => heard.push(['ready', c === conn]);
  conn.addEventListener('ready', onReady);
  conn.addEventListener('disconnected', () => heard.push(['disconnected']));
  page.ws().push({ type: 'conn', connected: false });
  assert.equal(page.card().hass.connected, false);
  assert.equal(conn.connected, false);
  page.ws().push({ type: 'conn', connected: true });
  assert.equal(page.card().hass.connected, true);
  conn.removeEventListener('ready', onReady);
  page.ws().push({ type: 'conn', connected: false });
  page.ws().push({ type: 'conn', connected: true });
  assert.deepEqual(heard, [['disconnected'], ['ready', true], ['disconnected']]);
});

test('a reconnect check that says no fails a subscription that never started, and an older check counts for nothing', async () => {
  const page = await openPage({ card: plainCard });
  let answers = [];
  // Never acked: the connection drops before HA answered.
  const pending = page.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' }, { preCheck: () => true });
  await settle();
  page.ws().push({ type: 'conn', connected: false });
  page.ws().push({ type: 'conn', connected: true });
  await settle();
  // Not refused: still waiting for HA.
  let settled = false;
  pending.then(() => { settled = true; }, () => { settled = true; });
  await settle();
  assert.equal(settled, false);

  // A slow check from one reconnect that answers no after a newer reconnect said yes: nothing changes.
  const page2 = await openPage({ card: plainCard });
  let first = true;
  const ok2 = page2.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' }, {
    preCheck: () => (first ? (first = false, true) : new Promise((r) => answers.push(r))),
  });
  await settle();
  const sid2 = page2.sent('subscribe').at(-1).sid;
  page2.ws().push({ type: 'subAck', sid: sid2 });
  await ok2;
  answers = [];
  page2.ws().push({ type: 'conn', connected: false });
  page2.ws().push({ type: 'conn', connected: true });
  page2.ws().push({ type: 'conn', connected: false });
  page2.ws().push({ type: 'conn', connected: true });
  await settle();
  assert.equal(answers.length, 2);
  answers[1](true);
  answers[0](false);
  await settle();
  assert.deepEqual(page2.sent('unsubscribe'), [], 'the older no counts for nothing');

  // Never acked, and this reconnect's check says no: the card's promise fails.
  const page3 = await openPage({ card: plainCard });
  let allow = true;
  const p3 = page3.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' }, { preCheck: () => allow });
  await settle();
  const sid3 = page3.sent('subscribe').at(-1).sid;
  page3.ws().push({ type: 'conn', connected: false });
  allow = false;
  page3.ws().push({ type: 'conn', connected: true });
  await assert.rejects(p3, /Pre-check failed/);
  assert.deepEqual(page3.sent('unsubscribe'), [{ type: 'unsubscribe', sid: sid3 }]);
});

test('a url action and a tapped link leave through Vitro, never in the island\'s own window', async () => {
  const page = await openPage({ card: plainCard });
  const fire = (type, ev) => { for (const fn of page.listeners[type] || []) fn(ev); };
  fire('hass-action', { detail: { config: { tap_action: { action: 'url', url_path: 'https://example.com/events/1' } }, action: 'tap' } });
  const link = (href, prevented = false) => {
    const ev = {
      button: 0, defaultPrevented: prevented, prevented: false,
      composedPath: () => [{ localName: 'span' }, { localName: 'a', href }, { localName: 'div' }],
      preventDefault() { this.prevented = true; },
    };
    fire('click', ev);
    return ev.prevented;
  };
  assert.ok(link('https://example.com/news'), 'an outside link stays out of the island');
  // A link on the page's own origin is a path on Home Assistant.
  assert.ok(link('http://127.0.0.1:5/lovelace/1?x=1'));
  assert.equal(link('https://example.com/handled', true), false, 'a link the card handled itself');
  assert.equal(link('mailto:someone@example.com'), false, 'not a web page');
  await settle();
  // The url action runs as a native card's (the `action` message, island-budget's path); the links navigate.
  assert.deepEqual(page.sent('action').map((m) => m.action.url_path), ['https://example.com/events/1']);
  assert.deepEqual(page.sent('navigate').map((m) => m.path), ['https://example.com/news', '/lovelace/1?x=1']);
});

test('a navigate with replace leaves no step in the history, as HA\'s navigate(path, {replace: true})', async () => {
  const page = await openPage({ card: plainCard });
  const fire = (type, ev) => { for (const fn of page.listeners[type] || []) fn(ev); };
  fire('hass-action', { detail: { config: { tap_action: { action: 'navigate', navigation_path: '/lovelace/1', navigation_replace: true } }, action: 'tap' } });
  fire('hass-action', { detail: { config: { tap_action: { action: 'navigate', navigation_path: '/lovelace/2' } }, action: 'tap' } });
  // HA's navigate() fires location-changed with { replace }.
  fire('location-changed', { detail: { path: '/lovelace/3', replace: true } });
  fire('location-changed', { detail: { path: '/lovelace/4' } });
  await settle();
  assert.deepEqual(page.sent('navigate').map((m) => [m.path, m.replace]),
    [['/lovelace/1', true], ['/lovelace/2', false], ['/lovelace/3', true], ['/lovelace/4', false]]);
});

test('formatEntityState writes a duration as HA does, at the entity\'s display precision', async () => {
  const page = await openPage({ card: plainCard });
  const { formatEntityState } = page.hass();
  const duration = (id, state, unit) => ({ entity_id: id, state, attributes: { device_class: 'duration', unit_of_measurement: unit } });
  assert.equal(formatEntityState(duration('sensor.dishwasher_left', '1.5', 'h')), '1h 30m');
  assert.equal(formatEntityState(duration('sensor.dishwasher_left', '90', 'min')), '90m');
  assert.equal(formatEntityState(duration('sensor.uptime', '2.25', 'd')), '2d 6h');
  // Seconds aren't one of the units HA formats: the number with its unit.
  assert.equal(formatEntityState(duration('sensor.timer', '42', 's')), '42 s');
  // Nor is a state that isn't a number.
  assert.equal(formatEntityState(duration('sensor.timer', 'unavailable', 'h')), 'Unavailable');
  assert.equal(formatEntityState({ entity_id: 'sensor.boiler', state: '1.5', attributes: { unit_of_measurement: 'h' } }), '1.5 h');
  // The registry's display precision rounds first, as HA's formatDuration does.
  page.ws().push({ type: 'registries', registries: { entities: { 'sensor.dishwasher_left': { entity_id: 'sensor.dishwasher_left', display_precision: 1 } } } });
  await settle();
  assert.equal(page.hass().formatEntityState(duration('sensor.dishwasher_left', '1.999', 'h')), '2h');
});

test('formatEntityState asks for the registries once, for the integration\'s label', async () => {
  const page = await openPage({ card: plainCard });
  const before = page.sent('registries').length;
  page.hass().formatEntityState({ entity_id: 'light.lounge', state: 'on', attributes: {} });
  page.hass().formatEntityState({ entity_id: 'light.lounge', state: 'on', attributes: {} });
  assert.equal(page.sent('registries').length - before, 1);
});

test('a subscription whose preCheck says no after a reconnect ends, as HA\'s client does', async () => {
  const page = await openPage({ card: plainCard });
  let allowed = true;
  const pending = page.hass().connection.subscribeMessage(() => {}, { type: 'scheduler' }, { preCheck: () => allowed });
  await settle();
  const sub = page.sent('subscribe').at(-1);
  page.ws().push({ type: 'subAck', sid: sub.sid });
  await pending;
  page.ws().push({ type: 'conn', connected: false });
  allowed = false;
  page.ws().push({ type: 'conn', connected: true });
  await settle();
  assert.deepEqual(page.sent('unsubscribe'), [{ type: 'unsubscribe', sid: sub.sid }]);
});

test('hass.services is read when a card reads it, and again when HA registers a service', async () => {
  const page = await openPage({ card: plainCard });
  assert.equal(Object.keys(page.hass().services).length, 0, 'nothing until HA answers');
  await settle();
  const subs = page.sent('subscribe');
  assert.deepEqual(subs.map((m) => m.msg.event_type).sort(), ['service_registered', 'service_removed']);
  assert.equal(page.sent('callWs').filter((m) => m.msg.type === 'get_services').length, 0, 'the listeners go first');
  // One listener accepted, one refused: the list is read anyway.
  page.ws().push({ type: 'subAck', sid: subs[0].sid });
  page.ws().push({ type: 'subError', sid: subs[1].sid, error: { code: 'unauthorized' } });
  await settle();
  const ask = page.sent('callWs').find((m) => m.msg.type === 'get_services');
  assert.ok(ask, 'get_services');
  page.ws().push({ type: 'wsReply', id: ask.id, ok: true, result: { light: { turn_on: {} } } });
  await settle();
  assert.deepEqual(Object.keys(page.card().hass.services), ['light']);
  assert.equal(page.sent('callWs').filter((m) => m.msg.type === 'get_services').length, 1, 'asked once');
});

test('refreshAccessToken resolves with the token the panel holds now', async () => {
  const page = await openPage({ card: plainCard });
  const auth = page.hass().auth;
  assert.equal(auth.expired, false);
  const pending = auth.refreshAccessToken();
  await settle();
  assert.equal(page.sent('refreshToken').length, 1);
  // A seed's auth (no request id) settles nothing; the reply to this request does.
  page.ws().push({ type: 'auth', token: 'older', user: null });
  await settle();
  let settled = false;
  pending.then(() => { settled = true; });
  await settle();
  assert.equal(settled, false, 'not settled by another auth');
  const rid = page.sent('refreshToken').at(-1).rid;
  assert.equal(typeof rid, 'number');
  page.ws().push({ type: 'auth', token: 'renewed', user: null, rid });
  await pending;
  assert.equal(page.card().hass.auth.accessToken, 'renewed');
  assert.equal(auth.accessToken, 'renewed', 'the Auth the card held reads it too');
  assert.equal(auth.data.access_token, 'renewed');
});

// ---- A stand-in for scheduler-card, and the safety net ----

test('a card that loads backend strings, subscribes and reads services on every update draws', async () => {
  // What scheduler-card does (scheduler-card.ts): loadBackendTranslation in willUpdate, subscribeMessage in
  // connectedCallback, hass.services for its actions, localize(...) || fallback for its words.
  const card = (el) => {
    el.setConfig = () => {};
    el.updates = 0;
    Object.defineProperty(el, 'hass', {
      set(h) {
        el._hass = h;
        el.updates++;
        h.loadBackendTranslation('services');
        if (!el.subscribed) {
          el.subscribed = true;
          h.connection.subscribeMessage((ev) => { el.lastEvent = ev; }, { type: 'scheduler' });
        }
        el.text = (h.localize('ui.panel.config.automation.editor.actions.name') || 'Actions') + ': ' + Object.keys(h.services).length;
        el.drawn = true;
      },
      get() { return el._hass; },
    });
  };
  const page = await openPage({ card });
  const ask = page.sent('callWs').find((m) => m.msg.type === 'frontend/get_translations');
  page.ws().push({ type: 'wsReply', id: ask.id, ok: true, result: { resources: {} } });
  for (const m of page.sent('subscribe')) if (m.msg.type === 'subscribe_events') page.ws().push({ type: 'subAck', sid: m.sid });
  await settle();
  const services = page.sent('callWs').find((m) => m.msg.type === 'get_services');
  page.ws().push({ type: 'wsReply', id: services.id, ok: true, result: { switch: {}, light: {} } });
  await tick(1700);
  assert.equal(page.card().text, 'Actions: 2');
  assert.ok(page.card().updates < 6, `no update loop (${page.card().updates})`);
  assert.deepEqual(page.sent('cardBroken'), []);
});

test('a card that throws on a member the island lacks and draws nothing goes to HA\'s frontend', async () => {
  // The update throws, as a Lit card's does (it reaches the page as an unhandled rejection), before it draws.
  const card = (el) => {
    el.setConfig = () => {};
    Object.defineProperty(el, 'hass', { set(h) { try { h.someNewMember(); el.drawn = true; } catch (e) { el.error = e; } } });
  };
  const page = await openPage({ card });
  assert.ok(page.card().error instanceof page.sandbox.TypeError || page.card().error.name === 'TypeError');
  page.rejectInPage(page.card().error);
  await tick(1700);
  assert.deepEqual(page.sent('cardBroken'), [{ type: 'cardBroken', member: 'someNewMember' }]);
});

test('a full-viewport island whose card drew nothing goes too (its mount is 100vh anyway)', async () => {
  const card = (el) => { el.setConfig = () => {}; };
  const page = await openPage({ card, mountHeight: 720 });
  page.throwInPage(new TypeError('e.hass.someNewMember is not a function'));
  await tick(1700);
  assert.deepEqual(page.sent('cardBroken').map((m) => m.member), ['someNewMember']);
});

test('an error the card survives, or one that isn\'t a missing member, leaves it in the island', async () => {
  const drew = await openPage({ card: plainCard });
  drew.throwInPage(new TypeError('e.hass.someNewMember is not a function'));
  const other = await openPage({ card: (el) => { el.setConfig = () => {}; } });
  other.rejectInPage(new Error('Not connected'));
  await tick(1700);
  assert.deepEqual(drew.sent('cardBroken'), [], 'it drew');
  assert.deepEqual(other.sent('cardBroken'), [], 'not a TypeError');
});

test('Gecko\'s and Chromium\'s messages name the member', async () => {
  const blank = (el) => { el.setConfig = () => {}; };
  for (const [message, member] of [
    ["can't access property \"subscribeMessage\", e.connection is undefined", 'connection'],
    ["Cannot read properties of undefined (reading 'subscribeMessage')", 'subscribeMessage'],
    ['this.hass.loadBackendTranslation is not a function', 'loadBackendTranslation'],
  ]) {
    const page = await openPage({ card: blank });
    page.throwInPage(new TypeError(message));
    await tick(1600);
    assert.deepEqual(page.sent('cardBroken').map((m) => m.member), [member], message);
  }
});

// ---- Entity names (HA's formatEntityName) ----

test('formatEntityName is HA\'s: the friendly name until the registries are in, then the composed name', async () => {
  const page = await openPage({ card: plainCard });
  const accent = { entity_id: 'light.bedroom_accent', state: 'off', attributes: { friendly_name: 'Bedroom Strip Accent' } };
  const h = page.hass();
  assert.equal(h.formatEntityName(accent), 'Bedroom Strip Accent', 'no registries yet: the friendly name');
  assert.equal(h.formatEntityName(accent, 'My lamp'), 'My lamp');
  assert.equal(h.formatEntityName(accent, [{ type: 'text', text: 'A' }, { type: 'text', text: 'B' }], { separator: ' - ' }), 'A - B');
  assert.ok(page.sent('registries').length >= 1, 'the registries are asked for');
  page.ws().push({ type: 'registries', registries: {
    entities: {
      'light.bedroom_accent': { entity_id: 'light.bedroom_accent', device_id: 'strip', name: 'Accent', next_name_part: 'device' },
      'sensor.hall_temp': { entity_id: 'sensor.hall_temp', device_id: 'hall', name: 'Temperature' },
      'switch.plug': { entity_id: 'switch.plug', device_id: 'plug' },
    },
    devices: { strip: { id: 'strip', name: 'Bedroom Strip', area_id: 'bed' }, hall: { id: 'hall', name: 'Hall Sensor', name_by_user: 'Hall ' }, plug: { id: 'plug', name: 'Plug' } },
    areas: { bed: { area_id: 'bed', name: 'Bedroom', floor_id: 'up' } },
    floors: { up: { floor_id: 'up', name: 'Upstairs' } },
  } });
  const h2 = page.card().hass;
  assert.equal(h2.formatEntityName(accent), 'Bedroom Strip Accent', 'device then entity (next_name_part: device)');
  assert.equal(h2.formatEntityName({ entity_id: 'sensor.hall_temp', attributes: {} }), 'Temperature', 'the name stops at the entity');
  assert.equal(h2.formatEntityName({ entity_id: 'switch.plug', attributes: {} }), 'Plug', 'no name of its own: its device\'s');
  assert.equal(h2.formatEntityName(accent, [{ type: 'floor' }, { type: 'area' }, { type: 'entity' }]), 'Upstairs Bedroom Accent');
  assert.equal(h2.formatEntityName({ entity_id: 'switch.plug', attributes: {} }, [{ type: 'entity' }]), 'Plug', 'the entity item is the device\'s name');
  assert.equal(h2.formatEntityName({ entity_id: 'sensor.other', attributes: { friendly_name: 'Other' } }), 'Other', 'no registry entry: the friendly name');
});

test('attribute values and names are HA\'s: units by domain, its formatters, the config\'s temperature unit', async () => {
  const page = await openPage({ card: plainCard });
  const h = page.hass();
  const fan = { entity_id: 'fan.ceiling', state: 'on', attributes: { percentage: 20, preset_mode: 'eco', device_id: 'x' } };
  assert.equal(h.formatEntityAttributeValue(fan, 'percentage'), '20%', "Mushroom's fan shows HA's 20%");
  assert.equal(h.formatEntityAttributeValueToParts(fan, 'percentage').map((p) => p.type).join(','), 'value,unit');
  const light = { entity_id: 'light.lamp', state: 'on', attributes: { brightness: 166, color_temp_kelvin: 2700 } };
  assert.equal(h.formatEntityAttributeValue(light, 'brightness'), '65%');
  assert.equal(h.formatEntityAttributeValue(light, 'color_temp_kelvin'), '2,700 K');
  const climate = { entity_id: 'climate.study', state: 'heat', attributes: { current_temperature: 19.5 } };
  assert.equal(h.formatEntityAttributeValue(climate, 'current_temperature'), '19.5 °C', 'the config\'s unit');
  assert.equal(h.formatEntityAttributeValue(fan, 'preset_mode'), 'eco', 'no translation: the value, as HA');
  assert.equal(h.formatEntityAttributeValue(fan, 'missing'), 'Unknown');
  assert.equal(h.formatEntityAttributeName(fan, 'device_id'), 'Device ID');
});

// ---- What the card is made with (the panel's states and config first) ----

test('the card is made once the panel\'s states are here, and an honest notice waits in its place', async () => {
  const page = await openPage({ card: plainCard, snapshot: null, mountHeight: 48 });
  assert.equal(page.card(), null, 'no card drawn with no states (it would say "Entity not found")');
  await tick(8200);
  const mount = page.sandbox.document.getElementById('mount');
  assert.ok(mount.children.some((c) => c.id === 'vitro-waiting'), 'a notice, not a blank card');
  assert.ok(page.sent('resize').length > 0, 'the notice takes its room');
  page.ws().push({ type: 'snapshot', states: LAMP });
  await settle();
  assert.ok(page.card(), 'made when the states come');
  assert.equal(page.card().got[0].states['light.lounge'].state, 'on', 'its first hass has them');
});

test('a seed sent before HA answered (complete: false) keeps the card waiting until the complete one', async () => {
  // The panel's store may still be empty when the page's bridge opens (HA's snapshot slower than the view's build):
  // the card isn't made with those states, it is made with the whole store once HA's answer is in.
  const page = await openPage({ card: plainCard, snapshot: null });
  page.ws().push({ type: 'snapshot', states: {}, complete: false });
  await settle();
  assert.equal(page.card(), null, 'not made with a store HA hasn\'t filled yet');
  page.ws().push({ type: 'snapshot', states: LAMP, complete: true });
  await settle();
  assert.ok(page.card(), 'made with HA\'s answer');
  assert.equal(page.card().got[0].states['light.lounge'].state, 'on');
  // An answer that says the card's entity doesn't exist is complete too: the card is made, as HA's draws its warning.
  const empty = await openPage({ card: plainCard, snapshot: null });
  empty.ws().push({ type: 'snapshot', states: {}, complete: true });
  await settle();
  assert.ok(empty.card(), 'a complete, empty answer makes the card');
});

test('a page made before the config waits for it, and a card that reads hass.config.version draws', async (t) => {
  if (!SOURCE_NO_CONFIG) return t.skip('no page without a config');
  // Mushroom 5.3 compares hass.config.version on every render with the entity (mg -> ge(t.config.version, ...)).
  const card = (el) => {
    el.setConfig = () => {};
    el.got = [];
    Object.defineProperty(el, 'hass', {
      set(h) { el.got.push(h); const [major] = h.config.version.split('.'); el.text = major + ' ' + h.states['light.lounge'].state; el.drawn = true; },
      get() { return el.got[el.got.length - 1]; },
    });
  };
  const page = await openPage({ card, source: SOURCE_NO_CONFIG });
  assert.equal(page.card(), null, 'waiting for the config');
  page.ws().push({ type: 'config', config: { version: '2026.11.0', unit_system: { temperature: '°C' } } });
  await settle();
  assert.equal(page.card().text, '2026 on');
  assert.equal(page.hass().connection.haVersion, '2026.11.0');
});

test('a card the config never reaches is still made (HA without one draws it too)', async (t) => {
  if (!SOURCE_NO_CONFIG) return t.skip('no page without a config');
  const page = await openPage({ card: plainCard, source: SOURCE_NO_CONFIG });
  await tick(3200);
  assert.ok(page.card());
});

test('a host with a height of its own that throws before drawing is blank, and one that drew text is not', async () => {
  const blank = (el) => {
    el.setConfig = () => {};
    // `:host { height: 120px }`: the host takes room, nothing is in it.
    el.getBoundingClientRect = () => ({ height: 120 });
    el.shadowRoot = { children: [{ localName: 'style', getBoundingClientRect: () => ({ height: 0 }) }], textContent: '' };
    el.textContent = '';
  };
  const empty = await openPage({ card: blank });
  empty.throwInPage(new TypeError('e.hass.someNewMember is not a function'));
  await tick(1700);
  assert.deepEqual(empty.sent('cardBroken').map((m) => m.member), ['someNewMember']);

  const text = (el) => {
    el.setConfig = () => {};
    el.getBoundingClientRect = () => ({ height: 24 });
    el.shadowRoot = { children: [], textContent: '21.5 °C' };
  };
  const drew = await openPage({ card: text });
  drew.throwInPage(new TypeError('e.hass.someNewMember is not a function'));
  await tick(1700);
  assert.deepEqual(drew.sent('cardBroken'), [], 'it shows text');
});

test('a card whose host is inline but whose content drew is not blank', async () => {
  const card = (el) => {
    el.setConfig = () => {};
    // An inline custom element around a block ha-card in its shadow root: the host measures 0 px.
    el.shadowRoot = { children: [{ localName: 'style', getBoundingClientRect: () => ({ height: 0 }) }, { localName: 'ha-card', getBoundingClientRect: () => ({ height: 56 }) }] };
    Object.defineProperty(el, 'hass', { set() {} });
  };
  const page = await openPage({ card });
  page.throwInPage(new TypeError('e.hass.someNewMember is not a function'));
  await tick(1700);
  assert.deepEqual(page.sent('cardBroken'), []);
});

test('a card whose every update fails on a missing member goes to HA\'s frontend, drawn or not', async () => {
  const page = await openPage({ card: plainCard });
  const update = (state) => page.ws().push({ type: 'patch', states: { 'light.lounge': { entity_id: 'light.lounge', state, attributes: {} } } });
  const split = () => page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'split')"));
  // One update failing three times is one failed update.
  split(); split(); split();
  await settle();
  assert.deepEqual(page.sent('cardBroken'), [], 'one update');
  update('off'); split();
  update('on'); split();
  await settle();
  assert.deepEqual(page.sent('cardBroken'), [{ type: 'cardBroken', member: 'split' }], 'three updates in a row');
});

test('unrelated errors, or an update that works in between, never send a working card away', async () => {
  const page = await openPage({ card: plainCard });
  const update = (state) => page.ws().push({ type: 'patch', states: { 'light.lounge': { entity_id: 'light.lounge', state, attributes: {} } } });
  // Three different members, one per update.
  page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'split')"));
  update('off'); page.rejectInPage(new TypeError('e.hass.someNewMember is not a function'));
  update('on'); page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'icon')"));
  await settle();
  assert.deepEqual(page.sent('cardBroken'), [], 'different members');
  // The same member, but an update between them ran clean: the count starts again.
  update('off'); page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'split')"));
  update('on');
  update('off'); page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'split')"));
  update('on'); page.rejectInPage(new TypeError("Cannot read properties of undefined (reading 'split')"));
  await settle();
  assert.deepEqual(page.sent('cardBroken'), [], 'a clean update reset it');
  // A TypeError that names no member never counts as one, however often.
  for (const s of ['off', 'on', 'off', 'on']) { update(s); page.rejectInPage(new TypeError('Illegal invocation')); }
  await tick(1700);
  assert.deepEqual(page.sent('cardBroken'), [], 'unnamed, and the card drew');
});

// ---- A slow script, links, an empty card (#799 D6-D8) ----

test('a card whose script is late shows HA\'s error while the page waits, and is made when the script comes', async () => {
  const page = await openPage({ card: plainCard, defineLater: true });
  await tick(4200);
  assert.deepEqual(page.sent('cardError'), [{ type: 'cardError', message: "Custom element doesn't exist: acme-island-card.", waiting: true }],
    'the panel hears it may still come');
  assert.equal(page.card(), null);
  page.define();
  await settle();
  assert.deepEqual(page.sent('cardDefined'), [{ type: 'cardDefined' }]);
  assert.ok(page.card(), 'made when the script defined it (HA\'s ll-rebuild)');
  assert.equal(page.card().got[0].states['light.lounge'].state, 'on');
});

test('a url action opens as a native card\'s does, never in the card\'s own page', async () => {
  const page = await openPage({ card: plainCard });
  let opened = null;
  page.sandbox.open = (u) => { opened = u; };
  const action = { action: 'url', url_path: 'https://www.example.com/' };
  for (const fn of page.listeners['hass-action'] || []) fn({ detail: { config: { entity: 'light.lounge', tap_action: action }, action: 'tap' } });
  assert.equal(opened, null, 'no window.open: it would load the site into the island');
  assert.deepEqual(page.sent('action'), [{ type: 'action', action, entity: 'light.lounge' }]);
});

test('a card that draws nothing collapses once it rendered, and not before', async () => {
  // A wrapper with no match (a state-switch, a conditional): its host takes no room.
  const empty = (el) => {
    el.setConfig = () => {};
    let done;
    el.updateComplete = new Promise((r) => { done = r; });
    Object.defineProperty(el, 'hass', { set() { setTimeout(() => done(true), 200); } });
  };
  const page = await openPage({ card: empty });
  await tick(100);
  assert.deepEqual(page.sent('resize'), [], 'not drawn yet: no 0 that would flash');
  await tick(300);
  assert.deepEqual(page.sent('resize').map((m) => m.height), [0], 'drawn and empty: no height, as HA gives it');
});

test('formatEntityState is HA\'s computeStateDisplay: precision, numbers, money, options, timestamps', async () => {
  const page = await openPage({ card: plainCard });
  page.ws().push({ type: 'registries', registries: {
    entities: { 'sensor.power': { entity_id: 'sensor.power', display_precision: 1 } }, devices: {}, areas: {}, floors: {},
  } });
  await settle();
  const f = page.card().hass.formatEntityState;
  assert.equal(f({ entity_id: 'sensor.power', state: '1234.567', attributes: { unit_of_measurement: 'W' } }), '1,234.6 W', 'the registry\'s display precision');
  assert.equal(f({ entity_id: 'sensor.count', state: '1234', attributes: { state_class: 'measurement' } }), '1,234', 'numeric by its state class, no unit');
  assert.equal(f({ entity_id: 'input_number.level', state: '40.0', attributes: { step: 1 } }), '40', 'a whole step: no decimals');
  assert.equal(f({ entity_id: 'sensor.bill', state: '12.5', attributes: { device_class: 'monetary', unit_of_measurement: 'EUR' } }), '€12.50');
  assert.equal(page.card().hass.formatEntityStateToParts({ entity_id: 'sensor.bill', state: '12.5', attributes: { device_class: 'monetary', unit_of_measurement: 'EUR' } }).map((p) => p.type).join(','), 'unit,value');
  assert.equal(f({ entity_id: 'input_select.scene', state: 'movie_night', attributes: {} }), 'Movie night', 'a domain the page has no labels for: its words, as HA\'s label would be');
  assert.equal(f({ entity_id: 'light.lounge', state: 'flashing', attributes: {} }), 'flashing', 'a state its domain\'s labels don\'t name: as it is, as HA');
  // IslandHassContractTest runs this with TZ=UTC; the user's time format is the language's (12-hour for English).
  assert.match(f({ entity_id: 'sensor.last_boot', state: '2026-11-20T08:05:00+00:00', attributes: { device_class: 'timestamp' } }), /^November 20, 2026\D+8:05\sAM$/u,
    'a timestamp as HA\'s formatDateTime writes it');
  assert.equal(f({ entity_id: 'sensor.last_boot', state: 'soon', attributes: { device_class: 'timestamp' } }), 'soon', 'not a date: as it is');
  assert.equal(f({ entity_id: 'input_datetime.alarm', state: '2026-11-20', attributes: {} }), 'November 20, 2026');
  assert.match(f({ entity_id: 'input_datetime.alarm', state: '07:30:00', attributes: {} }), /^7:30\sAM$/u);
});
