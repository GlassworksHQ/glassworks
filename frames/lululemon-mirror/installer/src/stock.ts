// Stock-in-place Glassworks on the lululemon Studio Mirror: the browser-safe core that both Kiln
// (WebUSB, in the browser) and the Node tool (`tools/mirror/src/surgical-adb.ts`) build on, so there
// is one source of truth for the surgical sparse builder and the services.jar patch.
//
// This file runs in the browser AND in Node, so it uses ONLY standard Web APIs (`Uint8Array`,
// `DataView`, `TextEncoder`/`TextDecoder`, `crypto.subtle`) plus pure-TS SHA-1/CRC32/Adler-32 and
// workspace packages (`@glassworks/ext4`, `@glassworks/bootpatch`, `@glassworks/app-adb`). NO `node:*`
// imports — no `node:crypto`, `node:fs`, `Buffer`, etc. (see the strict rule in the Mirror AGENTS).
//
// What untouched stock 1.24.0 verifies this against, with offsets and commands, is in
// frames/lululemon-mirror/research/stock-firmware-probe.md.
import type { PanelShell } from '@glassworks/adb';
import { zipEntry } from '@glassworks/app-adb';
import { loadCompressor } from '@glassworks/bootpatch';
import { SPARSE_MAGIC, parseSparseHeader } from '@glassworks/ext4';
import { GLASS_PANES_KEY_FINGERPRINT, adbFingerprint, adbKeyLines, replaceDexString } from './services';

// ---------------------------------------------------------------------------
// Verified stock 1.24.0 facts (frames/lululemon-mirror/research/stock-firmware-probe.md)
// ---------------------------------------------------------------------------

/** Exact MSM8916 Mirror `system` eMMC partition size (`mmcblk0p22`, 0x4cccd000 = 1,258,292 KiB). */
export const STOCK_SYSTEM_PARTITION_BYTES = 0x4cccd000;

/** The un-odexed `classes.dex`-carrying framework jar whose `UsbDebuggingManager` the patch rewrites. */
export const STOCK_SERVICES_JAR_PATH = '/system/framework/services.jar';

/**
 * The eight stock lululemon packages in `/system/app` on untouched stock 1.24.0 (verified
 * 2026-11-24: §5 and §7 of the probe note). `pm hide` on these — never `pm disable-user`, which
 * throws `SecurityException` for `shell` on an Android 6 `user` build — leaves Glassworks the only
 * running home with zero lululemon processes.
 */
export const STOCK_BLOAT_PACKAGES: readonly string[] = [
  'co.mirror.cw',
  'co.mirror.datacap',
  'co.mirror.elevator',
  'com.mirror.launcher',
  'com.mirror.mirrorupdater',
  'com.mirror.remover',
  'com.mirror.services',
  'com.mirror.watchdog',
];

/**
 * Single-use 71-byte log string inside stock Android 6.0.1
 * `UsbDebuggingManager.startConfirmation(String, String)`.
 * Replaced in-place via `replaceDexString` with `<47-char MD5 fingerprint><24 spaces>`.
 */
export const START_CONFIRMATION_LOG_STRING =
  'unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component ';

export const SPARSE_BLOCK_SIZE = 4096;
export const MAX_RAW_CHUNK_BLOCKS = 4096; // 16 MiB per RAW chunk
const CHUNK_RAW = 0xcac1;
const CHUNK_DONT_CARE = 0xcac3;
// FIEMAP_EXTENT_{UNKNOWN,DELALLOC,ENCODED,DATA_ENCRYPTED,NOT_ALIGNED,DATA_INLINE,DATA_TAIL,UNWRITTEN}.
// A RAW write to physical blocks under any of these does not produce the file the workflow expects
// (e.g. an UNWRITTEN extent still reads as zeros after a raw write). FIEMAP_EXTENT_LAST (0x1) is fine.
const UNSAFE_FIEMAP_FLAGS = 0x0002 | 0x0004 | 0x0008 | 0x0080 | 0x0100 | 0x0200 | 0x0400 | 0x0800;

/** One contiguous physical run of a `/system` file, as `FS_IOC_FIEMAP` reports it over the probe. */
export interface FiemapExtent {
  logical: number;
  physical: number;
  block: number;
  length: number;
  blocks: number;
  flags: number;
}

