// The entity states HA's page holds, rebuilt from its subscribe_entities stream (compressed states).
//
// Touches: nothing (a pure reducer over a states object).

/**
 * Applies one subscribe_entities event to [states] (mutated in place: capture holds the same object). [first]
 * marks a subscription's first event, its full snapshot: like HA's frontend, the collection is replaced then,
 * so an entity gone since the last page load is gone here too. Later events merge (`c`) and remove (`r`).
 */
export function applyEntityEvent(states, ev, first) {
  if (first) for (const id of Object.keys(states)) delete states[id];
  for (const [id, s] of Object.entries(ev.a || {})) states[id] = { ...s, a: { ...(s.a || {}) } };
  for (const [id, d] of Object.entries(ev.c || {})) {
    const cur = states[id] || (states[id] = { s: '', a: {} });
    const plus = d['+'] || {};
    if ('s' in plus) cur.s = plus.s;
    if ('lc' in plus) { cur.lc = plus.lc; delete cur.lu; }
    if ('lu' in plus) cur.lu = plus.lu;
    if (plus.a) cur.a = { ...(cur.a || {}), ...plus.a };
    for (const k of (d['-'] || {}).a || []) delete cur.a[k];
  }
  for (const id of ev.r || []) delete states[id];
  return states;
}

/**
 * The entities whose state in [states] isn't the one a case's `expect` ({entity_id: state}) asks for, as
 * `id: wanted, got` strings: a refresh must not write an "on" case's reference while the light is off.
 */
export function unmetExpectations(expect, states) {
  // `expect` names a served state that doesn't move with the clock: an "@" time marker never matches (the fake serves
  // it resolved), and says so, as the corpus test does. A literal state that merely starts with "@" ("@standby") is
  // served as written, so it matches as any other.
  return Object.entries(expect || {})
    .filter(([id, want]) => isTimeMarker(want) || states[id]?.s !== want)
    .map(([id, want]) => isTimeMarker(want)
      ? `${id}: expects the time marker ${want}, not a state HA serves`
      : `${id}: expected ${want}, got ${states[id]?.s ?? 'no state'}`);
}

/**
 * True for a fixture time the fake HA resolves (fakehome.py's `resolve`): "@D+1", "@D-2 14:30", "@-2h", "@+1d12h",
 * "@90". Anything else after an "@" is a literal state.
 */
export function isTimeMarker(v) {
  if (typeof v !== 'string' || !v.startsWith('@')) return false;
  const spec = v.slice(1).trim();
  return /^D[+-]\d+(?: \d\d:\d\d(?::\d\d)?)?$/.test(v.slice(1)) ||
    /^[+-]?\d+(\.\d+)?$/.test(spec) ||
    /^[+-]?(?:\d+(?:\.\d+)?[smhdw])+$/.test(spec);
}
