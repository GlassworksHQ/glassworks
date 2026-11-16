// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
//
// tools/kiln/patch-boot.mjs refuses, with what to build, when the repo's build outputs are missing.
// It runs from a copy in an empty repo layout, so the real checkout's builds don't matter.

import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

const PKG = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = resolve(PKG, '../../../tools/kiln/patch-boot.mjs');
const RADIO_OUT = 'frames/aqara-s1-plus/tools/radio/out';

let repo: string;
const put = (path: string, text = '') => {
  mkdirSync(dirname(join(repo, path)), { recursive: true });
  writeFileSync(join(repo, path), text);
};
const run = () => {
  const r = spawnSync(process.execPath, [join(repo, 'tools/kiln/patch-boot.mjs'), 'in.img', 'out.img', '--apk', 'Magisk.apk', '--package', PKG], { encoding: 'utf8' });
  return { status: r.status, stderr: r.stderr };
};

beforeEach(() => {
  repo = realpathSync(mkdtempSync(join(tmpdir(), 'patch-boot-')));
  mkdirSync(join(repo, 'tools/kiln'), { recursive: true });
  copyFileSync(SCRIPT, join(repo, 'tools/kiln/patch-boot.mjs'));
});
afterEach(() => rmSync(repo, { recursive: true, force: true }));

describe('patch-boot.mjs without the build outputs', () => {
  it('asks for the radio build when it is missing', () => {
    const { status, stderr } = run();
    expect(status).toBe(1);
    expect(stderr.trim()).toBe(`missing ${join(repo, RADIO_OUT, 'otbr-agent')}: build the radio first: OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run frames/aqara-s1-plus/tools/radio/build-otbr.sh`);
  });

  it('asks for s1kiosk_verify when only the radio is built', () => {
    for (const f of ['otbr-agent', 'ot-ctl']) put(`${RADIO_OUT}/${f}`);
    put(`${RADIO_OUT}/BUILD-INFO.txt`, 'OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run\n');
    const { status, stderr } = run();
    expect(status).toBe(1);
    expect(stderr).toContain('s1kiosk_verify: build it first: frames/aqara-s1-plus/tools/root/build-verify.sh');
  });

  it('refuses a radio built for another runtime directory', () => {
    for (const f of ['otbr-agent', 'ot-ctl']) put(`${RADIO_OUT}/${f}`);
    put(`${RADIO_OUT}/BUILD-INFO.txt`, 'OTBR_RUNTIME_DIR=/tmp/otbr\n');
    put('frames/aqara-s1-plus/tools/root/out/s1kiosk_verify');
    const { status, stderr } = run();
    expect(status).toBe(1);
    expect(stderr.trim()).toBe('otbr was built with the wrong OTBR_RUNTIME_DIR (need /data/s1kiosk/radio/run)');
  });
});
