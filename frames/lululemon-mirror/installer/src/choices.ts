// What Kiln can put on the Mirror (#579): Glassworks, first and the default, and the community images
// it offers, each a recipe of data over Kiln's step library (kiln-core images.ts). Kiln wraps each
// in its safety steps: the fastboot checks, the hashes of what it writes (the Mirror's bootloader
// can't read anything back, so there is no backup), the wipe warning, and the way back on Done.
// How to add one: docs/kiln-images.md. Only URLs and hashes here, never an image's bytes.
import type { FrameAccess, ImageRecipe } from '@glassworks/kiln-core';
import { GLASS_PANES_USERDATA } from './flash';
import { GLASS_PANES } from './image';

/** The steps the Mirror's page runs, and the partitions a recipe may write: never LK, boot or the modem. */
export const MIRROR_ACCESS: FrameAccess = { steps: ['download', 'patch', 'fastboot', 'flash', 'restart', 'first-start'], partitions: ['system', 'userdata'] };

/** Refract's ids for the Glass Panes release files (kiln/workers/refract/allowlist.json). */
const onRefract = <F extends { name: string }>(f: F) => ({ ...f, refract: `glass-panes/mirror-android-2/${f.name}` });
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
  summary: 'Your Home Assistant dashboard on the glass, built on the Glass Panes image.',
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

/** Glass Panes as its release has it: today's way back from Glassworks, as data. */
export const GLASS_PANES_IMAGE: ImageRecipe = {
  id: 'glass-panes',
  name: panes.name,
  community: true,
  by: { name: panes.developer, href: panes.developerHref },
  project: { name: 'git-nimrod/glass-panes', href: panes.href },
  licence: 'MIT; the image’s parts keep their own',
  summary: 'Android 6.0.1 for the Mirror as its release has it, with its own Mirror Kiosk app.',
  support: { name: 'Glass Panes on GitHub', href: `${panes.href}/issues` },
  release: { name: 'mirror-android-2', href: GLASS_PANES.release },
  next: { name: 'its README', href: `${panes.href}#readme` },
  files: [SYSTEM, USERDATA],
  steps: [
    { do: 'download', file: SYSTEM.name },
    { do: 'download', file: USERDATA.name },
    { do: 'fastboot' },
    { do: 'flash', partition: 'system', file: SYSTEM.name },
    { do: 'flash', partition: 'userdata', file: USERDATA.name },
    { do: 'restart' },
  ],
};

/** Glassworks first; the community images after it, hidden on the page until asked for. */
export const MIRROR_IMAGES: readonly ImageRecipe[] = [GLASSWORKS_IMAGE, GLASS_PANES_IMAGE];
