// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md ("Reading the boot image").
//
// The repack keeps only the header page, the blocks, the vbmeta block and the AVB footer. The spec
// checks two of the ranges it drops: from the end of the last block to the vbmeta block (to the end
// of the image without AVB data), and from the end of the vbmeta block to the footer. A non-zero
// byte there would be lost, so the patch must refuse the image. Padding inside the header page and
// between blocks is not checked.

import { describe, expect, it } from 'vitest';
import { type MagiskBinaries, patchBootImage } from '../src/index';
import { S_IFDIR, S_IFREG, a32Words, bootImage, cpioNewc, elf, gzipStored, pattern, type BootIn } from './vectors/synth';

const MAGISK: MagiskBinaries = { magiskinit: elf(0x28, pattern(600, 1)), magisk: elf(0x28, pattern(2000, 2)), initLd: a32Words(150, 3) };
const RAMDISK = gzipStored(cpioNewc([
  { name: 'init', mode: S_IFREG | 0o750, data: pattern(900, 10) },
  { name: 'system', mode: S_IFDIR | 0o755 },
]));
const image = (over: Partial<BootIn> = {}) => bootImage({
  version: 2, pageSize: 2048, kernel: pattern(5000, 20), ramdisk: RAMDISK, dtb: pattern(300, 21), id: 'sha1', avb: true, size: 0x40000, ...over,
});
const patch = (boot: Uint8Array) => patchBootImage({ boot, magisk: MAGISK, overlay: [] });
const footer = (b: Uint8Array) => new DataView(b.buffer, b.length - 64, 64);

describe('bytes the repack would drop', () => {
  it('patches an image whose dropped ranges are all zero', async () => {
    expect((await patch(image())).image).toHaveLength(0x40000);
    expect((await patch(image({ avb: false }))).image).toHaveLength(0x40000);
  });

  it('refuses a non-zero byte between the last block and the vbmeta block', async () => {
    await expect(patch(image({ tail: new Uint8Array([0, 0, 1]) }))).rejects.toThrow('unsupported');
  });

  it('refuses a non-zero byte between the vbmeta block and the footer', async () => {
    const b = image();
    b[b.length - 65] = 1;
    await expect(patch(b)).rejects.toThrow('unsupported');
  });

  it('refuses a non-zero byte at the end of an image without AVB data', async () => {
    const b = image({ avb: false });
    b[b.length - 1] = 1;
    await expect(patch(b)).rejects.toThrow('unsupported');
  });

  it('treats a footer that does not point at a vbmeta block as dropped data', async () => {
    const b = image();
    footer(b).setBigUint64(20, BigInt(b.length - 4096), false);
    await expect(patch(b)).rejects.toThrow('unsupported');
  });
});
