// A tiny stand-in APK: a zip with only an AndroidManifest.xml in binary XML, naming `pkg`. Enough for
// apkPackage, the tests and Kiln's demos. Nothing in it would install.

function axml(pkg: string, utf8: boolean): Uint8Array {
  const strs = ['manifest', 'package', pkg];
  const enc = new TextEncoder();
  const bodies = strs.map((s) => {
    if (utf8) { const b = enc.encode(s); return Uint8Array.of(s.length, b.length, ...b, 0); }
    const out = new Uint8Array(2 + s.length * 2 + 2);
    const dv = new DataView(out.buffer);
    dv.setUint16(0, s.length, true);
    for (let i = 0; i < s.length; i++) dv.setUint16(2 + i * 2, s.charCodeAt(i), true);
    return out;
  });
  const data = new Uint8Array(Math.ceil(bodies.reduce((n, b) => n + b.length, 0) / 4) * 4);
  const offsets: number[] = [];
  bodies.reduce((o, b) => { offsets.push(o); data.set(b, o); return o + b.length; }, 0);
  const poolHdr = 28, poolSize = poolHdr + strs.length * 4 + data.length;
  const elemSize = 16 + 20 + 20;
  const total = 8 + poolSize + elemSize;
  const x = new Uint8Array(total);
  const v = new DataView(x.buffer);
  v.setUint16(0, 0x0003, true); v.setUint16(2, 8, true); v.setUint32(4, total, true);
  let p = 8;
  v.setUint16(p, 0x0001, true); v.setUint16(p + 2, poolHdr, true); v.setUint32(p + 4, poolSize, true);
  v.setUint32(p + 8, strs.length, true); v.setUint32(p + 12, 0, true); v.setUint32(p + 16, utf8 ? 0x100 : 0, true);
  v.setUint32(p + 20, poolHdr + strs.length * 4, true); v.setUint32(p + 24, 0, true);
  offsets.forEach((o, i) => v.setUint32(p + poolHdr + i * 4, o, true));
  x.set(data, p + poolHdr + strs.length * 4);
  p += poolSize;
  v.setUint16(p, 0x0102, true); v.setUint16(p + 2, 16, true); v.setUint32(p + 4, elemSize, true);
  v.setUint32(p + 8, 1, true); v.setUint32(p + 12, 0xffffffff, true);
  const e = p + 16;
  v.setUint32(e, 0xffffffff, true); v.setUint32(e + 4, 0, true); v.setUint16(e + 8, 20, true); v.setUint16(e + 10, 20, true); v.setUint16(e + 12, 1, true);
  const a = e + 20;
  v.setUint32(a, 0xffffffff, true); v.setUint32(a + 4, 1, true); v.setUint32(a + 8, 2, true);
  v.setUint16(a + 12, 8, true); x[a + 15] = 0x03; v.setUint32(a + 16, 2, true);
  return x;
}

/** A zip holding one stored file. */
function zip(name: string, body: Uint8Array): Uint8Array {
  const n = new TextEncoder().encode(name);
  const local = new Uint8Array(30 + n.length), central = new Uint8Array(46 + n.length), end = new Uint8Array(22);
  const lv = new DataView(local.buffer), cv = new DataView(central.buffer), ev = new DataView(end.buffer);
  lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint32(18, body.length, true); lv.setUint32(22, body.length, true); lv.setUint16(26, n.length, true);
  local.set(n, 30);
  cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint32(20, body.length, true); cv.setUint32(24, body.length, true);
  cv.setUint16(28, n.length, true); cv.setUint32(42, 0, true);
  central.set(n, 46);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, 1, true); ev.setUint16(10, 1, true);
  ev.setUint32(12, central.length, true); ev.setUint32(16, local.length + body.length, true);
  const out = new Uint8Array(local.length + body.length + central.length + end.length);
  out.set(local); out.set(body, local.length); out.set(central, local.length + body.length); out.set(end, local.length + body.length + central.length);
  return out;
}

export function fakeApk(pkg = 'sh.edm.s1kiosk', o: { utf8?: boolean } = {}): Uint8Array {
  return zip('AndroidManifest.xml', axml(pkg, !!o.utf8));
}
