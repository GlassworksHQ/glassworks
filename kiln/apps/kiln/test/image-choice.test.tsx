// @vitest-environment jsdom
// issue 579 mockups (?demo=image-…): each still renders, has at most one ember button, and every screen of a
// community install says whose software it is. Community images stay hidden until the person asks for them,
// and an image's link (?image=<id>) opens straight into its install.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { IMAGE_DEMOS, ImageChoiceDemo } from '../src/mirror/choice/ChoiceScreens';
import { imageFromLink } from '../src/mirror/choice/options';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

describe('the image choice mockups', () => {
  it.each(IMAGE_DEMOS)('%s renders with at most one ember button', async (d) => {
    render(<ImageChoiceDemo name={d} search="" />);
    await act(async () => {});
    const card = document.querySelector('.card')!;
    expect(card).not.toBeNull();
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    const community = !d.startsWith('image-choose') && !d.startsWith('image-back') && d !== 'image-done-glassworks';
    if (community) expect(card.textContent).toMatch(/Glass Panes, by git-nimrod\. Kiln installs it; it isn't Glassworks\./);
  });

  it('shows Glassworks alone, recommended, until the community images are opened', async () => {
    render(<ImageChoiceDemo name="image-choose" search="" />);
    await act(async () => {});
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['Glassworks']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).textContent).toMatch(/Recommended/);
    expect(screen.queryByText('Glass Panes')).toBeNull();
    const show = screen.getByRole('button', { name: 'Show community images (1)' });
    expect(show.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(show);
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['Glassworks', 'Glass Panes']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).getAttribute('aria-checked')).toBe('true');
  });

  it('hiding the community images again goes back to Glassworks', async () => {
    render(<ImageChoiceDemo name="image-choose-panes" search="" />);
    await act(async () => {});
    expect(document.querySelector('.btn.ember')!.textContent).toBe('Continue with Glass Panes');
    fireEvent.click(screen.getByRole('button', { name: 'Hide community images' }));
    expect(screen.queryByRole('radio', { name: 'Glass Panes' })).toBeNull();
    expect(document.querySelector('.btn.ember')!.textContent).toBe('Continue with Glassworks');
  });

  it('reads only a community image this frame offers from ?image=', () => {
    expect(imageFromLink('?image=glass-panes')?.id).toBe('glass-panes');
    for (const q of ['', '?image=', '?image=nope', '?image=glassworks', '?image=Glass-Panes']) expect(imageFromLink(q)).toBeUndefined();
  });

  it('?image=glass-panes skips the picker and opens Glass Panes, still its, still with every step', async () => {
    const toGlassworks = vi.fn();
    render(<ImageChoiceDemo name="image-choose" search="?image=glass-panes" toGlassworks={toGlassworks} />);
    await act(async () => {});
    expect(screen.queryAllByRole('radio')).toEqual([]);
    expect(screen.getByRole('heading').textContent).toBe('Install Glass Panes on the Mirror.');
    expect(document.querySelector('.card')!.textContent).toMatch(/Glass Panes, by git-nimrod\. Kiln installs it; it isn't Glassworks\./);
    expect([...document.querySelectorAll('.steps > li')].map((li) => li.textContent)).toEqual(['Mirror', 'Download', 'Fastboot', 'Install', 'Done']);
    expect(document.querySelector('.btn.ember')!.textContent).toBe('Download Glass Panes'); // nothing downloads before the click
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks instead' }));
    expect(toGlassworks).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: 'Download Glass Panes' }));
    expect(screen.getByRole('heading').textContent).toBe('Downloading Glass Panes.');
  });

  it.each(['?image=nope', '?image=glassworks', '?image='])('%s shows the normal page with the picker', async (q) => {
    render(<ImageChoiceDemo name="image-choose" search={q} />);
    await act(async () => {});
    expect(screen.getByRole('heading').textContent).toBe('What goes on the Mirror?');
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['Glassworks']);
  });
});
