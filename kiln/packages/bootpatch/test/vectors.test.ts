// Black-box vectors for kiln/packages/bootpatch: patched images (by SHA-256) and the ramdisk inside
// them, refusals, isMagiskPatched, and the compressors in bootpatch.wasm. Any implementation of the
// public API must reproduce them. The spec: docs/research/kiln-cleanroom/bootpatch-spec.md.
//
// test/vectors/bootpatch.json was recorded from the implementation of 2026-11-15:
//   BOOTPATCH_VECTORS_WRITE=1 npx vitest run test/vectors.test.ts   (from kiln/packages/bootpatch)
// Don't rewrite it to make a new implementation pass: the file is the contract.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { beforeAll, describe, expect, it } from 'vitest';
import type { Compressor } from '../src/compress';
import { type MagiskBinaries, type OverlayFile, isMagiskPatched, loadCompressor, patchBootImage } from '../src/index';
import {
  type BootIn, type CpioIn, S_IFCHR, S_IFDIR, S_IFLNK, S_IFREG, a32Words, bootImage, cpioNewc, elf, gzipStored, pattern,
} from './vectors/synth';

const FILE = join(__dirname, 'vectors/bootpatch.json');
const WRITE = !!process.env.BOOTPATCH_VECTORS_WRITE;
const enc = new TextEncoder();
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');
const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64');
const unb64 = (s: string) => new Uint8Array(Buffer.from(s, 'base64'));

// ---------------------------------------------------------------------------------------------
// Inputs

const MAGISK: MagiskBinaries = {
  magiskinit: elf(0x28, pattern(6000, 1)),
  magisk: elf(0x28, pattern(20000, 2)),
  initLd: a32Words(1500, 3),
};

const STOCK: CpioIn[] = [
  { name: '.', mode: S_IFDIR | 0o755 },
  { name: 'init', mode: S_IFREG | 0o750, data: elf(0x28, pattern(9000, 10)) },
  { name: 'fstab.sun50iw10p1', mode: S_IFREG | 0o640, data: '/dev/block/by-name/system /system ext4 ro wait,verify,avb=vbmeta,forceencrypt=footer\n' },
  { name: 'dev', mode: S_IFDIR | 0o755 },
  { name: 'dev/console', mode: S_IFCHR | 0o600, rdev: [5, 1] },
  { name: 'system', mode: S_IFDIR | 0o755 },
  { name: 'sh', mode: S_IFLNK | 0o777, data: '/system/bin/sh' },
  { name: 'verity_key', mode: S_IFREG | 0o644, data: pattern(524, 11) },
  { name: 'data', mode: S_IFDIR | 0o771, uid: 1000, gid: 1000 },
  { name: 'init.rc', mode: S_IFREG | 0o750, data: 'on init\n    start ueventd\n' },
  { name: 'überdir', mode: S_IFDIR | 0o755 },
  { name: 'a/b', mode: S_IFREG | 0o644, data: 'a/b' },
  { name: 'a.b', mode: S_IFREG | 0o644, data: 'a.b' },
  { name: 'a-b', mode: S_IFREG | 0o644, data: 'a-b' },
  { name: 'A', mode: S_IFREG | 0o644, data: 'A' },
  { name: 'a', mode: S_IFDIR | 0o755 },
];

const OVERLAY: OverlayFile[] = [
  { path: 'overlay.d/sbin/s1kiosk_demo.sh', mode: 0o700, data: enc.encode('#!/system/bin/sh\necho demo\n') },
  { path: 'overlay.d/s1kiosk_demo.rc', mode: 0o644, data: enc.encode('service s1kiosk_demo /sbin/s1kiosk_demo.sh\n') },
];

const SIZE = 0x80000;
const KERNEL = pattern(20000, 20);
const DTB = pattern(3000, 21);
const base = (over: Partial<BootIn> = {}): BootIn => ({
  version: 2, pageSize: 2048, kernel: KERNEL, ramdisk: gzipStored(cpioNewc(STOCK)), dtb: DTB, id: 'sha1', avb: true, size: SIZE, ...over,
});

