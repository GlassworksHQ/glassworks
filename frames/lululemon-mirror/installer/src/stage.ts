// The stage of the Mirror's in-place install (Stock-in-place): a fresh userdata that the stock software boots with the
// setup agent in it, the MIT stock probe app (tools/mirror/stock-probe-app). Browser-safe: Kiln builds it in the
// browser, and the Node probe (tools/mirror/src/stock-probe.ts) re-exports this one copy, so the two lay the files out
// the same way, with the same owners, modes and SELinux labels. It writes userdata only: the stock system, boot,
// recovery, modem and persist stay as they are.
import { Disk, MemoryStore, writeSparse } from '@glassworks/ext4';
import { LABELS, buildUserdata } from './image';
import { GLASS_PANES_KEY_FINGERPRINT, adbFingerprint, adbKeyLines } from './services';

export const STOCK_PROBE_LABELS = {
  ...LABELS,
  /** /data/property(/.*)? (AOSP 6.0.1 file_contexts). */
  property: 'u:object_r:property_data_file:s0',
  /** /data/app(/.*)? (AOSP 6.0.1 file_contexts). */
  apk: 'u:object_r:apk_data_file:s0',
  /** /data/data/<pkg>(/.*)? as installd and seapp_contexts label an untrusted app's data (AOSP 6.0.1, no MLS categories). */
  appData: 'u:object_r:app_data_file:s0',
} as const;

/** Length in bytes of the optional per-run HMAC secret planted at the probe app's files/stage.secret. */
export const STAGE_SECRET_BYTES = 32;

export const DEFAULT_PROBE_APP_PACKAGE = 'dev.glassworks.stockprobe';
export const DEFAULT_PROBE_APP_UID = 10099;

/** Standard Android 6 wpa_supplicant header (same lines as Glass Panes /etc/wifi/wpa_supplicant.conf). */
export const DEFAULT_WIFI_TEMPLATE = [
  'update_config=1',
  'eapol_version=1',
  'ap_scan=1',
  'fast_reauth=1',
  'pmf=1',
  'p2p_no_group_iface=1',
  '',
].join('\n');

const SAFE_USB_CONFIG = /^[a-z0-9_,]+$/;
const SAFE_PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const HEX_CERT = /^[0-9a-fA-F]{64,}$/;

export interface ProbeAppPayload {
  /** Signed v1 APK for Android 6 (e.g. tools/mirror/stock-probe-app/out/stock-probe.apk). */
  apk: Uint8Array;
  /** Hex-encoded DER signing certificate for /data/system/packages.xml <cert key="...">. */
  certHex: string;
  /** Optional arm64-v8a libfiemap.so unpacked to /data/app/<pkg>-1/lib/arm64/libfiemap.so. */
  fiemapLib?: Uint8Array;
  packageName?: string;
  userId?: number;
}

export interface StockProbeUserdataOptions {
  adbKey: string;
  /** Value for /data/property/persist.sys.usb.config (default: "adb"). No trailing newline. */
  usbConfig?: string;
  /** Port for /data/property/persist.adb.tcp.port (default: 5555; omit or 0 to skip). */
  tcpPort?: number;
  /** Optional Wi-Fi credentials pre-saved as a PBKDF2 PSK in /data/misc/wifi/wpa_supplicant.conf. */
  wifi?: { ssid: string; password: string; template?: string };
  /**
   * Optional no-ADB fallback APK planted in /data/app/<pkg>-1/base.apk along with the verified
   * 1-entry /data/system/packages.xml so Android 6 boots it stopped=false and fires BOOT_COMPLETED.
   */
  probeApp?: ProbeAppPayload;
  /**
   * Optional 32-byte per-run secret planted at /data/data/<pkg>/files/stage.secret (0600, owned by the
   * probe app's uid) so the probe app appends an HMAC-SHA256 over its GWSB bundle. Requires probeApp.
   */
  stageSecret?: Uint8Array;
  time?: number;
  uuid?: Uint8Array;
}

