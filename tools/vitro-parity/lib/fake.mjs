// Starts the parity harness's fake Home Assistant (tools/perf/fakeha.py with fakehome.py) on a free port of
// 127.0.0.1 for one probe: the corpus's home.json, a one-view dashboard of [cards] and one case [fixture]. For the
// island tools (island-check.mjs, island-probe.mjs); capture.mjs runs its own, for every case.
//
// Touches: a python3 child process on 127.0.0.1, stopped when the calling process exits. Reads the corpus.
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export async function startFake(root, corpus, { cards, fixture, id = 'probe' }) {
  const script = `import sys; sys.path[:0] = ${JSON.stringify([join(root, 'tools', 'perf'), join(root, 'tools', 'vitro-parity')])}
import fakeha, fakehome
srv = fakeha.serve(0, harness=False, home=fakehome)
print("serving on", srv.server_address[1], flush=True)
srv.serve_forever()`;
  const child = spawn(process.env.PYTHON || 'python3', ['-c', script], { stdio: ['ignore', 'pipe', 'pipe'] });
  process.on('exit', () => { try { child.kill(); } catch {} });
  const port = await new Promise((ok, no) => {
    let out = '';
    const t = setTimeout(() => no(new Error(`the fake HA didn't start:\n${out}`)), 300000);
    const on = (d) => { out += d; const m = out.match(/serving on (\d+)/); if (m) { clearTimeout(t); ok(+m[1]); } };
    child.stdout.on('data', on);
    child.stderr.on('data', on);
    child.on('exit', (code) => { clearTimeout(t); no(new Error(`the fake HA exited (${code}):\n${out}`)); });
  });
  const origin = `http://127.0.0.1:${port}`;
  const post = async (p, b) => {
    const r = await fetch(origin + p, { method: 'POST', body: JSON.stringify(b), headers: { 'Content-Type': 'application/json' } });
    if (!r.ok) throw new Error(`fake HA ${p}: HTTP ${r.status}`);
    return r.json();
  };
  const home = JSON.parse(readFileSync(join(corpus, 'home.json'), 'utf8'));
  const res = await post('/vitro-parity/home', { ...home, fixtures_dir: join(corpus, 'fixtures'), dashboard: { title: id, views: [{ title: id, path: id, type: 'masonry', cards }] } });
  await post('/vitro-parity/case', { id, fixture: fixture || {} });
  return { origin, home, now: Math.round(res.now * 1000), path: `/vitro-harness/${id}` };
}
