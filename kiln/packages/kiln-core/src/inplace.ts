// Kiln's own check on an in-place install, before any byte of it is written. A frame's code builds the write
// image(s) and the restore image from the bundle its agent read off the device; Kiln doesn't trust that code to get
// the blocks right. It reads both Android sparse images itself and refuses unless every block the write changes lies
// inside the extents the bundle names, and the restore puts back exactly those blocks, from raw bytes, no more and no
// less. A wrong offset in the frame's code then fails here, in the browser, never on the device.

/** Partition byte offsets, `[start, end)`. */
export interface ByteRange { start: number; end: number }

export interface InPlaceCheckInput {
  /** The sparse image Kiln is about to flash. */
  write: Uint8Array;
  /** The sparse image that puts back every block `write` changes. */
  restore: Uint8Array;
  /** Where the bundle's file sits on the partition, in bytes: each `[physical, physical + length)`. */
  extents: readonly { physical: number; length: number }[];
  /** The partition's size, as fastboot reports it. */
  partitionBytes: number;
}

/** What a sparse image writes: every block a RAW or FILL chunk covers (merged), and the part of that from FILL. */
export interface SparseWrites { blockSize: number; totalBytes: number; ranges: ByteRange[]; filled: ByteRange[] }

const MAGIC = 0xed26ff3a;
const FILE_HEADER = 28;
const CHUNK_HEADER = 12;
const RAW = 0xcac1, FILL = 0xcac2, DONT_CARE = 0xcac3, CRC32 = 0xcac4;

/** Sorts and merges touching or overlapping ranges, dropping empty ones. */
export function mergeRanges(ranges: readonly ByteRange[]): ByteRange[] {
  const out: ByteRange[] = [];
  for (const r of [...ranges].filter((x) => x.end > x.start).sort((a, b) => a.start - b.start)) {
    const last = out[out.length - 1];
    if (last && r.start <= last.end) last.end = Math.max(last.end, r.end);
    else out.push({ start: r.start, end: r.end });
  }
  return out;
}

/**
 * Reads an Android sparse image (format 1.0) and returns the byte ranges it writes. RAW and FILL chunks write their
 * blocks; DONT_CARE skips blocks without writing; CRC32 writes nothing. Throws on anything that isn't a whole,
 * well-formed sparse image: a bad header, a chunk that runs past the end, block counts that don't add up, or bytes
 * after the last chunk.
 */
export function parseSparseWrites(sparse: Uint8Array): SparseWrites {
  const bad = (why: string): never => { throw new Error(`not a valid sparse image: ${why}`); };
  if (sparse.byteLength < FILE_HEADER) bad('shorter than its header');
  const v = new DataView(sparse.buffer, sparse.byteOffset, sparse.byteLength);
  if (v.getUint32(0, true) !== MAGIC) bad('wrong magic');
  if (v.getUint16(4, true) !== 1) bad(`major version ${v.getUint16(4, true)}`);
  const fileHeader = v.getUint16(8, true), chunkHeader = v.getUint16(10, true);
  if (fileHeader < FILE_HEADER || chunkHeader < CHUNK_HEADER) bad('header sizes too small');
  const blockSize = v.getUint32(12, true), blocks = v.getUint32(16, true), chunks = v.getUint32(20, true);
  if (!blockSize || blockSize % 4) bad(`block size ${blockSize}`);
  const ranges: ByteRange[] = [], filled: ByteRange[] = [];
  let o = fileHeader, block = 0;
  for (let c = 0; c < chunks; c++) {
    if (o + chunkHeader > sparse.byteLength) bad(`chunk ${c + 1} runs past the end`);
    const type = v.getUint16(o, true), count = v.getUint32(o + 4, true), total = v.getUint32(o + 8, true);
    const body = total - chunkHeader;
    if (total < chunkHeader || o + total > sparse.byteLength) bad(`chunk ${c + 1} runs past the end`);
    const range = { start: block * blockSize, end: (block + count) * blockSize };
    switch (type) {
      case RAW:
        if (body !== count * blockSize) bad(`raw chunk ${c + 1} holds ${body} bytes for ${count} blocks`);
        if (count) ranges.push(range);
        break;
      case FILL:
        if (body !== 4) bad(`fill chunk ${c + 1} isn't 4 bytes`);
        if (count) { ranges.push(range); filled.push(range); }
        break;
      case DONT_CARE:
        if (body !== 0) bad(`skip chunk ${c + 1} carries data`);
        break;
      case CRC32:
        if (body !== 4 || count !== 0) bad(`crc chunk ${c + 1} is malformed`);
        break;
      default:
        bad(`chunk ${c + 1} has unknown type 0x${type.toString(16)}`);
    }
    block += count;
    if (block > blocks) bad('chunks cover more blocks than the header says');
    o += total;
  }
  if (block !== blocks) bad(`chunks cover ${block} blocks, the header says ${blocks}`);
  if (o !== sparse.byteLength) bad(`${sparse.byteLength - o} bytes after the last chunk`);
  const totalBytes = blocks * blockSize;
  if (!Number.isSafeInteger(totalBytes)) bad('too large');
  return { blockSize, totalBytes, ranges: mergeRanges(ranges), filled: mergeRanges(filled) };
}

