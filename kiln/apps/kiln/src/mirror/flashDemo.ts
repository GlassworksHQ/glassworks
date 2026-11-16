// Development and preview only (/lululemon-mirror?demo=flash-…): the fastboot install against a
// simulated Mirror, so each screen can be seen and captured without one. The images aren't built (a
// stand-in reports progress); the fastboot and first-boot answers are the recipe's fakes.
import { fakeApk } from '@glassworks/app-adb/fakes';
import { GLASS_PANES, GLASS_PANES_USERDATA, ProxyError, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, checkDownload, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import { FlashSession, type ImageMaker } from './flash';
import { MirrorSession } from './session';

export const FLASH_DEMOS = ['flash-image', 'flash-downloading', 'flash-proxy-failed', 'flash-checking', 'flash-wrong', 'flash-ready', 'flash-fastboot', 'flash-fastboot-wrong', 'flash-confirm',
  'flash-working', 'flash-adb', 'flash-done', 'flash-done-chooser', 'flash-done-no-home', 'flash-error', 'flash-back', 'flash-run'] as const;
export type FlashDemo = (typeof FLASH_DEMOS)[number];

export async function flashDemo(name: FlashDemo, fast = false): Promise<{ flash: FlashSession; mirror: MirrorSession }> {
  const tick = (ms: number) => new Promise<void>((r) => setTimeout(r, fast ? 0 : ms));
  const images: ImageMaker = {
    async load(file, onProgress) {
      if (name === 'flash-checking') { onProgress(0.42); return new Promise(() => {}); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
      if (file.size === 1234) throw new WrongImage('This file is 1,234 bytes; the Glass Panes system.img is 850,905,808. It\'s another file, or the download stopped early. Download system.img again from the Glass Panes release, then select the new file.');
    },
    async fetch(_, onProgress) {
      if (name === 'flash-downloading') { onProgress(0.42); return new Promise(() => {}); }
      if (['flash-proxy-failed', 'flash-checking', 'flash-wrong'].includes(name)) { await tick(200); throw new ProxyError('the download proxy answered 502: GitHub answered 503'); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
    },
    async build(_, onProgress) {
      for (const step of ['patch', 'system', 'userdata'] as const) {
        for (let i = 1; i <= 5; i++) {
          if (name === 'flash-working' && step === 'system' && i === 3) { await tick(300); onProgress(step, 0.4); return new Promise(() => {}); } // stays here, painted twice
          await tick(150); onProgress(step, i / 5);
        }
      }
      return { system: new Blob([new Uint8Array(16)]), userdata: new Blob([new Uint8Array(16)]) };
    },
    check: (file, which, onProgress) => checkDownload(file, which === 'system' ? GLASS_PANES.system : GLASS_PANES_USERDATA, onProgress),
    async discard() {},
  };
  const fastboot = (system: number): FastbootLike => ({
    async getvar(n) { if (n === 'product') return 'MSM8916'; if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? system : USERDATA_BYTES; },
    async flashBlob(_, __, onProgress = () => {}) {
      if (name === 'flash-error') throw new Error('flash:system: FAILed (remote: write failed)');
      for (let i = 1; i <= 8; i++) { await tick(150); onProgress(i / 8); }
    },
    async reboot() {},
  });
  const mirror = new MirrorSession({ connect: async () => { throw new Error('not in this demo'); } }, true);
  const flash = new FlashSession({
    images,
    imageUrl: 'https://refract.glassworks.dev/glass-panes/mirror-android-2/system.img',
    requestFastboot: async () => fastboot(name === 'flash-fastboot-wrong' ? 0x40000000 : SYSTEM_BYTES),
    requestAdb: async () => { const m = new FirstBootMirror(); m.hideFails = name === 'flash-done-chooser'; m.noHome = name === 'flash-done-no-home'; return m; },
    adbKey: () => 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks',
    apk: () => mirror.apkBytes(),
    sleep: (ms) => tick(ms / 20),
  });
  const image = Object.assign(new Blob([new Uint8Array(name === 'flash-wrong' ? 1234 : 16)]), { name: 'system.img' });
  if (name === 'flash-image' || name === 'flash-run') return { flash, mirror };
  if (name === 'flash-back') { flash.wayBack(true); return { flash, mirror }; }
  if (name === 'flash-downloading') { void flash.downloadImage(); return { flash, mirror }; }
  await flash.downloadImage(); // through the proxy; these three fall back to download-and-select
  if (name === 'flash-proxy-failed') return { flash, mirror };
  if (name === 'flash-checking') { void flash.selectImage(image); return { flash, mirror }; }
  await mirror.pickApk('kiosk-legacy-debug.apk', fakeApk('sh.edm.s1kiosk'));
  flash.setHaUrl('http://homeassistant.local:8123');
  flash.setWifi({ ssid: 'Home Net', password: 'not-a-real-password' });
  if (name === 'flash-wrong') { await flash.selectImage(image); return { flash, mirror }; }
  if (name === 'flash-ready') return { flash, mirror };
  flash.toFastboot();
  if (name === 'flash-fastboot') return { flash, mirror };
  await flash.connectFastboot();
  if (name === 'flash-fastboot-wrong' || name === 'flash-confirm') return { flash, mirror };
  flash.understand(true);
  const flashing = flash.flash();
  if (name === 'flash-working') return { flash, mirror };
  await flashing;
  if (name === 'flash-adb' || name === 'flash-error') return { flash, mirror };
  await flash.connectAdb();
  return { flash, mirror };
}
