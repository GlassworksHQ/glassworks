// @vitest-environment jsdom
// /android (#403): open to everyone (beta; the release, test/android-release.test.tsx; ?apk=local, a file), and every demo screen on the real generic recipe behind a
// simulated device, as the approved mockup draws them.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { FakeAndroid, fakeGenericApk } from '@glassworks/generic-android-installer/fakes';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { registry } from '../src/wizard/registry';
import { android, ANDROID_BASE, ANDROID_DEMOS, AndroidActivity, type AndroidDemo, androidDemo, androidMode, AndroidSession, AndroidWizard, keptAndroid, SCREEN_LOCK_HELP, SCREEN_LOCK_LINE } from './android-frame';
import { Screens } from '../src/device/UsbPage';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); });

const until = async (f: () => boolean, ms = 10_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await act(() => new Promise((r) => setTimeout(r, 20)));
  expect(f()).toBe(true);
};
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);
const heading = () => document.querySelector('h2')?.textContent ?? '';
const rail = () => [...document.querySelectorAll('.steps > li')].map((li) => `${li.textContent}:${li.className}`);

async function show(name: AndroidDemo, ready: string | RegExp) {
  const s = await androidDemo(name, true);
  render(<Screens session={s} />);
  await until(() => (typeof ready === 'string' ? heading() === ready : ready.test(document.body.textContent ?? '')));
  return s;
}

describe('the switch', () => {
  it('is on for everyone, the release by default (beta), and demos only where demos run', () => {
    expect(androidMode('', true, ANDROID_DEMOS)).toEqual({ on: true, apk: 'release' });
    expect(androidMode('?apk=local', false, ANDROID_DEMOS)).toEqual({ on: true, apk: 'local' });
    expect(androidMode('?demo=done', true, ANDROID_DEMOS)).toEqual({ on: true, demo: 'done' });
    expect(androidMode('?demo=done', false, ANDROID_DEMOS)).toEqual({ on: true, apk: 'release' }); // a production build
  });

  it('with ?apk=local, the device screen and an APK field', async () => {
    history.replaceState(null, '', '/android?apk=local');
    render(<AndroidWizard />);
    await screen.findByText('Plug in the tablet');
    expect(screen.getByText('Choose a test APK file.')).toBeTruthy(); // a file from this computer is a test build, open page or not
    expect(document.querySelector('input[type=file]')).toBeTruthy();
    expect(embers()).toEqual(['Find device']);
  });
});

