#!/usr/bin/env node
// Builds one npm package for the registry (docs/releasing.md "npm packages"), without publishing it:
//
//   node tools/release/npm-package.mjs <name> [--out <dir>]   # e.g. fel: prints the .tgz it packed
//   node tools/release/npm-package.mjs --list                  # the publishable packages' names
//
// A package is publishable when its folder (kiln/packages/<name> or packages/<name>) has a CHANGELOG.md and a
// tsconfig.build.json. In the workspace it stays `"private": true` and its `exports` point at src/*.ts, so Kiln
// and the site build from source. This writes the package as npm gets it, in <out>/<name>/ (default: a temp
// dir), and packs it there:
//   - dist/: src/ compiled by its tsconfig.build.json (Node ESM, .d.ts, source maps), plus src/ for the maps.
//     src/ names .ts files in relative imports; the build and this script make them .js
//   - package.json: `private` and `scripts` dropped; every export of ./src/X.ts becomes ./dist/X.js with its
//     types; exports of ./test/* (fakes for our own tests) are left out; a workspace @glassworks dependency
//     gets ^ its version, and must itself be publishable; repository, homepage and bugs point at this repo;
//     `sideEffects` kept, so a bundler may drop the modules a consumer doesn't import
//   - README.md with its relative links made absolute, at the release's tag (npm/<name>/v<version>);
//     LICENSE (the repo's MIT, and under it the package's own LICENSE if it's a fork); CHANGELOG.md
// Writes only <out> and the package's dist/ (git-ignored). Reads nothing from the network.
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, posix, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const GITHUB = 'https://github.com/GlassworksHQ/glassworks';
const HOMES = ['kiln/packages', 'packages'];

const fail = (msg) => {
  console.error(`npm-package: ${msg}`);
  process.exit(1);
};
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

/** Every publishable package: { name (its folder), dir (repo-relative), pkg (its package.json) }. */
export function publishable() {
  const found = [];
  for (const home of HOMES) {
    for (const name of readdirSync(join(ROOT, home)).sort()) {
      const dir = `${home}/${name}`;
      if (['CHANGELOG.md', 'tsconfig.build.json', 'package.json'].every((f) => existsSync(join(ROOT, dir, f)))) {
        found.push({ name, dir, pkg: readJson(join(ROOT, dir, 'package.json')) });
      }
    }
  }
  return found;
}

/** ./src/a/b.ts → ./dist/a/b.js and .d.ts; ./test/… → null (left out); anything else is an error. */
function distExport(key, target, name) {
  if (typeof target !== 'string') fail(`${name}: export ${key} must be a path string, not ${JSON.stringify(target)}`);
  if (target.startsWith('./test/')) return null;
  const m = /^\.\/src\/(.+)\.ts$/.exec(target);
  if (!m) fail(`${name}: export ${key} → ${target} isn't ./src/*.ts or ./test/*`);
  return { types: `./dist/${m[1]}.d.ts`, default: `./dist/${m[1]}.js` };
}

