// adb sideload against a fake device that plays minadbd's side (AOSP bootable/recovery, android-15.0.0_r36:
// minadbd/fuse_adb_provider.cpp writes "%08u" and reads the block back; minadbd_services.cpp ends with DONEDONE).
import { ReadableStream, WritableStream } from '@yume-chan/stream-extra';
import { describe, expect, it, vi } from 'vitest';
import { Panel, SIDELOAD_BLOCK_SIZE, SideloadError, sideload, type SideloadProgress, type SideloadSocket, type SideloadTransport } from '../src';

const BS = SIDELOAD_BLOCK_SIZE;
const ascii = (s: string) => new TextEncoder().encode(s);
const req = (block: number) => ascii(String(block).padStart(8, '0'));

/** A package of `size` bytes whose every byte says where it is, so a wrong block shows. */
function pkg(size: number): Uint8Array<ArrayBuffer> {
  const b = new Uint8Array(size);
  for (let i = 0; i < size; i++) b[i] = (i * 31 + (i >> 16)) & 0xff;
  return b;
}

type Step = Uint8Array | 'close' | 'error' | 'hang';

/**
 * A device in sideload mode. `script(got)` returns what it says next after each block it gets (got: the bytes of
 * the last block, undefined at the start): bytes it sends (requests, the end, any split), then the stream closes or
 * hangs. Records the service opened and every block received.
 */
function fakeDevice(plan: (Step | Step[])[], o: { mode?: string; refuse?: boolean; writeHangs?: boolean; writeFails?: boolean } = {}) {
  const received: Uint8Array[] = [];
  const services: string[] = [];
  let closed = 0;
  let push!: (b: Uint8Array) => void;
  let end!: () => void;
  let fail!: (e: Error) => void;
  let i = 0;
  const say = () => {
    const step = plan[i++];
    if (step === undefined) return;
    for (const s of Array.isArray(step) ? step : [step]) {
      if (s === 'close') end();
      else if (s === 'error') fail(new Error('USB transfer failed'));
      else if (s !== 'hang') push(s);
    }
  };
  const transport: SideloadTransport = {
    mode: o.mode,
    open: async (service) => {
      services.push(service);
      if (o.refuse) throw new Error('Socket open failed');
      const readable = new ReadableStream<Uint8Array>({
        start(c) {
          push = (b) => c.enqueue(b);
          end = () => { try { c.close(); } catch { /* closed */ } };
          fail = (e) => c.error(e);
        },
      });
      const writable = new WritableStream<Uint8Array>({
        write: (chunk) => {
          if (o.writeHangs) return new Promise(() => {});
          if (o.writeFails) throw new Error('Socket closed');
          received.push(chunk.slice());
          queueMicrotask(say);
        },
      });
      queueMicrotask(say);
      const socket: SideloadSocket = { readable, writable, close: () => { closed++; end(); } };
      return socket;
    },
  };
  return { transport, received, services, closed: () => closed };
}

