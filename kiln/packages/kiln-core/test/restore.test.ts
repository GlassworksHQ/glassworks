// Kiln's checks on an in-place way back before its first write (restore.ts): the restore point is the one Kiln saved,
// made on this device, a whole image for this partition, and it puts back exactly the original files it records; and
// a file on the device is what the install wrote, the original, or neither. Every serial here is made up.
import { describe, expect, it } from 'vitest';
import { sha256Hex } from '../src/bundle';
import { RestoreRefused, checkRestorePoint, deviceFileState, recordRestoreFiles, restoreFileRecord, type RestoreCheckInput, type RestoredFile } from '../src/restore';
import type { NamedPart } from '../src/backup';

const BS = 4096;
const BLOCKS = 64;
const SERIAL = '0000fake01';

type Chunk = { raw: Uint8Array } | { skip: number } | { fill: number };

/** A sparse image (format 1.0) from chunks; a RAW chunk's bytes must be whole blocks. */
function sparse(chunks: Chunk[], blocks = BLOCKS): Uint8Array {
  const parts: Uint8Array[] = [];
  let used = 0;
  for (const c of chunks) {
    const [type, count, body] = 'raw' in c ? [0xcac1, c.raw.length / BS, c.raw] : 'fill' in c ? [0xcac2, c.fill, new Uint8Array(4)] : [0xcac3, c.skip, new Uint8Array(0)];
    const h = new DataView(new ArrayBuffer(12));
    h.setUint16(0, type, true); h.setUint32(4, count, true); h.setUint32(8, 12 + body.length, true);
    parts.push(new Uint8Array(h.buffer), body);
    used += count;
  }
  if (used < blocks) {
    const h = new DataView(new ArrayBuffer(12));
    h.setUint16(0, 0xcac3, true); h.setUint32(4, blocks - used, true); h.setUint32(8, 12, true);
    parts.push(new Uint8Array(h.buffer));
  }
  const head = new DataView(new ArrayBuffer(28));
  head.setUint32(0, 0xed26ff3a, true); head.setUint16(4, 1, true); head.setUint16(8, 28, true); head.setUint16(10, 12, true);
  head.setUint32(12, BS, true); head.setUint32(16, blocks, true); head.setUint32(20, chunks.length + (used < blocks ? 1 : 0), true);
  const all = [new Uint8Array(head.buffer), ...parts];
  const out = new Uint8Array(all.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of all) { out.set(p, at); at += p.length; }
  return out;
}

/** `n` bytes of a made-up file. */
const fileOf = (n: number, seed: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) & 0xff);
/** A file's bytes padded with zeros to whole blocks, as the surgical image writes its last block. */
const padded = (b: Uint8Array, from = 0, len = Math.ceil(b.length / BS) * BS - from) => { const o = new Uint8Array(len); o.set(b.subarray(from, from + len)); return o; };

// the original file: 2.5 blocks, in blocks 10..11 and 20 (logical 0..2 blocks, then 2..3)
const ORIG = fileOf(BS * 2 + BS / 2, 7);
const WRITTEN = fileOf(ORIG.length, 99);
const EXTENTS = [{ logical: 0, physical: 10 * BS, length: 2 * BS }, { logical: 2 * BS, physical: 20 * BS, length: BS }];
const goodSparse = () => sparse([{ skip: 10 }, { raw: padded(ORIG, 0, 2 * BS) }, { skip: 8 }, { raw: padded(ORIG, 2 * BS, BS) }]);

