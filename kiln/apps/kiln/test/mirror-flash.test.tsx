// @vitest-environment jsdom
// The Mirror's install over fastboot: the image in two clicks, the stock warning before any write,
// the order of the writes, the first start over adb, the credit to Glass Panes, and the way back.
import { act, cleanup, render, screen } from '@testing-library/react';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { GLASS_PANES, GLASS_PANES_USERDATA, ProxyError, SYSTEM_BYTES, USERDATA_BYTES, checkDownload, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
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

function setup(o: { system?: number; flashFails?: number; buildFails?: boolean; proxy?: 'ok' | 'down'; settingsOverlay?: Uint8Array; serial?: string } = {}) {
  let fails = o.flashFails ?? 0;
  const calls: string[] = [];
  const built: Parameters<ImageMaker['build']>[0][] = [];
  const images: ImageMaker = {
    async load() { calls.push('load'); },
    async fetch(url, onProgress) {
      calls.push(`fetch ${url}`);
      if (o.proxy === 'down') throw new ProxyError('the download proxy answered 502: GitHub answered 503');
      onProgress(0.5); onProgress(1);
    },
    async build(b, onProgress) { built.push(b); if (o.buildFails) throw new Error('No space left on the device'); calls.push(`build userdata=${b.userdataBytes} key=${b.adbKey.split(' ')[1]}${b.fill === false ? ' nofill' : ''}`); onProgress('patch', 1); return { system: new Blob(['SS']), userdata: new Blob(['U']) }; },
    check: (file, which, onProgress) => { calls.push(`check ${which}`); return checkDownload(file, which === 'system' ? GLASS_PANES.system : GLASS_PANES_USERDATA, onProgress); },
    async discard() { calls.push('discard'); },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; if (n === 'serialno' && o.serial) return o.serial; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? (o.system ?? SYSTEM_BYTES) : USERDATA_BYTES; },
    async flashBlob(p, blob) { if (fails > 0) { fails--; throw new Error('flash:system: FAILUnkown chunk type'); } calls.push(`flash ${p} ${blob.size}`); },
    async reboot() { calls.push('reboot'); },
  };
  const mirror = new MirrorSession({ connect: async () => { throw new Error('unused'); } }, true);
  const adb = new FirstBootMirror();
  const requestFastboot = vi.fn(async () => fb);
  const f = new FlashSession({ images, imageUrl: 'https://proxy.test/glass-panes/mirror-android-2/system.img', requestFastboot, requestAdb: async () => adb, adbKey: () => 'QAAA= kiln@glassworks', apk: () => mirror.apkBytes(), sleep: async () => {},
    ...(o.settingsOverlay ? { settingsOverlay: async () => o.settingsOverlay } : {}) });
  return { f, mirror, calls, adb, requestFastboot, built };
}

const image = () => Object.assign(new Blob(['x']), { name: 'system.img' });

