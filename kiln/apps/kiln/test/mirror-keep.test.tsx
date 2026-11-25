// @vitest-environment jsdom
// Keeping Home Assistant's key over a Mirror reflash (#580): Kiln reads it from the running app over adb before the
// flash, writes only the hand-over flag into the image, and gives it back at the first start. The key never reaches
// the state, the log, the worker or a command line. Every demo state renders with one ember button at most, the
// hand-back row shows only with a key held, and Done says what Home Assistant does.
import { AdbAuthTimeout, type PanelShell } from '@glassworks/adb';
import { FakeKeySocket } from '@glassworks/app-adb/fakes';
import { SYSTEM_BYTES, USERDATA_BYTES, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { ImageSession, type ImageMaker } from '../src/device/imageSession';
import { imageDemo } from '../src/device/imageDemo';
import { DEMO_KEY, KEEP_DEMOS, keepDemo, type KeepDemo } from '../src/device/keepDemo';
import { ImageFlow, leaveWarning } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { AUTH_MS, READ_MS, mirrorEntry, mirrorFrame } from './mirror-frame';
import { showImage } from './device-page';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const show = async (d: Awaited<ReturnType<typeof keepDemo>>) => { render(<ImageFlow image={d.image} app={d.app} />); await act(async () => {}); };
/** A Done demo as the page shows it: the standard flow's Done, with the key's line under it. */
const showDone = (d: KeepDemo | 'flash-done') => showImage(d);
const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

describe('keeping the Mirror in Home Assistant over a reflash', () => {
  it.each(KEEP_DEMOS)('%s renders with at most one ember button', async (d) => {
    await show(await keepDemo(mirrorEntry, d, true));
    expect(document.querySelector('.card')).not.toBeNull();
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('the fastboot screen opens on the steps by hand; its restart over USB is the keep step for Glassworks, which only asks for USB', async () => {
    const d = await imageDemo(mirrorEntry, 'flash-fastboot', true);
    await show(d);
    expect(screen.getByText(/hold VOL− \(SW3\)/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Already running Glassworks or Glass Panes? Restart over USB' }));
    expect(d.image.state.screen).toBe('keep');
    cleanup();
    await show(await keepDemo(mirrorEntry, 'flash-keep', true));
    expect([...document.querySelectorAll('ol.join li')].map((li) => li.textContent)).toEqual(['Plug the Mirror\'s micro-USB cable into this computer with Glassworks running.']);
    expect(screen.queryByText(/switch|unplug|screws|the back off/i)).toBeNull(); // Kiln restarts it into fastboot over adb (the Mirror run, 2026-11-19)
    expect(embers()).toEqual(['Connect']);
  });

  it('after the keep step restarted the Mirror, "Fastboot by hand" lands on Connect, not another restart', async () => {
    const d = await keepDemo(mirrorEntry, 'flash-keep-held', true);
    expect(d.image.state.restarted).toBe(true);
    d.image.skipKeep();
    expect(d.image.state).toMatchObject({ screen: 'fastboot', restarted: true });
  });

  it('the hand-back row shows only with a key held', async () => {
    await show(await keepDemo(mirrorEntry, 'flash-keep-working', true));
    expect(screen.getByText('Keep your Home Assistant connection')).toBeTruthy();
    cleanup();
    await show(await imageDemo(mirrorEntry, 'flash-working', true));
    expect(screen.queryByText('Keep your Home Assistant connection')).toBeNull();
  });

  it('Done says Same device when the key went back, Confirm once with the HA steps otherwise, nothing on a first install', async () => {
    await showDone('flash-done-kept');
    expect(screen.getByText('Same device')).toBeTruthy();
    expect(screen.queryByText('Reconfigure')).toBeNull();
    cleanup();
    for (const d of ['flash-done-asks', 'flash-done-refused'] as const) {
      await showDone(d);
      expect(screen.getByText('Confirm once')).toBeTruthy();
      expect(screen.getByText('Reconfigure')).toBeTruthy();
      cleanup();
    }
    await showDone('flash-done');
    expect(screen.queryByRole('group', { name: 'Home Assistant' })).toBeNull();
  });
});

// made up, never a real key: base64 of 32 bytes 1..32 (the demos' key)
const KEY = DEMO_KEY;

/** A session on fakes: the running Mirror before the flash (`before`), and the fresh one after it. */
async function ready(o: { before?: () => Promise<PanelShell>; after?: FirstBootMirror; flashed?: boolean } = {}) {
  const holding: boolean[] = [];
  const built: Parameters<ImageMaker['build']>[0][] = [];
  const images: ImageMaker = {
    async fetch(_, p) { p(1); }, async download(_, __, p) { p(1); }, async file(n) { return new Blob([n]); }, async check() {}, async discard() {}, async wifi() { throw new Error('not for Glassworks'); }, async adbKey() { throw new Error('not for Glassworks'); }, async systemKey() { throw new Error('not for Glassworks'); },
    async build(b, p) { built.push(b); p('patch', 1); return { system: new Blob(['S']), userdata: new Blob(['U']) }; },
  };
  const fb: FastbootLike = {
    async getvar(n) { if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? SYSTEM_BYTES : USERDATA_BYTES; },
    async flashBlob() {}, async reboot() {},
  };
  const after = o.after ?? new FirstBootMirror().withKeySocket();
  const asked: (number | undefined)[] = [];
  let flashed = !!o.flashed;
  const mirror = new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true);
  const f = new ImageSession({ frame: mirrorFrame, simulated: true,
    images, refract: 'https://proxy.test', requestFastboot: async () => fb,
    requestAdb: async (r) => { asked.push(r?.authMs); return r?.authMs === AUTH_MS ? (o.before ?? (async () => { throw new Error('unused'); }))() : after; },
    adbKey: () => 'QAAA= kiln@test', apk: () => mirror.apkBytes(), sleep: async () => {},
    flashedHere: { get: () => flashed, set: () => { flashed = true; } },
    holdingKey: (on) => holding.push(on),
  });
  f.pick(mirrorFrame.imageFlow.recipes[0]!.id); // the full image, not the recommended in-place way
  f.choose();
  await f.download();
  await mirror.pickApk('kiosk.apk', fakeMirrorApk());
  return { f, built, after, asked, holding, flashedHere: () => flashed };
}

/** Glassworks running on the Mirror, with the key socket holding `key` (none: an app from before #574). */
function running(o: { key?: string; socket?: boolean; board?: string } = {}) {
  const m = new FakeMirror({ installed: 600, ...(o.board ? { platform: o.board } : {}) });
  const keys = new FakeKeySocket(o.key);
  if (o.socket !== false) m.withKeySocket(keys);
  const reboots: string[] = [];
  const closed: number[] = [];
  return { m: Object.assign(m, { rebootToBootloader: async () => { reboots.push('bootloader'); }, close: async () => { closed.push(1); } }), keys, reboots, closed };
}

const through = async (f: ImageSession) => { await f.connectFastboot(); f.understand(true); await f.flash(); await f.connectAdb(); };

describe('the keep step, on the real session (#580)', () => {
  it('reads the key, restarts into fastboot, flags the image, and gives the key back: the same device', async () => {
    const r = running({ key: KEY });
    const { f, built, after, asked, holding } = await ready({ before: async () => r.m });
    f.toKeep();
    expect(f.state.screen).toBe('keep');
    await f.readKey();
    expect(asked).toEqual([AUTH_MS]);
    expect(r.keys.verbs).toEqual(['get']);
    expect(r.reboots).toEqual(['bootloader']);
    expect(f.state.key).toMatchObject({ state: 'held', fingerprint: expect.stringMatching(/^[0-9a-f]{4}$/) });
    expect(f.holdsKey()).toBe(true);
    expect(holding).toEqual([true]);
    await through(f);
    // the image gets the flag, never the key; the fresh app gets the key over its socket
    expect(built[0]?.apiKeyHandover).toBe(true);
    expect(JSON.stringify(built[0])).not.toContain(KEY);
    expect(after.keys?.key).toBe(KEY);
    expect(f.state.firstBoot).toBeDefined(); // the first start, then the standard flow
    expect(f.state.key).toMatchObject({ state: 'handed' });
    expect(f.holdsKey()).toBe(false);
    expect(holding).toEqual([true, false]);
    // nowhere in what the page keeps or shows: the state, the log, a command line
    expect(JSON.stringify(f.state)).not.toContain(KEY);
    expect([...r.m.cmds, ...after.cmds].join('\n')).not.toContain(KEY);
  });

  it('a fresh app holding another key refuses it: Done says Home Assistant asks once', async () => {
    const r = running({ key: KEY });
    const after = new FirstBootMirror().withKeySocket(new FakeKeySocket(btoa('y'.repeat(32))));
    const { f } = await ready({ before: async () => r.m, after });
    f.toKeep();
    await f.readKey();
    await through(f);
    expect(f.state.key).toMatchObject({ state: 'refused' });
  });

  it('nothing to keep from an app with no key: no flag, and the Mirror still restarts into fastboot', async () => {
    const r = running();
    const { f, built } = await ready({ before: async () => r.m });
    f.toKeep();
    await f.readKey();
    expect(f.state.key).toEqual({ state: 'none' });
    expect(r.reboots).toEqual(['bootloader']);
    await through(f);
    expect(built[0]?.apiKeyHandover).toBeUndefined();
  });

  it('a Mirror that doesn\'t let this browser in: unreachable, and Reflash anyway goes on without the flag', async () => {
    const { f, built } = await ready({ before: async () => { throw new AdbAuthTimeout(); } });
    f.toKeep();
    await f.readKey();
    expect(f.state.key).toEqual({ state: 'unreachable', why: 'no-adb' });
    f.skipKeep();
    expect(f.state.screen).toBe('fastboot');
    await through(f);
    expect(built[0]?.apiKeyHandover).toBeUndefined();
    expect(f.state.key).toMatchObject({ state: 'unreachable' });
  });

  it('a closed device list is not "unreachable": the step stays, with the browser\'s reason', async () => {
    const { f } = await ready({ before: async () => { throw new Error('no panel selected'); } });
    f.toKeep();
    await f.readKey();
    expect(f.state.key).toBeUndefined();
    expect(f.state.connectError).toBe('no panel selected');
  });

  it('an app from before the hand-over: no key to read, and Kiln restarts it into fastboot on the click', async () => {
    const r = running({ socket: false });
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    await f.readKey();
    expect(f.state.key).toEqual({ state: 'unreachable', why: 'old-app', app: '0.5.0-legacy' });
    expect(r.reboots).toEqual([]);
    await f.restartOld();
    expect(r.reboots).toEqual(['bootloader']);
    expect(f.state.key).toMatchObject({ state: 'unreachable', restarted: true });
  });

  it('a key the app can\'t hand over now stops before the restart: nothing changed, the step stays', async () => {
    const r = running({ key: KEY });
    r.keys.unreadable = true;
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    await f.readKey();
    expect(r.reboots).toEqual([]);
    expect(f.state.screen).toBe('keep');
    expect(f.state.key).toBeUndefined();
    expect(f.state.connectError).toMatch(/Nothing was written/);
    // the connection is let go, so Try again starts a clean one (#603 review)
    expect(r.closed).toEqual([1]);
  });

  it('going to fastboot by hand during the read stops it: no restart, the connection let go (PR 603 review)', async () => {
    const r = running({ key: KEY });
    let release!: () => void;
    const gate = new Promise<void>((res) => { release = res; });
    const run = r.m.run.bind(r.m);
    let waiting = false;
    r.m.run = async (cmd: string) => { if (cmd === 'getprop ro.board.platform') { waiting = true; await gate; } return run(cmd); };
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    const reading = f.readKey();
    await vi.waitFor(() => expect(waiting).toBe(true));
    f.skipKeep();
    expect(r.closed).toEqual([1]);
    expect(f.state).toMatchObject({ screen: 'fastboot', key: undefined });
    release();
    await reading;
    expect(r.keys.verbs).toEqual([]);
    expect(r.reboots).toEqual([]);
    expect(f.state).toMatchObject({ screen: 'fastboot', key: undefined });
    expect(f.holdsKey()).toBe(false);
  });

  it('a key read before the skip stays, to give back after the flash (PR 603 review)', async () => {
    const r = running({ key: KEY });
    let release!: () => void;
    const gate = new Promise<void>((res) => { release = res; });
    r.m.rebootToBootloader = async () => { await gate; r.reboots.push('bootloader'); };
    const { f, built, after } = await ready({ before: async () => r.m });
    f.toKeep();
    const reading = f.readKey();
    await vi.waitFor(() => expect(f.state.key).toEqual({ state: 'reading', at: 2 }));
    f.skipKeep();
    expect(f.state.key).toMatchObject({ state: 'held' });
    expect(f.holdsKey()).toBe(true);
    release();
    await reading;
    expect(f.state).toMatchObject({ screen: 'fastboot', key: { state: 'held' } });
    await through(f);
    expect(built[0]?.apiKeyHandover).toBe(true);
    expect(after.keys?.key).toBe(KEY);
  });

  it('a read that fails after a skip leaves the screen and the next read alone (PR 603 review)', async () => {
    const r = running({ key: KEY });
    let fail!: () => void;
    const gate = new Promise<void>((res) => { fail = res; });
    const run = r.m.run.bind(r.m);
    let waiting = false;
    r.m.run = async (cmd: string) => {
      if (cmd === 'getprop ro.board.platform') { waiting = true; await gate; throw new Error('transfer error'); }
      return run(cmd);
    };
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    const reading = f.readKey();
    await vi.waitFor(() => expect(waiting).toBe(true));
    f.skipKeep();
    fail();
    await reading;
    expect(f.state).toMatchObject({ screen: 'fastboot', key: undefined, connectError: undefined });
    expect(f.state.log.join('\n')).not.toContain('transfer error');
  });

  it('a socket that goes away between the check and the read stops before the restart (PR 603 review)', async () => {
    const r = running({ key: KEY });
    const shell = r.keys.shell.bind(r.keys);
    let checks = 0;
    r.keys.shell = (cmd: string) => { const a = shell(cmd); if (a) { checks++; if (checks === 1) r.keys.listening = false; } return a; };
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    await f.readKey();
    expect(r.reboots).toEqual([]);
    expect(f.state.key).toBeUndefined();
    expect(f.state.connectError).toMatch(/socket closed/);
  });

  it('a Mirror that stops answering the app\'s version read ends in an error, with no restart, and lets go', async () => {
    vi.useFakeTimers();
    try {
      const rebootToBootloader = vi.fn(async () => {});
      const close = vi.fn(async () => {});
      const hung: PanelShell = {
        run: (cmd) => (cmd === 'getprop ro.board.platform' ? Promise.resolve({ out: 'msm8916\n', code: 0 }) : new Promise(() => {})),
        push: async () => {}, rebootToBootloader, close,
      };
      const { f } = await ready({ before: async () => hung });
      f.toKeep();
      const reading = f.readKey();
      await vi.advanceTimersByTimeAsync(READ_MS);
      await reading;
      expect(rebootToBootloader).not.toHaveBeenCalled();
      expect(close).toHaveBeenCalled();
      expect(f.state.key).toBeUndefined();
      expect(f.state.connectError).toMatch(/didn't answer `dumpsys package .+` in 15 s/);
    } finally {
      vi.useRealTimers();
    }
  });

  it('another device isn\'t read', async () => {
    const r = running({ key: KEY, board: 'sun50iw10' });
    const { f } = await ready({ before: async () => r.m });
    f.toKeep();
    await f.readKey();
    expect(r.keys.verbs).toEqual([]);
    expect(r.reboots).toEqual([]);
    expect(f.state.connectError).toMatch(/isn't the Mirror/);
  });

  it('Continue opens fastboot by hand, unless this browser has flashed a Mirror: then the keep step (the maintainer, 2026-11-19)', async () => {
    const first = await ready();
    first.f.toFastboot();
    render(<ImageFlow image={first.f} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true)} />);
    expect(screen.getByRole('heading').textContent).toBe('Put the Mirror in fastboot mode');
    expect(screen.getByText(/hold VOL− \(SW3\)/)).toBeTruthy();
    expect(embers()).toEqual(['Connect']);
    cleanup();
    const again = await ready({ flashed: true });
    again.f.toFastboot();
    render(<ImageFlow image={again.f} app={new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('unused'); } }, true)} />);
    expect(screen.getByRole('heading').textContent).toBe('Keep the Mirror in Home Assistant');
    expect(screen.queryByText(/hold VOL−/)).toBeNull();
  });

  it('Continue opens the keep step in a browser that has flashed a Mirror; a flash marks it', async () => {
    const first = await ready();
    first.f.toFastboot();
    expect(first.f.state.screen).toBe('fastboot');
    await first.f.connectFastboot();
    first.f.understand(true);
    await first.f.flash();
    expect(first.flashedHere()).toBe(true);
    const again = await ready({ flashed: true });
    again.f.toFastboot();
    expect(again.f.state.screen).toBe('keep');
  });

  it('the page asks before it closes while the session holds the key, whichever screen shows (#603 review)', () => {
    const on = leaveWarning();
    const leave = () => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented; };
    expect(leave()).toBe(false);
    on(true);
    expect(leave()).toBe(true);
    on(false);
    expect(leave()).toBe(false);
  });

  it('the keep screen\'s Connect reads the key', async () => {
    const d = await keepDemo(mirrorEntry, 'flash-keep', true);
    const read = vi.spyOn(d.image, 'readKey').mockResolvedValue();
    render(<ImageFlow image={d.image} app={d.app} />);
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));
    expect(read).toHaveBeenCalledOnce();
  });

  it('the way back to a fresh install clears an earlier key outcome, so the keep step and Done start over (#579)', async () => {
    const d = await keepDemo(mirrorEntry, 'flash-done-kept', true);
    expect(d.image.state.key?.state).toBe('handed');
    d.image.wayBack('glass-panes');
    expect(d.image.state.key).toBeUndefined();
    d.image.wayBack('glassworks');
    expect(d.image.state.key).toBeUndefined();
  });

  it('an image link while the key is being read stops the read: no restart, no key stored, nothing left reading (#596)', async () => {
    let answer!: (p: PanelShell) => void;
    const before = () => new Promise<PanelShell>((r) => { answer = r; });
    const { f } = await ready({ before, flashed: true });
    f.toFastboot();
    expect(f.state.screen).toBe('keep');
    const reading = f.readKey(); // the browser's list is open: the read waits for the Mirror
    expect(f.state.key).toEqual({ state: 'reading', at: 0 });
    f.followLink('?image=glass-panes');
    const r = running({ key: DEMO_KEY });
    answer(r.m);
    await reading;
    expect(f.state).toMatchObject({ screen: 'download', picked: 'glass-panes', key: undefined, connecting: false });
    expect(r.reboots).toEqual([]); // no restart into fastboot
    expect(r.closed).toHaveLength(1); // the connection it got is let go
    expect(f.holdsKey()).toBe(false);
  });
});
