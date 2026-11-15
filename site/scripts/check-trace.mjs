/**
 * After `npm run build`: every route that renders the docs carries, in its server bundle, every
 * Markdown file the docs publish and every image the built pages show. A function may render a doc
 * again on revalidation (src/lib/docs/index.ts), and a file missing from its bundle would fail it.
 * The list of Markdown comes from src/lib/docs/sources.ts, the same map the site renders from, so a
 * new doc that next.config.ts's outputFileTracingIncludes misses fails here. Reads .next only.
 *
 *   npm run check:trace -w site
 */
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const site = join(dirname(fileURLToPath(import.meta.url)), "..");
const repo = resolve(site, "..");
const app = join(site, ".next/server/app");
process.chdir(site); // sources.ts finds the repo from the working directory
const { SOURCES } = await import(join(site, "src/lib/docs/sources.ts"));

/* the routes whose render reads the docs: every page under /docs, /changelog, a frame's page, the images route */
const ROUTES = ["docs/page", "docs/[...slug]/page", "docs/panes/page", "docs/libraries/page", "(site)/changelog/page", "(site)/frames/[slug]/page", "doc-assets/[...path]/route"];

/* every image a built docs page shows, by repo path */
const walk = (d) => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]));
const images = new Set();
for (const f of walk(app).filter((f) => f.endsWith(".html"))) {
  for (const m of readFileSync(f, "utf8").matchAll(/\/doc-assets\/([^"?#\s\\]+)/g)) images.add(decodeURI(m[1]));
}
const need = [...new Set([...SOURCES.map((s) => s.file), ...images])].sort();

const bad = [];
for (const route of ROUTES) {
  const file = join(app, `${route}.js.nft.json`);
  if (!existsSync(file)) {
    bad.push(`${route}: no trace (${relative(site, file)})`);
    continue;
  }
  const traced = new Set(JSON.parse(readFileSync(file, "utf8")).files.map((f) => relative(repo, resolve(dirname(file), f))));
  const missing = need.filter((f) => !traced.has(f));
  if (missing.length) bad.push(`${route}: ${missing.length} file(s) not in its bundle:\n    ${missing.join("\n    ")}`);
}

if (bad.length) {
  console.error(`Trace check: add them to outputFileTracingIncludes in next.config.ts.\n  ${bad.join("\n  ")}`);
  process.exit(1);
}
console.log(`Trace check: ${ROUTES.length} routes each carry ${need.length - images.size} Markdown files and ${images.size} images`);
