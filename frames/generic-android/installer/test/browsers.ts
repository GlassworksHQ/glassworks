// Browsers as the tablet page (/android/tablet) reads them: for the detection tests and Kiln's demos. PIXEL_DESKTOP
// is what Chrome 157 on a Pixel Tablet reported (#403, 2026-11-16). The others are built from each browser's
// documented user agent and client hints, not read on a device.
import type { BrowserFacts } from '../src/browser';

const CHROME_DESKTOP = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';
const CHROME_REDUCED = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';

export const BROWSERS = {
  /** Chrome's desktop site on a Pixel Tablet, on its dock (landscape) */
  PIXEL_DESKTOP: {
    userAgent: CHROME_DESKTOP, platform: 'Linux armv81', maxTouchPoints: 5,
    screen: { width: 1280, height: 800, dpr: 2 }, gpu: 'Mali-G710 MC7',
    hints: { platform: 'Linux', model: '', platformVersion: '', bitness: '' },
  },
  /** the same, held upright */
  PIXEL_PORTRAIT: {
    userAgent: CHROME_DESKTOP, platform: 'Linux armv81', maxTouchPoints: 5,
    screen: { width: 800, height: 1280, dpr: 2 }, gpu: 'Mali-G710 MC7',
    hints: { platform: 'Linux', model: '', platformVersion: '', bitness: '' },
  },
  /** a tablet Kiln can't name, in desktop mode */
  UNKNOWN_DESKTOP: {
    userAgent: CHROME_DESKTOP, platform: 'Linux armv81', maxTouchPoints: 10,
    screen: { width: 1340, height: 800, dpr: 1.5 }, gpu: 'Mali-G57 MC2',
    hints: { platform: 'Linux', model: '', platformVersion: '', bitness: '' },
  },
  /** desktop mode on Chrome 115, which still ran on Android 7: the version can't be told */
  OLD_CHROME_DESKTOP: {
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36',
    platform: 'Linux armv81', maxTouchPoints: 10, screen: { width: 1280, height: 800, dpr: 1.5 },
  },
  /** Chrome's mobile site on a Galaxy Tab A8: the reduced user agent, the real model and version in client hints */
  GALAXY_TAB: {
    userAgent: CHROME_REDUCED, platform: 'Linux armv81', maxTouchPoints: 10,
    screen: { width: 1280, height: 800, dpr: 1.5 },
    hints: { platform: 'Android', model: 'SM-X200', platformVersion: '14.0.0', bitness: '64' },
  },
  /** a Galaxy phone Kiln has no model entry for */
  GALAXY_PHONE: {
    userAgent: CHROME_REDUCED.replace('Safari', 'Mobile Safari'), platform: 'Linux armv81', maxTouchPoints: 5,
    screen: { width: 412, height: 915, dpr: 2.625 },
    hints: { platform: 'Android', model: 'SM-S921B', platformVersion: '15.0.0', bitness: '64' },
  },
  /** Android 7 on Chrome 103, before the user agent was reduced */
  NOUGAT_TAB: {
    userAgent: 'Mozilla/5.0 (Linux; Android 7.0; SM-T580) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.129 Safari/537.36',
    platform: 'Linux armv8l', maxTouchPoints: 10, screen: { width: 1280, height: 800, dpr: 1.5 },
  },
  /**
   * A Galaxy Tab A 10.1 (SM-T580) on Samsung's Android 8.1, a 64-bit chip with a 32-bit userspace (issue 700), in
   * Chrome's mobile site: the frozen platform and an empty bitness hint, and 32-bit Chrome's lower WebAssembly bound.
   */
  GALAXY_TAB_A_2016: {
    userAgent: CHROME_REDUCED, platform: 'Linux armv81', maxTouchPoints: 10,
    screen: { width: 1280, height: 800, dpr: 1.5 },
    hints: { platform: 'Android', model: 'SM-T580', platformVersion: '8.1.0', bitness: '' }, wasm4g: false,
  },
  /** the same tablet in Samsung Internet, whose platform still says the 32-bit machine it runs as */
  GALAXY_TAB_A_2016_SAMSUNG: {
    userAgent: 'Mozilla/5.0 (Linux; Android 8.1.0; SAMSUNG SM-T580) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/16.0 Chrome/92.0.4515.166 Safari/537.36',
    platform: 'Linux armv8l', maxTouchPoints: 10, screen: { width: 1280, height: 800, dpr: 1.5 },
  },
  /** a Fire HD 10 (2021) in Silk, which names the model */
  FIRE_SILK: {
    userAgent: 'Mozilla/5.0 (Linux; Android 11; KFTRWI) AppleWebKit/537.36 (KHTML, like Gecko) Silk/120.4.1 like Chrome/120.0.6099.230 Safari/537.36',
    platform: 'Linux armv8l', maxTouchPoints: 10, screen: { width: 1280, height: 800, dpr: 1.5 },
  },
  /** Firefox keeps the real version and says Tablet where Chrome puts the model */
  FIREFOX_TAB: {
    userAgent: 'Mozilla/5.0 (Android 14; Tablet; rv:140.0) Gecko/140.0 Firefox/140.0',
    platform: 'Linux armv81', maxTouchPoints: 5, screen: { width: 1280, height: 800, dpr: 2 },
  },
  /** Android 5.1, below Glassworks' floor */
  LOLLIPOP: {
    userAgent: 'Mozilla/5.0 (Linux; Android 5.1.1; SM-T280) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.74 Safari/537.36',
    platform: 'Linux armv7l', maxTouchPoints: 5, screen: { width: 1280, height: 800, dpr: 1 },
  },
  /** a computer */
  MAC: {
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36',
    platform: 'MacIntel', maxTouchPoints: 0, screen: { width: 1512, height: 982, dpr: 2 },
    hints: { platform: 'macOS', model: '', platformVersion: '15.5.0', bitness: '64' },
  },
  /** a Raspberry Pi with a touch screen: ARM Linux, but not Android */
  PI_TOUCH: {
    userAgent: 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    platform: 'Linux aarch64', maxTouchPoints: 10, screen: { width: 800, height: 480, dpr: 1 },
  },
} satisfies Record<string, BrowserFacts>;

export type BrowserName = keyof typeof BROWSERS;
