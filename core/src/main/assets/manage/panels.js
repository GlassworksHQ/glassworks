/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The All panels page (#466). The browser signs in to Home Assistant itself and keeps the token in this tab
// (sessionStorage). With it, the page lists the panels from Home Assistant's device registry, asks each one
// for its settings (each panel checks the token with its own Home Assistant), opens a panel's own Manage page
// signed in, and copies settings from one panel to another. No panel sees another's requests. The token goes
// only to a device Home Assistant lists as a Glassworks panel that answers as one first (readPanel).

import { authorizeUrl, base64url, panelsFrom, pkceChallenge, plan, readPanel, revokeAt, usable, writeSetting } from './panels-core.js';

const root = document.getElementById('gw-panels');
const ha = (root.dataset.ha || '').replace(/\/+$/, '');
const listCard = document.getElementById('gw-list');
const copyCard = document.getElementById('gw-copy');
const clientId = location.origin + '/';
const KEY = 'gw-ha';
const STATE = 'gw-ha-state';
const VERIFIER = 'gw-ha-verifier';

/** Every panel found, with what it answered: { ...panel, reach, settings }. */
let panels = [];

// ------------------------------------------------------------------ the page's bits

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === false || v == null) continue;
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k);
  return e;
}

const dot = (tone) => h('i', { class: 'dot ' + tone });
const chip = (tone, word) => h('span', { class: 'chip' }, dot(tone), word);
const row = (title, line, ...right) => h('div', { class: 'row static' }, h('div', {}, h('b', {}, title), line ? h('span', {}, line) : null), ...right);
const head = (title, ...right) => h('div', { class: 'lh' }, h('b', {}, title), ...right);

function say(card, title, text, ...buttons) {
  card.replaceChildren(head(title), h('div', { class: 'row static note' }, h('span', {}, text), ...buttons));
}

// ------------------------------------------------------------------ Home Assistant's sign-in, in this tab

function load(k) { try { return sessionStorage.getItem(k); } catch { return null; } }
function keep(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* private mode: sign in again */ } }

function stored() {
  try {
    const t = JSON.parse(load(KEY) || 'null');
    return t && t.ha === ha ? t : null;
  } catch {
    return null;
  }
}

async function tokenCall(form) {
  let r;
  try {
    r = await fetch(ha + '/auth/token', { method: 'POST', body: new URLSearchParams(form) });
  } catch {
    throw new Error("Can't reach Home Assistant.");
  }
  if (!r.ok) throw new Error('Home Assistant refused the sign-in. Sign in again.');
  return r.json();
}

/** A current access token, renewed from the refresh token a minute before it runs out; null when signed out. */
async function token() {
  const t = stored();
  if (!t) return null;
  if (Date.now() < t.until - 60_000) return t.access;
  const j = await tokenCall({ grant_type: 'refresh_token', refresh_token: t.refresh, client_id: clientId });
  keep(KEY, JSON.stringify({ ...t, access: j.access_token, until: Date.now() + j.expires_in * 1000 }));
  return j.access_token;
}

/** Back from Home Assistant's login page with `?code=…&state=…`: trade the code for this tab's tokens. */
async function finishSignIn() {
  const q = new URLSearchParams(location.search);
  if (!q.has('code') && !q.has('error')) return;
  history.replaceState(null, '', location.pathname);
  const want = load(STATE);
  const verifier = load(VERIFIER);
  keep(STATE, null);
  keep(VERIFIER, null);
  if (q.has('error')) throw new Error('Sign-in cancelled.');
  if (!want || !verifier || q.get('state') !== want) throw new Error('This sign-in has expired. Start again.');
  const j = await tokenCall({ grant_type: 'authorization_code', code: q.get('code'), client_id: clientId, code_verifier: verifier });
  keep(KEY, JSON.stringify({ ha, access: j.access_token, refresh: j.refresh_token, until: Date.now() + j.expires_in * 1000 }));
}

function random(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return base64url(b);
}

