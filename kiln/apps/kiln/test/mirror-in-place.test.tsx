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

/** Receive (over the Mirror's own Wi-Fi, or Bluetooth), the second fastboot session, and the restore point saved and checked. */
async function toRestorePoint(r: Rig, via: 'wifi' | 'ble' = 'wifi') {
  if (via === 'wifi') await r.image.receiveWifi();
  else await r.image.receive();
  expect(r.image.state).toMatchObject({ screen: 'fastboot', round: 2 });
  await r.image.connectFastboot();
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
    for (let i = 0; i < 200 && b.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
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
    for (let i = 0; i < 200 && slow.image.state.restorePoint?.status !== 'ready'; i++) await new Promise((x) => setTimeout(x, 5));
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

  it.each(['place-receive-file', 'place-restore-point', 'place-fastboot-again', 'place-undo', 'place-error-bundle'] as const)('%s renders, with one ember button at most', async (d) => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, d, true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    await act(async () => {});
    expect(document.querySelectorAll('.btn.ember').length).toBeLessThanOrEqual(1);
    expect(document.body.textContent).not.toContain(FAKE_SERIAL);
  }, 20_000);

  it('the restore point\'s Continue opens only once it is saved and checked', async () => {
    const h = handTo();
    const { image, app } = await imageDemo(mirrorEntry, 'place-restore-point', true, {}, h.handOver);
    render(<ImageFlow image={image} app={app} />);
    expect(screen.getByRole('button', { name: 'Continue' }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByText(/Saved in this browser/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Download a copy' }));
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

