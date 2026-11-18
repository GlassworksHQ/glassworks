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
const S1 = 'Panel Hub S1 Plus (S1 Kiosk)';
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
  if (u.host === HA) {
    if (u.pathname === '/auth/authorize') {
      assert.equal(u.searchParams.get('code_challenge_method'), 'S256');
      challenge = u.searchParams.get('code_challenge');
      res.writeHead(302, { Location: u.searchParams.get('redirect_uri') + '?code=fake-code&state=' + u.searchParams.get('state') });
      return res.end();
    }
    if (u.pathname === '/auth/token') {
      const f = await form(req);
      const verified = f.get('grant_type') !== 'authorization_code' ||
        createHash('sha256').update(f.get('code_verifier') || '').digest('base64url') === challenge;
      if (!verified) return send(res, 400, { error: 'invalid_request', error_description: 'PKCE verifier does not match' });
      return send(res, 200, { access_token: 'fake-access', refresh_token: 'fake-refresh', expires_in: 1800, token_type: 'Bearer' });
    }
    if (u.pathname === '/auth/revoke') return send(res, revokeOk ? 200 : 500, '');
  }
  const p = panels[u.host];
  if (u.host === 'kitchen-panel.local:8099' && u.pathname === '/panels') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': csp });
    return res.end(readFileSync(pageFile));
  }
  if (u.pathname === '/manage/api/hello' && hellos[u.host]) return send(res, 200, { api: 1, glassworks: true, model: hellos[u.host] });
  if (u.pathname.startsWith('/manage/js/')) return send(res, 200, readFileSync(join(assets, u.pathname.slice(11))), 'text/javascript');
  // Slow, like a panel whose Home Assistant check takes a while: the page draws the others first.
  if (u.host === 'porch-panel.local:8099' && u.pathname === '/manage/api/settings')
    return setTimeout(() => send(res, 401, { problem: "Home Assistant didn't confirm this sign-in. Sign in again." }), 1500);
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
      if (m.type === 'auth') { frame({ type: 'auth_ok' }); continue; }
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
  assert.ok(!contacted.has('203.0.113.7:8099'), 'a panel off the local network got no request at all');
  assert.ok(!haAddressSent, "no copy sent a panel's own Home Assistant address");
  console.log('ok: CSP served and nothing refused; PKCE verified; the token went to', [...tokenTo].sort().join(', ') + '; nothing went to 203.0.113.7');
} finally {
  cdp.close();
  browser.kill();
  proxy.close();
  inner.close();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