/** To HA's login page, with a state and a PKCE verifier this tab keeps for the way back. */
function signIn() {
  const state = random(18);
  const verifier = random(32);
  keep(STATE, state);
  keep(VERIFIER, verifier);
  location.assign(authorizeUrl(ha, location.origin, state, pkceChallenge(verifier)));
}

/** Home Assistant drops the refresh token and every access token made from it: no panel can use them after. */
async function signOut() {
  const t = stored();
  keep(KEY, null);
  if (t && !(await revokeAt(ha, t.refresh))) return revokeFailed(t.refresh);
  start();
}

/** Home Assistant didn't confirm the revoke: the sign-in may stay active there. Say so, with a way to try again. */
function revokeFailed(refresh) {
  copyCard.hidden = true;
  say(listCard, 'Panels in Home Assistant',
    "Signed out of this page, but Home Assistant didn't confirm it ended the sign-in, so it may stay active there. Try again, or remove it in Home Assistant: your profile, Security, Refresh tokens.",
    h('button', { class: 'pill', type: 'button', onclick: async () => ((await revokeAt(ha, refresh)) ? start() : revokeFailed(refresh)) }, 'Try again'));
}

/** Home Assistant's websocket: [types] asked in turn, their results in that order. */
function ask(access, types) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(ha.replace(/^http/, 'ws') + '/api/websocket');
    const out = [];
    let left = types.length;
    const end = (f) => { clearTimeout(timer); ws.onclose = null; ws.close(); f(); };
    const timer = setTimeout(() => end(() => reject(new Error("Home Assistant didn't answer."))), 15_000);
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.type === 'auth_required') ws.send(JSON.stringify({ type: 'auth', access_token: access }));
      else if (m.type === 'auth_ok') types.forEach((type, i) => ws.send(JSON.stringify({ id: i + 1, type })));
      else if (m.type === 'auth_invalid') end(() => reject(Object.assign(new Error('Home Assistant refused the sign-in. Sign in again.'), { signIn: true })));
      else if (m.type === 'result') {
        if (!m.success) return end(() => reject(new Error('Home Assistant said no: ' + ((m.error && m.error.message) || 'unknown error') + '.')));
        out[m.id - 1] = m.result;
        if (--left === 0) end(() => resolve(out));
      }
    };
    ws.onclose = () => end(() => reject(new Error("Can't reach Home Assistant.")));
  });
}

// ------------------------------------------------------------------ the panels

const TONE = { asking: ['work', 'Asking'], up: ['ok', 'Up'], down: ['bad', 'Down'], refused: ['bad', 'Refused'], busy: ['work', 'Busy'], unchecked: ['work', 'No answer'], stranger: ['bad', 'Not a panel'], remote: ['off', 'Not on your network'] };

/** A panel's own Manage page, in a new tab, signed in there with this tab's token (the panel checks it with Home Assistant). */
async function open(p) {
  if (p.self) return location.assign('/');
  if (!usable(p)) return;
  const access = await token().catch(() => null);
  if (!access) return start();
  // A new tab, without rel: the panel there gets the token in the post anyway, so what window.opener would add (it
  // could only send this tab elsewhere; it can't read it, being another origin) gives it nothing more.
  const f = h('form', { method: 'post', action: p.origin + '/manage/ha/token', target: '_blank' },
    h('input', { type: 'hidden', name: 'token', value: access }), h('input', { type: 'hidden', name: 'next', value: '/' }));
  document.body.append(f);
  f.submit();
  f.remove();
}

function drawList(user) {
  const rows = panels.map((p) => {
    const [tone, word] = TONE[p.reach.state];
    const where = [p.area || 'No area', p.host, p.reach.version].filter(Boolean).join(' · ');
    return row(h('span', {}, p.name, p.self ? h('span', { class: 'me' }, ' · this panel') : null), p.reach.state === 'up' ? where : where + ' · ' + p.reach.why,
      chip(tone, word), h('button', { class: 'pill sm', type: 'button', disabled: p.reach.state !== 'up', onclick: () => open(p) }, 'Manage'));
  });
  listCard.replaceChildren(
    head('Panels in Home Assistant', h('span', {}, 'Signed in as ' + user + ' · ', h('button', { class: 'lk', type: 'button', onclick: signOut }, 'Sign out'))),
    ...(rows.length ? rows : [h('div', { class: 'row static note' }, h('span', {}, 'Home Assistant has no Glassworks panels. A panel shows here once Home Assistant has added it.'))]),
  );
}