async function point(o: { sparse?: Uint8Array; orig?: Uint8Array; record?: boolean; deviceIdHash?: unknown; extra?: NamedPart } = {}) {
  const files: NamedPart[] = [{ filename: 'restore.sparse', data: o.sparse ?? goodSparse() }, { filename: 'file.orig', data: o.orig ?? ORIG }];
  const kiln = o.record === false ? {} : { kiln: { files: await recordRestoreFiles(files) } };
  if (o.extra) files.push(o.extra);
  const manifest: Record<string, unknown> = { deviceIdHash: 'deviceIdHash' in o ? o.deviceIdHash : await sha256Hex(new TextEncoder().encode(SERIAL)), ...kiln };
  return { files, manifest };
}
const restored = async (o: Partial<RestoredFile> = {}): Promise<RestoredFile[]> => [{
  path: '/system/framework/file.jar', file: 'file.orig', size: ORIG.length, sha256: await sha256Hex(ORIG), extents: EXTENTS,
  written: { sha256: await sha256Hex(WRITTEN), size: WRITTEN.length }, ...o,
}];
async function input(p: Awaited<ReturnType<typeof point>>, o: Partial<RestoreCheckInput> = {}): Promise<RestoreCheckInput> {
  return { files: p.files, sparse: 'restore.sparse', manifest: p.manifest, restored: await restored(), partitionBytes: BLOCKS * BS, partition: 'system', serial: SERIAL, ...o };
}
const refuses = async (i: RestoreCheckInput, why: RegExp) => {
  const e = await checkRestorePoint(i).then(() => undefined, (x: unknown) => x);
  expect(e).toBeInstanceOf(RestoreRefused);
  expect((e as Error).message).toMatch(why);
  expect((e as Error).message).toMatch(/Nothing was written\.$/);
};

