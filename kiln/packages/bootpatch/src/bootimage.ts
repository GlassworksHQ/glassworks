// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md.
//
// Android boot images, header v2 only (AOSP bootimg.h), with an optional AVB footer
// (libavb avb_footer.h): read with every refusal the spec lists, and repack with a new ramdisk.

const enc = new TextEncoder();

export type IdKind = 'SHA-1' | 'SHA-256';

export interface AvbData {
  /** The 64-byte footer, as read. */
  footer: Uint8Array;
  /** The vbmeta block it points at, copied as is. */
  vbmeta: Uint8Array;
}

export interface BootImage {
  size: number;
  pageSize: number;
  /** The whole header page. */
  headerPage: Uint8Array;
  idKind: IdKind;
  kernel: Uint8Array;
  ramdisk: Uint8Array;
  second: Uint8Array;
  recoveryDtbo: Uint8Array;
  dtb: Uint8Array;
  avb?: AvbData;
}

const PAGE_SIZES = [2048, 4096, 8192, 16384];
const FOOTER = 64;

const alignUp = (x: number, a: number) => Math.ceil(x / a) * a;

function startsWith(d: Uint8Array, sig: ArrayLike<number>, at = 0): boolean {
  if (d.length < at + sig.length) return false;
  for (let i = 0; i < sig.length; i++) if (d[at + i] !== sig[i]) return false;
  return true;
}

function indexOf(d: Uint8Array, sig: Uint8Array, from = 0): number {
  outer: for (let i = from; i + sig.length <= d.length; i++) {
    for (let j = 0; j < sig.length; j++) if (d[i + j] !== sig[j]) continue outer;
    return i;
  }
  return -1;
}

export const hasBytes = (d: Uint8Array, s: string) => indexOf(d, enc.encode(s)) >= 0;

export type Compression = 'gzip' | 'xz' | 'cpio';

const GZIP = [[0x1f, 0x8b, 0x08], [0x1f, 0x9e, 0x08]];
const XZ = [0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00];
const OTHER_FORMATS = [
  [0x89, 0x4c, 0x5a, 0x4f], // lzop
  [0x42, 0x5a, 0x68], // bzip2
  [0x04, 0x22, 0x4d, 0x18], [0x03, 0x21, 0x4c, 0x18], // lz4
  [0x02, 0x21, 0x4c, 0x18], // lz4 legacy
  [0x88, 0x16, 0x88, 0x58], // MediaTek header
];

function isRawLzma(d: Uint8Array): boolean {
  if (d.length < 13 || d[0]! >= 225) return false;
  for (let i = 5; i < 13; i++) if (d[i] !== 0xff) return false;
  const dict = new DataView(d.buffer, d.byteOffset + 1, 4).getUint32(0, true);
  return dict !== 0 && (dict & (dict - 1)) === 0;
}

function isCompressed(d: Uint8Array): boolean {
  return GZIP.some((s) => startsWith(d, s)) || startsWith(d, XZ) || OTHER_FORMATS.some((s) => startsWith(d, s)) ||
    startsWith(d, [0x18, 0x28, 0x6f, 0x01], 0x24) || isRawLzma(d);
}

export function ramdiskCompression(d: Uint8Array): Compression {
  if (GZIP.some((s) => startsWith(d, s))) return 'gzip';
  if (startsWith(d, XZ)) return 'xz';
  if (!isCompressed(d) && startsWith(d, enc.encode('070701'))) return 'cpio';
  throw new Error('Unsupported ramdisk format: Kiln reads gzip, xz or uncompressed cpio ramdisks.');
}

function hasAppendedDtb(kernel: Uint8Array): boolean {
  const magic = new Uint8Array([0xd0, 0x0d, 0xfe, 0xed]);
  const i = indexOf(kernel, magic);
  return i >= 0 && kernel.length - i >= 40;
}

const allZero = (d: Uint8Array) => d.every((x) => x === 0);

function u64be(v: DataView, at: number): number {
  const x = v.getBigUint64(at, false);
  return x > BigInt(Number.MAX_SAFE_INTEGER) ? Number.MAX_SAFE_INTEGER : Number(x);
}

