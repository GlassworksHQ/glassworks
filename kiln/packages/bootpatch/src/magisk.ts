// Magisk v31.1: read boot_patch.sh's inputs out of the APK, and reproduce boot_patch.sh's ramdisk
// steps (scripts/boot_patch.sh, run by frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh with KEEPVERITY=true
// KEEPFORCEENCRYPT=true, outside Android so BOOTMODE=false).

import type { Compressor } from './compress';
import { CPIO_MAGISK, CPIO_STOCK, CPIO_UNSUPPORTED, Cpio } from './cpio';

import type { MagiskBinaries } from './apk';

// ---------------------------------------------------------------------------------------------
// boot_patch.sh

/** util_functions.sh grep_prop KEY: first `KEY=` line (CRs stripped), else ''. */
export function grepProp(key: string, text: string): string {
  for (const line of text.replace(/\r$/gm, '').split('\n')) {
    if (line.startsWith(key + '=')) return line.slice(key.length + 1);
  }
  return '';
}

export interface MagiskConfig {
  keepVerity: boolean;
  keepForceEncrypt: boolean;
  recoveryMode: boolean;
  vendorBoot: boolean;
  preinitDevice: string;
  sha1: string;
}

/** The `config` file boot_patch.sh writes and stores as `.backup/.magisk`. */
export function magiskConfigText(c: MagiskConfig): string {
  let s = `KEEPVERITY=${c.keepVerity}\nKEEPFORCEENCRYPT=${c.keepForceEncrypt}\nRECOVERYMODE=${c.recoveryMode}\nVENDORBOOT=${c.vendorBoot}\n`;
  if (c.preinitDevice) s += `PREINITDEVICE=${c.preinitDevice}\n`;
  if (c.sha1) s += `SHA1=${c.sha1}\n`;
  return s;
}

export interface RamdiskPatchOptions {
  /** `magiskboot sha1` of the boot image as read (used only for a stock image). */
  bootSha1: string;
  /** The PREINITDEVICE env (wrapper default 'cache'); a re-patch inherits the old config's instead. */
  preinitDevice: string;
  keepVerity?: boolean;
  keepForceEncrypt?: boolean;
}

export interface RamdiskPatchResult {
  /** `cpio test` status before patching: 0 stock, 1 Magisk. */
  status: number;
  config: string;
}

/**
 * boot_patch.sh from "Ramdisk Checks" through the ramdisk cpio edit, applied to `cpio` in place.
 * KEEPVERITY / KEEPFORCEENCRYPT default to true (what the wrapper passes).
 */
export function magiskPatchRamdisk(
  cpio: Cpio, payload: MagiskBinaries, opts: RamdiskPatchOptions, comp: Compressor, log: (s: string) => void,
): RamdiskPatchResult {
  const keepVerity = opts.keepVerity ?? true;
  const keepForceEncrypt = opts.keepForceEncrypt ?? true;

  log('- Checking ramdisk status');
  const status = cpio.test();
  let sha1 = '';
  let preinit = opts.preinitDevice;
  let configOrig: string | undefined;
  if (status === CPIO_STOCK) {
    log('- Stock boot image detected');
    sha1 = opts.bootSha1;
  } else if (status === CPIO_MAGISK) {
    log('- Magisk patched boot image detected');
    // "extract .backup/.magisk config.orig" "restore": if extract fails the CLI would carry on
    // with the unrestored ramdisk (old/foreign Magisk), so refuse instead.
    if (!cpio.exists('.backup/.magisk')) throw new Error('Magisk-patched ramdisk has no .backup/.magisk (old Magisk?): restore the stock boot image first');
    configOrig = new TextDecoder().decode(cpio.extract('.backup/.magisk').data);
    cpio.restore(comp);
  } else if (status === CPIO_UNSUPPORTED) {
    throw new Error('Boot image patched by unsupported programs: please restore back to stock boot image');
  }
  const orig = cpio.clone(); // ramdisk.cpio.orig

  if (configOrig !== undefined) {
    sha1 = grepProp('SHA1', configOrig);
    // BOOTMODE=false: the old config's PREINITDEVICE wins over the environment
    preinit = grepProp('PREINITDEVICE', configOrig);
  }

  log('- Patching ramdisk');
  const magiskXz = comp.xz(payload.magisk, 4096); // compress=xz sniffs the first 4 KiB
  const initLdXz = comp.xz(payload.initLd, 4096);

  if (preinit) log(`- Pre-init storage partition: ${preinit}`);
  const config = magiskConfigText({ keepVerity, keepForceEncrypt, recoveryMode: false, vendorBoot: false, preinitDevice: preinit, sha1 });

  cpio.add(0o750, 'init', payload.magiskinit);
  cpio.mkdir(0o750, 'overlay.d');
  cpio.mkdir(0o750, 'overlay.d/sbin');
  cpio.add(0o644, 'overlay.d/sbin/magisk.xz', magiskXz);
  cpio.add(0o644, 'overlay.d/sbin/init-ld.xz', initLdXz);
  cpio.patch(keepVerity, keepForceEncrypt);
  cpio.backup(orig, comp);
  cpio.mkdir(0o000, '.backup');
  cpio.add(0o000, '.backup/.magisk', new TextEncoder().encode(config));
  return { status, config };
}

// "Binary Patches": what boot_patch.sh would change outside the ramdisk. With KEEPVERITY=true none
// of these apply to this panel's images; if one ever does, refuse rather than emit a different
// image than the CLI.
const KERNEL_HEXPATCHES = [
  '49010054011440B93FA00F71E9000054010840B93FA00F7189000054001840B91FA00F7188010054', // Samsung RKP
  '821B8012', // Samsung defex
  '70726F63615F636F6E66696700', // Samsung PROCA
];

/** Throws if boot_patch.sh would patch this dtb or kernel (it never does on the S1). */
export function checkBinaryPatches(kernel: Uint8Array, dtb: Uint8Array, findBytes: (h: Uint8Array, n: Uint8Array) => number): void {
  const enc = new TextEncoder();
  if (findBytes(dtb, enc.encode('/system_root\0')) >= 0)
    throw new Error('Boot image dtb was patched by old (unsupported) Magisk');
  if (findBytes(dtb, enc.encode('skip_initramfs')) >= 0)
    throw new Error('dtb has skip_initramfs (legacy SAR): would be patched by magiskboot, unsupported here');
  for (const h of KERNEL_HEXPATCHES) {
    const pat = new Uint8Array(h.match(/../g)!.map((x) => parseInt(x, 16)));
    if (findBytes(kernel, pat) >= 0) throw new Error(`kernel matches Magisk hexpatch ${h}: unsupported here`);
  }
}
