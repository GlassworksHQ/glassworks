// Kiln's checks on an in-place install's way back, before any byte of the restore is written. The restore can't be
// read back after it is written: fastboot on such a device has no partition read, and the restored system starts with
// fresh app data, whose adb no longer lets this browser in. So Kiln checks every piece of it before the first write,
// as far as it can (AGENTS.md hard rule 1's exception, kiln/AGENTS.md):
//
//   - the restore point is the one Kiln saved: each file's size and SHA-256 as recorded when it was made
//     (`recordRestoreFiles`, kept in its manifest as `kiln.files`);
//   - it was made on this device: the SHA-256 of the serial fastboot reports, against the one it records;
//   - its restore image is whole, sized for this partition, and fills nothing;
//   - it puts back exactly the files the frame says it does (InPlaceCode `restored`): every block it writes belongs to
//     one of them, and its bytes there are that file's original bytes, whose size and SHA-256 it recorded;
//   - and, before the restart into fastboot while the device still lets this browser in over adb, each of those files
//     on the device is either what the install wrote or still the original (`deviceFileState`): anything else means
//     something other than Kiln changed the system, and the old blocks may no longer belong to that file.
//
// Each refusal throws RestoreRefused with a plain sentence; nothing is written.

import { sha256Hex } from './bundle';
import { mergeRanges, parseSparseWrites, type ByteRange } from './inplace';
import type { NamedPart } from './backup';

/** A file the restore image puts back: where it sits on the partition and what it was before the install. */
export interface RestoredFile {
  /** Its path on the running device, e.g. `/system/framework/services.jar`. */
  path: string;
  /** The restore point's copy of the original file (its file name there). */
  file: string;
  /** The original's size in bytes. */
  size: number;
  /** The original's SHA-256 (hex). */
  sha256: string;
  /** Where the file sits on the partition, in bytes: `[physical, physical + length)` holds its bytes from `logical`. */
  extents: readonly { logical: number; physical: number; length: number }[];
  /** What the install wrote there, when the restore point records it: its SHA-256 (hex) and size. */
  written?: { sha256: string; size?: number };
}

/** Each file of a restore point by name, as recorded when it was made. */
export type RestoreFileRecord = Record<string, { size: number; sha256: string }>;

/** A restore point Kiln won't write, and why, in a sentence that ends with what was (not) written. */
export class RestoreRefused extends Error {}

const NOTHING = 'Nothing was written.';

/** The size and SHA-256 of each file of a restore point (manifest.json aside), for its manifest's `kiln.files`. */
export async function recordRestoreFiles(files: readonly NamedPart[]): Promise<RestoreFileRecord> {
  const out: RestoreFileRecord = {};
  for (const f of files) if (f.filename !== 'manifest.json') out[f.filename] = { size: f.data.length, sha256: await sha256Hex(f.data) };
  return out;
}

/** The restore point's record of its files (manifest `kiln.files`), if it has a well-formed one. */
export function restoreFileRecord(manifest: Record<string, unknown> | undefined): RestoreFileRecord | undefined {
  const kiln = manifest?.kiln;
  if (!kiln || typeof kiln !== 'object') return undefined;
  const files = (kiln as { files?: unknown }).files;
  if (!files || typeof files !== 'object') return undefined;
  const out: RestoreFileRecord = {};
  for (const [name, v] of Object.entries(files as Record<string, unknown>)) {
    const x = v as { size?: unknown; sha256?: unknown } | null;
    if (!x || typeof x.size !== 'number' || typeof x.sha256 !== 'string') return undefined;
    out[name] = { size: x.size, sha256: x.sha256.toLowerCase() };
  }
  return out;
}

export interface RestoreCheckInput {
  /** The restore point's files, as Kiln has them now (this browser's copy, or the zip the person picked). */
  files: readonly NamedPart[];
  /** The restore image's file name among them. */
  sparse: string;
  /** Its manifest.json, parsed. */
  manifest?: Record<string, unknown>;
  /** The files the restore image puts back, as the frame reads them from the manifest. None: the frame doesn't say. */
  restored?: readonly RestoredFile[];
  /** The partition's size, as fastboot reports it now. */
  partitionBytes: number;
  /** The partition the restore image goes to, for the sentences. */
  partition: string;
  /** The serial fastboot reports now ('' when it reports none). Only its SHA-256 is compared, never logged. */
  serial: string;
}

