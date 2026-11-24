// Where an image a card shows is saved for Vitro's fixture server, and whether it may be fetched at all.
//
// Touches: nothing (pure functions over paths).
import { posix, resolve, sep } from 'node:path';

/**
 * The file under [assetsDir] for HA path [path] (`/static/icons/x.png`), or a thrown error when the path isn't a
 * plain absolute path that stays inside assets/: `..` or `.` segments (also percent-encoded, which fetch would
 * normalize to another HA path, Bearer token included), backslashes, or, for the committed corpus, anything
 * outside HA's own /static.
 */
export function assetDestination(assetsDir, path, committed) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) throw new Error(`asset ${path}: not an absolute HA path`);
  if (/\\|%2e|%2f|%5c/i.test(path)) throw new Error(`asset ${path}: encoded or backslash path`);
  if (path.split('/').some((seg) => seg === '..' || seg === '.')) throw new Error(`asset ${path}: dot segments`);
  if (posix.normalize(path) !== path) throw new Error(`asset ${path}: not a normalized path`);
  if (committed && !path.startsWith('/static/')) throw new Error(`asset ${path}: the committed corpus may only show HA's own /static images`);
  const root = resolve(assetsDir);
  const out = resolve(root, path.slice(1));
  if (!out.startsWith(root + sep)) throw new Error(`asset ${path}: outside assets/`);
  return out;
}
