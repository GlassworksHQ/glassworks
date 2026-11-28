// Development and preview only (?demo=…): a simulated device running Glassworks, so every screen can be seen and
// captured without a panel. What only the frame knows of it (its faked recipe, getprops, log) comes beside the frame
// as a DemoFrame's `sim`, from its installer's sim.ts; the frame Kiln installs never has it. `?demo=run` drives the
// whole flow through the real InstallFlow and Session with a fake device behind them; the other names jump straight
// to one state.
import type { PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';
import { isMagiskPatched, loadCompressor } from '@glassworks/bootpatch';
import { Assets, BackupRefused, checkRestorable, fmtSize, type BackupFile, type BackupMeta } from '@glassworks/kiln-core';
import { IMPROV_SOCKET } from '@glassworks/app-adb';
import { SETUP_SOCKET, type PaneCard, type SetupState } from '@glassworks/setup-channel';
import { PacketReader, PacketType, Rpc, State as ImprovState, frame as packet, parseResult, strings, type ByteStream } from '@glassworks/improv-serial';
import { explain, type Written } from './explain';
import { firstStart, type Changed } from './state';
import type { AppPhase, DemoFrame } from '@glassworks/kiln-core';
import { Session, type SessionDeps } from './session';

export const DEMOS = ['run', 'selftest', 'connect', 'ask-fastboot', 'install', 'ask-adb', 'first-boot', 'app', 'app-update', 'app-installing', 'app-keyboard', 'wifi', 'wifi-new', 'wifi-fail', 'home-assistant', 'home-assistant-signed-in', 'panes', 'panes-busy', 'panes-refused', 'radio', 'radio-next', 'radio-thread', 'radio-thread-old',
  'done', 'done-old-boot', 'undo', 'undo-refused', 'undo-refused-splash', 'undo-apps-back', 'undo-still-on', 'undo-unreadable', 'undo-connect', 'restore', 'undo-done', 'error', 'error-written',
  'error-no-recovery', 'error-secure-boot', 'error-not-this-panel', 'error-usb-unreliable', 'error-download', 'error-release', 'error-no-release', 'error-not-started', 'error-adb-held',
  'error-backup-refused', 'error-nothing-picked', 'error-earlier', 'error-unknown', 'error-unknown-written'] as const;
export type Demo = (typeof DEMOS)[number];

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const IP = '192.0.2.50';

/** Whether the simulated panel is on Wi-Fi: its app's setup state and Android's `dumpsys wifi` read it. */
/**
 * `radioOff`: Android's Wi-Fi is off (a fresh flash): its scans find nothing until `svc wifi enable`. `noLocation`: the app
 * has no location grant: its scans find nothing until `pm grant … ACCESS_FINE_LOCATION`.
 */
interface Net { online: boolean; radioOff?: boolean; noLocation?: boolean }

/** `dumpsys wifi` on a panel on the made-up "Home Net", in Android 10's shape, the network added by `pkg`. */
const dumpsysWifi = (pkg: string) => [
  'mWifiInfo SSID: Home Net, BSSID: 02:00:00:00:00:01, Supplicant state: COMPLETED, RSSI: -48, Net ID: 0, score: 60',
  'Configured networks Begin ----',
  '* ID: 0 SSID: "Home Net" PROVIDER-NAME: null BSSID: null FQDN: null PRIO: 0 HIDDEN: false',
  ` cuid=10071 cname=${pkg} luid=10071`,
  'Configured networks End ----',
].join('\n');

/** What a simulated app's Panes step does: its cards, and `refuse`: Continue refused, saying why. */
export interface DemoPanes { cards: PaneCard[]; refuse?: string }

/**
 * The app's setup channel on a simulated panel. `radio`: the app reports one (a frame without one leaves it out).
 * `panes`: the app offers the Panes step (issue 621); without, it has none, as apps before it.
 */
function setupStream(net: Net, radio = true, panes?: DemoPanes, ha?: Partial<SetupState['ha']>): ByteStream {
  let cb: (c: Uint8Array) => void = () => {};
  const state: SetupState = {
    ip: net.online ? IP : undefined,
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: `http://${IP}:8099/signin`, ...ha },
    radio: {
      mode: 'off', status: 'Off: radio idle', helper: true, answered: false,
      options: [
        { id: 'off', label: 'Off', detail: 'The radio stays idle. Choose this if you don\'t use Zigbee or Thread here.' },
        { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: 'Home Assistant runs a Zigbee network (ZHA) on this panel\'s radio, over the network.' },
        { id: 'thread', label: 'Thread border router', detail: 'Runs a Thread network for Matter devices, through Home Assistant\'s OpenThread Border Router integration.' },
        { id: 'zigbee_router', label: 'Zigbee router (repeater)', detail: 'Joins the Zigbee network you already have.', experimental: true, repeater: true },
        { id: 'thread_router', label: 'Thread router (repeater)', detail: 'Joins the Thread network you already have.', experimental: true, repeater: true },
      ],
    },
  };
  if (!radio) delete (state as Partial<SetupState>).radio;
  if (panes) state.panes = panes.cards.map((c) => ({ ...c }));
  const send = (o: object, ms = 0) => setTimeout(() => cb(new TextEncoder().encode(`${JSON.stringify(o)}\n`)), ms);
  return {
    onData: (f) => { cb = f; },
    close: async () => {},
    write: async (data) => {
      for (const line of new TextDecoder().decode(data).split('\n').filter(Boolean)) {
        const m = JSON.parse(line) as { id: number; op: string; url?: string; dashboard?: string; mode?: string; on?: string };
        if (m.op === 'panes.set' && panes?.refuse) { send({ id: m.id, ok: false, error: panes.refuse }, 1200); continue; }
        if (m.op === 'panes.set') {
          const on = new Set((m.on ?? '').split(',').filter(Boolean));
          state.panes = state.panes?.map((c) => ({ ...c, on: on.has(c.id) }));
        }
        if (m.op === 'set_ha') { state.ha.url = m.url; setTimeout(() => { state.ha.signedIn = true; send({ event: 'state', state }); }, 6000); }
        if (m.op === 'ha.set_dashboard' && m.dashboard) state.ha.dashboard = m.dashboard;
        if (m.op === 'set_radio') {
          state.radio.mode = m.mode!;
          state.radio.answered = true;
          state.radio.status = m.mode === 'off' ? 'Off: radio idle' : 'ok Zigbee coordinator (TCP): listening on :6638 (waiting for Home Assistant to connect over ESPHome)';
          state.radio.allowed = m.mode === 'off' ? undefined : '';
        }
        state.ip = net.online ? IP : undefined;
        send({ id: m.id, ok: true, state }, m.op === 'set_radio' || m.op === 'panes.set' ? 1500 : 200);
      }
    },
  };
}

