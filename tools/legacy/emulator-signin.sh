#!/usr/bin/env bash
# emulator-signin.sh: signs the app on an emulator in to a test Home Assistant, with no phone and no typing.
#
# The QR code's own sign-in, driven from this computer: it forwards the panel's sign-in server (port 8099), asks it
# for the authorize URL, logs in through HA's login-flow API with that URL's client_id, redirect_uri and S256
# code_challenge (HA 2026.11 refuses the code exchange without the challenge), and hands the code back to the
# panel's /callback. The panel then exchanges it and stores its refresh token, as after a phone sign-in.
#
# What it touches: the emulator's app (it signs it in), through the adb command you give it, and one adb forward
# (tcp:18099, or FWD_PORT) that it creates and removes at the end; it refuses to start if that port is taken.
# HA's login flow runs against HA_LOCAL, the address this computer reaches HA at; the app must already know HA by the
# address the emulator uses (`--es url http://10.0.2.2:8123`). Use it only against a throwaway HA: the password file
# is read in place and never echoed.
#
#   ADB="adb -P 5091 -s 127.0.0.1:5621" HA_LOCAL=http://127.0.0.1:8123 HA_USER=test \
#     tools/legacy/emulator-signin.sh <file holding the password>
#
# Every adb call on this Mac needs ADB_USB=0 ADB_MDNS=0 and a server port of its own (docs/research/legacy-engine.md).
set -euo pipefail

pass_file=${1:?usage: emulator-signin.sh <password file>}
: "${ADB:?set ADB to the adb command for the emulator}"
ha=${HA_LOCAL:-http://127.0.0.1:8123}
user=${HA_USER:-test}
fwd=${FWD_PORT:-18099}

# --no-rebind: a forward someone else holds on this port stays theirs (adb refuses, and so do we). The cleanup trap
# is set only once the forward is ours, so it never removes another session's.
if ! $ADB forward --no-rebind tcp:"$fwd" tcp:8099 >/dev/null; then
  echo "tcp:$fwd is already forwarded or in use on this computer; set FWD_PORT to a free port" >&2
  exit 1
fi
trap '$ADB forward --remove tcp:$fwd >/dev/null 2>&1 || true' EXIT

loc=$(curl -fsS -o /dev/null -w '%{redirect_url}' -H 'Host: 127.0.0.1:8099' "http://127.0.0.1:$fwd/signin/continue")
[ -n "$loc" ] || { echo "the panel gave no authorize URL (does it know HA's address yet?)" >&2; exit 1; }
q() { python3 -c 'import sys, urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)[sys.argv[2]][0])' "$loc" "$1"; }
json() { python3 -c 'import sys, json; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

client_id=$(q client_id) redirect=$(q redirect_uri) state=$(q state) challenge=$(q code_challenge)
flow=$(python3 -c 'import json, sys; print(json.dumps({"client_id": sys.argv[1], "handler": ["homeassistant", None],
  "redirect_uri": sys.argv[2], "code_challenge": sys.argv[3], "code_challenge_method": "S256"}))' \
  "$client_id" "$redirect" "$challenge" |
  curl -fsS -X POST "$ha/auth/login_flow" -H 'Content-Type: application/json' -d @- | json flow_id)
code=$(python3 -c 'import json, sys; print(json.dumps({"client_id": sys.argv[1], "username": sys.argv[2],
  "password": open(sys.argv[3]).read().strip()}))' "$client_id" "$user" "$pass_file" |
  curl -fsS -X POST "$ha/auth/login_flow/$flow" -H 'Content-Type: application/json' -d @- | json result)

status=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: 127.0.0.1:8099' \
  "http://127.0.0.1:$fwd/callback?code=$code&state=$state")
echo "panel callback: HTTP $status (200 = signed in; the app logs \"Signed in to\" under Glassworks.Auth)"
[ "$status" = 200 ]
