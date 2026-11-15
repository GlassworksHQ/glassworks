// The boot animation Kiln writes into the Mirror's system image (branding/lululemon-mirror, built by
// branding/boot/generate.py): portrait for the Mirror's turned screen, and the generator's own output.
// CI's branding job also rebuilds it byte for byte when the generator or the frame's data changes.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { zipEntry, zipNames } from '@glassworks/app-adb';

const REPO = join(__dirname, '../../../..');
const OUT = join(REPO, 'branding/lululemon-mirror');
const ZIP = new Uint8Array(readFileSync(join(OUT, 'bootanimation.zip')));
const sha = (p: string) => createHash('sha256').update(readFileSync(p)).digest('hex');

describe('the Mirror\'s bootanimation.zip', () => {
  it('is portrait 1080x1920 at 30 fps, full frames (Android 6 reads no trim.txt)', async () => {
    const desc = new TextDecoder().decode(await zipEntry(ZIP, 'desc.txt'));
    expect(desc.split('\n')[0]).toBe('1080 1920 30');
    expect(zipNames(ZIP).some((n) => n.endsWith('trim.txt'))).toBe(false);
    expect(zipNames(ZIP).filter((n) => n.endsWith('.png')).length).toBeGreaterThan(100);
  });
});

describe('the Mirror\'s boot branding outputs', () => {
  const manifest = JSON.parse(readFileSync(join(OUT, 'outputs.json'), 'utf8')) as {
    target: Record<string, unknown>; inputs: Record<string, string>; outputs: Record<string, string>;
  };
  it('were built for this frame', () => {
    const frame = JSON.parse(readFileSync(join(REPO, 'frames/lululemon-mirror/design/frame.json'), 'utf8'));
    expect(manifest.target).toEqual({ name: frame.name, width: frame.display.width, height: frame.display.height, android_api: frame.boot.androidApi, splash: null, zip_budget: null });
  });
  it('were built from the committed generator', () => {
    for (const f of ['generate.py', 'stack.py', 'formats.py', 'target.py']) {
      const p = `branding/boot/${f}`;
      expect(sha(join(REPO, p)), `${p} changed without a rebuild: run generate.py --frame lululemon-mirror --out branding/lululemon-mirror`).toBe(manifest.inputs[p]);
    }
  });
  it('are the files that build wrote', () => {
    expect(Object.keys(manifest.outputs).sort()).toEqual(['bootanimation.zip', 'preview.webp', 'splash-landscape.png']);
    for (const [f, h] of Object.entries(manifest.outputs)) expect(sha(join(OUT, f)), `branding/lululemon-mirror/${f}: rebuild, don't edit it`).toBe(h);
  });
});
