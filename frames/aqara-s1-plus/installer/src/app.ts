// The S1 app's id over adb. The boot helpers and every installed panel use it: never change it.
import type { AppId } from '@glassworks/app-adb';

export const PKG = 'sh.edm.s1kiosk';
export const ACT = `${PKG}/.MainActivity`;
export const ADMIN = `${PKG}/.AdminReceiver`;
/** The app the S1's provisioning acts on (its commands are pinned: test/adb-commands-pin.test.ts). */
export const S1_KIOSK: AppId = { package: PKG, activity: ACT, admin: ADMIN };
