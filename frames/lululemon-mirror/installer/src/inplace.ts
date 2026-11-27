// The Mirror's in-place install for Kiln (Stock-in-place): the code behind kiln-core's `InPlaceCode`, for what data
// can't say. Kiln runs the steps (stage, receive, in-place, app-install), checks every image this builds against the
// bundle's extents (kiln-core inplace.ts `checkInPlace`), keeps the restore point, and owns every screen.
//
//   stage        a fresh userdata with the MIT stock probe app as its setup agent (stage.ts): userdata only
//   receive      the agent's GWSB bundle (the stock services.jar, where it sits on system, the serial): checked here
//   in-place     services.jar patched in place (stock.ts), as a surgical sparse image of its own blocks, the restore
//                image of the same blocks from the stock bytes, and a clean userdata
//   app-install  Glassworks over adb, the stock apps hidden (pm hide), and Glassworks as Device Owner
//   way back     the restore image over those blocks, then a fresh userdata: what app-install set (Glassworks, its
//                Device Owner record, the hidden apps, the home screen, the rotation) all lives in userdata, so the
//                Mirror comes back as stock with its app data fresh, as after a factory reset
//
// Browser-safe: no node:* imports. Nothing here logs the secret, the Wi-Fi password or the serial.
import type { PanelShell } from '@glassworks/adb';
import { Steps, grantPermissions, kioskVersion, startApp, zipEntry } from '@glassworks/app-adb';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { inPlaceWords, parseGwsbBundle, sha256Hex, verifyGwsbBundle, type FirstStart, type InPlaceCode, type RestoredFile } from '@glassworks/kiln-core';
import { GLASSWORKS, type MirrorApp } from './app';
import { makeOnlyHome, setPortrait } from './flash';
import { AID, LABELS, buildUserdata, macFromSerial } from './image';
import { DEFAULT_WIFI_TEMPLATE, STOCK_PROBE_ASSETS, STOCK_PROBE_LABELS, buildStageUserdata } from './stage';
import {
  SPARSE_BLOCK_SIZE, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES, USB_ALLOW_PERMISSION, buildSurgicalSparseImage, buildSurgicalSparseImageFiles, hideStockBloat, patchStockServicesJar, patchedKeyFingerprint,
  type FiemapExtent,
} from './stock';
import { describeMirrorUpdates, readMirrorUpdates } from './updates';
import { BOOT_ANIMATION_ASSET, ORIGINAL_BOOT, STOCK_BOOT_ANIMATION_PATH, bootInPlaceZip } from './bootstill';

/** The probe app's HTTP server (ProbeServerService.PORT) and bundle path. */
export const STOCK_PROBE_LAN = {
  port: 18765,
  path: '/stock-bundle.bin',
  pagePath: '/',
  // stock's MirrorApService opens mirror-<serial> (no password) on wlan0_ap, at 192.168.43.1, while nothing is set up
  hotspot: { ssid: 'mirror-{serial}', host: '192.168.43.1' },
} as const;

/** The probe app's Bluetooth GATT service and characteristics (BleProbeServer). */
export const STOCK_PROBE_BLE = {
  service: '67770001-6b69-6c6e-7374-6f636b70726f',
  meta: '67770002-6b69-6c6e-7374-6f636b70726f',
  ctrl: '67770003-6b69-6c6e-7374-6f636b70726f',
  data: '67770004-6b69-6c6e-7374-6f636b70726f',
} as const;

/** The restore point's files: the image that puts the stock services.jar back, its manifest, and the stock jar itself. */
export const RESTORE_SPARSE = 'restore-services.sparse';
export const ORIGINAL_JAR = 'services.jar.orig';

/**
 * A bundle Kiln mustn't build from, said in plain words. When it's thrown, lululemon's system hasn't been changed: only
 * the setup agent's app storage has been written, which the Glassworks image or a factory reset replaces.
 */
export class BundleRefused extends Error {}

/** What every refusal says about the Mirror: the stage wrote its app storage, and nothing else. */
const UNCHANGED = 'Kiln didn\'t change lululemon\'s system: only the setup agent is in the Mirror\'s app storage.';

interface BundleMeta {
  servicesPath?: unknown;
  servicesSize?: unknown;
  servicesSha256?: unknown;
  servicesOdexFound?: unknown;
  servicesHasClassesDex?: unknown;
  mirrorVersion?: unknown;
  fingerprint?: unknown;
  fiemap?: { size?: unknown; extents?: unknown } | null;
  /** Stock's boot animation, when the agent sent it (StockBundle.java `bootAnimation`): its bytes, SHA-256 and FIEMAP. */
  bootAnimation?: unknown;
}

