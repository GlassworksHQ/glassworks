// A community image from Kiln: this browser's adb key in a copy of its checked system image (one file,
// services.jar) and of its userdata (adb_keys), the network saved in a copy of a checked userdata (one
// file, from the system image's own template, read in place), and Wi-Fi switched on with one adb command.
// On small images made here: the real Glass Panes files can't go in the repo (hard rule 3).
import { createHash } from 'node:crypto';
import { zipEntry } from '@glassworks/app-adb';
import { Disk, Ext4, MemoryStore, mkfs, readSparse, writeSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import { ADB_KEYS, GLASS_PANES, IMAGE_OWNER_FINGERPRINT, SERVICES_JAR, WIFI_CONF, WrongImage, adbFingerprint, systemWithKey, userdataWithKey, userdataWithWifi, wifiOn, wifiTemplate, wpaPsk } from '../src';
import { fakeAdbKey, fakeServicesJar } from './fakes';

const BS = 4096;
const TEMPLATE = 'update_config=1\neapol_version=1\nap_scan=1\nfast_reauth=1\npmf=1\np2p_no_group_iface=1\n';
const sys = { mode: 0o755, uid: 0, gid: 0, label: 'u:object_r:system_file:s0', time: 1 };

async function sparse(d: Disk): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  await writeSparse(d, (b) => { parts.push(b.slice()); });
  return new Uint8Array(Buffer.concat(parts));
}
const blob = (b: Uint8Array) => new Blob([b as BlobPart]);

/** A system image with (or without) the Wi-Fi template, as a sparse file. */
async function systemImg(withTemplate = true): Promise<Uint8Array> {
  const d = new Disk(new MemoryStore(), BS, 8192, { zeroed: true });
  const fs = await mkfs(d, { time: 1, journalBlocks: 0 });
  await fs.mkdir('/etc', sys);
  await fs.mkdir('/etc/wifi', sys);
  if (withTemplate) await fs.writeFile('/etc/wifi/wpa_supplicant.conf', new TextEncoder().encode(TEMPLATE), { ...sys, mode: 0o644 });
  await fs.writeFile('/build.prop', new TextEncoder().encode('ro.build.fingerprint=x\n'), { ...sys, mode: 0o644 });
  await fs.flush();
  return sparse(d);
}

/** A userdata like Glass Panes' (read 2026-11-19): lost+found and /misc/adb, and no /misc/wifi. */
async function userdataImg(): Promise<Uint8Array> {
  const d = new Disk(new MemoryStore(), BS, 16384, { zeroed: true });
  const data = { uid: 1000, gid: 1000, label: 'u:object_r:system_data_file:s0', time: 1 };
  const fs = await mkfs(d, { time: 1, journalBlocks: 0, root: { mode: 0o771, ...data }, lostFound: { mode: 0o770, uid: 0, gid: 0, label: data.label } });
  await fs.mkdir('/misc', { mode: 0o1771, uid: 1000, gid: 9998, label: data.label, time: 1 });
  await fs.mkdir('/misc/adb', { mode: 0o2750, uid: 1000, gid: 2000, label: 'u:object_r:adb_keys_file:s0', time: 1 });
  await fs.writeFile('/misc/adb/adb_keys', new TextEncoder().encode('QAAAAkey project@glass-panes\n'), { mode: 0o640, uid: 1000, gid: 2000, label: 'u:object_r:adb_keys_file:s0', time: 1 });
  await fs.flush();
  return sparse(d);
}

const pin = (b: Uint8Array, name = 'userdata.sparse') => ({ name, size: b.length, sha256: createHash('sha256').update(b).digest('hex') });

async function open(b: Uint8Array): Promise<Ext4> {
  const { disk } = await readSparse(blob(b).stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }));
  return Ext4.open(disk);
}

