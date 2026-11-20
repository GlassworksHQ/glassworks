import type { PanelShell } from '@glassworks/adb';
import { describe, expect, it } from 'vitest';
import { FRAME_ID_PROP, LOADER_UPDATE_SERVICE_PROP, UPDATE_SERVICE_PROP, readRootUpdates } from '../src';

const shell = (run: PanelShell['run']): PanelShell => ({ run, push: async () => {} });
const prop = (value: string) => shell(async (cmd) => ({ out: cmd === `getprop ${UPDATE_SERVICE_PROP}` ? `${value}\n` : '\n', code: 0 }));

describe('silent updates on the S1, read back (#422)', () => {
  it('reads the prop the update service\'s rc sets at boot', () => {
    expect(UPDATE_SERVICE_PROP).toBe('sys.s1kiosk.update.version');
  });

  it('on when the boot image announces the service (version 1 or more), as the app reads it', async () => {
    expect(await readRootUpdates(prop('1'))).toEqual({ state: 'on' });
    expect(await readRootUpdates(prop('2'))).toEqual({ state: 'on' });
  });

  it('missing on a boot image from before the service: no prop, or not a version', async () => {
    expect(await readRootUpdates(prop(''))).toEqual({ state: 'missing' });
    expect(await readRootUpdates(prop('0'))).toEqual({ state: 'missing' });
    expect(await readRootUpdates(prop('yes'))).toEqual({ state: 'missing' });
  });

  it('on a frame loader image, the loader\'s names (#549): its frame id set, its own update prop', async () => {
    expect(LOADER_UPDATE_SERVICE_PROP).toBe('sys.glassworks.update.version');
    const props = (p: Record<string, string>) => shell(async (cmd) => ({ out: `${p[cmd.replace(/^getprop /, '')] ?? ''}\n`, code: 0 }));
    expect(await readRootUpdates(props({ [FRAME_ID_PROP]: 'aqara-s1-plus', [LOADER_UPDATE_SERVICE_PROP]: '1' }))).toEqual({ state: 'on' });
    expect(await readRootUpdates(props({ [FRAME_ID_PROP]: 'aqara-s1-plus', [UPDATE_SERVICE_PROP]: '1' })))
      .toEqual({ state: 'missing' }); // the 0.4.3 name means nothing on a loader image
    expect(await readRootUpdates(props({ [UPDATE_SERVICE_PROP]: '1', [LOADER_UPDATE_SERVICE_PROP]: '1' })))
      .toEqual({ state: 'on' }); // no frame id: 0.4.3's names
  });

  it('unknown when getprop fails or adb drops', async () => {
    expect(await readRootUpdates(shell(async () => ({ out: '', code: 1 })))).toEqual({ state: 'unknown', detail: 'getprop exited 1' });
    expect(await readRootUpdates(shell(async () => { throw new Error('adb: device offline'); }))).toEqual({ state: 'unknown', detail: 'adb: device offline' });
  });
});
