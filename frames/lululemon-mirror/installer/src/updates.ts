// Silent updates on the Mirror (#422): whether Glassworks is a privileged system app that holds INSTALL_PACKAGES,
// read back from `dumpsys package` over adb. The app answers the same question with that permission alone
// (core SilentUpdates: SystemApp), and Android 6 grants it to an app in /system/priv-app (privileged-app.md).
import type { PanelShell } from '@glassworks/adb';
import { GLASSWORKS, type MirrorApp } from './app';

/**
 * `on`: INSTALL_PACKAGES granted, so updates install with nobody at the Mirror. `normal-app`: installed over adb
 * into /data, not privileged, so Android asks before each update. `not-granted`: privileged, yet Android didn't
 * grant the permission. `unknown`: not read back.
 */
export type MirrorUpdates = { state: 'on' | 'normal-app' | 'not-granted'; privileged: boolean } | { state: 'unknown'; detail: string };

/**
 * Android 6's `dumpsys package <pkg>`: the package's block, `codePath=/system/priv-app/…` for the copy in the
 * image (also under "Hidden system packages" once an update is in /data), `privateFlags=[ … PRIVILEGED … ]`, and
 * `android.permission.INSTALL_PACKAGES: granted=true` under its install permissions.
 */
export function parseMirrorUpdates(dump: string, pkg: string): MirrorUpdates {
  const start = dump.indexOf(`Package [${pkg}]`);
  if (start < 0) return { state: 'unknown', detail: `${pkg} isn't in dumpsys package` };
  const privileged = /\bcodePath=\/system\/priv-app\//.test(dump) || /Flags=\[[^\]]*\bPRIVILEGED\b/.test(dump);
  // the grant only from the installed copy's record: the first, up to the next line indented less than its fields.
  // The image's copy under "Hidden system packages" keeps its own permissions, which may differ.
  const lines = dump.slice(start).split(/\r?\n/);
  const end = lines.findIndex((l, i) => i > 0 && l.trim() !== '' && /^ {0,3}\S/.test(l));
  const active = (end < 0 ? lines : lines.slice(0, end)).join('\n');
  if (/\bandroid\.permission\.INSTALL_PACKAGES: granted=true\b/.test(active)) return { state: 'on', privileged };
  return { state: privileged ? 'not-granted' : 'normal-app', privileged };
}

/** `signal`: cancels a read that takes too long (Kiln's set-up bounds it); it then reads as not confirmed. */
export async function readMirrorUpdates(p: PanelShell, app: MirrorApp = GLASSWORKS, signal?: AbortSignal): Promise<MirrorUpdates> {
  try {
    const r = await p.run(`dumpsys package ${app.package}`, signal);
    if (r.code !== 0) return { state: 'unknown', detail: `dumpsys exited ${r.code}` };
    return parseMirrorUpdates(r.out, app.package);
  } catch (e) {
    return { state: 'unknown', detail: (e as Error).message };
  }
}

/** The technical log's line. */
export function describeMirrorUpdates(u: MirrorUpdates): string {
  return u.state === 'unknown' ? `silent updates: not read back (${u.detail})`
    : `silent updates: ${u.state === 'on' ? 'on' : 'off'} (${u.privileged ? 'privileged system app' : 'not privileged'}, INSTALL_PACKAGES ${u.state === 'on' ? 'granted' : 'not granted'})`;
}
