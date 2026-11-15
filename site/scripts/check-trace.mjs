/**
 * After `npm run build`: every route carries, in its server bundle, every Markdown file the docs
 * and the blog publish and every image the built pages show. Every page reads them (the sidebar
 * lists the blog's posts). A function may render a doc
 * again on revalidation (src/lib/docs/index.ts), and a file missing from its bundle would fail it.
 * The docs' Markdown comes from src/lib/docs/sources.ts, the same map the site renders from, and the
 * posts are every site/blog/<folder>/index.md, as src/lib/blog/posts.ts reads them, so a new doc or
 * post that next.config.ts's outputFileTracingIncludes misses fails here. The list of the libraries' API
 * references (.api-docs/libraries.json, scripts/api-docs.mjs) is read the same way.
 *
 * It also loads every external module a route's server chunks import from that route's bundle alone.
 * On Vercel a function has only its traced files, with no node_modules of the repo's around them, so a
 * package Next keeps external whose dependencies the trace missed loads fine here and fails there
 * (shiki without @shikijs/core: a 500 on every page rendered on demand, 2026-11-15). Each route's
 * traced files are hard-linked (or copied) into a temporary folder outside the repo, and a child
 * Node imports each module from where the Turbopack runtime would. Reads .next; writes only that
 * temporary folder, and removes it.
 *
 *   npm run check:trace -w site
 */
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, linkSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, rmSync, symlinkSync } from "node:fs";
import { builtinModules } from "node:module";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
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
const need = [...markdown, ...images, "site/.api-docs/libraries.json"].sort();

/* every page and route handler the build made, from Next's own list of them, so a route
   without a trace file is reported rather than skipped */
const ROUTES = Object.values(JSON.parse(readFileSync(join(site, ".next/server/app-paths-manifest.json"), "utf8")))
  .map((f) => f.replace(/^app\//, "").replace(/\.js$/, ""))
  .sort();
if (!ROUTES.length) {
  console.error("Trace check: .next/server/app-paths-manifest.json lists no routes. Run it after npm run build.");
  process.exit(1);
}

/* a trace's files, as repo paths */
const traceOf = (file) => JSON.parse(readFileSync(file, "utf8")).files.map((f) => relative(repo, resolve(dirname(file), f)));
// the server every function runs (Vercel's launcher starts it in minimal mode)
const server = traceOf(join(site, ".next/next-minimal-server.js.nft.json"));

/* how the Turbopack runtime names its external import and require on a chunk's context */
const runtimeOf = (files) => files.find((f) => f.endsWith("/[turbopack]_runtime.js"));
const callsIn = (runtime) => {
  const src = readFileSync(join(repo, runtime), "utf8");
  const name = (fn) => src.match(new RegExp(`contextPrototype\\.(\\w+) = ${fn};`))?.[1];
  const [imp, req] = [name("externalImport"), name("externalRequire")];
  if (!imp || !req) throw new Error(`${runtime}: can't find externalImport and externalRequire; Turbopack's runtime changed`);
  return new RegExp(`\\.(${imp}|${req})\\("([^"]+)"`, "g");
};
const builtin = (id) => id.startsWith("node:") || builtinModules.includes(id.split("/")[0]);

/* the route's bundle, alone in a folder with no node_modules above it */
const scratch = mkdtempSync(join(tmpdir(), "site-functions-"));
if (!relative(repo, scratch).startsWith("..")) throw new Error(`${scratch} is inside the repo, whose node_modules would hide a miss`);
for (let d = dirname(scratch); d !== dirname(d); d = dirname(d)) {
  if (existsSync(join(d, "node_modules"))) throw new Error(`${join(d, "node_modules")} would hide a miss; set TMPDIR elsewhere`);
}
const materialize = (files, into) => {
  for (const f of files) {
    const from = join(repo, f);
    const to = join(into, f);
    mkdirSync(dirname(to), { recursive: true });
    const st = lstatSync(from, { throwIfNoEntry: false });
    if (!st || existsSync(to)) continue;
    if (st.isSymbolicLink()) {
      const target = readlinkSync(from);
      symlinkSync(isAbsolute(target) ? relative(dirname(f), relative(repo, target)) : target, to);
    } else if (st.isDirectory()) mkdirSync(to, { recursive: true });
    else {
      try {
        linkSync(from, to);
      } catch {
        copyFileSync(from, to);
      }
    }
  }
};
// loads each module the way the runtime does, from the runtime's folder, and prints what failed
const LOAD = `
import { createRequire } from "node:module";
const require = createRequire(process.cwd() + "/[turbopack]_runtime.js");
// the server Vercel's launcher starts first, which sets up what Next's own modules expect
require("next/dist/compiled/next-server/server.runtime.prod.js");
const failed = [];
for (const [how, id] of JSON.parse(process.argv[1])) {
  try {
    how === "import" ? await import(id) : require(id);
  } catch (e) {
    failed.push(id + ": " + String(e.message ?? e).split("\\n")[0]);
  }
}
console.log(JSON.stringify(failed));
`;

const bad = [];
const externals = new Set();
try {
  ROUTES.forEach((route, i) => {
    const file = join(app, `${route}.js.nft.json`);
    if (!existsSync(file)) {
      bad.push(`${route}: no trace (${relative(site, file)})`);
      return;
    }
    const files = traceOf(file);
    const traced = new Set(files);
    const missing = need.filter((f) => !traced.has(f));
    if (missing.length) bad.push(`${route}: ${missing.length} file(s) not in its bundle (add them to outputFileTracingIncludes):\n    ${missing.join("\n    ")}`);

    const runtime = runtimeOf(files);
    if (!runtime) {
      bad.push(`${route}: no Turbopack runtime in its trace`);
      return;
    }
    const calls = callsIn(runtime);
    const [imp] = calls.source.match(/\(([^|]+)\|/).slice(1);
    const ids = new Map();
    for (const f of files.filter((f) => f.startsWith(`site/.next/server${sep}`) && f.endsWith(".js"))) {
      for (const m of readFileSync(join(repo, f), "utf8").matchAll(calls)) {
        if (!builtin(m[2])) ids.set(m[2], m[1] === imp ? "import" : "require");
      }
    }
    for (const id of ids.keys()) externals.add(id);
    const into = join(scratch, String(i));
    materialize([...files, ...server], into);
    const run = spawnSync(process.execPath, ["--input-type=module", "-e", LOAD, JSON.stringify([...ids].map(([id, how]) => [how, id]))], {
      cwd: join(into, dirname(runtime)),
      env: { PATH: process.env.PATH, NODE_ENV: "production", __NEXT_PRIVATE_PREBUNDLED_REACT: "next" },
      encoding: "utf8",
    });
    const failed = run.status === 0 ? JSON.parse(run.stdout.trim().split("\n").pop()) : [`node exited ${run.status}: ${run.stderr.trim().split("\n").filter((l) => l && !l.startsWith("Node.js v")).slice(0, 6).join(" / ")}`];
    if (failed.length) bad.push(`${route}: ${failed.length} external module(s) don't load from its bundle (bundle them with transpilePackages):\n    ${failed.join("\n    ")}`);
    rmSync(into, { recursive: true, force: true });
  });
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
if (!externals.size) bad.push("no route imports an external module; the chunk format changed, so this check checks nothing");

if (bad.length) {
  console.error(`Trace check:\n  ${bad.join("\n  ")}`);
  process.exit(1);
}
console.log(
  `Trace check: ${ROUTES.length} routes each carry ${markdown.length} Markdown files (${posts.length} posts) and ${images.size} images, and load each of their ${externals.size} external modules from their own bundle`,
);