// ------------------------------------------------------------------ copying settings

function options(selected, skip) {
  return panels.filter((p) => usable(p) && p !== skip).map((p) => h('option', { value: p.origin, selected: p === selected }, p.name + (p.area ? ' · ' + p.area : '')));
}

function drawCopy(fromP, toP, result) {
  // A panel that isn't usable now (a picker tampered with, or one that stopped answering as a panel): say why, send nothing.
  const bad = [fromP, toP].find((p) => p && !usable(p));
  if (bad) {
    copyCard.hidden = false;
    return copyCard.replaceChildren(head('Copy settings'), h('div', { class: 'row static note' },
      h('span', {}, copied(result, bad) + stopped(bad)), h('button', { class: 'pill', type: 'button', onclick: () => drawCopy() }, 'Start again')));
  }
  const up = panels.filter(usable);
  if (up.length < 2) {
    copyCard.hidden = true;
    return;
  }
  copyCard.hidden = false;
  fromP = fromP || up[0];
  toP = toP && toP !== fromP ? toP : up.find((p) => p !== fromP);
  const pick = (name, now, skip) => h('select', { class: 'fld', name, onchange: (e) => {
    const p = panels.find((x) => x.origin === e.target.value);
    drawCopy(name === 'from' ? p : fromP, name === 'to' ? p : toP);
  } }, options(now, skip));
  const { rows, same } = plan(fromP.settings, toP.settings);
  const send = rows.filter((r) => r.state === 'send');
  const grey = { missing: toP.name + "'s version or hardware doesn't have this. It isn't sent.", cant: toP.name + " can't take this value. It isn't sent.", skip: "Sign-ins aren't copied: their passwords never leave a panel." };
  const lines = rows.map((r) => {
    const done = result && result.get(r.section + '\n' + r.key);
    if (r.state !== 'send') return h('div', { class: 'row static quiet cp' }, h('div', {}, h('b', {}, r.label), h('span', {}, grey[r.state])));
    const now = done ? (done.state === 'done' ? 'Copied' : done.state === 'pending' ? 'Still applying' : done.state === 'ask' ? 'Asks first' : done.why) : r.to + ' → ' + r.from;
    return h('label', { class: 'row cp' + (done && done.state === 'failed' ? ' bad' : '') },
      h('input', { type: 'checkbox', name: 'c', value: r.section + '\n' + r.key, checked: !result, disabled: !!result }),
      h('div', {}, h('b', {}, r.label), h('span', {}, now)));
  });
  const asked = result ? send.filter((r) => (result.get(r.section + '\n' + r.key) || {}).state === 'ask') : [];
  const restart = result && [...result.values()].some((o) => o.restart);
  const foot = h('div', { class: 'cpf' },
    h('span', {}, copied(result, toP) + same + (same === 1 ? ' setting is' : ' settings are') + ' the same already.' + (restart ? ' ' + toP.name + ' applies some after its app restarts: open it and restart it.' : '')),
    result ? null : h('button', { class: 'pill primary', type: 'submit', disabled: !send.length }, send.length ? 'Copy to ' + toP.name : 'Nothing to copy'));
  const asks = asked.length ? h('div', { class: 'cpa' }, h('b', {}, 'These ask first on ' + toP.name),
    ...asked.map((r) => { const a = result.get(r.section + '\n' + r.key).ask; return h('p', {}, r.label + ': ' + a.title + ' ' + a.detail); }),
    h('button', { class: 'pill danger', type: 'button', onclick: () => apply(fromP, toP, asked, result) }, 'Change them too')) : null;
  const form = h('form', { onsubmit: (e) => {
    e.preventDefault();
    const picked = new Set(new FormData(e.target).getAll('c'));
    apply(fromP, toP, send.filter((r) => picked.has(r.section + '\n' + r.key)), null);
  } }, ...lines, asks, foot);
  copyCard.replaceChildren(
    head('Copy settings', h('span', {}, 'Changes only what you tick, on one panel')),
    h('div', { class: 'cph' }, h('label', {}, 'From', pick('from', fromP, null)), h('span', { class: 'arrow' }, '→'), h('label', {}, 'To', pick('to', toP, fromP))),
    form,
  );
}

