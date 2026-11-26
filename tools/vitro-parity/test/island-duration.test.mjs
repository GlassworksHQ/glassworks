// The island page's duration text (format-duration.js, inlined by WebIslandCardView.buildIslandHtml), run in Node:
// HA's formatDuration (frontend 20261028.5, common/datetime/format_duration.ts) writes a duration sensor's state as
// "1h 30m", never "1.5 h". Touches nothing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SOURCE = readFileSync(join(ROOT, 'engines', 'engine-vitro', 'src', 'main', 'resources', 'dev', 'glassworks', 'engine', 'vitro', 'island', 'format-duration.js'), 'utf8');

function load() {
  const ctx = vm.createContext({ Intl });
  vm.runInContext(SOURCE, ctx);
  return ctx;
}

// Intl without DurationFormat, as an older Gecko has it: HA's own fallback runs.
const NO_DURATION_FORMAT = { NumberFormat: Intl.NumberFormat, ListFormat: Intl.ListFormat };

const EN = { language: 'en' };

// What HA's page writes, for the state and unit of a duration sensor (with its display precision).
const CASES = [
  ['1.5', 'h', undefined, '1h 30m'],
  // A zero in the smaller unit isn't written.
  ['2', 'h', undefined, '2h'],
  ['0.25', 'h', undefined, '0h 15m'],
  // Minutes stay minutes: HA splits a value into its unit and the next one down, and never carries them over.
  ['90', 'min', undefined, '90m'],
  ['45.5', 'min', undefined, '45m 30s'],
  ['2.25', 'd', undefined, '2d 6h'],
  ['0.5', 'd', undefined, '0d 12h'],
  // The entity's display precision rounds first: 1.999 h at 1 decimal is 2 h.
  ['1.999', 'h', 1, '2h'],
  ['1.999', 'h', undefined, '1h 59m'],
];

test('a duration reads as HA writes it, in each unit HA formats', () => {
  const ctx = load();
  for (const [state, unit, precision, want] of CASES) {
    assert.equal(ctx.vitroFormatDuration(EN, state, unit, precision, NO_DURATION_FORMAT), want, `${state} ${unit} (fallback)`);
    if ('DurationFormat' in Intl) {
      assert.equal(ctx.vitroFormatDuration(EN, state, unit, precision), want, `${state} ${unit} (Intl.DurationFormat)`);
    }
  }
  assert.equal(vm.runInContext('JSON.stringify(VITRO_DURATION_UNITS)', ctx), '["min","h","d"]');
});

test('the fallback writes what Intl.DurationFormat writes, in other languages too', { skip: !('DurationFormat' in Intl) && 'no Intl.DurationFormat in this Node' }, () => {
  const ctx = load();
  for (const language of ['en', 'de', 'fr', 'nl', 'ja']) {
    for (const [state, unit] of [['1.5', 'h'], ['45.5', 'min'], ['2.25', 'd'], ['3', 'h']]) {
      assert.equal(
        ctx.vitroFormatDuration({ language }, state, unit, undefined, NO_DURATION_FORMAT),
        ctx.vitroFormatDuration({ language }, state, unit),
        `${language}: ${state} ${unit}`,
      );
    }
  }
});

test('a state that is no number throws, so the page falls back to the number and its unit as HA does', () => {
  const ctx = load();
  assert.throws(() => ctx.vitroFormatDuration(EN, 'soon', 'h', undefined, NO_DURATION_FORMAT), { name: 'RangeError' });
  assert.throws(() => ctx.vitroFormatDuration(EN, '1', 's', undefined, NO_DURATION_FORMAT));
});
