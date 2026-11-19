// The signing-key switch (debug build -> release) and the Wi-Fi-keeping undo, on a simulated panel.
// The panel-side script's Wi-Fi commands run for real (frames/aqara-s1-plus/tools/install/glassworks-switch.sh against the
// fixture WifiConfigStore.xml); its data copy/restore is simulated here and tested in
// frames/aqara-s1-plus/tools/install/test/install-tests.sh.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { FakeKeySocket } from '@glassworks/app-adb/fakes';
import { IMPROV_SOCKET, PacketReader, PacketType, Rpc, frame, parseResult, strings, type ByteStream } from '../src/improv';
import { KeyMismatchError, provision, unprovision } from '../src/provision';
import {
  SWITCH_SCRIPT, describeSwitch, describeSwitched, isKeyMismatch, parseWifiList, parseWifiOwner, xmlUnescape, type SwitchPlan,
} from '../src/switch';

const REPO = join(__dirname, '../../../..');
const FIX = join(REPO, 'frames/aqara-s1-plus/tools/install/test/fixtures');
const fixture = (f: string) => readFileSync(join(FIX, f), 'utf8');
const SCRIPT = join(REPO, 'frames/aqara-s1-plus/tools/install/glassworks-switch.sh');
/** The fixture's (fake) password for "Home & Garden", as the app must receive it. */
const PSK = 'fixture <not> "real" & fake';
/** Made up: base64 of 32 bytes 1..32 and of a token. Never a real key or sign-in. */
const KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => i + 1)));
const TOKEN = btoa('made-up-refresh-token-0123456789abcdef');
const MISMATCH = 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package sh.edm.s1kiosk signatures do not match previously installed version; ignoring!]\n';

/** Glassworks' Improv server over adb, as far as the installer uses it. */
function improvApp(onWifi: (ssid: string, psk: string) => void): ByteStream {
  const rd = new PacketReader();
  let cb: (b: Uint8Array) => void = () => {};
  let state = 0x02;
  return {
    async write(d) {
      for (const p of rd.push(d)) {
        if (p.type !== PacketType.Rpc) continue;
        const r = parseResult(p.data); // same layout as an RPC command: command, length, strings
        if (p.data[0] === Rpc.RequestState) cb(frame(PacketType.State, Uint8Array.of(state)));
        if (p.data[0] === Rpc.WifiSettings) {
          onWifi(r.strings[0]!, r.strings[1]!);
          state = 0x04;
          cb(frame(PacketType.State, Uint8Array.of(state)));
          const s = strings('');
          cb(frame(PacketType.RpcResult, Uint8Array.of(Rpc.WifiSettings, s.length, ...s)));
        }
      }
    },
    onData(f) { cb = f; },
    async close() {},
  };
}

/** A panel with a debug build of Glassworks as HOME and device admin, on a Wi-Fi network it saved. */
class SwitchPanel implements PanelShell {
  cmds: string[] = [];
  pushed = new Map<string, Uint8Array>();
  installed: 'debug' | 'release' | 'none' = 'debug';
  root = true;
  newInstallWorks = true;
  restoreWorks = true;
  wifiDump = fixture('dumpsys-wifi-kiosk.txt');
  adminAsked = false;
  disabled = false;
  backup = false;
  /** The installed build has empty data: a fresh install, before a restore. */
  fresh = false;
  /**
   * Commands before which Android had started the app with empty data while the switch held its real data: it
   * starts an enabled HOME app at once, and then serves an unkeyed, provisionable ESPHome API (#626).
   */
  ranUnkeyed: string[] = [];
  restoredInto?: 'debug' | 'release';
  /** The last restore's argument: `handover` when the installer marked a hand-over to come (#608). */
  restoreArg?: string;
  /** The APK is older than the installed app: Android refuses it before it compares keys. */
  downgrade = false;
  wifiSent: { ssid: string; psk: string }[] = [];
  streams = 0;
  /** Every hand-over socket this panel had, oldest first. */
  sockets: FakeKeySocket[] = [];
  /**
   * What the debug build can hand over (#584): both (this change on), key (#574's build), none (older: no socket).
   * The build installed after it is always this one. Set it with `oldApp`.
   */
  oldCarries: 'both' | 'key' | 'none' = 'both';
  /** What the debug build holds, sealed with its Keystore key. */
  held: { key?: string; signIn?: string } = { key: KEY, signIn: TOKEN };
  /** The hand-over socket of the app installed now; undefined while none runs. */
  handover?: FakeKeySocket = this.oldApp('both');

  /** The debug build that runs before the switch, holding `held`. */
  oldApp(carries: SwitchPanel['oldCarries'], held = this.held): FakeKeySocket | undefined {
    this.oldCarries = carries;
    this.held = held;
    this.handover = this.socketFor('debug');
    if (this.handover) Object.assign(this.handover, { key: held.key, signIn: held.signIn, signInHere: true });
    return this.handover;
  }

