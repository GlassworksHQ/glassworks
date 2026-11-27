// A frame's design data, `frames/<id>/design/frame.json` (the format is in frames/README.md), and the
// card's words for it. Kiln and glassworks.dev read the same files through these types.
import type { FrameFacts, FrameRun } from './completeness';

type V3 = [number, number, number];
/** a side's colour facing away from the light, and facing it */
type Sides = { dark: string; light: string };

/**
 * A frame's look on a dark page, e.g. the Pixel Tablet in Hazel beside its Porcelain: only the colours that change.
 * The model draws each colour as CSS light-dark(), so the page's color-scheme picks the look.
 */
export interface DarkLook {
  face?: string;
  sides?: Sides;
}

/** The device's look in millimetres; the site and Kiln draw the 3D mockup from it. */
export interface FrameModel {
  body: { shape: 'rect'; width: number; height: number; depth: number; radius: number };
  /** frame around the screen, mm */
  bezel: number;
  /** without width and height, the screen is the face minus the bezel */
  screen: { radius: number; width?: number; height?: number; top?: number };
  /** the body's sides: the colour facing away from the light, and facing it */
  sides: Sides;
  finish: 'glass' | 'mirror';
  /** the front's colour around the screen, e.g. a white bezel; black glass when left out */
  face?: string;
  /** `desk`: on a desk or counter, seen from a little above, e.g. in a dock */
  mount: 'wall' | 'floor' | 'lean' | 'desk';
  /** for lean and desk: how far the top tilts back, degrees */
  lean?: number;
  /** parts outside the body: tubes that move with it, and a dock it sits in */
  parts?: (TubePart | DockPart)[];
  /** the look on a dark page; a part's own colours have their own `dark` */
  dark?: DarkLook;
}

/** A tube, e.g. a stand's leg: mm from the body's centre (x right, y down, z towards you), leaning with the body. */
export interface TubePart { kind: 'tube'; from: V3; to: V3; diameter: number; color: string; dark?: { color: string } }

/**
 * A dock the body sits in, on the floor behind it (the Pixel Tablet's speaker dock). Its front leans with
 * the body's back; it is `height` tall and `depth` deep along the floor, and holds the body's lowest edge
 * `gap` above the floor. `sides` shades it like the body's sides.
 * The card lifts the body off it on hover. mm.
 */
export interface DockPart {
  kind: 'dock';
  width: number;
  depth: number;
  height: number;
  gap: number;
  sides: Sides;
  /** its look on a dark page, as the model's `dark` */
  dark?: { sides: Sides };
}

/** The same stages as the Glassworks status badges (planned, under development, alpha, beta, stable). */
export type Stage = 'planned' | 'development' | 'alpha' | 'beta' | 'stable';
export type Support = Stage | 'depends' | 'unknown' | 'no';
export const RATING_KEYS = ['difficulty', 'reversible', 'modifiesAndroid', 'root', 'opensDevice', 'vendorApps', 'wipes', 'backup', 'updates'] as const;
export type RatingKey = (typeof RATING_KEYS)[number];
/** Android's ABI names (`Build.SUPPORTED_ABIS`) */
export const ABIS = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] as const;
export type Abi = (typeof ABIS)[number];
/**
 * The three kinds of device (issue 403, maintainer 2026-11-16). `full`: a frame with its own app and a Kiln
 * recipe (the S1). `device`: a frame that runs the generic app, with a recipe or settings of its own
 * (the Mirror, Generic Android). `tested`: a device that installs the generic app as it is, with no
 * recipe or code of its own; `base` names the frame whose app and install it uses.
 */
export const FRAME_KINDS = ['full', 'device', 'tested'] as const;
export type FrameKind = (typeof FRAME_KINDS)[number];
export const KIND_TEXT: Record<FrameKind, string> = { full: 'Full frame', device: 'Device frame', tested: 'Tested device' };

