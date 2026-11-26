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
async function openPage({ card, strings = STRINGS, height = 40, mountHeight } = {}) {
  const listeners = {};
  const sockets = [];
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
      setTimeout(() => { this.readyState = 1; this.onopen && this.onopen(); }, 0);
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
    customElements: {
      get: (t) => (t === TAG ? function () {} : undefined),
      whenDefined: (t) => (t === TAG ? Promise.resolve() : new Promise(() => {})),
    },
    document: {
      getElementById: (id) => (id === 'mount' ? mount : null),
      createElement: (t) => {
        const el = { localName: t, isConnected: false, drawn: false };
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
    sandbox, sockets, ws, listeners, card: () => cardEl,
    hass: () => sandbox.__vitroHass,
    sent: (type) => sockets.flatMap((s) => s.sent).filter((m) => !type || m.type === type),
    // An error the page's handlers hear, as a browser reports an uncaught one.
    throwInPage(err) { for (const fn of listeners.error || []) fn({ error: err }); },
    rejectInPage(err) { for (const fn of listeners.unhandledrejection || []) fn({ reason: err }); },
  };
  vm.runInContext(DURATION, sandbox, { filename: 'format-duration.js' });
  vm.runInContext(SOURCE, sandbox, { filename: 'island-module.js' });
  await settle();
  return page;
}

/** A card that keeps every hass it gets and draws on the first. */
const plainCard = (el) => {
  el.got = [];
  el.setConfig = () => {};
  Object.defineProperty(el, 'hass', { set(h) { el.got.push(h); el.drawn = true; }, get() { return el.got[el.got.length - 1]; } });
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
const KNOWN_GAPS = {
  // HA names an entity from its registries (entity, device, area). Every card that calls it feature-tests it and falls
  // back to friendly_name; a port that names differently would draw wrong, so it waits for a real port.
  formatEntityName: 'cards fall back to friendly_name without it',
};

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
  assert.deepEqual(page.sent('navigate').map((m) => m.path), ['https://example.com/events/1', 'https://example.com/news', '/lovelace/1?x=1']);
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
  page.ws().push({ type: 'auth', token: 'renewed', user: null });
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