  /** A freshly installed `build`'s socket: its data (if any) comes with a restore. */
  socketFor(build: 'debug' | 'release'): FakeKeySocket | undefined {
    const carries = build === 'release' ? 'both' : this.oldCarries;
    if (carries === 'none') return undefined;
    const s = new FakeKeySocket();
    s.keyOnly = carries === 'key';
    s.signInHere = false;
    this.sockets.push(s);
    return s;
  }

  async run(cmd: string) {
    this.cmds.push(cmd);
    if (this.installed !== 'none' && !this.disabled && this.fresh && this.backup) this.ranUnkeyed.push(cmd);
    // one shell line, `a && b`: no adb round trip between a and b
    let out = '';
    for (const part of cmd.split(' && ')) {
      const r = await this.exec(part);
      out += r.out;
      if (r.code !== 0) return { out, code: r.code };
    }
    return { out, code: 0 };
  }

  async exec(cmd: string) {
    const ok = (out = '') => ({ out, code: 0 });
    if (cmd === 'getprop ro.product.model') return ok('Aqara S1 Plus\n');
    if (cmd === 'getprop ro.build.version.sdk') return ok('29\n');
    if (cmd === 'pm install -r -g /data/local/tmp/glassworks.apk') {
      if (this.downgrade && this.installed !== 'none') return { out: 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n', code: 1 };
      if (this.installed === 'debug') return { out: MISMATCH, code: 1 };
      if (!this.newInstallWorks) return { out: 'Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n', code: 1 };
      this.installed = 'release';
      this.disabled = false;
      this.fresh = true;
      this.handover = this.socketFor('release');
      return ok('Success\n');
    }
    if (cmd === 'pm install -r -g /data/local/tmp/glassworks-old.apk') {
      this.installed = 'debug'; this.disabled = false; this.fresh = true; this.handover = this.socketFor('debug'); return ok('Success\n');
    }
    const sock = this.handover?.shell(cmd);
    if (sock) return sock;
    if (cmd === `grep -c ' @glassworks_api_key$' /proc/net/unix`) return { out: '0\n', code: 1 };
    if (cmd === 'pm disable-user --user 0 sh.edm.s1kiosk') {
      if (this.installed === 'none') return { out: 'Error: java.lang.IllegalArgumentException: Unknown package: sh.edm.s1kiosk\n', code: 255 };
      this.disabled = true;
      return ok();
    }
    if (cmd === 'pm list packages sh.edm.s1kiosk') return ok(this.installed === 'none' ? '' : 'package:sh.edm.s1kiosk\n');
    if (cmd === 'pm enable sh.edm.s1kiosk') { this.disabled = false; return ok(); }
    if (cmd === 'dumpsys package sh.edm.s1kiosk') return ok('Packages:\n  Package [sh.edm.s1kiosk] (1a2b):\n    versionName=0.1.0\n    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]\n');
    if (cmd === `timeout 15 su -c 'id -u' 2>/dev/null`) return this.root ? ok('0\n') : { out: '', code: 1 };
    if (cmd === 'dumpsys wifi') return ok(this.wifiDump);
    if (cmd === 'pm path sh.edm.s1kiosk') return ok('package:/data/app/sh.edm.s1kiosk-Abc==/base.apk\n');
    if (cmd.includes('--ez remove_admin true')) { this.adminAsked = true; return ok(); }
    if (cmd === 'dumpsys device_policy') return ok(this.adminAsked || this.installed === 'none' ? 'no admins\n' : 'Admin sh.edm.s1kiosk/.AdminReceiver\n');
    if (cmd === 'pm uninstall sh.edm.s1kiosk') {
      if (this.installed === 'none' || !this.adminAsked) return { out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n', code: 1 };
      this.installed = 'none';
      this.handover = undefined;
      return ok('Success\n');
    }
    if (cmd === `grep -c ' @s1kiosk_improv$' /proc/net/unix`) return ok(this.installed === 'none' ? '0\n' : '1\n');
    if (cmd.startsWith('ip -4')) return ok('3: wlan0    inet 192.0.2.50/24 brd 192.0.2.255 scope global wlan0\n');
    const su = /^su -c 'sh \/data\/local\/tmp\/glassworks-switch\.sh (.*)'$/.exec(cmd);
    if (su) {
      expect(this.root).toBe(true);
      expect(new TextDecoder().decode(this.pushed.get('/data/local/tmp/glassworks-switch.sh'))).toBe(SWITCH_SCRIPT);
      const args = su[1]!.split(' ');
      if (args[0] === 'wifi-list' || args[0] === 'wifi-psk') {
        try {
          const out = execFileSync('sh', [SCRIPT, ...args], { env: { ...process.env, S1_WIFI_STORES: join(FIX, 'WifiConfigStore.xml') }, encoding: 'utf8' });
          return ok(out.replace(/\n/g, '\r\n')); // adb's shell: a PTY
        } catch { return { out: 'glassworks-switch: no network\n', code: 1 }; }
      }
      if (args[0] === 'backup') { expect(this.installed).toBe('debug'); expect(this.disabled).toBe(true); this.backup = true; return ok('kept 6 item(s), 18342 KiB\n'); }
      if (args[0] === 'restore') {
        expect(this.backup).toBe(true);
        if (this.installed === 'none') return { out: 'glassworks-switch: not installed\n', code: 1 };
        expect(this.disabled).toBe(true); // or it would start as HOME while its data is replaced
        if (!this.restoreWorks) return { out: 'glassworks-switch: could not restore the app\'s data\n', code: 1 };
        this.restoredInto = this.installed;
        this.restoreArg = args[1];
        this.fresh = false;
        // the copied data: the sign-in and the key, sealed with the key Android deleted with the old app
        if (this.handover) Object.assign(this.handover, { key: this.held.key, signIn: this.held.signIn, lost: true, signInHere: true });
        return ok('restored for uid 10072\n');
      }
      if (args[0] === 'discard') { this.backup = false; return ok(); }
    }
    return ok();
  }
  async push(path: string, data: Uint8Array) { this.pushed.set(path, data); }
  async openStream(service: string) {
    if (service === 'localabstract:glassworks_api_key') {
      if (!this.handover) throw new Error('connection refused');
      return this.handover.open(service);
    }
    expect(service).toBe(IMPROV_SOCKET);
    this.streams++;
    return improvApp((ssid, psk) => this.wifiSent.push({ ssid, psk }));
  }
}

const noSleep = async () => {};

/**
 * The app took the sign-in and key back: both readable again (the restore alone leaves them sealed with the deleted
 * key, holding the same values), after a `set` and a `set-signin` it accepted (#608 review).
 */
function expectHandedBack(s: FakeKeySocket | undefined) {
  expect(s).toMatchObject({ key: KEY, signIn: TOKEN, keyLost: false, signInLost: false });
  expect(s!.verbs).toEqual(expect.arrayContaining(['set', 'set-signin']));
}

describe('Wi-Fi owner and store parsing', () => {
  it('reads who saved the current network from dumpsys wifi', () => {
    expect(parseWifiOwner(fixture('dumpsys-wifi-kiosk.txt'))).toEqual({ owner: 'kiosk', ssid: 'Home & Garden' });
    expect(parseWifiOwner(fixture('dumpsys-wifi-other.txt'))).toEqual({ owner: 'other', ssid: 'Neighbours' });
    expect(parseWifiOwner(fixture('dumpsys-wifi-none.txt'))).toEqual({ owner: 'none' });
    // an older, disconnected mWifiInfo printed first names a saved network that isn't the connected one
    const stale = 'mWifiInfo SSID: Neighbours, BSSID: aa:bb:cc:dd:ee:09, Supplicant state: DISCONNECTED, RSSI: -80, Net ID: 0, score: 0\n';
    expect(parseWifiOwner(stale + fixture('dumpsys-wifi-kiosk.txt'))).toEqual({ owner: 'kiosk', ssid: 'Home & Garden' });
    expect(parseWifiOwner('nothing useful')).toEqual({ owner: 'unknown' });
  });

  it('decodes the stored XML values', () => {
    expect(xmlUnescape('&quot;a &amp; b&quot; &lt;&gt;&apos; &#233;&#x41;')).toBe(`"a & b" <>' éA`);
    expect(xmlUnescape('broken &amp')).toBeUndefined();
    expect(xmlUnescape('&nope;')).toBeUndefined();
    expect(parseWifiList('1 psk &quot;Home &amp; Garden&quot;\r\n2 open &quot;Cafe&quot;\r\n')).toEqual([
      { n: 1, kind: 'psk', ssid: 'Home & Garden' }, { n: 2, kind: 'open', ssid: 'Cafe' },
    ]);
  });

  it('knows Android\'s refusal of another signing key', () => {
    expect(isKeyMismatch(MISMATCH)).toBe(true);
    expect(isKeyMismatch('Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]')).toBe(false);
  });

  it('says plainly what is kept, with and without root', () => {
    const plan: SwitchPlan = { installedVersion: '0.1.0', debugBuild: true, root: true, carries: { apiKey: false, signIn: false }, wifi: { owner: 'kiosk', ssid: 'Home' } };
    const withRoot = describeSwitch(plan);
    expect(withRoot).toMatch(/development build \(0\.1\.0\)[\s\S]*settings are kept[\s\S]*"Home"[\s\S]*stays online/);
    // #584: uninstalling deletes the app's Keystore keys, so from an app that can't hand them over the sealed sign-in
    // and API key never come across.
    expect(withRoot).toMatch(/Home Assistant sign-in and encryption key are not: Android deletes the app's Keystore keys/);
    expect(withRoot).toMatch(/Sign in again afterwards; Home Assistant may ask to confirm the panel's encryption key\./);
    expect(withRoot).not.toMatch(/sign-in and keys are kept/);
    const noRoot = describeSwitch({ ...plan, root: false });
    expect(noRoot).toMatch(/starts fresh/);
    expect(noRoot).toMatch(/forgets "Home"[\s\S]*asks for its password again/);
    expect(describeSwitch({ ...plan, wifi: { owner: 'other', ssid: 'Home' } })).toMatch(/wasn't saved by Glassworks and stays/);
  });

  it('says the sign-in is kept when the running app can hand it over, and what isn\'t (#584)', () => {
    const plan: SwitchPlan = { installedVersion: '0.1.0', debugBuild: true, root: true, carries: { apiKey: true, signIn: true }, wifi: { owner: 'kiosk', ssid: 'Home' } };
    const both = describeSwitch(plan);
    expect(both).toMatch(/settings are kept, and so are its Home Assistant sign-in and encryption key: the installer reads them from the running app and hands them to the new one\./);
    expect(both).not.toMatch(/Sign in again/);
    expect(both).toMatch(/camera's stream password and the Music Assistant pairing are not carried: set and pair them again/);
    // #574's build hands over the key, not the sign-in
    const keyOnly = describeSwitch({ ...plan, carries: { apiKey: true, signIn: false } });
    expect(keyOnly).toMatch(/and so is its encryption key: the installer reads it from the running app/);
    expect(keyOnly).toMatch(/Its Home Assistant sign-in is not: this build of the app can't hand it over[\s\S]*Sign in again afterwards\./);
    expect(keyOnly).not.toMatch(/confirm the panel's encryption key/);
    expect(keyOnly).toMatch(/not carried either/);
    // nothing is carried without root, whatever the app could hand over
    expect(describeSwitch({ ...plan, root: false })).toMatch(/starts fresh/);
  });

  it('says at the end whether to sign in again (#584)', () => {
    expect(describeSwitched({ dataKept: true, signIn: 'kept', wifi: 'resent' })).toBe('Settings and sign-in kept · Wi-Fi kept');
    expect(describeSwitched({ dataKept: true, signIn: 'none', wifi: 'kept' })).toBe('Settings kept · Wi-Fi kept');
    expect(describeSwitched({ dataKept: true, signIn: 'lost', wifi: 'resent' })).toBe('Settings kept, sign in again · Wi-Fi kept');
    expect(describeSwitched({ dataKept: false, signIn: 'lost', wifi: 'lost' })).toBe('Settings reset · Wi-Fi needs its password again');
  });

  it('says Glassworks saved the network only when dumpsys says so', () => {
    const plan: SwitchPlan = { installedVersion: '0.1.0', debugBuild: true, root: false, carries: { apiKey: false, signIn: false }, wifi: { owner: 'unknown', ssid: 'Home' } };
    const noRoot = describeSwitch(plan);
    expect(noRoot).not.toMatch(/Glassworks saved/);
    expect(noRoot).toMatch(/can't tell who saved "Home", so the panel may lose its Wi-Fi[\s\S]*asks for its password again/);
    expect(describeSwitch({ ...plan, root: true })).toMatch(/Android may forget "Home" with the old app/);
    expect(describeSwitch({ ...plan, root: true, wifi: { owner: 'kiosk', ssid: 'Home' } })).toMatch(/Android would forget "Home"/);
  });
});

describe('switching a development build to this one', () => {
  it('with root: keeps the data, re-sends the Wi-Fi to the new app, never shows the password', async () => {
    const p = new SwitchPanel();
    const text: string[] = [];
    let asked: SwitchPlan | undefined;
    const res = await provision(p, {
      kioskApk: new Uint8Array([1, 2, 3]), debloat: false,
      confirmSwitch: async (plan) => { asked = plan; return true; },
    }, (s, d) => text.push(s || d || ''), noSleep);

    expect(asked).toEqual({ installedVersion: '0.1.0', debugBuild: true, root: true, carries: { apiKey: true, signIn: true }, wifi: { owner: 'kiosk', ssid: 'Home & Garden' } });
    expect(res.switched).toEqual({ dataKept: true, signIn: 'kept', apiKey: 'kept', wifi: 'resent', ssid: 'Home & Garden', notes: [] });
    // #584: the new app has the old one's sign-in and key, sealed with its own Keystore key now
    expectHandedBack(p.handover);
    expect(p.wifiSent).toEqual([{ ssid: 'Home & Garden', psk: PSK }]);
    expect(p.installed).toBe('release');
    expect(p.restoredInto).toBe('release');
    expect(p.disabled).toBe(false);
    expect(p.backup).toBe(false); // discarded after the restore
    // installed and turned off in one shell line, so it never runs on empty data before the restore (#626)
    expect(p.ranUnkeyed).toEqual([]);

    // the password, the sign-in and the key: never in a command, a step or a detail
    for (const s of [...p.cmds, ...text]) for (const secret of ['fixture', TOKEN, KEY, atob(TOKEN)]) expect(s).not.toContain(secret);
    expect(JSON.stringify(res)).not.toContain('fixture');
    expect(JSON.stringify(res)).not.toContain(TOKEN);
    expect(text.some((t) => /^Home Assistant sign-in: read from the running app \(fingerprint [0-9a-f]{4}\)/.test(t))).toBe(true);
    expect(text).toContain('Home Assistant sign-in: handed to the app');
    expect(text).toContain('Encryption key: handed to the app');
    const [old, fresh] = p.sockets;
    expect(old!.verbs).toEqual(['carries', 'get-signin', 'get']);
    expect(fresh!.verbs).toEqual(['set', 'set-signin', 'done']);
    // the restore marked the hand-over, so the new app keeps the copied secrets locked until then (#608)
    expect(p.restoreArg).toBe('handover');

    const i = (c: string) => p.cmds.indexOf(c);
    const su = (c: string) => i(`su -c 'sh /data/local/tmp/glassworks-switch.sh ${c}'`);
    expect(su('wifi-psk 1')).toBeGreaterThan(su('wifi-list'));
    expect(su('backup')).toBeGreaterThan(su('wifi-psk 1'));
    expect(i('pm uninstall sh.edm.s1kiosk')).toBeGreaterThan(su('backup'));
    expect(i('pm uninstall sh.edm.s1kiosk')).toBeGreaterThan(p.cmds.indexOf('am start -n sh.edm.s1kiosk/.MainActivity --ez remove_admin true'));
    const held = 'pm install -r -g /data/local/tmp/glassworks.apk && pm disable-user --user 0 sh.edm.s1kiosk';
    expect(i(held)).toBeGreaterThan(i('pm uninstall sh.edm.s1kiosk'));
    expect(su('restore handover')).toBeGreaterThan(i(held));
    // then the usual install steps: permissions, admin, HOME, start, and only then Wi-Fi
    expect(i('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver')).toBeGreaterThan(su('restore handover'));
    expect(i('cmd package set-home-activity sh.edm.s1kiosk/.MainActivity')).toBeGreaterThan(su('restore handover'));
    expect(p.cmds.indexOf(`grep -c ' @s1kiosk_improv$' /proc/net/unix`)).toBeGreaterThan(i('am start -n sh.edm.s1kiosk/.MainActivity'));
    // the secrets are read while the old app runs, before it is stopped, and handed over once the new one starts
    const sock = `grep -c ' @glassworks_api_key$' /proc/net/unix`;
    expect(i(sock)).toBeLessThan(i('pm disable-user --user 0 sh.edm.s1kiosk'));
    expect(p.cmds.lastIndexOf(sock)).toBeGreaterThan(i('am start -n sh.edm.s1kiosk/.MainActivity'));
    expect(p.cmds.lastIndexOf(sock)).toBeLessThan(p.cmds.indexOf(`grep -c ' @s1kiosk_improv$' /proc/net/unix`));
    expect(p.cmds).toContain('rm -f /data/local/tmp/glassworks-old.apk /data/local/tmp/glassworks.apk /data/local/tmp/glassworks-switch.sh');
    expect(text).toContain('Switch to this build (remove the old one, install this one)');
  });

  it('without root: says the app starts fresh, does no root step, leaves Wi-Fi to the Wi-Fi step', async () => {
    const p = new SwitchPanel();
    p.root = false;
    let asked: SwitchPlan | undefined;
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async (plan) => { asked = plan; return !plan.root; } }, () => {}, noSleep);
    expect(res.switched).toEqual({ dataKept: false, signIn: 'lost', apiKey: 'lost', wifi: 'lost', ssid: 'Home & Garden', notes: [] });
    // nothing is read without root: the data, which holds the rest of the sign-in, doesn't come across
    expect(asked!.carries).toEqual({ apiKey: false, signIn: false });
    expect(p.sockets.flatMap((x) => x.verbs)).toEqual([]);
    expect(p.cmds.filter((c) => c.startsWith('su '))).toEqual([]);
    expect(p.pushed.has('/data/local/tmp/glassworks-switch.sh')).toBe(false);
    expect(p.streams).toBe(0);
    expect(p.installed).toBe('release');
    // no data comes after the install, so nothing holds the new build off
    expect(p.cmds).toContain('pm install -r -g /data/local/tmp/glassworks.apk');
    expect(p.cmds.some((c) => c.includes(' && '))).toBe(false);
  });

  it('a restore that throws still turns the new build back on (#626)', async () => {
    const p = new SwitchPanel();
    const exec = p.exec.bind(p);
    p.exec = async (cmd) => {
      if (cmd.includes('glassworks-switch.sh restore')) throw new Error('USB transfer failed');
      return exec(cmd);
    };
    await expect(provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/USB transfer failed/);
    expect(p.installed).toBe('release');
    expect(p.disabled).toBe(false);
  });

  it('a network the app did not save is left alone', async () => {
    const p = new SwitchPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, wifi: 'kept', ssid: 'Neighbours' });
    expect(p.cmds.some((c) => c.includes('wifi-psk'))).toBe(false);
    expect(p.wifiSent).toEqual([]);
  });

  it('declined, or nobody to ask: stops with nothing changed', async () => {
    for (const confirmSwitch of [async () => false, undefined]) {
      const p = new SwitchPanel();
      await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch }, () => {}, noSleep)).rejects.toThrow(KeyMismatchError);
      expect(p.cmds.some((c) => c.startsWith('pm uninstall') || c.startsWith('su -c \'sh'))).toBe(false);
      expect(p.installed).toBe('debug');
    }
  });

  it('if the new build will not install, the old one comes back with its data', async () => {
    const p = new SwitchPanel();
    p.newInstallWorks = false;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/previous build is back with its data\.$/);
    expect(p.installed).toBe('debug');
    expect(p.restoredInto).toBe('debug');
    expect(p.ranUnkeyed).toEqual([]); // the old build too is off until its data is back (#626)
    // it lost its Keystore key too: it gets its sign-in and key back (#584)
    expectHandedBack(p.handover);
    expect(p.cmds).toContain('am start -n sh.edm.s1kiosk/.MainActivity');
    expect(p.disabled).toBe(false);
    expect(p.cmds).toContain('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver');
    expect(p.backup).toBe(false);
    expect(p.wifiSent).toEqual([]); // the old app still has its network
  });

  it('from an app of #574 (before #584): carries the key, says to sign in again', async () => {
    const p = new SwitchPanel();
    const old = p.oldApp('key')!;
    const text: string[] = [];
    let asked: SwitchPlan | undefined;
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async (plan) => { asked = plan; return true; } },
      (st, d) => text.push(st || d || ''), noSleep);
    expect(asked!.carries).toEqual({ apiKey: true, signIn: false });
    expect(res.switched).toMatchObject({ dataKept: true, signIn: 'lost', apiKey: 'kept' });
    expect(text).toContain('Home Assistant sign-in: this build of the app can\'t hand it over; sign in again afterwards');
    expect(old.verbs).toEqual(['carries', 'get']);
    expect(p.handover).toMatchObject({ key: KEY });
    expect(p.handover!.verbs).toEqual(['set', 'done']);
  });

