// The boot animation for Stock-in-place: stock's /system/media/bootanimation.zip can only be overwritten in its own
// blocks, at its own size (22,901 bytes on stock 1.24.0, as services.jar is), so the full animation doesn't fit. Kiln
// writes branding/lululemon-mirror/bootanimation-compact.zip (branding/boot/compact.py: upright, as stock's own is,
// built within that size) padded to the stock file's exact length with padZipToExactLength.
import { padZipToExactLength } from './stock';

/** Kiln's staged asset with the compact animation (kiln/scripts/stage-assets.mjs), from branding/lululemon-mirror. */
export const BOOT_ANIMATION_ASSET = 'mirror-bootanimation-compact';
/** Where stock keeps the boot animation Android 6 plays (no /oem on the Mirror; research/stock-firmware-probe.md). */
export const STOCK_BOOT_ANIMATION_PATH = '/system/media/bootanimation.zip';
/** The restore point's copy of stock's boot animation. */
export const ORIGINAL_BOOT = 'bootanimation.zip.orig';

/** The compact animation at exactly `size` bytes, stock's: its zip comment pads it, and nothing else moves. */
export function bootInPlaceZip(compact: Uint8Array, size: number): Uint8Array {
  return padZipToExactLength(compact, size);
}
