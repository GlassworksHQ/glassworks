// Development and preview only (/lululemon-mirror?demo=flash-… and ?demo=image-…): the fastboot
// install against a simulated Mirror, so each screen can be seen and captured without one. The
// images aren't built or downloaded (a stand-in reports progress); the fastboot and first-boot
// answers are the recipe's fakes. `flash-…`: Glassworks; `image-…`: the choice and Glass Panes (issue 579).
import { ProxyError, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { FirstBootMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { FlashSession, type ImageMaker } from './flash';
import { fakeReleaseRoute, type FakeRelease } from '../release/demo';
import { MirrorSession } from './session';

export const FLASH_DEMOS = ['flash-image', 'flash-release', 'flash-release-downloading', 'flash-release-none', 'flash-release-bad', 'flash-downloading', 'flash-download-error', 'flash-download-bad', 'flash-ready', 'flash-wifi-short', 'flash-fastboot', 'flash-fastboot-wrong', 'flash-confirm',
  'flash-working', 'flash-adb', 'flash-done', 'flash-done-chooser', 'flash-done-no-home', 'flash-error', 'flash-run',
  'image-choose', 'image-choose-open', 'image-choose-panes', 'image-link', 'image-download', 'image-download-done', 'image-download-error', 'image-download-bad',
  'image-fastboot', 'image-confirm', 'image-writing', 'image-done', 'image-error', 'image-back-glassworks', 'image-back-panes'] as const;
export type FlashDemo = (typeof FLASH_DEMOS)[number];

const REFRACT_DOWN = 'the download proxy answered 502: GitHub answered 503';

export async function flashDemo(name: FlashDemo, fast = false): Promise<{ flash: FlashSession; mirror: MirrorSession }> {
  const tick = (ms: number) => new Promise<void>((r) => setTimeout(r, fast ? 0 : ms));
  let failures = 1; // a failed download fails once: Try again works
  const images: ImageMaker = {
    async fetch(_, onProgress) {
      if (name === 'flash-downloading') { onProgress(0.42); return new Promise(() => {}); }
      if (name === 'flash-download-error' && failures-- > 0) { onProgress(0.31); await tick(200); throw new ProxyError(REFRACT_DOWN); }
      if (name === 'flash-download-bad' && failures-- > 0) { for (let i = 1; i <= 10; i++) { await tick(60); onProgress(i / 10); } throw new WrongImage('its SHA-256 differs from the release'); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
    },
    async download(_, want, onProgress) {
      if (name === 'image-download') { onProgress(0.42); return new Promise(() => {}); }
      if (name === 'image-download-error' && want.name === 'system.img' && failures-- > 0) { onProgress(0.31); await tick(200); throw new ProxyError(REFRACT_DOWN); }
      for (let i = 1; i <= 10; i++) { await tick(120); onProgress(i / 10); }
      if (name === 'image-download-bad' && want.name === 'userdata.sparse' && failures-- > 0) throw new WrongImage('its SHA-256 differs from the release');
    },
    async file() { return new Blob([new Uint8Array(16)]); },
    async check() {},
    async build(_, onProgress) {
      for (const step of ['patch', 'system', 'userdata'] as const) {
        for (let i = 1; i <= 5; i++) {
          if (name === 'flash-working' && step === 'system' && i === 3) { await tick(300); onProgress(step, 0.4); return new Promise(() => {}); } // stays here, painted twice
          await tick(150); onProgress(step, i / 5);
        }
      }
      return { system: new Blob([new Uint8Array(16)]), userdata: new Blob([new Uint8Array(16)]) };
    },
    async discard() {},
  };
  const fastboot = (system: number): FastbootLike => ({
    async getvar(n) { if (n === 'product') return 'MSM8916'; if (n === 'max-download-size') return '0x10000000'; throw new Error('FAIL'); },
    async partitionSize(n) { return n === 'system' ? system : USERDATA_BYTES; },
    async flashBlob(_, __, onProgress = () => {}) {
      if (name === 'flash-error' || name === 'image-error') throw new Error('flash:system: FAILed (remote: write failed)');
      if (name === 'image-writing') { onProgress(0.63); return new Promise(() => {}); }
      for (let i = 1; i <= 8; i++) { await tick(150); onProgress(i / 8); }
    },
    async reboot() {},
  });
  // flash-release…: the APK from a stand-in release (?apk=release), as the image screen shows it
  const release = name.startsWith('flash-release')
    ? fakeReleaseRoute(({ 'flash-release': 'out', 'flash-release-downloading': 'stuck', 'flash-release-none': 'none', 'flash-release-bad': 'bad' } as Record<string, FakeRelease>)[name]!, fakeMirrorApk(), tick)
    : undefined;
  const mirror = new MirrorSession({ connect: async () => { throw new Error('not in this demo'); }, ...(release ? { release } : {}) }, true);
  const flash = new FlashSession({
    images,
    refract: 'https://refract.glassworks.dev',
    search: name === 'image-link' ? '?image=glass-panes' : '',
    requestFastboot: async () => fastboot(name === 'flash-fastboot-wrong' ? 0x40000000 : SYSTEM_BYTES),
    requestAdb: async () => { const m = new FirstBootMirror(); m.hideFails = name === 'flash-done-chooser'; m.noHome = name === 'flash-done-no-home'; return m; },
    adbKey: () => 'QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= kiln@glassworks',
    apk: () => mirror.apkBytes(),
    apkBlocked: () => mirror.releaseBlocked(),
    sleep: (ms) => tick(ms / 20),
  });
  const done = { flash, mirror };
  if (name === 'image-choose' || name === 'image-link') return done;
  if (name === 'image-choose-open') { flash.showCommunity(true); return done; }
  if (name === 'image-choose-panes') { flash.showCommunity(true); flash.pick('glass-panes'); return done; }
  if (name.startsWith('image-')) return glassPanes(name, done);

  flash.choose(); // Glassworks, picked
  if (name === 'flash-image' || name === 'flash-run' || release) return done;
  if (name === 'flash-downloading') { void flash.download(); return done; }
  await flash.download(); // through Refract
  if (name === 'flash-download-error' || name === 'flash-download-bad') return done;
  await mirror.pickApk('generic-android-legacy.apk', fakeMirrorApk());
  flash.setHaUrl('http://homeassistant.local:8123');
  flash.setWifi({ ssid: 'Home Net', password: 'not-a-real-password' });
  if (name === 'flash-ready') return done;
  // a password WPA can't take: refused before fastboot, since the image saves it as the network's PSK
  if (name === 'flash-wifi-short') { flash.setWifi({ password: 'short' }); return done; }
  flash.toFastboot();
  if (name === 'flash-fastboot') return done;
  await flash.connectFastboot();
  if (name === 'flash-fastboot-wrong' || name === 'flash-confirm') return done;
  flash.understand(true);
  const flashing = flash.flash();
  if (name === 'flash-working') return done;
  await flashing;
  if (name === 'flash-adb' || name === 'flash-error') return done;
  await flash.connectAdb();
  return done;
}

/** Glass Panes, picked on the choice: its download, fastboot, warning, writes and Done, and both ways back. */
async function glassPanes(name: FlashDemo, d: { flash: FlashSession; mirror: MirrorSession }) {
  const { flash } = d;
  if (name === 'image-back-panes') { // Glassworks' Done, then its way back
    flash.choose();
    await flash.download();
    await d.mirror.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    flash.toFastboot(); await flash.connectFastboot(); flash.understand(true); await flash.flash(); await flash.connectAdb();
    flash.wayBack('glass-panes');
    return d;
  }
  flash.showCommunity(true);
  flash.pick('glass-panes');
  flash.choose();
  if (name === 'image-download') { void flash.download(); return d; }
  await flash.download();
  if (['image-download-done', 'image-download-error', 'image-download-bad'].includes(name)) return d;
  flash.toFastboot();
  if (name === 'image-fastboot') return d;
  await flash.connectFastboot();
  if (name === 'image-confirm') return d;
  flash.understand(true);
  const writing = flash.flash();
  if (name === 'image-writing') { await new Promise((r) => setTimeout(r, 0)); return d; }
  await writing;
  if (name === 'image-back-glassworks') flash.wayBack('glassworks');
  return d;
}
