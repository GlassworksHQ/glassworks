/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The All panels page's logic, without the page (#466): which of Home Assistant's devices are panels, and
// what copying one panel's settings to another would change. panels.js draws it; node --test checks it.

/** The Manage page's port: Home Assistant's "Visit" link on a panel's device page points there. */
export const PORT = '8099';

/** HA's login page for this page, as a browser-side OAuth client (HA's IndieAuth: same origin, nothing fetched). */
export function authorizeUrl(ha, origin, state) {
  const q = new URLSearchParams({ response_type: 'code', client_id: origin + '/', redirect_uri: origin + '/panels', state });
  return ha.replace(/\/+$/, '') + '/auth/authorize?' + q;
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
 * The panels among Home Assistant's [devices] (`config/device_registry/list`): each one whose "Visit" link is a
 * Manage page, with its name and area (`config/area_registry/list`). [selfHost]: this page's host, which marks
 * the panel the page is on. This panel first, then by area and name.
 */
export function panelsFrom(devices, areas, selfHost) {
  const area = new Map((areas || []).map((a) => [a.area_id, a.name]));
  const seen = new Set();
  const out = [];
  for (const d of devices || []) {
    if (d.disabled_by || d.entry_type === 'service') continue;
    const origin = manageOrigin(d.configuration_url);
    if (!origin || seen.has(origin)) continue;
    seen.add(origin);
    const host = new URL(origin).host;
    out.push({ id: d.id, name: d.name_by_user || d.name || host, area: area.get(d.area_id) || '', origin, host, self: host === selfHost });
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
 * A panel's answer to reading its settings, as its row says it. A panel on a version from before this page
 * sends no CORS headers, so the browser can't read it at all: that reads as down ([readPanel] in panels.js).
 */
export function reachability(status, body) {
  if (status === 200 && body && body.api >= 1) return { state: 'up', version: body.version };
  return { state: 'refused', why: (body && body.problem) || 'It refused (' + status + ').' };
}
