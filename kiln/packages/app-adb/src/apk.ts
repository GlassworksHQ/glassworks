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

/** Android's resource ids for the manifest attributes read here (android.R.attr). */
const ATTR_IDS: Record<string, number> = { minSdkVersion: 0x0101020c };
/** Attributes that only count without a namespace. */
const NO_NAMESPACE = new Set(['package']);

/**
 * The value of attribute `attr` on the first element `element` in binary XML: a string, or a number
 * for an integer value (TYPE_INT_DEC or TYPE_INT_HEX). The attribute is found by name, or by its
 * resource id when the build left the name out.
 */
export function axmlAttribute(xml: Uint8Array, element: string, attr: string): string | number | undefined {
  const dv = new DataView(xml.buffer, xml.byteOffset, xml.byteLength);
  if (dv.getUint16(0, true) !== 0x0003) throw new Error('AndroidManifest.xml isn\'t binary XML');
  let strings: string[] = [];
  let resIds: number[] = [];
  for (let at = dv.getUint16(2, true); at + 8 <= xml.length;) {
    const type = dv.getUint16(at, true), hsize = dv.getUint16(at + 2, true), size = dv.getUint32(at + 4, true);
    if (size < 8) break;
    if (type === 0x0001) {
      const n = dv.getUint32(at + 8, true), utf8 = !!(dv.getUint32(at + 16, true) & 0x100), base = at + dv.getUint32(at + 20, true);
      strings = [];
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
          if (strings[nameIdx] !== attr && (ATTR_IDS[attr] === undefined || resIds[nameIdx] !== ATTR_IDS[attr])) continue;
          // <manifest package> has no namespace: an android:package (or any namespaced one) isn't it
          if (NO_NAMESPACE.has(attr) && ns !== 0xffffffff) continue;
          const raw = dv.getUint32(a + 8, true), kind = xml[a + 15], data = dv.getUint32(a + 16, true);
          if (raw !== 0xffffffff) return strings[raw];
          if (kind === 0x03) return strings[data];                 // TYPE_STRING
          if (kind === 0x10 || kind === 0x11) return data | 0;      // TYPE_INT_DEC, TYPE_INT_HEX
          return undefined;
        }
        return undefined;
      }
    }
    at += size;
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
  const pkg = axmlAttribute(manifest, 'manifest', 'package');
  if (typeof pkg !== 'string' || !pkg) throw new Error('this APK\'s manifest names no package');
  const min = axmlAttribute(manifest, 'uses-sdk', 'minSdkVersion');
  const abis = [...new Set(zipNames(apk).map((n) => /^lib\/([^/]+)\/[^/]+\.so$/.exec(n)?.[1]).filter((x): x is string => !!x))];
  return { package: pkg, ...(min !== undefined ? { minSdk: typeof min === 'string' && /^\d+$/.test(min) ? Number(min) : min } : {}), abis };
}

/** The package an APK installs as, e.g. sh.edm.s1kiosk. Throws for a file that isn't an APK. */
export async function apkPackage(apk: Uint8Array): Promise<string> {
  return (await apkInfo(apk)).package;
}
