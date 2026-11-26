// Where the S1 tests (flow.test.ts, wire-trace.test.ts) find the panel's real partitions and the goldens, and
// whether that golden set fits this revision. The goldens hold Aqara's boot images and Magisk binaries, so they
// live outside the repo: S1_WEB_GOLDEN=<dir> npx vitest run (default ~/src/aqara-panel-backups/web-golden).
// Each set is made by the gen.sh in its own folder, from the CLI patcher at one revision.
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const B = join(homedir(), 'src/aqara-panel-backups');
export const STOCK = join(B, 'install-4.5.80-20261109-203611');
export const G = process.env.S1_WEB_GOLDEN ?? join(B, 'web-golden');
export const HAVE = existsSync(join(STOCK, 'boot.img')) && existsSync(join(G, 'out/SHA256SUMS'));

/** The overlay files this revision's recipe reads: a set made for another overlay (before a rename) can't drive these tests. */
export const OVERLAY = ['glassworks.rc', 'glassworks_loader.sh', 'glassworks_verify', 'glassworks_bundle.pub', 'glassworks_agent.sh',
  'glassworks_firstboot.sh', 'glassworks_netwatch.sh', 'glassworks_update.sh', 'glassworks_bootanimation.zip', 'otbr-agent', 'ot-ctl'];
const NEED = [...OVERLAY.map((f) => `fixtures/overlay/${f}`), 'fixtures/Magisk-v31.1.apk', 'fixtures/bootlogo.bmp',
  'fixtures/test_adbkey.pub', 'out/stock-4.5.80.img', 'out/bootloader-glass-tile.img'];
/** What the set at G lacks (empty without a set: then the tests just skip). */
export const MISSING = HAVE ? NEED.filter((f) => !existsSync(join(G, f))) : [];
export const STALE = `${G} is a golden set for another revision (it lacks ${MISSING.join(', ')}): ` +
  'make a set for this revision with gen.sh (copy one from an older set) and point S1_WEB_GOLDEN at it';
