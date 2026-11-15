/**
 * After `npm run build`: every route carries, in its server bundle, every Markdown file the docs
 * and the blog publish and every image the built pages show. Every page reads them (the sidebar
 * lists the blog's posts). A function may render a doc
 * again on revalidation (src/lib/docs/index.ts), and a file missing from its bundle would fail it.
 * The docs' Markdown comes from src/lib/docs/sources.ts, the same map the site renders from, and the
 * posts are every site/blog/<folder>/index.md, as src/lib/blog/posts.ts reads them, so a new doc or
 * post that next.config.ts's outputFileTracingIncludes misses fails here. Reads .next only.
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


/* every image a built docs page shows, by repo path */
const walk = (d) => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]));
const images = new Set();
for (const f of walk(app).filter((f) => f.endsWith(".html"))) {
  for (const m of readFileSync(f, "utf8").matchAll(/\/doc-assets\/([^"?#\s\\]+)/g)) images.add(decodeURI(m[1]));
}
const posts = readdirSync(join(site, "blog"), { withFileTypes: true })
  .filter((e) => e.isDirectory())
  .map((e) => `site/blog/${e.name}/index.md`);
const markdown = [...new Set([...SOURCES.map((s) => s.file), ...posts])];
const need = [...markdown, ...images].sort();

/* every page and route handler the build made */
const ROUTES = walk(app)
  .filter((f) => /\/(page|route)\.js\.nft\.json$/.test(f))
  .map((f) => relative(app, f).replace(/\.js\.nft\.json$/, ""));

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
console.log(`Trace check: ${ROUTES.length} routes each carry ${markdown.length} Markdown files (${posts.length} posts) and ${images.size} images`);
