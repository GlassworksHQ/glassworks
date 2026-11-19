// Development and preview only (/lululemon-mirror?demo=flash-keep…, flash-done-…): the mockups for
// keeping Home Assistant's key over a reflash (#580). The key isn't read yet: each state is set on a
// flash demo's session, with a made-up fingerprint.
import type { FlashSession, FlashState } from './flash';
import { flashDemo } from './flashDemo';
import type { MirrorSession } from './session';

export const KEEP_DEMOS = ['flash-keep', 'flash-keep-reading', 'flash-keep-held', 'flash-keep-none', 'flash-keep-unreachable', 'flash-keep-old',
  'flash-keep-working', 'flash-keep-adb', 'flash-done-kept', 'flash-done-asks', 'flash-done-refused'] as const;
export type KeepDemo = (typeof KEEP_DEMOS)[number];

const FINGERPRINT = '3F9A';

const STATES: Record<KeepDemo, { from: 'flash-ready' | 'flash-adb' | 'flash-done'; patch: Partial<FlashState>; log?: string[] }> = {
  'flash-keep': { from: 'flash-ready', patch: { screen: 'keep' } },
  'flash-keep-reading': { from: 'flash-ready', patch: { screen: 'keep', key: { state: 'reading' } }, log: ['  Glassworks 0.7.0 answers on @glassworks_api_key'] },
  'flash-keep-held': { from: 'flash-ready', patch: { screen: 'keep', key: { state: 'held', fingerprint: FINGERPRINT } }, log: ['  Glassworks 0.7.0 answers on @glassworks_api_key', `  key read, fingerprint ${FINGERPRINT}, held in this tab`, 'Restarting the Mirror into fastboot'] },
  'flash-keep-none': { from: 'flash-ready', patch: { screen: 'keep', key: { state: 'none' } } },
  'flash-keep-unreachable': { from: 'flash-ready', patch: { screen: 'keep', key: { state: 'unreachable', why: 'no-adb' } } },
  'flash-keep-old': { from: 'flash-ready', patch: { screen: 'keep', key: { state: 'unreachable', why: 'old-app', app: '0.6.2' } } },
  'flash-keep-working': { from: 'flash-adb', patch: { screen: 'working', work: { step: 'hand-back', x: 0 }, key: { state: 'held', fingerprint: FINGERPRINT } } },
  'flash-keep-adb': { from: 'flash-adb', patch: { key: { state: 'held', fingerprint: FINGERPRINT } } },
  'flash-done-kept': { from: 'flash-done', patch: { key: { state: 'handed', fingerprint: FINGERPRINT } }, log: [`  key ${FINGERPRINT} handed back; Glassworks started its API server with it`] },
  'flash-done-asks': { from: 'flash-done', patch: { key: { state: 'unreachable', why: 'no-adb' } } },
  'flash-done-refused': { from: 'flash-done', patch: { key: { state: 'refused', fingerprint: FINGERPRINT } } },
};

export async function keepDemo(name: KeepDemo, fast = false): Promise<{ flash: FlashSession; mirror: MirrorSession }> {
  const { from, patch, log = [] } = STATES[name];
  const d = await flashDemo(from, fast || from !== 'flash-ready'); // jumps to the screen: no simulated flash to wait for
  d.flash.state = { ...d.flash.state, ...patch, log: [...d.flash.state.log, ...log] };
  return d;
}
