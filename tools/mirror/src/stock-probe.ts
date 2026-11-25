// Guided, step-by-step probe for a stock lululemon Studio Mirror (MSM8916).
//
// NEVER touches system, boot, recovery, modem, persist, or aboot.
// Steps 1-3 are read-only on the device (fastboot getvar + local sparse userdata build).
// Step 4 WRITES ONLY userdata (wiping stock app data, leaving stock system untouched).
// Step 5 waits for stock ADB (USB/TCP); if stock blocks ADB (1.2x+ unauthorized / not-seen),
// the flow seamlessly leads into the no-ADB fallback: planting a tiny read-only HTTP probe APK
// in /data/app/ + a verified 1-entry /data/system/packages.xml (still userdata-only!), rebooting,
// and running Step 6 extraction (including optional FS_IOC_FIEMAP physical block offsets) over HTTP.
//
// Redacted progress is written to <out>/report.md after EVERY step so progress can be reported at
// any checkpoint; raw device files stay locally in <out>/raw/ (outside git, never committed).
import { createHash } from 'node:crypto';
import { chmodSync, closeSync, fchmodSync, mkdirSync, mkdtempSync, openSync, writeFileSync, writeSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { Disk, MemoryStore, writeSparse } from '@glassworks/ext4';
import {
  GLASS_PANES_KEY_FINGERPRINT, LABELS, SYSTEM_BYTES, adbFingerprint, adbKeyLines, buildUserdata,
} from '@glassworks/lululemon-mirror-installer';

export const STOCK_PROBE_LABELS = {
  ...LABELS,
  /** /data/property(/.*)? (AOSP 6.0.1 file_contexts). */
  property: 'u:object_r:property_data_file:s0',
  /** /data/app(/.*)? (AOSP 6.0.1 file_contexts). */
  apk: 'u:object_r:apk_data_file:s0',
} as const;

export const DEFAULT_PROBE_APP_PACKAGE = 'dev.glassworks.stockprobe';
export const DEFAULT_PROBE_APP_UID = 10099;
export const DEFAULT_PROBE_HTTP_PORT = 18765;

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

/** Read-only fastboot variables queried one at a time (this LK answers empty for `getvar all`; serialno is never logged). */
export const FASTBOOT_VARS = [
  'product',
  'variant',
  'secure',
  'version-bootloader',
  'max-download-size',
  'partition-type:system',
  'partition-size:system',
  'partition-type:userdata',
  'partition-size:userdata',
  'partition-size:boot',
  'partition-size:modem',
  'partition-size:persist',
] as const;

/** Safe, non-unique build/hardware properties allowed into the shareable report.md. */
export const STOCK_PROPS_ALLOWLIST = [
  'ro.build.fingerprint',
  'ro.bootimage.build.fingerprint',
  'ro.build.version.release',
  'ro.build.version.sdk',
  'ro.build.type',
  'ro.build.tags',
  'ro.board.platform',
  'ro.product.model',
  'ro.product.device',
  'ro.product.manufacturer',
  'ro.hardware',
  'ro.crypto.state',
  'ro.adb.secure',
  'ro.secure',
  'ro.debuggable',
  'ro.sf.hwrotation',
  'persist.sys.usb.config',
  'persist.adb.tcp.port',
] as const;

/** Ramdisk policy & config files pulled locally in Step 6 (only readable/denied status goes in report.md). */
export const RAMDISK_FILES = [
  '/sepolicy',
  '/file_contexts',
  '/seapp_contexts',
  '/property_contexts',
  '/service_contexts',
  '/default.prop',
] as const;

/** Key stock /system paths audited by presence, size, and SHA-256 (bytes stay local in raw/). */
export const KEY_SYSTEM_FILES = [
  '/system/build.prop',
  '/system/etc/wifi/wpa_supplicant.conf',
  '/system/etc/init.qcom.post_boot.sh',
  '/system/lib/modules/adsprpc.ko',
  '/system/lib/modules/pronto/pronto_wlan.ko',
  '/system/xbin/su',
  '/system/framework/services.jar',
] as const;

/** Paths queried with FS_IOC_FIEMAP when native libfiemap.so is available. */
export const FIEMAP_TARGETS = [
  '/system/etc/init.qcom.post_boot.sh',
  '/system/build.prop',
] as const;

const SAFE_PATH = /^\/[A-Za-z0-9._+/-]+$/;
const EXTRA_ETC_CONFIG_PATH = /^\/system\/etc\/(?:mixer_paths[A-Za-z0-9._-]*\.xml|thermal-engine[A-Za-z0-9._-]*\.conf)$/;
const SAFE_USB_CONFIG = /^[a-z0-9_,]+$/;
const SAFE_PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const HEX_CERT = /^[0-9a-fA-F]{64,}$/;

function isSafeDevicePath(p: string): boolean {
  return SAFE_PATH.test(p) && !p.split('/').some((seg) => seg === '.' || seg === '..');
}

export const STOCK_USERDATA_WARNING = [
  'This flashes ONLY the userdata partition; stock system, boot, recovery, modem, and persist are NOT touched.',
  'Wiping userdata erases stock account/onboarding data, which is likely a one-way door off lululemon\'s stock app.',
  'Fastboot stays open, so you can still flash Glass Panes or Glassworks later.',
];

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
  }

  await fs.flush();
  return { plantedPaths };
}

export interface VendorEntry {
  part: string;
  path: string;
  bytes: number;
  sha256: string;
  label: string;
  shell: string;
}

/** Parses frames/lululemon-mirror/research/option-c-vendor-files.tsv, validating every path. */
export function parseVendorTsv(text: string): VendorEntry[] {
  const out: VendorEntry[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('part\t')) continue;
    const cols = line.split('\t');
    if (cols.length < 6) continue;
    const [part, path, bytesStr, sha256, label, shell] = cols;
    if (!part || !path || !isSafeDevicePath(path)) throw new Error(`unsafe vendor path in TSV: ${path}`);
    out.push({ part, path, bytes: Number.parseInt(bytesStr ?? '0', 10) || 0, sha256: sha256 ?? '', label: label ?? '', shell: shell ?? '' });
  }
  return out;
}

export type AdbState = 'device' | 'unauthorized' | 'offline' | 'not-seen';

export interface StockFastboot {
  /** Internal device identifier for cross-session matching (never logged or written to report.md). */
  readonly serial?: string;
  /** `fastboot oem device-info` raw lines (serials already redacted). */
  oemDeviceInfo(): Promise<string[]>;
  /** `fastboot getvar <name>` value, or undefined if unsupported/failed. */
  getvar(name: string): Promise<string | undefined>;
  /** `fastboot flash userdata <file>` — only userdata is ever allowed here. */
  flashUserdata(sparsePath: string): Promise<void>;
  /** `fastboot reboot`. */
  reboot(): Promise<void>;
}

export interface StockAdbSession {
  /** Runs a read-only shell command and returns stdout + exit code. */
  shell(cmd: string): Promise<{ out: string; code: number }>;
  /** Reads raw file bytes via `adb exec-out cat <path>` (null if unreadable/missing). */
  readFile(path: string): Promise<Uint8Array | null>;
}

export interface FiemapExtent {
  logical: number;
  physical: number;
  block: number;
  length: number;
  blocks: number;
  flags: number;
}

export interface FiemapResult {
  path: string;
  size?: number;
  ino?: number;
  extents?: FiemapExtent[];
  error?: string;
}

export interface StockHttpSession {
  status(timeoutMs?: number): Promise<{ ok: boolean; uid: number; pid: number; sdk: number; fiemap: boolean; bootCompleted: string } | null>;
  props(): Promise<string>;
  dtPanel(): Promise<string>;
  list(path: string): Promise<string[]>;
  /** Like `list`, but distinguishes a denied listing (HTTP 403) from a missing/absent directory
   * (HTTP 404) instead of collapsing both to `[]`. Optional: a denied listing must stop a safety
   * gate (e.g. the services.odex check) before any write, which treating it as "empty" cannot do. */
  listRaw?(path: string): Promise<{ status: number; entries: string[] }>;
  readFile(path: string): Promise<{ status: 'ok'; data: Uint8Array } | { status: 'missing' | 'denied' }>;
  fiemap(path: string): Promise<FiemapResult>;
}

