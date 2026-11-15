// A frame's design data, `frames/<id>/design/frame.json` (the format is in frames/README.md), and the
// card's words for it. Kiln and glassworks.dev read the same files through these types.

type V3 = [number, number, number];

/** The device's look in millimetres; the site and Kiln draw the 3D mockup from it. */
export interface FrameModel {
  body: { shape: 'rect'; width: number; height: number; depth: number; radius: number };
  /** frame around the screen, mm */
  bezel: number;
  /** without width and height, the screen is the face minus the bezel */
  screen: { radius: number; width?: number; height?: number; top?: number };
  /** the body's sides: the colour facing away from the light, and facing it */
  sides: { dark: string; light: string };
  finish: 'glass' | 'mirror';
  mount: 'wall' | 'floor' | 'lean';
  /** for lean: how far the top tilts back, degrees */
  lean?: number;
  /** parts outside the body, mm from its centre (x right, y down, z towards you) */
  parts?: { kind: 'tube'; from: V3; to: V3; diameter: number; color: string }[];
}

/** The same stages as the Glassworks status badges (planned, under development, alpha, beta, stable). */
export type Stage = 'planned' | 'development' | 'alpha' | 'beta' | 'stable';
export type Support = Stage | 'depends' | 'unknown' | 'no';
export const RATING_KEYS = ['difficulty', 'reversible', 'modifiesAndroid', 'root', 'opensDevice', 'vendorApps', 'wipes', 'backup', 'updates'] as const;
export type RatingKey = (typeof RATING_KEYS)[number];
/** Android's ABI names (`Build.SUPPORTED_ABIS`) */
export const ABIS = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] as const;
export type Abi = (typeof ABIS)[number];

export interface FrameDesign {
  id: string;
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
  /** the screen in px, as Android's boot animation sees it (branding/boot reads it) */
  display?: { width: number; height: number };
  /** the boot branding's format limits (branding/boot/target.py) */
  boot?: { androidApi?: number; splash?: 'allwinner-boot-resource'; zipBudget?: number };
  /** what the device's userspace runs, preferred first; the app's release builds every frame's (#98). Optional so code that builds a FrameDesign without it still compiles */
  abis?: Abi[];
  specs: { label: string; value: string }[];
  /** the panes it rates, by pane id (panes.ts PANE_IDS, or an old alias such as `base`); a pane it doesn't rate is worked out from `provides` */
  panes: Record<string, { support: Support; note?: string }>;
  /** the hardware it has, in panes.ts CAPABILITIES' words; leave it out when it varies by device */
  provides?: string[];
  /** href: where it installs from, only for a frame without `kiln` */
  install: { how: string; href?: string };
  ratings: Record<RatingKey, { value: string; detail?: string }>;
  ratingsNote?: string;
  notice?: string;
  unverified?: boolean;
  /** the outside work the frame stands on: images, unlocks, tools, research (frames/README.md#credits) */
  credits?: FrameCredit[];
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
    tags.push({ key: 'reversible', value: rev, text: rev === 'yes' ? 'Reversible' : rev === 'partly' ? 'Partly reversible' : 'Not reversible',
      severity: rev === 'no' ? 'warn' : rev === 'partly' ? 'caution' : undefined });
  }
  const m = r.modifiesAndroid.value;
  if (m !== 'unknown') tags.push({ key: 'modifiesAndroid', value: m, text: m === 'yes' ? 'Modifies Android' : 'Android untouched' });
  return tags;
}

export const STAGE_TEXT: Record<Stage, string> = { planned: 'Planned', development: 'Under development', alpha: 'Alpha', beta: 'Beta', stable: 'Stable' };

/** Kiln can install it now, or the pane runs on the frame now. `depends`, `unknown` and `no` don't. */
export const ships = (s: Support) => s === 'alpha' || s === 'beta' || s === 'stable';