/**
 * Minimal Android 6.0.1 /data/system/packages.xml that prevents Settings.getPackageLPw()
 * (Settings.java:641-673) from marking a freshly scanned /data/app APK as stopped=true on first boot.
 * Verified live on Android 6.0 (API 23, arm64-v8a):
 *  - Both <version> tags (internal + primary_physical) are required by PMS and MountService.
 *  - <permissions> must pre-declare RECEIVE_BOOT_COMPLETED before <package> so PMS grants it.
 */
export function buildPackagesXml(o: {
  packageName: string;
  certHex: string;
  userId: number;
  hasNativeArm64: boolean;
}): string {
  if (!SAFE_PKG.test(o.packageName)) throw new Error(`invalid packageName "${o.packageName}"`);
  const cert = o.certHex.trim().toLowerCase();
  if (!HEX_CERT.test(cert)) throw new Error('invalid certHex: expected hex-encoded DER certificate');
  if (!Number.isInteger(o.userId) || o.userId < 10000 || o.userId > 19999) {
    throw new Error(`invalid userId ${o.userId}: expected 10000..19999`);
  }
  const codePath = `/data/app/${o.packageName}-1`;
  const abiAttr = o.hasNativeArm64 ? ' primaryCpuAbi="arm64-v8a"' : '';
  return `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<packages>
  <version sdkVersion="23" databaseVersion="3" fingerprint="placeholder" />
  <version volumeUuid="primary_physical" sdkVersion="23" databaseVersion="3" fingerprint="placeholder" />
  <permissions>
    <item name="android.permission.RECEIVE_BOOT_COMPLETED" package="android" />
    <item name="android.permission.ACCESS_WIFI_STATE" package="android" />
    <item name="android.permission.CHANGE_WIFI_STATE" package="android" />
    <item name="android.permission.BLUETOOTH" package="android" />
    <item name="android.permission.BLUETOOTH_ADMIN" package="android" />
  </permissions>
  <package name="${o.packageName}" codePath="${codePath}" nativeLibraryPath="${codePath}/lib"${abiAttr} publicFlags="0" privateFlags="0" ft="19000000000" it="19000000000" ut="19000000000" version="1" userId="${o.userId}">
    <sigs count="1">
      <cert index="0" key="${cert}" />
    </sigs>
    <perms>
      <item name="android.permission.RECEIVE_BOOT_COMPLETED" granted="true" flags="0" />
      <item name="android.permission.INTERNET" granted="true" flags="0" />
      <item name="android.permission.ACCESS_WIFI_STATE" granted="true" flags="0" />
      <item name="android.permission.CHANGE_WIFI_STATE" granted="true" flags="0" />
      <item name="android.permission.BLUETOOTH" granted="true" flags="0" />
      <item name="android.permission.BLUETOOTH_ADMIN" granted="true" flags="0" />
    </perms>
  </package>
</packages>
`;
}

/** Validates every line of an ADB public key input and refuses any forbidden fingerprint. */
export function validateStockAdbKey(adbKey: string, forbiddenFingerprint = GLASS_PANES_KEY_FINGERPRINT): string {
  const keyLines = adbKeyLines(adbKey);
  if (keyLines.some((l) => adbFingerprint(l) === forbiddenFingerprint)) {
    throw new Error('refusing the published Glass Panes project key; pass your own --adb-key');
  }
  return adbFingerprint(keyLines[0]!);
}

/**
 * Builds a fresh ext4 userdata image tailored to reach a stock Mirror without touching system:
 * /misc/adb/adb_keys (via buildUserdata), /property/persist.sys.usb.config, optional
 * /property/persist.adb.tcp.port, optional /misc/wifi/wpa_supplicant.conf, and optional
 * /app/<pkg>-1/base.apk (+ lib/arm64/libfiemap.so) + /system/packages.xml for the no-ADB fallback.
 * With probeApp and stageSecret it also plants /data/<pkg>/files/stage.secret (Android's
 * /data/data/<pkg>/files/stage.secret), the probe app's per-run HMAC key.
 *
 * AOSP 6.0.1 init's load_persistent_properties() reads raw file bytes without trimming newlines,
 * so property files are written with exact UTF-8 bytes and no trailing '\n'.
 */
