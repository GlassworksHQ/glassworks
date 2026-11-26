// Kiln's ?demo=… and tests only; the real install is recipe.ts (the app over adb) and flash.ts (the image over
// fastboot). A simulated Mirror for each of Kiln's device demos, built from this package's fakes (test/fakes.ts): Kiln's
// demos click through its sessions, and this is the device behind them. The frame Kiln installs never carries it.
import type { PanelShell } from '@glassworks/adb';
import { FakeKeySocket, fakeApk } from '@glassworks/app-adb/fakes';
import { sha256Hex, type DeviceSim, type FastbootLike, type InPlaceCode, type InPlaceSim } from '@glassworks/kiln-core';
import {
  FAKE_SERIAL, FakeMirror, FakeStockAgent, FakeTwoStageFastboot, FirstBootMirror, MIRROR_KIOSK, buildFakeGwsbBundle, fakeMirrorApk,
  type FakeMirrorOptions,
} from '../test/fakes';
import design from '../../design/installer.json';
import { SYSTEM_BYTES, USERDATA_BYTES } from './flash';
import { ORIGINAL_JAR, bundleExtents, mirrorInPlace } from './inplace';
import { HIDDEN_HOMES, PREVIOUS_HOME } from './recipe';
import { STOCK_SERVICES_JAR_PATH, buildSurgicalSparseImage } from './stock';

const never = <T>() => new Promise<T>(() => {});

/** What the stand-in stage writes: a marker, then the run's secret, as the simulated agent reads it back off userdata. */
const STAGE_MARK = new TextEncoder().encode('KILN-DEMO-STAGE\n');

/** A Blob's bytes, in a browser, in Node, and in jsdom (whose Blob has no arrayBuffer). */
function bytesOf(b: Blob): Promise<Uint8Array> {
  if (typeof b.arrayBuffer === 'function') return b.arrayBuffer().then((x) => new Uint8Array(x));
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer));
    r.onerror = () => reject(r.error ?? new Error('could not read the stage image'));
    r.readAsArrayBuffer(b);
  });
}

/** The run's secret, out of a stand-in stage image (undefined for anything else). */
async function stageSecret(blob: Blob): Promise<Uint8Array | undefined> {
  const b = await bytesOf(blob);
  if (b.length !== STAGE_MARK.length + 32 || STAGE_MARK.some((x, i) => b[i] !== x)) return undefined;
  return b.slice(STAGE_MARK.length);
}

/**
 * A file on the simulated system partition after a sparse image is written over it: each RAW or FILL chunk's bytes that
 * fall in the file's extents (where the file sits on the partition) land in the file, the rest of it stays.
 */
function writeSparseOverFile(sparse: Uint8Array, extents: readonly { logical: number; physical: number; length: number }[], file: Uint8Array): Uint8Array {
  const out = file.slice();
  const v = new DataView(sparse.buffer, sparse.byteOffset, sparse.byteLength);
  const fileHeader = v.getUint16(8, true), chunkHeader = v.getUint16(10, true), blockSize = v.getUint32(12, true), chunks = v.getUint32(20, true);
  let o = fileHeader, block = 0;
  for (let c = 0; c < chunks; c++) {
    const type = v.getUint16(o, true), count = v.getUint32(o + 4, true), total = v.getUint32(o + 8, true);
    const start = block * blockSize, end = (block + count) * blockSize, body = o + chunkHeader;
    if (type === 0xcac1 || type === 0xcac2) {
      for (const e of extents) {
        const from = Math.max(start, e.physical), to = Math.min(end, e.physical + e.length);
        for (let at = from; at < to; at++) {
          out[e.logical + at - e.physical] = type === 0xcac1 ? sparse[body + at - start]! : sparse[body + ((at - start) % 4)]!;
        }
      }
    }
    if (type !== 0xcac4) block += count;
    o += total;
  }
  return out;
}

/**
 * The Mirror's real in-place code with stand-ins where it needs the device's own files: the stage is a marker and the
 * secret (no ext4 to build), and the build patches nothing (the bundle's jar is made up) but lays the same surgical
 * images over the bundle's own extents, so Kiln's checks run on them as on a real Mirror.
 */
export const simInPlaceCode: InPlaceCode = {
  ...mirrorInPlace(design.device.words),
  assets: [],
  async stage({ secret }) { return new Blob([STAGE_MARK, secret as Uint8Array<ArrayBuffer>]); },
  async build(bundle, o) {
    const dv = new DataView(bundle.buffer, bundle.byteOffset, bundle.byteLength);
    const metaLen = dv.getUint32(4, true);
    const meta = JSON.parse(new TextDecoder().decode(bundle.subarray(12, 12 + metaLen))) as Parameters<typeof bundleExtents>[0];
    const jar = bundle.slice(12 + metaLen);
    const extents = bundleExtents(meta, jar.length);
    const patched = jar.slice();
    patched[0] = patched[0]! ^ 0xff; // a stand-in change, inside the file's own blocks
    const systemBytes = o.partitionBytes.system ?? SYSTEM_BYTES;
    const sparse = (b: Uint8Array) => new Blob([buildSurgicalSparseImage(systemBytes, extents, b) as Uint8Array<ArrayBuffer>]);
    const patchedSha256 = await sha256Hex(patched);
    return {
      writes: { 'system-in-place': sparse(patched), userdata: new Blob([new Uint8Array(4096)]) },
      restore: sparse(jar),
      // the real build's manifest shape (servicesJar, its original kept as a file): the way back checks the restore against it
      manifest: { version: 1, systemBytes, servicesJar: { path: STOCK_SERVICES_JAR_PATH, size: jar.length, originalSha256: await sha256Hex(jar), patchedSha256, extents,
        origFile: ORIGINAL_JAR, restoreSparse: 'restore-services.sparse' } },
      files: { [ORIGINAL_JAR]: jar },
      readBack: [{ path: STOCK_SERVICES_JAR_PATH, sha256: patchedSha256, size: patched.length }],
    };
  },
  // the way back's fresh userdata, as a stand-in of one block (the real one is a whole empty filesystem)
  wayBack: {
    partitions: ['userdata'],
    async build() { return { writes: { userdata: new Blob([new Uint8Array(4096)]) }, what: 'userdata: fresh, as after a factory reset' }; },
  },
};

