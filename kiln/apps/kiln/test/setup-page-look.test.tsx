// @vitest-environment jsdom
// The setup page the panel serves (#34) is the panel's own, so it wears the panel's look (#63), not Kiln's:
// the Glassworks lockup and stack. Its colours: setup-page-css.test.ts.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { LanPage } from '../src/lan/LanPage';
import { FULL_RAIL, type LanSession, type LanView, type RailStep } from '../src/lan/lanSession';

afterEach(cleanup);

function page(view: LanView, rail: readonly RailStep[] = FULL_RAIL) {
  const state = { view, rail };
  const lan = { subscribe: () => () => {}, get: () => state } as unknown as LanSession;
  return render(<LanPage lan={lan} />).container;
}
const setup = (phase: 'wifi' | 'ha' | 'radio') => ({ at: 'setup', setup: { phase, ha: { url: '', signedIn: false }, radio: { mode: 'off', options: [] } } }) as unknown as LanView;
/** The rail's stack, top plate first. */
const plates = (c: HTMLElement) => [...c.querySelector('.rail-stack')!.querySelectorAll('path')].reverse().map((p) => p.getAttribute('class'));

describe('the setup page wears the panel\'s look', () => {
  it('Glassworks at the top, not Kiln', () => {
    const c = page({ at: 'pair', open: true, shown: false });
    const lockup = c.querySelector<HTMLImageElement>('.brand img')!;
    expect(lockup.alt).toBe('Glassworks');
    expect(decodeURIComponent(lockup.src)).toMatch(/^data:image\/svg\+xml,<svg[^>]+aria-label="Glassworks"/);
    expect(c.querySelector('.top')!.textContent).not.toMatch(/Kiln/);
    expect(c.querySelector('[fill="url(#gw-ember)"]')).toBeNull(); // neither Kiln's mark nor its furnace
  });

  it('the plates set one per step, as on the panel\'s screen; done lights the top one', () => {
    expect(plates(page({ at: 'pair', open: true, shown: false }))).toEqual(['ps-ghost', 'ps-ghost', 'ps-ghost']);
    expect(plates(page(setup('wifi')))).toEqual(['ps-ghost', 'ps-ghost', 'ps-ghost']);
    expect(plates(page(setup('radio')))).toEqual(['ps-ghost', 'ps-outline', 'ps-outline']);
    expect(plates(page({ at: 'done' }))).toEqual(['ps-lit', 'ps-outline', 'ps-outline']);
  });

  it('a panel that stopped answering shows its next plate in rose', () => {
    expect(plates(page({ at: 'lost', error: 'The panel stopped answering.' }))).toEqual(['ps-ghost', 'ps-ghost', 'ps-bad']);
  });
});

describe('the rail is the panel\'s own steps (#453)', () => {
  const NO_RADIO: RailStep[] = ['pair', 'wifi', 'ha', 'done'];
  const steps = (c: HTMLElement) => [...c.querySelectorAll('.steps li')].map((li) => `${li.textContent} ${li.className}`);
  const counter = (c: HTMLElement) => c.querySelector('.top .narrow')!.textContent;

  it('a panel without a radio lists no radio step', () => {
    const c = page(setup('ha'), NO_RADIO);
    expect(steps(c)).toEqual(['Pair with the panel done', 'Wi-Fi done', 'Home Assistant now', 'Done todo']);
    expect(counter(c)).toBe('3 of 4');
    expect(plates(c)).toEqual(['ps-ghost', 'ps-outline', 'ps-outline']);
  });

  it('and ends on Done with every step done', () => {
    const c = page({ at: 'done' }, NO_RADIO);
    expect(steps(c)).toEqual(['Pair with the panel done', 'Wi-Fi done', 'Home Assistant done', 'Done done']);
    expect(counter(c)).toBe('Done');
  });

  it('a panel with a radio lists it', () => {
    const c = page(setup('radio'));
    expect(steps(c)).toEqual(['Pair with the panel done', 'Wi-Fi done', 'Home Assistant done', 'Radio now', 'Done todo']);
    expect(counter(c)).toBe('4 of 5');
  });
});
