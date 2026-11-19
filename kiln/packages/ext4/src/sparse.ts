// Android sparse images (AOSP libsparse's format), streamed: read one into a Disk as it arrives,
// and write a Disk back out as one, a chunk at a time. Format: a 28-byte file header, then chunks,
// each a 12-byte header and its data: RAW (blocks), FILL (a 4-byte pattern), DONT_CARE (nothing),
// CRC32 (4 bytes, skipped).
import { Disk, type Store } from './disk';

export const SPARSE_MAGIC = 0xed26ff3a;
const FILE_HDR = 28;
const CHUNK_HDR = 12;
export const enum Chunk { Raw = 0xcac1, Fill = 0xcac2, DontCare = 0xcac3, Crc32 = 0xcac4 }

export interface SparseHeader { blockSize: number; blocks: number; chunks: number }

/** Takes exact byte counts from an async source of arbitrarily sized pieces. */
class Reader {
  private pending: Uint8Array[] = [];
  private have = 0;
  private done = false;
  consumed = 0;
  constructor(private readonly it: AsyncIterator<Uint8Array>) {}

  private async more(): Promise<boolean> {
    if (this.done) return false;
    const r = await this.it.next();
    if (r.done) { this.done = true; return false; }
    if (r.value.length) { this.pending.push(r.value); this.have += r.value.length; }
    return true;
  }

  /** Up to `max` bytes that are already here or come next, without copying; empty at the end. */
  async some(max: number): Promise<Uint8Array> {
    while (!this.have) if (!(await this.more())) return new Uint8Array(0);
    const first = this.pending[0]!;
    const n = Math.min(max, first.length);
    const out = first.subarray(0, n);
    if (n === first.length) this.pending.shift(); else this.pending[0] = first.subarray(n);
    this.have -= n;
    this.consumed += n;
    return out;
  }

  /** Exactly `n` bytes, or fewer at the end of the source. */
  async take(n: number): Promise<Uint8Array> {
    const out = new Uint8Array(n);
    let o = 0;
    while (o < n) {
      const p = await this.some(n - o);
      if (!p.length) return out.subarray(0, o);
      out.set(p, o);
      o += p.length;
    }
    return out;
  }
}

export function parseSparseHeader(b: Uint8Array): SparseHeader | undefined {
  if (b.length < FILE_HDR) return undefined;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  if (dv.getUint32(0, true) !== SPARSE_MAGIC) return undefined;
  if (dv.getUint16(4, true) !== 1) throw new Error(`sparse image version ${dv.getUint16(4, true)} isn't supported`);
  const fileHdr = dv.getUint16(8, true), chunkHdr = dv.getUint16(10, true);
  if (fileHdr !== FILE_HDR || chunkHdr !== CHUNK_HDR) throw new Error(`sparse header sizes ${fileHdr}/${chunkHdr} aren't supported`);
  const blockSize = dv.getUint32(12, true);
  if (!blockSize || blockSize % 4) throw new Error(`sparse block size ${blockSize} is invalid`);
  return { blockSize, blocks: dv.getUint32(16, true), chunks: dv.getUint32(20, true) };
}

async function* pieces(source: AsyncIterable<Uint8Array> | ReadableStream<Uint8Array>, onBytes?: (b: Uint8Array) => void): AsyncGenerator<Uint8Array> {
  if (Symbol.asyncIterator in source) {
    for await (const p of source as AsyncIterable<Uint8Array>) { onBytes?.(p); yield p; }
    return;
  }
  const r = (source as ReadableStream<Uint8Array>).getReader();
  try {
    for (;;) { const { done, value } = await r.read(); if (done) return; onBytes?.(value); yield value; }
  } finally { r.releaseLock(); }
}

export interface ReadOptions {
  /** Every byte of the source, in order, as it is read: for a hash over the whole file. */
  onBytes?: (b: Uint8Array) => void;
  /** Bytes of the source read so far. */
  onProgress?: (read: number) => void;
}