export interface StockProbeDeps {
  log(line: string): void;
  confirm(question: string): Promise<boolean>;
  /** Optional free-text prompt (e.g. asking for the Mirror's LAN IP during no-ADB HTTP fallback). */
  promptText?(question: string): Promise<string>;
  /** Checks current USB adb state (and optionally tries TCP connect if tcpHost is provided). */
  checkAdb(tcpTarget?: string, opts?: { timeoutMs?: number; preflight?: boolean }): Promise<{ usb: AdbState; tcp?: AdbState; session?: StockAdbSession }>;
  /** Waits for / opens the Mirror in fastboot. */
  fastboot(): Promise<StockFastboot>;
  /** Loads or builds the no-ADB fallback probe APK (+ cert.hex + optional libfiemap.so). */
  loadProbeApp?(): Promise<ProbeAppPayload | undefined>;
  /** Connects to the HTTP probe server running on the Mirror (default port 18765). */
  connectHttp?(host: string, port: number): StockHttpSession;
  /** Optional vendor TSV rows (option-c-vendor-files.tsv) for Step 6 comparison. */
  vendorEntries?: VendorEntry[];
  sleep?: (ms: number) => Promise<void>;
  now?: () => Date;
  /** Monotonic millisecond clock used for elapsed wait-deadline tracking (default: Date.now). */
  monotonicMs?: () => number;
}

export type StepStatus = 'pending' | 'done' | 'skipped' | 'stopped' | 'blocked' | 'failed';

export interface StepRecord {
  number: number;
  title: string;
  status: StepStatus;
  note?: string;
}

export interface FileAudit {
  path: string;
  status: 'readable' | 'denied' | 'missing';
  bytes?: number;
  sha256?: string;
}

export interface VendorPartSummary {
  part: string;
  total: number;
  match: number;
  differs: number;
  missing: number;
  denied: number;
}

export interface ProbeReport {
  startedAt: string;
  updatedAt: string;
  mode: 'full' | 'read-only' | 'build-only' | 'extract-only' | 'http-only';
  dryRun: boolean;
  steps: StepRecord[];
  preflight?: {
    adbKeyFingerprint: string;
    usbConfig: string;
    tcpPort?: number;
    wifiConfigured: boolean;
    probeAppPrePlanted: boolean;
    preFlashUsbAdb: AdbState;
  };
  fastboot?: {
    oemDeviceInfo: string[];
    vars: Record<string, string>;
    productOk: boolean;
    userdataBytes?: number;
    userdataOk: boolean;
    systemBytes?: number;
    systemOk: boolean;
  };
  sparseBuild?: {
    userdataBytes: number;
    blocks: number;
    sparseBytes: number;
    fill: boolean;
    plantedPaths: string[];
  };
  flash?: {
    userdataFlashed: boolean;
    systemTouched: false;
    rebooted?: boolean;
    rebootError?: string;
  };
  adbWait?: {
    usb: AdbState;
    tcp?: AdbState;
    outcome: 'authorized' | 'unauthorized' | 'not-seen';
    nextHint: string;
  };
  httpFallback?: {
    triggered: boolean;
    reFlashedUserdataWithApp: boolean;
    port: number;
    connected: boolean;
    uid?: number;
    sdk?: number;
    fiemapAvailable?: boolean;
    note?: string;
  };
  extraction?: {
    transport: 'adb' | 'http';
    props: Record<string, string>;
    selinuxEnforce?: string;
    shellId?: string;
    procVersion?: string;
    kernelConfigFlags: string[];
    procModules?: string;
    meminfoHead?: string;
    partitions?: string;
    initServices: Record<string, string>;
    ramdiskFiles: FileAudit[];
    rotation?: string;
    backlightEntries: string[];
    dtPanelLines: string[];
    dmesgHighlights: string[];
    keySystemFiles: FileAudit[];
    extraConfigFiles: FileAudit[];
    fiemapResults: FiemapResult[];
    vendorSummary: VendorPartSummary[];
    vendorCameraIncluded: boolean;
    pulledVendorBlobs: number;
  };
}

export interface StockProbeOptions {
  outDir: string;
  /** ADB public key to plant in userdata; optional for --extract-only and --http-only. */
  adbKey?: string;
  usbConfig?: string;
  tcpPort?: number;
  tcpHost?: string;
  /** Optional LAN IP of the Mirror for the no-ADB HTTP probe server (port 18765). */
  probeHost?: string;
  probeHttpPort?: number;
  /** Plant /data/app + 1-entry packages.xml on the first userdata flash (single flash for 1.2x+). */
  withProbeApk?: boolean;
  wifi?: { ssid: string; password: string };
  fill?: boolean;
  readOnly?: boolean;
  buildOnly?: boolean;
  extractOnly?: boolean;
  httpOnly?: boolean;
  includeCamera?: boolean;
  pullSystemBlobs?: boolean;
  waitSeconds?: number;
  /** Override userdata size in bytes (used by --dry-run tests so sparse images stay small). */
  userdataBytesOverride?: number;
  dryRun?: boolean;
}

function parseHexBytes(v: string | undefined): number | undefined {
  if (!v) return undefined;
  const s = v.trim();
  if (!/^0x[0-9a-f]+$/i.test(s)) return undefined;
  const n = Number.parseInt(s, 16);
  return Number.isSafeInteger(n) && n > 0 ? n : undefined;
}

function sha256Hex(data: Uint8Array): string {
  return createHash('sha256').update(data).digest('hex');
}

function redactPossibleIds(text: string): string {
  return text
    .replace(/androidboot\.serialno=[^\s]+/gi, 'androidboot.serialno=<redacted>')
    .replace(/\b([0-9a-f]{2}:){5}[0-9a-f]{2}\b/gi, '<mac-redacted>');
}

function ensurePrivateDir(dir: string): void {
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  try { chmodSync(dir, 0o700); } catch { /* ignore on platforms without POSIX chmod */ }
}

function containedRawPath(rawBase: string, ...segments: string[]): string {
  const base = resolve(rawBase);
  const dest = resolve(base, ...segments);
  const rel = relative(base, dest);
  if (!rel || rel === '.' || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) {
    throw new Error(`refusing raw artifact path outside ${base}`);
  }
  return dest;
}

function writePrivateFile(path: string, data: string | Uint8Array, encoding?: BufferEncoding): void {
  ensurePrivateDir(dirname(path));
  if (typeof data === 'string') writeFileSync(path, data, { encoding: encoding ?? 'utf8', mode: 0o600 });
  else writeFileSync(path, data, { mode: 0o600 });
  try { chmodSync(path, 0o600); } catch { /* ignore on platforms without POSIX chmod */ }
}

function fileSink(path: string) {
  ensurePrivateDir(dirname(path));
  const fd = openSync(path, 'w', 0o600);
  try { fchmodSync(fd, 0o600); } catch { /* ignore on platforms without POSIX fchmod */ }
  let pos = 0;
  return {
    write(b: Uint8Array) { writeSync(fd, b, 0, b.length, pos); pos += b.length; },
    bytes: () => pos,
    close: () => closeSync(fd),
  };
}

