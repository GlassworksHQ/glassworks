/**
 * The libraries' API reference, from Maven Central. `npm run build` and `npm run dev` run it first:
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
 * What it found goes to .api-docs/libraries.json, which src/lib/apiDocs.ts reads:
 *   { "<name>": { "kind": "site", "version": "0.1.0", "href": "/docs/libraries/<name>/api/index.html" }
 *             | { "kind": "javadoc.io", "href": "https://javadoc.io/doc/dev.glassworks/<name>" }
 *             | { "kind": "unreleased" } }
 * A library that isn't on Central yet, or isn't published at all, is "unreleased". When Central doesn't
 * answer, the newest cached jar is used, and with none cached the page links javadoc.io and the build
 * goes on. A checksum that doesn't match fails the build.
 *
 * Jars are cached in .api-docs/cache/<repository>/ with their checksums, so a rebuild only asks Central for the
 * metadata. Writes only public/docs/libraries/ and .api-docs/ (both git-ignored).
 * MAVEN_REPO=<url> reads another Maven repository (a local one, to try a release before Central has it).
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";

const site = join(dirname(fileURLToPath(import.meta.url)), "..");
const repo = join(site, "..");
const SITE = "https://www.glassworks.dev";
const MAVEN = (process.env.MAVEN_REPO ?? "https://repo1.maven.org/maven2").replace(/\/$/, "");
const GROUP = "dev.glassworks";
const OUT = join(site, "public/docs/libraries");
const STATE = join(site, ".api-docs");
// one cache per repository, so a jar from MAVEN_REPO never stands in for Central's
const CACHE = join(STATE, "cache", MAVEN.replace(/^https?:\/\//, "").replace(/[^\w.-]+/g, "_"));

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

/** The newest jar in the cache, for a build that can't reach Central. */
function newestCached(name) {
  const dir = join(CACHE, name);
  if (!existsSync(dir)) return undefined;
  const semver = (v) => v.split(/[.-]/).map((x) => Number(x) || 0);
  const newer = (a, b) => {
    const [x, y] = [semver(a), semver(b)];
    for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (y[i] ?? 0) - (x[i] ?? 0);
    return 0;
  };
  for (const version of readdirSync(dir).sort(newer)) {
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

/** One Dokka page with the site's parts added: a few tags in, nothing of Dokka's taken out. */
function dress(html, { lib, version, path }) {
  const base = `/docs/libraries/${lib.name}/api/`;
  let head = `<link rel="canonical" href="${SITE}${base}${path}">\n<link href="/api-reference.css" rel="Stylesheet">\n`;
  if (path === "index.html") {
    const title = `${lib.name} ${version} API reference · Glassworks`;
    const description = `The API reference of ${GROUP}:${lib.name} ${version}, every public class and function. ${lib.description}`;
    html = html.replace(/<title>[^<]*<\/title>/, `<title>${esc(title)}</title>`);
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
  const crumbs =
    `<nav class="gw-crumbs" aria-label="Glassworks"><a class="gw-home" href="/docs">Docs</a>` +
    `<a href="/docs/libraries/${lib.name}">${esc(lib.name)}</a></nav>`;
  return html
    .replace(/<link href="[^"]*images\/logo-icon\.svg" rel="icon"[^>]*>/, '<link href="/icon.svg" rel="icon" type="image/svg+xml">')
    .replace("</head>", `${head}</head>`)
    .replace(/(<header class="navigation[^>]*>)/, `$1\n${crumbs}`)
    .replace(/(<a class="library-name--link"[^>]*>)\s*[^<]*?\s*(<\/a>)/, "$1API$2");
}

/** Unpack one jar into dir. */
function unpack(jar, dir, lib, version) {
  for (const [path, data] of unzip(jar)) {
    const to = join(dir, path);
    mkdirSync(dirname(to), { recursive: true });
    writeFileSync(to, path.endsWith(".html") ? dress(data.toString("utf8"), { lib, version, path }) : data);
  }
  if (!existsSync(join(dir, "index.html"))) throw new Error(`${lib.name} ${version}: the javadoc jar has no index.html`);
}

const href = (name) => `/docs/libraries/${name}/api/index.html`;
const javadocIo = (name) => `https://javadoc.io/doc/${GROUP}/${name}`;

/** Where name's API reference is, unpacking it into out when the site serves it. */
async function reference(lib, out) {
  if (!lib.published) return { kind: "unreleased" };
  const dir = join(out, lib.name, "api");
  try {
    const meta = await get(`${MAVEN}/${GROUP.replace(/\./g, "/")}/${lib.name}/maven-metadata.xml`);
    if (!meta) return { kind: "unreleased" };
    const xml = meta.toString("utf8");
    const version = (xml.match(/<release>([^<]+)<\/release>/) ?? xml.match(/<latest>([^<]+)<\/latest>/))?.[1].trim();
    if (!version) throw new Error(`${lib.name}: maven-metadata.xml names no release`);
    unpack(await javadocJar(lib.name, version), dir, lib, version);
    return { kind: "site", version, href: href(lib.name) };
  } catch (e) {
    if (!(e instanceof Offline)) throw e;
    const cached = newestCached(lib.name);
    console.warn(`api-docs: ${e.message}. ${cached ? `Using the cached ${cached.version}.` : "Linking javadoc.io instead."}`);
    if (!cached) return { kind: "javadoc.io", href: javadocIo(lib.name) };
    unpack(cached.jar, dir, lib, cached.version);
    return { kind: "site", version: cached.version, href: href(lib.name) };
  }
}

/* Built into a fresh folder and swapped in at the end, so a run that stops halfway leaves the last good one. */
const next = `${OUT}.next`;
rmSync(next, { recursive: true, force: true });
const found = {};
for (const lib of libraries()) found[lib.name] = await reference(lib, next);
rmSync(OUT, { recursive: true, force: true });
if (existsSync(next)) renameSync(next, OUT);
writeAtomic(join(STATE, "libraries.json"), `${JSON.stringify(found, null, 2)}\n`);
for (const [name, r] of Object.entries(found)) console.log(`api-docs: ${name}: ${r.kind === "site" ? `${r.version}, ${r.href}` : r.kind === "javadoc.io" ? r.href : "not on Maven Central"}`);
