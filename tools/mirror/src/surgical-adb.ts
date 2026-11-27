// Phase 2 stock Mirror tooling: reversible surgical sparse patching of /system/framework/services.jar
// (for authorized USB ADB on untouched stock 1.24.0 without replacing system) and an optional
// in-place patch of /system/vendor/lib64/libdpmframework.so. That library's constructor runs inside
// dpmd (uid 0, CAP_SYS_MODULE). The patch embeds the selinux_permissive .ko, loads it, and makes the
// system and boot block nodes readable for one boot so the raw partitions can be streamed out, then
// the original library blocks are flashed back. The module is the pinned GPL-2.0 artifact from
// glassworks-device-bins, consumed by URL + SHA-256 (device-bins.json), or a local --backup-module.
//
// init.qcom.post_boot.sh is not the hook. qti_init_shell has no sys_module and cannot search
// /data/local/tmp (verified on stock 1.24.0). patchPostBootForBackup remains for that record.
//
// Hard rules enforced here:
// - Read first, back up first, verify sizes/hashes before any write.
// - Every surgical system patch preserves the EXACT original file byte length so ext4 inode
//   metadata (i_size, extents, directory entries) is never modified; only the target file's
//   physical 4 KiB blocks are written (DONT_CARE everywhere else).
// - Byte-for-byte restore sparse images are written BEFORE any flash is offered.
// - No GPL payloads or vendor binaries are committed to this repo (Rule 3 & Rule 4): the GPL-2.0
//   selinux_permissive module is fetched at run time from glassworks-device-bins, pinned by SHA-256.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { Disk, MemoryStore, parseSparseHeader, writeSparse } from '@glassworks/ext4';
import {
  buildSurgicalSparseImage, patchStockServicesJar as patchStockServicesJarCore,
  SPARSE_BLOCK_SIZE, START_CONFIRMATION_LOG_STRING, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES,
  USERDATA_BYTES, type PatchedServicesJar,
} from '@glassworks/lululemon-mirror-installer';
import { buildDpmdHook, planDpmdHook } from './dpmd-hook';
import {
  buildStockProbeUserdata, DEFAULT_PROBE_HTTP_PORT, validateStockAdbKey,
  type AdbState, type FiemapExtent, type FiemapResult, type ProbeAppPayload,
  type StockAdbSession, type StockHttpSession,
} from './stock-probe';

// The surgical sparse builder and the services.jar patch now live in the browser-safe shared core
// (`@glassworks/lululemon-mirror-installer`, src/stock.ts), so Kiln (WebUSB) and this Node tool share
// one source of truth. They are re-exported here so the tool's CLI, tests, and report keep working.
export { SPARSE_BLOCK_SIZE, START_CONFIRMATION_LOG_STRING, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES, buildSurgicalSparseImage };
export type { PatchedServicesJar };

/**
 * Patches a stock `services.jar` for the Node USB-ADB tool. The shared core defaults to also applying
 * the Device Owner fixes (Stock-in-place silent updates), to emptying `MirrorApService.systemReady()`
 * (no stock onboarding hotspot), to letting the app approve USB debugging (`WRITE_SECURE_SETTINGS`), and to
 * letting adb set the clock and time zone. This tool is a reversible probe that only changes who USB
 * debugging lets in, so it opts OUT of all four by default; a caller can opt back in with
 * `{ enableDeviceOwner: true }`, `{ disableStockAp: true }`, `{ appApprovesUsbDebugging: true }` or
 * `{ adbSetsClock: true }`.
 */
export function patchStockServicesJar(
  jar: Uint8Array,
  adbKey: string,
  options: { enableDeviceOwner?: boolean; disableStockAp?: boolean; appApprovesUsbDebugging?: boolean; adbSetsClock?: boolean } = {},
): Promise<PatchedServicesJar> {
  return patchStockServicesJarCore(jar, adbKey, {
    ...options,
    enableDeviceOwner: options.enableDeviceOwner ?? false,
    disableStockAp: options.disableStockAp ?? false,
    appApprovesUsbDebugging: options.appApprovesUsbDebugging ?? false,
    adbSetsClock: options.adbSetsClock ?? false,
  });
}

export const STOCK_POST_BOOT_PATH = '/system/etc/init.qcom.post_boot.sh';
/** ELF64 copy. `/system/vendor/lib/libdpmframework.so` is ELF32; dpmd links this one. */
export const STOCK_DPMD_LIB_PATH = '/system/vendor/lib64/libdpmframework.so';
export const REMOTE_BACKUP_KO_PATH = '/data/local/tmp/gw_backup.ko';

/** One pinned GPL binary published by GlassworksHQ/glassworks-device-bins and consumed by URL + SHA-256. */
export interface DeviceBinPin {
  /** The device-bins component name (`<frame>-<name>`). */
  component: string;
  /** The key of this file in `kiln/workers/refract/allowlist.json` (`<repo>/<tag>/<name>`). */
  allowlistId: string;
  /** Where the tool fetches it: Refract streams the pinned release asset with the SHA checked again. */
  refractUrl: string;
  /** The GitHub release asset behind Refract, for reference and as a documented fallback. */
  releaseUrl: string;
  name: string;
  size: number;
  sha256: string;
  license: string;
}

/**
 * The pinned device-bins artifacts this tool fetches, loaded from `tools/mirror/device-bins.json`
 * (the single source `tools/check-device-bins.py` holds to the Refract allowlist). The Mirror backup
 * module is a GPL-2.0 kernel module that can't live in this repo (Hard Rule 4), so it's published in
 * `glassworks-device-bins` with its Corresponding Source and consumed by pinned URL + SHA-256 here.
 */
const DEVICE_BINS = JSON.parse(
  readFileSync(new URL('../device-bins.json', import.meta.url), 'utf8'),
) as { selinuxPermissive: DeviceBinPin };
export const SELINUX_PERMISSIVE_PIN: DeviceBinPin = DEVICE_BINS.selinuxPermissive;

const sha256Hex = (b: Uint8Array): string => createHash('sha256').update(b).digest('hex');
const td = new TextDecoder();
const te = new TextEncoder();

/**
 * Surgically inserts a one-shot `insmod <modulePath>` + read-only `chmod 0444` (RAM devtmpfs
 * only, cleared on reboot) for `mmcblk0p22` (`system`) and `mmcblk0p19` (`boot`) into
 * `/system/etc/init.qcom.post_boot.sh` while preserving the EXACT original byte length by
 * shortening an existing `#` comment block (never modifying `#!/system/bin/sh` or any executable
 * statement).
 *
 * The runner does not call this. On stock 1.24.0 `qti_init_shell` has no `sys_module` and cannot
 * search `/data/local/tmp`, so Step 4 patches `libdpmframework.so` instead.
 */
export function patchPostBootForBackup(orig: Uint8Array, modulePath = REMOTE_BACKUP_KO_PATH): Uint8Array {
  if (!/^\/data\/local\/tmp\/[A-Za-z0-9._-]+\.ko$/.test(modulePath)) {
    throw new Error(`unsafe backup module path: ${modulePath}`);
  }
  const text = td.decode(orig);
  if (!text.startsWith('#!/')) throw new Error('init.qcom.post_boot.sh does not start with a shebang');
  const snippet = `\n[ -f ${modulePath} ] && insmod ${modulePath} && chmod 0444 /dev/block/mmcblk0p22 /dev/block/mmcblk0p19\n`;
  if (text.includes(modulePath)) {
    throw new Error(`init.qcom.post_boot.sh already references ${modulePath}`);
  }

  const lines = text.split('\n');
  // Find comment lines after line 0 whose total trimmable characters (keeping `#` prefix) cover snippet.length.
  const need = te.encode(snippet).length;
  let saved = 0;
  const outLines = [...lines];
  for (let i = 1; i < outLines.length && saved < need; i++) {
    const line = outLines[i]!;
    if (!line.startsWith('#')) continue;
    const avail = line.length - 1; // keep leading '#'
    if (avail <= 0) continue;
    const take = Math.min(avail, need - saved);
    // Only trim pure ASCII bytes so byte length === char count.
    if (/[^\x00-\x7f]/.test(line)) continue;
    outLines[i] = line.slice(0, line.length - take);
    saved += take;
  }
  if (saved < need) {
    throw new Error(`not enough comment space in init.qcom.post_boot.sh (needed ${need} bytes, found ${saved})`);
  }

  const rebuilt = te.encode(outLines.join('\n') + snippet);
  if (rebuilt.length !== orig.length) {
    throw new Error(`patched init.qcom.post_boot.sh length ${rebuilt.length} !== original ${orig.length}`);
  }
  return rebuilt;
}

export interface KernelModuleInfo {
  arch: string;
  vermagic: string;
  moduleLayoutCrc?: string;
}

