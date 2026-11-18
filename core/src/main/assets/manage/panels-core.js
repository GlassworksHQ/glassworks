/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The All panels page's logic, without the page (#466): which of Home Assistant's devices are panels, which of
// them may have the token, and what copying one panel's settings to another would change. panels.js draws it;
// node --test checks it.

/** The Manage page's port: Home Assistant's "Visit" link on a panel's device page points there. */
export const PORT = '8099';

/**
 * HA's login page for this page, as a browser-side OAuth client (HA's IndieAuth: same origin, nothing fetched),
 * with PKCE: [challenge] is [pkceChallenge] of the verifier the tab keeps for the code's exchange.
 */
export function authorizeUrl(ha, origin, state, challenge) {
  const q = new URLSearchParams({ response_type: 'code', client_id: origin + '/', redirect_uri: origin + '/panels', state,
    code_challenge: challenge, code_challenge_method: 'S256' });
  return ha.replace(/\/+$/, '') + '/auth/authorize?' + q;
}

/** [bytes] as base64url without padding (RFC 7636's encoding). */
export function base64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** PKCE's S256 challenge for [verifier]: base64url(SHA-256(verifier)). */
export function pkceChallenge(verifier) {
  return base64url(sha256(new TextEncoder().encode(verifier)));
}

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

/**
 * SHA-256 of [data] (a Uint8Array). The page is plain http on the LAN, which browsers don't count as a secure
 * context, so `crypto.subtle` isn't there for PKCE.
 */
export function sha256(data) {
  const n = data.length;
  const padded = new Uint8Array(((n + 9 + 63) >> 6) << 6);
  padded.set(data);
  padded[n] = 0x80;
  const dv = new DataView(padded.buffer);
  dv.setUint32(padded.length - 8, Math.floor(n / 0x20000000));
  dv.setUint32(padded.length - 4, (n << 3) >>> 0);
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  const rotr = (x, r) => (x >>> r) | (x << (32 - r));
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i]);
  return out;
}

/** A device's "Visit" link as a panel's Manage page (`http://<host>:8099`), or null. */
export function manageOrigin(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' || u.port !== PORT || u.username || u.password || (u.pathname !== '/' && u.pathname !== '')) return null;
    return u.origin;
  } catch {
    return null;
  }
}

/**
 * Whether [hostname] (a URL's, IPv6 in brackets) is on the local network: a private, loopback or link-local IP
 * address, `localhost` or an mDNS `.local` name. At most what the panel's API takes for a page's Origin
 * (`ManageApi.localOrigin`); it refuses a few forms that one accepts (a bare `.local`, `fec0::/10`, IPv4-mapped or
 * written-out IPv6), which only makes it stricter. Names aren't resolved. A panel elsewhere gets no question and no token: over the internet, the
 * token would cross in clear text.
 */
export function localHost(hostname) {
  const h = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (h === 'localhost' || (h.endsWith('.local') && h.length > 6)) return true;
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    if (v4.slice(1).some((x) => Number(x) > 255)) return false;
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  if (!h.includes(':') || !/^[0-9a-f:]+$/.test(h)) return false;
  if (h === '::1') return true;
  const first = h.startsWith('::') ? 0 : parseInt(h.split(':')[0], 16);
  return (first & 0xffc0) === 0xfe80 || (first & 0xfe00) === 0xfc00;
}

/**
 * The panels among Home Assistant's [devices] (`config/device_registry/list`): ESPHome devices ([entries],
 * `config_entries/get`) whose "Visit" link is a Manage page and that have a model, with their name and area
 * (`config/area_registry/list`). [selfHost]: this page's host, which marks the panel the page is on. This panel
 * first, then by area and name. A panel here gets the token only once its [hello] matches ([readPanel]).
 */
