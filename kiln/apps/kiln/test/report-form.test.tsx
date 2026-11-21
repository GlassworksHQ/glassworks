// @vitest-environment jsdom
// The device report sheet (src/report/ReportDialog.tsx): what Kiln read comes filled in and can be changed, the preview
// is the issue exactly as sent, and GitHub's own form is the way on whenever Kiln can't send.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { identifyBrowser } from '@glassworks/generic-android-installer';
import { BROWSERS } from '@glassworks/generic-android-installer/browsers';
import { registry } from '../src/wizard/registry';
import { Screens } from '../src/device/UsbPage';
import { ReportButton, routeTransportFor, SendError, type ReportTransport } from '../src/report/ReportDialog';
import { onDevicePrefill } from '../src/report/prefill';
import { reportDemo } from '../src/report/demo';
import { clean, FLOWS, githubUrl, issueOf, type ReportFields } from '../src/report/report';
import { androidDemo } from './android-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  // the Done page's own sheet asks Kiln's route, which isn't here: it answers as a deployment without credentials
  globalThis.fetch = (async () => new Response('{"error":"not-configured"}', { status: 503 })) as typeof fetch;
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); });

const until = async (f: () => boolean, ms = 10_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
  expect(f()).toBe(true);
};
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement;
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

function fake(o: { off?: boolean; fail?: SendError } = {}) {
  const sent: { fields: ReportFields; token: string; honeypot: string }[] = [];
  const t: ReportTransport = {
    open: async () => (o.off ? 'off' : { token: 't0' }),
    send: async (fields, token, honeypot) => {
      sent.push({ fields, token, honeypot });
      if (o.fail) throw o.fail;
      return { url: 'https://github.com/GlassworksHQ/glassworks/issues/88', number: 88 };
    },
  };
  return { t, sent };
}

/** Done for a device Kiln doesn't know. */
async function done() {
  const s = await androidDemo('done-untested', true);
  const { rerender } = render(<Screens session={s} />);
  await until(() => document.querySelector('h2')?.textContent === 'You\'re all set');
  return { s, rerender };
}

async function openFrom(prefill: Parameters<typeof ReportButton>[0]['prefill'], t: ReportTransport) {
  render(<ReportButton prefill={prefill} transport={t} />);
  fireEvent.click(screen.getByText('Tell us how it goes'));
  await screen.findByText('Tell us how it went');
}

function fillPersonalPart() {
  type('Stock or custom Android?', 'custom');
  type('Which custom OS?', 'LineageOS 21, unofficial');
  type('What worked', 'The dashboard, and @someone said it works at 192.168.1.5');
  type('What didn\'t work', 'Nothing so far.');
  type('Clarity grade, if you ran it', 'B');
}

