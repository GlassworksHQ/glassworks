// The dry run: runInstall and runWayBack against a fake Mirror. No device is touched. The fastboot
// side is @glassworks/fastboot's real Fastboot class on FakeLk (Qualcomm LK as far as flashBlob uses
// it); adb is the installer's fakes (the community image before the flash, the first boot after).
// The system image is a small one made like the Glass Panes one, so the real size and SHA checks run
// against its own published-style size and hash.
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import { zipEntry } from '@glassworks/app-adb';
import { FakeKeySocket } from '@glassworks/app-adb/fakes';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { Fastboot } from '@glassworks/fastboot';
import { FakeLk } from '@glassworks/fastboot/fake-lk';
import { GLASS_PANES, SYSTEM_BYTES, USERDATA_BYTES, apkLibs } from '@glassworks/lululemon-mirror-installer';
import type { MirrorApp } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, FirstBootMirror, fakeServicesJar } from '@glassworks/lululemon-mirror-installer/fakes';
import type { RunDeps, Want } from './run';

const MiB = 1024 * 1024;
/** The userdata the dry run builds and its fake partition holds (the real one is 5.2 GiB). */
export const DRY_USERDATA = 64 * MiB;

/**
 * The bytes the patch adds to the dry run's system image for `apk`: the APK itself, and every native
 * library image.ts unpacks out of it (Android 6 doesn't read them from the APK, so they land
 * uncompressed next to it). `extra`: the boot animation and the settings overlay, when there are.
 */
export async function dryRoom(apk: Uint8Array, extra = 0): Promise<number> {
  const libs = apkLibs(apk);
  let room = apk.length + extra;
  for (const n of libs?.names ?? []) room += (await zipEntry(apk, n))?.length ?? 0;
  return room;
}

/**
 * A small system.img made like the Glass Panes one: its fingerprint in /build.prop, Launcher3 and its
 * own kiosk app in /priv-app, and a services.jar that trusts the image's own key. `room`: bytes the patch will add (the APK, its libraries, the boot animation, the settings overlay).
 */
export async function dryImage(work: string, room = 0): Promise<{ path: string; want: Want; bytes: number }> {
  const blocks = Math.ceil((32 * MiB + room * 1.5) / 4096);
  const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
  const fs = await mkfs(disk, { label: 'system', journalBlocks: 1024 });
  const a = { mode: 0o755, uid: 0, gid: 0 }, f = { mode: 0o644, uid: 0, gid: 0 };
  await fs.mkdirs('/priv-app/Launcher3', a);
  await fs.mkdirs('/media', a);
  await fs.mkdirs('/etc/wifi', a);
  await fs.mkdirs('/framework', a);
  await fs.writeFile('/framework/services.jar', await fakeServicesJar(), f);
  await fs.writeFile('/etc/wifi/wpa_supplicant.conf', new TextEncoder().encode('update_config=1\neapol_version=1\nap_scan=1\nfast_reauth=1\npmf=1\np2p_no_group_iface=1\n'), f);
  await fs.writeFile('/build.prop', new TextEncoder().encode(`ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`), f);
  await fs.writeFile('/priv-app/Launcher3/Launcher3.apk', new Uint8Array(64 * 1024).fill(0x4c), f);
  await fs.mkdirs('/priv-app/MirrorKiosk', a);
  await fs.writeFile('/priv-app/MirrorKiosk/MirrorKiosk.apk', new Uint8Array(64 * 1024).fill(0x4d), f);
  await fs.flush();
  const parts: Uint8Array[] = [];
  await writeSparse(disk, (b) => { parts.push(b.slice()); });
  const bytes = Buffer.concat(parts);
  const path = join(work, 'dry-system.img');
  writeFileSync(path, bytes);
  return { path, want: { name: 'system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }, bytes: blocks * 4096 };
}

/** A made-up serial for the dry LK (PanelDevice's DeviceMacPinTest uses the same one). */
export const DRY_SERIAL = '0123456789abcdef';

/**
 * FakeLk plus the variables the Mirror's checks read. Both partitions report the Mirror's sizes, so
 * checkFastboot's real checks run, but hold only what the dry run writes: the dry image, and a
 * userdata of DRY_USERDATA (runInstall's `userdataBytes`, dry runs only).
 */
export class DryLk extends FakeLk {
  /** `maxDownloadReply`: LK's answer, `\t0x…` as it writes it (a test gives a decimal one). */
  constructor(systemBytes: number, maxDownloadReply = `\t0x${(16 * MiB).toString(16)}`) {
    super(16 * MiB, [['system', systemBytes], ['userdata', DRY_USERDATA]], maxDownloadReply);
  }

  override async out(data: Uint8Array): Promise<void> {
    const cmd = data.length <= 64 ? new TextDecoder().decode(data) : '';
    const answer = cmd === 'getvar:partition-size:system' ? `0x${SYSTEM_BYTES.toString(16)}`
      : cmd === 'getvar:partition-size:userdata' ? `0x${USERDATA_BYTES.toString(16)}`
        : cmd === 'getvar:product' ? 'MSM8916' : cmd === 'getvar:serialno' ? DRY_SERIAL : cmd === 'reboot' ? '' : undefined;
    if (answer === undefined) return super.out(data);
    this.commands.push(cmd);
    this.replies.push(new TextEncoder().encode(`OKAY${answer}`));
  }
}

export interface Dry { deps: RunDeps; lk: DryLk; before: FakeMirror; after: FirstBootMirror; state: () => 'android' | 'fastboot' | 'glassworks' }

/** Fake devices for runInstall/runWayBack. `stockAtStart: false` starts in fastboot already; `app` is the app the APK is (Glassworks by default). */
export function dryDevices(systemBytes: number, log: (l: string) => void, o: { stockAtStart?: boolean; hideFails?: boolean; maxDownloadReply?: string; app?: MirrorApp;
  /** a reflash (#574): Glassworks runs before the flash and holds this ESPHome API key; the fresh app's socket starts empty */
  apiKey?: string } = {}): Dry {
  const lk = new DryLk(systemBytes, o.maxDownloadReply);
  const before = new FakeMirror({ app: o.app });
  const after = new FirstBootMirror(undefined, true, o.app);
  if (o.apiKey) { before.withKeySocket(new FakeKeySocket(o.apiKey)); after.withKeySocket(); }
  after.hideFails = !!o.hideFails;
  let state: 'android' | 'fastboot' | 'glassworks' = o.stockAtStart === false ? 'fastboot' : 'android';
  const fb = new Fastboot(lk);
  const reboot = fb.reboot.bind(fb);
  fb.reboot = async () => { await reboot(); state = 'glassworks'; };
  const deps: RunDeps = {
    log,
    confirm: async () => true,
    adb: async () => (state === 'android' ? before : state === 'glassworks' ? after : undefined),
    rebootToBootloader: async () => { state = 'fastboot'; },
    fastboot: async () => { if (state !== 'fastboot') throw new Error('dry run: the Mirror isn\'t in fastboot'); return fb; },
    waitForAdb: async (): Promise<PanelShell> => { if (state !== 'glassworks') throw new Error('dry run: no adb'); return after; },
    screenshot: async (file) => { writeFileSync(file, new Uint8Array([0x89, 0x50, 0x4e, 0x47])); },
    sleep: async () => {},
  };
  return { deps, lk, before, after, state: () => state };
}
