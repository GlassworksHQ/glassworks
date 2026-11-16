// @vitest-environment jsdom
// The three kinds of device (#403): every frame.json says which it is, a tested device names the frame
// whose app it installs and has no recipe of its own, and the card says the kind.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FrameCardBody, kindProblems, type FrameDesign } from '../src';

const FRAMES = join(__dirname, '../../../frames');
const ids = readdirSync(FRAMES, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
const read = (id: string) => JSON.parse(readFileSync(join(FRAMES, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;
const all = ids.map(read);

describe('kindProblems', () => {
  it.each(ids)('%s: its kind passes', (id) => {
    expect(kindProblems(read(id), all)).toEqual([]);
  });

  it('knows each frame\'s kind', () => {
    expect(Object.fromEntries(all.map((f) => [f.id, f.kind]))).toEqual({
      'aqara-s1-plus': 'full', 'generic-android': 'device', 'google-pixel-tablet': 'tested', 'lululemon-mirror': 'device',
    });
    expect(read('google-pixel-tablet').base).toBe('generic-android');
  });

  const frames = [{ id: 'generic-android', kind: 'device' as const }, { id: 'a-tablet', kind: 'tested' as const }];
  it.each([
    ['no kind', {}, 'kind: should be one of full, device, tested'],
    ['an unknown kind', { kind: 'phone' }, 'kind: should be one of full, device, tested'],
    ['a base on a full frame', { kind: 'full', base: 'generic-android' }, 'base: only a tested device has one'],
    ['a tested device without a base', { kind: 'tested' }, 'base: a tested device names the frame whose app it installs, e.g. generic-android'],
    ['a base that isn\'t a frame', { kind: 'tested', base: 'nope' }, 'base: a tested device names the frame whose app it installs, e.g. generic-android'],
    ['a base that is a tested device', { kind: 'tested', base: 'a-tablet' }, 'base: a-tablet is a tested device itself; name the frame it installs as'],
    ['a Kiln route on a tested device', { kind: 'tested', base: 'generic-android', kiln: '/x' }, 'kiln: a tested device has no recipe of its own; its base\'s install is used'],
  ])('fails %s', (_, f, why) => {
    expect(kindProblems(f as FrameDesign, frames)).toEqual([why]);
  });
});

describe('the card', () => {
  const text = (f: FrameDesign) => {
    const el = document.createElement('div');
    el.innerHTML = renderToStaticMarkup(createElement(FrameCardBody, { f }));
    return el.textContent;
  };

  it('says the kind, before the other names it goes by', () => {
    expect(text(read('aqara-s1-plus'))).toMatch(/Full frame/);
    expect(text(read('google-pixel-tablet'))).toMatch(/Tested device/);
    expect(text(read('generic-android'))).toMatch(/Device frame · Tablets, old phones/);
  });
});
