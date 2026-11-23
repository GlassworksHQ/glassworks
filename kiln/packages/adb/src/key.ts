// This browser's adb key. The public half is baked into the patched boot image
// (a frame's boot overlay, e.g. overlay.d/sbin/glassworks_adb_keys read by its firstboot script), so the device authorizes
// this browser on its first boot without a prompt on the screen. Kept in IndexedDB so a later visit
// (undo, re-run) is still authorized.
import { adbGeneratePublicKey, type AdbCredentialStore, type AdbPrivateKey } from '@yume-chan/adb';

/**
 * The name this browser's key gives itself (the `user@host` after the key), which a device shows next to it, e.g. on
 * Manage → Security. Display only: devices match the key itself, so a key saved under an older name still gets in.
 */
export const KEY_NAME = 'kiln@glassworks';

/** Where the PKCS#8 private key lives. IndexedDB in the browser, memory in the tests. */
export interface KeyStorage {
  load(): Promise<Uint8Array | undefined>;
  save(pkcs8: Uint8Array): Promise<void>;
}

export class IndexedDbKeyStorage implements KeyStorage {
  // the database keeps its first name: renaming it would lose every browser's saved key, and with it the devices that let it in
  constructor(private readonly db = 's1-unleashed', private readonly store = 'adb-key') {}

  private open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(this.db, 1);
      r.onupgradeneeded = () => r.result.createObjectStore(this.store);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
    });
  }

  private async tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await this.open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const req = f(db.transaction(this.store, mode).objectStore(this.store));
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error ?? new Error('IndexedDB request failed'));
      });
    } finally {
      db.close();
    }
  }

  async load(): Promise<Uint8Array | undefined> {
    const v = await this.tx<unknown>('readonly', (s) => s.get('pkcs8'));
    return v instanceof Uint8Array ? v : v instanceof ArrayBuffer ? new Uint8Array(v) : undefined;
  }

  async save(pkcs8: Uint8Array): Promise<void> {
    await this.tx('readwrite', (s) => s.put(pkcs8, 'pkcs8'));
  }
}

export class MemoryKeyStorage implements KeyStorage {
  private v: Uint8Array | undefined;
  async load() { return this.v; }
  async save(p: Uint8Array) { this.v = p; }
}

async function generatePkcs8(): Promise<Uint8Array> {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
    true, ['sign', 'verify'],
  );
  return new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
}

function base64(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export interface AdbKey {
  /** One adb_keys line: base64 Android RSA public key, a space, the name (like ~/.android/adbkey.pub). */
  publicKey: string;
  store: AdbCredentialStore;
}

/** Loads this browser's adb key, creating it the first time. */
export async function loadOrCreateAdbKey(storage: KeyStorage = new IndexedDbKeyStorage()): Promise<AdbKey> {
  let pkcs8 = await storage.load();
  if (!pkcs8) {
    pkcs8 = await generatePkcs8();
    await storage.save(pkcs8);
  }
  return adbKeyFromPkcs8(pkcs8);
}

/**
 * The AdbKey for a PKCS#8 private key, in memory only. For this browser's key (loadOrCreateAdbKey),
 * and for a key a project publishes for its own images, which a device on that image trusts (a
 * community image's project key): that one is never saved, so it is never offered to another device.
 */
export function adbKeyFromPkcs8(pkcs8: Uint8Array, name: string = KEY_NAME): AdbKey {
  const key: AdbPrivateKey = { buffer: pkcs8, name };
  return {
    publicKey: `${base64(adbGeneratePublicKey(pkcs8))} ${name}`,
    store: {
      generateKey: () => key, // never needed: the device already trusts this key
      iterateKeys: () => [key],
    },
  };
}

/** adb's own key file (`adbkey`: a PEM `PRIVATE KEY`, PKCS#8) as its DER bytes. Anything else is refused. */
export function pemToPkcs8(pem: string): Uint8Array {
  const m = /-----BEGIN PRIVATE KEY-----([\s\S]+?)-----END PRIVATE KEY-----/.exec(pem);
  if (!m) throw new Error('this isn\'t an adb key file (a PKCS#8 PRIVATE KEY)');
  return Uint8Array.from(atob(m[1]!.replace(/\s+/g, '')), (c) => c.charCodeAt(0));
}
