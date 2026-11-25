// Kiln's bundle transfer channels on a fake agent: the cache, the local network (Local Network Access), Bluetooth with
// selective resend and resume, the file fallback, and the orchestrator's automatic fallback from one to the next.
import { describe, expect, it, vi } from 'vitest';
import {
  CTRL_ABORT,
  CTRL_RESEND,
  CTRL_START,
  FRAME_END,
  FRAME_INFO,
  LanTransferError,
  buildAgentPageUrl,
  connectBleAgent,
  encodeResend,
  fetchBundleOverLan,
  parseAgentMeta,
  readBundleFromFile,
  receiveBundle,
  receiveBundleFromFile,
  type BleCharacteristicLike,
  type BleChannelConfig,
  type BleResumeStore,
  type BluetoothLike,
  type BundleCacheAdapter,
  type ChannelConfig,
  type FallbackEvent,
  type TransferProgress,
} from '../src/device/channels';

const BLE: BleChannelConfig = {
  service: '00000000-0000-4000-8000-00000000a000',
  meta: '00000000-0000-4000-8000-00000000a001',
  ctrl: '00000000-0000-4000-8000-00000000a002',
  data: '00000000-0000-4000-8000-00000000a003',
};
const CONFIG: ChannelConfig = { ble: BLE, lan: { port: 8080, path: '/bundle.bin', pagePath: '/' } };
const DIGEST = 'ab'.repeat(32);

/** A bundle as the agent builds it, with a payload of `payloadLen` pseudo-random bytes. */
function makeBundle(payloadLen: number, meta: Record<string, unknown> = { version: 1, digest: DIGEST }): Uint8Array<ArrayBuffer> {
  const m = new TextEncoder().encode(JSON.stringify(meta));
  const out = new Uint8Array(12 + m.length + payloadLen);
  out.set(new TextEncoder().encode('GWSB'));
  const v = new DataView(out.buffer);
  v.setUint32(4, m.length, true);
  v.setUint32(8, payloadLen, true);
  out.set(m, 12);
  let x = 0x12345678;
  for (let i = 12 + m.length; i < out.length; i++) { x = (x * 1103515245 + 12345) >>> 0; out[i] = x >>> 24; }
  return out;
}

const view = (bytes: Uint8Array) => new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
const u32 = (...n: number[]) => { const b = new Uint8Array(n.length * 4); const v = new DataView(b.buffer); n.forEach((x, i) => v.setUint32(i * 4, x, true)); return b; };
const cat = (a: Uint8Array, b: Uint8Array) => { const o = new Uint8Array(a.length + b.length); o.set(a); o.set(b, a.length); return o; };

interface AgentOptions {
  chunkSize?: number;
  /** Drop a chunk's notification: (index, which command's stream) → true to drop it. */
  drop?: (idx: number, batch: number) => boolean;
  /** Stop streaming after this many chunks of the first 0x01 and never send END (a stall). */
  hangAfter?: number;
  meta?: Record<string, unknown>;
  /** INFO as the agent sends it, in place of the bundle's own: [chunk size, bundle size, chunk count]. */
  info?: [number, number, number];
  /** In place of the data characteristic's own: a GATT operation that never settles, say. */
  startNotifications?: () => Promise<void>;
  stopNotifications?: () => Promise<void>;
  /** Called with each CTRL write before the agent acts on it; a promise that never settles stalls the write. */
  ctrlGate?: (b: Uint8Array) => Promise<void> | void;
  /** Awaited before the GATT connect resolves: a promise that never settles stalls it. */
  connectGate?: () => Promise<void>;
}