const short = (sha: string) => `${sha.slice(0, 12)}…`;
const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1) || path;

/**
 * Every check Kiln can make on a restore point in fastboot, before its first write (see the top of this file). Returns
 * lines for the log, one per check that passed; throws RestoreRefused on the first that doesn't.
 */
export async function checkRestorePoint(o: RestoreCheckInput): Promise<string[]> {
  const lines: string[] = [];
  const byName = new Map(o.files.map((f) => [f.filename, f.data] as const));
  const sparseBytes = byName.get(o.sparse);
  if (!sparseBytes) throw new RestoreRefused(`This restore point has no restore image (${o.sparse}). ${NOTHING}`);

  // 1. the files are the ones Kiln saved: each one's size and SHA-256, as recorded when the restore point was made
  const record = restoreFileRecord(o.manifest);
  if (record) {
    if (!record[o.sparse]) throw new RestoreRefused(`This restore point doesn't record its restore image, so Kiln can't tell it is the one it saved. ${NOTHING}`);
    for (const [name, want] of Object.entries(record)) {
      const got = byName.get(name);
      if (!got) throw new RestoreRefused(`This restore point is missing ${name}. ${NOTHING}`);
      if (got.length !== want.size || await sha256Hex(got) !== want.sha256) throw new RestoreRefused(`${name} isn't the file Kiln saved in this restore point. ${NOTHING}`);
    }
    for (const name of byName.keys()) {
      if (name !== 'manifest.json' && !record[name]) throw new RestoreRefused(`This restore point holds ${name}, which Kiln didn't save in it. ${NOTHING}`);
    }
    lines.push(`restore point: each of its ${Object.keys(record).length} files has the size and SHA-256 recorded when it was saved`);
  } else if (!o.restored?.length) {
    throw new RestoreRefused(`This restore point doesn't record its files, so Kiln can't check it. ${NOTHING}`);
  } else {
    lines.push('restore point: made before Kiln recorded each file\'s SHA-256, so its contents are checked against the original files it records instead');
  }

  // 2. made on this device: only the serial's SHA-256 is kept and compared
  const want = o.manifest?.deviceIdHash;
  if (typeof want !== 'string' || !/^[0-9a-f]{64}$/i.test(want)) throw new RestoreRefused(`This restore point doesn't say which device it was made on, so Kiln won't write it. ${NOTHING}`);
  const serial = o.serial.trim();
  if (!serial || await sha256Hex(new TextEncoder().encode(serial)) !== want.toLowerCase()) throw new RestoreRefused(`This restore point was made on another device. ${NOTHING}`);
  lines.push('restore point: made on this device (the SHA-256 of its serial matches)');

  // 3. a whole restore image, for this partition, that writes only the device's own bytes
  let sparse: ReturnType<typeof parseSparseWrites>;
  try { sparse = parseSparseWrites(sparseBytes); } catch (e) { throw new RestoreRefused(`The restore image is damaged (${(e as Error).message}). ${NOTHING}`); }
  if (sparse.totalBytes !== o.partitionBytes) throw new RestoreRefused(`This restore point is for a ${o.partition} partition of ${sparse.totalBytes} bytes; this device's is ${o.partitionBytes}. ${NOTHING}`);
  if (sparse.filled.length) throw new RestoreRefused(`This restore point fills blocks instead of restoring them, so Kiln won't write it. ${NOTHING}`);
  lines.push(`restore image: whole, for this ${o.partition} partition (${o.partitionBytes} bytes), every block raw`);

  // 4. it puts back exactly the files it says, with their original bytes
  if (o.restored?.length) {
    const raw = rawChunks(sparseBytes);
    const covered: ByteRange[] = [];
    for (const f of o.restored) {
      const name = nameOf(f.path);
      const orig = byName.get(f.file);
      if (!orig) throw new RestoreRefused(`This restore point is missing ${f.file}, the original ${name}. ${NOTHING}`);
      if (orig.length !== f.size || await sha256Hex(orig) !== f.sha256.toLowerCase()) throw new RestoreRefused(`${f.file} isn't the original ${name} this restore point records. ${NOTHING}`);
      const ranges = fileBlocks(f, sparse.blockSize);
      for (const r of ranges) {
        const got = readRaw(raw, r.start, r.end);
        if (!got) throw new RestoreRefused(`The restore image doesn't put back all of ${name}. ${NOTHING}`);
        if (!sameBytes(got, orig, r.logical)) throw new RestoreRefused(`The restore image doesn't hold the original ${name}. ${NOTHING}`);
        covered.push({ start: r.start, end: r.end });
      }
      lines.push(`restore image: puts back the original ${name} (${f.size} bytes, SHA-256 ${short(f.sha256.toLowerCase())}) in its own blocks`);
    }
    const files = mergeRanges(covered);
    const same = files.length === sparse.ranges.length && files.every((r, i) => r.start === sparse.ranges[i]!.start && r.end === sparse.ranges[i]!.end);
    if (!same) throw new RestoreRefused(`The restore image writes blocks that aren't part of the files it puts back. ${NOTHING}`);
    lines.push('restore image: writes no block outside those files');
  }
  return lines;
}

