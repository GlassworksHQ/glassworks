/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Screenshots of All panels (#466) with its real script and the page's real CSP (from the mockup), against fakes: a
// fake Home Assistant (login with PKCE, token, websocket) and fake panels, all behind an HTTP proxy in this process,
// driven in headless Chrome over CDP. It fails if the CSP blocks anything the page does, or if the token reaches a
// device that didn't answer as a panel.
// Touches nothing else: no real panel or Home Assistant, a temporary Chrome profile. Illustrative values only.
//
//   ./gradlew :kiosk:testDebugUnitTest --tests '*ManagePageMockups*' -Ps1MockupDir=/tmp/shots
//   node core/src/test/js/panels-shots.mjs /tmp/shots/manage/13-all-panels.html /tmp/shots/panels
//
// Chrome: CHROME=<path>, else Google Chrome's macOS path.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [pageFile, out] = process.argv.slice(2);
const csp = readFileSync(pageFile.replace(/\.html$/, '.csp'), 'utf8').trim();
const csrfOnPage = /data-csrf="([^"]*)"/.exec(readFileSync(pageFile, 'utf8'))?.[1] ?? '';
// The real page always carries one: without it, the fake would take an empty form token the real panel refuses.
assert.ok(csrfOnPage, 'the page has a form token (data-csrf)');
if (!pageFile || !out) throw new Error('usage: panels-shots.mjs <13-all-panels.html> <out dir>');
mkdirSync(out, { recursive: true });
const assets = join(dirname(fileURLToPath(import.meta.url)), '../../main/assets/manage');
const chrome = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

// ------------------------------------------------------------------ the fakes

