// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
//
// A re-patch replaces the whole overlay (#139). The Magisk restore only undoes its own changes, so an
// image patched with older overlay files (a 0.4.3 S1's s1kiosk_* services) must not keep them next
// to the new ones: frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh drops them the same way.

import { describe, expect, it } from 'vitest';
import { type MagiskBinaries, type OverlayFile, patchBootImage } from '../src/index';
import { S_IFDIR, S_IFREG, a32Words, bootImage, cpioNewc, elf, gzipStored, pattern } from './vectors/synth';

const MAGISK: MagiskBinaries = { magiskinit: elf(0x28, pattern(600, 1)), magisk: elf(0x28, pattern(2000, 2)), initLd: a32Words(150, 3) };
const stock = bootImage({
  version: 2, pageSize: 2048, kernel: pattern(5000, 20), dtb: pattern(300, 21), id: 'sha1', avb: true, size: 0x40000,
  ramdisk: gzipStored(cpioNewc([
    { name: 'init', mode: S_IFREG | 0o750, data: pattern(900, 10) },
    { name: 'system', mode: S_IFDIR | 0o755 },
  ])),
});
const enc = new TextEncoder();
const OLD: OverlayFile[] = [
  { path: 'overlay.d/s1kiosk_radio.rc', mode: 0o644, data: enc.encode('service s1kiosk_radio\n') },
  { path: 'overlay.d/sbin/s1kiosk_radio.sh', mode: 0o700, data: enc.encode('#!/system/bin/sh\n') },
];
const NEW: OverlayFile[] = [
  { path: 'overlay.d/glassworks.rc', mode: 0o644, data: enc.encode('service glassworks_radio\n') },
  { path: 'overlay.d/sbin/glassworks_loader.sh', mode: 0o700, data: enc.encode('#!/system/bin/sh\n') },
];
const patch = async (boot: Uint8Array, overlay: OverlayFile[]) => (await patchBootImage({ boot, magisk: MAGISK, overlay })).image;

describe('re-patching with another overlay', () => {
  it('gives the same image as patching stock: the old overlay files are gone', async () => {
    const old = await patch(stock, OLD);
    expect(Buffer.from(await patch(old, NEW)).equals(Buffer.from(await patch(stock, NEW)))).toBe(true);
  });

  it('keeps the old files only when the new overlay has them again', async () => {
    const old = await patch(stock, OLD);
    expect(Buffer.from(await patch(old, OLD)).equals(Buffer.from(old))).toBe(true);
  });
});
