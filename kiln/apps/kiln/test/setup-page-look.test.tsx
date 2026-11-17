// @vitest-environment jsdom
// The setup page the panel serves (#34) is the panel's own, so it wears the panel's look (#63), not Kiln's:
// the Glassworks lockup and stack. Its colours: setup-page-css.test.ts.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { LanPage } from '../src/lan/LanPage';
import type { LanSession, LanView } from '../src/lan/lanSession';

afterEach(cleanup);

function page(view: LanView) {
  const state = { view };
  const lan = { subscribe: () => () => {}, get: () => state } as unknown as LanSession;
  return render(<LanPage lan={lan} />).container;
}
const setup = (phase: 'wifi' | 'radio') => ({ at: 'setup', setup: { phase, radio: { mode: 'off', options: [] } } }) as unknown as LanView;
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