export interface FrameDesign {
  id: string;
  /** full frame, device frame or tested device (FRAME_KINDS) */
  kind: FrameKind;
  /** a tested device only: the frame whose app and install it uses, e.g. `generic-android` */
  base?: string;
  name: string;
  /** the maker's part of `name`, set light in the frame's banner; the rest of `name` is bold. `name` starts with it and a space */
  brand?: string;
  aka?: string;
  status: Stage;
  /** one sentence: why this device */
  summary: string;
  /** the banner's line under `frames/<id>`, at most 50 characters (glassworks.dev scripts/brand/frame.mjs draws it) */
  tagline?: string;
  project?: { name: string; href: string };
  /** Kiln's route for the frame, once it installs from Kiln */
  kiln?: string;
  model: FrameModel;
  /** the recovery-mode drawing data, a path next to frame.json */
  recovery?: string;
  /** the screen's render, a `.webp` next to frame.json (tools/showcase/render.mjs); DeviceModel's `screen` */
  render?: string;
  /** the screen in px, upright, as people see it (the Mirror: 1080x1920; branding/boot reads it). A panel that stands turned says so with `boot.rotate` */
  display?: { width: number; height: number };
  /** the boot branding's format limits (branding/boot/target.py); `rotate`: degrees counter-clockwise from `display` into the panel's framebuffer */
  boot?: { androidApi?: number; splash?: 'allwinner-boot-resource'; zipBudget?: number; rotate?: 90 | 270; compactBudget?: number };
  /** what the device's userspace runs, preferred first; the app's release builds every frame's (#98). Optional so code that builds a FrameDesign without it still compiles */
  abis?: Abi[];
  /** the device can sit on a dock (the Pixel Tablet): the app gives Home Assistant its Docked sensor from the start (docs/esphome.md#docked) */
  dock?: boolean;
  /**
   * `maxPixels`: the most the camera and its stream may run at, for a device whose memory can't hold more next to the page.
   * `sensorDegrees`: how the camera's sensor really sits, for a device whose camera reports it wrong (the Mirror's)
   */
  camera?: { maxPixels?: number; sensorDegrees?: 0 | 90 | 180 | 270 };
  /**
   * The app Kiln installs on this frame, when it isn't Glassworks: its package (`id`), the activity adb
   * starts it with (whose extras it trusts from adb), the activity its HOME filter is on (an alias),
   * and its device admin. Class names are full, as in the app's manifest.
   */
  app?: { id: string; activity: string; home: string; admin?: string };
  specs: { label: string; value: string }[];
  /** the panes it rates, by pane id (panes.ts PANE_IDS, or an old alias such as `base`); a pane it doesn't rate is worked out from `provides` */
  panes: Record<string, { support: Support; note?: string }>;
  /** the hardware it has, in panes.ts CAPABILITIES' words; leave it out when it varies by device */
  provides?: string[];
  /** its frame agent, the root-side service the app asks for what it can't do itself (docs/frame-agent.md); leave it out for none */
  agent?: { capabilities: AgentCapability[] };
  /** href: where it installs from, only for a frame without `kiln` */
  install: { how: string; href?: string };
  /** `label`: the card's tag in the frame's own words, instead of the value's default (e.g. reversible: partly). */
  ratings: Record<RatingKey, { value: string; detail?: string; label?: string }>;
  ratingsNote?: string;
  notice?: string;
  /** the card's line for a frame that runs the legacy build (Android 6–7, issue 198): its frozen engine and what that means */
  legacy?: string;
  unverified?: boolean;
  /** the outside work the frame stands on: images, unlocks, tools, research (frames/README.md#credits) */
  credits?: FrameCredit[];
  /** what its panels show, for the checklist (completeness.ts; frames/README.md#how-complete-it-is). Not on a tested device */
  facts?: FrameFacts;
  /** a tested device only: its run on a real unit, which its checklist comes from */
  run?: FrameRun;
  /** a tested device only, and every one has it: who tested it, on which Android, and where they wrote it up */
  tested?: FrameTested;
  /** what the Release workflow ships for it: its app lines and its frame release (tools/release/plan.py; frames/README.md#release) */
  release?: FrameRelease;
}

