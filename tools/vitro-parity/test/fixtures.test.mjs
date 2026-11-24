import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { mkdirSync, mkdtempSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { FIXTURE_PREFIX, fixtureFile, fixtureResources } from '../lib/fixtures.mjs';

test('a case lists type: css fixtures only', () => {
  assert.deepEqual(fixtureResources({ id: 'a' }), []);
  assert.deepEqual(fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}fonts/x.css` }] }),
    [{ type: 'css', url: `${FIXTURE_PREFIX}fonts/x.css` }]);
  // Never a module (it would run in HA's page) and never a path HA serves for real.
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'module', url: `${FIXTURE_PREFIX}x.js` }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: '/local/theme.css' }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: 'x' }));
  // A path that normalizes out of the fixtures would reach HA itself.
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}../theme.css` }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}fonts/./x.css` }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}%2e%2e/theme.css` }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: FIXTURE_PREFIX }] }));
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}fonts//brand.css` }] }));
  // Encoded names are refused on both sides (ParityModel reads the path as it stands).
  assert.throws(() => fixtureResources({ id: 'a', resources: [{ type: 'css', url: `${FIXTURE_PREFIX}fonts/My%20Font.css` }] }));
});

test('a fixture request stays inside fixtures/ and serves fonts and sheets', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixtures-'));
  mkdirSync(join(dir, 'fonts'));
  for (const f of ['a.woff', 'a.css', 'OFL.txt']) writeFileSync(join(dir, 'fonts', f), 'x');
  const outside = mkdtempSync(join(tmpdir(), 'outside-'));
  writeFileSync(join(outside, 'secret.css'), 'x');
  symlinkSync(join(outside, 'secret.css'), join(dir, 'fonts', 'link.css'));
  // A link that leads out of fixtures/ is never served.
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/link.css`), null);
  // A file that isn't there is no fixture.
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/missing.css`), null);
  assert.deepEqual(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/a.woff`), { file: realpathSync(join(dir, 'fonts/a.woff')), type: 'font/woff' });
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/a.css`).type, 'text/css; charset=utf-8');
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}../refs/s1/x.json`), null);
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/%2e%2e/%2e%2e/x.css`), null);
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/OFL.txt`), null);
  assert.equal(fixtureFile(dir, '/local/other.css'), null);
  assert.equal(fixtureFile(dir, FIXTURE_PREFIX), null);
  assert.equal(fixtureFile(dir, `${FIXTURE_PREFIX}fonts/%.css`), null);
});