describe('checkRestorePoint', () => {
  it('passes a restore point Kiln saved on this device, and says each check that ran', async () => {
    const lines = await checkRestorePoint(await input(await point()));
    expect(lines).toEqual([
      'restore point: each of its 2 files has the size and SHA-256 recorded when it was saved',
      'restore point: made on this device (the SHA-256 of its serial matches)',
      `restore image: whole, for this system partition (${BLOCKS * BS} bytes), every block raw`,
      expect.stringMatching(/^restore image: puts back the original file\.jar \(10240 bytes, SHA-256 [0-9a-f]{12}…\) in its own blocks$/),
      'restore image: writes no block outside those files',
    ]);
    expect(lines.join('\n')).not.toContain(SERIAL);
  });

  it('records each file but the manifest, by size and SHA-256, and reads the record back', async () => {
    const files = [{ filename: 'a', data: new Uint8Array([1, 2]) }, { filename: 'manifest.json', data: new Uint8Array([3]) }];
    const rec = await recordRestoreFiles(files);
    expect(rec).toEqual({ a: { size: 2, sha256: await sha256Hex(new Uint8Array([1, 2])) } });
    expect(restoreFileRecord({ kiln: { files: rec } })).toEqual(rec);
    expect(restoreFileRecord({})).toBeUndefined();
    expect(restoreFileRecord({ kiln: { files: { a: { size: '2', sha256: 'x' } } } })).toBeUndefined();
  });

  describe('the restore point is the one Kiln saved', () => {
    it('refuses a file whose bytes changed since it was saved', async () => {
      const p = await point();
      p.files[1] = { filename: 'file.orig', data: ORIG.map((x, i) => (i === 5 ? x ^ 1 : x)) };
      await refuses(await input(p), /^file\.orig isn't the file Kiln saved in this restore point\./);
    });
    it('refuses a file of another size', async () => {
      const p = await point();
      p.files[0] = { filename: 'restore.sparse', data: p.files[0]!.data.subarray(0, p.files[0]!.data.length - 1) };
      await refuses(await input(p), /^restore\.sparse isn't the file Kiln saved/);
    });
    it('refuses one with a recorded file missing', async () => {
      const p = await point();
      p.files.splice(1, 1);
      await refuses(await input(p), /^This restore point is missing file\.orig\./);
    });
    it('refuses one holding a file Kiln didn\'t save in it', async () => {
      await refuses(await input(await point({ extra: { filename: 'other.bin', data: new Uint8Array(1) } })), /holds other\.bin, which Kiln didn't save in it/);
    });
    it('refuses a record that leaves out the restore image', async () => {
      const p = await point();
      delete (p.manifest.kiln as { files: Record<string, unknown> }).files['restore.sparse'];
      await refuses(await input(p), /doesn't record its restore image/);
    });
    it('refuses one with no restore image at all', async () => {
      await refuses(await input(await point(), { sparse: 'nope.sparse' }), /has no restore image \(nope\.sparse\)/);
    });
    it('without a record (made before Kiln kept one), checks the contents against the original files instead', async () => {
      const lines = await checkRestorePoint(await input(await point({ record: false })));
      expect(lines[0]).toMatch(/made before Kiln recorded each file's SHA-256/);
      expect(lines).toContain('restore image: writes no block outside those files');
    });
    it('without a record or a description of its files, refuses: there is nothing to check it against', async () => {
      await refuses(await input(await point({ record: false }), { restored: undefined }), /doesn't record its files, so Kiln can't check it/);
    });
  });

  describe('made on this device', () => {
    it('refuses one that doesn\'t say which device', async () => {
      await refuses(await input(await point({ deviceIdHash: undefined })), /doesn't say which device it was made on/);
      await refuses(await input(await point({ deviceIdHash: 'not-a-hash' })), /doesn't say which device it was made on/);
    });
    it('refuses another device, and a fastboot that reports no serial', async () => {
      await refuses(await input(await point(), { serial: '0000fake02' }), /^This restore point was made on another device\./);
      await refuses(await input(await point(), { serial: '' }), /made on another device/);
    });
  });

  describe('a whole image for this partition', () => {
    it('refuses a damaged image', async () => {
      const bad = goodSparse();
      bad[0] = 0;
      const p = await point({ sparse: bad });
      await refuses(await input(p), /^The restore image is damaged \(not a valid sparse image: wrong magic\)\./);
    });
    it('refuses one for another partition size', async () => {
      await refuses(await input(await point(), { partitionBytes: (BLOCKS + 1) * BS }), new RegExp(`for a system partition of ${BLOCKS * BS} bytes; this device's is ${(BLOCKS + 1) * BS}`));
    });
    it('refuses one that fills blocks', async () => {
      const p = await point({ sparse: sparse([{ skip: 10 }, { raw: padded(ORIG, 0, 2 * BS) }, { skip: 8 }, { raw: padded(ORIG, 2 * BS, BS) }, { fill: 1 }]) });
      await refuses(await input(p), /fills blocks instead of restoring them/);
    });
  });

  describe('it puts back exactly the original files it records', () => {
    it('refuses one whose original copy is missing or isn\'t the recorded original', async () => {
      await refuses(await input(await point(), { restored: await restored({ file: 'gone.orig' }) }), /missing gone\.orig, the original file\.jar/);
      await refuses(await input(await point(), { restored: await restored({ sha256: await sha256Hex(WRITTEN) }) }), /file\.orig isn't the original file\.jar this restore point records/);
      await refuses(await input(await point(), { restored: await restored({ size: ORIG.length - 1 }) }), /isn't the original file\.jar/);
    });
    it('refuses an image whose bytes in the file\'s blocks aren\'t the original\'s', async () => {
      const p = await point({ sparse: sparse([{ skip: 10 }, { raw: padded(WRITTEN, 0, 2 * BS) }, { skip: 8 }, { raw: padded(ORIG, 2 * BS, BS) }]) });
      await refuses(await input(p), /doesn't hold the original file\.jar/);
    });
    it('refuses an image that leaves part of the file out', async () => {
      const p = await point({ sparse: sparse([{ skip: 10 }, { raw: padded(ORIG, 0, 2 * BS) }]) });
      await refuses(await input(p), /doesn't put back all of file\.jar/);
    });
    it('refuses an image that writes a block outside the files', async () => {
      const p = await point({ sparse: sparse([{ skip: 10 }, { raw: padded(ORIG, 0, 2 * BS) }, { skip: 8 }, { raw: padded(ORIG, 2 * BS, BS) }, { raw: new Uint8Array(BS) }]) });
      await refuses(await input(p), /writes blocks that aren't part of the files it puts back/);
    });
    it('refuses a map of the file with a gap, or one that doesn\'t cover it', async () => {
      await refuses(await input(await point(), { restored: await restored({ extents: [{ ...EXTENTS[0]!, length: BS }, EXTENTS[1]!] }) }), /map of file\.jar has a gap/);
      await refuses(await input(await point(), { restored: await restored({ extents: [EXTENTS[0]!] }) }), /map of file\.jar doesn't cover the whole file/);
    });
  });
});

describe('deviceFileState', () => {
  it('tells what the install wrote, the original, and anything else', async () => {
    const [f] = await restored();
    expect(await deviceFileState(f!, WRITTEN)).toBe('written');
    expect(await deviceFileState(f!, ORIG)).toBe('original');
    expect(await deviceFileState(f!, ORIG.subarray(0, ORIG.length - 1))).toBe('other');
    expect(await deviceFileState(f!, fileOf(ORIG.length, 3))).toBe('other');
    // a restore point that doesn't record what the install wrote: only the original fits
    expect(await deviceFileState({ ...f!, written: undefined }, WRITTEN)).toBe('other');
  });
});
