// The Bluetooth channel: the bundle from the on-device agent over a GATT service, for when the local network can't
// reach it. The UUIDs come from the frame's data (BleChannelConfig).
//
// The agent's profile:
//   META (read, long read up to 512 B): UTF-8 JSON about the agent and its bundle.
//   CTRL (write):  0x01 + uint32 LE startChunk + uint32 LE count (0 = to the end): stream chunks from startChunk.
//                  0x02 + uint16 LE n + n × uint32 LE index: resend those chunks (chunk size of the latest 0x01).
//                  0x03: stop the stream.
//   DATA (notify): uint32 LE idx first.
//                  0xFFFFFFFF INFO: uint32 LE chunkSize, bundleSize, totalChunks. Trusted over META (the MTU may
//                  have changed since it was read).
//                  0xFFFFFFFE END: uint32 LE sentCount, the end of one 0x01 or 0x02 batch.
//                  idx < totalChunks: the bundle's bytes at idx × chunkSize.
import { RateMeter } from './lan';
import {
  abortError,
  findDigest,
  isAbortError,
  type AgentMeta,
  type BleChannelConfig,
  type BleCharacteristicLike,
  type BleDeviceLike,
  type BleResumeStore,
  type BleServerLike,
  type BluetoothLike,
  type TransferProgress,
} from './types';

export const CTRL_START = 0x01;
export const CTRL_RESEND = 0x02;
export const CTRL_ABORT = 0x03;
export const FRAME_INFO = 0xffffffff;
export const FRAME_END = 0xfffffffe;
/** Indexes per 0x02 write: 3 + 100 × 4 = 403 bytes, under the 512-byte attribute limit. */
export const MAX_RESEND_BATCH = 100;

export class BleTransferError extends Error {
  override name = 'BleTransferError';
  constructor(message: string, override readonly cause?: unknown) {
    super(message);
  }
}

export function encodeStart(startChunk: number, count = 0): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(9);
  const v = new DataView(out.buffer);
  v.setUint8(0, CTRL_START);
  v.setUint32(1, startChunk, true);
  v.setUint32(5, count, true);
  return out;
}

export function encodeResend(indexes: readonly number[]): Uint8Array<ArrayBuffer> {
  if (indexes.length === 0 || indexes.length > 0xffff) throw new RangeError(`A resend asks for 1 to 65535 chunks, not ${indexes.length}`);
  const out = new Uint8Array(3 + indexes.length * 4);
  const v = new DataView(out.buffer);
  v.setUint8(0, CTRL_RESEND);
  v.setUint16(1, indexes.length, true);
  indexes.forEach((idx, i) => v.setUint32(3 + i * 4, idx, true));
  return out;
}

export const encodeAbort = (): Uint8Array<ArrayBuffer> => Uint8Array.of(CTRL_ABORT);

export type DataFrame =
  | { kind: 'info'; chunkSize: number; bundleSize: number; totalChunks: number }
  | { kind: 'end'; sent: number }
  | { kind: 'chunk'; idx: number; bytes: Uint8Array };

/** One DATA notification, or null when it's too short to be one. Chunk bytes are a view of `view`'s buffer. */
export function parseDataFrame(view: DataView): DataFrame | null {
  if (view.byteLength < 4) return null;
  const idx = view.getUint32(0, true);
  if (idx === FRAME_INFO) {
    if (view.byteLength < 16) return null;
    return { kind: 'info', chunkSize: view.getUint32(4, true), bundleSize: view.getUint32(8, true), totalChunks: view.getUint32(12, true) };
  }
  if (idx === FRAME_END) return { kind: 'end', sent: view.byteLength >= 8 ? view.getUint32(4, true) : 0 };
  return { kind: 'chunk', idx, bytes: new Uint8Array(view.buffer, view.byteOffset + 4, view.byteLength - 4) };
}

const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined);

