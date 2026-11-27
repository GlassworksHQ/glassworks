/// <reference types="web-bluetooth" />
// A simulated stock Mirror for the stock-in-place flow, for tests and Kiln's demos: the GWSB bundle the stock probe app
// serves, the probe app's HTTP server and BLE GATT peripheral (with faults to inject), and the two fastboot sessions the
// flow goes through. Every value is made up: the serial, the LAN address (RFC 5737), the jar and its extents.
//
// The wire formats follow the probe app (tools/mirror/stock-probe-app, branch edu/mirror-stock-bundle-ble-v2, read
// 2026-11-24): StockBundle.java (GWSB layout, meta field order, the BLE META cap), ProbeServerService.java (routes, CORS
// and Private Network Access headers) and BleProbeServer.java (the CTRL ops and the DATA frames). Two parts aren't in
// that Java yet and follow the in-place design instead: `serialno` in the meta, and the trailing `hmacSha256` (HMAC-SHA256
// with the per-run secret over the meta without that field, then the jar).
//
// Self-contained on purpose: nothing here imports the in-place code it tests (src/stock.ts).
import type { FastbootLike } from '../src/flash';
import { SYSTEM_BYTES, USERDATA_BYTES } from '../src/flash';
import { STOCK_PROBE_LAN } from '../src/inplace';

// ---------------------------------------------------------------------------------------------------------------------
// The GWSB bundle

export const GWSB_MAGIC = 'GWSB';
export const GWSB_VERSION = 1;
/** ProbeServerService.PORT */
export const STOCK_PROBE_PORT = 18765;
export const SERVICES_PATH = '/system/framework/services.jar';
export const SERVICES_ODEX_PATH = '/system/framework/oat/arm64/services.odex';
/** A serial made up for tests. Never a real unit's. */
export const FAKE_SERIAL = '0000fake01';
/** RFC 5737 TEST-NET-1 */
export const FAKE_LAN_IP = '192.0.2.42';
const FAKE_FINGERPRINT = 'fake/mirror/mirror:0/FAKE.000/0:user/release-keys';

/** One FIEMAP extent as the probe app's fiemap.c prints it. flags 1: FIEMAP_EXTENT_LAST. */
export interface FakeExtent { logical: number; physical: number; block?: number; length: number; blocks?: number; flags: number }

export interface FakeGwsbOptions {
  serialno?: string;
  /** the per-run secret (32 bytes): when given, the meta ends with `"hmacSha256"` */
  secret?: Uint8Array;
  lanIp?: string | null;
  httpPort?: number;
  servicesOdexFound?: boolean;
  /** whether services.jar holds classes.dex: by default the opposite of servicesOdexFound, as on an odexed build */
  servicesHasClassesDex?: boolean;
  mirrorVersion?: string;
  fingerprint?: string;
  /** services.jar: by default 16 KiB of ZIP-looking bytes */
  jarBytes?: Uint8Array;
  /** its extents: by default one, the whole jar at block 100 */
  extents?: FakeExtent[];
  /** the meta's hmacSha256 doesn't match (needs `secret`) */
  corruptHmac?: boolean;
  /** the meta's servicesSha256 doesn't match the jar */
  corruptSha?: boolean;
  /** fields laid over the meta before it's signed (an undefined one is left out): a meta of another shape */
  metaPatch?: Record<string, unknown>;
}

export interface FakeGwsbBundle {
  /** the whole bundle: "GWSB", metaLen, jarLen, meta, jar */
  bytes: Uint8Array;
  /** the meta as parsed from metaJson */
  meta: Record<string, unknown>;
  /** the meta as written in the bundle (with hmacSha256 when signed) */
  metaJson: string;
  /** the meta without hmacSha256: what the HMAC covers, and the base of the BLE META */
  metaBaseJson: string;
  jarBytes: Uint8Array;
  /** the jar's real SHA-256 (hex); with corruptSha the meta claims another */
  sha256: string;
  /** the HMAC the meta carries (hex), as written: wrong with corruptHmac */
  hmacSha256?: string;
}

const enc = new TextEncoder();
const dec = new TextDecoder();
const buf = (b: Uint8Array) => new Uint8Array(b) as Uint8Array<ArrayBuffer>;

export const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

export async function sha256Hex(b: Uint8Array): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', buf(b))));
}