/** Validates that the backup `.ko` (pinned or override) is an AArch64 relocatable ELF and reads its vermagic/CRC. */
export function validateKernelModuleElf(ko: Uint8Array): KernelModuleInfo {
  if (ko.length < 64 || ko[0] !== 0x7f || ko[1] !== 0x45 || ko[2] !== 0x4c || ko[3] !== 0x46) {
    throw new Error('backup module is not an ELF file');
  }
  if (ko[4] !== 2 || ko[5] !== 1) {
    throw new Error('backup module must be a 64-bit little-endian ELF');
  }
  const dv = new DataView(ko.buffer, ko.byteOffset, ko.byteLength);
  const eType = dv.getUint16(16, true);
  const eMachine = dv.getUint16(18, true);
  if (eType !== 1) throw new Error(`backup module must be relocatable ELF (ET_REL=1, got ${eType})`);
  if (eMachine !== 183) throw new Error(`backup module must be AArch64 (EM_AARCH64=183, got ${eMachine})`);

  const needle = te.encode('vermagic=');
  let vermagic = '';
  for (let i = 0; i <= ko.length - needle.length; i++) {
    let ok = true;
    for (let j = 0; j < needle.length; j++) {
      if (ko[i + j] !== needle[j]) { ok = false; break; }
    }
    if (ok) {
      let end = i + needle.length;
      while (end < ko.length && ko[end] !== 0) end++;
      vermagic = td.decode(ko.subarray(i + needle.length, end));
      break;
    }
  }
  if (!vermagic) throw new Error('backup module has no vermagic= modinfo entry');

  let moduleLayoutCrc: string | undefined;
  const ml = te.encode('module_layout\0');
  for (let i = 8; i <= ko.length - ml.length; i++) {
    let ok = true;
    for (let j = 0; j < ml.length; j++) {
      if (ko[i + j] !== ml[j]) { ok = false; break; }
    }
    if (ok) {
      const crc = dv.getUint32(i - 8, true);
      moduleLayoutCrc = `0x${crc.toString(16).padStart(8, '0')}`;
      break;
    }
  }

  return { arch: 'aarch64', vermagic, ...(moduleLayoutCrc ? { moduleLayoutCrc } : {}) };
}

/**
 * Resolves the Step 4 backup module. By default this is the pinned, hash-checked `selinux_permissive`
 * artifact from `glassworks-device-bins` (fetched through Refract); `--backup-module <path>` overrides
 * it with a local `.ko`, e.g. for a Mirror revision the pinned build doesn't match. Either way the
 * bytes are validated as an AArch64 relocatable module before anything is built.
 */
export async function loadBackupModule(
  o: Pick<SurgicalAdbOptions, 'backupModulePath'>,
  d: Pick<SurgicalAdbDeps, 'readHostFile' | 'fetchUrl'>,
): Promise<{ bytes: Uint8Array; sourceNote: string }> {
  if (o.backupModulePath) {
    const bytes = await d.readHostFile(o.backupModulePath);
    return { bytes, sourceNote: `local override (--backup-module ${o.backupModulePath})` };
  }
  const pin = SELINUX_PERMISSIVE_PIN;
  if (!d.fetchUrl) {
    throw new Error('no way to fetch the pinned backup module; pass --backup-module <path> to supply one');
  }
  let bytes: Uint8Array;
  try {
    bytes = await d.fetchUrl(pin.refractUrl);
  } catch (e) {
    throw new Error(`could not fetch the pinned backup module from ${pin.refractUrl}: ${(e as Error).message}`);
  }
  if (bytes.length !== pin.size) {
    throw new Error(`pinned backup module is ${bytes.length} B, the pin says ${pin.size} (${pin.component}); refusing`);
  }
  const got = sha256Hex(bytes);
  if (got !== pin.sha256) {
    throw new Error(`pinned backup module SHA-256 ${got} != pinned ${pin.sha256} (${pin.component}); refusing`);
  }
  return { bytes, sourceNote: `pinned ${pin.component} (${pin.license}, sha256 ${pin.sha256.slice(0, 12)}…)` };
}

export interface SurgicalFastboot {
  serial: string;
  oemDeviceInfo(): Promise<string[]>;
  getvar(name: string): Promise<string | undefined>;
  flashPartition(partition: 'system' | 'userdata', sparsePath: string): Promise<void>;
  erasePartition(partition: 'userdata'): Promise<void>;
  reboot(): Promise<void>;
}

export interface SurgicalAdbSession extends StockAdbSession {
  push(localPath: string, remotePath: string): Promise<void>;
  rebootBootloader(): Promise<void>;
  streamCommandToFile(remoteCmd: string, outPath: string): Promise<{ bytes: number; sha256: string }>;
  forwardTcp?(localPort: number, remotePort: number): Promise<void>;
  removeForward?(localPort: number): Promise<void>;
}

export interface BackupManifest {
  version: 1;
  createdAt: string;
  systemBytes: number;
  userdataBytes: number;
  /**
   * SHA-256 of the device's `androidboot.serialno` (never the raw serial — Hard Rule 2), captured
   * either from `/proc/cmdline` over the Step 1 SoftAP probe or from `fastboot getvar serialno` at
   * flash time. Lets `--undo`/`--apply-adb` refuse to restore this backup onto a different Mirror.
   */
  deviceIdHash?: string;
  servicesJar: {
    path: string;
    size: number;
    originalSha256: string;
    patchedSha256?: string;
    fingerprint?: string;
    extents: FiemapExtent[];
    origFile: string;
    restoreSparse: string;
    patchSparse?: string;
  };
  postBoot?: {
    path: string;
    size: number;
    originalSha256: string;
    extents: FiemapExtent[];
    origFile: string;
    restoreSparse: string;
  };
  dpmdLib?: {
    path: string;
    size: number;
    originalSha256: string;
    patchedSha256?: string;
    extents: FiemapExtent[];
    origFile: string;
    restoreSparse: string;
    patchSparse?: string;
  };
}

export interface SurgicalAdbOptions {
  mode: 'full' | 'preflight-only' | 'build-only' | 'undo' | 'apply-adb' | 'backup-only';
  probeHost: string;
  probeHttpPort: number;
  adbKey?: string;
  /** Run Step 4's raw backup, loading the pinned device-bins module (unless backupModulePath overrides it). */
  wantBackup?: boolean;
  /** An override backup `.ko` on the caller's disk, instead of the pinned device-bins artifact (implies wantBackup). */
  backupModulePath?: string;
  backupDir?: string;
  restoreAllAfterBackup?: boolean;
  eraseUserdataOnUndo?: boolean;
  waitSeconds: number;
  outDir: string;
}

export interface SurgicalAdbDeps {
  now: () => Date;
  monotonicMs?: () => number;
  sleep: (ms: number) => Promise<void>;
  log: (line: string) => void;
  confirm: (prompt: string) => Promise<boolean>;
  connectHttp: (host: string, port: number) => StockHttpSession;
  waitForFastboot: (timeoutMs: number) => Promise<SurgicalFastboot | null>;
  checkAdb: (timeoutMs: number) => Promise<{ usb: AdbState; session?: SurgicalAdbSession }>;
  loadProbeApp: () => Promise<ProbeAppPayload | undefined>;
  writeFile: (relPath: string, data: Uint8Array | string) => Promise<string>;
  readHostFile: (absPath: string) => Promise<Uint8Array>;
  /** Fetches the pinned device-bins backup module by URL (its bytes are checked against the pin). */
  fetchUrl?: (url: string) => Promise<Uint8Array>;
  restoreExtentsInRawImage?: (
    rawImagePath: string,
    patches: Array<{ extents: readonly FiemapExtent[]; origData: Uint8Array }>,
  ) => Promise<string>;
}

export interface SurgicalAdbReport {
  mode: SurgicalAdbOptions['mode'];
  startedAt: string;
  updatedAt: string;
  adbKeyFingerprint?: string;
  frameworkListing: string[];
  oatListing: string[];
  servicesOdexFound: boolean;
  appListing: string[];
  privAppListing: string[];
  servicesFiemap?: FiemapResult;
  postBootFiemap?: FiemapResult;
  dpmdFiemap?: FiemapResult;
  servicesOriginalSha256?: string;
  servicesPatchedSha256?: string;
  servicesPadBytes?: number;
  postBootOriginalSha256?: string;
  dpmdOriginalSha256?: string;
  dpmdPatchedSha256?: string;
  dpmdEntryVaddr?: number;
  dpmdChainedVaddr?: number;
  systemPartitionBytes?: number;
  userdataPartitionBytes?: number;
  bootPartitionBytes?: number;
  adbAuthorized?: boolean;
  adbId?: string;
  adbEnforce?: string;
  adbMirrorVersion?: string;
  adbPackagesCount?: number;
  backupSystemPath?: string;
  backupSystemBytes?: number;
  backupSystemSha256?: string;
  backupBootPath?: string;
  backupBootBytes?: number;
  backupBootSha256?: string;
  restoredPostBoot?: boolean;
  restoredDpmd?: boolean;
  restoredServices?: boolean;
  notes: string[];
  stopReason?: string;
}

function parseHexOrDecSize(v: string | undefined): number | undefined {
  if (!v) return undefined;
  const s = v.trim().toLowerCase();
  const n = s.startsWith('0x') ? Number.parseInt(s.slice(2), 16) : Number.parseInt(s, 10);
  return Number.isSafeInteger(n) && n > 0 ? n : undefined;
}

/** SHA-256 of a device serial for device-binding checks. Never store or log the raw serial (Hard Rule 2). */
function deviceIdHashOf(serial: string): string {
  return sha256Hex(te.encode(serial.trim()));
}

/** Best-effort: reads the probed device's `androidboot.serialno` out of `/proc/cmdline` over the
 * already-open SoftAP HTTP session and hashes it, so Step 3's fastboot target can be checked against
 * the same device that Step 1 actually probed. Degrades silently if `/proc/cmdline` isn't readable. */