export function panelsFrom(devices, areas, selfHost, entries) {
  const area = new Map((areas || []).map((a) => [a.area_id, a.name]));
  const esphome = new Set((entries || []).filter((e) => e.domain === 'esphome').map((e) => e.entry_id));
  const seen = new Set();
  const out = [];
  for (const d of devices || []) {
    if (d.disabled_by || d.entry_type === 'service' || !d.model) continue;
    if (!(d.config_entries || []).some((id) => esphome.has(id))) continue;
    const origin = manageOrigin(d.configuration_url);
    if (!origin || seen.has(origin)) continue;
    seen.add(origin);
    const host = new URL(origin).host;
    out.push({ id: d.id, name: d.name_by_user || d.name || host, area: area.get(d.area_id) || '', origin, host, model: d.model,
      local: localHost(new URL(origin).hostname), self: host === selfHost });
  }
  return out.sort((a, b) => (b.self - a.self) || a.area.localeCompare(b.area) || a.name.localeCompare(b.name));
}

/** "Screen › Turn off when idle": a setting's place, from its section's title and its key. */
export function label(section, key) {
  return section.title + ' › ' + (key === 'on' ? 'On/off' : key.split('/').join(' › '));
}

/** Whether [item], as the other panel has it, can take [value]: an option it lists, a choice, a number in range. */
export function accepts(item, value) {
  switch (item.kind) {
    case 'choice': return (item.options || []).some((o) => o[0] === value);
    case 'duration': return (item.choices || []).includes(Number(value));
    case 'slider': {
      const n = Number(value);
      const [min, max, step] = [Number(item.min), Number(item.max), Number(item.step)];
      if (!Number.isFinite(n) || n < min - 1e-4 || n > max + 1e-4) return false;
      // On the other panel's steps too: it would snap anything else to its nearest step, and the copy would be wrong.
      if (!(step > 0)) return true;
      const k = (n - min) / step;
      return Math.abs(k - Math.round(k)) < 1e-4;
    }
    case 'switch': return value === 'on' || value === 'off';
    default: return true;
  }
}

/**
 * What copying [from]'s settings to [to] would do, both as `/manage/api/settings` answers. Each of [from]'s
 * settings is one of:
 * - `send`: [to] has it, with another value it can take;
 * - `missing`: [to]'s version or hardware doesn't have it, so it is never sent;
 * - `cant`: [to] has it, but not that value (an option its version lacks), so it is never sent;
 * - `skip`: a sign-in, whose password never leaves a panel;
 * - `own`: the panel's own connection (its Home Assistant address, `copy: false`), never copied.
 * [same] counts the ones that already match. Nothing on [to] that [from] lacks is touched.
 */
export function plan(from, to) {
  const theirs = new Map();
  for (const s of to.sections) for (const i of s.items) theirs.set(s.id + '\n' + i.key, i);
  const rows = [];
  let same = 0;
  for (const s of from.sections) {
    for (const i of s.items) {
      const row = { section: s.id, key: i.key, label: label(s, i.key), from: i.shown ?? '' };
      const t = theirs.get(s.id + '\n' + i.key);
      // The panel's own connection (its Home Assistant address): never copied (the 2026-11-18 panel run signed a dashboard out).
      if (i.copy === false || (t && t.copy === false)) rows.push({ ...row, state: 'own' });
      else if (!t || t.kind !== i.kind) rows.push({ ...row, state: 'missing' });
      else if (i.kind === 'sign-in') rows.push({ ...row, state: 'skip' });
      else if (t.value === i.value) same++;
      else if (!accepts(t, i.value)) rows.push({ ...row, state: 'cant', to: t.shown ?? '' });
      else rows.push({ ...row, state: 'send', value: i.value, to: t.shown ?? '' });
    }
  }
  return { rows, same };
}

/** A panel's answer to a change, as the page says it: done, still applying, asks first, or why not. */
export function outcome(status, body) {
  if (status === 200) return { state: 'done', restart: !!(body && body.restart) };
  if (status === 202) return { state: 'pending' };
  if (status === 409 && body && body.ask) return { state: 'ask', ask: body.ask };
  if (status === 404 && body && body.missing) return { state: 'failed', why: "That panel doesn't have this setting now." };
  return { state: 'failed', why: (body && body.problem) || "That panel didn't take it (" + status + ').' };
}

/**
 * Whether [p]'s token-free hello (`/manage/api/hello`) says it is the Glassworks panel Home Assistant lists at its
 * address: the same model as the registry's. Only then does the page send it the token.
 */
