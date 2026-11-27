// The bundle a frame's agent reads off a running device for an in-place install (images.ts `receive`), and how Kiln
// takes it: its wire format (GWSB), its checks, the channels it comes over, and where it is kept between visits.
//
// Wire format, all lengths little-endian:
//   0..4    "GWSB"
//   4..8    uint32 metaLen
//   8..12   uint32 payloadLen
//   12..    metaLen bytes of UTF-8 JSON (an object), then payloadLen bytes of payload, and nothing after.
// The agent signs it with the stage's secret: `hmacSha256`, the last field of the JSON, is HMAC-SHA256 over the JSON
// up to that field (without the field's comma and without the closing brace), followed by the payload.

const MAGIC = [0x47, 0x57, 0x53, 0x42]; // "GWSB"
const HEADER = 12;

export interface ParsedGwsbBundle {
  meta: Record<string, unknown>;
  /** The JSON exactly as it came: what the HMAC is over (less its own field). */
  rawMetaBytes: Uint8Array;
  payload: Uint8Array;
}

export interface VerifyGwsbOptions {
  /** The device's serial: the bundle's `serialno` must be it. */
  expectedSerial?: string;
  /** Refuse a bundle with no `serialno` (with `expectedSerial`). */
  requireSerial?: boolean;
  /** The stage's secret: checks `hmacSha256` when the bundle has one. */
  secret?: Uint8Array;
  /** Refuse a bundle with no `hmacSha256`. */
  requireHmac?: boolean;
  /** The meta field holding the payload's SHA-256 (hex). Default `servicesSha256`. */
  payloadHashField?: string;
  /** Refuse a bundle without that field. */
  requirePayloadHash?: boolean;
}

/** Splits a GWSB bundle into its meta and payload. Throws unless it is exactly one whole bundle. */
export function parseGwsbBundle(bytes: Uint8Array): ParsedGwsbBundle {
  if (bytes.byteLength < HEADER) throw new Error('bundle: shorter than its header');
  if (MAGIC.some((b, i) => bytes[i] !== b)) throw new Error('bundle: not a GWSB bundle (wrong magic)');
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const metaLen = v.getUint32(4, true), payloadLen = v.getUint32(8, true);
  const want = HEADER + metaLen + payloadLen;
  if (bytes.byteLength < want) throw new Error(`bundle: truncated (${bytes.byteLength} of ${want} bytes)`);
  if (bytes.byteLength > want) throw new Error(`bundle: ${bytes.byteLength - want} bytes after its payload`);
  const rawMetaBytes = bytes.slice(HEADER, HEADER + metaLen);
  let meta: unknown;
  try { meta = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(rawMetaBytes)); } catch { throw new Error('bundle: its meta isn\'t UTF-8 JSON'); }
  if (!meta || typeof meta !== 'object' || Array.isArray(meta)) throw new Error('bundle: its meta isn\'t a JSON object');
  return { meta: meta as Record<string, unknown>, rawMetaBytes, payload: bytes.slice(HEADER + metaLen, want) };
}

const hex = (b: ArrayBuffer) => Array.from(new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');
const buf = (b: Uint8Array) => b as Uint8Array<ArrayBuffer>;

/** SHA-256 of `bytes`, lower-case hex. */
export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  return hex(await crypto.subtle.digest('SHA-256', buf(bytes)));
}

/** Equal strings, compared without stopping at the first difference. */
function sameInConstantTime(a: string, b: string): boolean {
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a.charCodeAt(i) | 0) ^ (b.charCodeAt(i) | 0);
  return diff === 0;
}

