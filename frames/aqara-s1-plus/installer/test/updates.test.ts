import type { PanelShell } from '@glassworks/adb';
import { describe, expect, it } from 'vitest';
import { UPDATE_SERVICE_PROP, readRootUpdates } from '../src';

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

  it('unknown when getprop fails or adb drops', async () => {
    expect(await readRootUpdates(shell(async () => ({ out: '', code: 1 })))).toEqual({ state: 'unknown', detail: 'getprop exited 1' });
    expect(await readRootUpdates(shell(async () => { throw new Error('adb: device offline'); }))).toEqual({ state: 'unknown', detail: 'adb: device offline' });
  });
});