describe('sideload', () => {
  it('opens sideload-host with the size and adb\'s 64 KiB block, serves blocks in order, and ends on DONEDONE', async () => {
    const data = pkg(3 * BS + 1234);
    const dev = fakeDevice([req(0), req(1), req(2), req(3), ascii('DONEDONE')]);
    const progress: SideloadProgress[] = [];
    const result = await sideload(dev.transport, new Blob([data]), { onProgress: (p) => progress.push(p) });
    expect(dev.services).toEqual([`sideload-host:${data.length}:65536`]);
    expect(dev.received.map((b) => b.length)).toEqual([BS, BS, BS, 1234]); // the last block short, not padded
    expect(dev.received[3]).toEqual(data.subarray(3 * BS));
    expect(result).toEqual({ ended: 'done', sent: data.length });
    expect(progress.map((p) => p.block)).toEqual([0, 1, 2, 3]);
    expect(progress.at(-1)).toEqual({ block: 3, sent: data.length, size: data.length });
    expect(dev.closed()).toBeGreaterThan(0); // let go at the end, as adb does
  });

  it('serves blocks out of order and the same block again, as the recovery\'s FUSE reads ask', async () => {
    const data = pkg(5 * BS);
    const order = [4, 0, 2, 2, 1, 3, 0, 4];
    const dev = fakeDevice([...order.map(req), ascii('DONEDONE')]);
    const progress: SideloadProgress[] = [];
    const { sent } = await sideload(dev.transport, new Blob([data]), { onProgress: (p) => progress.push(p) });
    order.forEach((b, k) => expect(dev.received[k]).toEqual(data.subarray(b * BS, (b + 1) * BS)));
    expect(sent).toBe(order.length * BS);
    expect(progress.map((p) => p.sent)).toEqual(order.map((_, k) => (k + 1) * BS)); // repeats count
  });

  it('a device that closes the stream after its blocks ends the call too, as LineageOS can on an install that worked', async () => {
    const data = pkg(2 * BS);
    const dev = fakeDevice([req(0), req(1), 'close']);
    expect(await sideload(dev.transport, new Blob([data]))).toEqual({ ended: 'closed', sent: 2 * BS });
  });

  it('reads requests split across packets and the end sent with the last request', async () => {
    const data = pkg(2 * BS);
    const dev = fakeDevice([[ascii('000'), ascii('00001')], [ascii('00000000DONE'), ascii('DONE')]]);
    const { sent } = await sideload(dev.transport, new Blob([data]));
    expect(dev.received).toEqual([data.subarray(BS), data.subarray(0, BS)]);
    expect(sent).toBe(2 * BS);
  });

  it('reads blocks from a reader, only those asked for, never past the end', async () => {
    const data = pkg(2 * BS + 7);
    const read = vi.fn(async (offset: number, length: number) => data.slice(offset, offset + length));
    const dev = fakeDevice([req(2), req(0), ascii('DONEDONE')]);
    await sideload(dev.transport, { size: data.length, read });
    expect(read.mock.calls).toEqual([[2 * BS, 7], [0, BS]]);
  });

  it('a device in another mode is refused before anything is opened', async () => {
    const dev = fakeDevice([], { mode: 'recovery' });
    const e = await sideload(dev.transport, new Blob([pkg(10)])).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(SideloadError);
    expect(e).toMatchObject({ code: 'not-in-sideload-mode', message: expect.stringMatching(/recovery mode.*Apply from ADB/) });
    expect(dev.services).toEqual([]);
  });

  it('a device that refuses the service isn\'t waiting for a sideload', async () => {
    const dev = fakeDevice([], { refuse: true });
    await expect(sideload(dev.transport, new Blob([pkg(10)]))).rejects.toMatchObject({ code: 'not-in-sideload-mode' });
  });

  it('a device that closes the stream before it asks for a block', async () => {
    const dev = fakeDevice(['close']);
    const e = await sideload(dev.transport, new Blob([pkg(2 * BS)])).catch((x: unknown) => x);
    expect(e).toMatchObject({ code: 'closed', message: expect.stringMatching(/before it asked for a block/) });
  });

  it('a device that closes halfway through its first request', async () => {
    const dev = fakeDevice([[ascii('0000'), 'close']]);
    await expect(sideload(dev.transport, new Blob([pkg(BS)]))).rejects.toMatchObject({ code: 'closed' });
  });

  it('a device that closes while it waits for a block', async () => {
    const dev = fakeDevice([req(0)], { writeFails: true });
    const e = await sideload(dev.transport, new Blob([pkg(BS)])).catch((x: unknown) => x);
    expect(e).toMatchObject({ code: 'closed', message: expect.stringMatching(/while it was getting block 0/) });
  });

  it('a connection that goes after blocks is an error, not an end', async () => {
    const dev = fakeDevice([req(0), 'error']);
    await expect(sideload(dev.transport, new Blob([pkg(2 * BS)]))).rejects.toMatchObject({ code: 'closed', message: expect.stringMatching(/after 1 block\b/) });
  });

  it('a block past the end of the package', async () => {
    const dev = fakeDevice([req(2)]);
    const e = await sideload(dev.transport, new Blob([pkg(2 * BS)])).catch((x: unknown) => x);
    expect(e).toMatchObject({ code: 'out-of-range', message: expect.stringMatching(/block 2, past the end/) });
    expect(dev.received).toEqual([]);
    expect(dev.closed()).toBeGreaterThan(0);
  });

  it('a request that isn\'t a block number', async () => {
    const dev = fakeDevice([ascii('0000x001')]);
    await expect(sideload(dev.transport, new Blob([pkg(BS)]))).rejects.toMatchObject({ code: 'bad-request' });
  });

  it('FAILFAIL (rescue mode\'s failed install)', async () => {
    const dev = fakeDevice([req(0), ascii('FAILFAIL')]);
    await expect(sideload(dev.transport, new Blob([pkg(BS)]))).rejects.toMatchObject({ code: 'failed' });
  });

  it('a source that gives the wrong number of bytes, or can\'t be read', async () => {
    const short = fakeDevice([req(0)]);
    await expect(sideload(short.transport, { size: BS, read: async () => new Uint8Array(10) })).rejects.toMatchObject({ code: 'source' });
    const broken = fakeDevice([req(0)]);
    const e = await sideload(broken.transport, { size: BS, read: async () => { throw new Error('NotReadableError'); } }).catch((x: unknown) => x);
    expect(e).toMatchObject({ code: 'source', cause: expect.objectContaining({ message: 'NotReadableError' }) });
  });

  it('an empty package is refused before anything is opened', async () => {
    const dev = fakeDevice([]);
    await expect(sideload(dev.transport, new Blob([]))).rejects.toBeInstanceOf(RangeError);
    expect(dev.services).toEqual([]);
  });

  it('cancel: rejects with the signal\'s reason and closes the stream, so the device stops', async () => {
    const dev = fakeDevice([req(0), 'hang']);
    const ac = new AbortController();
    const going = sideload(dev.transport, new Blob([pkg(2 * BS)]), { signal: ac.signal, onProgress: () => ac.abort(new Error('cancelled')) });
    await expect(going).rejects.toThrow('cancelled');
    expect(dev.closed()).toBeGreaterThan(0);
  });

  it('cancel from onProgress with the end already buffered still rejects', async () => {
    const dev = fakeDevice([[req(0)], [ascii('00000001DONEDONE')]]);
    const ac = new AbortController();
    const going = sideload(dev.transport, new Blob([pkg(2 * BS)]), {
      signal: ac.signal,
      onProgress: (p) => { if (p.block === 1) ac.abort(new Error('cancelled')); },
    });
    await expect(going).rejects.toThrow('cancelled');
  });

  it('cancel before the start opens nothing', async () => {
    const dev = fakeDevice([]);
    await expect(sideload(dev.transport, new Blob([pkg(BS)]), { signal: AbortSignal.abort(new Error('no')) })).rejects.toThrow('no');
    expect(dev.services).toEqual([]);
  });

  it('stalled: a device that stops asking for blocks', async () => {
    vi.useFakeTimers();
    try {
      const dev = fakeDevice([req(0), 'hang']);
      const going = sideload(dev.transport, new Blob([pkg(2 * BS)]), { stallMs: 60_000 }).catch((x: unknown) => x);
      await vi.advanceTimersByTimeAsync(59_000);
      expect(dev.closed()).toBe(0);
      await vi.advanceTimersByTimeAsync(2_000);
      expect(await going).toMatchObject({ code: 'stalled', message: expect.stringMatching(/stopped asking for blocks for 60 s/) });
      expect(dev.closed()).toBeGreaterThan(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stalled: a device that never takes a block', async () => {
    vi.useFakeTimers();
    try {
      const dev = fakeDevice([req(0)], { writeHangs: true });
      const going = sideload(dev.transport, new Blob([pkg(BS)]), { stallMs: 1000 }).catch((x: unknown) => x);
      await vi.advanceTimersByTimeAsync(1500);
      expect(await going).toMatchObject({ code: 'stalled' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('stalled: a source that never gives a block', async () => {
    vi.useFakeTimers();
    try {
      const dev = fakeDevice([req(0)]);
      const going = sideload(dev.transport, { size: BS, read: () => new Promise(() => {}) }, { stallMs: 1000 }).catch((x: unknown) => x);
      await vi.advanceTimersByTimeAsync(1500);
      expect(await going).toMatchObject({ code: 'stalled', message: expect.stringMatching(/didn't give block 0/) });
      expect(dev.closed()).toBeGreaterThan(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('the default watchdog waits 10 minutes; Infinity waits for ever', async () => {
    vi.useFakeTimers();
    try {
      const a = fakeDevice(['hang']);
      let settled = false;
      const going = sideload(a.transport, new Blob([pkg(BS)])).catch((x: unknown) => { settled = true; return x; });
      await vi.advanceTimersByTimeAsync(9 * 60_000);
      expect(settled).toBe(false);
      await vi.advanceTimersByTimeAsync(2 * 60_000);
      expect(await going).toMatchObject({ code: 'stalled' });

      const b = fakeDevice(['hang']);
      let done = false;
      void sideload(b.transport, new Blob([pkg(BS)]), { stallMs: Infinity }).finally(() => { done = true; });
      await vi.advanceTimersByTimeAsync(24 * 3600_000);
      expect(done).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('Panel.sideload', () => {
  it('opens the service with the NUL real adb sends, on a device whose banner says sideload', async () => {
    const dev = fakeDevice([req(0), ascii('DONEDONE')]);
    const adb = { banner: { state: 'sideload' }, createSocket: (s: string) => dev.transport.open(s) };
    const panel = Object.assign(Object.create(Panel.prototype), { adb }) as Panel;
    expect(panel.mode).toBe('sideload');
    expect(await panel.sideload(new Blob([pkg(100)]))).toEqual({ ended: 'done', sent: 100 });
    expect(dev.services).toEqual(['sideload-host:100:65536\0']);
  });

  it('refuses a device whose banner says it is booted', async () => {
    const createSocket = vi.fn();
    const panel = Object.assign(Object.create(Panel.prototype), { adb: { banner: { state: 'device' }, createSocket } }) as Panel;
    expect(panel.mode).toBe('device');
    await expect(panel.sideload(new Blob([pkg(100)]))).rejects.toMatchObject({ code: 'not-in-sideload-mode' });
    expect(createSocket).not.toHaveBeenCalled();
  });
});
