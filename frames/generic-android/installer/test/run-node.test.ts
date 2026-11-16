// The root's harness (run-node.ts) on the fake tablet: each mode runs end to end and touches no device.
import { describe, expect, it } from 'vitest';
import { cli } from '../run-node';

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
});
