import { cacheLife } from "next/cache";

/*
 * Every Home Assistant blueprint a panel offers (docs/blueprints.md), as the latest release has them: the
 * list the app itself reads, its asset blueprints.json. BlueprintCatalogTest writes that list from each
 * pane's pane.json and each core settings page's page.json, and keeps it in step with the YAML files at
 * every commit. It is read from GitHub at the release tag when the site is built, so the site lists what a
 * panel on that version offers, with the same links. A release without the file has none.
 *
 * BLUEPRINTS_REF=<tag or commit> builds the site from another ref (a preview of unreleased blueprints).
 */

const REPO = "GlassworksHQ/glassworks";
const RAW = `https://raw.githubusercontent.com/${REPO}`;
/** The app's list (Blueprint.ASSET in pane-api). */
export const ASSET = "frames/aqara-s1-plus/android/src/main/assets/blueprints.json";

export type Blueprint = {
  /** the pane, or the core settings page, that offers it */
  pane: string;
  name: string;
  /** its one line from pane.json or page.json */
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

export const importLink = (raw: string) =>
  `https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=${encodeURIComponent(raw)}`;

/** The asset's `[{pane, path, name, summary}]` at [ref], with the links a panel on that ref offers. */
export function fromAsset(json: string, ref: string): Blueprint[] {
  const listed: { pane: string; path: string; name: string; summary: string }[] = JSON.parse(json);
  return listed.map(({ pane, path, name, summary }) => ({
    pane,
    name,
    summary,
    path,
    add: importLink(`${RAW}/${ref}/${path}`),
    source: `https://github.com/${REPO}/blob/${ref}/${path}`,
  }));
}

/** The core's own blueprints: its settings pages', in `core/blueprints/`, on every panel whatever its panes. */
export const isCore = (b: Blueprint) => b.path.startsWith("core/");

export async function blueprints(): Promise<Blueprints> {
  "use cache";
  // Read once, when the site is built, like the rest of the site's data: the pages stay static.
  cacheLife({ stale: Infinity, revalidate: Infinity, expire: Infinity });
  const ref = process.env.BLUEPRINTS_REF?.trim() || (await latestRelease());
  const json = await file(`${RAW}/${ref}/${ASSET}`);
  return { ref, list: json ? fromAsset(json, ref) : [] };
}

/** "v0.5.0" → "release 0.5.0"; a commit (a BLUEPRINTS_REF preview) → "commit 97f81f5". */
export const releaseName = (ref: string) =>
  /^[0-9a-f]{7,40}$/.test(ref) ? `commit ${ref.slice(0, 7)}` : `release ${ref.replace(/^v(?=\d)/, "")}`;