describe('installing Glassworks over fastboot', () => {
  it('writes the Mirror\'s id from its serial into the image, so a reflash keeps its Home Assistant device (#523)', async () => {
    const flashed = async (serial?: string) => {
      const { f, mirror, built } = setup({ serial });
      await f.downloadImage();
      await mirror.pickApk('kiosk.apk', fakeMirrorApk());
      f.toFastboot();
      await f.connectFastboot();
      f.understand(true);
      await f.flash();
      return { built, log: f.state.log.join('\n') };
    };
    // made up; core's DeviceMacPinTest derives the same bytes from it
    const withSerial = await flashed('0123456789abcdef');
    expect(withSerial.built[0]?.deviceMac).toBe('E6:79:E6:E0:96:A3');
    expect(withSerial.log).not.toContain('E6:79');
    expect((await flashed()).built[0]?.deviceMac).toBeUndefined();
  });

  it('needs the image checked and Glassworks picked before fastboot, then writes only after "I understand"', async () => {
    const { f, mirror, calls, adb } = setup();
    expect(f.blocked()).toMatch(/system\.img/);
    await f.downloadImage(); // one button: through the proxy, checked as it arrives
    expect(f.state.image).toMatchObject({ state: 'ok', progress: 1 });
    expect(f.state.proxyFailed).toBeUndefined();
    expect(f.blocked()).toMatch(/APK/);
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.setHaUrl('http://homeassistant.local:8123');
    expect(f.blocked()).toBeUndefined();
    f.toFastboot();
    await f.connectFastboot();
    expect(f.state.screen).toBe('confirm');
    await f.flash(); // not understood yet: nothing happens
    const fetched = 'fetch https://proxy.test/glass-panes/mirror-android-2/system.img';
    expect(calls).toEqual([fetched]);
    f.understand(true);
    await f.flash();
    expect(calls).toEqual([fetched, `build userdata=${USERDATA_BYTES} key=kiln@glassworks`, 'flash system 2', 'flash userdata 1', 'reboot', 'discard']);
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'done', firstBoot: { hidden: ['com.android.launcher3'], rotation: 3 } });
    expect(adb.cmds).toContain(`am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`);
  });

  it('saves the Wi-Fi and the settings overlay into the images, and refuses a password WPA can\'t take before fastboot', async () => {
    const overlay = new Uint8Array([1, 2, 3]);
    const { f, mirror, built } = setup({ settingsOverlay: overlay });
    await f.downloadImage();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.setWifi({ ssid: 'Home Net', password: 'short' });
    expect(f.blocked()).toMatch(/8 to 63 characters/);
    f.toFastboot();
    expect(f.state.screen).toBe('image'); // nothing reached fastboot
    f.setWifi({ password: 'not-a-real-password' });
    expect(f.blocked()).toBeUndefined();
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(built).toHaveLength(1);
    expect(built[0]!.wifi).toEqual({ ssid: 'Home Net', password: 'not-a-real-password' });
    expect(built[0]!.settingsOverlay).toBe(overlay);
    await f.connectAdb(); // the first boot sends the network to Glassworks too (the fallback)
    // the Technical log, and the failure report saved from it: never the network's name or password (CLAUDE.md rule 2)
    expect(f.state.log).toContain('Wi-Fi: the network you entered');
    expect(f.state.log.join('\n')).not.toContain('Home Net');
    expect(f.state.log.join('\n')).not.toContain('not-a-real-password');
  });

  it('the flash-wifi-short demo shows the refusal of a password WPA can\'t take', async () => {
    const { flash, mirror } = await flashDemo('flash-wifi-short', true);
    expect(flash.blocked()).toMatch(/8 to 63 characters/);
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByText(/The Wi-Fi password must be 8 to 63 characters/)).toBeTruthy();
  });

  it('when the proxy can\'t serve the image, says why and falls back to download-and-select', async () => {
    const { f, calls } = setup({ proxy: 'down' });
    await f.downloadImage();
    expect(f.state).toMatchObject({ image: undefined, proxyFailed: expect.stringMatching(/answered 502: GitHub answered 503/) });
    await f.selectImage(image());
    expect(f.state.image?.state).toBe('ok');
    expect(calls).toEqual(['fetch https://proxy.test/glass-panes/mirror-android-2/system.img', 'load']);
  });

  it('without a proxy, it is download-and-select from the start', async () => {
    const mirror = new MirrorSession({ connect: async () => { throw new Error('x'); } }, true);
    const f = new FlashSession({ images: {} as never, requestFastboot: async () => ({}) as never, requestAdb: async () => ({}) as never, adbKey: () => undefined, apk: () => mirror.apkBytes() });
    await f.downloadImage();
    expect(f.state.proxyFailed).toMatch(/no download proxy/);
  });

  it('a USB drop while writing: back to the files, and installing again builds again from the loaded image', async () => {
    const { f, mirror, calls } = setup({ flashFails: 1 });
    await f.downloadImage();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    const go = async () => { f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash(); };
    await go();
    expect(f.state).toMatchObject({ screen: 'error', flashFailed: true });
    f.retry();
    expect(f.state.image?.state).toBe('ok'); // nothing to select again
    await go();
    expect(f.state.screen).toBe('adb');
    expect(calls.filter((c) => c.startsWith('build'))).toHaveLength(2);
    expect(calls.filter((c) => c.startsWith('fetch'))).toHaveLength(1);
  });

  it('a failed build: back to the image, which must be selected or downloaded again (it may be patched halfway)', async () => {
    const { f, mirror, calls } = setup({ buildFails: true });
    await f.downloadImage();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state).toMatchObject({ screen: 'error', flashFailed: false });
    expect(f.state.image).toBeUndefined();
    expect(calls.filter((c) => c.startsWith('flash'))).toHaveLength(0);
    f.retry();
    expect(f.state.screen).toBe('image');
  });

  it('after a refused write, "Try again without FILL" builds the zeros as RAW blocks', async () => {
    const { f, mirror, calls } = setup({ flashFails: 1 });
    await f.downloadImage();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    render(<Flash flash={f} mirror={mirror} />);
    expect(screen.getByText('Try again without FILL')).toBeTruthy();
    cleanup();
    f.retryWithoutFill();
    expect(f.state.screen).toBe('fastboot');
    await f.connectFastboot(); f.understand(true); await f.flash();
    expect(calls.filter((c) => c.startsWith('build')).at(-1)).toMatch(/ nofill$/);
    expect(f.state.screen).toBe('adb');
  });

  it('Done says when another launcher is still a home app, and its button hides it again', async () => {
    const { flash, mirror } = await flashDemo('flash-done-chooser', true);
    expect(flash.state.firstBoot?.home.result).toBe('chooser');
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByText('Glassworks isn\'t the only home screen yet.')).toBeTruthy();
    expect(screen.getByText(/no touchscreen, so after a restart it would wait on that list/)).toBeTruthy();
  });

  it('Done says when the Glassworks build has no home screen, and offers no hiding: the launcher stays (2026-11-16)', async () => {
    const { flash, mirror } = await flashDemo('flash-done-no-home', true);
    expect(flash.state.firstBoot?.home).toEqual({ result: 'none', left: ['com.android.launcher3'] });
    expect(flash.state.firstBoot?.hidden).toEqual([]);
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByText('This Glassworks build has no home screen.')).toBeTruthy();
    expect(screen.getByText(/stays the Mirror's home screen, and the Mirror still starts to it/)).toBeTruthy();
    expect(screen.queryByText('Hide the other launcher again')).toBeNull();
    expect(screen.queryByText('Glassworks isn\'t the only home screen yet.')).toBeNull();
  });

  it('the fastboot card plays the Mirror\'s recovery steps and names the button to hold (#521)', async () => {
    const { flash, mirror } = await flashDemo('flash-fastboot', true);
    expect(flash.state.screen).toBe('fastboot');
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByRole('img', { name: /lululemon Studio Mirror in recovery mode: Take out the screws, Lift off the back, Hold VOL−, Plug in USB, then power\. VOL− \(SW3\)/ })).toBeTruthy();
    expect(screen.getByText(/hold VOL−, the one nearer the frame's edge \(SW3\)/)).toBeTruthy();
    expect(screen.getByText(/Both buttons together start a Qualcomm mode Kiln can't use/)).toBeTruthy();
  });

  it('asks for the fastboot device inside the click, before anything is awaited', () => {
    const { f, requestFastboot } = setup();
    void f.connectFastboot();
    expect(requestFastboot).toHaveBeenCalledTimes(1);
  });

  it('a Mirror whose system partition is smaller than the image stops at fastboot, with nothing written', async () => {
    const { f, mirror, calls } = setup({ system: 0x40000000 });
    await f.selectImage(image());
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
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
    expect(flash.state).toMatchObject({ screen: 'error', error: expect.stringMatching(/FAILed/) });
    render(<Flash flash={flash} mirror={new MirrorSession({ connect: async () => { throw new Error('x'); } }, true)} />);
    expect(screen.getByText(/put it in fastboot again/)).toBeTruthy();
  });

  it('a wrong file says what\'s wrong and what to do', async () => {
    const { flash } = await flashDemo('flash-wrong', true);
    expect(flash.state.image).toMatchObject({ state: 'bad', error: expect.stringMatching(/download stopped early.*Download system\.img again/) });
  });
});

describe('the way back', () => {
  it('needs both Glass Panes files, each checked against the release\'s SHA-256', async () => {
    const { f, calls } = setup();
    f.wayBack(true);
    await f.selectImage(image());
    expect(f.state.image).toMatchObject({ state: 'bad', error: expect.stringMatching(/isn't system\.img, or the download stopped early/) });
    await f.selectUserdata(Object.assign(new Blob(['y']), { name: 'userdata.sparse' }));
    expect(f.state.userdataFile).toMatchObject({ state: 'bad' });
    expect(calls).toEqual(['check system', 'check userdata']); // hashed in the worker, not on the page's thread
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
    expect(screen.getByText('Download system.img').closest('a')!.getAttribute('href')).toBe('https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/system.img');
    expect(screen.getByText('Select the downloaded system.img')).toBeTruthy();
  });

  it('credits Glass Panes and its developer at the download (frame.json credits), and on Done', async () => {
    const { flash, mirror } = await flashDemo('flash-image', true);
    render(<Flash flash={flash} mirror={mirror} />);
    const credit = screen.getByText((_, el) => el?.tagName === 'SPAN' && /^This image is built by git-nimrod as part of Glass Panes\.$/.test(el.textContent ?? ''));
    expect([...credit.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual(['https://github.com/git-nimrod', 'https://github.com/git-nimrod/glass-panes']);
    expect(screen.getByText(/vendor files belong to Qualcomm and Quectel and come from the Glass Panes release, not from Glassworks/)).toBeTruthy();
    cleanup();
    const done = await flashDemo('flash-done', true);
    render(<Flash flash={done.flash} mirror={done.mirror} />);
    expect(screen.getByText((_, el) => el?.tagName === 'SPAN' && /^Built on Glass Panes by git-nimrod\.$/.test(el.textContent ?? ''))).toBeTruthy();
    expect(screen.getByText('Glassworks is on the Mirror.')).toBeTruthy();
    expect(screen.getByText("Reversible to the community image, not to lululemon's software.")).toBeTruthy();
  });

  it('warns that lululemon\'s software can\'t be restored before anything is written', async () => {
    const { flash, mirror } = await flashDemo('flash-confirm', true);
    render(<Flash flash={flash} mirror={mirror} />);
    expect(screen.getByText('This replaces lululemon’s software for good.')).toBeTruthy();
    expect(screen.getByText(/can't be\s+restored, by Kiln or anyone.*Everything on the Mirror is replaced\./s)).toBeTruthy();
    expect(screen.getByText("Reversible to the community image, not to lululemon's software.")).toBeTruthy(); // #146
    expect((screen.getByText('Install Glassworks') as HTMLButtonElement).disabled).toBe(true);
  });
});
