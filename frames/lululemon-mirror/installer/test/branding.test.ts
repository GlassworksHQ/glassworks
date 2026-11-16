// The boot animation Kiln writes into the Mirror's system image (branding/lululemon-mirror, built by
// branding/boot/generate.py): portrait art turned into the Mirror's 1920x1080 landscape panel, and the
// generator's own output.
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
  it('plays in the panel\'s own 1920x1080 framebuffer at 30 fps, full frames (Android 6 reads no trim.txt)', async () => {
    // ro.sf.hwrotation turns Android's UI, not the boot animation (seen on the unit, 2026-11-16): the
    // portrait art is turned into the landscape panel, frame by frame
    const desc = new TextDecoder().decode(await zipEntry(ZIP, 'desc.txt'));
    expect(desc.split('\n')[0]).toBe('1920 1080 30');
    for (const n of zipNames(ZIP).filter((x) => x.endsWith('.png')).slice(0, 3)) {
      const png = (await zipEntry(ZIP, n))!;
      const ihdr = new DataView(png.buffer, png.byteOffset + 16, 8); // IHDR: width, height
      expect([ihdr.getUint32(0), ihdr.getUint32(4)], n).toEqual([1920, 1080]);
    }
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
    expect(manifest.target).toEqual({ name: frame.name, width: frame.display.width, height: frame.display.height, android_api: frame.boot.androidApi, splash: null, zip_budget: null, rotate: 90 });
    expect(frame.boot.rotate).toBe(90);
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
