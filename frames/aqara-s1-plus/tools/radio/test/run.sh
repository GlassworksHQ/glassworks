#!/usr/bin/env bash
# Off-device tests of the root side (the frame loader glassworks_loader.sh, the radio helper v5,
# make-bundle.py, the verifier, the update and netwatch services, and the old radio bundle of 0.4.3 boot
# images) in a 32-bit ARM Linux container with mksh as /system/bin/sh (Android's shell) and
# mocks for init, properties, PackageManager, Aqara's cpcd/socat and the radio (plus real socat and a
# pty pair for the EZSP bridge's drain on connect). Nothing here talks to a panel.
#
#   frames/aqara-s1-plus/tools/radio/test/run.sh            # needs Docker and frames/aqara-s1-plus/tools/root/out/s1kiosk_verify (build-verify.sh)
#   STOCK_GBL=<file> frames/aqara-s1-plus/tools/radio/test/run.sh   # also parse a real stock GBL (never committed)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../../.." && pwd)"
IMAGE=s1kiosk-shell-test:bookworm-armhf-2
[ -x "$REPO/frames/aqara-s1-plus/tools/root/out/s1kiosk_verify" ] || { echo "build frames/aqara-s1-plus/tools/root/out/s1kiosk_verify first (frames/aqara-s1-plus/tools/root/build-verify.sh)" >&2; exit 1; }
docker build --platform linux/arm/v7 -q -t "$IMAGE" - <<'EOF' >/dev/null
FROM arm32v7/debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends mksh python3 openssl procps xxd file socat \
 && rm -rf /var/lib/apt/lists/*
EOF
extra=()
if [ -n "${STOCK_GBL:-}" ]; then extra=(-v "$(cd "$(dirname "$STOCK_GBL")" && pwd)/$(basename "$STOCK_GBL"):/stock.gbl:ro"); fi
docker run --rm --platform linux/arm/v7 -v "$REPO":/repo:ro "${extra[@]}" "$IMAGE" bash /repo/frames/aqara-s1-plus/tools/radio/test/shell-tests.sh