const HA = '192.0.2.10:8123';
let admin = true;
const sw = (key, on, more = {}) => ({ key, title: key, kind: 'switch', value: on ? 'on' : 'off', shown: on ? 'On' : 'Off', ...more });
const idle = (s, choices) => ({ key: 'Turn off when idle', title: 'Turn off when idle', kind: 'duration', value: String(s), shown: s ? 'After ' + s / 60 + ' minutes' : 'Never', choices });
const range = (m) => ({ key: 'Presence range', title: 'Presence range', kind: 'slider', value: String(m), shown: 'Up to ' + m + ' m', min: '0', max: '2.5', step: '0.1' });
const wake = (id, ids) => ({ key: 'Wake word', title: 'Wake word', kind: 'choice', value: id, shown: { okay_nabu: 'Okay Nabu', hey_jarvis: 'Hey Jarvis' }[id], options: ids.map((i) => [i, { okay_nabu: 'Okay Nabu', hey_jarvis: 'Hey Jarvis' }[i]]) });
const panels = {
  'kitchen-panel.local:8099': { api: 1, name: 'Glassworks Panel', frame: 'S1 Plus', version: '0.9.2', sections: [
    { id: 'dashboard', title: 'Home Assistant & dashboard', items: [{ key: 'Home Assistant', title: 'Home Assistant', kind: 'text', value: 'http://192.0.2.10:8123', shown: '192.0.2.10', copy: false }, sw('Full screen', true)] },
    { id: 'screen', title: 'Screen', items: [sw('Wakes when someone comes close', true), range(1.2), idle(300, [30, 60, 120, 300, 600, 0]), sw('Brightness', true)] },
    { id: 'assist', title: 'Voice', items: [sw('on', true), wake('hey_jarvis', ['okay_nabu', 'hey_jarvis']), sw('Wake sound', false)] },
    { id: 'radio', title: 'Radio', items: [sw('on', true)] },
    { id: 'camera', title: 'Camera', items: [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: true }] },
  ] },
  'bedroom-panel.local:8099': { api: 1, name: 'Glassworks Panel', frame: 'S1 Plus', version: '0.9.1', sections: [
    { id: 'dashboard', title: 'Home Assistant & dashboard', items: [{ key: 'Home Assistant', title: 'Home Assistant', kind: 'text', value: 'http://homeassistant.local:8123', shown: 'homeassistant.local', copy: false }, sw('Full screen', false)] },
    { id: 'screen', title: 'Screen', items: [sw('Wakes when someone comes close', true), range(0.8), idle(120, [30, 60, 120, 600, 0])] },
    { id: 'assist', title: 'Voice', items: [sw('on', false), wake('okay_nabu', ['okay_nabu', 'hey_jarvis'])] },
    { id: 'radio', title: 'Radio', items: [sw('on', false)] },
    { id: 'camera', title: 'Camera', items: [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: false }] },
  ] },
};
const S1 = 'Panel Hub S1 Plus (Glassworks)';
const devices = [
  { id: 'k', name: 'Aqara S1 Plus', name_by_user: 'Kitchen panel', model: S1, config_entries: ['esp-k'], area_id: 'kitchen', configuration_url: 'http://kitchen-panel.local:8099', disabled_by: null },
  { id: 'b', name: 'Aqara S1 Plus', name_by_user: 'Bedroom panel', model: S1, config_entries: ['esp-b'], area_id: 'bedroom', configuration_url: 'http://bedroom-panel.local:8099', disabled_by: null },
  { id: 'h', name: 'Pixel Tablet', name_by_user: 'Hallway tablet', model: 'Pixel Tablet (Glassworks)', config_entries: ['esp-h'], area_id: 'hallway', configuration_url: 'http://hallway-tablet.local:8099', disabled_by: null },
  // In HA as a panel, but what answers at its address says it's something else: it must never get the token.
  { id: 'g', name: 'Garage panel', model: S1, config_entries: ['esp-g'], area_id: 'garage', configuration_url: 'http://garage-panel.local:8099', disabled_by: null },
  // Answers hello, but its Home Assistant doesn't confirm the token (the Pixel Tablet on 2026-11-18): Refused, not Down.
  { id: 'p', name: 'Porch panel', model: S1, config_entries: ['esp-p'], area_id: 'porch', configuration_url: 'http://porch-panel.local:8099', disabled_by: null },
  // In HA as a panel, at an address off the local network: it must get no request at all.
  { id: 'o', name: 'Office panel', model: S1, config_entries: ['esp-o'], area_id: 'office', configuration_url: 'http://203.0.113.7:8099', disabled_by: null },
  { id: 'ma', name: 'S1 Panel', model: S1, config_entries: ['ma'], area_id: null, configuration_url: null, disabled_by: null },
];
const entries = [...['k', 'b', 'h', 'g', 'o', 'p'].map((x) => ({ entry_id: 'esp-' + x, domain: 'esphome' })), { entry_id: 'ma', domain: 'music_assistant' }];
const hellos = { 'kitchen-panel.local:8099': S1, 'bedroom-panel.local:8099': S1, 'garage-panel.local:8099': 'ESP32-CAM', 'porch-panel.local:8099': S1, '203.0.113.7:8099': S1 };
/** Hosts that got an `Authorization` header, and the PKCE challenge the login page was given. */
const tokenTo = new Set();
/** Every host the page sent anything to. */
const contacted = new Set();
/** Every request, as `METHOD host/path`. */
const requests = [];
let challenge = null;
/** Whether a copy ever sent a panel's own Home Assistant address (the 2026-11-18 panel run's blocker). */
let haAddressSent = false;
/** Whether any request carried an access token in its URL (it must only ever be a header or a form field). */
let tokenInUrl = false;
/** Handoffs a panel received: which panel, and the page it says it came from. */
const handoffs = [];
/** How long the fake Home Assistant takes to answer a token request, and the status it answers a renewal with. */
let tokenDelayMs = 0;
let renewStatus = 200;
/** The bearer each websocket to the fake Home Assistant signed in with. */
const wsTokens = [];
/** Whether this panel keeps the Manage sign-in's Home Assistant sign-in for All panels (#466 one sign-in). */
let panelSignIn = false;
/** While set, this panel holds its renewal of the kept sign-in's access token until the test releases it. */
let accessHeld = null;
/** Renewals this panel received. */
let accessAsked = 0;
/** Whether this panel confirms it ended its kept sign-in, and, while set, the promise it waits for first. */
let forgetOk = true;
let forgetHeld = null;
/** Whether this panel still keeps the sign-in: a forget drops it, confirmed by HA or not, as the real panel does. */
let panelKeeps = true;
/** The form token each forget brought. */
const forgetCsrfs = [];
/** What this panel answers a renewal with: 200, 503 (Home Assistant down) or 401 (the Manage session ended). */
let accessStatus = 200;
/** While set, this panel takes the page's form token for another session's (signed in again in another tab). */
let staleCsrf = false;
/** Whether this panel takes a tab's own sign-in to keep (`/manage/ha/keep`), and the forms it got. */
let keepOk = false;
const keeps = [];
/** While set, Porch holds its settings answers ({ waiting }) until the test answers them. */
let porchHeld = null;
/** Which script this panel stalls on, if any; the stalled answers ({ res, open }), to end later. */
let stallScript = null;
const stalled = [];
/** Whether the fake Home Assistant takes a revoke. */
let revokeOk = true;
const initial = JSON.stringify(panels);
const areas = [{ area_id: 'kitchen', name: 'Kitchen' }, { area_id: 'bedroom', name: 'Bedroom' }, { area_id: 'hallway', name: 'Hallway' }, { area_id: 'garage', name: 'Garage' }, { area_id: 'office', name: 'Office' }, { area_id: 'porch', name: 'Porch' }];

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS' };
const send = (res, status, body, type = 'application/json') => {
  res.writeHead(status, { 'Content-Type': type, ...CORS });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
};
const form = (req) => new Promise((ok) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => ok(new URLSearchParams(b))); });

