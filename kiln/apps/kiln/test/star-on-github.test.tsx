// @vitest-environment jsdom
// Done asks, once and quietly, for a star on GitHub after an install; an undo's Done doesn't.
import { act, cleanup, render, screen } from '@testing-library/react';
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
}

describe('star on GitHub', () => {
  it('an install\'s Done links to the repository, in a new tab, as a link and not a button', async () => {
    await show('done');
    const a = screen.getByRole('link', { name: 'Star it on GitHub' });
    expect(a.getAttribute('href')).toBe('https://github.com/GlassworksHQ/glassworks');
    expect(a.getAttribute('target')).toBe('_blank');
    expect(a.className).toBe('link');
    expect(a.closest('p')!.textContent).toBe('Enjoying Glassworks? Star it on GitHub to help others find it.');
  });

  it('an undo\'s Done doesn\'t ask', async () => {
    await show('undo-done');
    expect(screen.queryByRole('link', { name: 'Star it on GitHub' })).toBeNull();
  });
});
