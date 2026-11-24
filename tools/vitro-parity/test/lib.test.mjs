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
