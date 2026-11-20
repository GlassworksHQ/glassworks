/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The All panels page (#466). The browser signs in to Home Assistant itself and keeps the token in this tab
// (sessionStorage). With it, the page lists the panels from Home Assistant's device registry, asks each one
// for its settings (each panel checks the token with its own Home Assistant), opens a panel's own Manage page
// signed in, and copies settings from one panel to another. No panel sees another's requests. The token goes
// only to a device Home Assistant lists as a Glassworks panel that answers as one first (readPanel).

import { panelKeep, authorizeUrl, base64url, copyRows, panelAccess, panelForget, panelsFrom, pkceChallenge, plan, readPanel, revokeAt, storedFor, tokenRequest, usable } from './panels-core.js';

const root = document.getElementById('gw-panels');
// Running, imports loaded: panels-boot.js leaves the page alone.
root.dataset.started = '1';
const ha = (root.dataset.ha || '').replace(/\/+$/, '');
const session = root.dataset.session || '';
/** The panel keeps this browser's Manage sign-in with Home Assistant: All panels uses it, with no second sign-in. */
// Whether this panel keeps a Home Assistant sign-in for this session; true too once this tab hands it its own (panelKeep).
let haSignIn = root.dataset.haSignin === '1';
const csrf = root.dataset.csrf || '';
const listCard = document.getElementById('gw-list');
const copyCard = document.getElementById('gw-copy');
const clientId = location.origin + '/';
const KEY = 'gw-ha';
const STATE = 'gw-ha-state';
const VERIFIER = 'gw-ha-verifier';

/** Every panel found, with what it answered: { ...panel, reach, settings }. */
let panels = [];

/** Bumped by each start and sign-out: a slower, older start finds it changed and draws nothing (CodeRabbit on #488). */
let generation = 0;

/** A copy is running: another Copy or Change them too waits for it to end. */
let applying = false;

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
  return storedFor(load(KEY), ha, session);
}

/** A sign-in kept for another Manage session (someone signed out of Manage in this tab): end it in Home Assistant. */
function dropOthers() {
  const raw = load(KEY);
  if (!raw || stored()) return;
  keep(KEY, null);
  try {
    const t = JSON.parse(raw);
    if (t && t.ha && t.refresh) revokeAt(t.ha, t.refresh);
  } catch { /* nothing kept worth ending */ }
}

const tokenCall = (form) => tokenRequest(ha, form);

/**
 * A current access token, renewed from the refresh token a minute before it runs out; null when signed out,
 * also when a sign-out came while it was being renewed: the renewal then stores nothing (CodeRabbit on #534).
 */
