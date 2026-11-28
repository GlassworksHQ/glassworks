// @vitest-environment jsdom
// Glassworks on the Mirror's stock software (the in-place recipe), on the engine's image session with the Mirror's
// declaration and its simulated stock Mirror (its sim's `inPlace`): the stage, the bundle over the local network or
// Bluetooth (or this browser's copy), Kiln's refusals before any system write, the restore point kept and read back
// with its download, the writes, the app over adb, and the way back. Every serial and address is made up.
import { Blob as NodeBlob } from 'node:buffer';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { readNamedBackupZip, sha256Hex, type InPlaceCode, type InPlaceSimDevice } from '@glassworks/kiln-core';
import { FAKE_SERIAL, buildFakeGwsbBundle } from '@glassworks/lululemon-mirror-installer/fakes';
import { kilnSim } from '@glassworks/lululemon-mirror-installer/sim';
import type { PanelShell } from '@glassworks/adb';
import { AppSession } from '../src/device/appSession';
import type { BluetoothLike } from '../src/device/channels';
import { ImageFlow, inPlaceStep, installRoutes } from '../src/device/DevicePage';
import { imageRail } from '../src/device/ImageScreens';
import { imageDemo } from '../src/device/imageDemo';
import { ImageSession, type DeviceProgress, type ImageDeps, type ProgressMark } from '../src/device/imageSession';
import { memoryInPlaceStore, restorePointZip, type InPlaceStore } from '../src/device/inPlace';
import { routesOf } from '../src/app/[frame]/frames';
import { withAppStreams } from '../src/wizard/demo';
import { handTo, mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  // a browser that has WebUSB over HTTPS: without it every flow stops on the Browser screen (issue 849)
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  // the restore point's download: a link the page clicks, which jsdom can't follow
  URL.createObjectURL = () => 'blob:kiln-test';
  URL.revokeObjectURL = () => {};
  HTMLAnchorElement.prototype.click = function click() {};
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => cleanup());

const sim = kilnSim.inPlace!;
const RECIPE = mirrorFrame.imageFlow.recipes.find((r) => r.mode === 'in-place')!;
const frame = { ...mirrorFrame, imageFlow: { ...mirrorFrame.imageFlow, inPlace: sim.code } };

function tab(): DeviceProgress & { mark?: ProgressMark } {
  const t: DeviceProgress & { mark?: ProgressMark } = { get: () => t.mark, set: (m) => { t.mark = m; } };
  return t;
}

interface Rig { image: ImageSession; dev: InPlaceSimDevice; store: InPlaceStore; progress: ReturnType<typeof tab>; flashed: string[]; adb: () => PanelShell;
  /** how many fastboot connections Kiln let go of (FastbootLike `close`) */
  closes: () => number }

/** A session on a simulated stock Mirror; `store` and `progress` carry over to a second browser load or a second run. */
function rig(o: { lan?: 'ok' | 'drop-midway' | 'offline' | 'lna-denied'; tampered?: boolean; refusedBuild?: boolean; systemNotTaken?: boolean; unreadable?: boolean; store?: InPlaceStore; progress?: ReturnType<typeof tab>;
  dev?: InPlaceSimDevice; deps?: Partial<ImageDeps>; code?: Partial<InPlaceCode>;
  /** the frame's limit on one adb read (its `timings.readMs`) */
  readMs?: number;
  /** adb commands the device never answers (it stops answering partway, say) */
  hangs?: (cmd: string) => boolean;
  /** a write (`flash <partition>`) or a restart (`reboot`) that fails, by what it is and how many such calls came before it */
  fail?: (what: string, n: number) => boolean } = {}): Rig {
  const dev = o.dev ?? sim.device({ lan: o.lan ?? 'ok', tampered: o.tampered, refusedBuild: o.refusedBuild, systemNotTaken: o.systemNotTaken, unreadable: o.unreadable });
  const store = o.store ?? memoryInPlaceStore();
  const progress = o.progress ?? tab();
  const flashed: string[] = [];
  const calls = new Map<string, number>();
  let closes = 0;
  const failing = (what: string) => { const n = calls.get(what) ?? 0; calls.set(what, n + 1); if (o.fail?.(what, n)) throw new Error(`the bootloader stopped answering (${what})`); };
  const fastboot = () => {
    const fb = dev.fastboot();
    return { ...fb,
      flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { failing(`flash ${p}`); await fb.flashBlob(p, b, on); flashed.push(p); },
      reboot: async () => { failing('reboot'); await fb.reboot(); },
      close: async () => { closes++; } };
  };
  const withCode = o.code ? { ...frame, imageFlow: { ...frame.imageFlow, inPlace: { ...sim.code, ...o.code } } } : frame;
  const frame_ = o.readMs ? { ...withCode, timings: { ...withCode.timings, readMs: o.readMs } } : withCode;
  const adb = () => {
    const a = dev.adb();
    const hung: PanelShell = o.hangs ? { ...a, run: (cmd, signal) => (o.hangs!(cmd) ? new Promise(() => {}) : a.run(cmd, signal)) } : a;
    return Object.assign(withAppStreams(hung, { online: true, radio: false }), { rebootToBootloader: () => a.rebootToBootloader!() });
  };
  const app = new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true);
  const apk = mirrorEntry.sim.apk();
  const image = new ImageSession({
    frame: frame_,
    images: { fetch: async () => {}, download: async () => {}, file: async () => new Blob([]), check: async () => {}, build: async () => { throw new Error('no'); },
      wifi: async () => new Blob([]), adbKey: async () => new Blob([]), systemKey: async () => new Blob([]), discard: async () => {} },
    requestFastboot: async () => fastboot(),
    requestAdb: async () => adb(),
    adbKey: () => 'QAAA= kiln@test',
    apk: () => apk,
    sleep: async () => {},
    simulated: true,
    progress,
    inPlaceStore: store,
    bluetooth: dev.bluetooth as BluetoothLike,
    agentFetch: dev.fetch,
    localNetworkPermission: async () => (o.lan === 'lna-denied' ? 'denied' : 'granted'),
    bleStallMs: 2000,
    handOver: handTo().handOver,
    ...o.deps,
  });
  void app;
  return { image, dev, store, progress, flashed, adb, closes: () => closes };
}

/** Choose the in-place install, the first fastboot session, the warning, and the stage written and restarted. */
async function toReceive(r: Rig) {
  const { image } = r;
  image.pick(RECIPE.id);
  image.choose();
  expect(image.state.screen).toBe('image');
  image.toFastboot();
  expect(image.state).toMatchObject({ screen: 'fastboot', round: 1 });
  await image.connectFastboot();
  expect(image.state.screen).toBe('confirm');
  image.understand(true);
  await image.flash();
  expect(image.state).toMatchObject({ screen: 'receive', round: 1 });
}

/**
 * Receive (over the Mirror's own Wi-Fi, or Bluetooth), the second fastboot session, and the restore point saved and
 * checked. `copy`: the person downloads its copy too, which Continue waits for.
 */
async function toRestorePoint(r: Rig, via: 'wifi' | 'ble' = 'wifi', copy = true) {
  if (via === 'wifi') await r.image.receiveWifi();
  else await r.image.receive();
  expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
  await r.image.connectFastboot();
  await readyRestorePoint(r);
  if (copy) await saveCopy(r.image);
}

/** The person downloads the restore point's copy and says it's saved: Continue opens. */
async function saveCopy(image: ImageSession) {
  const zip = await image.restoreZip();
  await image.confirmCopy();
  return zip;
}