/** A panel answering Improv Serial; `fail`: a wrong password. */
function improvStream(fail: boolean, net: Net): ByteStream {
  const reader = new PacketReader();
  let cb: (c: Uint8Array) => void = () => {};
  const send = (t: number, d: Uint8Array, ms = 0) => setTimeout(() => cb(packet(t, d)), ms);
  const result = (cmd: number, ss: string[], ms = 0) => {
    const b = strings(...ss);
    send(PacketType.RpcResult, Uint8Array.of(cmd, b.length, ...b), ms);
  };
  return {
    onData: (f) => { cb = f; },
    close: async () => {},
    write: async (data) => {
      for (const p of reader.push(data)) {
        const cmd = p.data[0];
        if (cmd === Rpc.RequestState) send(PacketType.State, Uint8Array.of(net.online ? ImprovState.Provisioned : ImprovState.Ready));
        if (cmd === Rpc.ScanWifi) {
          if (!net.radioOff && !net.noLocation) [['Home Net', '-48', 'YES'], ['Home Net 5G', '-62', 'YES'], ['Neighbours', '-74', 'YES'], ['Cafe Guest', '-83', 'NO']]
            .forEach((n, i) => result(Rpc.ScanWifi, n, 600 + i * 40));
          result(Rpc.ScanWifi, [], 800);
        }
        if (cmd === Rpc.WifiSettings) {
          void parseResult(p.data);
          send(PacketType.State, Uint8Array.of(ImprovState.Provisioning), 900);
          if (fail) send(PacketType.Error, Uint8Array.of(0x03), 4200);
          else { setTimeout(() => { net.online = true; }, 4200); send(PacketType.State, Uint8Array.of(ImprovState.Provisioned), 4200); result(Rpc.WifiSettings, [`http://${IP}:6053`], 4250); }
        }
      }
    },
  };
}

