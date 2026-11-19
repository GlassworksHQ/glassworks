// Every frame Kiln lists, from its design data (frames/<id>/design: frame.json, the format in frames/README.md, and
// installer.json, the format in docs/design/kiln/frames-as-data.md). Only JSON and images: the picker, the routes and
// the release route read it without loading any frame's code. kiln.ts loads that, for the frames in ENGINE.
import type { FrameDesign } from '@glassworks/frame-card';
import s1Plus from '../../../frames/aqara-s1-plus/design/frame.json';
import s1PlusInstaller from '../../../frames/aqara-s1-plus/design/installer.json';
import s1PlusRender from '../../../frames/aqara-s1-plus/design/render.webp';
import genericAndroid from '../../../frames/generic-android/design/frame.json';
import genericAndroidInstaller from '../../../frames/generic-android/design/installer.json';
import genericAndroidRender from '../../../frames/generic-android/design/render.webp';
import pixelTablet from '../../../frames/google-pixel-tablet/design/frame.json';
import pixelTabletRender from '../../../frames/google-pixel-tablet/design/render.webp';
import mirror from '../../../frames/lululemon-mirror/design/frame.json';
import mirrorInstaller from '../../../frames/lululemon-mirror/design/installer.json';
import mirrorRender from '../../../frames/lululemon-mirror/design/render.webp';

export const FRAMES = [s1Plus, mirror, genericAndroid, pixelTablet] as FrameDesign[];

/** An image import's URL: Next gives `{ src }`, Vite (the tests) a string. */
const url = (m: string | { src: string }) => (typeof m === 'string' ? m : m.src);

/** Each frame's render (frame.json `render`, made by tools/showcase/render.mjs), by frame id. */
export const RENDERS: Record<string, string> = {
  [s1Plus.id]: url(s1PlusRender),
  [mirror.id]: url(mirrorRender),
  [genericAndroid.id]: url(genericAndroidRender),
  [pixelTablet.id]: url(pixelTabletRender),
};

/** Frames with a published Clarity result (`frames/<id>/clarity/clarity.json`); kiln/apps/kiln/test/frame-designs.test.ts keeps it true. */
export const MEASURED: ReadonlySet<string> = new Set([s1Plus.id]);

/** A frame's release line: its tags (`{version}` is the version) and the files a tag may serve (`{abi}`: one per frame.json `abis`). */
export interface Release { line: string; tag: string; assets: string[] }

/** What Kiln reads of a frame's installer.json before it loads the frame. */
export interface Installer {
  route: string;
  words?: { name: string };
  releases?: Release[];
  /** The first installer page's links: its hash links (/#/wifi) and its release route (/api/release-asset) lead here. */
  legacy?: { hashLinks?: boolean; releaseAsset?: string };
}

/** Each frame's installer.json, by frame id. */
export const INSTALLERS: Record<string, Installer> = {
  [s1Plus.id]: s1PlusInstaller,
  [mirror.id]: mirrorInstaller,
  [genericAndroid.id]: genericAndroidInstaller,
};

/** The frames Kiln's shared wizard runs, by id: kiln.ts has a loader for each, and its routes are theirs. */
export const ENGINE: readonly string[] = [s1Plus.id];

/** The frame.json `abis` of each frame, for `{abi}` in its release assets. */
export const ABIS: Record<string, readonly string[]> = Object.fromEntries(FRAMES.map((f) => [f.id, (f as FrameDesign & { abis?: string[] }).abis ?? []]));
