// The two builds of Glassworks for generic Android (#403, maintainer 2026-11-16: app ids are per build).
// Android 8+ gets `dev.glassworks.generic_android`; Android 6-7 its legacy build, `.legacy` (#198). Both
// have the same components, from the core (package sh.edm.s1kiosk) and the generic app
// (frames/generic-android/android). test/app.test.ts checks these names against the build and manifest.
import type { AppId } from '@glassworks/app-adb';

export interface GenericApp extends AppId {
  /** what the screens and logs call it */
  name: string;
  build: 'generic' | 'legacy';
  /** the HOME component: the Launcher alias, whose MAIN+HOME+DEFAULT filter Android resolves HOME to */
  home: string;
  /** the Android API levels it is for */
  apis: { min: number; max?: number };
}

const ID = 'dev.glassworks.generic_android';
const parts = (pkg: string) => ({
  package: pkg,
  activity: `${pkg}/sh.edm.s1kiosk.MainActivity`,
  home: `${pkg}/dev.glassworks.generic.Launcher`,
  admin: `${pkg}/sh.edm.s1kiosk.AdminReceiver`,
});

export const GENERIC: GenericApp = { name: 'Glassworks', build: 'generic', apis: { min: 26 }, ...parts(ID) };
export const LEGACY: GenericApp = { name: 'Glassworks (legacy build)', build: 'legacy', apis: { min: 23, max: 25 }, ...parts(`${ID}.legacy`) };
export const APPS = [GENERIC, LEGACY] as const;

/** The oldest Android Glassworks runs on: 6.0, API 23. */
export const MIN_API = 23;

/** The build for a device on API `sdk`; undefined below Android 6. */
export function appFor(sdk: number): GenericApp | undefined {
  return APPS.find((a) => sdk >= a.apis.min && (a.apis.max === undefined || sdk <= a.apis.max));
}

/** Android's version for an API level, for messages: 23 → "6.0". */
export function androidName(sdk: number): string {
  const n: Record<number, string> = { 21: '5.0', 22: '5.1', 23: '6.0', 24: '7.0', 25: '7.1', 26: '8.0', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };
  return n[sdk] ?? (sdk > 36 ? `newer than 16` : 'older than 5');
}