async function probedDeviceIdHash(http: StockHttpSession): Promise<string | undefined> {
  try {
    const cmdline = await http.readFile('/proc/cmdline');
    if (cmdline.status !== 'ok') return undefined;
    const m = /androidboot\.serialno=([^\s]+)/i.exec(td.decode(cmdline.data));
    return m ? deviceIdHashOf(m[1]!) : undefined;
  } catch {
    return undefined;
  }
}

/** Reads and parses a saved restore sparse image before it is ever flashed, so a corrupt or
 * mismatched artifact (wrong systemBytes) is caught before the first restore write. */
async function validateRestoreSparse(
  d: SurgicalAdbDeps,
  backupDir: string,
  relPath: string,
  label: string,
  expectedSystemBytes: number,
): Promise<string | undefined> {
  try {
    const bytes = await d.readHostFile(`${backupDir}/${relPath}`);
    const parsed = parseSparseHeader(bytes);
    if (!parsed) return `${label} (${relPath}) is not a valid sparse image`;
    if (parsed.blocks * SPARSE_BLOCK_SIZE !== expectedSystemBytes) {
      return `${label} (${relPath}) covers ${parsed.blocks * SPARSE_BLOCK_SIZE} B, expected systemBytes=${expectedSystemBytes}`;
    }
    return undefined;
  } catch (err) {
    return `could not read/validate ${label} (${relPath}): ${err instanceof Error ? err.message : String(err)}`;
  }
}

function formatExtents(extents?: readonly FiemapExtent[]): string {
  if (!extents || extents.length === 0) return 'none';
  return extents.map((e) => `block \`${e.block}\` × \`${e.blocks}\` (logical ${e.logical})`).join(', ');
}

export function renderSurgicalReport(r: SurgicalAdbReport): string {
  const out: string[] = [
    '# lululemon Studio Mirror — Phase 2 Surgical Stock ADB Report',
    '',
    '> Redacted report — safe to share in a GitHub issue, PR, or chat.',
    '> Backups and raw images live locally outside git and must never be committed.',
    '',
    `- **Mode:** \`${r.mode}\``,
    `- **Started:** ${r.startedAt}`,
    `- **Updated:** ${r.updatedAt}`,
  ];
  if (r.adbKeyFingerprint) out.push(`- **Host ADB key fingerprint:** \`${r.adbKeyFingerprint}\``);
  if (r.stopReason) out.push(`- **Stopped:** ${r.stopReason}`);
  out.push('');

  out.push('## Step 1 — Preflight (read-only over SoftAP HTTP)');
  out.push('');
  out.push(`- \`/system/framework\` entries: **${r.frameworkListing.length}**`);
  out.push(`- \`services.odex\` detected: **${r.servicesOdexFound ? 'YES (STOPPED)' : 'no'}**`);
  out.push(`- \`/system/app\` entries: **${r.appListing.length}**`);
  out.push(`- \`/system/priv-app\` entries: **${r.privAppListing.length}**`);
  if (r.servicesFiemap) {
    out.push(`- \`/system/framework/services.jar\` FIEMAP: ${r.servicesFiemap.error ?? `${r.servicesFiemap.size ?? '?'} B — ${formatExtents(r.servicesFiemap.extents)}`}`);
  }
  if (r.postBootFiemap) {
    out.push(`- \`/system/etc/init.qcom.post_boot.sh\` FIEMAP: ${r.postBootFiemap.error ?? `${r.postBootFiemap.size ?? '?'} B — ${formatExtents(r.postBootFiemap.extents)}`}`);
  }
  if (r.dpmdFiemap) {
    out.push(`- \`${STOCK_DPMD_LIB_PATH}\` FIEMAP: ${r.dpmdFiemap.error ?? `${r.dpmdFiemap.size ?? '?'} B — ${formatExtents(r.dpmdFiemap.extents)}`}`);
  }
  if (r.servicesOriginalSha256) out.push(`- Original \`services.jar\` SHA-256: \`${r.servicesOriginalSha256}\``);
  if (r.postBootOriginalSha256) out.push(`- Original \`init.qcom.post_boot.sh\` SHA-256: \`${r.postBootOriginalSha256}\``);
  if (r.dpmdOriginalSha256) out.push(`- Original \`libdpmframework.so\` SHA-256: \`${r.dpmdOriginalSha256}\``);
  if (r.oatListing.length > 0) {
    out.push('', '`/system/framework/oat` entries:', '```', ...r.oatListing, '```');
  }
  if (r.appListing.length > 0) {
    out.push('', '`/system/app`:', '```', ...r.appListing, '```');
  }
  if (r.privAppListing.length > 0) {
    out.push('', '`/system/priv-app`:', '```', ...r.privAppListing, '```');
  }

  if (r.servicesPatchedSha256 || r.dpmdPatchedSha256) {
    out.push('', '## Step 2 — Surgical Patch Build (exact byte length)', '');
    if (r.servicesPatchedSha256) {
      out.push(`- Patched \`services.jar\` SHA-256: \`${r.servicesPatchedSha256}\` (EOCD pad: ${r.servicesPadBytes ?? 0} B)`);
    }
    if (r.dpmdPatchedSha256) {
      const entry = r.dpmdEntryVaddr !== undefined ? `0x${r.dpmdEntryVaddr.toString(16)}` : '?';
      const chained = r.dpmdChainedVaddr !== undefined ? `0x${r.dpmdChainedVaddr.toString(16)}` : '?';
      out.push(`- Patched \`libdpmframework.so\` SHA-256: \`${r.dpmdPatchedSha256}\` (.init_array[0] → ${entry}, chains to ${chained})`);
    }
  }

  if (r.adbAuthorized !== undefined) {
    out.push('', '## Step 3 — Authorized USB ADB Verification', '');
    out.push(`- USB ADB authorized: **${r.adbAuthorized ? 'YES' : 'no'}**`);
    if (r.adbId) out.push(`- \`id\`: \`${r.adbId}\``);
    if (r.adbEnforce) out.push(`- \`getenforce\`: \`${r.adbEnforce}\``);
    if (r.adbMirrorVersion) out.push(`- \`ro.build.version.mirror\`: \`${r.adbMirrorVersion}\``);
    if (r.adbPackagesCount !== undefined) out.push(`- \`pm list packages\` count: **${r.adbPackagesCount}**`);
  }

  if (r.backupSystemSha256 || r.backupBootSha256) {
    out.push('', '## Step 4 — Full Raw Backup & Restore', '');
    if (r.backupSystemSha256) {
      out.push(`- Raw \`system\` backup: ${r.backupSystemBytes} B, SHA-256 \`${r.backupSystemSha256}\``);
    }
    if (r.backupBootSha256) {
      out.push(`- Raw \`boot\` backup: ${r.backupBootBytes} B, SHA-256 \`${r.backupBootSha256}\``);
    }
    if (r.restoredPostBoot !== undefined) {
      out.push(`- Restored original \`init.qcom.post_boot.sh\` blocks: **${r.restoredPostBoot ? 'yes' : 'no'}**`);
    }
    if (r.restoredDpmd !== undefined) {
      out.push(`- Restored original \`libdpmframework.so\` blocks: **${r.restoredDpmd ? 'yes' : 'no'}**`);
    }
    if (r.restoredServices !== undefined) {
      out.push(`- Restored original \`services.jar\` blocks: **${r.restoredServices ? 'yes' : 'no'}**`);
    }
  }

  if (r.notes.length > 0) {
    out.push('', '## Notes', '');
    for (const n of r.notes) out.push(`- ${n}`);
  }
  out.push('');
  return out.join('\n');
}

function detectServicesOdex(frameworkLines: readonly string[], oatLines: readonly string[]): boolean {
  const all = [...frameworkLines, ...oatLines];
  return all.some((l) => /(?:^|\/)services\.odex$/i.test(l.split('\t').pop() ?? ''));
}

