// @vitest-environment jsdom
// The moments people worry most (#672): while Kiln writes, it says the backup came first and every write is read back;
// Done puts saving the backup before Home Assistant; an error says what happened, then how to fix it, apart.
import { act, cleanup, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { frame as s1PlusUi, demoFrame as s1Demo } from './s1-frame';
import { demoSession, enterDemo, type Demo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
const open: Session[] = [];
afterEach(() => { cleanup(); for (const s of open.splice(0)) s.close(); });

async function show(which: Demo) {
  const s = demoSession(s1Demo, which);
  open.push(s);
  render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
  await act(() => enterDemo(s, s1Demo, which));
  return s;
}

describe('the calm moments', () => {
  it('while it writes: the backup is saved, and every write is read back', async () => {
    const s = await show('install');
    expect(s.store.state.screen).toBe('firing');
    expect(screen.getByText(/Kiln reads every write back to check it\./)).toBeTruthy();
  });

  it('Done: first the backup, then Home Assistant, in that order', async () => {
    await show('done');
    const groups = screen.getAllByRole('group').map((g) => g.getAttribute('aria-labelledby'));
    expect(groups.indexOf('backup-h')).toBeLessThan(groups.indexOf('next-h'));
    expect(within(screen.getByRole('group', { name: 'First, save your backup' })).getByRole('button', { name: 'Download' })).toBeTruthy();
    expect(within(screen.getByRole('group', { name: 'Then, in Home Assistant' })).getByText(/Home Assistant finds it by itself\./)).toBeTruthy();
  });

  it('an error: whether anything was written, first, then how to fix it in its own callout', async () => {
    await show('error');
    expect(document.querySelector('main .note.ok')?.textContent).toBe('Nothing was written.');
    expect(screen.getByRole('group', { name: 'How to fix it' }).textContent).toMatch(/^How to fix itPlug the cable straight into this computer/);
  });
});
