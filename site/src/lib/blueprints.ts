import { cacheLife } from "next/cache";
import { frameById } from "./frames";

/*
 * Every Home Assistant blueprint a panel offers (docs/blueprints.md), as the newest app release has them: the
 * list the app itself reads, its asset blueprints.json. BlueprintCatalogTest writes that list from each
 * pane's pane.json and each core settings page's page.json, and keeps it in step with the YAML files at
 * every commit. It is read from GitHub at the release tag when the site is built, so the site lists what a
 * panel on that version offers, with the same links.
 *
 * The newest app release is the newest published one on any frame's app line (`aqara-s1-plus-v1.2.3`,
 * `generic-android-v1.2.3`, `generic-android-legacy-v1.2.3`, or the S1's plain `v1.2.3` up to 0.4.4), whose
 * tree has the file: a release from before the blueprints has none, and the one before it is tried. One call
 * to GitHub's Releases API per build (with GITHUB_TOKEN when it is set), like the app's own update check.
 *
 * BLUEPRINTS_REF=<tag or commit> builds the site from another ref (a preview of unreleased blueprints).
 */

const REPO = "GlassworksHQ/glassworks";
const RAW = `https://raw.githubusercontent.com/${REPO}`;
/** The newest 100 releases, as the app's update check reads them (Releases.LIST_URL in core). */
const RELEASES = `https://api.github.com/repos/${REPO}/releases?per_page=100`;
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
  /** the release tag (or BLUEPRINTS_REF) the files come from; null when no release has them yet */
  ref: string | null;
  list: Blueprint[];
};

/** The fields of a release in GitHub's Releases API that this file reads. */
export type GithubRelease = { tag_name: string; draft: boolean; prerelease: boolean; published_at: string | null };

/** The S1 app's frame: its releases up to 0.4.4 were tagged a plain `v1.2.3`. */
const PLAIN_TAGS = "aqara-s1-plus";

/**
 * An app release's tag, `<line>-v1.2.3` or a plain `v1.2.3`: the line is a frame's id, or its legacy build's
 * (`<id>-legacy`), as ReleaseLine in core has them. Null for any other tag: a library's (`libs/<name>/v…`), a
 * frame's own (`frames/<id>/v…`), a pre-release (`v1.2.3-rc.1`).
 */
export function appTag(tag: string): { frame: string; legacy: boolean; version: string } | null {
  const m = /^(?:([a-z0-9-]+)-)?v(\d+\.\d+\.\d+)$/.exec(tag);
  if (!m) return null;
  const line = m[1] ?? PLAIN_TAGS;
  const legacy = line.endsWith("-legacy");
  const frame = legacy ? line.slice(0, -"-legacy".length) : line;
  return frameById(frame) ? { frame, legacy, version: m[2]! } : null;
}

/** The app releases' tags in [list], newest published first: every frame's line, and the old plain tags. */
export function appReleases(list: GithubRelease[]): string[] {
  return list
    .filter((r) => !r.draft && !r.prerelease && r.published_at && appTag(r.tag_name))
    .sort((a, b) => Date.parse(b.published_at!) - Date.parse(a.published_at!))
    .map((r) => r.tag_name);
}

/** The file at [url], or null when the release doesn't have it. Anything else stops the build. */
async function file(get: typeof fetch, url: string): Promise<string | null> {
  const r = await get(url);
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

/** The blueprints at [pinned] (BLUEPRINTS_REF), else at the newest app release that has them. */
export async function findBlueprints(get: typeof fetch, pinned?: string, token?: string): Promise<Blueprints> {
  if (pinned) {
    const json = await file(get, `${RAW}/${pinned}/${ASSET}`);
    return { ref: pinned, list: json ? fromAsset(json, pinned) : [] };
  }
  const r = await get(RELEASES, {
    headers: { accept: "application/vnd.github+json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
  });
  if (!r.ok) throw new Error(`src/lib/blueprints.ts: ${RELEASES}: HTTP ${r.status}${token ? "" : " (set GITHUB_TOKEN for a higher rate limit)"}`);
  for (const tag of appReleases(await r.json())) {
    const json = await file(get, `${RAW}/${tag}/${ASSET}`);
    if (json) return { ref: tag, list: fromAsset(json, tag) };
  }
  return { ref: null, list: [] };
}

export async function blueprints(): Promise<Blueprints> {
  "use cache";
  // Read once, when the site is built, like the rest of the site's data: the pages stay static.
  cacheLife({ stale: Infinity, revalidate: Infinity, expire: Infinity });
  return findBlueprints(fetch, process.env.BLUEPRINTS_REF?.trim() || undefined, process.env.GITHUB_TOKEN?.trim() || undefined);
}

/**
 * The release [ref] names, by its frame: "Aqara S1 Plus release 0.5.0" (`aqara-s1-plus-v0.5.0`, or `v0.5.0`
 * up to 0.4.4), "Generic Android legacy release 0.2.0". A commit (a BLUEPRINTS_REF preview) → "commit 97f81f5";
 * any other ref is itself.
 */
export function releaseName(ref: string): string {
  if (/^[0-9a-f]{7,40}$/.test(ref)) return `commit ${ref.slice(0, 7)}`;
  const t = appTag(ref);
  return t ? `${frameById(t.frame)!.name}${t.legacy ? " legacy" : ""} release ${t.version}` : ref;
}
