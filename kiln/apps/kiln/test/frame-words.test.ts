// Each frame's installer.json `words` are what the shared wizard says for it, checked where they come in: a missing or
// misspelt one fails here and at import, never as an empty heading on a screen.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { kiln } from '@glassworks/aqara-s1-plus-installer';
import { demoFrame, frame } from '../src/app/aqara-s1-plus/frame';
import { frameWords } from '../src/wizard/frame';
import { REPO } from './contract/harness';

/** The frames on the shared wizard, with the Silent updates states their code gives. */
const FRAMES = [['aqara-s1-plus', kiln.updateStates]] as const;
const words = (id: string) => JSON.parse(readFileSync(join(REPO, 'frames', id, 'design', 'installer.json'), 'utf8')).words;

describe('installer.json words', () => {
  it.each(FRAMES)('%s: every word the wizard reads is there', (id, states) => {
    expect(() => frameWords(words(id), states)).not.toThrow();
  });

  it('a missing word, a missing Silent updates state or a tone that isn\'t a status fails, naming it', () => {
    const [id, states] = FRAMES[0];
    const w = words(id);
    const { felPick: _, ...noPick } = w;
    expect(() => frameWords(noPick, states)).toThrow('words.felPick: missing');
    expect(() => frameWords({ ...w, undo: { ...w.undo, keep: { ...w.undo.keep, back: '' } } }, states)).toThrow('words.undo.keep.back: missing');
    const { missing: __, ...noMissing } = w.silent;
    expect(() => frameWords({ ...w, silent: noMissing }, states)).toThrow('words.silent.missing: missing');
    expect(() => frameWords({ ...w, silent: { ...w.silent, on: { word: 'On', tone: 'green', line: 'x' } } }, states)).toThrow('words.silent.on.tone');
  });

  it('the frame Kiln installs carries no simulated device: only the demo frame beside it does', () => {
    expect(frame).not.toHaveProperty('sim');
    expect(kiln).not.toHaveProperty('sim');
    expect(demoFrame.frame).toBe(frame);
    expect(demoFrame.sim.recipe).not.toBe(frame.recipe);
  });
});