/**
 * Streams a sparse image into a new Disk that `open` makes once the header says how big it is. A
 * source that isn't sparse is refused: the caller decides what a raw image means.
 */
export async function readSparse(source: AsyncIterable<Uint8Array> | ReadableStream<Uint8Array>,
  open: (h: SparseHeader) => Disk | Promise<Disk>, o: ReadOptions = {}): Promise<{ disk: Disk; header: SparseHeader }> {
  const rd = new Reader(pieces(source, o.onBytes)[Symbol.asyncIterator]());
  const header = parseSparseHeader(await rd.take(FILE_HDR));
  if (!header) throw new Error('this is not an Android sparse image');
  const disk = await open(header);
  if (disk.blockSize !== header.blockSize || disk.blocks !== header.blocks) throw new Error('the disk doesn\'t match the sparse header');
  const bs = header.blockSize;
  let block = 0;
  const STEP = 256 * bs;
  for (let c = 0; c < header.chunks; c++) {
    const h = await rd.take(CHUNK_HDR);
    if (h.length < CHUNK_HDR) throw new Error(`the sparse image ends after ${c} of ${header.chunks} chunks (cut short?)`);
    const dv = new DataView(h.buffer, h.byteOffset, CHUNK_HDR);
    const type = dv.getUint16(0, true), count = dv.getUint32(4, true), total = dv.getUint32(8, true);
    if (block + count > header.blocks) throw new Error(`sparse chunk ${c} runs past the image's ${header.blocks} blocks`);
    switch (type) {
      case Chunk.Raw: {
        if (total !== CHUNK_HDR + count * bs) throw new Error(`sparse chunk ${c} has the wrong size`);
        let left = count * bs, at = block;
        // gather a block-aligned piece, then write it
        while (left) {
          const piece = await rd.take(Math.min(left, STEP));
          if (piece.length < Math.min(left, STEP)) throw new Error(`the sparse image ends inside chunk ${c} (cut short?)`);
          await disk.write(at, piece);
          at += piece.length / bs;
          left -= piece.length;
          o.onProgress?.(rd.consumed);
        }
        break;
      }
      case Chunk.Fill: {
        if (total !== CHUNK_HDR + 4) throw new Error(`sparse chunk ${c} has the wrong size`);
        const pat = await rd.take(4);
        if (pat.length < 4) throw new Error(`the sparse image ends inside chunk ${c} (cut short?)`);
        const v = new DataView(pat.buffer, pat.byteOffset, 4).getUint32(0, true);
        if (v === 0) await disk.zeroFill(block, count);
        else {
          const buf = new Uint8Array(Math.min(count, 256) * bs);
          const bv = new DataView(buf.buffer);
          for (let i = 0; i < buf.length; i += 4) bv.setUint32(i, v, true);
          for (let b = block; b < block + count; b += 256) {
            const n = Math.min(256, block + count - b);
            await disk.write(b, buf.subarray(0, n * bs));
          }
        }
        break;
      }
      case Chunk.DontCare:
        if (total !== CHUNK_HDR) throw new Error(`sparse chunk ${c} has the wrong size`);
        break;
      case Chunk.Crc32: {
        if (total !== CHUNK_HDR + 4) throw new Error(`sparse chunk ${c} has the wrong size`);
        if ((await rd.take(4)).length < 4) throw new Error(`the sparse image ends inside chunk ${c} (cut short?)`);
        break;
      }
      default:
        throw new Error(`sparse chunk ${c} has an unknown type 0x${type.toString(16)}`);
    }
    if (type !== Chunk.Crc32) block += count;
    o.onProgress?.(rd.consumed);
  }
  if (block !== header.blocks) throw new Error(`the sparse chunks cover ${block} of ${header.blocks} blocks`);
  if ((await rd.some(1)).length) throw new Error('there are bytes after the sparse image\'s last chunk');
  return { disk, header };
}

