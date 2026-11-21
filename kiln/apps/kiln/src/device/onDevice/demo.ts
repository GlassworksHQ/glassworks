// Development and preview only (<route>/<onDevice route>?demo=…): a browser from the frame's detection fixtures (its
// UsbSim `browsers`), and a release line that is out, not out yet, or unreachable, so each screen can be seen without
// the device.
import type { BrowserFacts, OnDevice, UsbOnDevice } from '@glassworks/kiln-core';
import type { Line, Release, TabletScreen } from './model';

interface Demo { browser: string; screen?: TabletScreen; release?: 'out' | 'none' | 'error' | 'loading' }

export const ON_DEVICE_DEMOS = {
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

export type OnDeviceDemo = keyof typeof ON_DEVICE_DEMOS;

const out = (line: Line): Release => ({
  state: 'out', tag: `${line}-v1.0.0`, version: '1.0.0',
  assets: ['arm64-v8a', 'armeabi-v7a'].map((abi) => `glassworks-${line}-${abi}-1.0.0.apk`),
});

export function onDeviceDemo(od: Pick<UsbOnDevice, 'identify'>, browsers: Record<string, BrowserFacts>, name: string): { on: OnDevice; screen?: TabletScreen; load: (line: Line) => Promise<Release> } | undefined {
  if (!Object.hasOwn(ON_DEVICE_DEMOS, name)) return undefined;
  const d: Demo = ON_DEVICE_DEMOS[name as OnDeviceDemo];
  const facts = browsers[d.browser];
  if (!facts) return undefined;
  const release = d.release ?? 'out';
  return {
    on: od.identify(facts),
    ...(d.screen ? { screen: d.screen } : {}),
    load: (line) => (release === 'loading' ? new Promise<Release>(() => {})
      : Promise.resolve(release === 'out' ? out(line) : release === 'none' ? { state: 'none' } : { state: 'error', why: 'GitHub answered 502' })),
  };
}