export function hello(p, body) {
  return !!(body && body.glassworks === true && typeof body.model === 'string' && body.model === p.model);
}

const DOWN = "Can't reach it. It may be off, or on a version from before this page: update it.";
const STRANGER = "It doesn't answer as the panel Home Assistant lists at this address, so it gets no sign-in.";
const REMOTE = "Its address isn't on your network, so the page doesn't contact it or send it a sign-in.";
const UNCHECKED = "It answered, but didn't finish checking your sign-in with its Home Assistant in time. Try again in a moment.";

/**
 * How long the settings read may take. Longer than hello's: the panel asks its own Home Assistant first, which can
 * take seconds when that address is slow (a stalled IPv6 connect, seen on the Pixel Tablet on 2026-11-18).
 */
export const SETTINGS_MS = 15_000;

/**
 * How long a write may take: longer than the panel's own wait for a save (`ManageServer.SAVE_WAIT_MS`, 20 s, after
 * which it answers "still applying"), plus its sign-in check and the answer (CodeRabbit on #488).
 */
export const WRITE_MS = 30_000;

/** How many times a call that answers 503 with Retry-After is made in all, and the longest it waits in all, in s. */
export const TRIES = 3;
export const MAX_WAIT_S = 5;

/**
 * [call] again while it answers 503 with Retry-After (a panel still asking Home Assistant about other sign-ins),
 * at most [TRIES] times in all, waiting what it asks, but [MAX_WAIT_S] s at most in all per request: one busy panel
 * can't hold the page (it waits for every panel) much past its own timeouts. A copy of N settings makes N requests,
 * so it can wait up to N × [MAX_WAIT_S] s. The last answer otherwise.
 */
export async function retrying(call, sleep = (ms) => new Promise((ok) => setTimeout(ok, ms)), stop = () => false) {
  let left = MAX_WAIT_S;
  let r;
  for (let i = 1; ; i++) {
    // Signed out during the wait: no further try; the last answer stands (CodeRabbit on #488).
    if (i > 1 && stop()) return r;
    r = await call();
    const header = r.status === 503 && r.headers ? r.headers.get('Retry-After') : null;
    const after = header == null || String(header).trim() === '' ? NaN : Number(header);
    if (i >= TRIES || !Number.isFinite(after) || after < 0 || left <= 0) return r;
    const wait = Math.min(after, left);
    left -= wait;
    await sleep(wait * 1000);
  }
}

/**
 * Whether the page may send [p] its token or a change: on the local network, trusted ([readPanel] passed its hello)
 * and with settings it read. Every path that sends the token checks this, not only [readPanel].
 */
export function usable(p) {
  return !!(p && p.local === true && p.trusted === true && p.settings);
}

/**
 * [p]'s settings, or why not, each step within [ms]: a panel that's down shows as down, the rest carry on. Nothing
 * for a panel off the local network ([localHost]). Then the token-free [hello]; the token goes only to a panel that
 * passes it, and [p] comes back `trusted` then. A busy panel's 503 is tried again ([retrying]).
 */
export async function readPanel(p, access, fetchFn = fetch, ms = 6_000, sleep = undefined, settingsMs = SETTINGS_MS) {
  if (p.local !== true) return { ...p, trusted: false, reach: { state: 'remote', why: REMOTE }, settings: null };
  const get = (path, headers, timeout) => retrying(() => timed(fetchFn, p.origin + path, { headers }, timeout), sleep);
  let hi;
  try {
    // Hello never answers 503 (it asks Home Assistant nothing), so only the settings read can wait: one read stays
    // within its timeouts plus [MAX_WAIT_S] s.
    hi = await get('/manage/api/hello', {}, ms);
  } catch {
    return { ...p, trusted: false, reach: { state: 'down', why: DOWN }, settings: null };
  }
  if (!hello(p, hi.body)) return { ...p, trusted: false, reach: { state: 'stranger', why: STRANGER }, settings: null };
  // It answered hello, so from here on it is reachable: whatever goes wrong is said as what it is, never Down.
  try {
    const r = await get('/manage/api/settings', { Authorization: 'Bearer ' + access }, settingsMs);
    const reach = reachability(r.status, r.body);
    return { ...p, trusted: true, reach, settings: reach.state === 'up' ? r.body : null };
  } catch {
    return { ...p, trusted: true, reach: { state: 'unchecked', why: UNCHECKED }, settings: null };
  }
}

