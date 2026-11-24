// Phase 2 stock Mirror tooling: reversible surgical sparse patching of /system/framework/services.jar
// (for authorized USB ADB on untouched stock 1.24.0 without replacing system) and optional
// /system/etc/init.qcom.post_boot.sh hooking (with an external user-supplied .ko) for full raw
// system/boot backup + automatic byte-for-byte restore.
//
// Hard rules enforced here:
// - Read first, back up first, verify sizes/hashes before any write.
// - Every surgical system patch preserves the EXACT original file byte length so ext4 inode
//   metadata (i_size, extents, directory entries) is never modified; only the target file's
//   physical 4 KiB blocks are written (DONT_CARE everywhere else).
// - Byte-for-byte restore sparse images are written BEFORE any flash is offered.
// - No GPL payloads or vendor binaries are bundled or committed (Rule 3 & Rule 4).
import { createHash } from 'node:crypto';
import { zipEntry } from '@glassworks/app-adb';
import { loadCompressor } from '@glassworks/bootpatch';
import { Disk, MemoryStore, parseSparseHeader, SPARSE_MAGIC, writeSparse } from '@glassworks/ext4';
import {
  GLASS_PANES_KEY_FINGERPRINT, replaceDexString, USERDATA_BYTES,
} from '@glassworks/lululemon-mirror-installer';
import {
  buildStockProbeUserdata, DEFAULT_PROBE_HTTP_PORT, validateStockAdbKey,
  type AdbState, type FiemapExtent, type FiemapResult, type ProbeAppPayload,
  type StockAdbSession, type StockHttpSession,
} from './stock-probe';

export const SPARSE_BLOCK_SIZE = 4096;
export const MAX_RAW_CHUNK_BLOCKS = 4096; // 16 MiB per RAW chunk
/** Exact MSM8916 Mirror `system` eMMC partition size (`mmcblk0p22`, 0x4cccd000 = 1,258,292 KiB). */
export const STOCK_SYSTEM_PARTITION_BYTES = 0x4cccd000;
const CHUNK_RAW = 0xcac1;
const CHUNK_DONT_CARE = 0xcac3;

/**
 * Single-use 71-byte log string inside stock Android 6.0.1
 * `UsbDebuggingManager.startConfirmation(String, String)`.
 * Replaced in-place via `replaceDexString` with `<47-char MD5 fingerprint><24 spaces>`.
 */
export const START_CONFIRMATION_LOG_STRING =
  'unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component ';

export const STOCK_SERVICES_JAR_PATH = '/system/framework/services.jar';
export const STOCK_POST_BOOT_PATH = '/system/etc/init.qcom.post_boot.sh';
export const REMOTE_BACKUP_KO_PATH = '/data/local/tmp/gw_backup.ko';

const sha256Hex = (b: Uint8Array): string => createHash('sha256').update(b).digest('hex');
const sha1Bytes = (b: Uint8Array): Uint8Array => new Uint8Array(createHash('sha1').update(b).digest());

function adler32(b: Uint8Array): number {
  let a = 1, s = 0;
  for (let i = 0; i < b.length; i++) {
    a = (a + b[i]!) % 65521;
    s = (s + a) % 65521;
  }
  return ((s << 16) | a) >>> 0;
}

