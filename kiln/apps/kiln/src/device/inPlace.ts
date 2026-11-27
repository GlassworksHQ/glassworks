// An in-place install's parts outside the session (imageSession.ts runs the steps): where its bundles and restore
// points are kept in this browser, the stage's per-run secret, and taking the bundle from the device's agent over the
// channels (channels/: this browser's copy, the local network, Bluetooth, a file). Nothing here names a frame: the
// agent's ports and UUIDs, the storage's name and every word come from the frame's data (kiln-core InPlaceCode).
//
// The secret signs the bundle the agent sends. It is this browser's (browserStageSecret, in its localStorage), never in
// IndexedDB and never in the log; the Wi-Fi password never reaches this module.
import {
  BackupStore, BundleSignatureError, backupId, namedBackupZip, sha256Hex, verifyGwsbBundle,
  type BackupFile, type BackupMeta, type InPlaceCode, type NamedPart,
} from '@glassworks/kiln-core';
import {
  BundleFormatError, abortError, agentAnswers, buildAgentPageUrl, connectBleAgent, fetchBundleOverLan, isBluetoothAvailable, readBundleFromFile, receiveBundle,
  type AgentMeta, type BleAgentConnection, type BleResumeStore, type BluetoothLike, type BundleCacheAdapter, type FallbackEvent, type TransferProgress,
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
  /**
   * Records that a copy of this restore point went to the computer: `sum`, its restore image's SHA-256. Continue waits
   * for that copy, and a reload that saves the same restore point again doesn't ask for it twice.
   */
  markCopied(serial: string, digest: string, sum: string): Promise<void>;
  /** Whether this browser recorded a downloaded copy of the restore point whose restore image is `sum`. */
  copied(serial: string, digest: string, sum: string): Promise<boolean>;
  /** Asks the browser to keep this site's storage (navigator.storage.persist): whether it will. */
  persist(): Promise<boolean>;
}

interface Kept { serial: string; digest: string; bundle?: Uint8Array; restore?: NamedPart[]; copied?: string; at: number }

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
    async markCopied(s, d, sum) { at(s, d).copied = sum; },
    async copied(s, d, sum) { return kept.get(`${s}\n${d}`)?.copied === sum; },
    async persist() { return true; },
  };
}

const BUNDLE = 'bundle';
/** The part that records a downloaded copy of the restore point: its restore image's SHA-256. Not under `restore/`. */
const COPIED = 'restore-copied';

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
        const { serial, digest } = inPlaceKey(m);
        const files = (await store.files(m)).filter((f) => f.part.startsWith('restore/')).map((f) => ({ filename: f.part.slice('restore/'.length), data: f.data }));
        return { serial, digest, files };
      }
      return undefined;
    },
    markCopied: (s, d, sum) => save(s, d, [{ part: COPIED, data: new TextEncoder().encode(sum) }]),
    async copied(s, d, sum) {
      const got = (await filesOf(await find(s, d))).find((f) => f.part === COPIED);
      return !!got && new TextDecoder().decode(got.data) === sum;
    },
    async persist() { try { return (await navigator.storage?.persist?.()) ?? false; } catch { return false; } },
  };
}

/**
 * The key an idbInPlaceStore record's id stands for (`<serial>-<digest>`): the digest is all that follows the serial, so
 * a restore point the person imported (`restore-<sha>`, imageSession's way back) keeps its prefix and is found again.
 */
export function inPlaceKey(m: { id: string; serial?: string }): { serial: string; digest: string } {
  if (m.serial && m.id.startsWith(`${m.serial}-`)) return { serial: m.serial, digest: m.id.slice(m.serial.length + 1) };
  const at = /^(.*?)-((?:restore-)?[^-]*)$/.exec(m.id);
  return at ? { serial: at[1]!, digest: at[2]! } : { serial: m.serial ?? '', digest: m.id };
}

/** A new secret for the stage: 32 random bytes. */
export function newSecret(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(32));
}

const STAGE_SECRET_KEY = 'kiln:stage-secret';

/**
 * This browser's stage secret: made once and kept in its storage, so a reload, another tab or a later run still checks
 * the agent's signature. A browser that keeps nothing gets a new one each time, and its bundles then come with Kiln's
 * warning ([UnverifiedBundle]).
 */