export async function hmacSha256Hex(secret: Uint8Array, ...parts: Uint8Array[]): Promise<string> {
  const key = await crypto.subtle.importKey('raw', buf(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(new Uint8Array(await crypto.subtle.sign('HMAC', key, buf(concat(...parts)))));
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

const flipHex = (h: string) => (h[0] === '0' ? '1' : '0') + h.slice(1);

/** 16 KiB that start like a ZIP (local header) and end like one (end of central directory). Not a real jar. */
export function fakeJarBytes(size = 16384, seed = 7): Uint8Array {
  let x = seed >>> 0;
  const b = Uint8Array.from({ length: size }, () => { x = (Math.imul(x, 1103515245) + 12345) >>> 0; return x >>> 24; });
  b.set([0x50, 0x4b, 0x03, 0x04], 0);
  if (size >= 22) { b.fill(0, size - 22); b.set([0x50, 0x4b, 0x05, 0x06], size - 22); }
  return b;
}

/** The meta in StockBundle.buildMetaWithFiemap's order (JSON.stringify writes it as compactly as the Java does). */
function metaObject(o: Required<Pick<FakeGwsbOptions, 'serialno' | 'httpPort' | 'mirrorVersion' | 'fingerprint'>> & {
  lanIp: string | null; jarLen: number; sha256: string; odexFound: boolean; hasClassesDex: boolean; fiemap: unknown;
}): Record<string, unknown> {
  return {
    version: GWSB_VERSION,
    mirrorVersion: o.mirrorVersion,
    fingerprint: o.fingerprint,
    servicesPath: SERVICES_PATH,
    servicesSize: o.jarLen,
    servicesSha256: o.sha256,
    servicesOdexFound: o.odexFound,
    servicesOdexPath: o.odexFound ? SERVICES_ODEX_PATH : null,
    servicesHasClassesDex: o.hasClassesDex,
    fiemap: o.fiemap,
    lanIp: o.lanIp,
    httpPort: o.httpPort,
    serialno: o.serialno,
  };
}

/** A self-consistent GWSB bundle (or a broken one, with corruptSha / corruptHmac). */
export async function buildFakeGwsbBundle(o: FakeGwsbOptions = {}): Promise<FakeGwsbBundle> {
  const jarBytes = o.jarBytes ?? fakeJarBytes();
  const extents = o.extents ?? [{ logical: 0, physical: 4096 * 100, block: 100, length: jarBytes.length, blocks: Math.ceil(jarBytes.length / 4096), flags: 1 }];
  const sha256 = await sha256Hex(jarBytes);
  const odexFound = o.servicesOdexFound ?? false;
  const base = metaObject({
    serialno: o.serialno ?? FAKE_SERIAL,
    lanIp: o.lanIp === undefined ? FAKE_LAN_IP : o.lanIp,
    httpPort: o.httpPort ?? STOCK_PROBE_PORT,
    mirrorVersion: o.mirrorVersion ?? '1.24.0',
    fingerprint: o.fingerprint ?? FAKE_FINGERPRINT,
    jarLen: jarBytes.length,
    sha256: o.corruptSha ? flipHex(sha256) : sha256,
    odexFound,
    hasClassesDex: o.servicesHasClassesDex ?? !odexFound,
    fiemap: { size: jarBytes.length, ino: 1234, extents },
  });
  const metaBaseJson = JSON.stringify(o.metaPatch ? { ...base, ...o.metaPatch } : base);
  let metaJson = metaBaseJson;
  let hmacSha256: string | undefined;
  if (o.secret) {
    // as the probe app signs (StockBundle.computeHmacHex): the JSON up to the field, without its closing brace
    const real = await hmacSha256Hex(o.secret, enc.encode(metaBaseJson.slice(0, -1)), jarBytes);
    hmacSha256 = o.corruptHmac ? flipHex(real) : real;
    metaJson = `${metaBaseJson.slice(0, -1)},"hmacSha256":"${hmacSha256}"}`;
  } else if (o.corruptHmac) {
    throw new Error('corruptHmac needs a secret');
  }
  const meta = enc.encode(metaJson);
  const bytes = new Uint8Array(12 + meta.length + jarBytes.length);
  const dv = new DataView(bytes.buffer);
  bytes.set(enc.encode(GWSB_MAGIC), 0);
  dv.setUint32(4, meta.length, true);
  dv.setUint32(8, jarBytes.length, true);
  bytes.set(meta, 12);
  bytes.set(jarBytes, 12 + meta.length);
  return { bytes, meta: JSON.parse(metaJson) as Record<string, unknown>, metaJson, metaBaseJson, jarBytes, sha256, ...(hmacSha256 ? { hmacSha256 } : {}) };
}

/** Splits a GWSB bundle the way a reader must: magic, both lengths, nothing left over. Throws on a malformed one. */
export function splitGwsb(bytes: Uint8Array): { metaJson: string; meta: Record<string, unknown>; jar: Uint8Array } {
  if (bytes.length < 12 || dec.decode(bytes.subarray(0, 4)) !== GWSB_MAGIC) throw new Error('not a GWSB bundle');
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const metaLen = dv.getUint32(4, true), jarLen = dv.getUint32(8, true);
  if (12 + metaLen + jarLen !== bytes.length) throw new Error(`GWSB lengths ${metaLen}+${jarLen} don't match ${bytes.length - 12} bytes`);
  const metaJson = dec.decode(bytes.subarray(12, 12 + metaLen));
  return { metaJson, meta: JSON.parse(metaJson) as Record<string, unknown>, jar: bytes.subarray(12 + metaLen) };
}

/**
 * Checks a GWSB bundle as the in-place flow must: its jar against servicesSha256, and with `secret`, hmacSha256 over the
 * meta up to that field (without the closing brace) and the jar. The test reference for the contract; src/stock.ts carries the real reader.
 */
export async function verifyFakeGwsb(bytes: Uint8Array, secret?: Uint8Array): Promise<{ shaOk: boolean; hmacOk: boolean | null }> {
  const { metaJson, meta, jar } = splitGwsb(bytes);
  const shaOk = meta.servicesSha256 === await sha256Hex(jar) && meta.servicesSize === jar.length;
  if (!secret) return { shaOk, hmacOk: null };
  const m = /,"hmacSha256":"([0-9a-f]{64})"\}$/.exec(metaJson);
  if (!m) return { shaOk, hmacOk: false };
  const base = metaJson.slice(0, m.index);
  return { shaOk, hmacOk: m[1] === await hmacSha256Hex(secret, enc.encode(base), jar) };
}

// ---------------------------------------------------------------------------------------------------------------------
// The probe app: HTTP and BLE

/** BleProbeServer's UUIDs. */
export const STOCK_BLE = {
  SERVICE: '67770001-6b69-6c6e-7374-6f636b70726f',
  META: '67770002-6b69-6c6e-7374-6f636b70726f',
  CTRL: '67770003-6b69-6c6e-7374-6f636b70726f',
  DATA: '67770004-6b69-6c6e-7374-6f636b70726f',
} as const;
export const OP_STREAM = 0x01;
export const OP_RESEND = 0x02;
export const OP_ABORT = 0x03;
/** DATA frames with these indexes carry stream info, not bundle bytes. */
export const IDX_INFO = 0xffffffff;
export const IDX_END = 0xfffffffe;
const MAX_ATTR = 512;

/** BleProbeServer.chunkFor: 3 bytes of ATT header and 4 of chunk index, within one 512-byte attribute. */
export const chunkFor = (mtu: number) => Math.min(MAX_ATTR - 4, Math.max(16, mtu - 7));

/** The CORS and Private Network Access headers ProbeServerService sends on /stock-bundle.bin. */
export const STOCK_CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Private-Network': 'true',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Disposition',
} as const;

export type LanMode = 'ok' | 'lna-denied' | 'drop-midway' | 'timeout' | 'offline';

export interface FakeStockAgentOptions {
  /** `lna-denied`: the browser's Local Network Access refuses (fetch rejects); `drop-midway`: the bundle stops at
   * dropAfterBytes; `timeout`: no answer until the request's signal aborts; `offline`: nothing on the LAN */
  lanMode?: LanMode;
  /** with drop-midway: bytes of the bundle body delivered before the connection drops (default: half the bundle) */
  dropAfterBytes?: number;
  /** the negotiated ATT MTU (default 185: 178-byte chunks) */
  bleMtu?: number;
  /** chunk indexes lost the first time a 0x01 stream sends them; a 0x02 resend always delivers them */
  bleDropChunks?: readonly number[];
  /** no Bluetooth adapter: getAvailability() is false, requestDevice() rejects */
  bleUnavailable?: boolean;
  /** the stream stalls once after this many data chunks of a 0x01 stream (no END) until the next CTRL write */
  bleStallAfterChunks?: number;
  /** delay between DATA notifications (default 0: the next macrotask) */
  bleFrameDelayMs?: number;
}

export interface FakeStockAgentStats {
  lanRequests: number;
  bleStreams: number;
  bleResends: number;
  bleAborts: number;
  /** data chunks the peripheral sent, lost ones included */
  totalBleChunksSent: number;
  /** data chunks lost on the way (bleDropChunks) */
  bleChunksDropped: number;
}

const le32 = (b: Uint8Array, at: number) => (b[at]! | b[at + 1]! << 8 | b[at + 2]! << 16 | b[at + 3]! << 24) >>> 0;
const norm = (u: BluetoothServiceUUID | BluetoothCharacteristicUUID) => String(u).toLowerCase();
const domError = (message: string, name: string) => new DOMException(message, name);
const toBytes = (v: BufferSource) => (v instanceof Uint8Array ? new Uint8Array(v)
  : ArrayBuffer.isView(v) ? new Uint8Array(v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength)) : new Uint8Array(v));