function crc32(b: Uint8Array): number {
  let c = ~0;
  for (let i = 0; i < b.length; i++) {
    c ^= b[i]!;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  return (~c) >>> 0;
}

const td = new TextDecoder();
const te = new TextEncoder();

function readUleb128(b: Uint8Array, at: number): [number, number] {
  let v = 0, shift = 0, i = at;
  for (;;) {
    const x = b[i++];
    if (x === undefined || shift > 28) throw new Error('malformed uleb128 in classes.dex');
    v += (x & 0x7f) * (2 ** shift);
    if (x < 0x80) return [v, i];
    shift += 7;
  }
}

function readDexStrings(dex: Uint8Array): string[] {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const count = dv.getUint32(0x38, true);
  const off = dv.getUint32(0x3c, true);
  const out: string[] = new Array(count);
  for (let i = 0; i < count; i++) {
    const dataOff = dv.getUint32(off + i * 4, true);
    const [, start] = readUleb128(dex, dataOff);
    let end = start;
    while (end < dex.length && dex[end] !== 0) end++;
    out[i] = td.decode(dex.subarray(start, end));
  }
  return out;
}

function readDexTypes(dex: Uint8Array, strings: readonly string[]): string[] {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const count = dv.getUint32(0x40, true);
  const off = dv.getUint32(0x44, true);
  const out: string[] = new Array(count);
  for (let i = 0; i < count; i++) {
    const sIdx = dv.getUint32(off + i * 4, true);
    out[i] = strings[sIdx] ?? '';
  }
  return out;
}

function readDexProtos(dex: Uint8Array, types: readonly string[]): string[] {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const count = dv.getUint32(0x48, true);
  const off = dv.getUint32(0x4c, true);
  const out: string[] = new Array(count);
  for (let i = 0; i < count; i++) {
    const base = off + i * 12;
    const retType = types[dv.getUint32(base + 4, true)] ?? '';
    const paramsOff = dv.getUint32(base + 8, true);
    let params = '';
    if (paramsOff !== 0) {
      const pCount = dv.getUint32(paramsOff, true);
      for (let p = 0; p < pCount; p++) {
        params += types[dv.getUint16(paramsOff + 4 + p * 2, true)] ?? '';
      }
    }
    out[i] = `(${params})${retType}`;
  }
  return out;
}

interface DexMethodLookup {
  findMethod(classDesc: string, name: string, proto: string): number;
  findCodeItem(classDesc: string, targetMethodIdx: number): number;
}

function createDexLookup(dex: Uint8Array, strings: readonly string[]): DexMethodLookup {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const types = readDexTypes(dex, strings);
  const protos = readDexProtos(dex, types);
  const methodCount = dv.getUint32(0x58, true);
  const methodOff = dv.getUint32(0x5c, true);

  const methodMap = new Map<string, number>();
  for (let i = 0; i < methodCount; i++) {
    const base = methodOff + i * 8;
    const cls = types[dv.getUint16(base, true)] ?? '';
    const proto = protos[dv.getUint16(base + 2, true)] ?? '';
    const name = strings[dv.getUint32(base + 4, true)] ?? '';
    methodMap.set(`${cls}.${name}:${proto}`, i);
  }

  return {
    findMethod(classDesc: string, name: string, proto: string): number {
      const key = `${classDesc}.${name}:${proto}`;
      const idx = methodMap.get(key);
      if (idx === undefined) throw new Error(`classes.dex is missing required method ${key}`);
      return idx;
    },
    findCodeItem(classDesc: string, targetMethodIdx: number): number {
      const classCount = dv.getUint32(0x60, true);
      const classOff = dv.getUint32(0x64, true);
      for (let i = 0; i < classCount; i++) {
        const base = classOff + i * 32;
        const cls = types[dv.getUint32(base, true)] ?? '';
        if (cls !== classDesc) continue;
        const classDataOff = dv.getUint32(base + 24, true);
        if (classDataOff === 0) throw new Error(`${classDesc} has no class_data_item`);
        let p = classDataOff;
        let staticFields: number, instanceFields: number, directMethods: number, virtualMethods: number;
        [staticFields, p] = readUleb128(dex, p);
        [instanceFields, p] = readUleb128(dex, p);
        [directMethods, p] = readUleb128(dex, p);
        [virtualMethods, p] = readUleb128(dex, p);
        for (let k = 0; k < staticFields + instanceFields; k++) {
          [, p] = readUleb128(dex, p);
          [, p] = readUleb128(dex, p);
        }
        for (const n of [directMethods, virtualMethods]) {
          let mid = 0;
          for (let k = 0; k < n; k++) {
            let diff: number, codeOff: number;
            [diff, p] = readUleb128(dex, p);
            [, p] = readUleb128(dex, p); // access_flags
            [codeOff, p] = readUleb128(dex, p);
            mid += diff;
            if (mid === targetMethodIdx) return codeOff;
          }
        }
      }
      throw new Error(`method #${targetMethodIdx} not found in ${classDesc}`);
    },
  };
}

interface ZipEntryMeta {
  name: string;
  method: number;
  flags: number;
  crc: number;
  csize: number;
  usize: number;
  local: number;
  cd: number;
  cdLen: number;
}

function parseZipEntries(jar: Uint8Array): { list: ZipEntryMeta[]; eocd: number } {
  const dv = new DataView(jar.buffer, jar.byteOffset, jar.byteLength);
  let eocd = -1;
  for (let i = jar.length - 22; i >= Math.max(0, jar.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) {
      const commentLen = dv.getUint16(i + 20, true);
      if (i + 22 + commentLen === jar.length) {
        eocd = i;
        break;
      }
    }
  }
  if (eocd < 0) throw new Error('services.jar is not a valid ZIP archive');
  const list: ZipEntryMeta[] = [];
  const totalEntries = dv.getUint16(eocd + 10, true);
  let at = dv.getUint32(eocd + 16, true);
  for (let n = 0; n < totalEntries; n++) {
    if (at + 46 > eocd || dv.getUint32(at, true) !== 0x02014b50) {
      throw new Error('services.jar has a corrupt ZIP central directory');
    }
    const nlen = dv.getUint16(at + 28, true);
    const cdLen = 46 + nlen + dv.getUint16(at + 30, true) + dv.getUint16(at + 32, true);
    list.push({
      name: td.decode(jar.subarray(at + 46, at + 46 + nlen)),
      method: dv.getUint16(at + 10, true),
      flags: dv.getUint16(at + 8, true),
      crc: dv.getUint32(at + 16, true),
      csize: dv.getUint32(at + 20, true),
      usize: dv.getUint32(at + 24, true),
      local: dv.getUint32(at + 42, true),
      cd: at,
      cdLen,
    });
    at += cdLen;
  }
  return { list, eocd };
}

/**
 * Rewrites `classes.dex` inside `jar` using `@glassworks/bootpatch`'s `zlib-rs` level-9 deflate
 * (cross-platform & deterministic, avoiding Node 24's Chromium-zlib size bloat) and pads the ZIP
 * EOCD comment with ASCII spaces (`0x20`) so the resulting JAR has the EXACT byte length `jar.length`.
 */
async function replaceJarDexExactSize(jar: Uint8Array, newDex: Uint8Array): Promise<{ out: Uint8Array; padBytes: number }> {
  const comp = await loadCompressor();
  const { list, eocd } = parseZipEntries(jar);
  const dv = new DataView(jar.buffer, jar.byteOffset, jar.byteLength);
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let pos = 0;

  for (const e of list) {
    const head = 30 + dv.getUint16(e.local + 26, true) + dv.getUint16(e.local + 28, true);
    const local = jar.slice(e.local, e.local + head);
    const cd = jar.slice(e.cd, e.cd + e.cdLen);
    let body = jar.subarray(e.local + head, e.local + head + e.csize);
    if (e.name === 'classes.dex') {
      if (e.method === 8) {
        const gz = comp.gzip(newDex);
        if (gz.length < 18 || gz[0] !== 0x1f || gz[1] !== 0x8b || gz[3] !== 0) {
          throw new Error('unexpected gzip framing from zlib-rs compressor');
        }
        body = gz.subarray(10, gz.length - 8);
      } else {
        body = newDex;
      }
      const crc = crc32(newDex);
      const lv = new DataView(local.buffer, local.byteOffset, local.byteLength);
      lv.setUint32(14, crc, true);
      lv.setUint32(18, body.length, true);
      lv.setUint32(22, newDex.length, true);
      const cv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
      cv.setUint32(16, crc, true);
      cv.setUint32(20, body.length, true);
      cv.setUint32(24, newDex.length, true);
    }
    new DataView(cd.buffer, cd.byteOffset, cd.byteLength).setUint32(42, pos, true);
    parts.push(local, body);
    central.push(cd);
    pos += local.length + body.length;
  }

  const cdSize = central.reduce((n, c) => n + c.length, 0);
  const baseLen = pos + cdSize + 22;
  const padBytes = jar.length - baseLen;
  if (padBytes < 0) {
    throw new Error(
      `patched services.jar compressed to ${baseLen} bytes, which exceeds original ${jar.length} bytes by ${-padBytes} bytes`,
    );
  }
  if (padBytes > 0xffff) {
    throw new Error(
      `patched services.jar requires ${padBytes} bytes of EOCD padding (> 65535 ZIP comment limit)`,
    );
  }

  const end = new Uint8Array(22 + padBytes);
  end.set(jar.subarray(eocd, eocd + 22), 0);
  const ev = new DataView(end.buffer);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, pos, true);
  ev.setUint16(20, padBytes, true);
  end.fill(0x20, 22);

  const out = new Uint8Array(jar.length);
  let at = 0;
  for (const chunk of [...parts, ...central, end]) {
    out.set(chunk, at);
    at += chunk.length;
  }
  if (at !== jar.length) throw new Error(`internal size mismatch (${at} !== ${jar.length})`);
  return { out, padBytes };
}

