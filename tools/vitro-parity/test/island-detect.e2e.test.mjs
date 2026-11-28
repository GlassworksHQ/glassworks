// The island's frontend watch (frontend-watch.js) in a real browser: each made-up case in island-cases/ (test-*.json,
// cards from island-cases/cards/) runs in the island page Vitro would serve, and must send `needsFrontend` exactly
// when its `expect` says. Pinned community cards (Better Thermostat's) need the fake HA and their download: run those
// with island-probe.mjs (docs/research/vitro-island-better-thermostat.md).
//
// Needs the pages first (Gradle, off CI's Node step), then Chromium:
//   ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=$(ls $PWD/tools/vitro-parity/island-cases/test-*.json | paste -sd, -)
//   GW_VITRO_ISLAND_E2E=1 node --test tools/vitro-parity/test/island-detect.e2e.test.mjs
// Without the pages, or without playwright-core, it skips, unless GW_VITRO_ISLAND_E2E=1, where it fails.
//
// Touches: a throwaway headless Chromium; no fake HA, no network (the cases' origin is answered in the browser).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CASES = join(ROOT, 'tools', 'vitro-parity', 'island-cases');
const required = process.env.GW_VITRO_ISLAND_E2E === '1';
const files = readdirSync(CASES).filter((f) => /^test-.*\.json$/.test(f)).sort().map((f) => join(CASES, f));

let lib = null;
let why = '';
try {
  lib = await import('../lib/island.mjs');
  const missing = files.filter((f) => !existsSync(lib.islandPagePath(ROOT, f)));
  if (missing.length) { why = `no island pages for ${missing.length} case(s): dump them with IslandPageDumpTest`; lib = null; }
} catch (e) {
  why = `playwright-core isn't installed (npm ci --prefix tools/vitro-parity): ${e.message}`;
}

test('the made-up cases hand off exactly when they draw an HA element the island lacks', { skip: !lib && !required ? why : false }, async () => {
  assert.ok(lib, why);
  const { launch } = await import('../lib/browser.mjs');
  const browser = await launch();
  try {
    for (const f of files) {
      const c = JSON.parse(readFileSync(f, 'utf8'));
      const r = await lib.probeIsland(ROOT, f, { browser });
      const reports = r.bridge.filter((m) => m.type === 'needsFrontend');
      assert.ok(r.before.instance, `${f}: the card drew (${JSON.stringify(r.before)})`);
      assert.deepEqual(r.errors, [], `${f}: no page errors`);
      if (c.expect.needsFrontend) {
        assert.equal(reports.length, 1, `${f}: one needsFrontend (${JSON.stringify(r.bridge)})`);
        assert.ok(reports[0].tags.length > 0 && reports[0].tags.every((t) => /^(ha|hui|state|more-info)-/.test(t)), JSON.stringify(reports[0]));
        assert.deepEqual(reports[0].cards, [c.card.type.replace(/^custom:/, '')], `${f}: the card is blamed`);
      } else {
        assert.equal(reports.length, 0, `${f}: no needsFrontend (${JSON.stringify(r.bridge)})`);
      }
    }
  } finally {
    await browser.close();
  }
});

// HA's own elements as the island defines them, checked on the plain made-up card (tablet Vitro run 3):
// - ha-card has HA's look in its own shadow root, so it holds inside the card's shadow root too (no rule of the page
//   reaches there): the card's background, 12 px corners, a 1 px border in --ha-card-border-color, and no header;
// - ha-state-icon with neither an icon nor a state object draws nothing, as HA's (a house before);
// - the island reports the card's getCardSize (2), which HA's masonry view places it by.
test('the island draws ha-card and ha-state-icon as HA does, and reports the card size', { skip: !lib && !required ? why : false }, async () => {
  assert.ok(lib, why);
  const plain = join(CASES, 'test-plain.json');
  const r = await lib.probeIsland(ROOT, plain, {
    evaluate: `c => {
      const card = c.shadowRoot.querySelector('ha-card');
      const cs = getComputedStyle(card);
      return {
        background: cs.backgroundColor, radius: cs.borderTopLeftRadius,
        border: [cs.borderTopWidth, cs.borderTopStyle, cs.borderTopColor, cs.borderLeftWidth, cs.borderBottomWidth].join(' '),
        header: card.shadowRoot.querySelectorAll('h1').length,
        stateIcon: c.shadowRoot.querySelector('ha-state-icon').childElementCount,
      };
    }`,
  });
  assert.deepEqual(r.evaluated, {
    background: 'rgb(28, 28, 28)', radius: '12px', border: '1px solid rgb(44, 44, 44) 1px 1px', header: 0, stateIcon: 0,
  });
  const sizes = r.bridge.filter((m) => m.type === 'cardSize');
  assert.deepEqual(sizes, [{ type: 'cardSize', size: 2 }], JSON.stringify(r.bridge));
});