/** Parse a header v2 boot image. Throws, before anything is written, on anything Kiln can't repack. */
export function readBootImage(img: Uint8Array): BootImage {
  if (!startsWith(img, enc.encode('ANDROID!'))) throw new Error('This is not an Android boot image (no ANDROID! magic).');
  if (img.length < 1660) throw new Error('The boot image is truncated.');
  const v = new DataView(img.buffer, img.byteOffset, img.byteLength);
  const version = v.getUint32(40, true);
  if (version !== 2) throw new Error(`Unsupported boot image header version ${version}: Kiln patches header v2 only.`);
  const page = v.getUint32(36, true);
  if (!PAGE_SIZES.includes(page)) throw new Error(`Unsupported boot image page size ${page}.`);
  if (img.length < page) throw new Error('The boot image is truncated.');
  if (img.length % 4096) throw new Error(`The boot image is ${img.length} bytes, not a multiple of 4096 (a partition dump).`);

  const sizes = [8, 16, 24, 1632, 1648].map((at) => v.getUint32(at, true));
  const blocks: Uint8Array[] = [];
  let end = page;
  for (const size of sizes) {
    if (end + size > img.length) throw new Error('Corrupted boot image: a block runs past the end of the image.');
    blocks.push(img.subarray(end, end + size));
    end = alignUp(end + size, page);
  }
  const [kernel, ramdisk, second, recoveryDtbo, dtb] = blocks as [Uint8Array, Uint8Array, Uint8Array, Uint8Array, Uint8Array];

  if (isCompressed(kernel)) throw new Error('Unsupported kernel format: Kiln patches images with a raw kernel only.');
  if (hasAppendedDtb(kernel)) throw new Error('Unsupported kernel: it has a DTB appended.');
  if (ramdisk.length === 0) throw new Error('The boot image has no ramdisk.');
  ramdiskCompression(ramdisk);
  if (startsWith(img, enc.encode('SEANDROIDENFORCE'), end)) throw new Error('Unsupported boot image: it has a Samsung SEANDROIDENFORCE trailer.');
  if (img[end] === 0x30 && (img[end + 1]! & 0x80) !== 0) throw new Error('Unsupported boot image: it has an AVB1 signature.');

  let avb: AvbData | undefined;
  let gapEnd = img.length;
  let tail: Uint8Array = new Uint8Array(0);
  const footerAt = img.length - FOOTER;
  if (startsWith(img, enc.encode('AVBf'), footerAt)) {
    const off = u64be(v, footerAt + 20), len = u64be(v, footerAt + 28);
    if (off + 4 <= img.length && startsWith(img, enc.encode('AVB0'), off)) {
      if (off + len > img.length) throw new Error('The boot image is truncated: its vbmeta block runs past the end.');
      avb = { footer: img.slice(footerAt), vbmeta: img.slice(off, off + len) };
      gapEnd = off;
      tail = img.subarray(Math.min(off + len, footerAt), footerAt);
    }
  }
  if (!allZero(img.subarray(Math.min(end, gapEnd), gapEnd)) || !allZero(tail)) {
    throw new Error('This boot image is unsupported: it has data after its last block that a repack would drop.');
  }

  return {
    size: img.length, pageSize: page, headerPage: img.slice(0, page),
    idKind: img.subarray(576 + 24, 576 + 32).some((x) => x !== 0) ? 'SHA-256' : 'SHA-1',
    kernel, ramdisk, second, recoveryDtbo, dtb, avb,
  };
}

async function bootId(kind: IdKind, blocks: Uint8Array[]): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  for (const b of blocks) {
    const size = new Uint8Array(4);
    new DataView(size.buffer).setUint32(0, b.length, true);
    parts.push(b, size);
  }
  const all = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let p = 0;
  for (const part of parts) { all.set(part, p); p += part.length; }
  return new Uint8Array(await crypto.subtle.digest(kind, all));
}

/** The image `b` with `ramdisk` in place of its own, the same size as the input. */
export async function repackBootImage(b: BootImage, ramdisk: Uint8Array): Promise<Uint8Array> {
  const page = b.pageSize;
  const out = new Uint8Array(b.size);
  const v = new DataView(out.buffer);
  out.set(b.headerPage);

  const layout = [b.kernel, ramdisk, b.second, b.recoveryDtbo, b.dtb];
  const offsets: number[] = [];
  let end = page;
  layout.forEach((blk, i) => {
    if (i >= 2 && blk.length === 0) { offsets.push(0); return; }
    offsets.push(end);
    end = alignUp(end + blk.length, page);
  });

  const vbmetaAt = alignUp(end, 4096);
  const need = b.avb ? vbmetaAt + b.avb.vbmeta.length + FOOTER : end;
  if (need > b.size) throw new Error(`The patched boot image (${need} bytes) does not fit the ${b.size}-byte partition.`);

  layout.forEach((blk, i) => { if (blk.length) out.set(blk, offsets[i]!); });
  v.setUint32(8, b.kernel.length, true);
  v.setUint32(16, ramdisk.length, true);
  v.setUint32(24, b.second.length, true);
  v.setUint32(1632, b.recoveryDtbo.length, true);
  if (b.recoveryDtbo.length) v.setBigUint64(1636, BigInt(offsets[3]!), true);
  v.setUint32(1644, 1660, true);
  v.setUint32(1648, b.dtb.length, true);
  out.fill(0, 576, 576 + 32);
  out.set(await bootId(b.idKind, layout), 576);

  if (b.avb) {
    out.set(b.avb.vbmeta, vbmetaAt);
    const footerAt = b.size - FOOTER;
    out.set(b.avb.footer, footerAt);
    v.setBigUint64(footerAt + 12, BigInt(end), false);
    v.setBigUint64(footerAt + 20, BigInt(vbmetaAt), false);
  }
  return out;
}
