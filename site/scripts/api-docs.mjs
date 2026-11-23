/**
 * The libraries' API reference, from Maven Central and npm. `npm run build` and `npm run dev` run it first:
 *
 *   node scripts/api-docs.mjs
 *
 * For each library in ../libs that is published (its build file applies com.vanniktech.maven.publish,
 * the release workflow's rule), it reads the latest release from Central's maven-metadata.xml,
 * downloads that version's -javadoc.jar (Dokka HTML), checks it against Central's .sha256 or .sha1,
 * and unpacks it to public/docs/libraries/<name>/api/. Each page gets a canonical link, the site's
 * stylesheet (public/api-reference.css) and a breadcrumb back to the site; each index page gets its
 * title, description and link preview, as `check:seo` wants of every page in the sitemap.
 *
 * For each npm package (the ones tools/release/npm-package.mjs publishes), it reads the version npm tags
 * `latest`, downloads that tarball, checks it against npm's integrity hash, and runs TypeDoc on the type
 * declarations it ships, one module per entry of its `exports`, into the same public/docs/libraries/<name>/api/.
 * Those pages get the same canonical link, stylesheet, breadcrumb and index metadata.
 *
 * What it found goes to .api-docs/libraries.json, which src/lib/apiDocs.ts reads:
 *   { "<name>": { "kind": "site", "version": "0.1.0", "href": "/docs/libraries/<name>/api/index.html",
 *                 "coordinate": "dev.glassworks:<name>:0.1.0" | "@glassworks/<name>@0.1.0" }
 *             | { "kind": "elsewhere", "host": "javadoc.io" | "jsdocs.io", "href": "https://…" }
 *             | { "kind": "unreleased" } }
 * A library that isn't on its registry yet, or isn't published at all, is "unreleased". When a registry
 * doesn't answer, the newest cached jar or tarball is used, and with none cached the page links javadoc.io
 * or jsdocs.io and the build goes on. A checksum that doesn't match fails the build.
 *
 * Jars and tarballs are cached in .api-docs/cache/<repository>/ with their checksums, so a rebuild only asks
 * the registry for the metadata. Writes only public/docs/libraries/ and .api-docs/ (both git-ignored).
 * MAVEN_REPO=<url> reads another Maven repository (a local one, to try a release before Central has it),
 * NPM_REGISTRY=<url> another npm registry.
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync, inflateRawSync } from "node:zlib";
import { Application, ReflectionKind } from "typedoc";
import { publishable } from "../../tools/release/npm-package.mjs";

const site = join(dirname(fileURLToPath(import.meta.url)), "..");
const repo = join(site, "..");
const SITE = "https://www.glassworks.dev";
const MAVEN = (process.env.MAVEN_REPO ?? "https://repo1.maven.org/maven2").replace(/\/$/, "");
const GROUP = "dev.glassworks";
const OUT = join(site, "public/docs/libraries");
const STATE = join(site, ".api-docs");
// one cache per repository, so a jar from MAVEN_REPO never stands in for Central's
const CACHE = join(STATE, "cache", MAVEN.replace(/[^\w.-]+/g, "_"));
const NPM = (process.env.NPM_REGISTRY ?? "https://registry.npmjs.org").replace(/\/$/, "");
const NPM_CACHE = join(STATE, "cache", NPM.replace(/[^\w.-]+/g, "_"));

const read = (p) => readFileSync(p, "utf8");
const writeAtomic = (p, data) => {
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(`${p}.tmp`, data);
  renameSync(`${p}.tmp`, p);
};
const esc = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");

class Offline extends Error {}

/** fetch that tells "not there" (undefined) from "Central didn't answer" (Offline) */
async function get(url) {
  let res;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(60_000) });
    if (res.ok) return Buffer.from(await res.arrayBuffer());
  } catch (e) {
    throw new Offline(`${url}: ${e.cause?.code ?? e.message}`);
  }
  if (res.status === 404) return undefined;
  throw new Offline(`${url}: HTTP ${res.status}`);
}

/** Every libs/<name>, published or not, in name order. */
function libraries() {
  return readdirSync(join(repo, "libs"), { withFileTypes: true })
    .filter((e) => e.isDirectory() && existsSync(join(repo, "libs", e.name, "build.gradle.kts")))
    .map((e) => {
      const dir = join(repo, "libs", e.name);
      const props = existsSync(join(dir, "gradle.properties")) ? read(join(dir, "gradle.properties")) : "";
      return {
        name: e.name,
        published: read(join(dir, "build.gradle.kts")).includes('id("com.vanniktech.maven.publish")'),
        description: props.match(/^description=(.*)$/m)?.[1].trim() ?? "",
      };
    })
    .sort((a, b) => a.name.localeCompare(b.name));
}