/** A fake agent behind a fake Web Bluetooth: the GATT profile, streaming on a timer like real notifications. */
function fakeAgent(bundle: Uint8Array, o: AgentOptions = {}) {
  const chunkSize = o.chunkSize ?? 180;
  const total = Math.ceil(bundle.length / chunkSize);
  const writes: Uint8Array[] = [];
  const listeners = new Set<(e: Event) => void>();
  let notifying = false;
  let batch = 0;
  let stopped = false;
  const chunksSent: number[] = [];

  const notify = (bytes: Uint8Array) => {
    if (!notifying) return;
    const ev = { target: { value: view(bytes) } } as unknown as Event;
    for (const l of listeners) l(ev);
  };
  const chunk = (i: number) => cat(u32(i), bundle.subarray(i * chunkSize, Math.min(bundle.length, (i + 1) * chunkSize)));

  const stream = (indexes: number[], withInfo: boolean, hangAfter?: number) => {
    const b = batch++;
    stopped = false;
    setTimeout(() => {
      if (withInfo) notify(u32(FRAME_INFO, ...(o.info ?? [chunkSize, bundle.length, total])));
      let sent = 0;
      for (const i of indexes) {
        if (stopped) return;
        if (hangAfter !== undefined && sent >= hangAfter) return;
        sent++;
        chunksSent.push(i);
        if (!o.drop?.(i, b)) notify(chunk(i));
      }
      notify(u32(FRAME_END, sent));
    }, 1);
  };

  const meta = { lanIp: '192.0.2.10', httpPort: 8080, bundleSize: bundle.length, chunkSize: 244, chunkLocked: false, contentSha256: DIGEST, serialno: 'TESTSERIAL', ...o.meta };
  const metaBytes = new TextEncoder().encode(JSON.stringify(meta));

  const data: BleCharacteristicLike = {
    readValue: async () => view(new Uint8Array()),
    startNotifications: async () => { if (o.startNotifications) await o.startNotifications(); notifying = true; },
    stopNotifications: async () => { if (o.stopNotifications) await o.stopNotifications(); notifying = false; },
    addEventListener: (_t, l) => { listeners.add(l); },
    removeEventListener: (_t, l) => { listeners.delete(l); },
  };
  const ctrl: BleCharacteristicLike = {
    readValue: async () => view(new Uint8Array()),
    writeValueWithResponse: async (value) => {
      const b = new Uint8Array(value as ArrayBuffer | Uint8Array as Uint8Array).slice();
      writes.push(b);
      await o.ctrlGate?.(b);
      const v = view(b);
      if (b[0] === CTRL_START) {
        const start = v.getUint32(1, true);
        const all = Array.from({ length: total - start }, (_, k) => start + k);
        stream(all, true, batch === 0 ? o.hangAfter : undefined);
      } else if (b[0] === CTRL_RESEND) {
        const n = v.getUint16(1, true);
        stream(Array.from({ length: n }, (_, k) => v.getUint32(3 + k * 4, true)), false);
      } else if (b[0] === CTRL_ABORT) {
        stopped = true;
      }
    },
    startNotifications: async () => {},
    stopNotifications: async () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  const metaChar: BleCharacteristicLike = { ...ctrl, readValue: async () => view(metaBytes) };
  const chars: Record<string, BleCharacteristicLike> = { [BLE.meta]: metaChar, [BLE.ctrl]: ctrl, [BLE.data]: data };
  const requests: unknown[] = [];
  const disconnects = { n: 0, gatt: 0 };
  const bluetooth: BluetoothLike = {
    getAvailability: async () => true,
    requestDevice: async (opts) => {
      requests.push(opts);
      return {
        gatt: {
          disconnect: () => { disconnects.gatt++; },
          connect: async () => (await o.connectGate?.(), {
            connected: true,
            disconnect: () => { disconnects.n++; },
            getPrimaryService: async (uuid: string) => {
              expect(uuid).toBe(BLE.service);
              return { getCharacteristic: async (u: string) => chars[u]! };
            },
          }),
        },
      };
    },
  };
  return { bluetooth, writes, requests, total, chunkSize, chunksSent, meta, disconnects };
}

const cmds = (writes: Uint8Array[], code: number) => writes.filter((w) => w[0] === code);
const resendIndexes = (w: Uint8Array) => { const v = view(w); return Array.from({ length: v.getUint16(1, true) }, (_, k) => v.getUint32(3 + k * 4, true)); };

function memoryCache(): BundleCacheAdapter & { map: Map<string, Uint8Array> } {
  const map = new Map<string, Uint8Array>();
  return { map, get: async (k) => map.get(`${k.serial}/${k.digest}`) ?? null, put: async (k, b) => { map.set(`${k.serial}/${k.digest}`, b); } };
}

function memoryResume(): BleResumeStore & { saved: Map<string, Map<number, Uint8Array>>; info: { chunkSize: number; bundleSize: number } | null } {
  const saved = new Map<string, Map<number, Uint8Array>>();
  const store = {
    saved,
    info: null as { chunkSize: number; bundleSize: number } | null,
    load: async (d: string) => (saved.get(d) && store.info ? { ...store.info, received: saved.get(d)! } : null),
    saveChunk: async (d: string, i: number, b: Uint8Array) => { if (!saved.has(d)) saved.set(d, new Map()); saved.get(d)!.set(i, b); },
    clear: async (d: string) => { saved.delete(d); },
  };
  return store;
}

/** A fetch that streams `bytes` in `parts` pieces, optionally failing after `failAfter` of them. */
function lanFetch(bytes: Uint8Array, o: { parts?: number; failAfter?: number; status?: number } = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  const impl = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const parts = o.parts ?? 8;
    const size = Math.ceil(bytes.length / parts);
    let k = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(c) {
        if (o.failAfter !== undefined && k >= o.failAfter) { c.error(new TypeError('network connection was lost')); return; }
        if (k * size >= bytes.length) { c.close(); return; }
        c.enqueue(bytes.slice(k * size, (k + 1) * size));
        k++;
      },
    });
    return { ok: (o.status ?? 200) < 400, status: o.status ?? 200, headers: new Headers({ 'content-length': String(bytes.length) }), body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe('the cache', () => {
  it('returns a bundle it holds for this device and digest, with no transfer', async () => {
    const bundle = makeBundle(5000);
    const agent = fakeAgent(bundle);
    const cache = memoryCache();
    cache.map.set(`TESTSERIAL/${DIGEST}`, bundle);
    const fetchImpl = vi.fn();
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const got = await receiveBundle({ config: CONFIG, connection: conn, cache, fetchImpl: fetchImpl as unknown as typeof fetch });
    expect(got.channel).toBe('cache');
    expect(got.bytes).toBe(bundle);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(agent.writes).toEqual([]);
  });

  it('passes over a cached bundle that isn\'t the one the agent describes', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle);
    const cache = memoryCache();
    cache.map.set(`TESTSERIAL/${DIGEST}`, makeBundle(2000));
    const lan = lanFetch(bundle);
    const got = await receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), cache, fetchImpl: lan.impl });
    expect(got.channel).toBe('lan');
    expect(got.bytes).toEqual(bundle);
    expect(cache.map.get(`TESTSERIAL/${DIGEST}`)).toEqual(bundle);
  });
});

