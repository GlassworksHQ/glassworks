// @vitest-environment jsdom
// What goes on the Mirror (#579), on the real session: Glassworks first and picked with the community
// images one quiet click away; Glass Panes downloaded through Refract and checked, then Kiln's checks,
// the warning and its writes in the recipe's order; a download Kiln can't serve shown as Kiln's bug
// with Try again and Report this; ?image=<id> straight into an image's install; both ways back; and
// the round-three tone on every screen.
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { GLASS_PANES, GLASS_PANES_USERDATA, ProxyError, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { FlashSession, type ImageMaker } from '../src/mirror/flash';
import { FLASH_DEMOS, flashDemo, type FlashDemo } from '../src/mirror/flashDemo';
import { flashLevel } from '../src/mirror/FlashScreens';
import { Flash, MirrorWizard } from '../src/mirror/MirrorWizard';
import { MirrorSession } from '../src/mirror/session';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const WHOSE = /Glass Panes is made by git-nimrod\. Questions about it go to its project\./;
/** Round three (maintainer, 2026-11-19): welcoming to image authors, and no manual download path. */
const NEVER = [/recommended/i, /isn.t Glassworks/i, /not Glassworks/i, /can.t help/i, /yourself/i, /select the/i, /not to Glassworks/i, /Way back:/];
const IMAGE_DEMOS = FLASH_DEMOS.filter((d) => d.startsWith('image-'));
/** The demos whose screen belongs to Glass Panes' install. */
const COMMUNITY = IMAGE_DEMOS.filter((d) => !d.startsWith('image-choose') && !d.startsWith('image-back'));

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
const radios = () => screen.queryAllByRole('radio').map((r) => r.getAttribute('aria-label'));
const rail = () => [...document.querySelectorAll('.steps > li')].map((li) => li.textContent);

async function show(d: FlashDemo) {
  const { flash, mirror } = await flashDemo(d, true);
  render(<Flash flash={flash} mirror={mirror} />);
  await act(async () => {});
  return flash;
}

/** A session on fakes that records what reaches Refract, the worker and the Mirror, in order. */
function setup(o: { search?: string; fail?: 'route' | 'hash' | 'flash' | 'changed' | 'gone' | 'discard'; userdataBytes?: number; fastboot?: Promise<void> } = {}) {
  const calls: string[] = [];
  let fail = o.fail;
  const images: ImageMaker = {
    async fetch(url) { calls.push(`fetch ${url}`); },
    async download(url, want, onProgress) {
      calls.push(`download ${url}`);
      onProgress(0.5);
      if (fail === 'route' && want.name === 'userdata.sparse') { fail = undefined; throw new ProxyError('the download proxy answered 502: GitHub answered 503'); }
      if (fail === 'hash' && want.name === 'system.img') { fail = undefined; throw new WrongImage('its SHA-256 differs from the release'); }
      expect(want.sha256).toMatch(/^[0-9a-f]{64}$/);
    },
    async file(name) { calls.push(`file ${name}`); if (fail === 'gone') { fail = undefined; throw new DOMException('A requested file or directory could not be found', 'NotFoundError'); } return Object.assign(new Blob([name]), { tag: name }); },
    async check(blob, want) {
      calls.push(`check ${(blob as Blob & { tag?: string }).tag} ${want.sha256.slice(0, 8)}`);
      if (fail === 'changed' && want.name === 'userdata.sparse') { fail = undefined; throw new WrongImage('its SHA-256 is 00…, not the release\'s'); }
    },
    async build() { calls.push('build'); return { system: new Blob(['S']), userdata: new Blob(['U']) }; },
    async wifi(o) { calls.push(`wifi ${o.userdata.name} ${o.system.name}`); return Object.assign(new Blob(['W']), { tag: o.makes }); },
    async discard() { calls.push('discard'); if (fail === 'discard') throw new Error('the browser\'s storage is busy'); },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { calls.push(`read ${n} size`); await o.fastboot; return n === 'system' ? SYSTEM_BYTES : (o.userdataBytes ?? USERDATA_BYTES); },
    async flashBlob(p, blob) { if (fail === 'flash') { fail = undefined; throw new Error('flash:system: FAILed'); } calls.push(`flash ${p} ${(blob as Blob & { tag?: string }).tag}`); },
    async reboot() { calls.push('reboot'); },
  };
  const mirror = new MirrorSession({ connect: async () => { throw new Error('unused'); } }, true);
  const f = new FlashSession({ images, refract: 'https://refract.test', search: o.search ?? '', requestFastboot: async () => fb,
    requestAdb: async () => new FirstBootMirror(), adbKey: () => 'QAAA= kiln@glassworks', apk: () => undefined, sleep: async () => {} });
  return { f, mirror, calls };
}

describe('the choice', () => {
  it('opens on Glassworks alone, first and picked, with no badge; the community images are one quiet click away', async () => {
    await show('image-choose');
    expect(screen.getByRole('heading').textContent).toBe('What goes on the Mirror?');
    expect(radios()).toEqual(['Glassworks']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('radio', { name: 'Glassworks' }).querySelector('.pill')).toBeNull();
    expect(rail()).toEqual(['Mirror', 'Choose', 'Download', 'Fastboot', 'Install', 'Done']);
    const more = screen.getByRole('button', { name: 'Show community images (1)' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    expect(radios()).toEqual(['Glassworks', 'Glass Panes']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('radio', { name: 'Glass Panes' }).textContent).toMatch(/Community image.*Its project, on GitHub/);
  });

  it('hiding the community images again goes back to Glassworks', async () => {
    await show('image-choose-panes');
    expect(embers()).toEqual(['Continue with Glass Panes']);
    fireEvent.click(screen.getByRole('button', { name: 'Hide community images' }));
    expect(radios()).toEqual(['Glassworks']);
    expect(embers()).toEqual(['Continue with Glassworks']);
  });

  it('choosing downloads nothing; Continue goes to the picked image\'s first screen', () => {
    const { f, calls } = setup();
    f.showCommunity(true);
    f.pick('glass-panes');
    expect(calls).toEqual([]);
    f.choose();
    expect(f.state.screen).toBe('download');
    const g = setup();
    g.f.choose();
    expect(g.f.state.screen).toBe('image');
  });
});

describe('Glass Panes', () => {
  it('runs its recipe inside Kiln\'s safety steps: both files checked, Kiln\'s checks, the warning, then the writes as published', async () => {
    const { f, calls } = setup();
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    expect(f.blocked()).toMatch(/Download system\.img first/);
    await f.download();
    expect(f.state.files.map((x) => [x.name, x.state])).toEqual([['system.img', 'ok'], ['userdata.sparse', 'ok'], ['adbkey', 'ok']]);
    expect(f.blocked()).toBeUndefined(); // no APK, Home Assistant or Wi-Fi: it goes on as published
    f.toFastboot();
    await f.connectFastboot(); // Kiln's checks, read-only
    expect(f.state.screen).toBe('confirm');
    await f.flash(); // not confirmed: nothing written
    expect(calls.some((c) => c.startsWith('flash'))).toBe(false);
    f.understand(true);
    await f.flash();
    expect(calls).toEqual([
      'download https://refract.test/glass-panes/mirror-android-2/system.img',
      'download https://refract.test/glass-panes/mirror-android-2/userdata.sparse',
      'download https://refract.test/glass-panes/project-key/adbkey',
      'read system size', 'read userdata size',
      // every file checked again before the first write, as the very Blobs fastboot then sends
      'file system.img', 'check system.img bdb6b844', 'file userdata.sparse', 'check userdata.sparse 74b1f57a',
      'flash system system.img', 'flash userdata userdata.sparse', 'reboot', 'discard',
    ]);
    expect(f.state.screen).toBe('done'); // no first start over adb: it doesn't trust Kiln's key
    expect(f.state.files.every((x) => x.state === 'waiting')).toBe(true); // discarded: another install downloads again
    // the backup step: the Mirror can't be read back, so the log keeps each written file's hash
    expect(f.state.log.join('\n')).toContain(`system.img: SHA-256 ${GLASS_PANES.system.sha256}`);
    expect(f.state.log.join('\n')).toContain(`userdata.sparse: SHA-256 ${GLASS_PANES_USERDATA.sha256}`);
  });

  it('a file Refract can\'t serve stops before the device, and Try again carries on from it', async () => {
    const { f, calls } = setup({ fail: 'route' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download();
    expect(f.state.files.map((x) => x.state)).toEqual(['ok', 'error', 'waiting']);
    expect(f.blocked()).toMatch(/userdata\.sparse/);
    f.toFastboot();
    expect(f.state.screen).toBe('download');
    await f.download();
    expect(f.state.files.map((x) => x.state)).toEqual(['ok', 'ok', 'ok']);
    expect(calls.filter((c) => c.endsWith('system.img'))).toHaveLength(1); // not downloaded twice
  });

  it('a stored file that no longer checks out before the writes: nothing is written, not even the other partition', async () => {
    const { f, calls } = setup({ fail: 'changed' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('error');
    expect(calls.filter((c) => c.startsWith('flash'))).toEqual([]);
    expect(calls).not.toContain('reboot');
    f.retry();
    expect(f.state.files.map((x) => x.state)).toEqual(['waiting', 'waiting', 'waiting']);
    expect(f.blocked()).toMatch(/Download system\.img first/);
  });

  it('a stored file that is gone (another tab discarded it) must be downloaded again', async () => {
    const { f, calls } = setup({ fail: 'gone' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('error');
    expect(calls.filter((c) => c.startsWith('flash'))).toEqual([]);
    expect(f.state.files.map((x) => x.state)).toEqual(['waiting', 'waiting', 'waiting']);
  });

  it('a userdata partition smaller than its published userdata image stops at fastboot, with nothing written', async () => {
    const { f, calls } = setup({ userdataBytes: 4 * 1024 ** 3 });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot();
    expect(f.state.screen).toBe('fastboot');
    expect(f.state.fastboot?.checks.at(-1)).toMatchObject({ what: 'Userdata partition for Glass Panes', ok: false, need: expect.stringMatching(/at least 5583458304 bytes/) });
    f.understand(true); await f.flash();
    expect(calls.filter((c) => c.startsWith('flash'))).toEqual([]);
    // Glassworks builds its userdata to fit: the same partition is fine for it
    const g = setup({ userdataBytes: 4 * 1024 ** 3 });
    g.f.choose(); await g.f.download(); g.f.state = { ...g.f.state }; // no APK needed to read fastboot
    g.f.state.screen = 'fastboot'; await g.f.connectFastboot();
    expect(g.f.state.fastboot?.checks.some((c) => c.what.startsWith('Userdata partition for'))).toBe(false);
  });

  it('a fastboot check from before a link changed the image is ignored', async () => {
    let release!: () => void;
    const { f } = setup({ fastboot: new Promise<void>((r) => { release = r; }) });
    f.choose(); await f.download();
    f.state = { ...f.state, screen: 'fastboot' };
    const pending = f.connectFastboot(); // Glassworks' fastboot, still reading
    f.followLink('?image=glass-panes'); // the person comes back by Glass Panes' link
    release(); await pending;
    expect(f.state).toMatchObject({ screen: 'download', picked: 'glass-panes', fastboot: undefined });
    expect(f.state.files.every((x) => x.state === 'waiting')).toBe(true);
  });

  it('a download store that fails to clear after the restart is logged; the install still ends on Done', async () => {
    const { f } = setup({ fail: 'discard' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('done');
    expect(f.state.files.every((x) => x.state === 'waiting')).toBe(true);
    expect(f.state.log.join('\n')).toContain('deleting the downloads failed: the browser\'s storage is busy');
  });

  it('a failed write: back to the download with the files kept, then fastboot again', async () => {
    const { f, calls } = setup({ fail: 'flash' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('error');
    f.retry();
    expect(f.state).toMatchObject({ screen: 'download', understood: false });
    expect(f.state.files.every((x) => x.state === 'ok')).toBe(true);
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('done');
    expect(calls.filter((c) => c.startsWith('download'))).toHaveLength(3);
  });

  it.each(['image-download-error', 'image-download-bad'] as const)('%s: Kiln\'s bug, Try again and Report this, nothing to pick by hand', async (d) => {
    const flash = await show(d);
    const alert = screen.getByRole('alert').textContent!;
    expect(alert).toMatch(/Nothing was written to the Mirror\. This is a bug in Kiln, not something you did\./);
    if (d === 'image-download-bad') expect(alert).toMatch(/its SHA-256 differs\), so it deleted it/);
    else expect(alert).toMatch(/Kiln couldn’t download it: the download proxy answered 502: GitHub answered 503\./);
    const report = new URL(screen.getByRole('link', { name: 'Report this' }).getAttribute('href')!);
    expect(report.origin + report.pathname).toBe('https://github.com/GlassworksHQ/glassworks/issues/new');
    const file = d === 'image-download-bad' ? 'userdata.sparse (154944572 bytes)' : 'system.img (850905808 bytes)';
    expect(report.searchParams.get('body')).toContain(`Image: glass-panes\nFile: ${file}\nWhat Kiln saw: `);
    expect(embers()).toEqual(['Try again']);
    expect(document.querySelector('input[type="file"]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(flash.state.files.every((x) => x.state === 'ok')).toBe(true));
  });

  it.each(['image-link', 'image-download', 'image-confirm'] as const)('%s offers Install Glassworks instead, which drops what was downloaded', async (d) => {
    const flash = await show(d);
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks instead' }));
    expect(flash.state).toMatchObject({ screen: 'image', picked: 'glassworks' });
    expect(flash.state.files.map((x) => [x.name, x.state])).toEqual([['system.img', 'waiting']]);
  });

  it('Done says what is next in its own project, and the way back to Glassworks', async () => {
    const flash = await show('image-done');
    expect(screen.getByRole('heading').textContent).toBe('Glass Panes is on the Mirror.');
    expect(screen.getByText(/Set it up as its README says/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks' }));
    expect(flash.state).toMatchObject({ screen: 'choose', picked: 'glassworks', from: 'glass-panes', open: false });
    expect(screen.getByRole('status').textContent).toMatch(/^The way back from Glass Panes\. Glassworks replaces it/);
  });
});

describe('Glassworks\' Done', () => {
  it('has the way back: the choice, community images shown, Glass Panes picked', async () => {
    const flash = await show('flash-done');
    expect(screen.queryByText(/choose Way back/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Install Glass Panes instead' }));
    expect(flash.state).toMatchObject({ screen: 'choose', picked: 'glass-panes', from: 'glassworks', open: true, files: [{ state: 'waiting' }, { state: 'waiting' }, { state: 'waiting' }] });
    expect(radios()).toEqual(['Glassworks', 'Glass Panes']);
    expect(embers()).toEqual(['Continue with Glass Panes']);
  });
});

describe('?image=<id>', () => {
  it('?image=glass-panes skips the choice and opens Glass Panes\' install; nothing downloads before the click', async () => {
    const { f, calls } = setup({ search: '?image=glass-panes' });
    expect(f.state).toMatchObject({ screen: 'download', picked: 'glass-panes', link: true });
    expect(calls).toEqual([]);
    render(<Flash flash={f} mirror={new MirrorSession({ connect: async () => { throw new Error('x'); } }, true)} />);
    expect(radios()).toEqual([]);
    expect(screen.getByRole('heading').textContent).toBe('Install Glass Panes on the Mirror.');
    expect(document.querySelector('.card')!.textContent).toMatch(WHOSE);
    expect(rail()).toEqual(['Mirror', 'Download', 'Fastboot', 'Install', 'Done']);
    expect(embers()).toEqual(['Download Glass Panes']);
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(screen.getAllByText('Not downloaded yet.')).toHaveLength(3);
  });

  it.each(['', '?image=', '?image=nope', '?image=glassworks', '?image=Glass-Panes'])('%s opens the normal page', (q) => {
    const { f } = setup({ search: q });
    expect(f.state).toMatchObject({ screen: 'choose', picked: 'glassworks', link: false, open: false });
  });

  it('opens on a kept, idle session too; a session that is writing stays as it is', async () => {
    const { f } = setup();
    f.followLink('?image=glass-panes');
    expect(f.state).toMatchObject({ screen: 'download', picked: 'glass-panes', link: true });
    f.followLink('?image=nope');
    expect(f.state.picked).toBe('glass-panes');
    const busy = setup();
    busy.f.choose(); await busy.f.download();
    busy.f.toFastboot(); await busy.f.connectFastboot(); busy.f.understand(true);
    busy.f.state = { ...busy.f.state, screen: 'adb' };
    busy.f.followLink('?image=glass-panes');
    expect(busy.f.state).toMatchObject({ screen: 'adb', picked: 'glassworks' });
  });

  it('works on the page itself, as a community project would link to it', async () => {
    history.replaceState(null, '', '/lululemon-mirror?apk=local&image=glass-panes');
    render(<MirrorWizard />);
    expect((await screen.findByRole('heading')).textContent).toBe('Install Glass Panes on the Mirror.');
    history.replaceState(null, '', '/');
  });
});

describe('every image screen', () => {
  it.each(IMAGE_DEMOS)('%s: one ember button at most, the welcoming tone, no file picker', async (d) => {
    await show(d);
    const card = document.querySelector('.card')!;
    expect(card).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
    if ((COMMUNITY as readonly string[]).includes(d)) expect(card.textContent).toMatch(WHOSE);
    for (const w of NEVER) expect(document.body.textContent).not.toMatch(w);
    expect(document.querySelector('input[type="file"]')).toBeNull();
  });

  it.each(['image-choose-panes', 'image-link', 'image-download', 'image-download-error', 'image-confirm', 'image-done', 'flash-done'] as const)('%s links each GitHub address once', async (d) => {
    await show(d);
    const hrefs = [...document.querySelectorAll('.card a[href^="https://github.com/"]')].map((a) => a.getAttribute('href'));
    expect(hrefs.length).toBeGreaterThan(0);
    expect(hrefs).toEqual([...new Set(hrefs)]);
  });

  it('the stack stays empty while a community image installs: its layers are Glassworks\' layers', async () => {
    for (const d of ['image-writing', 'image-done'] as const) expect(flashLevel((await flashDemo(d, true)).flash.state, true)).toBe(0);
    expect(flashLevel((await flashDemo('flash-done', true)).flash.state, false)).toBe(4);
  });
});
