// An in-place install's parts outside the session (imageSession.ts runs the steps): where its bundles and restore
// points are kept in this browser, the stage's per-run secret, and taking the bundle from the device's agent over the
// channels (channels/: this browser's copy, the local network, Bluetooth, a file). Nothing here names a frame: the
// agent's ports and UUIDs, the storage's name and every word come from the frame's data (kiln-core InPlaceCode).
//
// The secret signs the bundle the agent sends for one run. It lives in this tab until the bundle is in, never in
// IndexedDB and never in the log; the Wi-Fi password never reaches this module.
import {
  BackupStore, backupId, namedBackupZip, sha256Hex, verifyGwsbBundle,
  type BackupFile, type BackupMeta, type InPlaceCode, type NamedPart,
} from '@glassworks/kiln-core';
import {
  BundleFormatError, abortError, buildAgentPageUrl, connectBleAgent, isBluetoothAvailable, readBundleFromFile, receiveBundle,
  type AgentMeta, type BleAgentConnection, type BluetoothLike, type BundleCacheAdapter, type FallbackEvent, type TransferProgress,
} from './channels';

/** What Kiln keeps of an in-place install, by the device's serial and the bundle's digest. */
export interface InPlaceStore {
  putBundle(serial: string, digest: string, bytes: Uint8Array): Promise<void>;
  getBundle(serial: string, digest: string): Promise<Uint8Array | undefined>;
  /** The restore point: the restore image, its manifest and what else the frame keeps with it. */
  putRestore(serial: string, digest: string, files: readonly NamedPart[]): Promise<void>;
  getRestore(serial: string, digest: string): Promise<NamedPart[] | undefined>;
  /** The newest restore point kept, for the way back. */
  latestRestore(): Promise<{ serial: string; digest: string; files: NamedPart[] } | undefined>;
  /** Asks the browser to keep this site's storage (navigator.storage.persist): whether it will. */
  persist(): Promise<boolean>;
}

interface Kept { serial: string; digest: string; bundle?: Uint8Array; restore?: NamedPart[]; at: number }

/** An InPlaceStore in memory: demos and tests (one per simulated browser). */
export function memoryInPlaceStore(): InPlaceStore {
  const kept = new Map<string, Kept>();
  let tick = 0;
  const at = (s: string, d: string) => { const k = `${s}\n${d}`; const v = kept.get(k) ?? { serial: s, digest: d, at: 0 }; kept.set(k, v); return v; };
  return {
    async putBundle(s, d, bytes) { Object.assign(at(s, d), { bundle: bytes.slice(), at: ++tick }); },
    async getBundle(s, d) { return kept.get(`${s}\n${d}`)?.bundle?.slice(); },
    async putRestore(s, d, files) { Object.assign(at(s, d), { restore: files.map((f) => ({ filename: f.filename, data: f.data.slice() })), at: ++tick }); },
    async getRestore(s, d) { return kept.get(`${s}\n${d}`)?.restore?.map((f) => ({ ...f, data: f.data.slice() })); },
    async latestRestore() {
      const k = [...kept.values()].filter((x) => x.restore).sort((a, b) => b.at - a.at)[0];
      return k && { serial: k.serial, digest: k.digest, files: k.restore! };
    },
    async persist() { return true; },
  };
}

const BUNDLE = 'bundle';

/**
 * An InPlaceStore in this browser's IndexedDB (`db`: the frame's `storage.inPlaceDb`; never rename it), as kiln-core's
 * BackupStore records: one per device and bundle, its id `<serial>-<digest>`, the bundle and each restore file a part.
 * The serial stays in this browser, as a backup's does.
 */
