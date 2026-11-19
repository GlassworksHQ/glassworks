// What a frame can have installed on it, as data: Glassworks, or a community image as its project
// publishes it (#579). Each image is a recipe: the files Kiln downloads (each pinned by size and
// SHA-256), and an ordered list of steps from Kiln's step library below. The library is reviewed
// code; recipes are data only, so a new image never brings code of its own.
//
// Kiln wraps every recipe in its own safety steps, which a recipe can't name, skip or reorder: right
// after the device connects, Kiln's checks, the backup (or, where the device can't be read back, the
// hashes of what is about to be written) and the wipe warning, before any other step; and the way
// back on Done. The frame says which steps it can run and which partitions a recipe may write.
// How to add an image: docs/kiln-images.md.

export interface ImageLink { name: string; href: string }

/** A file Kiln downloads through Refract (kiln/workers/refract) and checks before it goes near the device. */
export interface ImageFile {
  name: string;
  /** Where its release publishes it (GitHub): the credit, and what Refract fetches. */
  url: string;
  /** Its id on Refract's allowlist: Kiln downloads `<refract base>/<refract>`. */
  refract: string;
  size: number;
  sha256: string;
}

/** The steps in Kiln's library. `file` names one of the recipe's files, or what a `patch` step makes. */
export type ImageStep =
  /** Downloads one file through Refract and checks its size and SHA-256. Writes nothing to the device. */
  | { do: 'download'; file: string }
  /**
   * Kiln's own change to a downloaded image: the frame's patch, which adds Glassworks (and, for a frame
   * that needs one, a fresh userdata with this browser's key, sized from what fastboot reports). Only
   * Glassworks. Writes nothing to the device.
   */
  | { do: 'patch'; file: string; makes: readonly string[] }
  /** The person puts the device in fastboot and Kiln connects. Read-only. */
  | { do: 'fastboot' }
  /** Writes a checked file, or one Kiln made from a checked file, to a partition the frame allows. */
  | { do: 'flash'; partition: string; file: string }
  /** Restarts the device into what was written. */
  | { do: 'restart' }
  /** Kiln's first start over adb with this browser's key. Only an image that trusts that key: Glassworks. */
  | { do: 'first-start' }
  /**
   * The person's Wi-Fi network, saved in a copy of a checked userdata image (`file`), named `makes`.
   * It writes one file and nothing else: `/misc/wifi/wpa_supplicant.conf`, which is the template from
   * the checked system image (`template`'s `/etc/wifi/wpa_supplicant.conf`), then the network with its
   * PSK, never the password. Optional: when the person gives no network, `makes` is `file` as checked.
   * Writes nothing to the device.
   */
  | { do: 'wifi'; file: string; template: string; makes: string }
  /**
   * After the restart, over adb with the image's own published key (`key`, a checked download, used for
   * this one session and never stored), runs `svc wifi enable` and nothing else, so a saved network
   * joins without anyone opening Settings. Only after a `wifi` step, and only when a network was saved.
   */
  | { do: 'wifi-on'; key: string };

export type StepKind = ImageStep['do'];

/** What each library step does, as the install screen lists it, and the fields it takes. */
export const STEP_LIBRARY: Readonly<Record<StepKind, { fields: readonly string[]; writes: boolean; device: boolean }>> = {
  download: { fields: ['file'], writes: false, device: false },
  patch: { fields: ['file', 'makes'], writes: false, device: false },
  fastboot: { fields: [], writes: false, device: true },
  flash: { fields: ['partition', 'file'], writes: true, device: true },
  restart: { fields: [], writes: false, device: true },
  'first-start': { fields: [], writes: false, device: true },
  wifi: { fields: ['file', 'template', 'makes'], writes: false, device: false },
  'wifi-on': { fields: ['key'], writes: false, device: true },
};

/**
 * Kiln's own steps around every recipe, all three right after the device connects and before any
 * other step. `checks`: read-only, and a failed check stops the install. `backup`: what the device has
 * (or, when it can't be read back, the SHA-256 of each file about to be written, in the log).
 * `confirm`: the wipe warning, which the person must accept. `way-back`: last, Done says how to put
 * the other image back.
 */
export const SAFETY_STEPS = ['checks', 'backup', 'confirm', 'way-back'] as const;
export type SafetyStep = (typeof SAFETY_STEPS)[number];

export type PlannedStep = ImageStep | { do: SafetyStep; safety: true };

export interface ImageRecipe {
  /** The `?image=<id>` in Kiln's link. Lower case, digits and dashes. `glassworks` is Glassworks' own. */
  id: string;
  name: string;
  /** Made by another project. Glassworks is the one image that isn't. */
  community: boolean;
  by: ImageLink;
  project: ImageLink;
  licence: string;
  /** One line on the card. */
  summary: string;
  /** Where questions about it go. A community image's own project. */
  support: ImageLink;
  /** Whose its vendor files are, when they aren't its maker's: said on its download screen. */
  vendor?: string;
  /** The release the files come from (a community image). */
  release?: ImageLink;
  /** After Done: where the person carries on (a community image's install guide). */
  next?: ImageLink;
  files: readonly ImageFile[];
  steps: readonly ImageStep[];
}

