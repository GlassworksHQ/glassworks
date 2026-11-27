// @vitest-environment jsdom
// A device the browser listed but couldn't open (Chrome's SecurityError "Access denied": a missing udev rule on Linux,
// the wrong driver on Windows), or whose interface another program holds (NetworkError "Unable to claim interface":
// often adb): calm words, not "the panel disconnected" or the browser's message, and the frame's one-time USB setup
// for this computer on its fastboot screens (installer.json `device.drivers`).
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { explain, usbRefusal, usbRefusalText } from '../src/wizard/explain';
import { ImageSession } from '../src/device/imageSession';
import { ImageFlow } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { imageDemo } from '../src/device/imageDemo';
import { mirrorEntry, mirrorFrame } from './mirror-frame';

const DENIED = () => new DOMException('Failed to execute \'open\' on \'USBDevice\': Access denied.', 'SecurityError');
const CLAIMED = () => new DOMException('Failed to execute \'claimInterface\' on \'USBDevice\': Unable to claim interface.', 'NetworkError');
const UA = navigator.userAgent;
const as = (ua: string) => Object.defineProperty(navigator, 'userAgent', { configurable: true, value: ua });

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => { cleanup(); as(UA); });

describe('a refused USB device, in words', () => {
  it('tells access denied and a claimed interface apart from a disconnect', () => {
    expect(usbRefusal(DENIED())).toBe('denied');
    expect(usbRefusal(CLAIMED())).toBe('claimed');
    expect(usbRefusal(new DOMException('The device was disconnected.', 'NetworkError'))).toBeUndefined();
    expect(explain(DENIED(), 1, 'none').title).toBe('This computer didn\'t let Kiln open the panel');
    expect(explain(CLAIMED(), 1, 'none').title).toBe('Another program is using the panel');
    // a NetworkError that isn't a claim is still a disconnect
    expect(explain(new DOMException('The device was disconnected.', 'NetworkError'), 1, 'none').title).toBe('The panel disconnected');
  });
});

/** The Mirror's install on the stock software at its fastboot screen (nothing to download), on a session whose fastboot request fails with `e`. */
function fastbootFails(e: unknown) {
  const image = new ImageSession({ frame: mirrorFrame, images: mirrorFrame.imageFlow.images(), requestFastboot: () => Promise.reject(e),
    requestAdb: () => Promise.reject(new Error('off')), adbKey: () => undefined, apk: () => new Uint8Array(1) });
  image.pick('glassworks-in-place');
  image.choose();
  image.toFastboot();
  expect(image.state.screen).toBe('fastboot');
  return image;
}
const app = () => new AppSession({ frame: mirrorFrame, connect: async () => { throw new Error('off'); } }, true);

describe('the Mirror\'s fastboot screen', () => {
  it('shows the one-time USB setup for Windows and Linux, with its guide, and none on a Mac', () => {
    const guide = mirrorFrame.drivers!.guide!;
    for (const [ua, line] of [['Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0', mirrorFrame.drivers!.win], ['Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0', mirrorFrame.drivers!.linux]] as const) {
      as(ua);
      render(<ImageFlow image={fastbootFails(DENIED())} app={app()} />);
      expect(screen.getByText(line)).toBeTruthy();
      expect(screen.getByRole('link', { name: 'Setup guide' }).getAttribute('href')).toBe(guide);
      cleanup();
    }
    as('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/131.0');
    render(<ImageFlow image={fastbootFails(DENIED())} app={app()} />);
    expect(screen.queryByRole('link', { name: 'Setup guide' })).toBeNull();
  });

  it.each([['denied', DENIED], ['claimed', CLAIMED]] as const)('a connection %s says what to do, with the browser\'s words in the log', async (r, e) => {
    const image = fastbootFails(e());
    await image.connectFastboot();
    expect(image.state.connectError).toBe(usbRefusalText(r, mirrorFrame.the, true));
    expect(image.state.log.join('\n')).toContain(e().message);
    expect(image.state.screen).toBe('fastboot');
  });

  it('?demo=flash-fastboot-denied shows it, for the screenshots', async () => {
    const { image } = await imageDemo(mirrorEntry, 'flash-fastboot-denied', true);
    expect(image.state).toMatchObject({ screen: 'fastboot', connectError: usbRefusalText('denied', mirrorFrame.the, true) });
  });
});
