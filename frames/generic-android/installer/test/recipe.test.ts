// The generic Android recipe against a fake device at each API level (test/fakes.ts). The command
// sequences are pinned in golden/generic-adb-commands.json: a change there is a change to what a device
// sees, so review it as one. Rewrite it on purpose with GENERIC_PIN_WRITE=1.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { describe, expect, it } from 'vitest';
import { GENERIC, LEGACY, OwnFrameRefused, Refused, SILENT_LINES, handOff, installGeneric, parseScreenLock, planInstall, undoGeneric, undoSilentUpdates, type GenericPhase } from '../src';
import { FakeAndroid, fakeGenericApk, type FakeAndroidOptions } from './fakes';

const GOLDEN = join(__dirname, 'golden/generic-adb-commands.json');
const noSleep = async () => {};
const IP = '192.0.2.10';
const HA = 'http://homeassistant.local:8123';
/** Commands that change the device. Everything else the recipe runs only reads. */
const WRITES = /^(push |pm (install|uninstall|grant)|appops set|dpm |am (start|force-stop)|cmd package set-home-activity|setup \{.*pairing)/;

const LEVELS: Record<string, FakeAndroidOptions> = {
  api23: { sdk: 23, release: '6.0.1', manufacturer: 'LENOVO', model: 'Lenovo TB-X103F', abilist: 'armeabi-v7a,armeabi', picksAfter: 2, ip: IP },
  api25: { sdk: 25, release: '7.1.1', manufacturer: 'samsung', model: 'SM-T580', abilist: 'armeabi-v7a,armeabi', ip: IP },
  api26: { sdk: 26, release: '8.0.0', manufacturer: 'HUAWEI', model: 'AGS-W09', ip: IP },
  api29: { sdk: 29, release: '10', manufacturer: 'Amazon', model: 'KFTRWI', ip: IP },
  api34: { sdk: 34, release: '14', manufacturer: 'Google', model: 'Pixel Tablet', abilist: 'arm64-v8a', ip: IP },
};
const apkFor = (sdk: number, abis = sdk >= 26 ? ['arm64-v8a'] : ['armeabi-v7a']) => fakeGenericApk({ app: sdk >= 26 ? GENERIC : LEGACY, abis });

async function run(o: FakeAndroidOptions, extra: { apk?: Uint8Array; homeTimeoutMs?: number; silentUpdates?: boolean } = {}) {
  const p = new FakeAndroid(o);
  const phases: GenericPhase[] = [];
  const res = await installGeneric(p, {
    apk: extra.apk ?? apkFor(o.sdk ?? 34), haUrl: HA, sleep: noSleep, onPhase: (ph) => phases.push(ph), homeTimeoutMs: extra.homeTimeoutMs ?? 60_000, setupTries: 2,
    ...(extra.silentUpdates !== undefined ? { silentUpdates: extra.silentUpdates } : {}),
  });
  return { p, res, phases };
}

describe('the command sequence, per Android version', () => {
  it('matches the golden for API 23, 25, 26, 29 and 34', async () => {
    const got: Record<string, string[]> = {};
    for (const [name, o] of Object.entries(LEVELS)) got[name] = (await run(o)).p.cmds;
    if (process.env.GENERIC_PIN_WRITE) writeFileSync(GOLDEN, `${JSON.stringify(got, null, 2)}\n`);
    expect(got).toEqual(JSON.parse(readFileSync(GOLDEN, 'utf8')));
  });

  it('Android 6: the legacy build, and the chooser for the home screen', async () => {
    const { p, res, phases } = await run(LEVELS.api23!);
    expect(res.plan.app).toBe(LEGACY);
    expect(res.home).toEqual({ how: 'chooser', state: 'set', home: LEGACY.home });
    expect(phases).toEqual([{ step: 'read' }, { step: 'apk', stage: 'copy' }, { step: 'apk', stage: 'install' }, { step: 'grant' }, { step: 'updates' },
      { step: 'granted', grants: res.grants, silentUpdates: res.silentUpdates }, { step: 'start' },
      { step: 'home', how: 'chooser', pick: LEGACY.name }, { step: 'handoff' }]);
    expect(p.cmds.some((c) => c.startsWith('cmd '))).toBe(false);
  });

  it.each(['api25', 'api26', 'api29', 'api34'])('%s: set-home-activity, read back by package', async (level) => {
    const { p, res } = await run(LEVELS[level]!);
    expect(res.plan.app).toBe((LEVELS[level]!.sdk ?? 34) >= 26 ? GENERIC : LEGACY);
    expect(res.home).toMatchObject({ how: 'set-home-activity', state: 'set' });
    expect(p.cmds).not.toContain('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
  });

  it('Android 7.0 (API 24) is the first with set-home-activity', async () => {
    const { p, res } = await run({ ...LEVELS.api25!, sdk: 24, release: '7.0' });
    expect(res.plan.app).toBe(LEGACY);
    expect(res.home).toMatchObject({ how: 'set-home-activity', state: 'set' });
    expect(p.cmds.some((c) => c.startsWith('cmd package set-home-activity'))).toBe(true);
  });

  it('Android 14: grants what the app asks for, reads each back, starts it with the URL and hands over to setup', async () => {
    const { p, res } = await run(LEVELS.api34!);
    expect(res.grants).toEqual([
      { what: 'microphone', result: 'granted' }, { what: 'camera', result: 'not-asked' }, { what: 'secure-settings', result: 'granted' },
      { what: 'overlay', result: 'granted' }, { what: 'device-admin', result: 'granted' },
    ]);
    expect(res.plan.identified).toMatchObject({ tested: true, model: { name: 'Pixel Tablet', frame: 'google-pixel-tablet' } });
    expect(res.plan.abi).toBe('arm64-v8a');
    expect(res.handoff).toEqual({ ip: IP, setupPage: `http://${IP}:8099/setup` });
    expect(p.cmds).toContain(`am start -n ${GENERIC.activity} --es url '${HA}'`);
    expect(p.cmds).not.toContain(`pm grant ${GENERIC.package} android.permission.CAMERA`);
    // read, install, grant, start, home, hand-off: in that order
    const at = (c: string) => p.cmds.findIndex((x) => x.startsWith(c));
    const order = ['getprop ro.build.version.sdk', 'pm install', 'pm grant', 'am start -n', 'cmd package set-home-activity', 'open localabstract:s1kiosk_setup'].map(at);
    expect(order.every((i) => i >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b));
  });

  it('updates the app in place when it is there, and says so', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, installed: 600 });
    expect(res.updated).toBe(true);
    expect(res.plan.installed).toEqual({ name: '0.6.0', code: 600 });
    expect(p.cmds.filter((c) => c.startsWith('pm uninstall'))).toEqual([]);
    // dpm answers "already added": read back, the admin is active
    expect(res.grants.find((g) => g.what === 'device-admin')).toEqual({ what: 'device-admin', result: 'granted' });
  });

  it('Android 6 with the app already home: no chooser', async () => {
    const { p, res } = await run({ ...LEVELS.api23!, installed: 600 });
    expect(res.home).toEqual({ how: 'chooser', state: 'set', home: LEGACY.home });
    expect(p.cmds).not.toContain('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
  });
});