async function handle(req, res) {
  const u = new URL(req.url, 'http://' + req.headers.host);
  if (req.method === 'OPTIONS') return send(res, 204, '');
  if (req.headers.authorization) tokenTo.add(u.host);
  contacted.add(u.host);
  requests.push(req.method + ' ' + u.host + u.pathname);
  if (/fake-access/.test(decodeURIComponent(u.search))) tokenInUrl = true;
  if (u.host === HA) {
    if (u.pathname === '/auth/authorize') {
      assert.equal(u.searchParams.get('code_challenge_method'), 'S256');
      challenge = u.searchParams.get('code_challenge');
      res.writeHead(302, { Location: u.searchParams.get('redirect_uri') + '?code=fake-code&state=' + u.searchParams.get('state') });
      return res.end();
    }
    if (u.pathname === '/auth/token') {
      const f = await form(req);
      if (tokenDelayMs) await new Promise((ok) => setTimeout(ok, tokenDelayMs));
      const verified = f.get('grant_type') !== 'authorization_code' ||
        createHash('sha256').update(f.get('code_verifier') || '').digest('base64url') === challenge;
      if (!verified) return send(res, 400, { error: 'invalid_request', error_description: 'PKCE verifier does not match' });
      if (f.get('grant_type') === 'refresh_token' && renewStatus !== 200) return send(res, renewStatus, { error: 'unavailable' });
      return send(res, 200, { access_token: 'fake-access', refresh_token: 'fake-refresh', expires_in: 1800, token_type: 'Bearer' });
    }
    if (u.pathname === '/auth/revoke') return send(res, revokeOk ? 200 : 500, '');
  }
  const p = panels[u.host];
  if (u.host === 'kitchen-panel.local:8099' && u.pathname === '/panels') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': csp });
    // The page says whether this panel keeps a Home Assistant sign-in, as the real server does from the session.
    return res.end(readFileSync(pageFile, 'utf8').replace(/data-ha-signin="[^"]*"/, 'data-ha-signin="' + (panelSignIn ? '1' : '') + '"'));
  }
  if (u.pathname === '/manage/ha/token' && req.method === 'POST') {
    const f = await form(req);
    handoffs.push({ host: u.host, from: f.get('from'), token: !!f.get('token') });
    return send(res, 200, '<p>Signed in</p>', 'text/html');
  }
  if (u.host === 'kitchen-panel.local:8099' && u.pathname === '/manage/ha/access' && req.method === 'POST' && panelSignIn) {
    const f = await form(req);
    if (staleCsrf || f.get('csrf') !== csrfOnPage) return send(res, 403, { problem: 'This form has expired.' });
    accessAsked++;
    // The answer is settled when the panel takes the request (HA renewed it then); holding it is the network being slow.
    const keeps = panelKeeps;
    if (accessHeld) await accessHeld.promise;
    if (!keeps) return send(res, 404, { problem: 'No Home Assistant sign-in is kept for this browser.' });
    if (accessStatus === 503) return send(res, 503, { problem: "Can't reach Home Assistant. Try again in a moment." });
    if (accessStatus === 401) return send(res, 401, { problem: 'Your sign-in to this Manage page has ended. Sign in again.' });
    return send(res, 200, { access_token: 'fake-access-from-panel', expires_in: 1800 });
  }
  if (u.host === 'kitchen-panel.local:8099' && u.pathname === '/manage/ha/keep' && req.method === 'POST') {
    const f = await form(req);
    keeps.push({ csrf: f.get('csrf'), refresh: f.get('refresh') });
    if (!keepOk || f.get('csrf') !== csrfOnPage) return send(res, 404, { problem: 'Not kept.' });
    // Kept: from now on this panel gives All panels its token.
    panelSignIn = true;
    panelKeeps = true;
    return send(res, 200, { ok: true });
  }
  if (u.host === 'kitchen-panel.local:8099' && u.pathname === '/manage/ha/forget' && req.method === 'POST') {
    const f = await form(req);
    forgetCsrfs.push(f.get('csrf'));
    if (forgetHeld) await forgetHeld.promise;
    if (f.get('csrf') !== csrfOnPage) return send(res, 403, { problem: 'This form has expired.' });
    panelKeeps = false;
    return forgetOk ? send(res, 200, { ok: true }) : send(res, 502, { problem: "Home Assistant didn't confirm it ended the sign-in. Try again." });
  }
  if (u.pathname === '/manage/api/hello' && hellos[u.host]) return send(res, 200, { api: 1, glassworks: true, model: hellos[u.host] });
  if (stallScript && u.pathname === '/manage/js/' + stallScript) {
    const s = { res, open: true };
    res.on('close', () => { s.open = false; });
    return void stalled.push(s);
  }
  if (u.pathname.startsWith('/manage/js/')) return send(res, 200, readFileSync(join(assets, u.pathname.slice(11))), 'text/javascript');
  // Slow, like a panel whose Home Assistant check takes a while: the page draws the others first.
  if (u.host === 'porch-panel.local:8099' && u.pathname === '/manage/api/settings') {
    const answer = () => send(res, 401, { problem: "Home Assistant didn't confirm this sign-in. Sign in again." });
    // Held by a test until it says so, else answered after 1.5 s.
    if (porchHeld) return void porchHeld.waiting.push(answer);
    return setTimeout(answer, 1500);
  }
  if (p && u.pathname === '/manage/api/settings') {
    if (req.method === 'GET') return send(res, 200, p);
    const f = await form(req);
    if (f.get('key') === 'Home Assistant') haAddressSent = true;
    const item = p.sections.find((s) => s.id === f.get('section'))?.items.find((i) => i.key === f.get('key'));
    if (!item) return send(res, 404, { problem: "This panel doesn't have that setting.", missing: true });
    if (f.get('section') === 'radio' && f.get('answer') !== 'ask-1') return send(res, 409, { ask: { title: 'Turn the radio on?', detail: 'Zigbee devices on this panel join again.', action: 'Turn on', id: 'ask-1' } });
    Object.assign(item, { value: f.get('value') }, item.kind === 'switch' ? { shown: f.get('value') === 'on' ? 'On' : 'Off' } : {});
    return send(res, 200, { ok: true, restart: false });
  }
  res.socket.destroy();
}