/**
 * `p` with Glassworks' setup channel and Improv answering, for any frame's simulated device after its app goes in.
 * `online`: on Wi-Fi already; `radio`: the app reports a radio; `wifiOff`: Android's Wi-Fi is off until `svc wifi enable`.
 */
export function withAppStreams(p: PanelShell, o: { online: boolean; wifiFails?: boolean; radio?: boolean; wifiOff?: boolean; noLocation?: boolean; panes?: DemoPanes }): PanelShell {
  const net: Net = { online: o.online, radioOff: o.wifiOff, noLocation: o.noLocation };
  return {
    run: async (cmd, signal) => {
      if (cmd === 'settings get global wifi_on') return { out: net.radioOff ? '0\n' : '1\n', code: 0 };
      if (cmd === 'svc wifi enable') { net.radioOff = false; return { out: '', code: 0 }; }
      if (/^pm grant \S+ android\.permission\.ACCESS_FINE_LOCATION$/.test(cmd)) { net.noLocation = false; return { out: '', code: 0 }; }
      const r = await p.run(cmd, signal);
      // Android 6's runtime permissions in `dumpsys package`, as the image path leaves them
      if (cmd.startsWith('dumpsys package ') && net.noLocation) return { ...r, out: `${r.out}    runtime permissions:\n      android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ ]\n` };
      return r;
    },
    push: (path, data, mode) => p.push(path, data, mode),
    ...(p.pull ? { pull: (path: string, x?: { signal?: AbortSignal; maxBytes?: number }) => p.pull!(path, x) } : {}),
    openStream: async (svc) => (svc === SETUP_SOCKET ? setupStream(net, o.radio ?? true, o.panes) : svc === IMPROV_SOCKET ? improvStream(!!o.wifiFails, net) : p.openStream!(svc)),
  };
}

/** `online`: on Wi-Fi already, as a device set up with its own apps is. */
/**
 * `app`: Glassworks' version on the device (`dumpsys package`), or null when it isn't installed. `current`: its
 * software is current, so it answers the frame's `sim.props`.
 */
export function simulatedPanel({ frame, sim }: DemoFrame, wifiFails = false, online = true, app: { name: string; code: number } | null = { name: '0.5.0', code: 500 }, current = true, panes?: DemoPanes, ha?: Partial<SetupState['ha']>): PanelShell {
  const net: Net = { online };
  const pkg = frame.appId.package;
  const prop = (cmd: string) => (current ? sim.props[cmd.replace(/^getprop /, '')] : undefined);
  const out = (cmd: string) => cmd === 'getprop sys.boot_completed' || cmd.startsWith('grep -c') ? '1\n'
    : cmd.startsWith('getprop ') && prop(cmd) ? `${prop(cmd)}\n`
    : cmd === `pm path ${pkg}` && app ? `package:/data/app/${pkg}-1/base.apk\n`
    : cmd === `dumpsys package ${pkg}` && app ? `    versionCode=${app.code} minSdk=26 targetSdk=34\n    versionName=${app.name}\n`
    : cmd === 'dumpsys wifi' && net.online ? dumpsysWifi(pkg)
    : cmd.startsWith('ip -4') && net.online ? `3: wlan0    inet ${IP}/24 brd 192.0.2.255 scope global wlan0\n` : '';
  return {
    run: async (cmd) => ({ out: out(cmd), code: 0 }),
    push: async () => {},
    openStream: async (svc) => (svc === SETUP_SOCKET ? setupStream(net, true, panes, ha) : improvStream(wifiFails, net)),
  };
}

/**
 * The smallest boot image the engine accepts: an AOSP v2 header and a raw cpio holding only its
 * trailer. Enough for isMagiskPatched() to say "not patched".
 */