const td = new TextDecoder();
const te = new TextEncoder();

// ---------------------------------------------------------------------------
// Pure-TS checksums (no node:crypto)
// ---------------------------------------------------------------------------

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

/** SHA-1 (FIPS 180-1), pure TypeScript: dex recomputation must not reach for `node:crypto`. */
function sha1Bytes(msg: Uint8Array): Uint8Array {
  const ml = msg.length;
  const withOne = ml + 1;
  const total = withOne + ((56 - (withOne % 64) + 64) % 64) + 8;
  const buf = new Uint8Array(total);
  buf.set(msg);
  buf[ml] = 0x80;
  const bits = ml * 8;
  const dv = new DataView(buf.buffer);
  dv.setUint32(total - 8, Math.floor(bits / 2 ** 32), false);
  dv.setUint32(total - 4, bits >>> 0, false);

  let h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476, h4 = 0xc3d2e1f0;
  const w = new Uint32Array(80);
  for (let at = 0; at < total; at += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(at + i * 4, false);
    for (let i = 16; i < 80; i++) {
      const x = w[i - 3]! ^ w[i - 8]! ^ w[i - 14]! ^ w[i - 16]!;
      w[i] = (x << 1) | (x >>> 31);
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4;
    for (let i = 0; i < 80; i++) {
      const [f, k] = i < 20 ? [(b & c) | (~b & d), 0x5a827999]
        : i < 40 ? [b ^ c ^ d, 0x6ed9eba1]
        : i < 60 ? [(b & c) | (b & d) | (c & d), 0x8f1bbcdc]
        : [b ^ c ^ d, 0xca62c1d6];
      const t = (((a << 5) | (a >>> 27)) + f + e + k + w[i]!) | 0;
      e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = t;
    }
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0; h4 = (h4 + e) | 0;
  }
  const out = new Uint8Array(20);
  new DataView(out.buffer).setUint32(0, h0 >>> 0, false);
  new DataView(out.buffer).setUint32(4, h1 >>> 0, false);
  new DataView(out.buffer).setUint32(8, h2 >>> 0, false);
  new DataView(out.buffer).setUint32(12, h3 >>> 0, false);
  new DataView(out.buffer).setUint32(16, h4 >>> 0, false);
  return out;
}

const hex = (b: Uint8Array): string => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');

/** SHA-256 over Web Crypto (`crypto.subtle`), which both the browser and Node provide (no `node:crypto`). */
async function sha256Hex(b: Uint8Array): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', b as Uint8Array<ArrayBuffer>)));
}

// ---------------------------------------------------------------------------
// Surgical Android sparse builder
// ---------------------------------------------------------------------------

/**
 * Builds an Android sparse image for `partitionBytes` that writes ONLY the physical 4 KiB blocks
 * described by `extents` (using `RAW` chunks) and skips every other block (`DONT_CARE`).
 *
 * Works identically for patching a file in-place AND for building its byte-exact undo image from
 * the original pulled bytes. Validates every extent's bounds, alignment, and overlap first, so a
 * corrupt FIEMAP can never produce an image that writes outside the file's own blocks.
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
    if ((e.flags & UNSAFE_FIEMAP_FLAGS) !== 0) {
      throw new Error(`FIEMAP extent is not plain, initialized data (flags=0x${e.flags.toString(16)}): ${JSON.stringify(e)}`);
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

// ---------------------------------------------------------------------------
// DEX (format 035) reading helpers
// ---------------------------------------------------------------------------

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

/** Dalvik 035 instruction widths in 16-bit code units, indexed by the low opcode byte. 0 = not an opcode. */
const INSN_WIDTH = (() => {
  const w = new Uint8Array(256);
  const set = (n: number, ...ops: (number | [number, number])[]): void => {
    for (const o of ops) { const [a, z] = Array.isArray(o) ? o : [o, o]; for (let i = a; i <= z; i++) w[i] = n; }
  };
  set(1, 0x00, 0x01, 0x04, 0x07, [0x0a, 0x12], 0x1d, 0x1e, 0x21, 0x27, 0x28, [0x7b, 0x8f], [0xb0, 0xcf]);
  set(2, 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, [0x2d, 0x3d], [0x44, 0x6d], [0x90, 0xaf], [0xd0, 0xe2]);
  set(3, 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, [0x24, 0x26], 0x2a, 0x2b, 0x2c, [0x6e, 0x72], [0x74, 0x78]);
  set(5, 0x18);
  return w;
})();