/** HMAC-SHA256 of `parts` joined, with `secret`, lower-case hex. */
export async function hmacSha256Hex(secret: Uint8Array, ...parts: Uint8Array[]): Promise<string> {
  const key = await crypto.subtle.importKey('raw', buf(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const all = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
  let o = 0;
  for (const p of parts) { all.set(p, o); o += p.byteLength; }
  return hex(await crypto.subtle.sign('HMAC', key, all));
}

/**
 * A bundle whose `hmacSha256` doesn't match the stage's secret: everything else may hold, but Kiln can't tell that its
 * own agent sent it (another browser's or an earlier run's stage, or this browser's storage cleared since).
 */
export class BundleSignatureError extends Error {}

const SIGNED_TAIL = /,"hmacSha256":"([0-9a-f]{64})"\}$/;

/**
 * Parses and checks a bundle: its payload against its SHA-256, its serial against the device's, and its HMAC with the
 * stage's secret. Throws, saying which, on the first that fails.
 */
export async function verifyGwsbBundle(bytes: Uint8Array, opts: VerifyGwsbOptions = {}): Promise<ParsedGwsbBundle> {
  const parsed = parseGwsbBundle(bytes);
  const { meta, rawMetaBytes, payload } = parsed;

  const field = opts.payloadHashField ?? 'servicesSha256';
  const want = meta[field];
  if (want === undefined) {
    if (opts.requirePayloadHash) throw new Error(`bundle: no ${field}`);
  } else if (typeof want !== 'string' || !sameInConstantTime(want.toLowerCase(), await sha256Hex(payload))) {
    throw new Error(`bundle: its payload doesn't match ${field}`);
  }

  if (opts.expectedSerial !== undefined) {
    if (meta.serialno === undefined) {
      if (opts.requireSerial) throw new Error('bundle: no serialno');
    } else if (meta.serialno !== opts.expectedSerial) {
      throw new Error('bundle: it was read from another device (serialno)');
    }
  }

  const mac = meta.hmacSha256;
  if (mac === undefined) {
    if (opts.requireHmac) throw new Error('bundle: not signed (no hmacSha256)');
  } else if (typeof mac !== 'string') {
    throw new Error('bundle: hmacSha256 isn\'t a string');
  } else if (opts.secret) {
    // ASCII only from the comma on, so the text's tail is the bytes' tail
    const text = new TextDecoder().decode(rawMetaBytes);
    const tail = SIGNED_TAIL.exec(text);
    if (!tail || tail[1] !== mac) throw new Error('bundle: hmacSha256 must be the meta\'s last field, 64 lower-case hex digits');
    // the agent signs the JSON up to that field, without it and without the closing brace (StockBundle.buildMetaBase)
    const base = rawMetaBytes.subarray(0, rawMetaBytes.byteLength - tail[0].length);
    if (!sameInConstantTime(await hmacSha256Hex(opts.secret, base, payload), mac)) throw new BundleSignatureError('bundle: its signature doesn\'t match (hmacSha256)');
  } else if (opts.requireHmac) {
    throw new Error('bundle: there is no secret to check its signature with');
  }
  return parsed;
}

/** How Kiln can take a bundle from the device's agent: over the LAN, over Bluetooth, or as a file the person picks. */
export type BundleChannelKind = 'lan' | 'ble' | 'file';

/** One way a bundle comes in. The app implements them; the engine tries the recipe's `channels` in order. */
export interface BundleChannel {
  kind: BundleChannelKind;
  /** Whether this browser can use it at all (Web Bluetooth, a file picker, a reachable device). */
  available(): boolean | Promise<boolean>;
  /** The bundle's bytes, as received. Rejects when `signal` aborts. */
  receive(o: { signal?: AbortSignal; onProgress?: (received: number, total?: number) => void }): Promise<Uint8Array>;
}

/** Bundles kept between visits, by device serial and bundle digest, so a repeat run skips receiving it again. */
export interface BundleCache {
  get(serial: string, digest: string): Promise<Uint8Array | undefined>;
  put(serial: string, digest: string, bytes: Uint8Array): Promise<void>;
  /** The newest bundle kept for the device. */
  latest(serial: string): Promise<{ digest: string; bytes: Uint8Array } | undefined>;
  delete(serial: string, digest: string): Promise<void>;
}

interface Kept { key: string; serial: string; digest: string; bytes: Uint8Array; at: number }
const keyOf = (serial: string, digest: string) => `${serial}\n${digest}`;

/** A BundleCache in memory: demos and tests. */
export function memoryBundleCache(): BundleCache {
  const kept = new Map<string, Kept>();
  let tick = 0;
  return {
    get: async (s, d) => kept.get(keyOf(s, d))?.bytes,
    put: async (s, d, bytes) => { kept.set(keyOf(s, d), { key: keyOf(s, d), serial: s, digest: d, bytes: bytes.slice(), at: ++tick }); },
    latest: async (s) => {
      const k = [...kept.values()].filter((x) => x.serial === s).sort((a, b) => b.at - a.at)[0];
      return k && { digest: k.digest, bytes: k.bytes };
    },
    delete: async (s, d) => { kept.delete(keyOf(s, d)); },
  };
}

/** A BundleCache in this browser's IndexedDB (`db`: the frame's `storage.inPlaceDb`; never rename it). */
export class IdbBundleCache implements BundleCache {
  constructor(private readonly db: string) {}

  private open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(this.db, 1);
      r.onupgradeneeded = () => { r.result.createObjectStore('bundles', { keyPath: 'key' }); };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
    });
  }

  private async run<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest | undefined): Promise<T> {
    const db = await this.open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const tx = db.transaction('bundles', mode);
        const r = f(tx.objectStore('bundles'));
        tx.oncomplete = () => resolve(r?.result as T);
        tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('could not keep the bundle in this browser'));
      });
    } finally { db.close(); }
  }

  async get(serial: string, digest: string): Promise<Uint8Array | undefined> {
    return (await this.run<Kept | undefined>('readonly', (s) => s.get(keyOf(serial, digest))))?.bytes;
  }

  async put(serial: string, digest: string, bytes: Uint8Array): Promise<void> {
    await this.run('readwrite', (s) => s.put({ key: keyOf(serial, digest), serial, digest, bytes, at: Date.now() } satisfies Kept));
  }

  async latest(serial: string): Promise<{ digest: string; bytes: Uint8Array } | undefined> {
    const all = await this.run<Kept[]>('readonly', (s) => s.getAll());
    const k = all.filter((x) => x.serial === serial).sort((a, b) => b.at - a.at)[0];
    return k && { digest: k.digest, bytes: k.bytes };
  }

  async delete(serial: string, digest: string): Promise<void> {
    await this.run('readwrite', (s) => s.delete(keyOf(serial, digest)));
  }
}
