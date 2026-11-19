// The release lines Kiln's release route serves (/api/release/<line>, [frame]/route.ts), and what it lists as their
// latest ([frame]/latest/route.ts). Only these lines pass, and for each only its own tags and asset names.
import generic from '../../../../../../../frames/generic-android/design/frame.json';

export const REPO = 'GlassworksHQ/glassworks';

/** A release line: its tags (the version is the first group), and the asset names a tag may serve. */
export interface ReleaseLine {
  tag: RegExp;
  assets: (version: string) => readonly string[];
}

/** One APK per ABI in the generic frame's design data, named as generic-release.yml names them. */
const perAbi = (stem: string) => (v: string) => generic.abis.map((abi) => `glassworks-${stem}-${abi}-${v}.apk`);

export const RELEASES = new Map<string, ReleaseLine>([
  // Glassworks: `vX.Y.Z` (every release so far) or `aqara-s1-plus-vX.Y.Z`, and the S1's armeabi-v7a APK.
  ['aqara-s1-plus', { tag: /^(?:aqara-s1-plus-)?v(\d+\.\d+\.\d+)$/, assets: (v) => [`s1-kiosk-${v}.apk`] }],
  // Glassworks for generic Android (Android 8+) and its legacy build (6 and 7): docs/releasing.md.
  ['generic-android', { tag: /^generic-android-v(\d+\.\d+\.\d+)$/, assets: perAbi('generic-android') }],
  ['generic-android-legacy', { tag: /^generic-android-legacy-v(\d+\.\d+\.\d+)$/, assets: perAbi('generic-android-legacy') }],
]);
