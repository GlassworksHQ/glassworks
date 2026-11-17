// The Android SDK's dexdump, which verifies a dex as ART does (it refuses "Out-of-order string_ids")
// and prints its code, debug locals and annotations. Optional: tests that need it skip without an SDK.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';

function find(): string | undefined {
  for (const sdk of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, join(homedir(), 'Library/Android/sdk')]) {
    if (!sdk || !existsSync(join(sdk, 'build-tools'))) continue;
    const v = readdirSync(join(sdk, 'build-tools')).sort((a, b) => a.localeCompare(b, 'en', { numeric: true })).reverse()
      .map((d) => join(sdk, 'build-tools', d, 'dexdump')).find((p) => existsSync(p));
    if (v) return v;
  }
  return undefined;
}

export const DEXDUMP = find();

/**
 * dexdump's verdict and its disassembly with the annotations, minus what renumbering strings changes
 * by design: the indexes (`string@…`, `source_file_idx`) and the instruction bytes. `fingerprint`
 * texts become <FP>, so the only expected change disappears too.
 */
export function disassemble(dex: Uint8Array): { ok: boolean; text: string; err: string } {
  const dir = mkdtempSync(join(tmpdir(), 'gw-dex-'));
  try {
    const f = join(dir, 'classes.dex');
    writeFileSync(f, dex);
    let text = '';
    try { text = execFileSync(DEXDUMP!, ['-d', '-a', f], { encoding: 'utf8', maxBuffer: 1 << 30, stdio: ['ignore', 'pipe', 'pipe'] }); } catch (e) {
      return { ok: false, text: '', err: String((e as { stderr?: string }).stderr ?? e) };
    }
    text = text.split('\n').filter((l) => !l.startsWith('Processing') && !l.startsWith('Opened')).join('\n')
      .replace(/ \/\/ string@[0-9a-f]+/g, '').replace(/string@[0-9a-f]+/g, 'string@?')
      .replace(/source_file_idx {3}: \d+ /g, 'source_file_idx   : ? ')
      .replace(/^([0-9a-f]{6}:) (?:[0-9a-f]{4} )+/gm, '$1 ')
      .replace(/"(?:[0-9A-F]{2}:){15}[0-9A-F]{2}"/g, '"<FP>"');
    return { ok: true, text, err: '' };
  } finally { rmSync(dir, { recursive: true, force: true }); }
}
