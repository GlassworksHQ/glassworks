// Tests for the capture tool's pure helpers: node --test tools/vitro-parity/test (touches nothing).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs, pick } from '../lib/args.mjs';
import { assetDestination } from '../lib/assets.mjs';
import { denyPattern, scrubStates, scrubText } from '../lib/scrub.mjs';

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

test('scrub drops API keys at every depth', () => {
  const states = { 'sensor.x': { s: '1', a: { api_key: 'k1', nested: { api_key: 'k2', list: [{ api_key: 'k3', keep: 1 }] } } } };
  assert.deepEqual(scrubStates(states, null)['sensor.x'].a, { nested: { list: [{ keep: 1 }] } });
});

test('scrub replaces IPv6 literals, but not times, CSS or the documentation prefix', () => {
  assert.equal(scrubText('gw fe80::1c2b:3cff:fe4d:5e6f and 2001:db8::7', null), 'gw 2001:db8::1 and 2001:db8::7');
  assert.equal(scrubText('2a02:1810:4d02:aa00:1111:2222:3333:4444', null), '2001:db8::1');
  for (const keep of ['10:08:00', 'a::before', 'std::map', 'aa:bb']) assert.equal(scrubText(keep, null), keep);
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
  assert.deepEqual(findPrivate('Router fd00::1', null), ['an IPv6 address']);
  assert.deepEqual(findPrivate('Docs 2001:db8::1 at 10:08:00', null), []);
  assert.deepEqual(findPrivate('/api/x?token=secretvalue', null), ['a token']);
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
      cwd: fileURLToPath(new URL('..', import.meta.url)), env: { ...process.env, VITRO_PARITY_HA: join(dir, 'ha.json') }, encoding: 'utf8',
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
  const script = fileURLToPath(new URL('../run.sh', import.meta.url));
  for (const args of [['--cases', ''], ['--frames'], ['--frames', '--cases', 'x'], ['--out', '']]) {
    const r = spawnSync('bash', [script, ...args], { encoding: 'utf8' });
    assert.equal(r.status, 2, JSON.stringify(args));
    assert.match(r.stderr, /needs a value/);
  }
});