const digest = (algo, buf) => createHash(algo).update(buf).digest("hex");
const ALGOS = [
  ["sha256", 64],
  ["sha1", 40],
];

/** the checksum file's hash: Central writes the hex alone, some tools add the file name after it */
function parseSum(text, len) {
  const hex = text.trim().split(/\s+/)[0]?.toLowerCase() ?? "";
  if (hex.length !== len || !/^[0-9a-f]+$/.test(hex)) throw new Error(`not a checksum: ${JSON.stringify(text.slice(0, 80))}`);
  return hex;
}

/** The cached jar if its saved checksum still matches, else undefined. */
function cachedJar(dir, file) {
  for (const [algo, len] of ALGOS) {
    const sum = join(dir, `${file}.${algo}`);
    if (!existsSync(sum) || !existsSync(join(dir, file))) continue;
    const jar = readFileSync(join(dir, file));
    if (digest(algo, jar) === parseSum(read(sum), len)) return jar;
  }
  return undefined;
}

/** The javadoc jar of name:version, from the cache or Central, checked against Central's checksum. */
async function javadocJar(name, version) {
  const file = `${name}-${version}-javadoc.jar`;
  const dir = join(CACHE, name, version);
  const cached = cachedJar(dir, file);
  if (cached) return cached;

  const url = `${MAVEN}/${GROUP.replace(/\./g, "/")}/${name}/${version}/${file}`;
  let sum;
  for (const [algo, len] of ALGOS) {
    const text = await get(`${url}.${algo}`);
    if (text) {
      sum = { algo, hex: parseSum(text.toString("utf8"), len) };
      break;
    }
  }
  if (!sum) throw new Error(`${url}: Central has no .sha256 or .sha1 for it`);
  const jar = await get(url);
  if (!jar) throw new Error(`${url}: not found, though maven-metadata.xml lists ${version}`);
  const got = digest(sum.algo, jar);
  if (got !== sum.hex) throw new Error(`${url}: ${sum.algo} is ${got}, Central says ${sum.hex}. Not using it.`);
  rmSync(dir, { recursive: true, force: true });
  writeAtomic(join(dir, file), jar);
  writeAtomic(join(dir, `${file}.${sum.algo}`), `${sum.hex}\n`);
  return jar;
}

function newestFirst(a, b) {
  const [x, y] = [a, b].map((v) => v.split(/[.-]/).map((n) => Number(n) || 0));
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (y[i] ?? 0) - (x[i] ?? 0);
  return 0;
}

/** The newest jar in the cache, for a build that can't reach Central. */
function newestCached(name) {
  const dir = join(CACHE, name);
  if (!existsSync(dir)) return undefined;
  for (const version of readdirSync(dir).sort(newestFirst)) {
    const jar = cachedJar(join(dir, version), `${name}-${version}-javadoc.jar`);
    if (jar) return { version, jar };
  }
  return undefined;
}

/**
 * The files of a zip (a jar), stored or deflated, as [path, bytes]. Directories and META-INF are left
 * out, and a path that would leave the folder it's unpacked to fails.
 */
function* unzip(buf) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error("not a zip file");
  let p = buf.readUInt32LE(eocd + 16);
  for (let n = buf.readUInt16LE(eocd + 10); n > 0; n--) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error("bad zip central directory");
    const method = buf.readUInt16LE(p + 10);
    const size = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString("utf8", p + 46, p + 46 + nameLen);
    p += 46 + nameLen + buf.readUInt16LE(p + 30) + buf.readUInt16LE(p + 32);
    if (name.endsWith("/") || name.startsWith("META-INF/")) continue;
    if (name.startsWith("/") || normalize(name).split(sep).includes("..")) throw new Error(`zip entry leaves its folder: ${name}`);
    const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const data = buf.subarray(start, start + size);
    if (method === 0) yield [name, data];
    else if (method === 8) yield [name, inflateRawSync(data)];
    else throw new Error(`zip entry ${name}: compression method ${method}`);
  }
}

