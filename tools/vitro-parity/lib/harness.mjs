// Whether the `vitro-harness` dashboard is this tool's to overwrite.
//
// Touches: nothing (pure functions over HA's dashboard list entry and config).

export const HARNESS = 'vitro-harness';
export const HARNESS_TITLE = 'Vitro harness';
/** The key every config capture.mjs saves carries, so a later run knows the dashboard is its own. */
export const MARKER_KEY = 'glassworks_vitro_harness';
export const MARKER_VALUE = 'tools/vitro-parity/capture.mjs';

/** What capture.mjs creates the dashboard with. */
export const HARNESS_META = { url_path: HARNESS, title: HARNESS_TITLE, icon: 'mdi:test-tube', show_in_sidebar: false, require_admin: true, mode: 'storage' };

/**
 * Why the existing `vitro-harness` dashboard ([entry] from lovelace/dashboards/list, [config] its current config,
 * or null when it has none) may not be overwritten, or null when it's this tool's: hidden, admin-only and storage
 * mode as created, and its config either empty, marked with [MARKER_KEY], or one an earlier version of this tool
 * saved (its title, and nothing but views named after corpus cases, [caseIds]).
 */
export function notOurs(entry, config, caseIds) {
  if (entry.mode !== 'storage' || entry.require_admin !== true || entry.show_in_sidebar !== false || entry.title !== HARNESS_TITLE) {
    return `it isn't the hidden, admin-only storage dashboard "${HARNESS_TITLE}" this tool creates`;
  }
  if (config == null || (typeof config === 'object' && Object.keys(config).length === 0)) return null;
  if (config[MARKER_KEY] === MARKER_VALUE) return null;
  const views = Array.isArray(config.views) ? config.views : null;
  const ids = new Set(caseIds);
  if (config.title === HARNESS_TITLE && views && views.every((v) => ids.has(v.path))) return null;
  return `its config isn't one this tool saved (no "${MARKER_KEY}" marker)`;
}
