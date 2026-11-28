// What became of each image request HA's page made: the latest outcome per path wins.
//
// Touches: nothing (in-memory bookkeeping; capture.mjs feeds it the page's responses).

/**
 * Per asset path, either the body of its latest successful response (`loaded`) or its latest failure (`failed`).
 * A retry that succeeds clears an earlier failure; a later failure drops an earlier body; a later success
 * replaces an earlier body. The browser's cache means a repeated image may bring no new response, so the
 * tracker lives as long as the page, not one case.
 */
export function imageOutcomes() {
  const loaded = new Map();
  const failed = new Set();
  return {
    loaded,
    failed,
    success(paths, body) {
      for (const p of paths) { loaded.set(p, body); failed.delete(p); }
    },
    failure(paths) {
      for (const p of paths) { failed.add(p); loaded.delete(p); }
    },
  };
}

/**
 * Records that this run saved [path] with content [digest] into [seen] (path -> digest). Returns an error message
 * when the run already saved that path with other content: one fixture path can't serve two different images to
 * the references that drew them, so the run must say so instead of keeping one.
 */
export function noteSaved(seen, path, digest) {
  const before = seen.get(path);
  if (before !== undefined && before !== digest) return `image ${path} changed during the run: references drew two different images at one path`;
  seen.set(path, digest);
  return null;
}