/** Waits for the restore point to be saved and checked. */
async function readyRestorePoint(r: Rig) {
  for (let i = 0; i < 200 && r.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
  expect(r.image.state.screen).toBe('restore-point');
  expect(r.image.state.restorePoint?.status).toBe('ready');
}

describe('Glassworks on the stock software', () => {
  it('stages, receives over the local network, keeps a restore point, writes, installs the app, and hands over', async () => {
    const r = rig();
    await toReceive(r);
    expect(r.flashed).toEqual(['userdata']); // the stage: userdata only, system untouched
    expect(r.progress.mark).toMatchObject({ at: 'staged', serial: FAKE_SERIAL });
    await toRestorePoint(r);
    expect(r.image.state.receive?.channel).toBe('lan');
    expect(r.progress.mark).toMatchObject({ at: 'received', serial: FAKE_SERIAL });
    expect(r.progress.mark?.secret).toBeUndefined(); // the secret goes once the bundle is in
    expect(r.flashed).toEqual(['userdata']); // nothing written to system before the restore point is saved
    await r.image.continueInstall();
    expect(r.flashed).toEqual(['userdata', 'system', 'userdata']);
    expect(r.image.state.screen).toBe('adb');
    await r.image.connectAdb();
    expect(r.image.state.firstBoot).toMatchObject({ home: { result: 'set' }, updates: { state: 'on' } });
    expect(r.progress.mark).toBeUndefined();
    const log = r.image.state.log.join('\n');
    expect(log).not.toContain(FAKE_SERIAL);
    expect(log).toContain('Device Owner: set');
    // the bootloader can't read the system write back: Kiln reads services.jar back over adb before the app goes on
    expect(log).toContain('services.jar on the Mirror matches what Kiln wrote');
    expect(log.indexOf('matches what Kiln wrote')).toBeLessThan(log.indexOf('Installing'));
  });

  it('on a reinstall over Glassworks (stock\'s hotspot turned off), gives the helper the person\'s network and reaches it there', async () => {
    // the frame's check runs over adb before the restart (the Mirror's: Glassworks is installed, inplace.test.ts)
    let asked: PanelShell | undefined;
    // what Kiln hands the frame's stage (the sim's stand-in ignores the network; inplace.test.ts checks the real one
    // writes it as Android's Wi-Fi reads it)
    let staged: Parameters<InPlaceCode['stage']>[0] | undefined;
    const r = rig({ code: { ownWifiGone: async (p) => { asked = p; return true; }, stage: async (o) => { staged = o; return sim.code.stage(o); } } });
    r.image.setWifi({ ssid: 'HomeNet', password: 'correcthorse' });
    r.image.pick(RECIPE.id);
    r.image.choose();
    r.image.toFastboot();
    await r.image.restartToFastboot();
    expect(r.image.state.restarted).toBe(true);
    expect(asked).toBeTruthy();
    await r.image.connectFastboot();
    r.image.understand(true);
    await r.image.flash();
    expect(r.image.state).toMatchObject({ screen: 'receive', round: 1 });
    const log = r.image.state.log.join('\n');
    expect(log).toContain('own Wi-Fi network is off since its earlier install');
    expect(log).toContain('the setup agent, this browser\'s adb key, its signing secret and your network, on a fresh userdata');
    expect(log).not.toContain('HomeNet'); // the person's network stays out of the log
    expect(staged?.wifi).toEqual({ ssid: 'HomeNet', password: 'correcthorse' });
    // no "join mirror-<serial>" screen: the Bluetooth way, with the local network the agent joined
    expect(r.image.state.receive).toMatchObject({ network: null });
    expect(r.progress.mark).toMatchObject({ at: 'staged', noOwnWifi: true });
    await toRestorePoint(r, 'ble');
    expect(r.image.state.receive?.channel).toBe('lan');
  });

  it('without its own Wi-Fi, the download by hand starts at the Mirror\'s address from before the restart, until Bluetooth says (#944)', async () => {
    const r = rig({ code: { ownWifiGone: async () => true, lanAddress: async () => '192.0.2.44' } });
    r.image.setWifi({ ssid: 'HomeNet', password: 'correcthorse' });
    r.image.pick(RECIPE.id);
    r.image.choose();
    r.image.toFastboot();
    await r.image.restartToFastboot();
    await r.image.connectFastboot();
    r.image.understand(true);
    await r.image.flash();
    expect(r.image.state.receive).toMatchObject({ network: null, pageUrl: 'http://192.0.2.44:18765/', pageGuessed: true });
    expect(r.progress.mark).toMatchObject({ at: 'staged', noOwnWifi: true, lanHost: '192.0.2.44' });
    // what the agent says over Bluetooth replaces the guess
    await toRestorePoint(r, 'ble');
    expect(r.image.state.receive?.pageGuessed).toBeUndefined();
  });

  it('a reload on receive keeps the guessed address, but never one that isn\'t an address', async () => {
    const kept = tab();
    kept.set({ at: 'staged', image: RECIPE.id, secret: '00'.repeat(32), serial: FAKE_SERIAL, noOwnWifi: true, lanHost: '192.0.2.44' });
    expect(rig({ progress: kept }).image.state.receive).toMatchObject({ pageUrl: 'http://192.0.2.44:18765/', pageGuessed: true });
    const changed = tab();
    changed.set({ at: 'staged', image: RECIPE.id, secret: '00'.repeat(32), serial: FAKE_SERIAL, noOwnWifi: true, lanHost: 'evil.example/x#' });
    expect(rig({ progress: changed }).image.state.receive).toMatchObject({ pageUrl: null });
    expect(rig({ progress: changed }).image.state.receive?.pageGuessed).toBeUndefined();
  });

  it('a Mirror that stops answering the Wi-Fi check still restarts, within the read limit, and keeps the network out of the stage', async () => {
    // the Mirror's own check (pm list packages) on a shell that never answers it
    const r = rig({ code: { ownWifiGone: sim.code.ownWifiGone }, readMs: 50, hangs: (c) => c.startsWith('pm list packages') });
    r.image.setWifi({ ssid: 'HomeNet', password: 'correcthorse' });
    r.image.pick(RECIPE.id);
    r.image.choose();
    r.image.toFastboot();
    const t0 = Date.now();
    await r.image.restartToFastboot();
    expect(Date.now() - t0).toBeLessThan(5_000);
    expect(r.image.state).toMatchObject({ restarted: true, connecting: false });
    expect(r.image.state.connectError).toBeUndefined();
    await r.image.connectFastboot();
    r.image.understand(true);
    await r.image.flash();
    // unknown counts as no: the Mirror's own Wi-Fi, as before the check
    expect(r.image.state.receive).toMatchObject({ network: `mirror-${FAKE_SERIAL}` });
    expect(r.image.state.log.join('\n')).toContain('the setup agent, this browser\'s adb key and its signing secret, on a fresh userdata');
    expect(r.progress.mark?.noOwnWifi).toBeUndefined();
  });

  it('keeps the stage free of the person\'s network when the Mirror\'s own Wi-Fi is still there', async () => {
    let staged: Parameters<InPlaceCode['stage']>[0] | undefined;
    const r = rig({ code: { ownWifiGone: async () => false, stage: async (o) => { staged = o; return sim.code.stage(o); } } });
    r.image.setWifi({ ssid: 'HomeNet', password: 'correcthorse' });
    r.image.pick(RECIPE.id);
    r.image.choose();
    r.image.toFastboot();
    await r.image.restartToFastboot();
    await r.image.connectFastboot();
    r.image.understand(true);
    await r.image.flash();
    expect(r.image.state.receive).toMatchObject({ network: `mirror-${FAKE_SERIAL}` });
    expect(r.image.state.log.join('\n')).toContain('the setup agent, this browser\'s adb key and its signing secret, on a fresh userdata');
    expect(r.progress.mark?.noOwnWifi).toBeUndefined();
    expect(staged).toBeTruthy();
    expect(staged?.wifi).toBeUndefined(); // the agent would switch Wi-Fi to client and close stock's hotspot
  });

  it('a reload on receive keeps knowing the Mirror\'s own Wi-Fi is gone', async () => {
    const progress = tab();
    progress.set({ at: 'staged', image: RECIPE.id, secret: '00'.repeat(32), serial: FAKE_SERIAL, noOwnWifi: true });
    const r = rig({ progress });
    expect(r.image.state).toMatchObject({ screen: 'receive', resumed: 'staged' });
    expect(r.image.state.receive).toMatchObject({ network: null });
  });

  it('asks the person to join the Mirror\'s own Wi-Fi, sees when this computer has, and keeps their Wi-Fi out of the stage', async () => {
    const r = rig({ deps: { hotspotPollMs: 5 } });
    r.image.setWifi({ ssid: 'HomeNet', password: 'correcthorse' });
    await toReceive(r);
    expect(r.image.state.receive).toMatchObject({ network: `mirror-${FAKE_SERIAL}` });
    for (let i = 0; i < 100 && !r.image.state.receive?.joined; i++) await new Promise((x) => setTimeout(x, 5));
    expect(r.image.state.receive).toMatchObject({ stage: 'ready', joined: true });
    expect(r.image.state.log.join('\n')).toContain('the setup agent, this browser\'s adb key and its signing secret, on a fresh userdata');
    await toRestorePoint(r);
    expect(r.image.state.leftHotspot).toBe(true);
  });

  it('a Wi-Fi transfer that drops offers Bluetooth: Kiln doesn\'t switch on its own, and Bluetooth then goes over Bluetooth alone', async () => {
    const r = rig({ lan: 'drop-midway' });
    await toReceive(r);
    await r.image.receiveWifi();
    expect(r.image.state.receive).toMatchObject({ stage: 'wifi-unreachable', wifiFailed: 'unreachable' });
    expect(r.image.state.screen).toBe('receive');
    await toRestorePoint(r, 'ble');
    expect(r.image.state.receive?.channel).toBe('ble');
    expect(r.image.state.receive?.fallback).toBeNull();
  });

  it('a second run takes the bundle this browser kept only while the frame says it still fits the device', async () => {
    const store = memoryInPlaceStore();
    const first = rig({ store });
    await toReceive(first);
    await toRestorePoint(first);
    const second = rig({ store, code: { keptMatches: () => true } });
    await toReceive(second);
    await toRestorePoint(second, 'ble');
    expect(second.image.state.receive?.channel).toBe('cache');
    expect(second.image.state.log.join('\n')).toContain('this browser\'s saved copy');
  });

  it('a second run on the same Mirror takes the bundle again: its Bluetooth description can\'t say where services.jar sits now', async () => {
    const store = memoryInPlaceStore();
    const first = rig({ store });
    await toReceive(first);
    await toRestorePoint(first);
    const second = rig({ store });
    await toReceive(second);
    await toRestorePoint(second);
    expect(second.image.state.receive?.channel).toBe('lan');
  });

  it('a new run forgets what the last run\'s agent said: a file from this run\'s agent, with another jar, is taken', async () => {
    const secret = new Uint8Array(32).fill(7);
    const r = rig({ deps: { secret: () => secret } });
    await toReceive(r);
    await toRestorePoint(r); // the agent's description (its digest) read over Bluetooth
    r.image.toFastboot();
    await r.image.connectFastboot();
    r.image.understand(true);
    await r.image.flash();
    expect(r.image.state).toMatchObject({ screen: 'receive', round: 1 });
    const other = await buildFakeGwsbBundle({ secret, jarBytes: new Uint8Array(16384).fill(3) });
    await r.image.receiveFile(new NodeBlob([other.bytes as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(r.image.state.receive?.error ?? null).toBeNull();
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
  });

  it('a Cancel while the bundle comes in stops it there: nothing it brings moves the install on', async () => {
    const r = rig();
    await toReceive(r);
    const p = r.image.receive();
    await r.image.cancelReceive();
    await p;
    expect(r.image.state.screen).toBe('receive');
    expect(r.image.state.receive?.stage).toBe('ready');
    expect(r.progress.mark).toMatchObject({ at: 'staged' });
  });

  it('asks before going on with a bundle whose signature doesn\'t match: nothing kept or written until the person says so', async () => {
    const r = rig({ tampered: true });
    await toReceive(r);
    await r.image.receive();
    expect(r.image.state.screen).toBe('receive');
    expect(r.image.state.receive).toMatchObject({ stage: 'unverified' });
    expect(r.flashed).toEqual(['userdata']);
    expect(await r.store.latestRestore()).toBeUndefined();
    // Back: the warning goes, and nothing moved on
    r.image.receiveAgain();
    expect(r.image.state.receive?.stage).toBe('ready');
    await r.image.receiveUnverified(); // nothing waits on a decision any more
    expect(r.image.state.screen).toBe('receive');
    expect(r.flashed).toEqual(['userdata']);
  });

  it('Continue anyway takes the bundle with every other check, and the log says the signature wasn\'t checked', async () => {
    const r = rig({ tampered: true });
    await toReceive(r);
    await r.image.receive();
    expect(r.image.state.receive?.stage).toBe('unverified');
    await r.image.receiveUnverified();
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    const log = r.image.state.log.join('\n');
    expect(log).toContain('went on without the signature');
    expect(log).not.toContain('and its signature');
  });

  it('a wrong serial still stops it: only a signature can be waved through', async () => {
    const r = rig();
    await toReceive(r);
    const other = await buildFakeGwsbBundle({ secret: new Uint8Array(32).fill(9), serialno: 'OTHERSERIAL' });
    await r.image.receiveFile(new NodeBlob([other.bytes as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(r.image.state.receive).toMatchObject({ stage: 'error', channel: 'file' });
    expect(r.image.state.receive?.error).toMatch(/another device/);
    expect(r.flashed).toEqual(['userdata']);
  });

  it('stops on an odexed build before any system write', async () => {
    const r = rig({ refusedBuild: true }); // an odexed build
    await toReceive(r);
    await r.image.receive();
    expect(r.image.state.receive?.stage).toBe('error');
    expect(r.image.state.receive?.error).toMatch(/services.odex/);
    expect(r.flashed).toEqual(['userdata']);
  });

  it('a bundle file from the agent\'s page is checked like one that came over a channel', async () => {
    const r = rig();
    await toReceive(r);
    // a bundle signed with another run's secret, as from an old download
    const old = await buildFakeGwsbBundle({ secret: new Uint8Array(32).fill(9) });
    await r.image.receiveFile(new NodeBlob([old.bytes as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(r.image.state.receive).toMatchObject({ stage: 'unverified', channel: 'file' });
    expect(r.flashed).toEqual(['userdata']);
  });

  it('keeps Continue shut when the restore point doesn\'t read back as saved', async () => {
    const store = memoryInPlaceStore();
    const bad: InPlaceStore = { ...store, getRestore: async (s, d) => (await store.getRestore(s, d))?.map((f) => ({ ...f, data: f.data.map((x) => x ^ 1) })) };
    const r = rig({ store: bad });
    await toReceive(r);
    await r.image.receive();
    await r.image.connectFastboot();
    for (let i = 0; i < 200 && r.image.state.restorePoint?.status !== 'error' && r.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
    expect(r.image.state.restorePoint?.status).toBe('error');
    await r.image.continueInstall();
    expect(r.flashed).toEqual(['userdata']);
  });

  it('Continue waits for a copy of the restore point on the computer before anything is written to the system', async () => {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r, 'wifi', false);
    expect(r.image.state.restorePoint?.downloaded).toBe(false);
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('restore-point');
    expect(r.flashed).toEqual(['userdata']); // the stage alone
    await r.image.restoreZip();
    // offered, not counted: a browser can't say the download landed, so the person confirms it
    expect(r.image.state.restorePoint).toMatchObject({ offered: true, downloaded: false });
    expect(await r.store.copied(FAKE_SERIAL, r.progress.mark!.digest as string, r.image.state.restorePoint!.digest!)).toBe(false);
    await r.image.continueInstall();
    expect(r.flashed).toEqual(['userdata']);
    await r.image.confirmCopy();
    expect(r.image.state.restorePoint?.downloaded).toBe(true);
    expect(await r.store.copied(FAKE_SERIAL, r.progress.mark!.digest as string, r.image.state.restorePoint!.digest!)).toBe(true);
    await r.image.continueInstall();
    expect(r.flashed).toEqual(['userdata', 'system', 'userdata']);
  });

  it('offers the restore point as a zip the CLI\'s undo reads: the restore image, its manifest, SHA256SUMS', async () => {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r);
    const { blob, name } = await r.image.restoreZip();
    expect(name).toMatch(/^glassworks-in-place-\d{8}-\d{6}\.zip$/);
    expect(r.image.state.restorePoint).toMatchObject({ offered: true, downloaded: true }); // toRestorePoint confirmed the first copy
    const { files } = await readNamedBackupZip(new NodeBlob([await bytesOf(blob) as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(files.map((f) => f.filename).sort()).toEqual(['manifest.json', 'restore-services.sparse', 'services.jar.orig']);
    const manifest = JSON.parse(new TextDecoder().decode(files.find((f) => f.filename === 'manifest.json')!.data)) as { servicesJar: { restoreSparse: string }; kiln: { files: Record<string, { size: number; sha256: string }> } };
    expect(manifest.servicesJar.restoreSparse).toBe('restore-services.sparse');
    // each file's size and SHA-256 as made, for the way back's check before it writes
    for (const f of files.filter((x) => x.filename !== 'manifest.json')) expect(manifest.kiln.files[f.filename]).toEqual({ size: f.data.length, sha256: await sha256Hex(f.data) });
    expect(await sha256Hex(files.find((f) => f.filename === 'restore-services.sparse')!.data)).toBe(r.image.state.restorePoint?.digest);
  });
});

describe('Start over', () => {
  it('on receive: asks first, then forgets the run (its mark too) and opens the first screen, where a new run starts clean', async () => {
    const r = rig();
    await toReceive(r);
    expect(r.image.canStartOver()).toBe(true);
    r.image.startOver(); // not asked yet: nothing happens
    r.image.askStartOver();
    expect(r.image.state.startingOver).toBe(true);
    r.image.startOver();
    expect(r.image.state).toMatchObject({ screen: 'image', changed: true, startingOver: false });
    expect(r.image.state.round).toBeUndefined();
    expect(r.progress.mark).toBeUndefined();
    // a reload now opens the first screen, not receive
    expect(rig({ dev: r.dev, store: r.store, progress: r.progress }).image.state.screen).not.toBe('receive');
    // and a new run goes through its stage again
    await toReceive(r);
  });

  it('on the second fastboot and the restore point, before any system write', async () => {
    const r = rig();
    await toReceive(r);
    await r.image.receiveWifi();
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    expect(r.image.canStartOver()).toBe(true);
    await r.image.connectFastboot();
    for (let i = 0; i < 200 && r.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
    expect(r.image.state.screen).toBe('restore-point');
    expect(r.image.canStartOver()).toBe(true);
    r.image.askStartOver();
    r.image.startOver();
    expect(r.image.state.screen).toBe('image');
    expect(r.flashed).toEqual(['userdata']); // the stage alone: the system was never written
  });

  it('a restore point still saving for the run before changes nothing in the new run, and Continue waits for the new one', async () => {
    const inner = memoryInPlaceStore();
    const gates: (() => void)[] = [];
    const store: InPlaceStore = { ...inner, putRestore: async (...a: Parameters<InPlaceStore['putRestore']>) => { await new Promise<void>((x) => gates.push(x)); return inner.putRestore(...a); } };
    const r = rig({ store });
    await toReceive(r);
    await r.image.receiveWifi();
    await r.image.connectFastboot();
    await vi.waitFor(() => expect(gates).toHaveLength(1)); // the first run's save, held
    expect(r.image.state.restorePoint?.status).toBe('saving');
    r.image.askStartOver();
    r.image.startOver();
    await toReceive(r);
    await r.image.receiveWifi();
    // the stand-in agent signs with the first run's secret (it reads it off its first stage only): taken unverified
    if (r.image.state.screen === 'receive') await r.image.receiveUnverified();
    await r.image.connectFastboot();
    await vi.waitFor(() => expect(gates).toHaveLength(2)); // the new run's save, held too
    gates[0]!(); // the old save finishes now
    for (let i = 0; i < 20; i++) await new Promise((x) => setTimeout(x, 5));
    expect(r.image.state.restorePoint?.status).toBe('saving'); // not the old run's "ready"
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('restore-point');
    gates[1]!();
    await readyRestorePoint(r);
    await saveCopy(r.image);
    await r.image.continueInstall();
    expect(r.flashed).toEqual(['userdata', 'userdata', 'system', 'userdata']); // two stages, then this run's writes
  });

  it('not once a system write began: its error, and a reload after it, offer the restore point instead', async () => {
    const r = rig({ fail: (what, n) => what === 'flash system' && n === 0 });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('error');
    expect(r.image.canStartOver()).toBe(false);
    expect(r.progress.mark?.began).toContain('system-in-place');
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress });
    expect(b.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    expect(b.image.canStartOver()).toBe(false);
  });

  it('not after the install restarted into what it wrote: One last click offers the restore point', async () => {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    await vi.waitFor(() => expect(r.image.state.screen).toBe('adb'));
    expect(r.image.canStartOver()).toBe(false);
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    expect(screen.queryByRole('button', { name: 'Start over' })).toBeNull();
    // the way out is the rail's: the restore point, never a silent Back
    expect(screen.getByRole('button', { name: 'Restore the original system' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(document.querySelector('.foot-lock')!.textContent).toBe('The Mirror’s system is changed');
  });

  it('the second fastboot screen has no silent Back: Start over says what it resets before it does', async () => {
    const r = rig();
    await toReceive(r);
    await r.image.receiveWifi();
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Start over' }));
    expect(screen.getByText(frame.imageFlow.inPlace!.words['inPlace.startOver'])).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep going' }));
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2, startingOver: false });
    fireEvent.click(screen.getByRole('button', { name: 'Start over' }));
    act(() => { fireEvent.click(screen.getAllByRole('button', { name: 'Start over' }).at(-1)!); });
    expect(r.image.state.screen).toBe('image');
  });
});

describe('a reload', () => {
  it('after the stage resumes on receive, and after the bundle on the second fastboot, from this browser\'s copy', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    const a = rig({ dev, store, progress });
    await toReceive(a);
    const b = rig({ dev, store, progress });
    expect(b.image.state).toMatchObject({ screen: 'receive', round: 1, changed: true });
    await b.image.receive();
    expect(b.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    const c = rig({ dev, store, progress });
    expect(c.image.state).toMatchObject({ screen: 'fastboot', round: 2, changed: true });
    await c.image.connectFastboot();
    for (let i = 0; i < 200 && c.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
    expect(c.image.state.restorePoint?.status).toBe('ready');
  });

  it('on the restore point, a reload asks for the copy again only if this browser has no record of one', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    // no copy before the reload: the restore point, saved again, still waits for it
    const a = rig({ dev, store, progress });
    await toReceive(a);
    await toRestorePoint(a, 'wifi', false);
    const b = rig({ dev, store, progress });
    expect(b.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    await b.image.connectFastboot();
    await readyRestorePoint(b);
    expect(b.image.state.restorePoint?.downloaded).toBe(false);
    await b.image.continueInstall();
    expect(b.flashed).toEqual([]);
    // a copy, then a reload: the same restore point saved again goes on without asking twice
    await saveCopy(b.image);
    const c = rig({ dev, store, progress });
    await c.image.connectFastboot();
    await readyRestorePoint(c);
    expect(c.image.state.restorePoint?.downloaded).toBe(true);
    expect(c.image.state.log.join('\n')).toMatch(/a copy of it was saved from this browser before/);
    await c.image.continueInstall();
    expect(c.flashed).toEqual(['system', 'userdata']);
  });

  it('after the system write, a reload doesn\'t write the system again: it goes on from the write that didn\'t finish', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    const a = rig({ dev, store, progress, fail: (what, n) => what === 'flash userdata' && n === 1 }); // the stage's is n 0
    await toReceive(a);
    await toRestorePoint(a);
    await a.image.continueInstall();
    expect(a.flashed).toEqual(['userdata', 'system']);
    expect(a.image.state.screen).toBe('error');
    expect(progress.mark).toMatchObject({ at: 'received', wrote: ['system-in-place'] });
    const b = rig({ dev, store, progress });
    expect(b.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    await b.image.connectFastboot();
    await readyRestorePoint(b);
    // the copy was downloaded before the reload: Continue doesn't wait for it again
    expect(b.image.state.restorePoint?.downloaded).toBe(true);
    await b.image.continueInstall();
    expect(b.flashed).toEqual(['userdata']);
    expect(b.image.state.screen).toBe('adb');
    expect(b.image.state.log.join('\n')).toMatch(/already written before this page reloaded/);
  });

  it('refuses another Mirror in the second fastboot session', async () => {
    const r = rig();
    await toReceive(r);
    await r.image.receive();
    // the page reloads with another Mirror on the cable in fastboot
    const fb = r.dev.fastboot();
    const other = { ...fb, getvar: async (n: string) => (n === 'serialno' ? '0000fake02' : fb.getvar(n)) };
    const swapped = rig({ dev: { ...r.dev, fastboot: () => other }, store: r.store, progress: r.progress });
    await swapped.image.connectFastboot();
    expect(swapped.image.state.connectError).toMatch(/another device/);
    expect(swapped.image.state.screen).toBe('fastboot');
    // nothing goes on with that connection: Kiln lets go of it, so Connect again opens the right Mirror fresh
    expect(swapped.closes()).toBe(1);
  });

  it('keeps the fastboot connection a later step goes on with, and lets go of it on no other success', async () => {
    const r = rig();
    r.image.pick(RECIPE.id);
    r.image.choose();
    r.image.toFastboot();
    await r.image.connectFastboot();
    expect(r.image.state.screen).toBe('confirm');
    expect(r.closes()).toBe(0); // the warning's Continue writes over it
    r.image.understand(true);
    await r.image.flash();
    expect(r.image.state.screen).toBe('receive');
    expect(r.closes()).toBe(0); // its restart let go of it
  });
});

describe('the way back (restore-blocks)', () => {
  async function installed() {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r);
    const zip = await saveCopy(r.image);
    await r.image.continueInstall();
    await r.image.connectAdb();
    return { r, zip };
  }

  it('writes this browser\'s restore point over the blocks it changed, then a fresh userdata, so nothing the app set stays', async () => {
    const { r } = await installed();
    const device = () => r.dev.adb() as unknown as { installed?: number; owner?: string };
    expect(device().owner).toBeTruthy(); // the install made Glassworks the Device Owner
    await r.image.startUndo();
    expect(r.image.state).toMatchObject({ screen: 'undo', undo: { at: 'start', source: 'browser' } });
    await r.image.undoRestart();
    expect(r.image.state.undo).toMatchObject({ at: 'fastboot', restarted: true });
    const before = r.flashed.length;
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    expect(r.flashed.slice(before)).toEqual(['system', 'userdata']);
    expect(r.image.state.log.join('\n')).toContain('as after a factory reset');
    // the log says which checks ran: everything before the first write, fastboot's OKAY on each write, and no read-back
    expect(r.image.state.log.join('\n')).toContain('fastboot accepted every write');
    expect(r.image.state.log.join('\n')).toMatch(/not read back: fastboot can't read a partition, and after the restore the Mirror no longer lets this browser in over adb/);
    expect(device()).toMatchObject({ installed: undefined, owner: undefined }); // the fresh userdata took the app and its owner
  });

  it('a stop after its system write: a reload opens the way back again, never the install, and writes only what is left', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    // the stage's userdata is n 0, the install's n 1, the way back's n 2
    const a = rig({ dev, store, progress, fail: (what, n) => what === 'flash userdata' && n === 1 });
    await toReceive(a);
    await toRestorePoint(a);
    await a.image.continueInstall();
    expect(progress.mark).toMatchObject({ at: 'received', wrote: ['system-in-place'] });
    const b = rig({ dev, store, progress, fail: (what) => what === 'flash userdata' });
    await b.image.startUndo();
    b.image.undoByHand();
    await b.image.undoFlash();
    expect(b.flashed).toEqual(['system']);
    expect(b.image.state.undo?.at).toBe('error');
    expect(b.closes()).toBe(1); // a failed write lets go of fastboot: Try again opens it fresh
    expect(progress.mark).toMatchObject({ at: 'undo', wrote: ['system'] });
    // the page reloads: the way back, on the restore point kept here, with its system write done
    const c = rig({ dev, store, progress });
    expect(c.image.state).toMatchObject({ screen: 'undo', changed: true });
    await vi.waitFor(() => expect(c.image.state.undo?.source).toBe('browser'));
    c.image.undoByHand();
    await c.image.undoFlash();
    expect(c.image.state.undo?.at).toBe('done');
    expect(c.flashed).toEqual(['userdata']);
    expect(c.image.state.log.join('\n')).toContain('already written by an earlier try');
    expect(progress.mark).toBeUndefined();
  });

  it('Try again after a restart that failed writes nothing again', async () => {
    const { r } = await installed();
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress, fail: (what, n) => what === 'reboot' && n === 0 });
    await b.image.startUndo();
    b.image.undoByHand();
    await b.image.undoFlash();
    expect(b.image.state.undo?.at).toBe('error');
    expect(b.flashed).toEqual(['system', 'userdata']);
    b.image.undoByHand();
    await b.image.undoFlash();
    expect(b.image.state.undo?.at).toBe('done');
    expect(b.flashed).toEqual(['system', 'userdata']);
  });

  it('runs the frame\'s undo over adb only on the device the restore point was made on', async () => {
    const before = vi.fn(async () => {});
    const r = rig({ code: { beforeRestore: before } });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    await r.image.connectAdb();
    // another Mirror that trusts this browser, on the cable for the way back
    const other = () => { const a = r.adb(); const run = a.run.bind(a); return Object.assign(a, { run: async (c: string) => (c === 'getprop ro.serialno' ? { out: '0000fake02\n', code: 0 } : run(c)) }); };
    const swapped = rig({ dev: r.dev, store: r.store, code: { beforeRestore: before }, deps: { requestAdb: async () => other() } });
    await swapped.image.startUndo();
    await swapped.image.undoRestart();
    expect(swapped.image.state.connectError).toMatch(/another device/);
    expect(before).not.toHaveBeenCalled();
    await r.image.startUndo();
    await r.image.undoRestart();
    expect(before).toHaveBeenCalledOnce();
  });

  it('refuses a restore point that doesn\'t say which device it was made on', async () => {
    const { r } = await installed();
    const kept = (await r.store.latestRestore())!;
    const files = kept.files.map((f) => {
      if (f.filename !== 'manifest.json') return f;
      const m = JSON.parse(new TextDecoder().decode(f.data)) as Record<string, unknown>;
      delete m.deviceIdHash;
      return { ...f, data: new TextEncoder().encode(JSON.stringify(m)) };
    });
    const zip = await restorePointZip('glassworks-in-place', files);
    const fresh = rig({ dev: r.dev });
    await fresh.image.startUndo();
    await fresh.image.pickRestoreZip(new NodeBlob([await bytesOf(zip.blob) as Uint8Array<ArrayBuffer>]) as unknown as Blob, zip.name);
    fresh.image.undoByHand();
    await fresh.image.undoFlash();
    expect(fresh.image.state.undo?.refused).toMatch(/doesn't say which device/);
    expect(fresh.flashed).toEqual([]);
  });

  it('an install after the way back asks only for what the in-place install needs, not the full image\'s system.img', async () => {
    const r = rig();
    await r.image.startUndo();
    r.image.backToChoice();
    r.image.choose();
    expect(r.image.recipe.mode).toBe('in-place');
    expect(r.image.state.files).toEqual([]);
    expect(r.image.blocked() ?? '').not.toMatch(/system\.img/);
  });

  it('doesn\'t open over an install that is writing: that install keeps its screens', async () => {
    let release!: () => void;
    const r = rig();
    await toReceive(r);
    await r.image.receive();
    const slow = rig({ dev: { ...r.dev, fastboot: () => { const f = r.dev.fastboot(); return { ...f, flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { await new Promise<void>((x) => { release = x; }); await f.flashBlob(p, b, on); } }; } }, store: r.store, progress: r.progress });
    await slow.image.connectFastboot();
    await readyRestorePoint(slow);
    await saveCopy(slow.image);
    const writing = slow.image.continueInstall();
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    await slow.image.startUndo();
    expect(slow.image.state.screen).toBe('working');
    for (let i = 0; i < 3; i++) { release(); await new Promise((x) => setTimeout(x, 5)); }
    await writing;
    expect(slow.image.state.screen).toBe('adb');
  });

  it('takes the downloaded zip in a browser that kept nothing, and refuses a damaged one', async () => {
    const { r, zip } = await installed();
    const fresh = rig({ dev: r.dev });
    await fresh.image.startUndo();
    expect(fresh.image.state.undo?.source).toBeUndefined();
    const bytes = await bytesOf(zip.blob);
    const damaged = bytes.slice();
    damaged[120] = damaged[120]! ^ 0xff; // inside the restore image's bytes
    await fresh.image.pickRestoreZip(new NodeBlob([damaged as Uint8Array<ArrayBuffer>]) as unknown as Blob, 'damaged.zip');
    expect(fresh.image.state.undo?.error).toBeTruthy();
    await fresh.image.pickRestoreZip(new NodeBlob([bytes as Uint8Array<ArrayBuffer>]) as unknown as Blob, zip.name);
    expect(fresh.image.state.undo).toMatchObject({ source: 'file', fileName: zip.name });
    fresh.image.undoByHand();
    await fresh.image.undoFlash();
    expect(fresh.image.state.undo?.at).toBe('done');
    expect(fresh.flashed).toEqual(['system', 'userdata']);
  });
});

describe('the way back checks everything it can before it writes', () => {
  async function installed(o: Parameters<typeof rig>[0] = {}) {
    const r = rig(o);
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    await r.image.connectAdb();
    return r;
  }
  /** This browser's restore point with one file swapped for `edit`'s; the record in its manifest stays as saved. */
  async function editKept(r: Rig, edit: (files: { filename: string; data: Uint8Array }[]) => { filename: string; data: Uint8Array }[]) {
    const kept = (await r.store.latestRestore())!;
    await r.store.putRestore(kept.serial, kept.digest, edit(kept.files));
  }
  const log = (r: Rig) => r.image.state.log.join('\n');
  /** adb on the same Mirror, with services.jar as `pull` gives it (something other than Kiln changed it, say). */
  const withJar = (r: Rig, pull: () => Promise<Uint8Array>, run?: (c: string) => { out: string; code: number } | undefined) => async () => {
    const a = r.adb();
    const was = a.run.bind(a);
    return Object.assign(a, { pull, run: async (c: string, s?: AbortSignal) => run?.(c) ?? was(c, s) });
  };

  it('over adb and in fastboot: each check is in the log, and only then the writes', async () => {
    const r = await installed();
    await r.image.startUndo();
    await r.image.undoRestart();
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    const l = log(r);
    expect(l).toContain('services.jar on the Mirror is what the install wrote: the restore point fits');
    expect(l).toContain('restore point: each of its 2 files has the size and SHA-256 recorded when it was saved');
    expect(l).toContain('restore point: made on this device');
    expect(l).toMatch(/restore image: puts back the original services\.jar \(16384 bytes, SHA-256 [0-9a-f]{12}…\) in its own blocks/);
    expect(l).toContain('restore image: writes no block outside those files');
    expect(l).toContain('the Mirror\'s files were checked over adb before the restart');
    // the checks come before the first write
    expect(l.indexOf('restore image: writes no block outside')).toBeLessThan(l.indexOf('Writing the restore point to system'));
    expect(l).not.toContain(FAKE_SERIAL);
  });

  it('by its buttons, with no adb: says the device\'s files weren\'t checked, and checks the rest', async () => {
    const r = await installed();
    await r.image.startUndo();
    r.image.undoByHand();
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    expect(log(r)).toContain('not checked: the Mirror\'s files as they are now (Kiln didn\'t reach it over adb before this restart)');
    expect(log(r)).toContain('restore image: writes no block outside those files');
  });

  it('a file that changed since the restore point was saved: nothing is written, and the screen says why with a way out', async () => {
    const r = await installed();
    await editKept(r, (files) => files.map((f) => (f.filename === 'services.jar.orig' ? { ...f, data: f.data.map((x, i) => (i === 0 ? x ^ 1 : x)) } : f)));
    await r.image.startUndo();
    r.image.undoByHand();
    const before = r.flashed.length;
    await r.image.undoFlash();
    expect(r.image.state.undo?.refused).toBe('services.jar.orig isn\'t the file Kiln saved in this restore point. Nothing was written.');
    expect(r.flashed.length).toBe(before);
    expect(r.closes()).toBe(1); // the refusal lets go of fastboot: Pick another restore point connects again
    expect(r.progress.mark?.at).not.toBe('undo'); // no checkpoint: the way back didn't begin
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    expect(screen.getByRole('heading', { name: 'Kiln won’t write this restore point' })).toBeTruthy();
    expect(screen.getByText(/services\.jar\.orig isn't the file Kiln saved/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Pick another restore point' })).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Choose another install' })); });
    expect(r.image.state.screen).toBe('choose');
  });

  it('a restore point missing a file it recorded is refused', async () => {
    const r = await installed();
    await editKept(r, (files) => files.filter((f) => f.filename !== 'services.jar.orig'));
    await r.image.startUndo();
    r.image.undoByHand();
    await r.image.undoFlash();
    expect(r.image.state.undo?.refused).toMatch(/^This restore point is missing services\.jar\.orig\./);
  });

  it('a manifest the frame can\'t read is refused', async () => {
    const r = await installed();
    await editKept(r, (files) => files.map((f) => {
      if (f.filename !== 'manifest.json') return f;
      const m = JSON.parse(new TextDecoder().decode(f.data)) as Record<string, unknown>;
      delete m.servicesJar;
      return { ...f, data: new TextEncoder().encode(JSON.stringify(m)) };
    }));
    await r.image.startUndo();
    r.image.undoByHand();
    await r.image.undoFlash();
    expect(r.image.state.undo?.refused).toBe('The restore point\'s manifest doesn\'t describe services.jar. Nothing was written.');
  });

  it('a manifest that names another path is refused before anything reaches the device\'s shell', async () => {
    const r = await installed();
    await editKept(r, (files) => files.map((f) => {
      if (f.filename !== 'manifest.json') return f;
      const m = JSON.parse(new TextDecoder().decode(f.data)) as { servicesJar: Record<string, unknown> };
      m.servicesJar.path = '/nope; reboot';
      return { ...f, data: new TextEncoder().encode(JSON.stringify(m)) };
    }));
    const ran: string[] = [];
    const pulled: string[] = [];
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress, deps: { requestAdb: async () => {
      const a = await withJar(r, async () => { throw new Error('No such file or directory'); }, (c) => { ran.push(c); return undefined; })();
      const pull = a.pull;
      return Object.assign(a, { pull: async (path: string, ...rest: unknown[]) => { pulled.push(path); return (pull as (...x: unknown[]) => Promise<Uint8Array>)(path, ...rest); } });
    } } });
    await b.image.startUndo();
    await b.image.undoRestart();
    expect(b.image.state.undo?.refused).toBe('The restore point\'s manifest puts services.jar somewhere other than /system/framework/services.jar. Nothing was written.');
    expect(b.image.state.undo?.restarted).toBeUndefined();
    expect(ran.filter((c) => c.includes('nope') || c.includes('reboot'))).toEqual([]);
    expect(pulled).toEqual([]);
    expect(b.flashed).toEqual([]);
  });

  it('a restore point from before the record still goes, checked against the original file it keeps', async () => {
    const r = await installed();
    await editKept(r, (files) => files.map((f) => {
      if (f.filename !== 'manifest.json') return f;
      const m = JSON.parse(new TextDecoder().decode(f.data)) as Record<string, unknown>;
      delete m.kiln;
      return { ...f, data: new TextEncoder().encode(JSON.stringify(m)) };
    }));
    await r.image.startUndo();
    r.image.undoByHand();
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    expect(log(r)).toMatch(/made before Kiln recorded each file's SHA-256/);
  });

  it('over adb, a file something else changed since: no restart into fastboot, nothing written', async () => {
    const r = await installed();
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress, deps: { requestAdb: withJar(r, async () => new Uint8Array(16384).fill(7)) } });
    await b.image.startUndo();
    await b.image.undoRestart();
    expect(b.image.state.undo).toMatchObject({ at: 'start', refused: 'services.jar on the Mirror has changed since this restore point was made, so the restore may no longer fit it. Nothing was changed.' });
    expect(b.image.state.undo?.restarted).toBeUndefined();
    expect(b.flashed).toEqual([]);
  });

  it('over adb, a file that\'s gone is refused; one Kiln can\'t read is logged as unchecked, and the restore goes on', async () => {
    const r = await installed();
    const gone = rig({ dev: r.dev, store: r.store, progress: r.progress, deps: { requestAdb: withJar(r, async () => { throw new Error('No such file or directory'); },
      (c) => (c.startsWith('ls ') ? { out: `ls: ${c.slice(3)}: No such file or directory\n`, code: 1 } : undefined)) } });
    await gone.image.startUndo();
    await gone.image.undoRestart();
    expect(gone.image.state.undo?.refused).toMatch(/^services\.jar is no longer on the Mirror, so this restore point doesn't fit its system anymore\. Nothing was changed\.$/);
    const unread = rig({ dev: r.dev, store: r.store, progress: r.progress, deps: { requestAdb: withJar(r, async () => { throw new Error('Permission denied'); }) } });
    await unread.image.startUndo();
    await unread.image.undoRestart();
    expect(unread.image.state.undo).toMatchObject({ at: 'fastboot', restarted: true });
    expect(log(unread)).toContain('not checked: services.jar (Kiln couldn\'t read it: Permission denied)');
    await unread.image.undoFlash();
    expect(unread.image.state.undo?.at).toBe('done');
    expect(log(unread)).toContain('not checked: the Mirror\'s files as they are now');
  });

  it('after a write the system didn\'t take, the original file is what the restore expects too', async () => {
    const r = rig({ systemNotTaken: true });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    await r.image.connectAdb();
    expect(r.image.state.notTaken).toBe(true);
    await r.image.restoreAfterError();
    await vi.waitFor(() => expect(r.image.state.undo?.source).toBe('browser'));
    await r.image.undoRestart();
    expect(log(r)).toContain('services.jar on the Mirror is still the original: the restore writes the same bytes back');
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
  });
});

describe('the app\'s install after a reload', () => {
  it('keeps the APK picker, and Connect shut, until an APK passes its checks', async () => {
    const progress = tab();
    progress.mark = { at: 'flashed', image: RECIPE.id };
    const r = rig({ progress });
    expect(r.image.state).toMatchObject({ screen: 'adb', round: 2 });
    const app = new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true);
    await app.pickApk('not-an-apk.apk', new Uint8Array([1, 2, 3]));
    expect(app.state.apk?.error).toBeTruthy();
    render(<ImageFlow image={r.image} app={app} />);
    // the resume screen first, with the restore point beside Continue
    expect(screen.getByRole('heading', { name: 'Pick up where you left off' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Restore the original system' }).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    const picker = () => document.querySelector('input[type=file][accept*=".apk"]');
    expect(picker()).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Connect' }).hasAttribute('disabled')).toBe(true);
    await act(async () => { await app.pickApk('glassworks.apk', mirrorEntry.sim.apk()); });
    expect(picker()).toBeNull();
    expect(screen.getByRole('button', { name: 'Connect' }).hasAttribute('disabled')).toBe(false);
  });
});

describe('a stop in the second fastboot session', () => {
  async function stopped(fail: (what: string, n: number) => boolean) {
    const r = rig({ fail });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('error');
    return r;
  }

  it('Try again writes the system again when its write failed, rather than skipping to userdata', async () => {
    const r = await stopped((what, n) => what === 'flash system' && n === 0);
    expect(r.flashed).toEqual(['userdata']);
    expect(r.image.tryAgainLands()).toBe('fastboot');
    r.image.tryAgain();
    await r.image.connectFastboot();
    await vi.waitFor(() => expect(r.image.state.screen).toBe('adb'));
    expect(r.flashed).toEqual(['userdata', 'system', 'userdata']);
  });

  it('a restart that failed is tried again on the same round, with nothing staged or written again', async () => {
    const r = await stopped((what, n) => what === 'reboot' && n === 1); // the stage's restart is n 0
    expect(r.flashed).toEqual(['userdata', 'system', 'userdata']);
    expect(r.image.tryAgainLands()).toBe('fastboot');
    r.image.tryAgain();
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
    await r.image.connectFastboot();
    await vi.waitFor(() => expect(r.image.state.screen).toBe('adb'));
    expect(r.flashed).toEqual(['userdata', 'system', 'userdata']);
  });

  it('offers the restore point from the error once it is saved, and writes it with a fresh userdata', async () => {
    const r = await stopped((what, n) => what === 'flash userdata' && n === 1);
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    fireEvent.click(screen.getByRole('button', { name: 'Restore the original system' }));
    expect(r.image.state).toMatchObject({ screen: 'undo', undo: { at: 'fastboot', source: 'browser' } });
    await act(async () => { await r.image.undoFlash(); });
    expect(r.image.state.undo?.at).toBe('done');
    expect(r.flashed).toEqual(['userdata', 'system', 'system', 'userdata']);
  });
});

describe('the app\'s install on the device it wrote', () => {
  it('turns away another device that trusts this browser, and stays on the step to connect the right one', async () => {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    expect(r.progress.mark).toMatchObject({ at: 'flashed', serial: FAKE_SERIAL });
    const other = () => { const a = r.adb(); const run = a.run.bind(a); return Object.assign(a, { run: async (c: string) => (c === 'getprop ro.serialno' ? { out: '0000fake02\n', code: 0 } : run(c)) }); };
    // after a reload, on the mark
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress, deps: { requestAdb: async () => other() } });
    expect(b.image.state.screen).toBe('adb');
    await b.image.connectAdb();
    expect(b.image.state).toMatchObject({ screen: 'adb', connectError: expect.stringMatching(/another device/) });
    expect((r.dev.adb() as unknown as { installed?: number }).installed).toBeUndefined();
    await r.image.connectAdb();
    expect(r.image.state.firstBoot).toBeTruthy();
  });

  it('a stop in the app\'s install still opens the way back, on the restore point this browser saved', async () => {
    const r = rig({ code: { appInstall: async () => { throw new Error('pm install failed'); } } });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    await r.image.connectAdb();
    expect(r.image.state).toMatchObject({ screen: 'error', work: { step: 'app-install' } });
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Restore the original system' })); });
    await vi.waitFor(() => expect(r.image.state).toMatchObject({ screen: 'undo', undo: { at: 'start', source: 'browser' } }));
  });
});

describe('the way back after the review\'s second round', () => {
  it('a stop in the app\'s install after a reload opens this run\'s restore point, never a newer one of another device', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    const a = rig({ dev, store, progress });
    await toReceive(a);
    await toRestorePoint(a);
    await a.image.continueInstall();
    expect(progress.mark).toMatchObject({ at: 'flashed', serial: FAKE_SERIAL, digest: expect.any(String) });
    const own = (await store.latestRestore())!;
    // another Mirror's restore point, kept in this browser after this one
    await store.putRestore('0000fake02', 'other', own.files.map((f) => (f.filename === 'manifest.json' ? { ...f, data: new TextEncoder().encode('{"deviceIdHash":"' + '1'.repeat(64) + '"}') } : f)));
    const b = rig({ dev, store, progress, code: { appInstall: async () => { throw new Error('pm install failed'); } } });
    await b.image.connectAdb();
    expect(b.image.state).toMatchObject({ screen: 'error', work: { step: 'app-install' } });
    render(<ImageFlow image={b.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Restore the original system' })); });
    await vi.waitFor(() => expect(b.image.state).toMatchObject({ screen: 'undo', undo: { at: 'start', source: 'browser' } }));
    await b.image.undoRestart();
    await b.image.undoFlash();
    expect(b.image.state.undo?.at).toBe('done'); // this Mirror's own: the other one would have been refused
  });

  it('a way back from a picked zip that stops goes on with that zip after a reload, and only on the device it began', async () => {
    const { r, zip } = await (async () => {
      const x = rig();
      await toReceive(x);
      await toRestorePoint(x);
      const z = await saveCopy(x.image);
      await x.image.continueInstall();
      await x.image.connectAdb();
      return { r: x, zip: z };
    })();
    const store = memoryInPlaceStore(); // a browser that kept nothing
    const progress = tab();
    const a = rig({ dev: r.dev, store, progress, fail: (what) => what === 'flash userdata' });
    await a.image.startUndo();
    await a.image.pickRestoreZip(new NodeBlob([await bytesOf(zip.blob) as Uint8Array<ArrayBuffer>]) as unknown as Blob, zip.name);
    a.image.undoByHand();
    await a.image.undoFlash();
    expect(a.flashed).toEqual(['system']);
    expect(progress.mark).toMatchObject({ at: 'undo', serial: FAKE_SERIAL, wrote: ['system'], restore: expect.any(Object) });
    const b = rig({ dev: r.dev, store, progress });
    await vi.waitFor(() => expect(b.image.state).toMatchObject({ screen: 'undo', undo: { source: 'browser' } }));
    // what was written belongs to the device it was written on: a mark from another one writes everything
    const c = rig({ dev: r.dev, store, progress: Object.assign(tab(), { mark: { ...progress.mark!, serial: '0000fake02' } }) });
    await vi.waitFor(() => expect(c.image.state.undo?.source).toBe('browser'));
    c.image.undoByHand();
    await c.image.undoFlash();
    expect(c.flashed).toEqual(['system', 'userdata']);
    b.image.undoByHand();
    await b.image.undoFlash();
    expect(b.image.state.undo?.at).toBe('done');
    expect(b.flashed).toEqual(['userdata']);
  });
});

describe('checkpoints before a restart or a write', () => {
  it('the stage, once written, resumes on receive after a reload, even when its restart didn\'t happen', async () => {
    const dev = sim.device({});
    const progress = tab();
    const a = rig({ dev, progress, fail: (what, n) => what === 'reboot' && n === 0 });
    a.image.pick(RECIPE.id);
    a.image.choose();
    a.image.toFastboot();
    await a.image.connectFastboot();
    a.image.understand(true);
    await a.image.flash();
    expect(a.image.state.screen).toBe('error');
    expect(progress.mark).toMatchObject({ at: 'staged', serial: FAKE_SERIAL, secret: expect.any(String) });
    const b = rig({ dev, progress });
    expect(b.image.state).toMatchObject({ screen: 'receive', round: 1 });
  });

  it('a system write that stopped partway counts: a lost bundle then offers the way back, not a new install', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    const a = rig({ dev, store, progress, fail: (what, n) => what === 'flash system' && n === 0 });
    await toReceive(a);
    await toRestorePoint(a);
    await a.image.continueInstall();
    expect(progress.mark).toMatchObject({ at: 'received', wrote: [], began: ['system-in-place'] });
    const b = rig({ dev, progress, store: Object.assign(memoryInPlaceStore(), { getRestore: store.getRestore }) });
    await b.image.connectFastboot();
    await vi.waitFor(() => expect(b.image.state.screen).toBe('error'));
    expect(b.image.state.bundleLost).toBe('restore');
  });
});

describe('a bundle this browser no longer has', () => {
  it('a file from this run\'s agent is taken after a Bluetooth attempt that read another description', async () => {
    const secret = new Uint8Array(32).fill(5);
    const r = rig({ dev: sim.device({ lan: 'offline', bleFrameDelayMs: 40 }), deps: { secret: () => secret } });
    await toReceive(r);
    const p = r.image.receive();
    await vi.waitFor(() => expect(r.image.state.receive?.pageUrl).toBeTruthy());
    await r.image.cancelReceive();
    await p;
    const other = await buildFakeGwsbBundle({ secret, jarBytes: new Uint8Array(16384).fill(4) });
    await r.image.receiveFile(new NodeBlob([other.bytes as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(r.image.state.receive?.error ?? null).toBeNull();
    expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
  }, 20_000);

  it('with nothing of the second session written: Try again starts the install over from its first screen', async () => {
    const dev = sim.device({});
    const progress = tab();
    const a = rig({ dev, progress });
    await toReceive(a);
    await a.image.receive();
    // the page reloads in a browser that dropped the bundle
    const b = rig({ dev, progress, store: memoryInPlaceStore() });
    await b.image.connectFastboot();
    await vi.waitFor(() => expect(b.image.state.screen).toBe('error'));
    expect(b.image.state).toMatchObject({ bundleLost: 'restart', error: expect.stringMatching(/no longer has the bundle/) });
    expect(b.image.tryAgainLands()).toBe('image');
    b.image.tryAgain();
    expect(b.image.state).toMatchObject({ screen: 'image', bundleLost: undefined });
    expect(b.flashed).toEqual([]);
  });

  it('after a write, or a kept copy that isn\'t the one the writes came from: the way back, never staging again', async () => {
    const dev = sim.device({});
    const store = memoryInPlaceStore();
    const progress = tab();
    const a = rig({ dev, store, progress, fail: (what, n) => what === 'flash userdata' && n === 1 });
    await toReceive(a);
    await toRestorePoint(a);
    await a.image.continueInstall();
    expect(progress.mark).toMatchObject({ at: 'received', wrote: ['system-in-place'], bundle: expect.stringMatching(/^[0-9a-f]{64}$/) });
    progress.mark = { ...progress.mark!, bundle: '0'.repeat(64) }; // the same file's digest, another whole bundle
    const b = rig({ dev, store, progress });
    await b.image.connectFastboot();
    await vi.waitFor(() => expect(b.image.state.screen).toBe('error'));
    expect(b.image.state).toMatchObject({ bundleLost: 'restore', error: expect.stringMatching(/isn't the one this install wrote from/) });
    expect(b.flashed).toEqual([]);
    render(<ImageFlow image={b.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Restore the original system' })); });
    await vi.waitFor(() => expect(b.image.state).toMatchObject({ screen: 'undo', undo: { at: 'fastboot', source: 'browser' } }));
  });
});

describe('the system write, read back after the restart', () => {
  async function toAppInstall(o: Parameters<typeof rig>[0] = {}) {
    const r = rig(o);
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('adb');
    return r;
  }
  const installed = (r: Rig) => (r.dev.adb() as unknown as { installed?: number }).installed;

  it('keeps what to read back in the tab\'s mark: paths and hashes, from the frame\'s build', async () => {
    const r = await toAppInstall();
    expect(r.progress.mark).toMatchObject({ at: 'flashed', readBack: [{ path: '/system/framework/services.jar', sha256: expect.stringMatching(/^[0-9a-f]{64}$/) }] });
  });

  it('a file that isn\'t what Kiln wrote stops before the app\'s install, with the way back as its one action', async () => {
    const r = await toAppInstall({ systemNotTaken: true });
    await r.image.connectAdb();
    expect(r.image.state).toMatchObject({ screen: 'error', notTaken: true, work: { step: 'app-install' } });
    expect(r.image.state.error).toMatch(/services\.jar on the Mirror isn't what Kiln wrote \(SHA-256 [0-9a-f]{12}…, expected [0-9a-f]{12}…\)\. Nothing was installed\./);
    expect(installed(r)).toBeUndefined();
    expect(r.image.state.log.join('\n')).not.toContain('Installing');
    render(<ImageFlow image={r.image} app={new AppSession({ frame, connect: async () => { throw new Error('not here'); } }, true)} />);
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    const back = screen.getByRole('button', { name: 'Restore the original system' });
    expect(back.className).toContain('ember');
    await act(async () => { fireEvent.click(back); });
    await vi.waitFor(() => expect(r.image.state).toMatchObject({ screen: 'undo', undo: { at: 'start', source: 'browser' } }));
  });

  it('a file Kiln can\'t read is said plainly in the log, and the install goes on', async () => {
    const r = await toAppInstall({ unreadable: true });
    await r.image.connectAdb();
    expect(r.image.state.firstBoot).toBeTruthy();
    const log = r.image.state.log.join('\n');
    expect(log).toContain('Not read back: services.jar');
    expect(log).toMatch(/couldn't read \/system\/framework\/services\.jar back from the Mirror \(.*Permission denied\), so this write is unchecked/);
  });

  it('a reload that resumes on the app\'s install still reads back, from the mark', async () => {
    const r = await toAppInstall();
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress });
    expect(b.image.state.screen).toBe('adb');
    await b.image.connectAdb();
    expect(b.image.state.firstBoot).toBeTruthy();
    expect(b.image.state.log.join('\n')).toContain('services.jar on the Mirror matches what Kiln wrote');
  });

  it('a reload after a write that didn\'t take still stops there', async () => {
    const r = await toAppInstall({ systemNotTaken: true });
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress });
    await b.image.connectAdb();
    expect(b.image.state).toMatchObject({ screen: 'error', notTaken: true });
    expect(installed(r)).toBeUndefined();
  });

  it('a mark from before the read back says it can\'t check, and goes on', async () => {
    const r = await toAppInstall();
    const { readBack: _, ...old } = r.progress.mark!;
    r.progress.mark = old;
    const b = rig({ dev: r.dev, store: r.store, progress: r.progress });
    await b.image.connectAdb();
    expect(b.image.state.firstBoot).toBeTruthy();
    expect(b.image.state.log.join('\n')).toContain('no record of which files the install changed');
  });

  it('the simulated Mirror\'s file follows the writes: Kiln\'s, then the way back\'s stock one', async () => {
    const r = await toAppInstall();
    const jar = () => r.dev.adb().pull!('/system/framework/services.jar').then(sha256Hex);
    const stock = await sha256Hex((await buildFakeGwsbBundle()).jarBytes);
    expect(await jar()).toBe(r.progress.mark?.readBack?.[0]?.sha256);
    expect(await jar()).not.toBe(stock);
    await r.image.startUndo();
    await r.image.undoRestart();
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    expect(await jar()).toBe(stock);
  });
});

describe('routes and screens', () => {
  it('each screen is on its step\'s route, and undo has its own', () => {
    const routes = installRoutes(mirrorFrame.id);
    expect(routes.inPlace).toEqual({ stock: 'stock', 'stock-fastboot': 'stock/fastboot', stage: 'stock/stage', receive: 'stock/receive', install: 'stock/install', 'first-start': 'stock/first-start' });
    expect(routes.image).toMatchObject({ image: '', fastboot: 'fastboot' });
    const ids = Object.keys(routes.inPlace);
    const at = (s: Partial<ImageSession['state']>) => inPlaceStep({ screen: 'choose', picked: '', open: false, link: false, files: [], wifi: { ssid: '', password: '' }, understood: false, log: [], ...s }, ids);
    expect(at({ screen: 'image' })).toBe('stock');
    expect(at({ screen: 'fastboot', round: 1 })).toBe('stock-fastboot');
    expect(at({ screen: 'working', round: 1, work: { step: 'stage', x: 0 } })).toBe('stage');
    expect(at({ screen: 'receive', round: 1 })).toBe('receive');
    expect(at({ screen: 'restore-point', round: 2 })).toBe('install');
    expect(at({ screen: 'adb', round: 2 })).toBe('first-start');
    // a kept session back on the choice after the app's install connected: the choice's route, not the install's
    expect(at({ screen: 'choose', round: 2, work: { step: 'app-install', x: 1 } })).toBe('stock');
    expect(Object.keys(routesOf(mirrorFrame.id))).toEqual(expect.arrayContaining(['undo', 'undo/connect', 'undo/restore', 'undo/done']));
  });

  it.each(['place-start', 'place-fastboot', 'place-confirm', 'place-receive-file', 'place-restore-point', 'place-restore-offered', 'place-restore-copied', 'place-fastboot-again', 'place-connect', 'place-first-start', 'place-installing',
    'place-error-write', 'place-undo', 'place-undo-fastboot', 'place-undo-working', 'place-undo-error', 'place-undo-done', 'place-undo-refused', 'place-error-bundle', 'place-error-lost', 'place-error-not-taken'] as const)('%s renders, with one ember button at most', async (d) => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, d, true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    await act(async () => {});
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    expect(document.body.textContent).not.toContain(FAKE_SERIAL);
    expect(document.body.textContent).not.toContain('Check every part'); // the restore isn't read back, so no screen says it is
  }, 20_000);

  it('place-undo-refused stops on the refusal, with nothing written and the way out', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'place-undo-refused', true);
    expect(image.state.undo?.refused).toMatch(/isn't the file Kiln saved in this restore point\. Nothing was written\./);
    render(<ImageFlow image={image} app={app} />);
    await act(async () => {});
    expect(screen.getByRole('heading', { name: 'Kiln won’t write this restore point' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose another install' }).className).toContain('ember');
  });

  it('the first screen says the whole road up front: every part, in order, the person\'s marked, with how long', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'place-start', true);
    render(<ImageFlow image={image} app={app} />);
    const road = [...document.querySelectorAll('.road-list li')].map((li) => {
      const time = li.querySelector('.road-time')?.textContent;
      // the person's rows carry the marker and, when there is one, the estimate too
      expect(!!li.querySelector('.road-you')).toBe(li.classList.contains('you'));
      return `${li.classList.contains('you') ? 'You: ' : ''}${li.querySelector('.road-t')!.textContent}${time ? ` (${time})` : ''}`;
    });
    expect(road).toEqual([
      'You: Put the Mirror in fastboot mode (about 10 minutes)',
      'Kiln adds a small setup helper. The original system isn’t touched',
      'You: Join the Mirror’s own Wi-Fi, so Kiln can copy the parts of its system it will change (5–7 minutes)',
      'You: Fastboot mode once more',
      'Kiln prepares the changes and checks them',
      'You: Kiln saves a restore point, and you download a copy',
      'Kiln writes the changes',
      'It starts for the first time, and Kiln installs Glassworks (4–5 minutes)',
      'You: Set it up: Wi-Fi, Home Assistant and panes',
    ]);
    expect(document.querySelector('.road-h')!.textContent).toBe('What happens · 20–30 minutes');
  });

  it('the in-place screens say no plumbing: no bundle, no agent, no SHA-256, no sparse', async () => {
    for (const d of ['place-start', 'place-fastboot', 'place-confirm', 'place-join-wifi', 'place-joined', 'place-receive-ble', 'place-receive-lan', 'place-wifi-unreachable', 'place-lna-denied',
      'place-receive-file', 'place-error-bundle', 'place-fastboot-again', 'place-restore-point', 'place-working', 'place-connect', 'place-first-start', 'place-resume', 'place-resume-staged',
      'place-undo', 'place-undo-fastboot'] as const) {
      const { image, app } = await imageDemo(mirrorEntry, d, true);
      render(<ImageFlow image={image} app={app} />);
      await act(async () => {});
      // what a person reads: the page without its technical log, which is for help
      const main = document.querySelector('main')!.cloneNode(true) as HTMLElement;
      main.querySelectorAll('.details').forEach((x) => x.remove());
      const rail = document.querySelector('.rail')!.textContent ?? '';
      expect(`${main.textContent} ${rail}`, d).not.toMatch(/bundle|agent|SHA-256|sparse|Refract/i);
      cleanup();
    }
  }, 60_000);

  it('the wait for the first start has its own row after the restart, and the list never goes back', async () => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, 'place-working', true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    const cur = () => document.querySelector('.prow.now .plabel')?.textContent;
    const done = () => [...document.querySelectorAll('.prow.done .plabel')].map((x) => x.textContent);
    await act(async () => { (image as unknown as { set(p: object): void }).set({ screen: 'working', round: 2, work: { step: 'restart', x: 0 } }); });
    expect(cur()).toBe('Restart the Mirror');
    await act(async () => { (image as unknown as { set(p: object): void }).set({ work: { step: 'first-boot', x: 0 } }); });
    expect(cur()).toBe('Let it finish its first start');
    expect(done()).toEqual(['Prepare the changes and check them', 'Write the new system', 'Start its app storage fresh', 'Restart the Mirror', 'Connect to the Mirror']);
    expect(screen.getByText('Usually 4–5 minutes')).toBeTruthy();
    await act(async () => { (image as unknown as { set(p: object): void }).set({ work: { step: 'app-install', x: 0 } }); });
    expect(cur()).toBe('Install Glassworks and hide the original apps');
    // a step reported late never pulls the list back
    await act(async () => { (image as unknown as { set(p: object): void }).set({ work: { step: 'in-place', x: 0 } }); });
    expect(cur()).toBe('Install Glassworks and hide the original apps');
  }, 20_000);

  it('the way back has its own rail, not the install\'s', () => {
    const at = (undo: { at: 'start' | 'fastboot' | 'working' | 'error' | 'done' }) =>
      imageRail({ screen: 'undo', undo, picked: RECIPE.id, files: [], wifi: { ssid: '', password: '' }, understood: false, log: [] } as never, mirrorFrame, false, true);
    expect(at({ at: 'start' }).map((x) => x.name)).toEqual(['Restore point', 'Fastboot', 'Restore', 'Done']);
    expect(at({ at: 'working' }).map((x) => x.mark)).toEqual(['done', 'done', 'now', 'todo']);
    expect(at({ at: 'error' })[2]!.mark).toBe('bad');
    expect(at({ at: 'done' }).every((x) => x.mark === 'done')).toBe(true);
  });

  it('place-error-lost stops on the lost bundle after a write, with the way back as its one action', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'place-error-lost', true, {}, handTo().handOver);
    expect(image.state).toMatchObject({ screen: 'error', bundleLost: 'restore' });
    render(<ImageFlow image={image} app={app} />);
    expect(screen.getByRole('button', { name: 'Restore the original system' }).className).toContain('ember');
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  }, 20_000);

  it('the restore point\'s Continue opens once it is saved and checked, and the person confirms a copy is on the computer', async () => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, 'place-restore-point', true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    expect(screen.getByText(/Saved in this browser/)).toBeTruthy();
    const go = () => screen.getByRole('button', { name: 'Continue' });
    expect(go().hasAttribute('disabled')).toBe(true);
    // why, in one line beside it
    expect(go().getAttribute('aria-describedby')).toBe('continue-why');
    expect(document.getElementById('continue-why')!.textContent).toBe('Continue opens once a copy is on this computer.');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Download a copy' })); });
    // the browser can't say the download landed: Continue waits for the person to say it's saved
    const saved = await vi.waitFor(() => screen.getByRole('checkbox', { name: 'I have the .zip saved on this computer' }));
    expect(go().hasAttribute('disabled')).toBe(true);
    expect(document.getElementById('continue-why')!.textContent).toBe('Continue opens once you confirm the copy is saved.');
    expect(screen.getByRole('button', { name: 'Download again' })).toBeTruthy();
    await act(async () => { fireEvent.click(saved); });
    await vi.waitFor(() => expect(go().hasAttribute('disabled')).toBe(false));
    expect(go().className).toContain('ember');
    expect(document.getElementById('continue-why')).toBeNull();
    expect(screen.getByText('A copy is on this computer')).toBeTruthy();
  });
});

function bytesOf(b: Blob): Promise<Uint8Array> {
  if (typeof b.arrayBuffer === 'function') return b.arrayBuffer().then((x) => new Uint8Array(x));
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer));
    r.onerror = () => reject(r.error);
    r.readAsArrayBuffer(b);
  });
}

