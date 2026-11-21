// /android's flow for the contract, on the engine's USB-debugging flow (UsbSession) with generic Android's frame: any
// Android tablet over adb, Glassworks installed and granted. The tablet has a touchscreen and the flow declares its one tap (Allow USB debugging), so the person makes it.
// The install hands the tablet to the standard flow (as the page does), driven as the S1's set-up is: the App step,
// then done once the app answers its Wi-Fi step. A reload is a new session over the same tablet, as a page load makes one.
import type { PanelShell } from '@glassworks/adb';
import { FakeAndroid, fakeGenericApk } from '@glassworks/generic-android-installer/fakes';
import { GENERIC } from '@glassworks/generic-android-installer';
import { androidStandard } from '../../src/android/AndroidWizard';
import { UsbSession, type Device, type UsbDeps } from '../../src/device/usbSession';
import { kilnFrame as genericFrame } from '@glassworks/generic-android-installer/kiln';
import type { DeviceProgress, ProgressMark } from '../../src/device/imageSession';
import { StandardSession } from '../../src/standard/session';
import { withAppStreams } from '../../src/wizard/demo';
import {
  Clicks, REFUSED, UNPLUGGED, fastSleep, flowData, never, tick, type Driver, type Knobs, type Look, type Page, type World, type Write,
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

function world(k: Knobs): World {
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
  const tablet = new FakeAndroid({ sdk: 34 });
  // the taps the person makes on the tablet: in a no-touch run, only those the flow declares
  const taps = new Set(flowData('generic-android', 'install').steps.flatMap((s) => s.taps ?? []));
  const tapped = (tap: string) => !k.noTouch || taps.has(tap);
  const apk = fakeGenericApk();

  /** One USB link to the tablet: a pulled cable kills it, and the browser hands out a new one. */
  const link = (): Device => {
    let dead = false;
    const d: PanelShell & { close(): Promise<void> } = {
      async run(cmd) {
        if (dead) throw new Error(UNPLUGGED);
        if (/^pm install/.test(cmd)) {
          // the APK is pushed to the tablet; the cable goes as pm installs it
          if (k.dropUsbIn === 'install' && !fired.dropped) { fired.dropped = true; dead = true; partial.add('app'); throw new Error(UNPLUGGED); }
          writes.push({ what: 'install the app', step: 'install' });
          const out = await tablet.run(cmd);
          partial.delete('app');
          return reloadAt<{ out: string; code: number }>('install') ?? out;
        }
        return tablet.run(cmd);
      },
      async push(path, data, mode) { if (dead) throw new Error(UNPLUGGED); return tablet.push(path, data, mode); },
      close: async () => {},
    };
    return d;
  };

  /** adb with this browser's key: refused the first `refuseAuth` times, then Allow, the tap this flow declares */
  const allowed = async (): Promise<Device> => {
    if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
    // Allow on the tablet's screen: without that tap, adb never lets the browser in
    if (!tapped('allow-usb-debugging')) return never<Device>();
    return link();
  };
  let picked = false;
  const deps: UsbDeps = {
    frame: genericFrame,
    async findUsb() {
      const r = reloadAt<never>('find');
      if (r) return r;
      return { vendorId: 0x18d1, productName: 'Tablet' };
    },
    // as the page wires them (webUsbAdb): the browser's list once, then the picked tablet again with no list
    async connect() {
      const r = reloadAt<Device>('connect');
      if (r) return r;
      picked = true;
      return allowed();
    },
    reopen: allowed,
    picked: () => picked,
    sleep: fastSleep,
    homeTimeoutMs: 300,
    setupTries: 1,
  };
  // the tab's sessionStorage: it outlives a page load
  let mark: ProgressMark | undefined;
  const progress: DeviceProgress = { get: () => mark, set: (m) => { mark = m; } };

  function load(): Page {
    let standard: StandardSession | undefined;
    const s = new UsbSession({
      ...deps, progress,
      handOver: (d, installed) => {
        // Glassworks answers set-up over its setup channel and Improv once it is on, on Wi-Fi already
        const p = withAppStreams(d, { online: true, radio: false });
        standard = new StandardSession({ standard: androidStandard(installed.plan.app ?? GENERIC, installed), devices: { knownAdb: async () => p, requestAdb: async () => p },
          loadKey: async () => ({ publicKey: 'QAAA kiln@contract' }) as never, sleep: fastSleep, simulated: true });
        void standard.handOver(p);
      },
    }, true);
    const clicks = new Clicks();
    return {
      look(): Look {
        const st = s.state;
        const after = standardLook(standard, clicks, () => st.result?.flow === 'install' && st.result.installed.home.state === 'set');
        if (after) return after;
        switch (st.screen) {
          case 'find':
            if (!st.apk) return clicks.act('apk', () => s.pickApk('glassworks.apk', apk));
            if (st.findError && clicks.has('find')) return { end: 'error' };
            return clicks.act('find', () => s.find());
          case 'named':
            return clicks.act('named', () => s.toSteps());
          case 'brand':
            return clicks.act('brand', () => { s.pickBrand('other'); s.chooseBrand(); });
          case 'debug':
            return clicks.act('debug', () => s.connect());
          case 'allow':
            return st.connectError && !st.connecting ? { end: 'error' } : 'wait';
          case 'done':
            return { end: 'done', goal: st.result?.flow === 'install' && st.result.installed.home.state === 'set' };
          case 'error':
            return { end: 'error' };
          default:
            return 'wait';
        }
      },
      step() {
        if (standard) return 'install'; // the standard flow after the install
        const sc = s.state.screen;
        return sc === 'find' || sc === 'named' || sc === 'brand' ? 'find' : sc === 'debug' || sc === 'allow' ? 'connect' : 'install';
      },
      // the error screen's Try again (AndroidWizard Stopped), or the standard flow's
      async retry() {
        clicks.reset();
        if (standard) { const ss = standard; await ss.click('resume', () => ss.route('resume')); return; }
        void s.connect(); await tick();
      },
      // the page knows from the tab's mark (UsbDeps `progress`) that an earlier load wrote
      remembers: () => !!s.state.changed,
      snapshot: () => JSON.stringify({ ...s.state, log: s.state.log.length, standard: standard && { ...standard.store.state, log: [] } }),
      close: () => s.close(),
    };
  }
  return w;
}

export const android: Driver = { id: 'generic-android', frame: 'generic-android', flow: 'install', world };