export interface PatchedServicesJar {
  patched: Uint8Array;
  fingerprint: string;
  originalSha256: string;
  patchedSha256: string;
  padBytes: number;
  startConfirmationCodeOff: number;
  patchedCodeUnits: number;
}

/**
 * Surgically patches a stock Android 6.0.1 `services.jar` (`UsbDebuggingManager.startConfirmation`)
 * so that ONLY `adbKey`'s MD5 fingerprint is auto-approved (`allowUsbDebugging(true, key)`) and
 * every other key is denied (`denyUsbDebugging()`), keeping the exact original JAR byte length.
 */
export async function patchStockServicesJar(jar: Uint8Array, adbKey: string): Promise<PatchedServicesJar> {
  const fp = validateStockAdbKey(adbKey);
  if (fp === GLASS_PANES_KEY_FINGERPRINT) {
    throw new Error('refusing to patch services.jar with the published Glass Panes project key');
  }

  const { list } = parseZipEntries(jar);
  const dexEntries = list.filter((e) => /^classes\d*\.dex$/.test(e.name));
  if (dexEntries.length !== 1 || dexEntries[0]!.name !== 'classes.dex') {
    throw new Error(`expected a single classes.dex in services.jar, found: ${dexEntries.map((e) => e.name).join(', ') || 'none'}`);
  }
  const dexMeta = dexEntries[0]!;
  if ((dexMeta.flags & 0x9) !== 0 || (dexMeta.method !== 0 && dexMeta.method !== 8) || dexMeta.csize === 0xffffffff) {
    throw new Error('services.jar stores classes.dex with unsupported ZIP flags or compression method');
  }
  const origDex = await zipEntry(jar, 'classes.dex');
  if (!origDex || crc32(origDex) !== dexMeta.crc) {
    throw new Error('services.jar classes.dex failed CRC32 verification');
  }

  // Keep the leading "unable to start " prefix so the replaced string stays right next to its
  // original sorted position in string_ids (minimizing index shifts across the DEX).
  const prefix = 'unable to start ';
  const padLen = START_CONFIRMATION_LOG_STRING.length - prefix.length - fp.length;
  const paddedFp = prefix + fp + ' '.repeat(padLen);
  if (paddedFp.length !== START_CONFIRMATION_LOG_STRING.length) {
    throw new Error(`internal error: padded fingerprint length ${paddedFp.length} !== ${START_CONFIRMATION_LOG_STRING.length}`);
  }

  // Step 1: swap the single-use 71-byte log string inside startConfirmation with our padded MD5.
  // replaceDexString re-sorts string_ids, renumbers every reference, and validates the whole DEX.
  const dex = await replaceDexString(origDex, START_CONFIRMATION_LOG_STRING, paddedFp);
  const strings = readDexStrings(dex);
  const fpStrIdx = strings.indexOf(paddedFp);
  if (fpStrIdx < 0) throw new Error('padded fingerprint not found in string_ids after replaceDexString');

  // Step 2: dynamically locate required methods and UsbDebuggingManager.startConfirmation code_item.
  const lookup = createDexLookup(dex, strings);
  const mSubstring = lookup.findMethod('Ljava/lang/String;', 'substring', '(II)Ljava/lang/String;');
  const mEquals = lookup.findMethod('Ljava/lang/String;', 'equals', '(Ljava/lang/Object;)Z');
  const mAllow = lookup.findMethod('Lcom/android/server/usb/UsbDebuggingManager;', 'allowUsbDebugging', '(ZLjava/lang/String;)V');
  const mDeny = lookup.findMethod('Lcom/android/server/usb/UsbDebuggingManager;', 'denyUsbDebugging', '()V');
  const mStartConfirm = lookup.findMethod(
    'Lcom/android/server/usb/UsbDebuggingManager;',
    'startConfirmation',
    '(Ljava/lang/String;Ljava/lang/String;)V',
  );
  const codeOff = lookup.findCodeItem('Lcom/android/server/usb/UsbDebuggingManager;', mStartConfirm);
  if (codeOff === 0 || codeOff + 16 > dex.length) {
    throw new Error(`invalid code_item offset 0x${codeOff.toString(16)} for startConfirmation`);
  }

  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const regs = dv.getUint16(codeOff + 0, true);
  const ins = dv.getUint16(codeOff + 2, true);
  const outs = dv.getUint16(codeOff + 4, true);
  const tries = dv.getUint16(codeOff + 6, true);
  const insnsSize = dv.getUint32(codeOff + 12, true);

  if (ins !== 3) throw new Error(`expected startConfirmation ins_size=3, got ${ins}`);
  if (regs < 6 || regs > 16) throw new Error(`unexpected startConfirmation registers_size=${regs} (need 6..16)`);
  if (outs < 3) throw new Error(`expected startConfirmation outs_size>=3, got ${outs}`);
  if (tries !== 0) throw new Error(`expected startConfirmation tries_size=0, got ${tries}`);

  const pThis = regs - 3;
  const pKey = regs - 2;
  const pFp = regs - 1;
  if (pFp > 15) throw new Error(`parameter register v${pFp} exceeds 4-bit invoke-virtual encoding`);

  // Step 3: assemble verified Dalvik 035 bytecode for startConfirmation(key, fingerprints):
  //   v0 = paddedFp.substring(16, 63);
  //   if (v0.equals(fingerprints)) allowUsbDebugging(true, key);
  //   else denyUsbDebugging();
  const units: number[] = [];
  if (fpStrIdx <= 0xffff) {
    units.push(0x001a, fpStrIdx); // const-string v0, paddedFp
  } else {
    units.push(0x001b, fpStrIdx & 0xffff, (fpStrIdx >>> 16) & 0xffff); // const-string/jumbo v0, paddedFp
  }
  units.push(
    0x0113, prefix.length, // const/16 v1, #16
    0x0213, prefix.length + fp.length, // const/16 v2, #63
    0x306e, mSubstring, 0x0210, // invoke-virtual {v0, v1, v2}, String.substring(II)String
    0x000c, // move-result-object v0
    0x206e, mEquals, ((pFp & 0xf) << 4) | 0, // invoke-virtual {v0, pFp}, String.equals(Object)Z
    0x000a, // move-result v0
    0x0038, 0x0007, // if-eqz v0, +7 (:deny)
    0x1012, // const/4 v0, #1 (true)
    0x306e, mAllow, ((pKey & 0xf) << 8) | (0 << 4) | (pThis & 0xf), // invoke-virtual {pThis, v0, pKey}, allowUsbDebugging(Z, String)
    0x000e, // return-void
    // :deny
    0x106e, mDeny, pThis & 0xf, // invoke-virtual {pThis}, denyUsbDebugging()
    0x000e, // return-void
  );

  if (units.length > insnsSize || codeOff + 16 + insnsSize * 2 > dex.length) {
    throw new Error(`startConfirmation insns_size=${insnsSize} is too small for ${units.length}-unit patch`);
  }

  // Zero debug_info_off so ART does not inspect stale local/position tables for the replaced body.
  dv.setUint32(codeOff + 8, 0, true);
  for (let i = 0; i < insnsSize; i++) {
    dv.setUint16(codeOff + 16 + i * 2, i < units.length ? units[i]! : 0x0000, true);
  }

  // Recompute DEX SHA-1 (over bytes 32..end) and Adler-32 (over bytes 12..end).
  dex.set(sha1Bytes(dex.subarray(32)), 12);
  dv.setUint32(8, adler32(dex.subarray(12)), true);

  // Step 4: repack JAR with zlib-rs level 9 + EOCD comment padding to exact original length.
  const { out, padBytes } = await replaceJarDexExactSize(jar, dex);

  // Step 5: round-trip verify classes.dex reads back identically.
  const back = await zipEntry(out, 'classes.dex');
  if (!back || back.length !== dex.length || !back.every((x, i) => x === dex[i])) {
    throw new Error('patched services.jar failed round-trip classes.dex read-back check');
  }
  if (out.length !== jar.length) {
    throw new Error(`patched services.jar length ${out.length} !== original ${jar.length}`);
  }

  return {
    patched: out,
    fingerprint: fp,
    originalSha256: sha256Hex(jar),
    patchedSha256: sha256Hex(out),
    padBytes,
    startConfirmationCodeOff: codeOff,
    patchedCodeUnits: units.length,
  };
}

