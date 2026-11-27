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
   * This browser's adb key in a copy of a checked userdata image (`file`), named `makes`, in place of the
   * key the image publishes there, for an image that lets in a key listed in its adb_keys (as its own
   * installer does it). It writes one file and nothing else: `/misc/adb/adb_keys`, keeping its owner,
   * mode and label. Not optional: a published key's private half is everyone's. Writes nothing to the
   * device.
   */
  | { do: 'adb-key'; file: string; makes: string }
  /**
   * This browser's adb key in a copy of a checked system image (`file`), named `makes`, in place of a key
   * the image's framework lets in without asking, for an image that hardcodes one nobody can take back
   * (no user can remove it, and its owner can get into every device on the image). It changes one file,
   * the one the frame names (the framework jar with the key in it), and in it only that key's
   * fingerprint. Writes nothing to the device.
   */
  | { do: 'system-key'; file: string; makes: string }
  /**
   * The person's Wi-Fi network, saved in a copy of a checked userdata image (`file`), or of what an
   * `adb-key` step made from one, named `makes`. It writes one file and nothing else:
   * `/misc/wifi/wpa_supplicant.conf`, which is the template from the checked system image (`template`'s
   * `/etc/wifi/wpa_supplicant.conf`), then the network with its PSK, never the password. Optional: when
   * the person gives no network, `makes` is `file` as it was. Writes nothing to the device.
   */
  | { do: 'wifi'; file: string; template: string; makes: string }
  /**
   * After the restart, over adb, runs `svc wifi enable` and nothing else, so a saved network joins
   * without anyone opening Settings. With this browser's key after an `adb-key` step; or with the
   * image's own published key (`key`, a checked download, used for this one session and never stored).
   * Only after a `wifi` step, and only when a network was saved.
   */
  | { do: 'wifi-on'; key?: string }
  /**
   * An in-place recipe's first stage: the frame's code builds a small userdata image (`makes`) that starts the
   * frame's agent on the stock software with this browser's key. Only Glassworks, only an in-place recipe. After
   * fastboot and before restart. Writes nothing to the device by itself: a `flash` writes what it makes.
   */
  | { do: 'stage'; makes: string }
  /**
   * After the restart into the stage: Kiln receives the bundle (`bundle`) the agent reads off the running device,
   * over one of `channels`, and checks it (its SHA-256, its serial, its HMAC with the stage's secret). Read-only.
   */
  | { do: 'receive'; bundle: string; channels: readonly ('lan' | 'ble' | 'file')[] }
  /**
   * The frame's code builds, from the received `bundle`, the surgical write images (`makes`) and the image that
   * puts every block they change back (`restore`). Kiln checks each against the bundle's extents before any is
   * written (inplace.ts). After fastboot (a new session after `receive`) and before restart. Writes nothing.
   */
  | { do: 'in-place'; bundle: string; makes: readonly string[]; restore: string }
  /** After the restart into what an in-place recipe wrote: the app over adb with this browser's key. */
  | { do: 'app-install' };

export type StepKind = ImageStep['do'];

/** What each library step does, as the install screen lists it, and the fields it takes. */
export const STEP_LIBRARY: Readonly<Record<StepKind, { fields: readonly string[]; writes: boolean; device: boolean }>> = {
  download: { fields: ['file'], writes: false, device: false },
  patch: { fields: ['file', 'makes'], writes: false, device: false },
  fastboot: { fields: [], writes: false, device: true },
  flash: { fields: ['partition', 'file'], writes: true, device: true },
  restart: { fields: [], writes: false, device: true },
  'first-start': { fields: [], writes: false, device: true },
  'adb-key': { fields: ['file', 'makes'], writes: false, device: false },
  'system-key': { fields: ['file', 'makes'], writes: false, device: false },
  wifi: { fields: ['file', 'template', 'makes'], writes: false, device: false },
  'wifi-on': { fields: ['key'], writes: false, device: true },
  stage: { fields: ['makes'], writes: false, device: false },
  receive: { fields: ['bundle', 'channels'], writes: false, device: true },
  'in-place': { fields: ['bundle', 'makes', 'restore'], writes: false, device: false },
  'app-install': { fields: [], writes: true, device: true },
};

