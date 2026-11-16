// An APK's package name, read in the browser before anything goes to the device: the zip's
// AndroidManifest.xml, in Android's binary XML (AXML), its <manifest package="…">. So a wrong file is
// named and refused, never installed.

const td16 = new TextDecoder('utf-16le');
const td8 = new TextDecoder();

async function inflateRaw(data: Uint8Array): Promise<Uint8Array> {
  const s = new Blob([data as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

/** The names of the files in a zip, from its central directory. */
export function zipNames(zip: Uint8Array): string[] {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const { count, at: first } = centralDirectory(zip, dv);
  const out: string[] = [];
  for (let i = 0, at = first; i < count; i++) {
    if (dv.getUint32(at, true) !== 0x02014b50) throw new Error('this APK\'s zip directory is broken');
    const nlen = dv.getUint16(at + 28, true);
    out.push(td8.decode(zip.subarray(at + 46, at + 46 + nlen)));
    at += 46 + nlen + dv.getUint16(at + 30, true) + dv.getUint16(at + 32, true);
  }
  return out;
}

function centralDirectory(zip: Uint8Array, dv: DataView): { count: number; at: number } {
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) return { count: dv.getUint16(i + 10, true), at: dv.getUint32(i + 16, true) };
  }
  throw new Error('this file isn\'t an APK (no zip directory)');
}

/** One file out of a zip, by name (stored or deflated). Undefined if it isn't there. */
export async function zipEntry(zip: Uint8Array, name: string): Promise<Uint8Array | undefined> {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const cd = centralDirectory(zip, dv);
  const count = cd.count;
  let at = cd.at;
  for (let i = 0; i < count; i++) {
    if (dv.getUint32(at, true) !== 0x02014b50) throw new Error('this APK\'s zip directory is broken');
    const method = dv.getUint16(at + 10, true), csize = dv.getUint32(at + 20, true);
    const nlen = dv.getUint16(at + 28, true), xlen = dv.getUint16(at + 30, true), clen = dv.getUint16(at + 32, true);
    const local = dv.getUint32(at + 42, true);
    if (td8.decode(zip.subarray(at + 46, at + 46 + nlen)) === name) {
      const start = local + 30 + dv.getUint16(local + 26, true) + dv.getUint16(local + 28, true);
      const data = zip.subarray(start, start + csize);
      if (method === 0) return data;
      if (method === 8) return inflateRaw(data);
      throw new Error(`this APK compresses ${name} in a way Kiln doesn't read (method ${method})`);
    }
    at += 46 + nlen + xlen + clen;
  }
  return undefined;
}

/**
 * The document's chunks, bounds checked as Android's ResXMLTree does: the declared document size
 * within the data, and every chunk within the document. A manifest cut short is a ManifestError, not
 * a tree read from what is left.
 */
function* xmlChunks(xml: Uint8Array, dv: DataView): Generator<{ at: number; type: number; hsize: number; size: number }> {
  if (xml.length < 8 || dv.getUint16(0, true) !== 0x0003) throw new Error('AndroidManifest.xml isn\'t binary XML');
  const end = dv.getUint32(4, true), first = dv.getUint16(2, true);
  if (end > xml.length || first < 8 || first > end) throw new ManifestError();
  for (let at = first; at < end;) {
    if (at + 8 > end) throw new ManifestError();
    const type = dv.getUint16(at, true), hsize = dv.getUint16(at + 2, true), size = dv.getUint32(at + 4, true);
    if (size < 8 || hsize < 8 || hsize > size || at + size > end) throw new ManifestError();
    yield { at, type, hsize, size };
    at += size;
  }
}

/** A string pool chunk (RES_STRING_POOL_TYPE) at `at`: its strings, UTF-8 or UTF-16. */
function stringPool(xml: Uint8Array, dv: DataView, at: number, hsize: number): string[] {
  const n = dv.getUint32(at + 8, true), utf8 = !!(dv.getUint32(at + 16, true) & 0x100), base = at + dv.getUint32(at + 20, true);
  const strings: string[] = [];
  for (let i = 0; i < n; i++) {
    let p = base + dv.getUint32(at + hsize + i * 4, true);
    if (utf8) {
      p += xml[p]! & 0x80 ? 2 : 1;               // length in UTF-16 units
      let len = xml[p]!; p++;
      if (len & 0x80) { len = ((len & 0x7f) << 8) | xml[p]!; p++; }
      strings.push(td8.decode(xml.subarray(p, p + len)));
    } else {
      let len = dv.getUint16(p, true); p += 2;
      if (len & 0x8000) { len = ((len & 0x7fff) << 16) | dv.getUint16(p, true); p += 2; }
      strings.push(td16.decode(xml.subarray(p, p + len * 2)));
    }
  }
  return strings;
}

/** Android's resource ids for the manifest attributes read here (android.R.attr). */
const ATTR_IDS: Record<string, number> = { minSdkVersion: 0x0101020c };
/** Attributes that only count without a namespace. */
const NO_NAMESPACE = new Set(['package']);

/**
 * The value of attribute `attr` on the first element `element` in binary XML: a string, or a number
 * for an integer value (TYPE_INT_DEC or TYPE_INT_HEX). A framework attribute (ATTR_IDS) is found by its
 * resource id, as Android finds it; by name only when the build gave it no id and it is namespaced.
 * Any other value type (a reference, a float, …) throws: Kiln can't tell what Android would read.
 */
export function axmlAttribute(xml: Uint8Array, element: string, attr: string): string | number | undefined {
  const dv = new DataView(xml.buffer, xml.byteOffset, xml.byteLength);
  let strings: string[] = [];
  let resIds: number[] = [];
  for (const { at, type, hsize, size } of xmlChunks(xml, dv)) {
    if (type === 0x0001) {
      strings = stringPool(xml, dv, at, hsize);
      resIds = []; // the resource map belongs to the pool before it
    } else if (type === 0x0180) {
      resIds = [];
      for (let o = at + hsize; o < at + size; o += 4) resIds.push(dv.getUint32(o, true));
    } else if (type === 0x0102) {
      const ext = at + hsize;
      if (strings[dv.getUint32(ext + 4, true)] === element) {
        const aStart = dv.getUint16(ext + 8, true), aSize = dv.getUint16(ext + 10, true), aCount = dv.getUint16(ext + 12, true);
        for (let i = 0; i < aCount; i++) {
          const a = ext + aStart + i * aSize;
          const ns = dv.getUint32(a, true), nameIdx = dv.getUint32(a + 4, true);
          const id = ATTR_IDS[attr];
          if (id !== undefined) {
            // an unqualified minSdkVersion, or one with another id, isn't the one Android checks
            const byId = resIds[nameIdx];
            if (ns === 0xffffffff || (byId !== undefined ? byId !== id : strings[nameIdx] !== attr)) continue;
          } else if (strings[nameIdx] !== attr) continue;
          // <manifest package> has no namespace: an android:package (or any namespaced one) isn't it
          if (NO_NAMESPACE.has(attr) && ns !== 0xffffffff) continue;
          const raw = dv.getUint32(a + 8, true), kind = xml[a + 15], data = dv.getUint32(a + 16, true);
          // the typed value is what Android reads; the raw string is only a copy that could disagree with it
          if (kind === 0x10 || kind === 0x11) return data | 0;      // TYPE_INT_DEC, TYPE_INT_HEX
          if (kind === 0x03) return strings[data] ?? (raw !== 0xffffffff ? strings[raw] : undefined); // TYPE_STRING
          throw new Error(`this APK's ${element} ${attr} is a value Kiln can't read (type 0x${(kind ?? 0).toString(16)}), so it can't check it`);
        }
        return undefined;
      }
    }
  }
  return undefined;
}

export interface ApkInfo {
  package: string;
  /** android:minSdkVersion: a number, or a codename for a preview SDK; undefined when not set (API 1) */
  minSdk?: number | string;
  /** the ABIs it has native code for (lib/<abi>/), none for a pure-Java APK */
  abis: string[];
}

/** What an APK needs of a device, read in the browser before anything goes to it. */
export async function apkInfo(apk: Uint8Array): Promise<ApkInfo> {
  const manifest = await zipEntry(apk, 'AndroidManifest.xml');
  if (!manifest) throw new Error('this file isn\'t an APK (no AndroidManifest.xml)');
  const pkg = reading(() => axmlAttribute(manifest, 'manifest', 'package'));
  if (typeof pkg !== 'string' || !pkg) throw new Error('this APK\'s manifest names no package');
  const min = reading(() => axmlAttribute(manifest, 'uses-sdk', 'minSdkVersion'));
  const abis = [...new Set(zipNames(apk).map((n) => /^lib\/([^/]+)\/[^/]+\.so$/.exec(n)?.[1]).filter((x): x is string => !!x))];
  return { package: pkg, ...(min !== undefined ? { minSdk: typeof min === 'string' && /^\d+$/.test(min) ? Number(min) : min } : {}), abis };
}

/** The package an APK installs as, e.g. sh.edm.s1kiosk. Throws for a file that isn't an APK. */
export async function apkPackage(apk: Uint8Array): Promise<string> {
  return (await apkInfo(apk)).package;
}

/** A value in binary XML: a string, an integer, a boolean, or another type (a reference, …) as it is. */
export type AxmlValue = string | number | boolean | { type: number; data: number };
/** One element: its attributes (android:… for namespaced ones, by resource id where the build kept it) and children. */
export interface AxmlElement { name: string; attrs: Record<string, AxmlValue>; children: AxmlElement[] }

/** Framework attributes read by resource id (android.R.attr), as Android reads them. */
const ATTR_BY_ID: Record<number, string> = { 0x01010003: 'name', 0x0101000e: 'enabled', 0x01010202: 'targetActivity' };

/** Binary XML as a tree. An element left open at the end (no end event) still counts. */
export function axmlTree(xml: Uint8Array): AxmlElement[] {
  const dv = new DataView(xml.buffer, xml.byteOffset, xml.byteLength);
  let strings: string[] = [];
  let resIds: number[] = [];
  const roots: AxmlElement[] = [];
  const open: AxmlElement[] = [];
  for (const { at, type, hsize, size } of xmlChunks(xml, dv)) {
    if (type === 0x0001) { strings = stringPool(xml, dv, at, hsize); resIds = []; } // the resource map belongs to the pool before it
    else if (type === 0x0180) { resIds = []; for (let o = at + hsize; o < at + size; o += 4) resIds.push(dv.getUint32(o, true)); }
    else if (type === 0x0102) {
      const ext = at + hsize;
      const el: AxmlElement = { name: strings[dv.getUint32(ext + 4, true)] ?? '', attrs: {}, children: [] };
      const aStart = dv.getUint16(ext + 8, true), aSize = dv.getUint16(ext + 10, true), aCount = dv.getUint16(ext + 12, true);
      if (aCount && (aSize < 20 || ext + aStart + aCount * aSize > at + size)) throw new ManifestError();
      for (let i = 0; i < aCount; i++) {
        const a = ext + aStart + i * aSize;
        const ns = dv.getUint32(a, true), nameIdx = dv.getUint32(a + 4, true);
        const id = resIds[nameIdx];
        const bare = (id !== undefined ? ATTR_BY_ID[id] : undefined) ?? strings[nameIdx] ?? '';
        const key = ns === 0xffffffff ? bare : `android:${bare}`;
        const kind = xml[a + 15]!, data = dv.getUint32(a + 16, true);
        el.attrs[key] = kind === 0x03 ? strings[data] ?? '' : kind === 0x10 || kind === 0x11 ? data | 0 : kind === 0x12 ? data !== 0 : { type: kind, data };
      }
      (open.at(-1)?.children ?? roots).push(el);
      open.push(el);
    } else if (type === 0x0103) open.pop();
  }
  return roots;
}

/** A manifest Kiln can't parse (cut short, or not what its chunks say): reported plainly, never as a RangeError. */
export class ManifestError extends Error {
  constructor() { super('this APK\'s AndroidManifest.xml can\'t be read'); }
}

/** Runs a manifest read; a parse that runs off the data is a ManifestError. */
function reading<T>(f: () => T): T {
  try { return f(); } catch (e) { if (e instanceof RangeError) throw new ManifestError(); throw e; }
}

const MAIN = 'android.intent.action.MAIN', HOME = 'android.intent.category.HOME', DEFAULT = 'android.intent.category.DEFAULT';

/**
 * The APK's home screens: activities (and aliases) with one intent filter that has MAIN, HOME and
 * DEFAULT, and no <data> scheme or MIME type (the HOME intent carries no data). All three in the same filter, because Android 6's `am start -a MAIN -c HOME` and its
 * chooser resolve with MATCH_DEFAULT_ONLY, which drops a filter without DEFAULT
 * (ActivityStackSupervisor.resolveActivity, IntentResolver.buildResolveList).
 *
 * `on`: Android can open it. `off`: android:enabled="false" on it or on its <application>.
 * `unclear`: turned on or off by a resource Kiln doesn't resolve (@bool/…). An activity-alias counts
 * by its own enabled bit, as PackageParser gives it one: a disabled target class still starts through
 * an enabled alias (ActivityThread instantiates the target by name).
 */
export async function apkHome(apk: Uint8Array): Promise<{ on: string[]; off: string[]; unclear: string[] }> {
  const manifest = await zipEntry(apk, 'AndroidManifest.xml');
  if (!manifest) throw new Error('this file isn\'t an APK (no AndroidManifest.xml)');
  const out = { on: [] as string[], off: [] as string[], unclear: [] as string[] };
  const pkg = reading(() => axmlAttribute(manifest, 'manifest', 'package'));
  const full = (n: string) => (n.startsWith('.') && typeof pkg === 'string' ? pkg + n : n);
  const has = (f: AxmlElement, el: string, name: string) => f.children.some((c) => c.name === el && c.attrs['android:name'] === name);
  // a <data> scheme or MIME type: the data-less HOME intent then doesn't match (IntentFilter.matchData: NO_MATCH_DATA, NO_MATCH_TYPE)
  const wantsData = (f: AxmlElement) => f.children.some((c) => c.name === 'data'
    && ((c.attrs['android:scheme'] !== undefined && c.attrs['android:scheme'] !== '') || c.attrs['android:mimeType'] !== undefined));
  for (const m of reading(() => axmlTree(manifest)).filter((e) => e.name === 'manifest')) {
    for (const app of m.children.filter((e) => e.name === 'application')) {
      for (const act of app.children.filter((e) => e.name === 'activity' || e.name === 'activity-alias')) {
        const home = act.children.some((f) => f.name === 'intent-filter' && has(f, 'action', MAIN) && has(f, 'category', HOME) && has(f, 'category', DEFAULT) && !wantsData(f));
        if (!home) continue;
        const name = full(String(act.attrs['android:name'] ?? '?'));
        const states = [app.attrs['android:enabled'], act.attrs['android:enabled']];
        if (states.some((v) => v === false)) out.off.push(name);
        else if (states.every((v) => v === undefined || v === true)) out.on.push(name);
        else out.unclear.push(name);
      }
    }
  }
  return out;
}
