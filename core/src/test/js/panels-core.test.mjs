/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// All panels' logic (#466): node --test core/src/test/js/*.test.mjs

import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { test } from 'node:test';
import { MAX_WAIT_S, SETTINGS_MS, TRIES, accepts, copyRows, authorizeUrl, hello, localHost, manageOrigin, revokeAt, usable, writeSetting, outcome, panelsFrom, pkceChallenge, plan, reachability, readPanel, retrying, sha256 } from '../../main/assets/manage/panels-core.js';

const MODEL = 'Panel Hub S1 Plus (S1 Kiosk)';
const device = (id, url, more = {}) => ({ id, name: 'Glassworks Panel', model: MODEL, config_entries: ['esp'], configuration_url: url, area_id: null, disabled_by: null, ...more });
const entries = [{ entry_id: 'esp', domain: 'esphome' }, { entry_id: 'ma', domain: 'music_assistant' }];

test('a panel is a device whose Visit link is a Manage page on 8099', () => {
  assert.equal(manageOrigin('http://192.0.2.41:8099'), 'http://192.0.2.41:8099');
  assert.equal(manageOrigin('http://192.0.2.41:8099/'), 'http://192.0.2.41:8099');
  assert.equal(manageOrigin('http://[fd00::41]:8099'), 'http://[fd00::41]:8099');
  for (const no of [null, '', 'http://192.0.2.41', 'http://192.0.2.41:6052', 'https://192.0.2.41:8099', 'http://192.0.2.41:8099/config', 'http://u:p@192.0.2.41:8099', 'homeassistant://navigate/x']) {
    assert.equal(manageOrigin(no), null, String(no));
  }
});

test('the panels, from the device registry: ESPHome devices with a Manage link and a model; Music Assistant copies, other integrations and disabled ones left out', () => {
  const devices = [
    device('b', 'http://192.0.2.42:8099', { name_by_user: 'Bedroom panel', area_id: 'bedroom' }),
    device('a', 'http://192.0.2.41:8099', { name: 'Aqara S1 Plus', area_id: 'kitchen' }),
    // Music Assistant makes a device for a panel's player, with the same model and no Visit link.
    device('ma', null, { name: 'S1 Panel', config_entries: ['ma'] }),
    // Another integration's device with a Visit link on 8099 isn't an ESPHome panel.
    device('other', 'http://192.0.2.44:8099', { config_entries: ['ma'] }),
    device('nomodel', 'http://192.0.2.45:8099', { model: null }),
    device('off', 'http://192.0.2.43:8099', { disabled_by: 'user' }),
    device('dup', 'http://192.0.2.41:8099/'),
    device('esp', 'http://192.0.2.50'),
  ];
  const areas = [{ area_id: 'kitchen', name: 'Kitchen' }, { area_id: 'bedroom', name: 'Bedroom' }];
  const got = panelsFrom(devices, areas, '192.0.2.42:8099', entries);
  assert.ok(got.every((p) => p.local === false), 'TEST-NET addresses are public: listed, but not local');
  assert.equal(panelsFrom([device('l', 'http://kitchen-panel.local:8099')], [], '', entries)[0].local, true);
  assert.deepEqual(got.map((p) => [p.name, p.area, p.origin, p.model, p.self]), [
    ['Bedroom panel', 'Bedroom', 'http://192.0.2.42:8099', MODEL, true],
    ['Aqara S1 Plus', 'Kitchen', 'http://192.0.2.41:8099', MODEL, false],
  ]);
  assert.deepEqual(panelsFrom(devices, areas, '', []), [], 'no ESPHome entries, no panels');
});

