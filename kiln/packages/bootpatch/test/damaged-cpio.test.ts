// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md ("Reading the ramdisk").

import { expect, it } from 'vitest';
import { type MagiskBinaries, patchBootImage } from '../src/index';
import { a32Words, bootImage, elf, pattern } from './vectors/synth';

const MAGISK: MagiskBinaries = { magiskinit: elf(0x28, pattern(600, 1)), magisk: elf(0x28, pattern(2000, 2)), initLd: a32Words(150, 3) };

it('refuses a cpio header whose name size would wrap the next offset', async () => {
  const fields = [1, 0o100644, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xffffff92, 0];
  const header = new TextEncoder().encode(`070701${fields.map((x) => x.toString(16).padStart(8, '0')).join('')}`);
  const boot = bootImage({ version: 2, pageSize: 2048, kernel: pattern(5000, 20), ramdisk: header, dtb: pattern(300, 21), id: 'sha1', avb: false, size: 0x40000 });
  await expect(patchBootImage({ boot, magisk: MAGISK, overlay: [] })).rejects.toThrow('Damaged ramdisk');
});
