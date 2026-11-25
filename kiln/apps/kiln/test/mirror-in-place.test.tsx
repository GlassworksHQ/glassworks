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

interface Rig { image: ImageSession; dev: InPlaceSimDevice; store: InPlaceStore; progress: ReturnType<typeof tab>; flashed: string[]; adb: () => PanelShell }

/** A session on a simulated stock Mirror; `store` and `progress` carry over to a second browser load or a second run. */
function rig(o: { lan?: 'ok' | 'drop-midway' | 'offline' | 'lna-denied'; tampered?: boolean; refusedBuild?: boolean; store?: InPlaceStore; progress?: ReturnType<typeof tab>;
  dev?: InPlaceSimDevice; deps?: Partial<ImageDeps>; code?: Partial<InPlaceCode>;
  /** a write (`flash <partition>`) or a restart (`reboot`) that fails, by what it is and how many such calls came before it */
  fail?: (what: string, n: number) => boolean } = {}): Rig {
  const dev = o.dev ?? sim.device({ lan: o.lan ?? 'ok', tampered: o.tampered, refusedBuild: o.refusedBuild });
  const store = o.store ?? memoryInPlaceStore();
  const progress = o.progress ?? tab();
  const flashed: string[] = [];
  const calls = new Map<string, number>();
  const failing = (what: string) => { const n = calls.get(what) ?? 0; calls.set(what, n + 1); if (o.fail?.(what, n)) throw new Error(`the bootloader stopped answering (${what})`); };
  const fastboot = () => {
    const fb = dev.fastboot();
    return { ...fb,
      flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { failing(`flash ${p}`); await fb.flashBlob(p, b, on); flashed.push(p); },
      reboot: async () => { failing('reboot'); await fb.reboot(); } };
  };
  const frame_ = o.code ? { ...frame, imageFlow: { ...frame.imageFlow, inPlace: { ...sim.code, ...o.code } } } : frame;
  const adb = () => { const a = dev.adb(); return Object.assign(withAppStreams(a, { online: true, radio: false }), { rebootToBootloader: () => a.rebootToBootloader!() }); };
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
  return { image, dev, store, progress, flashed, adb };
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
  if (copy) await r.image.restoreZip();
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
    expect(r.image.state.restorePoint?.downloaded).toBe(true);
    const { files } = await readNamedBackupZip(new NodeBlob([await bytesOf(blob) as Uint8Array<ArrayBuffer>]) as unknown as Blob);
    expect(files.map((f) => f.filename).sort()).toEqual(['manifest.json', 'restore-services.sparse']);
    const manifest = JSON.parse(new TextDecoder().decode(files.find((f) => f.filename === 'manifest.json')!.data)) as { servicesJar: { restoreSparse: string } };
    expect(manifest.servicesJar.restoreSparse).toBe('restore-services.sparse');
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

  it('not once a system write began: its error, and a reload after it, offer the restore point instead', async () => {
    const r = rig({ fail: (what, n) => what === 'flash system' && n === 0 });
    await toReceive(r);
    await toRestorePoint(r);
    await r.image.continueInstall();
    expect(r.image.state.screen).toBe('error');
    expect(r.image.canStartOver()).toBe(false);
    expect(r.progress.mark?.started).toContain('system-in-place');
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
    await b.image.restoreZip();
    const c = rig({ dev, store, progress });
    await c.image.connectFastboot();
    await readyRestorePoint(c);
    expect(c.image.state.restorePoint?.downloaded).toBe(true);
    expect(c.image.state.log.join('\n')).toMatch(/a copy of it was downloaded from this browser before/);
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
  });
});

describe('the way back (restore-blocks)', () => {
  async function installed() {
    const r = rig();
    await toReceive(r);
    await toRestorePoint(r);
    const zip = await r.image.restoreZip();
    await r.image.continueInstall();
    await r.image.connectAdb();
    return { r, zip };
  }

  it('writes this browser\'s restore point over the blocks it changed, then a fresh userdata, so nothing the app set stays', async () => {
    const { r } = await installed();
    await r.image.startUndo();
    expect(r.image.state).toMatchObject({ screen: 'undo', undo: { at: 'start', source: 'browser' } });
    await r.image.undoRestart();
    expect(r.image.state.undo).toMatchObject({ at: 'fastboot', restarted: true });
    const before = r.flashed.length;
    await r.image.undoFlash();
    expect(r.image.state.undo?.at).toBe('done');
    expect(r.flashed.slice(before)).toEqual(['system', 'userdata']);
    expect(r.image.state.log.join('\n')).toContain('as after a factory reset');
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
    expect(fresh.image.state.undo?.error).toMatch(/doesn't say which device/);
    expect(fresh.flashed).toEqual([]);
  });

  it('doesn\'t open over an install that is writing: that install keeps its screens', async () => {
    let release!: () => void;
    const r = rig();
    await toReceive(r);
    await r.image.receive();
    const slow = rig({ dev: { ...r.dev, fastboot: () => { const f = r.dev.fastboot(); return { ...f, flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { await new Promise<void>((x) => { release = x; }); await f.flashBlob(p, b, on); } }; } }, store: r.store, progress: r.progress });
    await slow.image.connectFastboot();
    await readyRestorePoint(slow);
    await slow.image.restoreZip();
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
    expect(Object.keys(routesOf(mirrorFrame.id))).toEqual(expect.arrayContaining(['undo', 'undo/connect', 'undo/restore', 'undo/done']));
  });

  it.each(['place-start', 'place-fastboot', 'place-confirm', 'place-receive-file', 'place-restore-point', 'place-restore-copied', 'place-fastboot-again', 'place-connect', 'place-first-start', 'place-installing',
    'place-error-write', 'place-undo', 'place-undo-fastboot', 'place-undo-working', 'place-undo-error', 'place-undo-done', 'place-error-bundle'] as const)('%s renders, with one ember button at most', async (d) => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, d, true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    await act(async () => {});
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    expect(document.body.textContent).not.toContain(FAKE_SERIAL);
  }, 20_000);

  it('the first screen says the whole road up front: every part, in order, the person\'s marked, with how long', async () => {
    const { image, app } = await imageDemo(mirrorEntry, 'place-start', true);
    render(<ImageFlow image={image} app={app} />);
    const road = [...document.querySelectorAll('.road-list li')].map((li) => `${li.classList.contains('you') ? 'You: ' : ''}${li.querySelector('.road-t')!.textContent}${li.querySelector('.road-m')!.textContent && !li.classList.contains('you') ? ` (${li.querySelector('.road-m')!.textContent})` : ''}`);
    expect(road).toEqual([
      'You: Put the Mirror in fastboot mode',
      'Kiln adds a small setup helper. The original system isn’t touched',
      'You: Join the Mirror’s own Wi-Fi, so Kiln can copy the parts of its system it will change',
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

  it('the restore point\'s Continue opens once it is saved and checked, and a copy is on the computer', async () => {
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

