// The root's harness (run-node.ts) on the fake tablet: each mode runs end to end and touches no device.
import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { cli } from '../run-node';
import { FakeAndroid } from './fakes';

describe('run-node.ts --dry-run', () => {
  const run = async (args: string[]) => { const out: string[] = []; const code = await cli([...args, '--dry-run'], (l) => out.push(l)); return { code, text: out.join('\n') }; };

  it('--plan reads the device and names the build', async () => {
    const { code, text } = await run(['--plan']);
    expect(code).toBe(0);
    expect(text).toMatch(/"app": "dev\.glassworks\.generic_android"/);
    expect(text).toMatch(/"model": "Pixel Tablet"/);
  });

  it('--install reports the grants, the home screen and the setup page', async () => {
    const { code, text } = await run(['--install']);
    expect(code).toBe(0);
    expect(text).toMatch(/"how": "set-home-activity"/);
    expect(text).toMatch(/"setupPage": "http:\/\/192\.0\.2\.10:8099\/setup"/);
  });

  it('without a mode, prints the usage', async () => {
    expect((await run([])).code).toBe(2);
  });

  const plan = async (p: PanelShell) => { const out: string[] = []; await cli(['--plan', '--dry-run'], (l) => out.push(l), p); return JSON.parse(out.join('\n')) as { home: string }; };

  it('--plan on Android 6 reads HOME from the "Always" pick, without `cmd`', async () => {
    const p = new FakeAndroid({ sdk: 23, release: '6.0.1' });
    expect((await plan(p)).home).toBe('com.android.launcher3/.Launcher');
    expect(p.cmds.some((c) => c.startsWith('cmd '))).toBe(false);
  });

  it('--plan never prints a failed lookup as the home app', async () => {
    const p = new FakeAndroid({});
    const run0 = p.run.bind(p);
    p.run = async (cmd) => (cmd.startsWith('cmd package resolve-activity') ? { out: 'Exception occurred while executing \'resolve-activity\':\njava.lang.SecurityException\n', code: 255 } : run0(cmd));
    expect((await plan(p)).home).toBe('unknown (the lookup failed)');
  });
});