/**
 * Who tested a tested device, and on what (issue 700). `by`: Glassworks (the maintainers, on their own unit) or the
 * community (someone who ran it and reported it). `os`: `stock`, the maker's Android, or the custom OS it ran, by name
 * (`LineageOS`). `report`: the write-up it comes from, an https link; leave it out when there is none.
 */
export interface FrameTested {
  by: 'glassworks' | 'community';
  os: string;
  report?: string;
}

/** The card's line for who tested it: "Tested by the community on LineageOS", "Tested by Glassworks on stock Android". */
export const testedText = (t: FrameTested) =>
  `Tested by ${t.by === 'glassworks' ? 'Glassworks' : 'the community'} on ${t.os === 'stock' ? 'stock Android' : t.os}`;

/**
 * What's wrong with a frame's `tested`, one line per problem; empty when it's fine. Every tested device says who tested
 * it and on what; no other kind has the field. Kiln's tests and glassworks.dev's build run it.
 */
export function testedProblems(f: Pick<FrameDesign, 'kind' | 'tested'>): string[] {
  if (f.kind !== 'tested') return f.tested === undefined ? [] : ['tested: only a tested device has one'];
  const t = f.tested as unknown;
  if (t === null || typeof t !== 'object' || Array.isArray(t)) return ['tested: a tested device says who tested it and on what, { "by", "os", "report" }'];
  const o = t as Record<string, unknown>;
  const out = Object.keys(o).filter((k) => !['by', 'os', 'report'].includes(k)).map((k) => `tested.${k}: not a tested field`);
  if (o.by !== 'glassworks' && o.by !== 'community') out.push('tested.by: should be glassworks or community');
  if (!isText(o.os)) out.push('tested.os: should be stock, or the custom OS it ran by name, e.g. LineageOS');
  if (o.report !== undefined && !isHttps(o.report)) out.push('tested.report: should be an https link to the report');
  return out;
}

/**
 * A frame's `release` block. Read by tools/release/plan.py, the Release workflow's plan (docs/releasing.md); the site
 * and Kiln don't use it. `lines` are the app lines this frame's app module builds, `app` the lines its devices run.
 */
export interface FrameRelease {
  lines?: Record<string, {
    title: string; tag: string; changelog: string; build: 's1-kiosk' | 'gradle'; assets: Record<string, string>;
    abis?: 'every-frame'; latest?: boolean; task?: string; apk?: string; package?: string; minSdk?: number; writeSettings?: boolean;
  }>;
  app?: string[];
  frame?: { bundle: 'signed' | 'notice'; title: string; changelog: string; script?: string; pubkey?: string; assets?: string[]; note?: string; versionFile?: string };
}

/** One piece of outside work a frame relies on, and who made it. */
export interface FrameCredit {
  /** kebab-case, unique in the frame; a Kiln recipe names the credits its steps use by it */
  id: string;
  /** the person or group, linked to their profile */
  who: { name: string; href: string };
  /** what they made, in a few words: `Android image`, `Bootloader unlock`, `Research` */
  what: string;
  project: { name: string; href: string };
  /** as the project states it; leave it out when it states none */
  licence?: string;
  /** one plain sentence: what Glassworks uses from it */
  uses: string;
  /** their own words for the work, and the page they're from */
  quote?: { text: string; href: string };
  /** name it on the frame's card: the work the device can't run without, such as its image */
  card?: boolean;
}

/**
 * What a frame agent can do, in the words `agent.capabilities` uses (docs/frame-agent.md#capabilities). The app's
 * `dev.glassworks.frame.AgentCapability` has the same words, and its contract test checks that it does.
 */
export const AGENT_CAPABILITIES = [
  'radio-mode', 'radio-firewall', 'radio-firmware', 'frame-bundle', 'app-updates', 'time-zone',
  'hotspot-portal', 'hotspot-name', 'bluetooth-address', 'loader-install',
] as const;
export type AgentCapability = (typeof AGENT_CAPABILITIES)[number];

