// Development and preview only (/lululemon-mirror?demo=…): a simulated Mirror behind the real recipe,
// so each screen can be seen and captured without one. `run` waits for clicks like the real page (the
// simulated person taps S1 Kiosk in the chooser a few seconds in); the other names go to one screen.
import { FakeMirror, MIRROR_KIOSK, type FakeMirrorOptions } from '@glassworks/lululemon-mirror-installer/fakes';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { MirrorSession } from './session';

export const MIRROR_DEMOS = ['run', 'pick', 'pick-wrong', 'checks', 'checks-fail', 'installing', 'home', 'home-clear', 'done', 'done-unconfirmed', 'undo-done', 'undo-pending', 'error'] as const;
export type MirrorDemo = (typeof MIRROR_DEMOS)[number];

const OURS = 'sh.edm.s1kiosk/.MainActivity';
const APK = fakeApk('sh.edm.s1kiosk'); // a stand-in: a manifest and nothing else

/** `fast`: no delays (the tests). */
export async function mirrorDemo(name: MirrorDemo, fast = false): Promise<MirrorSession> {
  const o: FakeMirrorOptions = { delayMs: fast ? 0 : 120, pickAfter: 3, picks: (c) => c.find((x) => x === OURS) ?? c.find((x) => x === MIRROR_KIOSK) };
  if (name === 'checks-fail') Object.assign(o, { sdk: 29, release: '10', platform: 'sun50iw10' });
  if (['home', 'home-clear', 'installing', 'done-unconfirmed', 'undo-pending'].includes(name)) o.picks = undefined;
  if (name === 'undo-done' || name === 'undo-pending') o.installed = 600;
  if (name === 'home-clear') o.installed = 500;
  if (name === 'error') o.installFails = 'Failure [INSTALL_FAILED_OLDER_SDK]';
  const mirror = new FakeMirror(o);
  const s = new MirrorSession({ connect: async () => mirror, ...(fast ? { sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms / 100)) } : {}), homeTimeoutMs: name === 'done-unconfirmed' || name === 'undo-pending' ? 0 : 10 * 60_000 }, true);
  if (name === 'pick-wrong') { await s.pickApk('mirror-kiosk-v0.12.0.apk', fakeApk('com.nimrod.mirrorkiosk')); return s; }
  if (name !== 'run') await s.pickApk('kiosk-legacy-debug.apk', APK);
  if (name === 'run' || name === 'pick') return s;
  if (name === 'undo-done' || name === 'undo-pending') mirror.files.set('/data/local/tmp/glassworks-previous-home', `${MIRROR_KIOSK}\n`);
  if (name === 'home-clear') mirror.pick(MIRROR_KIOSK); // an update: Android keeps the other app's Always pick
  await s.connect();
  if (name === 'checks' || name === 'checks-fail') return s;
  if (name === 'installing') { mirror.push = () => new Promise(() => {}); void s.install(); return s; } // stays on the copy
  s.setHaUrl('http://homeassistant.local:8123');
  if (name === 'undo-done') { await s.undo(); return s; }
  if (name === 'undo-pending') { await s.undo(); await s.again(); return s; }
  const done = s.install();
  if (name === 'home' || name === 'home-clear') return s;
  await done;
  return s;
}