interface Case { name: string; boot: (xzRamdisk: Uint8Array) => Uint8Array; overlay?: OverlayFile[]; preinit?: string }
const CASES: Case[] = [
  { name: 'stock: v2, page 2048, gzip ramdisk, AVB footer, SHA-1 id', boot: () => bootImage(base()) },
  { name: 'stock, PREINITDEVICE=metadata', boot: () => bootImage(base()), preinit: 'metadata' },
  { name: 'stock, no overlay files', boot: () => bootImage(base()), overlay: [] },
  {
    name: 'stock: page 4096, uncompressed ramdisk, second and recovery DTBO, SHA-256 id, no AVB',
    boot: () => bootImage(base({ pageSize: 4096, ramdisk: cpioNewc(STOCK), second: pattern(5000, 30), recoveryDtbo: pattern(700, 31), id: 'sha256', avb: false })),
  },
  { name: 'stock: xz ramdisk', boot: (xz) => bootImage(base({ ramdisk: xz })) },
  {
    name: 'stock: ramdisk of two concatenated cpio archives',
    boot: () => bootImage(base({ ramdisk: gzipStored(new Uint8Array([...cpioNewc(STOCK.slice(0, 8)), ...cpioNewc(STOCK.slice(8))])) })),
  },
];

interface Refusal { name: string; error: string; boot: () => Uint8Array }
const withStock = (edit: (e: CpioIn[]) => CpioIn[]) => gzipStored(cpioNewc(edit(STOCK)));
const REFUSALS: Refusal[] = [
  ...[0, 1, 3, 4].map((v) => ({ name: `header version ${v}`, error: 'header version', boot: () => bootImage(base({ version: v })) })),
  { name: 'page size 1024', error: 'page size', boot: () => bootImage(base({ pageSize: 1024 })) },
  { name: 'size not a multiple of 4096', error: 'multiple of 4096', boot: () => bootImage(base({ avb: false, size: SIZE + 512 })) },
  { name: 'no ANDROID! magic', error: 'not an Android boot image', boot: () => { const b = bootImage(base()); b[0] = 0x58; return b; } },
  { name: 'compressed kernel', error: 'kernel format', boot: () => bootImage(base({ kernel: gzipStored(KERNEL) })) },
  { name: 'kernel with an appended DTB', error: 'DTB', boot: () => bootImage(base({ kernel: new Uint8Array([...KERNEL, 0xd0, 0x0d, 0xfe, 0xed, ...pattern(60, 40)]) })) },
  { name: 'no ramdisk', error: 'no ramdisk', boot: () => bootImage(base({ ramdisk: new Uint8Array(0) })) },
  { name: 'lz4 ramdisk', error: 'ramdisk format', boot: () => bootImage(base({ ramdisk: new Uint8Array([0x04, 0x22, 0x4d, 0x18, ...pattern(100, 41)]) })) },
  { name: 'block past the end of the image', error: 'Corrupted', boot: () => { const b = bootImage(base()); new DataView(b.buffer).setUint32(8, SIZE, true); return b; } },
  { name: 'Samsung trailer', error: 'SEANDROID', boot: () => bootImage(base({ tail: enc.encode('SEANDROIDENFORCE') })) },
  { name: 'LG trailer', error: 'LG', boot: () => bootImage(base({ tail: new Uint8Array([0x41, 0xa9, 0xe4, 0x67, 0x74, 0x4d, 0x1d, 0x1b, 0xa4, 0x29, 0xf2, 0xec, 0xea, 0x65, 0x52, 0x79]) })) },
  { name: 'AVB1 signature trailer', error: 'AVB1', boot: () => bootImage(base({ tail: new Uint8Array([0x30, 0x82, 0x01, 0x00]) })) },
  { name: 'vbmeta block past the end', error: 'truncated', boot: () => { const b = bootImage(base()); new DataView(b.buffer).setBigUint64(b.length - 64 + 28, BigInt(SIZE), false); return b; } },
  { name: 'ramdisk already rooted by another tool (sbin/su)', error: 'stock boot image', boot: () => bootImage(base({ ramdisk: withStock((e) => [...e, { name: 'sbin/su', mode: S_IFREG | 0o755, data: 'x' }]) })) },
  { name: 'old Magisk ramdisk (init.magisk.rc, no .backup/.magisk)', error: '.backup/.magisk', boot: () => bootImage(base({ ramdisk: withStock((e) => [...e, { name: 'init.magisk.rc', mode: S_IFREG | 0o750, data: 'x' }]) })) },
  { name: 'dtb with skip_initramfs', error: 'skip_initramfs', boot: () => bootImage(base({ dtb: new Uint8Array([...DTB, ...enc.encode('skip_initramfs')]) })) },
  { name: 'dtb patched for /system_root', error: 'patched by old', boot: () => bootImage(base({ dtb: new Uint8Array([...DTB, ...enc.encode('/system_root\0')]) })) },
  ...[
    '49010054011440B93FA00F71E9000054010840B93FA00F7189000054001840B91FA00F7188010054',
    '821B8012',
    '70726F63615F636F6E66696700',
  ].map((h, i) => ({
    name: `kernel with Samsung pattern ${i + 1}`, error: 'kernel matches',
    boot: () => bootImage(base({ kernel: new Uint8Array([...KERNEL, ...Buffer.from(h, 'hex')]) })),
  })),
  { name: 'patched image does not fit the partition', error: 'does not fit', boot: () => bootImage(base({ avb: false, size: undefined, tight: true })) },
];

