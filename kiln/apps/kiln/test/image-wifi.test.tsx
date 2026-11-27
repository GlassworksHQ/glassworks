// @vitest-environment jsdom
// Glass Panes' Wi-Fi from Kiln (maintainer, 2026-11-19): an optional network, saved in a copy of the
// checked userdata (one file), and after the first start Wi-Fi switched on over adb. The Mirror's own
// recipe (image 3) first puts this browser's key in that userdata, in place of the published project key,
// and switches Wi-Fi on with it. The engine's other way, a recipe whose wifi-on uses the image's own
// published key (images 1 and 2), which Kiln checks, holds for that one connection and never stores, is
// tested on such a recipe here (PUBLISHED). No network: userdata goes on with only the key changed, or as
// published, and a published key is never used.
import { act, cleanup, render, screen } from '@testing-library/react';
import { GLASSWORKS_IMAGE, GLASS_PANES, GLASS_PANES_IMAGE, GLASS_PANES_USERDATA, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import type { ImageRecipe } from '@glassworks/kiln-core';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { pemToPkcs8 } from '@glassworks/adb';
import { ImageSession, readImageKey, type ImageMaker } from '../src/device/imageSession';
import { imageDemo, type ImageDemo } from '../src/device/imageDemo';
import { ImageFlow } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

/** A made-up PKCS#8 PEM: base64 of the bytes 1..6. */
const PEM = '-----BEGIN PRIVATE KEY-----\nAQIDBAUG\n-----END PRIVATE KEY-----\n';
const NET = { ssid: 'Home Net', password: 'not-a-real-password' };
const tag = (b: Blob) => (b as Blob & { tag?: string }).tag;
const BROWSER_KEY = 'QAAA= kiln@glassworks';

/** A community image like Glass Panes' images 1 and 2: its userdata as published, and Wi-Fi on with its published key. */
const KEY_FILE = { name: 'adbkey', url: 'https://raw.githubusercontent.com/example/image/0000000000000000000000000000000000000000/adbkey', refract: 'example/adbkey', size: PEM.length, sha256: 'a'.repeat(64) };
const [SYSTEM, USERDATA] = GLASS_PANES_IMAGE.files as [ImageRecipe['files'][number], ImageRecipe['files'][number]];
const PUBLISHED: ImageRecipe = {
  ...GLASS_PANES_IMAGE,
  files: [SYSTEM, USERDATA, KEY_FILE],
  steps: [
    { do: 'download', file: SYSTEM.name }, { do: 'download', file: USERDATA.name }, { do: 'download', file: KEY_FILE.name }, { do: 'fastboot' },
    { do: 'wifi', file: USERDATA.name, template: SYSTEM.name, makes: 'userdata+wifi' },
    { do: 'flash', partition: 'system', file: SYSTEM.name }, { do: 'flash', partition: 'userdata', file: 'userdata+wifi' },
    { do: 'restart' }, { do: 'wifi-on', key: KEY_FILE.name },
  ],
};

function setup(o: { keyChanged?: boolean; wifiOn?: 'enabled' | 'disabled'; adbFails?: number; userdataWriteFails?: boolean; failConnect?: number; userdataBytes?: number;
  /** the Mirror's own recipe (image 3: this browser's key), not PUBLISHED */
  own?: boolean } = {}) {
  const calls: string[] = [];
  const keys: Uint8Array[] = [];
  let adbFails = o.adbFails ?? 0;
  let userdataWriteFails = !!o.userdataWriteFails;
  let connects = 0;
  const images: ImageMaker = {
    async fetch() {},
    async download(url) { calls.push(`download ${url.split('/').pop()}`); },
    async file(name) {
      calls.push(`file ${name}`);
      return Object.assign(new Blob([name === 'adbkey' ? PEM : name]), { tag: name });
    },
    async check(blob, want) {
      calls.push(`check ${tag(blob)}`);
      if (o.keyChanged && want.name === 'adbkey') throw new WrongImage('its SHA-256 differs from the one Kiln pins');
    },
    async build() { throw new Error('not for Glass Panes'); },
    async wifi(w) { calls.push(`wifi ${w.userdata.name}${w.made ? ` (on ${w.made})` : ''} from ${w.system.name} -> ${w.makes}`); expect(w.wifi).toEqual(NET); return Object.assign(new Blob(['W']), { tag: w.makes }); },
    async adbKey(k) { calls.push(`adb-key ${k.userdata.name} -> ${k.makes}`); expect(k.adbKey).toBe(BROWSER_KEY); return Object.assign(new Blob(['K']), { tag: k.makes }); },
    async systemKey(k) { calls.push(`system-key ${k.system.name} -> ${k.makes}`); expect(k.adbKey).toBe(BROWSER_KEY); return Object.assign(new Blob(['Y']), { tag: k.makes }); },
    async discard() { calls.push('discard'); },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? SYSTEM_BYTES : (o.userdataBytes ?? USERDATA_BYTES); },
    async flashBlob(p, blob) {
      if (p === 'userdata' && userdataWriteFails) { userdataWriteFails = false; throw new Error('flash:userdata: FAILed (remote: write failed)'); }
      calls.push(`flash ${p} ${tag(blob)}`);
    },
    async reboot() { calls.push('reboot'); },
  };
  const shell = {
    async close() { calls.push('adb closed'); },
    async run(c: string) {
      calls.push(`adb ${c}`);
      if (c === 'svc wifi enable' && adbFails > 0) { adbFails--; throw new Error('the connection to the Mirror closed'); }
      return { out: c === 'getprop sys.boot_completed' ? '1' : c === 'dumpsys wifi' ? `Wi-Fi is ${o.wifiOn ?? 'enabled'}\n` : '', code: 0 };
    },
    async push() { throw new Error('nothing is pushed'); },
  };
  const f = new ImageSession({ frame: mirrorFrame, simulated: true, ...(o.own ? {} : { recipes: [GLASSWORKS_IMAGE, PUBLISHED] }),
    images, refract: 'https://refract.test', search: '?image=glass-panes', requestFastboot: async () => fb,
    requestAdb: async () => {
      if (!o.own) throw new Error('this image doesn\'t trust this browser\'s key');
      if (++connects === o.failConnect) throw new Error('No device selected.');
      calls.push('connect with this browser\'s key'); return shell;
    },
    adbWithKey: (k: Uint8Array) => { keys.push(k); return async () => {
      if (++connects === o.failConnect) throw new Error('No device selected.');
      calls.push('connect with the image\'s key'); return shell;
    }; },
    adbKey: () => BROWSER_KEY, apk: () => undefined, sleep: async () => {},
  });
  return { f, calls, keys };
}