describe('refusals: nothing changes on the device', () => {
  const refuses = async (o: FakeAndroidOptions, apk: Uint8Array, why: RegExp) => {
    const p = new FakeAndroid(o);
    const e = await installGeneric(p, { apk, sleep: noSleep }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Refused);
    expect((e as Error).message).toMatch(why);
    expect((e as Error).message).toMatch(/Nothing was changed\.$/);
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  };

  it('below Android 6', () => refuses({ sdk: 22, release: '5.1.1' }, apkFor(23), /needs Android 6 \(API 23\) or newer.*Android 5\.1\.1 \(API 22\)/));
  it('an APK that isn\'t Glassworks', () => refuses({}, fakeApk('com.example.other', { minSdk: 23 }), /This APK is com\.example\.other, not Glassworks: pick Glassworks \(dev\.glassworks\.generic_android\)/));
  it('S1 Kiosk\'s APK', () => refuses({}, fakeApk('sh.edm.s1kiosk', { minSdk: 29 }), /This APK is sh\.edm\.s1kiosk, not Glassworks/));
  it('the legacy build on Android 10', () => refuses({ sdk: 29, release: '10' }, apkFor(23, ['arm64-v8a']), /legacy build\), for Android 6\.0 to 7\.1.*Android 10: pick Glassworks \(dev\.glassworks\.generic_android\)/));
  it('Glassworks on Android 7', () => refuses({ sdk: 25, release: '7.1.1' }, apkFor(34, ['armeabi-v7a']), /for Android 8\.0 and newer.*Android 7\.1: pick Glassworks \(legacy build\)/));
  it('an APK for another processor', () => refuses({ sdk: 34, abilist: 'x86_64,x86' }, apkFor(34, ['arm64-v8a']), /code for arm64-v8a, and this device runs x86_64, x86: pick the APK for x86_64/));
  it('a 64-bit APK on a 32-bit device', () => refuses({ sdk: 25, release: '7.1.1', abilist: 'armeabi-v7a,armeabi' }, apkFor(25, ['arm64-v8a']), /code for arm64-v8a/));
  it('an APK whose minSdk is above the device\'s', () => refuses({ sdk: 26, release: '8.0.0' }, fakeGenericApk({ minSdk: 28 }), /needs Android API 28, and this device runs API 26/));
  it('a file that isn\'t an APK', () => refuses({}, new TextEncoder().encode('not a zip'), /isn't an APK/));
  it('an S1, by its hardware: its own install, never the generic app over S1 Kiosk', async () => {
    // the S1's build properties as read 2026-11-14 (AqaraS1Plus.kt): Allwinner's SDK defaults, so only ro.hardware names it
    const s1 = { sdk: 29, release: '10', abilist: 'armeabi-v7a,armeabi', manufacturer: 'Allwinner', model: 'QUAD-CORE R818 EVB2', hardware: 'sun50iw10p1' };
    await refuses(s1, apkFor(29, ['armeabi-v7a']), /This is an Aqara S1 Plus, which has its own install in Kiln \(\/aqara-s1-plus\)/);
    const e = await planInstall(new FakeAndroid(s1), apkFor(29, ['armeabi-v7a'])).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(OwnFrameRefused);
    expect((e as OwnFrameRefused).own.frame).toBe('aqara-s1-plus');
  });
  it.each([['fails', { hardwareFails: true }], ['is empty', { hardware: '' }]] as const)('ro.hardware that %s: can\'t tell, so refused', (_, o) =>
    refuses(o, apkFor(34), /couldn't read this device's hardware \(ro\.hardware\), so it can't tell whether it is a panel with its own install/));
  it('another Allwinner A133 tablet with the same maker and model, but not the S1\'s hardware, installs', async () => {
    const p = new FakeAndroid({ sdk: 29, release: '10', abilist: 'armeabi-v7a,armeabi', manufacturer: 'Allwinner', model: 'QUAD-CORE R818 EVB2', hardware: 'sun50iw10' });
    await expect(planInstall(p, apkFor(29, ['armeabi-v7a']))).resolves.toMatchObject({ app: GENERIC });
  });

  it('reads the device with getprop only', async () => {
    const p = new FakeAndroid({});
    await planInstall(p, apkFor(34));
    expect(p.cmds.every((c) => /^(getprop |dumpsys package )/.test(c))).toBe(true);
  });

  it.each([
    ['another signing key', 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.glassworks.generic_android signatures do not match]', /signed with another key.*Uninstall it first/],
    ['a newer version there', 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]', /has a newer Glassworks/],
    ['installs over USB off (a vendor switch)', 'Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]', /"Install via USB" switch in Developer options/],
  ])('pm refuses: %s', async (_, out, why) => {
    const p = new FakeAndroid({ installFails: out });
    const e = await installGeneric(p, { apk: apkFor(34), sleep: noSleep }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Refused);
    expect((e as Error).message).toMatch(why);
    // the copy only: no grant, no start, no home
    expect(p.cmds.filter((c) => WRITES.test(c)).map((c) => c.split(' ').slice(0, 2).join(' '))).toEqual(['push /data/local/tmp/glassworks.apk', 'pm install']);
  });
});

describe('vendor builds: report what didn\'t apply, finish the rest', () => {
  it('a refused grant', async () => {
    const { res } = await run({ ...LEVELS.api34!, refuseGrant: ['android.permission.WRITE_SECURE_SETTINGS'] });
    expect(res.grants.find((g) => g.what === 'secure-settings')).toEqual({ what: 'secure-settings', result: 'refused', detail: 'Exception occurred while executing \'grant\':' });
    expect(res.grants.find((g) => g.what === 'microphone')?.result).toBe('granted');
    expect(res.home.state).toBe('set');
  });

  it('a grant that answers 0 but doesn\'t take', async () => {
    const { res } = await run({ ...LEVELS.api34!, silentlyIgnore: ['android.permission.RECORD_AUDIO'] });
    expect(res.grants.find((g) => g.what === 'microphone')).toEqual({ what: 'microphone', result: 'refused', detail: 'Android says it is still off' });
  });

  it('a refused admin isn\'t read as granted when the other build\'s admin is left over', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, refuseAdmin: true, otherAdmin: true });
    expect(p.admins).toEqual(new Set([LEGACY.admin]));
    expect(res.grants.find((g) => g.what === 'device-admin')).toEqual({ what: 'device-admin', result: 'refused', detail: 'java.lang.SecurityException: Calling identity is not authorized' });
  });

  it('a refused overlay and device admin', async () => {
    const { res } = await run({ ...LEVELS.api29!, refuseAppops: true, refuseAdmin: true });
    expect(res.grants.filter((g) => g.result === 'refused').map((g) => g.what)).toEqual(['overlay', 'device-admin']);
    expect(res.handoff.setupPage).toBe(`http://${IP}:8099/setup`);
  });

  it('set-home-activity refused: Android\'s chooser, then the tap', async () => {
    const { p, res, phases } = await run({ ...LEVELS.api29!, refuseSetHome: true, picksAfter: 2 });
    expect(res.home).toEqual({ how: 'chooser', state: 'set', home: GENERIC.home, note: 'set-home-activity: Error: Failed to set default home.' });
    expect(phases.filter((ph) => ph.step === 'home')).toEqual([{ step: 'home', how: 'set-home-activity' }, { step: 'home', how: 'chooser', pick: 'Glassworks' }]);
    expect(p.cmds).toContain('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
  });

  it('nobody taps Always in time: unconfirmed, not a failure', async () => {
    const { res } = await run({ ...LEVELS.api29!, refuseSetHome: true }, { homeTimeoutMs: 0 });
    expect(res.home).toMatchObject({ how: 'chooser', state: 'unconfirmed', home: 'android/com.android.internal.app.ResolverActivity' });
    expect(res.handoff.setupPage).toBeDefined();
  });
});

describe('silent updates (#422)', () => {
  const OP = (pkg: string, mode: string) => `appops set ${pkg} REQUEST_INSTALL_PACKAGES ${mode}`;

  it('Android 12+: allows Install unknown apps by default, reads it back, after the grants and before the start', async () => {
    const { p, res } = await run(LEVELS.api34!);
    expect(res.silentUpdates).toEqual({ state: 'allowed', line: SILENT_LINES.allowed });
    expect(p.installOp).toBe('allow');
    const at = (c: string) => p.cmds.findIndex((x) => x.startsWith(c));
    expect(at('pm grant')).toBeLessThan(at(OP(GENERIC.package, 'allow')));
    expect(at(`appops get ${GENERIC.package} REQUEST_INSTALL_PACKAGES`)).toBeLessThan(at('am start -n'));
  });

  it('Android 12 (API 31) is the first; Android 11 is left alone and says why', async () => {
    expect((await run({ ...LEVELS.api34!, sdk: 31, release: '12' })).res.silentUpdates.state).toBe('allowed');
    const { p, res } = await run({ ...LEVELS.api34!, sdk: 30, release: '11' });
    expect(res.silentUpdates).toEqual({ state: 'old-android', line: 'No: Android 11 asks for a tap on each update' });
    expect(p.cmds.some((c) => c.includes('REQUEST_INSTALL_PACKAGES'))).toBe(false);
  });

  it('the legacy build on Android 6: the same words as the app', async () => {
    const { p, res } = await run(LEVELS.api23!);
    expect(res.silentUpdates).toEqual({ state: 'old-android', line: SILENT_LINES.old('6.0.1') });
    expect(p.cmds.some((c) => c.includes('REQUEST_INSTALL_PACKAGES'))).toBe(false);
  });

  it('the person keeps a tap per update: nothing set', async () => {
    const { p, res } = await run(LEVELS.api34!, { silentUpdates: false });
    expect(res.silentUpdates).toEqual({ state: 'declined', line: SILENT_LINES.off });
    expect(p.cmds.filter((c) => WRITES.test(c) && c.includes('REQUEST_INSTALL_PACKAGES'))).toEqual([]);
  });

  it('keeping a tap over an earlier install that allowed it takes the allow back', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, installed: 600, installOpAllowed: true }, { silentUpdates: false });
    expect(res.silentUpdates).toEqual({ state: 'declined', line: SILENT_LINES.off });
    expect(p.installOp).toBe('default');
    expect(p.cmds).toContain(OP(GENERIC.package, 'default'));
  });

  it('an earlier allow the device won\'t take back: refused, and the line says updates stay silent', async () => {
    const { res } = await run({ ...LEVELS.api34!, installed: 600, installOpAllowed: true, keepInstallOpOn: true }, { silentUpdates: false });
    expect(res.silentUpdates).toEqual({ state: 'refused', line: SILENT_LINES.allowed, detail: 'Android still allows it from an earlier install' });
  });

  it('a build that keeps it off: refused, not fatal', async () => {
    const { res } = await run({ ...LEVELS.api34!, keepInstallOpOff: true });
    expect(res.silentUpdates).toEqual({ state: 'refused', line: SILENT_LINES.off, detail: 'exit 0, and Android says it is still off' });
    expect(res.home.state).toBe('set');
  });

  it('a build of the app that doesn\'t ask for it: not set', async () => {
    const apk = fakeGenericApk({ abis: ['arm64-v8a'], perms: ['android.permission.RECORD_AUDIO'] });
    const { p, res } = await run(LEVELS.api34!, { apk });
    expect(res.silentUpdates.state).toBe('not-asked');
    expect(p.cmds.some((c) => c.includes('REQUEST_INSTALL_PACKAGES'))).toBe(false);
  });

  it('undo puts it back to Android\'s default, and leaves the app', async () => {
    const { p } = await run(LEVELS.api34!);
    expect(await undoSilentUpdates(p)).toEqual({ app: GENERIC });
    expect(p.installOp).toBe('default');
    expect(p.installed).toBeDefined();
    expect(p.cmds).toContain(OP(GENERIC.package, 'default'));
  });

  it('keeping a tap when the app-op can\'t be read: refused with why, not declined, and the install goes on', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, installed: 600, installOpAllowed: true, installOpGetFailsAfter: 0 }, { silentUpdates: false });
    expect(res.silentUpdates).toEqual({ state: 'refused', line: SILENT_LINES.unknown, detail: 'couldn\'t read it: Error: java.lang.SecurityException' });
    expect(p.installOp).toBe('allow');
    expect(res.home.state).toBe('set');
  });

  it('keeping a tap when the readback after the reset fails: refused, and the line says it isn\'t confirmed', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, installed: 600, installOpAllowed: true, installOpGetFailsAfter: 1 }, { silentUpdates: false });
    expect(res.silentUpdates).toEqual({ state: 'refused', line: SILENT_LINES.unknown, detail: 'couldn\'t read it: Error: java.lang.SecurityException' });
    expect(p.installOp).toBe('default');
  });

  it('allowing it when the readback fails: refused, not confirmed, and the install goes on', async () => {
    const { p, res } = await run({ ...LEVELS.api34!, installOpGetFailsAfter: 0 });
    expect(res.silentUpdates).toEqual({ state: 'refused', line: SILENT_LINES.unknown, detail: 'couldn\'t read it: Error: java.lang.SecurityException' });
    expect(p.installOp).toBe('allow');
    expect(res.home.state).toBe('set');
  });

  it('undo throws when the device refuses the reset or its readback', async () => {
    const p = new FakeAndroid({ ...LEVELS.api34!, installed: 700, installOpAllowed: true, installOpFails: true });
    await expect(undoSilentUpdates(p)).rejects.toThrow(/panel command failed \(255\): appops set/);
    expect(p.installOp).toBe('allow');
  });

  it('undo throws when Android still allows it after the reset', async () => {
    const p = new FakeAndroid({ ...LEVELS.api34!, installed: 700, installOpAllowed: true, keepInstallOpOn: true });
    await expect(undoSilentUpdates(p)).rejects.toThrow(/still lets Glassworks install apps/);
  });

  it('undo with nothing installed changes nothing', async () => {
    const p = new FakeAndroid({});
    expect(await undoSilentUpdates(p)).toEqual({});
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });
});

