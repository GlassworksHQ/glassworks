// Development and preview only (<route>?demo=flash-… and ?demo=image-…): the image over fastboot against a device
// frame's simulated device (its `kilnSim`), so each screen can be seen and captured without one. The images aren't
// built or downloaded (a stand-in reports progress); the fastboot and first-start answers are the frame's fakes.
// `flash-…`: Glassworks; `image-…`: the choice and the frame's community image (issue 579).
import { ProxyError, WrongImage, type DeviceEntry, type ImageMaker } from '@glassworks/kiln-core';
import { ImageSession, type DeviceProgress, type ImageDeps, type ProgressMark } from './imageSession';
import { fakeReleaseRoute, type FakeRelease } from '../release/demo';
import { AppSession, releaseLine } from './appSession';
import type { PanelShell } from '@glassworks/adb';
import { standardDemo } from '../standard/demo';
import type { StandardSession } from '../standard/session';
import { withAppStreams } from '../wizard/demo';
import { memoryInPlaceStore } from './inPlace';
import type { BluetoothLike } from './channels';

export const IMAGE_DEMOS = ['flash-image', 'flash-release', 'flash-release-downloading', 'flash-release-none', 'flash-release-bad', 'flash-downloading', 'flash-download-error', 'flash-download-bad', 'flash-ready', 'flash-fastboot', 'flash-fastboot-wrong', 'flash-fastboot-denied', 'flash-confirm',
  'flash-working', 'flash-adb', 'flash-adb-wait', 'flash-adb-refused', 'flash-error-first-start', 'flash-setup', 'flash-setup-wifi', 'flash-setup-ha', 'flash-done', 'flash-done-chooser', 'flash-done-no-home', 'flash-error', 'flash-run',
  'image-choose', 'image-choose-open', 'image-choose-panes', 'image-link', 'image-download', 'image-download-done', 'image-download-error', 'image-download-bad',
  'image-fastboot', 'image-confirm', 'image-writing', 'image-done', 'image-error', 'image-back-glassworks', 'image-back-panes',
  'image-wifi', 'image-wifi-writing', 'image-adb', 'image-done-wifi', 'image-done-wifi-unconfirmed',
  ...['place-choose', 'place-start', 'place-fastboot', 'place-confirm', 'place-stage', 'place-join-wifi', 'place-joined', 'place-wifi-unreachable', 'place-receive-lan', 'place-receive-ble', 'place-lna-denied', 'place-receive-file', 'place-restore-point', 'place-restore-offered', 'place-restore-copied',
    'place-fastboot-again', 'place-working', 'place-connect', 'place-first-start', 'place-installing', 'place-done', 'place-error-bundle', 'place-error-lost', 'place-error-not-taken', 'place-error-write',
    'place-undo', 'place-undo-fastboot', 'place-undo-working', 'place-undo-error', 'place-undo-done', 'place-undo-refused', 'place-run', 'place-resume', 'place-resume-staged',
    'place-reinstall-no-bluetooth', 'place-reinstall-no-address'] as const] as const;
export type ImageDemo = (typeof IMAGE_DEMOS)[number];

const REFRACT_DOWN = 'the download proxy answered 502: GitHub answered 503';

/**
 * `deps`: what another demo changes on the simulated device (keepDemo.ts: the app's key before and after the flash).
 * `handOver`: the page's, which gives the device to the standard flow after the first start (flash-setup is its App step,
 * flash-setup-wifi Wi-Fi, flash-setup-ha Home Assistant, flash-done… its Done).
 */
