// Kiln's own check on an in-place install: the write changes only blocks inside the bundle's extents, and the restore
// puts back exactly those blocks from raw bytes. Sparse images are built here chunk by chunk.
import { describe, expect, it } from 'vitest';
import { checkInPlace, mergeRanges, parseSparseWriteRanges, parseSparseWrites, type InPlaceCheckInput } from '../src/inplace';

const BS = 4096;
type C = { raw: number } | { fill: number } | { skip: number } | { crc: true };

/** A sparse image (format 1.0) from chunks, covering `blocks` in all. */
function sparse(chunks: C[], o: { blockSize?: number; magic?: number; trailing?: number; blocks?: number } = {}): Uint8Array {
  const bs = o.blockSize ?? BS;
  const parts: Uint8Array[] = [];
  let blocks = 0;
  for (const c of chunks) {
    const [type, count, body] = 'raw' in c ? [0xcac1, c.raw, c.raw * bs] : 'fill' in c ? [0xcac2, c.fill, 4] : 'skip' in c ? [0xcac3, c.skip, 0] : [0xcac4, 0, 4];
    const h = new DataView(new ArrayBuffer(12 + body));
    h.setUint16(0, type, true); h.setUint32(4, count, true); h.setUint32(8, 12 + body, true);
    parts.push(new Uint8Array(h.buffer));
    blocks += count;
  }
  const head = new DataView(new ArrayBuffer(28));
  head.setUint32(0, o.magic ?? 0xed26ff3a, true); head.setUint16(4, 1, true); head.setUint16(6, 0, true);
  head.setUint16(8, 28, true); head.setUint16(10, 12, true); head.setUint32(12, bs, true);
  head.setUint32(16, o.blocks ?? blocks, true); head.setUint32(20, chunks.length, true);
  const all = [new Uint8Array(head.buffer), ...parts, new Uint8Array(o.trailing ?? 0)];
  const out = new Uint8Array(all.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of all) { out.set(p, at); at += p.length; }
  return out;
}

const PART = 100 * BS;
// the bundle's file sits in blocks 10..14 and 20..22
const EXTENTS = [{ physical: 10 * BS, length: 4 * BS }, { physical: 20 * BS, length: 2 * BS }];
const good = (): InPlaceCheckInput => ({
  write: sparse([{ skip: 10 }, { raw: 2 }, { fill: 1 }, { skip: 7 }, { raw: 1 }, { skip: 79 }]),
  restore: sparse([{ skip: 10 }, { raw: 3 }, { skip: 7 }, { raw: 1 }, { crc: true }, { skip: 79 }]),
  extents: EXTENTS,
  partitionBytes: PART,
});

describe('parseSparseWriteRanges', () => {
  it('RAW and FILL write their blocks, DONT_CARE and CRC32 don\'t; touching ranges merge', () => {
    const r = parseSparseWrites(sparse([{ raw: 1 }, { fill: 2 }, { skip: 3 }, { crc: true }, { raw: 1 }]));
    expect(r).toEqual({ blockSize: BS, totalBytes: 7 * BS, ranges: [{ start: 0, end: 3 * BS }, { start: 6 * BS, end: 7 * BS }], filled: [{ start: BS, end: 3 * BS }] });
    expect(parseSparseWriteRanges(sparse([{ skip: 2 }]))).toEqual({ blockSize: BS, totalBytes: 2 * BS, ranges: [] });
  });

  it('refuses anything that isn\'t a whole sparse image', () => {
    expect(() => parseSparseWrites(new Uint8Array(10))).toThrow(/shorter than its header/);
    expect(() => parseSparseWrites(sparse([{ raw: 1 }], { magic: 0x12345678 }))).toThrow(/wrong magic/);
    expect(() => parseSparseWrites(sparse([{ raw: 1 }], { trailing: 3 }))).toThrow(/3 bytes after the last chunk/);
    expect(() => parseSparseWrites(sparse([{ raw: 1 }], { blocks: 5 }))).toThrow(/chunks cover 1 blocks, the header says 5/);
    expect(() => parseSparseWrites(sparse([{ raw: 2 }]).slice(0, 28 + 12 + BS))).toThrow(/runs past the end/);
    expect(() => parseSparseWrites(sparse([{ raw: 1 }], { blockSize: 1001 }))).toThrow(/block size 1001/);
  });

  it('mergeRanges sorts, merges and drops empty ranges', () => {
    expect(mergeRanges([{ start: 8, end: 10 }, { start: 0, end: 4 }, { start: 4, end: 6 }, { start: 5, end: 5 }])).toEqual([{ start: 0, end: 6 }, { start: 8, end: 10 }]);
  });
});

