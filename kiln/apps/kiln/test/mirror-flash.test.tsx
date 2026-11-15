// @vitest-environment jsdom
// The Mirror's install over fastboot: the image in two clicks, the stock warning before any write,
// the order of the writes, the first start over adb, the credit to Glass Panes, and the way back.
import { act, cleanup, render, screen } from '@testing-library/react';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { ProxyError, SYSTEM_BYTES, USERDATA_BYTES, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { FlashSession, type ImageMaker } from '../src/mirror/flash';
import { FLASH_DEMOS, flashDemo } from '../src/mirror/flashDemo';
import { Flash } from '../src/mirror/MirrorWizard';
import { MirrorSession } from '../src/mirror/session';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

function setup(o: { system?: number; flashFails?: boolean; proxy?: 'ok' | 'down' } = {}) {
  const calls: string[] = [];
  const images: ImageMaker = {
    async load() { calls.push('load'); },
    async fetch(url, onProgress) {
      calls.push(`fetch ${url}`);
      if (o.proxy === 'down') throw new ProxyError('the download proxy answered 502: GitHub answered 503');
      onProgress(0.5); onProgress(1);
    },
    async build(b, onProgress) { calls.push(`build userdata=${b.userdataBytes} key=${b.adbKey.split(' ')[1]}`); onProgress('patch', 1); return { system: new Blob(['SS']), userdata: new Blob(['U']) }; },
    async discard() { calls.push('discard'); },
  };
  const fb: FastbootLike = {
    async getvar() { throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? (o.system ?? SYSTEM_BYTES) : USERDATA_BYTES; },
    async flashBlob(p, blob) { if (o.flashFails) throw new Error('flash:system: FAILwrite'); calls.push(`flash ${p} ${blob.size}`); },
    async reboot() { calls.push('reboot'); },
  };
  const mirror = new MirrorSession({ connect: async () => { throw new Error('unused'); } }, true);
  const adb = new FirstBootMirror();
  const requestFastboot = vi.fn(async () => fb);
  const f = new FlashSession({ images, imageUrl: 'https://proxy.test/glass-panes/mirror-android-1/system.img', requestFastboot, requestAdb: async () => adb, adbKey: () => 'QAAA= kiln@glassworks', apk: () => mirror.apkBytes(), sleep: async () => {} });
  return { f, mirror, calls, adb, requestFastboot };
}

const image = () => Object.assign(new Blob(['x']), { name: 'system.img' });

describe('installing Glassworks over fastboot', () => {
  it('needs the image checked and S1 Kiosk picked before fastboot, then writes only after "I understand"', async () => {
    const { f, mirror, calls, adb } = setup();
    expect(f.blocked()).toMatch(/system\.img/);
    await f.downloadImage(); // one button: through the proxy, checked as it arrives
    expect(f.state.image).toMatchObject({ state: 'ok', progress: 1 });
    expect(f.state.proxyFailed).toBeUndefined();
    expect(f.blocked()).toMatch(/APK/);
    await mirror.pickApk('kiosk.apk', fakeApk('sh.edm.s1kiosk'));
    f.setHaUrl('http://homeassistant.local:8123');
    expect(f.blocked()).toBeUndefined();
    f.toFastboot();
    await f.connectFastboot();
    expect(f.state.screen).toBe('confirm');
    await f.flash(); // not understood yet: nothing happens
    const fetched = 'fetch https://proxy.test/glass-panes/mirror-android-1/system.img';
    expect(calls).toEqual([fetched]);
    f.understand(true);
    await f.flash();
    expect(calls).toEqual([fetched, `build userdata=${USERDATA_BYTES} key=kiln@glassworks`, 'flash system 2', 'flash userdata 1', 'reboot', 'discard']);
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'done', firstBoot: { hidden: ['com.android.launcher3'], rotation: '270' } });
    expect(adb.cmds).toContain(`am start -n sh.edm.s1kiosk/.MainActivity --es url 'http://homeassistant.local:8123'`);
  });

  it('when the proxy can\'t serve the image, says why and falls back to download-and-select', async () => {
    const { f, calls } = setup({ proxy: 'down' });
    await f.downloadImage();
    expect(f.state).toMatchObject({ image: undefined, proxyFailed: /answered 502: GitHub answered 503/ });
    await f.selectImage(image());
    expect(f.state.image?.state).toBe('ok');
    expect(calls).toEqual(['fetch https://proxy.test/glass-panes/mirror-android-1/system.img', 'load']);
  });

  it('without a proxy, it is download-and-select from the start', async () => {
    const mirror = new MirrorSession({ connect: async () => { throw new Error('x'); } }, true);
    const f = new FlashSession({ images: {} as never, requestFastboot: async () => ({}) as never, requestAdb: async () => ({}) as never, adbKey: () => undefined, apk: () => mirror.apkBytes() });
    await f.downloadImage();
    expect(f.state.proxyFailed).toMatch(/no download proxy/);
  });

  it('asks for the fastboot device inside the click, before anything is awaited', () => {
    const { f, requestFastboot } = setup();
    void f.connectFastboot();
    expect(requestFastboot).toHaveBeenCalledTimes(1);
  });

  it('a Mirror whose system partition isn\'t the image\'s size stops at fastboot, with nothing written', async () => {
    const { f, mirror, calls } = setup({ system: 0x60000000 });
    await f.selectImage(image());
    await mirror.pickApk('kiosk.apk', fakeApk('sh.edm.s1kiosk'));
    f.toFastboot();
    await f.connectFastboot();
    expect(f.state.screen).toBe('fastboot');
    expect(f.state.fastboot?.checks[0]).toMatchObject({ what: 'System partition', ok: false });
    f.understand(true);
    await f.flash();
    expect(calls.filter((c) => c.startsWith('flash'))).toEqual([]);
  });

  it('a failed write ends on the error screen, which says how to recover', async () => {
    const { flash } = await flashDemo('flash-error', true);
    expect(flash.state).toMatchObject({ screen: 'error', error: /FAILed/ });
    render(<Flash flash={flash} mirror={new MirrorSession({ connect: async () => { throw new Error('x'); } }, true)} />);
    expect(screen.getByText(/put it in fastboot again/)).toBeTruthy();
  });

  it('a wrong file says what\'s wrong and what to do', async () => {
    const { flash } = await flashDemo('flash-wrong', true);
    expect(flash.state.image).toMatchObject({ state: 'bad', error: /download stopped early.*Download system\.img again/ });
  });
});

