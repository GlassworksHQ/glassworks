// The registry lists every frame folder, loads each frame on the shared engine, and its ids and routes agree with the
// frames' own design data.
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ENGINE, FRAMES, INSTALLERS } from '../src/catalog';
import { KILN } from '../src/kiln';

const repo = join(import.meta.dirname, '../../..');

describe('the frames registry', () => {
  it('lists every frame folder', () => {
    const folders = readdirSync(join(repo, 'frames'), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort();
    expect(FRAMES.map((f) => f.id).sort()).toEqual(folders);
  });

  it('loads every frame on the engine, and only those', async () => {
    expect(Object.keys(KILN).sort()).toEqual([...ENGINE].sort());
    for (const id of ENGINE) {
      const { frame, sim } = await KILN[id]!();
      expect(frame.id).toBe(id);
      expect(sim.recipe).not.toBe(frame.recipe);
      expect(INSTALLERS[id]!.route).toMatch(/^\/[a-z0-9-]+$/);
    }
  });

  it('gives each route to one frame', () => {
    const routes = Object.values(INSTALLERS).map((i) => i.route);
    expect(new Set(routes).size).toBe(routes.length);
  });
});
