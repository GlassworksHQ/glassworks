import { createHash, randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { Disk, MemoryStore, Sha256, hex, parseSparseHeader, readSparse, sparseSize, writeSparse } from '../src';

const BS = 4096;

/** Splits `b` into pieces of awkward sizes, as a network or file stream would hand it over. */
async function* ragged(b: Uint8Array, seed = 7) {
  let s = seed;
  for (let o = 0; o < b.length;) {
    s = (s * 1103515245 + 12345) & 0x7fffffff;
    const n = 1 + (s % 9000);
    yield b.subarray(o, o + n);
    o += n;
  }
}

async function sample(): Promise<Disk> {
  const d = new Disk(new MemoryStore(), BS, 64);
  await d.write(0, new Uint8Array(randomBytes(3 * BS)));
  await d.zeroFill(3, 5);
  await d.write(20, new Uint8Array(randomBytes(BS)));
  await d.write(40, new Uint8Array(randomBytes(10 * BS)));
  return d;
}

async function toBytes(d: Disk, o = {}): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  await writeSparse(d, (b) => { parts.push(b.slice()); }, o);
  return Buffer.concat(parts);
}

describe('sparse images', () => {
  it('round-trip: the same blocks, the same care and zero maps, from a stream cut anywhere', async () => {
    const a = await sample();
    const bytes = await toBytes(a);
    expect(bytes.length).toBe(sparseSize(a));
    expect(parseSparseHeader(bytes)).toEqual({ blockSize: BS, blocks: 64, chunks: 7 }); // raw, fill, skip, raw, skip, raw, skip
    const hash = new Sha256();
    let read = 0;
    const { disk: b } = await readSparse(ragged(bytes), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks),
      { onBytes: (p) => hash.update(p), onProgress: (n) => { read = n; } });
    expect(read).toBe(bytes.length);
    expect(hex(hash.digest())).toBe(createHash('sha256').update(bytes).digest('hex'));
    expect(Buffer.from(await b.read(0, 64))).toEqual(Buffer.from(await a.read(0, 64)));
    expect(b.care.bits).toEqual(a.care.bits);
    expect(b.zero.bits).toEqual(a.zero.bits);
  });

  it('splits long RAW runs, and writes zeros as RAW for a bootloader without FILL', async () => {
    const a = await sample();
    const h = parseSparseHeader(await toBytes(a, { maxRawBlocks: 4, fill: false }))!;
    expect(h.chunks).toBe(2 + 1 + 1 + 1 + 3 + 1); // 0-3 and 4-7 raw, skip, 20 raw, skip, 40-49 in three, skip
  });

  it('reads FILL with a pattern and skips CRC32 chunks (as libsparse writes them)', async () => {
    const hdr = (type: number, blocks: number, total: number) => {
      const b = Buffer.alloc(12); b.writeUInt16LE(type, 0); b.writeUInt32LE(blocks, 4); b.writeUInt32LE(total, 8); return b;
    };
    const file = Buffer.alloc(28);
    file.writeUInt32LE(0xed26ff3a, 0); file.writeUInt16LE(1, 4); file.writeUInt16LE(28, 8); file.writeUInt16LE(12, 10);
    file.writeUInt32LE(BS, 12); file.writeUInt32LE(3, 16); file.writeUInt32LE(3, 20);
    const pat = Buffer.alloc(4); pat.writeUInt32LE(0xdeadbeef, 0);
    const bytes = Buffer.concat([file, hdr(0xcac2, 2, 16), pat, hdr(0xcac4, 0, 16), Buffer.alloc(4), hdr(0xcac3, 1, 12)]);
    const { disk } = await readSparse(ragged(bytes), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks));
    const two = await disk.read(0, 2);
    expect(new DataView(two.buffer).getUint32(BS * 2 - 4, true)).toBe(0xdeadbeef);
    expect([disk.care.get(0), disk.care.get(1), disk.care.get(2)]).toEqual([1, 1, 0]);
  });

  it('refuses a FILL cut short and a CRC32 chunk of the wrong size', async () => {
    const hdr = (type: number, blocks: number, total: number) => {
      const b = Buffer.alloc(12); b.writeUInt16LE(type, 0); b.writeUInt32LE(blocks, 4); b.writeUInt32LE(total, 8); return b;
    };
    const file = Buffer.alloc(28);
    file.writeUInt32LE(0xed26ff3a, 0); file.writeUInt16LE(1, 4); file.writeUInt16LE(28, 8); file.writeUInt16LE(12, 10);
    file.writeUInt32LE(BS, 12); file.writeUInt32LE(2, 16); file.writeUInt32LE(2, 20);
    const open = (h: { blockSize: number; blocks: number }) => new Disk(new MemoryStore(), h.blockSize, h.blocks);
    await expect(readSparse(ragged(Buffer.concat([file, hdr(0xcac2, 2, 16), Buffer.alloc(2)])), open)).rejects.toThrow(/ends inside chunk 0/);
    await expect(readSparse(ragged(Buffer.concat([file, hdr(0xcac4, 0, 1 << 30)])), open)).rejects.toThrow(/chunk 0 has the wrong size/);
  });

  it('refuses a file cut short, and a file that isn\'t sparse', async () => {
    const bytes = await toBytes(await sample());
    const open = (h: { blockSize: number; blocks: number }) => new Disk(new MemoryStore(), h.blockSize, h.blocks);
    await expect(readSparse(ragged(bytes.subarray(0, bytes.length - 5000)), open)).rejects.toThrow(/cut short/);
    await expect(readSparse(ragged(new Uint8Array(4096)), open)).rejects.toThrow(/not an Android sparse image/);
  });

  it('SHA-256 matches node\'s for any split', () => {
    for (const n of [0, 1, 55, 56, 63, 64, 65, 1000, 100_000]) {
      const b = new Uint8Array(randomBytes(n));
      const h = new Sha256();
      for (let o = 0; o < n; o += 37) h.update(b.subarray(o, o + 37));
      expect(hex(h.digest())).toBe(createHash('sha256').update(b).digest('hex'));
    }
  });
});