const install = async (f: ImageSession) => { await f.download(); f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash(); };

describe('with a Wi-Fi network', () => {
  it('saves it in a copy of the checked userdata, writes that, then switches Wi-Fi on with the image\'s own key', async () => {
    const { f, calls, keys } = setup();
    f.setWifi(NET);
    await install(f);
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    expect(calls).toEqual([
      'download system.img', 'download userdata.sparse', 'download adbkey',
      // after Kiln's checks and the warning: every published file it writes checked before the first write
      'file system.img', 'check system.img',
      // the key checked and held now (the restart deletes the downloads)
      'file adbkey', 'check adbkey',
      'wifi userdata.sparse from system.img -> userdata+wifi', // which hashes userdata.sparse as it reads it
      'flash system system.img',
      'flash userdata userdata+wifi',
      'reboot', 'discard',
      'connect with the image\'s key',
      'adb getprop sys.boot_completed', 'adb svc wifi enable', 'adb dumpsys wifi', 'adb closed',
    ]);
    expect(keys).toEqual([pemToPkcs8(PEM)]);
    expect([...keys[0]!]).toEqual([1, 2, 3, 4, 5, 6]);
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: 'on' });
  });

  it('never logs the network or its password', async () => {
    const { f } = setup();
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    const log = f.state.log.join('\n');
    expect(log).toContain('Saving the Wi-Fi network you entered');
    expect(log).toContain(`userdata.sparse: SHA-256 ${GLASS_PANES_USERDATA.sha256} as published, with the Wi-Fi network saved in it`);
    expect(log).not.toContain('Home Net');
    expect(log).not.toContain('not-a-real-password');
  });

  it('uses the key for one connection only: a second Connect has none', async () => {
    const { f } = setup();
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    f.state = { ...f.state, screen: 'adb' };
    await f.connectAdb();
    expect(f.state.connectError).toMatch(/no key for this image/);
  });

  it('a key that doesn\'t match its pin stops the install before anything is written', async () => {
    const { f, calls } = setup({ keyChanged: true });
    f.setWifi(NET);
    await install(f);
    expect(f.state.screen).toBe('error');
    expect(calls.filter((c) => c.startsWith('flash') || c.startsWith('wifi'))).toEqual([]);
    expect(f.state.files.every((x) => x.state === 'waiting')).toBe(true); // download again
  });

  it('when Android doesn\'t confirm Wi-Fi on, Done says to switch it on once', async () => {
    const { f } = setup({ wifiOn: 'disabled' });
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    expect(f.state.communityWifi).toBe('unconfirmed');
  });

  it('a retry after a refused write, with the network cleared, writes userdata as published, not the earlier copy', async () => {
    const { f, calls } = setup({ userdataWriteFails: true });
    f.setWifi(NET);
    await install(f);
    expect(f.state.screen).toBe('error');
    f.retry();
    f.setWifi({ ssid: '', password: '' });
    calls.length = 0;
    f.toFastboot(); await f.connectFastboot(); f.understand(true); await f.flash();
    expect(calls.filter((c) => c.startsWith('flash') || c.startsWith('wifi'))).toEqual(['flash system system.img', 'flash userdata userdata.sparse']);
    expect(f.state.screen).toBe('done');
  });

  it('a failed Wi-Fi-on after the install offers Connect again with the same key, not a reinstall', async () => {
    const { f, calls, keys } = setup({ adbFails: 1 });
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'error', wifiOnFailed: true });
    render(<ImageFlow image={f} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('x'); } }, true)} />);
    expect(screen.getByRole('button', { name: 'Connect again' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Back to the download' })).toBeNull();
    cleanup();
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: 'on', wifiOnFailed: false });
    expect(keys).toHaveLength(1); // the key was read once, before the restart
    expect(calls.filter((c) => c === 'adb svc wifi enable')).toHaveLength(2);
    expect(calls.filter((c) => c === 'adb closed')).toHaveLength(2); // each attempt releases the Mirror
  });

  it('a Connect again that is refused says why, on the error screen, and stays offered', async () => {
    const { f } = setup({ adbFails: 1, failConnect: 2 }); // wifi-on fails, then the retry's connection is refused
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'error', wifiOnFailed: true });
    await f.connectAdb(); // Connect again: the browser's list is closed with nothing picked
    expect(f.state).toMatchObject({ screen: 'error', wifiOnFailed: true, connectError: 'No device selected.' });
    render(<ImageFlow image={f} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('x'); } }, true)} />);
    expect(screen.getByText('No device selected.').closest('[role=alert]')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Connect again' })).toBeTruthy();
    cleanup();
    await f.connectAdb(); // and a third try works
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: 'on' });
  });

  it('a userdata partition too small for Glass Panes\' userdata stops at fastboot with a network saved too', async () => {
    const { f, calls } = setup({ userdataBytes: 4 * 1024 ** 3 });
    f.setWifi(NET);
    await f.download(); f.toFastboot(); await f.connectFastboot();
    expect(f.state.screen).toBe('fastboot');
    expect(f.state.fastboot?.checks.at(-1)).toMatchObject({ what: 'Userdata partition for Glass Panes', ok: false });
    f.understand(true); await f.flash();
    expect(calls.filter((c) => c.startsWith('flash') || c.startsWith('wifi'))).toEqual([]);
  });

  it('refuses a password WPA can\'t take before fastboot', () => {
    const { f } = setup();
    f.state = { ...f.state, files: f.state.files.map((x) => ({ ...x, state: 'ok' as const })) };
    f.setWifi({ ssid: 'Home Net', password: 'short' });
    expect(f.blocked()).toMatch(/8 to 63 characters/);
    f.toFastboot();
    expect(f.state.screen).toBe('download');
    f.setWifi({ password: '' }); // an open network
    expect(f.blocked()).toBeUndefined();
  });
});