describe('the cache, bound to the device as it is now', () => {
  it('passes over a kept bundle the caller says no longer fits the device, and takes it while it does', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle);
    const cache = memoryCache();
    cache.map.set(`TESTSERIAL/${DIGEST}`, bundle);
    const lan = lanFetch(bundle);
    const moved = await receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), cache, fetchImpl: lan.impl, verifyKept: () => false });
    expect(moved.channel).toBe('lan');
    expect(lan.calls).toHaveLength(1);
    const same = await receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), cache, fetchImpl: lan.impl, verifyKept: () => true });
    expect(same.channel).toBe('cache');
    expect(lan.calls).toHaveLength(1);
  });

  it('hands nothing back once cancelled while the kept bundle is checked', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle);
    const cache = memoryCache();
    cache.map.set(`TESTSERIAL/${DIGEST}`, bundle);
    const ctl = new AbortController();
    const got = receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), cache, signal: ctl.signal,
      verify: async () => { ctl.abort(); return true; } });
    await expect(got).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('hands nothing back once cancelled while a bundle from the network is checked', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle);
    const ctl = new AbortController();
    const got = receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), fetchImpl: lanFetch(bundle).impl, signal: ctl.signal,
      verify: async () => { ctl.abort(); return true; } });
    await expect(got).rejects.toMatchObject({ name: 'AbortError' });
  });
});

