#!/usr/bin/env bash
# Checks what `./gradlew :<name>:publishToMavenLocal` put in mavenLocal before it goes to Maven Central
# (pr.yml libs-publish, libs-release.yml): the jar or AAR, the sources and KDoc jars and the POM; the
# licence in the jar and in the POM, and each of the library's extraLicenses (gradle.properties) and its
# NOTICE, in both too; the org, not a person, as developer; no class left under the old sh.edm packages
# (#153: a rename after publishing breaks every user). Reads files only.
#   tools/release/check-lib-artifact.sh <name> [<maven-repo>]    e.g. esphome-api ~/.m2/repository
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
name=${1:?library name, e.g. esphome-api}
m2=${2:-$HOME/.m2/repository}
props="$repo/libs/$name/gradle.properties"
[ -f "$props" ] || { echo "$name: no libs/$name/gradle.properties" >&2; exit 1; }
v=$(sed -n 's/^version=//p' "$props")
d="$m2/dev/glassworks/$name/$v"
base="$d/$name-$v"
fail() { echo "::error::$name $v: $*" >&2; exit 1; }

for f in "$base.pom" "$base-sources.jar" "$base-javadoc.jar"; do [ -f "$f" ] || fail "missing $(basename "$f") in $d"; done
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [ -f "$base.aar" ]; then
  unzip -q -o "$base.aar" classes.jar -d "$tmp" || fail "the AAR has no classes.jar"
  classes="$tmp/classes.jar"
elif [ -f "$base.jar" ]; then
  classes="$base.jar"
else
  fail "no $name-$v.jar or .aar in $d"
fi

# Listings in variables: `unzip -l … | grep -q` fails under pipefail when grep stops early.
has() { grep -qx -- "$2" <<<"$1"; }
classes_list=$(unzip -Z1 "$classes"); sources_list=$(unzip -Z1 "$base-sources.jar"); javadoc_list=$(unzip -Z1 "$base-javadoc.jar")
has "$classes_list" 'META-INF/LICENSE' || fail "no META-INF/LICENSE in the classes jar"
# Ported code's licences: `extraLicenses=<SPDX id>|<copyright>;…` in gradle.properties, and libs/<name>/NOTICE, in
# the library's own folder (META-INF/dev.glassworks/<name>/), so no two libraries' files share a path in an app.
extra=$(sed -n 's/^extraLicenses=//p' "$props")
extra_ids=()
if [ -n "$extra" ]; then
  IFS=';' read -r -a entries <<<"$extra"
  for e in "${entries[@]}"; do
    id=${e%%|*}; copyright=${e#*|}
    extra_ids+=("$id")
    f="META-INF/dev.glassworks/$name/LICENSE-$(tr '[:lower:]' '[:upper:]' <<<"$id")"
    has "$classes_list" "$f" || fail "no $f in the classes jar (extraLicenses names $id)"
    unzip -p "$classes" "$f" | grep -qF -- "$copyright" || fail "$f lacks its copyright line: $copyright"
  done
fi
if [ -f "$repo/libs/$name/NOTICE" ]; then
  has "$classes_list" "META-INF/dev.glassworks/$name/NOTICE" || fail "no META-INF/dev.glassworks/$name/NOTICE in the classes jar (libs/$name/NOTICE exists)"
elif [ ${#extra_ids[@]} -eq 0 ] && grep -q '^META-INF/dev\.glassworks/' <<<"$classes_list"; then
  fail "META-INF/dev.glassworks/ in the classes jar, with no extraLicenses or NOTICE to put there"
fi
has "$javadoc_list" 'index.html' || fail "the KDoc jar has no index.html"
grep -q '\.kt$' <<<"$sources_list" || fail "the sources jar has no Kotlin sources"
if grep -q '^sh/edm/.*[^/]$' <<<"$classes_list"$'\n'"$sources_list"; then
  fail "classes or sources still under sh/edm/: rename the packages to dev.glassworks first"
fi

pom=$(cat "$base.pom")
for want in '<groupId>dev.glassworks</groupId>' "<artifactId>$name</artifactId>" "<version>$v</version>" \
    '<description>' '<name>MIT</name>' '<url>https://github.com/GlassworksHQ</url>' '<connection>scm:git:'; do
  grep -qF "$want" <<<"$pom" || fail "the POM lacks $want"
done
for id in "${extra_ids[@]+"${extra_ids[@]}"}"; do
  grep -qF "<name>$id</name>" <<<"$pom" || fail "the POM's licenses lack $id (extraLicenses)"
done
if grep -q '<email>' <<<"$pom"; then fail "the POM names an email: developers are the org only"; fi
echo "$name $v: jar, sources, KDoc, POM and licence look right"
