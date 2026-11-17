#!/usr/bin/env bash
# Which hosts the app fetches from chain to a root that old Android lacks (#456, MIT). Read-only: it fetches each
# host's chain (openssl s_client) and AOSP's CA store at a few tags (shallow, sparse git clones), then checks each
# chain against each store with `openssl verify`. Writes only to a temporary directory, which it deletes.
#
#   tools/legacy/ca-roots.sh                 # the default hosts, against Android 6.0.1, 7.0 and 7.1
#   tools/legacy/ca-roots.sh --with FILE.pem # the same, with FILE's roots added to every store (the bundled ones)
#   HOSTS="example.com" tools/legacy/ca-roots.sh
#
# Android 6.0.1 kept its store in libcore (luni/src/main/files/cacerts); from 7.0 it is system/ca-certificates (files).
# The research note is docs/research/android-ca-roots.md.
set -euo pipefail

HOSTS=${HOSTS:-"github.com api.github.com release-assets.githubusercontent.com raw.githubusercontent.com objects.githubusercontent.com"}
EXTRA=""
[ "${1:-}" = "--with" ] && EXTRA=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

# name  repo  tag  directory-in-repo
STORES="6.0.1(API23) libcore android-6.0.1_r81 luni/src/main/files/cacerts
7.0(API24) system/ca-certificates android-7.0.0_r1 files
7.1(API25) system/ca-certificates android-7.1.0_r1 files"

echo "$STORES" | while read -r name repo tag dir; do
    git clone -q --depth 1 --branch "$tag" --filter=blob:none --sparse "https://android.googlesource.com/platform/$repo" "src-$name" 2>/dev/null
    git -C "src-$name" sparse-checkout set "$dir"
    for f in "src-$name/$dir"/*; do openssl x509 -in "$f" 2>/dev/null; done > "$name.pem"
    [ -n "$EXTRA" ] && cat "$EXTRA" >> "$name.pem"
    echo "store $name: $(grep -c BEGIN "$name.pem") roots, $repo $tag $(git -C "src-$name" rev-parse --short HEAD)" >&2
done

printf '| Host | Root it chains to |'; echo "$STORES" | while read -r name _; do printf ' %s |' "$name"; done; echo
for h in $HOSTS; do
    echo | openssl s_client -connect "$h:443" -servername "$h" -showcerts 2>/dev/null |
        awk -v d="$h" '/BEGIN CERT/{n++} n{print > (d "." n ".pem")} /END CERT/{n=n}'
    [ -f "$h.1.pem" ] || { echo "| $h | (no answer) |"; continue; }
    cat "$h".[2-9].pem > "$h.chain.pem" 2>/dev/null || :
    last=$(ls "$h".[0-9].pem | sort -t. -k2 -n | tail -1)
    top=$(openssl x509 -in "$last" -noout -issuer | sed 's/.*CN *= *//')
    printf '| %s | %s |' "$h" "$top"
    echo "$STORES" | while read -r name _; do
        if openssl verify -no-CApath -no-CAfile -CAfile "$name.pem" -untrusted "$h.chain.pem" "$h.1.pem" >/dev/null 2>&1
        then printf ' OK |'; else printf ' **fails** |'; fi
    done
    echo
done
