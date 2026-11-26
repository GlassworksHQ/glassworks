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
export const MEASURED: ReadonlySet<string> = new Set([s1Plus.id, pixelTablet.id, mirror.id]);

/** A frame's release line: its tags (`{version}` is the version) and the files a tag may serve (`{abi}`: one per frame.json `abis`). */
export interface Release { line: string; tag: string; assets: string[] }

/** How a flow step reaches the device. `agent`: the frame's own agent on the running device (an in-place install's bundle). */
export const FLOW_TRANSPORTS = ['fel', 'fastboot', 'adb', 'usb', 'agent', 'none'] as const;
export type FlowTransport = (typeof FLOW_TRANSPORTS)[number];

/** Which of the engine's flows runs a flow: whole images, the app alone, or the stock software changed in place. */
export const FLOW_ENGINES = ['image', 'app', 'in-place'] as const;
export type FlowEngine = (typeof FLOW_ENGINES)[number];

/**
 * A way back. `restore-backup`: Kiln's undo routes; `restore-blocks`: the restore image an in-place install kept
 * (`restore`, the file its `in-place` step names) puts back every block it changed. Others are the frame's own words.
 */
export type WayBack = { how: 'restore-blocks'; restore: string } | { how: string; [k: string]: unknown };

/** A flow step as installer.json declares it (docs/design/kiln/frames-as-data.md). `transport` is one of FLOW_TRANSPORTS. */
export interface FlowStep { id: string; route?: string; title?: string; transport?: string }

/** A flow of installer.json. `engine`: which engine flow runs it; `wayBack`: its own, in place of the frame's. */
export interface Flow { steps: FlowStep[]; engine?: FlowEngine; wayBack?: WayBack }

/** What Kiln reads of a frame's installer.json before it loads the frame. */
export interface Installer {
  route: string;
  /** `time.total`: how long its install usually takes, which the picker's What you'll need says (FrameWords `time`). */
  words?: { name: string; time?: { total?: string } };
  /**
   * Its flows' steps (docs/design/kiln/frames-as-data.md). `route`: the step's address under the frame's route ("" is
   * the route itself), `title` the first part of that page's title. Kiln gives every frame its steps' routes, the
   * standard flow's after them, and resumes at each.
   */
  flows?: Record<string, Flow>;
  /** The way back: `restore-backup` has Kiln's undo routes. */
  wayBack?: WayBack;
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
function installer(d: Omit<Installer, 'flows'> & { page?: unknown; flows?: Record<string, { steps: FlowStep[]; engine?: string; wayBack?: WayBack }> }): Installer {
  if (d.page !== undefined) throw new Error('installer.json page: gone. Every frame runs on Kiln\'s one page, from its flows');
  const restoreFile = (w: WayBack | undefined, at: string) => {
    if (w?.how === 'restore-blocks' && (typeof w.restore !== 'string' || !w.restore)) throw new Error(`installer.json ${at}: restore-blocks needs its restore file`);
  };
  restoreFile(d.wayBack, 'wayBack');
  for (const [name, flow] of Object.entries(d.flows ?? {})) {
    if (flow.engine !== undefined && !(FLOW_ENGINES as readonly string[]).includes(flow.engine)) throw new Error(`installer.json flows.${name}.engine: ${flow.engine}`);
    restoreFile(flow.wayBack, `flows.${name}.wayBack`);
    for (const s of flow.steps) {
      if (s.transport !== undefined && !(FLOW_TRANSPORTS as readonly string[]).includes(s.transport)) throw new Error(`installer.json flows.${name}.${s.id}: transport ${s.transport}`);
    }
  }
  return d as Installer;
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
