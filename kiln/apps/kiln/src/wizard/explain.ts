// What a failure means for the person at the panel: what happened, whether anything was written, and
// the one most likely fix. Matched on the engine's messages, first match wins.
import { BackupRefused } from '@glassworks/kiln-core';
import type { Changed, Failure } from './state';

interface Rule {
  test: (e: unknown, msg: string) => boolean;
  title: string;
  /** the most likely fix, in one sentence */
  fix: string;
}

const named = (name: string) => (e: unknown) => e instanceof DOMException && e.name === name;

/**
 * WebUSB refusing a device the browser listed: `denied`, this computer didn't let the browser open it (Chrome's
 * SecurityError "Access denied": on Linux a missing udev rule, on Windows the wrong driver); `claimed`, it opened but
 * another program holds its interface (Chrome's NetworkError "Unable to claim interface": often adb, or on Windows the
 * wrong driver). Neither is a disconnect.
 */
export type UsbRefusal = 'denied' | 'claimed';

export function usbRefusal(e: unknown): UsbRefusal | undefined {
  const m = e instanceof Error ? e.message : String(e);
  if (named('SecurityError')(e) || /access denied/i.test(m)) return 'denied';
  if (/unable to claim interface/i.test(m)) return 'claimed';
  return undefined;
}

/**
 * A refusal in calm words, for a device screen's connect error (`the`: the frame's `the`). `shown`: the screen shows the
 * frame's one-time USB setup for this computer above it (installer.json `device.drivers`).
 */
export function usbRefusalText(r: UsbRefusal, the: string, shown = false): string {
  return r === 'denied'
    ? `This computer didn’t let the browser open ${the} over USB, so nothing was written. ${shown ? 'Do the one-time USB setup above' : 'On Windows or Linux, set up its USB driver or permission once'}, then unplug ${the}, plug it back in and click Connect again.`
    : `Another program is using ${the}’s USB connection, often adb, so nothing was written. Close it (or run "adb kill-server") and any other Kiln tab, then click Connect again.`;
}

const RULES: Rule[] = [
  // undo's checkRestorable, before any write; the reason is in `raw`, under More help. First, because
  // its words ("install") match the disconnect rule's /stall/.
  { test: (e) => e instanceof BackupRefused, title: 'This backup can\'t undo the install',
    fix: 'Choose the backup from your first install, or follow the manual restore guide.' },
  // the browser listed the panel but couldn't open it, or something else holds it: before the disconnect rule, which
  // a NetworkError would otherwise match
  { test: (e) => usbRefusal(e) === 'denied', title: 'This computer didn\'t let Kiln open the panel',
    fix: 'Set up the one-time USB driver step from the first screen (on Linux a udev rule, on Windows the WinUSB driver), then unplug the panel, plug it back in and try again.' },
  { test: (e) => usbRefusal(e) === 'claimed', title: 'Another program is using the panel',
    fix: 'Close any other browser tab or Android tool using the panel (or run "adb kill-server"), then try again. On Windows, check the panel has the WinUSB driver.' },
  { test: named('NotFoundError'), title: 'No panel in recovery mode',
    fix: 'Unplug it, hold the recovery button on the board first, then plug it in and keep holding for 10 seconds.' },
  { test: (_, m) => /secure boot is enabled/.test(m), title: 'This panel only runs signed software',
    fix: 'Its chip has secure boot enabled, so Kiln can\'t start on it.' },
  { test: (_, m) => /does not look like/.test(m), title: 'This isn\'t a panel Kiln recognizes',
    fix: 'Check that you picked the right panel on the first screen.' },
  { test: (_, m) => /two reads of .* differ/.test(m), title: 'The USB connection isn\'t steady',
    fix: 'Plug the cable straight into this computer rather than through a hub, then try again.' },
  // the app from its release (wizard/assets.ts, release/apk.ts), before the disconnect rule: its /stall/ is in "install"
  { test: (_, m) => /release to install yet/.test(m), title: 'There\'s no release to install yet',
    fix: 'Kiln couldn\'t find a published release of the app. Try again in a little while.' },
  { test: (_, m) => /^Couldn't (check for releases|download|verify the release)|^The release checksum|didn't match the published release/.test(m),
    title: 'A download failed', fix: 'Check this computer\'s internet connection, then try again.' },
  { test: (e, m) => named('NetworkError')(e) || /disconnect|transfer|LIBUSB|stall|No device selected|device was lost/i.test(m),
    title: 'The panel disconnected', fix: 'Plug the cable straight into this computer or try another USB port, then try again.' },
  { test: (_, m) => /SHA-256|installer files are missing|could not download|not in the manifest|HTTP \d/.test(m),
    title: 'A download failed', fix: 'Check this computer\'s internet connection, then try again.' },
  { test: (_, m) => /did not finish starting/.test(m), title: 'The panel took too long to start',
    fix: 'Unplug it and plug it back in, then choose Finish setting up.' },
  { test: (_, m) => /adb kill-server|another program holds/.test(m), title: 'Another program is using the panel',
    fix: 'Close any other browser tab or Android tool using the panel (or run "adb kill-server"), then try again.' },
];

