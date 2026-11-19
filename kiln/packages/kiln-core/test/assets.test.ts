// Assets: a pinned blob is checked by SHA-256, and a download that stops sending fails after the
// stall watchdog's time without a byte (#621), so the step that wanted it can be tried again.
import { describe, expect, it } from 'vitest';
import { Assets, toHex } from '../src';

const BLOB = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]);

async function manifest() {
  const sha256 = toHex(await crypto.subtle.digest('SHA-256', BLOB));
  return { generated: '', assets: [{ name: 'blob', path: 'blob.bin', sha256, size: BLOB.length, license: 'MIT', source: 'test' }] };
}

/** A body that sends `chunks`, then (with `stall`) never sends again or ends. */
function body(chunks: Uint8Array[], stall: boolean): ReadableStream<Uint8Array> {
  let i = 0;
  return new ReadableStream({
    pull(c) {
      if (i < chunks.length) { c.enqueue(chunks[i++]!); return; }
      if (!stall) { c.close(); return; }
      return new Promise(() => {}); // stalled: no byte, no end
    },
  });
}

/** fetch for the manifest and the blob; `blob` decides how the blob arrives, `list` how the manifest does. */
function fakeFetch(blob: (signal?: AbortSignal | null) => Promise<Response>, list?: () => Promise<Response>): { f: typeof fetch; signals: (AbortSignal | null | undefined)[] } {
  const signals: (AbortSignal | null | undefined)[] = [];
  const f = (async (url: string, init?: RequestInit) => {
    if (url.endsWith('manifest.json')) return list ? list() : new Response(JSON.stringify(await manifest()));
    signals.push(init?.signal);
    return blob(init?.signal);
  }) as typeof fetch;
  return { f, signals };
}

describe('Assets', () => {
  it('gives the blob once it arrives whole and matches its hash', async () => {
    const { f } = fakeFetch(async () => new Response(body([BLOB.slice(0, 3), BLOB.slice(3)], false)));
    expect(await new Assets('/a/', f, { stallMs: 50 }).get('blob')).toEqual(BLOB);
  });

  it('fails a download that stops sending, and aborts its request', async () => {
    const { f, signals } = fakeFetch(async () => new Response(body([BLOB.slice(0, 3)], true)));
    const seen: number[] = [];
    await expect(new Assets('/a/', f, { stallMs: 50 }).get('blob', (n) => seen.push(n))).rejects.toThrow(/nothing arrived for 0 s/);
    expect(seen).toEqual([3]);
    expect(signals[0]?.aborted).toBe(true);
  });

  it('fails a request that never answers', async () => {
    const { f } = fakeFetch(() => new Promise(() => {}));
    await expect(new Assets('/a/', f, { stallMs: 50 }).get('blob')).rejects.toThrow(/could not download blob: nothing arrived/);
  });

  it('fails a manifest that never answers, or stops halfway, and reads it again next time', async () => {
    const text = new TextEncoder().encode(JSON.stringify(await manifest()));
    let tries = 0;
    const list = async () => {
      tries++;
      if (tries === 1) return new Promise<Response>(() => {});
      if (tries === 2) return new Response(body([text.slice(0, 5)], true));
      return new Response(text);
    };
    const { f } = fakeFetch(async () => new Response(body([BLOB], false)), list);
    const a = new Assets('/a/', f, { stallMs: 50 });
    await expect(a.has('blob')).rejects.toThrow(/could not download manifest.json: nothing arrived/);
    await expect(a.get('blob')).rejects.toThrow(/could not download manifest.json: nothing arrived/);
    expect(await a.get('blob')).toEqual(BLOB);
    expect(tries).toBe(3);
  });

  it('downloads again after a stall: the failed try is not cached', async () => {
    let tries = 0;
    const { f } = fakeFetch(async () => new Response(body([BLOB], ++tries === 1)) );
    const a = new Assets('/a/', f, { stallMs: 50 });
    // the first try stalls after its only chunk: the body neither ends nor sends again
    await expect(a.get('blob')).rejects.toThrow(/nothing arrived/);
    expect(await a.get('blob')).toEqual(BLOB);
    expect(tries).toBe(2);
  });

  it('keeps a slow download going while bytes arrive', async () => {
    const chunks = [...BLOB].map((b) => Uint8Array.of(b));
    let i = 0;
    const slow = new ReadableStream<Uint8Array>({
      async pull(c) {
        if (i >= chunks.length) { c.close(); return; }
        await new Promise((r) => setTimeout(r, 20)); // under the 50 ms stall time each, 160 ms in all
        c.enqueue(chunks[i++]!);
      },
    });
    const { f } = fakeFetch(async () => new Response(slow));
    expect(await new Assets('/a/', f, { stallMs: 50 }).get('blob')).toEqual(BLOB);
  });
});
