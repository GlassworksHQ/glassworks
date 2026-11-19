// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md ("Public API to keep").
//
// Kiln's boot image patcher: the Magisk patch of frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh,
// byte for byte, plus the partition edits around it (vbmeta flags, boot logo).

import { extractMagiskBusybox, extractMagiskPayload, type MagiskBinaries } from './apk';
import { loadCompressor } from './compress';
import { type OverlayFile, type PatchImageInput, type PatchResult, openBoot, patchWith, ramdiskState } from './patch';

export type { OverlayFile, PatchImageInput, PatchResult };
export { vbmetaFlags, withVbmetaFlags } from './vbmeta';
export { readBootlogo, replaceBootlogo } from './bootlogo';
export { s1Overlay, type S1OverlayInputs } from './overlay';
export { loadCompressor } from './compress';
export { zipEntries, type ZipEntry, type MagiskBinaries } from './apk';

export interface PatchInput {
  boot: Uint8Array;
  /** The Magisk v31.1 APK; the binaries are taken out of it. */
  magiskApk: Uint8Array;
  overlay: OverlayFile[];
  preinitDevice?: string;
}

/** Patch a stock or Magisk-patched boot image with Magisk's binaries and Kiln's overlay files. */
export async function patchBootImage(input: PatchImageInput): Promise<PatchResult> {
  return patchWith(input, await loadCompressor());
}

/** `patchBootImage`, with the binaries taken out of the Magisk APK. */
export async function patchBoot(input: PatchInput): Promise<PatchResult> {
  const comp = await loadCompressor();
  const { magiskinit, magisk, initLd } = await extractMagiskPayload(input.magiskApk, comp);
  const binaries: MagiskBinaries = { magiskinit, magisk, initLd };
  return patchWith({ boot: input.boot, magisk: binaries, overlay: input.overlay, preinitDevice: input.preinitDevice }, comp);
}

/** Magisk's own busybox from the Magisk APK (the same one `patchBoot` takes), for s1Overlay's `magiskBusybox`. */
export async function magiskBusybox(magiskApk: Uint8Array): Promise<Uint8Array> {
  return extractMagiskBusybox(magiskApk, await loadCompressor());
}

/** True when the image's ramdisk has been patched by Magisk. */
export async function isMagiskPatched(boot: Uint8Array): Promise<boolean> {
  return ramdiskState(openBoot(boot, await loadCompressor()).ramdisk) === 'magisk';
}
