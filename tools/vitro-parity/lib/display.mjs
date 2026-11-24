// The display data Vitro reads live from HA (HaDisplay), cut down to the entities one corpus case shows.
//
// Touches: nothing (pure functions); capture.mjs fetches the inputs and writes the result through the scrubber.

/** The list_for_display fields HaDisplay.applyRegistry reads. Everything else (labels, areas, ...) stays out. */
const ENTRY_KEYS = ['ei', 'pl', 'tk', 'dp', 'ic', 'di', 'en'];

/**
 * The fixture's `display` block for [ids]: their registry entries, their devices' names (HA's device ids replaced
 * by `device-1`, `device-2`, ... in entity order, since they are unique to one HA), and the icons and English
 * state labels of their domains and of the integrations of entries with a translation key, the same sets
 * HaDisplay.missingFor asks the live client for. [icons] and [translations] are lookups:
 * (category, name) -> HA's `resources` for that one name.
 */
export function buildDisplay(ids, registry, devices, icons, translations) {
  const byId = new Map((registry.entities || []).map((e) => [e.ei, e]));
  const deviceIds = new Map();
  const entities = [];
  for (const id of [...ids].sort()) {
    const e = byId.get(id);
    if (!e) continue;
    const out = {};
    for (const k of ENTRY_KEYS) if (e[k] !== undefined && e[k] !== null) out[k] = e[k];
    if (out.di) {
      if (!deviceIds.has(out.di)) deviceIds.set(out.di, `device-${deviceIds.size + 1}`);
      out.di = deviceIds.get(out.di);
    }
    entities.push(out);
  }
  const deviceList = [];
  for (const [real, synthetic] of deviceIds) {
    const d = devices.find((x) => x.id === real);
    if (!d) continue;
    deviceList.push({ id: synthetic, name: d.name ?? null, name_by_user: d.name_by_user ?? null });
  }
  const domains = [...new Set([...ids].map((id) => id.split('.')[0]))].sort();
  const platforms = [...new Set(entities.filter((e) => e.tk && e.pl).map((e) => e.pl))].sort();
  const pick = (category, names, lookup) => Object.fromEntries(names.map((n) => [n, lookup(category, n)]).filter(([, v]) => v !== undefined));
  const stateLabels = (category, names) => {
    const out = {};
    for (const n of names) for (const [k, v] of Object.entries(translations(category, n) || {})) if (k.includes('.state.')) out[k] = v;
    return out;
  };
  return {
    entities,
    devices: deviceList,
    requested: { domains, platforms },
    icons: { entity_component: pick('entity_component', domains, icons), entity: pick('entity', platforms, icons) },
    translations: { entity_component: stateLabels('entity_component', domains), entity: stateLabels('entity', platforms) },
  };
}