/** The META characteristic's JSON. Throws BleTransferError when it isn't JSON. */
export function parseAgentMeta(bytes: Uint8Array): AgentMeta {
  let raw: unknown;
  try {
    raw = JSON.parse(new TextDecoder().decode(bytes).replace(/\0+$/, ''));
  } catch (e) {
    throw new BleTransferError('The device\'s agent sent metadata Kiln can\'t read', e);
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new BleTransferError('The device\'s agent sent metadata Kiln can\'t read');
  const r = raw as Record<string, unknown>;
  return {
    lanIp: typeof r.lanIp === 'string' && r.lanIp ? r.lanIp : null,
    httpPort: num(r.httpPort),
    bundleSize: num(r.bundleSize),
    chunkSize: num(r.chunkSize),
    digest: findDigest(r),
    serialno: typeof r.serialno === 'string' && r.serialno ? r.serialno : null,
    raw: r,
  };
}

function globalBluetooth(): BluetoothLike | undefined {
  return (globalThis as { navigator?: { bluetooth?: BluetoothLike } }).navigator?.bluetooth;
}

/** Whether this browser has Web Bluetooth and a radio to use it with. */
export async function isBluetoothAvailable(bt: BluetoothLike | undefined = globalBluetooth()): Promise<boolean> {
  if (!bt) return false;
  if (!bt.getAvailability) return true;
  try {
    return await bt.getAvailability();
  } catch {
    return false;
  }
}

export interface BleDownloadOptions {
  signal?: AbortSignal;
  onProgress?: (p: TransferProgress) => void;
  /** No frame for this long ends a batch as a stall. Default 15 s. */
  stallTimeoutMs?: number;
  /** Resend passes after the first stream. Default 5. */
  maxResendRounds?: number;
  /** Indexes per 0x02 write, at most 100. */
  resendBatch?: number;
  /** Keeps chunks as they arrive, under `digest`, and loads them to resume. */
  resumeStore?: BleResumeStore;
  digest?: string;
  /** Chunks already in hand, when there's no store. */
  initialChunks?: { chunkSize: number; bundleSize?: number; chunks: Map<number, Uint8Array> };
  /** The least time between two progress reports. Default 200 ms. */
  progressIntervalMs?: number;
  now?: () => number;
}

export interface BleAgentConnection {
  readonly device: BleDeviceLike;
  readonly connected: boolean;
  readMeta(): Promise<AgentMeta>;
  downloadBundle(opts?: BleDownloadOptions): Promise<Uint8Array>;
  /** Stops the stream (0x03) and fails a running download with an AbortError. */
  abort(): Promise<void>;
  disconnect(): void;
}

export interface BleConnectOptions {
  bluetooth?: BluetoothLike;
  signal?: AbortSignal;
}

/** Asks the user to pick the device (the browser's chooser, filtered to the agent's service) and connects to it. */
export async function connectBleAgent(config: BleChannelConfig, opts: BleConnectOptions = {}): Promise<BleAgentConnection> {
  const bt = opts.bluetooth ?? globalBluetooth();
  if (!bt) throw new BleTransferError('This browser has no Bluetooth. Use Chrome or Edge on a computer with Bluetooth.');
  const uuid = (u: string) => u.toLowerCase();
  const check = () => { if (opts.signal?.aborted) throw abortError(); };
  check();
  const device = await bt.requestDevice({ filters: [{ services: [uuid(config.service)] }], optionalServices: [uuid(config.service)] });
  check();
  if (!device.gatt) throw new BleTransferError('This device has no Bluetooth services to connect to');
  const server = await device.gatt.connect();
  try {
    check();
    const service = await server.getPrimaryService(uuid(config.service));
    const [meta, ctrl, data] = await Promise.all([config.meta, config.ctrl, config.data].map((u) => service.getCharacteristic(uuid(u))));
    check();
    return createBleAgentConnection(device, server, { meta: meta!, ctrl: ctrl!, data: data! });
  } catch (e) {
    server.disconnect();
    throw e;
  }
}

/** A connection over characteristics already in hand (connectBleAgent's second half). */
export function createBleAgentConnection(
  device: BleDeviceLike,
  server: BleServerLike,
  chars: { meta: BleCharacteristicLike; ctrl: BleCharacteristicLike; data: BleCharacteristicLike },
): BleAgentConnection {
  let writes: Promise<unknown> = Promise.resolve();
  /** CTRL writes one at a time: Web Bluetooth refuses a second GATT operation while one runs. */
  const ctrlWrite = (bytes: Uint8Array<ArrayBuffer>): Promise<void> => {
    const c = chars.ctrl;
    const run = () => (c.writeValueWithResponse ? c.writeValueWithResponse(bytes) : c.writeValue ? c.writeValue(bytes) : c.writeValueWithoutResponse!(bytes));
    const next = writes.then(run, run);
    writes = next.catch(() => {});
    return next;
  };

  let active: { fail(e: Error): void } | null = null;
  let lost = false;
  const onDisconnected = () => {
    lost = true;
    active?.fail(new BleTransferError('The Bluetooth connection to the device dropped'));
  };
  device.addEventListener?.('gattserverdisconnected', onDisconnected);

  async function downloadBundle(o: BleDownloadOptions = {}): Promise<Uint8Array> {
    if (active) throw new BleTransferError('A transfer is already running on this connection');
    if (o.signal?.aborted) throw abortError();
    const stallMs = o.stallTimeoutMs ?? 15_000;
    const maxRounds = o.maxResendRounds ?? 5;
    const batchSize = Math.max(1, Math.min(o.resendBatch ?? MAX_RESEND_BATCH, MAX_RESEND_BATCH));
    const progressMs = o.progressIntervalMs ?? 200;
    const now = o.now ?? (() => Date.now());
    const store = o.resumeStore && o.digest ? { s: o.resumeStore, digest: o.digest } : null;

    let prior: { chunkSize: number; bundleSize?: number; received: Map<number, Uint8Array> } | null = null;
    if (store) prior = await store.s.load(store.digest).catch(() => null);
    if (!prior && o.initialChunks) prior = { chunkSize: o.initialChunks.chunkSize, bundleSize: o.initialChunks.bundleSize, received: o.initialChunks.chunks };
    let startChunk = 0;
    if (prior) while (prior.received.has(startChunk)) startChunk++;

    let chunkSize = 0;
    let bundleSize = 0;
    let total = 0;
    let buf: Uint8Array | null = null;
    let bitmap: Uint8Array | null = null;
    let receivedCount = 0;
    let receivedBytes = 0;
    let sessionBytes = 0;
    let highest = -1;
    let resendPass = 0;
    const pending = new Map<number, Uint8Array>();
    let endSeen = false;
    let lastFrameAt = now();
    let failure: Error | null = null;
    let wake: (() => void) | null = null;
    const poke = () => { const w = wake; wake = null; w?.(); };
    const complete = () => buf !== null && receivedCount === total;

    const meter = new RateMeter();
    let lastReport = -Infinity;
    const report = (force = false) => {
      if (!o.onProgress) return;
      const t = now();
      const rate = meter.add(t, sessionBytes);
      if (!force && t - lastReport < progressMs) return;
      lastReport = t;
      o.onProgress({
        channel: 'ble',
        receivedBytes,
        totalBytes: bundleSize,
        bytesPerSecond: rate,
        etaSeconds: rate > 0 ? Math.max(0, bundleSize - receivedBytes) / rate : null,
        ...(resendPass ? { resendPass } : {}),
      });
    };

    const store1 = (idx: number, data: Uint8Array, persist: boolean): number => {
      if (!buf || !bitmap || idx >= total || bitmap[idx]) return 0;
      const len = Math.min(chunkSize, bundleSize - idx * chunkSize);
      if (len <= 0 || data.length < len) return 0; // a short chunk is a corrupt one: leave it missing
      buf.set(data.subarray(0, len), idx * chunkSize);
      bitmap[idx] = 1;
      receivedCount++;
      receivedBytes += len;
      if (idx > highest) highest = idx;
      if (persist && store) store.s.saveChunk(store.digest, idx, buf.slice(idx * chunkSize, idx * chunkSize + len)).catch(() => {});
      return len;
    };

    const applyInfo = (cs: number, bs: number, tc: number) => {
      if (buf && cs === chunkSize && bs === bundleSize && tc === total) return;
      if (cs <= 0 || tc !== Math.ceil(bs / cs)) {
        failure ??= new BleTransferError(`The device's agent described a bundle Kiln can't follow (chunk ${cs}, size ${bs}, ${tc} chunks)`);
        poke();
        return;
      }
      chunkSize = cs; bundleSize = bs; total = tc;
      buf = new Uint8Array(bs);
      bitmap = new Uint8Array(tc);
      receivedCount = 0; receivedBytes = 0; highest = -1;
      if (prior && prior.chunkSize === cs && (prior.bundleSize === undefined || prior.bundleSize === bs)) {
        for (const [i, d] of prior.received) store1(i, d, false);
      } else if (prior && prior.received.size && store) {
        // kept with another chunk size: those offsets don't line up any more; the resend passes fetch them again
        store.s.clear(store.digest).catch(() => {});
      }
      prior = null;
      for (const [i, d] of pending) store1(i, d, true);
      pending.clear();
      report(true);
    };

    const onFrame = (ev: Event) => {
      const view = (ev.target as { value?: DataView | null } | null)?.value;
      if (!view) return;
      const f = parseDataFrame(view);
      if (!f) return;
      lastFrameAt = now();
      if (f.kind === 'info') applyInfo(f.chunkSize, f.bundleSize, f.totalChunks);
      else if (f.kind === 'end') { endSeen = true; poke(); }
      else if (!buf) pending.set(f.idx, f.bytes.slice());
      else {
        const got = store1(f.idx, f.bytes, true);
        if (got) { sessionBytes += got; report(); }
        if (complete()) poke();
      }
    };

    /** Until this batch's END, a stall, or every chunk. */
    const waitBatch = () => new Promise<'end' | 'stall' | 'complete'>((resolve, reject) => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      const check = () => {
        clearTimeout(timer);
        if (failure) return reject(failure);
        if (complete()) return resolve('complete');
        if (endSeen) return resolve('end');
        const left = lastFrameAt + stallMs - now();
        if (left <= 0) return resolve('stall');
        wake = check;
        timer = setTimeout(check, left);
      };
      check();
    });

    const send = async (bytes: Uint8Array<ArrayBuffer>) => {
      endSeen = false;
      lastFrameAt = now();
      await ctrlWrite(bytes);
      lastFrameAt = now();
    };

    const onAbort = () => { failure ??= abortError(); poke(); };
    o.signal?.addEventListener('abort', onAbort, { once: true });
    active = { fail: (e) => { failure ??= e; poke(); } };
    if (lost) failure = new BleTransferError('The Bluetooth connection to the device dropped');
    chars.data.addEventListener('characteristicvaluechanged', onFrame);
    try {
      if (failure) throw failure;
      await chars.data.startNotifications();
      await send(encodeStart(startChunk, 0));
      let outcome = await waitBatch();
      let rounds = 0;
      while (!complete()) {
        if (rounds >= maxRounds) {
          const missing = buf ? total - receivedCount : 'all';
          throw new BleTransferError(`${missing} chunks were still missing after ${rounds} resend passes`);
        }
        rounds++;
        resendPass = rounds;
        if (!buf) {
          // no INFO yet: the start never took; ask again
          await send(encodeStart(startChunk, 0));
          outcome = await waitBatch();
          continue;
        }
        if (outcome === 'stall' && highest + 1 < total) {
          // the stream stopped short: stream the tail again, and the next pass fills the gaps before it
          await send(encodeStart(highest + 1, 0));
          outcome = await waitBatch();
          continue;
        }
        const missing: number[] = [];
        for (let i = 0; i < total; i++) if (!bitmap![i]) missing.push(i);
        for (let i = 0; i < missing.length && !complete(); i += batchSize) {
          await send(encodeResend(missing.slice(i, i + batchSize)));
          outcome = await waitBatch();
        }
      }
      report(true);
      if (store) await store.s.clear(store.digest).catch(() => {});
      return buf!;
    } catch (e) {
      if (isAbortError(e) || o.signal?.aborted) {
        await ctrlWrite(encodeAbort()).catch(() => {});
        throw isAbortError(e) ? e : abortError();
      }
      if (!(e instanceof BleTransferError)) throw new BleTransferError('The Bluetooth transfer failed', e);
      throw e;
    } finally {
      chars.data.removeEventListener('characteristicvaluechanged', onFrame);
      o.signal?.removeEventListener('abort', onAbort);
      active = null;
      wake = null;
      if (!lost) await chars.data.stopNotifications().catch(() => {});
    }
  }

  return {
    device,
    get connected() { return !lost && server.connected; },
    async readMeta() {
      const v = await chars.meta.readValue();
      return parseAgentMeta(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
    },
    downloadBundle,
    async abort() {
      if (active) active.fail(abortError());
      else await ctrlWrite(encodeAbort()).catch(() => {});
    },
    disconnect() {
      device.removeEventListener?.('gattserverdisconnected', onDisconnected);
      active?.fail(new BleTransferError('The Bluetooth connection was closed'));
      lost = true;
      server.disconnect();
    },
  };
}
