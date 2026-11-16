// @vitest-environment jsdom
// Kiln hands off to the panel's own setup page (#34): a page on https can't reach the panel's http, so Done gives
// the page's address, and only for an app that has it (`pairing.arm` answers; an older app doesn't know it).
import { act, cleanup, render, screen } from '@testing-library/react';
import { Refused } from '@glassworks/setup-channel';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoSession, enterDemo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

type Steps = { channel?: { armPairing: () => Promise<unknown>; close: () => Promise<void> } };
const steps = (s: Session) => (s as unknown as { steps: Steps }).steps;

/** The radio step on the simulated panel, kept Off: Done follows. */
async function toDone(s: Session, before: (s: Session) => void = () => {}) {
  await s.route('radio');
  before(s);
  await s.click('radio', () => s.applyRadio('off'));
  await vi.waitFor(() => expect(s.store.state.screen).toBe('done'));
}

describe('the hand-off to the panel\'s setup page', () => {
  it('Done gives the page\'s address when the app has it, after opening pairing for it', async () => {
    const s = demoSession('radio');
    let armed = 0;
    let closed = 0;
    await toDone(s, (x) => {
      const c = steps(x).channel!;
      const arm = c.armPairing.bind(c);
      c.armPairing = () => { armed++; return arm(); };
      const close = c.close.bind(c);
      c.close = () => { closed++; return close(); };
    });
    await vi.waitFor(() => expect(s.store.state.done?.setupPage).toBe('http://192.0.2.50:8099/setup'));
    expect(armed).toBe(1);
    expect(closed).toBe(1); // the channel goes once the hand-off is done
    s.close();
  });

  it('an app without it (pairing.arm unknown) gets no hand-off', async () => {
    const s = demoSession('radio');
    await toDone(s, (x) => { steps(x).channel!.armPairing = () => Promise.reject(new Refused('Unknown request \'pairing.arm\'.')); });
    await new Promise((r) => setTimeout(r, 50));
    expect(s.store.state.done?.title).toBe('Done.');
    expect(s.store.state.done?.setupPage).toBeUndefined();
    s.close();
  });

  it('shows the address and opens the page in a new tab', async () => {
    const s = demoSession('done');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, 'done'));
    const open = screen.getByRole('link', { name: 'Open' });
    expect(open.getAttribute('href')).toBe('http://192.0.2.50:8099/setup');
    expect(open.getAttribute('target')).toBe('_blank');
    expect(screen.getByText('http://192.0.2.50:8099/setup')).toBeTruthy();
    s.close();
  });
});
