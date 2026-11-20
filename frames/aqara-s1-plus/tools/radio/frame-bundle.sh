#!/usr/bin/env bash
# Builds and signs the S1's frame bundle (frames/aqara-s1-plus/research/frame-loader.md, "The bundle"), the
# way the frame release does it (.github/workflows/frame-release.yml), and the shell tests too:
#
#   frames/aqara-s1-plus/tools/radio/frame-bundle.sh <frame version> <key.pem> <out dir> <radio dir> <sisdk dir> [pubkey]
#
#   radio dir  otbr-agent and ot-ctl (the release: the pinned builds of kiln/assets.lock.json), and
#              hotspot-name.apk (hotspot-name/build.sh: the setup hotspot's name, #197; the frame agent removes
#              its Magisk module when the bundle has none)
#   sisdk dir  the Silicon Labs Zigbee runtime (build-sisdk-runtime.sh), never zigbeed itself
#   pubkey     check the signature with it afterwards (the release: frames/aqara-s1-plus/tools/root/glassworks_bundle.pub)
#   SOURCE_DATE_EPOCH  (environment) the time written as the manifest's "created"; else "unknown"
#
# Writes <out dir>/frame-bundle/ (the signed dir) and <out dir>/frame-bundle-aqara-s1-plus-<version>.zip
# (what the release publishes and the app downloads: the same files, deterministic for the same inputs; build.sh
# signs hotspot-name.apk with a key made for each build, so that input differs between builds). Touches no device.
set -euo pipefail
V=${1:?frame version}; KEY=${2:?key.pem}; OUT=${3:?out dir}; RADIO_IN=${4:?radio dir}; SISDK=${5:?sisdk dir}; PUB=${6:-}
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../root"
REPO="$(cd "$HERE/../../../.." && pwd)"
# The Silicon Labs runtime: zigbeed's launcher and the libraries it can't start without. CI also checks the
# whole tree against sisdk-runtime.sha256; this catches a wrong or empty dir in a build by hand.
for f in zigbeed.sh lib/ld-linux-armhf.so.3 lib/libc.so.6 lib/libcpc.so.3; do
  { [ -f "$SISDK/$f" ] && [ ! -L "$SISDK/$f" ] && [ -s "$SISDK/$f" ]; } ||
    { echo "frame-bundle: $SISDK/$f is missing (or not a regular file): not a Silicon Labs runtime (build-sisdk-runtime.sh)" >&2; exit 1; }
done
for f in zigbeed.sh lib/ld-linux-armhf.so.3; do   # run, not read: the mode the manifest records is the panel's
  [ -x "$SISDK/$f" ] || { echo "frame-bundle: $SISDK/$f is not executable (chmod 0755; the release sets it)" >&2; exit 1; }
done
[ -f "$RADIO_IN/hotspot-name.apk" ] && [ ! -L "$RADIO_IN/hotspot-name.apk" ] && [ -s "$RADIO_IN/hotspot-name.apk" ] ||
  { echo "frame-bundle: $RADIO_IN/hotspot-name.apk is missing (hotspot-name/build.sh <radio dir>): without it the agent gives the hotspot Android's name back" >&2; exit 1; }
mkdir -p "$OUT"
# Signed and zipped in a dir of its own, so builds sharing <out dir> can't mix their files.
W=$(mktemp -d "$OUT/.frame-bundle.XXXXXX"); trap 'rm -rf "$W"' EXIT
# The manifest's "created": SOURCE_DATE_EPOCH (the release passes the tagged commit's time), so the same
# inputs give the same zip, byte for byte. Without it, "unknown".
CREATED=$(python3 -c 'import datetime, os; e = os.environ.get("SOURCE_DATE_EPOCH"); print(datetime.datetime.fromtimestamp(int(e), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") if e else "unknown")')
python3 "$HERE/make-bundle.py" --frame aqara-s1-plus --frame-version "$V" --key "$KEY" --out "$W" ${PUB:+--pubkey "$PUB"} --created "$CREATED" \
  --file "services/agent.sh=$HERE/glassworks_agent.sh:0700" \
  --file "services/netwatch.sh=$ROOT/glassworks_netwatch.sh:0700" \
  --file "services/firstboot.sh=$ROOT/glassworks_firstboot.sh:0700" \
  --file "services/update.sh=$ROOT/glassworks_update.sh:0700" \
  --file "otbr-agent=$RADIO_IN/otbr-agent:0700" \
  --file "ot-ctl=$RADIO_IN/ot-ctl:0700" \
  --file "hotspot-name.apk=$RADIO_IN/hotspot-name.apk:0644" \
  --dir "sisdk=$SISDK" \
  --file "bootanimation.zip=$REPO/branding/glass-tile/bootanimation.zip"
ZIP=frame-bundle-aqara-s1-plus-$V.zip
python3 - "$W/frame-bundle" "$W/$ZIP" <<'PY2'
import hashlib, os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
def fail(e): raise e
names = sorted(os.path.relpath(os.path.join(r, f), src) for r, _, fs in os.walk(src, onerror=fail) for f in fs)
signed = {}
for l in open(os.path.join(src, "manifest")).read().splitlines():
    if l.startswith("file "):
        _, _, size, digest, path = l.split(" ")
        signed[path] = (int(size), digest)
if set(names) != set(signed) | {"manifest", "manifest.sig"}:
    sys.exit(f"frame-bundle: the files on disk are not the signed list: {sorted(set(names) ^ (set(signed) | {'manifest', 'manifest.sig'}))}")
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for n in names:
        info = zipfile.ZipInfo(n, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED if n.endswith(".zip") else zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(src, n), "rb") as f:
            data = f.read()
        # The bytes going into the zip are the ones the manifest signed, or nothing is written.
        if n in signed and (len(data), hashlib.sha256(data).hexdigest()) != signed[n]:
            sys.exit(f"frame-bundle: {n} changed after it was signed")
        z.writestr(info, data)
if sorted(zipfile.ZipFile(dst).namelist()) != names:
    sys.exit("frame-bundle: the zip doesn't hold the signed files")
print(f"{dst}: {len(names)} files, {os.path.getsize(dst)} bytes")
PY2
rm -rf "${OUT:?}/frame-bundle"; mv "$W/frame-bundle" "$OUT/frame-bundle"; mv -f "$W/$ZIP" "$OUT/$ZIP"
echo "$OUT/$ZIP"
