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

import { ImageSession, type ImageMaker } from '../src/device/imageSession';
import { IMAGE_DEMOS as ALL_DEMOS, imageDemo, type ImageDemo } from '../src/device/imageDemo';
import { imageLevel } from '../src/device/ImageScreens';
import { ImageFlow, DevicePage } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { MIRROR_BASE, mirrorEntry, mirrorFrame } from './mirror-frame';
import { showImage } from './device-page';
import { stackLevel } from '../src/wizard/state';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const WHOSE = /Glass Panes is made by git-nimrod\. Questions about it go to its project\./;
/** Round three (maintainer, 2026-11-19): welcoming to image authors, and no manual download path. */
// a community image's screens never weigh it against Glassworks; the choice's advice is the frame's (maintainer, 2026-11-25)
const NEVER = [/isn.t Glassworks/i, /not Glassworks/i, /can.t help/i, /yourself/i, /select the/i, /not to Glassworks/i, /Way back:/];
const IMAGE_DEMOS = ALL_DEMOS.filter((d) => d.startsWith('image-'));
/** The demos whose screen belongs to Glass Panes' install. */
const COMMUNITY = IMAGE_DEMOS.filter((d) => !d.startsWith('image-choose') && !d.startsWith('image-back'));

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
/** Each image's radio, by its name (the card's heading names it). */
const radios = () => screen.queryAllByRole('radio').map((r) => document.getElementById(r.getAttribute('aria-labelledby')!)?.textContent);
/** An image's card: its radio covers it, and its project link sits beside the radio, not inside it. */
const card = (name: string) => screen.getByRole('radio', { name }).parentElement!;
const rail = () => [...document.querySelectorAll('.steps > li')].map((li) => li.textContent);

/** As the page shows it: the install's screens, or after the first start the standard flow with the image's lines. */
async function show(d: ImageDemo) {
  return (await showImage(d)).image;
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
    async adbKey(o) {
      calls.push(`adb-key ${o.userdata.name} -> ${o.makes}`);
      // it hashes the stored userdata as it reads it: one that no longer checks out stops here, before any write
      if (fail === 'changed') { fail = undefined; throw new WrongImage('the stored userdata.sparse no longer checks out (SHA-256 00…), so Kiln didn\'t put this browser\'s key in it'); }
      return Object.assign(new Blob(['K']), { tag: o.makes });
    },
    async systemKey(o) {
      calls.push(`system-key ${o.system.name} -> ${o.makes}`);
      if (fail === 'gone') { fail = undefined; throw new DOMException('A requested file or directory could not be found', 'NotFoundError'); }
      return Object.assign(new Blob(['Y']), { tag: o.makes }); },
    async discard() { calls.push('discard'); if (fail === 'discard') throw new Error('the browser\'s storage is busy'); },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { calls.push(`read ${n} size`); await o.fastboot; return n === 'system' ? SYSTEM_BYTES : (o.userdataBytes ?? USERDATA_BYTES); },
    async flashBlob(p, blob) { if (fail === 'flash') { fail = undefined; throw new Error('flash:system: FAILed'); } calls.push(`flash ${p} ${(blob as Blob & { tag?: string }).tag}`); },
    async reboot() { calls.push('reboot'); },
  };
  const mirror = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true);
  const f = new ImageSession({ frame: mirrorFrame, simulated: true, images, refract: 'https://refract.test', search: o.search ?? '', requestFastboot: async () => fb,
    requestAdb: async () => new FirstBootMirror(), adbKey: () => 'QAAA= kiln@glassworks', apk: () => undefined, sleep: async () => {} });
  f.pick(mirrorFrame.imageFlow.recipes[0]!.id); // the full image, not the recommended in-place way
  return { f, mirror, calls };
}

