// The display data Vitro reads live from HA (HaDisplay), cut down to the entities one corpus case shows.
//
// Touches: nothing (pure functions); capture.mjs fetches the inputs and writes the result through the scrubber.

/** The list_for_display fields HaDisplay.applyRegistry reads (`ai`: an alert card names the area). Labels etc. stay out. */
const ENTRY_KEYS = ['ei', 'pl', 'tk', 'dp', 'ic', 'di', 'en', 'ai', 'np'];

/**
 * The fixture's `display` block for [ids]: their registry entries, their devices' names (the one HA shows,
 * `name_by_user` else `name`; HA's device ids replaced by `device-1`, `device-2`, ... in entity order, since they
 * are unique to one HA), and the icons and English state labels HaDisplay reads for them: per domain, the default
 * (`_`) and the entities' device classes ([deviceClasses]: entity id -> device class); per integration, the
 * entries' own translation keys. [icons] and [translations] are lookups: (category, name) -> HA's `resources`
 * for that one name. [areas] is `config/area_registry/list`; of it the block keeps the areas in [areaIds] (the ones
 * the case's area cards name) and the ones its entities or their devices are in, with the fields HaDisplay.applyAreas
 * reads, and has no `areas` key without any. A device keeps its `area_id`.
 */
export function buildDisplay(ids, registry, devices, icons, translations, deviceClasses = {}, areas = [], areaIds = []) {
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
    const name = d && (d.name_by_user || d.name);
    // A device's area: an entity without its own is in its device's (HA's dialog title names it, #885).
    if (name) deviceList.push({ id: synthetic, name, ...(d.area_id ? { area_id: d.area_id } : {}) });
  }
  const domains = [...new Set([...ids].map((id) => id.split('.')[0]))].sort();
  const keyed = entities.filter((e) => e.tk && e.pl);
  const platforms = [...new Set(keyed.map((e) => e.pl))].sort();
  // Per domain: the default and the device classes the case's entities have.
  const classes = (domain) => new Set(['_', ...[...ids].filter((id) => id.startsWith(domain + '.')).map((id) => deviceClasses[id]).filter(Boolean)]);
  const componentIcons = {};
  for (const d of domains) {
    const all = icons('entity_component', d);
    if (!all) continue;
    componentIcons[d] = Object.fromEntries(Object.entries(all).filter(([k]) => classes(d).has(k)));
  }
  // Per integration: the entries' own [domain][translation key].
  const entityIcons = {};
  for (const e of keyed) {
    const icon = icons('entity', e.pl)?.[e.ei.split('.')[0]]?.[e.tk];
    if (icon === undefined) continue;
    const dom = e.ei.split('.')[0];
    ((entityIcons[e.pl] ||= {})[dom] ||= {})[e.tk] = icon;
  }
  const componentLabels = {};
  for (const d of domains) {
    // A state's labels, and its attributes' (a climate's hvac_action "Heating", as a tile's state_content shows it).
    const prefixes = [...classes(d)].flatMap((c) => [`component.${d}.entity_component.${c}.state.`, `component.${d}.entity_component.${c}.state_attributes.`]);
    for (const [k, v] of Object.entries(translations('entity_component', d) || {})) if (prefixes.some((p) => k.startsWith(p))) componentLabels[k] = v;
  }
  const entityLabels = {};
  for (const e of keyed) {
    const prefix = `component.${e.pl}.entity.${e.ei.split('.')[0]}.${e.tk}.state`;
    for (const [k, v] of Object.entries(translations('entity', e.pl) || {})) {
      if (k.startsWith(prefix + '.') || k.startsWith(prefix + '_attributes.')) entityLabels[k] = v;
    }
  }
  const areaList = [];
  // The areas the case's area cards name, and the ones its entities (or their devices) are in: an alert card and a
  // More Info dialog's title show that area's name.
  const deviceAreas = deviceList.map((d) => d.area_id).filter(Boolean);
  for (const id of [...new Set([...areaIds, ...entities.map((e) => e.ai).filter(Boolean), ...deviceAreas])].sort()) {
    const a = areas.find((x) => x.area_id === id);
    if (!a) continue;
    const out = {};
    for (const k of AREA_KEYS) if (a[k] !== undefined && a[k] !== null) out[k] = a[k];
    areaList.push(out);
  }
  return {
    entities,
    devices: deviceList,
    ...(areaList.length ? { areas: areaList } : {}),
    requested: { domains, platforms },
    icons: { entity_component: componentIcons, entity: entityIcons },
    translations: { entity_component: componentLabels, entity: entityLabels },
  };
}

/** The area registry fields HaDisplay.applyAreas reads. */
const AREA_KEYS = ['area_id', 'name', 'icon', 'picture', 'temperature_entity_id', 'humidity_entity_id'];

/** The areas [config]'s area cards name (`type: area`, `area: <id>`), nested cards included. */
export function referencedAreas(config) {
  const out = new Set();
  const walk = (v) => {
    if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === 'object') {
      if (v.type === 'area' && typeof v.area === 'string' && v.area) out.add(v.area);
      // A shortcut to an area's page is named after the area.
      const m = typeof v.navigation_path === 'string' && v.navigation_path.match(/^\/config\/areas\/area\/([^/?]+)/);
      if (m) out.add(m[1]);
      Object.values(v).forEach(walk);
    }
  };
  walk(config);
  return [...out];
}
