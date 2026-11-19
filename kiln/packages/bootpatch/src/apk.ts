// Reading the Magisk APK: a minimal zip reader (stored and deflate, CRC-checked) and the payload
// patch-boot-magisk.sh unzips from it. Only v31.1 is accepted, by the SHA-256 of its two scripts.

import type { Compressor } from './compress';

/** The three Magisk binaries that go into the boot image (armeabi-v7a: the panel is 32-bit only). */
export interface MagiskBinaries {
  magiskinit: Uint8Array;
  magisk: Uint8Array;
  initLd: Uint8Array;
}

/** The binaries plus the two scripts whose v31.1 behaviour bootpatch reproduces. */
export interface MagiskPayload extends MagiskBinaries {
  bootPatchSh: Uint8Array;
  utilFunctionsSh: Uint8Array;
}

/**
 * SHA-256 of the v31.1 scripts whose behaviour bootpatch reproduces. If they differ, the APK is not the
 * Magisk we reimplemented and the output would silently diverge from the CLI, so we refuse.
 */
export const PORTED_SCRIPTS = {
  'assets/boot_patch.sh': '721e879b9ebdda200796d95dbe6f8e9776cfff89b7550fdb5ca77ac87dcede17',
  'assets/util_functions.sh': '417866965cb3845c7ea085f1499393efcf65e6c7cf1321e7911a3bd7c25a6c8d',
} as const;

export const PAYLOAD_ABI = 'armeabi-v7a';

// ---------------------------------------------------------------------------------------------
// Minimal zip reader (stored + deflate, CRC-checked)

let crcTable: Uint32Array | undefined;
export function crc32(d: Uint8Array): number {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < d.length; i++) c = crcTable[(c ^ d[i]!) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export interface ZipEntry {
  name: string;
  method: number;
  crc: number;
  compressedSize: number;
  size: number;
  localOffset: number;
}

/** Central directory of a zip (no zip64; the Magisk APK does not need it). */
export function zipEntries(zip: Uint8Array): Map<string, ZipEntry> {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip file (no end of central directory)');
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const td = new TextDecoder();
  const out = new Map<string, ZipEntry>();
  for (let i = 0; i < count; i++) {
    if (dv.getUint32(p, true) !== 0x02014b50) throw new Error('bad zip central directory');
    const method = dv.getUint16(p + 10, true);
    const crc = dv.getUint32(p + 16, true);
    const compressedSize = dv.getUint32(p + 20, true);
    const size = dv.getUint32(p + 24, true);
    const nlen = dv.getUint16(p + 28, true), xlen = dv.getUint16(p + 30, true), clen = dv.getUint16(p + 32, true);
    const localOffset = dv.getUint32(p + 42, true);
    const name = td.decode(zip.subarray(p + 46, p + 46 + nlen));
    out.set(name, { name, method, crc, compressedSize, size, localOffset });
    p += 46 + nlen + xlen + clen;
  }
  return out;
}

export function zipRead(zip: Uint8Array, e: ZipEntry, comp: Compressor): Uint8Array {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const lo = e.localOffset;
  if (dv.getUint32(lo, true) !== 0x04034b50) throw new Error(`bad zip local header for ${e.name}`);
  const start = lo + 30 + dv.getUint16(lo + 26, true) + dv.getUint16(lo + 28, true);
  const raw = zip.subarray(start, start + e.compressedSize);
  let data: Uint8Array;
  if (e.method === 0) data = raw.slice();
  else if (e.method === 8) data = comp.inflateRaw(raw);
  else throw new Error(`${e.name}: unsupported zip method ${e.method}`);
  if (data.length !== e.size || crc32(data) !== e.crc) throw new Error(`${e.name}: CRC/size mismatch`);
  return data;
}

async function sha256Hex(d: Uint8Array): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', d as Uint8Array<ArrayBuffer>));
  return [...h].map((x) => x.toString(16).padStart(2, '0')).join('');
}

/** Pull boot_patch.sh's inputs out of the Magisk APK (what patch-boot-magisk.sh unzips). */
export async function extractMagiskPayload(apk: Uint8Array, comp: Compressor): Promise<MagiskPayload> {
  const ents = zipEntries(apk);
  const get = (name: string) => {
    const e = ents.get(name);
    if (!e) throw new Error(`Magisk APK has no ${name}`);
    return zipRead(apk, e, comp);
  };
  const p: MagiskPayload = {
    magiskinit: get(`lib/${PAYLOAD_ABI}/libmagiskinit.so`),
    magisk: get(`lib/${PAYLOAD_ABI}/libmagisk.so`),
    initLd: get(`lib/${PAYLOAD_ABI}/libinit-ld.so`),
    bootPatchSh: get('assets/boot_patch.sh'),
    utilFunctionsSh: get('assets/util_functions.sh'),
  };
  for (const [name, want] of Object.entries(PORTED_SCRIPTS)) {
    const got = await sha256Hex(name.endsWith('boot_patch.sh') ? p.bootPatchSh : p.utilFunctionsSh);
    if (got !== want) throw new Error(`${name} is not Magisk v31.1's (sha256 ${got}); this patcher reproduces v31.1 only`);
  }
  return p;
}

/**
 * Magisk's busybox for the panel's ABI (`lib/armeabi-v7a/libbusybox.so`), which Magisk needs in /data/adb/magisk
 * before it loads any module. The Magisk app puts it there; a frame's boot image can carry it instead (its overlay).
 */
export function extractMagiskBusybox(apk: Uint8Array, comp: Compressor): Uint8Array {
  const name = `lib/${PAYLOAD_ABI}/libbusybox.so`;
  const e = zipEntries(apk).get(name);
  if (!e) throw new Error(`Magisk APK has no ${name}`);
  return zipRead(apk, e, comp);
}
