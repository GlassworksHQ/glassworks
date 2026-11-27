// The Mirror image install's fastboot connection (1.0 pre-launch audit): one whose checks fail, or that fails partway
// through them, is let go of (FastbootLike `close`), so Connect again opens the device fresh instead of meeting a
// handle this page still holds; checks that pass keep it for the warning's writes. The in-place install's and the way
// back's: mirror-in-place.test.tsx.
import type { FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { describe, expect, it } from 'vitest';
import { imageDemo } from '../src/device/imageDemo';
import { mirrorEntry } from './mirror-frame';

const { sim } = mirrorEntry;
const tick = async () => {};

/** The image install on its fastboot screen, whose Connect gets `make()`'s fastboot, counting what Kiln lets go of. */
async function onFastboot(make: () => FastbootLike) {
  let closes = 0;
  const { image } = await imageDemo(mirrorEntry, 'flash-fastboot', true, {
    requestFastboot: async () => ({ ...make(), close: async () => { closes++; } }),
  });
  expect(image.state.screen).toBe('fastboot');
  return { image, closes: () => closes };
}

describe('the image install\'s fastboot connection', () => {
  it('checks that pass keep it for the writes', async () => {
    const r = await onFastboot(() => sim.fastboot(sim.systemBytes, { tick }));
    await r.image.connectFastboot();
    expect(r.image.state.screen).toBe('confirm');
    expect(r.closes()).toBe(0);
  });

  it('checks that fail let go of it, and Connect again opens it fresh', async () => {
    const r = await onFastboot(() => sim.fastboot(sim.wrongSystemBytes, { tick }));
    await r.image.connectFastboot();
    expect(r.image.state.screen).toBe('fastboot');
    expect(r.image.state.fastboot?.ok).toBe(false);
    expect(r.closes()).toBe(1);
    await r.image.connectFastboot();
    expect(r.closes()).toBe(2);
  });

  it('a device that stops answering fails its checks and is let go of, and a close that fails changes nothing', async () => {
    let closes = 0;
    const { image } = await imageDemo(mirrorEntry, 'flash-fastboot', true, {
      requestFastboot: async () => ({ ...sim.fastboot(sim.systemBytes, { tick }), partitionSize: async () => { throw new Error('transferIn: the device was disconnected'); },
        getvar: async () => { throw new Error('transferIn: the device was disconnected'); }, close: async () => { closes++; throw new Error('already gone'); } }),
    });
    await image.connectFastboot();
    expect(image.state.screen).toBe('fastboot');
    expect(image.state.connectError).toBeUndefined();
    expect(closes).toBe(1);
  });
});