/** Stock's boot animation as the bundle carries it, checked; or why Kiln leaves it as it is. */
type StockBoot = { data: Uint8Array; sha256: string; extents: FiemapExtent[] } | { skip: string };

/**
 * The bundle's `bootAnimation`, checked: at stock's path, its bytes the size and SHA-256 it says, and a whole file map
 * of plain blocks. Anything else leaves the boot animation as it is (`skip` says why): an agent from before it was
 * sent, a Mirror with none, or one Kiln can't read.
 */
export async function bundleBoot(meta: BundleMeta): Promise<StockBoot> {
  const b = meta.bootAnimation as { path?: unknown; size?: unknown; sha256?: unknown; fiemap?: { size?: unknown; extents?: unknown } | null; base64?: unknown } | null | undefined;
  if (!b || typeof b !== 'object') return { skip: 'the setup agent didn\'t send it' };
  if (b.path !== STOCK_BOOT_ANIMATION_PATH) return { skip: `the bundle's is ${String(b.path)}, not ${STOCK_BOOT_ANIMATION_PATH}` };
  if (typeof b.base64 !== 'string' || typeof b.size !== 'number' || typeof b.sha256 !== 'string') return { skip: 'the bundle doesn\'t carry its bytes' };
  let data: Uint8Array;
  try { data = Uint8Array.from(atob(b.base64), (c) => c.charCodeAt(0)); } catch { return { skip: 'its bytes aren\'t base64' }; }
  if (data.length !== b.size) return { skip: 'its bytes aren\'t the size the bundle says' };
  const sha256 = await sha256Hex(data);
  if (sha256 !== b.sha256.toLowerCase()) return { skip: 'its bytes don\'t match their SHA-256' };
  const f = b.fiemap;
  if (!f || !Array.isArray(f.extents) || !f.extents.length) return { skip: 'the bundle doesn\'t say where it sits' };
  if (f.size !== undefined && f.size !== data.length) return { skip: 'its file map is for another copy' };
  const extents = (f.extents as Record<string, unknown>[]).map((e) => {
    const n = (k: string) => (typeof e[k] === 'number' ? e[k] as number : NaN);
    const physical = n('physical'), length = n('length');
    return { logical: n('logical'), physical, length, flags: Number.isFinite(n('flags')) ? n('flags') : 0,
      block: Number.isFinite(n('block')) ? n('block') : physical / SPARSE_BLOCK_SIZE, blocks: Number.isFinite(n('blocks')) ? n('blocks') : length / SPARSE_BLOCK_SIZE };
  });
  // the same checks the write itself makes (alignment, plain blocks, no gaps): one Kiln couldn't write is left alone
  try { buildSurgicalSparseImageFiles(STOCK_SYSTEM_PARTITION_BYTES, [{ extents, data }]); } catch (e) { return { skip: `its file map isn't one Kiln writes (${(e as Error).message})` }; }
  return { data, sha256, extents };
}

/** The bundle's FIEMAP extents, whole (block and blocks filled in from bytes when the agent left them out). */
export function bundleExtents(meta: BundleMeta, size: number): FiemapExtent[] {
  const f = meta.fiemap;
  if (!f || !Array.isArray(f.extents) || !f.extents.length) throw new BundleRefused(`The bundle doesn't say where services.jar sits on the system partition, so Kiln can't change it in place. ${UNCHANGED}`);
  if (f.size !== undefined && f.size !== size) throw new BundleRefused(`The bundle's file map is for another copy of services.jar. ${UNCHANGED}`);
  return (f.extents as Record<string, unknown>[]).map((e) => {
    const n = (k: string) => (typeof e[k] === 'number' ? e[k] as number : NaN);
    const physical = n('physical'), length = n('length');
    return { logical: n('logical'), physical, length, flags: Number.isFinite(n('flags')) ? n('flags') : 0,
      block: Number.isFinite(n('block')) ? n('block') : physical / SPARSE_BLOCK_SIZE, blocks: Number.isFinite(n('blocks')) ? n('blocks') : length / SPARSE_BLOCK_SIZE };
  });
}