/**
 * A panel's answer to reading its settings, as its row says it. A panel on a version from before this page
 * sends no CORS headers, so the browser can't read it at all: that reads as down ([readPanel]).
 */
export function reachability(status, body) {
  if (status === 200 && body && body.api >= 1) return { state: 'up', version: body.version };
  const problem = body && typeof body.problem === 'string' ? body.problem : '';
  // 401 means the panel's Home Assistant didn't confirm the token: unknown, expired, or Home Assistant didn't answer it.
  if (status === 401) return { state: 'refused', why: "This panel couldn't confirm your sign-in with its Home Assistant." + (problem ? ' It said: ' + problem : '') };
  if (status === 503) return { state: 'busy', why: problem || 'It is busy asking Home Assistant about other sign-ins. Try again in a moment.' };
  return { state: 'refused', why: problem || 'It refused (' + status + ').' };
}

/**
 * One setting to [p] (`POST /manage/api/settings`), as [outcome] says it. Each try gives up after [ms] ([WRITE_MS]):
 * the panel checks the sign-in and may wait for the save first. A write that timed out may still have landed, so it says so.
 */
export async function writeSetting(p, access, fields, fetchFn = fetch, ms = WRITE_MS, sleep = undefined, stop = () => false) {
  // Checked here too, not only by the caller: nothing else stands between a later caller and the token (CodeRabbit on #488).
  if (!usable(p)) return { state: 'failed', why: 'Not sent: ' + p.name + " isn't a trusted panel on your network now." };
  try {
    const res = await retrying(() => timed(fetchFn, p.origin + '/manage/api/settings', {
      method: 'POST', headers: { Authorization: 'Bearer ' + access }, body: new URLSearchParams(fields),
    }, ms), sleep, stop);
    return outcome(res.status, res.body);
  } catch (e) {
    if (e && e.name === 'AbortError') return { state: 'failed', unreachable: true, why: p.name + " didn't answer in time. The setting may have changed: check its page." };
    return { state: 'failed', unreachable: true, why: "Can't reach " + p.name + '.' };
  }
}

/**
 * [rows] (from [plan]) to [p], one at a time, as a Map of row key to [writeSetting]'s outcome. [answers]: each row's
 * one-shot question id, when the person agreed. Stops at the first write [p] didn't answer: the rest say they weren't sent.
 * [stop]: asked before each write; true ends the copy there (the person signed out).
 */
export async function copyRows(p, access, rows, answers = null, fetchFn = fetch, ms = WRITE_MS, sleep = undefined, stop = () => false) {
  const result = new Map();
  for (let i = 0; i < rows.length; i++) {
    // Signed out meanwhile: nothing more is sent with the token.
    if (stop()) break;
    const r = rows[i];
    const k = r.section + '\n' + r.key;
    const id = answers && answers.get(k);
    const o = await writeSetting(p, access, { section: r.section, key: r.key, value: r.value, ...(id ? { answer: id } : {}) }, fetchFn, ms, sleep, stop);
    result.set(k, o);
    if (o.unreachable) {
      for (const rest of rows.slice(i + 1)) result.set(rest.section + '\n' + rest.key, { state: 'failed', why: 'Not sent: ' + p.name + ' stopped answering.' });
      break;
    }
  }
  return result;
}

/**
 * [fetchFn] of [url] as `{ status, headers, body }` (the body as JSON, or null), within [ms] in all: the timer stays
 * armed until the body is read, so a panel that sends headers and then stalls can't hold the page (CodeRabbit on #488).
 */