describe('the screen lock (#522)', () => {
  // As the emulators answered with and without a lock, 2026-11-18 (research/screen-lock.md); IDs made up.
  const API26 = (sid: string) => `Current lock settings service state:\nSP Enabled = false\n    User 0\n        SP Handle = 0\n        SID = ${sid}\n    mPasswordOwner=-1\n`;
  const NEW = (sid: string, type: string) => [
    'Current lock settings service state:', '', 'User State:', '  User 0', '    SP Handle: 1a2b3c4d5e6f7a8b', '    Last changed: 2026-11-18 04:00:00 (0011223344556677)',
    `    SID: ${sid}`, '    Quality: 0', `    CredentialType: ${type}`, '    SeparateChallenge: true', '    Metrics: known', '  ',
    'Storage:', '  User 0 [/data/system_de/0/spblob]:', '    186 2026-11-18 04:00:00 1a2b3c4d5e6f7a8b.spblob', '  ', 'StrongAuth:', '  PrimaryAuthFlags state:', '    userId=0, primaryAuthFlags=0', '',
  ].join('\n');

  it.each([
    ['Android 8, no lock', API26('0'), 'none'],
    ['Android 8, a PIN', API26('48e9d0039527242e'), 'secure'],
    ['Android 11, none or swipe', NEW('0', 'None'), 'none'],
    ['Android 11, a PIN', NEW('1234abcd5678ef90', 'Pin'), 'secure'],
    ['Android 11, a password', NEW('1234abcd5678ef90', 'Password'), 'secure'],
    ['Android 15, no lock: a SID all the same', NEW('1234abcd5678ef90', 'NONE'), 'none'],
    ['Android 15, a PIN', NEW('1234abcd5678ef90', 'PIN'), 'secure'],
    ['Android 15, a pattern', NEW('1234abcd5678ef90', 'PATTERN'), 'secure'],
    ['a numeric type, none', NEW('0', '-1'), 'none'],
  ])('%s', (_, dump, want) => expect(parseScreenLock(dump)).toBe(want));

  it('says nothing when it can\'t tell', () => {
    expect(parseScreenLock('')).toBeUndefined();
    expect(parseScreenLock("Can't find service: lock_settings")).toBeUndefined();
    expect(parseScreenLock('Current lock settings service state:\n  User 0\n    Quality: 0\n')).toBeUndefined();
  });

  it('reads the first user, not a second one\'s lock', () => {
    expect(parseScreenLock(NEW('0', 'None').replace('Storage:', '  User 10\n    SID: 99\n    CredentialType: PIN\n  \nStorage:'))).toBe('none');
  });

  it.each(['api26', 'api29', 'api34'])('%s: Done gets the lock, and the log never has the dump\'s IDs', async (level) => {
    const log: string[] = [];
    const p = new FakeAndroid({ ...LEVELS[level]!, lock: 'pin' });
    const res = await installGeneric(p, { apk: apkFor(p.o.sdk ?? 34), sleep: noSleep, setupTries: 2, onStep: (s, d) => log.push(s || d || '') });
    expect(res.screenLock).toBe('secure');
    expect(log).toContain('screen lock: a PIN, pattern or password');
    expect(log.join('\n')).not.toMatch(/1234abcd/);
    expect((await run(LEVELS[level]!)).res.screenLock).toBe('none');
  });

  it.each(['api23', 'api25'])('%s: Android 6 and 7 don\'t say, so Done says nothing', async (level) => {
    expect((await run({ ...LEVELS[level]!, lock: 'pin' })).res.screenLock).toBeUndefined();
  });
});