export async function buildStockProbeUserdata(disk: Disk, o: StockProbeUserdataOptions): Promise<{ plantedPaths: string[] }> {
  const usbConfig = o.usbConfig ?? 'adb';
  if (!SAFE_USB_CONFIG.test(usbConfig)) {
    throw new Error(`invalid usbConfig "${usbConfig}": expected comma-separated lowercase names (e.g. adb or diag,serial_smd,rmnet_bam,adb)`);
  }
  if (o.tcpPort !== undefined && o.tcpPort !== 0 && (!Number.isInteger(o.tcpPort) || o.tcpPort < 1024 || o.tcpPort > 65535)) {
    throw new Error(`invalid tcpPort ${o.tcpPort}: expected an integer from 1024 to 65535 (or 0 to disable)`);
  }
  validateStockAdbKey(o.adbKey);
  if (o.stageSecret !== undefined) {
    if (!(o.stageSecret instanceof Uint8Array) || o.stageSecret.length !== STAGE_SECRET_BYTES) {
      throw new Error(`invalid stageSecret: expected exactly ${STAGE_SECRET_BYTES} bytes`);
    }
    if (!o.probeApp) throw new Error('stageSecret needs probeApp: only the probe app reads files/stage.secret');
  }

  const time = o.time ?? Math.floor(Date.now() / 1000);
  const fs = await buildUserdata(disk, {
    adbKey: o.adbKey,
    time,
    uuid: o.uuid,
    ...(o.wifi ? { wifi: { ssid: o.wifi.ssid, password: o.wifi.password, template: o.wifi.template ?? DEFAULT_WIFI_TEMPLATE } } : {}),
  });

  const enc = new TextEncoder();
  const plantedPaths = ['/data/misc/adb/adb_keys'];
  if (o.wifi) plantedPaths.push('/data/misc/wifi/wpa_supplicant.conf');

  // init.rc: mkdir /data/property 0700 root root; property files are 0600 root:root, property_data_file.
  await fs.mkdir('/property', { mode: 0o700, uid: 0, gid: 0, label: STOCK_PROBE_LABELS.property, time });
  await fs.writeFile('/property/persist.sys.usb.config', enc.encode(usbConfig), {
    mode: 0o600, uid: 0, gid: 0, label: STOCK_PROBE_LABELS.property, time,
  });
  plantedPaths.push('/data/property/persist.sys.usb.config');

  if (o.tcpPort) {
    await fs.writeFile('/property/persist.adb.tcp.port', enc.encode(String(o.tcpPort)), {
      mode: 0o600, uid: 0, gid: 0, label: STOCK_PROBE_LABELS.property, time,
    });
    plantedPaths.push('/data/property/persist.adb.tcp.port');
  }

  if (o.probeApp) {
    const pkg = o.probeApp.packageName ?? DEFAULT_PROBE_APP_PACKAGE;
    const uid = o.probeApp.userId ?? DEFAULT_PROBE_APP_UID;
    const xml = buildPackagesXml({
      packageName: pkg,
      certHex: o.probeApp.certHex,
      userId: uid,
      hasNativeArm64: !!o.probeApp.fiemapLib,
    });
    const apkDir = `/app/${pkg}-1`;
    await fs.mkdir('/app', { mode: 0o771, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time });
    await fs.mkdir(apkDir, { mode: 0o755, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time });
    await fs.writeFile(`${apkDir}/base.apk`, o.probeApp.apk, {
      mode: 0o644, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time,
    });
    plantedPaths.push(`/data${apkDir}/base.apk`);

    if (o.probeApp.fiemapLib) {
      await fs.mkdir(`${apkDir}/lib`, { mode: 0o755, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time });
      await fs.mkdir(`${apkDir}/lib/arm64`, { mode: 0o755, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time });
      await fs.writeFile(`${apkDir}/lib/arm64/libfiemap.so`, o.probeApp.fiemapLib, {
        mode: 0o755, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time,
      });
      plantedPaths.push(`/data${apkDir}/lib/arm64/libfiemap.so`);
    }

    if (o.wifi) {
      await fs.writeFile(`${apkDir}/enable_wifi`, new Uint8Array(0), {
        mode: 0o644, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.apk, time,
      });
      plantedPaths.push(`/data${apkDir}/enable_wifi`);
    }

    await fs.mkdir('/system', { mode: 0o775, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.data, time });
    await fs.writeFile('/system/packages.xml', enc.encode(xml), {
      mode: 0o660, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.data, time,
    });
    plantedPaths.push('/data/system/packages.xml');

    if (o.stageSecret) {
      // init.rc: mkdir /data/data 0771 system system (system_data_file). installd's create_pkg_data
      // makes /data/data/<pkg> 0751 uid:uid and the app's getFilesDir() is 0771 uid:uid, both
      // app_data_file. PMS keeps an existing data dir whose owner matches packages.xml's userId.
      const appDir = `/data/${pkg}`;
      if (!(await fs.exists('/data'))) {
        await fs.mkdir('/data', { mode: 0o771, uid: 1000, gid: 1000, label: STOCK_PROBE_LABELS.data, time });
      }
      await fs.mkdir(appDir, { mode: 0o751, uid, gid: uid, label: STOCK_PROBE_LABELS.appData, time });
      await fs.mkdir(`${appDir}/files`, { mode: 0o771, uid, gid: uid, label: STOCK_PROBE_LABELS.appData, time });
      await fs.writeFile(`${appDir}/files/stage.secret`, o.stageSecret, {
        mode: 0o600, uid, gid: uid, label: STOCK_PROBE_LABELS.appData, time,
      });
      plantedPaths.push(`/data${appDir}/files/stage.secret`);
    }
  }

  await fs.flush();
  return { plantedPaths };
}

