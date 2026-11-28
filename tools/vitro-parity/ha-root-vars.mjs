#!/usr/bin/env node
// HA's own CSS variables for a Vitro island: every custom property Home Assistant's frontend puts on the page's
// :root with its default theme, in light and in dark mode, as the pinned frontend computes them. A card in HA's page
// inherits all of them (HA's color tokens, `--rgb-disabled-color`, `--ha-space-*`, `--ha-font-*`, …); an island page
// has no HA frontend, so it starts its :root from this table and puts the dashboard's resolved theme over it
// (ResolvedTheme.toCssRootBlock). A theme value that names one of them (`ha-card-background:
// var(--ha-color-surface-default)`) resolves to HA's value too, natively and in the island.
//
// Values are computed: Chromium substitutes every var() in a custom property, so each one is a plain value. Left out:
// what depends on the window or the writing direction (`--safe-*`, `--direction`, `--float-*`, `--margin-title-*`),
// the header height (the island's is 0), and the code editor's and Web Awesome's internals (`--codemirror-*`,
// `--rgb-codemirror-*`, `--wa-*`), which no card reads.
//
// Touches: a fake HA on 127.0.0.1 (tools/perf/fakeha.py with fakehome.py, stopped at the end) with the pinned
// frontend (tools/perf/hafrontend.py fetches it into ~/.cache/glassworks-perf on first use), and a throwaway headless
// Chromium. Writes only engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/theme/ha-root-vars.json.
// No real HA, no device.
//
//   node tools/vitro-parity/ha-root-vars.mjs [--check]
//
// `--check` writes nothing and exits 1 when the committed table differs from what the frontend computes now.
import { launch } from './lib/browser.mjs';
import { startFake } from './lib/fake.mjs';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CORPUS = join(ROOT, 'engines', 'engine-vitro', 'src', 'test', 'parity');
const OUT = join(ROOT, 'engines', 'engine-vitro', 'src', 'main', 'resources', 'dev', 'glassworks', 'engine', 'vitro', 'theme', 'ha-root-vars.json');
const FRONTEND = readFileSync(join(ROOT, 'tools', 'perf', 'hafrontend.py'), 'utf8').match(/^VERSION = "([^"]+)"/m)[1];
const SKIP = /^(safe-|direction$|float-|margin-title-|header-height$|(rgb-)?codemirror-|wa-)/;

const { origin, path } = await startFake(ROOT, CORPUS, { cards: [], fixture: {}, id: 'ha-root-vars' });
const browser = await launch();
const table = { _comment: `Home Assistant frontend ${FRONTEND}'s :root custom properties with its default theme (Apache-2.0, Copyright Home Assistant contributors), from tools/vitro-parity/ha-root-vars.mjs. Don't edit: run the script again.`, frontend: FRONTEND };
try {
  for (const mode of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 960, height: 480 }, colorScheme: mode });
    await ctx.addInitScript((origin) => {
      localStorage.setItem('hassTokens', JSON.stringify({ access_token: 'ha-root-vars', token_type: 'Bearer', expires_in: 1e9, hassUrl: origin, clientId: origin + '/', expires: Date.now() + 1e12, refresh_token: 'x' }));
    }, origin);
    const page = await ctx.newPage();
    await page.goto(origin + path, { waitUntil: 'load' });
    // HA applies its theme once it has connected and read the user's choice: wait for the mode's own card color.
    await page.waitForFunction((dark) => {
      const v = getComputedStyle(document.documentElement).getPropertyValue('--card-background-color').trim();
      return v && (dark ? v !== '#fff' && v !== '#ffffff' : v === '#fff' || v === '#ffffff');
    }, mode === 'dark', { timeout: 60000 });
    await page.waitForTimeout(1500);
    const vars = await page.evaluate(() => {
      const cs = getComputedStyle(document.documentElement);
      const out = {};
      for (const p of cs) if (p.startsWith('--')) out[p.slice(2)] = cs.getPropertyValue(p).trim();
      return out;
    });
    table[mode] = Object.fromEntries(Object.keys(vars).filter((k) => !SKIP.test(k) && vars[k] !== '').sort().map((k) => [k, vars[k]]));
    await ctx.close();
  }
} finally {
  await browser.close();
}
const json = JSON.stringify(table, null, 1) + '\n';
if (process.argv.includes('--check')) {
  let committed = '';
  try { committed = readFileSync(OUT, 'utf8'); } catch {}
  if (committed !== json) { console.error(`${OUT} differs from what frontend ${FRONTEND} computes: run node tools/vitro-parity/ha-root-vars.mjs`); process.exit(1); }
  console.log(`ha-root-vars.json matches frontend ${FRONTEND}`);
  process.exit(0);
}
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, json);
console.log(`wrote ${OUT}: ${Object.keys(table.light).length} light, ${Object.keys(table.dark).length} dark variables (frontend ${FRONTEND})`);
process.exit(0);
