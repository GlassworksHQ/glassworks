import fs from "node:fs";
import path from "node:path";
import type { SitePath } from "./seo";

/*
 * Each Kotlin library's API reference, as scripts/api-docs.mjs found it on Maven Central when the build
 * started: unpacked here from its javadoc jar, linked on javadoc.io when Central didn't answer, or not
 * released yet. Only the libraries in ../libs have one; the TypeScript ones in packages/ don't.
 */
export type ApiReference =
  | { kind: "site"; version: string; href: SitePath }
  | { kind: "javadoc.io"; href: string }
  | { kind: "unreleased" };

const FILE = path.join(/* turbopackIgnore: true */ process.cwd(), ".api-docs/libraries.json");

function parse(name: string, v: unknown): ApiReference {
  const r = v as Partial<Record<"kind" | "version" | "href", unknown>>;
  if (r?.kind === "unreleased") return { kind: "unreleased" };
  if (r?.kind === "javadoc.io" && typeof r.href === "string") return { kind: "javadoc.io", href: r.href };
  if (r?.kind === "site" && typeof r.version === "string" && typeof r.href === "string" && r.href.startsWith("/"))
    return { kind: "site", version: r.version, href: r.href as SitePath };
  throw new Error(`${FILE}: ${name}: ${JSON.stringify(v)} isn't an API reference`);
}

let cache: Map<string, ApiReference> | undefined;

/** Every library in ../libs, by name. */
export function apiReferences(): Map<string, ApiReference> {
  if (cache) return cache;
  if (!fs.existsSync(FILE)) throw new Error(`${FILE} is missing: run node scripts/api-docs.mjs (npm run build and npm run dev do)`);
  const json = JSON.parse(fs.readFileSync(FILE, "utf8")) as Record<string, unknown>;
  cache = new Map(Object.entries(json).map(([name, v]) => [name, parse(name, v)]));
  return cache;
}

/** The API reference of the library a doc is the README of (libs/<name>/README.md), if it's one. */
export function apiReferenceOf(file: string): { name: string; reference: ApiReference } | undefined {
  const name = file.match(/^libs\/([^/]+)\/README\.md$/)?.[1];
  const reference = name ? apiReferences().get(name) : undefined;
  return name && reference ? { name, reference } : undefined;
}
