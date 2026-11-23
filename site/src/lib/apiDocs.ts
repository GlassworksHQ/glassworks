import fs from "node:fs";
import path from "node:path";
import type { SitePath } from "./seo";

/*
 * Each library's API reference, as scripts/api-docs.mjs found it when the build started: a Kotlin library
 * in ../libs from its javadoc jar on Maven Central, an npm package from its tarball through TypeDoc. It is
 * served here, linked on javadoc.io or jsdocs.io when the registry didn't answer, or not released yet.
 * `coordinate` is what a build names it by: dev.glassworks:<name>:<version> or @glassworks/<name>@<version>.
 */
export type ApiReference =
  | { kind: "site"; version: string; href: SitePath; coordinate: string }
  | { kind: "elsewhere"; host: string; href: string }
  | { kind: "unreleased" };

const FILE = path.join(/* turbopackIgnore: true */ process.cwd(), ".api-docs/libraries.json");

function parse(name: string, v: unknown): ApiReference {
  const r = v as Partial<Record<"kind" | "version" | "href" | "coordinate" | "host", unknown>>;
  if (r?.kind === "unreleased") return { kind: "unreleased" };
  if (r?.kind === "elsewhere" && typeof r.host === "string" && typeof r.href === "string" && r.href.startsWith("https://"))
    return { kind: "elsewhere", host: r.host, href: r.href };
  if (
    r?.kind === "site" &&
    typeof r.version === "string" &&
    typeof r.coordinate === "string" &&
    typeof r.href === "string" &&
    r.href.startsWith("/")
  )
    return { kind: "site", version: r.version, href: r.href as SitePath, coordinate: r.coordinate };
  throw new Error(`${FILE}: ${name}: ${JSON.stringify(v)} isn't an API reference`);
}

let cache: Map<string, ApiReference> | undefined;

/** Every library in ../libs and every published npm package, by name. */
export function apiReferences(): Map<string, ApiReference> {
  if (cache) return cache;
  if (!fs.existsSync(FILE)) throw new Error(`${FILE} is missing: run node scripts/api-docs.mjs (npm run build and npm run dev do)`);
  const json = JSON.parse(fs.readFileSync(FILE, "utf8")) as Record<string, unknown>;
  cache = new Map(Object.entries(json).map(([name, v]) => [name, parse(name, v)]));
  return cache;
}

/** The API reference of the library a doc is the README of (libs/, kiln/packages/ or packages/<name>/README.md), if it's one. */
export function apiReferenceOf(file: string): { name: string; reference: ApiReference } | undefined {
  const name = file.match(/^(?:libs|kiln\/packages|packages)\/([^/]+)\/README\.md$/)?.[1];
  const reference = name ? apiReferences().get(name) : undefined;
  return name && reference ? { name, reference } : undefined;
}
