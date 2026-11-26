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
import { Steps, grantPermissions, kioskVersion, startApp } from '@glassworks/app-adb';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { inPlaceWords, parseGwsbBundle, sha256Hex, verifyGwsbBundle, type FirstStart, type InPlaceCode } from '@glassworks/kiln-core';
import { GLASSWORKS, type MirrorApp } from './app';
import { makeOnlyHome, setPortrait } from './flash';
import { AID, LABELS, buildUserdata } from './image';
import { DEFAULT_WIFI_TEMPLATE, STOCK_PROBE_ASSETS, buildStageUserdata } from './stage';
import {
  SPARSE_BLOCK_SIZE, STOCK_SERVICES_JAR_PATH, buildSurgicalSparseImage, hideStockBloat, patchStockServicesJar,
  type FiemapExtent,
} from './stock';
import { describeMirrorUpdates, readMirrorUpdates } from './updates';

/** The probe app's HTTP server (ProbeServerService.PORT) and bundle path. */
export const STOCK_PROBE_LAN = { port: 18765, path: '/stock-bundle.bin', pagePath: '/' } as const;

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
 * reports today. When the agent's description carries no whole file map (it trims it to fit Bluetooth's 512 bytes),
 * Kiln can't tell, so it takes the bundle again.
 */
export function keptBundleFits(bundle: Uint8Array, agent: Record<string, unknown>): boolean {
  const now = layoutOf(agent.fiemap);
  if (!now) return false;
  try {
    return layoutOf((parseGwsbBundle(bundle).meta as BundleMeta).fiemap) === now;
  } catch {
    return false;
  }
}

/** Builds a fresh userdata as a sparse image: this browser's adb key, and the network when given. */
async function cleanUserdata(o: { adbKey: string; userdataBytes: number; wifi?: { ssid: string; password: string } }): Promise<Blob> {
  const disk = new Disk(new MemoryStore(), 4096, Math.floor(o.userdataBytes / 4096), { zeroed: true });
  await buildUserdata(disk, { adbKey: o.adbKey, ...(o.wifi ? { wifi: { ...o.wifi, template: DEFAULT_WIFI_TEMPLATE } } : {}) });
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
  return 'Wi-Fi: was off, on from the next start';
}

/** The Mirror's in-place code. `words`: installer.json `device.words` (the `inPlace.*` keys, checked here). */
export function mirrorInPlace(words: Record<string, unknown>): InPlaceCode {
  return {
    assets: [STOCK_PROBE_ASSETS.apk, STOCK_PROBE_ASSETS.fiemap, STOCK_PROBE_ASSETS.cert],
    stage: (o) => buildStageUserdata(o),
    async readBundle(bytes, { secret, serial, kept }) {
      // the jar against its SHA-256, the serial, and the signature with this run's secret (a kept copy's was checked when it came)
      const { meta: raw, payload } = await verifyGwsbBundle(bytes, { ...(kept ? {} : { secret }), expectedSerial: serial, requireSerial: true, requireHmac: !kept, requirePayloadHash: true });
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
      return { digest: String(meta.servicesSha256).toLowerCase(), extents: extents.map((e) => ({ physical: e.physical, length: e.length })), info };
    },
    async build(bundle, o) {
      const { meta: raw, payload: jar } = parseGwsbBundle(bundle);
      const meta = raw as BundleMeta;
      checkNotOdexed(meta);
      const extents = bundleExtents(meta, jar.length);
      const systemBytes = o.partitionBytes.system;
      if (!systemBytes) throw new Error('Kiln doesn\'t know the system partition\'s size from fastboot.');
      const patched = await patchStockServicesJar(jar, o.adbKey, { enableDeviceOwner: true });
      const write = buildSurgicalSparseImage(systemBytes, extents, patched.patched);
      const restore = buildSurgicalSparseImage(systemBytes, extents, jar);
      const userdata = await cleanUserdata(o);
      const originalSha256 = await sha256Hex(jar);
      const patchedSha256 = await sha256Hex(patched.patched);
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
      };
      // the Mirror's bootloader can't read a partition back (no fastboot upload): Kiln reads services.jar back over adb
      // once the Mirror has started into it, before the app's install (kiln/AGENTS.md, writes are verified)
      const readBack = [{ path: STOCK_SERVICES_JAR_PATH, sha256: patchedSha256, size: patched.patched.length }];
      return { writes: { 'system-in-place': blob(write), userdata }, restore: blob(restore), manifest, files: { [ORIGINAL_JAR]: jar }, readBack };
    },
    keptMatches: keptBundleFits,
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
    channels: { lan: { ...STOCK_PROBE_LAN }, ble: { ...STOCK_PROBE_BLE } },
    words: inPlaceWords(words),
  };
}
