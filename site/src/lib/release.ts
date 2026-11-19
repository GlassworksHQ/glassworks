import fs from "node:fs";
import path from "node:path";
import { cacheLife } from "next/cache";
import { S1, appReleases, appTag, githubReleases, type GithubRelease } from "./blueprints";
import { links } from "./site";

/*
 * The latest app release: the newest "## [x.y.z] - YYYY-MM-DD" heading in the repo's CHANGELOG.md (the file
 * the release workflow publishes notes from) that GitHub has a published S1 release for, linked at that
 * release's own tag. The S1's line tags it `aqara-s1-plus-v1.2.3`; releases up to 0.4.4 have a plain `v1.2.3`.
 * A version whose section is in but isn't released yet (the release PR merged, its tag not pushed) is skipped.
 * The releases list is the one the blueprints read (blueprints.ts), so the build still asks GitHub once.
 * The build fails when no version has a release.
 */
const CHANGELOG = path.join(process.cwd(), "../CHANGELOG.md");

export type Release = { version: string; date: string; tag: string; href: string };

/** The S1 app's latest release in [changelog] that [list] has published, at its tag. */
export function latestRelease(changelog: string, list: GithubRelease[]): Release {
  const tags = new Map<string, string>();
  for (const tag of appReleases(list)) {
    const t = appTag(tag)!;
    if (t.frame === S1 && !t.legacy && !tags.has(t.version)) tags.set(t.version, tag);
  }
  const headings = [...changelog.matchAll(/^## \[(\d+\.\d+\.\d+)\] - (\d{4}-\d{2}-\d{2})$/gm)];
  if (!headings.length) throw new Error(`${CHANGELOG}: no "## [x.y.z] - YYYY-MM-DD" release heading`);
  for (const [, version, date] of headings) {
    const tag = tags.get(version!);
    if (tag) return { version: version!, date: date!, tag, href: `${links.s1}/releases/tag/${tag}` };
  }
  throw new Error(`${CHANGELOG}: no version has a published ${S1} release among GitHub's newest releases`);
}

export async function release(): Promise<Release> {
  "use cache";
  // Read once, when the site is built, like the blueprints: the pages stay static.
  cacheLife({ stale: Infinity, revalidate: Infinity, expire: Infinity });
  return latestRelease(fs.readFileSync(CHANGELOG, "utf8"), await githubReleases());
}