/**
 * What's wrong with a frame's `agent`, one line per problem; empty when it's fine. A frame without one has none to
 * check. A tested device runs its base's app as it is, so it has no agent of its own.
 */
export function agentProblems(f: Pick<FrameDesign, 'kind' | 'agent'>): string[] {
  if (f.agent === undefined) return [];
  if (f.kind === 'tested') return ["agent: a tested device has no code of its own; its base's agent is used"];
  const a = f.agent as unknown;
  if (a === null || typeof a !== 'object' || Array.isArray(a)) return ['agent: should be { "capabilities": [...] }'];
  const extra = Object.keys(a).filter((k) => k !== 'capabilities');
  const caps = (a as { capabilities?: unknown }).capabilities;
  const out = extra.map((k) => `agent.${k}: not an agent field (docs/frame-agent.md#discovery)`);
  if (!Array.isArray(caps) || !caps.length) return [...out, 'agent.capabilities: list what the agent does; leave `agent` out for none'];
  const seen = new Set<unknown>();
  for (const c of caps) {
    if (!(AGENT_CAPABILITIES as readonly unknown[]).includes(c)) out.push(`agent.capabilities: "${String(c)}" isn't a capability (docs/frame-agent.md#capabilities)`);
    else if (seen.has(c)) out.push(`agent.capabilities: "${c}" is listed twice`);
    seen.add(c);
  }
  return out;
}

const KEBAB = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const isHttps = (v: unknown) => typeof v === 'string' && URL.canParse(v) && new URL(v).protocol === 'https:';
const isText = (v: unknown) => typeof v === 'string' && /\S/.test(v) && !/\n/.test(v);

/**
 * What's wrong with a frame's `credits`, one line per problem; empty when it's fine. Kiln's tests and
 * glassworks.dev's build both run it, so a credit without its links never reaches a page.
 */
export function creditProblems(f: Pick<FrameDesign, 'credits'>): string[] {
  if (f.credits === undefined) return [];
  if (!Array.isArray(f.credits)) return ['credits: should be a list'];
  const out: string[] = [];
  const seen = new Set<string>();
  f.credits.forEach((c, i) => {
    const at = `credits[${i}]${typeof c?.id === 'string' ? ` (${c.id})` : ''}`;
    if (typeof c?.id !== 'string' || !KEBAB.test(c.id)) out.push(`${at}.id: should be kebab-case`);
    else if (seen.has(c.id)) out.push(`${at}.id: used twice`);
    else seen.add(c.id);
    for (const [k, v] of [['who.name', c?.who?.name], ['what', c?.what], ['project.name', c?.project?.name], ['uses', c?.uses]] as const) {
      if (!isText(v)) out.push(`${at}.${k}: should be one line of text`);
    }
    for (const [k, v] of [['who.href', c?.who?.href], ['project.href', c?.project?.href]] as const) {
      if (!isHttps(v)) out.push(`${at}.${k}: should be an https link`);
    }
    if (c?.licence !== undefined && !isText(c.licence)) out.push(`${at}.licence: leave it out, or state it`);
    if (c?.quote !== undefined && (c.quote === null || typeof c.quote !== "object" || !isText(c.quote.text) || !isHttps(c.quote.href))) out.push(`${at}.quote: needs its text and an https link to where it's from`);
    if (c?.card !== undefined && typeof c.card !== 'boolean') out.push(`${at}.card: should be true or false`);
  });
  return out;
}

/**
 * What's wrong with a frame's `kind` and `base`, one line per problem; empty when it's fine. `frames`
 * is every frame's id and kind, to check `base` against. A tested device has a `base` that is a frame
 * of another kind, and nothing a recipe needs (`kiln`, `app`, `recovery`); no other kind has a `base`.
 */
