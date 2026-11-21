// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md ("The patch").
//
// Adds Magisk to a stock ramdisk, after undoing an earlier Magisk patch, so that the result matches
// the reference CLI (patch-boot-magisk.sh) byte for byte.

import type { MagiskBinaries } from './apk';
import { type BootImage, type Compression, hasBytes, ramdiskCompression, readBootImage, repackBootImage } from './bootimage';
import type { Compressor } from './compress';
import { type Ramdisk, S_IFDIR, S_IFREG, entry, isRegular, readCpio, sortedNames, writeCpio } from './ramdisk';

export interface OverlayFile {
  path: string;
  mode: number;
  data: Uint8Array;
}

export interface PatchImageInput {
  boot: Uint8Array;
  magisk: MagiskBinaries;
  overlay: OverlayFile[];
  /** PREINITDEVICE for a stock image. Default 'cache'. */
  preinitDevice?: string;
}

export interface PatchResult {
  image: Uint8Array;
  /** The input was already patched by Magisk, and was restored before the new patch. */
  wasMagisk: boolean;
  /** The text of .backup/.magisk. */
  magiskConfig: string;
  log: string[];
}

const enc = new TextEncoder();
const BACKUP = '.backup';
const CONFIG = '.backup/.magisk';
const RMLIST = '.backup/.rmlist';
/** The overlay.d entries Magisk's own patch adds (below); every other one is the caller's overlay. */
const MAGISK_OVERLAY = new Set(['overlay.d/sbin', 'overlay.d/sbin/magisk.xz', 'overlay.d/sbin/init-ld.xz']);

export type RamdiskState = 'stock' | 'magisk' | 'other-root';

const OTHER_ROOT = ['sbin/launch_daemonsu.sh', 'sbin/su', 'init.xposed.rc', 'boot/sbin/launch_daemonsu.sh'];
const MAGISK_MARKERS = [CONFIG, 'init.magisk.rc', 'overlay/init.magisk.rc'];

export function ramdiskState(r: Ramdisk): RamdiskState {
  if (OTHER_ROOT.some((n) => r.has(n))) return 'other-root';
  if (MAGISK_MARKERS.some((n) => r.has(n))) return 'magisk';
  return 'stock';
}

interface OpenedBoot {
  boot: BootImage;
  compression: Compression;
  ramdisk: Ramdisk;
}

export function openBoot(img: Uint8Array, comp: Compressor): OpenedBoot {
  const boot = readBootImage(img);
  const compression = ramdiskCompression(boot.ramdisk);
  let cpio: Uint8Array;
  if (compression === 'gzip') cpio = comp.gunzip(boot.ramdisk);
  else if (compression === 'xz') {
    const d = comp.unxz(boot.ramdisk);
    if (!d) throw new Error('Damaged ramdisk: the xz data does not decode.');
    cpio = d;
  } else cpio = boot.ramdisk;
  return { boot, compression, ramdisk: readCpio(cpio) };
}

const isBackupName = (n: string) => n === BACKUP || n.startsWith(`${BACKUP}/`);
const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i]);

/** Undo an earlier Magisk patch in place, from its .backup. Returns the old config text. */
function restore(r: Ramdisk, comp: Compressor): string {
  const config = new TextDecoder().decode(r.get(CONFIG)!.data);
  let list: string | undefined;
  const restored: Ramdisk = new Map();
  for (const [name, e] of r) {
    if (!name.startsWith(`${BACKUP}/`)) continue;
    if (name === RMLIST) {
      try { list = new TextDecoder('utf-8', { fatal: true }).decode(e.data); } catch { /* not UTF-8: ignored */ }
    } else if (name !== CONFIG) {
      const orig = name.slice(BACKUP.length + 1);
      const plain = orig.endsWith('.xz') && isRegular(e) ? comp.unxz(e.data) : null;
      if (plain) restored.set(orig.slice(0, -3), { ...e, data: plain });
      else restored.set(orig, e);
    }
  }
  for (const name of [...r.keys()]) if (isBackupName(name)) r.delete(name);
  if (list === undefined && restored.size === 0) {
    r.clear();
    return config;
  }
  for (const name of (list ?? '').split('\0')) if (name) r.delete(name);
  for (const [name, e] of restored) r.set(name, e);
  return config;
}

function configValue(config: string, key: string): string {
  const line = config.split('\n').find((l) => l.startsWith(`${key}=`));
  return line === undefined ? '' : line.replace(/\r/g, '').slice(key.length + 1);
}