/**
 * Builds an Android sparse image for `partitionBytes` that writes ONLY the physical 4 KiB blocks
 * described by `extents` (using `RAW` chunks) and skips every other block (`DONT_CARE`).
 *
 * Works identically for patching a file in-place AND for building its byte-exact undo image from
 * the original pulled bytes.
 */
export function buildSurgicalSparseImage(
  partitionBytes: number,
  extents: readonly FiemapExtent[],
  fileData: Uint8Array,
  blockSize = SPARSE_BLOCK_SIZE,
): Uint8Array {
  if (blockSize !== SPARSE_BLOCK_SIZE) throw new Error(`unsupported sparse block size ${blockSize}`);
  if (!Number.isSafeInteger(partitionBytes) || partitionBytes <= 0 || partitionBytes % blockSize !== 0) {
    throw new Error(`partition size ${partitionBytes} must be a positive multiple of ${blockSize}`);
  }
  const totalBlocks = partitionBytes / blockSize;
  if (totalBlocks > 0xffffffff) throw new Error(`partition exceeds 32-bit sparse block count (${totalBlocks})`);
  if (extents.length === 0) throw new Error('FIEMAP returned zero extents');
  if (fileData.length === 0) throw new Error('file data is empty');

  const neededBytes = Math.ceil(fileData.length / blockSize) * blockSize;
  const byLogical = [...extents].sort((a, b) => a.logical - b.logical);
  let expectedLogical = 0;
  for (const e of byLogical) {
    if (e.logical < 0 || e.physical < 0 || e.length <= 0) {
      throw new Error(`invalid FIEMAP extent: ${JSON.stringify(e)}`);
    }
    if (e.logical % blockSize !== 0 || e.physical % blockSize !== 0 || e.length % blockSize !== 0) {
      throw new Error(`FIEMAP extent is not ${blockSize}-byte aligned: ${JSON.stringify(e)}`);
    }
    if (e.block !== e.physical / blockSize || e.blocks !== e.length / blockSize) {
      throw new Error(`FIEMAP extent block/length fields are inconsistent: ${JSON.stringify(e)}`);
    }
    if (e.block + e.blocks > totalBlocks) {
      throw new Error(`FIEMAP extent [${e.block}..${e.block + e.blocks}) exceeds partition ${totalBlocks} blocks`);
    }
    if (e.logical !== expectedLogical) {
      throw new Error(`FIEMAP logical gap/overlap at offset ${expectedLogical} (got ${e.logical})`);
    }
    expectedLogical += e.length;
  }
  if (expectedLogical < neededBytes) {
    throw new Error(`FIEMAP extents cover only ${expectedLogical} bytes, need ${neededBytes} for ${fileData.length}-byte file`);
  }

  // Build physical write segments (splitting at file end if an extent has preallocated trailing blocks)
  // and sort by physical block address so sparse chunks advance monotonically.
  interface PhysSeg { physBlock: number; blocks: number; logicalStart: number }
  const segments: PhysSeg[] = [];
  for (const e of byLogical) {
    if (e.logical >= neededBytes) break;
    const useBytes = Math.min(e.length, neededBytes - e.logical);
    const useBlocks = useBytes / blockSize;
    segments.push({ physBlock: e.block, blocks: useBlocks, logicalStart: e.logical });
  }
  segments.sort((a, b) => a.physBlock - b.physBlock);
  for (let i = 1; i < segments.length; i++) {
    const prev = segments[i - 1]!;
    const cur = segments[i]!;
    if (prev.physBlock + prev.blocks > cur.physBlock) {
      throw new Error(`overlapping physical extents at block ${cur.physBlock}`);
    }
  }

  type SparseChunk =
    | { type: typeof CHUNK_DONT_CARE; blocks: number }
    | { type: typeof CHUNK_RAW; blocks: number; data: Uint8Array };
  const chunks: SparseChunk[] = [];
  let cursor = 0;

  for (const seg of segments) {
    if (seg.physBlock > cursor) {
      chunks.push({ type: CHUNK_DONT_CARE, blocks: seg.physBlock - cursor });
      cursor = seg.physBlock;
    }
    let remBlocks = seg.blocks;
    let logicalOff = seg.logicalStart;
    while (remBlocks > 0) {
      const n = Math.min(remBlocks, MAX_RAW_CHUNK_BLOCKS);
      const buf = new Uint8Array(n * blockSize); // tail past fileData.length stays 0x00
      if (logicalOff < fileData.length) {
        const end = Math.min(fileData.length, logicalOff + buf.length);
        buf.set(fileData.subarray(logicalOff, end), 0);
      }
      chunks.push({ type: CHUNK_RAW, blocks: n, data: buf });
      remBlocks -= n;
      logicalOff += n * blockSize;
      cursor += n;
    }
  }
  if (cursor < totalBlocks) {
    chunks.push({ type: CHUNK_DONT_CARE, blocks: totalBlocks - cursor });
  }

  const totalBytes = 28 + chunks.reduce((sum, c) => sum + 12 + (c.type === CHUNK_RAW ? c.data.length : 0), 0);
  const out = new Uint8Array(totalBytes);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, SPARSE_MAGIC, true);
  dv.setUint16(4, 1, true); // major
  dv.setUint16(6, 0, true); // minor
  dv.setUint16(8, 28, true); // file_hdr_sz
  dv.setUint16(10, 12, true); // chunk_hdr_sz
  dv.setUint32(12, blockSize, true);
  dv.setUint32(16, totalBlocks, true);
  dv.setUint32(20, chunks.length, true);
  dv.setUint32(24, 0, true); // image_checksum

  let at = 28;
  for (const c of chunks) {
    dv.setUint16(at, c.type, true);
    dv.setUint16(at + 2, 0, true);
    dv.setUint32(at + 4, c.blocks, true);
    if (c.type === CHUNK_RAW) {
      dv.setUint32(at + 8, 12 + c.data.length, true);
      out.set(c.data, at + 12);
      at += 12 + c.data.length;
    } else {
      dv.setUint32(at + 8, 12, true);
      at += 12;
    }
  }

  const parsed = parseSparseHeader(out);
  if (!parsed || parsed.blocks !== totalBlocks || parsed.chunks !== chunks.length) {
    throw new Error('generated surgical sparse image failed header self-check');
  }
  return out;
}

