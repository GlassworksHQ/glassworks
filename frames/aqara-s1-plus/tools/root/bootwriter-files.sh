#!/usr/bin/env bash
# Stages what the boot writer (glassworks_bootwriter.sh, #139 route 2) needs in the radio bundle's bootwriter/ dir:
# the writer, the FEL install's patch (patch-ramdisk.sh) and the loader image's own files. release.yml, the size
# job (tools/size/radio-bundle.sh) and the shell tests all run this, so the three bundle the same files.
#
#   frames/aqara-s1-plus/tools/root/bootwriter-files.sh <out-dir>     # -> <out-dir>/ (made empty first)
#
# Host only: copies files from this checkout, touches no device. otbr-agent and ot-ctl come from the bundle's top
# level, the verifier from the panel's running boot image, the Magisk binaries from the official APK on the panel.
set -euo pipefail
OUT=${1:?usage: bootwriter-files.sh <out-dir>}
HERE="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$HERE/glassworks_bootwriter.sh" "$HERE/patch-ramdisk.sh" "$HERE/glassworks.rc" "$HERE/glassworks_loader.sh" \
  "$HERE/glassworks_bundle.pub" "$HERE/glassworks_firstboot.sh" "$HERE/glassworks_netwatch.sh" \
  "$HERE/glassworks_update.sh" "$HERE/../radio/glassworks_agent.sh" "$OUT/"
chmod 0644 "$OUT"/*
ls "$OUT"