async function token() {
  const t = stored();
  if (!t) return null;
  if (Date.now() < t.until - 60_000) return t.access;
  if (t.viaPanel) return fromPanel();
  const mine = generation;
  const j = await tokenCall({ grant_type: 'refresh_token', refresh_token: t.refresh, client_id: clientId });
  if (mine !== generation) return null;
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
  keep(KEY, JSON.stringify({ ha, session, access: j.access_token, refresh: j.refresh_token, until: Date.now() + j.expires_in * 1000 }));
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
/** An access token from the sign-in this panel keeps for the Manage session, kept in the tab; null when it keeps none. */
async function fromPanel() {
  const mine = generation;
  const was = stored();
  const a = haSignIn ? await panelAccess(csrf) : null;
  if (mine !== generation) return null;
  // An outage keeps the sign-in where it is: the page says so and tries again, rather than asking for a new one.
  if (a && a.outage) throw Object.assign(new Error(a.problem), { panel: true });
  if (a && a.manageEnded) {
    keep(KEY, null);
    throw Object.assign(new Error(a.problem), { panel: true, manageEnded: true });
  }
  if (a && a.stale) throw Object.assign(new Error(a.problem), { panel: true, stale: true });
  if (!a) { keep(KEY, null); return null; }
  // A renewal keeps who signed in, for the list's header; stored() is already this HA and this session's, or null.
  const user = was && was.viaPanel ? was.user : undefined;
  keep(KEY, JSON.stringify({ ha, session, viaPanel: true, user, access: a.access_token, until: Date.now() + a.expires_in * 1000 }));
  return a.access_token;
}

/** Ends [t] where it lives: the panel's kept sign-in through the panel, the tab's own at Home Assistant. */
const end = (t) => (t.viaPanel ? panelForget(csrf) : revokeAt(ha, t.refresh));

async function signOut() {
  const t = stored();
  keep(KEY, null);
  if (!t) return start();
  return ending(t);
}

/**
 * Ends [t], from Sign out or its Try again. The list or the button goes first, so a second click can't start another
 * attempt while this one waits, and only the newest attempt decides what the page shows next (CodeRabbit on #534).
 */
async function ending(t) {
  const mine = ++generation;
  copyCard.hidden = true;
  say(listCard, 'Panels in Home Assistant', 'Signing out…');
  const ok = await end(t);
  if (mine !== generation) return;
  // The panel ended (or dropped) the sign-in it kept: the page knows it keeps none now, and asks for Continue without
  // asking the panel again (that question could only get "none kept", a 404 in its Log).
  if (t.viaPanel && (ok === true || ok === 'left' || ok === 'ended')) haSignIn = false;
  if (ok === 'busy') {
    return say(listCard, 'Panels in Home Assistant', "The panel is busy, so it hasn't signed out yet. Try again in a moment.",
      h('button', { class: 'pill', type: 'button', onclick: () => ending(t) }, 'Try again'));
  }
  if (ok === 'left') {
    return say(listCard, 'Panels in Home Assistant',
      "Signed out of this page. This panel's Home Assistant address changed, so it couldn't end the sign-in at the old one. Remove it in Home Assistant: your profile, Security, Refresh tokens.",
      h('button', { class: 'pill', type: 'button', onclick: start }, 'Start again'));
  }
  if (ok === 'stale') {
    return say(listCard, 'Panels in Home Assistant',
      "This page is out of date, so it can't sign out here. Reload it and sign out again, or remove the sign-in in Home Assistant: your profile, Security, Refresh tokens.",
      h('button', { class: 'pill primary', type: 'button', onclick: () => location.reload() }, 'Reload'));
  }
  if (ok === 'ended') {
    return say(listCard, 'Panels in Home Assistant',
      "Your sign-in to this Manage page has ended, so the panel can't confirm Home Assistant ended the sign-in. Sign in again, or remove it in Home Assistant: your profile, Security, Refresh tokens.",
      h('button', { class: 'pill primary', type: 'button', onclick: () => location.assign('/') }, 'Sign in'));
  }
  return ok ? start() : revokeFailed(t);
}

/** Home Assistant didn't confirm the revoke: the sign-in may stay active there. Say so, with a way to try again. */
function revokeFailed(t) {
  copyCard.hidden = true;
  say(listCard, 'Panels in Home Assistant',
    "Signed out of this page, but Home Assistant didn't confirm it ended the sign-in, so it may stay active there. Try again, or remove it in Home Assistant: your profile, Security, Refresh tokens.",
    h('button', { class: 'pill', type: 'button', onclick: () => ending(t) }, 'Try again'));
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
  // The new tab is opened now, during the click: after a slow token renewal the click no longer counts, and the
  // browser would block a new tab as a pop-up (CodeRabbit on #488). Blocked anyway: this tab goes there instead.
  // No rel: the panel there gets the token in the post anyway, so what window.opener would add (it could only send
  // this tab elsewhere; it can't read it, being another origin) gives it nothing more.
  const name = 'gw-manage-' + random(9).replace(/[^A-Za-z0-9]/g, '');
  const tab = window.open('about:blank', name);
  const mine = generation;
  let access = null;
  let problem = null;
  try {
    access = await token();
  } catch (e) {
    problem = e;
  }
  // Signed out meanwhile: nothing goes to the panel, and the page keeps what the sign-out drew.
  if (mine !== generation) {
    if (tab) tab.close();
    return;
  }
  if (!access) {
    if (tab) tab.close();
    // The panel's own answer, as it gave it: no second request to it (CodeRabbit on #534).
    return problem && (problem.panel || problem.transient) ? panelProblem(problem) : start();
  }
  const fields = { token: access, next: '/', from: location.host };
  // The form goes into the new tab's own page and is sent from there: iOS Safari can lose the link from this tab to a
  // window it opened by name, and the tab stays on about:blank (2026-11-19 panel run, once). about:blank shares this
  // page's origin, so this tab can write it. If it can't, the named target as before.
  if (tab && postIn(tab, p.origin, fields)) return;
  const f = h('form', { method: 'post', action: p.origin + '/manage/ha/token', target: tab ? name : '_self' },
    ...Object.entries(fields).map(([k, v]) => h('input', { type: 'hidden', name: k, value: v })));
  document.body.append(f);
  f.submit();
  f.remove();
}

/**
 * The handoff form, built in [tab]'s page (the reserved about:blank) and sent from there; false when that page can't be
 * written, for one: reading the document of a tab sent to another site meanwhile throws, so that's inside the try too. The token is a form field, never in a URL. `from` names this page for the other panel's Log: Safari posts
 * from a new tab with `Origin: null` (2026-11-18 panel run).
 */
function postIn(tab, origin, fields) {
  try {
    const doc = tab.document;
    const f = doc.createElement('form');
    f.method = 'post';
    f.action = origin + '/manage/ha/token';
    for (const [k, v] of Object.entries(fields)) {
      const i = doc.createElement('input');
      i.type = 'hidden';
      i.name = k;
      i.value = v;
      f.append(i);
    }
    (doc.body || doc.documentElement).append(f);
    f.submit();
    return true;
  } catch {
    return false;
  }
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
  const grey = { missing: toP.name + "'s version or hardware doesn't have this. It isn't sent.", cant: toP.name + " can't take this value. It isn't sent.", skip: "Sign-ins aren't copied: their passwords never leave a panel.", own: "Each panel keeps its own Home Assistant address. It isn't copied." };
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
  if (applying) return;
  // Signed out (or started again) during the copy: stop sending, and draw nothing from it (CodeRabbit on #488).
  const mine = generation;
  const stale = () => mine !== generation;
  // The panel as the list has it now, not as the card was drawn: what reads it last decides.
  const current = panels.find((p) => p.origin === toP.origin);
  if (!usable(current)) return drawCopy(fromP, current || toP);
  toP = current;
  applying = true;
  for (const e of copyCard.querySelectorAll('button, input, select')) e.disabled = true;
  try {
    let access = null;
    let problem = null;
    try {
      access = await token();
    } catch (e) {
      problem = e;
    }
    // Signed out meanwhile: what the sign-out drew stays.
    if (stale()) return;
    if (!access) return problem && (problem.panel || problem.transient) ? panelProblem(problem) : start();
    const answers = asked ? new Map(rows.map((r) => [r.section + '\n' + r.key, asked.get(r.section + '\n' + r.key).ask.id])) : null;
    const fresh = await copyRows(toP, access, rows, answers, undefined, undefined, undefined, stale);
    // Change them too: the first run's other rows (a restart it asked for, what it copied) stay in the result (CodeRabbit on #488).
    const result = asked ? new Map([...asked, ...fresh]) : fresh;
    if (stale()) return;
    const again = await readPanel(toP, access, undefined, undefined, undefined, undefined, stale);
    if (stale()) return;
    panels = panels.map((p) => (p.origin === toP.origin ? again : p));
    drawList(stored()?.user || '');
    const to = panels.find((p) => p.origin === toP.origin);
    // Usable or not, the card says what happened; one that stopped answering as a panel says why.
    drawCopy(fromP, to, result);
  } finally {
    applying = false;
  }
}

// ------------------------------------------------------------------ start

/** The panel couldn't give a token: its outage, with Try again, or this Manage page's sign-in ended, with Sign in. */
function panelProblem(e) {
  // It takes the screen over: a slow panel's late answer from the last discovery can't draw the list back (CodeRabbit on #534).
  generation++;
  copyCard.hidden = true;
  return say(listCard, 'Panels in Home Assistant', e.message, e.manageEnded
    ? h('button', { class: 'pill primary', type: 'button', onclick: () => location.assign('/') }, 'Sign in')
    : e.stale
      ? h('button', { class: 'pill primary', type: 'button', onclick: () => location.reload() }, 'Reload')
      : h('button', { class: 'pill', type: 'button', onclick: start }, 'Try again'));
}

async function start() {
  const mine = ++generation;
  copyCard.hidden = true;
  if (!ha) return;
  dropOthers();
  // This panel no longer keeps the sign-in (the app restarted, say): a token the tab has from it goes too, fresh or not.
  if (!haSignIn && stored()?.viaPanel) keep(KEY, null);
  try {
    await finishSignIn();
  } catch (e) {
    return say(listCard, 'Panels in Home Assistant', e.message, h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  // Signed in on its own (Continue) while the panel keeps none: the panel keeps it for the session, and the tab drops its
  // copy, so this one sign-in also serves other tabs and the panels this browser opens (2026-11-19 panel run).
  const own = stored();
  if (!haSignIn && own && !own.viaPanel && own.refresh) {
    let kept = await panelKeep(csrf, own.refresh);
    if (mine !== generation) return;
    // The panel keeps another already: this tab's own goes, but only once HA confirms it; else the tab keeps it and the
    // next load tries again (CodeRabbit on #534).
    if (kept === 'already' && !(await revokeAt(ha, own.refresh))) kept = false;
    if (mine !== generation) return;
    if (kept) {
      keep(KEY, null);
      haSignIn = true;
    }
  }
  if (mine !== generation) return;
  // Signed in to this Manage page with Home Assistant: the panel's kept sign-in serves, no second one (#466 panel run).
  // A panel-backed token renews through the panel already: its outage shows as it is, with no second request to the
  // panel (CodeRabbit on #534). A tab's own that HA won't renew falls back to the panel's, then to Continue.
  const viaPanel = !!stored()?.viaPanel;
  let access = null;
  let problem = null;
  try {
    access = await token();
  } catch (e) {
    // The panel's answer, or Home Assistant not answering the tab's own renewal: say so, keep what the tab has.
    if (e.panel || e.transient) problem = e;
  }
  if (mine !== generation) return;
  if (!access && !problem && haSignIn && !viaPanel) {
    try {
      access = await fromPanel();
    } catch (e) {
      problem = e;
    }
  }
  if (mine !== generation) return;
  if (problem) return panelProblem(problem);
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
  if (mine !== generation) return;
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
    // Signed out meanwhile: the sign-out's screen stays (CodeRabbit on #534).
    if (mine !== generation) return;
    if (e.panel || e.transient) return panelProblem(e);
    return say(listCard, 'Panels in Home Assistant', e.message, h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  if (mine !== generation) return;
  // The panel's kept sign-in may have ended meanwhile: ask for one, rather than reading the panels with none (CodeRabbit on #534).
  if (!access) {
    return say(listCard, 'Panels in Home Assistant', 'Sign in with a Home Assistant administrator to find the panels. This tab keeps the sign-in until you close it.',
      h('button', { class: 'pill primary', type: 'button', onclick: signIn }, 'Continue with Home Assistant ↗'));
  }
  // Each row as soon as its panel answers: a slow one doesn't hold the others back.
  await Promise.all(panels.map(async (p, i) => {
    const got = await readPanel(p, access, undefined, undefined, undefined, undefined, () => mine !== generation);
    if (mine !== generation) return;
    panels[i] = got;
    drawList(me.name);
  }));
  if (mine !== generation) return;
  drawList(me.name);
  drawCopy();
}

start();
