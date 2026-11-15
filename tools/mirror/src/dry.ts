// The dry run: runInstall and runWayBack against a fake Mirror. No device is touched. The fastboot
// side is @glassworks/fastboot's real Fastboot class on FakeLk (Qualcomm LK as far as flashBlob uses
// it); adb is the installer's fakes (the community image before the flash, the first boot after).
// The system image is a small one made like the Glass Panes one, so the real size and SHA checks run
// against its own published-style size and hash.
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { Fastboot } from '@glassworks/fastboot';
import { FakeLk } from '@glassworks/fastboot/fake-lk';
import { GLASS_PANES, SYSTEM_BYTES, USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, FirstBootMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import type { RunDeps, Want } from './run';

const MiB = 1024 * 1024;
/** The userdata the dry run builds and its fake partition holds (the real one is 5.2 GiB). */
export const DRY_USERDATA = 64 * MiB;

/**
 * A small system.img made like the Glass Panes one: its fingerprint in /build.prop and Launcher3 in
 * /priv-app. `room`: bytes the patch will add (the APK, the boot animation).
 */
export async function dryImage(work: string, room = 0): Promise<{ path: string; want: Want; bytes: number }> {
  const blocks = Math.ceil((32 * MiB + room * 1.5) / 4096);
  const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
  const fs = await mkfs(disk, { label: 'system', journalBlocks: 1024 });
  const a = { mode: 0o755, uid: 0, gid: 0 }, f = { mode: 0o644, uid: 0, gid: 0 };
  await fs.mkdirs('/priv-app/Launcher3', a);
  await fs.mkdirs('/media', a);
  await fs.writeFile('/build.prop', new TextEncoder().encode(`ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`), f);
  await fs.writeFile('/priv-app/Launcher3/Launcher3.apk', new Uint8Array(64 * 1024).fill(0x4c), f);
  await fs.flush();
  const parts: Uint8Array[] = [];
  await writeSparse(disk, (b) => { parts.push(b.slice()); });
  const bytes = Buffer.concat(parts);
  const path = join(work, 'dry-system.img');
  writeFileSync(path, bytes);
  return { path, want: { name: 'system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }, bytes: blocks * 4096 };
}

/**
 * FakeLk plus the variables the Mirror's checks read. Both partitions report the Mirror's sizes, so
 * checkFastboot's real checks run, but hold only what the dry run writes: the dry image, and a
 * userdata of DRY_USERDATA (runInstall's `userdataBytes`, dry runs only).
 */
export class DryLk extends FakeLk {
  constructor(systemBytes: number) {
    super(16 * MiB, [['system', systemBytes], ['userdata', DRY_USERDATA]]);
  }

  override async out(data: Uint8Array): Promise<void> {
    const cmd = data.length <= 64 ? new TextDecoder().decode(data) : '';
    const answer = cmd === 'getvar:partition-size:system' ? `0x${SYSTEM_BYTES.toString(16)}`
      : cmd === 'getvar:partition-size:userdata' ? `0x${USERDATA_BYTES.toString(16)}`
        : cmd === 'getvar:product' ? 'MSM8916' : cmd === 'reboot' ? '' : undefined;
    if (answer === undefined) return super.out(data);
    this.commands.push(cmd);
    this.replies.push(new TextEncoder().encode(`OKAY${answer}`));
  }
}

export interface Dry { deps: RunDeps; lk: DryLk; before: FakeMirror; after: FirstBootMirror; state: () => 'android' | 'fastboot' | 'glassworks' }

/** Fake devices for runInstall/runWayBack. `stockAtStart: false` starts in fastboot already. */
export function dryDevices(systemBytes: number, log: (l: string) => void, o: { stockAtStart?: boolean; hideFails?: boolean } = {}): Dry {
  const lk = new DryLk(systemBytes);
  const before = new FakeMirror();
  const after = new FirstBootMirror();
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