/** Markdown-safe inline code span (neutralizes newlines, backticks, and optionally table pipes). */
function mdCode(value: string, forTable = false): string {
  let clean = value.replace(/[\r\n]+/g, ' ').replace(/\0/g, '').trim();
  if (forTable) clean = clean.replace(/\|/g, '\\|');
  let maxTicks = 0;
  for (const m of clean.matchAll(/`+/g)) {
    if (m[0].length > maxTicks) maxTicks = m[0].length;
  }
  const fence = '`'.repeat(maxTicks + 1);
  const pad = clean.startsWith('`') || clean.endsWith('`') || (clean.length > 0 && clean.trim() === '') ? ' ' : '';
  return `${fence}${pad}${clean}${pad}${fence}`;
}

/** Markdown-safe fenced code block whose delimiter cannot be closed by remote content. */
function pushMdFence(lines: string[], bodyLines: string[]): void {
  const joined = bodyLines.join('\n');
  let maxTicks = 2;
  for (const m of joined.matchAll(/`+/g)) {
    if (m[0].length > maxTicks) maxTicks = m[0].length;
  }
  const fence = '`'.repeat(maxTicks + 1);
  lines.push(fence);
  for (const l of bodyLines) lines.push(l);
  lines.push(fence);
}

function parseGetprop(rawGetprop: string): { props: Record<string, string>; initServices: Record<string, string> } {
  const allProps = new Map<string, string>();
  for (const line of rawGetprop.split(/\r?\n/)) {
    const m = /^\[([^\]]+)\]: \[(.*)\]$/.exec(line.trim());
    if (m) allProps.set(m[1]!, m[2]!);
  }
  const props: Record<string, string> = {};
  for (const k of STOCK_PROPS_ALLOWLIST) {
    const v = allProps.get(k);
    if (v !== undefined && v !== '') props[k] = v;
  }
  const initServices: Record<string, string> = {};
  for (const [k, v] of [...allProps.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    if (k.startsWith('init.svc.')) initServices[k.slice('init.svc.'.length)] = v;
  }
  return { props, initServices };
}

function extractKernelConfigFlags(cfgGz: Uint8Array | null, rawDir: string): string[] {
  const flags: string[] = [];
  if (!cfgGz || cfgGz.length === 0) return flags;
  writePrivateFile(containedRawPath(rawDir, 'config.gz'), cfgGz);
  try {
    const cfgText = gunzipSync(cfgGz).toString('utf8');
    writePrivateFile(containedRawPath(rawDir, 'kernel.config'), cfgText, 'utf8');
    for (const l of cfgText.split(/\r?\n/)) {
      if (/^(# )?CONFIG_(LOCALVERSION|IKCONFIG_PROC|MODULES|MODULE_SIG|MODVERSIONS|KALLSYMS_ALL|PID_NS|KEXEC|ARM_PSCI|MSM_SCM)(=| is not set)/.test(l)) {
        flags.push(l);
      }
    }
  } catch {
    flags.push('(config.gz pulled but gunzip failed)');
  }
  return flags;
}

/** Renders the redacted, copy-pasteable Markdown progress report. */
export function renderReportMarkdown(r: ProbeReport): string {
  const lines: string[] = [];
  lines.push('# lululemon Studio Mirror — Stock Firmware Probe Report');
  lines.push('');
  lines.push('> Redacted progress report — safe to paste into a GitHub issue, PR, or chat.');
  lines.push('> Raw device dumps live locally in `raw/` and must never be committed.');
  lines.push('');
  lines.push(`- **Mode:** \`${r.mode}\`${r.dryRun ? ' *(dry run — fake device)*' : ''}`);
  lines.push(`- **Started:** ${r.startedAt}`);
  lines.push(`- **Updated:** ${r.updatedAt}`);
  lines.push('');
  lines.push('## Progress');
  lines.push('');
  for (const s of r.steps) {
    const box = s.status === 'done' ? 'x' : ' ';
    const note = s.note ? ` — ${mdCode(s.note)}` : '';
    lines.push(`- [${box}] **Step ${s.number}/${r.steps.length}: ${s.title}** (\`${s.status}\`)${note}`);
  }

  if (r.preflight) {
    lines.push('');
    lines.push('## Step 1 — Preflight');
    lines.push('');
    lines.push('| Field | Value |');
    lines.push('|---|---|');
    lines.push(`| ADB key fingerprint | ${mdCode(r.preflight.adbKeyFingerprint, true)} |`);
    lines.push(`| Planned \`persist.sys.usb.config\` | ${mdCode(r.preflight.usbConfig, true)} |`);
    lines.push(`| Planned \`persist.adb.tcp.port\` | ${r.preflight.tcpPort ? `\`${r.preflight.tcpPort}\`` : 'disabled'} |`);
    lines.push(`| Pre-saved Wi-Fi in userdata | ${r.preflight.wifiConfigured ? 'yes (PBKDF2 PSK only)' : 'no'} |`);
    lines.push(`| No-ADB HTTP probe APK pre-planted | ${r.preflight.probeAppPrePlanted ? 'yes' : 'no (auto-fallback if ADB blocked)'} |`);
    lines.push(`| Pre-flash USB adb state | \`${r.preflight.preFlashUsbAdb}\` |`);
  }

  if (r.fastboot) {
    lines.push('');
    lines.push('## Step 2 — Fastboot (read-only)');
    lines.push('');
    lines.push(`- **Board gate (\`product == MSM8916\`):** ${r.fastboot.productOk ? 'PASS' : 'FAIL — STOP'}`);
    lines.push(`- **Userdata gate (>= 1 GiB, 4 KiB blocks):** ${r.fastboot.userdataOk ? `PASS (${r.fastboot.userdataBytes} bytes)` : 'FAIL — STOP'}`);
    lines.push(`- **System size vs Glass Panes (${SYSTEM_BYTES} bytes):** ${r.fastboot.systemBytes === undefined ? 'not reported' : `${r.fastboot.systemBytes} bytes (${r.fastboot.systemOk ? 'fits' : 'smaller than Glass Panes'})`}`);
    lines.push('');
    if (r.fastboot.oemDeviceInfo.length) {
      lines.push('`fastboot oem device-info`:');
      pushMdFence(lines, r.fastboot.oemDeviceInfo);
      lines.push('');
    }
    lines.push('| Variable | Value |');
    lines.push('|---|---|');
    for (const [k, v] of Object.entries(r.fastboot.vars)) {
      lines.push(`| ${mdCode(k, true)} | ${mdCode(v, true)} |`);
    }
  }

  if (r.sparseBuild) {
    lines.push('');
    lines.push('## Step 3 — Sparse `userdata` image');
    lines.push('');
    lines.push(`- **Partition size:** ${r.sparseBuild.userdataBytes} bytes (${r.sparseBuild.blocks} × 4 KiB blocks)`);
    lines.push(`- **Sparse image size:** ${r.sparseBuild.sparseBytes} bytes (FILL chunks: ${r.sparseBuild.fill ? 'on' : 'off'})`);
    lines.push(`- **Planted paths:** ${r.sparseBuild.plantedPaths.map((p) => mdCode(p)).join(', ')}`);
  }

  if (r.flash) {
    lines.push('');
    lines.push('## Step 4 — Flash `userdata` ONLY');
    lines.push('');
    lines.push(`- **\`userdata\` flashed:** ${r.flash.userdataFlashed ? 'yes' : 'no'}`);
    if (r.flash.rebooted !== undefined) {
      lines.push(`- **Reboot sent:** ${r.flash.rebooted ? 'yes' : 'no (retry fastboot reboot without reflashing)'}`);
    }
    if (r.flash.rebootError) {
      lines.push(`- **Reboot error:** ${mdCode(r.flash.rebootError)}`);
    }
    lines.push('- **`system` / `boot` / `recovery` / `modem` / `persist` touched:** **NO**');
  }

  if (r.adbWait) {
    lines.push('');
    lines.push('## Step 5 — Stock Android ADB Check');
    lines.push('');
    lines.push(`- **USB adb state:** \`${r.adbWait.usb}\``);
    if (r.adbWait.tcp !== undefined) lines.push(`- **TCP adb state:** \`${r.adbWait.tcp}\``);
    lines.push(`- **Outcome:** \`${r.adbWait.outcome}\``);
    lines.push(`- **Next:** ${r.adbWait.nextHint}`);
  }

  if (r.httpFallback) {
    lines.push('');
    lines.push('## Step 5b — No-ADB HTTP Probe App Fallback (`userdata`-only)');
    lines.push('');
    lines.push(`- **Triggered:** ${r.httpFallback.triggered ? 'yes' : 'no'}`);
    lines.push(`- **Re-flashed \`userdata\` with probe APK + \`packages.xml\`:** ${r.httpFallback.reFlashedUserdataWithApp ? 'yes (stock `system` untouched)' : 'no'}`);
    lines.push(`- **HTTP probe connected (port ${r.httpFallback.port}):** ${r.httpFallback.connected ? 'yes' : 'no'}`);
    if (r.httpFallback.uid !== undefined) lines.push(`- **Probe app runtime:** \`uid=${r.httpFallback.uid}\`, SDK \`${r.httpFallback.sdk}\`, \`FS_IOC_FIEMAP\`: ${r.httpFallback.fiemapAvailable ? 'available' : 'not bundled'}`);
    if (r.httpFallback.note) lines.push(`- **Note:** ${mdCode(r.httpFallback.note)}`);
  }

  if (r.extraction) {
    const ex = r.extraction;
    lines.push('');
    lines.push(`## Step 6 — Stock System Extraction (via \`${ex.transport}\`, redacted)`);
    lines.push('');
    lines.push('### Identity & Security');
    lines.push('');
    lines.push('| Property / Check | Value |');
    lines.push('|---|---|');
    for (const [k, v] of Object.entries(ex.props)) {
      lines.push(`| ${mdCode(k, true)} | ${mdCode(v, true)} |`);
    }
    if (ex.selinuxEnforce) lines.push(`| \`getenforce\` | ${mdCode(ex.selinuxEnforce, true)} |`);
    if (ex.shellId) lines.push(`| \`id\` | ${mdCode(ex.shellId, true)} |`);
    if (ex.procVersion) lines.push(`| \`/proc/version\` | ${mdCode(ex.procVersion, true)} |`);
    if (ex.rotation) lines.push(`| Display \`mRotation\` | ${mdCode(ex.rotation, true)} |`);

    if (ex.kernelConfigFlags.length) {
      lines.push('');
      lines.push('### Kernel Config Flags (`/proc/config.gz`)');
      lines.push('');
      pushMdFence(lines, ex.kernelConfigFlags);
    }

    if (Object.keys(ex.initServices).length) {
      lines.push('');
      lines.push(`### Init Services (\`init.svc.*\`, ${Object.keys(ex.initServices).length} entries)`);
      lines.push('');
      lines.push('| Service | State |');
      lines.push('|---|---|');
      for (const [k, v] of Object.entries(ex.initServices)) {
        lines.push(`| ${mdCode(k, true)} | ${mdCode(v, true)} |`);
      }
    }

    if (ex.ramdiskFiles.length) {
      lines.push('');
      lines.push('### Ramdisk / SELinux Files');
      lines.push('');
      lines.push('| Path | Status | Bytes | SHA-256 (prefix) |');
      lines.push('|---|---|---:|---|');
      for (const f of ex.ramdiskFiles) {
        lines.push(`| ${mdCode(f.path, true)} | \`${f.status}\` | ${f.bytes ?? '—'} | ${f.sha256 ? `\`${f.sha256.slice(0, 12)}\`` : '—'} |`);
      }
    }

    if (ex.dtPanelLines.length || ex.backlightEntries.length || ex.procModules) {
      lines.push('');
      lines.push('### Hardware, Display & Modules');
      lines.push('');
      if (ex.procModules) {
        lines.push('`/proc/modules`:');
        pushMdFence(lines, [ex.procModules]);
      }
      if (ex.backlightEntries.length) {
        lines.push('Backlight / LEDs:');
        pushMdFence(lines, ex.backlightEntries);
      }
      if (ex.dtPanelLines.length) {
        lines.push('Device-tree panel/bridge nodes (excluding `/chosen`):');
        pushMdFence(lines, ex.dtPanelLines);
      }
    }

    if (ex.dmesgHighlights.length) {
      lines.push('');
      lines.push('### `dmesg` Highlights (`adsprpc` / `insmod` / `adb_keys_file`)');
      lines.push('');
      pushMdFence(lines, ex.dmesgHighlights);
    }

    const auditedFiles = [...ex.keySystemFiles, ...ex.extraConfigFiles];
    if (auditedFiles.length) {
      lines.push('');
      lines.push('### Key Stock `/system` Files (hashes only)');
      lines.push('');
      lines.push('| Path | Status | Bytes | SHA-256 |');
      lines.push('|---|---|---:|---|');
      for (const f of auditedFiles) {
        lines.push(`| ${mdCode(f.path, true)} | \`${f.status}\` | ${f.bytes ?? '—'} | ${f.sha256 ? `\`${f.sha256}\`` : '—'} |`);
      }
    }

    if (ex.fiemapResults.length) {
      lines.push('');
      lines.push('### Physical Block Extents (`FS_IOC_FIEMAP`, read-only)');
      lines.push('');
      lines.push('| Path | Size | Extents (physical 4 KiB block + count) |');
      lines.push('|---|---:|---|');
      for (const fm of ex.fiemapResults) {
        const desc = fm.error
          ? `error: ${mdCode(fm.error, true)}`
          : Array.isArray(fm.extents) && fm.extents.length > 0
            ? fm.extents.map((e) => `block \`${e.block}\` × \`${e.blocks}\` (logical ${e.logical})`).join('; ')
            : 'none';
        lines.push(`| ${mdCode(fm.path, true)} | ${fm.size ?? '—'} | ${desc} |`);
      }
    }

    if (ex.vendorSummary.length) {
      lines.push('');
      lines.push(`### Option C Vendor File Audit (${ex.vendorCameraIncluded ? 'all parts' : 'non-camera parts; pass --include-camera for camera'})`);
      lines.push('');
      lines.push('| Part | Total | Match Glass Panes | Differs (stock-specific) | Missing | Denied (SELinux/DAC) |');
      lines.push('|---|---:|---:|---:|---:|---:|');
      for (const v of ex.vendorSummary) {
        lines.push(`| ${mdCode(v.part, true)} | ${v.total} | ${v.match} | ${v.differs} | ${v.missing} | ${v.denied} |`);
      }
      if (ex.pulledVendorBlobs > 0) {
        lines.push('');
        lines.push(`Local pull: ${ex.pulledVendorBlobs} readable stock vendor files saved under \`raw/run-*/system/\` (do not commit).`);
      }
    }
  }

  lines.push('');
  return lines.join('\n');
}

function initialSteps(): StepRecord[] {
  return [
    { number: 1, title: 'Preflight & ADB key check (read-only)', status: 'pending' },
    { number: 2, title: 'Fastboot device info & partition checks (read-only)', status: 'pending' },
    { number: 3, title: 'Build sparse userdata probe image (local file only)', status: 'pending' },
    { number: 4, title: 'Flash userdata ONLY & reboot (WRITES userdata; system untouched)', status: 'pending' },
    { number: 5, title: 'Wait for stock ADB (auto-fallback to HTTP probe app if blocked)', status: 'pending' },
    { number: 6, title: 'Extract stock diagnostics & vendor inventory (read-only)', status: 'pending' },
  ];
}

export interface StockProbeResult {
  report: ProbeReport;
  reportPath: string;
  rawDir: string;
  sparsePath?: string;
  outcome: 'complete' | 'read-only-done' | 'build-only-done' | 'stopped' | 'blocked';
}

export async function runStockProbe(o: StockProbeOptions, d: StockProbeDeps): Promise<StockProbeResult> {
  const now = d.now ?? (() => new Date());
  const monotonicMs = d.monotonicMs ?? (() => performance.now());
  const sleep = d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const mode: ProbeReport['mode'] = o.httpOnly ? 'http-only'
    : o.extractOnly ? 'extract-only'
      : o.readOnly ? 'read-only'
        : o.buildOnly ? 'build-only'
          : 'full';

  mkdirSync(o.outDir, { recursive: true, mode: 0o700 });
  const rawRoot = join(o.outDir, 'raw');
  ensurePrivateDir(rawRoot);
  // Private per-run directory (owner-only 0700) so concurrent runs sharing --out cannot clobber
  // each other's sparse image, authoritative checkpoint report, or raw dumps.
  const rawDir = mkdtempSync(join(rawRoot, 'run-'));
  try { chmodSync(rawDir, 0o700); } catch { /* ignore on platforms without POSIX chmod */ }
  const reportPath = containedRawPath(rawDir, 'report.md');
  const latestReportCopy = join(o.outDir, 'report.md');
  let warnedConvenienceCopy = false;

  const report: ProbeReport = {
    startedAt: now().toISOString(),
    updatedAt: now().toISOString(),
    mode,
    dryRun: !!o.dryRun,
    steps: initialSteps(),
  };

  const saveReport = () => {
    report.updatedAt = now().toISOString();
    const md = renderReportMarkdown(report);
    writePrivateFile(reportPath, md, 'utf8');
    try {
      writeFileSync(latestReportCopy, md, 'utf8');
    } catch (e) {
      if (!warnedConvenienceCopy) {
        warnedConvenienceCopy = true;
        d.log(`    WARNING: could not update convenience report copy ${latestReportCopy} (${(e as Error).message}); authoritative report is at ${reportPath}`);
      }
    }
  };
  const setStep = (idx: number, status: StepStatus, note?: string) => {
    report.steps[idx]!.status = status;
    if (note !== undefined) report.steps[idx]!.note = note;
    saveReport();
  };
  const checkpoint = (idx: number, hint?: string) => {
    const s = report.steps[idx]!;
    d.log(`    [checkpoint ${s.number}/6: ${s.status}] report updated -> ${reportPath}`);
    if (hint) d.log(`    ${hint}`);
  };
  const ask = async (idx: number, prompt: string): Promise<boolean> => {
    const s = report.steps[idx]!;
    d.log(`\n[${s.number}/6] ${s.title}`);
    const ok = await d.confirm(prompt);
    if (!ok) {
      setStep(idx, 'stopped', 'declined by user');
      checkpoint(idx, 'Stopped safely. You can share report.md as-is to report progress so far.');
      return false;
    }
    return true;
  };

  const usbConfig = o.usbConfig ?? 'adb';
  const tcpPort = o.tcpPort;
  const tcpTarget = o.tcpHost && tcpPort ? `${o.tcpHost}:${tcpPort}` : undefined;
  const probeHttpPort = o.probeHttpPort ?? DEFAULT_PROBE_HTTP_PORT;

  let fb: StockFastboot | undefined;
  let firstFastbootSerial: string | undefined;
  let userdataBytes = o.userdataBytesOverride ?? 0;
  let sparsePath: string | undefined;
  let adbSession: StockAdbSession | undefined;
  let httpSession: StockHttpSession | undefined;
  let probeAppPlanted = false;

  const writeSparseUserdata = async (fileName: string, probeApp?: ProbeAppPayload) => {
    if (!o.adbKey) throw new Error('adbKey is required to build a userdata image');
    const blocks = Math.floor(userdataBytes / 4096);
    const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    const { plantedPaths } = await buildStockProbeUserdata(disk, {
      adbKey: o.adbKey,
      usbConfig,
      tcpPort,
      wifi: o.wifi,
      ...(probeApp ? { probeApp } : {}),
    });
    const outPath = containedRawPath(rawDir, fileName);
    const sink = fileSink(outPath);
    const fill = o.fill ?? true;
    try {
      await writeSparse(disk, (b) => sink.write(b), { fill });
    } finally {
      sink.close();
    }
    return { outPath, blocks, sparseBytes: sink.bytes(), fill, plantedPaths };
  };

  saveReport();

  // ---- Step 1: Preflight ----
  if (!(await ask(0, 'Check preflight prerequisites and pre-flash ADB state (read-only)?'))) {
    return { report, reportPath, rawDir, outcome: 'stopped' };
  }
  try {
    let fp: string;
    if (o.adbKey) {
      fp = validateStockAdbKey(o.adbKey);
    } else if (o.extractOnly || o.httpOnly) {
      fp = `skipped (${mode})`;
    } else {
      throw new Error('adbKey is required for modes that inspect or build userdata');
    }
    const pre = o.httpOnly ? { usb: 'not-seen' as AdbState } : await d.checkAdb(tcpTarget, { preflight: true });
    report.preflight = {
      adbKeyFingerprint: fp,
      usbConfig,
      ...(tcpPort ? { tcpPort } : {}),
      wifiConfigured: !!o.wifi,
      probeAppPrePlanted: !!o.withProbeApk,
      preFlashUsbAdb: pre.usb,
    };
    d.log(`    ADB key fingerprint: ${fp}`);
    d.log(`    Pre-flash USB adb state: ${pre.usb}`);
    if (pre.usb === 'device' && !o.extractOnly && !o.httpOnly) {
      d.log('    NOTE: Mirror already responds as authorized over ADB! You may not need to wipe userdata — rerun with --extract-only to keep stock userdata intact.');
      adbSession = pre.session;
    }
    setStep(0, 'done', `key ${fp}, pre-flash adb: ${pre.usb}`);
    checkpoint(0);
  } catch (e) {
    setStep(0, 'failed', (e as Error).message);
    throw e;
  }

  // ---- Steps 2-4 (skipped in --extract-only and --http-only modes) ----
  if (o.extractOnly || o.httpOnly) {
    const why = o.httpOnly ? '--http-only' : '--extract-only';
    setStep(1, 'skipped', why);
    setStep(2, 'skipped', why);
    setStep(3, 'skipped', why);
  } else {
    // ---- Step 2: Fastboot (read-only) ----
    d.log('    (If not in fastboot yet: power off, hold VOL- [SW3, top button on small green board], switch power on for ~5s.)');
    if (!(await ask(1, 'Query fastboot device-info and partition sizes (read-only)?'))) {
      return { report, reportPath, rawDir, outcome: 'stopped' };
    }
    try {
      fb = await d.fastboot();
      firstFastbootSerial = fb.serial;
      const oemDeviceInfo = await fb.oemDeviceInfo();
      const vars: Record<string, string> = {};
      for (const v of FASTBOOT_VARS) {
        const val = await fb.getvar(v);
        if (val !== undefined) vars[v] = val;
      }
      const product = (vars.product ?? '').trim();
      const productOk = product === 'MSM8916';
      const udBytes = parseHexBytes(vars['partition-size:userdata']);
      const userdataOk = udBytes !== undefined && udBytes >= 1024 ** 3 && udBytes % 4096 === 0;
      const sysBytes = parseHexBytes(vars['partition-size:system']);
      const systemOk = sysBytes !== undefined && sysBytes >= SYSTEM_BYTES;

      report.fastboot = {
        oemDeviceInfo,
        vars,
        productOk,
        ...(udBytes !== undefined ? { userdataBytes: udBytes } : {}),
        userdataOk,
        ...(sysBytes !== undefined ? { systemBytes: sysBytes } : {}),
        systemOk,
      };
      for (const l of oemDeviceInfo) d.log(`    ${l}`);
      d.log(`    product=${product || 'unknown'} (${productOk ? 'MSM8916 OK' : 'NOT MSM8916'}), userdata=${udBytes ?? 'unknown'} bytes, system=${sysBytes ?? 'unknown'} bytes`);

      if (!productOk || !userdataOk) {
        const why = !productOk ? `product is "${product || 'unknown'}", not MSM8916` : 'partition-size:userdata is missing or invalid';
        setStep(1, 'blocked', `${why} — stopped before any write`);
        checkpoint(1, 'Safety stop-gate triggered. Share report.md before doing anything else.');
        return { report, reportPath, rawDir, outcome: 'blocked' };
      }

      if (!userdataBytes) userdataBytes = udBytes!;
      setStep(1, 'done', `product MSM8916, userdata ${udBytes} bytes`);
      checkpoint(1, 'You can share report.md now if you want to confirm hardware/partitions before writing anything.');
    } catch (e) {
      setStep(1, 'failed', (e as Error).message);
      throw e;
    }

    if (o.readOnly) {
      setStep(2, 'skipped', '--read-only');
      setStep(3, 'skipped', '--read-only');
      setStep(4, 'skipped', '--read-only');
      setStep(5, 'skipped', '--read-only');
      return { report, reportPath, rawDir, outcome: 'read-only-done' };
    }

    // ---- Step 3: Build sparse userdata probe image ----
    if (!(await ask(2, 'Build sparse userdata-probe.sparse locally (no device writes yet)?'))) {
      return { report, reportPath, rawDir, outcome: 'stopped' };
    }
    try {
      let probeApp: ProbeAppPayload | undefined;
      if (o.withProbeApk) {
        probeApp = await d.loadProbeApp?.();
        if (!probeApp) throw new Error('--with-probe-apk requested, but stock-probe.apk could not be loaded or built');
        probeAppPlanted = true;
      }
      const built = await writeSparseUserdata('userdata-probe.sparse', probeApp);
      sparsePath = built.outPath;
      report.sparseBuild = {
        userdataBytes,
        blocks: built.blocks,
        sparseBytes: built.sparseBytes,
        fill: built.fill,
        plantedPaths: built.plantedPaths,
      };
      d.log(`    wrote ${sparsePath} (${built.sparseBytes} bytes sparse for ${userdataBytes} byte partition)`);
      d.log(`    planted: ${built.plantedPaths.join(', ')}`);
      setStep(2, 'done', `${built.sparseBytes} bytes sparse (${built.plantedPaths.length} files${probeAppPlanted ? ', incl. HTTP probe APK' : ''})`);
      checkpoint(2);
    } catch (e) {
      setStep(2, 'failed', (e as Error).message);
      throw e;
    }

    if (o.buildOnly) {
      setStep(3, 'skipped', '--build-only');
      setStep(4, 'skipped', '--build-only');
      setStep(5, 'skipped', '--build-only');
      return { report, reportPath, rawDir, sparsePath, outcome: 'build-only-done' };
    }

    // ---- Step 4: Flash userdata ONLY & reboot ----
    for (const line of STOCK_USERDATA_WARNING) d.log(`    WARNING: ${line}`);
    if (!(await ask(3, 'FLASH userdata ONLY and reboot into stock system? (wipes stock userdata; system untouched)'))) {
      report.flash = { userdataFlashed: false, systemTouched: false };
      saveReport();
      return { report, reportPath, rawDir, sparsePath, outcome: 'stopped' };
    }
    try {
      await fb!.flashUserdata(sparsePath!);
      // Persist the userdata write immediately before attempting reboot so a reboot failure
      // still records that stock userdata was already overwritten.
      report.flash = { userdataFlashed: true, systemTouched: false, rebooted: false };
      saveReport();
    } catch (e) {
      setStep(3, 'failed', (e as Error).message);
      throw e;
    }
    try {
      await fb!.reboot();
      report.flash.rebooted = true;
      d.log('    userdata flashed and reboot sent (stock system untouched).');
      setStep(3, 'done', 'userdata flashed, rebooted (system untouched)');
      checkpoint(3);
    } catch (e) {
      report.flash.rebootError = (e as Error).message;
      setStep(3, 'failed', `userdata flashed, but reboot failed (${report.flash.rebootError}); retry fastboot reboot without reflashing`);
      throw e;
    }
  }

  // ---- Step 5: Wait for stock ADB — or seamlessly transition to the No-ADB HTTP Probe App Fallback ----
  const waitForHttpProbe = async (host: string, waitSec: number): Promise<StockHttpSession | undefined> => {
    if (!d.connectHttp) return undefined;
    const sess = d.connectHttp(host, probeHttpPort);
    const pollMs = 2000;
    const deadlineMs = waitSec * 1000;
    const startMs = monotonicMs();
    let sleptMs = 0;
    const elapsedMs = () => Math.max(monotonicMs() - startMs, sleptMs);

    for (let i = 0; ; i++) {
      const remainingBefore = Math.floor(deadlineMs - elapsedMs());
      if (i > 0 && remainingBefore < 1) break;
      const reqTimeout = Math.min(5000, Math.max(1, remainingBefore));
      const st = await sess.status(reqTimeout).catch(() => null);
      if (st?.ok) {
        report.httpFallback = {
          ...(report.httpFallback ?? { triggered: true, reFlashedUserdataWithApp: false, port: probeHttpPort }),
          connected: true,
          uid: st.uid,
          sdk: st.sdk,
          fiemapAvailable: st.fiemap,
        };
        d.log(`    HTTP probe app answered! uid=${st.uid}, sdk=${st.sdk}, fiemap=${st.fiemap}`);
        return sess;
      }
      const afterMs = elapsedMs();
      if (i % 10 === 0) d.log(`    waiting for HTTP probe app on port ${probeHttpPort} (${Math.min(waitSec, Math.floor(afterMs / 1000))}s/${waitSec}s)...`);
      if (afterMs >= deadlineMs) break;
      const delay = Math.min(pollMs, deadlineMs - afterMs);
      await sleep(delay);
      sleptMs += delay;
    }
    return undefined;
  };

  if (o.httpOnly) {
    if (!(await ask(4, `Connect directly to the stock HTTP probe app on port ${probeHttpPort}?`))) {
      return { report, reportPath, rawDir, sparsePath, outcome: 'stopped' };
    }
    const host = (o.probeHost ?? (await d.promptText?.('Enter the Mirror\'s LAN IP address:')) ?? '').trim();
    if (!host) {
      setStep(4, 'blocked', 'no Mirror IP provided for --http-only');
      setStep(5, 'skipped', 'no probe host');
      return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
    }
    report.httpFallback = { triggered: true, reFlashedUserdataWithApp: false, port: probeHttpPort, connected: false };
    httpSession = await waitForHttpProbe(host, o.waitSeconds ?? 60);
    if (!httpSession) {
      report.httpFallback.note = `no answer on port ${probeHttpPort} before timeout`;
      setStep(4, 'blocked', 'HTTP probe app did not answer');
      setStep(5, 'skipped', 'HTTP probe app unreachable');
      checkpoint(4, 'Check Ethernet/Wi-Fi and IP, then retry --http-only --probe-host <ip>.');
      return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
    }
    setStep(4, 'done', `connected to HTTP probe app (uid=${report.httpFallback.uid}, fiemap=${report.httpFallback.fiemapAvailable})`);
    checkpoint(4);
  } else {
    if (!(await ask(4, 'Wait for stock Android to boot and check USB/TCP ADB state?'))) {
      return { report, reportPath, rawDir, sparsePath, outcome: 'stopped' };
    }
    try {
      const waitSec = o.waitSeconds ?? 180;
      const pollMs = 2000;
      const deadlineMs = waitSec * 1000;
      const startMs = monotonicMs();
      let sleptMs = 0;
      const elapsedMs = () => Math.max(monotonicMs() - startMs, sleptMs);

      let lastUsb: AdbState = 'not-seen';
      let lastTcp: AdbState | undefined;
      adbSession = undefined;

      for (let i = 0; ; i++) {
        const remainingBefore = Math.floor(deadlineMs - elapsedMs());
        if (i > 0 && remainingBefore < 1) break;
        const cmdTimeout = Math.min(10_000, Math.max(1, remainingBefore));
        const st = await d.checkAdb(tcpTarget, { timeoutMs: cmdTimeout });
        lastUsb = st.usb;
        lastTcp = st.tcp;
        if (st.session && (st.usb === 'device' || st.tcp === 'device')) {
          adbSession = st.session;
          break;
        }
        const afterMs = elapsedMs();
        if (i % 10 === 0) {
          d.log(`    waiting for stock adb (${Math.min(waitSec, Math.floor(afterMs / 1000))}s/${waitSec}s): usb=${st.usb}${st.tcp ? `, tcp=${st.tcp}` : ''}`);
        }
        if (afterMs >= deadlineMs) break;
        const delay = Math.min(pollMs, deadlineMs - afterMs);
        await sleep(delay);
        sleptMs += delay;
      }

      if (adbSession) {
        report.adbWait = {
          usb: lastUsb,
          ...(lastTcp !== undefined ? { tcp: lastTcp } : {}),
          outcome: 'authorized',
          nextHint: 'Authorized ADB on untouched stock system! Proceeding to Step 6 extraction.',
        };
        d.log(`    AUTHORIZED over ${lastUsb === 'device' ? 'USB' : 'TCP'}!`);
        setStep(4, 'done', `authorized (usb=${lastUsb}${lastTcp ? `, tcp=${lastTcp}` : ''})`);
        checkpoint(4);
      } else {
        const didFlash = !!report.flash?.userdataFlashed;
        const isUnauth = lastUsb === 'unauthorized' || lastTcp === 'unauthorized';
        const outcome: 'unauthorized' | 'not-seen' = isUnauth ? 'unauthorized' : 'not-seen';
        const hint = isUnauth
          ? didFlash
            ? 'Device reported ADB unauthorized after userdata flash. Possible causes include a host adb key mismatch or stock adbd rejecting /data/misc/adb/adb_keys (observed on 1.2x+ / S22Q401). Stock system is untouched — you can try the no-ADB HTTP probe app fallback in userdata.'
            : 'Device reported ADB unauthorized (no userdata was flashed in this run). Check that the active adb server holds a key trusted by the Mirror, or run the full probe flow.'
          : didFlash
            ? 'No adb device appeared before timeout. Stock system is untouched — you can try the no-ADB HTTP probe app fallback in userdata (or retry with --usb-config diag,serial_smd,rmnet_bam,adb).'
            : 'No adb device appeared before timeout.';
        report.adbWait = {
          usb: lastUsb,
          ...(lastTcp !== undefined ? { tcp: lastTcp } : {}),
          outcome,
          nextHint: hint,
        };
        d.log(`    ${outcome.toUpperCase()}: ${hint}`);
        saveReport();

        // Offer the no-ADB fallback only when this run already flashed userdata (never in --extract-only).
        const canFallback = didFlash && !!d.connectHttp && (probeAppPlanted || (!!d.loadProbeApp && userdataBytes > 0 && !!o.adbKey));
        const tryFallback = canFallback && (await d.confirm(
          probeAppPlanted
            ? 'No ADB — probe APK is already planted. Connect to the Mirror\'s HTTP probe server now?'
            : 'No ADB — run the no-ADB fallback? (Builds & flashes userdata ONLY with /data/app probe APK + 1-entry packages.xml; stock system stays untouched)',
        ));

        if (!tryFallback) {
          setStep(4, 'blocked', isUnauth ? 'adb unauthorized' : 'no adb device seen before timeout');
          setStep(5, 'skipped', outcome);
          checkpoint(4, 'Please share report.md now.');
          return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
        }

        report.httpFallback = { triggered: true, reFlashedUserdataWithApp: false, port: probeHttpPort, connected: false };
        if (!probeAppPlanted) {
          const probeApp = await d.loadProbeApp?.();
          if (!probeApp) {
            report.httpFallback.note = 'stock-probe.apk not available (run tools/mirror/stock-probe-app/build.sh first)';
            setStep(4, 'blocked', 'no-ADB fallback aborted: probe APK not built');
            setStep(5, 'skipped', 'probe APK missing');
            checkpoint(4, 'Build the probe APK with tools/mirror/stock-probe-app/build.sh and rerun.');
            return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
          }
          const built = await writeSparseUserdata('userdata-probe-app.sparse', probeApp);
          sparsePath = built.outPath;
          report.sparseBuild = {
            userdataBytes,
            blocks: built.blocks,
            sparseBytes: built.sparseBytes,
            fill: built.fill,
            plantedPaths: built.plantedPaths,
          };
          d.log(`    built ${sparsePath} (${built.sparseBytes} bytes sparse, incl. /data/app probe APK + 1-entry packages.xml)`);
          d.log('    Put the Mirror back into fastboot now (power off, hold VOL- [SW3], switch on ~5s).');
          if (!(await d.confirm('Mirror in fastboot — FLASH userdata ONLY with the probe APK and reboot?'))) {
            report.httpFallback.note = 'declined before flashing probe-app userdata';
            setStep(4, 'stopped', 'declined no-ADB fallback flash');
            setStep(5, 'skipped', 'stopped');
            return { report, reportPath, rawDir, sparsePath, outcome: 'stopped' };
          }
          fb = await d.fastboot();
          // Recheck Mirror identity and partition gates on the second fastboot session before flashing.
          if (firstFastbootSerial && fb.serial !== firstFastbootSerial) {
            report.httpFallback.note = 'second fastboot session does not match the Mirror from Step 2 — refused to flash';
            setStep(4, 'blocked', 'fallback fastboot device mismatch — stopped before write');
            setStep(5, 'skipped', 'device mismatch');
            checkpoint(4, 'Safety stop-gate triggered before fallback flash.');
            return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
          }
          const product2 = ((await fb.getvar('product')) ?? '').trim();
          const udBytes2 = parseHexBytes(await fb.getvar('partition-size:userdata'));
          const ud2Valid = udBytes2 !== undefined && udBytes2 >= 1024 ** 3 && udBytes2 % 4096 === 0;
          const sizeMatches = ud2Valid && (o.userdataBytesOverride ? userdataBytes <= udBytes2 : udBytes2 === userdataBytes);
          if (product2 !== 'MSM8916' || !sizeMatches) {
            const why = product2 !== 'MSM8916'
              ? `second fastboot product is "${product2 || 'unknown'}", not MSM8916`
              : `second fastboot partition-size:userdata (${udBytes2 ?? 'unknown'}) does not match built image (${userdataBytes})`;
            report.httpFallback.note = `${why} — stopped before fallback flash`;
            setStep(4, 'blocked', `${why} — stopped before write`);
            setStep(5, 'skipped', 'safety gate blocked');
            checkpoint(4, 'Safety stop-gate triggered before fallback flash.');
            return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
          }

          await fb.flashUserdata(sparsePath);
          report.httpFallback.reFlashedUserdataWithApp = true;
          probeAppPlanted = true;
          saveReport();
          try {
            await fb.reboot();
          } catch (re) {
            report.httpFallback.note = `probe-app userdata flashed, but reboot failed (${(re as Error).message}); retry fastboot reboot without reflashing`;
            setStep(4, 'failed', report.httpFallback.note);
            throw re;
          }
          d.log('    userdata (with probe APK + packages.xml) flashed and rebooted; stock system untouched.');
          saveReport();
        }

        d.log('    Plug Ethernet into the Mirror (or let pre-saved Wi-Fi join) and find its LAN IP from your router.');
        const host = (o.probeHost ?? (await d.promptText?.(`Enter the Mirror's LAN IP (for http://<ip>:${probeHttpPort}/status), or leave blank to stop:`)) ?? '').trim();
        if (!host) {
          report.httpFallback.note = 'waiting for LAN IP — rerun with --http-only --probe-host <mirror-ip>';
          setStep(4, 'blocked', 'probe APK flashed; rerun with --http-only --probe-host <ip>');
          setStep(5, 'skipped', 'waiting for LAN IP');
          checkpoint(4, 'Once you have the Mirror\'s IP, run with --http-only --probe-host <ip> (no reflash needed).');
          return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
        }

        httpSession = await waitForHttpProbe(host, waitSec);
        if (!httpSession) {
          report.httpFallback.note = `no HTTP response on port ${probeHttpPort} within ${waitSec}s`;
          setStep(4, 'blocked', 'HTTP probe app did not answer before timeout');
          setStep(5, 'skipped', 'HTTP probe app unreachable');
          checkpoint(4, 'Share report.md, or retry with --http-only --probe-host <ip> once the Mirror is online.');
          return { report, reportPath, rawDir, sparsePath, outcome: 'blocked' };
        }

        setStep(4, 'done', `ADB ${outcome} -> HTTP probe connected (uid=${report.httpFallback.uid}, fiemap=${report.httpFallback.fiemapAvailable})`);
        checkpoint(4);
      }
    } catch (e) {
      setStep(4, 'failed', (e as Error).message);
      throw e;
    }
  }

  // ---- Step 6: Extract stock diagnostics & vendor inventory (over ADB or HTTP) ----
  const transport: 'adb' | 'http' = adbSession ? 'adb' : 'http';
  if (!(await ask(5, `Run read-only stock diagnostics & Option C vendor file audit (via ${transport.toUpperCase()})?`))) {
    return { report, reportPath, rawDir, sparsePath, outcome: 'stopped' };
  }
  try {
    let rawGetprop = '';
    let selinuxEnforce: string | undefined;
    let shellId: string | undefined;
    let procVersion = '';
    let procModules = '';
    let meminfoHead = '';
    let partitions = '';
    let kernelConfigFlags: string[] = [];
    let rotation: string | undefined;
    let backlightEntries: string[] = [];
    let dtPanelLines: string[] = [];
    let dmesgHighlights: string[] = [];
    const ramdiskFiles: FileAudit[] = [];
    const keySystemFiles: FileAudit[] = [];
    const extraConfigFiles: FileAudit[] = [];
    const fiemapResults: FiemapResult[] = [];
    const vendorEntries = (d.vendorEntries ?? []).filter((e) => o.includeCamera || e.part !== 'camera');
    const byPart = new Map<string, VendorPartSummary>();
    let pulledVendorBlobs = 0;
    const vendorAuditLog: string[] = ['status\tpart\tpath\tbytes\tsha256'];

    if (adbSession) {
      const sh = adbSession;
      const runShell = async (cmd: string, label: string) => {
        const res = await sh.shell(cmd);
        if (res.code === -1) {
          throw new Error(`ADB transport lost during ${label}${res.out.trim() ? `: ${res.out.trim()}` : ''}`);
        }
        return res;
      };

      for (let i = 0; i < 30; i++) {
        const bcRes = await runShell('getprop sys.boot_completed', 'boot_completed check');
        if (bcRes.out.trim() === '1') break;
        await sleep(1000);
      }
      const gpRes = await runShell('getprop', 'getprop');
      if (gpRes.code !== 0) {
        throw new Error(`getprop failed with exit code ${gpRes.code}`);
      }
      rawGetprop = gpRes.out;
      selinuxEnforce = (await runShell('getenforce', 'getenforce')).out.trim();
      shellId = (await runShell('id', 'id')).out.trim();
      procVersion = (await runShell('cat /proc/version', '/proc/version')).out.trim();
      procModules = (await runShell('cat /proc/modules', '/proc/modules')).out.trim();
      meminfoHead = (await runShell('head -3 /proc/meminfo', '/proc/meminfo')).out.trim();
      partitions = (await runShell('cat /proc/partitions', '/proc/partitions')).out.trim();
      kernelConfigFlags = extractKernelConfigFlags(await sh.readFile('/proc/config.gz'), rawDir);

      const dmesg = (await runShell('dmesg', 'dmesg')).out;
      writePrivateFile(containedRawPath(rawDir, 'dmesg.txt'), dmesg, 'utf8');
      dmesgHighlights = dmesg
        .split(/\r?\n/)
        .filter((l) => /adsprpc|insmod|adb_keys_file|install-recovery|qcom-post-boot/i.test(l))
        .slice(0, 40)
        .map((l) => redactPossibleIds(l));

      const dumpsysDisplay = (await runShell('dumpsys display', 'dumpsys display')).out;
      const dumpsysWindow = (await runShell('dumpsys window windows', 'dumpsys window')).out;
      const dumpsysAudio = (await runShell('dumpsys audio', 'dumpsys audio')).out;
      writePrivateFile(containedRawPath(rawDir, 'dumpsys-display.txt'), dumpsysDisplay, 'utf8');
      writePrivateFile(containedRawPath(rawDir, 'dumpsys-window.txt'), dumpsysWindow, 'utf8');
      writePrivateFile(containedRawPath(rawDir, 'dumpsys-audio.txt'), dumpsysAudio, 'utf8');
      rotation = /(?:^|\s)mRotation=(\d)\b/m.exec(dumpsysWindow)?.[1];

      const backlightOut = (await runShell('ls -la /sys/class/backlight /sys/class/leds 2>&1', 'backlight list')).out.trim();
      backlightEntries = backlightOut ? backlightOut.split(/\r?\n/).slice(0, 30) : [];

      const dtCmd = 'find /proc/device-tree \\( -name compatible -o -name qcom,mdss-dsi-panel-name \\) 2>/dev/null | grep -v /chosen/ | while read f; do c=$(tr "\\0" " " < "$f"); case "$f$c" in *panel-name*|*lvds*|*panel*|*bridge*|*dsi*|*sn65*|*tc358*|*lt89*|*it6*) echo "${f%/*}: ${f##*/}=$c";; esac; done';
      const dtOut = (await runShell(dtCmd, 'device-tree walk')).out.trim();
      dtPanelLines = dtOut ? dtOut.split(/\r?\n/).slice(0, 50) : [];
      writePrivateFile(containedRawPath(rawDir, 'dt-panel.txt'), `${dtOut}\n`, 'utf8');

      const auditFile = async (path: string, saveUnder?: string): Promise<FileAudit> => {
        if (!isSafeDevicePath(path)) throw new Error(`refusing unsafe device path: ${path}`);
        const data = await sh.readFile(path);
        if (data !== null) {
          if (saveUnder) {
            const dest = containedRawPath(rawDir, saveUnder, path.replace(/^\/+/, ''));
            writePrivateFile(dest, data);
          }
          return { path, status: 'readable', bytes: data.length, sha256: sha256Hex(data) };
        }
        const ls = (await runShell(`ls -ld '${path}' 2>&1`, `stat ${path}`)).out;
        return { path, status: /no such file/i.test(ls) ? 'missing' : 'denied' };
      };

      for (const p of RAMDISK_FILES) ramdiskFiles.push(await auditFile(p, 'ramdisk'));
      for (const p of KEY_SYSTEM_FILES) keySystemFiles.push(await auditFile(p, 'key-system'));

      const extraPaths = (await runShell('ls /system/etc/mixer_paths*.xml /system/etc/thermal-engine*.conf 2>/dev/null', 'extra etc discovery')).out
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => EXTRA_ETC_CONFIG_PATH.test(l));
      for (const p of extraPaths) extraConfigFiles.push(await auditFile(p, 'key-system'));

      for (const entry of vendorEntries) {
        if (!isSafeDevicePath(entry.path)) throw new Error(`refusing unsafe vendor path: ${entry.path}`);
        let s = byPart.get(entry.part);
        if (!s) {
          s = { part: entry.part, total: 0, match: 0, differs: 0, missing: 0, denied: 0 };
          byPart.set(entry.part, s);
        }
        s.total += 1;
        const data = await sh.readFile(entry.path);
        if (data !== null) {
          const hash = sha256Hex(data);
          const status = hash === entry.sha256 ? 'match' : 'differs';
          if (status === 'match') s.match += 1;
          else s.differs += 1;
          vendorAuditLog.push(`${status}\t${entry.part}\t${entry.path}\t${data.length}\t${hash}`);
          if (o.pullSystemBlobs) {
            const dest = containedRawPath(rawDir, 'system', entry.path.replace(/^\/system\//, ''));
            writePrivateFile(dest, data);
            pulledVendorBlobs += 1;
          }
        } else {
          const ls = (await runShell(`ls -ld '${entry.path}' 2>&1`, `stat ${entry.path}`)).out;
          const status = /no such file/i.test(ls) ? 'missing' : 'denied';
          if (status === 'missing') s.missing += 1;
          else s.denied += 1;
          vendorAuditLog.push(`${status}\t${entry.part}\t${entry.path}\t0\t`);
        }
      }
    } else if (httpSession) {
      const hs = httpSession;
      const dec = new TextDecoder();
      const readText = async (path: string) => {
        const r = await hs.readFile(path);
        return r.status === 'ok' ? dec.decode(r.data).trim() : '';
      };

      rawGetprop = await hs.props();
      procVersion = await readText('/proc/version');
      procModules = await readText('/proc/modules');
      meminfoHead = (await readText('/proc/meminfo')).split(/\r?\n/).slice(0, 3).join('\n');
      partitions = await readText('/proc/partitions');
      const cfgRes = await hs.readFile('/proc/config.gz');
      kernelConfigFlags = extractKernelConfigFlags(cfgRes.status === 'ok' ? cfgRes.data : null, rawDir);

      const dtOut = (await hs.dtPanel()).trim();
      dtPanelLines = dtOut ? dtOut.split(/\r?\n/).slice(0, 50) : [];
      writePrivateFile(containedRawPath(rawDir, 'dt-panel.txt'), `${dtOut}\n`, 'utf8');

      const bl = await hs.list('/sys/class/backlight');
      const leds = await hs.list('/sys/class/leds');
      backlightEntries = [...bl, ...leds].slice(0, 30);

      const auditFileHttp = async (path: string, saveUnder?: string): Promise<FileAudit> => {
        if (!isSafeDevicePath(path)) throw new Error(`refusing unsafe device path: ${path}`);
        const r = await hs.readFile(path);
        if (r.status === 'ok') {
          if (saveUnder) {
            const dest = containedRawPath(rawDir, saveUnder, path.replace(/^\/+/, ''));
            writePrivateFile(dest, r.data);
          }
          return { path, status: 'readable', bytes: r.data.length, sha256: sha256Hex(r.data) };
        }
        return { path, status: r.status };
      };

      for (const p of RAMDISK_FILES) ramdiskFiles.push(await auditFileHttp(p, 'ramdisk'));
      for (const p of KEY_SYSTEM_FILES) keySystemFiles.push(await auditFileHttp(p, 'key-system'));

      const etcEntries = await hs.list('/system/etc');
      const extraPaths = etcEntries
        .map((l) => l.split('\t').at(-1)?.trim() ?? '')
        .filter((p) => EXTRA_ETC_CONFIG_PATH.test(p));
      for (const p of extraPaths) extraConfigFiles.push(await auditFileHttp(p, 'key-system'));

      for (const target of FIEMAP_TARGETS) {
        const fm = await hs.fiemap(target);
        fiemapResults.push(fm);
      }
      writePrivateFile(containedRawPath(rawDir, 'fiemap.json'), JSON.stringify(fiemapResults, null, 2), 'utf8');

      for (const entry of vendorEntries) {
        if (!isSafeDevicePath(entry.path)) throw new Error(`refusing unsafe vendor path: ${entry.path}`);
        let s = byPart.get(entry.part);
        if (!s) {
          s = { part: entry.part, total: 0, match: 0, differs: 0, missing: 0, denied: 0 };
          byPart.set(entry.part, s);
        }
        s.total += 1;
        const r = await hs.readFile(entry.path);
        if (r.status === 'ok') {
          const hash = sha256Hex(r.data);
          const status = hash === entry.sha256 ? 'match' : 'differs';
          if (status === 'match') s.match += 1;
          else s.differs += 1;
          vendorAuditLog.push(`${status}\t${entry.part}\t${entry.path}\t${r.data.length}\t${hash}`);
          if (o.pullSystemBlobs) {
            const dest = containedRawPath(rawDir, 'system', entry.path.replace(/^\/system\//, ''));
            writePrivateFile(dest, r.data);
            pulledVendorBlobs += 1;
          }
        } else {
          if (r.status === 'missing') s.missing += 1;
          else s.denied += 1;
          vendorAuditLog.push(`${r.status}\t${entry.part}\t${entry.path}\t0\t`);
        }
      }
    }

    writePrivateFile(containedRawPath(rawDir, 'getprop.txt'), rawGetprop, 'utf8');
    writePrivateFile(containedRawPath(rawDir, 'proc-version.txt'), `${procVersion}\n`, 'utf8');
    writePrivateFile(containedRawPath(rawDir, 'partitions.txt'), `${partitions}\n`, 'utf8');
    if (vendorEntries.length) {
      writePrivateFile(containedRawPath(rawDir, 'vendor-audit.tsv'), `${vendorAuditLog.join('\n')}\n`, 'utf8');
    }

    const { props, initServices } = parseGetprop(rawGetprop);
    report.extraction = {
      transport,
      props,
      selinuxEnforce,
      shellId,
      procVersion,
      kernelConfigFlags,
      procModules,
      meminfoHead,
      partitions,
      initServices,
      ramdiskFiles,
      rotation,
      backlightEntries,
      dtPanelLines,
      dmesgHighlights,
      keySystemFiles,
      extraConfigFiles,
      fiemapResults,
      vendorSummary: [...byPart.values()],
      vendorCameraIncluded: !!o.includeCamera,
      pulledVendorBlobs,
    };

    d.log(`    extracted via ${transport.toUpperCase()}: ${Object.keys(props).length} props, ${Object.keys(initServices).length} init services, ${ramdiskFiles.filter((f) => f.status === 'readable').length}/${ramdiskFiles.length} ramdisk files, ${vendorEntries.length} vendor paths audited`);
    setStep(5, 'done', `extracted via ${transport} (${Object.keys(initServices).length} services, ${vendorEntries.length} vendor paths${fiemapResults.length ? `, ${fiemapResults.length} fiemap` : ''})`);
    checkpoint(5, 'All steps complete! Share report.md below (never commit raw/).');
    return { report, reportPath, rawDir, sparsePath, outcome: 'complete' };
  } catch (e) {
    setStep(5, 'failed', (e as Error).message);
    throw e;
  }
}
