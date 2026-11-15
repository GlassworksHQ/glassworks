// Synthetic inputs for the bootpatch vectors, built from the public formats only: AOSP's boot image
// header (system/tools/mkbootimg, include/bootimg/bootimg.h), the AVB footer (external/avb,
// libavb/avb_footer.h), cpio "newc" (cpio(5)) and gzip with stored deflate blocks (RFC 1951/1952).
// No Aqara bytes. Everything here is deterministic, so the vectors need no binary fixtures beyond
// the one xz ramdisk in bootpatch.json.

const enc = new TextEncoder();

export function crc32(d: Uint8Array): number {
  let c = ~0;
  for (const x of d) {
    c ^= x;
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
  }
  return ~c >>> 0;
}

/** Bytes 0..n-1 of a fixed pseudo-random stream, so blocks look like data, not zeros. */
export function pattern(n: number, seed: number): Uint8Array {
  const b = new Uint8Array(n);
  let x = (seed * 2654435761) >>> 0 || 1;
  for (let i = 0; i < n; i++) { x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0; b[i] = x & 0xff; }
  return b;
}

/** An ELF header (32-bit, little-endian) for `machine` (0x28 ARM, 0xb7 AArch64, 0x3e x86-64, 0xf3 RISC-V), then `body`. */
export function elf(machine: number, body: Uint8Array): Uint8Array {
  const b = new Uint8Array(52 + body.length);
  b.set([0x7f, 0x45, 0x4c, 0x46, 1, 1, 1]);
  b[16] = 2; // ET_EXEC
  b[18] = machine & 0xff; b[19] = machine >> 8;
  b.set(body, 52);
  return b;
}

/** `n` words that look like A32 code: condition field 0xE (always). */
export function a32Words(n: number, seed: number): Uint8Array {
  const b = pattern(n * 4, seed);
  for (let i = 3; i < b.length; i += 4) b[i] = 0xe0 | (b[i]! & 0x0f);
  return b;
}

/** gzip with stored (uncompressed) deflate blocks: valid gzip, byte-stable everywhere. */
export function gzipStored(d: Uint8Array): Uint8Array {
  const blocks = Math.max(1, Math.ceil(d.length / 0xffff));
  const out = new Uint8Array(10 + d.length + blocks * 5 + 8);
  out.set([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff]);
  let p = 10;
  for (let i = 0; i < blocks; i++) {
    const chunk = d.subarray(i * 0xffff, (i + 1) * 0xffff);
    out[p++] = i === blocks - 1 ? 1 : 0;
    out[p++] = chunk.length & 0xff; out[p++] = chunk.length >> 8;
    out[p++] = ~chunk.length & 0xff; out[p++] = (~chunk.length >> 8) & 0xff;
    out.set(chunk, p); p += chunk.length;
  }
  const v = new DataView(out.buffer);
  v.setUint32(p, crc32(d), true);
  v.setUint32(p + 4, d.length >>> 0, true);
  return out;
}

export interface CpioIn {
  name: string;
  /** Full st_mode: file type bits and permissions. */
  mode: number;
  data?: Uint8Array | string;
  uid?: number;
  gid?: number;
  rdev?: [number, number];
}

export const S_IFREG = 0o100000, S_IFDIR = 0o040000, S_IFLNK = 0o120000, S_IFCHR = 0o020000;

/** A newc archive with "real-looking" inodes and mtimes, in the order given, ending with TRAILER!!!. */
export function cpioNewc(entries: CpioIn[]): Uint8Array {
  const parts: Uint8Array[] = [];
  let len = 0;
  const push = (b: Uint8Array) => { parts.push(b); len += b.length; };
  const pad = () => { if (len % 4) push(new Uint8Array(4 - (len % 4))); };
  const one = (ino: number, e: CpioIn) => {
    const data = typeof e.data === 'string' ? enc.encode(e.data) : (e.data ?? new Uint8Array(0));
    const name = enc.encode(`${e.name}\0`);
    const f = [ino, e.mode, e.uid ?? 0, e.gid ?? 0, 1, 1700000000 + ino, data.length, 3, 1,
      e.rdev?.[0] ?? 0, e.rdev?.[1] ?? 0, name.length, 0];
    push(enc.encode(`070701${f.map((x) => (x >>> 0).toString(16).toUpperCase().padStart(8, '0')).join('')}`));
    push(name); pad();
    push(data); pad();
  };
  entries.forEach((e, i) => one(1000 + i, e));
  one(0, { name: 'TRAILER!!!', mode: 0 });
  const out = new Uint8Array(len);
  let p = 0;
  for (const b of parts) { out.set(b, p); p += b.length; }
  return out;
}

