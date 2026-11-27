// @vitest-environment jsdom
// The S1's times and recovery steps, from its own data (installer.json `words.time`, `words.recoverySteps`): Connect
// says how long the install usually takes and numbers the steps beside the drawing, the install's rows and the first
// start's wait say how long, and a frame that gives none of it shows none of it.
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import type { KilnFrame } from '@glassworks/kiln-core';
import { frame as s1, demoFrame as s1Demo } from './s1-frame';
import { demoSession, enterDemo, type Demo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';
import { firstStart } from '../src/wizard/state';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
const open: Session[] = [];
afterEach(() => { cleanup(); for (const s of open.splice(0)) s.close(); });

async function show(which: Demo, frame: KilnFrame = s1) {
  const demo = { ...s1Demo, frame };
  const s = demoSession(demo, which);
  open.push(s);
  render(<Wizard session={s} frame={frame} base="/aqara-s1-plus" />);
  await act(() => enterDemo(s, demo, which));
  return s;
}

describe('the S1: times and recovery steps from its data', () => {
  it('Connect says how long the install usually takes, and numbers the steps beside the drawing', async () => {
    await show('connect');
    expect(s1.time?.total).toBe('about 10 minutes');
    expect(screen.getByText('Usually about 10 minutes')).toBeTruthy();
    const list = screen.getByRole('list', { name: 'Recovery mode, by hand' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(s1.recoverySteps!.length);
    // the action in bold, from the frame's **…**
    expect(within(items[0]!).getByText('Unplug the panel').tagName).toBe('B');
    expect(list.closest('.howto')?.querySelector('.howto-art .recview')).toBeTruthy();
    // a step ticks off, and the next one is highlighted
    fireEvent.click(within(items[0]!).getByRole('button', { name: 'Step 1' }));
    expect(items[0]!.className).toBe('ticked');
    expect(items[1]!.className).toBe('up');
    // the photo is still one click away
    expect(screen.getByRole('button', { name: 'Show me a photo' })).toBeTruthy();
  });

  it('the way back\'s Connect keeps the steps, without the install\'s time', async () => {
    await show('undo-connect');
    expect(screen.getByRole('list', { name: 'Recovery mode, by hand' })).toBeTruthy();
    expect(screen.queryByText(/^Usually /)).toBeNull();
  });

  it('the install\'s rows say how long the restart takes, and only where the frame says', async () => {
    await show('install');
    const rows = within(screen.getByRole('list', { name: /./ })).getAllByRole('listitem');
    const restart = rows.find((r) => r.textContent?.includes('Restart the panel'))!;
    expect(restart.querySelector('.pmeta')?.textContent).toBe('1–2 minutes');
    // the backup, the patch and the write have no timed source: no time, rather than a guess
    for (const r of rows.filter((x) => x !== restart && x.className.includes('next'))) expect(r.querySelector('.pmeta')?.textContent).toBe('');
  });

  it('the first start\'s wait says how long it usually takes and counts', async () => {
    await show('first-boot');
    expect(screen.getByRole('heading', { name: 'Starting the panel' })).toBeTruthy();
    expect(screen.getByText('The panel is starting its new software')).toBeTruthy();
    expect(screen.getByText('Usually 1–2 minutes')).toBeTruthy();
    expect(screen.getByText(/so far$/)).toBeTruthy();
  });

  it('a frame that gives no times or steps shows the drawing alone, with no time anywhere', async () => {
    const { time: _, recoverySteps: __, ...bare } = s1;
    await show('connect', bare as KilnFrame);
    expect(screen.queryByText(/^Usually /)).toBeNull();
    expect(screen.queryByRole('list', { name: 'Recovery mode, by hand' })).toBeNull();
    expect(document.querySelector('.recview')).toBeTruthy();
    expect(firstStart(bare as KilnFrame)).toEqual({ phase: 'waiting', note: 'The panel is starting its new software' });
  });
});