/** Relative Markdown and HTML links → absolute GitHub URLs at `ref`; images to the raw file. */
export function absoluteLinks(markdown, dir, ref) {
  const url = (rel, raw) => {
    if (/^([a-z][a-z0-9+.-]*:|#|\/)/i.test(rel)) return rel;
    const [path, hash = ''] = rel.split(/(?=#)/);
    const to = posix.normalize(posix.join(dir, path));
    if (to.startsWith('..')) fail(`${dir}/README.md links outside the repo: ${rel}`);
    return raw ? `https://raw.githubusercontent.com/GlassworksHQ/glassworks/${ref}/${to}${hash}` : `${GITHUB}/blob/${ref}/${to}${hash}`;
  };
  return markdown
    .replace(/(!?)\[([^\]]*)\]\(([^)\s]+)\)/g, (_, bang, text, rel) => `${bang}[${text}](${url(rel, bang === '!')})`)
    .replace(/\b(href|src|srcset)="([^"]+)"/g, (_, attr, rel) => `${attr}="${url(rel, attr !== 'href')}"`);
}

/** Relative './x.ts' specifiers in a .d.ts (from, import(), export … from) → './x.js'. */
export const dtsSpecifiers = (dts) =>
  dts.replace(/(\bfrom\s*|\bimport\s*\(\s*)(['"])(\.\.?\/[^'"]+)\.ts\2/g, (_, pre, q, path) => `${pre}${q}${path}.js${q}`);

/** The package.json npm gets. */
export function manifest({ name, dir, pkg }, all) {
  const exports = {};
  for (const [key, target] of Object.entries(pkg.exports ?? fail(`${name}: no exports`))) {
    const to = distExport(key, target, name);
    if (to) exports[key] = to;
  }
  exports['./package.json'] = './package.json';
  const deps = { ...(pkg.dependencies ?? {}) };
  for (const dep of Object.keys(deps)) {
    const local = all.find((p) => p.pkg.name === dep);
    if (local) {
      if (deps[dep] !== local.pkg.version) {
        fail(`${name}: depends on ${dep} ${deps[dep]}, but its version is ${local.pkg.version}`);
      }
      deps[dep] = `^${local.pkg.version}`;
    } else if (dep.startsWith('@glassworks/')) {
      fail(`${name}: depends on ${dep}, which isn't publishable (no CHANGELOG.md and tsconfig.build.json)`);
    }
  }
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(pkg.version ?? '') || pkg.version === '0.0.0') {
    fail(`${name}: version ${pkg.version} isn't a release version (MAJOR.MINOR.PATCH, not 0.0.0)`);
  }
  if (!pkg.name.startsWith('@glassworks/')) fail(`${name}: ${pkg.name} isn't in the @glassworks scope`);
  const out = {
    name: pkg.name,
    version: pkg.version,
    description: pkg.description,
    keywords: pkg.keywords,
    homepage: `${GITHUB}/tree/main/${dir}#readme`,
    bugs: `${GITHUB}/issues`,
    repository: { type: 'git', url: `git+${GITHUB}.git`, directory: dir },
    license: pkg.license,
    type: pkg.type,
    sideEffects: pkg.sideEffects,
    exports,
    files: ['dist', 'src', 'CHANGELOG.md'],
    dependencies: Object.keys(deps).length ? deps : undefined,
    peerDependencies: pkg.peerDependencies,
    peerDependenciesMeta: pkg.peerDependenciesMeta,
    publishConfig: { access: 'public' },
  };
  if (out.type !== 'module') fail(`${name}: "type" must be "module"`);
  if (out.license !== 'MIT') fail(`${name}: licence ${out.license}; a published package is MIT (CLAUDE.md, hard rule 4)`);
  return JSON.parse(JSON.stringify(out)); // drops the undefined fields
}

/** Builds and packs one package; returns the .tgz path. */
export function pack(name, outRoot) {
  const all = publishable();
  const p = all.find((x) => x.name === name) ?? fail(`${name} isn't publishable: ${all.map((x) => x.name).join(', ')}`);
  const src = join(ROOT, p.dir);
  const m = manifest(p, all);

  rmSync(join(src, 'dist'), { recursive: true, force: true });
  execFileSync(join(ROOT, 'node_modules/.bin/tsc'), ['-p', join(src, 'tsconfig.build.json')], { stdio: 'inherit' });
  // rewriteRelativeImportExtensions rewrites the .js only. A .d.ts keeps './x.ts', which TypeScript before 5.0
  // can't read: give it the './x.js' every TypeScript resolves to x.d.ts.
  for (const f of readdirSync(join(src, 'dist'), { recursive: true })) {
    if (!f.endsWith('.d.ts')) continue;
    const file = join(src, 'dist', f);
    writeFileSync(file, dtsSpecifiers(readFileSync(file, 'utf8')));
  }
  for (const [key, to] of Object.entries(m.exports)) {
    for (const file of typeof to === 'string' ? [to] : Object.values(to)) {
      if (!existsSync(join(src, file))) fail(`${name}: export ${key} → ${file}, which the build didn't write`);
    }
  }

  const stage = join(outRoot, name);
  rmSync(stage, { recursive: true, force: true });
  mkdirSync(stage, { recursive: true });
  cpSync(join(src, 'dist'), join(stage, 'dist'), { recursive: true });
  cpSync(join(src, 'src'), join(stage, 'src'), { recursive: true });
  cpSync(join(src, 'CHANGELOG.md'), join(stage, 'CHANGELOG.md'));
  // A fork keeps upstream's licence in its own LICENSE: ship ours, then upstream's under it.
  let license = readFileSync(join(ROOT, 'LICENSE'), 'utf8');
  if (existsSync(join(src, 'LICENSE'))) {
    license += `\n---\n\nParts of this package come from upstream (see README.md), under this licence:\n\n${readFileSync(join(src, 'LICENSE'), 'utf8')}`;
  }
  writeFileSync(join(stage, 'LICENSE'), license);
  if (!existsSync(join(src, 'README.md'))) fail(`${name}: no README.md`);
  const ref = `npm/${name}/v${m.version}`;
  writeFileSync(join(stage, 'README.md'), absoluteLinks(readFileSync(join(src, 'README.md'), 'utf8'), p.dir, ref));
  writeFileSync(join(stage, 'package.json'), `${JSON.stringify(m, null, 2)}\n`);

  const [packed] = JSON.parse(
    execFileSync('npm', ['pack', '--ignore-scripts', '--json', '--pack-destination', outRoot], { cwd: stage, encoding: 'utf8' }),
  );
  const files = packed.files.map((f) => f.path);
  const stray = files.filter((f) => !/^(dist\/|src\/|package\.json$|README\.md$|LICENSE$|CHANGELOG\.md$)/.test(f));
  if (stray.length) fail(`${name}: unexpected files in the package: ${stray.join(', ')}`);
  console.error(`${m.name}@${m.version}: ${files.length} files, ${packed.size} bytes (${join(outRoot, packed.filename)})`);
  return join(outRoot, packed.filename);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args[0] === '--list') {
    for (const p of publishable()) console.log(p.name);
  } else if (args[0] && !args[0].startsWith('-')) {
    const i = args.indexOf('--out');
    const out = i >= 0 ? resolve(args[i + 1] ?? fail('--out needs a directory')) : mkdtempSync(join(tmpdir(), 'npm-package-'));
    mkdirSync(out, { recursive: true });
    console.log(pack(args[0], out));
  } else {
    fail('usage: npm-package.mjs <name> [--out <dir>] | --list');
  }
}
