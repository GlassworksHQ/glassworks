// Home Assistant access for the Vitro parity harness: where the credentials live, and a small WebSocket client.
//
// Touches: reads the credentials file (never prints, logs or writes the token or the origin); opens one
// WebSocket to Home Assistant. Read-only unless a caller sends a write command (capture.mjs saves only the
// `vitro-harness` dashboard).
import { readFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** The credentials file: {"origin": "...", "token": "..."}. VITRO_PARITY_HA overrides the default path. */
export function haCredentialsPath() {
  return process.env.VITRO_PARITY_HA || join(homedir(), '.t3', 'glassworks-m1', 'vitro-parity', 'ha.json');
}

export function loadCredentials() {
  const p = haCredentialsPath();
  if (!existsSync(p)) return null;
  const { origin, token } = JSON.parse(readFileSync(p, 'utf8'));
  if (!origin || !token) throw new Error(`${p} needs "origin" and "token"`);
  // One canonical form (scheme://host[:non-default port], no path): Chromium's location.origin compares with it.
  let url;
  try {
    url = new URL(origin);
  } catch {
    throw new Error(`${p}: "origin" isn't a URL`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error(`${p}: "origin" must be http or https`);
  // Like HA's own frontend, the harness talks to the HA it's pointed at, over that origin's scheme. Over plain
  // http the token travels unencrypted, as it does for every browser on that origin: say so, don't refuse.
  if (url.protocol !== 'https:') {
    console.error(`warning: the Home Assistant origin in ${p} is not https: the access token travels unencrypted to it, as it does for HA's own frontend there`);
  }
  return { origin: url.origin, token };
}

/** Removes the origin and token from text before it reaches a log or a file. */
export function redact(text, creds) {
  let s = String(text);
  if (creds) {
    s = s.split(creds.token).join('<token>').split(creds.origin).join('<ha>');
    const host = new URL(creds.origin).host;
    s = s.split(host).join('<ha-host>');
  }
  return s;
}

/** One authenticated WebSocket connection. `call` sends a command and resolves with its result. */
export async function connect(creds) {
  const ws = new WebSocket(creds.origin.replace(/^http/, 'ws') + '/api/websocket');
  let nextId = 1;
  const pending = new Map();
  const subs = new Map();
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('Home Assistant WebSocket: no auth within 15 s')), 15000);
    ws.onerror = () => { clearTimeout(t); reject(new Error('Home Assistant WebSocket: connection failed')); };
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.type === 'auth_required') ws.send(JSON.stringify({ type: 'auth', access_token: creds.token }));
      else if (m.type === 'auth_ok') { clearTimeout(t); resolve(); }
      else if (m.type === 'auth_invalid') { clearTimeout(t); reject(new Error('Home Assistant WebSocket: auth invalid')); }
    };
  });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.type === 'result' && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      if (m.success) resolve(m.result); else reject(new Error(`${m.error?.code}: ${m.error?.message}`));
    } else if (m.type === 'event' && subs.has(m.id)) subs.get(m.id)(m.event);
  };
  const send = (msg) => {
    const id = nextId++;
    ws.send(JSON.stringify({ id, ...msg }));
    return id;
  };
  return {
    call(msg, timeoutMs = 20000) {
      return new Promise((resolve, reject) => {
        const id = send(msg);
        const t = setTimeout(() => { pending.delete(id); reject(new Error(`timeout: ${msg.type}`)); }, timeoutMs);
        pending.set(id, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
      });
    },
    /** render_template once: subscribes, takes the first result, unsubscribes. */
    async renderTemplate(template, variables, timeoutMs = 10000) {
      return new Promise((resolve) => {
        const id = send({ type: 'render_template', template, ...(variables ? { variables } : {}), report_errors: false });
        const t = setTimeout(() => { subs.delete(id); resolve({ error: 'timeout' }); }, timeoutMs);
        pending.set(id, { resolve: () => {}, reject: (e) => { clearTimeout(t); subs.delete(id); resolve({ error: String(e.message) }); } });
        subs.set(id, (ev) => {
          clearTimeout(t);
          subs.delete(id);
          send({ type: 'unsubscribe_events', subscription: id });
          resolve(ev.error ? { error: ev.error } : { result: ev.result });
        });
      });
    },
    close() { try { ws.close(); } catch {} },
  };
}