export function browserStageSecret(given?: Pick<Storage, 'getItem' | 'setItem'>): Uint8Array {
  try {
    // inside the try: a browser that blocks storage throws from the localStorage getter itself
    const storage = given ?? globalThis.localStorage;
    const kept = fromHexString(storage?.getItem(STAGE_SECRET_KEY) ?? '');
    if (kept?.length === 32) return kept;
    const made = newSecret();
    storage?.setItem(STAGE_SECRET_KEY, toHexString(made));
    return made;
  } catch { return newSecret(); }
}

/**
 * A bundle that passed every check but its signature: from this device's serial and whole, but Kiln can't tell its own
 * agent sent it. The person decides ([InPlaceReceiver.acceptUnverified]).
 */
export class UnverifiedBundle extends Error {
  constructor(readonly bytes: Uint8Array, readonly channel: Received['channel'], message: string) { super(message); }
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

/** The device's own Wi-Fi network, by name for this serial, and the agent's addresses on it; null when it opens none. */
export function hotspotUrls(lan: InPlaceCode['channels']['lan'], serial: string | undefined): { ssid: string; bundle: string; status: string; page: string } | null {
  const h = lan?.hotspot;
  if (!lan || !h || !serial) return null;
  const host = h.host.includes(':') && !h.host.startsWith('[') ? `[${h.host}]` : h.host;
  const base = `http://${host}:${lan.port}`;
  const path = (p: string) => (p.startsWith('/') ? p : `/${p}`);
  return { ssid: h.ssid.replaceAll('{serial}', serial), bundle: base + path(lan.path), status: `${base}/status`, page: base + path(lan.pagePath ?? '/') };
}

/** Why a channel couldn't bring the bundle, in the screen's words. */
export function fallbackWords(e: FallbackEvent): string {
  switch (e.reason) {
    case 'lna-denied': return 'The browser blocked the local network, so Kiln switched to Bluetooth.';
    case 'no-address': return 'The device isn\'t on your network, so Kiln is using Bluetooth.';
    case 'timeout': return 'The local network stopped answering, so Kiln switched to Bluetooth.';
    case 'invalid-bundle': return 'What came over the local network wasn\'t complete, so Kiln switched to Bluetooth.';
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
  /**
   * The Bluetooth chunks this run has, by the agent's digest: a try that drops halfway leaves them here, and the next
   * try asks only for the rest. In memory only (a reload starts over). The download drops them when the agent's
   * INFO no longer matches (another chunk size or bundle size), and every check still runs on the whole bundle.
   */
  private readonly kept = new Map<string, { chunkSize: number; bundleSize: number; received: Map<number, Uint8Array> }>();
  private readonly resumeStore: BleResumeStore = {
    load: async (digest) => {
      const k = this.kept.get(digest);
      return k && k.received.size ? { chunkSize: k.chunkSize, bundleSize: k.bundleSize, received: new Map(k.received) } : null;
    },
    saveChunk: async (digest, idx, data) => {
      const size = this.meta?.bundleSize;
      if (!size) return;
      let k = this.kept.get(digest);
      if (!k || k.bundleSize !== size) { k = { chunkSize: 0, bundleSize: size, received: new Map() }; this.kept.set(digest, k); }
      k.chunkSize = Math.max(k.chunkSize, data.length);
      k.received.set(idx, data);
    },
    clear: async (digest) => { this.kept.delete(digest); },
  };
  constructor(private readonly d: ReceiverDeps) {}

  /** Whether this browser can use Bluetooth at all (Web Bluetooth and a radio). */
  bluetoothAvailable(): Promise<boolean> { return isBluetoothAvailable(this.d.bluetooth); }

  /**
   * Inside the click (the browser's Bluetooth chooser): the agent's bundle over the channels, checked. On a device with
   * its own Wi-Fi (`hotspot`) this is the Bluetooth way alone: the person chose it over that network, so Kiln doesn't
   * switch to a network behind their back.
   */
  async receive(o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    const { code } = this.d;
    if (!code.channels.ble && !code.channels.lan) throw new Error('This device declares no way to reach its setup agent: pick the bundle file instead.');
    const enabled = new Set(this.d.channels);
    if (code.channels.lan?.hotspot) enabled.delete('lan');
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
      resumeStore: this.resumeStore,
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
    try {
      return await this.accept(got.bytes, got.channel, o);
    } catch (e) {
      // a whole bundle that failed Kiln's or the frame's checks: none of its chunks are worth resuming from
      if (!(e instanceof UnverifiedBundle)) this.kept.clear();
      throw e;
    }
  }

  /**
   * The bundle straight from the agent on the device's own Wi-Fi (`hotspot`, at a fixed address): no Bluetooth and no
   * description first. Kiln's checks (the bundle's hash, the serial, the signature) and the frame's run as for any other.
   */
  async receiveHotspot(o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    const urls = hotspotUrls(this.d.code.channels.lan, o.serial);
    if (!urls) throw new Error('This device opens no Wi-Fi network of its own.');
    o.onChannel?.('lan');
    const bytes = await fetchBundleOverLan(urls.bundle, {
      fetchImpl: this.d.fetchImpl,
      signal: o.signal,
      timeoutMs: this.d.lanTimeoutMs ?? 8000,
      stallTimeoutMs: 15_000,
      onProgress: (p) => { if (!o.signal?.aborted) o.onProgress?.(p); },
      localNetworkPermission: this.d.localNetworkPermission,
    });
    return this.accept(bytes, 'lan', o);
  }

  /** Whether the agent answers on the device's own Wi-Fi: the computer has joined it. */
  hotspotReachable(serial: string, signal?: AbortSignal): Promise<boolean> {
    const urls = hotspotUrls(this.d.code.channels.lan, serial);
    return urls ? agentAnswers(urls.status, { fetchImpl: this.d.fetchImpl, signal }) : Promise.resolve(false);
  }

  /** A bundle file the person picked (downloaded from the agent's own page), checked the same way. */
  async receiveFile(file: Blob, o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    o.onChannel?.('file');
    const bytes = await readBundleFromFile(file, (p) => { if (!o.signal?.aborted) o.onProgress?.(p); }, o.signal);
    return this.accept(bytes, 'file', o);
  }

  /**
   * Kiln's check, then the frame's, then kept: a bundle that fails either is never stored and never built from. A
   * cancel during any of it (`signal`) stops it there: nothing is kept or handed back.
   */
  private async accept(bytes: Uint8Array, channel: Received['channel'], o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents, unsigned = false): Promise<Received> {
    const check = () => { if (o.signal?.aborted) throw abortError(); };
    check();
    o.onVerifying?.();
    const kept = channel === 'cache';
    const signed = !kept && !unsigned;
    // the agent's description only binds bytes from the same agent's channels: a file is bound by this run's signature
    // and the serial, never by what another attempt (another device's agent, say) described
    const described = channel === 'file' ? undefined : this.meta;
    try {
      // the payload's hash and the serial are checked before the signature: a signature error means they held
      await verifyGwsbBundle(bytes, { ...(signed ? { secret: o.secret } : {}), expectedSerial: o.serial, requireSerial: true, requireHmac: signed });
    } catch (e) {
      if (e instanceof BundleSignatureError) throw new UnverifiedBundle(bytes, channel, e.message);
      throw e;
    }
    const r = await this.d.code.readBundle(bytes, { secret: o.secret, serial: o.serial, ...(kept ? { kept } : {}), ...(unsigned ? { unsigned } : {}) });
    check();
    if (described?.digest && described.digest.toLowerCase() !== r.digest.toLowerCase()) {
      throw new BundleFormatError('This bundle is from another device or an older run. Get it again from the device.');
    }
    if (!kept) await this.d.store.putBundle(o.serial, r.digest, bytes);
    check();
    return { bytes, channel, ...r };
  }

  /** A bundle the person chose to keep past Kiln's signature warning: every other check still runs. */
  acceptUnverified(u: UnverifiedBundle, o: { secret: Uint8Array; serial: string; signal?: AbortSignal } & ReceiveEvents): Promise<Received> {
    return this.accept(u.bytes, u.channel, o, true);
  }

  /** Stops a transfer in flight. */
  async cancel(): Promise<void> {
    // the connection this Cancel stopped: a new attempt that connects while the abort winds down keeps its own
    const c = this.conn;
    if (!c) return;
    if (this.conn === c) this.conn = undefined;
    try { await c.abort(); } catch { /* nothing running */ }
    try { c.disconnect(); } catch { /* gone already */ }
  }
}

/** The restore point's download: its files and a SHA256SUMS, in a folder named for the recipe and the time. */
export async function restorePointZip(prefix: string, files: readonly NamedPart[]): Promise<{ blob: Blob; name: string }> {
  const dir = backupId(prefix);
  return { blob: await namedBackupZip(files, { dir }), name: `${dir}.zip` };
}