/**
 * The stop-gate before any system write: on an odexed build the code Android runs comes from services.odex, so a change
 * to services.jar would do nothing (or worse, leave the two out of step). Kiln goes on only when the agent says, in so
 * many words, that there is no services.odex and that services.jar carries its own classes.dex: a bundle that says
 * nothing about either (or says it in another shape) is refused like an odexed one.
 */
export function checkNotOdexed(meta: BundleMeta): void {
  if (meta.servicesOdexFound === true || meta.servicesHasClassesDex === false) {
    throw new BundleRefused(`This Mirror's software runs services.jar from a precompiled copy (services.odex), so Kiln can't change it in place. ${UNCHANGED} Install the Glassworks image instead, which replaces the app storage too.`);
  }
  if (meta.servicesOdexFound !== false || meta.servicesHasClassesDex !== true) {
    throw new BundleRefused(`The bundle doesn't say whether this Mirror's software runs services.jar itself or a precompiled copy (services.odex), so Kiln can't change it in place. ${UNCHANGED} Install the Glassworks image instead, which replaces the app storage too.`);
  }
}

/** The extents as the agent's description or the bundle gives them, for comparing: where each piece of the file sits. */
function layoutOf(fiemap: unknown): string | undefined {
  const f = fiemap as { size?: unknown; extents?: unknown; truncated?: unknown } | null | undefined;
  if (!f || typeof f !== 'object' || f.truncated || !Array.isArray(f.extents) || !f.extents.length) return undefined;
  const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : NaN);
  const extents = (f.extents as Record<string, unknown>[]).map((e) => [n(e?.logical), n(e?.physical), n(e?.length)]);
  if (extents.some((e) => e.some((x) => Number.isNaN(x)))) return undefined;
  return JSON.stringify({ size: n(f.size), extents });
}

/**
 * Whether a bundle this browser kept still fits the Mirror as its agent describes it now: the same services.jar can
 * sit in other blocks after a stock update or a reflash, so the kept bundle's file map must be the one the agent
 * reports today. The same goes for the boot animation when the kept copy carries one: Kiln writes it in place too, so
 * its file map must also be today's. When the agent's description carries no whole file map for either (it trims them
 * to fit Bluetooth's 512 bytes), Kiln can't tell, so it takes the bundle again.
 */
export function keptBundleFits(bundle: Uint8Array, agent: Record<string, unknown>): boolean {
  const now = layoutOf(agent.fiemap);
  if (!now) return false;
  // an agent that sends stock's boot animation (the whole object, or its size where the description is short) over a
  // copy from an agent that didn't: the fresh one lets Kiln change the boot animation too
  const agentAnim = agent.bootAnimation !== null && typeof agent.bootAnimation === 'object' ? agent.bootAnimation as { fiemap?: unknown } : undefined;
  const agentBoot = !!agentAnim || typeof agent.bootAnimationSize === 'number';
  try {
    const meta = parseGwsbBundle(bundle).meta as BundleMeta;
    const keptAnim = meta.bootAnimation !== null && typeof meta.bootAnimation === 'object' ? meta.bootAnimation as { fiemap?: unknown } : undefined;
    if (agentBoot && !keptAnim) return false;
    if (keptAnim) {
      // the kept animation's blocks must be the ones the agent reports now, from a whole file map
      const animNow = layoutOf(agentAnim?.fiemap);
      if (!animNow || layoutOf(keptAnim.fiemap) !== animNow) return false;
    }
    return layoutOf(meta.fiemap) === now;
  } catch {
    return false;
  }
}

/**
 * Builds a fresh userdata as a sparse image: this browser's adb key, USB debugging on from the first moment of boot, and
 * the network when given. The USB setting is the stage's (`persist.sys.usb.config=adb`): stock turns adb on only in its
 * post-boot script's last line, after boot completes, so without it the Mirror's first start (5–7 minutes of app
 * optimizing) has no adb at all. With it, adb is up from the start, as on the stage.
 */
async function cleanUserdata(o: { adbKey: string; userdataBytes: number; wifi?: { ssid: string; password: string } }): Promise<Blob> {
  const disk = new Disk(new MemoryStore(), 4096, Math.floor(o.userdataBytes / 4096), { zeroed: true });
  const time = Math.floor(Date.now() / 1000);
  const fs = await buildUserdata(disk, { adbKey: o.adbKey, ...(o.wifi ? { wifi: { ...o.wifi, template: DEFAULT_WIFI_TEMPLATE } } : {}) });
  // init.rc: mkdir /data/property 0700 root root; property files are 0600 root:root, property_data_file (as the stage's)
  await fs.mkdir('/property', { mode: 0o700, uid: 0, gid: 0, label: STOCK_PROBE_LABELS.property, time });
  await fs.writeFile('/property/persist.sys.usb.config', new TextEncoder().encode('adb'), { mode: 0o600, uid: 0, gid: 0, label: STOCK_PROBE_LABELS.property, time });
  await fs.flush();
  const parts: Uint8Array[] = [];
  await writeSparse(disk, (b) => { parts.push(b.slice()); });
  return new Blob(parts as BlobPart[]);
}

