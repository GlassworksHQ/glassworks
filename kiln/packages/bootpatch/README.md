<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# `@glassworks/bootpatch`

Kiln's boot image patcher. It roots the S1's `boot` partition in the browser and gives the same
bytes as the CLI, [`patch-boot-magisk.sh`](../../../frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh).
It also edits the vbmeta flags and the boot logo.

**Licence: MIT.** The patcher was rewritten in a clean room on 2026-11-15 from
[bootpatch-spec.md](../../../docs/research/kiln-cleanroom/bootpatch-spec.md) (#355), by someone who
had not read Magisk's source. It holds no Magisk code. Magisk's own binaries go into the boot image,
but they come from the official Magisk v31.1 APK, which Kiln fetches from Magisk's release and checks
by SHA-256 (`kiln/assets.lock.json`). This repo never ships them.

## Files

| File | What |
|---|---|
| `src/index.ts` | The public API: `patchBoot`, `patchBootImage`, `isMagiskPatched`, and re-exports |
| `src/bootimage.ts` | Android boot image header v2 and the AVB footer: read (with every refusal) and repack |
| `src/ramdisk.ts` | cpio "newc": read and write |
| `src/patch.ts` | The patch: undo an earlier Magisk patch, add Magisk, back up, add the overlay |
| `src/apk.ts` | A small zip reader that takes the binaries out of the Magisk APK |
| `src/compress.ts`, `src/wasm/` | gzip and xz in WebAssembly, from the same Rust crates the CLI's `magiskboot` uses, so the bytes match |
| `src/vbmeta.ts`, `src/bootlogo.ts` | vbmeta flags and the boot logo |

The files a frame adds to the ramdisk are its own, passed in as `overlay`: the S1's are in
[its installer](../../../frames/aqara-s1-plus/installer/src/overlay.ts), with the whole patch's golden test.

## Tests

```sh
npm test -w @glassworks/bootpatch
```

- `test/vectors.test.ts` replays the recorded vectors in `test/vectors/bootpatch.json` on synthetic
  images. Don't edit the vectors to make a change pass.
- `test/dropped-bytes.test.ts` checks that an image with data the repack would drop is refused.
- `test/bootpatch.test.ts` compares the output with the CLI's on the S1's real boot images. These
  run only where the goldens are (`S1_WEB_GOLDEN`, default `~/src/aqara-panel-backups/web-golden`),
  because they hold Aqara's firmware and never go in the repo.

To patch an image on the host, for example to compare two versions of this package on the same input:
`node tools/kiln/patch-boot.mjs <boot.img> <out.img> --apk <Magisk-v31.1.apk>` (see its header).

## Rebuilding the wasm

`src/wasm/build-wasm.sh` builds `bootpatch.wasm` in Docker with a pinned Rust image, and
`CHECK=1 src/wasm/build-wasm.sh` checks that the committed file is reproducible. The crates and their
licences are in `src/wasm/THIRD_PARTY.txt`.
