// Development and preview only (/android/tablet?demo=…): a browser from the detection tests' fixtures, and a
// release line that is out, not out yet, or unreachable, so each screen can be seen without a tablet.
import { identifyBrowser, type OnDevice } from '@glassworks/generic-android-installer';
import { BROWSERS, type BrowserName } from '@glassworks/generic-android-installer/browsers';
import type { Line, Release, TabletScreen } from './model';

interface Demo { browser: BrowserName; screen?: TabletScreen; release?: 'out' | 'none' | 'error' | 'loading' }

export const TABLET_DEMOS = {
  pixel: { browser: 'PIXEL_DESKTOP' },
  galaxy: { browser: 'GALAXY_TAB' },
  phone: { browser: 'GALAXY_PHONE' },
  unknown: { browser: 'UNKNOWN_DESKTOP' },
  nougat: { browser: 'NOUGAT_TAB' },
  'old-chrome': { browser: 'OLD_CHROME_DESKTOP' },
  lollipop: { browser: 'LOLLIPOP' },
  computer: { browser: 'MAC' },
  get: { browser: 'PIXEL_DESKTOP', screen: 'get' },
  'get-unsure': { browser: 'UNKNOWN_DESKTOP', screen: 'get' },
  'get-legacy': { browser: 'NOUGAT_TAB', screen: 'get' },
  'get-either': { browser: 'OLD_CHROME_DESKTOP', screen: 'get' },
  'get-none': { browser: 'PIXEL_DESKTOP', screen: 'get', release: 'none' },
  'get-error': { browser: 'PIXEL_DESKTOP', screen: 'get', release: 'error' },
  'get-loading': { browser: 'PIXEL_DESKTOP', screen: 'get', release: 'loading' },
  setup: { browser: 'PIXEL_DESKTOP', screen: 'setup' },
  kiln: { browser: 'PIXEL_DESKTOP', screen: 'kiln' },
  'kiln-galaxy': { browser: 'GALAXY_TAB', screen: 'kiln' },
  'kiln-unknown': { browser: 'UNKNOWN_DESKTOP', screen: 'kiln' },
} satisfies Record<string, Demo>;

export type TabletDemo = keyof typeof TABLET_DEMOS;

const out = (line: Line): Release => ({
  state: 'out', tag: `${line}-v1.0.0`, version: '1.0.0',
  assets: ['arm64-v8a', 'armeabi-v7a'].map((abi) => `glassworks-${line}-${abi}-1.0.0.apk`),
});

export function tabletDemo(name: string): { on: OnDevice; screen?: TabletScreen; load: (line: Line) => Promise<Release> } | undefined {
  if (!Object.hasOwn(TABLET_DEMOS, name)) return undefined;
  const d: Demo = TABLET_DEMOS[name as TabletDemo];
  const release = d.release ?? 'out';
  return {
    on: identifyBrowser(BROWSERS[d.browser]),
    ...(d.screen ? { screen: d.screen } : {}),
    load: (line) => (release === 'loading' ? new Promise<Release>(() => {})
      : Promise.resolve(release === 'out' ? out(line) : release === 'none' ? { state: 'none' } : { state: 'error', why: 'GitHub answered 502' })),
  };
}
