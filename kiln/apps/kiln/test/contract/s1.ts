// The S1's flow for the contract: the real Session, InstallFlow, registry and routes (as useRouteSync uses them),
// with the S1 recipe's hardware steps on a fake panel. Downloads go through kiln-core's Assets, so its stall
// watchdog is the one under test. A reload is a new page load: fresh modules, the same sessionStorage, the same
// backups (IndexedDB) and the same panel.
import { vi } from 'vitest';
import { s1Plus, type AssetName, type Provisioned, type SetUpOptions } from '@glassworks/aqara-s1-plus-installer';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';
import { Assets, toHex, type BackupFile, type BackupMeta, type Recipe } from '@glassworks/kiln-core';
import { s1PlusUi } from '../../src/frames/aqara-s1-plus';
import { demoBootImage, simulatedPanel } from '../../src/wizard/demo';
import { Session } from '../../src/wizard/session';
import type { Step } from '../../src/wizard/routes';
import {
  Clicks, REFUSED, STALL_MS, UNPLUGGED, body, fastSleep, never, type Driver, type Knobs, type Look, type Page, type World, type Write,
} from './harness';

const BASE = '/aqara-s1-plus';
const BLOBS: Partial<Record<AssetName, Uint8Array>> = {
  'uboot-spl': Uint8Array.of(1, 1), uboot: Uint8Array.of(2, 2), 'kiosk-apk': Uint8Array.of(3, 3),
};

