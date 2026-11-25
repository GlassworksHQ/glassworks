// Which channel brings the bundle: this browser's cache when it already holds this device's bundle, the local network
// when the agent has an address there, Bluetooth when the network can't reach it, and a file the user picks at any
// time. Bluetooth is the connection the metadata came over, so falling back to it needs no new chooser.
import { BleTransferError, connectBleAgent, type BleAgentConnection, type BleDownloadOptions } from './ble';
import { BundleFormatError, bundleMatches, readBundleFromFile } from './file';
import { LanTransferError, fetchBundleOverLan } from './lan';
import { abortError, type AgentMeta, type BleResumeStore, type BluetoothLike, type BundleCacheAdapter, type ChannelConfig, type ChannelKind, type LanErrorKind, type TransferProgress } from './types';

/** Why the local network was passed over. */
export type FallbackReason = LanErrorKind | 'no-address' | 'invalid-bundle' | 'disabled';

export interface FallbackEvent {
  from: 'lan';
  to: 'ble';
  reason: FallbackReason;
  error?: unknown;
}

export interface ReceivedBundle {
  bytes: Uint8Array;
  channel: ChannelKind;
  meta?: AgentMeta;
}

export interface ReceiveOptions {
  config: ChannelConfig;
  /** A connection already open, or how to open one (by default the browser's chooser for config.ble). */
  connection?: BleAgentConnection;
  connect?: () => Promise<BleAgentConnection>;
  bluetooth?: BluetoothLike;
  /** The channels to try, in their fixed order cache → lan → ble. Default all three. */
  channels?: readonly ChannelKind[];
  cache?: BundleCacheAdapter;
  resumeStore?: BleResumeStore;
  fetchImpl?: typeof fetch;
  lanTimeoutMs?: number;
  lanStallTimeoutMs?: number;
  localNetworkPermission?: () => Promise<PermissionState | null>;
  /** Bluetooth download tuning (stall timeout, resend passes). */
  ble?: Omit<BleDownloadOptions, 'signal' | 'onProgress' | 'resumeStore' | 'digest'>;
  signal?: AbortSignal;
  onProgress?: (p: TransferProgress) => void;
  onChannel?: (channel: ChannelKind) => void;
  onFallback?: (e: FallbackEvent) => void;
  /** Whether bytes are the bundle the agent describes. Default: a whole bundle of its size and digest. */
  verify?: (bytes: Uint8Array, meta: AgentMeta) => boolean | Promise<boolean>;
  /**
   * Whether a bundle the cache kept still describes the device as its agent does now, past `verify` (where the file
   * sits on the partition, say: the same contents can move). Default: `verify` alone.
   */
  verifyKept?: (bytes: Uint8Array, meta: AgentMeta) => boolean | Promise<boolean>;
}

/** The URL of the bundle on the agent's HTTP server. */
export function lanBundleUrl(meta: AgentMeta, lan: NonNullable<ChannelConfig['lan']>): string | null {
  if (!meta.lanIp) return null;
  const host = meta.lanIp.includes(':') && !meta.lanIp.startsWith('[') ? `[${meta.lanIp}]` : meta.lanIp;
  const path = lan.path.startsWith('/') ? lan.path : `/${lan.path}`;
  return `http://${host}:${meta.httpPort ?? lan.port}${path}`;
}

const cacheKey = (meta: AgentMeta) => (meta.serialno && meta.digest ? { serial: meta.serialno, digest: meta.digest } : null);

async function remember(cache: BundleCacheAdapter | undefined, meta: AgentMeta, bytes: Uint8Array): Promise<void> {
  const key = cacheKey(meta);
  if (cache && key) await cache.put(key, bytes).catch(() => {});
}

