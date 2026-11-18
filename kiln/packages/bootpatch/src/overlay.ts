// The files frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh adds to the ramdisk after Magisk's own patch, with the
// same paths, modes and pre-flight checks. The caller supplies the bytes (fetched assets + the
// browser's adb key).

import type { OverlayFile } from './index';
import { zipEntries } from './apk';

export interface S1OverlayInputs {
  rc: Uint8Array;             // frames/aqara-s1-plus/tools/root/glassworks.rc (the init service slots)
  loaderSh: Uint8Array;       // frames/aqara-s1-plus/tools/root/glassworks_loader.sh
  verify: Uint8Array;         // frames/aqara-s1-plus/tools/root/out/s1kiosk_verify (frames/aqara-s1-plus/tools/root/build-verify.sh)
  bundlePub: Uint8Array;      // frames/aqara-s1-plus/tools/root/glassworks_bundle.pub (bundle signing key, public half)
  /** adb authorized_keys line(s) ('QAAAA... name\n'), or empty (ADB_PUBKEY=none). */
  adbKeys: Uint8Array;
  radioSh: Uint8Array;        // frames/aqara-s1-plus/tools/radio/glassworks_radio.sh
  otbrAgent: Uint8Array;      // frames/aqara-s1-plus/tools/radio/out/otbr-agent
  otCtl: Uint8Array;          // frames/aqara-s1-plus/tools/radio/out/ot-ctl
  firstbootSh: Uint8Array;    // frames/aqara-s1-plus/tools/root/glassworks_firstboot.sh
  netwatchSh: Uint8Array;     // frames/aqara-s1-plus/tools/root/glassworks_netwatch.sh
  updateSh: Uint8Array;       // frames/aqara-s1-plus/tools/root/glassworks_update.sh
  /** Boot animation (all entries STORED), mounted by the loader; omit for stock. */
  bootAnimation?: Uint8Array;
}

/** The exact OverlayFile list patch-boot-magisk.sh adds. Throws where the script would exit 1. */
export function s1Overlay(i: S1OverlayInputs): OverlayFile[] {
  // patch-boot-magisk.sh: grep -q '^QAAAA' (some line must start with it); empty = ADB_PUBKEY=none
  if (i.adbKeys.length && !/^QAAAA/m.test(new TextDecoder().decode(i.adbKeys)))
    throw new Error('adbKeys does not look like an adb public key (must start with QAAAA)');
  // patch-boot-magisk.sh: grep -qE '^[0-9a-f]{64}$' frames/aqara-s1-plus/tools/root/glassworks_bundle.pub
  if (!/^[0-9a-f]{64}$/m.test(new TextDecoder().decode(i.bundlePub)))
    throw new Error('bundlePub is not a 32-byte hex public key');
  if (!i.verify.length) throw new Error('empty verifier');
  const files: OverlayFile[] = [
    { path: 'overlay.d/glassworks.rc', mode: 0o644, data: i.rc },
    { path: 'overlay.d/sbin/glassworks_loader.sh', mode: 0o700, data: i.loaderSh },
    { path: 'overlay.d/sbin/glassworks_verify', mode: 0o700, data: i.verify },
    { path: 'overlay.d/sbin/glassworks_bundle.pub', mode: 0o644, data: i.bundlePub },
    { path: 'overlay.d/sbin/glassworks_adb_keys', mode: 0o600, data: i.adbKeys },
    { path: 'overlay.d/sbin/glassworks_radio.sh', mode: 0o700, data: i.radioSh },
    { path: 'overlay.d/sbin/otbr-agent', mode: 0o700, data: i.otbrAgent },
    { path: 'overlay.d/sbin/ot-ctl', mode: 0o700, data: i.otCtl },
    { path: 'overlay.d/sbin/glassworks_firstboot.sh', mode: 0o700, data: i.firstbootSh },
    { path: 'overlay.d/sbin/glassworks_netwatch.sh', mode: 0o700, data: i.netwatchSh },
    { path: 'overlay.d/sbin/glassworks_update.sh', mode: 0o700, data: i.updateSh },
  ];
  if (i.bootAnimation) {
    if (!i.bootAnimation.length) throw new Error('empty boot animation');
    const bad = [...zipEntries(i.bootAnimation).values()].filter((e) => e.method !== 0).map((e) => e.name);
    if (bad.length) throw new Error(`bootanimation.zip entries must be STORED, not compressed: ${bad.slice(0, 3).join(', ')}`);
    files.push({ path: 'overlay.d/sbin/glassworks_bootanimation.zip', mode: 0o644, data: i.bootAnimation });
  }
  return files;
}
