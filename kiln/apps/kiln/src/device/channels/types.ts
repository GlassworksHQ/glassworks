// The shared shapes of Kiln's bundle transfer channels: how a bundle the on-device agent built reaches this browser.
// Every channel is frame-neutral. Service and characteristic UUIDs, ports and paths come from the frame's data through
// ChannelConfig, never from here.

/** Where a bundle came from: this browser's cache, the local network, Bluetooth, or a file the user picked. */
export type ChannelKind = 'cache' | 'lan' | 'ble' | 'file';

/** What the agent says about itself and its bundle (the Bluetooth metadata characteristic, UTF-8 JSON). */
export interface AgentMeta {
  /** The agent's address on the local network, when it has one. */
  lanIp?: string | null;
  /** The port its HTTP server listens on. */
  httpPort?: number;
  /** The whole bundle's size in bytes. */
  bundleSize?: number;
  /** The chunk size the agent would use over Bluetooth right now (the INFO frame has the final word). */
  chunkSize?: number;
  /** The bundle's digest (the agent's `digest` field, or its first `*Sha256` field other than the HMAC). */
  digest?: string;
  /** The device's serial number, the cache key with the digest. */
  serialno?: string | null;
  /** Everything the agent sent, as it sent it. */
  raw: Record<string, unknown>;
}

/** A transfer's progress, the same for every channel. */
export interface TransferProgress {
  channel: ChannelKind;
  receivedBytes: number;
  totalBytes: number;
  /** A rolling average over the last few seconds. */
  bytesPerSecond: number;
  /** null until there is a rate to go by. */
  etaSeconds: number | null;
  /** Bluetooth only: which resend pass is running (1 is the first resend after the stream). */
  resendPass?: number;
}

/** Why a local network transfer failed. `lna-denied`: the browser's local network access permission said no. */
export type LanErrorKind = 'lna-denied' | 'timeout' | 'aborted' | 'http-error' | 'network-error';

export interface LanChannelConfig {
  /** The agent's HTTP port, when its metadata doesn't say. */
  port: number;
  /** The bundle's path on the agent's HTTP server, starting with `/`. */
  path: string;
  /** The agent's own page, for the file fallback (the user downloads the bundle there). */
  pagePath?: string;
  /** The Wi-Fi network the device opens itself, `{serial}` standing for its serial, and its address there (InPlaceCode). */
  hotspot?: { ssid: string; host: string };
}

export interface BleChannelConfig {
  /** The agent's GATT service UUID. */
  service: string;
  /** Metadata characteristic (read). */
  meta: string;
  /** Control characteristic (write). */
  ctrl: string;
  /** Data characteristic (notify). */
  data: string;
}

/** A frame's channels, from its data. A channel without config is off. */
export interface ChannelConfig {
  lan?: LanChannelConfig;
  ble?: BleChannelConfig;
}

export interface BundleKey {
  serial: string;
  digest: string;
}

/** Bundles kept in this browser, by device and digest, so a second visit doesn't transfer again. */
export interface BundleCacheAdapter {
  get(key: BundleKey): Promise<Uint8Array | null>;
  put(key: BundleKey, bytes: Uint8Array): Promise<void>;
}

/** Bluetooth chunks kept as they arrive, so a reload or a reconnect picks up where the transfer stopped. */
export interface BleResumeStore {
  load(digest: string): Promise<{ chunkSize: number; bundleSize: number; received: Map<number, Uint8Array> } | null>;
  saveChunk(digest: string, idx: number, data: Uint8Array): Promise<void>;
  clear(digest: string): Promise<void>;
}

// The parts of Web Bluetooth the channels use. TypeScript's DOM library has no Web Bluetooth yet, and these small
// structural types let tests hand in a fake without touching globals.

export interface BleCharacteristicLike {
  readValue(): Promise<DataView>;
  writeValue?(value: BufferSource): Promise<void>;
  writeValueWithResponse?(value: BufferSource): Promise<void>;
  writeValueWithoutResponse?(value: BufferSource): Promise<void>;
  startNotifications(): Promise<unknown>;
  stopNotifications(): Promise<unknown>;
  addEventListener(type: 'characteristicvaluechanged', listener: (event: Event) => void): void;
  removeEventListener(type: 'characteristicvaluechanged', listener: (event: Event) => void): void;
}

export interface BleServiceLike {
  getCharacteristic(uuid: string): Promise<BleCharacteristicLike>;
}

export interface BleServerLike {
  readonly connected: boolean;
  getPrimaryService(uuid: string): Promise<BleServiceLike>;
  disconnect(): void;
}

export interface BleDeviceLike {
  readonly id?: string;
  readonly name?: string;
  /** `disconnect()` here (BluetoothRemoteGATTServer's) also aborts a `connect()` still pending. */
  readonly gatt?: { connect(): Promise<BleServerLike>; disconnect?(): void } | null;
  addEventListener?(type: 'gattserverdisconnected', listener: () => void): void;
  removeEventListener?(type: 'gattserverdisconnected', listener: () => void): void;
}

export interface BluetoothLike {
  getAvailability?(): Promise<boolean>;
  requestDevice(options: { filters: { services: string[] }[]; optionalServices?: string[] }): Promise<BleDeviceLike>;
}

/**
 * The digest in an agent's metadata: its `digest` field, else its first `*Sha256` (or `sha256`) field that isn't the
 * HMAC. Agents name the digest after what the bundle carries; Kiln doesn't need to know what that is.
 */
export function findDigest(raw: Record<string, unknown>): string | undefined {
  if (typeof raw.digest === 'string' && raw.digest) return raw.digest;
  for (const [k, v] of Object.entries(raw)) {
    if (/hmac/i.test(k)) continue;
    if (/sha256$/i.test(k) && typeof v === 'string' && v) return v;
  }
  return undefined;
}

/** An AbortError, as fetch and Web Bluetooth throw it. */
export function abortError(message = 'The transfer was stopped'): Error {
  try {
    return new DOMException(message, 'AbortError');
  } catch {
    const e = new Error(message);
    e.name = 'AbortError';
    return e;
  }
}

export const isAbortError = (e: unknown): boolean => typeof e === 'object' && e !== null && (e as { name?: unknown }).name === 'AbortError';
