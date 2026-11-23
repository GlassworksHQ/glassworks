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
import galaxyTabA2016 from '../../../frames/samsung-galaxy-tab-a-10-1-2016/design/frame.json';
import galaxyTabA2016Render from '../../../frames/samsung-galaxy-tab-a-10-1-2016/design/render.webp';

export const FRAMES = [s1Plus, mirror, genericAndroid, pixelTablet, galaxyTabA2016] as FrameDesign[];

/**
 * The frame whose install a frame uses: its own, or for a tested device (`kind` tested), its `base`'s. A tested device
 * has no recipe of its own (frame-card kindProblems); it installs as its base does.
 */
export const installsAs = (f: Pick<FrameDesign, 'id' | 'kind' | 'base'>): string => (f.kind === 'tested' && f.base ? f.base : f.id);

/** Where Kiln installs a frame: its `kiln` route, or a tested device's base's. Undefined where Kiln doesn't. */
export const kilnRoute = (f: Pick<FrameDesign, 'id' | 'kind' | 'base' | 'kiln'>): string | undefined =>
  f.kiln ?? (f.kind === 'tested' ? FRAMES.find((x) => x.id === installsAs(f))?.kiln : undefined);

/** An image import's URL: Next gives `{ src }`, Vite (the tests) a string. */
const url = (m: string | { src: string }) => (typeof m === 'string' ? m : m.src);

/** Each frame's render (frame.json `render`, made by tools/showcase/render.mjs), by frame id. */
export const RENDERS: Record<string, string> = {
  [s1Plus.id]: url(s1PlusRender),
  [mirror.id]: url(mirrorRender),
  [genericAndroid.id]: url(genericAndroidRender),
  [pixelTablet.id]: url(pixelTabletRender),
  [galaxyTabA2016.id]: url(galaxyTabA2016Render),
};

/** Frames with a published Clarity result (`frames/<id>/clarity/clarity.json`); kiln/apps/kiln/test/frame-designs.test.ts keeps it true. */
export const MEASURED: ReadonlySet<string> = new Set([s1Plus.id, pixelTablet.id]);

/** A frame's release line: its tags (`{version}` is the version) and the files a tag may serve (`{abi}`: one per frame.json `abis`). */
export interface Release { line: string; tag: string; assets: string[] }

/** What Kiln reads of a frame's installer.json before it loads the frame. */
export interface Installer {
  route: string;
  words?: { name: string };
  /**
   * Its flows' steps (docs/design/kiln/frames-as-data.md). `route`: the step's address under the frame's route ("" is
   * the route itself), `title` the first part of that page's title. Kiln gives every frame its steps' routes, the
   * standard flow's after them, and resumes at each.
   */
  flows?: Record<string, { steps: { id: string; route?: string; title?: string }[] }>;
  /** The way back: `restore-backup` has Kiln's undo routes. */
  wayBack?: { how: string };
  /** The device has a touchscreen. Without one (false), set-up never offers "do it on the panel". */
  touch?: boolean;
  /** The picker's What you'll need bar for this frame: what to have at hand, and its way back, in a sentence or two. */
  picker?: { needs: string[]; wayBack: string };
  /** An install from the device's own browser, at `route` under the frame's, its page's title `title`. */
  onDevice?: { route: string; title: string };
  /**
   * The standard flow after its install (Kiln's App, Wi-Fi, Home Assistant, Panes, Radio, Done): every step unless this
   * says otherwise. `remove`: a step it leaves out, by name, with why. A step its hardware lacks needs no line here.
   */
  setUp?: { remove?: Record<string, string> };
  releases?: Release[];
  /** The first installer page's links: its hash links (/#/wifi) lead here. */
  legacy?: { hashLinks?: boolean };
}

/** An installer.json as Kiln reads it, checked where it comes in: a frame names no page of its own (issue 621). */
function installer(d: Installer & { page?: unknown }): Installer {
  if (d.page !== undefined) throw new Error('installer.json page: gone. Every frame runs on Kiln\'s one page, from its flows');
  return d;
}

/** Each frame's installer.json, by frame id. */
export const INSTALLERS: Record<string, Installer> = {
  [s1Plus.id]: installer(s1PlusInstaller),
  [mirror.id]: installer(mirrorInstaller),
  [genericAndroid.id]: installer(genericAndroidInstaller),
};

/** The frames Kiln's shared wizard runs, by id: kiln.ts has a loader for each, and its routes are theirs. */
export const ENGINE: readonly string[] = [s1Plus.id, mirror.id, genericAndroid.id];

/** The frame.json `abis` of each frame, for `{abi}` in its release assets. */
export const ABIS: Record<string, readonly string[]> = Object.fromEntries(FRAMES.map((f) => [f.id, (f as FrameDesign & { abis?: string[] }).abis ?? []]));