// ---------------------------------------------------------------------------------------------
// Reading the output back, from the formats (not from the code under test)

function ramdiskOf(img: Uint8Array): Uint8Array {
  const v = new DataView(img.buffer, img.byteOffset, img.byteLength);
  const page = v.getUint32(36, true), k = v.getUint32(8, true), r = v.getUint32(16, true);
  const off = page + Math.ceil(k / page) * page;
  return img.subarray(off, off + r);
}

function listCpio(d: Uint8Array) {
  const out: { name: string; mode: string; uid: number; gid: number; rdev: string; size: number; sha256: string; ino: number }[] = [];
  const td = new TextDecoder();
  let p = 0;
  const h = (i: number) => parseInt(td.decode(d.subarray(p + 6 + i * 8, p + 14 + i * 8)), 16);
  while (p + 110 <= d.length) {
    expect(td.decode(d.subarray(p, p + 6))).toBe('070701');
    const nameSize = h(11), size = h(6);
    const name = td.decode(d.subarray(p + 110, p + 110 + nameSize - 1));
    const dataAt = Math.ceil((p + 110 + nameSize) / 4) * 4;
    const entry = { name, mode: h(1).toString(8), uid: h(2), gid: h(3), rdev: `${h(9)}:${h(10)}`, size, sha256: sha(d.subarray(dataAt, dataAt + size)), ino: h(0) };
    expect([h(4), h(5), h(7), h(8), h(12)]).toEqual([1, 0, 0, 0, 0]); // nlink, mtime, dev, check
    p = Math.ceil((dataAt + size) / 4) * 4;
    if (name === 'TRAILER!!!') { expect(p).toBe(d.length); break; }
    out.push(entry);
  }
  return out;
}

async function decodeRamdisk(img: Uint8Array, comp: Compressor): Promise<Uint8Array> {
  const r = ramdiskOf(img);
  if (r[0] === 0x1f && r[1] === 0x8b) return new Uint8Array(gunzipSync(r));
  if (r[0] === 0xfd) return comp.unxz(r)!;
  return r;
}

// ---------------------------------------------------------------------------------------------
// Compressor inputs (the wasm's gzip, xz with BCJ detection, unxz, inflate)