export async function imageDemo({ frame, sim }: Pick<DeviceEntry, 'frame' | 'sim'>, name: ImageDemo, fast = false, deps: Partial<ImageDeps> = {}, handOver?: (p: PanelShell) => StandardSession | undefined): Promise<{ image: ImageSession; app: AppSession; standard?: () => StandardSession | undefined }> {
  let standard: StandardSession | undefined;
  const tick = (ms: number) => new Promise<void>((r) => setTimeout(r, fast ? 0 : ms));
  // the frame's community image: its id, its files in download order, and the published key its wifi-on step uses, if any
  const community = frame.imageFlow.recipes.find((x) => x.community)!;
  const [firstFile, secondFile] = community.files;
  const keyFile = community.steps.find((x) => x.do === 'wifi-on')?.key;
  let failures = 1; // a failed download fails once: Try again works
  const images: ImageMaker = {
    async fetch(_, onProgress) {
      if (name === 'flash-downloading') { onProgress(0.42); return new Promise(() => {}); }
      if (name === 'flash-download-error' && failures-- > 0) { onProgress(0.31); await tick(200); throw new ProxyError(REFRACT_DOWN); }
      if (name === 'flash-download-bad' && failures-- > 0) { for (let i = 1; i <= 10; i++) { await tick(60); onProgress(i / 10); } throw new WrongImage('its SHA-256 differs from the release'); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
    },
    async download(_, want, onProgress) {
      if (name === 'image-download') { onProgress(0.42); return new Promise(() => {}); }
      if (name === 'image-download-error' && want.name === firstFile?.name && failures-- > 0) { onProgress(0.31); await tick(200); throw new ProxyError(REFRACT_DOWN); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
      if (name === 'image-download-bad' && want.name === secondFile?.name && failures-- > 0) throw new WrongImage('its SHA-256 differs from the release');
    },
    // the demo's stand-in for the image's published key: a PEM that parses, not a real key
    async file(n) { return n === keyFile ? new Blob(['-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n']) : new Blob([new Uint8Array(16)]); },
    async check() {},
    async wifi(_, onProgress) {
      if (name === 'image-wifi-writing') { onProgress(0.55); return new Promise(() => {}); }
      for (let i = 1; i <= 5; i++) { await tick(150); onProgress(i / 5); }
      return new Blob([new Uint8Array(16)]);
    },
    async adbKey(_, onProgress) {
      for (let i = 1; i <= 5; i++) { await tick(150); onProgress(i / 5); }
      return new Blob([new Uint8Array(16)]);
    },
    async systemKey(_, onProgress) {
      for (let i = 1; i <= 10; i++) { await tick(150); onProgress(i / 10); }
      return new Blob([new Uint8Array(16)]);
    },
    async build(_, onProgress) {
      for (const step of ['patch', 'system', 'userdata'] as const) {
        for (let i = 1; i <= 5; i++) {
          if (name === 'flash-working' && step === 'system' && i === 3) { await tick(300); onProgress(step, 0.4); return new Promise(() => {}); } // stays here, painted twice
          await tick(150); onProgress(step, i / 5);
        }
      }
      return { system: new Blob([new Uint8Array(16)]), userdata: new Blob([new Uint8Array(16)]) };
    },
    async discard() {},
  };
  /** A community image after its first start: takes svc wifi enable and reports Wi-Fi on (or not). */
  const wifiShell = (): PanelShell => ({
    async run(c: string) { return { out: c === 'getprop sys.boot_completed' ? '1' : c === 'dumpsys wifi' ? `Wi-Fi is ${name === 'image-done-wifi-unconfirmed' ? 'disabled' : 'enabled'}\n` : '', code: 0 }; },
    async push() { throw new Error('not in this demo'); },
  });
  const fastboot = (system: number) => sim.fastboot(system, { fails: name === 'flash-error' || name === 'image-error', stuck: name === 'image-writing', tick });
  // flash-release…: the APK from a stand-in release (?apk=release), as the image screen shows it
  const release = name.startsWith('flash-release')
    ? fakeReleaseRoute(({ 'flash-release': 'out', 'flash-release-downloading': 'stuck', 'flash-release-none': 'none', 'flash-release-bad': 'bad' } as Record<string, FakeRelease>)[name]!, sim.apk(), tick, releaseLine(frame).line)
    : undefined;
  const app = new AppSession({ frame, connect: async () => { throw new Error('not in this demo'); }, ...(release ? { release } : {}) }, true);
  const image = new ImageSession({
    frame,
    images,
    refract: 'https://refract.glassworks.dev',
    search: name === 'image-link' ? `?image=${community.id}` : '',
    // flash-fastboot-denied: the browser lists the device but this computer won't let it open it (Chrome's words on Linux
    // without a udev rule, or on Windows with the wrong driver)
    requestFastboot: async () => {
      if (name === 'flash-fastboot-denied') throw new DOMException('Failed to execute \'open\' on \'USBDevice\': Access denied.', 'SecurityError');
      return fastboot(name === 'flash-fastboot-wrong' ? sim.wrongSystemBytes : sim.systemBytes);
    },
    // flash-adb-wait: the device refuses this browser's key while it starts, and Kiln asks again; flash-adb-refused: it
    // never lets it in, and Kiln stops asking (two tries here, five minutes on a device)
    ...(name === 'flash-adb-wait' || name === 'flash-adb-refused' ? { reopenAdb: async () => { throw new Error('No authenticator can handle the request'); } } : {}),
    ...(name === 'flash-adb-refused' ? { adbTries: 2 } : {}),
    requestAdb: async (o) => {
      o?.onPicked?.();
      // a community image's Wi-Fi on, with this browser's key: the image's own shell
      if (image.recipe.community) return wifiShell();
      if (name === 'flash-adb-wait' || name === 'flash-adb-refused') throw new Error('No authenticator can handle the request');
      // flash-error-first-start: the cable goes during the first start: its error, whose Try again connects over adb again
      if (name === 'flash-error-first-start') return { run: async () => { throw new Error('transferOut: the device was disconnected'); }, push: async () => {} };
      // the device after its first start, with Glassworks' setup channel and Improv (flash-setup: not on Wi-Fi yet)
      return withAppStreams(sim.firstStart({ hideFails: name === 'flash-done-chooser', noHome: name === 'flash-done-no-home' }), { online: name !== 'flash-setup-wifi', radio: false });
    },
    // the image's own key: an adb shell that has started, takes svc wifi enable and reports Wi-Fi on (or not)
    adbWithKey: () => async () => wifiShell(),
    adbKey: () => 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks',
    apk: () => app.apkBytes(),
    apkBlocked: () => app.releaseBlocked(),
    simulated: true,
    sleep: (ms) => tick(ms / 20),
    handOver: (p) => { standard = handOver?.(p); },
    ...deps,
  });
  const done = { image, app, standard: () => standard };
  if (name.startsWith('place-')) return placeDemo({ frame, sim }, name, fast, deps, handOver);
  if (name === 'image-choose' || name === 'image-link') return done;
  if (name === 'image-choose-open') { image.showCommunity(true); return done; }
  if (name === 'image-choose-panes') { image.showCommunity(true); image.pick(community.id); return done; }
  if (name.startsWith('image-')) return withCommunity(name, done, sim, community.id, frame.imageFlow.recipes[0]!.id);

  // the full image (the frame's first), picked: the flash demos are its screens, not the recommended in-place way
  image.pick(frame.imageFlow.recipes[0]!.id);
  image.choose();
  if (name === 'flash-image' || name === 'flash-run' || release) return done;
  if (name === 'flash-downloading') { void image.download(); return done; }
  await image.download(); // through Kiln's download route
  if (name === 'flash-download-error' || name === 'flash-download-bad') return done;
  await app.pickApk(sim.apkName, sim.apk());
  if (name === 'flash-ready') return done;
  image.toFastboot();
  if (name === 'flash-fastboot') return done;
  await image.connectFastboot();
  if (name === 'flash-fastboot-wrong' || name === 'flash-fastboot-denied' || name === 'flash-confirm') return done;
  image.understand(true);
  const flashing = image.flash();
  if (name === 'flash-working') return done;
  await flashing;
  if (name === 'flash-adb' || name === 'flash-error') return done;
  if (name === 'flash-adb-wait') { void image.connectAdb(); return done; }
  if (name === 'flash-adb-refused' || name === 'flash-error-first-start') { await image.connectAdb(); return done; }
  await image.connectAdb(); // then the standard flow: App, Wi-Fi, Home Assistant, Panes, Done
  await standardDemo(standard, name === 'flash-setup' ? 'app' : name === 'flash-setup-wifi' ? 'wifi' : name === 'flash-setup-ha' ? 'home-assistant' : 'done');
  return done;
}

/** The community image, picked on the choice: its download, fastboot, warning, writes and Done, and both ways back. */
async function withCommunity(name: ImageDemo, d: { image: ImageSession; app: AppSession; standard: () => StandardSession | undefined }, sim: DeviceEntry['sim'], id: string, glassworks: string) {
  const { image } = d;
  if (name === 'image-back-panes') { // Glassworks' Done, then its way back
    image.choose();
    await image.download();
    await d.app.pickApk(sim.apkName, sim.apk());
    image.toFastboot(); await image.connectFastboot(); image.understand(true); await image.flash(); await image.connectAdb();
    await standardDemo(d.standard(), 'done');
    image.wayBack(id);
    return d;
  }
  image.showCommunity(true);
  image.pick(id);
  image.choose();
  if (name === 'image-download') { void image.download(); return d; }
  await image.download();
  const withWifi = name.startsWith('image-wifi') || name === 'image-adb' || name.startsWith('image-done-wifi');
  if (withWifi) image.setWifi({ ssid: 'Home Net', password: 'not-a-real-password' }); // a made-up network (CLAUDE.md rule 2)
  if (name === 'image-wifi') return d;
  if (['image-download-done', 'image-download-error', 'image-download-bad'].includes(name)) return d;
  image.toFastboot();
  if (name === 'image-fastboot') return d;
  await image.connectFastboot();
  if (name === 'image-confirm') return d;
  image.understand(true);
  const writing = image.flash();
  if (name === 'image-writing' || name === 'image-wifi-writing') { await new Promise((r) => setTimeout(r, 0)); return d; }
  await writing;
  if (name === 'image-adb') return d;
  if (name.startsWith('image-done-wifi')) { await image.connectAdb(); return d; }
  if (name === 'image-back-glassworks') image.wayBack(glassworks);
  return d;
}

/** A fetch whose bundle sends its first piece, then waits: the local network transfer, held on screen. */
function heldFetch(f: typeof fetch): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = await f(input, init);
    if (!r.body) return r;
    const reader = r.body.getReader();
    let sent = false;
    return new Response(new ReadableStream<Uint8Array>({
      async pull(c) {
        if (sent) return new Promise<void>(() => {});
        const { value } = await reader.read();
        sent = true;
        if (value) c.enqueue(value);
      },
    }), { status: r.status, headers: r.headers });
  }) as typeof fetch;
}