/** The byte ranges a sparse image writes (RAW and FILL), merged. */
export function parseSparseWriteRanges(sparse: Uint8Array): { blockSize: number; totalBytes: number; ranges: ByteRange[] } {
  const { blockSize, totalBytes, ranges } = parseSparseWrites(sparse);
  return { blockSize, totalBytes, ranges };
}

const fmt = (r: ByteRange) => `[${r.start}, ${r.end})`;

/** Throws, saying why, unless `write` and `restore` are safe to flash as an in-place install (see the top of this file). */
export function checkInPlace(input: InPlaceCheckInput): void {
  const { partitionBytes } = input;
  if (!Number.isSafeInteger(partitionBytes) || partitionBytes <= 0) throw new Error(`in-place: partition size ${partitionBytes} isn't a size`);
  let write: SparseWrites, restore: SparseWrites;
  try { write = parseSparseWrites(input.write); } catch (e) { throw new Error(`in-place write image: ${(e as Error).message}`); }
  try { restore = parseSparseWrites(input.restore); } catch (e) { throw new Error(`in-place restore image: ${(e as Error).message}`); }
  if (write.blockSize !== restore.blockSize) throw new Error(`in-place: the write uses ${write.blockSize}-byte blocks, the restore ${restore.blockSize}`);
  if (write.totalBytes !== restore.totalBytes) throw new Error(`in-place: the write covers ${write.totalBytes} bytes, the restore ${restore.totalBytes}`);
  if (write.totalBytes > partitionBytes) throw new Error(`in-place: the images cover ${write.totalBytes} bytes, the partition holds ${partitionBytes}`);
  if (!write.ranges.length) throw new Error('in-place: the write image writes nothing');

  for (const e of input.extents) {
    if (!Number.isSafeInteger(e.physical) || !Number.isSafeInteger(e.length) || e.physical < 0 || e.length <= 0) {
      throw new Error(`in-place: extent ${e.physical}+${e.length} isn't a byte range`);
    }
  }
  const allowed = mergeRanges(input.extents.map((e) => ({ start: e.physical, end: e.physical + e.length })));
  for (const r of write.ranges) {
    if (r.end > partitionBytes) throw new Error(`in-place: the write reaches ${fmt(r)}, past the partition's ${partitionBytes} bytes`);
    if (!allowed.some((a) => a.start <= r.start && r.end <= a.end)) {
      throw new Error(`in-place: the write changes ${fmt(r)}, outside the extents the bundle names`);
    }
  }

  if (restore.filled.length) throw new Error(`in-place: the restore fills ${fmt(restore.filled[0]!)}: every restored byte must be the device's own (raw)`);
  const same = write.ranges.length === restore.ranges.length
    && write.ranges.every((r, i) => r.start === restore.ranges[i]!.start && r.end === restore.ranges[i]!.end);
  if (!same) {
    const w = write.ranges.map(fmt).join(' '), u = restore.ranges.map(fmt).join(' ');
    throw new Error(`in-place: the restore puts back ${u || 'nothing'}, but the write changes ${w}: they must be the same blocks`);
  }
}