describe('checkInPlace', () => {
  it('passes a write inside the extents with a raw restore of the same blocks', () => {
    expect(() => checkInPlace(good())).not.toThrow();
  });

  it('refuses a write outside the extents', () => {
    const write = sparse([{ skip: 13 }, { raw: 2 }, { skip: 85 }]); // blocks 13..15: 14 is outside
    const restore = sparse([{ skip: 13 }, { raw: 2 }, { skip: 85 }]);
    expect(() => checkInPlace({ ...good(), write, restore })).toThrow(/outside the extents/);
    // spanning the gap between two extents is outside too
    const span = sparse([{ skip: 12 }, { raw: 10 }, { skip: 78 }]);
    expect(() => checkInPlace({ ...good(), write: span, restore: span, extents: [{ physical: 10 * BS, length: 4 * BS }, { physical: 14 * BS, length: 8 * BS }] })).not.toThrow();
    expect(() => checkInPlace({ ...good(), write: span, restore: span })).toThrow(/outside the extents/);
  });

  it('refuses a restore that misses a block, adds one, or fills', () => {
    const partial = sparse([{ skip: 10 }, { raw: 3 }, { skip: 87 }]);
    expect(() => checkInPlace({ ...good(), restore: partial })).toThrow(/must be the same blocks/);
    const more = sparse([{ skip: 10 }, { raw: 4 }, { skip: 6 }, { raw: 1 }, { skip: 79 }]);
    expect(() => checkInPlace({ ...good(), restore: more })).toThrow(/must be the same blocks/);
    const filled = sparse([{ skip: 10 }, { raw: 2 }, { fill: 1 }, { skip: 7 }, { raw: 1 }, { skip: 79 }]);
    expect(() => checkInPlace({ ...good(), restore: filled })).toThrow(/every restored byte must be the device's own/);
  });

  it('refuses images past the partition, or of different sizes or block sizes', () => {
    expect(() => checkInPlace({ ...good(), partitionBytes: 50 * BS })).toThrow(/the partition holds/);
    expect(() => checkInPlace({ ...good(), restore: sparse([{ skip: 10 }, { raw: 3 }, { skip: 7 }, { raw: 1 }, { skip: 80 }]) })).toThrow(/the restore/);
    const write = sparse([{ skip: 20 }, { raw: 4 }, { skip: 176 }], { blockSize: 2048 });
    expect(() => checkInPlace({ ...good(), write })).toThrow(/2048-byte blocks/);
    // the extents reach past the partition: a write there is refused even inside them
    const tail = sparse([{ skip: 99 }, { raw: 1 }]);
    expect(() => checkInPlace({ write: tail, restore: tail, extents: [{ physical: 99 * BS, length: 2 * BS }], partitionBytes: 99 * BS + 10 })).toThrow(/the partition holds|past the partition/);
  });

  it('refuses an empty write, a bad header, and nonsense extents', () => {
    const empty = sparse([{ skip: 100 }]);
    expect(() => checkInPlace({ ...good(), write: empty, restore: empty })).toThrow(/writes nothing/);
    expect(() => checkInPlace({ ...good(), write: new Uint8Array(64) })).toThrow(/write image: not a valid sparse image/);
    expect(() => checkInPlace({ ...good(), restore: sparse([{ raw: 1 }], { magic: 1 }) })).toThrow(/restore image: not a valid sparse image/);
    expect(() => checkInPlace({ ...good(), extents: [{ physical: -1, length: 4 }] })).toThrow(/isn't a byte range/);
    expect(() => checkInPlace({ ...good(), extents: [] })).toThrow(/outside the extents/);
    expect(() => checkInPlace({ ...good(), partitionBytes: 0 })).toThrow(/isn't a size/);
  });
});