/**
 * Surgically inserts a one-shot `insmod <modulePath>` + read-only `chmod 0444` (RAM devtmpfs
 * only, cleared on reboot) for `mmcblk0p22` (`system`) and `mmcblk0p19` (`boot`) into
 * `/system/etc/init.qcom.post_boot.sh` while preserving the EXACT original byte length by
 * shortening an existing `#` comment block (never modifying `#!/system/bin/sh` or any executable
 * statement).
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

/** Validates that a user-supplied backup `.ko` is an AArch64 relocatable ELF and reads its vermagic/CRC. */
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
}

export interface BackupManifest {
  version: 1;
  createdAt: string;
  systemBytes: number;
  userdataBytes: number;
  servicesJar: {
    path: string;
    size: number;
    originalSha256: string;
    patchedSha256?: string;
    fingerprint?: string;
    extents: FiemapExtent[];
    origFile: string;
    restoreSparse: string;
  };
  postBoot?: {
    path: string;
    size: number;
    originalSha256: string;
    extents: FiemapExtent[];
    origFile: string;
    restoreSparse: string;
  };
}

export interface SurgicalAdbOptions {
  mode: 'full' | 'preflight-only' | 'build-only' | 'undo';
  probeHost: string;
  probeHttpPort: number;
  adbKey?: string;
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
  servicesOriginalSha256?: string;
  servicesPatchedSha256?: string;
  servicesPadBytes?: number;
  postBootOriginalSha256?: string;
  systemPartitionBytes?: number;
  userdataPartitionBytes?: number;
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
  if (r.servicesOriginalSha256) out.push(`- Original \`services.jar\` SHA-256: \`${r.servicesOriginalSha256}\``);
  if (r.postBootOriginalSha256) out.push(`- Original \`init.qcom.post_boot.sh\` SHA-256: \`${r.postBootOriginalSha256}\``);
  if (r.oatListing.length > 0) {
    out.push('', '`/system/framework/oat` entries:', '```', ...r.oatListing, '```');
  }
  if (r.appListing.length > 0) {
    out.push('', '`/system/app`:', '```', ...r.appListing, '```');
  }
  if (r.privAppListing.length > 0) {
    out.push('', '`/system/priv-app`:', '```', ...r.privAppListing, '```');
  }