describe('the way back', () => {
  it('needs both Glass Panes files, each checked against the release\'s SHA-256', async () => {
    const { f } = setup();
    f.wayBack(true);
    await f.selectImage(image());
    expect(f.state.image).toMatchObject({ state: 'bad', error: /isn't system\.img, or the download stopped early/ });
    await f.selectUserdata(Object.assign(new Blob(['y']), { name: 'userdata.sparse' }));
    expect(f.state.userdataFile).toMatchObject({ state: 'bad' });
    expect(f.blocked()).toMatch(/system\.img/);
  });
});

describe('every flash demo screen', () => {
  it.each(FLASH_DEMOS.filter((d) => d !== 'flash-run'))('%s renders with at most one ember button', async (d) => {
    const { flash, mirror } = await flashDemo(d, true);
    render(<Flash flash={flash} mirror={mirror} />);
    await act(async () => {});
    expect(document.querySelector('.card')).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('one button downloads the image; the GitHub link and picker show only after the proxy failed', async () => {
    const one = await flashDemo('flash-image', true);
    render(<Flash flash={one.flash} mirror={one.mirror} />);
    expect(embers()).toEqual(['Download the system image']);
    expect(document.querySelector('a[href*="releases/download"]')).toBeNull();
    expect(screen.queryByText('Select the downloaded system.img')).toBeNull();
    cleanup();
    const failed = await flashDemo('flash-proxy-failed', true);
    render(<Flash flash={failed.flash} mirror={failed.mirror} />);
    expect(screen.getByText(/couldn't download it directly: the download proxy answered 502/)).toBeTruthy();
    expect(screen.getByText('Download system.img').closest('a')!.getAttribute('href')).toBe('https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-1/system.img');
    expect(screen.getByText('Select the downloaded system.img')).toBeTruthy();
  });

  it('credits Glass Panes and its developer at the download, and on Done', async () => {
    const { flash, mirror } = await flashDemo('flash-image', true);
    render(<Flash flash={flash} mirror={mirror} />);
    const credit = document.querySelector('[data-credit="glass-panes-image"]')!;
    expect(credit.textContent).toMatch(/community work: git-nimrod builds Glass Panes, the Mirror's Android image\. Its vendor files belong to Qualcomm and Quectel.*not from Glassworks/);
    expect([...credit.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual(['https://github.com/git-nimrod', 'https://github.com/git-nimrod/glass-panes']);
    cleanup();
    const done = await flashDemo('flash-done', true);
    render(<Flash flash={done.flash} mirror={done.mirror} />);
    const doneCredit = document.querySelector('[data-credit="glass-panes-image"]')!;
    expect(doneCredit.textContent).toMatch(/^Built on Glass Panes, the Mirror's community Android image by git-nimrod\./);
    expect([...doneCredit.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual(['https://github.com/git-nimrod/glass-panes', 'https://github.com/git-nimrod']);
    expect(screen.getByText('Glassworks is on the Mirror.')).toBeTruthy();
  });

  it('warns that lululemon\'s software can\'t be restored before anything is written', async () => {
    const { flash, mirror } = await flashDemo('flash-confirm', true);
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByText('This replaces lululemon’s software for good.')).toBeTruthy();
    expect(screen.getByText(/can't be\s+restored, by Kiln or anyone/)).toBeTruthy();
    expect((screen.getByText('Install Glassworks') as HTMLButtonElement).disabled).toBe(true);
  });
});
