// @vitest-environment jsdom
// The three kinds of device (#403): every frame.json says which it is, a tested device names the frame
// whose app it installs and has no recipe of its own, and the card says the kind.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FrameCardBody, kindProblems, testedProblems, testedText, type FrameDesign } from '../src';

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
      'samsung-galaxy-tab-a-10-1-2016': 'tested',
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

describe('testedProblems: who tested a tested device, and on what (#700)', () => {
  it.each(ids)('%s: its tested passes', (id) => {
    expect(testedProblems(read(id))).toEqual([]);
  });

  it('knows who tested each tested device', () => {
    expect(read('google-pixel-tablet').tested).toEqual({ by: 'glassworks', os: 'stock' });
    expect(read('samsung-galaxy-tab-a-10-1-2016').tested).toEqual({ by: 'community', os: 'LineageOS', report: 'https://github.com/GlassworksHQ/glassworks/issues/700' });
  });

  it.each([
    ['a tested device that doesn\'t say', { kind: 'tested' }, ['tested: a tested device says who tested it and on what, { "by", "os", "report" }']],
    ['another kind that does', { kind: 'device', tested: { by: 'community', os: 'stock' } }, ['tested: only a tested device has one']],
    ['an unknown tester', { kind: 'tested', tested: { by: 'someone', os: 'stock' } }, ['tested.by: should be glassworks or community']],
    ['no OS', { kind: 'tested', tested: { by: 'community', os: ' ' } }, ['tested.os: should be stock, or the custom OS it ran by name, e.g. LineageOS']],
    ['a report that isn\'t https', { kind: 'tested', tested: { by: 'community', os: 'stock', report: 'http://example.com/r' } }, ['tested.report: should be an https link to the report']],
    ['a stray field', { kind: 'tested', tested: { by: 'community', os: 'stock', serial: 'x' } }, ['tested.serial: not a tested field']],
  ])('fails %s', (_, f, why) => {
    expect(testedProblems(f as FrameDesign)).toEqual(why);
  });

  it('says it in a few words', () => {
    expect(testedText({ by: 'community', os: 'LineageOS' })).toBe('Tested by the community on LineageOS');
    expect(testedText({ by: 'glassworks', os: 'stock' })).toBe('Tested by Glassworks on stock Android');
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

  it('says who tested a tested device, and nothing of the kind on another', () => {
    expect(text(read('samsung-galaxy-tab-a-10-1-2016'))).toContain('Tested by the community on LineageOS');
    expect(text(read('google-pixel-tablet'))).toContain('Tested by Glassworks on stock Android');
    expect(text(read('generic-android'))).not.toMatch(/Tested by/);
  });
});
