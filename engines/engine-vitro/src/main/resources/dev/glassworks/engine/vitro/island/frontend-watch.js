/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Vitro island: does the card draw with elements of Home Assistant's frontend that this page doesn't have?
//
// Some custom cards build their main control from HA's own elements (`ha-control-circular-slider`, `ha-big-number`,
// `hui-card-features`, `state-badge`, …) instead of shipping them. HA's page defines those; an island page has only
// its stand-ins (`vitroDefine`), so such an element stays an empty, undefined box and the card draws incomplete. This
// watches the card's render tree, every shadow root and nested custom element included, for elements named like HA's
// that nothing defines, and reports them once (`needsFrontend`), with the card each belongs to (a stack's inner card,
// not the stack): Vitro then shows the card's view in HA's frontend.
//
// The rule: a tag HA's frontend's naming covers (FRONTEND_TAG: `ha-*`, `hui-*`, `state-*`, `more-info-*`, the four
// prefixes of nearly all of the ~1,500 elements frontend 20261028.5 defines that a card would draw), not defined in
// this page, still in the tree and still undefined GRACE_MS after it was first seen. One such tag is enough: an
// undefined element draws nothing of its own, and the page can't tell a card's dial from its decoration, while HA's
// frontend draws every card right. The few tags that degrade to nothing a user misses are in HARMLESS (never a card
// type): ones with nothing of their own to draw, and ones whose content is their own children, which an undefined
// element shows anyway.
//
// Cheap and capped: a scan is a querySelectorAll per shadow root, at most MAX_NODES elements; scans run on a
// MutationObserver's debounce (elements that only appear later: an option, a state, a lazy render) and at a few fixed
// times (shadow roots attached later, which no observer reports), and stop after MAX_SCANS, or once reported. A card that defines its own `ha-`-named element (it is defined) never counts, nor one
// it defines lazily within GRACE_MS.
//
// A plain script: the island page inlines it (WebIslandCardView.buildIslandHtml), and
// tools/vitro-parity/test/island-frontend.test.mjs runs it in Node against a fake tree and clock.

const VITRO_FRONTEND_TAG = /^(?:ha|hui|state|more-info)-[a-z0-9]+(?:-[a-z0-9]+)*$/;

// Undefined, these leave nothing a user misses. `ha-ripple`: the press ripple (decoration; the island also defines
// it). `hui-warning`: HA's "Entity not available" notice, whose text is its own children, so it still reads, and a
// missing entity is a moment's state, not a reason to leave Vitro for the session.
const VITRO_FRONTEND_HARMLESS = new Set(['ha-ripple', 'hui-warning']);

// discoveryMs: scans at these times after the watch starts, whatever the observer saw. Attaching a shadow root (a
// card, or an element in it, upgraded late) notifies no MutationObserver, so a root that appears after a scan is found
// by the next of these.
const VITRO_FRONTEND_LIMITS = { graceMs: 2500, debounceMs: 400, maxScans: 30, maxNodes: 5000, discoveryMs: [1500, 4000, 8000, 15000, 30000] };

// The element above [n]: its parent, or a shadow root's host.
function vitroFrontendUp(n) {
  const p = n.parentNode;
  return p && p.nodeType === 11 ? p.host || null : p || null;
}

/**
 * The card an element belongs to: the nearest custom card above it (a defined element with `setConfig`, not one of
 * HA's names), up to [card] itself. A wrapper card (a stack) isn't blamed for the card inside it.
 */
function vitroFrontendOwner(el, card) {
  for (let n = vitroFrontendUp(el); n; n = vitroFrontendUp(n)) {
    if (n === card) break;
    const t = n.localName || '';
    if (t.includes('-') && typeof n.setConfig === 'function' && !VITRO_FRONTEND_TAG.test(t)) return t;
  }
  return card.localName;
}

/**
 * The tags under [card] (its light DOM and every open shadow root below it) that look like HA's frontend's and that
 * [isDefined] says nothing defines, each with the cards that draw it ([vitroFrontendOwner]), and every shadow root
 * walked (to observe). At most [maxNodes] elements. `tags` maps a tag to its owners.
 */
