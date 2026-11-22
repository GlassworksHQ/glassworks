#!/usr/bin/env node
// The npm packages as someone else gets them (docs/releasing.md "npm packages"): packs every publishable
// package (npm-package.mjs), installs the tarballs into a fresh project, and checks that
//   - every entry point type-checks from a consumer, under moduleResolution nodenext and bundler, with
//     skipLibCheck off (our .d.ts are checked too) and `types: []` (the packages bring the types they need)
//   - every entry point imports in Node (ESM that resolves, nothing that needs a browser at load)
//
//   node tools/release/npm-consumer-check.mjs [<name>…]   # default: every publishable package
//
// Writes only a temporary directory (deleted after) and the packages' dist/ (git-ignored). Needs the network:
// npm installs the packages' own dependencies, and @yume-chan/adb for improv-serial's optional peer.
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pack, publishable } from './npm-package.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const names = process.argv.slice(2).length ? process.argv.slice(2) : publishable().map((p) => p.name);
const work = mkdtempSync(join(tmpdir(), 'npm-consumer-'));
const run = (cmd, args, cwd) => execFileSync(cmd, args, { cwd, stdio: 'inherit' });

try {
  const tgz = join(work, 'tgz');
  mkdirSync(tgz);
  const packed = names.map((n) => ({ name: n, file: pack(n, tgz) }));

  const app = join(work, 'app');
  mkdirSync(app);
  writeFileSync(join(app, 'package.json'), `${JSON.stringify({ name: 'consumer', private: true, type: 'module' }, null, 2)}\n`);
  // Optional peers aren't installed with a package; install them at the version the workspace uses.
  const peers = new Set();
  const entries = [];
  for (const { name } of packed) {
    const m = JSON.parse(readFileSync(join(tgz, name, 'package.json'), 'utf8'));
    for (const peer of Object.keys(m.peerDependencies ?? {})) peers.add(peer);
    for (const key of Object.keys(m.exports)) {
      if (key !== './package.json') entries.push(key === '.' ? m.name : `${m.name}/${key.slice(2)}`);
    }
  }
  const peerSpecs = [...peers].map((p) => `${p}@${JSON.parse(readFileSync(join(ROOT, 'node_modules', p, 'package.json'), 'utf8')).version}`);
  run('npm', ['install', '--no-audit', '--no-fund', '--ignore-scripts', ...packed.map((p) => p.file), ...peerSpecs], app);

  // Every entry point, imported whole: a missing or untyped one is a type error (noImplicitAny under strict).
  writeFileSync(
    join(app, 'consumer.ts'),
    `${entries.map((e, i) => `import * as m${i} from '${e}';`).join('\n')}\nexport const all: object[] = [${entries.map((_, i) => `m${i}`).join(', ')}];\n`,
  );
  const tsc = join(ROOT, 'node_modules/.bin/tsc');
  for (const [module, moduleResolution] of [['nodenext', 'nodenext'], ['esnext', 'bundler']]) {
    const config = {
      compilerOptions: {
        target: 'ES2022', module, moduleResolution, lib: ['ES2022', 'DOM', 'DOM.Iterable'],
        strict: true, noEmit: true, skipLibCheck: false, types: [],
      },
      files: ['consumer.ts'],
    };
    writeFileSync(join(app, `tsconfig.${moduleResolution}.json`), `${JSON.stringify(config, null, 2)}\n`);
    run(tsc, ['-p', `tsconfig.${moduleResolution}.json`], app);
    console.error(`types: ${entries.length} entry points check under moduleResolution ${moduleResolution}`);
  }

  for (const e of entries) {
    const keys = execFileSync('node', ['--input-type=module', '-e', `console.log(Object.keys(await import('${e}')).length)`], { cwd: app, encoding: 'utf8' });
    if (Number(keys) < 1) throw new Error(`${e} imports in Node but exports nothing`);
    console.error(`node: ${e} imports (${Number(keys)} exports)`);
  }
  console.error(`npm-consumer-check: ${packed.map((p) => p.name).join(', ')} look right`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
