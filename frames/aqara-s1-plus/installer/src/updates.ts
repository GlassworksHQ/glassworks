// Silent updates on the S1 (#422): whether the boot image's root update service is in place, read back
// over adb the way the app reads it (RootUpdateInstaller.present: the version prop is a number, 1 or more).
import type { PanelShell } from '@glassworks/adb';

/**
 * The prop the update service's rc sets at boot (tools/root/s1kiosk_update.rc): a disabled service has no
 * init.svc.* prop until it first runs. The one place Kiln names it.
 */
export const UPDATE_SERVICE_PROP = 'sys.s1kiosk.update.version';

/** `on`: the service is in this boot image. `missing`: an older boot image without it. `unknown`: not read back. */
export type RootUpdates = { state: 'on' | 'missing' } | { state: 'unknown'; detail: string };

export async function readRootUpdates(p: PanelShell, signal?: AbortSignal): Promise<RootUpdates> {
  try {
    const r = await p.run(`getprop ${UPDATE_SERVICE_PROP}`, signal);
    if (r.code !== 0) return { state: 'unknown', detail: `getprop exited ${r.code}` };
    return { state: Number.parseInt(r.out.trim(), 10) >= 1 ? 'on' : 'missing' };
  } catch (e) {
    return { state: 'unknown', detail: (e as Error).message };
  }
}