describe('the screens', () => {
  it('1: a Pixel Tablet is named from USB, with its frame card', async () => {
    await show('named', 'This looks like a Pixel Tablet');
    expect(screen.getByText(/Google · tested device\./)).toBeTruthy();
    expect(document.querySelector('.fc')?.textContent).toContain('Google Pixel Tablet');
    expect(document.querySelector('.fc')?.textContent).toContain('Tested device');
    expect(embers()).toEqual(['Continue']);
  });

  it('1b: a chip maker\'s ID asks for the brand, and says why', async () => {
    await show('brand', 'Which brand is it?');
    expect(screen.getByText('It reports Allwinner, the maker of its chip, not its brand.')).toBeTruthy();
    expect([...document.querySelectorAll('.brand-pick')].map((b) => b.firstChild?.textContent))
      .toEqual(['Google Pixel', 'Samsung Galaxy', 'Amazon Fire', 'Lenovo', 'Xiaomi', 'Other']);
    expect((document.querySelector('.btn.ember') as HTMLButtonElement).disabled).toBe(true);
  });

  it('2: the brand\'s steps, its path as chips, and the sources', async () => {
    await show('debug', 'Turn on USB debugging');
    const paths = [...document.querySelectorAll('.num > li > .path')].map((p) => [...p.querySelectorAll('span')].map((x) => x.textContent));
    expect(paths).toEqual([['Settings', 'About tablet'], ['Settings', 'System', 'Developer options']]);
    expect(screen.getByText(/Tap/).textContent).toMatch(/Build number seven times/);
    expect(embers()).toEqual(['USB debugging is on']);
  });

  it('2, Other: Android\'s general steps, one path per Android version', async () => {
    await show('debug-generic', 'Turn on USB debugging');
    expect([...document.querySelectorAll('.when > li')].map((l) => l.querySelector('.small')?.textContent))
      .toEqual(['Android 9 and newer: ', 'Android 8: ', 'Android 7 and older: ']);
  });

  it('3: Allow on the tablet is the person\'s turn', async () => {
    await show('allow', 'Tap Allow on the tablet');
    expect(screen.getByText('Waiting for the tablet')).toBeTruthy();
    expect(rail()).toContain('Connect:turn');
    expect(embers()).toEqual([]);
  });

  it('4: the install line, 5 of 6 at the home screen, and each grant read back', async () => {
    await show('installing', /5 of 6/);
    expect(heading()).toBe('Installing Glassworks');
    expect(document.querySelector('.plist')?.textContent).toContain('Pixel Tablet, Android 14, 64-bit');
    const grants = [...document.querySelectorAll('.grants > div')].map((g) => g.textContent);
    expect(grants).toEqual(['MicrophoneOn', 'CameraNot requested', 'Automatic locationOn', 'Show over other appsOn', 'Turn the screen offOn', 'Updates without a tapOn']);
    expect(document.querySelector('.warnbox')).toBeNull();
    expect(rail()).toContain('Install:now');
  });

  it('4b: a vendor build that refuses a grant and the home screen: said once, then one tap', async () => {
    await show('vendor', 'One tap on the tablet');
    expect(document.querySelector('.lede')?.textContent).toBe('Android asks which app to use as the home screen. Pick Glassworks, then Always.');
    expect(document.querySelector('.grants')?.textContent).toContain('Automatic locationNot allowed');
    expect(document.querySelector('.warnbox')?.textContent).toBe('Android on this tablet didn\'t allow automatic location. Glassworks still works without it — the Bluetooth proxy can\'t turn Location on automatically.');
    expect(screen.getByText('Waiting for your tap')).toBeTruthy();
    expect(rail()).toContain('Install:turn');
  });

  it('5: a home screen Kiln couldn\'t confirm: the way that works after another app got Always, not pressing Home (CodeRabbit on #735)', async () => {
    const s = await androidDemo('done', true);
    const r = s.state.result as { flow: 'install'; installed: { home: { state: string } } };
    r.installed.home.state = 'unconfirmed';
    render(<Screens session={s} />);
    await until(() => /couldn't confirm/.test(document.querySelector('.lede')?.textContent ?? ''));
    expect(document.querySelector('.lede')?.textContent).toMatch(/Kiln couldn't confirm the home screen automatically\. In Settings on the tablet, open Default apps → Home app, and choose Glassworks\./);
    expect(document.body.textContent).not.toMatch(/press Home/);
  });

  it('5: a tested device: its frame card, its page, and the setup page on the network', async () => {
    await show('done', 'You\'re all set');
    expect(screen.getByText('Glassworks is now the home screen on your Pixel Tablet.')).toBeTruthy();
    // The Pixel Tablet has a published Clarity result (frames/google-pixel-tablet/clarity, Clarity v1): no "not measured" tag.
    expect(screen.queryByText('Clarity · not measured yet')).toBeNull();
    expect(screen.getByText('Its page on glassworks.dev').getAttribute('href')).toBe('https://www.glassworks.dev/frames/google-pixel-tablet');
    expect(screen.getByText('Open setup').getAttribute('href')).toBe('http://192.0.2.10:8099/setup');
    expect(embers()).toEqual(['Open setup']);
    expect(rail().every((r) => r.endsWith(':done'))).toBe(true);
  });

  it('5: Done says what Kiln did with silent updates, in its own block (issue 422)', async () => {
    const block = () => document.querySelector('.su')?.textContent;
    await show('done', 'You\'re all set');
    expect(block()).toBe('Silent updatesOnUpdates from Home Assistant install with nobody at the tablet. Kiln allowed “Install unknown apps” for Glassworks only. Undo takes it back.');
    expect(document.querySelector('.grants')).toBeNull(); // the block replaces the grants list's row
    cleanup();
    await show('done-old', 'You\'re all set');
    expect(block()).toBe('Silent updatesA tap eachAndroid 9 asks for a tap on each update. Updates still come from Home Assistant; someone taps Install on the tablet. Only a device owner skips the tap.');
    cleanup();
    await show('done-refused', 'You\'re all set');
    expect(block()).toBe('Silent updatesThis tablet said noThis tablet\'s software keeps “Install unknown apps” off. Updates still come from Home Assistant, with a tap on the tablet.');
    expect(document.querySelector('.warnbox')).toBeNull(); // said once, in the block
  });

  it('5: a tablet with a PIN: Done says someone has to unlock it, and how to turn the lock off (#522)', async () => {
    const lock = () => document.querySelector('[aria-label="Screen lock"]');
    await show('done-lock', 'You\'re all set');
    expect(lock()?.textContent).toBe(`Screen lockOn${SCREEN_LOCK_LINE} How to change it`);
    expect(screen.getByText('How to change it').getAttribute('href')).toBe(SCREEN_LOCK_HELP);
    expect(embers()).toEqual(['Open setup']);
    cleanup();
    await show('done', 'You\'re all set');
    expect(lock()).toBeNull(); // none or swipe: nothing to say
  });

  it('5: an unknown device: "untested device" and a way to report it', async () => {
    await show('done-untested', 'You\'re all set');
    expect(screen.getByText('Glassworks is installed on your Teclast P20HD.')).toBeTruthy();
    expect(screen.getByText('Untested device')).toBeTruthy();
    // the report sheet (test/report-form.test.tsx), not a bare link to GitHub
    expect(screen.getByText('Tell us how it goes').tagName).toBe('BUTTON');
  });

  it('a refused install: nothing changed, said so, with Try again', async () => {
    await show('error', 'Kiln can\'t install on this tablet');
    expect(document.querySelector('.lede')?.textContent).toMatch(/Install via USB/); // the refusal says why itself, in the lede
    expect(rail()).toContain('Install:bad');
    expect(embers()).toEqual(['Try again']);
  });

  it('undo: the app removed, its device admin first', async () => {
    const s = await show('undo-done', 'Glassworks is off the tablet');
    expect(s.state.log.join('\n')).toMatch(/device admin removed/);
    expect(rail().map((r) => r.split(':')[0])).toEqual(['Device', 'Connect', 'Undo', 'Done']);
  });

  it('an S1 with debugging on: refused before anything is written, and sent to its own page', async () => {
    const s = await show('own-frame', 'This is an Aqara S1 Plus');
    expect(screen.getByText('Go to the Aqara S1 Plus').getAttribute('href')).toBe('/aqara-s1-plus');
    expect(embers()).toEqual(['Go to the Aqara S1 Plus']);
    expect(s.state.log.join('\n')).toMatch(/Nothing was changed\./);
  });

  it('Undo an install keeps the first screen, says what happens, and goes back to installing', async () => {
    const s = await show('undo-find', 'Plug in the tablet to undo');
    expect(document.querySelector('.lede')?.textContent).toMatch(/removes Glassworks over USB/);
    expect(document.querySelector('input[type=file]')).toBeNull();
    fireEvent.click(screen.getByText('Install instead'));
    await until(() => heading() === 'Plug in the tablet');
    expect(s.state.flow).toBe('install');
  });

  it('the brand screen goes back to where it was opened', async () => {
    const s = await show('brand', 'Which brand is it?');
    fireEvent.click(screen.getByText('Back'));
    await until(() => heading() === 'Plug in the tablet');
    act(() => s.brands());
    expect(heading()).toBe('Which brand is it?');
    expect(document.querySelector('.lede')?.textContent).toBe('It reports Allwinner, the maker of its chip, not its brand.');
    s.pickBrand('other');
    act(() => s.chooseBrand());
    act(() => s.brands());
    fireEvent.click(screen.getByText('Back'));
    expect(heading()).toBe('Turn on USB debugging');
  });

  it('the APK\'s SHA-256 is shortened on screen, whole in its title', async () => {
    const s = await show('find', 'Plug in the tablet');
    const code = document.querySelector('.well code')!;
    expect(code.getAttribute('title')).toBe(s.state.apk!.sha256);
    expect(code.textContent).toBe(`${s.state.apk!.sha256.slice(0, 12)}…${s.state.apk!.sha256.slice(-12)}`);
  });

  it('Undo from Done removes what it installed', async () => {
    const s = await show('done', 'You\'re all set');
    fireEvent.click(screen.getByText('Undo'));
    await until(() => heading() === 'Glassworks is off the tablet');
    expect(s.state.result).toMatchObject({ flow: 'undo', removed: [{ package: 'dev.glassworks.generic_android' }] });
  });
});

describe('the session', () => {
  const deps = () => ({ findUsb: vi.fn(() => Promise.resolve({ vendorId: 0x04e8, productName: 'SM-X200' })), connect: vi.fn(() => Promise.resolve(new FakeAndroid())) });

  it('opens the browser\'s lists inside the click, before any await', () => {
    const d = deps();
    const s = new AndroidSession(d, true);
    void s.find();
    expect(d.findUsb).toHaveBeenCalledTimes(1);
    s.toSteps();
    void s.connect();
    expect(d.connect).toHaveBeenCalledTimes(1);
  });

  it('names a model it knows from USB, without a frame: not tested yet', async () => {
    const s = new AndroidSession(deps(), true);
    await s.pickApk('a.apk', fakeGenericApk());
    await s.find();
    expect(s.state).toMatchObject({ screen: 'named', usbName: 'SM-X200' });
    render(<Screens session={s} />);
    expect(heading()).toBe('This looks like a Galaxy Tab A8');
    expect(document.querySelector('.lede')?.textContent).toMatch(/^Samsung Galaxy · not tested yet\./);
  });

  it('refuses an APK that isn\'t Glassworks for Android, and won\'t go on without one', async () => {
    const s = new AndroidSession(deps(), true);
    expect(s.blocked()).toBe('Pick the APK file first.');
    await s.pickApk('s1.apk', fakeApk('sh.edm.s1kiosk'));
    expect(s.state.apk?.error).toBe('this is sh.edm.s1kiosk, not Glassworks for Android');
    await s.find();
    s.toSteps();
    expect(s.state.screen).toBe('named');
  });

  it('a tablet that refuses the key is asked again with no list and no click; a closed list is not (adbAsk)', async () => {
    let refusals = 2;
    const tablet = new FakeAndroid();
    const reopen = vi.fn(async () => { if (refusals-- > 0) throw new Error('No authenticator can handle the request'); return tablet; });
    const asked = new AndroidSession({ ...deps(), connect: vi.fn(async () => { refusals--; throw new Error('No authenticator can handle the request'); }), reopen, picked: () => true, sleep: async () => {} }, true);
    await asked.pickApk('a.apk', fakeGenericApk());
    await asked.find();
    asked.toSteps();
    await asked.connect();
    expect(reopen).toHaveBeenCalledTimes(2);
    expect(asked.state.log.filter((l) => /didn't let this browser in yet/.test(l))).toHaveLength(2);
    expect(asked.state.connectError).toBeUndefined();
    const closed = new AndroidSession({ ...deps(), connect: vi.fn(() => Promise.reject(new DOMException('No device selected.', 'NotFoundError'))), reopen, picked: () => false }, true);
    await closed.connect();
    expect(reopen).toHaveBeenCalledTimes(2);
    expect(closed.state.connectError).toBe('Nothing was picked. Pick the tablet in the browser\'s list, then tap Allow on it.');
  });

  it('every screen before the install has a way back: named to Find, the steps to where they came from, Allow to the steps once the ask stopped', async () => {
    const s = new AndroidSession({ ...deps(), connect: vi.fn(() => Promise.reject(new DOMException('No device selected.', 'NotFoundError'))), picked: () => false }, true);
    await s.pickApk('a.apk', fakeGenericApk());
    await s.find();
    expect(s.state.screen).toBe('named');
    s.toSteps();
    expect(s.state.screen).toBe('debug');
    s.backFromSteps();
    expect(s.state.screen).toBe('named');
    // a brand corrected from the named screen: Back from its steps is the brand list, and the list's Back is named again
    s.brands();
    s.pickBrand('other');
    s.chooseBrand();
    expect(s.state.screen).toBe('debug');
    s.backFromSteps();
    expect(s.state.screen).toBe('brand');
    s.back();
    expect(s.state.screen).toBe('named');
    s.toSteps();
    s.backFromSteps();
    expect(s.state.screen).toBe('named');
    s.restart();
    expect(s.state).toMatchObject({ screen: 'find' });
    expect(s.state.identified).toBeUndefined();
    await s.find();
    s.toSteps();
    await s.connect(); // nothing picked: the ask has stopped
    expect(s.state).toMatchObject({ screen: 'allow', connecting: false });
    s.backToSteps();
    expect(s.state).toMatchObject({ screen: 'debug' });
    expect(s.state.connectError).toBeUndefined();
  });

  it('a closed list is said plainly, and changes nothing', async () => {
    const s = new AndroidSession({ ...deps(), findUsb: () => Promise.reject(new DOMException('No device selected.', 'NotFoundError')) }, true);
    await s.find();
    expect(s.state).toMatchObject({ screen: 'find', findError: 'Nothing was picked. Plug the tablet in, then pick it in the list.' });
  });

  it('a refused plan writes nothing: the legacy APK on Android 14', async () => {
    const tablet = new FakeAndroid();
    const s = new AndroidSession({ ...deps(), connect: () => Promise.resolve(tablet) }, true);
    await s.pickApk('legacy.apk', fakeGenericApk({ app: { ...tablet.app, package: 'dev.glassworks.generic_android.legacy' } as never }));
    await s.find();
    s.toSteps();
    await s.connect();
    expect(s.state.screen).toBe('error');
    expect(s.state.error).toMatch(/Nothing was changed\.$/);
    expect(tablet.cmds.filter((c) => !/^(getprop|dumpsys package)/.test(c))).toEqual([]);
  });

  /** A tablet whose USB link can go: every command after `pull` throws, as a WebUSB transfer does once the cable is out. */
  class Pluggable extends FakeAndroid {
    gone = false;
    pullAt?: RegExp;
    close = vi.fn(async () => {});
    override async run(cmd: string) {
      if (this.pullAt?.test(cmd)) this.gone = true;
      if (this.gone) throw new Error('transferIn: the device was disconnected');
      return super.run(cmd);
    }
  }
  const ready = async (tablet: () => FakeAndroid) => {
    const connect = vi.fn(() => Promise.resolve(tablet()));
    const s = new AndroidSession({ findUsb: async () => ({ vendorId: 0x18d1, productName: 'Pixel Tablet' }), connect, sleep: async () => {}, setupTries: 1 }, true);
    await s.pickApk('a.apk', fakeGenericApk());
    await s.find();
    s.toSteps();
    return { s, connect };
  };

  it('a cable pulled mid-install: the link is let go, and Try again asks the browser for the tablet again', async () => {
    const first = new Pluggable();
    first.pullAt = /^pm install/;
    const { s, connect } = await ready(() => (connect.mock.calls.length === 1 ? first : new FakeAndroid()));
    await s.connect();
    expect(s.state.screen).toBe('error');
    expect(first.close).toHaveBeenCalledTimes(1);
    await s.connect();
    expect(connect).toHaveBeenCalledTimes(2);
    expect(s.state.screen).toBe('done');
  });

  it('a refused APK keeps the tablet: pick another on the error screen, and Try again needs no list', async () => {
    const tablet = new FakeAndroid();
    const { s, connect } = await ready(() => tablet);
    await s.pickApk('legacy.apk', fakeGenericApk({ app: { ...tablet.app, package: 'dev.glassworks.generic_android.legacy' } as never }));
    await s.connect();
    expect(s.state.error).toMatch(/Nothing was changed\.$/);
    render(<Screens session={s} />);
    expect(document.querySelector('input[type=file]')).toBeNull();
    fireEvent.click(screen.getByText('Pick another APK'));
    expect(document.querySelector('input[type=file]')).toBeTruthy();
    await act(() => s.pickApk('generic.apk', fakeGenericApk()));
    await act(async () => { fireEvent.click(screen.getByText('Try again')); });
    await until(() => heading() === 'You\'re all set');
    expect(connect).toHaveBeenCalledTimes(1);
  });

  it('Done lets the tablet go; Undo from Done asks for it again, inside the click', async () => {
    const tablet = new Pluggable();
    const { s, connect } = await ready(() => tablet);
    await s.connect();
    expect(s.state.screen).toBe('done');
    expect(tablet.close).toHaveBeenCalledTimes(1);
    void s.undoFromDone();
    expect(connect).toHaveBeenCalledTimes(2);
    await until(() => s.state.screen === 'done' && s.state.flow === 'undo');
    expect(s.state.result).toMatchObject({ flow: 'undo', removed: [{ package: 'dev.glassworks.generic_android' }] });
  });

  it('a finished session isn\'t kept: coming back after Done starts a new install', async () => {
    const done = await androidDemo('done', true);
    registry.keep(new AndroidActivity(ANDROID_BASE, android.name, done));
    const fresh = keptAndroid(() => new AndroidSession({ findUsb: vi.fn(), connect: vi.fn() }, true));
    expect(fresh.session).not.toBe(done);
    expect(fresh.session.state.screen).toBe('find');
    expect(registry.activity(ANDROID_BASE)).toBe(fresh);
    expect(keptAndroid(() => { throw new Error('a session on its way is kept'); })).toBe(fresh);
  });

  it('is kept in Kiln\'s registry: the picker\'s way back while it works, and LeaveGuard while it writes', async () => {
    const s = await androidDemo('installing', true);
    const a = new AndroidActivity(ANDROID_BASE, android.name, s);
    await until(() => s.state.screen === 'working');
    expect(a.writing()).toBe(true);
    expect(a.running()).toBe('/android?apk=local');
    expect(new AndroidActivity(ANDROID_BASE, android.name, await androidDemo('find', true)).running()).toBeUndefined();
  });
});
