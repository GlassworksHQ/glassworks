#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Glassworks contributors
# Written clean-room from docs/research/kiln-cleanroom/fel-spec.md (#355).
#
# Checks that the words in src/routines.ts are what src/routines.S assembles to. Host only: it
# touches no device and writes only a temporary directory. Needs an LLVM with the ARM target
# (clang, llvm-objdump); set LLVM=/path/to/llvm/bin if they are not on PATH.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
bin=${LLVM:+$LLVM/}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

"${bin}clang" --target=armv7a-none-eabi -march=armv8-a -c "$here/src/routines.S" -o "$tmp/r.o"
# Instruction words only (the parameter slots are filled in at run time).
"${bin}llvm-objdump" -d "$tmp/r.o" | awk '/^ +[0-9a-f]+: [0-9a-f]{8} /{print $2}' > "$tmp/want"
grep -oE '0x[0-9a-f]{8}, //' "$here/src/routines.ts" | cut -c3-10 > "$tmp/got"

if diff -u "$tmp/want" "$tmp/got"; then
  echo "routines.ts matches routines.S ($(wc -l < "$tmp/want" | tr -d ' ') words)"
else
  echo "routines.ts does not match routines.S" >&2; exit 1
fi