describe('without a network', () => {
  it('goes on exactly as published: no wifi step, no key used, Done with no Connect', async () => {
    const { f, calls, keys } = setup();
    await install(f);
    expect(calls).toEqual([
      'download system.img', 'download userdata.sparse', 'download adbkey',
      'file system.img', 'check system.img', 'file userdata.sparse', 'check userdata.sparse',
      'flash system system.img', 'flash userdata userdata.sparse',
      'reboot', 'discard',
    ]);
    expect(keys).toEqual([]);
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: undefined });
  });
});

describe('the Mirror\'s own recipe (Glass Panes image 3): this browser\'s key in place of the published one', () => {
  it('with a network: the key goes in a copy of the checked userdata, the network on that copy, then Wi-Fi on with this browser\'s key', async () => {
    const { f, calls, keys } = setup({ own: true });
    f.setWifi(NET);
    await install(f);
    expect(f.state.screen).toBe('adb');
    await f.connectAdb();
    expect(calls).toEqual([
      'download system.img', 'download userdata.sparse',
      'system-key system.img -> system+key', // which hashes system.img as it reads it
      'adb-key userdata.sparse -> userdata+key', // which hashes userdata.sparse as it reads it
      'wifi userdata.sparse (on userdata+key) from system.img -> userdata+wifi',
      'flash system system+key',
      'flash userdata userdata+wifi',
      'reboot', 'discard',
      'connect with this browser\'s key',
      'adb getprop sys.boot_completed', 'adb svc wifi enable', 'adb dumpsys wifi', 'adb closed',
    ]);
    expect(keys).toEqual([]); // no published key, ever
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: 'on' });
    const log = f.state.log.join('\n');
    expect(log).toContain(`userdata.sparse: SHA-256 ${GLASS_PANES_USERDATA.sha256} as published, with this browser's adb key in place of the published one and the Wi-Fi network saved in it`);
    expect(log).toContain(`system.img: SHA-256 ${GLASS_PANES.system.sha256} as published, with this browser's adb key in place of the one it lets in without asking`);
    expect(log).toContain('Switching Wi-Fi on, with this browser\'s key: svc wifi enable');
    expect(log).not.toContain('Home Net');
  });

  it('without a network: the key alone, written; Done with no Connect', async () => {
    const { f, calls } = setup({ own: true });
    await install(f);
    expect(calls).toEqual([
      'download system.img', 'download userdata.sparse',
      'system-key system.img -> system+key',
      'adb-key userdata.sparse -> userdata+key',
      'flash system system+key', 'flash userdata userdata+key',
      'reboot', 'discard',
    ]);
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: undefined });
  });

  it('a Connect that is refused says why and can be tried again; each try releases the Mirror', async () => {
    const { f, calls } = setup({ own: true, failConnect: 1 });
    f.setWifi(NET);
    await install(f);
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'adb', connectError: 'No device selected.' });
    await f.connectAdb();
    expect(f.state).toMatchObject({ screen: 'done', communityWifi: 'on' });
    expect(calls.filter((c) => c === 'adb closed')).toHaveLength(1);
  });
});