/** What a file on the running device is, against the restore point: what the install wrote, the original, or neither. */
export type DeviceFileState = 'written' | 'original' | 'other';

/** `got`: the file's bytes as the device shows them now (adb's sync). */
export async function deviceFileState(f: RestoredFile, got: Uint8Array): Promise<DeviceFileState> {
  const sha = await sha256Hex(got);
  if (f.written && sha === f.written.sha256.toLowerCase() && (f.written.size === undefined || got.length === f.written.size)) return 'written';
  if (sha === f.sha256.toLowerCase() && got.length === f.size) return 'original';
  return 'other';
}

/** A RAW chunk of a sparse image: the partition byte offset it starts at, and its bytes. */
interface RawChunk { start: number; bytes: Uint8Array }

/** The RAW chunks of a sparse image parseSparseWrites accepted, by partition offset. */
function rawChunks(sparse: Uint8Array): RawChunk[] {
  const v = new DataView(sparse.buffer, sparse.byteOffset, sparse.byteLength);
  const fileHeader = v.getUint16(8, true), chunkHeader = v.getUint16(10, true), blockSize = v.getUint32(12, true), chunks = v.getUint32(20, true);
  const out: RawChunk[] = [];
  let o = fileHeader, block = 0;
  for (let c = 0; c < chunks; c++) {
    const type = v.getUint16(o, true), count = v.getUint32(o + 4, true), total = v.getUint32(o + 8, true);
    if (type === 0xcac1 && count) out.push({ start: block * blockSize, bytes: sparse.subarray(o + chunkHeader, o + total) });
    block += count;
    o += total;
  }
  return out;
}

/** The bytes the RAW chunks write over `[start, end)`, or undefined if any byte of it isn't written raw. */
function readRaw(raw: readonly RawChunk[], start: number, end: number): Uint8Array | undefined {
  const out = new Uint8Array(end - start);
  let at = start;
  for (const c of raw) {
    const cEnd = c.start + c.bytes.length;
    if (cEnd <= at || c.start >= end) continue;
    if (c.start > at) return undefined; // a gap
    const take = Math.min(cEnd, end) - at;
    out.set(c.bytes.subarray(at - c.start, at - c.start + take), at - start);
    at += take;
    if (at === end) return out;
  }
  return at === end ? out : undefined;
}

/** `got` is the file's bytes from `logical` on, and zeros past its end (the surgical image pads its last block). */
function sameBytes(got: Uint8Array, file: Uint8Array, logical: number): boolean {
  for (let i = 0; i < got.length; i++) if (got[i] !== (logical + i < file.length ? file[logical + i] : 0)) return false;
  return true;
}

/** The partition byte ranges holding a file's blocks (up to its last block), each with the file offset it starts at. */
function fileBlocks(f: RestoredFile, blockSize: number): (ByteRange & { logical: number })[] {
  const need = Math.ceil(f.size / blockSize) * blockSize;
  const out: (ByteRange & { logical: number })[] = [];
  let next = 0;
  for (const e of [...f.extents].sort((a, b) => a.logical - b.logical)) {
    if (e.logical >= need) break;
    if (e.logical !== next || e.length <= 0 || e.physical < 0) throw new RestoreRefused(`The restore point's map of ${nameOf(f.path)} has a gap. ${NOTHING}`);
    const len = Math.min(e.length, need - e.logical);
    out.push({ start: e.physical, end: e.physical + len, logical: e.logical });
    next += e.length;
  }
  if (next < need) throw new RestoreRefused(`The restore point's map of ${nameOf(f.path)} doesn't cover the whole file. ${NOTHING}`);
  return out;
}
