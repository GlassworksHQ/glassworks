// @vitest-environment jsdom
// The stock-in-place Mirror's Done and its way back's Done (1.0 pre-launch audit): the Done that offers "Restore the
// original system" doesn't also say the install can't go back to lululemon's software (the frame's rating is for the
// image install), and the way back's Back to Kiln is an internal Next link.
import { cleanup, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} data-next-link="" {...p}>{children}</a> }));

import { FRAMES } from '../src/frames/catalog';
import { showImage } from './device-page';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  HTMLAnchorElement.prototype.click = function click() {};
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => cleanup());

const REV = FRAMES.find((x) => x.id === 'lululemon-mirror')!.ratings.reversible.label!;

describe('the in-place Done', () => {
  it('offers the restore and says nothing about having no way back to the stock software', async () => {
    await showImage('place-done');
    expect(screen.getByRole('button', { name: 'Restore the original system' })).toBeTruthy();
    expect(screen.queryByText(`${REV}.`)).toBeNull();
  });

  it('the image install\'s Done still gives the frame\'s rating', async () => {
    await showImage('flash-done');
    expect(screen.getByText(`${REV}.`)).toBeTruthy();
  });

  it('the way back\'s Done goes back to Kiln with a Next link', async () => {
    await showImage('place-undo-done');
    const back = screen.getByRole('link', { name: 'Back to Kiln' });
    expect(back.getAttribute('href')).toBe('/');
    expect(back.hasAttribute('data-next-link')).toBe(true);
  });
});