test("HA's login page for this page: client id and redirect on the page's own origin", () => {
  const u = new URL(authorizeUrl('http://192.0.2.10:8123/', 'http://192.0.2.41:8099', 'abc', 'chal'));
  assert.equal(u.searchParams.get('code_challenge'), 'chal');
  assert.equal(u.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(u.origin + u.pathname, 'http://192.0.2.10:8123/auth/authorize');
  assert.equal(u.searchParams.get('client_id'), 'http://192.0.2.41:8099/');
  assert.equal(u.searchParams.get('redirect_uri'), 'http://192.0.2.41:8099/panels');
  assert.equal(u.searchParams.get('state'), 'abc');
  assert.equal(u.searchParams.get('response_type'), 'code');
});

const panel = (version, sections) => ({ api: 1, name: 'Glassworks Panel', version, sections });
const sec = (id, title, items) => ({ id, title, items });
const sw = (key, value) => ({ key, title: key, kind: 'switch', value, shown: value === 'on' ? 'On' : 'Off' });
const dur = (key, value, choices) => ({ key, title: key, kind: 'duration', value: String(value), shown: 'After ' + value + ' s', choices });
const choice = (key, value, ids) => ({ key, title: key, kind: 'choice', value, shown: value, options: ids.map((i) => [i, i]) });

test('copying sends only what differs and the other panel can take; the rest says why', () => {
  const kitchen = panel('0.9.2', [
    sec('screen', 'Screen', [sw('Full screen', 'on'), dur('Turn off when idle', 300, [30, 60, 120, 300, 0]), sw('Night dimming', 'on')]),
    sec('assist', 'Voice', [sw('on', 'on'), choice('Wake word', 'hey_jarvis', ['okay_nabu', 'hey_jarvis'])]),
    sec('camera', 'Camera', [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: true }]),
    sec('radio', 'Radio', [sw('on', 'on')]),
  ]);
  const bedroom = panel('0.9.1', [
    sec('screen', 'Screen', [sw('Full screen', 'on'), dur('Turn off when idle', 120, [30, 60, 120, 0])]),
    sec('assist', 'Voice', [sw('on', 'off'), choice('Wake word', 'okay_nabu', ['okay_nabu'])]),
    sec('camera', 'Camera', [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: false }]),
    sec('dashboard', 'Home Assistant & dashboard', [sw('Full screen', 'off')]),
  ]);
  const { rows, same } = plan(kitchen, bedroom);
  assert.equal(same, 1, 'Full screen matches');
  const by = Object.fromEntries(rows.map((r) => [r.label, r]));
  assert.deepEqual(rows.filter((r) => r.state === 'send').map((r) => [r.section, r.key, r.value]), [['assist', 'on', 'on']]);
  assert.equal(by['Screen › Turn off when idle'].state, 'cant', "300 s isn't one of the bedroom's choices");
  assert.equal(by['Screen › Night dimming'].state, 'missing', "the bedroom's version doesn't have it");
  assert.equal(by['Voice › Wake word'].state, 'cant', 'an option its version lacks');
  assert.equal(by['Camera › Stream sign-in'].state, 'skip');
  assert.equal(by['Radio › On/off'].state, 'missing', 'no radio there');
  assert.equal(by['Voice › On/off'].to, 'Off');
  assert.equal(by['Voice › On/off'].from, 'On');
  assert.ok(!rows.some((r) => r.section === 'dashboard'), "nothing the kitchen lacks is touched");
});

test('a setting whose kind changed between versions is not sent', () => {
  const a = panel('2', [sec('screen', 'Screen', [sw('Brightness', 'on')])]);
  const b = panel('1', [sec('screen', 'Screen', [{ key: 'Brightness', kind: 'slider', value: '0.5', min: '0', max: '1', step: '0.1', shown: '50 %' }])]);
  assert.equal(plan(a, b).rows[0].state, 'missing');
});

test('what a panel can take', () => {
  assert.ok(accepts({ kind: 'slider', min: '0', max: '2.5' }, '2.5'));
  assert.ok(!accepts({ kind: 'slider', min: '0', max: '2.5' }, '3'));
  assert.ok(!accepts({ kind: 'slider', min: '0', max: '2.5' }, 'x'));
  assert.ok(accepts({ kind: 'duration', choices: [0, 60] }, '0'));
  assert.ok(accepts({ kind: 'text' }, 'anything'));
});