export function kindProblems(f: Pick<FrameDesign, 'kind' | 'base' | 'kiln' | 'app' | 'recovery'>, frames: Pick<FrameDesign, 'id' | 'kind'>[]): string[] {
  if (!(FRAME_KINDS as readonly unknown[]).includes(f.kind)) return [`kind: should be one of ${FRAME_KINDS.join(', ')}`];
  if (f.kind !== 'tested') return f.base === undefined ? [] : ['base: only a tested device has one'];
  const out: string[] = [];
  const base = frames.find((x) => x.id === f.base);
  if (typeof f.base !== 'string' || !base) out.push('base: a tested device names the frame whose app it installs, e.g. generic-android');
  else if (base.kind === 'tested') out.push(`base: ${f.base} is a tested device itself; name the frame it installs as`);
  for (const k of ['kiln', 'app', 'recovery'] as const) if (f[k] !== undefined) out.push(`${k}: a tested device has no recipe of its own; its base's install is used`);
  return out;
}

/** Every field a frame.json may have. A `Record` over FrameDesign's keys, so the compiler fails when the two differ. */
const FIELDS: Record<keyof FrameDesign, true> = {
  id: true, kind: true, base: true, name: true, brand: true, aka: true, status: true, summary: true, tagline: true, project: true,
  kiln: true, model: true, recovery: true, render: true, display: true, boot: true, abis: true, app: true, specs: true, panes: true,
  provides: true, agent: true, dock: true, camera: true, install: true, ratings: true, ratingsNote: true, notice: true, legacy: true, unverified: true, credits: true, facts: true, run: true, tested: true, release: true,
};

/**
 * The keys a frame.json has that FrameDesign doesn't, one line each; empty when there are none. A stray `serial`
 * or a misspelt field never reaches a page. Kiln's tests and glassworks.dev's build run it.
 */
export function keyProblems(f: object): string[] {
  return Object.keys(f).filter((k) => !Object.hasOwn(FIELDS, k)).map((k) => `${k}: not a frame.json field (frames/README.md#designframejson)`);
}

/** The card's credit line, from the first credit marked `card`: "Built on Glass Panes by git-nimrod". */
export function cardCredit(f: Pick<FrameDesign, 'credits'>): string | undefined {
  const c = f.credits?.find((x) => x.card);
  return c && `Built on ${c.project.name} by ${c.who.name}`;
}

/** A card's three tags, phrased to stand alone (glassworks.dev `cardTags`). */
export function cardTags(r: FrameDesign['ratings']) {
  const tags: { key: 'difficulty' | 'reversible' | 'modifiesAndroid'; value: string; text: string; severity?: 'caution' | 'warn' }[] = [];
  const level: Record<string, string> = { preinstalled: 'Pre-installed', easy: 'Easy install', medium: 'Medium install', hard: 'Hard install' };
  const d = r.difficulty.value;
  if (d !== 'unknown') tags.push({ key: 'difficulty', value: d, text: level[d] ?? d, severity: d === 'hard' ? 'warn' : d === 'medium' ? 'caution' : undefined });
  const rev = r.reversible.value;
  if (rev !== 'unknown') {
    tags.push({ key: 'reversible', value: rev, text: r.reversible.label ?? (rev === 'yes' ? 'Reversible' : rev === 'partly' ? 'Partly reversible' : 'Not reversible'),
      severity: rev === 'no' ? 'warn' : rev === 'partly' ? 'caution' : undefined });
  }
  const m = r.modifiesAndroid.value;
  if (m !== 'unknown') tags.push({ key: 'modifiesAndroid', value: m, text: m === 'yes' ? 'Modifies Android' : 'Android untouched' });
  return tags;
}

export const STAGE_TEXT: Record<Stage, string> = { planned: 'Planned', development: 'Under development', alpha: 'Alpha', beta: 'Beta', stable: 'Stable' };

/** Kiln can install it now, or the pane runs on the frame now. `depends`, `unknown` and `no` don't. */
export const ships = (s: Support) => s === 'alpha' || s === 'beta' || s === 'stable';
