// Kiln's app and packages name no frame (#621, kiln/AGENTS.md "One engine, frames as data"). Everything about a frame
// lives in frames/<id>/. kiln-core has held to this since its start (its test/no-frame-names.test.ts); this check
// extends the idea to kiln/apps/kiln/src and every package in kiln/packages.
//
// Today's hits are in frame-neutral-allowlist.json, each with the issue that removes it. The list only shrinks: a hit
// outside it fails, and so does an entry with no hit left (delete the entry in the PR that removes the last hit).
// `notFrames` holds words that only look like a frame's name (SHA-256's S1), under the same no-stale rule.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';
import allowlist from './frame-neutral-allowlist.json';
import { REPO } from './harness';

/** Each frame name with samples it must catch, one per alternative, as kiln-core's test does. */
export const NAMES: [term: string, pattern: RegExp, catches: string[]][] = [
  // the word S1 or s1, and s1 starting a name (s1Plus, s1-backup, s1kiosk); SHA-256's S1 and s1 are in notFrames
  ['s1', /\bS1(?!\d)|\bs1\b|\bs1(?=[A-Z_-]|kiosk)/, ['S1 Plus', 'S1.tsx', 'Connect your s1', 's1Plus', 'aqara-s1-plus', 'sh.edm.s1kiosk', 's1_overlay']],
  ['aqara', /aqara/i, ['Aqara', 'aqara-s1-plus']],
  ['lululemon', /lululemon/i, ['lululemon Studio Mirror', 'lululemon-mirror']],
  // camelCase too (keptMirror, MIRROR_BASE); "mirrors", "mirrored" and "mirroring" are plain English
  ['mirror', /mirror(?!(?:s|ed|ing)\b)/i, ['Mirror', 'keptMirror', 'MIRROR_BASE', 'src/mirror/']],
  ['glass-panes', /glass.?panes/i, ['Glass Panes', 'GLASS_PANES', 'glass-panes']],
  ['generic-android', /generic.?android/i, ['Generic Android', 'generic-android', 'genericAndroid', 'generic_android']],
  ['pixel-tablet', /pixel.?tablet/i, ['Google Pixel Tablet', 'google-pixel-tablet', 'pixelTablet']],
];

/** Where frame names may not be: Kiln's app and its packages, their sources and their file names. */
const ROOTS = ['kiln/apps/kiln/src', ...readdirSync(join(REPO, 'kiln/packages')).map((p) => `kiln/packages/${p}/src`)];

function files(dir: string): string[] {
  let names: string[];
  try { names = readdirSync(dir); } catch { return []; }
  return names.flatMap((n) => {
    const p = join(dir, n);
    if (n === 'node_modules' || n.startsWith('.')) return [];
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

/** Every (file, term) with a hit in the file's path or text, with the first line it is on. */
export function hits(): Map<string, { file: string; term: string; line: string }> {
  const out = new Map<string, { file: string; term: string; line: string }>();
  for (const root of ROOTS) {
    for (const abs of files(join(REPO, root))) {
      const file = relative(REPO, abs).split(sep).join('/'); // forward slashes on every OS, as the allowlist has them
      if (/\.(webp|png|jpe?g|gif|wasm|woff2?|ico|zip|bin)$/i.test(file)) continue;
      const lines = [file, ...readFileSync(abs, 'utf8').split('\n')];
      for (const [term, pattern] of NAMES) {
        const at = lines.findIndex((l) => pattern.test(l));
        if (at >= 0) out.set(`${file} ${term}`, { file, term, line: at === 0 ? `(its path) ${file}` : `${at}: ${lines[at]!.trim().slice(0, 120)}` });
      }
    }
  }
  return out;
}

interface Entry { path: string; terms: string[]; why: string }
const ENTRIES = (allowlist as { entries: Entry[] }).entries;
/** Words that only look like a frame's name (SHA-256's S1). Checked for hits too, but they need no issue. */
const NOT_FRAMES = (allowlist as { notFrames: Entry[] }).notFrames;
const covers = (e: Entry, file: string, term: string) => (e.path.endsWith('/') ? file.startsWith(e.path) : file === e.path) && e.terms.includes(term);

describe('Kiln names no frame', () => {
  const found = hits();

  it.each(NAMES.flatMap(([term, pattern, catches]) => catches.map((s) => [s, term, pattern] as const)))('the pattern catches %s (%s)', (s, _, pattern) => {
    expect(pattern.test(s)).toBe(true);
  });

  it.each(['const s10 = 1', 'xs1', 'it mirrors the CLI', 'mirrored', 'mirroring the log', 'genericity'])('and leaves plain code and English alone: %s', (s) => {
    expect(NAMES.filter(([, p]) => p.test(s)).map(([t]) => t)).toEqual([]);
  });

  it('a pattern catches every frame\'s id and name', () => {
    const frames = readdirSync(join(REPO, 'frames')).flatMap((id) => {
      try { return [JSON.parse(readFileSync(join(REPO, 'frames', id, 'design', 'frame.json'), 'utf8')) as { id: string; name: string }]; } catch { return []; }
    });
    expect(frames.length).toBeGreaterThan(2);
    const missed = frames.flatMap((f) => [f.id, f.name]).filter((s) => !NAMES.some(([, p]) => p.test(s)));
    expect(missed, 'a new frame: add its names to NAMES').toEqual([]);
  });

  it('reads Kiln\'s sources', () => {
    expect([...found.values()].some((h) => h.file === 'kiln/apps/kiln/src/wizard/session.ts')).toBe(true);
  });

  it('no frame name outside the allowlist: say it in frames/<id>/, not in Kiln', () => {
    const outside = [...found.values()].filter((h) => ![...ENTRIES, ...NOT_FRAMES].some((e) => covers(e, h.file, h.term))).map((h) => `${h.file} [${h.term}] ${h.line}`);
    expect(outside).toEqual([]);
  });

  it('no allowlist entry without a hit: the list only shrinks', () => {
    const stale = [...ENTRIES, ...NOT_FRAMES].flatMap((e) => e.terms.filter((t) => ![...found.values()].some((h) => h.term === t && covers(e, h.file, t))).map((t) => `${e.path} [${t}]`));
    expect(stale, 'nothing names this any more: delete it from frame-neutral-allowlist.json').toEqual([]);
  });

  it('each entry says which issue removes it, and lists each path once', () => {
    expect(ENTRIES.filter((e) => !/#\d+/.test(e.why)).map((e) => e.path)).toEqual([]);
    const paths = [...ENTRIES, ...NOT_FRAMES].map((e) => e.path);
    expect(paths.filter((p, i) => paths.indexOf(p) !== i)).toEqual([]);
    expect(ENTRIES.filter((e) => !e.terms.every((t) => NAMES.some(([n]) => n === t))).map((e) => e.path)).toEqual([]);
  });
});
