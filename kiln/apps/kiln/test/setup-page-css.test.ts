// The panel's own setup page (#34) takes the panel's colours (#63) in lan/lan.css: a white main button,
// sky and violet where Kiln's steps read ember (docs/branding.md: ember is Kiln's only), and only the
// brand's tokens. Kiln's wizard never loads this file, so its own look (css.test.ts) stays as it is.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('lan.css', () => {
  const css = readFileSync(new URL('../lan/lan.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const decls = (selector: string) => [...css.matchAll(/(?<=^|\})\s*([^{}]+?)\s*\{([^}]*)\}/g)]
    .filter((m) => m[1] === selector)
    .map((m) => Object.fromEntries(m[2]!.split(';').map((d) => d.split(':').map((x) => x.trim())).filter(([k]) => k)));

  it('points the ember Kiln\'s steps read at sky and violet, and their main button is white', () => {
    expect(decls(':root').find((d) => d['--ember-a'])).toMatchObject({ '--ember-a': 'var(--sky)', '--ember-b': 'var(--violet)', '--bg': 'var(--night)' });
    expect(decls('.btn.ember')[0]).toMatchObject({ background: 'var(--fg)', color: 'var(--night)' });
  });

  it('copies no colour by hand', () => {
    expect(css.match(/#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi)).toBeNull();
  });
});
