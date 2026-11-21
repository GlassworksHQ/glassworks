// The image from Kiln's release proxy, against a fake proxy: it resumes with Range where a connection
// dropped, and refuses (so Kiln shows Try again and Report this) on a 5xx, a wrong size, or a
// resume at the wrong place.
import { createHash, randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { MemoryStore, writeSparse, Disk } from '@glassworks/ext4';
import { IMAGE_PROXY, ProxyError, loadSystem, resumableBody, retryAfterMs } from '../src';

const URL = `${IMAGE_PROXY.base.replace('refract.glassworks.dev', 'proxy.test')}/${IMAGE_PROXY.systemImg}`;

/** A fake proxy for `body`: `drops` cuts the first answers after that many bytes; `override` replaces an answer. */
function proxy(body: Uint8Array, o: { drops?: number[]; override?: (n: number, range?: string) => Response | undefined } = {}) {
  const asked: (string | undefined)[] = [];
  const f = (async (_u: string | URL | Request, init?: RequestInit) => {
    const range = (init?.headers as Record<string, string> | undefined)?.Range;
    asked.push(range);
    const n = asked.length;
    const o2 = o.override?.(n, range);
    if (o2) return o2;
    const from = range ? Number(/bytes=(\d+)-/.exec(range)![1]) : 0;
    const rest = body.subarray(from);
    const cut = o.drops?.[n - 1];
    const end = cut === undefined ? rest.length : Math.min(cut, rest.length);
    let at = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(c) { // a piece at a time, then the drop: an error in start() would discard what was queued
        if (at < end) { c.enqueue(rest.slice(at, Math.min(end, at + 7000))); at += 7000; return; }
        if (cut === undefined) c.close(); else c.error(new TypeError('network error'));
      },
    });
    return new Response(stream, range
      ? { status: 206, headers: { 'content-range': `bytes ${from}-${body.length - 1}/${body.length}`, 'content-length': String(rest.length) } }
      : { status: 200, headers: { 'content-length': String(body.length) } });
  }) as typeof fetch;
  return { f, asked };
}

async function read(s: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  return new Uint8Array(await new Response(s).arrayBuffer());
}
const noSleep = async () => {};