/** What the frame lets recipes do: the library steps it implements and the partitions a recipe may write. */
export interface FrameAccess { steps: readonly StepKind[]; partitions: readonly string[] }

const ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const SHA256 = /^[0-9a-f]{64}$/;
/** A file name a step may make: plain characters, and never one of Object's own names (`constructor`, `toString`…). */
const FILE_NAME = { test: (n: string) => /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/.test(n) && !(n in Object.prototype) };
const https = (u: unknown) => { try { return typeof u === 'string' && new URL(u).protocol === 'https:'; } catch { return false; } };

/**
 * Everything wrong with `r` for this frame, or [] when Kiln may offer it. Recipes are data from a
 * pull request, so this checks every field instead of trusting the types.
 */
export function validateImage(r: ImageRecipe, frame: FrameAccess): string[] {
  const bad: string[] = [];
  const at = (i: number) => `step ${i + 1}`;
  if (!ID.test(r.id ?? '')) bad.push(`id "${r.id}": lower case letters, digits and dashes only`);
  if (r.community && r.id === 'glassworks') bad.push('id "glassworks" is Glassworks\' own');
  if (!r.community && r.id !== 'glassworks') bad.push('only Glassworks is not a community image');
  for (const k of ['name', 'licence', 'summary'] as const) if (!r[k]?.trim()) bad.push(`${k} is missing`);
  for (const k of ['by', 'project', 'support'] as const) {
    if (!r[k]?.name?.trim() || !https(r[k]?.href)) bad.push(`${k} needs a name and an https link`);
  }
  for (const k of ['release', 'next'] as const) if (r[k] && (!r[k].name?.trim() || !https(r[k].href))) bad.push(`${k} needs a name and an https link`);

  const files = new Map<string, ImageFile>();
  for (const f of r.files ?? []) {
    if (!f.name?.trim()) { bad.push('a file has no name'); continue; }
    if (files.has(f.name)) bad.push(`${f.name} is listed twice`);
    files.set(f.name, f);
    if (!https(f.url)) bad.push(`${f.name}: url must be https`);
    if (!f.refract?.trim() || f.refract.startsWith('/') || f.refract.includes('..')) bad.push(`${f.name}: refract must be its id on Refract's allowlist`);
    if (!Number.isSafeInteger(f.size) || f.size <= 0) bad.push(`${f.name}: size must be a whole number of bytes`);
    if (!SHA256.test(f.sha256 ?? '')) bad.push(`${f.name}: sha256 must be 64 lower-case hex digits`);
  }

  const checked = new Set<string>();
  const made = new Set<string>();
  const downloaded = new Set<string>();
  let device = false, fastboot = false, restarted = false, writes = 0, wifi = false, wifiOn = false;
  (r.steps ?? []).forEach((s, i) => {
    const kind = (s as { do?: string }).do ?? '';
    if ((SAFETY_STEPS as readonly string[]).includes(kind)) { bad.push(`${at(i)}: "${kind}" is Kiln's own safety step; a recipe can't list it`); return; }
    const lib = Object.hasOwn(STEP_LIBRARY, kind) ? STEP_LIBRARY[kind as StepKind] : undefined;
    if (!lib) { bad.push(`${at(i)}: "${kind}" isn't a step in Kiln's library`); return; }
    if (!frame.steps.includes(kind as StepKind)) { bad.push(`${at(i)}: this frame can't run "${kind}"`); return; }
    const extra = Object.keys(s).filter((k) => k !== 'do' && !lib.fields.includes(k));
    if (extra.length) bad.push(`${at(i)}: "${kind}" takes no ${extra.join(', ')}`);
    if (kind === 'download' && device) bad.push(`${at(i)}: "download" must come before Kiln connects to the device: every file is checked first`);
    if (lib.device) device = true;
    switch (s.do) {
      case 'download':
        if (!files.has(s.file)) bad.push(`${at(i)}: no file "${s.file}"`);
        else if (downloaded.has(s.file)) bad.push(`${at(i)}: ${s.file} is downloaded twice`);
        downloaded.add(s.file);
        checked.add(s.file);
        break;
      case 'patch':
        if (r.community) bad.push(`${at(i)}: a community image goes on as its project publishes it, with no patch`);
        if (!checked.has(s.file)) bad.push(`${at(i)}: patch needs ${s.file} downloaded first`);
        // Kiln runs it after its checks and the warning (it uses what fastboot reports), so before fastboot it would be skipped
        if (!fastboot || restarted) bad.push(`${at(i)}: patch comes after fastboot and before restart`);
        if (!Array.isArray(s.makes) || !s.makes.length) bad.push(`${at(i)}: patch must say what it makes`);
        for (const m of s.makes ?? []) made.add(m);
        break;
      case 'fastboot':
        fastboot = true;
        restarted = false;
        break;
      case 'flash':
        writes++;
        if (!fastboot || restarted) bad.push(`${at(i)}: flash needs the device in fastboot first`);
        if (!frame.partitions.includes(s.partition)) bad.push(`${at(i)}: this frame doesn't let a recipe write "${s.partition}"`);
        if (!checked.has(s.file) && !made.has(s.file)) bad.push(`${at(i)}: ${s.file} isn't a checked download or something Kiln made from one`);
        break;
      case 'restart':
        if (!fastboot) bad.push(`${at(i)}: restart needs a device to restart`);
        restarted = true;
        break;
      case 'first-start':
        if (r.community) bad.push(`${at(i)}: Kiln's first start needs an image that trusts this browser's key: only Glassworks`);
        if (!restarted) bad.push(`${at(i)}: first-start comes after restart`);
        break;
      case 'wifi':
        if (wifi) bad.push(`${at(i)}: the Wi-Fi is saved once`);
        wifi = true;
        // Kiln runs it after its checks and the warning, before the writes: between fastboot and restart
        if (!fastboot || restarted) bad.push(`${at(i)}: wifi comes after fastboot and before restart`);
        if (!checked.has(s.file)) bad.push(`${at(i)}: wifi needs ${s.file} downloaded first`);
        if (!checked.has(s.template)) bad.push(`${at(i)}: wifi needs ${s.template} downloaded first`);
        if (typeof s.makes !== 'string' || !FILE_NAME.test(s.makes) || files.has(s.makes) || made.has(s.makes)) bad.push(`${at(i)}: wifi must name a new file it makes`);
        else made.add(s.makes);
        break;
      case 'wifi-on':
        if (wifiOn) bad.push(`${at(i)}: Wi-Fi is switched on once`);
        wifiOn = true;
        if (!wifi) bad.push(`${at(i)}: wifi-on needs a wifi step first: there is no network to join otherwise`);
        if (!restarted) bad.push(`${at(i)}: wifi-on comes after restart`);
        if (!checked.has(s.key)) bad.push(`${at(i)}: wifi-on's key must be a checked download`);
        break;
    }
  });
  for (const f of files.keys()) if (!downloaded.has(f)) bad.push(`${f} is never downloaded`);
  if (!writes) bad.push('the recipe writes nothing');
  if (!restarted) bad.push('the recipe must end by restarting the device');
  return bad;
}