/** HA's websocket: auth, then answers to the three calls the page makes. */
function websocket(req, socket) {
  const accept = createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
  const frame = (o) => {
    const b = Buffer.from(JSON.stringify(o));
    const head = b.length < 126 ? Buffer.from([0x81, b.length]) : b.length < 65536 ? Buffer.from([0x81, 126, b.length >> 8, b.length & 255]) : null;
    socket.write(Buffer.concat([head, b]));
  };
  frame({ type: 'auth_required' });
  let buf = Buffer.alloc(0);
  socket.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    // Several frames can come in one read: the page asks its three calls at once.
    while (buf.length >= 6) {
      if ((buf[0] & 0x0f) === 8) return socket.end();
      let len = buf[1] & 0x7f;
      let at = 2;
      if (len === 126) { len = buf.readUInt16BE(2); at = 4; }
      if (buf.length < at + 4 + len) return;
      const mask = buf.subarray(at, at + 4);
      const m = JSON.parse(Buffer.from(buf.subarray(at + 4, at + 4 + len).map((x, i) => x ^ mask[i % 4])).toString());
      buf = buf.subarray(at + 4 + len);
      if (m.type === 'auth') { wsTokens.push(m.access_token); frame({ type: 'auth_ok' }); continue; }
      const result = { 'auth/current_user': { id: 'u1', name: admin ? 'Alex' : 'Sam', is_admin: admin },
        'config/device_registry/list': devices, 'config/area_registry/list': areas, 'config_entries/get': entries }[m.type];
      frame({ id: m.id, type: 'result', success: true, result });
    }
  });
}

// The proxy: Chrome sends every request here, with the full URL; a websocket comes as CONNECT, then upgrades.
const inner = http.createServer(handle).on('upgrade', websocket);
const proxy = http.createServer(handle).on('upgrade', websocket).on('connect', (req, socket) => {
  socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
  inner.emit('connection', socket);
});
await new Promise((ok) => proxy.listen(0, '127.0.0.1', ok));

// ------------------------------------------------------------------ Chrome over CDP

const profile = mkdtempSync(join(tmpdir(), 'gw-panels-shots-'));
const browser = spawn(chrome, ['--headless=new', '--remote-debugging-port=0', '--user-data-dir=' + profile, '--no-first-run',
  '--proxy-server=http://127.0.0.1:' + proxy.address().port, '--proxy-bypass-list=<-loopback>', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
const cleanUp = () => { browser.kill(); proxy.close(); inner.close(); rmSync(profile, { recursive: true, force: true }); };
// Chrome that never starts, or exits first, fails the run instead of leaving it waiting (CodeRabbit on #488).
const wsUrl = await new Promise((ok, no) => {
  const late = setTimeout(() => no(new Error("Chrome didn't start within 30 s")), 30_000);
  browser.on('error', (e) => { clearTimeout(late); no(e); });
  browser.on('exit', (code) => { clearTimeout(late); no(new Error('Chrome exited before it started (' + code + ')')); });
  browser.stderr.on('data', (d) => { const m = /ws:\/\/\S+/.exec(String(d)); if (m) { clearTimeout(late); ok(m[0]); } });
}).catch((e) => { cleanUp(); throw e; });
const targets = await (await fetch(wsUrl.replace('ws://', 'http://').replace(/\/devtools.*/, '/json/list'))).json();
const cdp = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
await new Promise((ok) => cdp.addEventListener('open', ok));
let id = 0;
const pending = new Map();
/** What the page's CSP refused, from Chrome's log. */
const refused = [];
cdp.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.method === 'Log.entryAdded' && /Content Security Policy/i.test(m.params.entry.text)) refused.push(m.params.entry.text);
  if (m.method === 'Runtime.consoleAPICalled' && m.params.args.some((a) => /Content Security Policy/i.test(String(a.value)))) refused.push(m.params.args.map((a) => a.value).join(' '));
  pending.get(m.id)?.(m);
  pending.delete(m.id);
});
const call = (method, params = {}) => new Promise((ok, no) => { pending.set(++id, (m) => (m.error ? no(new Error(method + ': ' + m.error.message)) : ok(m.result))); cdp.send(JSON.stringify({ id, method, params })); });
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const js = async (expr, userGesture = false) => (await call('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, userGesture })).result.value;
const until = async (expr, what) => { for (let i = 0; i < 100; i++) { if (await js(expr)) return; await sleep(100); } throw new Error('never: ' + what + ': ' + (await js('location.href + " | " + document.getElementById("gw-panels")?.textContent'))); };

let viewport = { width: 1440, mobile: false };
async function look(width, scheme) {
  viewport = { width, mobile: width < 600 };
  await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: viewport.mobile ? 2 : 1, mobile: viewport.mobile });
  await call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: scheme }] });
}