export async function runStockSurgicalAdb(
  o: SurgicalAdbOptions,
  d: SurgicalAdbDeps,
): Promise<{ report: SurgicalAdbReport; ok: boolean }> {
  const nowIso = () => d.now().toISOString();
  const mono = () => (d.monotonicMs ? d.monotonicMs() : performance.now());
  const report: SurgicalAdbReport = {
    mode: o.mode,
    startedAt: nowIso(),
    updatedAt: nowIso(),
    frameworkListing: [],
    oatListing: [],
    servicesOdexFound: false,
    appListing: [],
    privAppListing: [],
    notes: [],
  };
  const saveReport = async () => {
    report.updatedAt = nowIso();
    await d.writeFile('report.md', renderSurgicalReport(report));
  };

  // --- Undo mode ---
  if (o.mode === 'undo') {
    if (!o.backupDir) throw new Error('--undo requires --backup-dir <dir>');
    const manifestRaw = await d.readHostFile(`${o.backupDir}/manifest.json`);
    const manifest = JSON.parse(td.decode(manifestRaw)) as BackupManifest;
    const fb = await d.waitForFastboot(o.waitSeconds * 1000);
    if (!fb) {
      report.stopReason = 'no Mirror detected in fastboot mode for --undo';
      await saveReport();
      return { report, ok: false };
    }
    const product = await fb.getvar('product');
    const sysBytes = parseHexOrDecSize(await fb.getvar('partition-size:system'));
    if (product !== 'MSM8916' || sysBytes !== manifest.systemBytes) {
      report.stopReason = `fastboot gate failed for undo (product=${product ?? 'unknown'}, system=${sysBytes ?? 'unknown'} vs manifest ${manifest.systemBytes})`;
      await saveReport();
      return { report, ok: false };
    }
    if (manifest.deviceIdHash) {
      const serial = await fb.getvar('serialno');
      if (!serial || deviceIdHashOf(serial) !== manifest.deviceIdHash) {
        report.stopReason = 'refusing --undo: this fastboot device does not match the Mirror this backup was taken from (serial mismatch)';
        await saveReport();
        return { report, ok: false };
      }
    }
    for (const [rel, label] of [
      [manifest.dpmdLib?.restoreSparse, 'restore-dpmd.sparse'],
      [manifest.postBoot?.restoreSparse, 'restore-post-boot.sparse'],
      [manifest.servicesJar.restoreSparse, 'restore-services.sparse'],
    ] as const) {
      if (!rel) continue;
      const err = await validateRestoreSparse(d, o.backupDir, rel, label, manifest.systemBytes);
      if (err) {
        report.stopReason = `refusing --undo: ${err}`;
        await saveReport();
        return { report, ok: false };
      }
    }
    const proceed = await d.confirm(
      `Restore original stock system blocks from ${o.backupDir} (and ${o.eraseUserdataOnUndo ? 'erase userdata' : 'keep userdata'})?`,
    );
    if (!proceed) {
      report.stopReason = 'undo cancelled by user';
      await saveReport();
      return { report, ok: false };
    }
    if (manifest.dpmdLib) {
      d.log('  flashing restore-dpmd.sparse to system...');
      await fb.flashPartition('system', `${o.backupDir}/${manifest.dpmdLib.restoreSparse}`);
      report.restoredDpmd = true;
    }
    if (manifest.postBoot) {
      d.log('  flashing restore-post-boot.sparse to system...');
      await fb.flashPartition('system', `${o.backupDir}/${manifest.postBoot.restoreSparse}`);
      report.restoredPostBoot = true;
    }
    d.log('  flashing restore-services.sparse to system...');
    await fb.flashPartition('system', `${o.backupDir}/${manifest.servicesJar.restoreSparse}`);
    report.restoredServices = true;
    if (o.eraseUserdataOnUndo) {
      d.log('  erasing userdata...');
      await fb.erasePartition('userdata');
    }
    await fb.reboot();
    report.notes.push('Restored original stock blocks to system and rebooted.');
    await saveReport();
    return { report, ok: true };
  }

  // --- Apply-ADB mode (re-enable saved surgical services.jar hook from a previous backup dir) ---
  if (o.mode === 'apply-adb') {
    if (!o.backupDir) throw new Error('--apply-adb requires --backup-dir <dir>');
    const manifestRaw = await d.readHostFile(`${o.backupDir}/manifest.json`);
    const manifest = JSON.parse(td.decode(manifestRaw)) as BackupManifest;
    const patchRel = manifest.servicesJar.patchSparse ?? '../patch-services.sparse';
    const patchSparsePath = `${o.backupDir}/${patchRel}`;
    // Verify patch sparse exists & is readable before touching fastboot
    await d.readHostFile(patchSparsePath);
    const fb = await d.waitForFastboot(o.waitSeconds * 1000);
    if (!fb) {
      report.stopReason = 'no Mirror detected in fastboot mode for --apply-adb';
      await saveReport();
      return { report, ok: false };
    }
    const product = await fb.getvar('product');
    const sysBytes = parseHexOrDecSize(await fb.getvar('partition-size:system'));
    if (product !== 'MSM8916' || sysBytes !== manifest.systemBytes) {
      report.stopReason = `fastboot gate failed for --apply-adb (product=${product ?? 'unknown'}, system=${sysBytes ?? 'unknown'} vs manifest ${manifest.systemBytes})`;
      await saveReport();
      return { report, ok: false };
    }
    if (manifest.deviceIdHash) {
      const serial = await fb.getvar('serialno');
      if (!serial || deviceIdHashOf(serial) !== manifest.deviceIdHash) {
        report.stopReason = 'refusing --apply-adb: this fastboot device does not match the Mirror this backup was taken from (serial mismatch)';
        await saveReport();
        return { report, ok: false };
      }
    }
    const proceed = await d.confirm(
      `Re-apply surgical USB ADB services.jar hook (${manifest.servicesJar.fingerprint ?? 'saved host key'}) from ${o.backupDir}? (SELinux stays Enforcing; reversible anytime with --undo)`,
    );
    if (!proceed) {
      report.stopReason = 'apply-adb cancelled by user';
      await saveReport();
      return { report, ok: false };
    }
    d.log('  flashing saved patch-services.sparse to system...');
    await fb.flashPartition('system', patchSparsePath);
    await fb.reboot();
    report.notes.push('Re-applied surgical USB ADB services.jar hook to system and rebooted (SELinux stays Enforcing).');
    await saveReport();
    return { report, ok: true };
  }

  // Shared Step 4 runner: flashes patch-dpmd.sparse (and optional restore-post-boot.sparse),
  // polls for Permissive + readable block nodes, streams raw system + boot, scrubs system back
  // to untouched stock, and flashes restore-dpmd.sparse (plus restore-services.sparse if asked).
  const executeDpmdBackup = async (args: {
    adbSession: SurgicalAdbSession;
    targetSystemBytes: number;
    origServices: Uint8Array;
    servicesExtents: readonly FiemapExtent[];
    restoreServicesPath: string;
    origDpmd: Uint8Array;
    dpmdExtents: readonly FiemapExtent[];
    patchDpmdPath: string;
    restoreDpmdPath: string;
    preRestorePostBootPath?: string;
    verifyFastbootGate?: boolean;
  }): Promise<{ report: SurgicalAdbReport; ok: boolean }> => {
    const preRestoreNote = args.preRestorePostBootPath ? ' (and restore original init.qcom.post_boot.sh first)' : '';
    const okBackup = await d.confirm(
      `Step 4/4: Surgically patch ${STOCK_DPMD_LIB_PATH}${preRestoreNote} so dpmd loads the backup module and makes mmcblk0p22 and mmcblk0p19 readable for one boot, stream raw system + boot, then restore the library?`,
    );
    if (!okBackup) {
      report.notes.push('Step 4 (raw system/boot backup) skipped by user.');
      await saveReport();
      return { report, ok: true };
    }

    await args.adbSession.rebootBootloader();
    const fb2 = await d.waitForFastboot(120_000);
    if (!fb2) {
      report.stopReason = 'Mirror did not enter fastboot for Step 4 backup hook';
      await saveReport();
      return { report, ok: false };
    }
    if (args.verifyFastbootGate) {
      const product = await fb2.getvar('product');
      const sysBytes = parseHexOrDecSize(await fb2.getvar('partition-size:system'));
      report.systemPartitionBytes = sysBytes;
      if (product !== 'MSM8916' || sysBytes !== args.targetSystemBytes) {
        report.stopReason = `fastboot gate failed for backup-only (product=${product ?? 'unknown'}, system=${sysBytes ?? 'unknown'} vs manifest ${args.targetSystemBytes})`;
        await saveReport();
        return { report, ok: false };
      }
    }
    report.bootPartitionBytes = parseHexOrDecSize(await fb2.getvar('partition-size:boot'));
    if (args.preRestorePostBootPath) {
      d.log('  flashing restore-post-boot.sparse to clear any earlier post_boot patch...');
      await fb2.flashPartition('system', args.preRestorePostBootPath);
      report.restoredPostBoot = true;
    }
    d.log('  flashing surgical patch-dpmd.sparse (libdpmframework.so blocks only)...');
    await fb2.flashPartition('system', args.patchDpmdPath);
    await fb2.reboot();

    const waitUntil2 = mono() + Math.max(60, o.waitSeconds) * 1000;
    let backupAdb: SurgicalAdbSession | undefined;
    while (mono() < waitUntil2) {
      const rem = Math.max(1000, Math.min(10_000, waitUntil2 - mono()));
      const st = await d.checkAdb(rem);
      if (st.usb === 'device' && st.session) {
        backupAdb = st.session;
        break;
      }
      await d.sleep(3000);
    }
    if (!backupAdb) {
      report.stopReason = `USB ADB did not return after Step 4 hook (restore the library with: fastboot flash system ${args.restoreDpmdPath})`;
      await saveReport();
      return { report, ok: false };
    }

    // dpmd's constructor runs a few seconds after boot, before USB ADB is up. The chmod lives in
    // devtmpfs until the next reboot, so once adb returns it is enough to see Permissive plus both
    // block nodes readable. Give that 45 s.
    const hookDeadline = mono() + 45_000;
    let enf = '';
    let blockReadable = false;
    while (mono() < hookDeadline) {
      const bc = (await backupAdb.shell('getprop sys.boot_completed')).out.trim();
      enf = (await backupAdb.shell('getenforce')).out.trim();
      if (enf.toLowerCase() === 'permissive') {
        const rchk = await backupAdb.shell('[ -r /dev/block/mmcblk0p22 ] && [ -r /dev/block/mmcblk0p19 ]');
        if (rchk.code === 0) {
          blockReadable = true;
          break;
        }
      }
      if (bc === '1' && enf.toLowerCase() === 'permissive') {
        await d.sleep(1000);
      } else {
        await d.sleep(2000);
      }
    }
    report.notes.push(`Step 4 boot getenforce=${enf || 'unknown'} blockReadable=${blockReadable}`);
    if (enf.toLowerCase() !== 'permissive' || !blockReadable) {
      try {
        const mods = (await backupAdb.shell('lsmod')).out.trim();
        if (mods) {
          d.log(`  lsmod:\n${mods}`);
          report.notes.push(`Step 4 lsmod: ${mods.replace(/\r?\n/g, ' | ')}`);
        }
        const dm = (await backupAdb.shell("dmesg | grep -iE 'selinux_permissive|module|kallsyms|dpm|avc' | tail -n 30")).out.trim();
        if (dm) {
          d.log(`  dmesg (filtered):\n${dm}`);
          report.notes.push(`Step 4 dmesg:\n${dm}`);
        }
      } catch {
        // best-effort diagnostics only
      }
      report.stopReason = `backup module did not switch SELinux to Permissive + readable block nodes (getenforce=${enf || 'unknown'}, readable=${blockReadable}); restore the library via fastboot flash system ${args.restoreDpmdPath}`;
      await saveReport();
      return { report, ok: false };
    }

    let sysDump: { bytes: number; sha256: string } | undefined;
    let bootDump: { bytes: number; sha256: string } | undefined;
    let dumpError: string | undefined;
    try {
      d.log('  streaming raw system (/dev/block/mmcblk0p22, ~1.2 GiB) over USB ADB...');
      sysDump = await backupAdb.streamCommandToFile(
        'dd if=/dev/block/mmcblk0p22 bs=1048576 2>/dev/null',
        `${o.outDir}/backup/system-stock.raw.img`,
      );
      report.backupSystemPath = `${o.outDir}/backup/system-stock.raw.img`;
      report.backupSystemBytes = sysDump.bytes;
      report.backupSystemSha256 = sysDump.sha256;
      if (sysDump.bytes === args.targetSystemBytes && d.restoreExtentsInRawImage) {
        d.log('  scrubbing temporary services.jar + libdpmframework.so patches out of system-stock.raw.img -> pure untouched stock...');
        report.backupSystemSha256 = await d.restoreExtentsInRawImage(report.backupSystemPath, [
          { extents: args.servicesExtents, origData: args.origServices },
          { extents: args.dpmdExtents, origData: args.origDpmd },
        ]);
        report.notes.push('Restored original services.jar and libdpmframework.so blocks inside system-stock.raw.img so the saved image is pure untouched stock.');
      }

      d.log('  streaming raw boot (/dev/block/mmcblk0p19, 32 MiB) over USB ADB...');
      bootDump = await backupAdb.streamCommandToFile(
        'dd if=/dev/block/mmcblk0p19 bs=1048576 2>/dev/null',
        `${o.outDir}/backup/boot-stock.raw.img`,
      );
      report.backupBootPath = `${o.outDir}/backup/boot-stock.raw.img`;
      report.backupBootBytes = bootDump.bytes;
      report.backupBootSha256 = bootDump.sha256;
    } catch (err) {
      dumpError = err instanceof Error ? err.message : String(err);
      report.notes.push(`raw backup stream failed: ${dumpError}; attempting to remove the temporary backup hook anyway.`);
    }

    // Always try to leave the device out of the permissive backup hook state, whether or not the
    // dumps above succeeded — a stalled or errored dump must not strand dpmd's Permissive/readable
    // backup hook on the device.
    await backupAdb.rebootBootloader();
    const fb3 = await d.waitForFastboot(120_000);
    let cleanedUp = false;
    if (fb3) {
      d.log('  restoring original libdpmframework.so blocks...');
      await fb3.flashPartition('system', args.restoreDpmdPath);
      report.restoredDpmd = true;
      cleanedUp = true;
      if (dumpError) {
        // Leave the device fully stock rather than mid-way through a failed backup attempt.
        d.log('  backup failed earlier: also restoring original services.jar blocks...');
        await fb3.flashPartition('system', args.restoreServicesPath);
        report.restoredServices = true;
      } else if (o.restoreAllAfterBackup) {
        d.log('  --restore-all: restoring original services.jar blocks...');
        await fb3.flashPartition('system', args.restoreServicesPath);
        report.restoredServices = true;
      } else {
        report.restoredServices = false;
        d.log(`  keeping authorized USB ADB services.jar hook active (SELinux Enforcing). Revert anytime with: --undo --backup-dir ${o.outDir}/backup`);
        report.notes.push('Kept surgical USB ADB services.jar hook active (SELinux Enforcing); libdpmframework.so restored to stock.');
      }
      await fb3.reboot();
    } else {
      report.restoredDpmd = false;
      report.notes.push(`Manual restore needed: fastboot flash system ${args.restoreDpmdPath}`);
    }

    if (dumpError) {
      report.stopReason = `raw backup failed: ${dumpError}${cleanedUp ? ' (the temporary backup hook was removed)' : ' — MANUAL RESTORE NEEDED, the backup hook may still be active'}`;
      await saveReport();
      return { report, ok: false };
    }
    if (!cleanedUp) {
      report.stopReason = `backup completed but the Mirror did not return to fastboot to remove the temporary hook — MANUAL RESTORE NEEDED: fastboot flash system ${args.restoreDpmdPath}`;
      await saveReport();
      return { report, ok: false };
    }
    if (!sysDump || sysDump.bytes !== args.targetSystemBytes) {
      report.stopReason = `raw system dump size ${sysDump?.bytes ?? 'unknown'} !== expected ${args.targetSystemBytes}`;
      await saveReport();
      return { report, ok: false };
    }
    if (report.bootPartitionBytes !== undefined && (!bootDump || bootDump.bytes !== report.bootPartitionBytes)) {
      report.stopReason = `raw boot dump size ${bootDump?.bytes ?? 'unknown'} !== expected ${report.bootPartitionBytes}`;
      await saveReport();
      return { report, ok: false };
    }

    await saveReport();
    return { report, ok: true };
  };

  // --- Backup-only mode (USB-only Step 4 using existing authorized USB ADB + saved backup-dir) ---
  if (o.mode === 'backup-only') {
    if (!o.backupDir) throw new Error('--backup-only requires --backup-dir <dir> (from a prior Phase 2 run)');

    const { bytes: backupKoBytes, sourceNote } = await loadBackupModule(o, d);
    const koInfo = validateKernelModuleElf(backupKoBytes);
    d.log(`  backup module: ${sourceNote}; ${koInfo.arch}, vermagic="${koInfo.vermagic}"${koInfo.moduleLayoutCrc ? `, module_layout=${koInfo.moduleLayoutCrc}` : ''}`);
    report.notes.push(`Backup module: ${sourceNote} (${koInfo.arch}, vermagic=${koInfo.vermagic}${koInfo.moduleLayoutCrc ? `, module_layout=${koInfo.moduleLayoutCrc}` : ''}).`);

    const manifestRaw = await d.readHostFile(`${o.backupDir}/manifest.json`);
    const prevManifest = JSON.parse(td.decode(manifestRaw)) as BackupManifest;
    const origServices = await d.readHostFile(`${o.backupDir}/${prevManifest.servicesJar.origFile}`);
    if (origServices.length !== prevManifest.servicesJar.size) {
      throw new Error(`backup services.jar.orig size ${origServices.length} !== manifest ${prevManifest.servicesJar.size}`);
    }
    const targetSystemBytes = prevManifest.systemBytes;
    report.servicesOriginalSha256 = prevManifest.servicesJar.originalSha256;
    report.servicesFiemap = {
      path: prevManifest.servicesJar.path,
      size: prevManifest.servicesJar.size,
      extents: prevManifest.servicesJar.extents,
    };

    // Wait briefly for authorized USB ADB (no Wi-Fi swap required)
    d.log('Step 1/2: Connecting to Mirror over USB ADB (no Wi-Fi SoftAP needed)...');
    const adbDeadline = mono() + Math.min(30, o.waitSeconds) * 1000;
    let adbSession: SurgicalAdbSession | undefined;
    while (mono() < adbDeadline) {
      const st = await d.checkAdb(5_000);
      if (st.usb === 'device' && st.session) {
        adbSession = st.session;
        break;
      }
      await d.sleep(2000);
    }
    if (!adbSession) {
      report.adbAuthorized = false;
      report.stopReason = '--backup-only requires an authorized USB ADB connection (connect micro-USB first)';
      await saveReport();
      return { report, ok: false };
    }

    report.adbAuthorized = true;
    report.adbId = (await adbSession.shell('id')).out.trim();
    report.adbEnforce = (await adbSession.shell('getenforce')).out.trim();
    report.adbMirrorVersion = (await adbSession.shell('getprop ro.build.version.mirror')).out.trim();
    d.log(`  connected over USB ADB: id=${report.adbId}, getenforce=${report.adbEnforce}, mirror=${report.adbMirrorVersion}`);

    // Tunnel the installed stock probe app over USB via `adb forward` so FIEMAP + file reads need no Wi-Fi.
    d.log(`  forwarding tcp:${o.probeHttpPort} over USB ADB to probe app...`);
    await adbSession.forwardTcp?.(o.probeHttpPort, o.probeHttpPort);
    let origDpmd: Uint8Array | undefined;
    let dpmdReadError: string | undefined;
    try {
      const http = d.connectHttp('127.0.0.1', o.probeHttpPort);
      let status = await http.status(5_000);
      if (!status || !status.ok) {
        d.log('  probe app not yet listening; starting ProbeServerService via broadcast...');
        await adbSession.shell('am broadcast -n dev.glassworks.stockprobe/.BootReceiver');
        await d.sleep(2000);
        status = await http.status(8_000);
      }
      if (!status || !status.ok) {
        dpmdReadError = `HTTP probe app did not answer over USB adb forward (127.0.0.1:${o.probeHttpPort})`;
      } else if (!status.fiemap) {
        dpmdReadError = 'HTTP probe app does not have libfiemap.so loaded (fiemap=false)';
      } else {
        report.dpmdFiemap = await http.fiemap(STOCK_DPMD_LIB_PATH);
        const libRead = await http.readFile(STOCK_DPMD_LIB_PATH);
        if (libRead.status !== 'ok') {
          dpmdReadError = `could not read ${STOCK_DPMD_LIB_PATH} (${libRead.status}); dpmd links the ELF64 copy under vendor/lib64`;
        } else if (report.dpmdFiemap.error || !report.dpmdFiemap.extents?.length) {
          dpmdReadError = `FIEMAP failed for ${STOCK_DPMD_LIB_PATH}: ${report.dpmdFiemap.error ?? 'no extents'}`;
        } else if (report.dpmdFiemap.size !== undefined && report.dpmdFiemap.size !== libRead.data.length) {
          dpmdReadError = `size mismatch for ${STOCK_DPMD_LIB_PATH}: FIEMAP=${report.dpmdFiemap.size} vs read=${libRead.data.length}`;
        } else {
          origDpmd = libRead.data;
          report.dpmdOriginalSha256 = sha256Hex(origDpmd);
        }
      }
    } finally {
      await adbSession.removeForward?.(o.probeHttpPort);
    }

    if (dpmdReadError || !origDpmd || !report.dpmdFiemap?.extents?.length) {
      report.stopReason = dpmdReadError ?? `could not read ${STOCK_DPMD_LIB_PATH} over USB adb forward`;
      await saveReport();
      return { report, ok: false };
    }

    let built: ReturnType<typeof buildDpmdHook>;
    try {
      built = buildDpmdHook(origDpmd, backupKoBytes);
    } catch (err) {
      report.stopReason = err instanceof Error ? err.message : String(err);
      await saveReport();
      return { report, ok: false };
    }

    // Write complete self-contained undo files in outDir/backup BEFORE offering any flash.
    await d.writeFile('backup/services.jar.orig', origServices);
    const restoreServicesPath = await d.writeFile(
      'backup/restore-services.sparse',
      buildSurgicalSparseImage(targetSystemBytes, prevManifest.servicesJar.extents, origServices),
    );
    await d.writeFile('backup/libdpmframework.so.orig', origDpmd);
    const restoreDpmdPath = await d.writeFile(
      'backup/restore-dpmd.sparse',
      buildSurgicalSparseImage(targetSystemBytes, report.dpmdFiemap.extents, origDpmd),
    );
    const patchDpmdSparse = buildSurgicalSparseImage(targetSystemBytes, report.dpmdFiemap.extents, built.bytes);
    const patchDpmdPath = await d.writeFile('patch-dpmd.sparse', patchDpmdSparse);
    await d.writeFile('backup/patch-dpmd.sparse', patchDpmdSparse);

    let preRestorePostBootPath: string | undefined;
    if (prevManifest.postBoot) {
      try {
        const postBootRestoreBytes = await d.readHostFile(`${o.backupDir}/${prevManifest.postBoot.restoreSparse}`);
        preRestorePostBootPath = await d.writeFile('backup/restore-post-boot.sparse', postBootRestoreBytes);
      } catch {
        // restore-post-boot.sparse absent; skip
      }
    }

    report.dpmdPatchedSha256 = sha256Hex(built.bytes);
    report.dpmdEntryVaddr = built.plan.entryVaddr;
    report.dpmdChainedVaddr = built.plan.chainedVaddr;
    const newManifest: BackupManifest = {
      ...prevManifest,
      createdAt: nowIso(),
      dpmdLib: {
        path: STOCK_DPMD_LIB_PATH,
        size: origDpmd.length,
        originalSha256: report.dpmdOriginalSha256!,
        patchedSha256: report.dpmdPatchedSha256,
        extents: report.dpmdFiemap.extents,
        origFile: 'libdpmframework.so.orig',
        restoreSparse: 'restore-dpmd.sparse',
        patchSparse: 'patch-dpmd.sparse',
      },
    };
    await d.writeFile('backup/manifest.json', `${JSON.stringify(newManifest, null, 2)}\n`);
    report.notes.push(
      `dpmd hook: .init_array[0] -> 0x${built.plan.entryVaddr.toString(16)}, chains to 0x${built.plan.chainedVaddr.toString(16)}, payload ${built.plan.payloadBytes} B embedding ${built.plan.moduleBytes} B.`,
    );
    await saveReport();
    d.log(`  saved byte-exact undo image: ${restoreDpmdPath}`);

    return executeDpmdBackup({
      adbSession,
      targetSystemBytes,
      origServices,
      servicesExtents: prevManifest.servicesJar.extents,
      restoreServicesPath,
      origDpmd,
      dpmdExtents: report.dpmdFiemap.extents,
      patchDpmdPath,
      restoreDpmdPath,
      preRestorePostBootPath,
      verifyFastbootGate: true,
    });
  }

  // Validate ADB key up front when patching
  let cleanAdbKey: string | undefined;
  if (o.mode !== 'preflight-only') {
    if (!o.adbKey) throw new Error('missing --adb-key (needed to authorize your host ADB key)');
    report.adbKeyFingerprint = validateStockAdbKey(o.adbKey);
    cleanAdbKey = o.adbKey.trim();
  }

  // Resolve the Step 4 backup module up front when a raw backup is wanted (--backup, or a local
  // --backup-module override). By default it's the pinned device-bins artifact, fetched and hash-checked.
  let backupKoBytes: Uint8Array | undefined;
  if (o.wantBackup || o.backupModulePath) {
    const loaded = await loadBackupModule(o, d);
    backupKoBytes = loaded.bytes;
    const info = validateKernelModuleElf(backupKoBytes);
    d.log(`  backup module: ${loaded.sourceNote}; ${info.arch}, vermagic="${info.vermagic}"${info.moduleLayoutCrc ? `, module_layout=${info.moduleLayoutCrc}` : ''}`);
    report.notes.push(`Backup module: ${loaded.sourceNote} (${info.arch}, vermagic=${info.vermagic}${info.moduleLayoutCrc ? `, module_layout=${info.moduleLayoutCrc}` : ''}).`);
  }

  // --- Step 1: Read-only preflight over SoftAP HTTP ---
  d.log(`Step 1/4: Connecting to stock probe app at http://${o.probeHost}:${o.probeHttpPort} (read-only)...`);
  const http = d.connectHttp(o.probeHost, o.probeHttpPort);
  const status = await http.status(10_000);
  if (!status || !status.ok) {
    report.stopReason = `HTTP probe app did not answer at http://${o.probeHost}:${o.probeHttpPort}/status`;
    await saveReport();
    return { report, ok: false };
  }
  if (!status.fiemap) {
    report.stopReason = 'HTTP probe app does not have libfiemap.so loaded (fiemap=false)';
    await saveReport();
    return { report, ok: false };
  }

  // A denied listing is NOT proof of an empty (and therefore services.odex-free) directory — only a
  // successfully read listing is. Stop before any write if /system/framework or an oat dir refuses.
  const listOrStop = async (dir: string): Promise<string[] | undefined> => {
    if (!http.listRaw) return http.list(dir); // no raw-status transport (e.g. a test double): best-effort
    const r = await http.listRaw(dir);
    if (r.status === 403) return undefined;
    return r.entries;
  };
  const fwListing = await listOrStop('/system/framework');
  if (fwListing === undefined) {
    report.stopReason = 'listing /system/framework was denied — cannot rule out services.odex. Nothing was written.';
    await saveReport();
    return { report, ok: false };
  }
  report.frameworkListing = fwListing;
  const oatDirs = ['/system/framework/oat', '/system/framework/oat/arm64', '/system/framework/arm64'];
  const oatLines: string[] = [];
  for (const dir of oatDirs) {
    const entries = await listOrStop(dir);
    if (entries === undefined) {
      report.stopReason = `listing ${dir} was denied — cannot rule out services.odex. Nothing was written.`;
      await saveReport();
      return { report, ok: false };
    }
    for (const line of entries) oatLines.push(line);
  }
  report.oatListing = oatLines;
  report.servicesOdexFound = detectServicesOdex(report.frameworkListing, oatLines);
  report.appListing = await http.list('/system/app');
  report.privAppListing = await http.list('/system/priv-app');
  const probedDeviceId = await probedDeviceIdHash(http);

  report.servicesFiemap = await http.fiemap(STOCK_SERVICES_JAR_PATH);
  report.postBootFiemap = await http.fiemap(STOCK_POST_BOOT_PATH);

  const servicesRead = await http.readFile(STOCK_SERVICES_JAR_PATH);
  if (servicesRead.status !== 'ok') {
    report.stopReason = `could not read ${STOCK_SERVICES_JAR_PATH} (${servicesRead.status})`;
    await saveReport();
    return { report, ok: false };
  }
  const origServices = servicesRead.data;
  report.servicesOriginalSha256 = sha256Hex(origServices);

  if (report.servicesFiemap.error || !report.servicesFiemap.extents?.length) {
    report.stopReason = `FIEMAP failed for ${STOCK_SERVICES_JAR_PATH}: ${report.servicesFiemap.error ?? 'no extents'}`;
    await saveReport();
    return { report, ok: false };
  }
  if (report.servicesFiemap.size !== undefined && report.servicesFiemap.size !== origServices.length) {
    report.stopReason = `size mismatch for ${STOCK_SERVICES_JAR_PATH}: FIEMAP=${report.servicesFiemap.size} vs read=${origServices.length}`;
    await saveReport();
    return { report, ok: false };
  }

  let origPostBoot: Uint8Array | undefined;
  const postBootRead = await http.readFile(STOCK_POST_BOOT_PATH);
  if (postBootRead.status === 'ok') {
    origPostBoot = postBootRead.data;
    report.postBootOriginalSha256 = sha256Hex(origPostBoot);
  }

  // The backup module is embedded in libdpmframework.so. Pull that file and refuse a library the
  // planner cannot hook before any image is offered for flashing.
  let origDpmd: Uint8Array | undefined;
  let dpmdReadError: string | undefined;
  if (backupKoBytes) {
    report.dpmdFiemap = await http.fiemap(STOCK_DPMD_LIB_PATH);
    const libRead = await http.readFile(STOCK_DPMD_LIB_PATH);
    if (libRead.status !== 'ok') {
      dpmdReadError = `could not read ${STOCK_DPMD_LIB_PATH} (${libRead.status}); dpmd links the ELF64 copy under vendor/lib64`;
    } else if (report.dpmdFiemap.error || !report.dpmdFiemap.extents?.length) {
      dpmdReadError = `FIEMAP failed for ${STOCK_DPMD_LIB_PATH}: ${report.dpmdFiemap.error ?? 'no extents'}`;
    } else if (report.dpmdFiemap.size !== undefined && report.dpmdFiemap.size !== libRead.data.length) {
      dpmdReadError = `size mismatch for ${STOCK_DPMD_LIB_PATH}: FIEMAP=${report.dpmdFiemap.size} vs read=${libRead.data.length}`;
    } else {
      origDpmd = libRead.data;
      report.dpmdOriginalSha256 = sha256Hex(origDpmd);
      try {
        planDpmdHook(origDpmd, backupKoBytes);
      } catch (err) {
        dpmdReadError = err instanceof Error ? err.message : String(err);
      }
    }
  }

  // Save byte-exact originals + restore sparse images immediately (before any flash is ever offered).
  let targetSystemBytes = STOCK_SYSTEM_PARTITION_BYTES;
  const defaultUserdataBytes = USERDATA_BYTES;
  await d.writeFile('backup/services.jar.orig', origServices);
  let restoreServicesSparse = buildSurgicalSparseImage(
    targetSystemBytes,
    report.servicesFiemap.extents,
    origServices,
  );
  const restoreServicesPath = await d.writeFile('backup/restore-services.sparse', restoreServicesSparse);

  let restorePostBootPath: string | undefined;
  if (origPostBoot && report.postBootFiemap && !report.postBootFiemap.error && report.postBootFiemap.extents?.length) {
    await d.writeFile('backup/init.qcom.post_boot.sh.orig', origPostBoot);
    const restorePostBootSparse = buildSurgicalSparseImage(
      targetSystemBytes,
      report.postBootFiemap.extents,
      origPostBoot,
    );
    restorePostBootPath = await d.writeFile('backup/restore-post-boot.sparse', restorePostBootSparse);
  }

  let restoreDpmdPath: string | undefined;
  if (origDpmd && report.dpmdFiemap?.extents?.length) {
    await d.writeFile('backup/libdpmframework.so.orig', origDpmd);
    const restoreDpmdSparse = buildSurgicalSparseImage(
      targetSystemBytes,
      report.dpmdFiemap.extents,
      origDpmd,
    );
    restoreDpmdPath = await d.writeFile('backup/restore-dpmd.sparse', restoreDpmdSparse);
  }

  const manifest: BackupManifest = {
    version: 1,
    createdAt: nowIso(),
    systemBytes: targetSystemBytes,
    userdataBytes: defaultUserdataBytes,
    ...(probedDeviceId ? { deviceIdHash: probedDeviceId } : {}),
    servicesJar: {
      path: STOCK_SERVICES_JAR_PATH,
      size: origServices.length,
      originalSha256: report.servicesOriginalSha256,
      extents: report.servicesFiemap.extents,
      origFile: 'services.jar.orig',
      restoreSparse: 'restore-services.sparse',
    },
    ...(origPostBoot && restorePostBootPath && report.postBootFiemap?.extents
      ? {
          postBoot: {
            path: STOCK_POST_BOOT_PATH,
            size: origPostBoot.length,
            originalSha256: report.postBootOriginalSha256!,
            extents: report.postBootFiemap.extents,
            origFile: 'init.qcom.post_boot.sh.orig',
            restoreSparse: 'restore-post-boot.sparse',
          },
        }
      : {}),
    ...(origDpmd && restoreDpmdPath && report.dpmdFiemap?.extents
      ? {
          dpmdLib: {
            path: STOCK_DPMD_LIB_PATH,
            size: origDpmd.length,
            originalSha256: report.dpmdOriginalSha256!,
            extents: report.dpmdFiemap.extents,
            origFile: 'libdpmframework.so.orig',
            restoreSparse: 'restore-dpmd.sparse',
          },
        }
      : {}),
  };
  await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
  await saveReport();

  d.log(`  saved byte-exact undo image: ${restoreServicesPath}`);
  if (report.servicesOdexFound) {
    report.stopReason = 'STOPPED: services.odex detected under /system/framework — ART would ignore patched services.jar unless services.odex is handled. Nothing was written.';
    await saveReport();
    return { report, ok: false };
  }
  if (dpmdReadError) {
    report.stopReason = dpmdReadError;
    await saveReport();
    return { report, ok: false };
  }
  if (o.mode === 'preflight-only') {
    d.log('Preflight complete (--preflight-only). No partition was written.');
    return { report, ok: true };
  }

  // --- Step 2: Build exact-size surgical patches locally ---
  d.log('Step 2/4: Patching stock services.jar (exact original byte size) and building sparse images locally...');
  const patchedJar = await patchStockServicesJar(origServices, cleanAdbKey!);
  report.servicesPatchedSha256 = patchedJar.patchedSha256;
  report.servicesPadBytes = patchedJar.padBytes;
  manifest.servicesJar.patchedSha256 = patchedJar.patchedSha256;
  manifest.servicesJar.fingerprint = patchedJar.fingerprint;
  manifest.servicesJar.patchSparse = 'patch-services.sparse';
  await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);

  let patchServicesSparse = buildSurgicalSparseImage(
    targetSystemBytes,
    report.servicesFiemap.extents,
    patchedJar.patched,
  );
  const patchServicesPath = await d.writeFile('patch-services.sparse', patchServicesSparse);
  await d.writeFile('backup/patch-services.sparse', patchServicesSparse);

  let patchedDpmd: Uint8Array | undefined;
  let patchDpmdPath: string | undefined;
  if (backupKoBytes) {
    if (!origDpmd || !report.dpmdFiemap?.extents?.length || !manifest.dpmdLib) {
      report.stopReason = `cannot build the dpmd hook: ${STOCK_DPMD_LIB_PATH} or its FIEMAP extents were unavailable`;
      await saveReport();
      return { report, ok: false };
    }
    let built: ReturnType<typeof buildDpmdHook>;
    try {
      built = buildDpmdHook(origDpmd, backupKoBytes);
    } catch (err) {
      report.stopReason = err instanceof Error ? err.message : String(err);
      await saveReport();
      return { report, ok: false };
    }
    patchedDpmd = built.bytes;
    report.dpmdPatchedSha256 = sha256Hex(patchedDpmd);
    report.dpmdEntryVaddr = built.plan.entryVaddr;
    report.dpmdChainedVaddr = built.plan.chainedVaddr;
    manifest.dpmdLib.patchedSha256 = report.dpmdPatchedSha256;
    manifest.dpmdLib.patchSparse = 'patch-dpmd.sparse';
    await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
    const patchDpmdSparse = buildSurgicalSparseImage(
      targetSystemBytes,
      report.dpmdFiemap.extents,
      patchedDpmd,
    );
    patchDpmdPath = await d.writeFile('patch-dpmd.sparse', patchDpmdSparse);
    await d.writeFile('backup/patch-dpmd.sparse', patchDpmdSparse);
    report.notes.push(
      `dpmd hook: .init_array[0] -> 0x${built.plan.entryVaddr.toString(16)}, chains to 0x${built.plan.chainedVaddr.toString(16)}, payload ${built.plan.payloadBytes} B embedding ${built.plan.moduleBytes} B.`,
    );
  }

  // Build fresh userdata (retaining the probe APK as a SoftAP safety net while wiping dalvik-cache)
  const probeApp = await d.loadProbeApp();
  if (!probeApp) {
    const proceedNoProbe = await d.confirm(
      'No SoftAP probe APK was found/built — the fresh userdata about to be flashed would have no recovery network if authorized USB ADB never comes up. Continue anyway?',
    );
    if (!proceedNoProbe) {
      report.stopReason = 'build cancelled: no probe APK available as a userdata recovery safety net (and no --yes override)';
      await saveReport();
      return { report, ok: false };
    }
    report.notes.push('Proceeding without the SoftAP probe APK safety net in userdata (operator confirmed).');
  }
  const disk = new Disk(new MemoryStore(), SPARSE_BLOCK_SIZE, defaultUserdataBytes / SPARSE_BLOCK_SIZE, { zeroed: true });
  await buildStockProbeUserdata(disk, {
    adbKey: cleanAdbKey!,
    usbConfig: 'adb',
    // Surgical flow uses USB ADB only; don't plant a persistent, key-authenticated-but-still-exposed
    // TCP ADB listener on whatever network the Mirror joins (the stock-probe CLI keeps its own default).
    ...(probeApp ? { probeApp } : {}),
  });
  const userdataChunks: Uint8Array[] = [];
  await writeSparse(disk, (chunk) => { userdataChunks.push(chunk.slice()); });
  const userdataSparse = new Uint8Array(userdataChunks.reduce((n, c) => n + c.length, 0));
  let uAt = 0;
  for (const c of userdataChunks) { userdataSparse.set(c, uAt); uAt += c.length; }
  const userdataPath = await d.writeFile('userdata-adb.sparse', userdataSparse);

  await saveReport();
  d.log(`  built ${patchServicesPath} (${patchServicesSparse.length} B) and ${userdataPath} (${userdataSparse.length} B)`);
  if (o.mode === 'build-only') {
    d.log('Local build complete (--build-only). No partition was written.');
    return { report, ok: true };
  }

  // --- Step 3: Flash surgical services.jar + fresh userdata, verify authorized USB ADB ---
  d.log('Step 3/4: Put the Mirror in fastboot mode (hold VOL- while powering on for ~5 s), then connect micro-USB...');
  const fb = await d.waitForFastboot(o.waitSeconds * 1000);
  if (!fb) {
    report.stopReason = 'timed out waiting for Mirror in fastboot mode';
    await saveReport();
    return { report, ok: false };
  }
  const product = await fb.getvar('product');
  const sysBytes = parseHexOrDecSize(await fb.getvar('partition-size:system'));
  const udBytes = parseHexOrDecSize(await fb.getvar('partition-size:userdata'));
  report.systemPartitionBytes = sysBytes;
  report.userdataPartitionBytes = udBytes;
  if (product !== 'MSM8916') {
    report.stopReason = `refusing to flash non-MSM8916 device (product=${product ?? 'unknown'})`;
    await saveReport();
    return { report, ok: false };
  }
  if (!sysBytes || sysBytes % SPARSE_BLOCK_SIZE !== 0 || sysBytes < 512 * 1024 * 1024 || sysBytes > 4 * 1024 * 1024 * 1024) {
    report.stopReason = `invalid fastboot partition-size:system (${sysBytes ?? 'unknown'})`;
    await saveReport();
    return { report, ok: false };
  }
  if (udBytes !== defaultUserdataBytes) {
    report.stopReason = `partition size mismatch (userdata=${udBytes ?? 'unknown'} expected ${defaultUserdataBytes})`;
    await saveReport();
    return { report, ok: false };
  }
  // Bind this fastboot target to the device Step 1 actually probed over SoftAP HTTP, so a different
  // (but compatible) Mirror in fastboot cannot receive writes computed from another unit's FIEMAP
  // offsets. Falls back to recording the fastboot-side hash when /proc/cmdline wasn't readable.
  {
    const fbSerial = await fb.getvar('serialno');
    const fbIdHash = fbSerial ? deviceIdHashOf(fbSerial) : undefined;
    if (manifest.deviceIdHash && fbIdHash && manifest.deviceIdHash !== fbIdHash) {
      report.stopReason = 'refusing to flash: this fastboot device does not match the Mirror probed over SoftAP HTTP in Step 1 (serial mismatch)';
      await saveReport();
      return { report, ok: false };
    }
    if (!manifest.deviceIdHash && fbIdHash) {
      manifest.deviceIdHash = fbIdHash;
      await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
    }
  }
  // If fastboot reports a different valid system partition size than the default 0x4cccd000,
  // rebuild every surgical sparse image to exact `sysBytes` before offering to flash.
  if (sysBytes !== targetSystemBytes) {
    d.log(`  rebuilding surgical sparse images to match live partition-size:system (${sysBytes} B)...`);
    targetSystemBytes = sysBytes;
    manifest.systemBytes = sysBytes;
    restoreServicesSparse = buildSurgicalSparseImage(sysBytes, report.servicesFiemap.extents, origServices);
    await d.writeFile('backup/restore-services.sparse', restoreServicesSparse);
    patchServicesSparse = buildSurgicalSparseImage(sysBytes, report.servicesFiemap.extents, patchedJar.patched);
    await d.writeFile('patch-services.sparse', patchServicesSparse);
    await d.writeFile('backup/patch-services.sparse', patchServicesSparse);
    if (origPostBoot && report.postBootFiemap?.extents?.length) {
      await d.writeFile(
        'backup/restore-post-boot.sparse',
        buildSurgicalSparseImage(sysBytes, report.postBootFiemap.extents, origPostBoot),
      );
    }
    if (origDpmd && report.dpmdFiemap?.extents?.length) {
      await d.writeFile(
        'backup/restore-dpmd.sparse',
        buildSurgicalSparseImage(sysBytes, report.dpmdFiemap.extents, origDpmd),
      );
      if (patchedDpmd) {
        const rebuilt = buildSurgicalSparseImage(sysBytes, report.dpmdFiemap.extents, patchedDpmd);
        await d.writeFile('patch-dpmd.sparse', rebuilt);
        await d.writeFile('backup/patch-dpmd.sparse', rebuilt);
      }
    }
    await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
  }

  const okFlash = await d.confirm(
    `Flash ONLY services.jar blocks (${report.servicesFiemap.extents.map((e) => `${e.block}+${e.blocks}`).join(',')}) to system + fresh userdata? Reversible anytime via: fastboot flash system ${restoreServicesPath}`,
  );
  if (!okFlash) {
    report.stopReason = 'flash cancelled by user';
    await saveReport();
    return { report, ok: false };
  }

  d.log('  flashing surgical patch-services.sparse to system...');
  try {
    await fb.flashPartition('system', patchServicesPath);
    d.log('  flashing userdata-adb.sparse to userdata...');
    await fb.flashPartition('userdata', userdataPath);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    report.stopReason = `flash failed: ${msg}. system may now hold the patched services.jar; restore it with: fastboot flash system ${restoreServicesPath} (or run --undo --backup-dir ${o.outDir}/backup).`;
    d.log(`  ${report.stopReason}`);
    await saveReport();
    return { report, ok: false };
  }
  d.log('  rebooting Mirror (first boot takes ~4-5 min for dex2oat)...');
  await fb.reboot();

  const waitUntil = mono() + Math.max(60, o.waitSeconds) * 1000;
  let adbSession: SurgicalAdbSession | undefined;
  while (mono() < waitUntil) {
    const rem = Math.max(1000, Math.min(10_000, waitUntil - mono()));
    const st = await d.checkAdb(rem);
    if (st.usb === 'device' && st.session) {
      adbSession = st.session;
      break;
    }
    await d.sleep(3000);
  }

  if (!adbSession) {
    report.adbAuthorized = false;
    report.stopReason = `USB ADB did not become authorized within ${o.waitSeconds}s (restore anytime with: fastboot flash system ${restoreServicesPath})`;
    await saveReport();
    return { report, ok: false };
  }

  report.adbAuthorized = true;
  report.adbId = (await adbSession.shell('id')).out.trim();
  report.adbEnforce = (await adbSession.shell('getenforce')).out.trim();
  report.adbMirrorVersion = (await adbSession.shell('getprop ro.build.version.mirror')).out.trim();
  const pkgs = await adbSession.shell('pm list packages');
  const pkgLines = pkgs.out.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  report.adbPackagesCount = pkgLines.length;
  await d.writeFile('adb-packages.txt', `${pkgLines.join('\n')}\n`);
  const mem = await adbSession.shell('dumpsys meminfo');
  await d.writeFile('adb-meminfo.txt', mem.out);
  await saveReport();
  d.log(`  SUCCESS: authorized USB ADB! id=${report.adbId}, getenforce=${report.adbEnforce}, mirror=${report.adbMirrorVersion}`);

  // --- Step 4 (Optional): Full raw system + boot backup via the dpmd constructor hook ---
  if (!backupKoBytes || !patchDpmdPath || !restoreDpmdPath || !origDpmd || !patchedDpmd || !report.dpmdFiemap?.extents) {
    return { report, ok: true };
  }

  return executeDpmdBackup({
    adbSession,
    targetSystemBytes,
    origServices,
    servicesExtents: report.servicesFiemap.extents,
    restoreServicesPath,
    origDpmd,
    dpmdExtents: report.dpmdFiemap.extents,
    patchDpmdPath,
    restoreDpmdPath,
  });
}

export { DEFAULT_PROBE_HTTP_PORT };
export type { FiemapExtent };
