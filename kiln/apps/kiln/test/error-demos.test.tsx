// @vitest-environment jsdom
// Every failure explain.ts tells apart has its own ?demo=error-… (#672), so the error screen can be reviewed for each:
// each demo lands on the error screen with that rule's title, and none falls through to the generic one by accident.
import { act, cleanup, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { frame as s1PlusUi, demoFrame as s1Demo } from './s1-frame';
import { DEMOS, demoSession, enterDemo, type Demo } from '../src/wizard/demo';
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

/** Each error demo and the title explain.ts gives its failure. */
const TITLES: [Demo, string][] = [
  ['error-no-recovery', 'No panel in recovery mode'],
  ['error-secure-boot', 'This panel only runs signed software'],
  ['error-not-this-panel', 'This isn\'t a panel Kiln recognizes'],
  ['error-usb-unreliable', 'The USB connection isn\'t steady'],
  ['error-download', 'A download failed'],
  ['error-not-started', 'The panel took too long to start'],
  ['error-adb-held', 'Another program is using the panel'],
  ['error-backup-refused', 'This backup can\'t undo the install'],
  ['error-nothing-picked', 'Nothing was picked'],
  ['error-earlier', 'The panel disconnected'],
  ['error-unknown', 'Something went wrong'],
  ['error-unknown-written', 'Something went wrong while writing'],
];

describe('the error screen, one demo per failure', () => {
  it('every error demo is in the list', () => {
    expect(DEMOS.filter((d) => d.startsWith('error-') && d !== 'error-written').sort()).toEqual(TITLES.map(([d]) => d).sort());
  });

  it.each(TITLES)('%s: %s', async (which, title) => {
    const s = demoSession(s1Demo, which);
    open.push(s);
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, which));
    expect(s.store.state.screen).toBe('error');
    expect(document.querySelector('h2')?.textContent).toBe(title);
  }, 20_000);

  it('a failure after the new software is in place says so, as the session\'s own does (fail with installed)', async () => {
    for (const which of ['error-not-started', 'error-adb-held', 'error-nothing-picked'] as Demo[]) {
      const s = demoSession(s1Demo, which);
      open.push(s);
      await enterDemo(s, s1Demo, which);
      expect(s.store.state.failure?.body, which).toMatch(/^The new software is in place\./);
    }
  });
});
