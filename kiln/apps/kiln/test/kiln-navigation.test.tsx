// @vitest-environment jsdom
// How a person moves through Kiln (docs/design/kiln/navigation.md), on the real sessions: Back is the rail's step before
// this one, in the same place in every foot; a step behind a write is locked and says why; nothing goes back while
// anything runs; Start over is a dialog that says what starts again and what stays; a page load on an earlier one's mark
// opens on the resume screen; and on a phone the whole rail is one tap away.
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { Shell, backOf } from '../src/components/Shell';
import { Foot } from '../src/components/Nav';
import { registry } from '../src/wizard/registry';
import { Store } from '../src/wizard/session';
import { initial, type RailStep } from '../src/wizard/state';
import { showImage } from './device-page';
import { frame as s1PlusUi, demoFrame as s1Demo } from './s1-frame';
import { demoSession, enterDemo } from '../src/wizard/demo';
import { Wizard } from '../src/wizard/Wizard';
import type { Session } from '../src/wizard/session';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
const open: Session[] = [];
afterEach(() => { cleanup(); registry.clear(); for (const s of open.splice(0)) s.close(); });

const foot = () => document.querySelector('main .foot') as HTMLElement;
const back = () => (foot() ? within(foot()).queryByRole('button', { name: /^Back/ }) ?? within(foot()).queryByRole('link', { name: /^Back/ }) : null);
/** What the resume screen says the device has now. */
const now = () => document.querySelector('.resume-now .t')!.textContent;
const railLinks = () => [...document.querySelectorAll('.rail .steps > li.can-go')].map((li) => li.querySelector('.step-name, .step-go')!.textContent);
const locked = () => [...document.querySelectorAll('.rail .steps > li')].filter((li) => li.querySelector('.step-lock')).map((li) => li.textContent);

describe('Back, from the rail', () => {
  const run = vi.fn();
  const step = (name: string, mark: RailStep['mark'], more: Partial<RailStep> = {}): RailStep => ({ name, mark, ...more });

  it('is the step before this one, when the flow lets you return to it', () => {
    expect(backOf([step('A', 'done', { go: { href: '/' } }), step('B', 'done', { go: { run } }), step('C', 'now')]).back).toEqual({ run });
    expect(backOf([step('A', 'done', { go: { href: '/' } }), step('B', 'now')]).back).toEqual({ href: '/' });
  });

  it('is none on a step behind a write, which says so, and none while it runs (no go then)', () => {
    expect(backOf([step('A', 'done', { lock: true }), step('B', 'now')])).toEqual({ locked: true });
    expect(backOf([step('A', 'done'), step('B', 'now')])).toEqual({ locked: false });
    expect(backOf([step('A', 'done'), step('B', 'done')])).toEqual({}); // all done
  });

  it('inside set-up, is the set-up step before this one; on its first, the main step before', () => {
    const sub = (at: number, go = true): RailStep['sub'] => (['set-up', 'wifi', 'home-assistant'] as const).map((route, i) => ({ name: route, route, mark: i < at ? 'done' : i === at ? 'now' : 'todo', ...(go && i < at ? { go: { href: `/x/${route}` } } : {}) }));
    expect(backOf([step('Install', 'done', { lock: true }), step('Set up', 'now', { sub: sub(2) })]).back).toEqual({ href: '/x/wifi' });
    expect(backOf([step('Install', 'done', { lock: true }), step('Set up', 'now', { sub: sub(2, false) })])).toEqual({}); // set-up runs
    expect(backOf([step('Install', 'done', { lock: true }), step('Set up', 'now', { sub: sub(0) })])).toEqual({ locked: true });
  });

  it('sits first in the foot, and a screen can give its own or none', () => {
    render(<Shell steps={[step('Device', 'done', { go: { run } }), step('Connect', 'now')]} level={0}><Foot><button type="button" className="btn ember">Go</button></Foot></Shell>);
    const btns = within(foot()).getAllByRole('button');
    expect(btns.map((b) => b.textContent)).toEqual(['Back', 'Go']);
    fireEvent.click(btns[0]!);
    expect(run).toHaveBeenCalledTimes(1);
    cleanup();
    render(<Shell steps={[step('Device', 'done', { go: { run } }), step('Connect', 'now')]} level={0}><Foot back={null}><span /></Foot></Shell>);
    expect(back()).toBeNull();
  });
});

describe('the image flow', () => {
  it('mid-flow: Back to the download, the choice and the picker as links on the rail, and nothing locked', async () => {
    const { image } = await showImage('flash-confirm');
    expect(railLinks()).toEqual(['Mirror', 'Choose', 'Download']);
    expect(locked()).toEqual([]);
    fireEvent.click(back()!);
    expect(image.state.screen).toBe('image');
    fireEvent.click(screen.getByRole('button', { name: 'Back to Choose' }));
    expect(image.state.screen).toBe('choose');
  });

  it('after the write: no Back, the steps before are locked, and the foot says why', async () => {
    await showImage('flash-adb');
    expect(back()).toBeNull();
    expect(railLinks()).toEqual([]);
    expect(locked().length).toBeGreaterThan(0);
    expect(foot().querySelector('.foot-lock')!.textContent).toBe('Glassworks is written to the Mirror');
    expect(document.querySelector('.rail-note')!.textContent).toBe('Glassworks is written to the Mirror');
    // writing it again is a real button, not a sentence with a link
    expect(within(foot()).getByRole('button', { name: 'Write the image again' }).className).toMatch(/btn ghost/);
  });

  it('while it writes: no Back, no rail links, no Start over', async () => {
    await showImage('place-working');
    expect(back()).toBeNull();
    expect(railLinks()).toEqual([]);
    expect(screen.queryByRole('button', { name: 'Start over' })).toBeNull();
  });
});