describe('the report sheet', () => {
  it('Done on an untested device opens it with what Kiln read filled in, and nothing device-unique', async () => {
    await done();
    fireEvent.click(screen.getByText('Tell us how it goes'));
    await screen.findByText('Tell us how it went');
    expect(input('Device model').value).toMatch(/Teclast/);
    expect(input('Android version').value).toMatch(/^\d+(\.\d+)* \(API \d+\)$/);
    expect(input('ABI').value).toMatch(/arm/);
    expect((screen.getByLabelText('How you installed it') as HTMLSelectElement).value).toBe('usb');
    expect((screen.getByLabelText('Kiln got to') as HTMLSelectElement).value).toBe('done');
    // stock or custom isn't something Kiln reads: the person says
    expect((screen.getByLabelText('Stock or custom Android?') as HTMLSelectElement).value).toBe('');
    const all = [...document.querySelectorAll('.report-form input, .report-form select, .report-form textarea')].map((e) => (e as HTMLInputElement).value).join(' ');
    expect(all).not.toMatch(/\b(?:\d{1,3}\.){3}\d{1,3}\b|[0-9a-f]{2}(?::[0-9a-f]{2}){5}|serial/i);
  });

  it('every prefilled field can be changed', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10 (API 29)', abi: 'arm64-v8a', flow: 'usb', step: 'done', appVersion: '0.2.0' }, fake().t);
    for (const [label, v] of [['Device model', 'Teclast P20HD (2021)'], ['Android version', '11'], ['ABI', 'armeabi-v7a'], ['Glassworks version', '0.2.1']] as const) {
      type(label, v);
      expect(input(label).value).toBe(v);
    }
    type('How you installed it', 'sideload');
    expect((screen.getByLabelText('How you installed it') as HTMLSelectElement).value).toBe('sideload');
  });

  it('Preview asks for what\'s missing first, by field', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10', flow: 'usb' }, fake().t);
    fireEvent.click(screen.getByText('Preview'));
    expect(screen.getByText('Pick stock or custom.')).toBeTruthy();
    expect(screen.getByText('Say what worked, even if it was nothing.')).toBeTruthy();
    expect(screen.queryByText('This goes on GitHub, in public')).toBeNull();
  });

  it('the preview is the issue exactly as sent, says it\'s public, and Send links what was made', async () => {
    const { t, sent } = fake();
    await openFrom({ model: 'Teclast P20HD', android: '10 (API 29)', abi: 'arm64-v8a', flow: 'usb', step: 'done', appVersion: '0.2.0' }, t);
    fillPersonalPart();
    fireEvent.click(screen.getByText('Preview'));
    await screen.findByText('This goes on GitHub, in public');
    expect(screen.getByText(/Anyone can read it there/)).toBeTruthy();
    const shownTitle = document.querySelector('.report-title')!.textContent;
    const shownBody = document.querySelector('.report-preview pre')!.textContent;
    expect(shownBody).toContain('`@someone`'); // no ping
    expect(shownBody).toContain('(removed)'); // no address
    expect(shownBody).not.toContain('192.168.1.5');
    fireEvent.click(screen.getByText('Send it'));
    await screen.findByText('Thanks, it\'s on GitHub');
    expect(sent).toHaveLength(1);
    expect(sent[0]!.token).toBe('t0');
    expect(sent[0]!.honeypot).toBe('');
    // the route rebuilds the issue from these fields, with the same code: the same title and body
    expect(issueOf(clean(sent[0]!.fields))).toEqual({ title: shownTitle, body: shownBody });
    expect(screen.getByText('See it on GitHub').getAttribute('href')).toBe('https://github.com/GlassworksHQ/glassworks/issues/88');
    expect(screen.getByText(/issue #88/)).toBeTruthy();
  });

  it('"Open it on GitHub instead" is the template, filled in with the same fields', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10', flow: 'usb' }, fake().t);
    fillPersonalPart();
    const href = screen.getByText('Open it on GitHub instead').getAttribute('href')!;
    const url = new URL(href);
    expect(url.pathname).toBe('/GlassworksHQ/glassworks/issues/new');
    expect(url.searchParams.get('template')).toBe('device-report.yml');
    expect(url.searchParams.get('model')).toBe('Teclast P20HD');
    expect(url.searchParams.get('flow')).toBe(FLOWS.usb);
    expect(url.searchParams.get('custom_os')).toBe('LineageOS 21, unofficial');
    expect(url.searchParams.get('worked')).not.toContain('192.168.1.5');
  });

  it('when Kiln can\'t send (no credentials: 503), the preview\'s one button is GitHub\'s form', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10', flow: 'usb' }, fake({ off: true }).t);
    fillPersonalPart();
    fireEvent.click(screen.getByText('Preview'));
    await screen.findByText(/can't send reports from here right now/);
    expect(screen.queryByText('Send it')).toBeNull();
    const gh = screen.getByText('Open it on GitHub');
    expect(gh.className).toContain('ember');
    expect(gh.getAttribute('href')).toContain('template=device-report.yml');
  });

  it('on an error, it says so and offers GitHub\'s form, filled in', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10', flow: 'usb' }, fake({ fail: new SendError('GitHub didn\'t take it just now.') }).t);
    fillPersonalPart();
    fireEvent.click(screen.getByText('Preview'));
    fireEvent.click(await screen.findByText('Send it'));
    await screen.findByText('Kiln couldn\'t send it');
    expect(screen.getByText(/GitHub didn't take it just now/)).toBeTruthy();
    const gh = screen.getByText('Open it on GitHub instead');
    expect(gh.className).toContain('ember');
    expect(new URL(gh.getAttribute('href')!).searchParams.get('model')).toBe('Teclast P20HD');
  });

  it('the route\'s problems go back to the form, by field', async () => {
    await openFrom({ model: 'Teclast P20HD', android: '10', flow: 'usb' }, fake({ fail: new SendError('x', { model: 'Say which device it is.' }) }).t);
    fillPersonalPart();
    fireEvent.click(screen.getByText('Preview'));
    fireEvent.click(await screen.findByText('Send it'));
    await screen.findByText('Say which device it is.');
    expect(screen.getByText('Tell us how it went')).toBeTruthy();
  });

  it('the device\'s own browser fills in what it read: the model, the version and bitness, and that flow', () => {
    const on = identifyBrowser(BROWSERS[Object.keys(BROWSERS)[0] as keyof typeof BROWSERS]);
    const p = onDevicePrefill({ ...on, release: '14', bits: 64, modelCode: 'SM-X200' }, 'get', '1.0.0');
    expect(p).toMatchObject({ android: '14', abi: '64-bit', flow: 'browser', step: 'get', appVersion: '1.0.0' });
    expect(p.model).toMatch(/SM-X200/);
  });
});

