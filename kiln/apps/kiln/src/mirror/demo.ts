// Development and preview only (/lululemon-mirror?demo=…): a simulated Mirror behind the real recipe,
// so each screen can be seen and captured without one. `run` waits for clicks like the real page; the other names go to
// one screen. Nothing here taps the Mirror: it has no touchscreen.
import { HIDDEN_HOMES, PREVIOUS_HOME } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, MIRROR_KIOSK, fakeMirrorApk, type FakeMirrorOptions } from '@glassworks/lululemon-mirror-installer/fakes';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { fakeReleaseRoute } from '../release/demo';
import { MIRROR_APK, MirrorSession } from './session';

export const MIRROR_DEMOS = ['run', 'pick', 'pick-release', 'pick-wrong', 'checks', 'checks-fail', 'installing', 'done', 'done-unconfirmed', 'undo-done', 'undo-pending', 'error'] as const;
export type MirrorDemo = (typeof MIRROR_DEMOS)[number];

const APK = fakeMirrorApk(); // a stand-in: the legacy app's manifest and nothing else

/** `fast`: no delays (the tests). */
export async function mirrorDemo(name: MirrorDemo, fast = false): Promise<MirrorSession> {
  // a Mirror Kiln flashed (a fresh userdata, no community kiosk); done-unconfirmed: the kiosk as Glass Panes' userdata has it,
  // a device admin Android won't hide; the undo demos: the kiosk with its admin off, hidden by an install
  const o: FakeMirrorOptions = { delayMs: fast ? 0 : 120, kiosk: name === 'done-unconfirmed' ? 'admin' : name.startsWith('undo-') ? 'app' : 'none' };
  if (name === 'checks-fail') Object.assign(o, { sdk: 29, release: '10', platform: 'sun50iw10' });
  if (name === 'undo-done' || name === 'undo-pending') o.installed = 600;
  if (name === 'error') o.installFails = 'Failure [INSTALL_FAILED_OLDER_SDK]';
  const mirror = new FakeMirror(o);
  const release = name === 'pick-release' ? { release: fakeReleaseRoute('out', APK, (ms) => new Promise((r) => setTimeout(r, fast ? 0 : ms)), MIRROR_APK.line) } : {};
  const s = new MirrorSession({ connect: async () => mirror, ...release, ...(fast ? { sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms / 100)) } : {}) }, true);
  if (name === 'pick-wrong') { await s.pickApk('mirror-kiosk-v0.12.0.apk', fakeApk('com.nimrod.mirrorkiosk')); return s; }
  if (name === 'pick-release') return s; // the screen downloads it
  if (name !== 'run') await s.pickApk('generic-android-legacy.apk', APK);
  if (name === 'run' || name === 'pick') return s;
  if (name === 'undo-done' || name === 'undo-pending') {
    // as an install leaves it: the kiosk hidden and recorded; undo-pending: the image's launcher left visible too
    mirror.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    mirror.hidden.add('com.nimrod.mirrorkiosk');
    if (name === 'undo-done') mirror.hidden.add('com.android.launcher3');
    mirror.files.set(HIDDEN_HOMES, name === 'undo-done' ? 'com.android.launcher3 com.nimrod.mirrorkiosk\n' : 'com.nimrod.mirrorkiosk\n');
  }
  await s.connect();
  if (name === 'checks' || name === 'checks-fail') return s;
  if (name === 'installing') { mirror.push = () => new Promise(() => {}); void s.install(); return s; } // stays on the copy
  s.setHaUrl('http://homeassistant.local:8123');
  if (name === 'undo-done') { await s.undo(); return s; }
  if (name === 'undo-pending') { await s.undo(); await s.again(); return s; }
  await s.install();
  return s;
}