describe('Start over', () => {
  it('is under the rail, and opens a dialog that says what starts again and what stays before it does anything', async () => {
    const { image } = await showImage('place-restore-point');
    fireEvent.click(within(document.querySelector('.rail') as HTMLElement).getByRole('button', { name: 'Start over' }));
    const dlg = screen.getByRole('dialog', { name: 'Start over?' });
    expect(dlg.textContent).toMatch(/Starts again.*setup agent again.*Where this install got to/);
    expect(dlg.textContent).toMatch(/Stays.*Your Wi-Fi and the app you picked.*bundle saved in this browser/);
    // the confirm throws work away: the problem color, never ember
    expect(within(dlg).getByRole('button', { name: 'Start over' }).className).toMatch(/danger/);
    expect(dlg.querySelector('.btn.ember')).toBeNull();
    fireEvent.click(within(dlg).getByRole('button', { name: 'Keep going' }));
    expect(image.state).toMatchObject({ screen: 'restore-point', startingOver: false });
    fireEvent.click(within(document.querySelector('.rail') as HTMLElement).getByRole('button', { name: 'Start over' }));
    act(() => { fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Start over' })); });
    expect(image.state.screen).toBe('image');
  });
});

describe('resume', () => {
  it('opens on where the install stopped: what is done, what the device has now, Continue and Start over', async () => {
    const { image } = await showImage('place-resume');
    expect(screen.getByRole('heading', { name: 'Pick up where you left off' })).toBeTruthy();
    expect(screen.getByRole('list', { name: 'Done so far' }).textContent).toMatch(/Choose.*Prepare.*Fastboot.*Receive.*Next: Install/);
    expect(now()).toBe('The bundle is saved in this browser');
    expect(back()).toBeNull(); // resume has no Back: Continue, or a way out
    expect([...foot().querySelectorAll('.btn')].map((b) => b.textContent)).toEqual(['Start over', 'Continue']);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(image.state).toMatchObject({ screen: 'fastboot', round: 2, resumed: undefined });
    expect(screen.getByRole('heading', { name: 'Put the Mirror in fastboot mode' })).toBeTruthy();
  });

  it('with only the setup agent on the device: Start over from the resume screen asks first', async () => {
    const { image } = await showImage('place-resume-staged');
    expect(now()).toBe('The setup agent is on the Mirror');
    fireEvent.click(within(foot()).getByRole('button', { name: 'Start over' }));
    expect(screen.getByRole('dialog', { name: 'Start over?' })).toBeTruthy();
    act(() => { fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Start over' })); });
    expect(image.state).toMatchObject({ screen: 'image', resumed: undefined });
  });
});

describe('on a phone', () => {
  it('the step bar says where you are, and Steps opens the whole rail with its links and actions', async () => {
    await showImage('place-restore-point');
    const bar = document.querySelector('.stepbar') as HTMLElement;
    expect(bar.textContent).toMatch(/Step 6 of 8Install/);
    fireEvent.click(within(bar).getByRole('button', { name: 'Steps' }));
    const sheet = screen.getByRole('dialog', { name: 'Steps' });
    expect(within(sheet).getByRole('button', { name: 'Start over' })).toBeTruthy();
    expect(sheet.querySelector('.rail-note')!.textContent).toBe('The setup agent is on the Mirror');
    fireEvent.click(within(sheet).getByRole('button', { name: 'Start over' }));
    expect(screen.queryByRole('dialog', { name: 'Steps' })).toBeNull(); // the sheet goes; Start over's dialog opens
    expect(screen.getByRole('dialog', { name: 'Start over?' })).toBeTruthy();
  });
});

describe('the S1 wizard', () => {
  it('Connect: Back goes to the picker, and the photo is a button, not a link in a sentence', async () => {
    const s = demoSession(s1Demo, 'connect');
    open.push(s);
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, 'connect'));
    expect(back()!.getAttribute('href')).toBe('/');
    expect(within(foot()).getByRole('button', { name: 'Show me a photo' }).className).toMatch(/btn ghost/);
  });

  it('set-up, past its first step: Back to the set-up step before', async () => {
    const s = demoSession(s1Demo, 'home-assistant');
    open.push(s);
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, 'home-assistant'));
    await act(() => new Promise((r) => setTimeout(r, 30)));
    expect(back()!.getAttribute('href')).toBe('/aqara-s1-plus/set-up/wifi');
  });
});

describe('the registry, from a render', () => {
  it('a session made while a component renders reaches the other listeners after that render, not during it', async () => {
    const heard = vi.fn();
    const off = registry.subscribe(heard);
    registry.set({ base: '/x', name: 'X', session: { store: new Store(initial()), close: () => {} } as never }, { quiet: true });
    expect(heard).not.toHaveBeenCalled();
    await Promise.resolve();
    expect(heard).toHaveBeenCalledTimes(1);
    registry.set({ base: '/y', name: 'Y', session: { store: new Store(initial()), close: () => {} } as never });
    expect(heard).toHaveBeenCalledTimes(2); // every other set is heard at once (LeaveGuard)
    off();
  });
});