  it('from an app with no hand-over at all: carries nothing, as before #584', async () => {
    const p = new SwitchPanel();
    p.oldApp('none');
    const text: string[] = [];
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, (st, d) => text.push(st || d || ''), noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, signIn: 'lost', apiKey: 'lost' });
    expect(text).not.toContain('Sign-in and encryption key to the new app');
    expect(p.handover!.verbs).toEqual([]);
    expect(p.restoreArg).toBeUndefined(); // nothing to wait for: no marker
  });

  it('an app that isn\'t signed in: nothing to carry, nothing to say', async () => {
    const p = new SwitchPanel();
    p.oldApp('both', { key: KEY });
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, signIn: 'none', apiKey: 'kept' });
    expect(p.handover!.verbs).toEqual(['set', 'done']);
  });

  it('an app that can\'t read its sign-in now: the switch goes on and says to sign in again', async () => {
    const p = new SwitchPanel();
    p.oldApp('both')!.unreadable = true;
    const text: string[] = [];
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, (st, d) => text.push(st || d || ''), noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, signIn: 'lost', apiKey: 'lost' });
    expect(text.some((t) => /^Home Assistant sign-in: could not read it \(the app has a sign-in but can't read it right now/.test(t))).toBe(true);
  });

  it('an app that stops answering before the read: lost, never taken for "none" (#608 review)', async () => {
    const p = new SwitchPanel();
    const run = p.run.bind(p);
    // releasing the device admin restarts it, and its socket doesn't come back in time
    p.run = async (cmd) => { if (cmd.includes('remove_admin')) p.handover!.listening = false; return run(cmd); };
    const text: string[] = [];
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, (st, d) => text.push(st || d || ''), noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, signIn: 'lost', apiKey: 'lost' });
    expect(text).toContain('Home Assistant sign-in: the app isn\'t answering on its hand-over socket; sign in again afterwards');
    expect(p.sockets[0]!.verbs).toEqual(['carries']);
  });

  it('a later step that fails still hands the sign-in and key to the new app (#608 review)', async () => {
    const p = new SwitchPanel();
    await expect(provision(p, { kioskApk: new Uint8Array([1]), debloat: false, timezone: 'not a zone!', confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/bad time zone/);
    expect(p.installed).toBe('release');
    expectHandedBack(p.handover);
    expect(p.cmds).toContain('am start -n sh.edm.s1kiosk/.MainActivity');
  });

  it('a failure at the end of the switch itself still hands the sign-in and key to the new app (#608 review)', async () => {
    const p = new SwitchPanel();
    const run = p.run.bind(p);
    // the USB link drops as the switch cleans up, after the data went into the new build
    p.run = async (cmd) => {
      if (cmd.startsWith('rm -f') && p.restoredInto === 'release') { p.cmds.push(cmd); throw new Error('USB transfer failed'); }
      return run(cmd);
    };
    await expect(provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/USB transfer failed/);
    expectHandedBack(p.handover);
    expect(p.cmds).toContain('am start -n sh.edm.s1kiosk/.MainActivity');
  });

  it('when the data won\'t go into the new build, hands nothing over (a fresh app has no sign-in to take it)', async () => {
    const p = new SwitchPanel();
    p.restoreWorks = false;
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep);
    expect(res.switched).toMatchObject({ dataKept: false, signIn: 'lost', apiKey: 'lost' });
    expect(p.handover!.verbs).toEqual([]);
  });

  it('if the old build comes back but its data won\'t go in, says so and keeps the copy', async () => {
    const p = new SwitchPanel();
    p.newInstallWorks = false;
    p.restoreWorks = false;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/previous build is back, but its data is not \(glassworks-switch: could not restore the app's data\)\. Its data is kept on the panel in \/data\/s1kiosk\/switch/);
    expect(p.installed).toBe('debug');
    expect(p.disabled).toBe(false);
    expect(p.backup).toBe(true);
    expect(p.cmds.some((c) => c.endsWith('glassworks-switch.sh discard\''))).toBe(false);
  });

  it('if neither build installs, the data copy is kept', async () => {
    const p = new SwitchPanel();
    p.newInstallWorks = false;
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd.startsWith('pm install -r -g /data/local/tmp/glassworks-old.apk') ? (p.cmds.push(cmd), { out: 'Failure [INSTALL_FAILED_INTERNAL_ERROR]\n', code: 1 }) : run(cmd));
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/neither would the previous one\. Run the installer again\. Its data is kept on the panel in \/data\/s1kiosk\/switch/);
    expect(p.backup).toBe(true);
  });

  it('an older build says so, before any switch, with nothing changed (#608 panel run)', async () => {
    const p = new SwitchPanel();
    p.downgrade = true;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/newer version than this one[\s\S]*Nothing was changed/);
    expect(p.cmds.some((c) => c.startsWith('pm uninstall') || c.startsWith('su '))).toBe(false);
    expect(p.installed).toBe('debug');
  });

  it('an ordinary install failure is not mistaken for a key mismatch', async () => {
    const p = new SwitchPanel();
    p.installed = 'none';
    p.newInstallWorks = false;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/INSUFFICIENT_STORAGE/);
    expect(p.cmds.some((c) => c.startsWith('pm uninstall'))).toBe(false);
  });

  it('if Android won\'t remove the old build, it is enabled and admin again, nothing else changed', async () => {
    const p = new SwitchPanel();
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm uninstall sh.edm.s1kiosk' ? (p.cmds.push(cmd), { out: 'Failure [DELETE_FAILED_INTERNAL_ERROR]\n', code: 1 }) : run(cmd));
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/would not remove[\s\S]*unchanged/);
    expect(p.installed).toBe('debug');
    expect(p.disabled).toBe(false);
    expect(p.backup).toBe(false);
    expect(p.cmds.at(-1)).toBe('rm -f /data/local/tmp/glassworks-old.apk /data/local/tmp/glassworks.apk /data/local/tmp/glassworks-switch.sh');
    expect(p.cmds).toContain('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver');
  });
});