/** Back up what `next` changed against `original` into .backup, as Magisk's restore expects. */
function backup(original: Ramdisk, next: Ramdisk, comp: Compressor): void {
  const before = sortedNames([...original.keys()].filter((n) => !isBackupName(n)));
  const after = sortedNames([...next.keys()].filter((n) => !isBackupName(n)));
  const saved: Ramdisk = new Map([[BACKUP, entry(S_IFDIR, 0)]]);
  for (const name of before) {
    const was = original.get(name)!, now = next.get(name);
    if (now && sameBytes(was.data, now.data)) continue;
    if (isRegular(was)) saved.set(`${BACKUP}/${name}.xz`, { ...was, data: comp.xz(was.data) });
    else saved.set(`${BACKUP}/${name}`, was);
  }
  const added = after.filter((n) => !original.has(n));
  if (added.length) saved.set(RMLIST, entry(S_IFREG, 0, enc.encode(added.map((n) => `${n}\0`).join(''))));
  for (const [name, e] of saved) next.set(name, e);
}

export async function sha1Hex(d: Uint8Array): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-1', d as Uint8Array<ArrayBuffer>));
  return [...h].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export async function patchWith(input: PatchImageInput, comp: Compressor): Promise<PatchResult> {
  const log: string[] = [];
  const { boot, compression, ramdisk } = openBoot(input.boot, comp);
  log.push(`boot image: page ${boot.pageSize}, ${compression} ramdisk, ${boot.avb ? 'AVB footer' : 'no AVB footer'}`);

  const state = ramdiskState(ramdisk);
  if (state === 'other-root') throw new Error('This boot image was rooted by another tool: restore the stock boot image first.');
  if (state === 'magisk' && !ramdisk.has(CONFIG)) {
    throw new Error('This boot image has an old Magisk without .backup/.magisk: restore the stock boot image first.');
  }
  if (hasBytes(boot.dtb, '/system_root\0')) throw new Error("This boot image's DTB was patched by old Magisk versions (/system_root).");
  if (hasBytes(boot.dtb, 'skip_initramfs')) throw new Error('This boot image\'s DTB has skip_initramfs, which Kiln does not patch.');

  const wasMagisk = state === 'magisk';
  let sha1 = await sha1Hex(input.boot);
  let preinit = input.preinitDevice ?? 'cache';
  if (wasMagisk) {
    const old = restore(ramdisk, comp);
    sha1 = configValue(old, 'SHA1');
    preinit = configValue(old, 'PREINITDEVICE');
    log.push('restored the stock ramdisk from the earlier Magisk patch');
  }

  const original: Ramdisk = new Map(ramdisk);
  ramdisk.set('init', entry(S_IFREG, 0o750, input.magisk.magiskinit));
  ramdisk.set('overlay.d', entry(S_IFDIR, 0o750));
  ramdisk.set('overlay.d/sbin', entry(S_IFDIR, 0o750));
  ramdisk.set('overlay.d/sbin/magisk.xz', entry(S_IFREG, 0o644, comp.xz(input.magisk.magisk, 4096)));
  ramdisk.set('overlay.d/sbin/init-ld.xz', entry(S_IFREG, 0o644, comp.xz(input.magisk.initLd, 4096)));
  backup(original, ramdisk, comp);

  const magiskConfig = [
    'KEEPVERITY=true', 'KEEPFORCEENCRYPT=true', 'RECOVERYMODE=false', 'VENDORBOOT=false',
    ...(preinit ? [`PREINITDEVICE=${preinit}`] : []),
    ...(sha1 ? [`SHA1=${sha1}`] : []),
  ].map((l) => `${l}\n`).join('');
  ramdisk.set(CONFIG, entry(S_IFREG, 0, enc.encode(magiskConfig)));

  // A boot image patched before still carries the overlay files added then: Magisk's restore only undoes
  // its own changes. Drop every overlay.d entry but Magisk's, so a re-patch equals a patch of stock.
  for (const name of [...ramdisk.keys()]) {
    if (name.startsWith('overlay.d/') && !MAGISK_OVERLAY.has(name)) ramdisk.delete(name);
  }
  for (const f of input.overlay) {
    ramdisk.set(f.path.split('/').filter(Boolean).join('/'), entry(S_IFREG, f.mode, f.data));
  }
  log.push(`added Magisk and ${input.overlay.length} overlay files`);

  const cpio = writeCpio(ramdisk);
  const packed = compression === 'gzip' ? comp.gzip(cpio) : compression === 'xz' ? comp.xz(cpio) : cpio;
  const image = await repackBootImage(boot, packed);
  log.push(`ramdisk ${boot.ramdisk.length} -> ${packed.length} bytes`);
  return { image, wasMagisk, magiskConfig, log };
}
