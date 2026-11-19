// Mockups for issue 579 (community images), not wired to a real install: the shape a frame's recipe would
// give each image it offers. Glassworks first, the default; community images after it. Throwaway
// until the maintainer approves the design; the real list moves into frames/<id>/installer.
import { GLASS_PANES, GLASS_PANES_USERDATA } from '@glassworks/lululemon-mirror-installer';
import { FRAMES } from '../../frames/catalog';

export type Link = { name: string; href: string };

export interface ImageOption {
  id: 'glassworks' | 'glass-panes';
  name: string;
  by: Link;
  project: Link;
  licence: string;
  summary: string;
  /** Where to ask for help. Not Glassworks for a community image. */
  support: Link;
  community: boolean;
  /** Fetched and checked by Kiln, in this order. */
  files: readonly { name: string; size: number; sha256: string }[];
  /** After Done: the project's own guide for its first start. */
  guide?: Link;
}

const panes = FRAMES.find((f) => f.id === 'lululemon-mirror')?.credits?.find((c) => c.id === 'glass-panes-image');

export const OPTIONS: readonly ImageOption[] = [
  {
    id: 'glassworks',
    name: 'Glassworks',
    by: { name: 'GlassworksHQ', href: 'https://github.com/GlassworksHQ' },
    project: { name: 'glassworks.dev', href: 'https://www.glassworks.dev' },
    licence: 'MPL-2.0 and MIT',
    summary: 'Your Home Assistant dashboard on the glass, built on the Glass Panes image.',
    support: { name: 'Glassworks on GitHub', href: 'https://github.com/GlassworksHQ/glassworks/issues' },
    community: false,
    files: [GLASS_PANES.system],
  },
  {
    id: 'glass-panes',
    name: panes?.project.name ?? 'Glass Panes',
    by: panes?.who ?? { name: 'git-nimrod', href: 'https://github.com/git-nimrod' },
    project: { name: 'git-nimrod/glass-panes', href: panes?.project.href ?? GLASS_PANES.project.href },
    licence: 'MIT; the image’s parts keep their own',
    summary: 'Android 6.0.1 for the Mirror as its release has it, with its own Mirror Kiosk app.',
    support: { name: 'Glass Panes on GitHub', href: `${GLASS_PANES.project.href}/issues` },
    community: true,
    files: [GLASS_PANES.system, GLASS_PANES_USERDATA],
    guide: { name: 'Glass Panes’ install guide', href: `${GLASS_PANES.project.href}#readme` },
  },
];

export const option = (id: ImageOption['id']) => OPTIONS.find((o) => o.id === id)!;

/** `?image=<id>` names a community image this frame offers; anything else (none, unknown, Glassworks) is the normal page. */
export function imageFromLink(search: string): ImageOption | undefined {
  const id = new URLSearchParams(search).get('image');
  return OPTIONS.find((o) => o.community && o.id === id);
}
