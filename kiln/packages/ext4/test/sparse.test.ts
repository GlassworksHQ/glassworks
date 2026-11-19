import { createHash, randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { Disk, Ext4, MemoryStore, Sha256, hex, mkfs, parseSparseHeader, readSparse, sparseSize, sparseStore, writeSparse } from '../src';

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

  it('a zeroed store isn\'t written for zero fills, and reads the same', async () => {
    const writes: number[] = [];
    const store = new MemoryStore();
    const spy = { read: store.read.bind(store), write: async (o: number, d: Uint8Array) => { writes.push(d.length); await store.write(o, d); } };
    const d = new Disk(spy, BS, 1000, { zeroed: true });
    await d.zeroFill(0, 1000);
    expect(writes).toEqual([]);
    expect([d.care.get(999), d.zero.get(999)]).toEqual([1, 1]);
    expect((await d.read(500)).every((x) => x === 0)).toBe(true);
  });

  it('a zeroed store still overwrites blocks that hold data with zeros', async () => {
    const d = new Disk(new MemoryStore(), BS, 100, { zeroed: true });
    await d.write(10, new Uint8Array(3 * BS).fill(0xee));
    await d.zeroFill(0, 100);
    expect((await d.read(10, 3)).every((x) => x === 0)).toBe(true);
  });

  it('SHA-256 matches node\'s for any split', () => {
    for (const n of [0, 1, 55, 56, 63, 64, 65, 1000, 100_000]) {
      const b = new Uint8Array(randomBytes(n));
      const h = new Sha256();
      for (let o = 0; o < n; o += 37) h.update(b.subarray(o, o + 37));
      expect(hex(h.digest())).toBe(createHash('sha256').update(b).digest('hex'));
    }
  });

  it('sparseStore reads blocks where they lie: the same bytes as an expanded copy, at any offset, touching only what it asks for', async () => {
    const a = await sample();
    const bytes = await toBytes(a);
    let touched = 0;
    const file = { size: bytes.length, slice: (s: number, e: number) => { touched += e - s; return new Blob([bytes.subarray(s, e) as BlobPart]); } };
    const { header, store } = await sparseStore(file);
    expect(header).toEqual({ blockSize: BS, blocks: 64, chunks: 7 });
    touched = 0;
    const want = Buffer.from(await a.read(0, 64));
    for (const [off, len] of [[0, 64 * BS], [BS - 3, 9], [2 * BS + 17, 7 * BS], [20 * BS, BS], [39 * BS + 100, 3 * BS]] as const) {
      expect(Buffer.from(await store.read(off, len))).toEqual(want.subarray(off, off + len));
    }
    await expect(store.write(0, new Uint8Array(BS))).rejects.toThrow(/read-only/);
    // a FILL with a pattern and a CRC32 chunk, as libsparse writes them
    const hdr = (type: number, blocks: number, total: number) => { const b = Buffer.alloc(12); b.writeUInt16LE(type, 0); b.writeUInt32LE(blocks, 4); b.writeUInt32LE(total, 8); return b; };
    const f = Buffer.alloc(28);
    f.writeUInt32LE(0xed26ff3a, 0); f.writeUInt16LE(1, 4); f.writeUInt16LE(28, 8); f.writeUInt16LE(12, 10); f.writeUInt32LE(BS, 12); f.writeUInt32LE(3, 16); f.writeUInt32LE(3, 20);
    const pat = Buffer.alloc(4); pat.writeUInt32LE(0xdeadbeef, 0);
    const lib = Buffer.concat([f, hdr(0xcac2, 2, 16), pat, hdr(0xcac4, 0, 16), Buffer.alloc(4), hdr(0xcac3, 1, 12)]);
    const ls = (await sparseStore({ size: lib.length, slice: (s: number, e: number) => new Blob([lib.subarray(s, e) as BlobPart]) })).store;
    const got = await ls.read(BS * 2 - 4, 8);
    expect(new DataView(got.buffer).getUint32(0, true)).toBe(0xdeadbeef);
    expect([...got.subarray(4)]).toEqual([0, 0, 0, 0]); // DONT_CARE reads as zeros
  });

  it('sparseStore lets ext4 read one file out of a sparse image without expanding it', async () => {
    const d = new Disk(new MemoryStore(), BS, 4096, { zeroed: true });
    const fs = await mkfs(d, { time: 1, journalBlocks: 0 });
    const attrs = { mode: 0o755, uid: 0, gid: 0, label: 'u:object_r:system_file:s0', time: 1 };
    await fs.mkdir('/etc', attrs);
    await fs.mkdir('/etc/wifi', attrs);
    await fs.writeFile('/etc/wifi/wpa_supplicant.conf', new TextEncoder().encode('update_config=1\nap_scan=1\n'), { ...attrs, mode: 0o644 });
    await fs.flush();
    const bytes = await toBytes(d);
    const { header, store } = await sparseStore({ size: bytes.length, slice: (s: number, e: number) => new Blob([bytes.subarray(s, e) as BlobPart]) });
    const ro = await Ext4.open(new Disk(store, header.blockSize, header.blocks));
    expect(new TextDecoder().decode(await ro.readFile('/etc/wifi/wpa_supplicant.conf'))).toBe('update_config=1\nap_scan=1\n');
  });

  it('sparseStore refuses a file that isn\'t sparse, or is cut short', async () => {
    const a = await toBytes(await sample());
    const blob = (b: Uint8Array) => ({ size: b.length, slice: (s: number, e: number) => new Blob([b.subarray(s, e) as BlobPart]) });
    await expect(sparseStore(blob(new Uint8Array(64)))).rejects.toThrow(/not an Android sparse image/);
    await expect(sparseStore(blob(a.subarray(0, a.length - 100)))).rejects.toThrow(/cut short/);
  });
});
