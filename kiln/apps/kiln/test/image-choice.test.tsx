// @vitest-environment jsdom
// issue 579 mockups (?demo=image-…): each still renders, has at most one ember button, and every screen of a
// community install says whose software it is.
import { act, cleanup, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { IMAGE_DEMOS, ImageChoiceDemo } from '../src/mirror/choice/ChoiceScreens';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

describe('the image choice mockups', () => {
  it.each(IMAGE_DEMOS)('%s renders with at most one ember button', async (d) => {
    render(<ImageChoiceDemo name={d} />);
    await act(async () => {});
    const card = document.querySelector('.card')!;
    expect(card).not.toBeNull();
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    const community = !d.startsWith('image-choose') && !d.startsWith('image-back') && d !== 'image-done-glassworks';
    if (community) expect(card.textContent).toMatch(/Glass Panes, by git-nimrod\. Kiln installs it; it isn't Glassworks\./);
  });
});