interface DexInsn { pc: number; op: number; unit: number; width: number }

/** Walks a code_item's instruction stream (`code_off` is a file offset), yielding each instruction. */
function* walkInsns(dex: Uint8Array, codeOff: number): Generator<DexInsn> {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const insnsOff = codeOff + 16;
  const size = dv.getUint32(codeOff + 12, true);
  for (let pc = 0; pc < size;) {
    const unit = dv.getUint16(insnsOff + pc * 2, true);
    const op = unit & 0xff;
    let width: number = INSN_WIDTH[op]!;
    if (op === 0x00 && unit !== 0) {
      const n = dv.getUint16(insnsOff + pc * 2 + 2, true);
      width = unit === 0x0100 ? 4 + n * 2
        : unit === 0x0200 ? 2 + n * 4
        : unit === 0x0300 ? 4 + Math.ceil((n * dv.getUint32(insnsOff + pc * 2 + 4, true)) / 2)
        : 0;
    }
    if (!width) throw new Error(`unknown opcode 0x${unit.toString(16)} in classes.dex code_item`);
    yield { pc, op, unit, width };
    pc += width;
  }
}

// ---------------------------------------------------------------------------
// ZIP (services.jar) repack at exact byte length
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// services.jar surgical patch
// ---------------------------------------------------------------------------

/** AOSP 6.0.1 descriptors the Device Owner patch anchors on. */
const DPMS = 'Lcom/android/server/devicepolicy/DevicePolicyManagerService;';
const DEVICE_ADMIN_FEATURE = 'android.software.device_admin';

export interface DeviceOwnerPatch {
  /** Code-unit offset of the `move-result` turned into `const/4 vA, #1` (relative to that code_item's insns). */
  hasFeatureMoveResultPc: number;
  /** Register `A` the `const/4 vA, #1` writes `mHasFeature = true` into. */
  hasFeatureReg: number;
  /** Code-unit offset of the NOP'd `invoke-interface IBackupManager.setBackupServiceActive`. */
  setBackupServiceActivePc: number;
}

export interface PatchedServicesJar {
  patched: Uint8Array;
  fingerprint: string;
  originalSha256: string;
  patchedSha256: string;
  padBytes: number;
  startConfirmationCodeOff: number;
  patchedCodeUnits: number;
  /** Present only when `enableDeviceOwner` was applied. */
  deviceOwner?: DeviceOwnerPatch;
}

export interface PatchStockServicesJarOptions {
  /**
   * Also apply the two verified Device Owner fixes in `classes.dex`, so stock lets
   * `dpm set-device-owner` succeed (`mHasFeature = true`, and the missing stock backup service can't
   * NPE it). Default `true` — the whole point of Stock-in-place is silent updates via a Device Owner.
   * The Node surgical-ADB tool passes `false` explicitly to keep its USB-ADB-only behavior.
   */
  enableDeviceOwner?: boolean;
}

/** Validates an adb_keys line and returns its Android-6 MD5 fingerprint, refusing the Glass Panes key. */
function stockKeyFingerprint(adbKey: string): string {
  const lines = adbKeyLines(adbKey); // throws on a published/forbidden key or a malformed line
  const fp = adbFingerprint(lines[0]!);
  if (fp === GLASS_PANES_KEY_FINGERPRINT) {
    throw new Error('refusing to patch services.jar with the published Glass Panes project key');
  }
  return fp;
}

/**
 * Surgically patches a stock Android 6.0.1 `services.jar` to:
 *  - authorize ONLY `adbKey`'s MD5 fingerprint in `UsbDebuggingManager.startConfirmation`
 *    (`allowUsbDebugging(true, key)`), denying every other key (`denyUsbDebugging()`); and
 *  - (when `enableDeviceOwner`) let `dpm set-device-owner` succeed on stock:
 *      a. `DevicePolicyManagerService.<init>` forces `mHasFeature = true` (stock lacks the
 *         `android.software.device_admin` system feature), and
 *      b. `DevicePolicyManagerService.setDeviceOwner` NOPs the `IBackupManager.setBackupServiceActive`
 *         call that would NPE because stock has no backup service.
 *
 * Keeps the EXACT original JAR byte length (so a surgical sparse write never touches ext4 inode
 * metadata) and round-trips `classes.dex` through the repack before returning.
 */
