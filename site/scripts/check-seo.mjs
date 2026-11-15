/**
 * Checks the built site's search and link-preview tags. Run after `npm run build`:
 *
 *   npm run check:seo -w site
 *
 * Starts `next start` on a free port (reads .next/ only, writes nothing), then fails when:
 *   - a prerendered page is missing from /sitemap.xml, or the sitemap lists a page that isn't built;
 *   - a page in the sitemap doesn't answer 200, or lacks one <title>, a meta description, a canonical
 *     equal to its own URL, og:url, og:image or twitter:image (and each image must load as a PNG);
 *   - two pages share a title or a description;
 *   - a JSON-LD block doesn't parse;
 *   - robots.txt doesn't allow crawling and name the sitemap;
 *   - an unknown address doesn't say noindex (the 404, and an unknown frame or pane).
 * Set SEO_BASE_URL to check a running site instead (e.g. http://localhost:3000).
 */
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { createServer } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const site = join(dirname(fileURLToPath(import.meta.url)), "..");
const SITE = "https://www.glassworks.dev";

const freePort = () =>
  new Promise((resolve) => {
    const s = createServer().listen(0, () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });

let server;
let base = process.env.SEO_BASE_URL;
if (!base) {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const next = createRequire(import.meta.url).resolve("next/dist/bin/next");
  server = spawn(process.execPath, [next, "start", "-p", String(port)], {
    cwd: site,
    stdio: "ignore",
  }).on("error", () => {});
  server.on("exit", (code) => code && !done && console.error(`next start exited with ${code}`));
}
let done = false;

const errors = [];
const fail = (where, why) => errors.push(`${where}: ${why}`);
const get = (path, init) => fetch(base + path, { redirect: "manual", ...init });

try {
  for (let i = 0; ; i++) {
    try {
      await get("/robots.txt");
      break;
    } catch {
      if (i > 100) throw new Error(`no server at ${base}`);
      await new Promise((r) => setTimeout(r, 200));
    }
  }

  /* robots.txt */
  const robots = await (await get("/robots.txt")).text();
  if (!/^Allow: \/$/m.test(robots)) fail("/robots.txt", "doesn't allow /");
  if (!robots.includes(`Sitemap: ${SITE}/sitemap.xml`)) fail("/robots.txt", "doesn't name the sitemap");

  /* the sitemap against what was built */
  const xml = await (await get("/sitemap.xml")).text();
  const listed = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
  const paths = listed.map((u) => {
    if (!u.startsWith(SITE)) fail("/sitemap.xml", `${u} isn't on ${SITE}`);
    return u.slice(SITE.length) || "/";
  });
  const manifest = JSON.parse(readFileSync(join(site, ".next/prerender-manifest.json"), "utf8"));
  const pages = Object.keys(manifest.routes).filter(
    // HTML pages only: not image, feed or file routes, not Next's own error pages
    (r) => !/\.[a-z]+$|\/(opengraph|twitter)-image$|^\/_/.test(r) && !r.endsWith("/manifest.webmanifest"),
  );
  for (const p of pages) if (!paths.includes(p)) fail(p, "built but not in the sitemap (src/lib/routes.ts)");
  for (const p of paths) if (!pages.includes(p)) fail(p, "in the sitemap but not a built page");

  /* every page's head */
  const attr = (html, re) => html.match(re)?.[1];
  const meta = (html, key, name = "name") => attr(html, new RegExp(`<meta ${name}="${key}" content="([^"]*)"`));
  const seen = { title: new Map(), description: new Map() };
  const images = new Set();
  for (const p of paths) {
    const res = await get(p);
    if (res.status !== 200) {
      fail(p, `answers ${res.status}`);
      continue;
    }
    const html = await res.text();
    const head = html.split("</head>")[0];
    const titles = [...head.matchAll(/<title>([^<]*)<\/title>/g)].map((m) => m[1]);
    if (titles.length !== 1) fail(p, `${titles.length} <title> tags`);
    const url = p === "/" ? `${SITE}/` : SITE + p;
    const description = meta(head, "description");
    if (!description) fail(p, "no meta description");
    const canonical = attr(head, /<link rel="canonical" href="([^"]*)"/);
    // Next writes the home page's URL without its slash; both name the same page
    const same = (u) => u === url || (p === "/" && u === SITE);
    if (!same(canonical)) fail(p, `canonical is ${canonical ?? "missing"}, should be ${url}`);
    if (!same(meta(head, "og:url", "property"))) fail(p, `og:url isn't ${url}`);
    for (const [key, name] of [
      ["og:image", "property"],
      ["twitter:image", "name"],
    ]) {
      const img = meta(head, key, name);
      if (!img) fail(p, `no ${key}`);
      else images.add(img);
    }
    for (const [k, v] of [
      ["title", titles[0]],
      ["description", description],
    ]) {
      if (!v) continue;
      if (seen[k].has(v)) fail(p, `same ${k} as ${seen[k].get(v)}: "${v}"`);
      seen[k].set(v, p);
    }
    for (const m of html.matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/gs)) {
      try {
        JSON.parse(m[1]);
      } catch (e) {
        fail(p, `JSON-LD doesn't parse: ${e.message}`);
      }
    }
  }

  /* the images the pages point at */
  for (const img of images) {
    const res = await get(img.replace(SITE, ""));
    const type = res.headers.get("content-type") ?? "";
    if (res.status !== 200 || !type.startsWith("image/png")) fail(img, `answers ${res.status} ${type}`);
  }

  /* unknown addresses stay out of the index */
  for (const p of ["/no-such-page", "/frames/no-such-frame", "/panes/no-such-pane"]) {
    const res = await get(p);
    const html = await res.text();
    // the robots meta is a comma-separated list: "noindex", "noindex, follow" …
    const robotsMeta = html.match(/<meta name="robots" content="([^"]*)"/)?.[1] ?? "";
    const noindex = robotsMeta.split(",").some((d) => d.trim().toLowerCase() === "noindex");
    if (res.status === 200 && !noindex) fail(p, "200 without noindex");
    if (res.status !== 200 && res.status !== 404) fail(p, `answers ${res.status}`);
  }

  if (errors.length) {
    console.error(`SEO check: ${errors.length} problem(s)\n  ${errors.join("\n  ")}`);
    process.exitCode = 1;
  } else {
    console.log(`SEO check: ${paths.length} pages and ${images.size} images OK`);
  }
} finally {
  done = true;
  server?.kill();
}