/** The whole page: the viewport grows to it first, so the fixed sidebar runs its full height. */
async function shot(name) {
  await sleep(250);
  const { cssContentSize: s } = await call('Page.getLayoutMetrics');
  const height = Math.max(Math.ceil(s.height), 600);
  await call('Emulation.setDeviceMetricsOverride', { width: viewport.width, height, deviceScaleFactor: viewport.mobile ? 2 : 1, mobile: viewport.mobile });
  await sleep(150);
  const { data } = await call('Page.captureScreenshot', { format: 'png' });
  await call('Emulation.setDeviceMetricsOverride', { width: viewport.width, height: 900, deviceScaleFactor: viewport.mobile ? 2 : 1, mobile: viewport.mobile });
  writeFileSync(join(out, name + '.png'), Buffer.from(data, 'base64'));
  console.log('wrote', name);
}

const PAGE = 'http://kitchen-panel.local:8099/panels';
/** Runs this tab's stored token out, so the next use renews it. A block: a top-level `const` would clash on the next call. */
const expire = () => js("{ const t = JSON.parse(sessionStorage.getItem('gw-ha')); t.until = Date.now(); sessionStorage.setItem('gw-ha', JSON.stringify(t)); }");
const listed = "document.querySelectorAll('#gw-list .chip').length === 6 && ![...document.querySelectorAll('#gw-list .chip')].some((c) => c.textContent === 'Asking')";
try {
  await call('Page.enable');
  await call('Log.enable');
  await call('Runtime.enable');
  await look(1440, 'dark');
  await call('Page.navigate', { url: PAGE });
  await until("document.querySelector('#gw-list button')", 'the sign-in button');
  await shot('01-signed-out');
  await js("document.querySelector('#gw-list button').click()");
  await until(listed + " && !document.getElementById('gw-copy').hidden", 'the panels and the copy card');
  await shot('02-panels-and-copy');
  assert.equal(await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Garage')).querySelector('.chip').textContent"), 'Not a panel');
  assert.equal(await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Office')).querySelector('.chip').textContent"), 'Not on your network');
  assert.equal(await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Porch')).querySelector('.chip').textContent"), 'Refused', 'answered hello, then 401: Refused, not Down');
  // Manage on Bedroom while the token needs a slow renewal (6 s, past the click's activation): the new tab opened during
  // the click still gets the sign-in, and the post says which page it came from.
  await expire();
  tokenDelayMs = 6_000;
  const tabsBefore = (await call('Target.getTargets')).targetInfos.filter((t) => t.type === 'page').length;
  // Counts forms this tab sends itself: the handoff is sent from the new tab's own page instead (iOS Safari, 2026-11-19).
  await js("{ window.gwOwnPosts = 0; const send = HTMLFormElement.prototype.submit; HTMLFormElement.prototype.submit = function () { window.gwOwnPosts++; return send.call(this); }; }");
  await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Bedroom')).querySelector('button.pill').click()", true);
  for (let i = 0; i < 120 && !handoffs.length; i++) await sleep(100);
  tokenDelayMs = 0;
  assert.deepEqual(handoffs, [{ host: 'bedroom-panel.local:8099', from: 'kitchen-panel.local:8099', token: true }], 'one handoff, from this page');
  assert.equal((await call('Target.getTargets')).targetInfos.filter((t) => t.type === 'page').length, tabsBefore + 1, 'in a new tab');
  assert.equal(await js('window.gwOwnPosts'), 0, "sent from the new tab's own page, not targeted from this one");
  // Home Assistant doesn't answer the tab's own renewal: the tab keeps its sign-in and says Try again (CodeRabbit on #534).
  await expire();
  renewStatus = 503;
  await call('Page.navigate', { url: PAGE });
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Try again')", 'Try again for the renewal');
  assert.equal(await js("JSON.parse(sessionStorage.getItem('gw-ha'))?.refresh"), 'fake-refresh', 'the tab kept its sign-in');
  renewStatus = 200;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Try again').click()");
  await until(listed, 'the panels after Try again');

  // Copy clicked twice: one copy runs, each setting is sent once.
  const posts = () => requests.filter((r) => r === 'POST bedroom-panel.local:8099/manage/api/settings').length;
  const before = posts();
  const ticked = await js("document.querySelectorAll('#gw-copy input[type=checkbox]:checked').length");
  await js("const b = document.querySelector('#gw-copy button[type=submit]'); b.click(); b.click();");
  await until("document.querySelector('.cpa')", 'the copy result');
  assert.equal(posts() - before, ticked, 'a double click sent each setting once');
  await shot('03-copied-asks-first');
  await js("document.querySelector('.cpa button').click()");
  await until("!document.querySelector('.cpa')", 'the confirmed change');
  assert.ok((await js("document.querySelector('#gw-copy .cpf span').textContent")).startsWith('Copied 5 settings'), 'Change them too keeps the first run\'s results');
  await shot('04-confirmed');

  // The guards at the call sites, not only readPanel: a Manage button forced on for Garage (not a panel) and
  // Office (off the LAN) sends nothing.
  const sent = requests.length;
  // As a click (a user gesture), so the new tab a Manage opens isn't blocked as a popup.
  await js("for (const r of document.querySelectorAll('#gw-list .row')) if (/Garage|Office/.test(r.textContent)) { const b = r.querySelector('button.pill'); b.disabled = false; b.click(); }", true);
  await sleep(1000);
  assert.deepEqual(requests.slice(sent), [], 'a Manage forced on for a panel that is not usable sent nothing');
  // A To picker tampered with to name the Office panel: the card says why, offers no Copy, and nothing goes to it.
  await js("const s = document.querySelector('#gw-copy select[name=to]'); const o = document.createElement('option'); o.value = 'http://203.0.113.7:8099'; s.append(o); s.value = o.value; s.dispatchEvent(new Event('change'))");
  await until("document.querySelector('#gw-copy .note')?.textContent.includes('Office panel')", 'the copy card saying why it stopped');
  assert.equal(await js("document.querySelector('#gw-copy button[type=submit]')"), null);
  await shot('08-copy-to-a-panel-off-the-network');
  // A target that stops answering as a panel during a copy: the card says what was copied and why it stopped.
  Object.assign(panels, JSON.parse(initial));
  await call('Page.navigate', { url: PAGE });
  await until(listed + " && !document.getElementById('gw-copy').hidden", 'the panels again');
  hellos['bedroom-panel.local:8099'] = 'ESP32-CAM';
  await js("document.querySelector('#gw-copy button[type=submit]').click()");
  await until("document.querySelector('#gw-copy .note')?.textContent.includes('Nothing more goes to Bedroom panel')", 'the copy stopping with why');
  await shot('09-copy-target-stopped');
  hellos['bedroom-panel.local:8099'] = S1;
  Object.assign(panels, JSON.parse(initial));
  await look(390, 'dark');
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels on a phone');
  await shot('05-phone');
  await look(1440, 'light');
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels, light');
  await shot('06-light');
  admin = false;
  await look(1440, 'dark');
  await call('Page.navigate', { url: PAGE });
  await until("document.querySelector('#gw-list .note')?.textContent.includes('administrator')", 'the non-admin refusal');
  await shot('07-not-admin');
  // Sign out while Home Assistant won't take the revoke: the page says the sign-in may stay active, and Try again ends it.
  revokeOk = false;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Sign out').click()");
  await until("document.querySelector('#gw-list .note')?.textContent.includes(\"didn't confirm it ended the sign-in\")", 'the revoke warning');
  await shot('10-sign-out-not-confirmed');
  revokeOk = true;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Try again').click()");
  await until("document.querySelector('#gw-list .note')?.textContent.includes('Sign in with a Home Assistant administrator')", 'signed out after Try again');
  // Sign out while a slow panel is still being read: its late answer must not bring the signed-in page back.
  admin = true;
  await js("document.querySelector('#gw-list button').click()");
  await until("[...document.querySelectorAll('#gw-list .chip')].some((c) => c.textContent === 'Asking')", 'a panel still being read');
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Sign out').click()");
  await sleep(2500);
  assert.ok(await js("document.querySelector('#gw-list .note')?.textContent.includes('Sign in with a Home Assistant administrator') && !document.querySelector('#gw-list .chip') && document.getElementById('gw-copy').hidden"),
    'still signed out after the slow panel answered');
  assert.deepEqual(refused, [], 'the CSP blocked nothing the page did');
  // The CSP is on: a request it doesn't allow is refused, and logged.
  await js("fetch('http://203.0.113.9:1234/').catch(() => 0)");
  await sleep(300);
  assert.ok(refused.some((t) => t.includes('203.0.113.9')), 'the served CSP refuses what it should: ' + JSON.stringify(refused));
  assert.deepEqual([...tokenTo].sort(), ['bedroom-panel.local:8099', 'kitchen-panel.local:8099', 'porch-panel.local:8099'], 'the token went to the panels that answered as panels, no one else');
  // One sign-in: signed in to this Manage page with Home Assistant, All panels lists the panels with no Continue.
  panelSignIn = true;
  admin = true;
  await js('sessionStorage.clear()');
  const since = requests.length;
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels, with no second sign-in');
  assert.ok(!requests.slice(since).some((r) => r.includes('/auth/authorize')), "no trip to Home Assistant's login page");
  assert.ok(requests.slice(since).includes('POST kitchen-panel.local:8099/manage/ha/access'), "the token came from this panel's kept sign-in");
  assert.equal(wsTokens.at(-1), 'fake-access-from-panel', "and Home Assistant got that token");
  await shot('11-one-sign-in');
  // A renewal from the panel keeps who signed in, for the list's header (CodeRabbit on #534).
  const who = await js("JSON.parse(sessionStorage.getItem('gw-ha')).user");
  assert.ok(who, 'the list knows who signed in');
  await expire();
  const renewedBefore = handoffs.length;
  const renewalsBefore = accessAsked;
  await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Bedroom')).querySelector('button.pill').click()", true);
  for (let i = 0; i < 50 && handoffs.length === renewedBefore; i++) await sleep(100);
  assert.equal(accessAsked, renewalsBefore + 1, 'the Manage click renewed the token from the panel');
  assert.equal(handoffs.length, renewedBefore + 1, 'and handed it to the panel');
  assert.equal(await js("JSON.parse(sessionStorage.getItem('gw-ha')).user"), who, 'still known after the renewal');
  // Sign out while Manage waits for a slow renewal from the panel, and the panel can't confirm the sign-out: the renewal's
  // late answer must not sign the page back in, and nothing goes to the other panel (CodeRabbit on #534).
  await expire();
  accessHeld = Promise.withResolvers();
  forgetOk = false;
  const handoffsBefore = handoffs.length;
  const askedBefore = accessAsked;
  const pages = async () => (await call('Target.getTargets')).targetInfos.filter((t) => t.type === 'page').length;
  const pagesBefore = await pages();
  await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Bedroom')).querySelector('button.pill').click()", true);
  for (let i = 0; i < 50 && accessAsked === askedBefore; i++) await sleep(100);
  assert.equal(accessAsked, askedBefore + 1, 'Manage asked the panel for a renewal');
  for (let i = 0; i < 50 && (await pages()) !== pagesBefore + 1; i++) await sleep(100);
  assert.equal(await pages(), pagesBefore + 1, 'the click reserved a tab');
  forgetHeld = Promise.withResolvers();
  const forgetsBefore = forgetCsrfs.length;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Sign out').click()");
  // While the panel ends it, there is no second Sign out to click: one attempt decides what the page shows next.
  await until("document.querySelector('#gw-list .note')?.textContent === 'Signing out…'", 'signing out');
  assert.ok(!(await js("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Sign out')")), 'no second Sign out while the first waits');
  for (let i = 0; i < 50 && forgetCsrfs.length === forgetsBefore; i++) await sleep(100);
  assert.deepEqual(forgetCsrfs.slice(forgetsBefore), [csrfOnPage], "one forget, with the page's form token");
  forgetHeld.resolve();
  forgetHeld = null;
  await until("document.querySelector('#gw-list .note')?.textContent.includes(\"didn't confirm it ended the sign-in\")", 'the sign-out warning');
  accessHeld.resolve();
  accessHeld = null;
  // The late renewal is handled once open() gives up: it closes the tab it reserved.
  for (let i = 0; i < 50 && (await pages()) !== pagesBefore; i++) await sleep(100);
  assert.equal(await pages(), pagesBefore, 'the late renewal was handled: the reserved tab is closed');
  assert.equal(await js("sessionStorage.getItem('gw-ha')"), null, 'the late renewal stored no sign-in');
  assert.equal(handoffs.length, handoffsBefore, 'nothing went to the other panel after the sign-out');
  assert.ok(await js("document.querySelector('#gw-list .note')?.textContent.includes(\"didn't confirm it ended the sign-in\")"), 'the sign-out warning stays');
  // Try again works the same way: one attempt at a time (CodeRabbit on #534).
  forgetHeld = Promise.withResolvers();
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Try again').click()");
  await until("document.querySelector('#gw-list .note')?.textContent === 'Signing out…'", 'signing out again');
  assert.ok(!(await js("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Try again')")), 'no second Try again while the first waits');
  forgetHeld.resolve();
  forgetHeld = null;
  await until("document.querySelector('#gw-list .note')?.textContent.includes(\"didn't confirm it ended the sign-in\")", 'the warning again');
  forgetOk = true;
  // Home Assistant down when the panel renews an expired token: the page says so after one request, not two.
  // Signed in to Manage with Home Assistant again: the panel keeps a new sign-in.
  panelKeeps = true;
  accessStatus = 200;
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels again, from the panel');
  await expire();
  accessStatus = 503;
  const outageBefore = accessAsked;
  await call('Page.navigate', { url: PAGE });
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Try again')", 'the outage');
  await sleep(500);
  assert.equal(accessAsked, outageBefore + 1, 'one request to the panel before the outage shows');
  assert.ok(await js("document.querySelector('#gw-list .note')?.textContent.includes(\"Can't reach Home Assistant\")"));
  // This Manage page's own sign-in ended: Sign in, not Try again.
  accessStatus = 401;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent === 'Try again').click()");
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Sign in')", 'the ended Manage sign-in');
  assert.ok(await js("document.querySelector('#gw-list .note')?.textContent.includes('has ended')"));
  await shot('12-manage-sign-in-ended');
  accessStatus = 200;
  // Manage on a row while the panel can't renew: the panel's outage shows after one request, not two (CodeRabbit on #534).
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels, before the Manage outage');
  await expire();
  accessStatus = 503;
  const manageBefore = accessAsked;
  await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Bedroom')).querySelector('button.pill').click()", true);
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Try again')", 'the outage from Manage');
  await sleep(500);
  assert.equal(accessAsked, manageBefore + 1, 'one request to the panel from the Manage click');
  accessStatus = 200;
  // Manage while a slow panel is still being read, and the panel says the Manage sign-in ended: Sign in stays, the slow
  // panel's late answer doesn't draw the list back over it (CodeRabbit on #534).
  porchHeld = { waiting: [] };
  await call('Page.navigate', { url: PAGE });
  await until("[...document.querySelectorAll('#gw-list .row')].some((r) => r.textContent.includes('Bedroom') && r.querySelector('.chip')?.textContent === 'Up')", 'Bedroom up');
  for (let i = 0; i < 50 && !porchHeld.waiting.length; i++) await sleep(100);
  assert.equal(porchHeld.waiting.length, 1, "Porch's read is held");
  await expire();
  accessStatus = 401;
  await js("[...document.querySelectorAll('#gw-list .row')].find((r) => r.textContent.includes('Bedroom')).querySelector('button.pill').click()", true);
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Sign in')", 'Sign in from the Manage click');
  // Porch answers now, after Sign in is up; the browser has it once its fetch to Porch is done.
  const porchAnswered = await js("performance.getEntriesByType('resource').filter((e) => e.name.includes('porch-panel') && e.name.includes('/manage/api/settings')).length");
  porchHeld.waiting.splice(0).forEach((answer) => answer());
  porchHeld = null;
  await until("performance.getEntriesByType('resource').filter((e) => e.name.includes('porch-panel') && e.name.includes('/manage/api/settings')).length > " + porchAnswered, "the browser got Porch's answer");
  await sleep(300);
  assert.ok(await js("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Sign in')"), 'Sign in still there after the slow panel answered');
  accessStatus = 200;
  // Signed in again in another tab: this page's form token is another session's. Reload, not a second HA sign-in.
  staleCsrf = true;
  await call('Page.navigate', { url: PAGE });
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent === 'Reload')", 'the stale page');
  assert.ok(await js("document.querySelector('#gw-list .note')?.textContent.includes('out of date')"));
  staleCsrf = false;
  // The panel no longer keeps the sign-in (an app restart): the tab's fresh token from it goes too; Continue is asked.
  await call('Page.navigate', { url: PAGE });
  await until(listed, 'the panels, with a fresh token from the panel');
  assert.ok(await js("JSON.parse(sessionStorage.getItem('gw-ha')).viaPanel"));
  panelSignIn = false;
  await call('Page.navigate', { url: PAGE });
  await until("[...document.querySelectorAll('#gw-list button')].some((b) => b.textContent.startsWith('Continue with Home Assistant'))", 'Continue after the panel forgot');
  assert.equal(await js("sessionStorage.getItem('gw-ha')"), null);
  // Continue here: the tab's own sign-in goes to this panel to keep, and the tab drops its copy, so the one sign-in also
  // serves other tabs and the panels this browser opens (2026-11-19 panel run).
  keepOk = true;
  const keptBefore = keeps.length;
  await js("[...document.querySelectorAll('#gw-list button')].find((b) => b.textContent.startsWith('Continue with Home Assistant')).click()");
  await until(listed, 'the panels after Continue, from the panel');
  assert.deepEqual(keeps.slice(keptBefore), [{ csrf: csrfOnPage, refresh: 'fake-refresh' }], "the tab's sign-in went to its own panel, with the form token");
  assert.ok(await js("JSON.parse(sessionStorage.getItem('gw-ha')).viaPanel"), 'the tab keeps no refresh token of its own now');
  assert.equal(await js("JSON.parse(sessionStorage.getItem('gw-ha')).refresh"), undefined);
  assert.equal(wsTokens.at(-1), 'fake-access-from-panel', "and the list came with the panel's token");
  keepOk = false;
  panelSignIn = true;
  // The module's download stalls (CodeRabbit on #488): the page says to reload it, rather than "Finding your panels…" for good.
  stallScript = 'panels-core.js';
  await call('Page.navigate', { url: PAGE });
  for (let i = 0; i < 50 && !stalled.length; i++) await sleep(100);
  assert.equal(stalled.length, 1, 'the module asked for panels-core.js, and it hangs');
  await until("document.querySelector('#gw-list .note')?.textContent.includes(\"couldn't start\")", 'the way out when the module stalls');
  assert.ok(stalled[0].open, 'that request was still open when the notice showed');
  for (const s of stalled.splice(0)) send(s.res, 404, 'Not found.', 'text/plain');
  // The fallback's own download stalls: the module isn't held up by it.
  stallScript = 'panels-boot.js';
  await call('Page.navigate', { url: PAGE });
  for (let i = 0; i < 50 && !stalled.length; i++) await sleep(100);
  assert.equal(stalled.length, 1, 'the page asked for panels-boot.js, and it hangs');
  await until("document.getElementById('gw-panels')?.dataset.started === '1'", 'the module running past a stalled fallback');
  assert.ok(stalled[0].open, 'the fallback was still loading when the module started');
  stallScript = null;
  for (const s of stalled.splice(0)) send(s.res, 404, 'Not found.', 'text/plain');
  assert.ok(!contacted.has('203.0.113.7:8099'), 'a panel off the local network got no request at all');
  assert.ok(!haAddressSent, "no copy sent a panel's own Home Assistant address");
  assert.ok(!tokenInUrl, 'no access token in any URL');
  console.log('ok: CSP served and nothing refused; PKCE verified; the token went to', [...tokenTo].sort().join(', ') + '; nothing went to 203.0.113.7');
} finally {
  cdp.close();
  browser.kill();
  proxy.close();
  inner.close();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