describe('the page\'s transport', () => {
  it('gives up on a route that stops answering: open falls back to GitHub, send says it couldn\'t reach', async () => {
    const saved = globalThis.fetch;
    // a fetch that never answers until it's aborted
    globalThis.fetch = ((_: string, init: RequestInit) => new Promise((_r, reject) => init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))) as typeof fetch;
    try {
      const t = routeTransportFor(50);
      expect(await t.open()).toBe('off');
      await expect(t.send({} as ReportFields, 't', '')).rejects.toThrow('Kiln couldn\'t reach its server.');
    } finally { globalThis.fetch = saved; }
  });

  it('a body that stalls after its headers counts too', async () => {
    const saved = globalThis.fetch;
    globalThis.fetch = ((_: string, init: RequestInit) => Promise.resolve({
      ok: true, status: 201,
      json: () => new Promise((_r, reject) => init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))),
    } as unknown as Response)) as typeof fetch;
    try {
      expect(await routeTransportFor(50).open()).toBe('off');
    } finally { globalThis.fetch = saved; }
  });
});

describe('its demo states', () => {
  it('open only in a demo build, at the stage asked for', () => {
    expect(reportDemo('?report=preview', false)).toBeUndefined();
    expect(reportDemo('?demo=done-untested', true)).toBeUndefined();
    expect(reportDemo('?report=nope', true)).toBeUndefined();
    expect(reportDemo('?report=form', true)?.stage).toBe('form');
    expect(reportDemo('?report=form', true)?.fill).toBeUndefined();
    for (const s of ['preview', 'sent', 'error'] as const) expect(reportDemo(`?report=${s}`, true)?.stage).toBe(s);
    expect(reportDemo('?report=off', true)?.stage).toBe('preview');
  });

  it('&report=sent opens the sheet on its thanks', async () => {
    vi.stubEnv('NEXT_PUBLIC_KILN_DEMO', '1');
    history.replaceState(null, '', '/android?demo=done-untested&report=sent');
    render(<ReportButton prefill={{ model: 'Teclast P20HD', android: '10', flow: 'usb' }} />);
    await screen.findByText('Thanks, it\'s on GitHub', undefined, { timeout: 3000 });
    vi.unstubAllEnvs();
  });
});
