// The example in README.md ("Use"), line for line. readme.test.ts imports it, so the typecheck checks it.
import { FelDevice, WrongChip, bootUboot } from '@glassworks/fel';

// From a click: the browser asks which device to use (USB id 1f3a:efe8, a chip in FEL mode).
export async function boot(spl: Uint8Array, uboot: Uint8Array): Promise<void> {
  const fel = await FelDevice.request();
  try {
    await bootUboot(fel, spl, uboot, (p) => console.log(`${Math.round(p * 100)} %`));
  } catch (e) {
    await fel.close(); // bootUboot closes it once it boots; on an error the device is still open
    if (e instanceof WrongChip) console.log(`not an A133: SoC 0x${e.socId.toString(16)}`);
    throw e;
  }
}