const arm64Image = () => { const b = pattern(8192, 50); b.set(enc.encode('ARM\x64'), 0x38); return b; };
const riscvImage = () => { const b = pattern(8192, 51); b.set(enc.encode('RSC\x05'), 0x30); return b; };
const COMPRESS = {
  'ELF ARM (Thumb filter)': elf(0x28, pattern(8000, 60)),
  'ELF AArch64': elf(0xb7, pattern(8000, 61)),
  'ELF x86-64': elf(0x3e, pattern(8000, 62)),
  'ELF RISC-V': elf(0xf3, pattern(8000, 63)),
  'ELF big-endian ARM': (() => { const b = elf(0x28, pattern(8000, 64)); b[5] = 2; b[18] = 0; b[19] = 0x28; return b; })(),
  'ARM64 Linux Image': arm64Image(),
  'RISC-V Linux Image': riscvImage(),
  'raw A32 code': a32Words(3000, 65),
  'raw A32 code, 63 bytes': a32Words(16, 66).subarray(0, 63),
  '70% A32 words': (() => { const b = a32Words(64, 67); for (let i = 0; i < 20; i++) b[i * 4 + 3] = 0x12; return b; })(),
  'text': enc.encode('hello hello hello hello '.repeat(400)),
  'random': pattern(10000, 68),
  'empty': new Uint8Array(0),
} as const;

// ---------------------------------------------------------------------------------------------

let comp: Compressor;
let want: any;
beforeAll(async () => {
  comp = await loadCompressor();
  if (!WRITE) want = JSON.parse(readFileSync(FILE, 'utf8'));
});

const xzRamdisk = () => (WRITE ? comp.xz(cpioNewc(STOCK)) : unb64(want.inputs.xzRamdiskBase64));

async function runCase(c: Case, boot: Uint8Array) {
  const r = await patchBootImage({ boot, magisk: MAGISK, overlay: c.overlay ?? OVERLAY, preinitDevice: c.preinit });
  return {
    imageSha256: sha(r.image), size: r.image.length, wasMagisk: r.wasMagisk, magiskConfig: r.magiskConfig,
    inputSha256: sha(boot), ramdisk: listCpio(await decodeRamdisk(r.image, comp)), image: r.image,
  };
}

