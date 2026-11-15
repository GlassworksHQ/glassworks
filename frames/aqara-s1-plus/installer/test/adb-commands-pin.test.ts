// The S1's adb command sequence, pinned. Kiln's S1 install, key switch and undo send exactly these
// commands, in this order, whatever the code behind them looks like (their shared steps moved to
// @glassworks/app-adb in #29's PR without changing one byte). The golden was written from main's
// code before that move. A change to it is a change to what the panel sees: review it as one.
// Rewrite it on purpose with S1_PIN_WRITE=1.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { provision, unprovision } from '../src/provision';

const GOLDEN = join(__dirname, 'golden/s1-adb-commands.json');
const MISMATCH = 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package sh.edm.s1kiosk signatures do not match previously installed version; ignoring!]\n';

/** Records every command and push. `answer` gives an output for the commands a path branches on. */
class Recorder implements PanelShell {
  cmds: string[] = [];
  constructor(private readonly answer: (cmd: string, n: number) => { out: string; code: number } | undefined = () => undefined) {}
  async run(cmd: string) {
    this.cmds.push(cmd);
    return this.answer(cmd, this.cmds.filter((c) => c === cmd).length) ?? { out: '', code: 0 };
  }
  async push(path: string, data: Uint8Array, mode?: number) { this.cmds.push(`push ${path} ${data.length} ${(mode ?? 0o644).toString(8)}`); }
}

const common = (cmd: string): { out: string; code: number } | undefined => {
  if (cmd === 'getprop ro.product.model') return { out: 'Aqara S1 Plus\n', code: 0 };
  if (cmd === 'getprop ro.build.version.sdk') return { out: '29\n', code: 0 };
  if (cmd.startsWith('pm install')) return { out: 'Success\n', code: 0 };
  if (cmd === 'ime list -a -s') return { out: 'com.baidu.input_auto/.ImeService\nrkr.simplekeyboard.inputmethod/.latin.LatinIME\n', code: 0 };
  if (cmd === 'pm list packages -d com.aqara.inhome.vassistant') return { out: 'package:com.aqara.inhome.vassistant\n', code: 0 };
  return undefined;
};
const noSleep = async () => {};

async function scenarios(): Promise<Record<string, string[]>> {
  const out: Record<string, string[]> = {};

  const full = new Recorder(common);
  await provision(full, {
    kioskApk: new Uint8Array(3), keyboardApk: new Uint8Array(2), haUrl: `http://homeassistant.local:8123/it's`,
    timezone: 'Europe/Berlin', locale: 'de-DE', clock24: true,
  }, () => {}, noSleep);
  out['provision, everything'] = full.cmds;

  const bare = new Recorder(common);
  await provision(bare, { kioskApk: new Uint8Array(1), debloat: false, dndOff: false }, () => {}, noSleep);
  out['provision, the app alone'] = bare.cmds;

  // a development build on the panel, no root: the switch uninstalls it and installs this one
  const sw = new Recorder((cmd, n) => {
    if (cmd === 'pm install -r -g /data/local/tmp/s1kiosk.apk' && n === 1) return { out: MISMATCH, code: 1 };
    if (cmd === 'dumpsys package sh.edm.s1kiosk') return { out: '    versionName=0.1.0\n    pkgFlags=[ DEBUGGABLE HAS_CODE ]\n', code: 0 };
    if (cmd.startsWith('timeout 15 su')) return { out: '', code: 1 };
    if (cmd === 'pm path sh.edm.s1kiosk') return { out: 'package:/data/app/sh.edm.s1kiosk-1/base.apk\n', code: 0 };
    if (cmd === 'dumpsys device_policy') return { out: n < 3 ? 'Admin sh.edm.s1kiosk/.AdminReceiver\n' : '', code: 0 };
    if (cmd === 'pm uninstall sh.edm.s1kiosk') return { out: 'Success\n', code: 0 };
    return common(cmd);
  });
  await provision(sw, { kioskApk: new Uint8Array(4), haUrl: 'http://homeassistant.local:8123', confirmSwitch: async () => true }, () => {}, noSleep);
  out['provision, signing-key switch without root'] = sw.cmds;

  const undo = new Recorder((cmd, n) => {
    if (cmd === 'dumpsys device_policy') return { out: n < 3 ? 'Admin sh.edm.s1kiosk/.AdminReceiver\n' : '', code: 0 };
    if (cmd === 'pm uninstall sh.edm.s1kiosk') return { out: 'Success\n', code: 0 };
    if (cmd === 'pm list packages rkr.simplekeyboard.inputmethod') return { out: 'package:rkr.simplekeyboard.inputmethod\n', code: 0 };
    if (cmd.startsWith('cmd package resolve-activity')) return { out: 'com.aqara.inhome.launcher/.MainActivity\n', code: 0 };
    return undefined;
  });
  await unprovision(undo, () => {}, noSleep);
  out.undo = undo.cmds;
  return out;
}

describe('the S1\'s adb commands', () => {
  it('are the pinned sequence, byte for byte', async () => {
    const now = await scenarios();
    if (process.env.S1_PIN_WRITE) writeFileSync(GOLDEN, `${JSON.stringify(now, null, 2)}\n`);
    expect(now).toEqual(JSON.parse(readFileSync(GOLDEN, 'utf8')));
    expect(Object.values(now).every((c) => c.length > 10)).toBe(true);
  });
});