test('a vitro-harness dashboard the tool did not make is never overwritten', async () => {
  const { notOurs, HARNESS_META, MARKER_KEY, MARKER_VALUE } = await import('../lib/harness.mjs');
  const entry = { ...HARNESS_META, id: 'x' };
  assert.equal(notOurs(entry, null, ['clock']), null, 'just created, no config yet');
  assert.equal(notOurs(entry, { [MARKER_KEY]: MARKER_VALUE, views: [{ path: 'anything' }] }, ['clock']), null, 'marked');
  assert.equal(notOurs(entry, { title: 'Vitro harness', views: [{ path: 'clock' }] }, ['clock']), null, 'an earlier version of the tool');
  assert.match(notOurs(entry, { title: 'My stuff', views: [{ path: 'home' }] }, ['clock']), /isn't one this tool saved/);
  assert.match(notOurs(entry, { title: 'Vitro harness', views: [{ path: 'home' }] }, ['clock']), /isn't one this tool saved/);
  assert.match(notOurs({ ...entry, require_admin: false }, null, []), /hidden, admin-only/);
  assert.match(notOurs({ ...entry, show_in_sidebar: true }, null, []), /hidden, admin-only/);
  assert.match(notOurs({ ...entry, mode: 'yaml' }, null, []), /hidden, admin-only/);
  assert.match(notOurs({ ...entry, title: 'Harness' }, null, []), /hidden, admin-only/);
});

test('a fresh subscription replaces the recorded states; later events merge', async () => {
  const { applyEntityEvent } = await import('../lib/states.mjs');
  const states = {};
  applyEntityEvent(states, { a: { 'light.a': { s: 'on', a: { b: 1 } }, 'light.gone': { s: 'off', a: {} } } }, true);
  applyEntityEvent(states, { c: { 'light.a': { '+': { s: 'off', lc: 2, a: { c: 2 } }, '-': { a: ['b'] } } } }, false);
  assert.deepEqual(states['light.a'], { s: 'off', a: { c: 2 }, lc: 2 });
  // The next page load's snapshot no longer has light.gone.
  applyEntityEvent(states, { a: { 'light.a': { s: 'on', a: {} } } }, true);
  assert.deepEqual(Object.keys(states), ['light.a']);
  applyEntityEvent(states, { r: ['light.a'] }, false);
  assert.deepEqual(states, {});
});

test('the HA origin is canonical, so the browser origin check matches it', async () => {
  const { mkdtempSync, writeFileSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { spawnSync } = await import('node:child_process');
  const dir = mkdtempSync(join(tmpdir(), 'vp-'));
  const load = (origin) => {
    writeFileSync(join(dir, 'ha.json'), JSON.stringify({ origin, token: 't' }));
    return spawnSync(process.execPath, ['-e', "import('./lib/ha.mjs').then((m) => { try { process.stdout.write(m.loadCredentials().origin) } catch (e) { process.stdout.write('ERR ' + e.message) } })"], {
      cwd: fileURLToPath(new URL('..', import.meta.url)), env: { ...process.env, VITRO_PARITY_HA: join(dir, 'ha.json') }, encoding: 'utf8',
    }).stdout;
  };
  assert.equal(load('https://homeassistant.local:443/'), 'https://homeassistant.local');
  assert.equal(load('http://homeassistant.local:8123/lovelace'), 'http://homeassistant.local:8123');
  assert.match(load('ftp://homeassistant.local'), /^ERR .*http or https/);
  assert.match(load('not a url'), /^ERR .*isn't a URL/);
});

test('scrub drops private keys at every depth', async () => {
  const states = { 'sensor.x': { s: '1', a: { friendly_name: 'X', info: { model: 'M', serial_number: 'SN123', nested: [{ mac: 'm', name: 'n' }] } } } };
  assert.deepEqual(scrubStates(states, null)['sensor.x'].a, { friendly_name: 'X', info: { model: 'M', nested: [{ name: 'n' }] } });
});

test('run.sh rejects an explicitly empty --local path', async () => {
  const { spawnSync } = await import('node:child_process');
  const r = spawnSync('bash', [fileURLToPath(new URL('../run.sh', import.meta.url)), '--refresh', '--local', ''], { encoding: 'utf8' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /--local needs a path/);
});

test('buildDisplay keeps only what Vitro reads, with synthetic device ids', async () => {
  const { buildDisplay } = await import('../lib/display.mjs');
  const realDevice = '53d0477a41d33ff0081a623f2a4e192d';
  const registry = { entities: [
    { ei: 'sensor.fridge', pl: 'localthings', tk: 'instance_temperature', dp: 1, di: realDevice, en: 'Fridge temperature', lb: ['x'], ai: 'kitchen', np: 'device' },
    { ei: 'sensor.other', pl: 'esphome', dp: 2 },
    { ei: 'light.lamp', pl: 'zha', di: realDevice },
  ] };
  const devices = [{ id: realDevice, name: 'AirDot 4c83f8', name_by_user: 'Fridge', serial_number: 'SN1', connections: [['mac', 'aa:bb:cc:dd:ee:ff']] }];
  const icons = (category, n) => ({
    entity_component: { sensor: { _: { default: 'mdi:eye' }, temperature: { default: 'mdi:thermometer' }, humidity: { default: 'mdi:water' } }, light: { _: { default: 'mdi:lightbulb' } } },
    entity: { localthings: { sensor: { instance_temperature: { default: 'mdi:fridge' }, other_key: { default: 'mdi:x' } } } },
  })[category][n];
  const translations = (category, n) => ({
    'component.sensor.entity_component._.state.on': 'On',
    'component.sensor.entity_component._.state_attributes.mode.state.eco': 'Eco',
    'component.sensor.entity_component.humidity.state.x': 'X',
    'component.sensor.entity_component._.name': 'Sensor',
    'component.localthings.entity.sensor.instance_temperature.state.cold': 'Cold',
    'component.localthings.entity.sensor.instance_temperature.state_attributes.mode.state.deep': 'Deep',
    'component.localthings.entity.sensor.instance_temperature.name': 'Instance',
    'component.localthings.entity.sensor.other_key.state.y': 'Y',
  });
  const d = buildDisplay(new Set(['sensor.fridge', 'light.lamp']), registry, devices, icons, translations, { 'sensor.fridge': 'temperature' });
  assert.deepEqual(d.entities, [
    { ei: 'light.lamp', pl: 'zha', di: 'device-1' },
    // `ai`: an alert card names its entity's area.
    { ei: 'sensor.fridge', pl: 'localthings', tk: 'instance_temperature', dp: 1, di: 'device-1', en: 'Fridge temperature', ai: 'kitchen', np: 'device' },
  ]);
  // The name HA shows only: the default one ("AirDot 4c83f8", a MAC fragment) stays out.
  assert.deepEqual(d.devices, [{ id: 'device-1', name: 'Fridge' }]);
  assert.deepEqual(d.requested, { domains: ['light', 'sensor'], platforms: ['localthings'] });
  assert.deepEqual(d.icons.entity_component.sensor, { _: { default: 'mdi:eye' }, temperature: { default: 'mdi:thermometer' } });
  assert.deepEqual(d.icons.entity, { localthings: { sensor: { instance_temperature: { default: 'mdi:fridge' } } } });
  // States' labels and their attributes' (a tile's state_content), nothing else.
  assert.deepEqual(Object.keys(d.translations.entity_component), [
    'component.sensor.entity_component._.state.on', 'component.sensor.entity_component._.state_attributes.mode.state.eco',
  ]);
  assert.deepEqual(Object.keys(d.translations.entity), [
    'component.localthings.entity.sensor.instance_temperature.state.cold',
    'component.localthings.entity.sensor.instance_temperature.state_attributes.mode.state.deep',
  ]);
  const text = JSON.stringify(d);
  assert.ok(!text.includes(realDevice) && !text.includes('SN1') && !text.includes('aa:bb') && !text.includes('4c83f8'));
});

test('buildDisplay keeps the areas the case names, and referencedAreas finds them in nested cards', async () => {
  const { buildDisplay, referencedAreas } = await import('../lib/display.mjs');
  const config = { type: 'vertical-stack', cards: [{ type: 'area', area: 'kitchen' }, { type: 'tile', entity: 'light.x', area: 'not_an_area_card' }] };
  assert.deepEqual(referencedAreas(config), ['kitchen']);
  const areas = [
    { area_id: 'kitchen', name: 'Kitchen', icon: 'mdi:stove', picture: null, floor_id: 'ground', aliases: ['x'], temperature_entity_id: 'sensor.kitchen_t' },
    { area_id: 'office', name: 'Office', icon: null },
  ];
  const d = buildDisplay(new Set(), { entities: [] }, [], () => undefined, () => undefined, {}, areas, referencedAreas(config));
  assert.deepEqual(d.areas, [{ area_id: 'kitchen', name: 'Kitchen', icon: 'mdi:stove', temperature_entity_id: 'sensor.kitchen_t' }]);
  // No area card, no `areas` key: every other reference keeps its shape.
  assert.ok(!('areas' in buildDisplay(new Set(), { entities: [] }, [], () => undefined, () => undefined, {}, areas, [])));
});

test('the to-do, logbook and forecast subscriptions are recorded and waited for, the logbook scrubbed', async () => {
  const { wsRecorder, pendingFirstEvents } = await import('../lib/wsdata.mjs');
  const { scrubGraphCalls } = await import('../lib/scrub.mjs');
  const rec = wsRecorder();
  const sock = rec.socket();
  sock.sent({ id: 1, type: 'todo/item/subscribe', entity_id: 'todo.x' });
  sock.sent({ id: 2, type: 'logbook/event_stream', start_time: 'a', end_time: 'b', entity_ids: ['light.x'] });
  sock.sent({ id: 3, type: 'weather/subscribe_forecast', entity_id: 'weather.x', forecast_type: 'daily' });
  sock.sent({ id: 4, type: 'config/area_registry/list' }); // not a card's data
  assert.deepEqual(pendingFirstEvents(rec.calls), ['todo/item/subscribe', 'logbook/event_stream', 'weather/subscribe_forecast']);
  sock.received({ id: 1, type: 'event', event: { items: [{ uid: '0f3c9a8e2b6d4e1f8a7c5b3d9e2f1a0b', summary: 'A' }, { uid: 'f00d', summary: 'B' }] } });
  // The logbook's history in parts: a partial one isn't enough.
  sock.received({ id: 2, type: 'event', event: { events: [{ entity_id: 'light.x', when: 1, context_user_id: 'u1', context_id: 'c1', context_domain: 'script', context_event_type: 'call_service' }], partial: true } });
  assert.deepEqual(pendingFirstEvents(rec.calls), ['logbook/event_stream', 'weather/subscribe_forecast']);
  sock.received({ id: 2, type: 'event', event: { events: [] } });
  sock.received({ id: 3, type: 'event', event: { forecast: [] } });
  assert.deepEqual(pendingFirstEvents(rec.calls), []);
  assert.equal(rec.calls.length, 3);
  const scrubbed = scrubGraphCalls(rec.calls);
  assert.deepEqual(scrubbed[0].events[0].items.map((i) => i.uid), ['item-1', 'item-2']);
  // The cause stays (what HA draws its badge from); who and which run don't.
  assert.deepEqual(scrubbed[1].events[0].events[0], { entity_id: 'light.x', when: 1, context_domain: 'script', context_event_type: 'call_service' });
});

test('findPrivate flags a MAC fragment but not a colour or a model id', async () => {
  const { findPrivate } = await import('../lib/scrub.mjs');
  assert.deepEqual(findPrivate('AirDot 4c83f8', null), ['a MAC fragment']);
  assert.deepEqual(findPrivate('esp-a1b2c3d4e5f6', null), ['a MAC fragment']);
  // `_` sets a fragment off too: it's a word character, so a \w boundary missed it.
  assert.deepEqual(findPrivate('AirDot_a1b2c3d4e5f6', null), ['a MAC fragment']);
  assert.deepEqual(findPrivate('esp_4C83F8.local', null), ['a MAC fragment']);
  const { hasMacFragment } = await import('../lib/scrub.mjs');
  assert.ok(hasMacFragment('{"friendly_name": "airdot_4bb0c0 Temperature"}'));
  assert.ok(!hasMacFragment('{"color": "#a1b2c3", "lc": 1795504930.063075}'));
  for (const ok of ['#34d399', 'TS0501B', '_TZB210_gumjktrw', 'abcdef', '123456', 'Fog 8.3 °C']) assert.deepEqual(findPrivate(ok, null), [], ok);
});

test('capturableImage keeps only images HA served from /local or /static on its own origin', async () => {
  const { capturableImage } = await import('../lib/assets.mjs');
  const o = 'http://homeassistant.local:8123';
  assert.equal(capturableImage(`${o}/static/icons/favicon-192x192.png`, o, 200, 'image/png'), '/static/icons/favicon-192x192.png');
  assert.equal(capturableImage(`${o}/local/plants/fig.jpg?v=3`, o, 200, 'image/jpeg'), '/local/plants/fig.jpg');
  assert.equal(capturableImage(`${o}/static/x.svg`, o, 200, ' Image/SVG+XML; charset=utf-8'), '/static/x.svg');
  // Another origin, a failed or non-image response, /api and anything outside /local and /static: never saved.
  assert.equal(capturableImage('https://cdn.example.test/static/x.png', o, 200, 'image/png'), null);
  assert.equal(capturableImage(`${o}/static/x.png`, o, 404, 'image/png'), null);
  assert.equal(capturableImage(`${o}/static/x.png`, o, 304, 'image/png'), null);
  assert.equal(capturableImage(`${o}/static/app.js`, o, 200, 'application/javascript'), null);
  assert.equal(capturableImage(`${o}/static/x.png`, o, 200, undefined), null);
  assert.equal(capturableImage(`${o}/api/image_proxy/image.plant`, o, 200, 'image/jpeg'), null);
  assert.equal(capturableImage(`${o}/api/camera_proxy/camera.x`, o, 200, 'image/jpeg'), null);
  assert.equal(capturableImage(`${o}//static/x.png`, o, 200, 'image/png'), null);
  assert.equal(capturableImage(`${o}/hacsfiles/x.png`, o, 200, 'image/png'), null);
  assert.equal(capturableImage('not a url', o, 200, 'image/png'), null);
});

test('aliasPaths files a redirected image under the path the page asked for too', async () => {
  const { aliasPaths } = await import('../lib/assets.mjs');
  const o = 'http://homeassistant.local:8123';
  assert.deepEqual(aliasPaths([`${o}/local/actual.png`, `${o}/local/alias.png`], o, 200, 'image/png'), ['/local/actual.png', '/local/alias.png']);
  // Every alias passes the same checks: another origin or /api in the chain isn't an asset path.
  assert.deepEqual(aliasPaths([`${o}/static/x.png`, 'https://cdn.example.test/static/x.png', `${o}/api/image_proxy/x`], o, 200, 'image/png'), ['/static/x.png']);
  assert.deepEqual(aliasPaths([`${o}/static/x.png`, `${o}/static/y.png`], o, 200, 'text/html'), []);
});

test('staleAssets lists the files nothing kept', async () => {
  const { staleAssets } = await import('../lib/assets.mjs');
  assert.deepEqual(staleAssets(['/static/a.png', '/static/b.png', '/local/c.jpg'], new Set(['/static/a.png'])), ['/local/c.jpg', '/static/b.png']);
  assert.deepEqual(staleAssets(['/static/a.png'], ['/static/a.png']), []);
});

test('imageOutcomes keeps the latest outcome per path', async () => {
  const { imageOutcomes } = await import('../lib/images.mjs');
  const t = imageOutcomes();
  // A failure a retry recovers from isn't HA-missing.
  t.failure(['/local/a.png']);
  t.success(['/local/a.png'], 'body-1');
  assert.equal(t.failed.has('/local/a.png'), false);
  assert.equal(t.loaded.get('/local/a.png'), 'body-1');
  // A later response with new bytes replaces the body.
  t.success(['/local/a.png', '/local/alias.png'], 'body-2');
  assert.equal(t.loaded.get('/local/a.png'), 'body-2');
  assert.equal(t.loaded.get('/local/alias.png'), 'body-2');
  // A later failure drops the body.
  t.failure(['/local/a.png']);
  assert.equal(t.loaded.has('/local/a.png'), false);
  assert.equal(t.failed.has('/local/a.png'), true);
});

test('noteSaved reports one path saved with two different images in a run', async () => {
  const { noteSaved } = await import('../lib/images.mjs');
  const seen = new Map();
  assert.equal(noteSaved(seen, '/static/x.png', 'aaa'), null);
  assert.equal(noteSaved(seen, '/static/x.png', 'aaa'), null);
  assert.match(noteSaved(seen, '/static/x.png', 'bbb'), /changed during the run/);
  assert.equal(noteSaved(seen, '/static/y.png', 'bbb'), null);
});

test('wsRecorder keeps the graph calls of the current case with their answers, per socket', async () => {
  const { wsRecorder, snapshotCalls } = await import('../lib/wsdata.mjs');
  const r = wsRecorder();
  const a = r.socket();
  a.sent({ id: 3, type: 'history/stream', entity_ids: ['sensor.a'], start_time: 'T' });
  a.sent({ id: 4, type: 'subscribe_entities' });
  a.sent({ id: 5, type: 'recorder/statistics_during_period', statistic_ids: ['sensor.a'] });
  a.received({ id: 3, type: 'result', success: true, result: null });
  a.received({ id: 3, type: 'event', event: { states: { 'sensor.a': [{ s: '1', lu: 1 }] } } });
  a.received({ id: 4, type: 'event', event: { a: {} } });
  a.received({ id: 5, type: 'result', success: false, error: { code: 'x' } });
  // Another socket reusing id 3 for something else never answers the first socket's request.
  const b = r.socket();
  b.received({ id: 3, type: 'result', success: true, result: { other: true } });
  assert.deepEqual(r.calls, [
    { request: { type: 'history/stream', entity_ids: ['sensor.a'], start_time: 'T' }, result: null, events: [{ states: { 'sensor.a': [{ s: '1', lu: 1 }] } }] },
    { request: { type: 'recorder/statistics_during_period', statistic_ids: ['sensor.a'] }, error: { code: 'x' }, events: [] },
  ]);
  const snap = snapshotCalls(r.calls);
  // A new case drops the old ids: a late event for an earlier card isn't this case's data.
  r.reset();
  a.received({ id: 3, type: 'event', event: { states: {} } });
  assert.deepEqual(r.calls, []);
  assert.equal(snap[0].events.length, 1);
});

test('scrubGraphCalls drops private attributes from recorded history, nothing else', async () => {
  const { scrubGraphCalls } = await import('../lib/scrub.mjs');
  const calls = [
    { request: { type: 'history/stream' }, events: [{ states: { 'climate.x': [{ s: 'heat', lu: 1, a: { temperature: 21, mac_address: 'aa', serial_number: 'b' } }] } }] },
    { request: { type: 'recorder/get_statistics_metadata' }, result: [{ statistic_id: 'sensor.a' }], events: [] },
  ];
  const out = scrubGraphCalls(calls);
  assert.deepEqual(out[0].events[0].states['climate.x'][0].a, { temperature: 21 });
  assert.deepEqual(out[1], calls[1]);
});

test('a denylisted word matches whole, at any edge and with non-ASCII letters', () => {
  assert.ok(denyPattern('José').test('José is home'));
  assert.ok(denyPattern('Élodie').test('Bonjour Élodie'));
  assert.ok(denyPattern('123 Main St.').test('123 Main St. Springfield'));
  assert.ok(denyPattern('max').test('Hi MAX!'));
  assert.ok(!denyPattern('Max').test('Maxwell'));
  assert.ok(!denyPattern('José').test('Joséka'));
});

test('unmetExpectations names the entities not in the state a case expects', async () => {
  const { unmetExpectations } = await import('../lib/states.mjs');
  const states = { 'light.a': { s: 'on', a: {} }, 'light.b': { s: 'off', a: {} } };
  assert.deepEqual(unmetExpectations(undefined, states), []);
  assert.deepEqual(unmetExpectations({ 'light.a': 'on' }, states), []);
  assert.deepEqual(unmetExpectations({ 'light.a': 'on', 'light.b': 'on', 'light.c': 'off' }, states),
    ['light.b: expected on, got off', 'light.c: expected off, got no state']);
  // A time marker is never a served state: refused, whatever the state.
  assert.deepEqual(unmetExpectations({ 'light.a': '@+3h' }, { 'light.a': { s: '@+3h', a: {} } }),
    ['light.a: expects the time marker @+3h, not a state HA serves']);
  // A literal state that starts with "@" is served as written: it matches when it's the state, and says what it got
  // when it isn't.
  assert.deepEqual(unmetExpectations({ 'select.mode': '@standby' }, { 'select.mode': { s: '@standby', a: {} } }), []);
  assert.deepEqual(unmetExpectations({ 'select.mode': '@standby' }, { 'select.mode': { s: 'eco', a: {} } }),
    ['select.mode: expected @standby, got eco']);
  for (const marker of ['@D+1', '@D-2 14:30', '@-2h', '@+1d12h', '@90']) {
    assert.deepEqual(unmetExpectations({ 'sensor.t': marker }, { 'sensor.t': { s: marker, a: {} } }),
      [`sensor.t: expects the time marker ${marker}, not a state HA serves`], marker);
  }
});

test('pendingOneShots lists the one-shot graph calls still unanswered, never a stream', async () => {
  const { pendingOneShots } = await import('../lib/wsdata.mjs');
  const calls = [
    { request: { type: 'history/stream' }, events: [] },
    { request: { type: 'recorder/get_statistics_metadata' }, events: [] },
    { request: { type: 'recorder/statistics_during_period' }, result: {}, events: [] },
    { request: { type: 'history/history_during_period' }, error: { code: 'x' }, events: [] },
  ];
  assert.deepEqual(pendingOneShots(calls), ['recorder/get_statistics_metadata']);
});

test('parseArgs takes the HA switches, never both', () => {
  assert.deepEqual(parseArgs(['capture', '--fakeha', '--cases', 'a']), { cmd: 'capture', fakeha: true, cases: 'a' });
  assert.deepEqual(parseArgs(['capture', '--real-ha']), { cmd: 'capture', 'real-ha': true });
  assert.throws(() => parseArgs(['capture', '--fakeha', '--real-ha']), /one or the other/);
  assert.throws(() => parseArgs(['capture', '--fakeha', '--fakeha']), /twice/);
});

test('restHistoryRequest reads mini-graph-card\'s REST history URL as a recorded call', async () => {
  const { restHistoryRequest, wsRecorder, pendingOneShots } = await import('../lib/wsdata.mjs');
  const r = restHistoryRequest('http://127.0.0.1:1/api/history/period/2026-11-11T16:08:00.000Z?filter_entity_id=sensor.a,sensor.b&end_time=2026-11-12T16%3A08%3A00.000Z&minimal_response');
  assert.deepEqual(r, {
    type: 'rest/history/period', entity_ids: ['sensor.a', 'sensor.b'], start_time: '2026-11-11T16:08:00.000Z',
    end_time: '2026-11-12T16:08:00.000Z', minimal_response: true, no_attributes: false, significant_changes_only: true,
    skip_initial_state: false,
  });
  assert.equal(restHistoryRequest('http://127.0.0.1:1/api/states'), null);
  const rec = wsRecorder();
  const call = rec.push({ request: r, events: [] });
  assert.deepEqual(pendingOneShots(rec.calls), ['rest/history/period']);
  call.result = [[]];
  assert.deepEqual(pendingOneShots(rec.calls), []);
});

test('scrubStates keeps a made-up fixture\'s attributes whole', () => {
  const st = { 'media_player.a': { s: 'playing', a: { media_title: 'Evening Tide', entity_picture: '/local/x.png', user_id: 'u' } } };
  assert.deepEqual(scrubStates(st, null, { keepPictures: true, synthetic: true })['media_player.a'].a, st['media_player.a'].a);
  assert.deepEqual(scrubStates(st, null)['media_player.a'].a, {});
});

test('a fixture picture is served to the capture with its image type', async () => {
  const { fixtureFile } = await import('../lib/fixtures.mjs');
  const dir = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..', 'engines', 'engine-vitro', 'src', 'test', 'parity', 'fixtures');
  assert.equal(fixtureFile(dir, '/local/vitro-harness-fixtures/images/album.png')?.type, 'image/png');
});

test('keepCall keeps the user list only from the fake HA and only for a logbook case', async () => {
  const { keepCall } = await import('../lib/wsdata.mjs');
  const users = { request: { type: 'config/auth/list' } };
  assert.equal(keepCall(users, true, { type: 'logbook', entities: [] }), true);
  assert.equal(keepCall(users, true, { type: 'tile' }), false);
  assert.equal(keepCall(users, false, { type: 'logbook' }), false);
  // A More Info case: its dialog's Activity section is a logbook.
  assert.equal(keepCall(users, true, { type: 'tile' }, 'binary_sensor.door'), true);
  assert.equal(keepCall(users, false, { type: 'tile' }, 'binary_sensor.door'), false);
  assert.equal(keepCall({ request: { type: 'history/stream' } }, false, { type: 'tile' }), true);
});