  if (r.servicesPatchedSha256) {
    out.push('', '## Step 2 — Surgical Patch Build (exact byte length)', '');
    out.push(`- Patched \`services.jar\` SHA-256: \`${r.servicesPatchedSha256}\` (EOCD pad: ${r.servicesPadBytes ?? 0} B)`);
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
    const proceed = await d.confirm(
      `Restore original stock system blocks from ${o.backupDir} (and ${o.eraseUserdataOnUndo ? 'erase userdata' : 'keep userdata'})?`,
    );
    if (!proceed) {
      report.stopReason = 'undo cancelled by user';
      await saveReport();
      return { report, ok: false };
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

  // Validate ADB key up front when patching
  let cleanAdbKey: string | undefined;
  if (o.mode !== 'preflight-only') {
    if (!o.adbKey) throw new Error('missing --adb-key (needed to authorize your host ADB key)');
    report.adbKeyFingerprint = validateStockAdbKey(o.adbKey);
    cleanAdbKey = o.adbKey.trim();
  }

  // Validate optional backup .ko up front
  let backupKoBytes: Uint8Array | undefined;
  if (o.backupModulePath) {
    backupKoBytes = await d.readHostFile(o.backupModulePath);
    const info = validateKernelModuleElf(backupKoBytes);
    d.log(`  validated backup module: ${info.arch}, vermagic="${info.vermagic}"${info.moduleLayoutCrc ? `, module_layout=${info.moduleLayoutCrc}` : ''}`);
    report.notes.push(`Validated external backup .ko (${info.arch}, vermagic=${info.vermagic}${info.moduleLayoutCrc ? `, module_layout=${info.moduleLayoutCrc}` : ''}).`);
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

  report.frameworkListing = await http.list('/system/framework');
  const oatDirs = ['/system/framework/oat', '/system/framework/oat/arm64', '/system/framework/arm64'];
  const oatLines: string[] = [];
  for (const dir of oatDirs) {
    const entries = await http.list(dir);
    for (const line of entries) oatLines.push(line);
  }
  report.oatListing = oatLines;
  report.servicesOdexFound = detectServicesOdex(report.frameworkListing, oatLines);
  report.appListing = await http.list('/system/app');
  report.privAppListing = await http.list('/system/priv-app');

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

  const manifest: BackupManifest = {
    version: 1,
    createdAt: nowIso(),
    systemBytes: targetSystemBytes,
    userdataBytes: defaultUserdataBytes,
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
  };
  await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
  await saveReport();

  d.log(`  saved byte-exact undo image: ${restoreServicesPath}`);
  if (report.servicesOdexFound) {
    report.stopReason = 'STOPPED: services.odex detected under /system/framework — ART would ignore patched services.jar unless services.odex is handled. Nothing was written.';
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
  await d.writeFile('backup/manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);

  let patchServicesSparse = buildSurgicalSparseImage(
    targetSystemBytes,
    report.servicesFiemap.extents,
    patchedJar.patched,
  );
  const patchServicesPath = await d.writeFile('patch-services.sparse', patchServicesSparse);

  let patchedPostBoot: Uint8Array | undefined;
  let patchPostBootPath: string | undefined;
  if (backupKoBytes) {
    if (!origPostBoot || !report.postBootFiemap?.extents?.length) {
      report.stopReason = 'cannot build Step 4 backup hook: init.qcom.post_boot.sh or its FIEMAP extents were unavailable';
      await saveReport();
      return { report, ok: false };
    }
    patchedPostBoot = patchPostBootForBackup(origPostBoot, REMOTE_BACKUP_KO_PATH);
    const patchPostBootSparse = buildSurgicalSparseImage(
      targetSystemBytes,
      report.postBootFiemap.extents,
      patchedPostBoot,
    );
    patchPostBootPath = await d.writeFile('patch-post-boot.sparse', patchPostBootSparse);
  }

  // Build fresh userdata (retaining the probe APK as a SoftAP safety net while wiping dalvik-cache)
  const probeApp = await d.loadProbeApp();
  const disk = new Disk(new MemoryStore(), SPARSE_BLOCK_SIZE, defaultUserdataBytes / SPARSE_BLOCK_SIZE, { zeroed: true });
  await buildStockProbeUserdata(disk, {
    adbKey: cleanAdbKey!,
    usbConfig: 'adb',
    tcpPort: 5555,
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
    if (origPostBoot && report.postBootFiemap?.extents?.length) {
      await d.writeFile(
        'backup/restore-post-boot.sparse',
        buildSurgicalSparseImage(sysBytes, report.postBootFiemap.extents, origPostBoot),
      );
      if (patchedPostBoot) {
        await d.writeFile(
          'patch-post-boot.sparse',
          buildSurgicalSparseImage(sysBytes, report.postBootFiemap.extents, patchedPostBoot),
        );
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
  await fb.flashPartition('system', patchServicesPath);
  d.log('  flashing userdata-adb.sparse to userdata...');
  await fb.flashPartition('userdata', userdataPath);
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

  // --- Step 4 (Optional): Full raw system + boot backup using external --backup-module ---
  if (!backupKoBytes || !patchPostBootPath || !restorePostBootPath) {
    return { report, ok: true };
  }

  const okBackup = await d.confirm(
    `Step 4/4: Push backup module, surgically patch init.qcom.post_boot.sh (20 blocks) for 1 boot to dump raw system + boot images, then automatically restore init.qcom.post_boot.sh?`,
  );
  if (!okBackup) {
    report.notes.push('Step 4 (raw system/boot backup) skipped by user.');
    await saveReport();
    return { report, ok: true };
  }

  await adbSession.push(o.backupModulePath!, REMOTE_BACKUP_KO_PATH);
  await adbSession.rebootBootloader();
  const fb2 = await d.waitForFastboot(120_000);
  if (!fb2) {
    report.stopReason = 'Mirror did not enter fastboot for Step 4 backup hook';
    await saveReport();
    return { report, ok: false };
  }
  d.log('  flashing surgical patch-post-boot.sparse (20 blocks)...');
  await fb2.flashPartition('system', patchPostBootPath);
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
    report.stopReason = `USB ADB did not return after Step 4 hook (restore post_boot with: fastboot flash system ${restorePostBootPath})`;
    await saveReport();
    return { report, ok: false };
  }

  // qcom-post-boot runs on sys.boot_completed=1, which can lag USB ADB authorization by a few
  // seconds; wait up to 45 s for boot_completed + Permissive + readable mmcblk0p22 before failing.
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
    await backupAdb.shell(`rm -f ${REMOTE_BACKUP_KO_PATH}`);
    report.stopReason = `backup module did not switch SELinux to Permissive + readable block nodes (getenforce=${enf || 'unknown'}, readable=${blockReadable}); restore post_boot via fastboot flash system ${restorePostBootPath}`;
    await saveReport();
    return { report, ok: false };
  }

  d.log('  streaming raw system (/dev/block/mmcblk0p22, ~1.2 GiB) over USB ADB...');
  const sysDump = await backupAdb.streamCommandToFile(
    'dd if=/dev/block/mmcblk0p22 bs=1048576 2>/dev/null',
    `${o.outDir}/backup/system-stock.raw.img`,
  );
  report.backupSystemPath = `${o.outDir}/backup/system-stock.raw.img`;
  report.backupSystemBytes = sysDump.bytes;
  report.backupSystemSha256 = sysDump.sha256;
  if (sysDump.bytes === targetSystemBytes && d.restoreExtentsInRawImage) {
    d.log('  scrubbing temporary services.jar + post_boot.sh patches out of system-stock.raw.img -> pure untouched stock...');
    report.backupSystemSha256 = await d.restoreExtentsInRawImage(report.backupSystemPath, [
      { extents: report.servicesFiemap.extents, origData: origServices },
      ...(origPostBoot && report.postBootFiemap?.extents
        ? [{ extents: report.postBootFiemap.extents, origData: origPostBoot }]
        : []),
    ]);
    report.notes.push('Restored original services.jar and init.qcom.post_boot.sh blocks inside system-stock.raw.img so the saved image is pure untouched stock.');
  }

  d.log('  streaming raw boot (/dev/block/mmcblk0p19, 32 MiB) over USB ADB...');
  const bootDump = await backupAdb.streamCommandToFile(
    'dd if=/dev/block/mmcblk0p19 bs=1048576 2>/dev/null',
    `${o.outDir}/backup/boot-stock.raw.img`,
  );
  report.backupBootPath = `${o.outDir}/backup/boot-stock.raw.img`;
  report.backupBootBytes = bootDump.bytes;
  report.backupBootSha256 = bootDump.sha256;

  await backupAdb.shell(`rm -f ${REMOTE_BACKUP_KO_PATH}`);
  await backupAdb.rebootBootloader();
  const fb3 = await d.waitForFastboot(120_000);
  if (fb3) {
    d.log('  restoring original init.qcom.post_boot.sh blocks...');
    await fb3.flashPartition('system', restorePostBootPath);
    report.restoredPostBoot = true;
    if (o.restoreAllAfterBackup) {
      d.log('  --restore-all: restoring original services.jar blocks...');
      await fb3.flashPartition('system', restoreServicesPath);
      report.restoredServices = true;
    }
    await fb3.reboot();
  } else {
    report.restoredPostBoot = false;
    report.notes.push(`Manual restore needed: fastboot flash system ${restorePostBootPath}`);
  }

  if (sysDump.bytes !== targetSystemBytes) {
    report.stopReason = `raw system dump size ${sysDump.bytes} !== expected ${targetSystemBytes}`;
    await saveReport();
    return { report, ok: false };
  }

  await saveReport();
  return { report, ok: true };
}

export { DEFAULT_PROBE_HTTP_PORT };
export type { FiemapExtent };