async function timed(fetchFn, url, init, ms) {
  const c = new AbortController();
  const t = setTimeout(() => c.abort(), ms);
  const aborted = new Promise((_, no) => c.signal.addEventListener('abort', () => no(new DOMException('The request took too long.', 'AbortError'))));
  aborted.catch(() => {});
  try {
    // No redirects: one could take the request to a host off the local network, past every check above (CodeRabbit on #488).
    const r = await Promise.race([fetchFn(url, { ...init, redirect: 'error', signal: c.signal }), aborted]);
    const body = await Promise.race([r.json().catch((e) => { if (e && e.name === 'AbortError') throw e; return null; }), aborted]);
    return { status: r.status, headers: r.headers, body };
  } finally {
    clearTimeout(t);
  }
}

/**
 * Home Assistant's `/auth/token` with [form], as its JSON, within [ms] in all (the request and its body): a stalled
 * answer can't leave a copy's controls disabled (CodeRabbit on #488). Throws an Error whose message the page shows.
 */
export async function tokenRequest(ha, form, fetchFn = fetch, ms = TOKEN_MS) {
  let r;
  try {
    r = await timed(fetchFn, ha + '/auth/token', { method: 'POST', body: new URLSearchParams(form) }, ms);
  } catch (e) {
    throw new Error(e && e.name === 'AbortError' ? "Home Assistant didn't answer in time. Try again." : "Can't reach Home Assistant.");
  }
  if (r.status < 200 || r.status >= 300 || !r.body) throw new Error('Home Assistant refused the sign-in. Sign in again.');
  return r.body;
}

/** How long a token exchange or refresh may take. */
export const TOKEN_MS = 15_000;

/** Asks Home Assistant at [ha] to drop [refresh] and every access token made from it. True only when it said yes. */
export async function revokeAt(ha, refresh, fetchFn = fetch, ms = REVOKE_MS) {
  // Its answer's status is all that counts: the timer covers the wait for it, not the (empty) body.
  const c = new AbortController();
  let t;
  const late = new Promise((_, no) => { t = setTimeout(() => { c.abort(); no(new DOMException('Home Assistant took too long.', 'AbortError')); }, ms); });
  try {
    const r = await Promise.race([fetchFn(ha + '/auth/revoke', { method: 'POST', body: new URLSearchParams({ token: refresh }), redirect: 'error', signal: c.signal }), late]);
    return r.status >= 200 && r.status < 300;
  } catch {
    return false;
  } finally {
    clearTimeout(t);
  }
}

/** How long sign-out waits for Home Assistant to take the revoke before it says it may not have. */
export const REVOKE_MS = 10_000;

/**
 * The tab's kept Home Assistant sign-in ([raw], as stored) when it was made for this Home Assistant ([ha]) and this
 * Manage session ([session]), else null. A sign-out from the Manage page ends the session, so whoever signs in next in
 * the same tab can't use the last administrator's sign-in (CodeRabbit on #488).
 */
export function storedFor(raw, ha, session) {
  try {
    const t = JSON.parse(raw || 'null');
    return t && t.ha === ha && typeof t.session === 'string' && t.session !== '' && t.session === session ? t : null;
  } catch {
    return null;
  }
}

/**
 * A fresh Home Assistant access token from this panel, out of the sign-in it keeps with this Manage session
 * (`POST /manage/ha/access`, same origin, with the session's [csrf]): one sign-in for the page and All panels (#466
 * panel run). `{ access_token, expires_in }`, or null when the panel keeps none (a code sign-in) or HA won't renew it.
 */
export async function panelAccess(csrf, fetchFn = fetch, ms = TOKEN_MS) {
  try {
    const r = await timed(fetchFn, '/manage/ha/access', { method: 'POST', body: new URLSearchParams({ csrf }) }, ms);
    return r.status === 200 && r.body && typeof r.body.access_token === 'string' ? r.body : null;
  } catch {
    return null;
  }
}

/** Ends the sign-in this panel keeps for All panels (`POST /manage/ha/forget`): it revokes it in Home Assistant. */
export async function panelForget(csrf, fetchFn = fetch, ms = REVOKE_MS) {
  try {
    const r = await timed(fetchFn, '/manage/ha/forget', { method: 'POST', body: new URLSearchParams({ csrf }) }, ms);
    return r.status === 200;
  } catch {
    return false;
  }
}
