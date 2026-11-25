// Silent updates on the Mirror (#422): whether Glassworks is a privileged system app that holds INSTALL_PACKAGES,
// read back from `dumpsys package` over adb. The app answers the same question with that permission alone
// (core SilentUpdates: SystemApp), and Android 6 grants it to an app in /system/priv-app (privileged-app.md).
import type { PanelShell } from '@glassworks/adb';
import { GLASSWORKS, type MirrorApp } from './app';

/**
 * `on`: updates install with nobody at the Mirror — either because the app holds INSTALL_PACKAGES (a
 * privileged `/system/priv-app` copy) or because it is the Device Owner (the Stock-in-place path:
 * `dpm set-device-owner` lets it `PackageInstaller`-self-update silently without INSTALL_PACKAGES).
 * `normal-app`: installed over adb into /data, not privileged, not Device Owner, so Android asks before
 * each update. `not-granted`: privileged, yet Android didn't grant the permission. `unknown`: not read
 * back. `deviceOwner` is present (true) only when the app is the Device Owner.
 */
export type MirrorUpdates =
  | { state: 'on' | 'normal-app' | 'not-granted'; privileged: boolean; deviceOwner?: boolean }
  | { state: 'unknown'; detail: string };

/**
 * Whether `pkg` is the Device Owner, from `dumpsys device_policy`. Android 6 prints a `Device Owner:`
 * block naming the admin as `admin=ComponentInfo{pkg/…}` and `package=pkg`; stock has no Device Owner
 * at all, so the block is absent.
 */
export function isDeviceOwner(policyDump: string, pkg: string): boolean {
  const i = policyDump.indexOf('Device Owner');
  if (i < 0) return false;
  const block = policyDump.slice(i, i + 500);
  const esc = pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?:ComponentInfo\\{|package=)${esc}(?:[/\\s]|$)`).test(block);
}

/**
 * Android 6's `dumpsys package <pkg>`: the package's block, `codePath=/system/priv-app/…` for the copy in the
 * image (also under "Hidden system packages" once an update is in /data), `privateFlags=[ … PRIVILEGED … ]`, and
 * `android.permission.INSTALL_PACKAGES: granted=true` under its install permissions. `policyDump` is
 * `dumpsys device_policy`, so a Device Owner app counts as silent even without INSTALL_PACKAGES.
 */
export function parseMirrorUpdates(dump: string, pkg: string, policyDump = ''): MirrorUpdates {
  const start = dump.indexOf(`Package [${pkg}]`);
  if (start < 0) return { state: 'unknown', detail: `${pkg} isn't in dumpsys package` };
  const privileged = /\bcodePath=\/system\/priv-app\//.test(dump) || /Flags=\[[^\]]*\bPRIVILEGED\b/.test(dump);
  const deviceOwner = isDeviceOwner(policyDump, pkg);
  // the grant only from the installed copy's record: the first, up to the next line indented less than its fields.
  // The image's copy under "Hidden system packages" keeps its own permissions, which may differ.
  const lines = dump.slice(start).split(/\r?\n/);
  const end = lines.findIndex((l, i) => i > 0 && l.trim() !== '' && /^ {0,3}\S/.test(l));
  const active = (end < 0 ? lines : lines.slice(0, end)).join('\n');
  const granted = /\bandroid\.permission\.INSTALL_PACKAGES: granted=true\b/.test(active);
  const owner = deviceOwner ? { deviceOwner: true as const } : {};
  if (granted || deviceOwner) return { state: 'on', privileged, ...owner };
  return { state: privileged ? 'not-granted' : 'normal-app', privileged, ...owner };
}

/** `signal`: cancels a read that takes too long (Kiln's set-up bounds it); it then reads as not confirmed. */
export async function readMirrorUpdates(p: PanelShell, app: MirrorApp = GLASSWORKS, signal?: AbortSignal): Promise<MirrorUpdates> {
  try {
    const r = await p.run(`dumpsys package ${app.package}`, signal);
    if (r.code !== 0) return { state: 'unknown', detail: `dumpsys exited ${r.code}` };
    // Device Owner is a second silent-update path (Stock-in-place). Reading it is best-effort: a
    // failure here just means we fall back to the INSTALL_PACKAGES answer from the package dump.
    let policyDump = '';
    try {
      const pol = await p.run('dumpsys device_policy', signal);
      if (pol.code === 0) policyDump = pol.out;
    } catch { /* best-effort: no device_policy read */ }
    return parseMirrorUpdates(r.out, app.package, policyDump);
  } catch (e) {
    return { state: 'unknown', detail: (e as Error).message };
  }
}

/** The technical log's line. */
export function describeMirrorUpdates(u: MirrorUpdates): string {
  if (u.state === 'unknown') return `silent updates: not read back (${u.detail})`;
  const how = u.deviceOwner ? 'Device Owner' : u.privileged ? 'privileged system app' : 'not privileged';
  const perm = u.deviceOwner ? 'Device Owner' : `INSTALL_PACKAGES ${u.state === 'on' ? 'granted' : 'not granted'}`;
  return `silent updates: ${u.state === 'on' ? 'on' : 'off'} (${how}, ${perm})`;
}
