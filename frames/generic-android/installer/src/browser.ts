// What a device is from its own browser (#403, part 3): Kiln's tablet page (/android/tablet) opened on the
// device it installs on. No USB, no adb: only what the page can read.
//
// Chrome hides most of it. Since Chrome 110 its user agent on Android says "Android 10; K" whatever the
// version and model, and navigator.platform is the constant "Linux armv81" (chromium.org/updates/ua-reduction,
// read 2026-11-18). On large tablets Chrome shows desktop sites by default, and then the user agent says
// "X11; Linux x86_64" and client hints say platform "Linux" with no model or version: read on a Pixel Tablet,
// Chrome 157, 2026-11-16 (#403). The frozen "Linux armv81" stays in desktop mode, so it still says Android.
// In mobile mode, client hints give the real model and version. Firefox keeps the real version in its user
// agent. A model the page can't read may still match a GPU and screen fingerprint.
//
// 32 or 64-bit (issue 700), so the person never has to know: whatever the browser itself runs as, the device runs too,
// so a 32-bit browser means the armeabi-v7a build installs and a 64-bit one the arm64-v8a build. A browser that isn't
// frozen says it in navigator.platform: "Linux armv7l", or "Linux armv8l" for 32-bit code on a 64-bit chip (Android's
// own 32-bit userspace on a Galaxy Tab A 10.1), and "Linux aarch64" for 64-bit code. Chrome freezes it to "Linux armv81"
// and leaves the bitness client hint empty on Android (read on a Pixel Tablet, 2026-11-16), so there the page asks
// WebAssembly: V8 bounds a memory's maximum at 65536 pages (4 GiB) in a 64-bit build and lower in a 32-bit one, and
// refuses a maximum above it with a RangeError (wasm4g; read from V8's kV8MaxWasmMemory32Pages, and the 64-bit bound
// checked in Node 2026-11-20; not yet read on a 32-bit device). Only V8's yes counts: another engine may clamp the
// maximum instead of refusing it.
import { BRANDS, GENERIC_STEPS, MODELS, type Brand, type BrandId, type Identified, type Model } from './devices';

/** What the page reads: navigator, screen, WebGL and the high-entropy client hints, where the browser has them. */
export interface BrowserFacts {
  userAgent: string;
  /** navigator.platform */
  platform: string;
  maxTouchPoints: number;
  /** CSS pixels, as screen.width and screen.height say them (they swap with rotation) */
  screen: { width: number; height: number; dpr: number };
  /** WebGL's renderer, unmasked where the browser allows */
  gpu?: string;
  /** navigator.userAgentData.getHighEntropyValues: empty strings where the browser won't say */
  hints?: { platform?: string; model?: string; platformVersion?: string; bitness?: string };
  /**
   * Whether a WebAssembly memory may reach 4 GiB (65536 pages): true in a 64-bit browser; false where the engine's bound
   * is lower, as in a 32-bit one; undefined where the page couldn't try
   */
  wasm4g?: boolean;
}

/** Which build of Glassworks fits: from the Android version, or the browser's own minimum when the version is hidden. */
export type AppFit = 'generic' | 'legacy' | 'either' | 'too-old';

export interface OnDevice {
  android: boolean;
  /** a phone's short side is under 600 CSS pixels (Android's own tablet line, sw600dp) */
  form: 'tablet' | 'phone';
  /** Chrome's desktop site: the user agent says Linux, not Android */
  desktopSite: boolean;
  /** the Android version as read ("13", "7.1.2"); undefined when the browser hides it */
  release?: string;
  app: AppFit;
  /** the model code as read ("SM-X200"), before any lookup */
  modelCode?: string;
  /** 64 or 32: what the browser runs as (navigator.platform), else client hints; undefined when the browser won't say */
  bits?: 64 | 32;
  identified: Identified;
}

/** Chrome's frozen navigator.platform on Android, desktop mode included. */
export const FROZEN_ANDROID_PLATFORM = 'Linux armv81';

/** Chrome 120 was the first that needs Android 8 (Chrome Enterprise release notes; Chrome 119 was Nougat's last). */
export const CHROME_NEEDS_OREO = 120;

/**
 * Models Kiln can tell by GPU and screen when the browser won't name them. A fingerprint, not proof, so only
 * pairs read on a real device go here. Screen: the long and short side in CSS pixels, and the pixel ratio.
 */
export const FINGERPRINTS: { gpu: RegExp; screen: [number, number, number]; model: string; read: string }[] = [
  { gpu: /Mali-G710/, screen: [1280, 800, 2], model: 'Pixel Tablet', read: 'Chrome 157 on a Pixel Tablet, desktop mode, 2026-11-16 (#403)' },
];

/** Model codes whose prefix names the brand, for models not in MODELS. */
const PREFIXES: [RegExp, BrandId][] = [
  [/^SM-/, 'samsung'],
  [/^KF[A-Z]{2,}/, 'amazon'],
  [/^Pixel\b/, 'google'],
  [/^(Lenovo|TB-)/, 'lenovo'],
  [/^(Redmi|Xiaomi|POCO|Mi )/i, 'xiaomi'],
  [/^moto/i, 'motorola'],
];

