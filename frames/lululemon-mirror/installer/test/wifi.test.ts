// A community image's Wi-Fi from Kiln: the network saved in a copy of a checked userdata (one file,
// from the system image's own template, read in place), and Wi-Fi switched on with one adb command.
// On small images made here: the real Glass Panes files can't go in the repo (hard rule 3).
import { createHash } from 'node:crypto';
import { Disk, Ext4, MemoryStore, mkfs, readSparse, writeSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import { WIFI_CONF, WrongImage, userdataWithWifi, wifiOn, wifiTemplate, wpaPsk } from '../src';

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
});
