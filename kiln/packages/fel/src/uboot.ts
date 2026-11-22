// The reference CLI's fel-boot.sh with NO_BL31=1, in the browser: check the chip, run the SPL (DRAM init),
// upload u-boot.bin to DRAM in 64 KiB writes, then warm-reset into it. Nothing is written to eMMC.
// Our U-Boot then starts USB fastboot by itself (BOOTCOMMAND "fastboot usb 0").
import { A133 } from './a133.ts';
import { hex, type FelDevice } from './felDevice.ts';

export interface ChipCheck { socId: number; secureBoot: number }

/** Another chip than the A133: a frame's recipe says what the person plugged in instead (its own words). */
export class WrongChip extends Error {
  constructor(readonly socId: number) {
    super(`this chip isn't an A133 (SoC 0x${socId.toString(16)}, expected 0x${A133.socId.toString(16)})`);
    this.name = 'WrongChip';
  }
}

/** Refuses anything but an A133 whose boot ROM still runs unsigned code (fel-install.sh step 1). */
export async function checkChip(fel: FelDevice): Promise<ChipCheck> {
  const v = await fel.version();
  if (v.socId !== A133.socId) {
    throw new WrongChip(v.socId);
  }
  const secureBoot = await fel.readl(A133.sidSecureBootReg);
  if (secureBoot !== 0) {
    throw new Error(
      `secure boot is enabled on this chip (SID+0xa0 = 0x${hex(secureBoot)}): its boot ROM only runs ` +
      'signed code, so this installer cannot work on it. Nothing was changed.',
    );
  }
  return { socId: v.socId, secureBoot };
}

/** Progress: 0..1 over the whole boot (the U-Boot upload is nearly all of the time). */
export async function bootUboot(
  fel: FelDevice, splWithUboot: Uint8Array, ubootBin: Uint8Array, onProgress?: (p: number) => void,
): Promise<void> {
  await checkChip(fel);
  onProgress?.(0.02);
  await fel.runSpl(splWithUboot);
  onProgress?.(0.08);
  await fel.write(A133.ubootAddr, ubootBin, (done, total) => onProgress?.(0.08 + 0.9 * (done / total)));
  await fel.reset64(A133.ubootAddr);
  onProgress?.(1);
  await fel.close();
}