/** The ways a `receive` step can take the bundle from the device's agent. */
export const BUNDLE_CHANNELS = ['lan', 'ble', 'file'] as const;

/**
 * Kiln's own steps around every recipe, all three right after the device connects and before any
 * other step. `restore-point`: in an in-place recipe, right before the first write of what an
 * `in-place` step made, the restore image kept in this browser and offered as a download. `checks`: read-only, and a failed check stops the install. `backup`: what the device has
 * (or, when it can't be read back, the SHA-256 of each file about to be written, in the log).
 * `confirm`: the wipe warning, which the person must accept. `way-back`: last, Done says how to put
 * the other image back.
 */
export const SAFETY_STEPS = ['checks', 'backup', 'confirm', 'restore-point', 'way-back'] as const;
export type SafetyStep = (typeof SAFETY_STEPS)[number];

/** The `restore-point` Kiln puts in: the bundle the restore image was built from, and that image. */
export type RestorePoint = { do: 'restore-point'; safety: true; bundle: string; restore: string };
export type PlannedStep = ImageStep | { do: Exclude<SafetyStep, 'restore-point'>; safety: true } | RestorePoint;

export interface ImageRecipe {
  /** The `?image=<id>` in Kiln's link. Lower case, digits and dashes. `glassworks` is Glassworks' own. */
  id: string;
  name: string;
  /** Made by another project. Glassworks (`glassworks`, `glassworks-*`) is the one image that isn't. */
  community: boolean;
  /**
   * `replace` (the default): whole partitions written from images. `in-place`: the stock software kept, only the
   * blocks the bundle names written, with a restore image for exactly those (stage, receive, in-place, app-install).
   */
  mode?: 'replace' | 'in-place';
  by: ImageLink;
  project: ImageLink;
  licence: string;
  /** One line on the card. */
  summary: string;
  /**
   * Where the frame's choice puts it, when it offers more than one way: `recommended` comes first and is picked,
   * `alternative` follows. `trade` is one line on what it gains and what it costs ("Slower, but …").
   */
  advice?: { tag: 'recommended' | 'alternative'; trade: string };
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
  const own = r.id === 'glassworks' || (r.id ?? '').startsWith('glassworks-');
  if (r.community && own) bad.push(`id "${r.id}" is Glassworks' own`);
  if (!r.community && !own) bad.push('only Glassworks is not a community image');
  const inPlace = r.mode === 'in-place';
  if (r.mode !== undefined && r.mode !== 'replace' && !inPlace) bad.push(`mode "${String(r.mode)}": replace or in-place`);
  if (inPlace && r.community) bad.push('a community image is installed as published: no in-place mode');
  for (const k of ['name', 'licence', 'summary'] as const) if (!r[k]?.trim()) bad.push(`${k} is missing`);
  if (r.advice !== undefined) {
    if (r.advice.tag !== 'recommended' && r.advice.tag !== 'alternative') bad.push(`advice tag "${String(r.advice.tag)}": recommended or alternative`);
    if (typeof r.advice.trade !== 'string' || !r.advice.trade.trim()) bad.push('advice trade is missing');
    if (r.community && r.advice.tag === 'recommended') bad.push('a community image is never the recommended one');
  }
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
  const keyed = new Set<string>();
  let systemKeyed = false;
  const flashed = new Set<string>();
  let device = false, fastboot = false, restarted = false, writes = 0, wifi = false, wifiOn = false;
  // in-place: stage → flash(stage) → restart → receive → fastboot → in-place → flash(all in-place) → restart → app-install
  let staged = false, stageFile: string | undefined, stageFlashed = false, stageRestarted = false;
  let received: string | undefined, fastbootAfterReceive = false;
  let inPlaced = false, inPlaceRestarted = false, appInstalled = false;
  const inPlaceFiles = new Set<string>();
  const inPlaceWritten = new Set<string>();
  const restores = new Set<string>();
  const newName = (n: unknown) => typeof n === 'string' && FILE_NAME.test(n) && !files.has(n) && !made.has(n) && !restores.has(n) && n !== received;
  const inPlaceOnly = (i: number, kind: string) => {
    if (r.community) bad.push(`${at(i)}: a community image gets no "${kind}": it is Glassworks' own in-place install`);
    else if (!inPlace) bad.push(`${at(i)}: "${kind}" is only for an in-place recipe (mode "in-place")`);
  };
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
        if (r.community) bad.push(`${at(i)}: a community image gets no patch: only the library's own changes (adb-key, system-key, wifi)`);
        if (!checked.has(s.file)) bad.push(`${at(i)}: patch needs ${s.file} downloaded first`);
        // Kiln runs it after its checks and the warning (it uses what fastboot reports), so before fastboot it would be skipped
        if (!fastboot || restarted) bad.push(`${at(i)}: patch comes after fastboot and before restart`);
        if (!Array.isArray(s.makes) || !s.makes.length) bad.push(`${at(i)}: patch must say what it makes`);
        // Kiln's build replaces every file made before it: a copy made first (adb-key, system-key, wifi) would be gone
        if (made.size) bad.push(`${at(i)}: patch comes before any step that makes a file: Kiln's build replaces what they made`);
        for (const m of s.makes ?? []) made.add(m);
        break;
      case 'fastboot':
        fastboot = true;
        restarted = false;
        if (received !== undefined) fastbootAfterReceive = true;
        break;
      case 'flash':
        writes++;
        if (!fastboot || restarted) bad.push(`${at(i)}: flash needs the device in fastboot first`);
        if (!frame.partitions.includes(s.partition)) bad.push(`${at(i)}: this frame doesn't let a recipe write "${s.partition}"`);
        // the last write wins: a second one in a fastboot session would replace what the first wrote (a keyed copy with
        // the published file). Each session (between fastboot and restart) starts over: an in-place recipe writes
        // userdata in its stage, and again after it.
        if (flashed.has(s.partition)) bad.push(`${at(i)}: ${s.partition} is written once`);
        flashed.add(s.partition);
        if (restores.has(s.file)) bad.push(`${at(i)}: ${s.file} is the way back: the install never writes it`);
        else if (inPlace) {
          if (s.file === stageFile) {
            if (stageRestarted) bad.push(`${at(i)}: ${s.file} can only be written in the stage session`);
            else stageFlashed = true;
          } else if (inPlaceFiles.has(s.file)) {
            if (appInstalled || inPlaceRestarted) bad.push(`${at(i)}: ${s.file} must be written before restarting into app-install`);
            else inPlaceWritten.add(s.file);
          } else {
            bad.push(`${at(i)}: ${s.file} isn't the stage output or something in-place made`);
          }
        } else if (!checked.has(s.file) && !made.has(s.file)) {
          bad.push(`${at(i)}: ${s.file} isn't a checked download or something Kiln made from one`);
        }
        break;
      case 'restart':
        if (!fastboot) bad.push(`${at(i)}: restart needs a device to restart`);
        restarted = true;
        if (inPlace) {
          flashed.clear();
          if (stageFlashed) stageRestarted = true;
          if (inPlaced && inPlaceFiles.size > 0 && [...inPlaceFiles].every((f) => inPlaceWritten.has(f))) inPlaceRestarted = true;
        }
        break;
      case 'stage':
        inPlaceOnly(i, 'stage');
        if (staged) bad.push(`${at(i)}: the stage is built once`);
        staged = true;
        // it is sized from what fastboot reports, and written in that session
        if (!fastboot || restarted) bad.push(`${at(i)}: stage comes after fastboot and before restart`);
        if (!newName(s.makes)) bad.push(`${at(i)}: stage must name a new file it makes`);
        else { made.add(s.makes); stageFile = s.makes; }
        break;
      case 'receive':
        inPlaceOnly(i, 'receive');
        if (received !== undefined) bad.push(`${at(i)}: the bundle is received once`);
        if (!stageRestarted || !restarted) bad.push(`${at(i)}: receive comes after a stage step and the restart into it`);
        if (!Array.isArray(s.channels) || !s.channels.length || s.channels.some((c) => !(BUNDLE_CHANNELS as readonly string[]).includes(c))
          || new Set(s.channels).size !== s.channels.length) bad.push(`${at(i)}: receive takes channels from ${BUNDLE_CHANNELS.join(', ')}, each once`);
        if (!newName(s.bundle)) bad.push(`${at(i)}: receive must name a new bundle`);
        received = typeof s.bundle === 'string' ? s.bundle : '';
        break;
      case 'in-place': {
        inPlaceOnly(i, 'in-place');
        if (inPlaced) bad.push(`${at(i)}: the in-place images are built once`);
        inPlaced = true;
        if (received === undefined) bad.push(`${at(i)}: in-place needs a receive step first`);
        else if (s.bundle !== received) bad.push(`${at(i)}: in-place builds from ${String(s.bundle)}, but the bundle received is ${received}`);
        if (!fastbootAfterReceive || restarted) bad.push(`${at(i)}: in-place comes after receive, in a new fastboot session, before restart`);
        const makes: unknown[] = Array.isArray(s.makes) ? s.makes : [];
        if (!makes.length) bad.push(`${at(i)}: in-place must say what it makes`);
        for (const m of makes) {
          if (!newName(m)) bad.push(`${at(i)}: in-place must name new files it makes`);
          else { made.add(m as string); inPlaceFiles.add(m as string); }
        }
        if (!newName(s.restore) || makes.includes(s.restore)) bad.push(`${at(i)}: in-place must name a new restore file`);
        else restores.add(s.restore);
        break;
      }
      case 'app-install':
        inPlaceOnly(i, 'app-install');
        if (appInstalled) bad.push(`${at(i)}: the app is installed once`);
        appInstalled = true;
        if (!inPlaceRestarted || !restarted) bad.push(`${at(i)}: app-install comes after restarting into the in-place writes`);
        break;
      case 'first-start':
        if (r.community) bad.push(`${at(i)}: Kiln's first start needs an image that trusts this browser's key: only Glassworks`);
        if (!restarted) bad.push(`${at(i)}: first-start comes after restart`);
        break;
      case 'adb-key':
        if (keyed.size) bad.push(`${at(i)}: the adb key is put in once`);
        // like wifi: after Kiln's checks and the warning, before the writes
        if (!fastboot || restarted) bad.push(`${at(i)}: adb-key comes after fastboot and before restart`);
        if (!checked.has(s.file)) bad.push(`${at(i)}: adb-key needs ${s.file} downloaded first`);
        if (typeof s.makes !== 'string' || !FILE_NAME.test(s.makes) || files.has(s.makes) || made.has(s.makes)) bad.push(`${at(i)}: adb-key must name a new file it makes`);
        else { made.add(s.makes); keyed.add(s.makes); }
        break;
      case 'system-key':
        if (systemKeyed) bad.push(`${at(i)}: the system image's key is changed once`);
        systemKeyed = true;
        // like adb-key: after Kiln's checks and the warning, before the writes
        if (!fastboot || restarted) bad.push(`${at(i)}: system-key comes after fastboot and before restart`);
        if (!checked.has(s.file)) bad.push(`${at(i)}: system-key needs ${s.file} downloaded first`);
        if (typeof s.makes !== 'string' || !FILE_NAME.test(s.makes) || files.has(s.makes) || made.has(s.makes)) bad.push(`${at(i)}: system-key must name a new file it makes`);
        else made.add(s.makes);
        break;
      case 'wifi':
        if (wifi) bad.push(`${at(i)}: the Wi-Fi is saved once`);
        wifi = true;
        // Kiln runs it after its checks and the warning, before the writes: between fastboot and restart
        if (!fastboot || restarted) bad.push(`${at(i)}: wifi comes after fastboot and before restart`);
        if (!checked.has(s.file) && !keyed.has(s.file)) bad.push(`${at(i)}: wifi needs ${s.file} downloaded first, or made by adb-key`);
        if (!checked.has(s.template)) bad.push(`${at(i)}: wifi needs ${s.template} downloaded first`);
        if (typeof s.makes !== 'string' || !FILE_NAME.test(s.makes) || files.has(s.makes) || made.has(s.makes)) bad.push(`${at(i)}: wifi must name a new file it makes`);
        else made.add(s.makes);
        break;
      case 'wifi-on':
        if (wifiOn) bad.push(`${at(i)}: Wi-Fi is switched on once`);
        wifiOn = true;
        if (!wifi) bad.push(`${at(i)}: wifi-on needs a wifi step first: there is no network to join otherwise`);
        if (!restarted) bad.push(`${at(i)}: wifi-on comes after restart`);
        if (s.key !== undefined && !checked.has(s.key)) bad.push(`${at(i)}: wifi-on's key must be a checked download`);
        if (s.key === undefined && !keyed.size) bad.push(`${at(i)}: wifi-on without a key connects with this browser's, so it needs an adb-key step first`);
        break;
    }
  });
  for (const f of files.keys()) if (!downloaded.has(f)) bad.push(`${f} is never downloaded`);
  // a copy with this browser's key that nothing writes leaves the device on the image's own keys: wifi-on with
  // this browser's would then be refused. What reaches a flash: each flashed file, and what a wifi step whose
  // copy is flashed starts from (without a network, that source is written in its place).
  const written = new Set((r.steps ?? []).flatMap((s) => (s.do === 'flash' ? [s.file] : [])));
  const wifis = (r.steps ?? []).filter((s): s is Extract<ImageStep, { do: 'wifi' }> => s.do === 'wifi');
  for (let grew = true; grew;) {
    grew = false;
    for (const w of wifis) if (written.has(w.makes) && !written.has(w.file)) { written.add(w.file); grew = true; }
  }
  for (const s of r.steps ?? []) {
    if ((s.do === 'adb-key' || s.do === 'system-key') && typeof s.makes === 'string' && !written.has(s.makes)) {
      bad.push(`${s.do} makes ${s.makes}, but nothing writes it: flash it, or a wifi step's copy of it`);
    }
    if (s.do === 'wifi' && typeof s.makes === 'string' && !written.has(s.makes)) bad.push(`wifi makes ${s.makes}, but nothing writes it: flash it`);
    if (s.do === 'stage' && typeof s.makes === 'string' && !written.has(s.makes)) bad.push(`stage makes ${s.makes}, but nothing writes it: flash it`);
    if (s.do === 'in-place' && Array.isArray(s.makes)) {
      for (const m of s.makes) if (typeof m === 'string' && !written.has(m)) bad.push(`in-place makes ${m}, but nothing writes it: flash it`);
    }
  }
  if (inPlace) {
    for (const k of ['stage', 'receive', 'in-place'] as const) if (!(r.steps ?? []).some((s) => s.do === k)) bad.push(`an in-place recipe needs a ${k} step`);
    if ((r.steps ?? []).some((s) => s.do === 'patch')) bad.push('an in-place recipe keeps the stock software: no patch');
  }
  const wifiFrom = wifis[0];
  if (wifiFrom && keyed.size && !keyed.has(wifiFrom.file) && written.has(wifiFrom.makes)) {
    bad.push(`wifi starts from ${wifiFrom.file}, not the copy with this browser's key: what it makes would leave the key out`);
  }
  if (!writes) bad.push('the recipe writes nothing');
  if (!restarted) bad.push('the recipe must end by restarting the device');
  return bad;
}

