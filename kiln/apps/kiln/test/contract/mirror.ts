// The Mirror's two flows for the contract, through their existing exports only (the files are open in #596, #603,
// #607 and #617; phase 2 of #621 moves them onto the shared engine). `fastboot`: FlashSession, Glassworks built into
// the Glass Panes image and flashed, then the first start over adb; the image comes through the real resumableBody
// over a fake network. `app`: MirrorSession, the app alone over adb on a Mirror already on Glass Panes.
// A reload is a new session over the same Mirror, as a page load makes one.
import { SYSTEM_BYTES, USERDATA_BYTES, resumableBody, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { PanelShell } from '@glassworks/adb';
import { FlashSession, type FlashDeps, type ImageMaker } from '../../src/mirror/flash';
import { MirrorSession } from '../../src/mirror/session';
import {
  Clicks, REFUSED, UNPLUGGED, body, fastSleep, never, type Driver, type Knobs, type Look, type Page, type World, type Write,
} from './harness';

const IMAGE = new Uint8Array(64).fill(7);
const pristine = (state: object) => JSON.stringify({ ...state, log: [] });

function flashWorld(k: Knobs): World {
  const writes: Write[] = [];
  const fired = { refused: 0, dropped: false, stalled: false, reloaded: false };
  const partial = new Set<string>();
  const w: World = { writes, partial, fired, reloadNow: false, load: async () => load() };
  const reloadAt = <T>(step: string): Promise<T> | undefined => {
    if (k.reloadIn !== step || fired.reloaded) return undefined;
    fired.reloaded = true;
    w.reloadNow = true;
    return never<T>();
  };
  let mode: 'android' | 'fastboot' | 'booted' = 'android';

  // Refract over a fake network: the real resumableBody reads it, as the worker does
  const net = (async () => {
    const r = reloadAt<Response>('image');
    if (r) return r;
    if (k.stall && !fired.stalled) { fired.stalled = true; return body(IMAGE, true); }
    return body(IMAGE);
  }) as unknown as typeof fetch;
  const images: ImageMaker = {
    async fetch(url, onProgress) {
      const reader = resumableBody(url, IMAGE.length, { fetch: net, sleep: fastSleep }).stream().getReader();
      let got = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        got += value.length;
        onProgress(got / IMAGE.length);
      }
    },
    build: async (_o, onProgress) => { onProgress('patch', 1); return { system: new Blob(['SS']), userdata: new Blob(['U']) }; },
    // a community image's files (#579): this flow is Glassworks, which writes only what it builds
    download: async () => {},
    file: async (n) => new Blob([n]),
    wifi: async () => { throw new Error('not for Glassworks'); },
    check: async () => {},
    discard: async () => {},
  };
  /** The Mirror's fastboot, one USB link per connection: a link the cable dropped stays dead. */
  const fastboot = (): FastbootLike => {
    let dead = false;
    const alive = () => { if (dead) throw new Error(UNPLUGGED); };
    return {
      async getvar(n) {
        alive();
        const r = reloadAt<string>('fastboot');
        if (r) return r;
        if (n === 'max-download-size') return '0x10000000';
        throw new Error('FAIL');
      },
      partitionSize: async (n) => { alive(); return n === 'system' ? SYSTEM_BYTES : USERDATA_BYTES; },
      async flashBlob(p, _blob, onProgress) {
        alive();
        onProgress?.(0.5);
        // half of system is on the Mirror when the cable goes
        if (k.dropUsbIn === 'install' && !fired.dropped) { fired.dropped = true; dead = true; partial.add(p); throw new Error(UNPLUGGED); }
        if (p === 'userdata') { const r = reloadAt<void>('install'); if (r) return r; }
        onProgress?.(1);
        partial.delete(p);
        writes.push({ what: `flash ${p}`, step: 'install' });
      },
      reboot: async () => { alive(); mode = 'booted'; },
    };
  };
  const deps: FlashDeps = {
    images,
    refract: 'https://proxy.test',
    // VOL- under the back cover: the physical step this flow asks for, so the person does it when asked
    requestFastboot: async () => { mode = 'fastboot'; return fastboot(); },
    async requestAdb() {
      if (mode !== 'booted') throw new DOMException('No device selected.', 'NotFoundError');
      const r = reloadAt<PanelShell>('first-boot');
      if (r) return r;
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      return new FirstBootMirror();
    },
    // as MirrorWizard wires them (#607): the device requestAdb picked, asked again with no list while the Mirror starts
    adbPicked: () => mode === 'booted',
    async reopenAdb() {
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      return new FirstBootMirror();
    },
    adbKey: () => 'QAAA= kiln@contract',
    apk: () => apk,
    sleep: fastSleep,
  };
  const apk = fakeMirrorApk();

  function load(): Page {
    const f = new FlashSession(deps);
    const clicks = new Clicks();
    return {
      look(): Look {
        const s = f.state;
        switch (s.screen) {
          case 'choose': // Glassworks, first and picked (#579)
            return clicks.act('choose', () => f.choose());
          case 'image': {
            // a download Refract can't serve is Kiln's bug: Try again, then the error the person reports (#579)
            const files = s.files;
            if (files.some((x) => x.state === 'error' || x.state === 'bad')) return clicks.has('download') ? { end: 'error' } : clicks.act('download', () => f.download());
            if (files.every((x) => x.state === 'ok')) return f.blocked() ? { end: 'error' } : clicks.act('continue', () => f.toFastboot());
            return files.some((x) => x.state === 'downloading') ? 'wait' : clicks.act('download', () => f.download());
          }
          case 'fastboot':
            if (s.fastboot && !s.fastboot.ok) return { end: 'error' };
            if (s.connectError && clicks.has('fastboot')) return { end: 'error' };
            return clicks.act('fastboot', () => f.connectFastboot());
          case 'confirm':
            return clicks.act('confirm', () => { f.understand(true); return f.flash(); });
          case 'adb':
            if (s.connectError && clicks.has('adb')) return { end: 'error' };
            return clicks.act('adb', () => f.connectAdb());
          case 'done':
            return { end: 'done', goal: s.firstBoot?.home.result === 'set' };
          case 'error':
            return { end: 'error' };
          default:
            return 'wait';
        }
      },
      step() {
        const s = f.state;
        if (s.screen === 'choose' || s.screen === 'image') return 'image';
        if (s.screen === 'fastboot') return 'fastboot';
        if (s.screen === 'adb' || s.work?.step === 'first-boot') return 'first-boot';
        return 'install';
      },
      // the error screen's "Back to the download" (FlashScreens FlashError)
      async retry() { clicks.reset(); f.retry(); },
      remembers: () => pristine(f.state) !== pristine(new FlashSession(deps).state),
      snapshot: () => JSON.stringify({ ...f.state, log: f.state.log.length }),
      close() {},
    };
  }
  return w;
}

