// A fake panel for the setup page (#34): serves the committed build (core/src/main/assets/setup) with the
// app's Inter, and plays the panel's /setup/pair, /setup/done and /setup/channel with made-up state. For
// screenshots and trying the page without a panel; it touches no device and only listens on localhost.
//
//   node kiln/apps/kiln/lan/fake-panel.mjs [--port 8099] [--closed] [--offline] [--no-radio]
//   then open http://localhost:8099/setup; the code the "panel" shows is printed here (482913).
//
// Sign in on the Home Assistant step goes to this fake's /signin/continue?then=setup in the same tab, which signs
// the "panel" in at once and sends the browser back to /setup#signed-in, as the panel does after HA (#453).
// --closed: Home Assistant has adopted the panel, so pairing is off.
// --offline: the panel is on no network, as on its setup hotspot (#197): the page starts with Wi-Fi, the scan
// finds made-up networks, and a join drops the connection, as the hotspot closing does.
// --no-radio: a frame without a radio (the Mirror): the state has no `radio`, so setup has no radio step.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const port = Number(args[args.indexOf('--port') + 1]) || 8099;
const closed = args.includes('--closed');
const offline = args.includes('--offline');
const noRadio = args.includes('--no-radio');
const withDashboards = args.includes('--dashboards');
const preSignedIn = args.includes('--signed-in');
const NETWORKS = [
  { ssid: 'Example-Home', rssi: -48, secure: true },
  { ssid: 'Example-Upstairs', rssi: -63, secure: true },
  { ssid: 'Example-Guest', rssi: -71, secure: false },
];
const CODE = '482913';
const repo = fileURLToPath(new URL('../../../../', import.meta.url));
const files = {
  'index.html': ['core/src/main/assets/setup/index.html', 'text/html; charset=utf-8'],
  'setup.js': ['core/src/main/assets/setup/setup.js', 'text/javascript; charset=utf-8'],
  'setup.css': ['core/src/main/assets/setup/setup.css', 'text/css; charset=utf-8'],
  'inter.ttf': ['core/src/main/res/font/inter.ttf', 'font/ttf'],
};

const state = {
  ip: '192.0.2.10',
  online: !offline,
  // the sign-in link is this fake's own /signin, which signs the "panel" in at once
  ha: {
    discovered: 'http://homeassistant.local:8123',
    url: preSignedIn ? 'http://homeassistant.local:8123' : '',
    signedIn: preSignedIn,
    signinUrl: `http://localhost:${port}/signin`,
    ...(preSignedIn && withDashboards ? { dashboards: ['Default', 'Tablet', 'Energy'], dashboard: 'Default' } : {}),
  },
  radio: {
    mode: 'off', status: 'Off', helper: true, answered: false, allowed: '',
    options: [
      { id: 'off', label: 'Off', detail: 'The radio stays off.' },
      { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: 'Home Assistant\'s ZHA uses the radio over the network.' },
      { id: 'thread_router', label: 'Thread router', detail: 'A Thread border router for Home Assistant (repeater).', experimental: true },
    ],
  },
};
if (noRadio) delete state.radio;
let shown = false;
const sessions = new Set();

const cookieOf = (req) => /(?:^|;\s*)gw_setup=([^;]+)/.exec(req.headers.cookie ?? '')?.[1];
const json = (res, status, body, cookie) => {
  res.writeHead(status, { 'content-type': 'application/json', ...(cookie ? { 'set-cookie': `gw_setup=${cookie}; Path=/setup; HttpOnly; SameSite=Strict` } : {}) });
  res.end(JSON.stringify(body));
};