/** Every path in the filesystem with its type, mode, owner and contents, for a before/after diff. */
async function tree(fs: Ext4, at = '/'): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const e of await fs.readdir(at)) {
    if (e.name === '.' || e.name === '..') continue;
    const p = at === '/' ? `/${e.name}` : `${at}/${e.name}`;
    const s = await fs.stat(p);
    out[p] = `${s.mode.toString(8)} ${s.uid}:${s.gid}${s.isFile ? ` ${createHash('sha256').update(await fs.readFile(p)).digest('hex')}` : ''}`;
    if (s.isDir) Object.assign(out, await tree(fs, p));
  }
  return out;
}

describe('the template', () => {
  it('comes from the system image\'s /etc/wifi/wpa_supplicant.conf, read where it lies', async () => {
    expect(await wifiTemplate(blob(await systemImg()))).toBe(TEMPLATE);
  });
  it('a system image without one is refused', async () => {
    await expect(wifiTemplate(blob(await systemImg(false)))).rejects.toThrow(/no \/etc\/wifi\/wpa_supplicant.conf/);
  });
});

describe('the network, saved in a copy of the checked userdata', () => {
  it('adds /misc/wifi and its wpa_supplicant.conf and changes nothing else', async () => {
    const src = await userdataImg();
    const parts: Uint8Array[] = [];
    await userdataWithWifi(blob(src), pin(src), new MemoryStore(), TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' }, (b) => { parts.push(b.slice()); });
    const before = await tree(await open(src));
    const fs = await open(new Uint8Array(Buffer.concat(parts)));
    const after = await tree(fs);
    const added = Object.keys(after).filter((p) => !(p in before)).sort();
    expect(added).toEqual(['/misc/wifi', WIFI_CONF]);
    for (const [p, v] of Object.entries(before)) if (p !== '/misc') expect(after[p], p).toBe(v);
    expect(after['/misc/wifi']).toBe('770 1010:1010');
    expect(after[WIFI_CONF]).toMatch(/^660 1000:1010 /);
    expect([await fs.label('/misc/wifi'), await fs.label(WIFI_CONF)]).toEqual(['u:object_r:wifi_data_file:s0', 'u:object_r:wifi_data_file:s0']);
    const conf = new TextDecoder().decode(await fs.readFile(WIFI_CONF));
    const psk = await wpaPsk('not-a-real-password', new TextEncoder().encode('Home Net'));
    expect(conf).toBe(`${TEMPLATE}\nnetwork={\n\tssid="Home Net"\n\tpsk=${psk}\n\tkey_mgmt=WPA-PSK\n\tpriority=1\n}\n`);
    expect(conf).not.toContain('not-a-real-password');
  });

  it('a stored file that doesn\'t match its pin is refused, and nothing is written', async () => {
    const src = await userdataImg();
    const sink: Uint8Array[] = [];
    await expect(userdataWithWifi(blob(src), { ...pin(src), sha256: '0'.repeat(64) }, new MemoryStore(), TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' }, (b) => { sink.push(b); }))
      .rejects.toBeInstanceOf(WrongImage);
    expect(sink).toEqual([]);
  });

  it('a password WPA can\'t take is refused before anything is written', async () => {
    const src = await userdataImg();
    const sink: Uint8Array[] = [];
    await expect(userdataWithWifi(blob(src), pin(src), new MemoryStore(), TEMPLATE, { ssid: 'Home Net', password: 'short' }, (b) => { sink.push(b); }))
      .rejects.toThrow(/8 to 63/);
    expect(sink).toEqual([]);
  });
});

describe('this browser\'s adb key, in a copy of the checked userdata (image 3)', () => {
  const KEY = fakeAdbKey(7, 'kiln@test');
  const copyWithKey = async (src: Uint8Array) => {
    const parts: Uint8Array[] = [];
    await userdataWithKey(blob(src), pin(src), new MemoryStore(), KEY, (b) => { parts.push(b.slice()); });
    return new Uint8Array(Buffer.concat(parts));
  };

  it('replaces adb_keys with this key alone, keeping its owner, mode and label, and changes nothing else', async () => {
    const src = await userdataImg();
    const before = await tree(await open(src));
    const fs = await open(await copyWithKey(src));
    const after = await tree(fs);
    expect(Object.keys(after).sort()).toEqual(Object.keys(before).sort());
    for (const [p, v] of Object.entries(before)) if (p !== ADB_KEYS) expect(after[p], p).toBe(v);
    expect(after[ADB_KEYS]).toMatch(/^640 1000:2000 /);
    expect(await fs.label(ADB_KEYS)).toBe('u:object_r:adb_keys_file:s0');
    expect(new TextDecoder().decode(await fs.readFile(ADB_KEYS))).toBe(`${KEY}\n`);
  });

  it('then the network goes on that copy: both changes, nothing else', async () => {
    const keyed = await copyWithKey(await userdataImg());
    const parts: Uint8Array[] = [];
    // a copy this page made: no pin to check it against (it was hashed as the adb-key step read the download)
    await userdataWithWifi(blob(keyed), undefined, new MemoryStore(), TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' }, (b) => { parts.push(b.slice()); });
    const fs = await open(new Uint8Array(Buffer.concat(parts)));
    expect(new TextDecoder().decode(await fs.readFile(ADB_KEYS))).toBe(`${KEY}\n`);
    expect(await fs.exists(WIFI_CONF)).toBe(true);
  });

  it('refuses a stored file that doesn\'t match its pin, the published project key, and a userdata with no adb_keys, writing nothing', async () => {
    const src = await userdataImg();
    const sink: Uint8Array[] = [];
    const to = (b: Uint8Array) => { sink.push(b); };
    await expect(userdataWithKey(blob(src), { ...pin(src), sha256: '0'.repeat(64) }, new MemoryStore(), KEY, to)).rejects.toBeInstanceOf(WrongImage);
    await expect(userdataWithKey(blob(src), pin(src), new MemoryStore(), 'not a key', to)).rejects.toThrow(/isn't an adb public key/);
    const bare = await (async () => {
      const d = new Disk(new MemoryStore(), BS, 16384, { zeroed: true });
      const fs = await mkfs(d, { time: 1, journalBlocks: 0 });
      await fs.flush();
      return sparse(d);
    })();
    await expect(userdataWithKey(blob(bare), pin(bare), new MemoryStore(), KEY, to)).rejects.toThrow(/has no \/misc\/adb\/adb_keys.*Nothing was changed/);
    expect(sink).toEqual([]);
  });
});

describe('this browser\'s adb key, in a copy of the checked system image (image 3)', () => {
  const KEY = fakeAdbKey(7, 'kiln@test');
  /** A system image like Glass Panes': its build.prop and its services.jar, which names the key it lets in. */
  async function glassPanesSystem(fingerprint: string = GLASS_PANES.fingerprint): Promise<Uint8Array> {
    const d = new Disk(new MemoryStore(), BS, 8192, { zeroed: true });
    const fs = await mkfs(d, { time: 1, journalBlocks: 0 });
    await fs.mkdir('/framework', sys);
    await fs.writeFile(SERVICES_JAR, await fakeServicesJar(), { ...sys, mode: 0o644 });
    await fs.mkdir('/etc', sys);
    await fs.mkdir('/etc/wifi', sys);
    await fs.writeFile('/etc/wifi/wpa_supplicant.conf', new TextEncoder().encode(TEMPLATE), { ...sys, mode: 0o644 });
    await fs.writeFile('/build.prop', new TextEncoder().encode(`ro.build.fingerprint=${fingerprint}\n`), { ...sys, mode: 0o644 });
    await fs.flush();
    return sparse(d);
  }
  const has = (b: Uint8Array, s: string) => Buffer.from(b).includes(Buffer.from(s));

  it('replaces services.jar with one that names this key in place of the image\'s own, and changes nothing else', async () => {
    const src = await glassPanesSystem();
    const before = await tree(await open(src));
    const parts: Uint8Array[] = [];
    await systemWithKey(blob(src), pin(src, 'system.img'), new MemoryStore(), KEY, (b) => { parts.push(b.slice()); });
    const fs = await open(new Uint8Array(Buffer.concat(parts)));
    const after = await tree(fs);
    expect(Object.keys(after).sort()).toEqual(Object.keys(before).sort());
    for (const [p, v] of Object.entries(before)) if (p !== SERVICES_JAR) expect(after[p], p).toBe(v);
    expect(after[SERVICES_JAR]).not.toBe(before[SERVICES_JAR]);
    expect(after[SERVICES_JAR]).toMatch(/^644 0:0 /);
    expect(await fs.label(SERVICES_JAR)).toBe('u:object_r:system_file:s0');
    const dex = (await zipEntry(await fs.readFile(SERVICES_JAR), 'classes.dex'))!;
    expect(has(dex, adbFingerprint(KEY))).toBe(true);
    expect(has(dex, IMAGE_OWNER_FINGERPRINT)).toBe(false);
  });

  it('refuses a stored file that doesn\'t match its pin, another image and a bad key, writing nothing', async () => {
    const src = await glassPanesSystem();
    const sink: Uint8Array[] = [];
    const to = (b: Uint8Array) => { sink.push(b); };
    await expect(systemWithKey(blob(src), { ...pin(src, 'system.img'), sha256: '0'.repeat(64) }, new MemoryStore(), KEY, to)).rejects.toBeInstanceOf(WrongImage);
    await expect(systemWithKey(blob(src), pin(src, 'system.img'), new MemoryStore(), 'not a key', to)).rejects.toThrow(/isn't an adb public key/);
    const other = await glassPanesSystem('Android/other/other:6.0.1/X/1:user/release-keys');
    await expect(systemWithKey(blob(other), pin(other, 'system.img'), new MemoryStore(), KEY, to)).rejects.toThrow(/build fingerprint differs.*Nothing was changed/);
    expect(sink).toEqual([]);
  });
});

describe('Wi-Fi on, after the first start', () => {
  it('waits for Android, runs svc wifi enable, and waits for the state machine to say enabled; it only reads otherwise', async () => {
    const cmds: string[] = [];
    let booted = 0, dumps = 0;
    // AOSP 6.0.1 dumpsys wifi: "Wi-Fi is enabling" while the driver starts, then "Wi-Fi is enabled"
    const p = { async run(c: string) {
      cmds.push(c);
      if (c === 'getprop sys.boot_completed') return { out: ++booted >= 2 ? '1\n' : '\n', code: 0 };
      if (c === 'dumpsys wifi') return { out: `Wi-Fi is ${++dumps >= 2 ? 'enabled' : 'enabling'}\nStay-awake conditions: 0\n`, code: 0 };
      return { out: '', code: 0 };
    }, async push() { throw new Error('no pushes'); } };
    expect(await wifiOn(p, async () => {})).toBe(true);
    expect(cmds).toEqual(['getprop sys.boot_completed', 'getprop sys.boot_completed', 'svc wifi enable', 'dumpsys wifi', 'dumpsys wifi']);
  });
  it('the saved switch alone isn\'t success: a driver that never comes up is unconfirmed', async () => {
    const p = { async run(c: string) { return { out: c === 'getprop sys.boot_completed' ? '1' : c === 'dumpsys wifi' ? 'Wi-Fi is disabled\n' : c.startsWith('settings') ? '1' : '', code: 0 }; }, async push() {} };
    expect(await wifiOn(p, async () => {}, 180, 5)).toBe(false);
  });

  it('a Mirror that stops answering ends in an error, not a wait forever (a stall watchdog on each command)', async () => {
    let signalled = false;
    const p = { async run(c: string, signal?: AbortSignal) {
      if (c === 'getprop sys.boot_completed') return { out: '1', code: 0 };
      signal?.addEventListener('abort', () => { signalled = true; });
      return new Promise<{ out: string; code: number }>(() => {}); // svc wifi enable never answers
    }, async push() {} };
    await expect(wifiOn(p, async () => {}, 180, 60, 50)).rejects.toThrow(/didn't answer `svc wifi enable` in 0 s/);
    expect(signalled).toBe(true); // the command's stream is closed through its signal
  });
});