/**
 * `place-…`: Glassworks on the device's own software (an in-place recipe), on the frame's simulated stock device
 * (`sim.inPlace`): its two fastboot sessions, its setup agent on the network and over Bluetooth, and adb after the
 * install. Each name stops on one screen; `place-run` clicks through to the standard flow.
 */
async function placeDemo({ frame, sim }: Pick<DeviceEntry, 'frame' | 'sim'>, name: ImageDemo, fast: boolean, deps: Partial<ImageDeps>, handOver?: (p: PanelShell) => StandardSession | undefined) {
  const ip = sim.inPlace;
  const recipe = frame.imageFlow.recipes.find((r) => r.mode === 'in-place');
  if (!ip || !recipe) throw new Error(`${frame.name} has no in-place install to show`);
  let standard: StandardSession | undefined;
  const tick = (ms: number) => new Promise<void>((r) => setTimeout(r, fast ? 0 : ms));
  const device = ip.device({
    lan: name === 'place-lna-denied' ? 'lna-denied' : name === 'place-receive-ble' || name === 'place-join-wifi' || name === 'place-wifi-unreachable' ? 'offline' : 'ok',
    tampered: name === 'place-error-bundle',
    // place-error-not-taken: fastboot takes the system write, but the started system still has the old file
    systemNotTaken: name === 'place-error-not-taken',
    tick: (ms) => tick(ms),
    ...(fast ? {} : { bleFrameDelayMs: 120 }),
  });
  const held = <T>() => new Promise<T>(() => {});
  let undoing = false;
  let userdataWrites = 0;
  const fastboot = () => {
    const fb = device.fastboot();
    // place-working: the second session's system write, held at half way; place-error-write: it fails there
    // place-undo-working / -error: the way back's write, held at 40 %, or failing
    if (name === 'place-working' || name === 'place-undo-working') return { ...fb, flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { if (p === 'system' && (name === 'place-working' || undoing)) { on?.(name === 'place-working' ? 0.5 : 0.4); return held<void>(); } return fb.flashBlob(p, b, on); } };
    if (name === 'place-error-write' || name === 'place-undo-error') return { ...fb, flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { if (p === 'system' && (name === 'place-error-write' || undoing)) { on?.(0.3); throw new Error('transferOut: the device was disconnected'); } return fb.flashBlob(p, b, on); } };
    // place-error-lost: the second session's userdata write (after the stage's) stops, once the system is written
    if (name === 'place-error-lost') return { ...fb, flashBlob: async (p: string, b: Blob, on?: (x: number) => void) => { if (p === 'userdata' && userdataWrites++ === 1) throw new Error('the bootloader stopped answering'); return fb.flashBlob(p, b, on); } };
    return fb;
  };
  // place-reinstall-…: a reinstall (the device's own Wi-Fi gone) in a browser without Bluetooth, with the device's
  // address from before the restart (192.0.2.44, RFC 5737) or none (on Ethernet, say)
  const reinstall = name === 'place-reinstall-no-bluetooth' || name === 'place-reinstall-no-address';
  const code = reinstall ? { ...ip.code, ownWifiGone: async () => true, lanAddress: async () => (name === 'place-reinstall-no-address' ? undefined : '192.0.2.44') } : ip.code;
  const frameWithStandIn = { ...frame, imageFlow: { ...frame.imageFlow, inPlace: name === 'place-stage' ? { ...code, stage: () => held<Blob>() } : code } };
  const app = new AppSession({ frame, connect: async () => { throw new Error('not in this demo'); } }, true);
  // place-resume… and place-error-lost: the tab's mark, kept in memory, and the browser's bundles and restore points,
  // shared by the run and the page load that opens on its mark (a reload)
  let mark: ProgressMark | undefined;
  const progress: DeviceProgress | undefined = name.startsWith('place-resume') || name === 'place-error-lost' ? { get: () => mark, set: (m) => { mark = m; } } : undefined;
  const store = memoryInPlaceStore();
  const sessionDeps: ImageDeps = {
    frame: frameWithStandIn,
    images: { fetch: async () => {}, download: async () => {}, file: async () => new Blob([]), check: async () => {}, build: async () => { throw new Error('not in this demo'); },
      wifi: async () => new Blob([]), adbKey: async () => new Blob([]), systemKey: async () => new Blob([]), discard: async () => {} },
    requestFastboot: async () => fastboot(),
    requestAdb: async (o) => {
      o?.onPicked?.();
      // place-first-start: picked, and still starting (no answer yet); place-installing: let in, its first start still running
      if (name === 'place-first-start') return held<PanelShell>();
      if (name === 'place-installing') return { run: () => held(), push: async () => {} } as PanelShell;
      const a = device.adb();
      return Object.assign(withAppStreams(a, { online: true, radio: false }), a.rebootToBootloader ? { rebootToBootloader: () => a.rebootToBootloader!() } : {});
    },
    adbKey: () => 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks',
    apk: () => app.apkBytes(),
    simulated: true,
    sleep: (ms) => tick(ms / 20),
    inPlaceStore: store,
    ...(progress ? { progress } : {}),
    bluetooth: reinstall ? { getAvailability: async () => false } as unknown as BluetoothLike : device.bluetooth as BluetoothLike,
    agentFetch: name === 'place-receive-lan' ? heldFetch(device.fetch) : device.fetch,
    localNetworkPermission: async () => (name === 'place-lna-denied' ? 'denied' : 'granted'),
    handOver: (p) => { standard = handOver?.(p); },
    hotspotPollMs: fast ? 5 : 1500,
    ...deps,
  };
  const make = (o: Partial<ImageDeps> = {}) => new ImageSession({ ...sessionDeps, ...o });
  const image = make();
  const done = { image, app, standard: () => standard };
  /** The page load after a reload: a new session that opens on the run's mark. */
  const reloaded = () => ({ ...done, image: make() });
  image.pick(recipe.id);
  if (name === 'place-choose') return done;
  image.choose();
  await app.pickApk(sim.apkName, sim.apk());
  image.setWifi({ ssid: 'Home Net', password: 'not-a-real-password' }); // a made-up network (CLAUDE.md rule 2)
  if (name === 'place-start') return done;
  image.toFastboot();
  if (name === 'place-fastboot') return done;
  if (reinstall) await image.restartToFastboot(); // over adb: where Kiln learns the device's own Wi-Fi is gone
  await image.connectFastboot();
  if (name === 'place-confirm') return done;
  image.understand(true);
  const staging = image.flash();
  if (name === 'place-stage') { await new Promise((r) => setTimeout(r, 0)); return done; }
  await staging;
  if (name === 'place-resume-staged') return reloaded();
  if (name === 'place-receive-file') { image.receiveByHand(); return done; }
  if (reinstall) return done;
  // the device's own Wi-Fi: before and after this computer joins it, and a Continue that finds nothing there
  if (name === 'place-join-wifi') return done;
  if (name === 'place-joined') { await waitFor(() => !!image.state.receive?.joined); return done; }
  if (name === 'place-wifi-unreachable' || name === 'place-lna-denied') { await image.receiveWifi(); return done; }
  const receiving = name === 'place-receive-ble' ? image.receive() : image.receiveWifi();
  if (name === 'place-receive-lan' || name === 'place-receive-ble') { void receiving; return done; }
  await receiving;
  if (name === 'place-error-bundle' || name === 'place-fastboot-again') return done;
  if (name === 'place-resume') return reloaded();
  const second = image.connectFastboot();
  await waitFor(() => image.state.screen === 'restore-point' && image.state.restorePoint?.status === 'ready');
  await second;
  if (name === 'place-restore-point') return done; // Continue waits for the copy
  // the person downloads the copy and says it's saved: Continue opens
  await image.restoreZip();
  if (name === 'place-restore-offered') return done;
  await image.confirmCopy();
  if (name === 'place-restore-copied' || name === 'place-working' || name === 'place-error-write') {
    if (name === 'place-working') void image.continueInstall();
    if (name === 'place-error-write') await image.continueInstall();
    return done;
  }
  await image.continueInstall();
  if (name === 'place-connect') return done;
  if (name.startsWith('place-undo')) {
    // place-undo-refused: a restore point whose original file changed in this browser since it was saved; the check
    // before the first write refuses it, and nothing is written
    if (name === 'place-undo-refused') {
      const kept = (await store.latestRestore())!;
      await store.putRestore(kept.serial, kept.digest, kept.files.map((f) => (f.filename.endsWith('.orig') ? { ...f, data: f.data.map((x, i) => (i === 0 ? x ^ 1 : x)) } : f)));
    }
    await image.startUndo();
    if (name === 'place-undo') return done;
    undoing = true;
    image.undoByHand();
    if (name === 'place-undo-fastboot') return done;
    const restoring = image.undoFlash();
    if (name === 'place-undo-working') { await waitFor(() => image.state.undo?.at === 'working' && (image.state.undo?.x ?? 0) > 0); return done; }
    await restoring;
    return done;
  }
  if (name === 'place-first-start' || name === 'place-installing') { void image.connectAdb(); await waitFor(() => image.state.adbWaiting === true || image.state.work?.step === 'first-boot'); return done; }
  if (name === 'place-error-lost') {
    // the page reloads in a browser that has lost the bundle, after the system write: the way back, not a new stage
    const again = make({ inPlaceStore: memoryInPlaceStore() });
    await again.connectFastboot();
    await waitFor(() => again.state.screen === 'error');
    return { ...done, image: again };
  }
  await image.connectAdb();
  // the read back after the restart stops it before the app's install: the way back is its one action
  if (name === 'place-error-not-taken') return done;
  await standardDemo(standard, 'done');
  return done;
}

/** Waits (a few seconds at most) until `ok()`: a demo's step that runs in the background. */
async function waitFor(ok: () => boolean): Promise<void> {
  for (let i = 0; i < 400 && !ok(); i++) await new Promise((r) => setTimeout(r, 10));
}
