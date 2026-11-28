// @vitest-environment jsdom
// The Mirror's Glassworks install over fastboot: the image in one click, the stock warning before any
// write, the order of the writes, the first start over adb, the credit to Glass Panes, and a download
// Refract can't serve shown as Kiln's bug. The choice and Glass Panes: image-choice.test.tsx.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { ProxyError, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { ImageSession, type ImageMaker } from '../src/device/imageSession';
import { IMAGE_DEMOS, imageDemo } from '../src/device/imageDemo';
import { ImageFlow } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { mirrorEntry, mirrorFrame } from './mirror-frame';
import { showImage } from './device-page';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

function setup(o: { system?: number; flashFails?: number; buildFails?: boolean; proxy?: 'ok' | 'down' | 'bad'; settingsOverlay?: Uint8Array; frameworkOverlay?: Uint8Array; serial?: string; refract?: string | null } = {}) {
  let fails = o.flashFails ?? 0;
  const calls: string[] = [];
  const built: Parameters<ImageMaker['build']>[0][] = [];
  const images: ImageMaker = {
    async fetch(url, onProgress) {
      calls.push(`fetch ${url}`);
      if (o.proxy === 'down') throw new ProxyError('the download proxy answered 502: GitHub answered 503');
      onProgress(0.5); onProgress(1);
      if (o.proxy === 'bad') throw new WrongImage('its SHA-256 differs from the release');
    },
    async download(url) { calls.push(`download ${url}`); },
    async file(name) { calls.push(`file ${name}`); return new Blob([name]); },
    async check(_b, want) { calls.push(`check ${want.name}`); },
    async wifi() { throw new Error('not for Glassworks'); },
    async adbKey() { throw new Error('not for Glassworks'); },
    async systemKey() { throw new Error('not for Glassworks'); },
    async build(b, onProgress) { built.push(b); if (o.buildFails) throw new Error('No space left on the device'); calls.push(`build userdata=${b.userdataBytes} key=${b.adbKey.split(' ')[1]}${b.fill === false ? ' nofill' : ''}`); onProgress('patch', 1); return { system: new Blob(['SS']), userdata: new Blob(['U']) }; },
    async discard() { calls.push('discard'); },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; if (n === 'serialno' && o.serial) return o.serial; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? (o.system ?? SYSTEM_BYTES) : USERDATA_BYTES; },
    async flashBlob(p, blob) { if (fails > 0) { fails--; throw new Error('flash:system: FAILUnkown chunk type'); } calls.push(`flash ${p} ${blob.size}`); },
    async reboot() { calls.push('reboot'); },
  };
  const mirror = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true);
  const adb = new FirstBootMirror();
  const requestFastboot = vi.fn(async () => fb);
  const f = new ImageSession({ frame: mirrorFrame, simulated: true, images, ...(o.refract === null ? {} : { refract: o.refract ?? 'https://proxy.test' }), requestFastboot, requestAdb: async () => adb, adbKey: () => 'QAAA= kiln@glassworks', apk: () => mirror.apkBytes(), sleep: async () => {},
    ...(o.settingsOverlay ? { settings: async () => o.settingsOverlay } : {}),
    ...(o.frameworkOverlay ? { framework: async () => o.frameworkOverlay } : {}) });
  f.pick(mirrorFrame.imageFlow.recipes[0]!.id); // the full image, not the recommended in-place way
  f.choose();
  return { f, mirror, calls, adb, requestFastboot, built };
}

