import fs from "node:fs";
import path from "node:path";
import { links } from "./site";

/*
 * The latest app release: the first "## [x.y.z] - YYYY-MM-DD" heading in the repo's CHANGELOG.md,
 * the file the release workflow publishes notes from. The build fails when there is none.
 */
const CHANGELOG = path.join(process.cwd(), "../CHANGELOG.md");

export type Release = { version: string; date: string; href: string };

function latest(): Release {
  const m = fs.readFileSync(CHANGELOG, "utf8").match(/^## \[(\d+\.\d+\.\d+)\] - (\d{4}-\d{2}-\d{2})$/m);
  if (!m) throw new Error(`${CHANGELOG}: no "## [x.y.z] - YYYY-MM-DD" release heading`);
  return { version: m[1], date: m[2], href: `${links.s1}/releases/tag/v${m[1]}` };
}

export const release = latest();