export function demoBootImage(size = 1 << 20): Uint8Array {
  const img = new Uint8Array(size);
  const dv = new DataView(img.buffer);
  img.set(new TextEncoder().encode('ANDROID!'), 0);
  const name = new TextEncoder().encode('TRAILER!!!\0');
  const hex = (n: number) => n.toString(16).padStart(8, '0');
  const head = `070701${[0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, name.length, 0].map(hex).join('')}`;
  const cpio = new Uint8Array(Math.ceil((110 + name.length) / 4) * 4);
  cpio.set(new TextEncoder().encode(head), 0);
  cpio.set(name, 110);
  dv.setUint32(16, cpio.length, true); // ramdisk size
  dv.setUint32(36, 4096, true); // page size
  dv.setUint32(40, 2, true); // header version
  dv.setUint32(1644, 1660, true); // header size
  img.set(cpio, 4096);
  return img;
}

/** U-Boot's fastboot on the simulated panel: the recipe's partitions, scaled down so the demo is quick. */
function fakeFastboot(parts: readonly string[]): Fastboot {
  const sizes = new Map<string, number>(parts.map((p) => [p, p === 'boot' ? 1 << 20 : 256 << 10]));
  const data = new Map<string, Uint8Array>([['boot', demoBootImage()]]);
  const chunked = async (n: number, on?: (d: number, t: number) => void) => {
    for (let i = 1; i <= 8; i++) { await wait(60); on?.((n * i) / 8, n); }
  };
  const fb = {
    log: (_: string) => {},
    getvar: async () => 'sunxi',
    partitionSize: async (p: string) => sizes.get(p) ?? 0,
    readPartition: async (p: string, on?: (d: number, t: number) => void) => {
      const n = sizes.get(p)!;
      await chunked(n, on);
      return data.get(p) ?? new Uint8Array(n);
    },
    flashVerified: async (p: string, d: Uint8Array, on?: (ph: 'write' | 'verify', done: number, t: number) => void) => {
      await chunked(d.length, (x, t) => on?.('write', x, t));
      await chunked(d.length, (x, t) => on?.('verify', x, t));
      data.set(p, d);
    },
    reboot: async () => {},
  };
  return fb as unknown as Fastboot;
}

/** A session on the simulated panel. */
export function demoSession(d: DemoFrame, which: Demo): Session {
  const { frame, sim } = d;
  const parts = frame.recipe.backup.parts;
  const saved: { meta: BackupMeta; files: BackupFile[] }[] = [];
  // run and the app's first-install states start without Glassworks on the panel; radio-thread-old has 0.4.3
  const app = ['run', 'app', 'app-installing', 'app-keyboard'].includes(which) ? null
    : which === 'radio-thread-old' ? { name: '0.4.3', code: 403 } : { name: '0.5.0', code: 500 };
  // the Panes mockup (issue 621, for approval): an app that offers the step; every other state's app has none, as today's
  const panes = which.startsWith('panes') ? { cards: sim.panes ?? [], refuse: which === 'panes-refused' ? 'Voice couldn\'t open the microphone. Try again, or leave Voice off.' : undefined } : undefined;
  const ha = which === 'home-assistant-signed-in'
    ? { url: 'http://homeassistant.local:8123', signedIn: true, dashboards: ['Default', 'Tablet', 'Energy'], dashboard: 'Default' }
    : undefined;
  const panel = simulatedPanel(d, which === 'wifi-fail', which !== 'wifi-fail' && which !== 'wifi-new', app, true, panes, ha);
  let adbAllowed = which !== 'run'; // the static states start on an installed panel
  const deps: SessionDeps = {
    recipe: sim.recipe,
    frame,
    simulated: true,
    sleep: (ms) => wait(Math.min(ms, 800)),
    // error-written: "Try again", then Connect, is a picker closed with nothing picked
    requestFel: async () => {
      if (which === 'error-written') throw new DOMException('No device selected.', 'NotFoundError');
      return { log: () => {} } as unknown as FelDevice;
    },
    asset: async () => new Uint8Array(16),
    hasAsset: async () => false,
    backups: {
      save: async (meta, files) => { saved.push({ meta, files }); },
      original: async () => saved[0]?.meta ?? (which.startsWith('undo')
        ? { id: `${frame.recipe.backup.idPrefix}-20261112-090000`, created: '', bootWasPatched: false, files: parts.map((part) => ({ part, size: 4096, sha256: '' })) }
        : undefined),
      files: async () => {
        if (which === 'undo-unreadable') throw new DOMException('The operation failed for reasons unrelated to the database itself.', 'UnknownError');
        return saved[0]?.files ?? parts.map((part) => ({ part, data: new Uint8Array(4096), sha256: '', crc: 0 }));
      },
    },
    loadKey: async () => ({ publicKey: 'QAAAAdemo kiln@demo' }) as never,
    devices: {
      knownFastboot: async () => undefined,
      requestFastboot: async () => fakeFastboot(parts),
      knownAdb: async () => (adbAllowed ? panel : undefined),
      requestAdb: async () => { adbAllowed = true; return panel; },
    },
    openTab: () => null,
    confirm: () => true,
    download: () => {},
  };
  return new Session(deps);
}