export interface WriteOptions {
  /** The longest RAW chunk, in blocks (default 4096, 16 MiB at 4 KiB): a flash piece is split between chunks. */
  maxRawBlocks?: number;
  /** Send zero blocks as FILL 0 (default) or as RAW zeros, for a bootloader without FILL. */
  fill?: boolean;
  /** Blocks written so far, of the disk's total. */
  onProgress?: (block: number) => void;
}

type Run = { kind: 'raw' | 'zero' | 'skip'; from: number; count: number };

function runs(disk: Disk, o: WriteOptions): Run[] {
  const max = o.maxRawBlocks ?? 4096;
  const fill = o.fill ?? true;
  const out: Run[] = [];
  for (let b = 0; b < disk.blocks;) {
    const kind = !disk.care.get(b) ? 'skip' : fill && disk.zero.get(b) ? 'zero' : 'raw';
    let n = 1;
    while (b + n < disk.blocks && (kind !== 'raw' || n < max)) {
      const k = !disk.care.get(b + n) ? 'skip' : fill && disk.zero.get(b + n) ? 'zero' : 'raw';
      if (k !== kind) break;
      n++;
    }
    out.push({ kind, from: b, count: n });
    b += n;
  }
  return out;
}

/** The size in bytes `writeSparse` will produce, without reading any data. */
export function sparseSize(disk: Disk, o: WriteOptions = {}): number {
  return runs(disk, o).reduce((n, r) => n + CHUNK_HDR + (r.kind === 'raw' ? r.count * disk.blockSize : r.kind === 'zero' ? 4 : 0), FILE_HDR);
}

/** Writes `disk` as a sparse image to `sink`, in order: the care blocks as RAW or FILL, the rest as DONT_CARE. */
export async function writeSparse(disk: Disk, sink: (b: Uint8Array) => Promise<void> | void, o: WriteOptions = {}): Promise<void> {
  const rs = runs(disk, o);
  const head = new Uint8Array(FILE_HDR);
  const hv = new DataView(head.buffer);
  hv.setUint32(0, SPARSE_MAGIC, true); hv.setUint16(4, 1, true); hv.setUint16(6, 0, true);
  hv.setUint16(8, FILE_HDR, true); hv.setUint16(10, CHUNK_HDR, true);
  hv.setUint32(12, disk.blockSize, true); hv.setUint32(16, disk.blocks, true); hv.setUint32(20, rs.length, true); hv.setUint32(24, 0, true);
  await sink(head);
  for (const r of rs) {
    const ch = new Uint8Array(CHUNK_HDR + (r.kind === 'zero' ? 4 : 0));
    const cv = new DataView(ch.buffer);
    cv.setUint16(0, r.kind === 'raw' ? Chunk.Raw : r.kind === 'zero' ? Chunk.Fill : Chunk.DontCare, true);
    cv.setUint32(4, r.count, true);
    cv.setUint32(8, CHUNK_HDR + (r.kind === 'raw' ? r.count * disk.blockSize : r.kind === 'zero' ? 4 : 0), true);
    await sink(ch);
    if (r.kind === 'raw') {
      for (let b = r.from; b < r.from + r.count; b += 256) await sink(await disk.read(b, Math.min(256, r.from + r.count - b)));
    }
    o.onProgress?.(r.from + r.count);
  }
}

/** Something with random reads: a File or Blob (page or worker), or a test's bytes. */
export interface RandomReadable { size: number; slice(start: number, end: number): { arrayBuffer(): Promise<ArrayBuffer> } }

type Piece = { block: number; count: number } & ({ kind: 'raw'; at: number } | { kind: 'fill'; value: number } | { kind: 'zero' });

/**
 * A sparse image read where it lies, without expanding it: the chunk headers are read once (a few
 * hundred 12-byte reads), then each read takes only the blocks it asks for. For reading a file or two
 * out of a large image (a system image of 850 MB sparse, 1.3 GB expanded, in about 0.2 s). Read-only: a
 * write throws. DONT_CARE blocks read as zeros.
 */
