// Asking a device to let this browser's adb key in, for every device flow (issue 621): the browser's list once, inside
// the click, then the device it picked again with no list every 5 s while it refuses the key (a device still starting
// answers before it reads its keys, and one waiting for Allow hasn't had it yet), until it lets the key in or the
// window ends. A list closed with nothing picked, or a device another program holds, isn't asked again: asking changes
// nothing there. The wizard's install polls the same way (kiln-core flow.ts findOrAsk).
import { AdbBusy, type PanelShell } from '@glassworks/adb';

/** The device over adb, as a page wires it to WebUSB (DevicePage `webUsbAdb`). */
export interface AdbAsk {
  /**
   * The browser's list, then this browser's key. Called inside the click, before any await. With `authMs`, it gives up
   * (AdbAuthTimeout) when the key isn't let in by then. `onPicked`: the list closed on a device.
   */
  request(o?: { authMs?: number; onPicked?: () => void }): Promise<PanelShell>;
  /** The device the last request picked, again with no list. Without it, the first refusal ends the ask. */
  reopen?(o?: { authMs?: number }): Promise<PanelShell>;
  /** Whether the last request picked a device, so its refusal came from the device. */
  picked?(): boolean;
}

export interface AskOptions {
  /** The first try's deadline (none: adb's own, which waits for Allow for ever). */
  firstAuthMs?: number;
  /** Each try after it gets at most this, and what's left of the window. */
  authMs: number;
  sleep(ms: number): Promise<void>;
  /** How many times it asks again, 5 s apart (default 60). */
  tries?: number;
  /** How long it keeps asking (default five minutes); each try gets what's left. */
  windowMs?: number;
  /** The list closed on a device: the screen can say it waits from here. */
  onPicked?(): void;
  /** It asks again after a refusal: `why` is the refusal, for the log. */
  onWait(why: string): void;
}

/** In: the device, connected. Out: why not, whether a device was picked at all, and whether it was asked again. */
export type Asked = { panel: PanelShell } | { panel?: undefined; last: unknown; picked: boolean; retried: boolean };

/** The browser's list was closed with nothing picked: nothing to ask again. */
export const nothingPicked = (e: unknown) => (e as { name?: string } | undefined)?.name === 'NotFoundError' || (e as Error | undefined)?.message === 'no panel selected';

/** Must be called inside the click: `request` runs before its first await. */
export async function askAdb(a: AdbAsk, o: AskOptions): Promise<Asked> {
  const req = a.request({ ...(o.firstAuthMs !== undefined ? { authMs: o.firstAuthMs } : {}), onPicked: () => o.onPicked?.() });
  let last: unknown;
  try { return { panel: await req }; } catch (e) { last = e; }
  const picked = a.picked?.() ?? !nothingPicked(last);
  const tries = a.reopen && picked && !(last instanceof AdbBusy) ? o.tries ?? 60 : 0;
  const until = Date.now() + (o.windowMs ?? 5 * 60_000);
  for (let i = 0; i < tries && Date.now() < until; i++) {
    o.onWait((last as Error).message);
    await o.sleep(Math.min(5000, Math.max(0, until - Date.now())));
    const left = until - Date.now();
    if (left <= 0) break;
    try { return { panel: await a.reopen!({ authMs: Math.min(o.authMs, left) }) }; } catch (e) { last = e; }
    if (last instanceof AdbBusy) break;
  }
  return { last, picked, retried: tries > 0 };
}