/** The image the frame recommends, or its first: what the choice starts on. */
export function defaultImage<R extends ImageRecipe>(images: readonly R[]): R {
  return images.find((r) => r.advice?.tag === 'recommended') ?? images[0]!;
}

/** Throws unless every recipe is valid for the frame and their ids are unique. For the frame's own tests and for Kiln at start. */
export function assertImages(images: readonly ImageRecipe[], frame: FrameAccess): void {
  const bad = images.flatMap((r) => validateImage(r, frame).map((b) => `${r.id}: ${b}`));
  const ids = images.map((r) => r.id);
  if (new Set(ids).size !== ids.length) bad.push('two images share an id');
  if (!images[0] || images[0].community) bad.push('Glassworks comes first');
  if (images.filter((r) => r.advice?.tag === 'recommended').length > 1) bad.push('more than one image is recommended');
  if (bad.length) throw new Error(`invalid image recipes:\n${bad.join('\n')}`);
}

/**
 * The steps Kiln runs, in order: the recipe's, with Kiln's safety steps put in. A recipe can't skip
 * them: `checks`, `backup` and `confirm` follow the first step that connects to the device (or, in a
 * recipe that writes without connecting, which validation refuses, come before that write),
 * `restore-point` comes right before the first write of what an `in-place` step made, and `way-back`
 * is last.
 */
