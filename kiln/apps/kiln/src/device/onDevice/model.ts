// The on-device page (issue 403 part 3, issue 621) without React: which screen comes first, which build and APK fit, and the
// release each line has. OnDevicePage.tsx draws it; facts.ts reads the browser.
import type { OnDevice } from '@glassworks/kiln-core';
import type { FrameDesign } from '@glassworks/frame-card';
import type { LatestRelease } from '../../app/api/release/[frame]/latest/route';
import type { RailStep } from '../../wizard/state';

/** Device, Get the app, Set up; `kiln` is Set up's optional finish over USB; `elsewhere`, `too-old` stop at Device. */
export type TabletScreen = 'device' | 'get' | 'setup' | 'kiln' | 'elsewhere' | 'too-old';

/** A release line, as Kiln's release route serves it (a frame's installer.json `releases`). */
export type Line = string;
export type Abi = 'arm64-v8a' | 'armeabi-v7a';

export type Release =
  | { state: 'loading' }
  | { state: 'none' }
  | { state: 'error'; why: string }
  | ({ state: 'out' } & LatestRelease);

/** Android's Settings, opened from a link. Read on a tablet with real taps (issue 403, 2026-11-16): these two open. */
export const SETTINGS_LINKS = {
  about: 'intent:#Intent;action=android.settings.DEVICE_INFO_SETTINGS;end',
  settings: 'intent:#Intent;action=android.settings.SETTINGS;end',
} as const;

export function firstScreen(on: OnDevice): TabletScreen {
  if (!on.android) return 'elsewhere';
  return on.app === 'too-old' ? 'too-old' : 'device';
}

/** The legacy build only where the version says 6 or 7; where it's hidden the page offers both, Android 8+ first. */
export const buildFor = (on: OnDevice): 'generic' | 'legacy' => (on.app === 'legacy' ? 'legacy' : 'generic');

/**
 * The ABI and whether Kiln knows it: what the browser says it runs as (OnDevice `bits`), else a tested model's one ABI,
 * else 64-bit, unsure: the Get screen then offers both builds (issue 700).
 */
export function abiFor(on: OnDevice, frames: readonly FrameDesign[]): { abi: Abi; sure: boolean } {
  if (on.bits) return { abi: on.bits === 64 ? 'arm64-v8a' : 'armeabi-v7a', sure: true };
  const abis = frames.find((f) => f.id === on.identified.model?.frame)?.abis;
  if (abis?.length === 1 && (abis[0] === 'arm64-v8a' || abis[0] === 'armeabi-v7a')) return { abi: abis[0], sure: true };
  return { abi: 'arm64-v8a', sure: false };
}

/** The download link for `abi` from a line's latest release, through Kiln's release route; undefined before a release. */
export function download(line: Line, r: Release, abi: Abi): { href: string; name: string; version: string } | undefined {
  if (r.state !== 'out') return undefined;
  const name = r.assets.find((n) => n.endsWith(`-${abi}-${r.version}.apk`));
  if (!name) return undefined;
  return { href: `/api/release/${line}?tag=${encodeURIComponent(r.tag)}&name=${encodeURIComponent(name)}`, name, version: r.version };
}

/** A line's latest release from Kiln's route. */
export async function loadRelease(line: Line, get: typeof fetch = fetch): Promise<Release> {
  try {
    const r = await get(`/api/release/${line}/latest`);
    if (!r.ok) return { state: 'error', why: `the release server answered ${r.status}` };
    const { release } = (await r.json()) as { release: LatestRelease | null };
    return release ? { state: 'out', ...release } : { state: 'none' };
  } catch (e) {
    return { state: 'error', why: e instanceof Error ? e.message : String(e) };
  }
}

const NAMES = ['Device', 'Get the app', 'Set up'];
const AT: Record<TabletScreen, number> = { device: 0, elsewhere: 0, 'too-old': 0, get: 1, setup: 2, kiln: 2 };

export function rail(screen: TabletScreen): RailStep[] {
  const at = AT[screen];
  return NAMES.map((name, i) => ({ name, mark: i < at ? 'done' : i > at ? 'todo' : screen === 'too-old' ? 'bad' : 'now' }));
}

