// Silent updates on the S1 (#422): whether the boot image's root update service is in place, read back
// over adb the way the app reads it (the version prop is a number, 1 or more), under the boot image's own names.
import type { PanelShell } from '@glassworks/adb';

/**
 * The prop the update service's rc sets at boot: a disabled service has no init.svc.* prop until it first runs.
 * A 0.4.3 boot image's (tools/root/s1kiosk_update.rc). The one place Kiln names it.
 */
export const UPDATE_SERVICE_PROP = 'sys.s1kiosk.update.version';
/** The same on a frame loader image (tools/root/glassworks.rc, #549): its update slot, under the Glassworks names. */
export const LOADER_UPDATE_SERVICE_PROP = 'sys.glassworks.update.version';
/** Set by the frame loader at boot; empty on a 0.4.3 image. The app picks its names by it too (S1Names, #657). */
export const FRAME_ID_PROP = 'ro.glassworks.frame.id';

/** `on`: the service is in this boot image. `missing`: an older boot image without it. `unknown`: not read back. */
export type RootUpdates = { state: 'on' | 'missing' } | { state: 'unknown'; detail: string };

export async function readRootUpdates(p: PanelShell, signal?: AbortSignal): Promise<RootUpdates> {
  try {
    // The image's own names: the loader's when it runs (its frame id is set), else 0.4.3's
    const id = await p.run(`getprop ${FRAME_ID_PROP}`, signal);
    if (id.code !== 0) return { state: 'unknown', detail: `getprop exited ${id.code}` };
    const prop = id.out.trim() ? LOADER_UPDATE_SERVICE_PROP : UPDATE_SERVICE_PROP;
    const r = await p.run(`getprop ${prop}`, signal);
    if (r.code !== 0) return { state: 'unknown', detail: `getprop exited ${r.code}` };
    return { state: Number.parseInt(r.out.trim(), 10) >= 1 ? 'on' : 'missing' };
  } catch (e) {
    return { state: 'unknown', detail: (e as Error).message };
  }
}