export interface BootIn {
  version: number;
  pageSize: number;
  kernel: Uint8Array;
  ramdisk: Uint8Array;
  second?: Uint8Array;
  recoveryDtbo?: Uint8Array;
  dtb?: Uint8Array;
  cmdline?: string;
  /** Fill id[] the way mkbootimg does with SHA-1 (bytes 20..31 zero), or with 32 non-zero bytes. */
  id: 'sha1' | 'sha256';
  /** Append an AVB hash footer with a vbmeta block after the payload. */
  avb?: boolean;
  /** Bytes after the page-aligned payload, before any AVB data (e.g. a vendor trailer). */
  tail?: Uint8Array;
  /** Total size (the partition). Default: the smallest multiple of 64 KiB that fits. */
  size?: number;
  /** No slack: the smallest multiple of 4096 that fits. */
  tight?: boolean;
}

const align = (x: number, a: number) => Math.ceil(x / a) * a;

/**
 * A boot image with header v0..v4 laid out as AOSP's bootimg.h describes. v0..v2 use `pageSize`
 * pages; v3 and v4 have a fixed 4096-byte page and a shorter header. Only v2 is accepted by Kiln.
 */
export function bootImage(b: BootIn): Uint8Array {
  const v3 = b.version >= 3;
  const page = v3 ? 4096 : b.pageSize;
  const second = b.second ?? new Uint8Array(0), rdtbo = b.recoveryDtbo ?? new Uint8Array(0), dtb = b.dtb ?? new Uint8Array(0);
  const blocks = v3 ? [b.kernel, b.ramdisk] : [b.kernel, b.ramdisk, second, ...(b.version >= 1 ? [rdtbo] : []), ...(b.version >= 2 ? [dtb] : [])];
  let end = page;
  const offs: number[] = [];
  for (const blk of blocks) { offs.push(end); end = align(end + blk.length, page); }
  const tail = b.tail ?? new Uint8Array(0);
  const vbmeta = b.avb ? avbVbmeta() : null;
  const vbOff = vbmeta ? align(end + tail.length, 4096) : 0;
  const need = vbmeta ? vbOff + vbmeta.length + 64 : end + tail.length;
  const size = b.size ?? align(need, b.tight ? 4096 : 0x10000);
  if (size < need) throw new Error(`synth: ${size} is smaller than ${need}`);
  const out = new Uint8Array(size);
  const v = new DataView(out.buffer);
  out.set(enc.encode('ANDROID!'));
  if (v3) {
    v.setUint32(8, b.kernel.length, true);
    v.setUint32(12, b.ramdisk.length, true);
    v.setUint32(16, 0x0a000000, true); // os_version
    v.setUint32(20, b.version === 4 ? 1584 : 1580, true); // header_size
    v.setUint32(40, b.version, true);
    out.set(enc.encode(b.cmdline ?? ''), 44);
  } else {
    v.setUint32(8, b.kernel.length, true); v.setUint32(12, 0x40008000, true);
    v.setUint32(16, b.ramdisk.length, true); v.setUint32(20, 0x41000000, true);
    v.setUint32(24, second.length, true); v.setUint32(28, 0x40f00000, true);
    v.setUint32(32, 0x40000100, true); v.setUint32(36, page, true);
    v.setUint32(40, b.version, true); v.setUint32(44, 0x0a000000, true);
    out.set(enc.encode('synthetic'), 48);
    out.set(enc.encode(b.cmdline ?? 'console=ttyS0,115200'), 64);
    const id = pattern(32, 77);
    if (b.id === 'sha1') id.fill(0, 20);
    out.set(id, 576);
    if (b.version >= 1) {
      v.setUint32(1632, rdtbo.length, true);
      v.setBigUint64(1636, BigInt(rdtbo.length ? offs[3]! : 0), true);
      v.setUint32(1644, b.version === 1 ? 1648 : 1660, true);
    }
    if (b.version >= 2) { v.setUint32(1648, dtb.length, true); v.setBigUint64(1652, 0x44000000n, true); }
  }
  blocks.forEach((blk, i) => out.set(blk, offs[i]!));
  out.set(tail, end);
  if (vbmeta) {
    out.set(vbmeta, vbOff);
    const f = new DataView(out.buffer, size - 64, 64);
    out.set(enc.encode('AVBf'), size - 64);
    f.setUint32(4, 1, false); f.setUint32(8, 0, false);
    f.setBigUint64(12, BigInt(end), false);
    f.setBigUint64(20, BigInt(vbOff), false);
    f.setBigUint64(28, BigInt(vbmeta.length), false);
  }
  return out;
}

/** A stand-in vbmeta block: AVB0 magic and fixed filler (Kiln copies it, it doesn't parse it). */
function avbVbmeta(): Uint8Array {
  const b = pattern(1088, 5);
  b.set(enc.encode('AVB0'));
  return b;
}