describe('the screens', () => {
  const show = async (d: ImageDemo) => {
    const { image: flash, app: mirror } = await imageDemo(mirrorEntry, d, true);
    render(<ImageFlow image={flash} app={mirror} />);
    await act(async () => {});
    return flash;
  };

  it('the download screen offers an optional network, joined by itself after the first start', async () => {
    await show('image-wifi');
    expect(screen.getByText('Wi-Fi (optional)')).toBeTruthy();
    expect(screen.getByText(/The Mirror joins it by itself after its first start\. Leave it empty to set up Wi-Fi in Glass Panes instead\./)).toBeTruthy();
    expect((screen.getByLabelText('Wi-Fi network') as HTMLInputElement).value).toBe('Home Net');
  });

  it('the install lists this browser\'s key, saving the network and switching Wi-Fi on', async () => {
    await show('image-wifi-writing');
    // every step in plain words, with the person's one click among them (docs/design/kiln/system.md)
    expect([...document.querySelectorAll('.plist .plabel')].map((x) => x.textContent)).toEqual([
      'Let only this browser connect to it', 'Remove the shared key anyone could connect with', 'Save your Wi-Fi for its first start', 'Write the new system', 'Start its app storage fresh', 'Restart the Mirror', 'Connect to the Mirror', 'Turn on its Wi-Fi',
    ]);
  });

  it('One last click says what Kiln does there', async () => {
    await show('image-adb');
    expect(screen.getByText(/Click Connect, and Kiln waits for it, then switches its Wi-Fi on\./)).toBeTruthy();
    expect(document.querySelector('.ask')!.textContent).toContain('Pick Android, which is the Mirror');
  });

  it('Done says Wi-Fi is on, or how to switch it on once', async () => {
    await show('image-done-wifi');
    expect(screen.getByText(/The Mirror joins your Wi-Fi a few minutes after it starts\./)).toBeTruthy();
    cleanup();
    await show('image-done-wifi-unconfirmed');
    expect(screen.getByText(/Android didn't confirm Wi-Fi is on — switch it on once in Settings inside Glass Panes/)).toBeTruthy();
  });
});

describe('readImageKey, the one loader for a recipe\'s published key', () => {
  it('checks the very Blob it reads against the pin, then gives the PKCS#8 bytes', async () => {
    const seen: string[] = [];
    const images = { async file(n: string) { seen.push(`file ${n}`); return Object.assign(new Blob([PEM]), { tag: n }); }, async check(b: Blob) { seen.push(`check ${tag(b)}`); } };
    const want = { name: 'adbkey', url: 'https://example.com/adbkey', refract: 'x/adbkey', size: PEM.length, sha256: 'a'.repeat(64) };
    expect([...await readImageKey(images, want)]).toEqual([1, 2, 3, 4, 5, 6]);
    expect(seen).toEqual(['file adbkey', 'check adbkey']);
    await expect(readImageKey({ ...images, async check() { throw new WrongImage('not the pinned key'); } }, want)).rejects.toBeInstanceOf(WrongImage);
  });
});

it('the session works without a key route: Done tells the person to switch Wi-Fi on once', async () => {
  const mirror = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('x'); } }, true);
  void mirror;
  const { f } = setup();
  f.setWifi(NET);
  await install(f);
  (f as unknown as { imageConnect?: unknown }).imageConnect = undefined;
  await f.connectAdb();
  expect(f.state.connectError).toMatch(/Turn Wi-Fi on in the Mirror's Settings once instead/);
});
