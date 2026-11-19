#!/usr/bin/env node
// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
//
// Patch an S1 boot image with Kiln's boot patcher (kiln/packages/bootpatch), on the host, the way
// Kiln does in the browser. Touches no device and no network: it reads the files named below and
// writes one output file.
//
//   node tools/kiln/patch-boot.mjs <boot.img> <out.img> --apk <Magisk-v31.1.apk> [options]
//
//   --apk <file>          the official Magisk v31.1 APK (required; checked against its SHA-256 in kiln/assets.lock.json)
//   --overlay-dir <dir>   take every overlay file from one directory, by its file name (the layout of
//                         the golden set's fixtures/overlay). Default: the repo's own files and build
//                         outputs, as frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh uses them
//   --adb-key <file|none> adb public key to authorize on first boot (default ~/.android/adbkey.pub)
//   --no-anim             no boot animation (BOOT_ANIMATION=none in the CLI)
//   --preinit <device>    PREINITDEVICE for a stock image (default cache)
//   --package <dir>       the bootpatch package to use (default: this checkout's). Point it at another
//                         checkout's kiln/packages/bootpatch to compare two patchers on the same input
//
// Needs Node 22.18 or later (it loads the package's TypeScript directly).
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { registerHooks } from 'node:module';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const { values: opt, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    apk: { type: 'string' }, 'overlay-dir': { type: 'string' }, 'adb-key': { type: 'string' },
    'no-anim': { type: 'boolean' }, preinit: { type: 'string' }, package: { type: 'string' },
  },
});
const [input, output] = positionals;
if (!input || !output || !opt.apk) {
  console.error('usage: node tools/kiln/patch-boot.mjs <boot.img> <out.img> --apk <Magisk-v31.1.apk> [options]');
  process.exit(2);
}

// The package's sources import each other without extensions, as the bundler allows.
registerHooks({
  resolve(spec, ctx, next) {
    try { return next(spec, ctx); } catch (e) {
      if (!spec.startsWith('.') || /\.[cm]?[jt]s$/.test(spec)) throw e;
      return next(`${spec}.ts`, ctx);
    }
  },
});
const pkg = resolve(opt.package ?? join(REPO, 'kiln/packages/bootpatch'));
const { magiskBusybox, patchBoot } = await import(pathToFileURL(join(pkg, 'src/index.ts')).href);

const read = (p) => new Uint8Array(readFileSync(p));
const sha256 = (d) => createHash('sha256').update(d).digest('hex');
const fail = (msg) => { console.error(msg); process.exit(1); };

const root = join(REPO, 'frames/aqara-s1-plus/tools/root');
const radio = join(REPO, 'frames/aqara-s1-plus/tools/radio');
if (!opt['overlay-dir']) {
  const buildRadio = 'build the radio first: OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run frames/aqara-s1-plus/tools/radio/build-otbr.sh';
  for (const [file, how] of [
    [join(radio, 'out/otbr-agent'), buildRadio], [join(radio, 'out/ot-ctl'), buildRadio], [join(radio, 'out/BUILD-INFO.txt'), buildRadio],
    [join(root, 'out/s1kiosk_verify'), 'build it first: frames/aqara-s1-plus/tools/root/build-verify.sh'],
  ]) {
    if (!existsSync(file)) fail(`missing ${file}: ${how}`);
  }
  if (!readFileSync(join(radio, 'out/BUILD-INFO.txt'), 'utf8').includes('/data/s1kiosk/radio/run')) {
    fail('otbr was built with the wrong OTBR_RUNTIME_DIR (need /data/s1kiosk/radio/run)');
  }
}

const apk = read(opt.apk);
const pinned = JSON.parse(readFileSync(join(REPO, 'kiln/assets.lock.json'), 'utf8')).assets.find((a) => a.name === 'magisk').sha256;
if (sha256(apk) !== pinned) fail(`${opt.apk} is not the pinned Magisk APK (sha256 ${pinned})`);
const from = opt['overlay-dir']
  ? (name) => read(join(opt['overlay-dir'], name))
  : (name) => read({
    'glassworks_radio.sh': join(radio, name),
    'otbr-agent': join(radio, 'out', name), 'ot-ctl': join(radio, 'out', name),
    'glassworks_verify': join(root, 'out', 's1kiosk_verify'),
    'glassworks_bootanimation.zip': join(REPO, 'branding/glass-tile/bootanimation.zip'),
  }[name] ?? join(root, name));
const adbKey = opt['adb-key'] ?? join(homedir(), '.android/adbkey.pub');

// The overlay always comes from this checkout, so --package changes only the patch algorithm.
const { s1Overlay } = await import(pathToFileURL(join(REPO, 'kiln/packages/bootpatch/src/index.ts')).href);
const overlay = s1Overlay({
  rc: from('glassworks.rc'), loaderSh: from('glassworks_loader.sh'), verify: from('glassworks_verify'), bundlePub: from('glassworks_bundle.pub'),
  adbKeys: adbKey === 'none' ? new Uint8Array(0) : read(adbKey),
  radioSh: from('glassworks_radio.sh'), otbrAgent: from('otbr-agent'), otCtl: from('ot-ctl'),
  firstbootSh: from('glassworks_firstboot.sh'), netwatchSh: from('glassworks_netwatch.sh'), updateSh: from('glassworks_update.sh'),
  magiskBusybox: await magiskBusybox(apk),
  bootAnimation: opt['no-anim'] ? undefined : from('glassworks_bootanimation.zip'),
});

const r = await patchBoot({ boot: read(input), magiskApk: apk, overlay, preinitDevice: opt.preinit });
writeFileSync(output, r.image);
for (const line of r.log) console.log(line);
console.log(`--- Magisk config:\n${r.magiskConfig}--- wasMagisk: ${r.wasMagisk}`);
console.log(`${output}: ${r.image.length} bytes, sha256 ${sha256(r.image)}`);