export async function receiveBundle(opts: ReceiveOptions): Promise<ReceivedBundle> {
  const enabled = new Set(opts.channels ?? ['cache', 'lan', 'ble']);
  const verify = opts.verify ?? ((bytes, meta) => bundleMatches(bytes, meta));
  const verifyKept = opts.verifyKept ?? (() => true);
  // every wait below can outlast a cancel: nothing is handed back once the signal is aborted
  const check = () => { if (opts.signal?.aborted) throw abortError(); };

  let conn = opts.connection;
  if (!conn) {
    if (opts.connect) conn = await opts.connect();
    else if (opts.config.ble) conn = await connectBleAgent(opts.config.ble, { bluetooth: opts.bluetooth, signal: opts.signal });
    else throw new BleTransferError('No way to reach the device\'s agent: this device declares no Bluetooth channel');
  }
  check();
  const meta = await conn.readMeta();
  check();

  // 1. this browser's cache
  const key = cacheKey(meta);
  if (enabled.has('cache') && opts.cache && key) {
    const hit = await opts.cache.get(key).catch(() => null);
    check();
    const fits = !!hit && (await verify(hit, meta)) && (await verifyKept(hit, meta));
    check();
    if (hit && fits) {
      opts.onChannel?.('cache');
      opts.onProgress?.({ channel: 'cache', receivedBytes: hit.length, totalBytes: hit.length, bytesPerSecond: 0, etaSeconds: 0 });
      return { bytes: hit, channel: 'cache', meta };
    }
  }

  // 2. the local network
  let reason: FallbackReason = 'disabled';
  let lanError: unknown;
  const url = enabled.has('lan') && opts.config.lan ? lanBundleUrl(meta, opts.config.lan) : null;
  if (enabled.has('lan') && opts.config.lan && !url) reason = 'no-address';
  if (url) {
    opts.onChannel?.('lan');
    try {
      const bytes = await fetchBundleOverLan(url, {
        fetchImpl: opts.fetchImpl,
        signal: opts.signal,
        timeoutMs: opts.lanTimeoutMs,
        stallTimeoutMs: opts.lanStallTimeoutMs,
        expectedBytes: meta.bundleSize,
        onProgress: opts.onProgress,
        localNetworkPermission: opts.localNetworkPermission,
      });
      const ok = await verify(bytes, meta);
      check();
      if (ok) {
        await remember(opts.cache, meta, bytes);
        check();
        return { bytes, channel: 'lan', meta };
      }
      reason = 'invalid-bundle';
      lanError = new BundleFormatError('The bundle from the local network isn\'t the one the device described');
    } catch (e) {
      if (opts.signal?.aborted || (e instanceof LanTransferError && e.kind === 'aborted')) throw e;
      reason = e instanceof LanTransferError ? e.kind : 'network-error';
      lanError = e;
    }
  }

  // 3. Bluetooth, over the connection the metadata came on
  if (!enabled.has('ble')) {
    throw lanError ?? new LanTransferError('network-error', 'No channel could bring the bundle');
  }
  if (url) opts.onFallback?.({ from: 'lan', to: 'ble', reason, error: lanError });
  opts.onChannel?.('ble');
  const bytes = await conn.downloadBundle({
    ...opts.ble,
    signal: opts.signal,
    onProgress: opts.onProgress,
    resumeStore: meta.digest ? opts.resumeStore : undefined,
    digest: meta.digest,
  });
  const ok = await verify(bytes, meta);
  check();
  if (!ok) throw new BundleFormatError('The bundle that came over Bluetooth isn\'t the one the device described');
  await remember(opts.cache, meta, bytes);
  check();
  return { bytes, channel: 'ble', meta };
}

/**
 * A bundle the user picked, the fallback the page offers at any time. With the agent's metadata it must be that
 * device's bundle, and it is kept in the cache like one that came over a channel.
 */
export async function receiveBundleFromFile(
  file: Blob,
  opts: { meta?: AgentMeta; cache?: BundleCacheAdapter; onProgress?: (p: TransferProgress) => void; verify?: ReceiveOptions['verify'] } = {},
): Promise<ReceivedBundle> {
  const bytes = await readBundleFromFile(file, opts.onProgress);
  if (opts.meta) {
    const ok = await (opts.verify ?? ((b, m) => bundleMatches(b, m)))(bytes, opts.meta);
    if (!ok) throw new BundleFormatError('This bundle is from another device or an older run. Download it again from the device\'s page.');
    await remember(opts.cache, opts.meta, bytes);
  }
  return { bytes, channel: 'file', meta: opts.meta };
}
