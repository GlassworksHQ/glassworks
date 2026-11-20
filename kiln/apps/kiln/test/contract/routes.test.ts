// Every frame's routes (#621, the maintainer's step 3 rule): Kiln is declarative, and the engine owns routing and
// resume. Each step of each flow a frame declares has its address under the frame's route, every frame has the standard
// flow's (its steps after the install), and none can have a page of its own. A reload at each step resumes there: the
// contract's reload@<step> cases (contract.test.ts), one per declared step.
import { ENGINE, INSTALLERS } from '@glassworks/frames/catalog';
import { describe, expect, it } from 'vitest';
import { STANDARD_ROUTES, routesOf } from '../../src/app/[frame]/frames';
import { generateStaticParams } from '../../src/app/[frame]/[...step]/page';
import { installers } from './harness';
import { DRIVERS, cases } from './suite';

const pages = () => generateStaticParams().map(({ frame, step }) => `/${frame}/${step.join('/')}`);

describe.each(ENGINE)('%s', (id) => {
  const data = installers().get(id)!;
  const base = INSTALLERS[id]!.route;

  it('declares no page of its own: every frame runs on Kiln\'s one page', () => {
    expect((data as unknown as { page?: unknown }).page).toBeUndefined();
  });

  it('every step of every flow has its address, and each answers', () => {
    const built = new Set(pages());
    for (const [flow, f] of Object.entries(data.flows)) {
      for (const s of f.steps as { id: string; route?: string }[]) {
        expect(s.route, `${flow}/${s.id} has no route`).toBeTypeOf('string');
        if (s.route) expect(built.has(`${base}/${s.route}`), `${base}/${s.route}`).toBe(true);
      }
    }
  });

  it('has the standard flow\'s routes, every one of them: a frame can\'t opt out', () => {
    const built = new Set(pages());
    for (const r of Object.keys(STANDARD_ROUTES)) expect(built.has(`${base}/${r}`), `${base}/${r}`).toBe(true);
    expect(Object.keys(routesOf(id))).toEqual(expect.arrayContaining(Object.keys(STANDARD_ROUTES)));
  });

  it('a reload in each of its steps is a contract case', () => {
    for (const [flow, f] of Object.entries(data.flows)) {
      const d = DRIVERS.find((x) => x.frame === id && x.flow === flow)!;
      expect(d, `${id} ${flow} has a driver`).toBeDefined();
      const reloads = cases(d).filter((c) => c.kind === 'reload').map((c) => c.step);
      expect(reloads).toEqual(f.steps.map((s) => s.id));
    }
  });
});

it('a page of its own in installer.json is refused where it comes in', async () => {
  const { INSTALLERS: all } = await import('@glassworks/frames/catalog');
  expect(Object.values(all).every((i) => !('page' in i))).toBe(true);
});
