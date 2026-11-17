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
    out.push({ id: d.id, name: d.name_by_user || d.name || host, area: area.get(d.area_id) || '', origin, host, model: d.model, self: host === selfHost });
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
      return Number.isFinite(n) && n >= Number(item.min) - 1e-4 && n <= Number(item.max) + 1e-4;
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
 * - `skip`: a sign-in, whose password never leaves a panel.
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
      if (!t || t.kind !== i.kind) rows.push({ ...row, state: 'missing' });
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

/**
 * [p]'s settings, or why not, each step within [ms]: a panel that's down shows as down, the rest carry on. First
 * the token-free [hello]; the token goes only to a panel that passes it, and [p] comes back `trusted` then.
 */
export async function readPanel(p, access, fetchFn = fetch, ms = 6_000) {
  const get = async (path, headers) => {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), ms);
    try {
      const r = await fetchFn(p.origin + path, { headers, signal: c.signal });
      return { status: r.status, body: await r.json().catch(() => null) };
    } finally {
      clearTimeout(t);
    }
  };
  let hi;
  try {
    hi = await get('/manage/api/hello', {});
  } catch {
    return { ...p, trusted: false, reach: { state: 'down', why: DOWN }, settings: null };
  }
  if (!hello(p, hi.body)) return { ...p, trusted: false, reach: { state: 'stranger', why: STRANGER }, settings: null };
  try {
    const r = await get('/manage/api/settings', { Authorization: 'Bearer ' + access });
    const reach = reachability(r.status, r.body);
    return { ...p, trusted: true, reach, settings: reach.state === 'up' ? r.body : null };
  } catch {
    return { ...p, trusted: true, reach: { state: 'down', why: DOWN }, settings: null };
  }
}

/**
 * A panel's answer to reading its settings, as its row says it. A panel on a version from before this page
 * sends no CORS headers, so the browser can't read it at all: that reads as down ([readPanel]).
 */
export function reachability(status, body) {
  if (status === 200 && body && body.api >= 1) return { state: 'up', version: body.version };
  return { state: 'refused', why: (body && body.problem) || 'It refused (' + status + ').' };
}