const WRITTEN_TIPS: [string, string][] = [
  ['Recovery mode still works.', 'Even if the panel doesn\'t start normally, you can always put it back into recovery mode and restore your saved backup.'],
  ['Keep your backup handy.', 'If a saved backup is still readable in this browser, you can download a copy from Undo; otherwise keep your backup ZIP file handy for another try.'],
];

const CLEAN_TIPS: [string, string][] = [
  ['Try another cable or USB port.', 'Plug straight into this computer instead of through a hub or dock, and make sure the cable carries data.'],
  ['Another program may be using the panel.', 'Only one app can connect over USB at a time. Close other Kiln tabs or Android tools (or run "adb kill-server") and try again.'],
  ['On Windows or Linux?', 'Make sure the one-time USB driver step from the first screen is set up.'],
];

/**
 * What this attempt wrote before it stopped: `verified`, a write was flashed and read back; `maybe`, a
 * write started and didn't finish; `none`. `installed`: the new software was already in place (the
 * setup step), so nothing more could break. `earlier`: an earlier step may have changed the panel
 * (WizardState.changed), so `none` isn't "nothing was written".
 */
export type Written = 'none' | 'maybe' | 'verified';

export function explain(e: unknown, at: number, written: Written, installed = false, earlier?: Changed): Failure {
  const raw = e instanceof Error ? e.message : String(e);
  // On an installed panel a cancelled picker is the adb one: the recovery-mode advice doesn't apply.
  const rule = installed && named('NotFoundError')(e)
    ? { title: 'Nothing was picked', fix: 'Try again, and pick the panel in the browser\'s list.' }
    : RULES.find((r) => r.test(e, raw));
  const known = earlier === 'written' || earlier === 'apps';
  const before = written === 'none' && !installed && known;
  const unsure = written === 'none' && !installed && earlier === 'unknown';
  const changed = !installed && (written !== 'none' || known);
  const said = installed ? 'The new software is in place.'
    : written === 'verified' ? 'Part of the new software was written.'
    : written === 'maybe' ? 'Part of the new software may have been written.'
    : before ? (earlier === 'written' ? 'This attempt wrote nothing; an earlier try did.' : 'This attempt wrote nothing; the panel\'s apps were changed earlier.')
    : unsure ? 'This attempt wrote nothing. Kiln can\'t tell if an earlier try in this browser tab did.'
    : 'Nothing was written.';
  // a refused backup: the way on is another backup or undo by hand, not this one again.
  // A closed device list: nothing runs, so the way back starts with recovery mode.
  const fix = before && e instanceof BackupRefused
    ? rule!.fix
    : before && named('NotFoundError')(e) && at === 1
    ? `${rule!.fix} Then use Undo with your backup.`
    : changed
      ? 'Put the panel back into recovery mode and use Undo with your saved backup (in this browser or your backup ZIP file).'
      : rule?.fix ?? (installed ? 'Unplug it, plug it back in, and choose Finish setting up.' : 'Unplug it and start again from recovery mode.');
  return {
    title: rule?.title ?? (changed ? 'Something went wrong while writing' : 'Something went wrong'),
    body: `${said} ${fix}`,
    happened: said,
    fix,
    wrote: changed,
    tips: changed ? WRITTEN_TIPS : CLEAN_TIPS,
    raw: rule && named('NotFoundError')(e) ? '' : raw,
    at,
  };
}
