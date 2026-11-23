// The Mirror's two flows for the contract, on the engine's device flows (src/device) with the Mirror's declaration
// (its installer's kilnFrame). `fastboot`: ImageSession, Glassworks built into
// the Glass Panes image and flashed, then the first start over adb; the image comes through the real resumableBody
// over a fake network. `app`: AppSession, the app alone over adb on a Mirror already on Glass Panes. Each install hands
// the Mirror to the standard flow (StandardSession, as the page does), which this drives as the S1's driver drives its
// set-up: the App step, then done once the app answers its Wi-Fi step. A reload is a new session over the same Mirror,
// as a page load makes one.
import { SYSTEM_BYTES, USERDATA_BYTES, resumableBody, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { PanelShell } from '@glassworks/adb';
import { ImageSession, type DeviceProgress, type ImageDeps, type ImageMaker, type ProgressMark } from '../../src/device/imageSession';
import { AppSession } from '../../src/device/appSession';
import { withAppStreams } from '../../src/wizard/demo';
import type { StandardSession } from '../../src/standard/session';
import { mirrorFrame, standardOn } from '../mirror-frame';
import {
  Clicks, REFUSED, STALL_MS, UNPLUGGED, body, fastSleep, never, type Driver, type Knobs, type Look, type Page, type World, type Write,
} from './harness';

/**
 * The standard flow after the hand-over, as a person clicks it: the App step (its settings applied), then done once the
 * app answers its Wi-Fi step. Undefined while it isn't the standard flow's turn.
 */
function standardLook(ss: StandardSession | undefined, clicks: Clicks, goal: () => boolean): Look | undefined {
  if (!ss) return undefined;
  const s = ss.store.state;
  if (s.screen === 'error') return { end: 'error' };
  if (s.screen !== 'setup' || s.busy) return 'wait';
  const st = s.setup;
  if (st.phase === 'settings' && st.error) return { end: 'error' };
  if (st.phase === 'settings' && !st.installing) return clicks.act('settings', () => ss.click('install-app', () => ss.installApp({ clock24: true, keyboard: false })));
  if (st.phase === 'wifi' && st.networks === undefined && !st.on && !st.unreachable) return 'wait';
  if (st.phase === 'wifi') return st.unreachable ? { end: 'error' } : { end: 'done', goal: goal() };
  return 'wait';
}

/** The tab's sessionStorage for one world: it outlives a page load, as the browser's does. */
function tabProgress(): DeviceProgress {
  let mark: ProgressMark | undefined;
  return { get: () => mark, set: (m) => { mark = m; } };
}

const IMAGE = new Uint8Array(64).fill(7);

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
      const reader = resumableBody(url, IMAGE.length, { fetch: net, sleep: fastSleep, stallMs: STALL_MS }).stream().getReader();
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
    adbKey: async () => { throw new Error('not for Glassworks'); },
    systemKey: async () => { throw new Error('not for Glassworks'); },
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
  // the Mirror after its first start: Glassworks answers set-up over its setup channel and Improv, on Wi-Fi already
  const started = () => withAppStreams(new FirstBootMirror(), { online: true, radio: false });
  const deps: ImageDeps = {
    frame: mirrorFrame,
    images,
    refract: 'https://proxy.test',
    // VOL- under the back cover: the physical step this flow asks for, so the person does it when asked
    requestFastboot: async () => { mode = 'fastboot'; return fastboot(); },
    async requestAdb() {
      if (mode !== 'booted') throw new DOMException('No device selected.', 'NotFoundError');
      const r = reloadAt<PanelShell>('first-boot');
      if (r) return r;
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      return started();
    },
    // as DevicePage wires them (#607): the device requestAdb picked, asked again with no list while the Mirror starts
    adbPicked: () => mode === 'booted',
    async reopenAdb() {
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      return started();
    },
    adbKey: () => 'QAAA= kiln@contract',
    apk: () => apk,
    sleep: fastSleep,
    simulated: true,
    progress: tabProgress(),
  };
  const apk = fakeMirrorApk();

  function load(): Page {
    let standard: StandardSession | undefined;
    const f = new ImageSession({ ...deps, handOver: (p) => { standard = standardOn(p, { sleep: fastSleep }); void standard.handOver(p); } });
    const clicks = new Clicks();
    return {
      look(): Look {
        const s = f.state;
        const after = standardLook(standard, clicks, () => s.firstBoot?.home.result === 'set');
        if (after) return after;
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
        if (standard) return 'first-boot'; // the standard flow after the first start
        if (s.screen === 'choose' || s.screen === 'image') return 'image';
        if (s.screen === 'fastboot') return 'fastboot';
        if (s.screen === 'adb' || s.work?.step === 'first-boot') return 'first-boot';
        return 'install';
      },
      // the error screen's Try again (ImageScreens ImageError), or the standard flow's
      async retry() {
        clicks.reset();
        if (standard) { const ss = standard; await ss.click('resume', () => ss.route('resume')); return; }
        f.tryAgain();
      },
      // the page knows from the tab's mark (ImageDeps `progress`) that an earlier load wrote
      remembers: () => !!f.state.changed,
      snapshot: () => JSON.stringify({ ...f.state, log: f.state.log.length, standard: standard && { ...standard.store.state, log: [] } }),
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
  /** adb with this browser's key: refused while the Mirror comes up, the first `refuseAuth` times */
  const allowed = async (): Promise<PanelShell> => {
    if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
    // Glassworks answers set-up over its setup channel and Improv once installed, on Wi-Fi already
    return withAppStreams(await link(mirror, k, fired, writes, partial, reloadAt), { online: true, radio: false });
  };
  let picked = false;
  const deps = {
    frame: mirrorFrame,
    // as DevicePage wires them (webUsbAdb): the browser's list once, then the picked Mirror again with no list
    async connect(): Promise<PanelShell> {
      const r = reloadAt<PanelShell>('connect');
      if (r) return r;
      picked = true;
      return allowed();
    },
    reopen: allowed,
    picked: () => picked,
    sleep: fastSleep,
    progress: tabProgress(),
  };

  function load(): Page {
    let standard: StandardSession | undefined;
    const s = new AppSession({ ...deps, handOver: (p) => { standard = standardOn(p, { sleep: fastSleep }); void standard.handOver(p); } }, true);
    const clicks = new Clicks();
    return {
      look(): Look {
        const st = s.state;
        const after = standardLook(standard, clicks, () => st.result?.flow === 'install' && st.result.installed.home === 'set');
        if (after) return after;
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
      step: () => (standard || ['working', 'error', 'done'].includes(s.state.screen) ? 'install' : 'connect'),
      // the error screen's button: the standard flow's Try again once the install went through, else Back to the checks
      async retry() {
        clicks.reset();
        if (standard) { const ss = standard; await ss.click('resume', () => ss.route('resume')); return; }
        await s.again();
      },
      remembers: () => !!s.state.changed,
      snapshot: () => JSON.stringify({ ...s.state, log: s.state.log.length, standard: standard && { ...standard.store.state, log: [] } }),
      close() {},
    };
  }
  return w;
}

export const mirrorFastboot: Driver = { id: 'lululemon-mirror', frame: 'lululemon-mirror', flow: 'fastboot', world: flashWorld };
export const mirrorApp: Driver = { id: 'lululemon-mirror/app', frame: 'lululemon-mirror', flow: 'app', world: appWorld };
