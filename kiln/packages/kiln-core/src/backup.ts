// The panel's own partitions, kept in this browser (IndexedDB) and offered as a zip download.
// It is the undo: the first backup of a panel is its only copy of the original software.
// Which partitions, and the id prefix, come from the frame's Recipe (BackupPlan).
import { crc32, zipBlob } from './zip';

export interface BackupFile { part: string; data: Uint8Array; sha256: string; crc: number }
export interface BackupMeta {
  id: string;            // <idPrefix>-YYYYMMDD-HHMMSS
  created: string;       // ISO time
  serial?: string;       // fastboot serial: shown only in this browser, never sent anywhere
  /** The boot partition was already patched (the recipe's `isPatched`, a re-run): not the original software. */
  bootWasPatched: boolean;
  files: { part: string; size: number; sha256: string }[];
}

export function toHex(b: ArrayBuffer): string {
  return Array.from(new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');
}

export async function backupFile(part: string, data: Uint8Array): Promise<BackupFile> {
  return { part, data, sha256: toHex(await crypto.subtle.digest('SHA-256', data as Uint8Array<ArrayBuffer>)), crc: crc32(data) };
}

export function backupId(prefix: string, d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${prefix}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

/** Same format as `shasum -a 256 *.img` (fel-install.sh), so the CLI's --undo accepts the unzipped backup. */
export function sha256sums(files: { part: string; sha256: string }[]): string {
  return [...files].sort((a, b) => (a.part < b.part ? -1 : 1)).map((f) => `${f.sha256}  ${f.part}.img\n`).join('');
}

export function backupZip(meta: BackupMeta, files: BackupFile[]): Blob {
  const sums = new TextEncoder().encode(sha256sums(files));
  const info = new TextEncoder().encode(JSON.stringify({ ...meta, serial: undefined }, null, 2) + '\n');
  return zipBlob([
    ...files.map((f) => ({ name: `${meta.id}/${f.part}.img`, data: f.data, crc: f.crc })),
    { name: `${meta.id}/SHA256SUMS`, data: sums, crc: crc32(sums) },
    { name: `${meta.id}/backup.json`, data: info, crc: crc32(info) },
  ]);
}

/** A file in a named zip: a plain file name, no folders. */
export interface NamedPart { filename: string; data: Uint8Array }

const PART_NAME = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$/;

/**
 * A zip of files under their own names (`restore-<x>.sparse`, `manifest.json`), in a folder `dir`, with a
 * SHA256SUMS of them all: a restore point's download, which a person can check with `shasum -a 256 -c`.
 */
export async function namedBackupZip(parts: readonly NamedPart[], o: { dir?: string } = {}): Promise<Blob> {
  // Snapshot filenames and bytes before the first await so caller buffer reuse cannot skew SHA256SUMS vs entries.
  const items = parts.map((p) => ({ filename: p.filename, data: Uint8Array.from(p.data) }));
  const names = items.map((p) => p.filename);
  for (const n of names) if (!PART_NAME.test(n) || n === 'SHA256SUMS') throw new Error(`zip: "${n}" isn't a plain file name`);
  if (new Set(names).size !== names.length) throw new Error('zip: two files share a name');
  if (o.dir !== undefined && !PART_NAME.test(o.dir)) throw new Error(`zip: "${o.dir}" isn't a plain folder name`);
  const at = (n: string) => (o.dir ? `${o.dir}/${n}` : n);
  const sums = new TextEncoder().encode((await Promise.all([...items].sort((a, b) => (a.filename < b.filename ? -1 : 1))
    .map(async (p) => `${toHex(await crypto.subtle.digest('SHA-256', p.data as Uint8Array<ArrayBuffer>))}  ${p.filename}\n`))).join(''));
  return zipBlob([
    ...items.map((p) => ({ name: at(p.filename), data: p.data, crc: crc32(p.data) })),
    { name: at('SHA256SUMS'), data: sums, crc: crc32(sums) },
  ]);
}

/**
 * Reads a zip made by namedBackupZip (STORED only) back into its files by name, checking each against its SHA256SUMS.
 * Throws on a compressed zip, a file SHA256SUMS doesn't list, or one that doesn't match.
 */
export async function readNamedBackupZip(zip: Blob): Promise<{ dir?: string; files: NamedPart[] }> {
  const buf = new Uint8Array(await zip.arrayBuffer());
  const v = new DataView(buf.buffer);
  const found = new Map<string, Uint8Array>();
  let dir: string | undefined;
  for (let o = 0; o + 30 <= buf.length && v.getUint32(o, true) === 0x04034b50; ) {
    if (v.getUint16(o + 8, true) !== 0) throw new Error('This isn\'t a restore point Kiln made (a compressed zip).');
    const size = v.getUint32(o + 18, true), nlen = v.getUint16(o + 26, true), xlen = v.getUint16(o + 28, true);
    const name = new TextDecoder().decode(buf.subarray(o + 30, o + 30 + nlen));
    const parts = name.split('/');
    if (parts.length > 2) throw new Error(`This isn't a restore point Kiln made (${name}).`);
    if (parts.length === 2) dir = parts[0];
    const base = parts.at(-1)!;
    if (base) found.set(base, buf.slice(o + 30 + nlen + xlen, o + 30 + nlen + xlen + size));
    o += 30 + nlen + xlen + size;
  }
  const sums = found.get('SHA256SUMS');
  if (!sums) throw new Error('This zip has no SHA256SUMS, so it isn\'t a restore point Kiln made.');
  found.delete('SHA256SUMS');
  const listed = new Map(new TextDecoder().decode(sums).trim().split('\n').map((l) => { const [sha, n] = l.split(/\s+/); return [n ?? '', sha ?? ''] as const; }));
  for (const [n, data] of found) {
    const want = listed.get(n);
    if (!want) throw new Error(`${n} isn't in the restore point's SHA256SUMS.`);
    if (toHex(await crypto.subtle.digest('SHA-256', data as Uint8Array<ArrayBuffer>)) !== want) throw new Error(`${n} doesn't match its SHA256SUMS: the restore point is damaged.`);
  }
  for (const n of listed.keys()) if (!found.has(n)) throw new Error(`The restore point is missing ${n}.`);
  return { ...(dir ? { dir } : {}), files: [...found].map(([filename, data]) => ({ filename, data })) };
}

/** Parses a backup zip made by backupZip (STORED only) back into files, checking SHA256SUMS. */
export async function readBackupZip(zip: Blob): Promise<{ id: string; files: Map<string, Uint8Array> }> {
  const buf = new Uint8Array(await zip.arrayBuffer());
  const v = new DataView(buf.buffer);
  const files = new Map<string, Uint8Array>();
  let id = '';
  let sums = '';
  for (let o = 0; o + 30 <= buf.length && v.getUint32(o, true) === 0x04034b50; ) {
    if (v.getUint16(o + 8, true) !== 0) throw new Error('not a backup made by this installer (compressed zip)');
    const size = v.getUint32(o + 18, true), nlen = v.getUint16(o + 26, true), xlen = v.getUint16(o + 28, true);
    const name = new TextDecoder().decode(buf.subarray(o + 30, o + 30 + nlen));
    const data = buf.subarray(o + 30 + nlen + xlen, o + 30 + nlen + xlen + size);
    const [dir, base] = name.split('/');
    if (dir && base) {
      id = dir;
      if (base === 'SHA256SUMS') sums = new TextDecoder().decode(data);
      else if (base.endsWith('.img')) files.set(base.slice(0, -4), data);
    }
    o += 30 + nlen + xlen + size;
  }
  if (!sums) throw new Error('this zip has no SHA256SUMS: not a panel backup');
  for (const line of sums.trim().split('\n')) {
    const [sha, name] = line.split(/\s+/);
    const part = name?.replace(/\.img$/, '');
    const data = part ? files.get(part) : undefined;
    if (!data) throw new Error(`backup is missing ${name}`);
    const got = toHex(await crypto.subtle.digest('SHA-256', data as Uint8Array<ArrayBuffer>));
    if (got !== sha) throw new Error(`${name} does not match its SHA256SUMS: the backup is damaged`);
  }
  return { id, files };
}

/** IndexedDB: one record per backup (meta + files). */
export class BackupStore {
  constructor(private readonly db: string) {}

  private open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(this.db, 1);
      r.onupgradeneeded = () => { r.result.createObjectStore('meta', { keyPath: 'id' }); r.result.createObjectStore('files'); };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
    });
  }

  async save(meta: BackupMeta, files: BackupFile[]): Promise<void> {
    try { await navigator.storage?.persist?.(); } catch { /* best effort */ }
    const db = await this.open();
    try {
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(['meta', 'files'], 'readwrite');
        tx.objectStore('meta').put(meta);
        for (const f of files) tx.objectStore('files').put(f, `${meta.id}/${f.part}`);
        tx.oncomplete = () => resolve();
        tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('could not save the backup in this browser (out of space?)'));
      });
    } finally { db.close(); }
  }

  async list(): Promise<BackupMeta[]> {
    const db = await this.open();
    try {
      return await new Promise((resolve, reject) => {
        const r = db.transaction('meta').objectStore('meta').getAll();
        r.onsuccess = () => resolve((r.result as BackupMeta[]).sort((a, b) => (a.created < b.created ? -1 : 1)));
        r.onerror = () => reject(r.error);
      });
    } finally { db.close(); }
  }

  async files(meta: BackupMeta): Promise<BackupFile[]> {
    const db = await this.open();
    try {
      const store = db.transaction('files').objectStore('files');
      return await Promise.all(meta.files.map((f) => new Promise<BackupFile>((resolve, reject) => {
        const r = store.get(`${meta.id}/${f.part}`);
        r.onsuccess = () => (r.result ? resolve(r.result as BackupFile) : reject(new Error(`backup ${meta.id} lost ${f.part}`)));
        r.onerror = () => reject(r.error);
      })));
    } finally { db.close(); }
  }

  /** The oldest backup whose boot was not already patched: the original software. */
  async original(): Promise<BackupMeta | undefined> {
    return (await this.list()).find((m) => !m.bootWasPatched);
  }
}
