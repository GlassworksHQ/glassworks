// The release lines Kiln downloads Glassworks' own APKs from: each frame's installer.json `releases`, from the frames
// registry. Refract (kiln/workers/refract, refract.glassworks.dev) serves their files to Kiln from releases.json, which
// refractReleases() writes (test/refract-releases.test.ts keeps the two in step), and [frame]/latest/route.ts lists
// each line's newest release. Only these lines pass, and for each only its own tags and asset names.
import { ABIS, INSTALLERS } from '@glassworks/frames/catalog';

export const REPO = 'GlassworksHQ/glassworks';

/** A release line: its tags (the version is the first group), and the asset names a tag may serve. */
export interface ReleaseLine {
  tag: RegExp;
  assets: (version: string) => readonly string[];
}

/** One line as Refract's releases.json holds it: the tag pattern, and every asset name with `{version}` left in. */
export interface RefractLine { line: string; tag: string; assets: string[] }

/** `{abi}`: one name per ABI in the frame's design data. `{version}` stays for the tag's version. */
const names = (id: string, assets: readonly string[]) =>
  assets.flatMap((a) => (a.includes('{abi}') ? (ABIS[id] ?? []).map((abi) => a.replace('{abi}', abi)) : [a]));

const LINES: RefractLine[] = Object.entries(INSTALLERS).flatMap(([id, i]) =>
  (i.releases ?? []).map((r) => ({ line: r.line, tag: r.tag, assets: names(id, r.assets) })));

/**
 * kiln/workers/refract/releases.json: what Refract admits of this repo's releases. Each `.apk` also has its `.sha256`
 * beside it. `REFRACT_RELEASES=write npm test -w @glassworks/kiln-app -- refract-releases` writes the file.
 */
export function refractReleases() {
  const [owner, repo] = REPO.split('/') as [string, string];
  return {
    comment: 'Generated from frames/*/design/installer.json `releases` by refractReleases() in kiln/apps/kiln/src/app/api/release/lines.ts. Don\'t edit it by hand: REFRACT_RELEASES=write npm test -w @glassworks/kiln-app -- refract-releases',
    owner,
    repo,
    prefix: 'glassworks',
    credit: { name: 'Glassworks', url: `https://github.com/${REPO}`, project: 'Glassworks' },
    lines: LINES,
  };
}

export const RELEASES = new Map<string, ReleaseLine>(LINES.map((l) =>
  [l.line, { tag: new RegExp(l.tag), assets: (v: string) => l.assets.map((a) => a.replaceAll('{version}', v)) }] as const));
