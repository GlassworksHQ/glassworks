// Kiln's ?demo=… and tests only; the real install is recipe.ts (and kiln.ts for Kiln's wizard). A simulated S1: the
// recipe with FEL, the layout check and the patch faked, and setUp stepping through provision's phases with no panel.
// Kiln's demo path takes it beside the frame (kiln/apps/kiln/src/wizard/demo.ts); the frame Kiln installs never has it.
import type { Recipe } from '@glassworks/kiln-core';
import type { AppPhase, Provisioned } from './provision';
import { s1Plus, type AssetName, type SetUpOptions } from './recipe';
import { UPDATE_SERVICE_PROP } from './updates';

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** The S1 recipe's shape with the hardware steps simulated. */
const simRecipe: Recipe<AssetName, SetUpOptions, Provisioned> = {
  ...s1Plus,
  fel: {
    checkChip: async () => { await wait(500); return 'SoC 0x1855 (A133), secure boot off (simulated)'; },
    loader: [],
    boot: async (_fel, _loader, on) => { for (let i = 1; i <= 10; i++) { await wait(120); on(i / 10); } },
  },
  checkLayout: async () => 'partition layout OK (simulated S1 Plus)',
  patchBoot: async ({ backup, log }) => {
    await wait(900);
    log('- simulated patch');
    const image = backup.get('boot')!.slice();
    image[image.length - 1]! ^= 1; // kiln-core refuses a stock boot handed back unchanged
    return image;
  },
  writesBeforeBoot: async ({ backup, log }) => { log('vbmeta flags: 0'); return [{ part: 'vbmeta', data: backup.get('vbmeta')!, what: 'Turning off verified boot' }]; },
  setUp: async (_panel, opts, { step }) => {
    // provision's phases, in its order, without the panel's real durations
    const phases: [AppPhase, string, number][] = [
      [{ step: 'apk', apk: 'kiosk', stage: 'copy' }, 'Install kiosk app', 600], [{ step: 'apk', apk: 'kiosk', stage: 'install' }, '', 2500],
      [{ step: 'home' }, 'Permissions', 600],
      ...(opts.keyboard !== false ? [[{ step: 'apk', apk: 'keyboard', stage: 'copy' }, 'Keyboard', 200], [{ step: 'apk', apk: 'keyboard', stage: 'install' }, '', 600]] as [AppPhase, string, number][] : []),
      [{ step: 'prefs' }, '', 300], [{ step: 'start' }, 'Start the kiosk', 400],
    ];
    for (const [phase, s, ms] of phases) {
      opts.onPhase?.(phase);
      if (s) step(s);
      await wait(ms);
    }
    return { ip: '192.0.2.50' };
  },
};

export const kilnSim = {
  recipe: simRecipe,
  /** getprop answers on a simulated S1 whose boot image is current: the root update service is in place */
  props: { [UPDATE_SERVICE_PROP]: '1' },
  installLog: ['SoC 0x1855 (A133), secure boot off', 'U-Boot started from RAM; waiting for its fastboot', 'partition layout OK (S1 Plus)', 'boot read twice, hashes match',
    'backup s1-backup-20261112-101500 stored in this browser', 'patched boot 4c1e…a0b7 (from 3a7f…91c2)', 'vbmeta flags: 3'],
  backupInfo: '9 partitions · 52 MiB',
};
