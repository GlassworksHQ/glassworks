// The Stock-in-place boot animation (src/bootstill.ts): the still branding/boot/still.py derived from the Mirror's
// animation, in a one-frame bootanimation.zip exactly the size of the stock file it overwrites in place.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { bootStillZip, pngSize } from '../src/bootstill';

const OUT = join(__dirname, '../../../../branding/lululemon-mirror');
const STILL = new Uint8Array(readFileSync(join(OUT, 'bootanimation-still.png')));
const MANIFEST = JSON.parse(readFileSync(join(OUT, 'still.json'), 'utf8'));
/** /system/media/bootanimation.zip on stock 1.24.0 (research/stock-firmware-probe.md). */
const STOCK_SIZE = 22_901;

/** Python's zipfile reads the zip: each entry's name, method and bytes (hex). */
function readWithPython(zip: Uint8Array): { name: string; stored: boolean; hex: string }[] {
  const dir = mkdtempSync(join(tmpdir(), 'gw-bootstill-'));
  try {
    const p = join(dir, 'b.zip');
    writeFileSync(p, zip);
    const out = execFileSync('python3', ['-I', '-c', `
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None
    print(json.dumps([{"name": i.filename, "stored": i.compress_type == zipfile.ZIP_STORED, "hex": z.read(i).hex()} for i in z.infolist()]))
`, p], { encoding: 'utf8' });
    execFileSync('unzip', ['-tq', p]); // a second reader agrees
    return JSON.parse(out);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

describe('the boot still', () => {
  it('is the file still.json describes, from the animation Kiln writes into the image', () => {
    expect(createHash('sha256').update(STILL).digest('hex')).toBe(MANIFEST.output['bootanimation-still.png']);
    expect(STILL.length).toBe(MANIFEST.output.bytes);
    const full = readFileSync(join(OUT, 'bootanimation.zip'));
    expect(createHash('sha256').update(full).digest('hex')).toBe(MANIFEST.source.sha256);
    expect(pngSize(STILL)).toEqual({ width: MANIFEST.size[0], height: MANIFEST.size[1] });
    // centered on the screen: Android 6 centers desc.txt's rectangle, so the still lands where the animation ends
    const [w, h] = MANIFEST.screen;
    const [x0, y0, x1, y1] = MANIFEST.box;
    expect(x0 + x1).toBe(w);
    expect(y0 + y1).toBe(h);
  });

  it('becomes a bootanimation.zip of exactly the stock size, every entry stored, that two readers accept', () => {
    const zip = bootStillZip(STILL, { background: MANIFEST.background, size: STOCK_SIZE });
    expect(zip.length).toBe(STOCK_SIZE);
    const entries = readWithPython(zip);
    expect(entries.map((e) => e.name)).toEqual(['desc.txt', 'part0/', 'part0/still.png']);
    expect(entries.every((e) => e.stored)).toBe(true); // Android 6 skips compressed frames
    const desc = Buffer.from(entries[0]!.hex, 'hex').toString('ascii');
    expect(desc).toBe(`${MANIFEST.size[0]} ${MANIFEST.size[1]} 10\np 0 0 part0 ${MANIFEST.background}\n`);
    expect(Buffer.from(entries[2]!.hex, 'hex').equals(Buffer.from(STILL))).toBe(true);
    // the same bytes every time (a restore point and a retry compare them)
    expect(Buffer.from(bootStillZip(STILL, { background: MANIFEST.background, size: STOCK_SIZE })).equals(Buffer.from(zip))).toBe(true);
  });

  it('refuses a size it can\'t reach, a background that isn\'t a color, and a file that isn\'t a PNG', () => {
    expect(() => bootStillZip(STILL, { background: MANIFEST.background, size: 1000 })).toThrow(/needs \d+ bytes, and the stock bootanimation\.zip has 1000/);
    expect(() => bootStillZip(STILL, { background: MANIFEST.background, size: STILL.length + 70_000 })).toThrow(/more than a zip comment holds/);
    expect(() => bootStillZip(STILL, { background: 'black', size: STOCK_SIZE })).toThrow(/isn't #rrggbb/);
    expect(() => bootStillZip(new Uint8Array(40), { background: '#000000', size: STOCK_SIZE })).toThrow(/isn't a PNG/);
  });
});