/** One DATA notification as BleProbeServer.pump builds it. */
function frameFor(idx: number, bundle: Uint8Array, chunkSize: number, totalChunks: number, sentInStream: number): Uint8Array {
  if (idx === IDX_INFO) {
    const f = new Uint8Array(16), dv = new DataView(f.buffer);
    dv.setUint32(0, IDX_INFO, true); dv.setUint32(4, chunkSize, true); dv.setUint32(8, bundle.length, true); dv.setUint32(12, totalChunks, true);
    return f;
  }
  if (idx === IDX_END) {
    const f = new Uint8Array(8), dv = new DataView(f.buffer);
    dv.setUint32(0, IDX_END, true); dv.setUint32(4, sentInStream, true);
    return f;
  }
  const off = idx * chunkSize;
  const data = bundle.subarray(off, Math.min(bundle.length, off + chunkSize));
  const f = new Uint8Array(4 + data.length);
  new DataView(f.buffer).setUint32(0, idx, true);
  f.set(data, 4);
  return f;
}

/** The stock probe app on a simulated stock Mirror: its HTTP server (fakeFetch) and its BLE peripheral (fakeBluetooth). */
export class FakeStockAgent {
  readonly stats: FakeStockAgentStats = { lanRequests: 0, bleStreams: 0, bleResends: 0, bleAborts: 0, totalBleChunksSent: 0, bleChunksDropped: 0 };
  readonly lanMode: LanMode;
  readonly chunkSize: number;
  /** a drop-in fetch for the probe's HTTP server; anything else is a network error */
  readonly fakeFetch: typeof fetch;
  /** a drop-in navigator.bluetooth for the probe's GATT peripheral */
  readonly fakeBluetooth: Pick<Bluetooth, 'requestDevice' | 'getAvailability'>;
  /** the BLE device requestDevice hands out (one per agent) */
  readonly device: FakeBleDevice;

