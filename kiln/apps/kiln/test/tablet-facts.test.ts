// @vitest-environment jsdom
// What the tablet page reads from its own browser (src/device/onDevice/facts.ts): WebAssembly's 4 GiB bound, which says
// 32 or 64-bit where Chrome hides both (issue 700).
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { readFacts } from '../src/device/onDevice/facts';

beforeAll(() => { HTMLCanvasElement.prototype.getContext = (() => null) as never; }); // jsdom has no WebGL
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('wasm4g', () => {
  it('true where a 4 GiB maximum is allowed (a 64-bit V8, as this Node is)', async () => {
    expect((await readFacts()).wasm4g).toBe(true);
  });

  it('false where the engine refuses it with a RangeError, as a 32-bit V8 does', async () => {
    const Memory = vi.fn(function () { throw new RangeError("WebAssembly.Memory(): Property 'maximum': value 65536 is above the upper bound 32767"); });
    vi.stubGlobal('WebAssembly', { ...WebAssembly, Memory });
    expect((await readFacts()).wasm4g).toBe(false);
    expect(Memory).toHaveBeenCalledWith({ initial: 1, maximum: 65536 });
  });

  it('left out where the page couldn\'t try: no WebAssembly, or another error', async () => {
    vi.stubGlobal('WebAssembly', undefined);
    expect(await readFacts()).not.toHaveProperty('wasm4g');
    vi.stubGlobal('WebAssembly', { Memory: vi.fn(function () { throw new TypeError('blocked'); }) });
    expect(await readFacts()).not.toHaveProperty('wasm4g');
  });
});
