// @vitest-environment jsdom
// A frame's credits (frames/README.md#credits): every frame.json passes the check, a credit without
// its links or its words fails it, and the card names the work a frame is built on.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FrameCardBody, cardCredit, creditProblems, type FrameCredit, type FrameDesign } from '../src';

const FRAMES = join(__dirname, '../../../frames');
const ids = readdirSync(FRAMES, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
const read = (id: string) => JSON.parse(readFileSync(join(FRAMES, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;

const good: FrameCredit = {
  id: 'an-image',
  who: { name: 'someone', href: 'https://example.com/someone' },
  what: 'Android image',
  project: { name: 'Their project', href: 'https://example.com/project' },
  uses: 'The image the device runs.',
};
const problems = (c: unknown) => creditProblems({ credits: [c as FrameCredit] });

describe('creditProblems', () => {
  it.each(ids)('%s: its credits pass', (id) => {
    expect(creditProblems(read(id))).toEqual([]);
  });

  it('passes a frame without credits, and a complete credit', () => {
    expect(creditProblems({})).toEqual([]);
    expect(problems(good)).toEqual([]);
  });

  it('fails a credit without its links', () => {
    expect(problems({ ...good, who: { name: 'someone' } })).toEqual(['credits[0] (an-image).who.href: should be an https link']);
    expect(problems({ ...good, project: { name: 'x', href: 'http://example.com' } })).toEqual(['credits[0] (an-image).project.href: should be an https link']);
  });

  it('fails a credit that doesn\'t say who, what or what we use', () => {
    expect(problems({ ...good, who: { name: ' ', href: good.who.href }, what: '', uses: undefined })).toEqual([
      'credits[0] (an-image).who.name: should be one line of text',
      'credits[0] (an-image).what: should be one line of text',
      'credits[0] (an-image).uses: should be one line of text',
    ]);
  });

  it('fails a bad or repeated id, an empty licence and a quote without its source', () => {
    expect(problems({ ...good, id: 'An Image' })).toEqual(['credits[0] (An Image).id: should be kebab-case']);
    expect(creditProblems({ credits: [good, good] })).toEqual(['credits[1] (an-image).id: used twice']);
    expect(problems({ ...good, licence: '' })).toEqual(['credits[0] (an-image).licence: leave it out, or state it']);
    expect(problems({ ...good, quote: { text: 'Their words.' } })).toEqual(['credits[0] (an-image).quote: needs its text and an https link to where it\'s from']);
  });
});

describe('the card\'s credit', () => {
  const card = (f: FrameDesign) => {
    const el = document.createElement('div');
    el.innerHTML = renderToStaticMarkup(createElement(FrameCardBody, { f }));
    return el.textContent ?? '';
  };

  it('names the Mirror\'s community image and who built it', () => {
    expect(cardCredit(read('lululemon-mirror'))).toBe('Built on Glass Panes by git-nimrod');
    expect(card(read('lululemon-mirror'))).toContain('Built on Glass Panes by git-nimrod');
  });

  it('says nothing on a frame whose credits aren\'t marked for the card', () => {
    const s1 = read('aqara-s1-plus');
    expect(s1.credits?.length).toBeGreaterThan(0);
    expect(cardCredit(s1)).toBeUndefined();
    expect(card(s1)).not.toContain('Built on');
  });
});