/**
 * A stock Mirror through its in-place install: its two fastboot sessions (FakeTwoStageFastboot), the setup agent the
 * stage starts (FakeStockAgent: its HTTP server and Bluetooth, signing with the secret it read off userdata), and adb
 * with this browser's key after the install (FakeMirror, nothing of the community image on it).
 */
export const simInPlace: InPlaceSim = {
  serial: FAKE_SERIAL,
  userdataBytes: USERDATA_BYTES,
  bundle: async ({ secret, tampered, otherDevice }) => (await buildFakeGwsbBundle({ secret, corruptHmac: tampered, ...(otherDevice ? { serialno: '0000fake02' } : {}) })).bytes,
  code: simInPlaceCode,
  device(o) {
    const fb = new FakeTwoStageFastboot({ serialno: FAKE_SERIAL, systemBytes: SYSTEM_BYTES, userdataBytes: USERDATA_BYTES });
    let secret: Uint8Array | undefined;
    let agent: FakeStockAgent | undefined;
    let installed = false;
    // services.jar as the running system shows it (from the agent's bundle on), and where it sits on the partition
    let jar: { bytes: Uint8Array; extents: { logical: number; physical: number; length: number }[] } | undefined;
    const tick = o.tick ?? (async () => {});
    // after the install, adb with this browser's key; it can restart into fastboot (the way back's restart). A fresh
    // userdata written in the second session or later (the install's, or the way back's) is a device with no app data:
    // no app, no Device Owner, nothing hidden, as after a factory reset
    const fresh = () => Object.assign(new FakeMirror({ kiosk: 'none', serialno: FAKE_SERIAL }), {
      rebootToBootloader: async () => { installed = false; },
      // `ls '<path>'`, as toybox answers it: the way back asks when a file won't read, to tell a file that's gone
      async run(this: FakeMirror, cmd: string) {
        // Kiln quotes the path (one shell argument): `ls '<path>'`
        const ls = /^ls '([^']*)'$/.exec(cmd);
        if (ls) return ls[1] === STOCK_SERVICES_JAR_PATH && jar ? { out: `${ls[1]}\n`, code: 0 } : { out: `ls: ${ls[1]}: No such file or directory\n`, code: 1 };
        return FakeMirror.prototype.run.call(this, cmd);
      },
      // adb's sync: the system's files as the last write left them
      async pull(path: string) {
        if (o.unreadable) throw new Error(`${path}: Permission denied`);
        if (path !== STOCK_SERVICES_JAR_PATH || !jar) throw new Error(`${path}: No such file or directory`);
        return jar.bytes.slice();
      },
    });
    let mirror = fresh();
    const lanMode = o.lan ?? 'ok';
    const fastboot = (): FastbootLike => {
      if (!fb.connected) fb.nextSession();
      return {
        getvar: (n) => fb.getvar(n),
        partitionSize: (n) => fb.partitionSize(n),
        async flashBlob(partition, blob, onProgress) {
          const session = fb.session;
          await tick(150);
          await fb.flashBlob(partition, blob, onProgress);
          if (partition === 'userdata' && session === 1) secret = await stageSecret(blob);
          if (partition === 'userdata' && session >= 2) mirror = fresh();
          if (session >= 2 && partition === 'system') {
            installed = true;
            if (jar && !o.systemNotTaken) jar = { ...jar, bytes: writeSparseOverFile(await bytesOf(blob), jar.extents, jar.bytes) };
          }
        },
        async reboot() {
          const session = fb.session;
          await fb.reboot();
          // the stage's restart: the agent comes up, signing with the secret it found on userdata
          if (session === 1 && secret) {
            const b = await buildFakeGwsbBundle({ secret, corruptHmac: o.tampered, ...(o.refusedBuild ? { servicesOdexFound: true } : {}) });
            jar ??= { bytes: b.jarBytes.slice(), extents: (b.meta.fiemap as { extents: { logical: number; physical: number; length: number }[] }).extents };
            agent = new FakeStockAgent(b, { lanMode, bleUnavailable: o.bluetooth === false, ...(o.bleFrameDelayMs ? { bleFrameDelayMs: o.bleFrameDelayMs } : {}) });
          }
          if (session >= 2 && installed) agent = undefined;
        },
      };
    };
    return {
      fastboot,
      fetch: ((input: RequestInfo | URL, init?: RequestInit) => (agent ? agent.fakeFetch(input, init) : Promise.reject(new TypeError('Failed to fetch')))) as typeof fetch,
      bluetooth: {
        getAvailability: async () => o.bluetooth !== false,
        requestDevice: async (opts: RequestDeviceOptions) => {
          if (!agent) throw new DOMException('User cancelled the requestDevice() chooser.', 'NotFoundError');
          return agent.fakeBluetooth.requestDevice(opts);
        },
      },
      adb: () => mirror,
      staged: () => !!agent,
    };
  },
};

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
  inPlace: simInPlace,
};