test("a panel's answers, in the page's words", () => {
  assert.deepEqual(outcome(200, { ok: true, restart: true }), { state: 'done', restart: true });
  assert.deepEqual(outcome(202, { pending: true }), { state: 'pending' });
  assert.equal(outcome(409, { ask: { title: 'Turn the radio off?', id: 'one-shot' } }).ask.id, 'one-shot');
  assert.equal(outcome(422, { problem: 'Pick one of the choices.' }).why, 'Pick one of the choices.');
  assert.equal(outcome(404, { missing: true }).state, 'failed');
  assert.equal(outcome(500, null).why, "That panel didn't take it (500).");
  assert.deepEqual(reachability(200, { api: 1, version: '0.9.2' }), { state: 'up', version: '0.9.2' });
  assert.equal(reachability(401, { problem: 'Home Assistant didn\'t confirm this sign-in. Sign in again.' }).state, 'refused');
  assert.equal(reachability(404, null).why, 'It refused (404).');
});

test('PKCE: S256 of the verifier, as RFC 7636 appendix B has it; SHA-256 matches node on every padding length', () => {
  assert.equal(pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  for (let n = 0; n < 200; n++) {
    const b = randomBytes(n);
    assert.equal(Buffer.from(sha256(new Uint8Array(b))).toString('hex'), createHash('sha256').update(b).digest('hex'), 'length ' + n);
  }
});

/** A fake network: each host's answers, and every request's Authorization header. */
function net(hosts) {
  const seen = [];
  const fetchFn = async (url, init) => {
    const u = new URL(url);
    seen.push([u.host, u.pathname, (init.headers || {}).Authorization || null]);
    const h = hosts[u.host];
    if (!h) throw new TypeError('Failed to fetch');
    const [status, body, extra] = h(u.pathname);
    return { status, json: async () => body, headers: { get: (k) => (extra || {})[k.toLowerCase()] ?? null } };
  };
  return { seen, fetchFn };
}

const settings = { api: 1, version: '0.9.2', sections: [] };
const panelAt = (host) => ({ origin: 'http://' + host, host, model: MODEL, local: localHost(new URL('http://' + host).hostname) });
const noWait = async () => {};

test('the token goes only to a device that answers its hello as the panel the registry lists', async () => {
  const { seen, fetchFn } = net({
    'kitchen-panel.local:8099': (path) => (path === '/manage/api/hello' ? [200, { api: 1, glassworks: true, model: MODEL }] : [200, settings]),
    // Answers on 8099, but as something else: a camera, or a panel the registry lists with another model.
    'garage-panel.local:8099': (path) => (path === '/manage/api/hello' ? [200, { api: 1, glassworks: true, model: 'Pixel Tablet (Glassworks)' }] : [200, settings]),
    'hall-panel.local:8099': () => [404, null],
  });
  const ok = await readPanel(panelAt('kitchen-panel.local:8099'), 'TOKEN', fetchFn);
  assert.equal(ok.reach.state, 'up');
  assert.ok(ok.trusted);
  const other = await readPanel(panelAt('garage-panel.local:8099'), 'TOKEN', fetchFn);
  assert.equal(other.reach.state, 'stranger');
  assert.ok(!other.trusted);
  assert.equal((await readPanel(panelAt('hall-panel.local:8099'), 'TOKEN', fetchFn)).reach.state, 'stranger');
  assert.equal((await readPanel(panelAt('bedroom-panel.local:8099'), 'TOKEN', fetchFn)).reach.state, 'down');
  assert.deepEqual(seen.filter(([, , auth]) => auth).map(([host, path]) => host + path), ['kitchen-panel.local:8099/manage/api/settings'], 'the token went to the matching panel only');
  assert.ok(seen.every(([, path, auth]) => path !== '/manage/api/hello' || auth === null), 'hello never carries the token');
});

test("a hello matches only Glassworks' own answer with the registry's model", () => {
  const p = panelAt('kitchen-panel.local:8099');
  assert.ok(hello(p, { glassworks: true, model: MODEL }));
  for (const no of [null, {}, { model: MODEL }, { glassworks: 'yes', model: MODEL }, { glassworks: true, model: MODEL + ' ' }, { glassworks: true }]) assert.ok(!hello(p, no), JSON.stringify(no));
  assert.ok(!hello({ ...p, model: undefined }, { glassworks: true, model: undefined }));
});

test('which hosts are on the local network: the panel API\'s own rule', () => {
  for (const yes of ['10.0.0.41', '172.16.0.1', '172.31.255.254', '192.168.1.20', '127.0.0.1', '169.254.3.4', 'localhost', 'kitchen-panel.local', 'Kitchen-Panel.LOCAL.',
    '[::1]', '[fe80::1]', '[febf::1]', '[fd00::41]', '[fc00::1]']) assert.ok(localHost(yes), yes);
  for (const no of ['', null, '192.0.2.41', '203.0.113.7', '172.32.0.1', '172.15.0.1', '8.8.8.8', '10.0.0.41.evil.example', 'local', '.local', 'panel.lan',
    'homeassistant.example', '[2001:db8::1]', '[fec0::1]', '[::ffff:10.0.0.1]', '999.1.1.1']) assert.ok(!localHost(no), String(no));
});

test('a panel off the local network gets nothing: no hello, no token', async () => {
  const { seen, fetchFn } = net({ '203.0.113.7:8099': () => [200, { api: 1, glassworks: true, model: MODEL }] });
  const r = await readPanel(panelAt('203.0.113.7:8099'), 'TOKEN', fetchFn, 6_000, noWait);
  assert.equal(r.reach.state, 'remote');
  assert.ok(!r.trusted);
  assert.deepEqual(seen, [], 'not contacted at all');
});

test("a busy panel's 503 with Retry-After is tried again, a bounded number of times", async () => {
  let n = 0;
  const { fetchFn } = net({ 'kitchen-panel.local:8099': (path) => (path === '/manage/api/hello' ? [200, { api: 1, glassworks: true, model: MODEL }]
    : ++n <= 2 ? [503, { problem: 'busy' }, { 'retry-after': '2' }] : [200, settings]) });
  const waits = [];
  const ok = await readPanel(panelAt('kitchen-panel.local:8099'), 'TOKEN', fetchFn, 6_000, async (ms) => waits.push(ms));
  assert.equal(ok.reach.state, 'up', 'the third try answered');
  assert.deepEqual(waits, [2000, 2000]);

  let calls = 0;
  const always = await retrying(async () => (calls++, { status: 503, headers: { get: () => '30' } }), async (ms) => waits.push(ms));
  assert.equal(always.status, 503);
  assert.equal(calls, 2, 'one wait of 5 s uses the whole budget: no third try');
  assert.deepEqual(waits.slice(2), [5000], 'a long Retry-After waits at most 5 s in all');

  const split = [];
  let tries = 0;
  await retrying(async () => (tries++, { status: 503, headers: { get: () => '4' } }), async (ms) => split.push(ms));
  assert.deepEqual(split, [4000, 1000], 'the waits together stay within the budget');
  assert.equal(tries, TRIES);
  assert.equal(split.reduce((a, b) => a + b, 0), MAX_WAIT_S * 1000);

  let once = 0;
  await retrying(async () => (once++, { status: 503, headers: { get: () => null } }), noWait);
  assert.equal(once, 1, 'no Retry-After, no retry');
  let ok200 = 0;
  await retrying(async () => (ok200++, { status: 200, headers: { get: () => '1' } }), noWait);
  assert.equal(ok200, 1);
});

test('only a local, trusted panel with settings is usable, whatever the list holds', async () => {
  const ok = { local: true, trusted: true, settings: settings };
  assert.ok(usable(ok));
  for (const no of [null, {}, { ...ok, local: false }, { ...ok, local: 'yes' }, { ...ok, trusted: false }, { ...ok, trusted: 1 }, { ...ok, settings: null }])
    assert.ok(!usable(no), JSON.stringify(no));

  // A list as the page holds it, even with remote entries that somehow carry trust and settings: after readPanel,
  // nothing off the local network is usable, trusted or has settings.
  const { seen, fetchFn } = net({
    'kitchen-panel.local:8099': (path) => (path === '/manage/api/hello' ? [200, { api: 1, glassworks: true, model: MODEL }] : [200, settings]),
    '203.0.113.7:8099': (path) => (path === '/manage/api/hello' ? [200, { api: 1, glassworks: true, model: MODEL }] : [200, settings]),
  });
  const list = await Promise.all([
    panelAt('kitchen-panel.local:8099'),
    { ...panelAt('203.0.113.7:8099'), trusted: true, settings },
    { ...panelAt('kitchen-panel.local:8099'), local: false, trusted: true, settings },
  ].map((p) => readPanel(p, 'TOKEN', fetchFn, 6_000, noWait)));
  const offLan = list.filter((p) => !localHost(new URL(p.origin).hostname) || p.local !== true);
  assert.equal(offLan.length, 2);
  for (const p of offLan) assert.ok(!p.trusted && !p.settings && !usable(p), JSON.stringify(p));
  assert.deepEqual(list.map(usable), [true, false, false]);
  assert.ok(!seen.some(([host]) => host === '203.0.113.7:8099'));
});

test("a panel that answered hello is never Down: each settings answer is said as what it is", async () => {
  const hi = [200, { api: 1, glassworks: true, model: MODEL }];
  const at = (settingsAnswer) => net({ 'kitchen-panel.local:8099': (path) => (path === '/manage/api/hello' ? hi : settingsAnswer()) }).fetchFn;
  const read = (fetchFn, settingsMs = 6_000) => readPanel(panelAt('kitchen-panel.local:8099'), 'TOKEN', fetchFn, 6_000, noWait, settingsMs);

  const noHello = await read(net({}).fetchFn);
  assert.deepEqual([noHello.reach.state, noHello.reach.why.startsWith("Can't reach it")], ['down', true], 'no hello: Down');

  const unconfirmed = await read(at(() => [401, { problem: "Home Assistant didn't confirm this sign-in. Sign in again." }]));
  assert.equal(unconfirmed.reach.state, 'refused');
  assert.equal(unconfirmed.reach.why, "This panel couldn't confirm your sign-in with its Home Assistant. It said: Home Assistant didn't confirm this sign-in. Sign in again.");

  const notAdmin = await read(at(() => [403, { problem: "Sam isn't a Home Assistant administrator." }]));
  assert.deepEqual([notAdmin.reach.state, notAdmin.reach.why], ['refused', "Sam isn't a Home Assistant administrator."]);

  const busy = await read(at(() => [503, { problem: 'The panel is still asking Home Assistant about other sign-ins. Try again in a moment.' }, { 'retry-after': '2' }]));
  assert.deepEqual([busy.reach.state, busy.reach.why], ['busy', 'The panel is still asking Home Assistant about other sign-ins. Try again in a moment.']);

  // Its Home Assistant is slow to answer it (the tablet's stalled IPv6 connect, 2026-11-18): hello came back, the read didn't.
  const silent = (url, init) => (url.endsWith('/manage/api/hello') ? Promise.resolve({ status: 200, json: async () => hi[1], headers: { get: () => null } })
    : new Promise((_, no) => init.signal.addEventListener('abort', () => no(new DOMException('aborted', 'AbortError')))));
  const slow = await read(silent, 30);
  assert.equal(slow.reach.state, 'unchecked');
  assert.ok(slow.reach.why.startsWith("It answered, but didn't finish checking your sign-in"), slow.reach.why);
  for (const p of [unconfirmed, notAdmin, busy, slow]) assert.ok(!usable(p), 'none of them gets a Manage or a copy');
});

test('the settings read waits longer than hello: the panel asks its Home Assistant first', () => {
  assert.ok(SETTINGS_MS >= 12_000, String(SETTINGS_MS));
});

test('a copied setting gives up after its timeout and says it may have changed; a network failure says can\'t reach', async () => {
  const p = { ...panelAt('kitchen-panel.local:8099'), name: 'Kitchen panel', trusted: true, settings };
  const fields = { section: 'screen', key: 'Turn off when idle', value: '60' };
  const seen = [];
  const ok = await writeSetting(p, 'TOKEN', fields, async (url, init) => (seen.push([url, init.method, init.headers.Authorization, String(init.body)]),
    { status: 200, json: async () => ({ ok: true, restart: false }), headers: { get: () => null } }), 1_000, noWait);
  assert.deepEqual(ok, { state: 'done', restart: false });
  assert.deepEqual(seen, [['http://kitchen-panel.local:8099/manage/api/settings', 'POST', 'Bearer TOKEN', 'section=screen&key=Turn+off+when+idle&value=60']]);

  const silent = (url, init) => new Promise((_, no) => init.signal.addEventListener('abort', () => no(new DOMException('aborted', 'AbortError'))));
  const late = await writeSetting(p, 'TOKEN', fields, silent, 30, noWait);
  assert.deepEqual(late, { state: 'failed', unreachable: true, why: "Kitchen panel didn't answer in time. The setting may have changed: check its page." });

  const gone = await writeSetting(p, 'TOKEN', fields, async () => { throw new TypeError('Failed to fetch'); }, 1_000, noWait);
  assert.deepEqual(gone, { state: 'failed', unreachable: true, why: "Can't reach Kitchen panel." });
});

test('sign-out counts as done only when Home Assistant says so', async () => {
  const calls = [];
  const with_ = (status) => async (url, init) => (calls.push([url, String(init.body)]), { status });
  assert.equal(await revokeAt('http://192.0.2.10:8123', 'REFRESH', with_(200)), true);
  assert.deepEqual(calls[0], ['http://192.0.2.10:8123/auth/revoke', 'token=REFRESH']);
  assert.equal(await revokeAt('http://192.0.2.10:8123', 'REFRESH', with_(500)), false);
  assert.equal(await revokeAt('http://192.0.2.10:8123', 'REFRESH', async () => { throw new TypeError('Failed to fetch'); }), false);
});

test('writeSetting sends nothing to a panel that is not usable now', async () => {
  const sent = [];
  const fetchFn = async (url) => (sent.push(url), { status: 200, json: async () => ({ ok: true }), headers: { get: () => null } });
  const base = { ...panelAt('kitchen-panel.local:8099'), name: 'Kitchen panel', trusted: true, settings };
  for (const p of [{ ...base, local: false }, { ...base, trusted: false }, { ...base, settings: null }, { ...panelAt('203.0.113.7:8099'), name: 'Office panel', trusted: true, settings }]) {
    const o = await writeSetting(p, 'TOKEN', { section: 's', key: 'k', value: 'v' }, fetchFn, 1_000, noWait);
    assert.equal(o.state, 'failed');
    assert.ok(o.why.startsWith('Not sent: '), o.why);
  }
  assert.deepEqual(sent, [], 'no request at all');
});

test('a body that stalls after the headers still times out, for reads and writes', { timeout: 5_000 }, async () => {
  const stall = (url, init) => Promise.resolve({
    status: 200, headers: { get: () => null },
    json: () => (url.endsWith('/manage/api/hello') ? Promise.resolve({ api: 1, glassworks: true, model: MODEL }) : new Promise(() => {})),
  });
  const read = await readPanel(panelAt('kitchen-panel.local:8099'), 'TOKEN', stall, 1_000, noWait, 30);
  assert.equal(read.reach.state, 'unchecked');
  const p = { ...panelAt('kitchen-panel.local:8099'), name: 'Kitchen panel', trusted: true, settings };
  const write = await writeSetting(p, 'TOKEN', { section: 's', key: 'k', value: 'v' }, stall, 30, noWait);
  assert.equal(write.state, 'failed');
  assert.ok(write.unreachable && write.why.includes('may have changed'), write.why);
});

test('a copy stops at the first write the panel does not answer; the rest are not sent', async () => {
  const p = { ...panelAt('kitchen-panel.local:8099'), name: 'Bedroom panel', trusted: true, settings };
  const rows = ['a', 'b', 'c'].map((k) => ({ section: 'screen', key: k, value: '1' }));
  let calls = 0;
  const firstOk = async () => (++calls === 1 ? { status: 200, headers: { get: () => null }, json: async () => ({ ok: true }) } : (() => { throw new TypeError('Failed to fetch'); })());
  const r = await copyRows(p, 'TOKEN', [...rows, { section: 'screen', key: 'd', value: '1' }], null, firstOk, 1_000, noWait);
  assert.equal(calls, 2, 'one more after the failure? no: it stops at the failed one');
  assert.deepEqual([...r.values()].map((o) => o.state), ['done', 'failed', 'failed', 'failed']);
  assert.equal(r.get('screen\nb').why, "Can't reach Bedroom panel.");
  assert.equal(r.get('screen\nc').why, 'Not sent: Bedroom panel stopped answering.');

  const bodies = [];
  await copyRows(p, 'TOKEN', rows.slice(0, 1), new Map([['screen\na', 'ask-1']]), async (u, init) => (bodies.push(String(init.body)), { status: 200, headers: { get: () => null }, json: async () => ({ ok: true }) }), 1_000, noWait);
  assert.deepEqual(bodies, ['section=screen&key=a&value=1&answer=ask-1'], 'a question is answered with its one-shot id');
});

test("a slider value off the other panel's steps isn't sent: it would be snapped to another value", () => {
  const slider = { kind: 'slider', min: '0', max: '1', step: '0.5' };
  assert.ok(accepts(slider, '0.5'));
  assert.ok(accepts(slider, '1'));
  assert.ok(!accepts(slider, '0.7'), '0.7 would land as 0.5');
  assert.ok(accepts({ kind: 'slider', min: '0', max: '2.5', step: '0.1' }, '1.2'), 'float steps still match');
  assert.ok(accepts({ kind: 'slider', min: '0', max: '2.5', step: '0' }, '1.234'), 'no step: any value in range');
  const from = panel('2', [sec('screen', 'Screen', [{ key: 'Range', kind: 'slider', value: '0.7', shown: '0.7', min: '0', max: '1', step: '0.1' }])]);
  const to = panel('1', [sec('screen', 'Screen', [{ key: 'Range', kind: 'slider', value: '0', shown: '0', min: '0', max: '1', step: '0.5' }])]);
  assert.equal(plan(from, to).rows[0].state, 'cant');
});

test('sign-out gives up on a Home Assistant that never answers, and says so', { timeout: 5_000 }, async () => {
  // Never answers, and ignores the abort too: the page's own timer must still end the wait.
  const silent = () => new Promise(() => {});
  assert.equal(await revokeAt('http://192.0.2.10:8123', 'REFRESH', silent, 30), false);
  const headersOnly = async () => ({ status: 200, headers: { get: () => null }, json: () => new Promise(() => {}) });
  assert.equal(await revokeAt('http://192.0.2.10:8123', 'REFRESH', headersOnly, 30), true, 'a 200 is an answer even if its empty body never ends');
});

test('a copy stops sending when the person signs out during it', async () => {
  const p = { ...panelAt('kitchen-panel.local:8099'), name: 'Bedroom panel', trusted: true, settings };
  const rows = ['a', 'b', 'c'].map((k) => ({ section: 'screen', key: k, value: '1' }));
  let signedOut = false;
  const sent = [];
  const fetchFn = async (u, init) => { sent.push(String(init.body)); signedOut = true; return { status: 200, headers: { get: () => null }, json: async () => ({ ok: true }) }; };
  const r = await copyRows(p, 'TOKEN', rows, null, fetchFn, 1_000, noWait, () => signedOut);
  assert.equal(sent.length, 1, 'nothing more after the sign-out');
  assert.deepEqual([...r.keys()], ['screen\na']);
});

test('a sign-out during a busy wait sends no further try', async () => {
  const p = { ...panelAt('kitchen-panel.local:8099'), name: 'Bedroom panel', trusted: true, settings };
  let signedOut = false;
  let posts = 0;
  const busy = async () => (posts++, { status: 503, headers: { get: () => '2' }, json: async () => ({ problem: 'busy' }) });
  const o = await writeSetting(p, 'TOKEN', { section: 's', key: 'k', value: 'v' }, busy, 1_000, async () => { signedOut = true; }, () => signedOut);
  assert.equal(posts, 1, 'the retry after the wait was not sent');
  assert.equal(o.state, 'failed');
  let calls = 0;
  await retrying(async () => (calls++, { status: 503, headers: { get: () => '1' } }), noWait, () => false);
  assert.equal(calls, TRIES, 'without a sign-out, the retries still run');
});