/** Jumps a fresh demo session to one state. `run` starts the live flow from Connect. */
export async function enterDemo(session: Session, d: DemoFrame, which: Demo): Promise<void> {
  const { dispatch } = session.store;
  const { frame, sim } = d;
  const plan = frame.recipe.backup;
  /** what the real check says of `files` against `p`, worded as the give-back shows it */
  const refused = async (p: typeof plan, files: Map<string, Uint8Array>) => {
    const why = await checkRestorable(p, files).then(() => '', (e: Error) => e.message);
    dispatch({ type: 'undo', patch: { depth: 'keep', note: { kind: 'err', title: 'This backup can\'t undo the install.', sub: `${why.charAt(0).toUpperCase()}${why.slice(1)}. This step changed nothing on the panel.`, link: { href: frame.help.undo, text: 'Undo by hand' } } } });
  };
  const never = new AbortController().signal;
  const progress = (overall: number, phase: 'boot' | 'backup' | 'patch' | 'flash' | 'verify', title: string, sub: string) =>
    dispatch({ type: 'progress', p: { overall, phase, phasePct: 0, title, sub } });
  const install = () => {
    dispatch({ type: 'start', flow: 'install', now: Date.now() - 95_000 });
    progress(0.05, 'boot', 'Waiting for the panel to reconnect', '');
  };
  switch (which) {
    case 'run':
    case 'connect':
      return;
    case 'selftest':
      return selfTest(session);
    case 'ask-fastboot':
      install();
      void session.asks.open('fastboot', () => new Promise(() => {}), never);
      return;
    case 'install':
      install();
      for (const [i, part] of plan.parts.entries()) {
        dispatch({ type: 'partition', part, state: 'done', info: { size: part === 'boot' ? 32 << 20 : 1 << 20, sha256: `${(0x3a7f + i * 977).toString(16)}…${(0x91c2 - i * 313).toString(16)}` } });
      }
      for (const l of sim.installLog) {
        dispatch({ type: 'log', line: `10:15:00 ${l}` });
      }
      progress(0.68, 'flash', 'Writing the new software', 'Writing boot');
      return;
    case 'ask-adb':
      dispatch({ type: 'setup', setup: firstStart(frame) });
      void session.asks.open('adb', () => new Promise(() => {}), never);
      return;
    case 'first-boot': // picked: waiting for the panel's first start
      dispatch({ type: 'setup', setup: firstStart(frame) });
      return;
    case 'app': // a first install: the panel has no Glassworks yet
    case 'app-update': // Glassworks is on the panel: installing replaces it
      await session.route('resume');
      return;
    case 'app-installing': // Android installing and optimising the app
    case 'app-keyboard': // the next APK: the on-screen keyboard
      await session.route('resume');
      dispatch({ type: 'setup', setup: { phase: 'settings', update: false, chosen: { clock24: true, keyboard: true }, installing: [
        { step: 'apk', apk: 'kiosk', stage: 'copy' }, { step: 'apk', apk: 'kiosk', stage: 'install' },
        ...(which === 'app-keyboard' ? [{ step: 'home' }, { step: 'apk', apk: 'keyboard', stage: 'install' }] as AppPhase[] : []),
      ] } });
      return;
    case 'wifi': // the panel is on Wi-Fi already
    case 'wifi-new': // it isn't: the networks it can see
    case 'wifi-fail':
      await session.route('wifi');
      return;
    case 'home-assistant':
    case 'home-assistant-signed-in':
      await session.route('home-assistant');
      return;
    case 'panes': // the Panes step (issue 621): a switch per pane the app offers
    case 'panes-refused': // Continue, and a pane couldn't apply its switch (click Continue)
      await session.route('panes');
      return;
    case 'panes-busy': // Continue pressed: the app applies the switches
      await session.route('panes');
      void session.click('panes', () => session.applyPanes(['wall-base', 'assist', 'sendspin', 'radio']));
      return;
    case 'radio':
      await session.route('radio');
      return;
    case 'radio-next':
      await session.route('radio');
      await session.applyRadio('zigbee_tcp');
      return;
    case 'radio-thread': // a Thread router, on a Glassworks that gets the network from Home Assistant
    case 'radio-thread-old': // the same on 0.4.3, which can't
      await session.route('radio');
      await session.applyRadio('thread_router');
      return;
    case 'done':
    case 'done-old-boot': // set up on a panel whose boot image is from before the root update service
      dispatch({ type: 'done', done: {
        title: 'You\'re all set', body: 'The panel is on Home Net. Home Assistant finds it by itself.',
        backup: { name: `${plan.idPrefix}-20261112-101500.zip`, info: sim.backupInfo, hint: 'Original software, backed up. Keep it.' },
        managePage: `http://${IP}:8099/`,
        updates: frame.silent[(await frame.updates(simulatedPanel(d, false, true, undefined, which === 'done'))).state],
      } });
      return;
    case 'undo':
      await session.openUndo();
      return;
    case 'undo-refused': // what the give-back shows for a backup undo can't restore: one with only boot
      await session.openUndo();
      await refused(plan, new Map([['boot', new Uint8Array(16)]]));
      return;
    case 'undo-apps-back': // "Keep its settings" after the apps are back: the backup is the one they were checked against
      await session.openUndo();
      dispatch({ type: 'undo', patch: { depth: 'keep', appsBack: true, locked: true, note: { kind: 'ok', title: frame.undo.keep.back, sub: 'Glassworks is removed. Next, put the original software back: Continue, then recovery mode.' } } });
      return;
    case 'undo-still-on': // the apps are back, but Android would not remove or disable Glassworks
      await session.openUndo();
      dispatch({ type: 'undo', patch: { depth: 'keep', note: { kind: 'err', title: frame.undo.keep.stillOn, sub: `Android would not turn Glassworks off. It is still installed, so the panel may start it again or ask which home screen to use. Try again, or choose "${frame.undo.factory.title}".` } } });
      return;
    case 'undo-unreadable': // this browser has a backup, but IndexedDB fails to read it
      await session.openUndo();
      return;
    case 'undo-refused-splash': { // the same for a backup whose first own part (a splash, issue 106) is already ours
      await session.openUndo();
      const [part, ours] = Object.entries(plan.ours ?? {})[0]!;
      const theirs = { ...plan, isPatched: async () => false, ours: { [part]: { ...ours, is: async () => true } } };
      await refused(theirs, new Map(plan.restore.map((p) => [p, new Uint8Array(16)])));
      return;
    }
    case 'undo-connect':
      await session.openUndo();
      dispatch({ type: 'go', screen: 'connect', flow: 'undo' });
      return;
    case 'restore':
      dispatch({ type: 'start', flow: 'undo', now: Date.now() - 40_000 });
      progress(0.45, 'flash', 'Putting the original software back', 'Writing boot');
      return;
    case 'undo-done':
      dispatch({ type: 'start', flow: 'undo', now: Date.now() });
      dispatch({ type: 'done', done: { title: 'The original software is back', body: 'One last step, on the panel: swipe down, tap Settings on the app\'s notification, then Advanced settings → Factory reset.' } });
      return;
    case 'error':
      install();
      dispatch({ type: 'fail', failure: explain(new Error('transfer error: the device was disconnected'), 2, 'none') });
      return;
    case 'error-written':
      install();
      dispatch({ type: 'log', line: '10:15:00 vbmeta flashed and verified' });
      dispatch({ type: 'fail', failure: explain(new Error('boot: read-back does not match what was written'), 2, 'verified') });
      return;
    default: {
      // every other way explain.ts can put a failure, one demo each (ERRORS)
      const e = ERRORS[which as keyof typeof ERRORS];
      if (!e) return;
      install();
      dispatch({ type: 'fail', failure: explain(e.error, e.at, e.written ?? 'none', e.installed, e.earlier) });
    }
  }
}