  // BLE stream state, as BleProbeServer keeps it: one stream at a time
  private queue: [number, number][] = [];
  private locked = false;
  private totalChunks = 0;
  private sentInStream = 0;
  private dataInStream = 0;
  private pumping = false;
  private stalled = false;
  private stalledOnce = false;
  private streamIsResend = false;
  private readonly dropped = new Set<number>();

  constructor(readonly bundle: FakeGwsbBundle, readonly o: FakeStockAgentOptions = {}) {
    this.lanMode = o.lanMode ?? 'ok';
    this.chunkSize = chunkFor(o.bleMtu ?? 185);
    this.fakeFetch = ((input: RequestInfo | URL, init?: RequestInit) => this.fetch(input, init)) as typeof fetch;
    this.device = new FakeBleDevice(this);
    this.fakeBluetooth = {
      getAvailability: async () => !o.bleUnavailable,
      requestDevice: async (opts?: RequestDeviceOptions) => {
        if (o.bleUnavailable) throw domError('Bluetooth adapter not available.', 'NotFoundError');
        const wanted = opts && 'filters' in opts && opts.filters
          ? opts.filters.some((f) => f.services?.some((s) => norm(s) === STOCK_BLE.SERVICE)) : !!(opts && 'acceptAllDevices' in opts && opts.acceptAllDevices);
        if (!wanted) throw domError('User cancelled the requestDevice() chooser.', 'NotFoundError');
        return this.device as unknown as BluetoothDevice;
      },
    };
  }