export function plan(r: ImageRecipe): PlannedStep[] {
  const out: PlannedStep[] = [];
  const safety: PlannedStep[] = [{ do: 'checks', safety: true }, { do: 'backup', safety: true }, { do: 'confirm', safety: true }];
  let guarded = false;
  const fromInPlace = new Map<string, Extract<ImageStep, { do: 'in-place' }>>();
  let restorePoint = false;
  for (const s of r.steps) {
    if (STEP_LIBRARY[s.do].writes && !guarded) { out.push(...safety); guarded = true; }
    if (s.do === 'in-place') for (const m of s.makes) fromInPlace.set(m, s);
    const source = s.do === 'flash' ? fromInPlace.get(s.file) : undefined;
    if (source && !restorePoint) { out.push({ do: 'restore-point', safety: true, bundle: source.bundle, restore: source.restore }); restorePoint = true; }
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

/** The `adb-key` step that makes `name`, when there is one. */
export function adbKeyStep(r: ImageRecipe, name: string): Extract<ImageStep, { do: 'adb-key' }> | undefined {
  return r.steps.find((s): s is Extract<ImageStep, { do: 'adb-key' }> => s.do === 'adb-key' && s.makes === name);
}

/** The `system-key` step that makes `name`, when there is one. */
export function systemKeyStep(r: ImageRecipe, name: string): Extract<ImageStep, { do: 'system-key' }> | undefined {
  return r.steps.find((s): s is Extract<ImageStep, { do: 'system-key' }> => s.do === 'system-key' && s.makes === name);
}

/**
 * The recipe's file a written `name` comes from: itself when it is one, else what the `wifi`, `adb-key`
 * and `system-key` steps that make it start from, followed back to a checked download. Undefined for
 * what a `patch` builds.
 */
export function sourceFile(r: ImageRecipe, name: string): ImageFile | undefined {
  for (let n = name, hops = 0; hops <= r.steps.length; hops++) {
    const f = r.files.find((x) => x.name === n);
    if (f) return f;
    const from = wifiStep(r, n)?.file ?? adbKeyStep(r, n)?.file ?? systemKeyStep(r, n)?.file;
    if (from === undefined) return undefined;
    n = from;
  }
  return undefined;
}
