/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// All panels' logic (#466): node --test core/src/test/js/

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { accepts, authorizeUrl, manageOrigin, outcome, panelsFrom, plan, reachability } from '../../main/assets/manage/panels-core.js';

const device = (id, url, more = {}) => ({ id, name: 'Glassworks Panel', configuration_url: url, area_id: null, disabled_by: null, ...more });

test('a panel is a device whose Visit link is a Manage page on 8099', () => {
  assert.equal(manageOrigin('http://192.0.2.41:8099'), 'http://192.0.2.41:8099');
  assert.equal(manageOrigin('http://192.0.2.41:8099/'), 'http://192.0.2.41:8099');
  assert.equal(manageOrigin('http://[fd00::41]:8099'), 'http://[fd00::41]:8099');
  for (const no of [null, '', 'http://192.0.2.41', 'http://192.0.2.41:6052', 'https://192.0.2.41:8099', 'http://192.0.2.41:8099/config', 'http://u:p@192.0.2.41:8099', 'homeassistant://navigate/x']) {
    assert.equal(manageOrigin(no), null, String(no));
  }
});

test('the panels, from the device registry: Music Assistant copies of a panel and disabled ones left out', () => {
  const devices = [
    device('b', 'http://192.0.2.42:8099', { name_by_user: 'Bedroom panel', area_id: 'bedroom' }),
    device('a', 'http://192.0.2.41:8099', { name: 'Aqara S1 Plus', area_id: 'kitchen' }),
    // Music Assistant makes a device for a panel's player, with the same model and no Visit link.
    device('ma', null, { name: 'S1 Panel', model: 'Panel Hub S1 Plus (S1 Kiosk)' }),
    device('off', 'http://192.0.2.43:8099', { disabled_by: 'user' }),
    device('dup', 'http://192.0.2.41:8099/'),
    device('esp', 'http://192.0.2.50'),
  ];
  const areas = [{ area_id: 'kitchen', name: 'Kitchen' }, { area_id: 'bedroom', name: 'Bedroom' }];
  const got = panelsFrom(devices, areas, '192.0.2.42:8099');
  assert.deepEqual(got.map((p) => [p.name, p.area, p.origin, p.self]), [
    ['Bedroom panel', 'Bedroom', 'http://192.0.2.42:8099', true],
    ['Aqara S1 Plus', 'Kitchen', 'http://192.0.2.41:8099', false],
  ]);
});

test("HA's login page for this page: client id and redirect on the page's own origin", () => {
  const u = new URL(authorizeUrl('http://192.0.2.10:8123/', 'http://192.0.2.41:8099', 'abc'));
  assert.equal(u.origin + u.pathname, 'http://192.0.2.10:8123/auth/authorize');
  assert.equal(u.searchParams.get('client_id'), 'http://192.0.2.41:8099/');
  assert.equal(u.searchParams.get('redirect_uri'), 'http://192.0.2.41:8099/panels');
  assert.equal(u.searchParams.get('state'), 'abc');
  assert.equal(u.searchParams.get('response_type'), 'code');
});

const panel = (version, sections) => ({ api: 1, name: 'Glassworks Panel', version, sections });
const sec = (id, title, items) => ({ id, title, items });
const sw = (key, value) => ({ key, title: key, kind: 'switch', value, shown: value === 'on' ? 'On' : 'Off' });
const dur = (key, value, choices) => ({ key, title: key, kind: 'duration', value: String(value), shown: 'After ' + value + ' s', choices });
const choice = (key, value, ids) => ({ key, title: key, kind: 'choice', value, shown: value, options: ids.map((i) => [i, i]) });

