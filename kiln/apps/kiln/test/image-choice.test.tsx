// @vitest-environment jsdom
// issue 579 mockups (?demo=image-…): each still renders, has at most one ember button, and every screen of a
// community install says who makes it, in friendly words. Community images stay hidden until the person asks
// for them, an image's link (?image=<id>) opens straight into its install, and a download Kiln can't serve is
// an error to retry or report, never a file to fetch by hand.
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

const WHOSE = /Glass Panes is made by git-nimrod\. Questions about it go to its project\./;
/** Round 3 (maintainer, 2026-11-19): welcoming to image authors, and no manual download path. */
const NEVER = [/recommended/i, /isn.t Glassworks/i, /not Glassworks/i, /can.t help/i, /yourself/i, /select the/i, /not to Glassworks/i];

describe('the image choice mockups', () => {
  it.each(IMAGE_DEMOS)('%s renders with at most one ember button', async (d) => {
    render(<ImageChoiceDemo name={d} search="" />);
    await act(async () => {});
    const card = document.querySelector('.card')!;
    expect(card).not.toBeNull();
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    const community = !d.startsWith('image-choose') && !d.startsWith('image-back') && d !== 'image-done-glassworks';
    if (community) expect(card.textContent).toMatch(WHOSE);
    for (const w of NEVER) expect(document.body.textContent).not.toMatch(w);
    expect(document.querySelector('input[type="file"]')).toBeNull();
  });

  it('shows Glassworks alone, first and picked, until the community images are opened', async () => {
    render(<ImageChoiceDemo name="image-choose" search="" />);
    await act(async () => {});
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('aria-label'))).toEqual(['Glassworks']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('radio', { name: 'Glassworks' }).querySelector('.pill')).toBeNull();
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
    expect(document.querySelector('.card')!.textContent).toMatch(WHOSE);
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

  it.each(['image-choose-panes', 'image-link', 'image-download', 'image-download-error', 'image-confirm', 'image-done'] as const)('%s links each GitHub address once', async (d) => {
    render(<ImageChoiceDemo name={d} search="" />);
    await act(async () => {});
    const hrefs = [...document.querySelectorAll('.card a[href^="https://github.com/"]')].map((a) => a.getAttribute('href'));
    expect(hrefs.length).toBeGreaterThan(0);
    expect(hrefs).toEqual([...new Set(hrefs)]);
  });

  it('a download Kiln can\'t serve is an error with Try again and Report this, and nothing to pick by hand', async () => {
    render(<ImageChoiceDemo name="image-download-error" search="" />);
    await act(async () => {});
    expect(screen.getByRole('alert').textContent).toMatch(/Kiln couldn’t download it: Refract answered 502 \(GitHub answered 503\)\. Nothing was written to the Mirror\. This is a bug in Kiln, not something you did\./);
    const report = new URL(screen.getByRole('link', { name: 'Report this' }).getAttribute('href')!);
    expect(report.origin + report.pathname).toBe('https://github.com/GlassworksHQ/glassworks/issues/new');
    expect(report.searchParams.get('title')).toBe("Kiln couldn't download system.img for Glass Panes");
    expect(report.searchParams.get('body')).toMatch(/^Image: glass-panes\nFile: system\.img \(850905808 bytes\)\nWhat Kiln saw: Refract answered 502/);
    expect([...document.querySelectorAll('.btn.ember')].map((b) => b.textContent)).toEqual(['Try again']);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('progressbar', { name: 'Downloading system.img' })).toBeTruthy();
  });

  it('a file that isn\'t the release\'s is deleted, with Try again and Report this', async () => {
    render(<ImageChoiceDemo name="image-download-bad" search="" />);
    await act(async () => {});
    expect(screen.getByRole('alert').textContent).toMatch(/SHA-256 differs\), so it deleted it\. Nothing was written to the Mirror\./);
    expect(screen.getByRole('link', { name: 'Report this' })).toBeTruthy();
    expect([...document.querySelectorAll('.btn.ember')].map((b) => b.textContent)).toEqual(['Try again']);
  });

  it.each(['image-link', 'image-download', 'image-confirm'] as const)('%s offers Install Glassworks instead', async (d) => {
    const toGlassworks = vi.fn();
    render(<ImageChoiceDemo name={d} search="" toGlassworks={toGlassworks} />);
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks instead' }));
    expect(toGlassworks).toHaveBeenCalledOnce();
  });
});
