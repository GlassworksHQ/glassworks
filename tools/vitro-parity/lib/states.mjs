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
