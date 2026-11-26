// The island end to end (IslandEndToEndTest): real island pages, served by Vitro's own island server and bridge (in
// the JVM test, over loopback), drawn by headless Chromium. For each case it waits until the card shows what its
// states say, then reports what the card drew: its text (shadow DOM included), its height, the elements it drew with,
// and the page's errors. The JVM side judges.
//
//   node island-e2e.mjs <job.json>      job: {"cases": [{"name", "url", "tag", "expect": ["text", ...]}]}
//
// Prints one JSON line per case on stdout. Touches: a throwaway headless Chromium (Playwright's, from
// tools/vitro-parity/node_modules); it loads only the loopback addresses the job names.
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', '..');
const { launch } = await import(pathToFileURL(join(ROOT, 'tools', 'vitro-parity', 'lib', 'browser.mjs')).href);
const job = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const WAIT_MS = Number(process.env.ISLAND_E2E_WAIT_MS || 20000);

// What a card shows: the text of every node, open shadow roots included, and whether anything in it has a box.
function drawn(tag) {
  const mount = document.getElementById('mount');
  const card = mount && [...mount.children].find((e) => e.localName === tag);
  if (!card) return { card: false, notice: mount ? mount.textContent.trim() : '' };
  const texts = [];
  const tags = new Set();
  let height = card.getBoundingClientRect().height;
  const walk = (root) => {
    for (const n of root.childNodes) {
      if (n.nodeType === 3) { const t = n.textContent.trim(); if (t) texts.push(t); continue; }
      if (n.nodeType !== 1 || n.localName === 'style' || n.localName === 'script') continue;
      tags.add(n.localName);
      const r = n.getBoundingClientRect();
      if (r.height > height) height = r.height;
      if (n.shadowRoot) walk(n.shadowRoot);
      walk(n);
    }
  };
  if (card.shadowRoot) walk(card.shadowRoot);
  walk(card);
  return { card: true, text: texts.join(' | '), height: Math.round(height), tags: [...tags].sort() };
}

const browser = await launch();
try {
  for (const c of job.cases) {
    const ctx = await browser.newContext({ viewport: { width: 480, height: 800 }, colorScheme: 'dark' });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));
    let result;
    try {
      await page.goto(c.url, { waitUntil: 'load' });
      // Until every expected text is there (a card draws, then redraws with its states), or the wait ends.
      await page.waitForFunction(([tag, expect]) => {
        const mount = document.getElementById('mount');
        const card = mount && [...mount.children].find((e) => e.localName === tag);
        if (!card) return false;
        const all = [];
        const walk = (root) => { for (const n of root.childNodes) { if (n.nodeType === 3) all.push(n.textContent); else if (n.nodeType === 1) { if (n.shadowRoot) walk(n.shadowRoot); walk(n); } } };
        if (card.shadowRoot) walk(card.shadowRoot);
        walk(card);
        const text = all.join(' ');
        return expect.every((e) => text.includes(e));
      }, [c.tag, c.expect], { timeout: WAIT_MS, polling: 250 }).catch(() => {});
      // A moment more for a redraw that comes after the text (a graph's path).
      await page.waitForTimeout(500);
      result = await page.evaluate(drawn, c.tag);
    } catch (e) {
      result = { card: false, failure: String(e.message).slice(0, 300) };
    }
    console.log(JSON.stringify({ name: c.name, ...result, errors }));
    await ctx.close();
  }
} finally {
  await browser.close();
}
