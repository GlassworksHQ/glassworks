// Each frame's installer.json `words` are what the shared wizard says for it, checked where they come in: a missing or
// misspelt one fails here and at import, never as an empty heading on a screen.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { frameWords } from '../src/wizard/frame';
import { REPO } from './contract/harness';

const designs = readdirSync(join(REPO, 'frames')).flatMap((id) => {
  try { return [[id, JSON.parse(readFileSync(join(REPO, 'frames', id, 'design', 'installer.json'), 'utf8'))] as const]; } catch { return []; }
});
const worded = designs.filter(([, d]) => d.words);

describe('installer.json words', () => {
  it('a frame on the shared wizard has them', () => {
    expect(worded.map(([id]) => id)).toContain('aqara-s1-plus');
  });

  it.each(worded)('%s: every word the wizard reads is there', (_, d) => {
    expect(() => frameWords(d.words)).not.toThrow();
  });

  it('a missing word or a tone that isn\'t a status fails, naming it', () => {
    const [, d] = worded[0]!;
    const { felPick: _, ...noPick } = d.words;
    expect(() => frameWords(noPick)).toThrow('words.felPick: missing');
    expect(() => frameWords({ ...d.words, undo: { ...d.words.undo, keep: { ...d.words.undo.keep, back: '' } } })).toThrow('words.undo.keep.back: missing');
    expect(() => frameWords({ ...d.words, silent: { on: { word: 'On', tone: 'green', line: 'x' } } })).toThrow('words.silent.on.tone');
  });
});
