// The app Kiln puts on the Mirror. Since #408 that's the legacy build of Glassworks for generic Android
// (minSdk 23, GeckoView 144), named in the frame's own data (design/frame.json `app`), not the S1 app.
import type { AppId } from '@glassworks/app-adb';
import frame from '../../design/frame.json';

export interface MirrorApp extends AppId {
  /** what the screens and logs call it */
  name: string;
  /** the HOME component Android records as the preferred home (its MAIN+HOME+DEFAULT filter's activity or alias) */
  home: string;
  /** its folder and file in the system image: /system/priv-app/<dir>/<file> */
  dir: string;
  file: string;
}

/**
 * Glassworks, as frame.json names it. Its folder is /system/priv-app/Glassworks: Android 6 keys a
 * package by its manifest, not its folder, so the folder can carry the product's name, and it never
 * meets the probe's /priv-app/S1Kiosk on one image.
 */
export const GLASSWORKS: MirrorApp = {
  name: 'Glassworks',
  package: frame.app.id,
  activity: `${frame.app.id}/${frame.app.activity}`,
  home: `${frame.app.id}/${frame.app.home}`,
  admin: `${frame.app.id}/${frame.app.admin}`,
  dir: '/priv-app/Glassworks',
  file: 'Glassworks.apk',
};

/**
 * The Mirror probe (tools/mirror/probe-app), test only, chosen by the harness's --probe. It keeps the folder its
 * README and research note were verified with, when it still borrowed the S1 app's id (before #527).
 */
export const MIRROR_PROBE: MirrorApp = {
  name: 'the Mirror probe',
  package: 'dev.glassworks.mirror.probe',
  activity: 'dev.glassworks.mirror.probe/.MainActivity',
  home: 'dev.glassworks.mirror.probe/.MainActivity',
  dir: '/priv-app/S1Kiosk',
  file: 'S1Kiosk.apk',
};

/** Every folder an app of ours can have in the image: patchSystem clears them all, so one image has one app. */
export const APP_DIRS = [GLASSWORKS.dir, MIRROR_PROBE.dir];

/**
 * The privileged permissions the app must ask for in the copy Kiln writes into /system. Android 6
 * grants a privileged permission only to what the /system copy asked for: an update can never add one
 * (privileged-app.md).
 */
export const PRIVILEGED = ['android.permission.INSTALL_PACKAGES', 'android.permission.REBOOT', 'android.permission.WRITE_SECURE_SETTINGS'];
