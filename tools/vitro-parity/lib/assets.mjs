// Where an image a card shows is saved for Vitro's fixture server, and whether it may be fetched at all.
//
// Touches: nothing (pure functions over paths).
import { posix, resolve, sep } from 'node:path';

/**
 * The file under [assetsDir] for HA path [path] (`/static/icons/x.png`), or a thrown error when the path isn't a
 * plain absolute path that stays inside assets/: `..` or `.` segments (also percent-encoded, which fetch would
 * normalize to another HA path, Bearer token included), backslashes, or, for the committed corpus, anything
 * outside HA's own /static. Every check runs on the path as given and as fetch's URL parser would canonicalize it,
 * so nothing the parser strips or resolves (tabs, newlines, dot segments) can change which path is requested.
 */
export function assetDestination(assetsDir, path, committed) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) throw new Error(`asset ${path}: not an absolute HA path`);
  // Tabs and newlines first: URL parsing drops them, so "/static/.\t./api/states" would reach /api/states.
  if (/[\u0000-\u001f\u007f]/.test(path)) throw new Error(`asset ${JSON.stringify(path)}: control characters`);
  if (/\\|%2e|%2f|%5c/i.test(path)) throw new Error(`asset ${path}: encoded or backslash path`);
  if (path.split('/').some((seg) => seg === '..' || seg === '.')) throw new Error(`asset ${path}: dot segments`);
  if (posix.normalize(path) !== path) throw new Error(`asset ${path}: not a normalized path`);
  // What fetch will actually request: the WHATWG-canonical path must be this very path.
  if (canonicalPath(path) !== path) throw new Error(`asset ${path}: the URL would request ${canonicalPath(path)}`);
  if (committed && !path.startsWith('/static/')) throw new Error(`asset ${path}: the committed corpus may only show HA's own /static images`);
  const root = resolve(assetsDir);
  const out = resolve(root, path.slice(1));
  if (!out.startsWith(root + sep)) throw new Error(`asset ${path}: outside assets/`);
  return out;
}

/** The path a URL with this path (and maybe a query) requests, after WHATWG URL parsing (what fetch does). */
export function canonicalPath(pathAndQuery) {
  return new URL(pathAndQuery, 'http://homeassistant.local').pathname;
}

/**
 * HA's own static images a card shows, saved so Vitro's fixture server can serve them: `image:` and `picture:`
 * values (picture-entity, Mushroom's picture) and `<img src="/...">` (or single-quoted) in Markdown content.
 */
export function imagePaths(card) {
  const out = new Set();
  const walk = (o) => {
    if (Array.isArray(o)) o.forEach(walk);
    else if (o && typeof o === 'object') for (const [k, v] of Object.entries(o)) {
      if (/(image|picture)$/.test(k) && typeof v === 'string' && v.startsWith('/')) out.add(v.split('?')[0]);
      else walk(v);
    }
    else if (typeof o === 'string') for (const m of o.matchAll(/<img\b[^>]*?\ssrc\s*=\s*(["'])(\/[^"'?]*)/gi)) out.add(m[2]);
  };
  walk(card);
  return [...out];
}
