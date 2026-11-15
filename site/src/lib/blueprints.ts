import { PANES } from "@glassworks/frame-card";
import { cacheLife } from "next/cache";

/*
 * The panes' Home Assistant blueprints (docs/blueprints.md), as the latest release has them. Each pane's
 * pane.json lists its blueprints; the YAML sits in the pane's module, `blueprints/<file>`. Both are read
 * from GitHub at the release tag when the site is built, so the site lists only files that exist in that
 * release, and its links are the ones a panel on that version offers.
 *
 * BLUEPRINTS_REF=<tag or commit> builds the site from another ref (a preview of unreleased blueprints).
 */

const REPO = "GlassworksHQ/glassworks";
const RAW = `https://raw.githubusercontent.com/${REPO}`;

export type Blueprint = {
  pane: string;
  name: string;
  /** pane.json's one line */
  summary: string;
  /** the file in the repo, e.g. panes/notifications/blueprints/doorbell-panels.yaml */
  path: string;
  /** My Home Assistant's import link: it opens the reader's own Home Assistant, filled in */
  add: string;
  /** the file on GitHub at the release */
  source: string;
};

export type Blueprints = {
  /** the release tag (or BLUEPRINTS_REF) the files come from */
  ref: string;
  list: Blueprint[];
};

/**
 * The latest release's tag, from github.com's /releases/latest redirect (no API call, so no rate limit to
 * share with other builds on the same machine).
 */
async function latestRelease(): Promise<string> {
  const r = await fetch(`https://github.com/${REPO}/releases/latest`, { redirect: "manual" });
  const tag = r.headers.get("location")?.match(/\/releases\/tag\/([^/?#]+)$/)?.[1];
  if (!tag) throw new Error(`src/lib/blueprints.ts: no latest release on github.com (HTTP ${r.status})`);
  return decodeURIComponent(tag);
}

/** The file at [url], or null when the release doesn't have it. Anything else stops the build. */
async function file(url: string): Promise<string | null> {
  const r = await fetch(url);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`src/lib/blueprints.ts: ${url}: HTTP ${r.status}`);
  return r.text();
}

/** The `name:` in the YAML's top-level `blueprint:` block. The app's BlueprintCatalogTest keeps that shape. */
export function blueprintName(yaml: string): string | null {
  const block = /^blueprint:[ \t]*\r?\n((?:(?:[ \t]+[^\n]*)?\r?\n)*)/m.exec(yaml)?.[1] ?? "";
  const name = /^[ \t]{2}name:[ \t]*(.+?)[ \t]*$/m.exec(block)?.[1];
  return name ? name.replace(/^(["'])(.*)\1$/, "$2") : null;
}

export const importLink = (raw: string) =>
  `https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=${encodeURIComponent(raw)}`;

export async function blueprints(): Promise<Blueprints> {
  "use cache";
  // Read once, when the site is built, like the rest of the site's data: the pages stay static.
  cacheLife({ stale: Infinity, revalidate: Infinity, expire: Infinity });
  const ref = process.env.BLUEPRINTS_REF?.trim() || (await latestRelease());
  const list: Blueprint[] = [];
  for (const p of PANES) {
    // A pane that wasn't at this path in the release has none there.
    const json = await file(`${RAW}/${ref}/${p.source}/pane.json`);
    if (!json) continue;
    const listed: { file: string; summary: string }[] = JSON.parse(json).blueprints ?? [];
    const moduleDir = p.source.split("/src/main/")[0];
    for (const b of listed) {
      const path = `${moduleDir}/${b.file}`;
      const raw = `${RAW}/${ref}/${path}`;
      const yaml = await file(raw);
      if (yaml === null) continue;
      const name = blueprintName(yaml);
      if (!name) throw new Error(`src/lib/blueprints.ts: ${path} at ${ref} has no blueprint name`);
      list.push({ pane: p.id, name, summary: b.summary, path, add: importLink(raw), source: `https://github.com/${REPO}/blob/${ref}/${path}` });
    }
  }
  return { ref, list };
}

/** "v0.5.0" → "release 0.5.0"; a commit (a BLUEPRINTS_REF preview) → "commit 97f81f5". */
export const releaseName = (ref: string) =>
  /^[0-9a-f]{7,40}$/.test(ref) ? `commit ${ref.slice(0, 7)}` : `release ${ref.replace(/^v(?=\d)/, "")}`;