describe('undo keeps the panel on its Wi-Fi', () => {
  class UndoWifiPanel extends SwitchPanel {
    home = 'sh.edm.s1kiosk/.MainActivity';
    /** Glassworks stays a HOME app (it wasn't disabled): Android shows the chooser. */
    chooser = false;
    override async run(cmd: string) {
      if (cmd.startsWith('cmd package set-home-activity com.aqara')) {
        this.cmds.push(cmd);
        this.home = this.chooser ? 'android/com.android.internal.app.ResolverActivity' : 'com.aqara.inhome.launcher/.MainActivity';
        return { out: '', code: 0 };
      }
      if (cmd.startsWith('cmd package resolve-activity')) { this.cmds.push(cmd); return { out: `${this.home}\n`, code: 0 }; }
      return super.run(cmd);
    }
  }

  it('disables Glassworks instead of uninstalling it when it saved the network', async () => {
    const p = new UndoWifiPanel();
    const text: string[] = [];
    const result = await unprovision(p, (s, d) => text.push(s || d || ''), noSleep);
    expect(p.cmds).toContain('pm disable-user --user 0 sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm uninstall sh.edm.s1kiosk');
    expect(text.some((t) => /disabled: it saved the panel's Wi-Fi \(Home & Garden\)/.test(t))).toBe(true);
    expect(result).toEqual({ app: 'disabled', note: 'Glassworks is installed but turned off, so the panel keeps its Wi-Fi.' });
  });

  it('says so when Android won\'t disable it, though HOME is Aqara\'s again', async () => {
    const p = new UndoWifiPanel();
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm disable-user --user 0 sh.edm.s1kiosk' ? (p.cmds.push(cmd), { out: 'Error: java.lang.SecurityException\n', code: 255 }) : run(cmd));
    const text: string[] = [];
    const result = await unprovision(p, (s, d) => text.push(s || d || ''), noSleep);
    expect(p.home).toBe('com.aqara.inhome.launcher/.MainActivity');
    expect(result.app).toBe('running');
    expect(result.note).toMatch(/would not turn Glassworks off/);
    expect(text).toContain('could not disable Glassworks (Error: java.lang.SecurityException)');
    expect(text.some((t) => /kept Glassworks installed but disabled/.test(t))).toBe(false);
  });

  it('says so when Android won\'t uninstall it or disable it', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm uninstall sh.edm.s1kiosk' || cmd === 'pm disable-user --user 0 sh.edm.s1kiosk'
      ? (p.cmds.push(cmd), { out: 'Failure\n', code: 1 }) : run(cmd));
    const text: string[] = [];
    expect((await unprovision(p, (s, d) => text.push(s || d || ''), noSleep)).app).toBe('running');
    expect(text.some((t) => /disabled it instead/.test(t))).toBe(false);
  });

  it('a retry after an undo that removed Glassworks says it is removed, on either Wi-Fi path', async () => {
    for (const dump of [fixture('dumpsys-wifi-other.txt'), fixture('dumpsys-wifi-kiosk.txt')]) {
      const p = new UndoWifiPanel();
      p.installed = 'none';
      p.wifiDump = dump;
      expect(await unprovision(p, () => {}, noSleep)).toEqual({ app: 'removed', note: 'Glassworks is removed.' });
    }
  });

  it('Glassworks still on leaves HOME on the chooser: says Glassworks is still on, not the HOME error', async () => {
    for (const dump of [fixture('dumpsys-wifi-kiosk.txt'), fixture('dumpsys-wifi-other.txt')]) {
      const p = new UndoWifiPanel();
      p.chooser = true;
      p.wifiDump = dump;
      const run = p.run.bind(p);
      p.run = async (cmd) => (cmd === 'pm uninstall sh.edm.s1kiosk' || cmd === 'pm disable-user --user 0 sh.edm.s1kiosk'
        ? (p.cmds.push(cmd), { out: 'Failure\n', code: 1 }) : run(cmd));
      const result = await unprovision(p, () => {}, noSleep);
      expect(result.app).toBe('running');
      expect(result.note).toMatch(/ask which home screen to use/);
      expect(p.cmds).not.toContain('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
    }
  });

  it('HOME on the chooser with Glassworks off is still an error', async () => {
    const p = new UndoWifiPanel();
    p.chooser = true;
    await expect(unprovision(p, () => {}, noSleep)).rejects.toThrow(/home screen is still android\/com\.android\.internal\.app\.ResolverActivity/);
  });

  it('a package listing that fails is not taken as removed', async () => {
    const p = new UndoWifiPanel();
    p.installed = 'none';
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm list packages sh.edm.s1kiosk' ? (p.cmds.push(cmd), { out: 'cmd: Failure calling service package\n', code: 1 }) : run(cmd));
    expect((await unprovision(p, () => {}, noSleep)).app).toBe('running');
  });

  it('uninstall refused, disable works: says it is turned off instead', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm uninstall sh.edm.s1kiosk' ? (p.cmds.push(cmd), { out: 'Failure\n', code: 1 }) : run(cmd));
    expect(await unprovision(p, () => {}, noSleep)).toEqual({ app: 'disabled', note: 'Android would not remove Glassworks, so it is turned off instead.' });
  });

  it('uninstalls it when the network is someone else\'s', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    expect(await unprovision(p, () => {}, noSleep)).toEqual({ app: 'removed', note: 'Glassworks is removed.' });
    expect(p.cmds).toContain('pm uninstall sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm disable-user --user 0 sh.edm.s1kiosk');
  });

  it('can\'t tell whose network it is but the panel is online: keeps it (disables)', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = 'Wi-Fi is enabled\n';
    const text: string[] = [];
    await unprovision(p, (s, d) => text.push(s || d || ''), noSleep);
    expect(text.some((t) => /disabled: the installer can't tell who saved the panel's Wi-Fi/.test(t))).toBe(true);
    expect(p.cmds).toContain('pm disable-user --user 0 sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm uninstall sh.edm.s1kiosk');
  });
});