describe('the hand-off to setup over the LAN', () => {
  it('not on Wi-Fi: no page, and why', async () => {
    const { res } = await run({ ...LEVELS.api34!, ip: undefined });
    expect(res.handoff).toEqual({ why: 'The device isn\'t on Wi-Fi yet.' });
  });

  it('an app with no setup channel', async () => {
    const p = new FakeAndroid({ installed: 700, noChannel: true });
    expect(await handOff(p, { tries: 3, sleep: noSleep })).toEqual({ why: 'The app didn\'t answer on its setup channel.' });
    expect(p.cmds.filter((c) => c.startsWith('open '))).toHaveLength(3);
  });

  it('an app without pairing.arm: its address, no page', async () => {
    const p = new FakeAndroid({ installed: 700, ip: IP, noPairing: true });
    expect(await handOff(p, { sleep: noSleep })).toEqual({ ip: IP, why: 'This version of the app has no setup page.' });
  });
});

describe('undo', () => {
  it('takes back the device admin, then removes the app', async () => {
    const p = new FakeAndroid({ ...LEVELS.api34!, installed: 700 });
    expect((await undoGeneric(p, { sleep: noSleep })).removed).toEqual([GENERIC]);
    expect(p.installed).toBeUndefined();
    const at = (c: string) => p.cmds.indexOf(c);
    expect(at(`am start -n ${GENERIC.activity} --ez remove_admin true`)).toBeLessThan(at(`pm uninstall ${GENERIC.package}`));
  });

  it('doesn\'t wait on the other build\'s admin', async () => {
    const p = new FakeAndroid({ ...LEVELS.api34!, installed: 700, otherAdmin: true });
    expect((await undoGeneric(p, { sleep: noSleep })).removed).toEqual([GENERIC]);
    expect(p.cmds.filter((c) => c === 'dumpsys device_policy')).toHaveLength(1);
    expect(p.admins).toEqual(new Set([LEGACY.admin]));
  });

  it('with nothing installed, removes nothing', async () => {
    const p = new FakeAndroid({});
    expect((await undoGeneric(p, { sleep: noSleep })).removed).toEqual([]);
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });
});