describe('the choice', () => {
  it('opens on Glassworks\' own installs, the recommended way first and picked, the alternative after it; the community images are one quiet click away', async () => {
    await show('image-choose');
    expect(screen.getByRole('heading').textContent).toBe('Choose what to install on the Mirror');
    // the frame's advice (maintainer, 2026-11-25): in place keeps lululemon's software, the full image is faster
    expect(radios()).toEqual(['Glassworks on the stock software', 'Glassworks']);
    expect(screen.getByRole('radio', { name: 'Glassworks on the stock software' }).getAttribute('aria-checked')).toBe('true');
    expect(card('Glassworks on the stock software').querySelector('.pill')!.textContent).toBe('Recommended');
    expect(card('Glassworks').querySelector('.pill')!.textContent).toBe('Alternative');
    expect(card('Glassworks on the stock software').textContent).toContain('Slower, but it keeps lululemon’s software, so you can always go back.');
    expect(card('Glassworks').textContent).toContain('Faster, but there’s no way back to lululemon’s software afterward.');
    expect(screen.getByRole('radio', { name: 'Glassworks' }).getAttribute('aria-describedby')).toContain('image-glassworks-trade');
    expect(embers()).toEqual(['Continue with Glassworks on the stock software']);
    expect(screen.getByRole('radio', { name: 'Glassworks' }).querySelector('a')).toBeNull(); // no link inside the radio
    expect(card('Glassworks').querySelector('a')!.textContent).toBe('glassworks.dev');
    expect(rail()).toEqual(['Mirror', 'Choose', 'Prepare', 'Fastboot', 'Copy', 'Install', 'Set up', 'Done']);
    const more = screen.getByRole('button', { name: 'Show community images (1)' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    expect(radios()).toEqual(['Glassworks on the stock software', 'Glassworks', 'Glass Panes']);
    expect(screen.getByRole('radio', { name: 'Glassworks on the stock software' }).getAttribute('aria-checked')).toBe('true');
    expect(card('Glass Panes').textContent).toMatch(/Community image.*Its project, on GitHub/);
  });

  it('each choice says how long it usually takes, from the frame\'s words, and only where the frame gives a time', async () => {
    await show('image-choose');
    const words = mirrorFrame.words as unknown as Record<string, string | undefined>;
    const time = (name: string) => card(name).querySelector('[id$="-time"]')?.textContent;
    for (const r of mirrorFrame.imageFlow.recipes.filter((x) => !x.community)) {
      const want = words[`time.total.${r.id}`];
      expect(time(r.name), r.id).toBe(want ? `Usually ${want}` : undefined);
      // read with the card's summary
      if (want) expect(screen.getByRole('radio', { name: r.name }).getAttribute('aria-describedby')).toContain(`image-${r.id}-time`);
    }
    // every Mirror choice has its total (maintainer, 2026-11-25)
    expect(time('Glassworks on the stock software')).toBe(`Usually ${words['time.total.glassworks-in-place']}`);
    expect(time('Glassworks')).toBe('Usually about 10 minutes');
  });

  it('a community image says how long it takes too, once the community images are shown', async () => {
    await show('image-choose-panes');
    expect(card('Glass Panes').querySelector('[id$="-time"]')?.textContent).toBe('Usually about 10 minutes');
  });

  it('hiding the community images again goes back to the recommended way', async () => {
    await show('image-choose-panes');
    expect(embers()).toEqual(['Continue with Glass Panes']);
    fireEvent.click(screen.getByRole('button', { name: 'Hide community images' }));
    expect(radios()).toEqual(['Glassworks on the stock software', 'Glassworks']);
    expect(embers()).toEqual(['Continue with Glassworks on the stock software']);
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
  it('runs its recipe inside Kiln\'s safety steps: both files checked, Kiln\'s checks, the warning, this browser\'s key in its system and its userdata, then the writes', async () => {
    const { f, calls } = setup();
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    expect(f.blocked()).toMatch(/Download system\.img first/);
    await f.download();
    expect(f.state.files.map((x) => [x.name, x.state])).toEqual([['system.img', 'ok'], ['userdata.sparse', 'ok']]);
    expect(f.blocked()).toBeUndefined(); // no APK, Home Assistant or Wi-Fi: it goes on as published
    f.toFastboot();
    await f.connectFastboot(); // Kiln's checks, read-only
    expect(f.state.screen).toBe('confirm');
    await f.flash(); // not confirmed: nothing written
    expect(calls.some((c) => c.startsWith('flash'))).toBe(false);
    f.understand(true);
    await f.flash();
    expect(calls).toEqual([
      'download https://refract.test/glass-panes/mirror-android-3/system.img',
      'download https://refract.test/glass-panes/mirror-android-3/userdata.sparse',
      'read system size', 'read userdata size',
      // system: this browser's key in a copy, in place of the one its services.jar hardcodes, hashed against its pin as it is read
      'system-key system.img -> system+key',
      // userdata: this browser's key in a copy, hashed against its pin as it is read
      'adb-key userdata.sparse -> userdata+key',
      'flash system system+key', 'flash userdata userdata+key', 'reboot', 'discard',
    ]);
    expect(f.state.screen).toBe('done'); // no first start over adb: Glass Panes sets itself up
    expect(f.state.files.every((x) => x.state === 'waiting')).toBe(true); // discarded: another install downloads again
    // the backup step: the Mirror can't be read back, so the log keeps each written file's hash
    expect(f.state.log.join('\n')).toContain(`system.img: SHA-256 ${GLASS_PANES.system.sha256}`);
    expect(f.state.log.join('\n')).toContain(`userdata.sparse: SHA-256 ${GLASS_PANES_USERDATA.sha256}`);
  });

  it('a file Refract can\'t serve stops before the device, and Try again carries on from it', async () => {
    const { f, calls } = setup({ fail: 'route' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download();
    expect(f.state.files.map((x) => x.state)).toEqual(['ok', 'error']);
    expect(f.blocked()).toMatch(/userdata\.sparse/);
    f.toFastboot();
    expect(f.state.screen).toBe('download');
    await f.download();
    expect(f.state.files.map((x) => x.state)).toEqual(['ok', 'ok']);
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
    expect(f.state.files.map((x) => x.state)).toEqual(['waiting', 'waiting']);
    expect(f.blocked()).toMatch(/Download system\.img first/);
  });

  it('a stored file that is gone (another tab discarded it) must be downloaded again', async () => {
    const { f, calls } = setup({ fail: 'gone' });
    f.showCommunity(true); f.pick('glass-panes'); f.choose();
    await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state.screen).toBe('error');
    expect(calls.filter((c) => c.startsWith('flash'))).toEqual([]);
    expect(f.state.files.map((x) => x.state)).toEqual(['waiting', 'waiting']);
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
    expect(calls.filter((c) => c.startsWith('download'))).toHaveLength(2);
  });

  it.each(['image-download-error', 'image-download-bad'] as const)('%s: Kiln\'s bug, Try again and Report this, nothing to pick by hand', async (d) => {
    const flash = await show(d);
    const alert = screen.getByRole('alert').textContent!;
    expect(alert).toMatch(/Nothing was written to the Mirror — this is a problem on Kiln's side\./);
    if (d === 'image-download-bad') expect(alert).toMatch(/This download doesn’t match the published release, so Kiln discarded it\./);
    else expect(alert).toMatch(/^Kiln couldn’t download this file\. /);
    expect(alert).not.toMatch(/proxy|Refract|SHA-256/); // the plumbing stays in the technical log and the report (#672)
    const report = new URL(screen.getByRole('link', { name: 'Report this' }).getAttribute('href')!);
    expect(report.origin + report.pathname).toBe('https://github.com/GlassworksHQ/glassworks/issues/new');
    const file = d === 'image-download-bad' ? 'userdata.sparse (154952764 bytes)' : 'system.img (1074051108 bytes)';
    expect(report.searchParams.get('body')).toContain(`Image: glass-panes\nFile: ${file}\nWhat Kiln saw: `);
    expect(embers()).toEqual(['Try again']);
    expect(document.querySelector('input[type="file"]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(flash.state.files.every((x) => x.state === 'ok')).toBe(true));
  });

  it('the stock install\'s first fastboot offers the restart over USB too (a reinstall: Kiln checks the Mirror\'s own Wi-Fi first); its second doesn\'t', async () => {
    const flash = await show('place-fastboot');
    const restart = vi.spyOn(flash, 'restartToFastboot').mockResolvedValue();
    fireEvent.click(screen.getByRole('button', { name: 'Already running Glassworks or Glass Panes? Restart over USB' }));
    expect(restart).toHaveBeenCalledTimes(1);
    cleanup();
    await show('place-fastboot-again');
    expect(screen.queryByRole('button', { name: 'Already running Glassworks or Glass Panes? Restart over USB' })).toBeNull();
  });

  it('fastboot opens on the steps by hand; its restart over USB restarts the image over adb, inside the click', async () => {
    const flash = await show('image-fastboot');
    expect(screen.getByText(/hold VOL− \(SW3\)/)).toBeTruthy();
    expect(embers()).toEqual(['Connect']);
    const restart = vi.spyOn(flash, 'restartToFastboot').mockResolvedValue();
    fireEvent.click(screen.getByRole('button', { name: 'Already running Glassworks or Glass Panes? Restart over USB' }));
    expect(restart).toHaveBeenCalledTimes(1);
    expect(flash.state.screen).toBe('fastboot');
  });

  it.each(['image-link', 'image-download', 'image-confirm'] as const)('%s offers Install Glassworks instead, which drops what was downloaded', async (d) => {
    const flash = await show(d);
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks instead' }));
    expect(flash.state).toMatchObject({ screen: 'image', picked: 'glassworks' });
    expect(flash.state.files.map((x) => [x.name, x.state])).toEqual([['system.img', 'waiting']]);
  });

  it('Done says what is next in its own project, and the way back to Glassworks', async () => {
    const flash = await show('image-done');
    expect(screen.getByRole('heading').textContent).toBe('Glass Panes is on the Mirror');
    expect(screen.getByText(/^Follow the setup instructions in its README\.$/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Install Glassworks' }));
    expect(flash.state).toMatchObject({ screen: 'choose', picked: 'glassworks', from: 'glass-panes', open: false });
    expect(screen.getByRole('status').textContent).toMatch(/^Glassworks replaces Glass Panes and everything on the Mirror\./);
  });
});

describe('Glassworks\' Done', () => {
  it('has the way back: the choice, community images shown, Glass Panes picked', async () => {
    const { image: flash, app } = await showImage('flash-done');
    expect(screen.queryByText(/choose Way back/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Install Glass Panes instead' }));
    expect(flash.state).toMatchObject({ screen: 'choose', picked: 'glass-panes', from: 'glassworks', open: true, files: [{ state: 'waiting' }, { state: 'waiting' }] });
    // the page leaves Done for the install's first screen
    cleanup();
    render(<ImageFlow image={flash} app={app} />);
    expect(radios()).toEqual(['Glassworks on the stock software', 'Glassworks', 'Glass Panes']);
    expect(embers()).toEqual(['Continue with Glass Panes']);
  });
});

describe('?image=<id>', () => {
  it('?image=glass-panes skips the choice and opens Glass Panes\' install; nothing downloads before the click', async () => {
    const { f, calls } = setup({ search: '?image=glass-panes' });
    expect(f.state).toMatchObject({ screen: 'download', picked: 'glass-panes', link: true });
    expect(calls).toEqual([]);
    render(<ImageFlow image={f} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('x'); } }, true)} />);
    expect(radios()).toEqual([]);
    expect(screen.getByRole('heading').textContent).toBe('Install Glass Panes on the Mirror');
    expect(document.querySelector('.card')!.textContent).toMatch(WHOSE);
    expect(rail()).toEqual(['Mirror', 'Download', 'Fastboot', 'Install', 'Done']);
    expect(embers()).toEqual(['Download Glass Panes']);
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(screen.getAllByText('Ready to download.')).toHaveLength(2);
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
    render(<DevicePage frame={mirrorFrame} demo={mirrorEntry} base={MIRROR_BASE} />);
    expect((await screen.findByRole('heading')).textContent).toBe('Install Glass Panes on the Mirror');
    history.replaceState(null, '', '/');
  });
});

describe('every image screen', () => {
  it.each(IMAGE_DEMOS)('%s: one ember button at most, the welcoming tone, no file picker', async (d) => {
    await show(d);
    const card = document.querySelector('.card')!;
    expect(card).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
    if ((COMMUNITY as readonly string[]).includes(d)) { expect(card.textContent).toMatch(WHOSE); expect(card.textContent).not.toMatch(/recommended|alternative/i); }
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
    for (const d of ['image-writing', 'image-done'] as const) expect(imageLevel((await imageDemo(mirrorEntry, d, true)).image.state, true)).toBe(0);
    // Glassworks' Done is the standard flow's: all four layers
    const { standard } = await showImage('flash-done');
    expect(stackLevel(standard!.store.state)).toBe(4);
  });
});