/** The site's tags for one API page: canonical, stylesheet, icon, and on the index its title and link preview. */
function siteHead({ lib, version, coordinate, path }) {
  const base = `/docs/libraries/${lib.name}/api/`;
  let head = `<link rel="canonical" href="${SITE}${base}${path}">\n<link href="/api-reference.css" rel="Stylesheet">\n`;
  const title = `${lib.name} ${version} API reference · Glassworks`;
  if (path === "index.html") {
    const description = `The API reference of ${coordinate}, every public class and function. ${lib.description}`;
    head += [
      `<meta name="description" content="${esc(description)}">`,
      `<meta property="og:title" content="${esc(title)}">`,
      `<meta property="og:description" content="${esc(description)}">`,
      `<meta property="og:url" content="${SITE}${base}index.html">`,
      `<meta property="og:site_name" content="Glassworks">`,
      `<meta property="og:type" content="website">`,
      `<meta property="og:image" content="${SITE}/opengraph-image.png">`,
      `<meta name="twitter:card" content="summary_large_image">`,
      `<meta name="twitter:image" content="${SITE}/opengraph-image.png">`,
      "",
    ].join("\n");
  }
  return { head, title };
}

const crumbs = (lib) =>
  `<nav class="gw-crumbs" aria-label="Glassworks"><a class="gw-home" href="/docs">Docs</a>` +
  `<a href="/docs/libraries/${lib.name}">${esc(lib.name)}</a></nav>`;

