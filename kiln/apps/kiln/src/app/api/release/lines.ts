// The release lines Kiln's release route serves (/api/release/<line>, [frame]/route.ts), and what it lists as their
// latest ([frame]/latest/route.ts): each frame's installer.json `releases`, from the frames registry. Only these lines
// pass, and for each only its own tags and asset names.
import { ABIS, INSTALLERS } from '@glassworks/frames/catalog';

export const REPO = 'GlassworksHQ/glassworks';

/** A release line: its tags (the version is the first group), and the asset names a tag may serve. */
export interface ReleaseLine {
  tag: RegExp;
  assets: (version: string) => readonly string[];
}

/** `{abi}`: one name per ABI in the frame's design data; `{version}`: the tag's. */
const names = (id: string, assets: readonly string[]) => (v: string) =>
  assets.flatMap((a) => (a.includes('{abi}') ? (ABIS[id] ?? []).map((abi) => a.replace('{abi}', abi)) : [a])).map((a) => a.replace('{version}', v));

export const RELEASES = new Map<string, ReleaseLine>(Object.entries(INSTALLERS).flatMap(([id, i]) =>
  (i.releases ?? []).map((r) => [r.line, { tag: new RegExp(r.tag), assets: names(id, r.assets) }] as const)));

/** The line the first installer page's release route served, before #28 (installer.json `legacy.releaseAsset`). */
export const LEGACY_LINE = Object.values(INSTALLERS).find((i) => i.legacy?.releaseAsset)?.legacy?.releaseAsset;