/** Why nothing (more) goes to [p]: it isn't a local, trusted panel with settings now. */
function stopped(p) {
  return 'Nothing more goes to ' + p.name + '. ' + ((p.reach && p.reach.state !== 'up' && p.reach.why) || "it isn't reachable as a Glassworks panel now.");
}

/** "Copied 4 settings to Bedroom panel. ", once a copy has run. */
function copied(result, toP) {
  if (!result) return '';
  const n = [...result.values()].filter((o) => o.state === 'done' || o.state === 'pending').length;
  return 'Copied ' + n + (n === 1 ? ' setting' : ' settings') + ' to ' + toP.name + '. ';
}

/**
 * Sends [rows] to [toP], one at a time, then reads its settings again. [asked]: the last run's answers, when the
 * person agreed to its questions: each row goes again with its question's one-shot id.
 */
async function apply(fromP, toP, rows, asked) {
  // The panel as the list has it now, not as the card was drawn: what reads it last decides.
  const current = panels.find((p) => p.origin === toP.origin);
  if (!usable(current)) return drawCopy(fromP, current || toP);
  toP = current;
  const result = new Map();
  const access = await token().catch(() => null);
  if (!access) return start();
  for (const r of rows) {
    const o = await writeSetting(toP, access,
      { section: r.section, key: r.key, value: r.value, ...(asked ? { answer: asked.get(r.section + '\n' + r.key).ask.id } : {}) });
    result.set(r.section + '\n' + r.key, o);
  }
  const again = await readPanel(toP, access);
  panels = panels.map((p) => (p.origin === toP.origin ? again : p));
  drawList(stored()?.user || '');
  const to = panels.find((p) => p.origin === toP.origin);
  // Usable or not, the card says what happened; one that stopped answering as a panel says why.
  drawCopy(fromP, to, result);
}

// ------------------------------------------------------------------ start

async function start() {
  copyCard.hidden = true;
  if (!ha) return;
  try {
    await finishSignIn();
  } catch (e) {
    return say(listCard, 'Panels in Home Assistant', e.message, h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  let access = await token().catch(() => null);
  if (!access) {
    return say(listCard, 'Panels in Home Assistant', 'Sign in with a Home Assistant administrator to find the panels. This tab keeps the sign-in until you close it.',
      h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  say(listCard, 'Panels in Home Assistant', 'Asking Home Assistant for the panels…');
  let me, devices, areas, entries;
  try {
    [me, devices, areas, entries] = await ask(access, ['auth/current_user', 'config/device_registry/list', 'config/area_registry/list', 'config_entries/get']);
  } catch (e) {
    if (e.signIn) keep(KEY, null);
    return say(listCard, 'Panels in Home Assistant', e.message, h('button', { class: 'pill primary', type: 'button', onclick: e.signIn ? signIn : start }, e.signIn ? 'Continue with Home Assistant ↗' : 'Try again'));
  }
  if (!me.is_admin) {
    return say(listCard, 'Panels in Home Assistant', me.name + " isn't a Home Assistant administrator. Only administrators manage panels.",
      h('button', { class: 'pill', type: 'button', onclick: signOut }, 'Sign out'));
  }
  keep(KEY, JSON.stringify({ ...stored(), user: me.name }));
  panels = panelsFrom(devices, areas, location.host, entries).map((p) => ({ ...p, reach: { state: 'asking', why: 'Asking it for its settings' } }));
  drawList(me.name);
  try {
    access = await token();
  } catch (e) {
    return say(listCard, 'Panels in Home Assistant', e.message, h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  // Each row as soon as its panel answers: a slow one doesn't hold the others back.
  await Promise.all(panels.map(async (p, i) => {
    panels[i] = await readPanel(p, access);
    drawList(me.name);
  }));
  drawList(me.name);
  drawCopy();
}

start();