function world(k: Knobs): World {
  const writes: Write[] = [];
  const fired = { refused: 0, dropped: false, stalled: false, reloaded: false };
  const partial = new Set<string>();
  const w: World = { writes, partial, fired, reloadNow: false, load: () => load() };
  /** the reload point of `step` was reached: the page goes away, and this call never answers */
  const reloadAt = <T>(step: string): Promise<T> | undefined => {
    if (k.reloadIn !== step || fired.reloaded) return undefined;
    fired.reloaded = true;
    w.reloadNow = true;
    return never<T>();
  };

  // ---- the panel ----------------------------------------------------------------------------------
  let mode: 'fel' | 'fastboot' | 'android' | 'off' = 'fel';
  let appIn = false;
  const parts = new Map<string, Uint8Array>(s1Plus.backup.parts.map((p) => [p, p === 'boot' ? demoBootImage(64 << 10) : new Uint8Array(4096)]));
  const shell = simulatedPanel(false, true, null);
  /** adb as it comes up: refused while booting, the first `refuseAuth` times */
  const adb = async () => {
    if (mode !== 'android') return undefined;
    const r = reloadAt<never>('first-boot');
    if (r) return r;
    if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
    return shell;
  };
  const fb = {
    log: (_: string) => {},
    getvar: async () => 'sunxi',
    partitionSize: async (p: string) => parts.get(p)?.length ?? 0,
    readPartition: async (p: string, on?: (d: number, t: number) => void) => { const d = parts.get(p)!; on?.(d.length, d.length); return d.slice(); },
    flashVerified: async (p: string, d: Uint8Array, on?: (ph: 'write' | 'verify', done: number, t: number) => void) => {
      on?.('write', 0, d.length);
      if (p === 'boot') {
        const r = reloadAt<void>('install');
        if (r) return r;
        if (k.dropUsbIn === 'install' && !fired.dropped) {
          // half the new boot is on the panel when the cable goes
          const half = d.length >> 1;
          parts.set(p, Uint8Array.from([...d.subarray(0, half), ...parts.get(p)!.subarray(half)]));
          partial.add(p);
          on?.('write', half, d.length);
          fired.dropped = true;
          mode = 'off';
          throw new Error(UNPLUGGED);
        }
      }
      on?.('write', d.length, d.length);
      on?.('verify', d.length, d.length);
      parts.set(p, d.slice());
      partial.delete(p);
      writes.push({ what: `flash ${p}`, step: 'install' });
    },
    reboot: async () => { mode = 'android'; },
  } as unknown as Fastboot;

  /** The S1 recipe with its hardware steps on the fake: the engine, the backup plan and the order are the real ones. */
  const recipe: Recipe<AssetName, SetUpOptions, Provisioned> = {
    ...s1Plus,
    // bootpatch's wasm doesn't load under Node: the fake patch marks boot in its last byte instead
    backup: { ...s1Plus.backup, isPatched: async (b) => b[b.length - 1] === 1 },
    fel: {
      checkChip: async () => 'SoC 0x1855 (A133), secure boot off (contract fake)',
      loader: ['uboot-spl', 'uboot'],
      boot: async (_fel, _loader, on) => { const r = reloadAt<void>('connect'); if (r) return r; on(1); mode = 'fastboot'; },
    },
    checkLayout: async () => 'partition layout OK (contract fake)',
    patchBoot: async ({ backup }) => { const b = backup.get('boot')!.slice(); b[b.length - 1] = 1; return b; },
    writesBeforeBoot: async ({ backup }) => [{ part: 'vbmeta', data: backup.get('vbmeta')!, what: 'Turning off verified boot' }],
    setUp: async (_panel, _opts, ctx) => {
      await ctx.asset('kiosk-apk');
      writes.push({ what: 'install the app', step: 'app' });
      const r = reloadAt<Provisioned>('app');
      if (r) return r;
      appIn = true;
      return { ip: '192.0.2.50' };
    },
  };

  // ---- this browser: its backups (IndexedDB) and the staged files ----------------------------------
  const saved: { meta: BackupMeta; files: BackupFile[] }[] = [];
  const manifest = Promise.all(Object.entries(BLOBS).map(async ([name, b]) => ({
    name, path: `${name}.bin`, size: b.length, license: 'MIT', source: 'contract',
    sha256: toHex(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>)),
  })));
  const fetch = (async (url: string) => {
    if (url.endsWith('manifest.json')) return new Response(JSON.stringify({ generated: '', assets: await manifest }));
    const name = url.replace(/^.*\/(.*)\.bin$/, '$1') as AssetName;
    if (k.stall && !fired.stalled) { fired.stalled = true; return body(BLOBS[name]!, true); }
    return body(BLOBS[name]!);
  }) as typeof globalThis.fetch;

  let prev: { session: Session; url: Step } | undefined;

  async function load(): Promise<Page> {
    // a page load: the old page stops, its modules go, sessionStorage stays
    prev?.session.close();
    vi.resetModules();
    const { registry } = await import('../../src/wizard/registry');
    const routes = await import('../../src/wizard/routes');
    const assets = new Assets<AssetName>('/assets/', fetch, { stallMs: STALL_MS });
    const session = new Session({
      recipe, frame: s1PlusUi, simulated: true, sleep: fastSleep,
      devices: {
        knownFastboot: async () => (mode === 'fastboot' ? fb : undefined),
        requestFastboot: async () => { if (mode !== 'fastboot') throw new DOMException('No device selected.', 'NotFoundError'); return fb; },
        knownAdb: adb,
        requestAdb: async () => { const p = await adb(); if (!p) throw new DOMException('No device selected.', 'NotFoundError'); return p; },
      },
      // the recovery button and the cable: a physical step the frame declares, so the person does it when asked
      requestFel: async () => { mode = 'fel'; return { log: () => {} } as unknown as FelDevice; },
      asset: (n, p) => assets.get(n, p),
      hasAsset: (n) => assets.has(n),
      backups: {
        save: async (meta, files) => { saved.push({ meta, files }); },
        original: async () => saved[0]?.meta,
        files: async () => saved[0]?.files ?? [],
      },
      loadKey: async () => ({ publicKey: 'QAAAAcontract kiln@contract' }) as never,
      openTab: () => null,
      confirm: () => true,
      download: () => {},
    });
    registry.set({ base: BASE, name: s1PlusUi.name, session });
    // the address bar of the page that went away, entered as useRouteSync does on a fresh load
    if (prev) {
      const d = routes.onNavigate(session.store.state, session.started, prev.url);
      if (d.kind === 'enter') void session.click('route', () => session.route(routes.ENTRIES[d.step]!.route));
    }
    const clicks = new Clicks();
    let asks = 0;
    let asked = false;
    const page: Page = {
      look(): Look {
        const s = session.store.state;
        if (s.ask && !asked) asks++;
        asked = !!s.ask;
        if (s.screen === 'error') return { end: 'error' };
        if (s.screen === 'done') return { end: 'done', goal: appIn };
        // "your turn": the sheet's button, once per sheet
        if (s.ask && !s.ask.busy && !s.ask.error) return clicks.act(`ask ${asks}`, () => session.asks.click());
        if (s.screen === 'connect' && s.flow === 'install') return clicks.act('connect', () => session.click('connect', () => session.connectFel()));
        if (s.screen === 'setup') {
          const st = s.setup;
          if (st.phase === 'settings' && st.error) return { end: 'error' };
          if (st.phase === 'settings' && !st.installing) {
            return clicks.act('settings', () => session.click('install-app', () => session.installApp({ clock24: true, keyboard: false })));
          }
          // the app answers over its own channel: the install is over, set-up (the same for every frame) begins
          if (st.phase === 'wifi') return st.unreachable ? { end: 'error' } : { end: 'done', goal: appIn };
        }
        return 'wait';
      },
      step() {
        const s = session.store.state;
        if (s.screen === 'connect') return 'connect';
        if (s.screen === 'firing') return s.progress.overall < 0.05 ? 'connect' : 'install';
        if (s.screen === 'setup') return s.setup.phase === 'waiting' ? 'first-boot' : 'app';
        if (s.screen === 'error') return ['connect', 'connect', 'install', 'first-boot'][s.failure?.at ?? 1] ?? 'app';
        return 'app';
      },
      // the error screen's Try again (screens.tsx Failure)
      async retry() {
        clicks.reset();
        const f = session.store.state.failure!;
        if (f.at >= 3) await session.click('resume', () => session.route('resume'));
        else session.store.dispatch({ type: 'go', screen: 'connect' });
      },
      remembers: () => session.store.state.changed !== undefined,
      snapshot: () => JSON.stringify({ ...session.store.state, log: session.store.state.log.length }),
      close: () => session.close(),
    };
    prev = { session, url: 'start' };
    // the address bar follows the state (routes.canonical), so a reload enters where the page was
    session.store.subscribe(() => { if (prev?.session === session) prev.url = routes.canonical(session.store.state); });
    return page;
  }

  return w;
}

export const s1: Driver = { id: 'aqara-s1-plus', frame: 'aqara-s1-plus', flow: 'install', world };
