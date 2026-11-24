// The Mirror's images as recipes (#579): both valid for the Mirror, every file pinned and on Refract's
// allowlist with the same size and SHA-256, and Glass Panes credited as frame.json credits it.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { assertImages, plan, validateImage } from '@glassworks/kiln-core';
import { describe, expect, it } from 'vitest';
import { GLASS_PANES, GLASS_PANES_IMAGE, GLASS_PANES_USERDATA, GLASSWORKS_IMAGE, MIRROR_ACCESS, MIRROR_IMAGES } from '../src';

const ROOT = join(__dirname, '../../../..');
const allowlist = JSON.parse(readFileSync(join(ROOT, 'kiln/workers/refract/allowlist.json'), 'utf8')) as Record<string, { owner: string; repo: string; tag?: string; name?: string; commit?: string; path?: string; size: number; sha256: string }>;
const frame = JSON.parse(readFileSync(join(__dirname, '../../design/frame.json'), 'utf8')) as { credits: { id: string; who: { name: string; href: string }; project: { name: string; href: string } }[] };

describe('the Mirror\'s images', () => {
  it('are valid recipes, Glassworks first', () => {
    expect(() => assertImages(MIRROR_IMAGES, MIRROR_ACCESS)).not.toThrow();
    for (const r of MIRROR_IMAGES) expect(validateImage(r, MIRROR_ACCESS)).toEqual([]);
    expect(MIRROR_IMAGES.map((r) => [r.id, r.community])).toEqual([['glassworks', false], ['glass-panes', true]]);
  });

  it('never let a recipe write the bootloader, boot or the modem', () => {
    expect(MIRROR_ACCESS.partitions).toEqual(['system', 'userdata']);
  });

  it('pin every file to Refract\'s allowlist: same source (a release file, or a repository file at a commit), size and SHA-256', () => {
    for (const f of MIRROR_IMAGES.flatMap((r) => r.files)) {
      const a = allowlist[f.refract];
      expect(a, f.refract).toBeDefined();
      expect(a!.commit ? `https://raw.githubusercontent.com/${a!.owner}/${a!.repo}/${a!.commit}/${a!.path}` : `https://github.com/${a!.owner}/${a!.repo}/releases/download/${a!.tag}/${a!.name}`).toBe(f.url);
      expect([a!.size, a!.sha256]).toEqual([f.size, f.sha256]);
    }
    expect(GLASS_PANES_IMAGE.files).toEqual([
      { ...GLASS_PANES.system, refract: 'glass-panes/mirror-android-3/system.img' },
      { ...GLASS_PANES_USERDATA, refract: 'glass-panes/mirror-android-3/userdata.sparse' },
    ]);
    // both options are on the same release
    expect(GLASSWORKS_IMAGE.files.map((f) => f.refract)).toEqual(['glass-panes/mirror-android-3/system.img']);
  });

  it('Glass Panes: both files downloaded and checked, fastboot and Kiln\'s checks, the warning, this browser\'s key in its system and its userdata, written, restart', () => {
    expect(plan(GLASS_PANES_IMAGE).map((s) => ('partition' in s ? `${s.do} ${s.partition} ${s.file}` : 'file' in s ? `${s.do} ${s.file}` : s.do))).toEqual([
      'download system.img', 'download userdata.sparse', 'fastboot', 'checks', 'backup', 'confirm',
      'system-key system.img', 'adb-key userdata.sparse', 'wifi userdata+key', 'flash system system+key', 'flash userdata userdata+wifi', 'restart', 'wifi-on', 'way-back',
    ]);
    // the image's hardcoded key never reaches the Mirror: what Kiln writes to system is its copy with this browser's
    expect(GLASS_PANES_IMAGE.steps.filter((s) => s.do === 'flash' && s.partition === 'system')).toEqual([{ do: 'flash', partition: 'system', file: 'system+key' }]);
    // Wi-Fi on with this browser's key, never the published project key's
    expect(GLASS_PANES_IMAGE.steps.find((s) => s.do === 'wifi-on')).toEqual({ do: 'wifi-on' });
  });

  it('Glassworks: the steps the page has always run, now as data', () => {
    expect(plan(GLASSWORKS_IMAGE).map((s) => s.do)).toEqual(['download', 'fastboot', 'checks', 'backup', 'confirm', 'patch', 'flash', 'flash', 'restart', 'first-start', 'way-back']);
  });

  it('credit Glass Panes as frame.json does', () => {
    const c = frame.credits.find((x) => x.id === 'glass-panes-image')!;
    expect(GLASS_PANES_IMAGE.by).toEqual(c.who);
    expect(GLASS_PANES_IMAGE.name).toBe(c.project.name);
    expect(GLASS_PANES_IMAGE.project.href).toBe(c.project.href);
  });
});