export async function patchStockServicesJar(
  jar: Uint8Array,
  adbKey: string,
  options: PatchStockServicesJarOptions = {},
): Promise<PatchedServicesJar> {
  const enableDeviceOwner = options.enableDeviceOwner ?? true;
  const fp = stockKeyFingerprint(adbKey);

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

  // Step 4 (optional): the two Device Owner fixes, located dynamically in the same classes.dex.
  let deviceOwner: DeviceOwnerPatch | undefined;
  if (enableDeviceOwner) {
    deviceOwner = patchDeviceOwner(dex, dv, lookup, strings);
  }

  // Recompute DEX SHA-1 (over bytes 32..end) and Adler-32 (over bytes 12..end), both pure TS.
  dex.set(sha1Bytes(dex.subarray(32)), 12);
  dv.setUint32(8, adler32(dex.subarray(12)), true);

  // Step 5: repack JAR with zlib-rs level 9 + EOCD comment padding to exact original length.
  const { out, padBytes } = await replaceJarDexExactSize(jar, dex);

  // Step 6: round-trip verify classes.dex reads back identically.
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
    originalSha256: await sha256Hex(jar),
    patchedSha256: await sha256Hex(out),
    padBytes,
    startConfirmationCodeOff: codeOff,
    patchedCodeUnits: units.length,
    ...(deviceOwner ? { deviceOwner } : {}),
  };
}

/**
 * Applies the two Device Owner fixes in place on `dex` (SHA-1/Adler recomputed by the caller):
 *   (a) `DevicePolicyManagerService.<init>`: the `move-result` after
 *       `pm.hasSystemFeature("android.software.device_admin")` becomes `const/4 vA, #1`, so
 *       `mHasFeature = true` even though stock lacks the feature.
 *   (b) `DevicePolicyManagerService.setDeviceOwner`: the `invoke-interface` to
 *       `IBackupManager.setBackupServiceActive(IZ)V` is NOP'd (3 code units), so the missing stock
 *       backup service can't throw and abort `dpm set-device-owner`.
 */
