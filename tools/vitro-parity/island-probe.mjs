#!/usr/bin/env node
// What a custom card draws in a Vitro island (#885): runs the island page Vitro would serve for a case's card (written
// by IslandPageDumpTest) in headless Chromium, with the island's bridge socket answered here: a snapshot of the
// fixture's states, empty registries, and every hass.callWS and connection subscription refused. It reports whether the card's element exists, how
// tall it drew, the custom elements in it that nothing defines, what the page sent over the bridge (cardError,
// needsFrontend, resize, callWs, …) and any page errors. `--click x,y` taps the page there and reports what the tap
// sent. `--eval '<function>'` calls a function in the page with the card's element (null when there is none) and
// prints what it returns, e.g. `'c => c && c.shadowRoot.innerHTML'`. `--snapshot-delay ms` holds the bridge's first
// snapshot back, so the card draws once with no states (as when its script loads before the bridge connects). A card pinned in cards.json loads from the parity harness's fake HA; a made-up one (island-cases/cards/)
// needs none (lib/island.mjs).
//
// Touches: a fake HA on 127.0.0.1 when the case needs one (stopped at the end) and a throwaway headless Chromium.
// Reads the island page from engines/engine-vitro/build/vitro-island/ and the pinned cards from
// ~/.cache/glassworks-perf. Writes nothing but an optional --shot PNG. No real HA, no device.
//
//   ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=<case.json>[,<case.json>…]
//   node tools/vitro-parity/island-probe.mjs <case.json> [--frame tablet] [--click x,y] [--shot out.png] [--eval '<fn>'] [--snapshot-delay ms]
//
// <case.json>: `{"card": {...}, "resources": ["/hacsfiles/<card>/<file>.js?hacstag=<version>"], "fixture": {...}}`.
// The resource names a file cards.json pins (or one in island-cases/cards/ under /__island-test/); the fixture is a
// corpus fixture (README.md, "Fixtures"). An optional `"storage": {key: value}` is HA's localStorage on the panel: the
// island takes it in before its card, and the probe reports what the island's localStorage holds after it ran.
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { islandPagePath, probeIsland } from './lib/island.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const argv = process.argv.slice(2);
const opt = (name) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined; };
const caseFile = argv[0];
if (!caseFile) {
  console.error("usage: island-probe.mjs <case.json> [--frame tablet] [--click x,y] [--shot out.png] [--eval '<fn>'] [--snapshot-delay ms]");
  process.exit(2);
}
if (!existsSync(islandPagePath(ROOT, caseFile))) {
  console.error(`no island page at ${islandPagePath(ROOT, caseFile)}: write it first with\n  ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=${resolve(caseFile)}`);
  process.exit(2);
}
const click = opt('--click');
const r = await probeIsland(ROOT, caseFile, { frame: opt('--frame'), click: click && click.split(',').map(Number), shot: opt('--shot'), evaluate: opt('--eval'), snapshotDelayMs: Number(opt('--snapshot-delay') || 0) });
if (r.storage) console.log(`localStorage: ${JSON.stringify(r.storage)}`);
console.log(`island ${r.card}: ${JSON.stringify(r.before)}`);
console.log(`bridge: ${JSON.stringify(r.bridge)}`);
if (opt('--eval')) console.log(`eval: ${JSON.stringify(r.evaluated)}`);
if (r.tap) console.log(`tap at ${click} sent: ${JSON.stringify(r.tap)}`);
if (r.errors.length) console.log(`page errors: ${r.errors.join(' | ')}`);
process.exit(0);