describe('installing Glassworks over fastboot', () => {
  it('writes the Mirror\'s id from its serial into the image, so a reflash keeps its Home Assistant device (#523)', async () => {
    const flashed = async (serial?: string) => {
      const { f, mirror, built } = setup({ serial });
      await f.download();
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
    expect(f.state.screen).toBe('image');
    expect(f.blocked()).toMatch(/system\.img/);
    await f.download(); // one button: through the proxy, checked as it arrives
    expect(f.state.files).toMatchObject([{ name: 'system.img', state: 'ok', progress: 1 }]);
    expect(f.blocked()).toMatch(/APK/);
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    expect(f.blocked()).toBeUndefined(); // Home Assistant and the Wi-Fi are set-up's, after the first start
    f.toFastboot();
    await f.connectFastboot();
    expect(f.state.screen).toBe('confirm');
    await f.flash(); // not understood yet: nothing happens
    const fetched = 'fetch https://proxy.test/glass-panes/mirror-android-3/system.img';
    expect(calls).toEqual([fetched]);
    f.understand(true);
    await f.flash();
    expect(calls).toEqual([fetched, `build userdata=${USERDATA_BYTES} key=kiln@glassworks`, 'flash system 2', 'flash userdata 1', 'reboot', 'discard']);
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    // the first start, then the standard flow over the same connection, as on every frame: the page has its screen back
    expect(f.state).toMatchObject({ screen: 'choose', firstBoot: { hidden: ['com.android.launcher3'], rotation: 3 } });
    expect(adb.cmds.some((c) => c.startsWith(`am start -n ${GLASSWORKS.activity}`))).toBe(true);
    expect(adb.cmds.filter((c) => c.includes('--es url'))).toEqual([]);
  });

  it('builds the overlays into the images; Glassworks\' Wi-Fi is set-up\'s, not the image\'s', async () => {
    const overlay = new Uint8Array([1, 2, 3]);
    const framework = new Uint8Array([4, 5, 6]);
    const { f, mirror, built } = setup({ settingsOverlay: overlay, frameworkOverlay: framework });
    await f.download();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(built).toHaveLength(1);
    expect(built[0]!.settings).toBe(overlay);
    expect(built[0]!.framework).toBe(framework);
    expect(built[0]!.wifi).toBeUndefined();
  });

  it('when Refract can\'t serve the image: Kiln\'s bug, with nothing to download by hand (#579)', async () => {
    const { f, calls } = setup({ proxy: 'down' });
    await f.download();
    expect(f.state.files[0]).toMatchObject({ state: 'error', error: expect.stringMatching(/answered 502: GitHub answered 503/) });
    expect(f.blocked()).toMatch(/Download system\.img first/);
    await f.download(); // Try again: the same route
    expect(calls).toEqual(['fetch https://proxy.test/glass-panes/mirror-android-3/system.img', 'fetch https://proxy.test/glass-panes/mirror-android-3/system.img']);
  });

  it('a file that isn\'t the release\'s is refused as Kiln\'s bug too', async () => {
    const { f } = setup({ proxy: 'bad' });
    await f.download();
    expect(f.state.files[0]).toMatchObject({ state: 'bad' });
    expect(f.blocked()).toMatch(/Download system\.img first/);
  });

  it('without Refract, every download fails as a bug to report', async () => {
    const { f, calls } = setup({ refract: null });
    await f.download();
    expect(f.state.files[0]).toMatchObject({ state: 'error', error: expect.stringMatching(/no download route/) });
    expect(calls).toEqual([]);
  });

  it('a USB drop while writing: back to the files, and installing again builds again from the loaded image', async () => {
    const { f, mirror, calls } = setup({ flashFails: 1 });
    await f.download();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    const go = async () => { f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash(); };
    await go();
    expect(f.state).toMatchObject({ screen: 'error', flashFailed: true });
    f.retry();
    expect(f.state.files[0]?.state).toBe('ok'); // nothing to download again
    await go();
    expect(f.state.screen).toBe('adb');
    expect(calls.filter((c) => c.startsWith('build'))).toHaveLength(2);
    expect(calls.filter((c) => c.startsWith('fetch'))).toHaveLength(1);
  });

  it('a failed build: back to the image, which must be downloaded again (it may be patched halfway)', async () => {
    const { f, mirror, calls } = setup({ buildFails: true });
    await f.download();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(f.state).toMatchObject({ screen: 'error', flashFailed: false });
    expect(f.state.files[0]?.state).toBe('waiting');
    expect(calls.filter((c) => c.startsWith('flash'))).toHaveLength(0);
    f.retry();
    expect(f.state.screen).toBe('image');
  });

  it('after a refused write, "Try again without FILL" builds the zeros as RAW blocks', async () => {
    const { f, mirror, calls } = setup({ flashFails: 1 });
    await f.download();
    await mirror.pickApk('kiosk.apk', fakeMirrorApk());
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    render(<ImageFlow image={f} app={mirror} />);
    expect(screen.getByText('Try compatibility write mode')).toBeTruthy();
    cleanup();
    f.retryWithoutFill();
    expect(f.state.screen).toBe('fastboot');
    await f.connectFastboot(); f.understand(true); await f.flash();
    expect(calls.filter((c) => c.startsWith('build')).at(-1)).toMatch(/ nofill$/);
    expect(f.state.screen).toBe('adb');
  });

  it('Done says when another launcher is still a home app, and its button hides it again', async () => {
    const { image: flash } = await showImage('flash-done-chooser');
    expect(flash.state.firstBoot?.home.result).toBe('chooser');
    expect(screen.getByText('Glassworks isn’t the only home screen yet')).toBeTruthy();
    expect(screen.getByText(/no touchscreen, hide the other launcher again or plug in a USB mouse/)).toBeTruthy();
  });

  it('Done says when the Glassworks build has no home screen, and offers no hiding: the launcher stays (2026-11-16)', async () => {
    const { image: flash } = await showImage('flash-done-no-home');
    expect(flash.state.firstBoot?.home).toEqual({ result: 'none', left: ['com.android.launcher3'] });
    expect(flash.state.firstBoot?.hidden).toEqual([]);
    expect(screen.getByText('This Glassworks build has no home screen')).toBeTruthy();
    expect(screen.getByText((_, el) => el?.tagName === 'DIV' && /^com\.android\.launcher3 stays its home screen\./.test(el.textContent ?? ''))).toBeTruthy();
    expect(screen.queryByText('Hide the other launcher again')).toBeNull();
    expect(screen.queryByText('Glassworks isn’t the only home screen yet')).toBeNull();
  });

  it('the fastboot card plays the Mirror\'s recovery steps and names the button to hold (#521), first: most Mirrors still run lululemon\'s software', async () => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, 'flash-fastboot', true);
    expect(flash.state.screen).toBe('fastboot');
    render(<ImageFlow image={flash} app={mirror} />);
    expect(embers()).toEqual(['Connect']);
    expect(screen.getByRole('button', { name: 'Already running Glassworks or Glass Panes? Restart over USB' })).toBeTruthy();
    expect(screen.getByRole('img', { name: /lululemon Studio Mirror in recovery mode: Unscrew the stand and the back, Lift off the back, Plug in the power cord, Plug in the micro-USB, Hold VOL−, Switch the power on\. VOL− \(SW3\)/ })).toBeTruthy();
    // the by-hand steps, numbered, each one ticked off by the person as they go
    const steps = [...document.querySelectorAll('.isteps li')].map((li) => li.querySelector('.itext')!.textContent);
    expect(steps).toHaveLength(7);
    expect(steps[2]).toMatch(/rear service hatch/);
    expect(steps[5]).toMatch(/^Press and hold VOL− \(SW3\) on the small green board at the top end: of its two buttons, the one closer to the top edge \(only VOL−; VOL\+ or both buttons start other modes\)\.$/);
    expect(steps[6]).toMatch(/^Keep holding while you switch the Mirror on/);
    expect(steps[3]).toMatch(/^Plug the power cord back in/);
    const first = screen.getByRole('button', { name: 'Step 1' });
    fireEvent.click(first);
    expect(first.getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'Step 1, done' })).toBe(first);
    // before the browser's list opens: what to pick in it
    expect(document.querySelector('.ask')!.textContent).toContain('Pick Android, which is the Mirror');
  });

  it('asks for the fastboot device inside the click, before anything is awaited', () => {
    const { f, requestFastboot } = setup();
    void f.connectFastboot();
    expect(requestFastboot).toHaveBeenCalledTimes(1);
  });

  it('a Mirror whose system partition is smaller than the image stops at fastboot, with nothing written', async () => {
    const { f, mirror, calls } = setup({ system: 0x40000000 });
    await f.download();
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
    const { image: flash } = await imageDemo(mirrorEntry, 'flash-error', true);
    expect(flash.state).toMatchObject({ screen: 'error', error: expect.stringMatching(/FAILed/) });
    render(<ImageFlow image={flash} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('x'); } }, true)} />);
    expect(screen.getByText('The Mirror is still in fastboot mode, so leave it plugged in.')).toBeTruthy();
    expect(screen.getByText('Click Try again. Kiln reconnects and picks up from fastboot.')).toBeTruthy();
    // the raw error is there for help, on request
    expect(screen.queryByText(/FAILed/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/FAILed/)).toBeTruthy();
    // one ember, Try again, on the step that stopped: fastboot, not the download two steps back
    expect(embers()).toEqual(['Try again']);
    expect(screen.getByRole('button', { name: 'Back to download' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(flash.state).toMatchObject({ screen: 'fastboot', restarted: true }); // no restart needed: Connect is the ember
    expect(embers()).toEqual(['Connect']);
    flash.state = { ...flash.state, screen: 'error', flashFailed: true };
    flash.retryWithoutFill(); // the same: still in fastboot
    expect(flash.state).toMatchObject({ screen: 'fastboot', restarted: true, noFill: true });
  });

  it('a wrong file says what happened, and that it is Kiln\'s bug', async () => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, 'flash-download-bad', true);
    expect(flash.state.files[0]).toMatchObject({ state: 'bad' });
    render(<ImageFlow image={flash} app={mirror} />);
    expect(screen.getByText(/This download doesn’t match the published release, so Kiln discarded it\. Nothing was written to the Mirror — this is a problem on Kiln's side\./)).toBeTruthy();
    expect(embers()).toEqual(['Try again']);
  });
});

