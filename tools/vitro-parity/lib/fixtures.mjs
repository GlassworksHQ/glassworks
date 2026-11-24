// A case's own Lovelace resources (#811): stylesheets and fonts from the corpus's fixtures/ folder, which the capture
// serves to HA's page at FIXTURE_PREFIX on HA's origin and puts in the page as HA's loadCSS does. Nothing is written
// to Home Assistant: the Lovelace resources stay as they are.
//
// Touches: nothing (pure functions over paths and case objects).
import { resolve, sep, extname } from 'node:path';

/** The path on HA's origin a case's fixture resources use. HA serves nothing there; the capture answers it. */
export const FIXTURE_PREFIX = '/local/vitro-harness-fixtures/';

const TYPES = {
  '.css': 'text/css; charset=utf-8',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

/** A case's `resources`, checked: only `type: css` files under FIXTURE_PREFIX. Throws otherwise. */
export function fixtureResources(c) {
  const list = c.resources ?? [];
  if (!Array.isArray(list)) throw new Error(`${c.id}: resources is a list`);
  return list.map((r) => {
    // Checked as the page will request it too: `${FIXTURE_PREFIX}../x.css` normalizes to a path HA serves.
    if (r?.type !== 'css' || typeof r.url !== 'string' || !r.url.startsWith(FIXTURE_PREFIX) || !staysInFixtures(r.url)) {
      throw new Error(`${c.id}: a resource is a type: css file under ${FIXTURE_PREFIX}, not ${JSON.stringify(r)}`);
    }
    return { type: 'css', url: r.url };
  });
}

/** Whether [url] (a path on HA's origin) still names a file under FIXTURE_PREFIX once a URL parser normalizes it. */
function staysInFixtures(url) {
  let parsed;
  try {
    parsed = new URL(url, 'http://homeassistant.local');
  } catch {
    return false;
  }
  if (parsed.origin !== 'http://homeassistant.local' || parsed.search || parsed.hash) return false;
  const path = parsed.pathname;
  return path === url && path.startsWith(FIXTURE_PREFIX) && path.length > FIXTURE_PREFIX.length && !/%2e|%2f|%5c/i.test(path);
}

/**
 * The file under [fixturesDir] for a request to [pathname] on HA's origin, with its content type, or null when the
 * path isn't under FIXTURE_PREFIX, leaves the folder, or names a type the fixtures don't serve.
 */
export function fixtureFile(fixturesDir, pathname) {
  if (typeof pathname !== 'string' || !pathname.startsWith(FIXTURE_PREFIX)) return null;
  const rel = decodeURIComponent(pathname.slice(FIXTURE_PREFIX.length));
  if (!rel || rel.includes('\\') || rel.split('/').some((s) => s === '..' || s === '.' || s === '')) return null;
  const root = resolve(fixturesDir);
  const file = resolve(root, rel);
  if (!file.startsWith(root + sep)) return null;
  const type = TYPES[extname(file).toLowerCase()];
  return type ? { file, type } : null;
}
