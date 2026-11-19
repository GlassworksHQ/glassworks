// /android's flow for the contract, through AndroidSession's existing exports (the file is open in #617; phase 2
// of #621 moves it onto the shared engine): any Android tablet over adb, Glassworks installed and granted. The
// tablet has a touchscreen and the flow declares its one tap (Allow USB debugging), so the person makes it.
// A reload is a new session over the same tablet, as a page load makes one.
import type { PanelShell } from '@glassworks/adb';
import { FakeAndroid, fakeGenericApk } from '@glassworks/generic-android-installer/fakes';
import { AndroidSession, type AndroidDeps, type Device } from '../../src/android/session';
import {
  Clicks, REFUSED, UNPLUGGED, fastSleep, flowData, never, tick, type Driver, type Knobs, type Look, type Page, type World, type Write,
} from './harness';

const pristine = (state: object) => JSON.stringify({ ...state, log: [] });

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

  const deps: AndroidDeps = {
    async findUsb() {
      const r = reloadAt<never>('find');
      if (r) return r;
      return { vendorId: 0x18d1, productName: 'Tablet' };
    },
    // the browser's list, then Allow on the tablet: the tap this flow declares
    async connect() {
      const r = reloadAt<Device>('connect');
      if (r) return r;
      if (fired.refused < (k.refuseAuth ?? 0)) { fired.refused++; throw new Error(REFUSED); }
      // Allow on the tablet's screen: without that tap, adb never lets the browser in
      if (!tapped('allow-usb-debugging')) return never<Device>();
      return link();
    },
    sleep: fastSleep,
    homeTimeoutMs: 300,
    setupTries: 1,
  };

  function load(): Page {
    const s = new AndroidSession(deps, true);
    const clicks = new Clicks();
    return {
      look(): Look {
        const st = s.state;
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
        const sc = s.state.screen;
        return sc === 'find' || sc === 'named' || sc === 'brand' ? 'find' : sc === 'debug' || sc === 'allow' ? 'connect' : 'install';
      },
      // the error screen's Try again (AndroidWizard Stopped)
      async retry() { clicks.reset(); void s.connect(); await tick(); },
      remembers: () => pristine(s.state) !== pristine(new AndroidSession(deps, true).state),
      snapshot: () => JSON.stringify({ ...s.state, log: s.state.log.length }),
      close: () => s.close(),
    };
  }
  return w;
}

export const android: Driver = { id: 'generic-android', frame: 'generic-android', flow: 'install', world };