/** Throws unless every recipe is valid for the frame and their ids are unique. For the frame's own tests and for Kiln at start. */
export function assertImages(images: readonly ImageRecipe[], frame: FrameAccess): void {
  const bad = images.flatMap((r) => validateImage(r, frame).map((b) => `${r.id}: ${b}`));
  const ids = images.map((r) => r.id);
  if (new Set(ids).size !== ids.length) bad.push('two images share an id');
  if (images[0]?.id !== 'glassworks') bad.push('Glassworks comes first');
  if (bad.length) throw new Error(`invalid image recipes:\n${bad.join('\n')}`);
}

/**
 * The steps Kiln runs, in order: the recipe's, with Kiln's safety steps put in. A recipe can't skip
 * them: `checks`, `backup` and `confirm` follow the first step that connects to the device (or, in a
 * recipe that writes without connecting, which validation refuses, come before that write), and
 * `way-back` is last.
 */
export function plan(r: ImageRecipe): PlannedStep[] {
  const out: PlannedStep[] = [];
  const safety: PlannedStep[] = [{ do: 'checks', safety: true }, { do: 'backup', safety: true }, { do: 'confirm', safety: true }];
  let guarded = false;
  for (const s of r.steps) {
    if (STEP_LIBRARY[s.do].writes && !guarded) { out.push(...safety); guarded = true; }
    out.push(s);
    if (STEP_LIBRARY[s.do].device && !guarded) { out.push(...safety); guarded = true; }
  }
  out.push({ do: 'way-back', safety: true });
  return out;
}

/** `?image=<id>`: a community image the frame offers. None, an unknown id or `glassworks` is the normal page. */
export function imageFromLink<R extends ImageRecipe>(images: readonly R[], search: string): R | undefined {
  const id = new URLSearchParams(search).get('image');
  return images.find((r) => r.community && r.id === id);
}

/** Each file a recipe writes as it is, with its SHA-256: the `backup` step's record when the device can't be read back. */
export function writtenAsIs(r: ImageRecipe): ImageFile[] {
  const names = new Set(r.steps.flatMap((s) => (s.do === 'flash' ? [s.file] : [])));
  return r.files.filter((f) => names.has(f.name));
}

/** The `wifi` step that makes `name`, when there is one: its source file is what gets written when no network is saved. */
export function wifiStep(r: ImageRecipe, name: string): Extract<ImageStep, { do: 'wifi' }> | undefined {
  return r.steps.find((s): s is Extract<ImageStep, { do: 'wifi' }> => s.do === 'wifi' && s.makes === name);
}