function patchDeviceOwner(
  dex: Uint8Array,
  dv: DataView,
  lookup: DexMethodLookup,
  strings: readonly string[],
): DeviceOwnerPatch {
  // (a) mHasFeature = true
  const daIdx = strings.indexOf(DEVICE_ADMIN_FEATURE);
  if (daIdx < 0) throw new Error(`Device Owner patch: "${DEVICE_ADMIN_FEATURE}" string not in classes.dex`);
  const mHasSystemFeature = lookup.findMethod('Landroid/content/pm/PackageManager;', 'hasSystemFeature', '(Ljava/lang/String;)Z');
  const ctorIdx = lookup.findMethod(DPMS, '<init>', '(Landroid/content/Context;)V');
  const ctorOff = lookup.findCodeItem(DPMS, ctorIdx);
  const ctorInsns = ctorOff + 16;

  const ctor = [...walkInsns(dex, ctorOff)];
  // The const-string that loads "android.software.device_admin" (d8 may hoist it above the
  // getPackageManager() call, so it need not sit immediately before the hasSystemFeature invoke).
  let csAt = -1;
  for (let i = 0; i < ctor.length; i++) {
    const cs = ctor[i]!;
    let sIdx = -1;
    if (cs.op === 0x1a) sIdx = dv.getUint16(ctorInsns + (cs.pc + 1) * 2, true); // const-string
    else if (cs.op === 0x1b) sIdx = dv.getUint32(ctorInsns + (cs.pc + 1) * 2, true); // const-string/jumbo
    if (sIdx === daIdx) { csAt = i; break; }
  }
  if (csAt < 0) throw new Error(`Device Owner patch: const-string "${DEVICE_ADMIN_FEATURE}" not found in <init>`);
  // The next PackageManager.hasSystemFeature invoke-virtual after it.
  let invAt = -1;
  for (let i = csAt + 1; i < ctor.length; i++) {
    const ins = ctor[i]!;
    if (ins.op === 0x6e && dv.getUint16(ctorInsns + (ins.pc + 1) * 2, true) === mHasSystemFeature) { invAt = i; break; }
  }
  if (invAt < 0) throw new Error('Device Owner patch: PackageManager.hasSystemFeature invoke not found after the device_admin const-string');
  // move-result must immediately follow the invoke whose value it takes (Dalvik verifier rule),
  // and the stored boolean lands in mHasFeature via iput-boolean.
  const mr = ctor[invAt + 1];
  if (!mr || mr.op !== 0x0a) throw new Error('Device Owner patch: expected move-result after hasSystemFeature');
  const iput = ctor[invAt + 2];
  if (!iput || iput.op !== 0x5c) throw new Error('Device Owner patch: expected iput-boolean (mHasFeature) after move-result');
  const hasFeatureReg = (mr.unit >>> 8) & 0xff;
  const hasFeatureMoveResultPc = mr.pc;
  if (hasFeatureReg > 15) throw new Error(`Device Owner patch: move-result register v${hasFeatureReg} exceeds const/4 4-bit encoding`);
  // const/4 vA, #1  ==  (1 << 12) | (vA << 8) | 0x12
  dv.setUint16(ctorInsns + hasFeatureMoveResultPc * 2, (1 << 12) | (hasFeatureReg << 8) | 0x12, true);

  // (b) NOP IBackupManager.setBackupServiceActive(IZ)V inside setDeviceOwner
  const mSetBackup = lookup.findMethod('Landroid/app/backup/IBackupManager;', 'setBackupServiceActive', '(IZ)V');
  const sdoIdx = lookup.findMethod(DPMS, 'setDeviceOwner', '(Ljava/lang/String;Ljava/lang/String;)Z');
  const sdoOff = lookup.findCodeItem(DPMS, sdoIdx);
  const sdoInsns = sdoOff + 16;

  let setBackupServiceActivePc = -1;
  for (const insn of walkInsns(dex, sdoOff)) {
    if (insn.op !== 0x72) continue; // invoke-interface
    if (dv.getUint16(sdoInsns + (insn.pc + 1) * 2, true) !== mSetBackup) continue;
    if (insn.width !== 3) throw new Error(`Device Owner patch: setBackupServiceActive invoke is ${insn.width} units, expected 3`);
    for (let k = 0; k < 3; k++) dv.setUint16(sdoInsns + (insn.pc + k) * 2, 0x0000, true); // nop × 3
    setBackupServiceActivePc = insn.pc;
    break;
  }
  if (setBackupServiceActivePc < 0) {
    throw new Error('Device Owner patch: invoke-interface IBackupManager.setBackupServiceActive not found in setDeviceOwner');
  }

  return { hasFeatureMoveResultPc, hasFeatureReg, setBackupServiceActivePc };
}

// ---------------------------------------------------------------------------
// Stage 1 single-file bundle: [4B "GWSB"][4B metaLen LE][4B jarLen LE][meta JSON][services.jar]
// ---------------------------------------------------------------------------

export const STOCK_BUNDLE_MAGIC = 'GWSB';
const BUNDLE_HEADER_BYTES = 12;

/** Caller-supplied metadata for a stock bundle. `jarSha256` is added by `encodeStockBundle`. */
export interface StockBundleMeta {
  /** SHA-256 (hex) of the services.jar bytes; set by `encodeStockBundle`, verified by `decodeStockBundle`. */
  jarSha256?: string;
  [key: string]: unknown;
}

/**
 * Packs the pulled stock `services.jar` and its metadata into one file for the Stage 1 transfer.
 * `encodeStockBundle` computes the jar's SHA-256 and records it in the metadata so `decodeStockBundle`
 * can verify the jar survived the transfer byte-for-byte.
 */
