// @vitest-environment jsdom
// The mockups for keeping Home Assistant's key over a Mirror reflash (#580): every state renders with
// one ember button at most, the hand-back row shows only with a key held, and Done says what HA does.
import { act, cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { flashDemo } from '../src/mirror/flashDemo';
import { KEEP_DEMOS, keepDemo } from '../src/mirror/keepDemo';
import { Flash } from '../src/mirror/MirrorWizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const show = async (d: Awaited<ReturnType<typeof keepDemo>>) => { render(<Flash flash={d.flash} mirror={d.mirror} />); await act(async () => {}); };
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('keeping the Mirror in Home Assistant over a reflash', () => {
  it.each(KEEP_DEMOS)('%s renders with at most one ember button', async (d) => {
    await show(await keepDemo(d, true));
    expect(document.querySelector('.card')).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('the fastboot screen links to the step; it only reads before the restart', async () => {
    await show(await flashDemo('flash-fastboot', true));
    expect(screen.getByText('Keep its Home Assistant device first')).toBeTruthy();
    cleanup();
    await show(await keepDemo('flash-keep', true));
    expect(screen.getByText(/Nothing is written yet/)).toBeTruthy();
    expect(embers()).toEqual(['Connect']);
  });

  it('the hand-back row shows only with a key held', async () => {
    await show(await keepDemo('flash-keep-working', true));
    expect(screen.getByText('Give Glassworks Home Assistant’s key back')).toBeTruthy();
    cleanup();
    await show(await flashDemo('flash-working', true));
    expect(screen.queryByText('Give Glassworks Home Assistant’s key back')).toBeNull();
  });

  it('Done says Same device when the key went back, Asks once with the HA steps otherwise, nothing on a first install', async () => {
    await show(await keepDemo('flash-done-kept', true));
    expect(screen.getByText('Same device')).toBeTruthy();
    expect(screen.queryByText('Reconfigure')).toBeNull();
    cleanup();
    for (const d of ['flash-done-asks', 'flash-done-refused'] as const) {
      await show(await keepDemo(d, true));
      expect(screen.getByText('Asks once')).toBeTruthy();
      expect(screen.getByText('Reconfigure')).toBeTruthy();
      cleanup();
    }
    await show(await flashDemo('flash-done', true));
    expect(screen.queryByRole('group', { name: 'Home Assistant' })).toBeNull();
  });
});
