// What Kiln can put on the Mirror (#579): Glassworks, first and the default, and the community images
// it offers, each a recipe of data over Kiln's step library (kiln-core images.ts). Kiln wraps each
// in its safety steps: the fastboot checks, the hashes of what it writes (the Mirror's bootloader
// can't read anything back, so there is no backup), the wipe warning, and the way back on Done.
// How to add one: docs/kiln-images.md. Only URLs and hashes here, never an image's bytes.
import type { FrameAccess, ImageRecipe } from '@glassworks/kiln-core';
import { GLASS_PANES_USERDATA } from './flash';
import { GLASS_PANES } from './image';

/** The steps the Mirror's page runs, and the partitions a recipe may write: never LK, boot or the modem. */
export const MIRROR_ACCESS: FrameAccess = {
  steps: ['download', 'patch', 'fastboot', 'flash', 'restart', 'first-start', 'system-key', 'adb-key', 'wifi', 'wifi-on', 'stage', 'receive', 'in-place', 'app-install'],
  partitions: ['system', 'userdata'],
};

/** Refract's ids for the Glass Panes release files (kiln/workers/refract/allowlist.json). */
const onRefract = <F extends { name: string }>(f: F) => ({ ...f, refract: `glass-panes/mirror-android-3/${f.name}` });
const SYSTEM = onRefract(GLASS_PANES.system);
const USERDATA = onRefract(GLASS_PANES_USERDATA);
const panes = GLASS_PANES.project;

export const GLASSWORKS_IMAGE: ImageRecipe = {
  id: 'glassworks',
  name: 'Glassworks',
  community: false,
  by: { name: 'GlassworksHQ', href: 'https://github.com/GlassworksHQ' },
  project: { name: 'glassworks.dev', href: 'https://www.glassworks.dev' },
  licence: 'MPL-2.0 and MIT',
  summary: 'Replaces lululemon’s system with the Glass Panes image, then puts your Home Assistant dashboard on the glass.',
  advice: { tag: 'alternative', trade: 'Faster, but there’s no way back to lululemon’s software afterward.' },
  support: { name: 'Glassworks on GitHub', href: 'https://github.com/GlassworksHQ/glassworks/issues' },
  files: [SYSTEM],
  steps: [
    { do: 'download', file: SYSTEM.name },
    { do: 'fastboot' },
    // after Kiln's checks and the warning: the fresh userdata takes the partition's size and the Mirror's id from fastboot
    { do: 'patch', file: SYSTEM.name, makes: ['system', 'userdata'] },
    { do: 'flash', partition: 'system', file: 'system' },
    { do: 'flash', partition: 'userdata', file: 'userdata' },
    { do: 'restart' },
    { do: 'first-start' },
  ],
};

/**
 * Glassworks on the Mirror's own stock software (Stock-in-place): nothing downloaded and no image replaced. A small
 * setup agent goes on userdata, reads services.jar and where it sits back off the running Mirror, and Kiln changes only
 * that file's blocks in system (this browser's adb key, and Glassworks as Device Owner), keeping a restore point of
 * exactly those blocks first. App data starts fresh; lululemon's system stays, and the restore point puts it back.
 */
export const GLASSWORKS_IN_PLACE: ImageRecipe = {
  id: 'glassworks-in-place',
  name: 'Glassworks on the stock software',
  community: false,
  mode: 'in-place',
  by: { name: 'GlassworksHQ', href: 'https://github.com/GlassworksHQ' },
  project: { name: 'glassworks.dev', href: 'https://www.glassworks.dev' },
  licence: 'MPL-2.0 and MIT',
  summary: 'Keeps lululemon’s system, with a restore point to go back to, and puts your Home Assistant dashboard on the glass.',
  advice: { tag: 'recommended', trade: 'Slower, but it keeps lululemon’s software, so you can always go back.' },
  support: { name: 'Glassworks on GitHub', href: 'https://github.com/GlassworksHQ/glassworks/issues' },
  files: [],
  steps: [
    { do: 'fastboot' },
    // the setup agent on a fresh userdata, sized from what fastboot reports; stock system untouched
    { do: 'stage', makes: 'stage-userdata' },
    { do: 'flash', partition: 'userdata', file: 'stage-userdata' },
    { do: 'restart' },
    // the agent's bundle: the stock services.jar and where it sits, signed with this run's secret
    { do: 'receive', bundle: 'stock-bundle', channels: ['lan', 'ble', 'file'] },
    { do: 'fastboot' },
    { do: 'in-place', bundle: 'stock-bundle', makes: ['system-in-place', 'userdata'], restore: 'restore-services' },
    // Kiln's restore point goes here, before the first write of what in-place made
    { do: 'flash', partition: 'system', file: 'system-in-place' },
    { do: 'flash', partition: 'userdata', file: 'userdata' },
    { do: 'restart' },
    { do: 'app-install' },
  ],
};

/** Glass Panes as its release has it, but for who it lets in over adb: today's way back from Glassworks, as data. */
export const GLASS_PANES_IMAGE: ImageRecipe = {
  id: 'glass-panes',
  name: panes.name,
  community: true,
  by: { name: panes.developer, href: panes.developerHref },
  project: { name: 'git-nimrod/glass-panes', href: panes.href },
  licence: 'MIT; the image’s parts keep their own',
  summary: 'Replaces everything on the Mirror with Android 6.0.1 as its release has it, with its own Mirror Kiosk app.',
  support: { name: 'Glass Panes on GitHub', href: `${panes.href}/issues` },
  vendor: 'Its vendor files belong to Qualcomm and Quectel.',
  release: { name: 'mirror-android-3', href: GLASS_PANES.release },
  next: { name: 'its README', href: `${panes.href}#readme` },
  files: [SYSTEM, USERDATA],
  steps: [
    { do: 'download', file: SYSTEM.name },
    { do: 'download', file: USERDATA.name },
    { do: 'fastboot' },
    // this browser's adb key in a copy of its system image, in place of the one its services.jar lets in without
    // asking (one file, one fingerprint in it): image 3's is a key Glass Panes never published, so every Mirror on
    // the image as released lets that computer in, and nobody on the Mirror can take it back
    { do: 'system-key', file: SYSTEM.name, makes: 'system+key' },
    // this browser's adb key in a copy of its userdata, in place of the published project key (one file), as the
    // image's own installer does it: the Mirror then lets in only this browser
    { do: 'adb-key', file: USERDATA.name, makes: 'userdata+key' },
    // optional: the person's network, saved in that copy (one file); without one, the copy with the key alone
    { do: 'wifi', file: 'userdata+key', template: SYSTEM.name, makes: 'userdata+wifi' },
    { do: 'flash', partition: 'system', file: 'system+key' },
    { do: 'flash', partition: 'userdata', file: 'userdata+wifi' },
    { do: 'restart' },
    // with a network saved: Wi-Fi on, over adb with this browser's key, so nobody opens Settings
    { do: 'wifi-on' },
  ],
};

/** Glassworks first, then Glassworks on the stock software; the community images after them, hidden until asked for. */
export const MIRROR_IMAGES: readonly ImageRecipe[] = [GLASSWORKS_IMAGE, GLASSWORKS_IN_PLACE, GLASS_PANES_IMAGE];
