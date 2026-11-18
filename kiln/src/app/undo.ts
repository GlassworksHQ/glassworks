// "Keep the panel's settings" gives the panel back to Aqara's apps before the software is restored,
// and that removes or disables Glassworks. So the backup must pass undo's checks first: a backup that
// can't be restored stops here, with the kiosk still in place.
import type { UndoResult } from '@glassworks/aqara-s1-plus-installer';
import { checkRestorable, type BackupPlan } from '@glassworks/kiln-core';

export async function giveBackApps<T>(plan: BackupPlan, files: ReadonlyMap<string, Uint8Array>, giveBack: () => Promise<T>): Promise<T> {
  await checkRestorable(plan, files);
  return giveBack();
}

/** The give-back step as the undo screen lists it. Glassworks stays, disabled, when it saved the Wi-Fi. */
export const GIVE_BACK_STEP: [string, string] = [
  'Give the panel back to Aqara\'s apps',
  'With the panel running normally and plugged in. This page removes Glassworks, or turns it off if it saved the panel\'s Wi-Fi, so the panel stays online. It turns Aqara\'s launcher, voice, updates and radio back on.',
];

/** What the undo screen says after the give-back, from what undo did with Glassworks. */
export function appsBackNote(r: UndoResult): { done: boolean; kind: 'ok' | 'err'; title: string; sub: string } {
  if (r.app === 'running') {
    return { done: false, kind: 'err', title: 'Aqara\'s apps are back, but Glassworks is still on', sub: `${r.note} Try again, or choose "Back to how it came from Aqara".` };
  }
  return { done: true, kind: 'ok', title: 'Aqara\'s apps are back', sub: `${r.note} Next: restore the original software. Click Continue, then put the panel in FEL mode.` };
}
