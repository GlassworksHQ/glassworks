// Refract (kiln/workers/refract) serves this repo's release files to Kiln from its releases.json. That file is
// written from the same installer.json `releases` Kiln reads (refractReleases() in lines.ts), so Refract admits
// exactly the lines, tags and files Kiln asks for. REFRACT_RELEASES=write writes it again.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { RELEASES, refractReleases } from '../src/app/api/release/lines';

const FILE = join(__dirname, '../../../workers/refract/releases.json');

describe("Refract's releases.json", () => {
  it('is what the frames declare', () => {
    const want = `${JSON.stringify(refractReleases(), null, 2)}\n`;
    if (process.env.REFRACT_RELEASES === 'write') writeFileSync(FILE, want);
    expect(readFileSync(FILE, 'utf8'), 'run REFRACT_RELEASES=write npm test -w @glassworks/kiln-app -- refract-releases').toBe(want);
  });

  it('holds every line Kiln lists releases of', () => {
    const { lines } = JSON.parse(readFileSync(FILE, 'utf8')) as { lines: { line: string }[] };
    expect(lines.map((l) => l.line).sort()).toEqual([...RELEASES.keys()].sort());
  });
});
