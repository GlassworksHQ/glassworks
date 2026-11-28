// @vitest-environment jsdom
// The picker: s1.edm.sh's old #/ links, the running install's way back, and Continue during one.
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => router }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { ships } from '@glassworks/frame-card';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { Picker } from '../src/components/Picker';
import { FRAMES, installsAs, kilnRoute } from '../src/frames/catalog';
import { registry } from '../src/wizard/registry';
import { Store } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
beforeEach(() => { router.replace.mockClear(); router.push.mockClear(); });
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); });

function installing() {
  const store = new Store(initial());
  store.dispatch({ type: 'start', flow: 'install', now: 0 });
  registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
  return store;
}

describe('the picker', () => {
  it.each([
    ['#/install', '/aqara-s1-plus'], ['#/undo', '/aqara-s1-plus/undo'], ['#/resume', '/aqara-s1-plus/set-up'],
    ['#/wifi', '/aqara-s1-plus/set-up/wifi'], ['#/home-assistant', '/aqara-s1-plus/set-up/home-assistant'], ['#/radio', '/aqara-s1-plus/set-up/radio'],
  ])('sends s1.edm.sh\'s %s to %s', (hash, to) => {
    history.replaceState(null, '', `/${hash}`);
    render(<Picker />);
    expect(router.replace).toHaveBeenCalledWith(to);
  });

  it('shows the frames, and Continue goes to the picked one\'s start', () => {
    render(<Picker />);
    // stable: no stage in its name and no pill, and every frame can be picked
    expect(screen.getByRole('radio', { name: 'Aqara S1 Plus' })).toHaveProperty('dataset.state', 'checked');
    expect(screen.getByRole('radio', { name: 'lululemon Studio Mirror' })).toHaveProperty('disabled', false);
    expect(screen.getByRole('radio', { name: 'Generic Android' })).toHaveProperty('disabled', false);
    // a tested device installs through its base: Ready to install, and Continue goes to generic Android's route
    expect(screen.getByRole('radio', { name: 'Google Pixel Tablet' })).toHaveProperty('disabled', false);
    const pills = (name: string) => screen.getByRole('radio', { name }).textContent ?? '';
    for (const name of ['Aqara S1 Plus', 'lululemon Studio Mirror', 'Generic Android']) expect(pills(name), name).not.toMatch(/Stable|Beta/);
    expect(screen.queryByText(/still working/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenCalledWith('/aqara-s1-plus');
    // another frame is picked and continued like the first: its route, opened to everyone
    fireEvent.click(screen.getByRole('radio', { name: 'lululemon Studio Mirror' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenLastCalledWith('/lululemon-mirror');
    // a tested device goes to its base's pages, and says what its base needs
    fireEvent.click(screen.getByRole('radio', { name: 'Google Pixel Tablet' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenLastCalledWith('/android');
    expect(screen.getByText('Already installed on Google Pixel Tablet?')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Finish setting up' }).getAttribute('href')).toBe('/android/set-up');
    expect(screen.getAllByText(INSTALLERS['generic-android']!.picker!.needs[0]!).length).toBeGreaterThan(0);
    // the summary and install tags reach screen readers as the card's description
    const s1 = screen.getByRole('radio', { name: 'Aqara S1 Plus' });
    const described = (s1.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent ?? '').join(' ');
    expect(described).toMatch(/The reference frame\./);
    expect(described).toMatch(/Medium install.*Reversible.*Modifies Android/);
  });

  it('a tested device says who tested it and on what, to screen readers too (#700)', () => {
    render(<Picker />);
    const tab = screen.getByRole('radio', { name: 'Samsung Galaxy Tab A 10.1 (2016)' });
    expect(tab).toHaveProperty('disabled', false);
    const described = (tab.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent ?? '').join(' ');
    expect(described).toContain('Tested by the community on LineageOS');
    fireEvent.click(tab);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenLastCalledWith('/android');
  });

  it('labels the Mirror reversible to the community image, never red "Not reversible" (issue 146)', () => {
    render(<Picker />);
    expect(screen.getAllByText("Reversible to the community image, not to lululemon's software").length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toMatch(/Not reversible/);
  });

  it('during an install: says so, leads back to it, and Continue goes there too', () => {
    const store = installing();
    render(<Picker />);
    expect(screen.getByText('Kiln is still working on your Aqara S1 Plus.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Return to install' }).getAttribute('href')).toBe('/aqara-s1-plus/install');
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenCalledWith('/aqara-s1-plus/install');
    act(() => store.dispatch({ type: 'done', done: { title: 'You\'re all set', body: '' } }));
    expect(screen.queryByText(/still working/)).toBeNull();
  });

  it('keeps the last stopped run\'s details', () => {
    const store = installing();
    store.dispatch({ type: 'fail', failure: { title: 'It disconnected', body: 'Nothing was written.', wrote: false, tips: [], raw: 'x', at: 2 } });
    store.dispatch({ type: 'go', screen: 'connect' });
    render(<Picker />);
    expect(screen.getByRole('status').textContent).toMatch(/Your last attempt stopped: It disconnected\. Download log/);
  });
});

describe('the picker, calm for a first visit and a returning one (#672)', () => {
  const group = (id: string) => document.querySelector<HTMLElement>(`[aria-labelledby="${id}"]`)!;
  const names = (id: string) => [...group(id).querySelectorAll('[role=radio]')].map((r) => r.getAttribute('aria-label'));

  // the frames Kiln can't install yet, from their data: Coming soon shows only while there is one
  const soon = FRAMES.filter((f) => !(ships(f.status) && kilnRoute(f)));

  it('puts the frames Kiln installs first, and the ones to come in their own group, none of them pickable', () => {
    render(<Picker />);
    expect(screen.getByRole('heading', { name: 'Ready to install' })).toBeTruthy();
    expect(names('ready')).toEqual(expect.arrayContaining(['Aqara S1 Plus']));
    expect(names('ready').every((n) => !/not installable/.test(n ?? ''))).toBe(true);
    if (soon.length === 0) {
      expect(screen.queryByRole('heading', { name: 'Coming soon' })).toBeNull();
      return;
    }
    expect(screen.getByRole('heading', { name: 'Coming soon' })).toBeTruthy();
    expect(names('soon').length).toBe(soon.length);
    expect([...group('soon').querySelectorAll('[role=radio]')].every((r) => r.hasAttribute('disabled') || r.getAttribute('data-disabled') !== null)).toBe(true);
  });

  it('puts what you\'ll need first, keeps Continue last and pinned, and shows no "Your panel" card (the maintainer, #672)', () => {
    render(<Picker />);
    const order = [...document.querySelectorAll('[aria-labelledby="needs"], [aria-labelledby="ready"], [aria-labelledby="soon"]')].map((e) => e.getAttribute('aria-labelledby'));
    expect(order).toEqual(soon.length ? ['needs', 'ready', 'soon'] : ['needs', 'ready']);
    const section = document.querySelector('section.picker')!;
    const continueRow = screen.getByRole('button', { name: 'Continue' }).closest('.foot')!;
    expect(section.lastElementChild).toBe(continueRow);
    expect(continueRow.className).toMatch(/sticky/);
    expect(screen.queryByText('Your panel')).toBeNull();
  });

  it('says what the picked frame needs, from its data, and its way back', () => {
    render(<Picker />);
    const needs = within(group('needs'));
    expect(needs.getByText('Chrome or Edge on this computer')).toBeTruthy();
    expect(needs.getByText('A USB-C data cable, plugged straight into this computer')).toBeTruthy();
    expect(needs.getByText(/Kiln backs up the panel before it writes anything\./)).toBeTruthy();
  });

  it('offers a returning person set-up for the picked frame, and Undo only where the frame has undo routes', () => {
    render(<Picker />);
    expect(screen.getByText('Already installed on Aqara S1 Plus?')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Finish setting up' }).getAttribute('href')).toBe('/aqara-s1-plus/set-up');
    expect(screen.getByRole('link', { name: 'Undo an install' }).getAttribute('href')).toBe('/aqara-s1-plus/undo');
    fireEvent.click(screen.getByRole('radio', { name: /^lululemon Studio Mirror/ }));
    expect(screen.getByRole('link', { name: 'Finish setting up' }).getAttribute('href')).toBe('/lululemon-mirror/set-up');
    // the Mirror's in-place install keeps a restore point, with routes of its own: someone back on another day finds it
    expect(screen.getByRole('link', { name: 'Undo an install' }).getAttribute('href')).toBe('/lululemon-mirror/undo');
    expect(within(group('needs')).getByText('A micro-USB data cable')).toBeTruthy();
  });

  it('the S1\'s time on the picker is its install\'s own words, said once', () => {
    render(<Picker />);
    const needs = document.querySelector<HTMLElement>('[aria-labelledby="needs"]')!;
    expect(within(needs).getAllByText('About 10 minutes')).toHaveLength(1);
    // one source: the picker's list has no time of its own, the line comes from words.time.total
    expect(INSTALLERS['aqara-s1-plus']!.picker!.needs.join(' ')).not.toMatch(/minute/);
    expect(INSTALLERS['aqara-s1-plus']!.words?.time?.total).toBe('about 10 minutes');
  });

  it('every frame Kiln installs says what it needs and its way back (installer.json picker)', () => {
    const ready = FRAMES.filter((f) => ships(f.status) && kilnRoute(f));
    expect(ready.length).toBeGreaterThan(0);
    for (const f of ready) {
      const d = INSTALLERS[installsAs(f)]?.picker;
      expect(d?.needs.length, f.id).toBeGreaterThan(0);
      expect(d?.wayBack, f.id).toMatch(/\.$/);
    }
  });
});
