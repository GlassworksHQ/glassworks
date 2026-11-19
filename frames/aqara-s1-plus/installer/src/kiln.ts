// The steps of the S1's install that only code can say, for Kiln's shared wizard (#621). Its words are in
// ../../design/installer.json `words`; Kiln's route for the S1 puts the two together (kiln/apps/kiln/src/wizard/frame.ts).
import type { PanelShell } from '@glassworks/adb';
import { S1_KIOSK } from '@glassworks/app-adb';
import type { Recipe } from '@glassworks/kiln-core';
import { unprovision, type AppPhase, type Provisioned } from './provision';
import { s1Plus, type AssetName, type SetUpOptions } from './recipe';
import { describeSwitch, panelWifi } from './switch';
import { UPDATE_SERVICE_PROP, readRootUpdates } from './updates';

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** The S1 recipe's shape with the hardware steps simulated, for Kiln's ?demo=…. */
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

export const kiln = {
  recipe: s1Plus,
  appId: S1_KIOSK,
  appOptions: (s: { locale?: string; timezone?: string; clock24: boolean; keyboard: boolean }, confirm: (q: string) => boolean, onPhase: (p: AppPhase) => void): SetUpOptions => ({
    timezone: s.timezone, locale: s.locale, clock24: s.clock24, keyboard: s.keyboard,
    // A development build on the panel: Android needs it removed first. Ask, saying what is kept.
    confirmSwitch: async (plan) => confirm(`${describeSwitch(plan)}\n\nSwitch now?`),
    onPhase,
  }),
  wifiName: panelWifi,
  /** The boot image's root update service. */
  async updates(p: PanelShell, signal?: AbortSignal) {
    const u = await readRootUpdates(p, signal);
    return { state: u.state, log: u.state === 'unknown' ? `silent updates: not read back (${u.detail})` : `silent updates: root update service ${u.state === 'on' ? 'in place' : 'missing'}` };
  },
  giveBack: (p: PanelShell, on: (step: string, detail?: string) => void) => unprovision(p, on),
  sim: {
    recipe: simRecipe,
    props: { [UPDATE_SERVICE_PROP]: '1' },
    installLog: ['SoC 0x1855 (A133), secure boot off', 'U-Boot started from RAM; waiting for its fastboot', 'partition layout OK (S1 Plus)', 'boot read twice, hashes match',
      'backup s1-backup-20261112-101500 stored in this browser', 'patched boot 4c1e…a0b7 (from 3a7f…91c2)', 'vbmeta flags: 3'],
    backupInfo: '9 partitions · 52 MiB',
  },
};