/** The failures explain.ts tells apart, as the engine raises them: each its own ?demo=error-…, for the error screen. */
const ERRORS: Partial<Record<Demo, { error: unknown; at: number; written?: Written; installed?: boolean; earlier?: Changed }>> = {
  'error-no-recovery': { error: new DOMException('No device selected.', 'NotFoundError'), at: 1 },
  'error-secure-boot': { error: new Error('secure boot is enabled on this chip (SID+0xa0 = 0x00000001): its boot ROM only runs signed code'), at: 1 },
  'error-not-this-panel': { error: new Error('this does not look like the expected device (product sunxi, boot 0, super 0). Nothing was changed.'), at: 2 },
  'error-usb-unreliable': { error: new Error('the two reads of boot differ'), at: 2 },
  'error-download': { error: new Error('could not download magisk: HTTP 503'), at: 2 },
  // the app from its newest release (wizard/assets.ts), after the new software is in place
  'error-release': { error: new Error('Couldn\'t download Glassworks 1.0.0 (the download proxy sent nothing for 30 s)'), at: 3, installed: true },
  'error-no-release': { error: new Error('There\'s no Glassworks release to install yet'), at: 3, installed: true },
  'error-not-started': { error: new Error('the panel did not finish starting in 5 minutes'), at: 3, installed: true },
  'error-adb-held': { error: new Error('another program holds the panel\'s adb connection: run "adb kill-server" on this computer and try again'), at: 3, installed: true },
  'error-backup-refused': { error: new BackupRefused('this backup has no super partition'), at: 1, earlier: 'written' },
  'error-nothing-picked': { error: new DOMException('No device selected.', 'NotFoundError'), at: 3, installed: true },
  'error-earlier': { error: new Error('transfer error: the device was disconnected'), at: 2, earlier: 'unknown' },
  'error-unknown': { error: new Error('something Kiln has no words for'), at: 2 },
  'error-unknown-written': { error: new Error('something Kiln has no words for'), at: 2, written: 'maybe' },
};