function vitroFrontendScan(card, isDefined, maxNodes) {
  const tags = new Map();
  const roots = [];
  const stack = [];
  if (card.shadowRoot) stack.push(card.shadowRoot);
  stack.push(card);
  let seen = 0;
  while (stack.length && seen < maxNodes) {
    const root = stack.pop();
    if (root !== card) roots.push(root);
    const all = root.querySelectorAll('*');
    for (let i = 0; i < all.length && seen < maxNodes; i++) {
      const el = all[i];
      seen++;
      const tag = el.localName;
      if (VITRO_FRONTEND_TAG.test(tag) && !VITRO_FRONTEND_HARMLESS.has(tag) && !isDefined(tag)) {
        if (!tags.has(tag)) tags.set(tag, new Set());
        tags.get(tag).add(vitroFrontendOwner(el, card));
      }
      if (el.shadowRoot) stack.push(el.shadowRoot);
    }
  }
  return { tags, roots };
}

/**
 * Watches [card] and calls [env.report] once, with the sorted HA frontend tags it draws with that stayed undefined for
 * [graceMs], and the cards that draw those ([vitroFrontendOwner]): never a tag still in its grace, nor its card. [env] holds `report` and, for tests, `isDefined`, `setTimeout`, `clearTimeout`, `now`,
 * `MutationObserver` and limits; the page's own by default. Returns a stop function.
 */
function vitroWatchFrontend(card, env) {
  const e = Object.assign({}, VITRO_FRONTEND_LIMITS, env || {});
  const isDefined = e.isDefined || ((t) => !!customElements.get(t));
  const setT = e.setTimeout || setTimeout;
  const clearT = e.clearTimeout || clearTimeout;
  const now = e.now || (() => performance.now());
  const MO = 'MutationObserver' in e ? e.MutationObserver : (typeof MutationObserver === 'function' ? MutationObserver : null);
  const firstSeen = new Map();
  const observed = new WeakSet();
  let scans = 0;
  let timer = null;
  let timerAt = Infinity;
  let done = false;
  let observer = null;
  const started = now();

  const stop = () => {
    done = true;
    if (timer) clearT(timer);
    timer = null;
    if (observer) observer.disconnect();
    observer = null;
  };
  // Runs a scan [delay] ms from now, unless one is already due sooner.
  const schedule = (delay) => {
    if (done) return;
    const at = now() + delay;
    if (timer && timerAt <= at) return;
    if (timer) clearT(timer);
    timerAt = at;
    timer = setT(run, delay);
  };
  const run = () => {
    timer = null;
    timerAt = Infinity;
    if (done) return;
    scans++;
    const { tags, roots } = vitroFrontendScan(card, isDefined, e.maxNodes);
    if (observer) {
      // The card itself too: its light DOM, and a card with no shadow root at all.
      for (const r of [card, ...roots]) {
        if (!observed.has(r)) { observed.add(r); observer.observe(r, { childList: true, subtree: true }); }
      }
    }
    const t = now();
    // A tag that left the tree, or got defined, starts its grace over if it comes back undefined.
    for (const tag of [...firstSeen.keys()]) if (!tags.has(tag)) firstSeen.delete(tag);
    for (const tag of tags.keys()) if (!firstSeen.has(tag)) firstSeen.set(tag, t);
    const due = [...tags.keys()].filter((tag) => t - firstSeen.get(tag) >= e.graceMs).sort();
    if (due.length) {
      const owners = new Set();
      for (const tag of due) for (const o of tags.get(tag)) owners.add(o);
      e.report(due, [...owners].sort());
      stop();
      return;
    }
    // The budget is hard: tags that come and go (never one past its grace) don't keep the watch going.
    if (scans >= e.maxScans) {
      stop();
      return;
    }
    if (tags.size) {
      // Decide when the oldest one's grace is over, whatever the observer does meanwhile.
      const oldest = Math.min(...[...tags.keys()].map((tag) => firstSeen.get(tag)));
      schedule(Math.max(0, oldest + e.graceMs - t));
    } else {
      // The next discovery scan, if any is left.
      const next = e.discoveryMs.find((ms) => started + ms > t);
      if (next !== undefined) schedule(started + next - t);
    }
  };
  if (MO) observer = new MO(() => { if (scans < e.maxScans) schedule(e.debounceMs); });
  schedule(e.debounceMs);
  return stop;
}
