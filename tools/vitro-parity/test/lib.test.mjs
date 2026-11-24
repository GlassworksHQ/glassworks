// Tests for the capture tool's pure helpers: node --test tools/vitro-parity/test (touches nothing).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { parseArgs, pick } from '../lib/args.mjs';
import { assetDestination } from '../lib/assets.mjs';
import { scrubStates, scrubText } from '../lib/scrub.mjs';

test('parseArgs takes known options with values', () => {
  assert.deepEqual(parseArgs(['capture', '--frames', 's1,tablet', '--root', '/tmp/x']), { cmd: 'capture', frames: 's1,tablet', root: '/tmp/x' });
});

test('parseArgs rejects unknown options, missing operands, repeats and strays', () => {
  assert.throws(() => parseArgs(['capture', '--frame', 's1']), /unknown option --frame/);
  assert.throws(() => parseArgs(['capture', '--root']), /needs a value/);
  assert.throws(() => parseArgs(['capture', '--frames', '--cases', 'a']), /needs a value/);
  assert.throws(() => parseArgs(['capture', '--templates', '']), /needs a value/);
  assert.throws(() => parseArgs(['capture', '--cases', 'a', '--cases', 'b']), /twice/);
  assert.throws(() => parseArgs(['capture', 'stray']), /unexpected argument/);
  assert.throws(() => parseArgs(['--root', 'x']), /missing the command/);
});

test('pick keeps all, or only known ids, and rejects unknown ones', () => {
  assert.deepEqual([...pick(undefined, ['s1', 'tablet'], 'frame')], ['s1', 'tablet']);
  assert.deepEqual([...pick('tablet', ['s1', 'tablet'], 'frame')], ['tablet']);
  assert.throws(() => pick('s2', ['s1', 'tablet'], 'frame'), /no frame s2/);
  assert.throws(() => pick(',', ['s1'], 'case'), /empty list/);
});

test('assetDestination keeps assets inside assets/', () => {
  const dir = '/tmp/corpus/assets';
  assert.equal(assetDestination(dir, '/static/icons/favicon-192x192.png', true), join(dir, 'static/icons/favicon-192x192.png'));
  assert.equal(assetDestination(dir, '/api/image_proxy/image.plant', false), join(dir, 'api/image_proxy/image.plant'));
  for (const bad of ['/static/../../../home/user/.bashrc', '/static/%2e%2e/api/states', '/static/./x.png', 'static/x.png', '//evil/x', '/static\\..\\x', '/static//x.png']) {
    assert.throws(() => assetDestination(dir, bad, true), /asset/, bad);
  }
  assert.throws(() => assetDestination(dir, '/api/states', true), /only show HA's own \/static/);
});

test('scrub drops private attributes and media lists, keeps pictures only when asked', () => {
  const states = { 'media_player.x': { s: 'idle', a: { friendly_name: 'Speaker', source_list: ['My playlist'], ip_address: '10.1.2.3', entity_picture: '/api/x?token=abc' } } };
  assert.deepEqual(scrubStates(states, null)['media_player.x'].a, { friendly_name: 'Speaker' });
  assert.equal(scrubStates(states, null, { keepPictures: true })['media_player.x'].a.entity_picture, '/api/x?token=redacted');
  assert.equal(scrubText('host 10.1.2.3 and 192.0.2.7', null), 'host 192.0.2.1 and 192.0.2.7');
});

test('assetDestination checks the path fetch would request (CWE-180)', async () => {
  const { canonicalPath } = await import('../lib/assets.mjs');
  const dir = '/tmp/corpus/assets';
  for (const bad of ['/static/.\t./api/states', '/static/.\n./api/states', '/static/x\r.png', '/static/\u0000x.png', '/static/a b.png']) {
    assert.throws(() => assetDestination(dir, bad, true), /asset/, JSON.stringify(bad));
  }
  assert.equal(canonicalPath('/static/.\t./api/states'), '/api/states');
  assert.equal(canonicalPath('/api/image_proxy/image.plant?token=x'), '/api/image_proxy/image.plant');
  assert.equal(assetDestination(dir, '/static/icons/favicon-192x192.png', true), join(dir, 'static/icons/favicon-192x192.png'));
});

test('findPrivate flags what a screenshot would show, never the values', async () => {
  const { findPrivate } = await import('../lib/scrub.mjs');
  assert.deepEqual(findPrivate('20.7° 48% v1.2.3 192.0.2.4', null), []);
  assert.deepEqual(findPrivate('Router 10.0.0.1', null), ['an IPv4 address']);
  assert.deepEqual(findPrivate('aa:bb:cc:dd:ee:ff', null), ['a MAC address']);
  assert.deepEqual(findPrivate('/api/x?token=abc123', null), ['a token']);
  const creds = { origin: 'http://ha.example.test:8123', token: 'secret-token' };
  assert.deepEqual(findPrivate('open ha.example.test:8123 now', creds), ["Home Assistant's address or token"]);
  const found = findPrivate('Router 10.0.0.1', null).join(' ');
  assert.ok(!found.includes('10.0.0.1'));
});

test('loadCredentials warns, without the origin, when HA is not https', async () => {
  const { mkdtempSync, writeFileSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { spawnSync } = await import('node:child_process');
  const dir = mkdtempSync(join(tmpdir(), 'vp-'));
  const run = (origin) => {
    writeFileSync(join(dir, 'ha.json'), JSON.stringify({ origin, token: 't' }));
    return spawnSync(process.execPath, ['-e', "import('./lib/ha.mjs').then((m) => m.loadCredentials())"], {
      cwd: new URL('..', import.meta.url).pathname, env: { ...process.env, VITRO_PARITY_HA: join(dir, 'ha.json') }, encoding: 'utf8',
    });
  };
  const plain = run('http://ha.example.test:8123');
  assert.equal(plain.status, 0);
  assert.match(plain.stderr, /not https/);
  assert.ok(!plain.stderr.includes('ha.example.test'));
  assert.equal(run('https://ha.example.test').stderr, '');
});

test('run.sh rejects an empty or missing operand instead of selecting everything', async () => {
  const { spawnSync } = await import('node:child_process');
  const script = new URL('../run.sh', import.meta.url).pathname;
  for (const args of [['--cases', ''], ['--frames'], ['--frames', '--cases', 'x'], ['--out', '']]) {
    const r = spawnSync('bash', [script, ...args], { encoding: 'utf8' });
    assert.equal(r.status, 2, JSON.stringify(args));
    assert.match(r.stderr, /needs a value/);
  }
});