  /** The probe app's origin, or null with no LAN address. */
  get origin(): string | null {
    const ip = this.bundle.meta.lanIp as string | null;
    return ip ? `http://${ip}:${this.bundle.meta.httpPort as number}` : null;
  }

  // --- HTTP ---------------------------------------------------------------------------------------------------------

  private async fetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
    this.stats.lanRequests++;
    const req = input instanceof Request ? input : undefined;
    const url = new URL(req ? req.url : String(input));
    const method = (init.method ?? req?.method ?? 'GET').toUpperCase();
    const signal = init.signal ?? req?.signal ?? undefined;
    if (signal?.aborted) throw signal.reason ?? domError('The operation was aborted.', 'AbortError');
    const fail = () => new TypeError('Failed to fetch');
    // the probe answers on the address its description gives, and on the Mirror's own Wi-Fi (STOCK_PROBE_LAN.hotspot)
    const hotspot = `http://${STOCK_PROBE_LAN.hotspot.host}:${STOCK_PROBE_LAN.port}`;
    if (this.lanMode === 'offline' || ((!this.origin || url.origin !== this.origin) && url.origin !== hotspot)) throw fail();
    if (this.lanMode === 'lna-denied') throw fail(); // Chrome's Local Network Access refusal looks the same to the page
    if (this.lanMode === 'timeout') {
      return new Promise<Response>((_, reject) => signal?.addEventListener('abort', () => reject(signal.reason ?? domError('The operation was aborted.', 'AbortError')), { once: true }));
    }
    const route = url.pathname;
    const text = (status: number, body: string, type = 'text/plain') => new Response(body, { status, headers: { 'Content-Type': type, Connection: 'close' } });
    if (method === 'OPTIONS') {
      if (route !== '/stock-bundle.bin') return text(405, 'GET only\n');
      return new Response(null, { status: 204, headers: { ...STOCK_CORS_HEADERS, 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS', 'Access-Control-Allow-Headers': '*', 'Access-Control-Max-Age': '600', 'Content-Length': '0', Connection: 'close' } });
    }
    if (method === 'HEAD' && route !== '/stock-bundle.bin') return text(405, 'HEAD is only for /stock-bundle.bin\n');
    if (method !== 'GET' && method !== 'HEAD') return text(405, 'GET only\n');
    if (route === '/stock-bundle.bin') {
      const headers = {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': 'attachment; filename="mirror-stock-bundle.bin"',
        'Content-Length': String(this.bundle.bytes.length),
        ...STOCK_CORS_HEADERS,
        'Cache-Control': 'no-store',
        Connection: 'close',
      };
      return new Response(method === 'HEAD' ? null : this.bodyStream(signal), { status: 200, headers });
    }
    if (route === '/' || route === '/bundle') return text(200, this.page(), 'text/html');
    if (route === '/status') return new Response('{"ok":true}\n', { status: 200, headers: { 'Content-Type': 'application/json', ...STOCK_CORS_HEADERS, Connection: 'close' } });
    return text(404, 'not found\n');
  }

  /** The bundle in 4 KiB reads, cut off at dropAfterBytes in drop-midway. */
  private bodyStream(signal?: AbortSignal): ReadableStream<Uint8Array> {
    const bytes = this.bundle.bytes;
    const cut = this.lanMode === 'drop-midway' ? Math.min(bytes.length, this.o.dropAfterBytes ?? Math.floor(bytes.length / 2)) : bytes.length;
    let at = 0;
    return new ReadableStream<Uint8Array>({
      pull(c) {
        if (signal?.aborted) { c.error(signal.reason ?? domError('The operation was aborted.', 'AbortError')); return; }
        if (at >= cut) {
          if (cut < bytes.length) c.error(new TypeError('network error')); else c.close();
          return;
        }
        const n = Math.min(4096, cut - at);
        c.enqueue(bytes.slice(at, at + n));
        at += n;
      },
    });
  }

  private page(): string {
    const m = this.bundle.meta;
    return '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Mirror stock bundle</title></head><body>'
      + '<h1>Mirror stock bundle</h1>'
      + `<dl><dt>services.jar SHA-256</dt><dd><code>${String(m.servicesSha256)}</code></dd>`
      + `<dt>Bundle size</dt><dd>${(this.bundle.bytes.length / 1048576).toFixed(1)} MB</dd></dl>`
      + '<a class="dl" href="/stock-bundle.bin" download>Download the stock bundle</a></body></html>';
  }

  // --- BLE ----------------------------------------------------------------------------------------------------------

  /**
   * The META characteristic as StockBundle.bleMetaJson builds it: the bundle meta plus bundleSize, chunkSize and
   * chunkLocked (and the HMAC when signed), then, until it fits 512 bytes, FIEMAP trimmed to its extent count, the HMAC
   * left to the bundle (`hasHmac`), and last the compact meta, with the serial and then without. A fixture whose compact
   * meta still doesn't fit is one the real agent can't describe either, so it's refused here.
   */
  bleMeta(): Uint8Array {
    const base = JSON.parse(this.bundle.metaBaseJson) as Record<string, unknown>;
    const extra = { bundleSize: this.bundle.bytes.length, chunkSize: this.chunkSize, chunkLocked: this.locked };
    const signed = this.bundle.hmacSha256;
    const hmac = signed ? { hmacSha256: signed } : {};
    const fiemap = base.fiemap as { extents?: unknown[] } | null;
    const trimmed = { truncated: true, extentCount: fiemap?.extents?.length ?? 0 };
    const compact = (serial: boolean) => ({
      version: base.version, compact: true, ...(serial ? { serialno: base.serialno } : {}),
      servicesSize: base.servicesSize, servicesSha256: base.servicesSha256, servicesOdexFound: base.servicesOdexFound,
      servicesHasClassesDex: base.servicesHasClassesDex, lanIp: base.lanIp, httpPort: base.httpPort, ...extra, ...(signed ? { hasHmac: true } : {}),
    });
    const tries: Record<string, unknown>[] = [
      { ...base, ...extra, ...hmac },
      { ...base, fiemap: trimmed, ...extra, ...hmac },
      ...(signed ? [{ ...base, fiemap: trimmed, ...extra, hasHmac: true }] : []),
      compact(true),
      compact(false),
    ];
    for (const t of tries) {
      const b = enc.encode(JSON.stringify(t));
      if (b.length <= MAX_ATTR) return b;
    }
    throw new Error(`the agent's compact META is still over ${MAX_ATTR} bytes: no real agent sends that, so this fixture can't stand for one`);
  }

  /** A CTRL write, as BleProbeServer.command handles it. */
  command(v: Uint8Array): void {
    if (v.length === 0) return;
    const resume = this.stalled;
    this.stalled = false;
    const op = v[0]!;
    if (op === OP_STREAM) {
      const start = v.length >= 5 ? le32(v, 1) : 0;
      const count = v.length >= 9 ? le32(v, 5) : 0;
      this.locked = true;
      this.totalChunks = Math.ceil(this.bundle.bytes.length / this.chunkSize);
      const end = count === 0 ? this.totalChunks : Math.min(this.totalChunks, start + count);
      this.queue = [[IDX_INFO, 0]];
      if (start < end) this.queue.push([start, end]);
      this.queue.push([IDX_END, 0]);
      this.sentInStream = 0;
      this.dataInStream = 0;
      this.streamIsResend = false;
      this.stats.bleStreams++;
      this.pump();
    } else if (op === OP_RESEND) {
      if (!this.locked) return; // no locked chunk size yet: 0x01 first
      const n = v.length >= 3 ? v[1]! | v[2]! << 8 : 0;
      const wasIdle = this.queue.length === 0 && !this.pumping;
      if (this.queue.at(-1)?.[0] === IDX_END) this.queue.pop();
      for (let i = 0; i < n && 3 + 4 * i + 4 <= v.length; i++) {
        const idx = le32(v, 3 + 4 * i);
        if (idx < this.totalChunks) this.queue.push([idx, idx + 1]);
      }
      this.queue.push([IDX_END, 0]);
      this.sentInStream = 0;
      this.streamIsResend = true;
      this.stats.bleResends++;
      if (wasIdle || resume) this.pump();
    } else if (op === OP_ABORT) {
      this.queue = [];
      this.stats.bleAborts++;
    } else if (resume) {
      this.pump();
    }
  }

  /** The link went away: BleProbeServer clears the stream. */
  disconnected(): void {
    this.queue = [];
    this.locked = false;
    this.stalled = false;
  }

  private pump(): void {
    if (this.pumping) return;
    this.pumping = true;
    void (async () => {
      try {
        while (this.queue.length && !this.stalled && this.device.gatt.connected) {
          await new Promise((r) => setTimeout(r, this.o.bleFrameDelayMs ?? 0));
          const r = this.queue[0];
          if (!r || this.stalled || !this.device.gatt.connected) break;
          const idx = r[0];
          const frame = frameFor(idx, this.bundle.bytes, this.chunkSize, this.totalChunks, this.sentInStream);
          if (idx >= IDX_END || r[0] + 1 >= r[1]) this.queue.shift(); else r[0] = idx + 1;
          if (idx < IDX_END) {
            this.sentInStream++;
            this.stats.totalBleChunksSent++;
            const lose = !this.streamIsResend && this.o.bleDropChunks?.includes(idx) && !this.dropped.has(idx);
            if (lose) { this.dropped.add(idx); this.stats.bleChunksDropped++; } else this.device.notify(frame);
            if (!this.streamIsResend && ++this.dataInStream === this.o.bleStallAfterChunks && !this.stalledOnce) {
              this.stalledOnce = true;
              this.stalled = true;
            }
          } else {
            this.device.notify(frame);
          }
        }
      } finally {
        this.pumping = false;
      }
    })();
  }
}

/** A BluetoothRemoteGATTCharacteristic, enough of it for Kiln: read, write, notify. */
export class FakeBleCharacteristic extends EventTarget {
  value?: DataView;
  oncharacteristicvaluechanged: ((e: Event) => void) | null = null;
  notifying = false;
  constructor(readonly service: FakeBleService, readonly uuid: string) { super(); }

  private get agent() { return this.service.device.agent; }
  private check() { if (!this.service.device.gatt.connected) throw domError('GATT Server is disconnected. Cannot perform GATT operations.', 'NetworkError'); }

  async readValue(): Promise<DataView> {
    this.check();
    if (this.uuid !== STOCK_BLE.META) throw domError('GATT operation not permitted.', 'NotSupportedError');
    const b = this.agent.bleMeta();
    this.value = new DataView(b.buffer, b.byteOffset, b.byteLength);
    return this.value;
  }
  async writeValueWithResponse(v: BufferSource): Promise<void> {
    this.check();
    if (this.uuid !== STOCK_BLE.CTRL) throw domError('GATT operation not permitted.', 'NotSupportedError');
    this.agent.command(toBytes(v));
  }
  writeValueWithoutResponse(v: BufferSource): Promise<void> { return this.writeValueWithResponse(v); }
  writeValue(v: BufferSource): Promise<void> { return this.writeValueWithResponse(v); }
  async startNotifications(): Promise<this> {
    this.check();
    if (this.uuid !== STOCK_BLE.DATA) throw domError('GATT operation not permitted.', 'NotSupportedError');
    this.notifying = true;
    return this;
  }
  async stopNotifications(): Promise<this> { this.notifying = false; return this; }

  /** The peripheral notified: the value changes, then the event fires (as in Chrome). */
  emit(frame: Uint8Array): void {
    if (!this.notifying) return;
    this.value = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
    const e = new Event('characteristicvaluechanged');
    this.dispatchEvent(e);
    this.oncharacteristicvaluechanged?.(e);
  }
}

export class FakeBleService {
  readonly uuid = STOCK_BLE.SERVICE;
  readonly isPrimary = true;
  readonly chars: Map<string, FakeBleCharacteristic>;
  constructor(readonly device: FakeBleDevice) {
    this.chars = new Map([STOCK_BLE.META, STOCK_BLE.CTRL, STOCK_BLE.DATA].map((u) => [u, new FakeBleCharacteristic(this, u)]));
  }
  async getCharacteristic(u: BluetoothCharacteristicUUID): Promise<FakeBleCharacteristic> {
    const c = this.chars.get(norm(u));
    if (!c) throw domError(`No Characteristics matching UUID ${String(u)} found in Service.`, 'NotFoundError');
    return c;
  }
}

export class FakeBleGatt {
  connected = false;
  constructor(readonly device: FakeBleDevice) {}
  async connect(): Promise<this> { this.connected = true; return this; }
  disconnect(): void {
    if (!this.connected) return;
    this.connected = false;
    this.device.agent.disconnected();
    for (const c of this.device.service.chars.values()) c.notifying = false;
    this.device.dispatchEvent(new Event('gattserverdisconnected'));
  }
  async getPrimaryService(u: BluetoothServiceUUID): Promise<FakeBleService> {
    if (!this.connected) throw domError('GATT Server is disconnected. Cannot retrieve services.', 'NetworkError');
    if (norm(u) !== STOCK_BLE.SERVICE) throw domError(`No Services matching UUID ${String(u)} found in Device.`, 'NotFoundError');
    return this.device.service;
  }
}

export class FakeBleDevice extends EventTarget {
  readonly id = 'fake-stock-probe';
  readonly name = 'Mirror';
  readonly gatt: FakeBleGatt;
  readonly service: FakeBleService;
  constructor(readonly agent: FakeStockAgent) {
    super();
    this.gatt = new FakeBleGatt(this);
    this.service = new FakeBleService(this);
  }
  /** A DATA notification to the central. */
  notify(frame: Uint8Array): void { this.service.chars.get(STOCK_BLE.DATA)!.emit(frame); }
}

// ---------------------------------------------------------------------------------------------------------------------
// The two fastboot sessions

export interface FakeTwoStageOptions {
  serialno?: string;
  /** the serial session 2 reports (another Mirror plugged in): by default the same */
  session2Serial?: string;
  /** a flash fastboot refuses with FAILED (remote: ...) */
  refuseFlash?: { session: number; partition: string; message?: string };
  /** the USB link drops partway through a flash (at progress `at`, default 0.5) */
  disconnectMidFlash?: { session: number; partition: string; at?: number };
  systemBytes?: number;
  userdataBytes?: number;
}

/**
 * The bootloader across the in-place flow's two fastboot sessions: session 1 flashes the staging userdata and reboots
 * into Android; the user brings the Mirror back to fastboot (`nextSession()`), and session 2 flashes the patched system
 * and the final userdata. A FastbootLike for whichever session is connected; calls while disconnected throw.
 */
export class FakeTwoStageFastboot implements FastbootLike {
  session = 1;
  connected = true;
  rebootCount = 0;
  serialno: string;
  readonly flashedPartitions: { session: number; partition: string; bytes: number }[] = [];
  readonly cmds: string[] = [];

  constructor(readonly o: FakeTwoStageOptions = {}) {
    this.serialno = o.serialno ?? FAKE_SERIAL;
  }

  private check() { if (!this.connected) throw new Error('fastboot: device disconnected'); }

  /** The Mirror is back in fastboot: the next session starts. */
  nextSession(): this {
    this.session++;
    this.connected = true;
    if (this.session === 2 && this.o.session2Serial) this.serialno = this.o.session2Serial;
    return this;
  }

  async getvar(name: string): Promise<string> {
    this.check();
    this.cmds.push(`getvar:${name}`);
    switch (name) {
      case 'serialno': return this.serialno;
      case 'product': return 'MSM8916';
      case 'max-download-size': return '0x10000000';
      case 'partition-size:system': return `0x${(await this.partitionSize('system')).toString(16)}`;
      case 'partition-size:userdata': return `0x${(await this.partitionSize('userdata')).toString(16)}`;
      default: throw new Error('FAIL');
    }
  }

  async partitionSize(name: string): Promise<number> {
    this.check();
    if (name === 'system') return this.o.systemBytes ?? SYSTEM_BYTES;
    if (name === 'userdata') return this.o.userdataBytes ?? USERDATA_BYTES;
    throw new Error(`FAILED (remote: partition ${name} not found)`);
  }

  async flashBlob(partition: string, blob: Blob, onProgress: (x: number) => void = () => {}): Promise<void> {
    this.check();
    this.cmds.push(`flash:${partition}`);
    const refuse = this.o.refuseFlash;
    if (refuse && refuse.session === this.session && refuse.partition === partition) {
      throw new Error(`flash:${partition}: FAILED (remote: ${refuse.message ?? 'write failed'})`);
    }
    if (blob.size > await this.partitionSize(partition)) throw new Error(`flash:${partition}: FAILED (remote: size too large)`);
    const cut = this.o.disconnectMidFlash;
    for (let i = 1; i <= 4; i++) {
      const x = i / 4;
      if (cut && cut.session === this.session && cut.partition === partition && x > (cut.at ?? 0.5)) {
        this.connected = false;
        throw new Error(`flash:${partition}: USB transfer failed (device disconnected)`);
      }
      onProgress(x);
      await Promise.resolve();
    }
    this.flashedPartitions.push({ session: this.session, partition, bytes: blob.size });
  }

  async reboot(): Promise<void> {
    this.check();
    this.cmds.push('reboot');
    this.rebootCount++;
    this.connected = false;
  }
}
