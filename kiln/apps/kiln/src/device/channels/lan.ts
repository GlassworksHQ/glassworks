// The local network channel: the bundle straight from the agent's HTTP server, with Chrome's Local Network Access
// (`targetAddressSpace: 'local'`), so a page served over HTTPS may reach a device on the user's network.
//
// Every failure is a LanTransferError with a kind, so the orchestrator can tell "the browser said no" (show a hint)
// from a drop or a timeout (fall back to Bluetooth without a word).
import { isAbortError, type LanErrorKind, type TransferProgress } from './types';

export class LanTransferError extends Error {
  override name = 'LanTransferError';
  constructor(readonly kind: LanErrorKind, message: string, override readonly cause?: unknown) {
    super(message);
  }
}

export interface LanFetchOptions {
  fetchImpl?: typeof fetch;
  signal?: AbortSignal;
  /** How long to wait for the response to start, and (unless `stallTimeoutMs` says otherwise) between chunks. */
  timeoutMs?: number;
  stallTimeoutMs?: number;
  /** The size to report progress against when the response has no Content-Length. */
  expectedBytes?: number;
  onProgress?: (p: TransferProgress) => void;
  /** The local network access permission's state, to tell a refusal from a plain network error. */
  localNetworkPermission?: () => Promise<PermissionState | null>;
  now?: () => number;
}

/** The permission's state, under its current name and the one Chrome used before. null when the browser can't say. */
export async function queryLocalNetworkPermission(): Promise<PermissionState | null> {
  const perms = (globalThis as { navigator?: { permissions?: Permissions } }).navigator?.permissions;
  if (!perms?.query) return null;
  for (const name of ['local-network-access', 'local-network']) {
    try {
      return (await perms.query({ name } as unknown as PermissionDescriptor)).state;
    } catch {
      // not a name this browser knows
    }
  }
  return null;
}

/** A rolling bytes-per-second average over `windowMs`. */
export class RateMeter {
  private samples: [number, number][] = [];
  constructor(private readonly windowMs = 3000) {}
  add(at: number, bytes: number): number {
    this.samples.push([at, bytes]);
    while (this.samples.length > 2 && at - this.samples[0]![0] > this.windowMs) this.samples.shift();
    const [t0, b0] = this.samples[0]!;
    const dt = (at - t0) / 1000;
    return dt > 0 ? (bytes - b0) / dt : 0;
  }
}

/**
 * Whether the agent answers at `url` (its status page) within `timeoutMs`: the computer is on a network that reaches
 * it. Never throws; a refusal, a timeout or a blocked request all read as no.
 */
export async function agentAnswers(url: string, o: { fetchImpl?: typeof fetch; timeoutMs?: number; signal?: AbortSignal } = {}): Promise<boolean> {
  const fetchImpl = o.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), o.timeoutMs ?? 2500);
  const onAbort = () => ctrl.abort();
  o.signal?.addEventListener('abort', onAbort, { once: true });
  try {
    const init = { method: 'GET', mode: 'cors', credentials: 'omit', cache: 'no-store', targetAddressSpace: 'local', signal: ctrl.signal } as RequestInit;
    const res = await fetchImpl(url, init);
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
    o.signal?.removeEventListener('abort', onAbort);
  }
}

export async function fetchBundleOverLan(url: string, opts: LanFetchOptions = {}): Promise<Uint8Array> {
  const fetchImpl = opts.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const timeoutMs = opts.timeoutMs ?? 10_000;
  const stallMs = opts.stallTimeoutMs ?? timeoutMs;
  const now = opts.now ?? (() => Date.now());
  if (opts.signal?.aborted) throw new LanTransferError('aborted', 'The transfer was stopped');

  const ctrl = new AbortController();
  let why: 'timeout' | 'aborted' | null = null;
  const stopped = new Promise<never>((_, reject) => {
    ctrl.signal.addEventListener('abort', () => reject(new LanTransferError(why ?? 'aborted', why === 'timeout' ? 'The device stopped answering on the local network' : 'The transfer was stopped')), { once: true });
  });
  stopped.catch(() => {});
  const onAbort = () => { why ??= 'aborted'; ctrl.abort(); };
  opts.signal?.addEventListener('abort', onAbort, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  const arm = (ms: number) => {
    clearTimeout(timer);
    timer = setTimeout(() => { why ??= 'timeout'; ctrl.abort(); }, ms);
  };

  const classify = async (e: unknown): Promise<LanTransferError> => {
    if (e instanceof LanTransferError) return e;
    if (why) return new LanTransferError(why, why === 'timeout' ? 'The device stopped answering on the local network' : 'The transfer was stopped', e);
    if (isAbortError(e)) return new LanTransferError('aborted', 'The transfer was stopped', e);
    const state = await (opts.localNetworkPermission ?? queryLocalNetworkPermission)().catch(() => null);
    if (state === 'denied') return new LanTransferError('lna-denied', 'This browser isn\'t allowed to reach devices on your local network', e);
    return new LanTransferError('network-error', 'The device couldn\'t be reached on the local network', e);
  };

  try {
    arm(timeoutMs);
    let res: Response;
    try {
      // targetAddressSpace isn't in TypeScript's DOM library yet: Chrome's Local Network Access request field.
      const init = { method: 'GET', mode: 'cors', credentials: 'omit', cache: 'no-store', targetAddressSpace: 'local', signal: ctrl.signal } as RequestInit;
      res = await Promise.race([fetchImpl(url, init), stopped]);
    } catch (e) {
      throw await classify(e);
    }
    if (!res.ok) throw new LanTransferError('http-error', `The device answered HTTP ${res.status}`);

    const declared = Number(res.headers.get('content-length'));
    const total = Number.isFinite(declared) && declared > 0 ? declared : opts.expectedBytes ?? 0;
    const meter = new RateMeter();
    let lastReport = -Infinity;
    const report = (received: number, force = false) => {
      if (!opts.onProgress) return;
      const t = now();
      const rate = meter.add(t, received);
      if (!force && t - lastReport < 200) return;
      lastReport = t;
      opts.onProgress({ channel: 'lan', receivedBytes: received, totalBytes: total || received, bytesPerSecond: rate, etaSeconds: rate > 0 && total ? Math.max(0, total - received) / rate : null });
    };
    report(0, true);

    let bytes: Uint8Array;
    if (!res.body) {
      try {
        bytes = new Uint8Array(await Promise.race([res.arrayBuffer(), stopped]));
      } catch (e) {
        throw await classify(e);
      }
    } else {
      const reader = res.body.getReader();
      const parts: Uint8Array[] = [];
      let received = 0;
      try {
        for (;;) {
          arm(stallMs);
          const { done, value } = await Promise.race([reader.read(), stopped]);
          if (done) break;
          parts.push(value);
          received += value.length;
          report(received);
        }
      } catch (e) {
        reader.cancel().catch(() => {});
        throw await classify(e);
      }
      bytes = new Uint8Array(received);
      let at = 0;
      for (const p of parts) { bytes.set(p, at); at += p.length; }
    }
    if (total && bytes.length !== total) {
      throw new LanTransferError('network-error', `The connection closed after ${bytes.length} of ${total} bytes`);
    }
    report(bytes.length, true);
    return bytes;
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener('abort', onAbort);
  }
}
