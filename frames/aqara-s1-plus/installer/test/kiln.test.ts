// The S1's code for Kiln's shared wizard (src/kiln.ts): what it asks and reads the same way the wizard did when it held them.
import { describe, expect, it, vi } from 'vitest';
import { describeSwitch, kiln, type SwitchPlan } from '../src/index';

const plan: SwitchPlan = { installedVersion: '0.1.0', debugBuild: true, root: true, carries: { apiKey: false, signIn: false }, wifi: { owner: 'kiosk', ssid: 'Home' } };

describe('the S1 for Kiln', () => {
  it('passes the settings to provision, and asks before a signing-key switch, with the answer it gets', async () => {
    const ask = vi.fn(() => false);
    const onPhase = vi.fn();
    const o = kiln.appOptions({ locale: 'en-GB', timezone: 'Europe/London', clock24: true, keyboard: false }, ask, onPhase);
    expect(o).toMatchObject({ locale: 'en-GB', timezone: 'Europe/London', clock24: true, keyboard: false, onPhase });
    await expect(o.confirmSwitch!(plan)).resolves.toBe(false);
    expect(ask).toHaveBeenCalledWith(`${describeSwitch(plan)}\n\nSwitch now?`);
  });

  it('reads Silent updates as a state the words have, with the log line', async () => {
    const panel = (out: string, code = 0) => ({ run: async () => ({ out, code }), push: async () => {} });
    await expect(kiln.updates(panel('1\n'))).resolves.toEqual({ state: 'on', log: 'silent updates: root update service in place' });
    await expect(kiln.updates(panel(''))).resolves.toEqual({ state: 'missing', log: 'silent updates: root update service missing' });
    await expect(kiln.updates(panel('', 1))).resolves.toMatchObject({ state: 'unknown', log: expect.stringMatching(/^silent updates: not read back \(/) });
  });
});
