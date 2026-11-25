// What the parity harness keeps out of its fixtures and references: tokens, the HA origin, IPs, MACs,
// serials, locations, and any word on the local denylist (person names, addresses).
//
// Touches: reads the denylist file only (VITRO_PARITY_DENY, default ~/.t3/glassworks-m1/vitro-parity/deny.txt,
// one word or phrase per line, never committed). Pure functions otherwise.
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** Personal content (playlists, what's playing, artwork links): dropped from every state. */
const PRIVATE_LIST = /^(source_list|sound_mode_list|media_playlist|media_title|media_artist|media_album_name|media_album_artist|media_series_title|app_name|entity_picture)$/;
/** Attribute keys that can hold a device-unique or private value. Dropped from every state. */
const PRIVATE_KEY = /(^|_)(ip|ips|ip_address|ipv4|ipv6|ip_addresses|mac|mac_address|serial|serial_number|sn|token|access_token|api_key|api_keys|apikey|password|ssid|bssid|latitude|longitude|gps_accuracy|altitude|timezone|time_zone|address|street|host|hostname|url|uuid|unique_id|device_id|user_id|ieee|eui64|ext_pan_id|pan_id|network_key|entity_picture_local|id)($|_)/i;

const IPV4 = /\b(?!192\.0\.2\.|198\.51\.100\.|203\.0\.113\.)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/g;
const MAC = /\b[0-9a-f]{2}([:-])[0-9a-f]{2}(\1[0-9a-f]{2}){4}\b/gi;
/**
 * An IPv6 literal: eight groups, or a `::` shortening with at least one group, set off by anything but a letter,
 * digit or colon (so `::before`, `std::map` and a clock's `10:08:00` aren't). The documentation prefix
 * (2001:db8::/32, RFC 3849) stays, as RFC 5737's IPv4 ranges do.
 */
const IPV6 = /(?<![0-9a-z:])(?!2001:0?db8:)(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?|::[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})(?![0-9a-z:])/gi;
/**
 * A 6- or 12-hex-digit run mixing digits and letters, set off by anything but a letter or digit, as device names
 * and hostnames carry the end of a MAC ("airdot-4c83f8", "AirDot_a1b2c3d4e5f6": `_` and `-` separate it too). Not
 * a CSS colour (`#34d399`). ParityHarnessSelfTest's MAC_FRAGMENT is the same pattern.
 */
const MAC_FRAGMENT = /(?<![#0-9a-z])(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])(?:[0-9a-f]{6}|[0-9a-f]{12})(?![0-9a-z])/i;

/** Whether [text] holds a MAC fragment (see MAC_FRAGMENT). */
export function hasMacFragment(text) {
  return MAC_FRAGMENT.test(String(text));
}
const JWT = /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/g;
const TOKEN_PARAM = /([?&](token|access_token|authSig)=)[^&"'\s]+/g;

/** Text with the origin, token, IPs (but RFC 5737 and RFC 3849), MACs, JWTs and token parameters replaced. */
export function scrubText(text, creds) {
  let s = String(text);
  if (creds) {
    s = s.split(creds.token).join('<token>').split(creds.origin).join('');
    s = s.split(new URL(creds.origin).host).join('homeassistant.local');
  }
  return s.replace(JWT, '<token>').replace(TOKEN_PARAM, '$1redacted').replace(MAC, '00:00:5e:00:53:00').replace(IPV6, '2001:db8::1').replace(IPV4, (m, a, b, c, d) => {
    // Version-like strings ("1.2.3.4") stay; only plausible addresses go.
    return [a, b, c, d].every((p) => +p <= 255) ? '192.0.2.1' : m;
  });
}

/** [v] with private keys dropped at every depth, objects inside arrays included. */
function dropPrivateKeys(v) {
  if (Array.isArray(v)) return v.map(dropPrivateKeys);
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.entries(v).filter(([k]) => !PRIVATE_KEY.test(k) && !PRIVATE_LIST.test(k)).map(([k, x]) => [k, dropPrivateKeys(x)]));
  }
  return v;
}

/**
 * Compressed states ({id: {s, a, lc, lu}}) without private attributes or context ids. keepPictures keeps
 * entity_picture (its token parameter still redacted), for a local corpus that never leaves the computer; synthetic
 * keeps every attribute (the fake HA's made-up fixture states), with only the text scrub applied.
 */
export function scrubStates(states, creds, { keepPictures = false, synthetic = false } = {}) {
  const out = {};
  for (const [id, st] of Object.entries(states)) {
    const a = {};
    for (const [k, v] of Object.entries(st.a || {})) {
      if (keepPictures && k === 'entity_picture') { a[k] = v; continue; }
      // A made-up fixture's attributes (the fake HA's) are kept whole: no one's media or ids are in them.
      if (synthetic) { a[k] = v; continue; }
      if (PRIVATE_KEY.test(k) || PRIVATE_LIST.test(k)) continue;
      a[k] = dropPrivateKeys(v);
    }
    out[id] = { s: st.s, a, ...(st.lc != null ? { lc: st.lc } : {}), ...(st.lu != null ? { lu: st.lu } : {}) };
  }
  return JSON.parse(scrubText(JSON.stringify(out), creds));
}

/** Entity ids a config mentions: string values that are existing entities, and ids inside templates. */
export function referencedEntities(config, states) {
  const ids = new Set();
  const ID = /\b([a-z_]+\.[a-z0-9_]+)\b/g;
  const walk = (o) => {
    if (Array.isArray(o)) o.forEach(walk);
    else if (o && typeof o === 'object') Object.values(o).forEach(walk);
    else if (typeof o === 'string') for (const m of o.matchAll(ID)) if (states[m[1]]) ids.add(m[1]);
  };
  walk(config);
  // A plant names its sensors in its `sensors` attribute: the plant-status card shows their states.
  for (const id of [...ids]) {
    const sensors = states[id]?.attributes?.sensors ?? states[id]?.a?.sensors;
    if (sensors && typeof sensors === 'object') Object.values(sensors).forEach(walk);
  }
  return ids;
}

function denylist() {
  const p = process.env.VITRO_PARITY_DENY || join(homedir(), '.t3', 'glassworks-m1', 'vitro-parity', 'deny.txt');
  if (!existsSync(p)) return [];
  return readFileSync(p, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
}

/**
 * A whole-word, any-case matcher for a denylisted [word]. Unicode-aware: JavaScript's `\b` only knows ASCII word
 * characters, so `José` would never match `José is home` with it (the guard would fail open). Not inside a longer
 * word either way: `Max` doesn't match in `Maxwell`.
 */
export function denyPattern(word) {
  const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?<![\\p{L}\\p{N}_])${escaped}(?![\\p{L}\\p{N}_])`, 'iu');
}

/** Fails when [text] contains a denied word (whole word, any case): the file is not written. */
export function checkDenylist(text, what) {
  for (const w of denylist()) {
    if (denyPattern(w).test(text)) throw new Error(`${what} would contain a denied word (from the denylist): not written`);
  }
}

/**
 * What in [text] (a card's drawn text, its rendered templates) must not reach a committed screenshot: an IPv4
 * address outside RFC 5737, a MAC, a token, the HA origin or host, a denylisted word. The PNG shows what the JSON
 * scrubs, so this runs on the raw text before anything is written. Returns the kinds found (never the values).
 */
export function findPrivate(text, creds) {
  const s = String(text);
  const found = [];
  const ips = [...s.matchAll(new RegExp(IPV4.source, 'g'))].filter((m) => m.slice(1, 5).every((p) => +p <= 255));
  if (ips.length) found.push('an IPv4 address');
  if (new RegExp(IPV6.source, 'i').test(s)) found.push('an IPv6 address');
  if (new RegExp(MAC.source, 'i').test(s)) found.push('a MAC address');
  if (MAC_FRAGMENT.test(s)) found.push('a MAC fragment');
  if (new RegExp(JWT.source).test(s) || new RegExp(TOKEN_PARAM.source).test(s)) found.push('a token');
  if (creds && (s.includes(creds.token) || s.includes(creds.origin) || s.includes(new URL(creds.origin).host))) found.push("Home Assistant's address or token");
  for (const w of denylist()) {
    if (denyPattern(w).test(s)) { found.push('a denylisted word'); break; }
  }
  return found;
}

/**
 * Recorded graph calls (wsdata.mjs) without private attributes: every state's `a` object in a history answer
 * (`history/stream` events and `history/history_during_period` results) loses the keys scrubStates drops. Requests,
 * statistics and metadata carry no attributes and stay as they are.
 */
export function scrubGraphCalls(calls) {
  const scrubA = (states) => {
    if (!states || typeof states !== 'object') return states;
    return Object.fromEntries(Object.entries(states).map(([id, list]) => [id, Array.isArray(list)
      ? list.map((st) => (st && typeof st === 'object' && st.a && typeof st.a === 'object'
        ? { ...st, a: Object.fromEntries(Object.entries(st.a).filter(([k]) => !PRIVATE_KEY.test(k) && !PRIVATE_LIST.test(k)).map(([k, v]) => [k, dropPrivateKeys(v)])) }
        : st))
      : list]));
  };
  // The logbook's rows keep what HA draws their cause from (the context's domain, event type, entity and name) but
  // not who or which run it was: the user and context ids stay out.
  const LOGBOOK_IDS = new Set(['context_user_id', 'context_id', 'context_parent_id']);
  const scrubLogbook = (ev) => (ev && Array.isArray(ev.events)
    ? { ...ev, events: ev.events.map((row) => (row && typeof row === 'object'
      ? Object.fromEntries(Object.entries(row).filter(([k]) => !LOGBOOK_IDS.has(k)))
      : row)) }
    : ev);
  // A to-do item's uid is an id unique to one HA: `item-1`, `item-2`, ... in the order HA sent them.
  const scrubTodo = (calls) => {
    const uids = new Map();
    const syn = (u) => { if (!uids.has(u)) uids.set(u, `item-${uids.size + 1}`); return uids.get(u); };
    return (ev) => (ev && Array.isArray(ev.items)
      ? { ...ev, items: ev.items.map((it) => (it && typeof it === 'object' && it.uid != null ? { ...it, uid: syn(it.uid) } : it)) }
      : ev);
  };
  return calls.map((c) => {
    if (c.request?.type === 'logbook/event_stream') return { ...c, events: c.events.map(scrubLogbook) };
    if (c.request?.type === 'todo/item/subscribe') return { ...c, events: c.events.map(scrubTodo()) };
    if (!String(c.request?.type).startsWith('history/')) return c;
    return {
      ...c,
      ...(c.result && typeof c.result === 'object' ? { result: scrubA(c.result) } : {}),
      events: c.events.map((ev) => (ev && ev.states ? { ...ev, states: scrubA(ev.states) } : ev)),
    };
  });
}