describe('the image from the release proxy', () => {
  it('resumes with Range where each connection dropped, and gives every byte once', async () => {
    const body = new Uint8Array(randomBytes(100_000));
    const { f, asked } = proxy(body, { drops: [30_000, 25_000] });
    const got = await read(resumableBody(URL, body.length, { fetch: f, sleep: noSleep }).stream());
    expect(Buffer.from(got)).toEqual(Buffer.from(body));
    expect(asked).toEqual([undefined, 'bytes=30000-', 'bytes=55000-']);
  });

  it.each([
    ['a 5xx', (n: number) => (n === 1 ? new Response('upstream answered 404', { status: 502 }) : undefined), /answered 502: upstream answered 404/],
    ['a wrong size', (n: number) => (n === 1 ? new Response('x', { status: 200, headers: { 'content-length': '5' } }) : undefined), /offers 5 bytes/],
    ['a resume at the wrong place', (n: number) => (n === 2 ? new Response('x', { status: 206, headers: { 'content-range': 'bytes 0-99999/100000' } }) : undefined), /can't resume at byte 30000/],
  ])('refuses %s at once, without retrying', async (_, override, msg) => {
    const body = new Uint8Array(100_000);
    const { f, asked } = proxy(body, { drops: [30_000], override });
    const e = await read(resumableBody(URL, body.length, { fetch: f, sleep: noSleep }).stream()).then(() => undefined, (x: unknown) => x);
    expect(e).toBeInstanceOf(ProxyError);
    expect((e as Error).message).toMatch(msg);
    expect(asked.length).toBe(override(1) ? 1 : 2); // no retry after the refusal
  });

  it('honours Retry-After on a 429: waits as asked, then resumes where it stopped', async () => {
    const body = new Uint8Array(randomBytes(100_000));
    const waits: number[] = [];
    const busy = (n: number) => (n === 2 ? new Response('slow down', { status: 429, headers: { 'retry-after': '7' } }) : undefined);
    const { f, asked } = proxy(body, { drops: [30_000], override: busy });
    const got = await read(resumableBody(URL, body.length, { fetch: f, sleep: async (ms) => { waits.push(ms); } }).stream());
    expect(Buffer.from(got)).toEqual(Buffer.from(body));
    expect(asked).toEqual([undefined, 'bytes=30000-', 'bytes=30000-']);
    expect(waits).toEqual([1000, 7000]);
  });

  it('falls back at once when Retry-After asks for more than a minute', async () => {
    const f = (async () => new Response('', { status: 429, headers: { 'retry-after': '600' } })) as typeof fetch;
    await expect(read(resumableBody(URL, 10, { fetch: f, sleep: noSleep }).stream())).rejects.toThrow(/busy for 600 s \(429\)/);
  });

  it('reads Retry-After as seconds or as an HTTP date', () => {
    const now = Date.parse('2026-11-15T12:00:00Z');
    expect(retryAfterMs('5', now)).toBe(5000);
    expect(retryAfterMs('Sun, 15 Nov 2026 12:00:30 GMT', now)).toBe(30_000);
    expect(retryAfterMs('Sun, 15 Nov 2026 11:00:00 GMT', now)).toBe(0);
    expect(retryAfterMs('soon', now)).toBeUndefined();
    expect(retryAfterMs(null, now)).toBeUndefined();
  });

  it('a connection that goes silent counts as dropped: it resumes with Range where the bytes stopped (#621)', async () => {
    const body = new Uint8Array(randomBytes(100_000));
    let at = 0;
    // the first answer sends 30 000 bytes, then nothing, and never closes
    const silent = (n: number) => (n === 1 ? new Response(new ReadableStream<Uint8Array>({
      pull(c) { if (at < 30_000) { c.enqueue(body.slice(at, at + 7500)); at += 7500; return; } return new Promise(() => {}); },
    }), { status: 200, headers: { 'content-length': String(body.length) } }) : undefined);
    const { f, asked } = proxy(body, { override: silent });
    const got = await read(resumableBody(URL, body.length, { fetch: f, sleep: noSleep, stallMs: 20 }).stream());
    expect(Buffer.from(got)).toEqual(Buffer.from(body));
    expect(asked).toEqual([undefined, 'bytes=30000-']);
  });

  it('a proxy that never answers, or stays silent, ends with ProxyError after its retries, not a wait for ever', async () => {
    const signals: AbortSignal[] = [];
    const never = (async (_u: string, init?: RequestInit) => { signals.push(init!.signal!); return new Promise<Response>(() => {}); }) as unknown as typeof fetch;
    await expect(read(resumableBody(URL, 10, { fetch: never, retries: 2, sleep: noSleep, stallMs: 20 }).stream())).rejects.toThrow(/sent nothing for 1 s/);
    // each silent attempt is let go before the next: no request left open behind a retry
    expect(signals.map((s) => s.aborted)).toEqual([true, true, true]);
    // a 503 whose body stalls after its headers: its reason is read under the watchdog too
    const stalled503 = (async () => new Response(new ReadableStream({ pull: () => new Promise(() => {}) }), { status: 503 })) as typeof fetch;
    await expect(read(resumableBody(URL, 10, { fetch: stalled503, retries: 2, sleep: noSleep, stallMs: 20 }).stream())).rejects.toThrow(/sent nothing for 1 s/);
    const mute = (async () => new Response(new ReadableStream({ pull: () => new Promise(() => {}) }), { headers: { 'content-length': '10' } })) as typeof fetch;
    const e = await read(resumableBody(URL, 10, { fetch: mute, retries: 2, sleep: noSleep, stallMs: 20 }).stream()).then(() => undefined, (x: unknown) => x);
    expect(e).toBeInstanceOf(ProxyError);
  });

  it('gives up after its retries when the network keeps failing', async () => {
    const f = (async () => { throw new TypeError('Failed to fetch'); }) as typeof fetch;
    await expect(read(resumableBody(URL, 10, { fetch: f, retries: 2, sleep: noSleep }).stream())).rejects.toThrow(/can't be reached \(Failed to fetch\)/);
  });

  it('streams into the image loader: unpacked and hashed on the way, through two dropped connections', async () => {
    const d = new Disk(new MemoryStore(), 4096, 64);
    await d.write(0, new Uint8Array(randomBytes(20 * 4096)));
    await d.zeroFill(30, 10);
    const parts: Uint8Array[] = [];
    await writeSparse(d, (b) => { parts.push(b.slice()); });
    const sparse = new Uint8Array(Buffer.concat(parts));
    const want = { name: 'system.img', size: sparse.length, sha256: createHash('sha256').update(sparse).digest('hex') };
    const { f, asked } = proxy(sparse, { drops: [10_000, 40_000] });
    const loaded = await loadSystem(resumableBody(URL, sparse.length, { fetch: f, sleep: noSleep }), new MemoryStore(), () => {}, want);
    expect(loaded.sha256).toBe(want.sha256);
    expect(Buffer.from(await loaded.disk.read(0, 20))).toEqual(Buffer.from(await d.read(0, 20)));
    expect(asked).toEqual([undefined, 'bytes=10000-', 'bytes=50000-']);
  });

  it('a proxy failure reaches the caller as ProxyError, not as a wrong file', async () => {
    const f = (async () => new Response('down', { status: 503 })) as typeof fetch;
    await expect(loadSystem(resumableBody(URL, 1000, { fetch: f, sleep: noSleep }), new MemoryStore(), () => {}, { name: 'system.img', size: 1000, sha256: '0' })).rejects.toBeInstanceOf(ProxyError);
  });

  it('a cancelled stream fetches nothing more: a read it cut short isn\'t a dropped connection (PR 596 review)', async () => {
    let fetches = 0;
    // the first chunk arrives, then the network waits; cancelling settles that read with done
    const f = (async () => {
      fetches++;
      return new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(10)); } }), { status: 200, headers: { 'content-length': '100' } });
    }) as unknown as typeof fetch;
    const r = resumableBody(URL, 100, { fetch: f, sleep: async () => {} }).stream().getReader();
    expect((await r.read()).value?.length).toBe(10);
    const pending = r.read(); // waits on the network
    await new Promise((res) => setTimeout(res, 0));
    await r.cancel();
    expect((await pending).done).toBe(true);
    for (let i = 0; i < 5; i++) await new Promise((res) => setTimeout(res, 0));
    expect(fetches).toBe(1);
  });
});