const blob = (b: Uint8Array) => new Blob([b as Uint8Array<ArrayBuffer>]);

/**
 * The way back's userdata, as a factory reset leaves it: an empty filesystem with only its root and lost+found, the
 * same owners and labels the install's userdata has. No adb key and no network: stock asks for neither.
 */
export async function factoryUserdata(userdataBytes: number, time = Math.floor(Date.now() / 1000)): Promise<Blob> {
  const disk = new Disk(new MemoryStore(), 4096, Math.floor(userdataBytes / 4096), { zeroed: true });
  const fs = await mkfs(disk, {
    label: 'userdata', time,
    root: { mode: 0o771, uid: AID.system, gid: AID.system, label: LABELS.data },
    lostFound: { mode: 0o770, uid: AID.root, gid: AID.root, label: LABELS.data },
  });
  await fs.flush();
  const parts: Uint8Array[] = [];
  await writeSparse(disk, (b) => { parts.push(b.slice()); });
  return new Blob(parts as BlobPart[]);
}

/**
 * The app-install step: Glassworks over adb, made the Device Owner (so its updates install by themselves), with the
 * stock apps hidden and Glassworks the only home screen. Device Owner is best effort: a build that refuses it still
 * gets the app, and Done says updates need a click.
 */
export async function installInPlace(p: PanelShell, o: { apk: Uint8Array; onStep: (step: string, detail?: string) => void; sleep: (ms: number) => Promise<void>; app?: MirrorApp }): Promise<FirstStart> {
  const ours = o.app ?? GLASSWORKS;
  const r = new Steps(p, o.onStep);
  r.step(`Install ${ours.name}`);
  const out = await r.tryInstall('glassworks', o.apk, true);
  if (!/Success/.test(out)) throw new Error(`Android didn't install ${ours.name}: ${out.trim().split('\n').at(-1) ?? 'no answer'}`);
  const app = await kioskVersion(p, undefined, ours);
  if (!app) throw new Error(`Android says it installed the APK, but ${ours.name} (${ours.package}) isn't there.`);
  r.detail(`${ours.name} ${app.name ?? ''} (${app.code}) installed`);

  // Before the app's first start, which picks its Home Assistant identity and keeps it: the MAC Kiln's image install
  // writes as ro.glassworks.device.mac (the serial's), as a global setting stock's build.prop can't carry, so a Mirror
  // keeps one Home Assistant device whichever way Kiln installs it. The log names the setting only: the value is
  // device-unique.
  r.step('Keep this Mirror\'s Home Assistant device');
  const mac = await macFromSerial((await p.run('getprop ro.serialno')).out);
  if (mac) {
    const put = await p.run(`settings put global ${INSTALLER_MAC_SETTING} ${mac}`);
    r.detail(put.code === 0 ? `Home Assistant id: from the serial (${INSTALLER_MAC_SETTING}), the same on every install` : `Home Assistant id: not set (exit ${put.code}), so the app picks its own`);
  } else r.detail('Home Assistant id: no serial reported, so the app picks its own');

  // Device Owner first, before anything else names it an admin: stock lets `dpm` do it once services.jar is patched
  r.step(`Let ${ours.name} install its own updates (Device Owner)`);
  if (ours.admin) {
    const owner = await p.run(`dpm set-device-owner ${ours.admin}`);
    r.detail(/Success/.test(owner.out) ? 'Device Owner: set' : `Device Owner: not set (${owner.out.trim().split('\n')[0] || `exit ${owner.code}`})`);
  }
  await grantPermissions(r, ours);
  for (const perm of ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION']) await r.try(`pm grant ${ours.package} android.permission.${perm}`);
  // Android 6 checks WRITE_SETTINGS before an app opens a Wi-Fi hotspot (no carrier provisioning app on the Mirror;
  // research/privileged-app.md). On the Glass Panes image Glassworks is a system app and has it; on stock it's a plain
  // install, so its setup hotspot needs the app-op (verified 2026-11-25: refused without it, `appops get` rejectTime).
  const ws = await p.run(`appops set ${ours.package} WRITE_SETTINGS allow`);
  r.detail(ws.code === 0 ? 'Setup Wi-Fi: allowed' : `Setup Wi-Fi: not allowed (${ws.out.trim().split('\n')[0] || `exit ${ws.code}`})`);

  // "Allow a new computer" on the Manage page, as on the Glass Panes image: services.jar now lets an app holding
  // USB_ALLOW_PERMISSION answer "Allow USB debugging?" (stock.ts), and READ_LOGS shows it which computer asks. Both are
  // development permissions on Android 6, so adb grants them; the setting tells the app the check moved.
  r.step(`Let ${ours.name} let in the computers you allow (USB debugging)`);
  const granted: string[] = [];
  for (const perm of ['android.permission.READ_LOGS', USB_ALLOW_PERMISSION]) {
    const g = await p.run(`pm grant ${ours.package} ${perm}`);
    if (g.code === 0 && !/Exception|Error/i.test(g.out)) granted.push(perm);
    else r.detail(`${perm.split('.').pop()}: not granted (${g.out.trim().split('\n')[0] || `exit ${g.code}`})`);
  }
  if (granted.length === 2) {
    const put = await p.run(`settings put global ${USB_ALLOW_PERMISSION_SETTING} ${USB_ALLOW_PERMISSION}`);
    r.detail(put.code === 0 ? 'USB debugging: the Manage page can let in other computers' : `USB debugging: not set up (exit ${put.code})`);
  } else r.detail('USB debugging: only this browser gets in; the Manage page can\'t add computers');

  // What the Glass Panes image's settings overlay makes the defaults of a fresh userdata (overlay/res/values/defaults.xml),
  // set over adb here, since stock's own SettingsProvider keeps its own: the display never sleeps by itself (the app
  // turns the backlight off to sleep, and Android's display off would stop it). Wi-Fi is the next step. Auto-rotate goes with
  // the portrait step below. The lock screen stays as stock has it: Android 6 moves that setting into LockSettings on
  // the first boot, out of adb's reach, and the Mirror's stock shows none.
  r.step('Keep the display on, as on the Glassworks image');
  for (const [cmd, what] of [
    ['settings put system screen_off_timeout 2147483647', 'screen timeout: never'],
    ['settings put global stay_on_while_plugged_in 7', 'stay awake while plugged in: on'],
  ] as const) {
    const out = await p.run(cmd);
    r.detail(out.code === 0 ? what : `${what.split(':')[0]}: not set (${out.out.trim().split('\n')[0] || `exit ${out.code}`})`);
  }

  r.step('Keep Wi-Fi on');
  r.detail(await keepWifiOn(p));

  r.step('Hide the stock apps');
  const bloat = await hideStockBloat(p);
  for (const b of bloat) r.detail(`${b.ok ? 'hidden   ' : 'not yet  '} ${b.pkg}`);

  r.step('Turn the screen upright (portrait)');
  await setPortrait(p, o.onStep, o.sleep);
  r.step(`Start ${ours.name}`);
  await startApp(r, {}, ours);
  r.step(`Make ${ours.name} the only home screen`);
  // any other launcher the build carries goes too (the stock one is hidden above): HOME must open Glassworks with no chooser
  const { hidden, home } = await makeOnlyHome(p, o.onStep, o.sleep, ours);
  const updates = await readMirrorUpdates(p, ours);
  r.detail(describeMirrorUpdates(updates));
  return { app, hidden: [...bloat.filter((b) => b.ok).map((b) => b.pkg), ...hidden], home, updates: { state: updates.state } };
}

/** The global setting the app reads its Home Assistant identity from when build.prop can't carry it (PanelDevice). */
export const INSTALLER_MAC_SETTING = 'glassworks_device_mac';
/** The global setting that tells the app which permission `allowUsbDebugging` checks now (UsbDebugging). */
export const USB_ALLOW_PERMISSION_SETTING = 'glassworks_usb_allow_permission';

/**
 * Wi-Fi's switch (`Settings.Global.WIFI_ON`), set back on when it reads off, and the detail line that says what
 * happened. Not `svc wifi enable`: on stock the shell lacks CHANGE_WIFI_STATE, and the shell is killed for asking
 * (verified on a Mirror 2026-11-25). It may write global settings, and Android reads this one when it starts, so a
 * switch put back on takes effect at the next start. Wi-Fi reads off on stock when a fast start loses the race with the
 * Wi-Fi firmware: Android then switches Wi-Fi off for good. From then on the app switches it back on after each such
 * failure (research/stock-wifi-boot.md); a switch that is off from the start of a boot has no failure to react to,
 * which is what this write is for. Airplane mode (3) is left as it is.
 */
export async function keepWifiOn(p: PanelShell): Promise<string> {
  const read = await p.run('settings get global wifi_on');
  const v = read.out.trim();
  if (read.code !== 0 || !/^\d+$/.test(v)) return `Wi-Fi: switch not read (${v.split('\n')[0] || `exit ${read.code}`}), left as it is`;
  if (v === '1' || v === '2') return 'Wi-Fi: on';
  if (v === '3') return 'Wi-Fi: off for airplane mode, left as it is';
  const put = await p.run('settings put global wifi_on 1');
  if (put.code !== 0) return `Wi-Fi: off, and not set (${put.out.trim().split('\n')[0] || `exit ${put.code}`})`;
  // Android 6's `settings` can exit 0 after a refused write: only a read-back says it took
  const back = await p.run('settings get global wifi_on');
  if (back.code !== 0 || back.out.trim() !== '1') return `Wi-Fi: off, and not set (it reads ${back.out.trim().split('\n')[0] || `exit ${back.code}`} after the write)`;
  return 'Wi-Fi: was off, on from the next start';
}

/** The Mirror's in-place code. `words`: installer.json `device.words` (the `inPlace.*` keys, checked here). */
export function mirrorInPlace(words: Record<string, unknown>): InPlaceCode {
  return {
    assets: [STOCK_PROBE_ASSETS.apk, STOCK_PROBE_ASSETS.fiemap, STOCK_PROBE_ASSETS.cert],
    buildAssets: [BOOT_ANIMATION_ASSET],
    stage: (o) => buildStageUserdata(o),
    async readBundle(bytes, { secret, serial, kept, unsigned }) {
      // the jar against its SHA-256, the serial, and the signature with this run's secret (a kept copy's was checked when it
      // came; an unsigned one, the person went on past Kiln's warning)
      const signed = !kept && !unsigned;
      const { meta: raw, payload } = await verifyGwsbBundle(bytes, { ...(signed ? { secret } : {}), expectedSerial: serial, requireSerial: true, requireHmac: signed, requirePayloadHash: true });
      const meta = raw as BundleMeta;
      if (meta.servicesPath !== undefined && meta.servicesPath !== STOCK_SERVICES_JAR_PATH) throw new BundleRefused(`The bundle holds ${String(meta.servicesPath)}, not services.jar. ${UNCHANGED}`);
      if (meta.servicesSize !== undefined && meta.servicesSize !== payload.length) throw new BundleRefused(`The bundle's services.jar isn't the size it says. ${UNCHANGED}`);
      checkNotOdexed(meta);
      const extents = bundleExtents(meta, payload.length);
      const info = [
        `stock ${typeof meta.mirrorVersion === 'string' ? meta.mirrorVersion : 'version unknown'}`,
        `services.jar ${payload.length} bytes, SHA-256 ${String(meta.servicesSha256)}, in ${extents.length} extent${extents.length === 1 ? '' : 's'}`,
        'services.odex: none, so services.jar is what Android runs',
      ];
      // a reinstall: an earlier install's changes are already in it, so only the key it trusts changes, and the restore
      // point keeps the jar as it is now (with those changes); lululemon's own jar is in the first install's restore point
      const dex = await zipEntry(payload, 'classes.dex').catch(() => undefined);
      if (dex && patchedKeyFingerprint(dex)) {
        info.push('services.jar already has Glassworks\' changes from an earlier install: Kiln changes only which computer\'s adb key it trusts');
        info.push('the restore point keeps services.jar as it is now, with those changes; lululemon\'s own is in the first install\'s restore point');
      }
      // stock's boot animation, overwritten with Glassworks' compact animation at its own size (bootstill.ts): its blocks are part of
      // what the write may change, and the restore point keeps lululemon's
      const boot = await bundleBoot(meta);
      if ('skip' in boot) info.push(`boot animation: stays lululemon's (${boot.skip})`);
      else info.push(`bootanimation.zip ${boot.data.length} bytes, SHA-256 ${boot.sha256}, in ${boot.extents.length} extent${boot.extents.length === 1 ? '' : 's'}: Glassworks' goes in its place`);
      const all = 'skip' in boot ? extents : [...extents, ...boot.extents];
      return { digest: String(meta.servicesSha256).toLowerCase(), extents: all.map((e) => ({ physical: e.physical, length: e.length })), info };
    },
    async build(bundle, o) {
      const { meta: raw, payload: jar } = parseGwsbBundle(bundle);
      const meta = raw as BundleMeta;
      checkNotOdexed(meta);
      const extents = bundleExtents(meta, jar.length);
      const systemBytes = o.partitionBytes.system;
      if (!systemBytes) throw new Error('Kiln doesn\'t know the system partition\'s size from fastboot.');
      const patched = await patchStockServicesJar(jar, o.adbKey, { enableDeviceOwner: true });
      // the boot animation, when the bundle carries stock's and Kiln has the compact one: Glassworks' at stock's exact size
      const stock = await bundleBoot(meta);
      const compact = o.assets?.[BOOT_ANIMATION_ASSET];
      let boot: { data: Uint8Array; sha256: string; extents: FiemapExtent[]; ours: Uint8Array } | undefined;
      let bootSkipped: string | undefined;
      if ('skip' in stock) bootSkipped = stock.skip;
      else if (!compact) bootSkipped = 'Kiln doesn\'t have the compact boot animation to put in its place';
      else {
        try { boot = { ...stock, ours: bootInPlaceZip(compact, stock.data.length) }; } catch (e) { bootSkipped = (e as Error).message; }
      }
      const files = [{ extents, data: patched.patched }, ...(boot ? [{ extents: boot.extents, data: boot.ours }] : [])];
      const write = buildSurgicalSparseImageFiles(systemBytes, files);
      const restore = buildSurgicalSparseImageFiles(systemBytes, [{ extents, data: jar }, ...(boot ? [{ extents: boot.extents, data: boot.data }] : [])]);
      const userdata = await cleanUserdata(o);
      const originalSha256 = await sha256Hex(jar);
      const patchedSha256 = await sha256Hex(patched.patched);
      const bootPatchedSha256 = boot ? await sha256Hex(boot.ours) : '';
      // the same manifest as tools/mirror's surgical-adb --backup-dir, so its --undo restores from the download too
      const manifest = {
        version: 1,
        createdAt: new Date().toISOString(),
        systemBytes,
        userdataBytes: o.userdataBytes,
        deviceIdHash: await sha256Hex(new TextEncoder().encode(o.serial.trim())),
        servicesJar: {
          path: STOCK_SERVICES_JAR_PATH,
          size: jar.length,
          originalSha256,
          patchedSha256,
          fingerprint: patched.fingerprint,
          extents,
          origFile: ORIGINAL_JAR,
          restoreSparse: RESTORE_SPARSE,
        },
        // the restore sparse puts this back too: it covers every block the write changes
        ...(boot ? { bootAnimation: { path: STOCK_BOOT_ANIMATION_PATH, size: boot.data.length, originalSha256: boot.sha256, patchedSha256: bootPatchedSha256, extents: boot.extents, origFile: ORIGINAL_BOOT } }
          : { bootAnimationLeft: bootSkipped }),
      };
      // the Mirror's bootloader can't read a partition back (no fastboot upload): Kiln reads services.jar (and the boot
      // animation, when written) back over adb
      // once the Mirror has started into it, before the app's install (kiln/AGENTS.md, writes are verified)
      const readBack = [
        { path: STOCK_SERVICES_JAR_PATH, sha256: patchedSha256, size: patched.patched.length },
        ...(boot ? [{ path: STOCK_BOOT_ANIMATION_PATH, sha256: bootPatchedSha256, size: boot.ours.length }] : []),
      ];
      return { writes: { 'system-in-place': blob(write), userdata }, restore: blob(restore), manifest, files: { [ORIGINAL_JAR]: jar, ...(boot ? { [ORIGINAL_BOOT]: boot.data } : {}) }, readBack };
    },
    keptMatches: keptBundleFits,
    restored: restoredFiles,
    appInstall: (p, o) => installInPlace(p, o),
    // no beforeRestore: everything app-install changed is in userdata, which the way back writes fresh (below), so
    // there is nothing to undo over adb first, and the way back works the same when Kiln can't reach adb at all
    wayBack: {
      partitions: ['userdata'],
      async build({ partitionBytes }) {
        const bytes = partitionBytes.userdata;
        if (!bytes) throw new Error('Kiln doesn\'t know the userdata partition\'s size from fastboot.');
        return {
          writes: { userdata: await factoryUserdata(bytes) },
          what: 'userdata: fresh, as after a factory reset (Glassworks, its Device Owner record, the hidden apps and the home screen go with it)',
        };
      },
    },
    // Glassworks in place means the install patched services.jar, MirrorApService.systemReady() included (stock.ts
    // patchStockAp): stock's mirror-<serial> hotspot never opens again, so a reinstall's agent needs the person's network
    ownWifiGone: (p) => glassworksInPlace(p),
    // the agent rejoins the same Wi-Fi after the stage (stageNetwork), usually at the address the Mirror has now
    lanAddress: (p) => wifiAddress(p),
    channels: { lan: { ...STOCK_PROBE_LAN }, ble: { ...STOCK_PROBE_BLE } },
    words: inPlaceWords(words),
  };
}

const IPV4 = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;

/**
 * The running Mirror's IPv4 address on Wi-Fi (wlan0): Android 6's DHCP client property, else `ip`'s. Ethernet's
 * doesn't count, as the stage plants only Wi-Fi. Anything Kiln can't read is undefined.
 */
export async function wifiAddress(p: PanelShell): Promise<string | undefined> {
  const prop = (await p.run('getprop dhcp.wlan0.ipaddress').catch(() => undefined))?.out.trim();
  if (prop && IPV4.test(prop)) return prop;
  const r = await p.run('ip -4 -o addr show wlan0').catch(() => undefined);
  const ip = r && r.code === 0 ? /\binet (\d+\.\d+\.\d+\.\d+)\//.exec(r.out)?.[1] : undefined;
  return ip && IPV4.test(ip) ? ip : undefined;
}

/**
 * Whether Glassworks is installed on the running Mirror: its install comes after the services.jar write that turns
 * stock's hotspot off, so with it there, that hotspot is gone. Anything Kiln can't read counts as no.
 */
export async function glassworksInPlace(p: PanelShell): Promise<boolean> {
  const r = await p.run(`pm list packages ${GLASSWORKS.package}`).catch(() => undefined);
  return !!r && r.code === 0 && r.out.split(/\r?\n/).some((l) => l.trim() === `package:${GLASSWORKS.package}`);
}

/**
 * The files a restore point's image puts back, from its manifest (the one `build` makes, which tools/mirror's
 * surgical-adb --backup-dir also writes): services.jar, and the boot animation when the install changed it. Kiln checks
 * the restore image and the Mirror against them before the way back writes anything (kiln-core restore.ts).
 */
export function restoredFiles(manifest: Record<string, unknown>): RestoredFile[] {
  // the paths are the Mirror's own two files, never what the manifest says: a restore point is a file anyone can hand
  // Kiln, and its path reaches the device (adb sync and shell)
  const paths: Record<string, string> = { servicesJar: STOCK_SERVICES_JAR_PATH, bootAnimation: STOCK_BOOT_ANIMATION_PATH };
  const one = (key: string, required: boolean): RestoredFile | undefined => {
    const x = manifest[key] as Record<string, unknown> | undefined;
    if (x === undefined && !required) return undefined;
    const extents = x?.extents;
    const what = key === 'servicesJar' ? 'services.jar' : 'the boot animation';
    if (x && typeof x.path === 'string' && x.path !== paths[key]) throw new Error(`The restore point's manifest puts ${what} somewhere other than ${paths[key]}.`);
    const ok = !!x && x.path === paths[key] && typeof x.size === 'number' && typeof x.originalSha256 === 'string' && typeof x.origFile === 'string'
      && Array.isArray(extents) && extents.every((e) => typeof e?.logical === 'number' && typeof e?.physical === 'number' && typeof e?.length === 'number');
    if (!ok) throw new Error(`The restore point's manifest doesn't describe ${what}.`);
    return {
      path: x.path as string,
      file: x.origFile as string,
      size: x.size as number,
      sha256: (x.originalSha256 as string).toLowerCase(),
      extents: (extents as { logical: number; physical: number; length: number }[]).map((e) => ({ logical: e.logical, physical: e.physical, length: e.length })),
      ...(typeof x.patchedSha256 === 'string' && x.patchedSha256 ? { written: { sha256: x.patchedSha256.toLowerCase() } } : {}),
    };
  };
  return [one('servicesJar', true)!, ...[one('bootAnimation', false)].filter((x): x is RestoredFile => !!x)];
}