/** Kiln's staged asset names for the probe app (kiln/scripts/stage-assets.mjs): its APK, its FIEMAP helper, its certificate. */
export const STOCK_PROBE_ASSETS = { apk: 'mirror-stock-probe-apk', fiemap: 'mirror-stock-probe-fiemap', cert: 'mirror-stock-probe-cert' } as const;

/**
 * Kiln's `stage` step on the Mirror: the probe app's userdata (buildStockProbeUserdata) as an Android sparse image sized
 * for the partition, with this browser's adb key, the per-run `secret` its bundle is signed with, and the network when
 * given (then the probe switches Wi-Fi on by itself). `assets`: the staged probe files, by STOCK_PROBE_ASSETS name.
 */
export async function buildStageUserdata(o: {
  adbKey: string;
  userdataBytes: number;
  secret: Uint8Array;
  wifi?: { ssid: string; password: string };
  assets: Record<string, Uint8Array>;
  time?: number;
}): Promise<Blob> {
  const apk = o.assets[STOCK_PROBE_ASSETS.apk];
  const cert = o.assets[STOCK_PROBE_ASSETS.cert];
  if (!apk?.length || !cert?.length) throw new Error('Kiln is missing the setup agent\'s files. Reload the page and try again.');
  const fiemap = o.assets[STOCK_PROBE_ASSETS.fiemap];
  const disk = new Disk(new MemoryStore(), 4096, Math.floor(o.userdataBytes / 4096), { zeroed: true });
  await buildStockProbeUserdata(disk, {
    adbKey: o.adbKey,
    ...(o.wifi ? { wifi: o.wifi } : {}),
    probeApp: { apk, certHex: new TextDecoder().decode(cert).trim(), ...(fiemap?.length ? { fiemapLib: fiemap } : {}) },
    stageSecret: o.secret,
    ...(o.time !== undefined ? { time: o.time } : {}),
  });
  const parts: Uint8Array[] = [];
  await writeSparse(disk, (b) => { parts.push(b.slice()); });
  return new Blob(parts as BlobPart[]);
}
