// Each frame's installer.json `words` are what the shared wizard says for it, checked where they come in: a missing or
// misspelt one fails here and at import, never as an empty heading on a screen.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { demoFrame, frame } from './s1-frame';
import { frameWords } from '@glassworks/kiln-core';
import { REPO } from './contract/harness';

/** The frames on the shared wizard, with the Silent updates states their code gives. */
const FRAMES = [['aqara-s1-plus', frame.updateStates]] as const;
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

  it('times and recovery steps are optional, but an empty one fails, naming it', () => {
    const [id, states] = FRAMES[0];
    const { time: _, recoverySteps: __, ...w } = words(id);
    expect(() => frameWords(w, states)).not.toThrow();
    expect(() => frameWords({ ...w, time: { total: '' } }, states)).toThrow('words.time.total: missing');
    expect(() => frameWords({ ...w, time: { steps: { 'first-boot': '' } } }, states)).toThrow('words.time.steps.first-boot: missing');
    expect(() => frameWords({ ...w, recoverySteps: [] }, states)).toThrow('words.recoverySteps');
    expect(() => frameWords({ ...w, recoverySteps: ['**Unplug it**', ''] }, states)).toThrow('words.recoverySteps');
  });

  it('a time a step names is a step of the frame\'s install flow', () => {
    for (const [id] of FRAMES) {
      const json = JSON.parse(readFileSync(join(REPO, 'frames', id, 'design', 'installer.json'), 'utf8'));
      const ids = json.flows.install.steps.map((x: { id: string }) => x.id);
      for (const k of Object.keys(json.words.time?.steps ?? {})) expect(ids, `${id} words.time.steps.${k}`).toContain(k);
    }
  });

  it('the frame Kiln installs carries no simulated device: only the demo frame beside it does', () => {
    expect(frame).not.toHaveProperty('sim');
    expect(demoFrame.frame).toBe(frame);
    expect(demoFrame.sim.recipe).not.toBe(frame.recipe);
  });
});
