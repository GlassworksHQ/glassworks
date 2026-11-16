// run-node.ts: the generic Android recipe (#403) from a computer, without a browser tab, for the first run
// on a real tablet. Same code as Kiln will run; only the transport differs: the host's adb server
// (`adb -s <serial>`, tools/mirror/src/adb.ts), the serial read from a file and never printed.
//
//   --plan     read-only: what the device is, which build it needs, whether the APK is it, the home app now
//   --install  WRITES: installs or updates the app, grants, sets the home screen, starts it (undo: --undo)
//   --undo     WRITES: takes back the device admin and removes the app
//   --dry-run  the same on a fake Android 14 tablet (test/fakes.ts): no device touched
//
// tsx isn't in the repo's lockfile (see tools/mirror/flash-node.ts): npx fetches the pinned version.
//   npx -y tsx@4.23.15 frames/generic-android/installer/run-node.ts --plan --apk app.apk [--serial-file /tmp/tablet-serial]
//   npx -y tsx@4.23.15 frames/generic-android/installer/run-node.ts --install --apk app.apk --ha-url http://homeassistant.local:8123
import { readFileSync } from 'node:fs';
import type { PanelShell } from '@glassworks/adb';
import { hostAdb, readSerial } from '../../../tools/mirror/src/adb';
import { currentHome, installGeneric, planInstall, undoGeneric } from './src';
import { FakeAndroid, fakeGenericApk } from './test/fakes';

/** `shell`: the device to use instead of the host's adb or --dry-run's fake (the tests). */
export async function cli(argv: string[], log: (l: string) => void = (l) => console.log(l), shell?: PanelShell): Promise<number> {
  const flag = (f: string) => argv.includes(f);
  const value = (f: string) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : undefined; };
  const mode = (['--plan', '--install', '--undo'] as const).find(flag);
  if (!mode) { log('usage: run-node.ts (--plan | --install | --undo) [--apk <file>] [--ha-url <url>] [--serial-file <file>] [--dry-run]'); return 2; }
  const dry = flag('--dry-run');
  const p: PanelShell = shell ?? (dry ? new FakeAndroid({ ip: '192.0.2.10' }) : hostAdb(readSerial(value('--serial-file') ?? '/tmp/tablet-serial')));
  const apkFile = value('--apk');
  const apk = apkFile ? new Uint8Array(readFileSync(apkFile)) : dry ? fakeGenericApk({ app: p instanceof FakeAndroid ? p.app : undefined }) : undefined;
  const onStep = (s: string, d?: string) => log(s ? `\n## ${s}` : `   ${d}`);
  if (mode === '--undo') { log(JSON.stringify(await undoGeneric(p, { onStep }), (k, v) => (k === 'removed' ? v.map((a: { package: string }) => a.package) : v), 2)); return 0; }
  if (!apk) { log('--apk <file> is needed'); return 2; }
  if (mode === '--plan') {
    const plan = await planInstall(p, apk);
    // version-aware (no `cmd` on Android 6); a failed lookup says so, never prints its error as a component
    const home = (await currentHome(p, plan.device.sdk)) ?? 'unknown (the lookup failed)';
    log(JSON.stringify({ device: plan.device, brand: plan.identified.brand?.name, model: plan.identified.model?.name, tested: plan.identified.tested,
      app: plan.app.package, abi: plan.abi, installed: plan.installed, home }, null, 2));
    return 0;
  }
  const res = await installGeneric(p, { apk, haUrl: value('--ha-url'), onStep });
  log(JSON.stringify({ app: res.app, updated: res.updated, grants: res.grants, home: res.home, handoff: res.handoff }, null, 2));
  return 0;
}

if (process.argv[1]?.endsWith('run-node.ts')) {
  cli(process.argv.slice(2)).then((code) => process.exit(code), (e: unknown) => { console.error(`\nrun-node: ${(e as Error).message}`); process.exit(1); });
}