export function idbInPlaceStore(db: string): InPlaceStore {
  const store = new BackupStore(db);
  const idOf = (serial: string, digest: string) => `${serial}-${digest}`;
  const find = async (serial: string, digest: string) => (await store.list()).find((m) => m.id === idOf(serial, digest));
  const filesOf = async (m: BackupMeta | undefined) => (m ? store.files(m) : []);
  const save = async (serial: string, digest: string, parts: { part: string; data: Uint8Array }[]) => {
    const old = await filesOf(await find(serial, digest));
    const byPart = new Map<string, { part: string; data: Uint8Array }>([...old, ...parts].map((f) => [f.part, f]));
    const files: BackupFile[] = await Promise.all([...byPart.values()].map(async (f) => ({ part: f.part, data: f.data, sha256: await sha256Hex(f.data), crc: 0 })));
    await store.save({ id: idOf(serial, digest), created: new Date().toISOString(), serial, bootWasPatched: false,
      files: files.map((f) => ({ part: f.part, size: f.data.byteLength, sha256: f.sha256 })) }, files);
  };
  return {
    putBundle: (s, d, bytes) => save(s, d, [{ part: BUNDLE, data: bytes }]),
    async getBundle(s, d) { return (await filesOf(await find(s, d))).find((f) => f.part === BUNDLE)?.data; },
    putRestore: (s, d, files) => save(s, d, files.map((f) => ({ part: `restore/${f.filename}`, data: f.data }))),
    async getRestore(s, d) {
      const parts = (await filesOf(await find(s, d))).filter((f) => f.part.startsWith('restore/'));
      return parts.length ? parts.map((f) => ({ filename: f.part.slice('restore/'.length), data: f.data })) : undefined;
    },
    async latestRestore() {
      for (const m of (await store.list()).reverse()) {
        if (!m.files.some((f) => f.part.startsWith('restore/'))) continue;
        const digest = m.id.slice(m.id.lastIndexOf('-') + 1);
        const serial = m.serial ?? m.id.slice(0, m.id.lastIndexOf('-'));
        const files = (await store.files(m)).filter((f) => f.part.startsWith('restore/')).map((f) => ({ filename: f.part.slice('restore/'.length), data: f.data }));
        return { serial, digest, files };
      }
      return undefined;
    },
    async persist() { try { return (await navigator.storage?.persist?.()) ?? false; } catch { return false; } },
  };
}

/** A new per-run secret for the stage: 32 random bytes. */
export function newSecret(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(32));
}

export const toHexString = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
export function fromHexString(h: string): Uint8Array | undefined {
  if (!/^(?:[0-9a-f]{2})+$/.test(h)) return undefined;
  return Uint8Array.from(h.match(/../g)!, (x) => parseInt(x, 16));
}

/** The bundle cache the channels read first: this browser's kept bundles, by the agent's serial and digest. */
export function cacheOf(store: InPlaceStore): BundleCacheAdapter {
  return {
    get: async ({ serial, digest }) => (await store.getBundle(serial, digest)) ?? null,
    // a bundle is kept only once it passes Kiln's and the frame's checks (InPlaceReceiver.accept), never as it arrives
    put: async () => {},
  };
}

/** Why a channel couldn't bring the bundle, in the screen's words. */
export function fallbackWords(e: FallbackEvent): string {
  switch (e.reason) {
    case 'lna-denied': return 'The browser blocked the local network, so Kiln switched to Bluetooth.';
    case 'no-address': return 'The device isn\'t on your network, so Kiln is using Bluetooth.';
    case 'timeout': return 'The local network stopped answering, so Kiln switched to Bluetooth.';
    case 'invalid-bundle': return 'What came over the local network wasn\'t the whole bundle, so Kiln switched to Bluetooth.';
    default: return 'The local network transfer dropped, so Kiln switched to Bluetooth.';
  }
}

export interface Received {
  bytes: Uint8Array;
  channel: 'cache' | 'lan' | 'ble' | 'file';
  digest: string;
  extents: readonly { physical: number; length: number }[];
  info: readonly string[];
}

/** What the receive step reports to its screen. */
export interface ReceiveEvents {
  onMeta?(meta: AgentMeta, pageUrl: string | null): void;
  onChannel?(channel: 'cache' | 'lan' | 'ble' | 'file'): void;
  onProgress?(p: TransferProgress): void;
  onFallback?(e: FallbackEvent): void;
  onVerifying?(): void;
}

export interface ReceiverDeps {
  code: InPlaceCode;
  store: InPlaceStore;
  /** Web Bluetooth (navigator.bluetooth by default). */
  bluetooth?: BluetoothLike;
  /** fetch for the agent's HTTP server (the page's by default). */
  fetchImpl?: typeof fetch;
  localNetworkPermission?: () => Promise<PermissionState | null>;
  /** The recipe's channels, from its `receive` step. */
  channels: readonly ('lan' | 'ble' | 'file')[];
  /** Bluetooth timings (tests and demos shorten them). */
  ble?: { stallTimeoutMs?: number };
  lanTimeoutMs?: number;
}

/**
 * Takes the bundle for one run: over the channels (Bluetooth first, for the agent's description and address; then this
 * browser's copy, the local network, and Bluetooth again for the bytes), or from a file the person picked. Each is
 * checked by Kiln (verifyGwsbBundle: SHA-256, serial, signature) and by the frame (its readBundle, with its stop-gates)
 * before it is kept; nothing is written to the device here. A new run gets a new receiver: the agent's description
 * this one read belongs to its run.
 */
export class InPlaceReceiver {
  private meta?: AgentMeta;
  private conn?: BleAgentConnection;
  constructor(private readonly d: ReceiverDeps) {}

