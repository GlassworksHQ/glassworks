// Credit where it's due (frames/README.md#credits): every frame installer says which credits its steps
// use, those credits exist in its frame.json, and a link to someone else's code in an installer or in
// Kiln has a credit that a recipe uses. A step that downloads another project's image without
// crediting it fails here.
import { existsSync, globSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { FrameCredit, FrameDesign } from '@glassworks/frame-card';
import type { StepCredits } from '@glassworks/kiln-core';

const repo = fileURLToPath(new URL('../../../..', import.meta.url));
const frames = join(repo, 'frames');
const read = (id: string) => JSON.parse(readFileSync(join(frames, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;
const installers = readdirSync(frames).filter((id) => existsSync(join(frames, id, 'installer', 'package.json')));

/** Code hosts whose links name an owner, and the one owner that is us. */
const CODE = /https?:\/\/(?:www\.)?(github\.com|gitlab\.com|codeberg\.org)\/(?:orgs\/)?([\w.-]+)(?:\/([\w.-]+))?/gi;
const OURS = 'glassworkshq';

interface Ref { host: string; owner: string; repo?: string }
const ref = (host: string, owner: string, r?: string): Ref => ({ host: host.toLowerCase(), owner: owner.toLowerCase(), repo: r?.toLowerCase().replace(/\.git$/, '') });

/** Someone else's code-host links in a file's text, as `url` and where it points. */
function outsideLinks(text: string): (Ref & { url: string })[] {
  return [...text.matchAll(CODE)].map((m) => ({ url: m[0], ...ref(m[1]!, m[2]!, m[3]) })).filter((r) => r.owner !== OURS);
}

/** Whether a credit's profile or project link covers `r`: the same owner, and the same repo if the link names one. */
function covers(c: FrameCredit, r: Ref): boolean {
  return [c.who.href, c.project.href].some((href) => {
    const m = [...href.matchAll(CODE)][0];
    if (!m) return false;
    const l = ref(m[1]!, m[2]!, m[3]);
    return l.host === r.host && l.owner === r.owner && (!l.repo || l.repo === r.repo);
  });
}

/** The links in `text` that none of `used` covers. */
export function uncredited(text: string, used: readonly FrameCredit[]): string[] {
  return outsideLinks(text).filter((r) => !used.some((c) => covers(c, r))).map((r) => r.url);
}

const stepsOf = async (id: string) =>
  (await import(join(frames, id, 'installer', 'src', 'index.ts')) as { credits?: StepCredits }).credits;
const usedBy = async (id: string) => {
  const ids = new Set(Object.values((await stepsOf(id)) ?? {}).flatMap((v) => v ?? []));
  return (read(id).credits ?? []).filter((c) => ids.has(c.id));
};
const files = (dir: string) => globSync('**/*.{ts,tsx}', { cwd: dir }).map((f) => ({ f: join(dir, f), text: readFileSync(join(dir, f), 'utf8') }));

describe('frame credits', () => {
  it('has an installer to check', () => {
    expect(installers).toContain('aqara-s1-plus');
  });

  it.each(installers)('%s: its installer says which credits each step uses, and the frame has them', async (id) => {
    const steps = await stepsOf(id);
    expect(steps, `frames/${id}/installer exports no \`credits\` (StepCredits; {} if it uses no outside work)`).toBeTypeOf('object');
    const have = new Set((read(id).credits ?? []).map((c) => c.id));
    const missing = Object.entries(steps!).flatMap(([step, ids]) => (ids ?? []).filter((c) => !have.has(c)).map((c) => `${step}: ${c}`));
    expect(missing, `credit ids not in frames/${id}/design/frame.json`).toEqual([]);
  });

  it.each(installers)('%s: every link to someone else\'s code in the installer has a credit its steps use', async (id) => {
    const used = await usedBy(id);
    const bad = files(join(frames, id, 'installer', 'src')).flatMap(({ f, text }) => uncredited(text, used).map((u) => `${f.slice(repo.length)}: ${u}`));
    expect(bad).toEqual([]);
  });

  it('every link to someone else\'s code in Kiln has a credit a recipe uses', async () => {
    const used = (await Promise.all(installers.map(usedBy))).flat();
    const dirs = [join(repo, 'kiln', 'apps', 'kiln', 'src'), ...globSync('kiln/packages/*/src', { cwd: repo }).map((d) => join(repo, d))];
    const bad = dirs.flatMap(files).flatMap(({ f, text }) => uncredited(text, used).map((u) => `${f.slice(repo.length)}: ${u}`));
    expect(bad).toEqual([]);
  });

  it('the S1 credits FEL tooling for booting and Magisk for the patch', async () => {
    expect(await stepsOf('aqara-s1-plus')).toEqual({ boot: ['sunxi-tools'], patch: ['magisk'] });
  });
});

describe('uncredited', () => {
  const glassPanes = read('lululemon-mirror').credits!.find((c) => c.id === 'glass-panes-image')!;
  const release = 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/system.img';

  it('flags another project\'s release that no used credit covers', () => {
    expect(uncredited(`const IMAGE = '${release}';`, [])).toEqual([release.slice(0, 'https://github.com/git-nimrod/glass-panes'.length)]);
  });

  it('passes it once its credit is used', () => {
    expect(uncredited(`const IMAGE = '${release}';`, [glassPanes])).toEqual([]);
  });

  it('needs the same repo when the credit names one, and any repo when it names only the owner', () => {
    const projectOnly = { ...glassPanes, who: { name: 'x', href: 'https://example.com' } };
    expect(uncredited('https://github.com/git-nimrod/other', [projectOnly])).toEqual(['https://github.com/git-nimrod/other']);
    expect(uncredited('https://github.com/git-nimrod/other', [glassPanes]), 'its profile link names only the owner').toEqual([]);
  });

  it('ignores our own links', () => {
    expect(uncredited('https://github.com/GlassworksHQ/glassworks/releases and https://github.com/orgs/GlassworksHQ/projects/1', [])).toEqual([]);
  });
});
