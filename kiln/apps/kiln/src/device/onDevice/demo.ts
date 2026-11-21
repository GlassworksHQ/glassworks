// Development and preview only (<route>/<onDevice route>?demo=…): a browser from the frame's detection fixtures (its
// UsbSim `browsers`), and a release line that is out, not out yet, or unreachable, so each screen can be seen without
// the device.
import type { BrowserFacts, OnDevice, OnDeviceRole, UsbOnDevice } from '@glassworks/kiln-core';
import type { Line, Release, TabletScreen } from './model';

/** `browser`: the role of the browser the demo shows, which the frame's sim maps to one of its fixtures (UsbSim `browsers`). */
interface Demo { browser: OnDeviceRole; screen?: TabletScreen; release?: 'out' | 'none' | 'error' | 'loading' }

export const ON_DEVICE_DEMOS = {
  pixel: { browser: 'known' },
  galaxy: { browser: 'branded' },
  phone: { browser: 'phone' },
  unknown: { browser: 'unknown' },
  nougat: { browser: 'legacy' },
  'old-chrome': { browser: 'either' },
  lollipop: { browser: 'too-old' },
  computer: { browser: 'computer' },
  get: { browser: 'known', screen: 'get' },
  'get-unsure': { browser: 'unknown', screen: 'get' },
  'get-legacy': { browser: 'legacy', screen: 'get' },
  'get-either': { browser: 'either', screen: 'get' },
  'get-none': { browser: 'known', screen: 'get', release: 'none' },
  'get-error': { browser: 'known', screen: 'get', release: 'error' },
  'get-loading': { browser: 'known', screen: 'get', release: 'loading' },
  setup: { browser: 'known', screen: 'setup' },
  kiln: { browser: 'known', screen: 'kiln' },
  'kiln-galaxy': { browser: 'branded', screen: 'kiln' },
  'kiln-unknown': { browser: 'unknown', screen: 'kiln' },
} satisfies Record<string, Demo>;

export type OnDeviceDemo = keyof typeof ON_DEVICE_DEMOS;

const out = (line: Line): Release => ({
  state: 'out', tag: `${line}-v1.0.0`, version: '1.0.0',
  assets: ['arm64-v8a', 'armeabi-v7a'].map((abi) => `glassworks-${line}-${abi}-1.0.0.apk`),
});

export function onDeviceDemo(od: Pick<UsbOnDevice, 'identify'>, browsers: Partial<Record<OnDeviceRole, BrowserFacts>>, name: string): { on: OnDevice; screen?: TabletScreen; load: (line: Line) => Promise<Release> } | undefined {
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
