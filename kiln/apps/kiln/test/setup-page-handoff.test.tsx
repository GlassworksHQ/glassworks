// @vitest-environment jsdom
// Kiln hands off to the panel's own setup page (#34): a page on https can't reach the panel's http, so Done gives
// the page's address, and only for an app that has it (`pairing.arm` answers; an older app doesn't know it).
import { act, cleanup, render, screen } from '@testing-library/react';
import { Refused } from '@glassworks/setup-channel';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { frame as s1PlusUi, demoFrame as s1Demo } from '../src/app/aqara-s1-plus/frame';
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
    const s = demoSession(s1Demo, 'radio');
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
    const s = demoSession(s1Demo, 'radio');
    let asked = 0;
    let closed = 0;
    await toDone(s, (x) => {
      const c = steps(x).channel!;
      c.armPairing = () => { asked++; return Promise.reject(new Refused('Unknown request \'pairing.arm\'.')); };
      const close = c.close.bind(c);
      c.close = () => { closed++; return close(); };
    });
    // the hand-off has run to its end: it asked, and let the channel go
    await vi.waitFor(() => expect([asked, closed]).toEqual([1, 1]));
    expect(s.store.state.done?.title).toBe('Done.');
    expect(s.store.state.done?.setupPage).toBeUndefined();
    s.close();
  });

  it('links the address in the panel\'s reply to pairing.arm, and none when the reply has none', async () => {
    const moved = demoSession(s1Demo, 'radio');
    await toDone(moved, (x) => {
      const c = steps(x).channel!;
      // the panel's address changed between Finish and the reply (DHCP)
      c.armPairing = async () => ({ ...(c as unknown as { state: object }).state, ip: '192.0.2.77' }) as never;
    });
    await vi.waitFor(() => expect(moved.store.state.done?.setupPage).toBe('http://192.0.2.77:8099/setup'));
    moved.close();

    const gone = demoSession(s1Demo, 'radio');
    let replied = 0;
    await toDone(gone, (x) => {
      const c = steps(x).channel!;
      c.armPairing = async () => { replied++; return ({ ...(c as unknown as { state: object }).state, ip: undefined }) as never; };
    });
    await vi.waitFor(() => expect(replied).toBe(1));
    await new Promise((r) => setTimeout(r, 0));
    expect(gone.store.state.done?.setupPage).toBeUndefined();
    gone.close();
  });

  it('a backup download that fails while the hand-off is pending shows its error, and the link stays', async () => {
    const s = demoSession(s1Demo, 'radio');
    s.store.dispatch({ type: 'backup', backup: { id: 's1-backup-20261116-101500', created: '', bootWasPatched: false, files: [{ part: 'boot', size: 1, sha256: '' }] } });
    let reply!: () => void;
    let fail!: (e: Error) => void;
    (s as unknown as { d: { backups: { files: () => Promise<never> } } }).d.backups.files = () => new Promise((_, no) => { fail = no; });
    await toDone(s, (x) => {
      const c = steps(x).channel!;
      const arm = c.armPairing.bind(c);
      c.armPairing = async () => { await new Promise<void>((r) => { reply = r; }); return arm(); };
    });
    await vi.waitFor(() => expect(reply).toBeTypeOf('function'));
    const download = s.downloadBackup(); // the read waits; Done is the one without the link
    await vi.waitFor(() => expect(fail).toBeTypeOf('function'));
    reply(); // the hand-off adds the link: a new Done object
    await vi.waitFor(() => expect(s.store.state.done?.setupPage).toBe('http://192.0.2.50:8099/setup'));
    fail(new Error('the stored backup is gone'));
    await download;
    expect(s.store.state.done?.backup?.error).toMatch(/the stored backup is gone/);
    expect(s.store.state.done?.setupPage).toBe('http://192.0.2.50:8099/setup');
    s.close();
  });

  it('a panel without an address on the network gets no hand-off, and isn\'t asked', async () => {
    const s = demoSession(s1Demo, 'wifi-new'); // the simulated panel isn't on Wi-Fi
    let asked = 0;
    let closed = 0;
    await toDone(s, (x) => {
      const c = steps(x).channel!;
      const arm = c.armPairing.bind(c);
      c.armPairing = () => { asked++; return arm(); };
      const close = c.close.bind(c);
      c.close = () => { closed++; return close(); };
    });
    await vi.waitFor(() => expect(closed).toBe(1));
    expect(asked).toBe(0);
    expect(s.store.state.done?.setupPage).toBeUndefined();
    s.close();
  });

  it('shows the address and opens the page in a new tab', async () => {
    const s = demoSession(s1Demo, 'done');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, 'done'));
    const open = screen.getByRole('link', { name: 'Open' });
    expect(open.getAttribute('href')).toBe('http://192.0.2.50:8099/setup');
    expect(open.getAttribute('target')).toBe('_blank');
    expect(screen.getByText('http://192.0.2.50:8099/setup')).toBeTruthy();
    s.close();
  });
});
