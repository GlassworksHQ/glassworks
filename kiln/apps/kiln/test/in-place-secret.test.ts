// One stage secret per browser: a reload, another tab or a later run checks the agent's signature with the same key.
import { describe, expect, it } from 'vitest';
import { browserStageSecret } from '../src/device/inPlace';

function memoryStorage() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); }, m };
}

describe('browserStageSecret', () => {
  it('makes 32 bytes once and gives the same ones back after', () => {
    const s = memoryStorage();
    const a = browserStageSecret(s);
    expect(a).toHaveLength(32);
    expect(browserStageSecret(s)).toEqual(a);
    expect(s.m.size).toBe(1);
  });

  it('a malformed stored value is replaced, not used', () => {
    const s = memoryStorage();
    s.setItem('kiln:stage-secret', 'zz');
    const a = browserStageSecret(s);
    expect(a).toHaveLength(32);
    expect(browserStageSecret(s)).toEqual(a);
  });

  it('a browser that keeps nothing still gets a secret (a new one each time)', () => {
    const broken = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
    expect(browserStageSecret(broken)).toHaveLength(32);
    expect(browserStageSecret(undefined)).toHaveLength(32);
  });
});