/** The reduced user agent's placeholders: neither is the device's. */
const REDUCED = /Android 10; K[;)]/;
/** Firefox's form factor tokens sit where Chrome puts the model. */
const NOT_A_MODEL = /^(K|Mobile|Tablet|wv|U|Linux)$/;

const brandById = (id: BrandId): Brand => BRANDS.find((b) => b.id === id)!;

/** "14.0.0" and "7.0" as Android names them: "14", "7". */
function versionOf(f: BrowserFacts): string | undefined {
  const v = f.hints?.platformVersion?.trim() || (REDUCED.test(f.userAgent) ? undefined : /Android (\d+(?:\.\d+)*)/.exec(f.userAgent)?.[1]);
  return v?.replace(/(\.0)+$/, '');
}

function modelCodeOf(f: BrowserFacts): string | undefined {
  const hinted = f.hints?.model?.trim();
  if (hinted) return hinted;
  if (REDUCED.test(f.userAgent)) return undefined;
  const m = /Android [\d.]+;(?: [a-z]{2}(?:-[a-z]{2})?;)? ([^;)]+?)(?: Build\/[^;)]*)?[;)]/i.exec(f.userAgent)?.[1]?.trim();
  return m && !NOT_A_MODEL.test(m) ? m : undefined;
}

function fit(release: string | undefined, ua: string): AppFit {
  const major = release ? Number.parseInt(release, 10) : Number.NaN;
  if (major >= 8) return 'generic';
  if (major >= 6) return 'legacy';
  if (major > 0) return 'too-old';
  const chrome = Number(/Chrome\/(\d+)/.exec(ua)?.[1] ?? 0);
  return chrome >= CHROME_NEEDS_OREO ? 'generic' : 'either';
}

function fingerprint(f: BrowserFacts): Model | undefined {
  const long = Math.max(f.screen.width, f.screen.height);
  const short = Math.min(f.screen.width, f.screen.height);
  const hit = FINGERPRINTS.find((p) => f.gpu && p.gpu.test(f.gpu) && p.screen[0] === long && p.screen[1] === short && p.screen[2] === f.screen.dpr);
  return hit && MODELS.find((m) => m.name === hit.model);
}

/** Chromium's engine, V8: Chrome, Samsung Internet, Edge, Silk. Not Firefox. */
const V8 = (ua: string) => /\bChrome\//.test(ua) && !/\bFirefox\//.test(ua);

/** 32 or 64-bit: the browser's own machine name where it isn't frozen, else WebAssembly's bound, else the bitness client hint. */
function bitsOf(f: BrowserFacts): 64 | 32 | undefined {
  if (/^Linux armv[78]l$/.test(f.platform)) return 32;
  if (f.platform === 'Linux aarch64') return 64;
  if (f.wasm4g === false) return 32;
  if (f.wasm4g === true && V8(f.userAgent)) return 64;
  const b = f.hints?.bitness;
  return b === '64' ? 64 : b === '32' ? 32 : undefined;
}

/** Read what the page can about the device it runs on. */
export function identifyBrowser(f: BrowserFacts): OnDevice {
  const ua = f.userAgent;
  const uaAndroid = /Android/.test(ua);
  const android = uaAndroid || f.hints?.platform === 'Android' || f.platform === FROZEN_ANDROID_PLATFORM;
  const release = android ? versionOf(f) : undefined;
  const modelCode = android ? modelCodeOf(f) : undefined;
  const model = (modelCode ? MODELS.find((m) => m.code.test(modelCode)) : undefined) ?? (android ? fingerprint(f) : undefined);
  const byBrowser: BrandId | undefined = /\bSilk\//.test(ua) ? 'amazon' : /SamsungBrowser\//.test(ua) ? 'samsung' : undefined;
  const brandId = model?.brand ?? (modelCode ? PREFIXES.find(([re]) => re.test(modelCode))?.[1] : undefined) ?? byBrowser;
  const brand = brandId ? brandById(brandId) : undefined;
  const desktopSite = android && !uaAndroid;
  const why = brand ? undefined : desktopSite ? 'In desktop mode the browser doesn\'t say the model.' : 'The browser doesn\'t say the model.';
  const bits = android ? bitsOf(f) : undefined;
  return {
    android,
    form: Math.min(f.screen.width, f.screen.height) >= 600 ? 'tablet' : 'phone',
    desktopSite,
    ...(release ? { release } : {}),
    app: android ? fit(release, ua) : 'either',
    ...(modelCode ? { modelCode } : {}),
    ...(bits ? { bits } : {}),
    identified: {
      ...(brand ? { brand } : {}),
      ...(model ? { model } : {}),
      steps: brand?.steps ?? GENERIC_STEPS,
      tested: !!model?.frame,
      pick: !brand,
      ...(why ? { why } : {}),
    },
  };
}