/**
 * ?demo=selftest: what #27 asks of the bundler, checked in the built page. bootpatch.wasm loads from
 * where Next put it, and every staged blob downloads and passes its SHA-256 (the APK through
 * Refract). Results go to the technical log; the page title ends in PASS or FAIL.
 */
async function selfTest(session: Session) {
  const { dispatch } = session.store;
  const say = (line: string) => dispatch({ type: 'log', line: `selftest ${line}` });
  dispatch({ type: 'start', flow: 'install', now: Date.now() });
  let ok = true;
  try {
    const c = await loadCompressor();
    const text = new TextEncoder().encode('kiln '.repeat(1000));
    const back = c.gunzip(c.gzip(text));
    if (back.length !== text.length || !back.every((x, i) => x === text[i])) throw new Error('gzip round trip differs');
    say('bootpatch.wasm loaded; gzip round trip OK');
    say(`isMagiskPatched(demo boot) = ${await isMagiskPatched(demoBootImage())}`);
  } catch (e) { ok = false; say(`FAIL wasm: ${(e as Error).message}`); }
  const assets = new Assets<string>('/assets/');
  let list: Awaited<ReturnType<typeof assets.getManifest>>['assets'] = [];
  try {
    list = (await assets.getManifest()).assets;
  } catch (e) { ok = false; say(`FAIL manifest: ${(e as Error).message}`); }
  for (const [i, a] of list.entries()) {
    try {
      const b = await assets.get(a.name);
      say(`${a.name} ${fmtSize(b.length)} sha256 OK (${/^https?:/.test(a.path) ? 'Refract' : 'static'})`);
    } catch (e) { ok = false; say(`FAIL ${a.name}: ${(e as Error).message}`); }
    dispatch({ type: 'progress', p: { overall: (i + 1) / list.length * 0.97, phase: 'backup', phasePct: 0, title: `Checked ${i + 1} of ${list.length} files`, sub: '' } });
  }
  say(ok ? 'PASS' : 'FAIL');
  document.title = `selftest ${ok ? 'PASS' : 'FAIL'}`;
}