export async function sparseStore(file: RandomReadable): Promise<{ header: SparseHeader; store: Store }> {
  const bytes = async (from: number, n: number) => new Uint8Array(await file.slice(from, from + n).arrayBuffer());
  const header = parseSparseHeader(await bytes(0, FILE_HDR));
  if (!header) throw new Error('this is not an Android sparse image');
  const hdrSize = new DataView((await bytes(8, 2)).buffer).getUint16(0, true);
  const chunkHdr = new DataView((await bytes(10, 2)).buffer).getUint16(0, true);
  const bs = header.blockSize;
  const runs: Piece[] = [];
  let at = hdrSize, block = 0;
  for (let c = 0; c < header.chunks; c++) {
    const h = await bytes(at, chunkHdr);
    if (h.length < CHUNK_HDR) throw new Error(`the sparse image ends after ${c} of ${header.chunks} chunks (cut short?)`);
    const dv = new DataView(h.buffer, h.byteOffset, h.length);
    const type = dv.getUint16(0, true), count = dv.getUint32(4, true), total = dv.getUint32(8, true);
    if (block + count > header.blocks) throw new Error(`sparse chunk ${c} runs past the image's ${header.blocks} blocks`);
    const data = at + chunkHdr;
    if (type === Chunk.Raw) {
      if (total !== chunkHdr + count * bs) throw new Error(`sparse chunk ${c} has the wrong size`);
      if (data + count * bs > file.size) throw new Error(`the sparse image ends inside chunk ${c} (cut short?)`);
      runs.push({ block, count, kind: 'raw', at: data });
    } else if (type === Chunk.Fill) {
      if (total !== chunkHdr + 4) throw new Error(`sparse chunk ${c} has the wrong size`);
      const value = new DataView((await bytes(data, 4)).buffer).getUint32(0, true);
      runs.push(value === 0 ? { block, count, kind: 'zero' } : { block, count, kind: 'fill', value });
    } else if (type === Chunk.DontCare) {
      if (total !== chunkHdr) throw new Error(`sparse chunk ${c} has the wrong size`);
      runs.push({ block, count, kind: 'zero' });
    } else if (type === Chunk.Crc32) {
      // as readSparse: its 4-byte payload, there in full (it isn't checked: the caller hashes what it needs)
      if (total !== chunkHdr + 4) throw new Error(`sparse chunk ${c} has the wrong size`);
      if (data + 4 > file.size) throw new Error(`the sparse image ends inside chunk ${c} (cut short?)`);
    } else {
      throw new Error(`sparse chunk ${c} has an unknown type 0x${type.toString(16)}`);
    }
    if (type !== Chunk.Crc32) block += count;
    at += total;
  }
  if (block !== header.blocks) throw new Error(`the sparse chunks cover ${block} of ${header.blocks} blocks`);
  const find = (b: number) => { // the run holding block b: binary search over the ordered runs
    let lo = 0, hi = runs.length - 1;
    while (lo <= hi) { const m = (lo + hi) >> 1, r = runs[m]!; if (b < r.block) hi = m - 1; else if (b >= r.block + r.count) lo = m + 1; else return r; }
    throw new Error(`block ${b} is in no sparse chunk`);
  };
  const store: Store = {
    async read(offset, length) {
      const out = new Uint8Array(length);
      let pos = offset;
      while (pos < offset + length) {
        const r = find(Math.floor(pos / bs));
        const runEnd = (r.block + r.count) * bs;
        const n = Math.min(offset + length, runEnd) - pos;
        const into = pos - offset;
        if (r.kind === 'raw') out.set(await bytes(r.at + (pos - r.block * bs), n), into);
        else if (r.kind === 'fill') {
          const pat = new Uint8Array(4); new DataView(pat.buffer).setUint32(0, r.value, true);
          for (let i = 0; i < n; i++) out[into + i] = pat[(pos + i) % 4]!;
        }
        pos += n;
      }
      return out;
    },
    async write() { throw new Error('this sparse image is read-only'); },
  };
  return { header, store };
}