test('copying sends only what differs and the other panel can take; the rest says why', () => {
  const kitchen = panel('0.9.2', [
    sec('screen', 'Screen', [sw('Full screen', 'on'), dur('Turn off when idle', 300, [30, 60, 120, 300, 0]), sw('Night dimming', 'on')]),
    sec('assist', 'Voice', [sw('on', 'on'), choice('Wake word', 'hey_jarvis', ['okay_nabu', 'hey_jarvis'])]),
    sec('camera', 'Camera', [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: true }]),
    sec('radio', 'Radio', [sw('on', 'on')]),
  ]);
  const bedroom = panel('0.9.1', [
    sec('screen', 'Screen', [sw('Full screen', 'on'), dur('Turn off when idle', 120, [30, 60, 120, 0])]),
    sec('assist', 'Voice', [sw('on', 'off'), choice('Wake word', 'okay_nabu', ['okay_nabu'])]),
    sec('camera', 'Camera', [{ key: 'Stream sign-in', title: 'Stream sign-in', kind: 'sign-in', set: false }]),
    sec('dashboard', 'Home Assistant & dashboard', [sw('Full screen', 'off')]),
  ]);
  const { rows, same } = plan(kitchen, bedroom);
  assert.equal(same, 1, 'Full screen matches');
  const by = Object.fromEntries(rows.map((r) => [r.label, r]));
  assert.deepEqual(rows.filter((r) => r.state === 'send').map((r) => [r.section, r.key, r.value]), [['assist', 'on', 'on']]);
  assert.equal(by['Screen › Turn off when idle'].state, 'cant', "300 s isn't one of the bedroom's choices");
  assert.equal(by['Screen › Night dimming'].state, 'missing', "the bedroom's version doesn't have it");
  assert.equal(by['Voice › Wake word'].state, 'cant', 'an option its version lacks');
  assert.equal(by['Camera › Stream sign-in'].state, 'skip');
  assert.equal(by['Radio › On/off'].state, 'missing', 'no radio there');
  assert.equal(by['Voice › On/off'].to, 'Off');
  assert.equal(by['Voice › On/off'].from, 'On');
  assert.ok(!rows.some((r) => r.section === 'dashboard'), "nothing the kitchen lacks is touched");
});

test('a setting whose kind changed between versions is not sent', () => {
  const a = panel('2', [sec('screen', 'Screen', [sw('Brightness', 'on')])]);
  const b = panel('1', [sec('screen', 'Screen', [{ key: 'Brightness', kind: 'slider', value: '0.5', min: '0', max: '1', step: '0.1', shown: '50 %' }])]);
  assert.equal(plan(a, b).rows[0].state, 'missing');
});

test('what a panel can take', () => {
  assert.ok(accepts({ kind: 'slider', min: '0', max: '2.5' }, '2.5'));
  assert.ok(!accepts({ kind: 'slider', min: '0', max: '2.5' }, '3'));
  assert.ok(!accepts({ kind: 'slider', min: '0', max: '2.5' }, 'x'));
  assert.ok(accepts({ kind: 'duration', choices: [0, 60] }, '0'));
  assert.ok(accepts({ kind: 'text' }, 'anything'));
});

test("a panel's answers, in the page's words", () => {
  assert.deepEqual(outcome(200, { ok: true, restart: true }), { state: 'done', restart: true });
  assert.deepEqual(outcome(202, { pending: true }), { state: 'pending' });
  assert.equal(outcome(409, { ask: { title: 'Turn the radio off?' } }).ask.title, 'Turn the radio off?');
  assert.equal(outcome(422, { problem: 'Pick one of the choices.' }).why, 'Pick one of the choices.');
  assert.equal(outcome(404, { missing: true }).state, 'failed');
  assert.equal(outcome(500, null).why, "That panel didn't take it (500).");
  assert.deepEqual(reachability(200, { api: 1, version: '0.9.2' }), { state: 'up', version: '0.9.2' });
  assert.equal(reachability(401, { problem: 'Home Assistant didn\'t confirm this sign-in. Sign in again.' }).state, 'refused');
  assert.equal(reachability(404, null).why, 'It refused (404).');
});