  /** Whether this browser can use Bluetooth at all (Web Bluetooth and a radio). */
  bluetoothAvailable(): Promise<boolean> { return isBluetoothAvailable(this.d.bluetooth); }

  /** Inside the click (the browser's Bluetooth chooser): the agent's bundle over the channels, checked. */
  async receive(o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    const { code } = this.d;
    if (!code.channels.ble && !code.channels.lan) throw new Error('This device declares no way to reach its setup agent: pick the bundle file instead.');
    const enabled = new Set(this.d.channels);
    const self = this;
    const got = await receiveBundle({
      config: code.channels,
      connect: async () => {
        if (!code.channels.ble) throw new BundleFormatError('This device has no Bluetooth channel');
        const c = await connectBleAgent(code.channels.ble, { bluetooth: this.d.bluetooth, signal: o.signal });
        self.conn = c;
        // the agent's description: its address for the local network, and its own page for a download by hand
        const wrapped: BleAgentConnection = {
          get device() { return c.device; },
          get connected() { return c.connected; },
          readMeta: async () => {
            const m = await c.readMeta();
            self.meta = m;
            const lan = code.channels.lan;
            o.onMeta?.(m, m.lanIp && lan ? buildAgentPageUrl(m.lanIp, m.httpPort ?? lan.port, lan.pagePath ?? '/') : null);
            return m;
          },
          downloadBundle: (x) => c.downloadBundle(x),
          abort: () => c.abort(),
          disconnect: () => c.disconnect(),
        };
        return wrapped;
      },
      bluetooth: this.d.bluetooth,
      channels: ['cache', ...(enabled.has('lan') ? ['lan' as const] : []), ...(enabled.has('ble') ? ['ble' as const] : [])],
      cache: cacheOf(this.d.store),
      // a kept bundle is built from only while the frame's code says it still fits the device (where its file sits now)
      verifyKept: (bytes, meta) => !!code.keptMatches?.(bytes, meta.raw),
      fetchImpl: this.d.fetchImpl,
      localNetworkPermission: this.d.localNetworkPermission,
      ...(this.d.lanTimeoutMs ? { lanTimeoutMs: this.d.lanTimeoutMs } : {}),
      ...(this.d.ble ? { ble: this.d.ble } : {}),
      signal: o.signal,
      onChannel: (c) => o.onChannel?.(c),
      onProgress: (p) => o.onProgress?.(p),
      onFallback: (e) => o.onFallback?.(e),
    });
    try { this.conn?.disconnect(); } catch { /* gone already */ }
    return this.accept(got.bytes, got.channel, o);
  }

  /** A bundle file the person picked (downloaded from the agent's own page), checked the same way. */
  async receiveFile(file: Blob, o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    o.onChannel?.('file');
    const bytes = await readBundleFromFile(file, (p) => { if (!o.signal?.aborted) o.onProgress?.(p); });
    return this.accept(bytes, 'file', o);
  }

  /**
   * Kiln's check, then the frame's, then kept: a bundle that fails either is never stored and never built from. A
   * cancel during any of it (`signal`) stops it there: nothing is kept or handed back.
   */
  private async accept(bytes: Uint8Array, channel: Received['channel'], o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    const check = () => { if (o.signal?.aborted) throw abortError(); };
    check();
    o.onVerifying?.();
    const kept = channel === 'cache';
    await verifyGwsbBundle(bytes, { ...(kept ? {} : { secret: o.secret }), expectedSerial: o.serial, requireSerial: true, requireHmac: !kept });
    const r = await this.d.code.readBundle(bytes, { secret: o.secret, serial: o.serial, ...(kept ? { kept } : {}) });
    check();
    if (this.meta?.digest && this.meta.digest.toLowerCase() !== r.digest.toLowerCase()) {
      throw new BundleFormatError('This bundle is from another device or an older run. Get it again from the device.');
    }
    if (!kept) await this.d.store.putBundle(o.serial, r.digest, bytes);
    check();
    return { bytes, channel, ...r };
  }

  /** Stops a transfer in flight. */
  async cancel(): Promise<void> {
    try { await this.conn?.abort(); } catch { /* nothing running */ }
    try { this.conn?.disconnect(); } catch { /* gone already */ }
  }
}

/** The restore point's download: its files and a SHA256SUMS, in a folder named for the recipe and the time. */
export async function restorePointZip(prefix: string, files: readonly NamedPart[]): Promise<{ blob: Blob; name: string }> {
  const dir = backupId(prefix);
  return { blob: await namedBackupZip(files, { dir }), name: `${dir}.zip` };
}