/** adb to the Mirror through one USB link: a pulled cable kills the link, and the browser hands out a new one. */
function link(m: FakeMirror, k: Knobs, fired: World['fired'], writes: Write[], partial: Set<string>, reloadAt: <T>(s: string) => Promise<T> | undefined): PanelShell {
  let dead = false;
  return {
    async run(cmd) {
      if (dead) throw new Error(UNPLUGGED);
      if (/^pm install/.test(cmd)) {
        // the APK is pushed to the Mirror; the cable goes as pm installs it
        if (k.dropUsbIn === 'install' && !fired.dropped) { fired.dropped = true; dead = true; partial.add('app'); throw new Error(UNPLUGGED); }
        writes.push({ what: 'install the app', step: 'install' });
        const out = await m.run(cmd);
        partial.delete('app');
        const r = reloadAt<{ out: string; code: number }>('install');
        return r ?? out;
      }
      return m.run(cmd);
    },
    async push(path) { if (dead) throw new Error(UNPLUGGED); return m.push(path); },
  };
}

function appWorld(k: Knobs): World {
  const writes: Write[] = [];
  const fired = { refused: 0, dropped: false, stalled: false, reloaded: false };
  const partial = new Set<string>();
  const w: World = { writes, partial, fired, reloadNow: false, load: async () => load() };
  const reloadAt = <T>(step: string): Promise<T> | undefined => {
    if (k.reloadIn !== step || fired.reloaded) return undefined;
    fired.reloaded = true;
    w.reloadNow = true;
    return never<T>();
  };
  // The Mirror the app-only path reaches with this browser's key: one Kiln flashed (a fresh userdata, no community kiosk).
  // Nobody touches it with or without the noTouch knob: the flow hides the other home apps over adb (#607).
  const mirror = new FakeMirror({ kiosk: 'none' });
  const apk = fakeMirrorApk();
  const deps = {
    async connect(): Promise<PanelShell> {
      const r = reloadAt<PanelShell>('connect');
      if (r) return r;
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      return link(mirror, k, fired, writes, partial, reloadAt);
    },
    sleep: fastSleep,
  };

  function load(): Page {
    const s = new MirrorSession(deps, true);
    const clicks = new Clicks();
    return {
      look(): Look {
        const st = s.state;
        switch (st.screen) {
          case 'pick':
            if (!st.apk) return clicks.act('apk', () => s.pickApk('glassworks-legacy.apk', apk));
            if (st.connecting) return 'wait';
            if (st.connectError && clicks.has('connect')) return { end: 'error' };
            return clicks.act('connect', () => s.connect());
          case 'checks':
            if (!st.apk) return clicks.act('apk', () => s.pickApk('glassworks-legacy.apk', apk));
            return s.installBlocked() ? { end: 'error' } : clicks.act('install', () => s.install());
          case 'done':
            return { end: 'done', goal: st.result?.flow === 'install' && st.result.installed.home === 'set' };
          case 'error':
            return { end: 'error' };
          default:
            return 'wait';
        }
      },
      step: () => (s.state.screen === 'working' || s.state.screen === 'error' || s.state.screen === 'done' ? 'install' : 'connect'),
      // the error screen's "Back to the checks" (MirrorWizard)
      async retry() { clicks.reset(); await s.again(); },
      remembers: () => pristine(s.state) !== pristine(new MirrorSession(deps, true).state),
      snapshot: () => JSON.stringify({ ...s.state, log: s.state.log.length }),
      close() {},
    };
  }
  return w;
}

export const mirrorFastboot: Driver = { id: 'lululemon-mirror', frame: 'lululemon-mirror', flow: 'fastboot', world: flashWorld };
export const mirrorApp: Driver = { id: 'lululemon-mirror/app', frame: 'lululemon-mirror', flow: 'app', world: appWorld };
