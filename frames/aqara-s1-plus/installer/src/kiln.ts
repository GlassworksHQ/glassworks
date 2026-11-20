// The S1 for Kiln (#621): its real install as the shared engine runs it. The words and drawings are its design data
// (../../design), the steps only code can say are below, and its recipe is recipe.ts. Kiln reads it through the frames
// registry (packages/frames). The simulated device for ?demo=… and tests is separate: sim.ts.
import type { PanelShell } from '@glassworks/adb';
import { S1_KIOSK } from '@glassworks/app-adb';
import { frameWords, type KilnFrame, type RecoveryData } from '@glassworks/kiln-core';
import photo from '../../../../docs/img/fel-button.jpg';
import design from '../../design/frame.json';
import installer from '../../design/installer.json';
import recovery from '../../design/recovery.json';
import { credits } from './credits';
import { unprovision, type AppPhase } from './provision';
import { s1Plus, type SetUpOptions } from './recipe';
import { describeSwitch, panelWifi } from './switch';
import { readRootUpdates, type RootUpdates } from './updates';

/** Every state readRootUpdates gives; a new one fails the typecheck here until it is added. */
const UPDATE_STATES: Record<RootUpdates['state'], true> = { on: true, missing: true, unknown: true };

const code = {
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
  updateStates: Object.keys(UPDATE_STATES),
  giveBack: (p: PanelShell, on: (step: string, detail?: string) => void) => unprovision(p, on),
};

export const kilnFrame: KilnFrame = {
  id: design.id,
  ...frameWords(installer.words, code.updateStates),
  recovery: recovery as RecoveryData,
  // Next gives a static image as { src, width, height }; Vite (the tests) as its URL
  photo: photo as unknown as KilnFrame['photo'],
  credits,
  ...code,
};