describe('the local network', () => {
  it('fetches with Local Network Access and reports progress', async () => {
    const bundle = makeBundle(40_000);
    const agent = fakeAgent(bundle);
    const lan = lanFetch(bundle, { parts: 10 });
    const progress: TransferProgress[] = [];
    let t = 0;
    const bytes = await fetchBundleOverLan('http://192.0.2.10:8080/bundle.bin', { fetchImpl: lan.impl, onProgress: (p) => progress.push(p), now: () => (t += 250) });
    expect(bytes).toEqual(bundle);
    expect(lan.calls[0]!.init).toMatchObject({ mode: 'cors', credentials: 'omit', cache: 'no-store', targetAddressSpace: 'local' });
    expect(progress.length).toBeGreaterThan(5);
    expect(progress.every((p) => p.channel === 'lan' && p.totalBytes === bundle.length)).toBe(true);
    expect(progress.at(-1)!.receivedBytes).toBe(bundle.length);
    expect(progress.some((p) => p.bytesPerSecond > 0 && p.etaSeconds !== null)).toBe(true);
    void agent;
  });

  it('takes the orchestrator\'s first choice when the agent has an address', async () => {
    const bundle = makeBundle(20_000);
    const agent = fakeAgent(bundle);
    const lan = lanFetch(bundle);
    const channels: string[] = [];
    const got = await receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), fetchImpl: lan.impl, onChannel: (c) => channels.push(c) });
    expect(got.channel).toBe('lan');
    expect(got.bytes).toEqual(bundle);
    expect(lan.calls[0]!.url).toBe('http://192.0.2.10:8080/bundle.bin');
    expect(channels).toEqual(['lan']);
    expect(agent.writes).toEqual([]);
  });

  it('classifies an HTTP error, a refusal, a drop and a timeout', async () => {
    const bundle = makeBundle(1000);
    await expect(fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: lanFetch(bundle, { status: 404 }).impl })).rejects.toMatchObject({ kind: 'http-error' });
    const refused = (async () => { throw new TypeError('Failed to fetch'); }) as unknown as typeof fetch;
    await expect(fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: refused, localNetworkPermission: async () => 'denied' })).rejects.toMatchObject({ kind: 'lna-denied' });
    await expect(fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: refused, localNetworkPermission: async () => 'granted' })).rejects.toMatchObject({ kind: 'network-error' });
    await expect(fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: lanFetch(bundle, { failAfter: 2 }).impl, localNetworkPermission: async () => null })).rejects.toMatchObject({ kind: 'network-error' });
    const hang = (() => new Promise(() => {})) as unknown as typeof fetch;
    await expect(fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: hang, timeoutMs: 30 })).rejects.toMatchObject({ kind: 'timeout' });
    const ctl = new AbortController();
    const p = fetchBundleOverLan('http://192.0.2.10/', { fetchImpl: hang, signal: ctl.signal });
    ctl.abort();
    await expect(p).rejects.toBeInstanceOf(LanTransferError);
    await expect(p).rejects.toMatchObject({ kind: 'aborted' });
  });
});

describe('falling back from the local network to Bluetooth', () => {
  it('a drop mid-transfer falls back on its own and completes byte for byte', async () => {
    const bundle = makeBundle(30_000);
    const agent = fakeAgent(bundle);
    const fallbacks: FallbackEvent[] = [];
    const got = await receiveBundle({
      config: CONFIG,
      connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }),
      fetchImpl: lanFetch(bundle, { failAfter: 3 }).impl,
      localNetworkPermission: async () => 'granted',
      onFallback: (e) => fallbacks.push(e),
    });
    expect(got.channel).toBe('ble');
    expect(got.bytes).toEqual(bundle);
    expect(fallbacks).toMatchObject([{ from: 'lan', to: 'ble', reason: 'network-error' }]);
  });

  it('a Local Network Access refusal falls back, and says so', async () => {
    const bundle = makeBundle(10_000);
    const agent = fakeAgent(bundle);
    const fallbacks: FallbackEvent[] = [];
    const refused = (async () => { throw new TypeError('Failed to fetch'); }) as unknown as typeof fetch;
    const got = await receiveBundle({
      config: CONFIG,
      connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }),
      fetchImpl: refused,
      localNetworkPermission: async () => 'denied',
      onFallback: (e) => fallbacks.push(e),
    });
    expect(got.channel).toBe('ble');
    expect(got.bytes).toEqual(bundle);
    expect(fallbacks[0]!.reason).toBe('lna-denied');
  });

  it('no local address goes straight to Bluetooth, and the bundle lands in the cache', async () => {
    const bundle = makeBundle(4000);
    const agent = fakeAgent(bundle, { meta: { lanIp: null } });
    const fetchImpl = vi.fn();
    const cache = memoryCache();
    const got = await receiveBundle({ config: CONFIG, connection: await connectBleAgent(BLE, { bluetooth: agent.bluetooth }), fetchImpl: fetchImpl as unknown as typeof fetch, cache });
    expect(got.channel).toBe('ble');
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(cache.map.get(`TESTSERIAL/${DIGEST}`)).toEqual(bundle);
  });
});

