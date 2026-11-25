// The bundle format the on-device agent builds, and the file fallback: the user downloads the bundle from the agent's
// own page and picks it here.
//
// A bundle: "GWSB", uint32 LE metadata length, uint32 LE payload length, the metadata (UTF-8 JSON), the payload.
import { abortError, findDigest, type AgentMeta, type TransferProgress } from './types';

export const BUNDLE_MAGIC = 'GWSB';
export const BUNDLE_HEADER_BYTES = 12;

export interface BundleHeader {
  metaLen: number;
  payloadLen: number;
  /** The bundle's own metadata, or null when it isn't JSON. */
  meta: Record<string, unknown> | null;
  /** The size the header promises: 12 + metadata + payload. */
  totalLength: number;
}

/** Why a picked file or a received bundle isn't one the agent built. */
export class BundleFormatError extends Error {
  override name = 'BundleFormatError';
}

export function hasBundleMagic(bytes: Uint8Array): boolean {
  return bytes.length >= 4 && String.fromCharCode(bytes[0]!, bytes[1]!, bytes[2]!, bytes[3]!) === BUNDLE_MAGIC;
}

/** Reads a bundle's header. Throws BundleFormatError when the bytes aren't a whole bundle. */
export function parseBundleHeader(bytes: Uint8Array): BundleHeader {
  if (!hasBundleMagic(bytes)) throw new BundleFormatError('This isn\'t a device bundle. Download it again from the device\'s page and pick that file.');
  if (bytes.length < BUNDLE_HEADER_BYTES) throw new BundleFormatError('This bundle is cut short. Download it again.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const metaLen = view.getUint32(4, true);
  const payloadLen = view.getUint32(8, true);
  const totalLength = BUNDLE_HEADER_BYTES + metaLen + payloadLen;
  if (bytes.length !== totalLength) throw new BundleFormatError(bytes.length < totalLength ? 'This bundle is cut short. Download it again.' : 'This bundle has extra bytes at the end. Download it again.');
  let meta: Record<string, unknown> | null = null;
  try {
    const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes.subarray(BUNDLE_HEADER_BYTES, BUNDLE_HEADER_BYTES + metaLen)));
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) meta = parsed as Record<string, unknown>;
  } catch {
    meta = null;
  }
  return { metaLen, payloadLen, meta, totalLength };
}

/**
 * Whether `bytes` is a whole bundle, and the one `agent` describes when it's given: the same size, and the same digest
 * when both name one.
 */
export function bundleMatches(bytes: Uint8Array, agent?: AgentMeta): boolean {
  let header: BundleHeader;
  try {
    header = parseBundleHeader(bytes);
  } catch {
    return false;
  }
  if (!agent) return true;
  if (agent.bundleSize !== undefined && agent.bundleSize !== bytes.length) return false;
  const inside = header.meta ? findDigest(header.meta) : undefined;
  if (agent.digest && inside && agent.digest.toLowerCase() !== inside.toLowerCase()) return false;
  return true;
}

/** The agent's own page on the local network, where the user can download the bundle. */
export function buildAgentPageUrl(lanIp: string, port: number, pagePath = '/'): string {
  const host = lanIp.includes(':') && !lanIp.startsWith('[') ? `[${lanIp}]` : lanIp;
  const path = pagePath.startsWith('/') ? pagePath : `/${pagePath}`;
  return `http://${host}:${port}${path}`;
}

/** Reads a bundle the user picked, checking early that it is one, with progress for big files. */
export async function readBundleFromFile(file: Blob, onProgress?: (p: TransferProgress) => void, signal?: AbortSignal): Promise<Uint8Array> {
  const check = () => { if (signal?.aborted) throw abortError(); };
  check();
  if (file.size === 0) throw new BundleFormatError('This file is empty. Download the bundle again from the device\'s page.');
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  if (!hasBundleMagic(head)) throw new BundleFormatError('This isn\'t a device bundle. Download it again from the device\'s page and pick that file.');

  const total = file.size;
  const started = Date.now();
  const report = (received: number) => {
    if (!onProgress) return;
    const secs = (Date.now() - started) / 1000;
    const rate = secs > 0 ? received / secs : 0;
    onProgress({ channel: 'file', receivedBytes: received, totalBytes: total, bytesPerSecond: rate, etaSeconds: rate > 0 ? (total - received) / rate : null });
  };

  let bytes: Uint8Array;
  if (typeof file.stream === 'function') {
    bytes = new Uint8Array(total);
    const reader = file.stream().getReader();
    // a cancel stops the read where it is, so a cancelled read doesn't go on to the end of a large file
    const onAbort = () => { reader.cancel().catch(() => {}); };
    signal?.addEventListener('abort', onAbort, { once: true });
    let at = 0;
    try {
      for (;;) {
        check();
        const { done, value } = await reader.read();
        check();
        if (done) break;
        if (at + value.length > total) throw new BundleFormatError('The file changed while it was read. Pick it again.');
        bytes.set(value, at);
        at += value.length;
        report(at);
      }
    } finally {
      signal?.removeEventListener('abort', onAbort);
      if (signal?.aborted) await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    if (at !== total) throw new BundleFormatError('The file changed while it was read. Pick it again.');
  } else {
    bytes = new Uint8Array(await file.arrayBuffer());
    check();
    report(bytes.length);
  }
  parseBundleHeader(bytes);
  return bytes;
}
