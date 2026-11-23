// The example in README.md ("Use"), line for line. readme.test.ts imports it, so the typecheck checks it.
import { ODIN_USB_FILTERS, OdinDevice, OdinError } from '@glassworks/odin';

export async function flashRecovery(recovery: File): Promise<void> {
  const usb = await navigator.usb.requestDevice({ filters: ODIN_USB_FILTERS }); // from a click
  const odin = await OdinDevice.open(usb); // claims the Download mode interface
  try {
    const info = await odin.info().catch(() => undefined); // model, sales code, bootloader (newer devices)
    console.log(info?.model ?? 'a Samsung device');

    await odin.beginSession({ totalBytes: recovery.size }); // all this session may write
    const pit = await odin.pit();
    const entry = pit.find('RECOVERY');
    if (!entry) throw new Error('this device has no RECOVERY partition');

    await odin.flash({
      partition: 'RECOVERY',
      entry,
      image: recovery,
      onProgress: ({ sentBytes, totalBytes }) => console.log(`${Math.round((100 * sentBytes) / totalBytes)}%`),
    });
    // Stay in Download mode: the person holds the keys for recovery, so the stock system can't replace it.
    await odin.end({ reboot: false });
  } catch (e) {
    if (e instanceof OdinError && e.code === 'claim-failed') console.log(e.message); // says what to do (WinUSB)
    throw e;
  } finally {
    await odin.close();
  }
}
