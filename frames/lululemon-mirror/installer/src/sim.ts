// Kiln's ?demo=… and tests only; the real install is recipe.ts (the app over adb) and flash.ts (the image over
// fastboot). A simulated Mirror for each of Kiln's device demos, built from this package's fakes (test/fakes.ts): Kiln's
// demos click through its sessions, and this is the device behind them. The frame Kiln installs never carries it.
import type { PanelShell } from '@glassworks/adb';
import { FakeKeySocket, fakeApk } from '@glassworks/app-adb/fakes';
import type { DeviceSim, FastbootLike } from '@glassworks/kiln-core';
import { FakeMirror, FirstBootMirror, MIRROR_KIOSK, fakeMirrorApk, type FakeMirrorOptions } from '../test/fakes';
import { SYSTEM_BYTES, USERDATA_BYTES } from './flash';
import { HIDDEN_HOMES, PREVIOUS_HOME } from './recipe';

const never = <T>() => new Promise<T>(() => {});

export const kilnSim: DeviceSim = {
  /** The Panes step's cards as Glassworks' legacy build on the Mirror shows them (each pane's PaneChoice), for ?demo=setup-panes */
  panes: [
    { id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true },
    { id: 'sendspin', title: 'Music', detail: 'A Sendspin speaker', on: true },
    { id: 'notifications', title: 'Notifications', detail: 'On-screen alerts', on: false },
    { id: 'camera', title: 'Camera', detail: 'Presence, and snapshots', on: false },
    { id: 'bluetooth-proxy', title: 'Bluetooth proxy', detail: 'For BLE sensors', on: false },
  ],
  async appDevice(demo, { fast }) {
    // a Mirror Kiln flashed (a fresh userdata, no community kiosk); done-unconfirmed: the kiosk as Glass Panes' userdata has it,
    // a device admin Android won't hide; the undo demos: the kiosk with its admin off, hidden by an install
    const o: FakeMirrorOptions = { delayMs: fast ? 0 : 120, kiosk: demo === 'done-unconfirmed' ? 'admin' : demo.startsWith('undo-') ? 'app' : 'none' };
    if (demo === 'checks-fail') Object.assign(o, { sdk: 29, release: '10', platform: 'sun50iw10' });
    if (demo === 'undo-done' || demo === 'undo-pending') o.installed = 600;
    if (demo === 'error') o.installFails = 'Failure [INSTALL_FAILED_OLDER_SDK]';
    const mirror = new FakeMirror(o);
    if (demo === 'undo-done' || demo === 'undo-pending') {
      // as an install leaves it: the kiosk hidden and recorded; undo-pending: the image's launcher left visible too
      mirror.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
      mirror.hidden.add('com.nimrod.mirrorkiosk');
      if (demo === 'undo-done') mirror.hidden.add('com.android.launcher3');
      mirror.files.set(HIDDEN_HOMES, demo === 'undo-done' ? 'com.android.launcher3 com.nimrod.mirrorkiosk\n' : 'com.nimrod.mirrorkiosk\n');
    }
    if (demo === 'installing') mirror.push = () => never(); // stays on the copy
    return mirror;
  },
  apk: () => fakeMirrorApk(), // a stand-in: the legacy app's manifest and nothing else
  apkName: 'generic-android-legacy.apk',
  wrongApk: () => fakeApk('com.nimrod.mirrorkiosk'),
  wrongApkName: 'mirror-kiosk-v0.12.0.apk',
  systemBytes: SYSTEM_BYTES,
  wrongSystemBytes: 0x40000000,
  fastboot(system, { fails, stuck, tick }): FastbootLike {
    return {
      async getvar(n) { if (n === 'product') return 'MSM8916'; if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
      async partitionSize(n) { return n === 'system' ? system : USERDATA_BYTES; },
      async flashBlob(_, __, onProgress = () => {}) {
        if (fails) throw new Error('flash:system: FAILed (remote: write failed)');
        if (stuck) { onProgress(0.63); return never(); }
        for (let i = 1; i <= 8; i++) { await tick(150); onProgress(i / 8); }
      },
      async reboot() {},
    };
  },
  firstStart({ hideFails, noHome, socket, key }) {
    const m = new FirstBootMirror();
    m.hideFails = !!hideFails;
    m.noHome = !!noHome;
    return socket ? m.withKeySocket(new FakeKeySocket(key)) : m;
  },
  running({ socket, key }): PanelShell {
    const m = new FakeMirror({ installed: 600 });
    if (socket) m.withKeySocket(new FakeKeySocket(key));
    return Object.assign(m, { rebootToBootloader: async () => {} });
  },
};
