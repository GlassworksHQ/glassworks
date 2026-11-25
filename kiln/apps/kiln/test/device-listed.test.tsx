// @vitest-environment jsdom
// The name the browser's USB list shows a device by (installer.json `device.listed`, kiln-core `listed`): the Mirror is
// listed as "Android", so every screen that asks for a pick says so, and set-up's sheet names it. A frame that doesn't
// declare one keeps the plain wording.
import { cleanup, render, screen } from '@testing-library/react';
import { pickInList, type DeviceEntry } from '@glassworks/kiln-core';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import installer from '../../../../frames/lululemon-mirror/design/installer.json';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { ImageFlow } from '../src/device/DevicePage';
import { AppSession } from '../src/device/appSession';
import { imageDemo } from '../src/device/imageDemo';
import { standardOfDevice } from '../src/standard/frame';
import { mirrorEntry, mirrorFrame } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

describe('the name in the browser\'s list', () => {
  it('says the listed name only when the frame declares one', () => {
    expect(pickInList({ the: 'the hub', listed: 'Android' })).toBe('pick the hub, listed as “Android”, in the browser\'s list');
    expect(pickInList({ the: 'the hub' })).toBe('pick the hub in the browser\'s list');
  });

  it('comes from the Mirror\'s data, for its screens and for set-up\'s sheet', () => {
    expect(installer.device.listed.as).toBe('Android');
    expect(mirrorFrame.listed).toBe('Android');
    expect(standardOfDevice(mirrorFrame).connect.pick).toBe('Android');
  });

  const fastbootScreen = async (entry: DeviceEntry) => {
    const { image } = await imageDemo(entry, 'flash-fastboot', true);
    render(<ImageFlow image={image} app={new AppSession({ frame: entry.frame, connect: async () => { throw new Error('x'); } }, true)} />);
  };

  it('the fastboot screen asks for the Mirror by it', async () => {
    await fastbootScreen(mirrorEntry);
    // the heads-up before the browser's list: the line to pick, as the list shows it
    expect(document.querySelector('.ask')!.textContent).toContain('Pick Android, which is the Mirror, then click Connect.');
    expect(document.querySelector('.ask-row')!.textContent).toContain('Android');
  });

  it('a frame that declares none keeps the plain words', async () => {
    await fastbootScreen({ ...mirrorEntry, frame: { ...mirrorFrame, listed: undefined } });
    expect(document.querySelector('.ask')!.textContent).toContain('Pick the Mirror, then click Connect.');
    expect(document.querySelector('.ask')!.textContent).not.toMatch(/Android/);
  });
});
