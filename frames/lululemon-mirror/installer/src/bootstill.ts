// The boot animation for Stock-in-place: stock's /system/media/bootanimation.zip can only be overwritten in its own
// blocks, at its own size (22,901 bytes on stock 1.24.0, as services.jar is), so the full animation doesn't fit. Kiln
// writes a one-frame animation of the still the full one ends on (branding/boot/still.py), padded to that size.
import { crc32 } from './stock';

/** The PNG's width and height, from its IHDR. */
export function pngSize(png: Uint8Array): { width: number; height: number } {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (png.length < 24 || !sig.every((b, i) => png[i] === b) || String.fromCharCode(...png.subarray(12, 16)) !== 'IHDR') {
    throw new Error('the boot still isn\'t a PNG');
  }
  const v = new DataView(png.buffer, png.byteOffset, png.byteLength);
  return { width: v.getUint32(16), height: v.getUint32(20) };
}

/** The largest zip comment: the padding a zip can carry after its end record. */
const MAX_COMMENT = 0xffff;
/** 2026-01-01 00:00 as DOS time and date, as the generator writes (the same bytes on every build). */
const DOS_TIME = 0;
const DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;

/**
 * A bootanimation.zip of exactly `size` bytes that shows `png` until Android has started: desc.txt names a rectangle
 * the PNG's size, which Android 6 centers on the screen and draws the frame into from its top-left, on `background`
 * (`#rrggbb`). Every entry is STORED (Android 6 skips compressed frames); the zip's comment pads it to `size`, spaces
 * only, so no end-record signature can appear in it. Throws when it can't fit.
 */
export function bootStillZip(png: Uint8Array, o: { background: string; size: number; fps?: number }): Uint8Array {
  if (!/^#[0-9a-fA-F]{6}$/.test(o.background)) throw new Error(`the background ${o.background} isn't #rrggbb`);
  const { width, height } = pngSize(png);
  const enc = new TextEncoder();
  const entries: [string, Uint8Array][] = [
    ['desc.txt', enc.encode(`${width} ${height} ${o.fps ?? 10}\np 0 0 part0 ${o.background.toLowerCase()}\n`)],
    ['part0/', new Uint8Array(0)],
    ['part0/still.png', png],
  ];
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const n = enc.encode(name);
    const crc = crc32(data);
    const lh = new Uint8Array(30 + n.length);
    const l = new DataView(lh.buffer);
    l.setUint32(0, 0x04034b50, true); l.setUint16(4, 10, true); l.setUint16(6, 0, true); l.setUint16(8, 0, true);
    l.setUint16(10, DOS_TIME, true); l.setUint16(12, DOS_DATE, true); l.setUint32(14, crc, true);
    l.setUint32(18, data.length, true); l.setUint32(22, data.length, true); l.setUint16(26, n.length, true); l.setUint16(28, 0, true);
    lh.set(n, 30);
    const ch = new Uint8Array(46 + n.length);
    const c = new DataView(ch.buffer);
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 0x031e, true); c.setUint16(6, 10, true); c.setUint16(8, 0, true); c.setUint16(10, 0, true);
    c.setUint16(12, DOS_TIME, true); c.setUint16(14, DOS_DATE, true); c.setUint32(16, crc, true);
    c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, n.length, true);
    c.setUint16(30, 0, true); c.setUint16(32, 0, true); c.setUint16(34, 0, true); c.setUint16(36, 0, true);
    c.setUint32(38, (name.endsWith('/') ? 0o40755 : 0o100644) << 16, true); c.setUint32(42, offset, true);
    ch.set(n, 46);
    locals.push(lh, data);
    centrals.push(ch);
    offset += lh.length + data.length;
  }
  const cdSize = centrals.reduce((s, x) => s + x.length, 0);
  const bare = offset + cdSize + 22;
  const pad = o.size - bare;
  if (pad < 0) throw new Error(`the boot still needs ${bare} bytes, and the stock bootanimation.zip has ${o.size}`);
  if (pad > MAX_COMMENT) throw new Error(`the stock bootanimation.zip is ${pad} bytes bigger than the still's, more than a zip comment holds`);
  const out = new Uint8Array(o.size);
  let at = 0;
  for (const part of [...locals, ...centrals]) { out.set(part, at); at += part.length; }
  const e = new DataView(out.buffer, at, 22);
  e.setUint32(0, 0x06054b50, true); e.setUint16(4, 0, true); e.setUint16(6, 0, true);
  e.setUint16(8, entries.length, true); e.setUint16(10, entries.length, true);
  e.setUint32(12, cdSize, true); e.setUint32(16, offset, true); e.setUint16(20, pad, true);
  out.fill(0x20, at + 22);
  return out;
}

/** Kiln's staged asset with the still (kiln/scripts/stage-assets.mjs), from branding/lululemon-mirror. */
export const BOOT_STILL_ASSET = 'mirror-bootanimation-still';
/** The background the animation plays on (its desc.txt; still.json `background`): the rest of the screen. */
export const BOOT_STILL_BACKGROUND = '#0a0b0f';
/** Where stock keeps the boot animation Android 6 plays (no /oem on the Mirror; research/stock-firmware-probe.md). */
export const STOCK_BOOT_ANIMATION_PATH = '/system/media/bootanimation.zip';
/** The restore point's copy of stock's boot animation. */
export const ORIGINAL_BOOT = 'bootanimation.zip.orig';
