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
 * The asset path to save for one response HA's page got, or null. Only a successful image on HA's own origin under
 * `/local/` or `/static/` counts: what HA drew is what Vitro's fixture server must serve, with no guessing from card
 * configs or Markdown. `/api/` (proxies, camera stills) and anything else on the origin are left out.
 */
export function capturableImage(url, origin, status, contentType) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (u.origin !== origin) return null;
  if (!(status >= 200 && status < 300)) return null;
  if (!/^image\//i.test(String(contentType || '').trim())) return null;
  const p = u.pathname;
  if (p.startsWith('//')) return null;
  if (!p.startsWith('/local/') && !p.startsWith('/static/')) return null;
  return p;
}

/**
 * Every path one image body stands for: the URL that answered and each URL that redirected to it ([urls]: the
 * response's URL, then its request's redirectedFrom() chain), as capturableImage accepts them with the final
 * response's [status] and [contentType]. An <img> keeps the URL it asked for, so a redirected image is saved under
 * that path too.
 */
export function aliasPaths(urls, origin, status, contentType) {
  const out = new Set();
  for (const url of urls) {
    const p = capturableImage(url, origin, status, contentType);
    if (p) out.add(p);
  }
  return [...out].sort();
}

/** The asset paths in [existing] that [kept] doesn't hold: files a full refresh found nothing drawing or fetching. */
export function staleAssets(existing, kept) {
  const keep = new Set(kept);
  return [...existing].filter((p) => !keep.has(p)).sort();
}