export async function encodeStockBundle(meta: StockBundleMeta, servicesJar: Uint8Array): Promise<Uint8Array> {
  const fullMeta: StockBundleMeta = { ...meta, jarSha256: await sha256Hex(servicesJar) };
  const metaBytes = te.encode(JSON.stringify(fullMeta));
  const out = new Uint8Array(BUNDLE_HEADER_BYTES + metaBytes.length + servicesJar.length);
  out.set(te.encode(STOCK_BUNDLE_MAGIC), 0);
  const dv = new DataView(out.buffer);
  dv.setUint32(4, metaBytes.length, true);
  dv.setUint32(8, servicesJar.length, true);
  out.set(metaBytes, BUNDLE_HEADER_BYTES);
  out.set(servicesJar, BUNDLE_HEADER_BYTES + metaBytes.length);
  return out;
}

export interface DecodedStockBundle {
  meta: StockBundleMeta;
  servicesJar: Uint8Array;
}

/**
 * Unpacks a stock bundle, checking the magic, the length fields (strict bounds — the three sections
 * must account for every byte, no more and no less), that the metadata is valid JSON, and that the
 * jar's SHA-256 matches `meta.jarSha256`.
 */
export async function decodeStockBundle(bytes: Uint8Array): Promise<DecodedStockBundle> {
  if (bytes.length < BUNDLE_HEADER_BYTES) {
    throw new Error(`stock bundle is ${bytes.length} B, shorter than the ${BUNDLE_HEADER_BYTES}-byte header`);
  }
  if (td.decode(bytes.subarray(0, 4)) !== STOCK_BUNDLE_MAGIC) {
    throw new Error(`stock bundle magic is not "${STOCK_BUNDLE_MAGIC}"`);
  }
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const metaLen = dv.getUint32(4, true);
  const jarLen = dv.getUint32(8, true);
  if (BUNDLE_HEADER_BYTES + metaLen + jarLen !== bytes.length) {
    throw new Error(
      `stock bundle length ${bytes.length} B != header + meta(${metaLen}) + jar(${jarLen}) = ${BUNDLE_HEADER_BYTES + metaLen + jarLen}`,
    );
  }
  let meta: StockBundleMeta;
  try {
    meta = JSON.parse(td.decode(bytes.subarray(BUNDLE_HEADER_BYTES, BUNDLE_HEADER_BYTES + metaLen))) as StockBundleMeta;
  } catch (err) {
    throw new Error(`stock bundle metadata is not valid JSON: ${err instanceof Error ? err.message : String(err)}`);
  }
  const servicesJar = bytes.slice(BUNDLE_HEADER_BYTES + metaLen);
  const actual = await sha256Hex(servicesJar);
  if (typeof meta.jarSha256 !== 'string' || meta.jarSha256 !== actual) {
    throw new Error(`stock bundle services.jar SHA-256 ${actual} does not match metadata ${meta.jarSha256 ?? '(absent)'}`);
  }
  return { meta, servicesJar };
}

// ---------------------------------------------------------------------------
// Hiding / unhiding the stock lululemon packages
// ---------------------------------------------------------------------------

export interface StockBloatChange {
  pkg: string;
  /** The command's trimmed output, e.g. `Package co.mirror.cw new hidden state: true`. */
  out: string;
  /** Whether Android confirmed the requested hidden state. */
  ok: boolean;
}

/**
 * Hides the eight stock lululemon packages with `pm hide` (NOT `pm disable-user`, which throws a
 * `SecurityException` for `shell` on an Android 6 `user` build). Survives reboot, leaves Glassworks
 * the only visible home, and is reversed by `unhideStockBloat`.
 */
export async function hideStockBloat(p: PanelShell, packages: readonly string[] = STOCK_BLOAT_PACKAGES): Promise<StockBloatChange[]> {
  return setHidden(p, packages, true);
}

/** Reverses `hideStockBloat`: `pm unhide` on the same stock lululemon packages. */
export async function unhideStockBloat(p: PanelShell, packages: readonly string[] = STOCK_BLOAT_PACKAGES): Promise<StockBloatChange[]> {
  return setHidden(p, packages, false);
}

async function setHidden(p: PanelShell, packages: readonly string[], hide: boolean): Promise<StockBloatChange[]> {
  const verb = hide ? 'hide' : 'unhide';
  const out: StockBloatChange[] = [];
  for (const pkg of packages) {
    const r = await p.run(`pm ${verb} ${pkg}`);
    const text = r.out.trim();
    out.push({ pkg, out: text, ok: r.code === 0 && new RegExp(`new hidden state: ${hide}`).test(text) });
  }
  return out;
}