describe('Bluetooth', () => {
  it('asks the chooser for the agent\'s service only', async () => {
    const agent = fakeAgent(makeBundle(100));
    await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    expect(agent.requests).toEqual([{ filters: [{ services: [BLE.service] }], optionalServices: [BLE.service] }]);
  });

  it('reads the agent\'s metadata, with its digest under any *Sha256 name', async () => {
    const agent = fakeAgent(makeBundle(100));
    const meta = await (await connectBleAgent(BLE, { bluetooth: agent.bluetooth })).readMeta();
    expect(meta).toMatchObject({ lanIp: '192.0.2.10', httpPort: 8080, digest: DIGEST, serialno: 'TESTSERIAL' });
    expect(parseAgentMeta(new TextEncoder().encode(JSON.stringify({ hmacSha256: 'x', payloadSha256: 'y' }))).digest).toBe('y');
  });

  it('resends dropped chunks after END, in batches of at most 100, until every byte is in', async () => {
    const bundle = makeBundle(64_000);
    // drop 250 chunks in the first stream, and a few again in the first resend pass
    const agent = fakeAgent(bundle, { chunkSize: 100, drop: (i, b) => (b === 0 && i % 2 === 1 && i < 500) || (b === 1 && i === 3) });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const progress: TransferProgress[] = [];
    const bytes = await conn.downloadBundle({ onProgress: (p) => progress.push(p), progressIntervalMs: 0 });
    expect(bytes).toEqual(bundle);
    const resends = cmds(agent.writes, CTRL_RESEND);
    expect(resends.length).toBeGreaterThanOrEqual(4);
    expect(resends.every((w) => w.length <= 403)).toBe(true);
    expect(resendIndexes(resends[0]!)).toHaveLength(100);
    expect(resends.flatMap(resendIndexes).filter((i) => i === 3).length).toBe(2);
    expect(progress.some((p) => p.resendPass === 1)).toBe(true);
    expect(progress.at(-1)).toMatchObject({ receivedBytes: bundle.length, totalBytes: bundle.length });
  });

  it('a stall mid-stream streams the tail again', async () => {
    const bundle = makeBundle(20_000);
    const agent = fakeAgent(bundle, { chunkSize: 200, hangAfter: 30 });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const bytes = await conn.downloadBundle({ stallTimeoutMs: 40 });
    expect(bytes).toEqual(bundle);
    const starts = cmds(agent.writes, CTRL_START).map((w) => view(w).getUint32(1, true));
    expect(starts).toEqual([0, 30]);
  });

  it('gives up after its resend passes', async () => {
    const bundle = makeBundle(2000);
    const agent = fakeAgent(bundle, { chunkSize: 100, drop: (i) => i === 5 });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    await expect(conn.downloadBundle({ maxResendRounds: 2 })).rejects.toThrow(/1 chunks were still missing after 2 resend passes/);
  });

  it('resumes from chunks kept before, starting at the first missing one', async () => {
    const bundle = makeBundle(10_000);
    const agent = fakeAgent(bundle, { chunkSize: 100 });
    const store = memoryResume();
    store.info = { chunkSize: 100, bundleSize: bundle.length };
    const kept = new Map<number, Uint8Array>();
    for (let i = 0; i < 40; i++) kept.set(i, bundle.slice(i * 100, (i + 1) * 100));
    store.saved.set(DIGEST, kept);
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const bytes = await conn.downloadBundle({ resumeStore: store, digest: DIGEST });
    expect(bytes).toEqual(bundle);
    expect(view(cmds(agent.writes, CTRL_START)[0]!).getUint32(1, true)).toBe(40);
    expect(agent.chunksSent[0]).toBe(40);
    expect(store.saved.has(DIGEST)).toBe(false); // cleared once whole
  });

  it('keeps chunks as they arrive, so the next try resumes', async () => {
    const bundle = makeBundle(5000);
    const agent = fakeAgent(bundle, { chunkSize: 100, hangAfter: 20 });
    const store = memoryResume();
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const ctl = new AbortController();
    const p = conn.downloadBundle({ resumeStore: store, digest: DIGEST, signal: ctl.signal, stallTimeoutMs: 10_000 });
    await vi.waitFor(() => expect(store.saved.get(DIGEST)?.size).toBe(20));
    ctl.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
    expect(store.saved.get(DIGEST)!.get(19)).toEqual(bundle.slice(1900, 2000));
  });

  it('resumes from initial chunks without a store', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle, { chunkSize: 100 });
    const chunks = new Map<number, Uint8Array>([[0, bundle.slice(0, 100)], [1, bundle.slice(100, 200)]]);
    const bytes = await (await connectBleAgent(BLE, { bluetooth: agent.bluetooth })).downloadBundle({ initialChunks: { chunkSize: 100, chunks } });
    expect(bytes).toEqual(bundle);
    expect(view(cmds(agent.writes, CTRL_START)[0]!).getUint32(1, true)).toBe(2);
  });

  it('trusts INFO over kept chunks of another size, and fetches them again', async () => {
    const bundle = makeBundle(3000);
    const agent = fakeAgent(bundle, { chunkSize: 150 });
    const chunks = new Map<number, Uint8Array>([[0, bundle.slice(0, 100)], [1, bundle.slice(100, 200)]]);
    const bytes = await (await connectBleAgent(BLE, { bluetooth: agent.bluetooth })).downloadBundle({ initialChunks: { chunkSize: 100, chunks } });
    expect(bytes).toEqual(bundle);
    expect(cmds(agent.writes, CTRL_RESEND).flatMap(resendIndexes)).toEqual([0, 1]);
  });

  it('an abort sends 0x03 and rejects with an AbortError', async () => {
    const bundle = makeBundle(5000);
    const agent = fakeAgent(bundle, { chunkSize: 100, hangAfter: 5 });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const ctl = new AbortController();
    const p = conn.downloadBundle({ signal: ctl.signal });
    await vi.waitFor(() => expect(agent.chunksSent.length).toBe(5));
    ctl.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
    expect(agent.writes.at(-1)).toEqual(Uint8Array.of(CTRL_ABORT));
  });

  it('the connection\'s own abort() does the same', async () => {
    const agent = fakeAgent(makeBundle(5000), { chunkSize: 100, hangAfter: 5 });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const p = conn.downloadBundle();
    await vi.waitFor(() => expect(agent.chunksSent.length).toBe(5));
    await conn.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
    expect(cmds(agent.writes, CTRL_ABORT)).toHaveLength(1);
  });

  it('holds the connection from its first line: a second download while the kept chunks load is turned away', async () => {
    const bundle = makeBundle(2000);
    const agent = fakeAgent(bundle, { chunkSize: 100 });
    let loaded!: () => void;
    const store: BleResumeStore = {
      load: () => new Promise((r) => { loaded = () => r(null); }),
      saveChunk: async () => {},
      clear: async () => {},
    };
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const first = conn.downloadBundle({ resumeStore: store, digest: DIGEST });
    await expect(conn.downloadBundle()).rejects.toThrow(/already running/);
    loaded();
    expect(await first).toEqual(bundle);
    expect(cmds(agent.writes, CTRL_START)).toHaveLength(1);
  });

  it('a cancel while the kept chunks load asks nothing of the device', async () => {
    const agent = fakeAgent(makeBundle(2000), { chunkSize: 100 });
    let loaded!: () => void;
    const store: BleResumeStore = { load: () => new Promise((r) => { loaded = () => r(null); }), saveChunk: async () => {}, clear: async () => {} };
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const ctl = new AbortController();
    const p = conn.downloadBundle({ resumeStore: store, digest: DIGEST, signal: ctl.signal });
    ctl.abort();
    loaded();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
    expect(cmds(agent.writes, CTRL_START)).toHaveLength(0);
  });

  it('keeps the connection until its notifications are off: no new download on a half-stopped stream', async () => {
    const bundle = makeBundle(1000);
    let stopped!: () => void;
    let stopping = false;
    const agent = fakeAgent(bundle, { chunkSize: 100, stopNotifications: () => { stopping = true; return new Promise((r) => { stopped = r; }); } });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const first = conn.downloadBundle();
    await vi.waitFor(() => expect(stopping).toBe(true));
    await expect(conn.downloadBundle()).rejects.toThrow(/already running/);
    stopped();
    expect(await first).toEqual(bundle);
  });

  it('turns down an INFO that offers more than it takes in, or chunks it can\'t follow, before it allocates for them', async () => {
    const big = fakeAgent(makeBundle(2000), { chunkSize: 100, info: [100, 2 ** 31, Math.ceil(2 ** 31 / 100)] });
    await expect((await connectBleAgent(BLE, { bluetooth: big.bluetooth })).downloadBundle({ stallTimeoutMs: 2000 })).rejects.toThrow(/more than the \d+ Kiln takes in/);
    const capped = fakeAgent(makeBundle(2000), { chunkSize: 100 });
    await expect((await connectBleAgent(BLE, { bluetooth: capped.bluetooth })).downloadBundle({ maxBundleBytes: 1000, stallTimeoutMs: 2000 })).rejects.toThrow(/more than the 1000 Kiln takes in/);
    const tiny = fakeAgent(makeBundle(2000), { chunkSize: 100, info: [1, 2000, 2000] });
    await expect((await connectBleAgent(BLE, { bluetooth: tiny.bluetooth })).downloadBundle({ stallTimeoutMs: 2000 })).rejects.toThrow(/can't follow/);
  });

  it('a command the device never answers fails after the watchdog and gives the link up', async () => {
    const agent = fakeAgent(makeBundle(2000), { chunkSize: 100, ctrlGate: (b) => (b[0] === CTRL_START ? new Promise<void>(() => {}) : undefined) });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    await expect(conn.downloadBundle({ stallTimeoutMs: 50 })).rejects.toThrow(/stopped answering over Bluetooth \(a command\)/);
    expect(agent.disconnects.n).toBe(1);
    expect(conn.connected).toBe(false);
  });

  it('turning on notifications that never settles fails too, and a cancel releases it at once', async () => {
    const stuck = fakeAgent(makeBundle(2000), { chunkSize: 100, startNotifications: () => new Promise<void>(() => {}) });
    await expect((await connectBleAgent(BLE, { bluetooth: stuck.bluetooth })).downloadBundle({ stallTimeoutMs: 50 })).rejects.toThrow(/turning on its notifications/);
    const again = fakeAgent(makeBundle(2000), { chunkSize: 100, startNotifications: () => new Promise<void>(() => {}) });
    const ctl = new AbortController();
    const p = (await connectBleAgent(BLE, { bluetooth: again.bluetooth })).downloadBundle({ signal: ctl.signal, stallTimeoutMs: 60_000 });
    await new Promise((r) => setTimeout(r, 5));
    ctl.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('a cancel that leaves notifications turning on gives the link up: the next download reads nothing of this one', async () => {
    let started!: () => void;
    const agent = fakeAgent(makeBundle(2000), { chunkSize: 100, startNotifications: () => new Promise<void>((r) => { started = r; }) });
    const conn = await connectBleAgent(BLE, { bluetooth: agent.bluetooth });
    const ctl = new AbortController();
    const p = conn.downloadBundle({ signal: ctl.signal, stallTimeoutMs: 60_000 });
    await vi.waitFor(() => expect(started).toBeTypeOf('function'));
    ctl.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
    expect(agent.disconnects.n).toBe(1);
    expect(conn.connected).toBe(false);
    started(); // the start settles after the stop: the link is gone, so its frames reach no one
    await expect(conn.downloadBundle({ stallTimeoutMs: 50 })).rejects.toThrow(/dropped/);
    expect(cmds(agent.writes, CTRL_START)).toHaveLength(0);
  });

  it('a connect that times out is called off, so it can\'t finish behind the failure', async () => {
    vi.useFakeTimers();
    try {
      const agent = fakeAgent(makeBundle(2000), { connectGate: () => new Promise<void>(() => {}) });
      const p = connectBleAgent(BLE, { bluetooth: agent.bluetooth });
      const done = expect(p).rejects.toThrow(/didn't answer over Bluetooth/);
      await vi.advanceTimersByTimeAsync(15_000);
      await done;
      expect(agent.disconnects.gatt).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('encodes a resend of 100 indexes in 403 bytes', () => {
    expect(encodeResend(Array.from({ length: 100 }, (_, i) => i)).length).toBe(403);
  });
});

describe('the file fallback', () => {
  it('reads a picked bundle and checks it against the agent', async () => {
    const bundle = makeBundle(9000);
    const progress: TransferProgress[] = [];
    const bytes = await readBundleFromFile(new Blob([bundle]), (p) => progress.push(p));
    expect(bytes).toEqual(bundle);
    expect(progress.at(-1)).toMatchObject({ channel: 'file', receivedBytes: bundle.length });
    const agent = fakeAgent(bundle);
    const meta = await (await connectBleAgent(BLE, { bluetooth: agent.bluetooth })).readMeta();
    const cache = memoryCache();
    await expect(receiveBundleFromFile(new Blob([bundle]), { meta, cache })).resolves.toMatchObject({ channel: 'file' });
    expect(cache.map.size).toBe(1);
    await expect(receiveBundleFromFile(new Blob([makeBundle(10)]), { meta })).rejects.toThrow(/another device/);
  });

  it('turns down the wrong file early, in plain words', async () => {
    await expect(readBundleFromFile(new Blob([new TextEncoder().encode('PK\u0003\u0004 not a bundle')]))).rejects.toThrow(/isn't a device bundle/);
    await expect(readBundleFromFile(new Blob([]))).rejects.toThrow(/empty/);
    await expect(readBundleFromFile(new Blob([makeBundle(100).slice(0, 50)]))).rejects.toThrow(/cut short/);
  });

  it('stops reading at a cancel, and lets the file go', async () => {
    const bundle = makeBundle(9000);
    const pulls = { n: 0, cancelled: false };
    const ctl = new AbortController();
    // a file read in pieces; the cancel comes after the second
    const file = {
      size: bundle.length,
      slice: (a: number, b: number) => new Blob([bundle.slice(a, b)]),
      stream: () => new ReadableStream<Uint8Array>({
        pull(c) {
          pulls.n++;
          if (pulls.n === 2) ctl.abort();
          c.enqueue(bundle.slice((pulls.n - 1) * 1000, pulls.n * 1000));
          if (pulls.n * 1000 >= bundle.length) c.close();
        },
        cancel() { pulls.cancelled = true; },
      }, { highWaterMark: 0 }),
    } as unknown as Blob;
    await expect(readBundleFromFile(file, undefined, ctl.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(pulls.cancelled).toBe(true);
    expect(pulls.n).toBeLessThan(9);
  });

  it('builds the agent\'s page URL', () => {
    expect(buildAgentPageUrl('192.0.2.10', 8080)).toBe('http://192.0.2.10:8080/');
    expect(buildAgentPageUrl('192.0.2.10', 8080, 'get')).toBe('http://192.0.2.10:8080/get');
    expect(buildAgentPageUrl('fe80::1', 80, '/')).toBe('http://[fe80::1]:80/');
  });
});