describe('bootpatch vectors', () => {
  it.runIf(WRITE)('records test/vectors/bootpatch.json', async () => {
    const xz = xzRamdisk();
    const cases = [];
    for (const c of CASES) {
      const { image, ...rest } = await runCase(c, c.boot(xz));
      const again = await runCase({ ...c, preinit: 'cache' }, image);
      cases.push({ name: c.name, ...rest, repatch: { imageSha256: again.imageSha256, wasMagisk: again.wasMagisk } });
    }
    const refusals = [];
    for (const r of REFUSALS) {
      let error = '';
      try { await patchBootImage({ boot: r.boot(), magisk: MAGISK, overlay: OVERLAY }); } catch (e) { error = (e as Error).message; }
      expect(error, r.name).toContain(r.error);
      refusals.push({ name: r.name, errorContains: r.error, inputSha256: sha(r.boot()) });
    }
    const compress = Object.entries(COMPRESS).map(([name, d]) => ({
      name, inputSha256: sha(d), bcjFilter: comp.bcjFilter(d), gzipSha256: sha(comp.gzip(d)),
      xz4096Sha256: sha(comp.xz(d, 4096)), xzFullSha256: sha(comp.xz(d)),
    }));
    writeFileSync(FILE, `${JSON.stringify({
      note: 'Recorded 2026-11-15 from the implementation then in kiln/packages/bootpatch. See docs/research/kiln-cleanroom/bootpatch-spec.md.',
      inputs: { xzRamdiskBase64: b64(xz), magisk: Object.fromEntries(Object.entries(MAGISK).map(([k, v]) => [k, sha(v)])) },
      cases, refusals, compress,
      isMagiskPatched: { stock: false, patched: true, oldMagiskMarker: true, otherRootTool: false },
    }, null, 1)}\n`);
  });

  it.skipIf(WRITE)('the synthetic inputs are the recorded ones', () => {
    expect(Object.fromEntries(Object.entries(MAGISK).map(([k, v]) => [k, sha(v)]))).toEqual(want.inputs.magisk);
    const xz = xzRamdisk();
    CASES.forEach((c, i) => expect(sha(c.boot(xz)), c.name).toBe(want.cases[i].inputSha256));
    REFUSALS.forEach((r, i) => expect(sha(r.boot()), r.name).toBe(want.refusals[i].inputSha256));
  });

  for (const [i, c] of CASES.entries()) {
    it.skipIf(WRITE)(`patches: ${c.name}`, async () => {
      const w = want.cases[i];
      expect(w.name).toBe(c.name);
      const { image, ...got } = await runCase(c, c.boot(xzRamdisk()));
      expect(got.ramdisk).toEqual(w.ramdisk); // read this diff first: it names the entry that differs
      expect(got.magiskConfig).toBe(w.magiskConfig);
      expect(got.wasMagisk).toBe(false);
      expect(got.size).toBe(w.size);
      expect(got.imageSha256).toBe(w.imageSha256);
      // Patching the result again restores the stock ramdisk first, so it gives the same image.
      const again = await runCase({ ...c, preinit: 'cache' }, image);
      expect(again.wasMagisk).toBe(true);
      expect(again.imageSha256).toBe(w.repatch.imageSha256);
      expect(again.imageSha256).toBe(w.imageSha256);
    });
  }

  for (const [i, r] of REFUSALS.entries()) {
    it.skipIf(WRITE)(`refuses: ${r.name}`, async () => {
      expect(want.refusals[i].name).toBe(r.name);
      await expect(patchBootImage({ boot: r.boot(), magisk: MAGISK, overlay: OVERLAY })).rejects.toThrow(want.refusals[i].errorContains);
    });
  }

  it.skipIf(WRITE)('isMagiskPatched', async () => {
    const stock = bootImage(base());
    const patched = (await patchBootImage({ boot: stock, magisk: MAGISK, overlay: OVERLAY })).image;
    const withEntry = (e: CpioIn) => bootImage(base({ ramdisk: withStock((s) => [...s, e]) }));
    expect({
      stock: await isMagiskPatched(stock),
      patched: await isMagiskPatched(patched),
      oldMagiskMarker: await isMagiskPatched(withEntry({ name: 'overlay/init.magisk.rc', mode: S_IFREG | 0o750, data: 'x' })),
      otherRootTool: await isMagiskPatched(withEntry({ name: 'sbin/launch_daemonsu.sh', mode: S_IFREG | 0o750, data: 'x' })),
    }).toEqual(want.isMagiskPatched);
  });

  it.skipIf(WRITE)('compressors: gzip, xz with BCJ detection, and round trips', () => {
    for (const w of want.compress) {
      const d = COMPRESS[w.name as keyof typeof COMPRESS];
      expect(sha(d), w.name).toBe(w.inputSha256);
      expect(comp.bcjFilter(d), w.name).toBe(w.bcjFilter);
      expect(sha(comp.gzip(d)), `${w.name} gzip`).toBe(w.gzipSha256);
      expect(sha(comp.xz(d, 4096)), `${w.name} xz 4096`).toBe(w.xz4096Sha256);
      expect(sha(comp.xz(d)), `${w.name} xz`).toBe(w.xzFullSha256);
      expect(sha(comp.gunzip(comp.gzip(d)))).toBe(w.inputSha256);
      expect(sha(comp.unxz(comp.xz(d))!)).toBe(w.inputSha256);
    }
    expect(comp.unxz(enc.encode('not xz'))).toBeNull();
    const stored = gzipStored(enc.encode('inflate me'));
    expect(new TextDecoder().decode(comp.inflateRaw(stored.subarray(10, stored.length - 8)))).toBe('inflate me');
    expect(new TextDecoder().decode(comp.gunzip(new Uint8Array([...gzipStored(enc.encode('ab')), ...gzipStored(enc.encode('cd'))])))).toBe('abcd');
  });
});
