// @vitest-environment jsdom
// "Try again" after a failed write starts the next attempt clean, but keeps the panel's history: a
// device list closed on the retry asks for recovery mode, says this attempt wrote nothing and an
// earlier one did, and keeps Undo.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { frame as s1PlusUi, demoFrame as s1Demo } from './s1-frame';
import { demoSession, enterDemo } from '../src/wizard/demo';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {}); // Session.fail logs the error
});
afterEach(() => cleanup());

describe('Try again after a failed write', () => {
  it('a device list closed on the retry asks for recovery mode, and keeps Undo', async () => {
    const s = demoSession(s1Demo, 'error-written'); // its Connect is a picker closed with nothing picked
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, 'error-written'));
    expect(screen.getByRole('heading', { name: 'Something went wrong while writing' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(/An earlier attempt already changed the panel\./)).toBeTruthy(); // not "Nothing is written yet."
    expect(screen.queryByText(/Nothing is written yet/)).toBeNull();
    // the way back is right there too, not only after another failed Connect
    expect(screen.getByRole('button', { name: 'Undo with your backup' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

    expect(await screen.findByRole('heading', { name: 'No panel in recovery mode' })).toBeTruthy();
    // what happened, then how to fix it in its own callout (#672)
    expect(document.querySelector('main .note')?.textContent).toBe('This attempt wrote nothing; an earlier try did.');
    expect(screen.getByRole('group', { name: 'How to fix it' }).textContent)
      .toBe('How to fix itUnplug it, hold the recovery button on the board first, then plug it in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(screen.getAllByRole('button', { name: 'Undo with your backup' })[0]!.className).toMatch(/ember/);
    expect(s.store.state.failure).toMatchObject({ at: 1, wrote: true });
    s.close();
  });
});