/** One Dokka page with the site's parts added: a few tags in, nothing of Dokka's taken out. */
function dress(html, page) {
  const { head, title } = siteHead(page);
  if (page.path === "index.html") html = html.replace(/<title>[^<]*<\/title>/, `<title>${esc(title)}</title>`);
  return html
    .replace(/<link href="[^"]*images\/logo-icon\.svg" rel="icon"[^>]*>/, '<link href="/icon.svg" rel="icon" type="image/svg+xml">')
    .replace("</head>", `${head}</head>`)
    .replace(/(<header class="navigation[^>]*>)/, `$1\n${crumbs(page.lib)}`)
    .replace(/(<a class="library-name--link"[^>]*>)\s*[^<]*?\s*(<\/a>)/, "$1API$2");
}

/** One TypeDoc page with the site's parts added, as dress does for Dokka: Docs / <library> / API in its toolbar. */
function dressTypedoc(html, page) {
  const { head, title } = siteHead(page);
  if (page.path === "index.html") {
    html = html.replace(/<title>[^<]*<\/title>/, `<title>${esc(title)}</title>`).replace(/<meta name="description"[^>]*>/, "");
  }
  return html
    .replace("</head>", `<link href="/icon.svg" rel="icon" type="image/svg+xml">\n${head}</head>`)
    .replace(/(<div class="tsd-toolbar-contents container">)/, `$1${crumbs(page.lib)}`)
    .replace(/(<a href="[^"]*" class="title">)[^<]*(<\/a>)/, `$1API$2<span class="gw-version">${esc(page.version)}</span>`);
}

/** Unpack one jar into dir. */
function unpack(jar, dir, lib, version) {
  for (const [path, data] of unzip(jar)) {
    const to = join(dir, path);
    mkdirSync(dirname(to), { recursive: true });
    writeFileSync(to, path.endsWith(".html") ? dress(data.toString("utf8"), { lib, version, coordinate: mavenCoordinate(lib.name, version), path }) : data);
  }
  if (!existsSync(join(dir, "index.html"))) throw new Error(`${lib.name} ${version}: the javadoc jar has no index.html`);
}

const href = (name) => `/docs/libraries/${name}/api/index.html`;
const mavenCoordinate = (name, version) => `${GROUP}:${name}:${version}`;
const javadocIo = (name) => ({ kind: "elsewhere", host: "javadoc.io", href: `https://javadoc.io/doc/${GROUP}/${name}` });
const onSite = (name, version, coordinate) => ({ kind: "site", version, href: href(name), coordinate });

/** Where name's API reference is, unpacking it into out when the site serves it. */
async function reference(lib, out) {
  if (!lib.published) return { kind: "unreleased" };
  const dir = join(out, lib.name, "api");
  try {
    const meta = await get(`${MAVEN}/${GROUP.replace(/\./g, "/")}/${lib.name}/maven-metadata.xml`);
    if (!meta) return { kind: "unreleased" };
    const xml = meta.toString("utf8");
    const version = (xml.match(/<release>([^<]+)<\/release>/) ?? xml.match(/<latest>([^<]+)<\/latest>/))?.[1].trim();
    // the version names folders the cache deletes and rewrites: a plain version only
    if (!version || !/^\w[\w.+-]*$/.test(version)) throw new Error(`${lib.name}: maven-metadata.xml names no usable release (${version})`);
    unpack(await javadocJar(lib.name, version), dir, lib, version);
    return onSite(lib.name, version, mavenCoordinate(lib.name, version));
  } catch (e) {
    if (!(e instanceof Offline)) throw e;
    const cached = newestCached(lib.name);
    console.warn(`api-docs: ${e.message}. ${cached ? `Using the cached ${cached.version}.` : "Linking javadoc.io instead."}`);
    if (!cached) return javadocIo(lib.name);
    unpack(cached.jar, dir, lib, cached.version);
    return onSite(lib.name, cached.version, mavenCoordinate(lib.name, cached.version));
  }
}

/** Every npm package tools/release/npm-package.mjs publishes, in name order. */
const npmPackages = () =>
  publishable()
    .map(({ name, pkg }) => ({ name, pkg: pkg.name, description: pkg.description ?? "" }))
    .sort((a, b) => a.name.localeCompare(b.name));

/** "sha512-<base64>" from npm's dist.integrity, as hex. */
function sha512Of(integrity) {
  const sri = integrity?.split(/\s+/).find((x) => x.startsWith("sha512-"));
  if (!sri) throw new Error(`no sha512 in ${JSON.stringify(integrity)}`);
  return Buffer.from(sri.slice(7), "base64").toString("hex");
}

/** The tarball of pkg@version, from the cache or npm, checked against npm's integrity hash. */
async function npmTarball(lib, version, dist) {
  const file = `${lib.name}-${version}.tgz`;
  const dir = join(NPM_CACHE, lib.name, version);
  const cached = cachedTarball(dir, file);
  if (cached) return cached;
  const hex = sha512Of(dist.integrity);
  const tgz = await get(dist.tarball);
  if (!tgz) throw new Error(`${dist.tarball}: not found, though npm lists ${lib.pkg}@${version}`);
  const got = digest("sha512", tgz);
  if (got !== hex) throw new Error(`${dist.tarball}: sha512 is ${got}, npm says ${hex}. Not using it.`);
  rmSync(dir, { recursive: true, force: true });
  writeAtomic(join(dir, file), tgz);
  writeAtomic(join(dir, `${file}.sha512`), `${hex}\n`);
  return tgz;
}

function cachedTarball(dir, file) {
  const sum = join(dir, `${file}.sha512`);
  if (!existsSync(sum) || !existsSync(join(dir, file))) return undefined;
  const tgz = readFileSync(join(dir, file));
  return digest("sha512", tgz) === parseSum(read(sum), 128) ? tgz : undefined;
}

/** The newest tarball in the cache, for a build that can't reach npm. */
function newestCachedTarball(lib) {
  const dir = join(NPM_CACHE, lib.name);
  if (!existsSync(dir)) return undefined;
  for (const version of readdirSync(dir).sort(newestFirst)) {
    const tgz = cachedTarball(join(dir, version), `${lib.name}-${version}.tgz`);
    if (tgz) return { version, tgz };
  }
  return undefined;
}

/**
 * The regular files of an npm tarball (ustar, gzipped), as [path, bytes], with npm's leading "package/"
 * taken off. Links and the like are left out, and a path that would leave the folder fails.
 */
function* untar(tgz) {
  const tar = gunzipSync(tgz);
  const str = (at, len) => tar.toString("utf8", at, at + len).replace(/\0.*$/s, "");
  for (let p = 0; p + 512 <= tar.length; ) {
    const name = str(p, 100);
    if (!name) break;
    const size = parseInt(str(p + 124, 12).trim() || "0", 8);
    const type = str(p + 156, 1) || "0";
    const prefix = str(p + 257, 6) === "ustar" ? str(p + 345, 155) : "";
    const path = (prefix ? `${prefix}/${name}` : name).replace(/^package\//, "");
    const data = tar.subarray(p + 512, p + 512 + size);
    p += 512 + Math.ceil(size / 512) * 512;
    if (type !== "0") continue;
    if (path.startsWith("/") || normalize(path).split(sep).includes("..")) throw new Error(`tarball entry leaves its folder: ${path}`);
    yield [path, data];
  }
}

/** TypeDoc over the type declarations one npm tarball ships, into dir, one module per export. */
async function typedoc(lib, version, tgz, dir) {
  const stage = join(STATE, "npm", lib.name);
  rmSync(stage, { recursive: true, force: true });
  // Only what TypeDoc reads: a README or CHANGELOG here would be one more Markdown file for the repo's checks.
  for (const [path, data] of untar(tgz)) if (path === "package.json" || path.endsWith(".d.ts")) writeAtomic(join(stage, path), data);
  const manifest = JSON.parse(read(join(stage, "package.json")));
  const entries = Object.entries(manifest.exports ?? {}).flatMap(([key, to]) =>
    typeof to === "object" && to.types ? [{ module: manifest.name + key.slice(1), file: join(stage, to.types) }] : [],
  );
  if (!entries.length) throw new Error(`${lib.pkg}@${version}: its exports name no type declarations`);
  // Under .api-docs, the declarations find their own dependencies' types (w3c-web-usb, @yume-chan/adb) in
  // the repo's node_modules.
  writeFileSync(
    join(stage, "tsconfig.json"),
    JSON.stringify({ compilerOptions: { target: "es2022", module: "nodenext", skipLibCheck: true, types: [] }, files: entries.map((e) => e.file) }),
  );
  const app = await Application.bootstrapWithPlugins({
    entryPoints: entries.map((e) => e.file),
    tsconfig: join(stage, "tsconfig.json"),
    name: lib.pkg,
    readme: "none",
    disableSources: true,
    excludeExternals: true,
    githubPages: false,
    navigationLinks: { npm: `https://www.npmjs.com/package/${lib.pkg}/v/${version}` },
    logLevel: "Warn",
  });
  const project = await app.convert();
  if (!project) throw new Error(`${lib.pkg}@${version}: TypeDoc couldn't read its declarations`);
  // TypeDoc names a module after its file (index, transport); a user imports it by its export.
  const byFile = new Map(entries.map((e) => [e.file.slice(join(stage, "dist").length + 1).replace(/\.d\.ts$/, ""), e.module]));
  for (const m of project.getChildrenByKind(ReflectionKind.Module)) m.name = byFile.get(m.name) ?? m.name;
  await app.generateDocs(project, dir);
  rmSync(stage, { recursive: true, force: true });
  for (const f of readdirSync(dir, { recursive: true })) {
    if (!f.endsWith(".html")) continue;
    const path = f.split(sep).join("/");
    const coordinate = `${lib.pkg}@${version}`;
    writeFileSync(join(dir, f), dressTypedoc(read(join(dir, f)), { lib, version, coordinate, path }));
  }
}

/** Where an npm package's API reference is, building it into out when the site serves it. */
async function npmReference(lib, out) {
  const dir = join(out, lib.name, "api");
  const done = (version) => onSite(lib.name, version, `${lib.pkg}@${version}`);
  try {
    const meta = await get(`${NPM}/${lib.pkg.replace("/", "%2F")}`);
    if (!meta) return { kind: "unreleased" };
    const doc = JSON.parse(meta.toString("utf8"));
    const version = doc["dist-tags"]?.latest;
    if (!version || !/^\w[\w.+-]*$/.test(version) || !doc.versions?.[version]?.dist) {
      throw new Error(`${lib.pkg}: npm tags no usable latest version (${version})`);
    }
    await typedoc(lib, version, await npmTarball(lib, version, doc.versions[version].dist), dir);
    return done(version);
  } catch (e) {
    if (!(e instanceof Offline)) throw e;
    const cached = newestCachedTarball(lib);
    console.warn(`api-docs: ${e.message}. ${cached ? `Using the cached ${cached.version}.` : "Linking jsdocs.io instead."}`);
    if (!cached) return { kind: "elsewhere", host: "jsdocs.io", href: `https://www.jsdocs.io/package/${lib.pkg}` };
    await typedoc(lib, cached.version, cached.tgz, dir);
    return done(cached.version);
  }
}

/* Built into a fresh folder and swapped in at the end, so a run that stops halfway leaves the last good one. */
const next = `${OUT}.next`;
rmSync(next, { recursive: true, force: true });
const found = {};
for (const lib of libraries()) found[lib.name] = await reference(lib, next);
for (const lib of npmPackages()) {
  if (found[lib.name]) throw new Error(`api-docs: ${lib.name} is both a library in libs/ and an npm package`);
  found[lib.name] = await npmReference(lib, next);
}
rmSync(OUT, { recursive: true, force: true });
if (existsSync(next)) renameSync(next, OUT);
writeAtomic(join(STATE, "libraries.json"), `${JSON.stringify(found, null, 2)}\n`);
for (const [name, r] of Object.entries(found)) {
  console.log(`api-docs: ${name}: ${r.kind === "site" ? `${r.coordinate}, ${r.href}` : r.kind === "elsewhere" ? r.href : "not released yet"}`);
}