const server = createServer((req, res) => {
  const path = req.url.split('?')[0];
  if (path === '/setup' || path === '/setup/' || path.startsWith('/setup/') && files[path.slice(7)]) {
    const [file, type] = files[path.startsWith('/setup/') && path.length > 7 ? path.slice(7) : 'index.html'];
    res.writeHead(200, { 'content-type': type, 'cache-control': 'no-cache' });
    return res.end(readFileSync(repo + file));
  }
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    if (path === '/setup/pair' && req.method === 'GET') return json(res, 200, { ok: true, paired: sessions.has(cookieOf(req)), open: !closed });
    if (path === '/setup/pair' && req.method === 'POST') {
      if (closed) return json(res, 403, { ok: false, error: 'Home Assistant has added this panel, so pairing over the network is off.' });
      const code = new URLSearchParams(body).get('code');
      if (code === null) { shown = true; console.log(`the panel shows ${CODE}`); return json(res, 200, { ok: true, shown: true }, 'unpaired-id'); }
      if (!shown) return json(res, 409, { ok: false, error: 'That code has run out. Show a new one.' });
      if (code !== CODE) return json(res, 401, { ok: false, error: 'That\'s not the code. 4 tries left.' });
      shown = false;
      const token = `session-${sessions.size + 1}`;
      sessions.add(token);
      return json(res, 200, { ok: true, paired: true }, token);
    }
    if (path === '/signin/continue' && new URLSearchParams(req.url.split('?')[1]).get('then') === 'setup') {
      state.ha.signedIn = true;
      if (withDashboards) { state.ha.dashboards = ['Default', 'Tablet', 'Energy']; state.ha.dashboard = 'Default'; }
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end('<!doctype html><meta http-equiv="refresh" content="0;url=/setup#signed-in"><title>Signed in</title>');
    }
    if (path === '/signin') {
      state.ha.signedIn = true;
      if (withDashboards) { state.ha.dashboards = ['Default', 'Tablet', 'Energy']; state.ha.dashboard = 'Default'; }
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end('<!doctype html><title>Signed in</title><p>The fake panel is signed in to Home Assistant. Close this tab: the setup page notices within 3 seconds.</p>');
    }
    if (path === '/setup/done' && req.method === 'POST') { sessions.delete(cookieOf(req)); return json(res, 200, { ok: true }); }
    res.writeHead(404).end();
  });
});

// The setup channel: the panel's line-delimited JSON, one text frame per line (WebSocketStreams' framing).
server.on('upgrade', (req, sock) => {
  if (req.url !== '/setup/channel') return sock.destroy();
  const accept = createHash('sha1').update(`${req.headers['sec-websocket-key']}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64');
  sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  const paired = sessions.has(cookieOf(req));
  const send = (o) => {
    const p = Buffer.from(`${JSON.stringify(o)}\n`);
    const head = p.length < 126 ? Buffer.from([0x81, p.length]) : Buffer.from([0x81, 126, p.length >> 8, p.length & 255]);
    sock.write(Buffer.concat([head, p]));
  };
  let buf = Buffer.alloc(0);
  sock.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 6) {
      const op = buf[0] & 15;
      let len = buf[1] & 127;
      let at = 2;
      if (len === 126) { len = buf.readUInt16BE(2); at = 4; }
      if (buf.length < at + 4 + len) return;
      const mask = buf.subarray(at, at + 4);
      const text = Buffer.from(buf.subarray(at + 4, at + 4 + len).map((b, i) => b ^ mask[i & 3])).toString();
      buf = buf.subarray(at + 4 + len);
      if (op === 8) return sock.end();
      for (const line of text.split('\n').filter(Boolean)) {
        const m = JSON.parse(line);
        if (m.op !== 'state' && !paired) { send({ id: m.id, ok: false, error: 'Pair with the panel first: enter the code it shows.' }); continue; }
        if (m.op === 'set_ha' || m.op === 'ha.set_url') state.ha.url = m.url;
        if (m.op === 'ha.set_dashboard' && m.dashboard) state.ha.dashboard = m.dashboard;
        if (m.op === 'set_radio' || m.op === 'radio.set_mode') Object.assign(state.radio, { mode: m.mode, answered: true, status: m.mode === 'off' ? 'Off' : 'Running' });
        if (m.op === 'wifi.scan') { setTimeout(() => send({ id: m.id, ok: true, state, networks: NETWORKS }), 1200); continue; }
        if (m.op === 'wifi.join') {
          if (state.online) { send({ id: m.id, ok: false, error: 'The panel is on a network already. Change its Wi-Fi on the panel\'s screen.' }); continue; }
          send({ id: m.id, ok: true, state });
          console.log(`the "panel" joins ${m.ssid} and closes its setup Wi-Fi`);
          setTimeout(() => sock.destroy(), 300);
          continue;
        }
        send({ id: m.id, ok: true, state });
      }
    }
  });
  sock.on('error', () => {});
});

server.listen(port, 'localhost', () => console.log(`fake panel: http://localhost:${port}/setup${closed ? ' (pairing off)' : ''}`));