describe('every flash demo screen', () => {
  it.each(IMAGE_DEMOS.filter((d) => d !== 'flash-run'))('%s renders with at most one ember button', async (d) => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, d, true);
    render(<ImageFlow image={flash} app={mirror} />);
    await act(async () => {});
    expect(document.querySelector('.card')).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('the first start\'s two endings have their demos: never let in (#672), and a first start that stopped', async () => {
    const refused = await imageDemo(mirrorEntry, 'flash-adb-refused', true);
    expect(refused.image.state).toMatchObject({ screen: 'adb', connecting: false, connectError: expect.stringMatching(/didn't let this browser in through its first start/) });
    const stopped = await imageDemo(mirrorEntry, 'flash-error-first-start', true);
    expect(stopped.image.state).toMatchObject({ screen: 'error', work: { step: 'first-boot' } });
    expect(stopped.image.tryAgainLands()).toBe('adb');
  });

  it('one button downloads the image; a failure offers Try again and Report this, never a file to fetch by hand (#579)', async () => {
    const one = await imageDemo(mirrorEntry, 'flash-image', true);
    render(<ImageFlow image={one.image} app={one.app} />);
    expect(embers()).toEqual(['Download the system image']);
    cleanup();
    const failed = await imageDemo(mirrorEntry, 'flash-download-error', true);
    render(<ImageFlow image={failed.image} app={failed.app} />);
    expect(screen.getByText(/Kiln couldn’t download this file\. Nothing was written to the Mirror/)).toBeTruthy();
    expect(embers()).toEqual(['Try again']);
    const report = new URL(screen.getByText('Report this').closest('a')!.getAttribute('href')!);
    expect(report.origin + report.pathname).toBe('https://github.com/GlassworksHQ/glassworks/issues/new');
    expect(report.searchParams.get('body')).toMatch(/Image: glassworks\nFile: system\.img \(1074051108 bytes\)\nWhat Kiln saw: the download proxy answered 502/);
    expect(document.querySelector('a[href*="releases/download"]')).toBeNull();
    expect(document.querySelector('input[type="file"][accept*=".img"]')).toBeNull();
    expect(screen.queryByText(/yourself|Select the downloaded/)).toBeNull();
  });

  it('credits Glass Panes and its developer at the download (frame.json credits), and on Done', async () => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, 'flash-image', true);
    render(<ImageFlow image={flash} app={mirror} />);
    const credit = screen.getByText((_, el) => el?.tagName === 'SPAN' && /^This image is built by git-nimrod as part of Glass Panes\.$/.test(el.textContent ?? ''));
    expect([...credit.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual(['https://github.com/git-nimrod', 'https://github.com/git-nimrod/glass-panes']);
    expect(screen.getByText(/vendor files come from Qualcomm and Quectel, via the Glass Panes release/)).toBeTruthy();
    cleanup();
    await showImage('flash-done');
    expect(screen.getByText((_, el) => el?.tagName === 'SPAN' && /^Built on Glass Panes by git-nimrod\.$/.test(el.textContent ?? ''))).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'You\'re all set' })).toBeTruthy();
    expect(screen.getByText("Reversible to the community image, not to lululemon's software.")).toBeTruthy();
  });

  it('warns that lululemon\'s software can\'t be restored before anything is written', async () => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, 'flash-confirm', true);
    render(<ImageFlow image={flash} app={mirror} />);
    expect(screen.getByText('This replaces lululemon’s software for good')).toBeTruthy();
    expect(screen.getByText(/Kiln can't create a backup before replacing it.*return to the plain Glass Panes image.*Reversible to the community image, not to lululemon's software\./s)).toBeTruthy(); // #146
    expect((screen.getByText('Install Glassworks') as HTMLButtonElement).disabled).toBe(true);
  });
});
