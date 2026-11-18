#!/system/bin/sh
# glassworks_loader: the S1 frame's loader (frames/aqara-s1-plus/research/frame-loader.md). It ships in
# the boot image (overlay.d/sbin, ${MAGISKTMP}) with glassworks_verify (Ed25519) and
# glassworks_bundle.pub (the project's public key), and is the part of the boot image meant never to
# change. Everything else the frame runs comes from a signed frame bundle in $BDIR/current, or from the
# boot image's own copies (glassworks_<slot>.sh next to this script) when there is none or it is bad.
#
# Modes. init passes them from glassworks.rc, and only root can run this script:
#   boot          post-fs-data, once per boot (an `exec`, so init waits). Promotes a staged bundle
#                 ($BDIR/next), checks $BDIR/current in full, writes the result to $RUN/active (tmpfs,
#                 root only, gone at the next boot), sets ro.glassworks.frame.id and
#                 ro.glassworks.frame.version, and bind-mounts the boot animation.
#   run <slot>    a service slot (glassworks.rc): runs services/<slot>.sh from the checked bundle, or the
#                 boot image's glassworks_<slot>.sh. A long-running slot's bundle script that exits within
#                 a minute 3 times in a row is marked bad ($BDIR/bad-<slot>) and the boot copy takes over.
#   install       the glassworks_bundle service: stages the frame bundle the app put in its private dir
#                 (files/frame/bundle-in) as $BDIR/next. It loads at the next restart.
#   check <dir>   prints "<frame version> <version>" for a valid bundle dir, or why not (exit 1).
# The scripts get GW_BOOT (this dir), GW_BUNDLE (the checked bundle dir, or empty), GW_SOURCE
# ("boot", "boot (<why>)", "bundle <frame version>", "dev") and GW_DEV (1 for a developer override).
#
# Frame bundle (make-bundle.py writes it; one item per line, single spaces, nothing else):
#   GLASSWORKS-BUNDLE 1
#   frame <id> <M.m.p>                   must be this frame's id; each part 0..999, no leading zeros
#   version <n>                          = M*1000000 + m*1000 + p; must not go down (anti-rollback)
#   created <text>                       informational
#   file <mode> <size> <sha256> <path>   mode 0600|0644|0700|0755; path: up to 5 components of
#                                        [A-Za-z0-9._+-], none starting with "." ("manifest*" reserved)
#   firmware <id> <path> <app> <label>   a radio firmware image among the files (glassworks_radio.sh)
#   end
# plus manifest.sig: the Ed25519 signature of manifest, 128 hex digits. Known paths: services/<slot>.sh,
# bootanimation.zip, and the radio's files (otbr-agent, ot-ctl, sisdk/, firmware images).
#
# Install (app side: stage files/frame/bundle-in/, setprop sys.glassworks.bundle.status requested,
# setprop ctl.start glassworks_bundle). Status in sys.glassworks.bundle.status =
# "<running|ok|error>: <detail>". App-readable state: $PUB_DIR/frame-bundle (root:root, dir 0755, file
# 0644) = "<ok|bad|none> <frame version|-> <floor>", written at boot and after an install. The app reads
# that file, never a sys.* property (any app can set those).
#
# Threat model: SELinux is permissive here, so any app may be able to start these services and set
# properties. So the install takes no input but the kiosk's one staging dir, and:
#   1. finds the kiosk's uid (PackageManager, and the owner of its data dir; they must agree);
#   2. copies manifest + signature into a fresh root-only dir and checks the signature against the key
#      in the boot image before reading anything else;
#   3. refuses another frame's bundle, and a version below the highest one ever loaded ($BDIR/version);
#   4. copies only the files the signed manifest names with `glassworks_verify --copy` (openat with
#      O_NOFOLLOW all the way, kiosk-owned, regular, one link, the listed size), then checks each copy's
#      SHA-256.
# At worst another app can make it stage a bundle we signed for this frame, of the same or a newer
# version, or spoof the status property. Running its own root code needs the private key.
FRAME_ID=aqara-s1-plus
BOOT_FRAME_VERSION=1.0.0
PKG=sh.edm.s1kiosk
HERE=${0%/*}
DATA=${GW_DATA:-/data/data/$PKG}
ROOT=${GW_ROOT:-/data/glassworks}
BDIR=$ROOT/bundle
RUN=${GW_RUN:-/dev/glassworks}
PUB_DIR=${GW_PUB:-/data/glassworks-pub}
PUBKEY=${GW_PUBKEY:-$HERE/glassworks_bundle.pub}
VERIFY=${GW_VERIFY:-$HERE/glassworks_verify}
ANIM=${GW_ANIM:-/system/media/bootanimation.zip}
MAX_FILES=200
MAX_TOTAL=$((96 * 1024 * 1024))
MAX_MANIFEST=65536

say() { log -t glassworks_loader "$*" 2>/dev/null; }
setp() { # name value: setprop refuses values over 91 bytes (PROP_VALUE_MAX) and keeps the old one
  v_=$2; [ ${#v_} -gt 91 ] && v_="$(printf '%.88s' "$v_")..."
  setprop "$1" "$v_"
}
num() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ ${#1} -le 9 ]; }
hex64() { case "$1" in *[!0-9a-f]*) return 1 ;; esac; [ ${#1} = 64 ]; }
sha256() { set -- $(sha256sum "$1" 2>/dev/null); echo "$1"; }
size() { stat -c %s "$1" 2>/dev/null; }
# Ours: exists, not a symlink, owned by root.
root_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1" 2>/dev/null)" = 0 ]; }
root_file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1" 2>/dev/null)" = 0 ]; }
uptime_s() { read -r u_ x_ < /proc/uptime; echo "${u_%%.*}"; }   # monotonic, unlike date
# A zip that the boot animation player can open: the local file header magic and a desc.txt entry.
anim_ok() { [ -s "$1" ] && [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = 504b0304 ] && grep -aq desc.txt "$1"; }

# semver <M.m.p>: prints M*1000000 + m*1000 + p; fails on anything else.
semver() {
  case "$1" in *[!0-9.]*|.*|*.|*..*) return 1 ;; esac
  o_=$IFS; IFS=.; set -- $1; IFS=$o_
  [ $# = 3 ] || return 1
  for x_ in "$@"; do
    case "$x_" in 0?*) return 1 ;; esac
    [ ${#x_} -le 3 ] || return 1
  done
  echo $(( $1 * 1000000 + $2 * 1000 + $3 ))
}

# Bundle-relative path rules (also enforced by make-bundle.py).
good_path() {
  case "$1" in
    ""|/*|*/|*//*|.*|*/.*|*[!A-Za-z0-9._+/-]*|manifest|manifest.sig) return 1 ;;
  esac
  [ ${#1} -le 160 ] || return 1
  s_=$(echo "$1" | tr -cd / | wc -c); [ "$s_" -le 4 ]
}

# --- taking files from the kiosk (install only) -------------------------------------------------
UID_=""
take() { # <path under files/frame/> <N|max:N> <dest>: the only way this script reads the kiosk's files
  rm -f "$3"
  out_=$("$VERIFY" --copy "$DATA" "files/frame/$1" "$UID_" "$2" "$3" 2>&1 >/dev/null); r_=$?
  case $r_ in
    0) return 0 ;;
    3) WHY="${1#bundle-in/} does not exist" ;;
    4) out_=${out_#*_verify: }; WHY="${1#bundle-in/} refused: ${out_%%: *}" ;;
    *) WHY="cannot copy ${1#bundle-in/}" ;;
  esac
  rm -f "$3"; return 1
}

# --- the manifest -------------------------------------------------------------------------------
# walk <manifest> <check|copy> <dir>: check = every listed file in <dir> matches; copy = copy every
# listed file from the kiosk's staging dir into <dir> and check the copy. Sets FVER, VERSION, NFILES,
# TOTAL; WHY on error.
walk() {
  m_=$1; act_=$2; d_=$3
  FVER=""; VERSION=""; NFILES=0; TOTAL=0; seen_=" "; n_=0; ended=""; FW_PATHS=""
  set -f
  while IFS= read -r l_ || [ -n "$l_" ]; do
    n_=$((n_ + 1))
    [ -n "$ended" ] && { WHY="manifest: text after end"; set +f; return 1; }
    # shellcheck disable=SC2086
    set -- $l_
    if [ $n_ = 1 ]; then
      [ "$l_" = "GLASSWORKS-BUNDLE 1" ] || { WHY="not a frame bundle"; set +f; return 1; }
      continue
    fi
    case "$1" in
      frame)
        [ $# = 3 ] && [ -z "$FVER" ] || { WHY="manifest: bad frame line"; set +f; return 1; }
        [ "$2" = "$FRAME_ID" ] || { WHY="a bundle for $2, not $FRAME_ID"; set +f; return 1; }
        semver "$3" >/dev/null || { WHY="manifest: bad frame version"; set +f; return 1; }
        FVER=$3 ;;
      version)
        [ $# = 2 ] && num "$2" && [ "$2" -gt 0 ] && [ -z "$VERSION" ] || { WHY="manifest: bad version line"; set +f; return 1; }
        VERSION=$2 ;;
      created) ;;
      file)
        [ $# = 5 ] || { WHY="manifest line $n_: bad file line"; set +f; return 1; }
        mo_=$2; sz_=$3; h_=$4; p_=$5
        case "$mo_" in 0600|0644|0700|0755) ;; *) WHY="manifest line $n_: bad mode"; set +f; return 1 ;; esac
        num "$sz_" && hex64 "$h_" && good_path "$p_" || { WHY="manifest line $n_: bad size, hash or path"; set +f; return 1; }
        case "$seen_" in *" $p_ "*) WHY="manifest: $p_ listed twice"; set +f; return 1 ;; esac
        seen_="$seen_$p_ "
        NFILES=$((NFILES + 1)); TOTAL=$((TOTAL + sz_))
        [ $NFILES -le $MAX_FILES ] && [ $TOTAL -le $MAX_TOTAL ] || { WHY="manifest: too many files or too big"; set +f; return 1; }
        if [ "$act_" = copy ]; then
          case "$p_" in */*) mkdir -p "$d_/${p_%/*}" || { WHY="cannot create ${p_%/*}"; set +f; return 1; } ;; esac
          take "bundle-in/$p_" "$sz_" "$d_/$p_" || { set +f; return 1; }
          chmod "$mo_" "$d_/$p_" || { WHY="cannot chmod $p_"; set +f; return 1; }
        else
          { [ -f "$d_/$p_" ] && [ ! -L "$d_/$p_" ]; } || { WHY="$p_ missing"; set +f; return 1; }
        fi
        [ "$(size "$d_/$p_")" = "$sz_" ] && [ "$(sha256 "$d_/$p_")" = "$h_" ] || { WHY="$p_ does not match the manifest"; set +f; return 1; } ;;
      firmware)
        [ $# -ge 5 ] || { WHY="manifest line $n_: bad firmware line"; set +f; return 1; }
        case "$2" in stock|*[!a-z0-9-]*) WHY="manifest line $n_: bad firmware id"; set +f; return 1 ;; esac
        [ ${#2} -le 16 ] || { WHY="manifest line $n_: firmware id too long"; set +f; return 1; }
        case "$4" in *[!A-Za-z0-9._-]*|"") WHY="manifest line $n_: bad firmware version"; set +f; return 1 ;; esac
        FW_PATHS="$FW_PATHS $3" ;;
      end) [ $# = 1 ] && ended=1 || { WHY="manifest: bad end line"; set +f; return 1; } ;;
      *) WHY="manifest line $n_: unknown item '$1'"; set +f; return 1 ;;
    esac
  done < "$m_"
  set +f
  [ -n "$ended" ] || { WHY="manifest: no end line (truncated?)"; return 1; }
  [ -n "$FVER" ] || { WHY="manifest: no frame line"; return 1; }
  [ -n "$VERSION" ] || { WHY="manifest: no version"; return 1; }
  [ "$(semver "$FVER")" = "$VERSION" ] || { WHY="manifest: version $VERSION is not frame version $FVER"; return 1; }
  for p_ in $FW_PATHS; do
    case "$seen_" in *" $p_ "*) ;; *) WHY="manifest: firmware $p_ is not one of the files"; return 1 ;; esac
  done
  return 0
}

# The version floor: the highest version ever loaded ($BDIR/version), and at least the current one.
floor() {
  f_=$(cat "$BDIR/version" 2>/dev/null); num "$f_" || f_=0
  c_=$(sed -n 's/^version \([0-9][0-9]*\)$/\1/p' "$BDIR/current/manifest" 2>/dev/null | head -1)
  num "$c_" && [ "$c_" -gt "$f_" ] && f_=$c_
  echo "$f_"
}

sig_ok() { # dir: manifest + manifest.sig are regular files and the signature checks out
  for f_ in manifest manifest.sig; do
    { [ -f "$1/$f_" ] && [ ! -L "$1/$f_" ]; } || { WHY="$f_ missing"; return 1; }
  done
  [ "$(size "$1/manifest")" -le $MAX_MANIFEST ] || { WHY="manifest too big"; return 1; }
  { [ -x "$VERIFY" ] && [ -s "$PUBKEY" ]; } || { WHY="no verifier or public key in the boot image"; return 1; }
  "$VERIFY" "$PUBKEY" "$1/manifest" "$1/manifest.sig" 2>/dev/null || { WHY="bad signature (not signed with the project's key)"; return 1; }
}

check_dir() { # dir: a complete, signed bundle for this frame at or above the floor
  root_dir "$1" || { WHY="not a root-owned directory"; return 1; }
  sig_ok "$1" || return 1
  walk "$1/manifest" check "$1" || return 1
  [ "$VERSION" -ge "$(floor)" ] || { WHY="version $FVER is below the floor $(floor)"; return 1; }
}

publish() { # state frame-version: the app-readable state file
  if [ -L "$PUB_DIR" ] || { [ -e "$PUB_DIR" ] && ! root_dir "$PUB_DIR"; }; then rm -rf "$PUB_DIR"; fi
  mkdir -p "$PUB_DIR" && chown root:root "$PUB_DIR" && chmod 755 "$PUB_DIR" || return 1
  rm -f "$PUB_DIR/.frame-bundle.tmp"
  echo "$1 $2 $(floor)" > "$PUB_DIR/.frame-bundle.tmp" && chmod 644 "$PUB_DIR/.frame-bundle.tmp" &&
    mv -f "$PUB_DIR/.frame-bundle.tmp" "$PUB_DIR/frame-bundle"
}

own_dirs() { # the root-only state dirs, created on first use
  for d_ in "$ROOT" "$BDIR"; do
    [ -L "$d_" ] && rm -f "$d_"
    [ -d "$d_" ] || mkdir -p "$d_" || return 1
    chown root:root "$d_" && chmod 700 "$d_" || return 1
  done
}

case "$1" in
# --- check <dir> --------------------------------------------------------------------------------
check)
  check_dir "$2" || { echo "$WHY"; exit 1; }
  echo "$FVER $VERSION"
  exit 0 ;;

# --- boot ---------------------------------------------------------------------------------------
boot)
  rm -rf "$RUN"; mkdir -p "$RUN" && chmod 700 "$RUN"
  own_dirs || say "cannot prepare $BDIR"
  # A bundle staged by `install` loads now. The floor moves up only once it has passed the full check.
  state=none; fv=$BOOT_FRAME_VERSION; why=""
  # An install stopped between moving the staged bundle aside and moving the new one in: the old one
  # still counts (it was reported staged), with the same checks as any.
  if [ -e "$BDIR/next" ]; then rm -rf "$BDIR/next.old"
  elif [ -e "$BDIR/next.old" ]; then mv "$BDIR/next.old" "$BDIR/next"; fi
  if [ -e "$BDIR/next" ]; then
    if check_dir "$BDIR/next"; then
      nv=$VERSION; fl=$(floor); [ "$nv" -gt "$fl" ] && fl=$nv
      rm -f "$BDIR/version.tmp"
      if echo "$fl" > "$BDIR/version.tmp" && chmod 600 "$BDIR/version.tmp" && mv -f "$BDIR/version.tmp" "$BDIR/version"; then
        rm -rf "$BDIR/old"
        [ -e "$BDIR/current" ] && mv "$BDIR/current" "$BDIR/old"
        if mv "$BDIR/next" "$BDIR/current"; then
          rm -rf "$BDIR/old"; rm -f "$BDIR"/bad-*; say "frame bundle $FVER loaded"
          state=ok; fv=$FVER
        else
          [ -e "$BDIR/old" ] && mv "$BDIR/old" "$BDIR/current"; say "cannot move the staged bundle in"
        fi
      fi
    else
      say "staged bundle dropped: $WHY"; rm -rf "$BDIR/next"
    fi
  fi
  if [ $state = ok ]; then :     # just promoted: checked in full above
  elif [ -e "$BDIR/current" ]; then
    if check_dir "$BDIR/current"; then
      state=ok; fv=$FVER
    else
      state=bad; why=$WHY; say "frame bundle rejected: $WHY; running the boot image's copies"
    fi
  fi
  if [ $state = ok ] && ! { echo "$BDIR/current $fv" > "$RUN/active" && chmod 600 "$RUN/active"; }; then
    rm -f "$RUN/active"; state=bad; why="cannot record the active bundle"; fv=$BOOT_FRAME_VERSION
    say "$why; running the boot image's copies"
  fi
  setprop ro.glassworks.frame.id "$FRAME_ID"
  setprop ro.glassworks.frame.version "$fv"
  if [ $state = ok ]; then setp sys.glassworks.bundle.state "ok $fv"; publish ok "$fv"
  elif [ $state = bad ]; then setp sys.glassworks.bundle.state "bad: $why"; publish bad -
  else setp sys.glassworks.bundle.state none; publish none -; fi
  # The boot animation: the bundle's, else the boot image's. A bind mount over the stock file, so
  # nothing is written to /system and the stock boot image brings the stock animation back.
  z=""
  if [ $state = ok ] && [ -f "$BDIR/current/bootanimation.zip" ]; then
    if anim_ok "$BDIR/current/bootanimation.zip"; then z=$BDIR/current/bootanimation.zip
    else say "the bundle's boot animation is not a usable zip; using the boot image's"; fi
  fi
  [ -z "$z" ] && [ -f "$HERE/glassworks_bootanimation.zip" ] && z=$HERE/glassworks_bootanimation.zip
  if [ -n "$z" ] && [ -f "$ANIM" ]; then
    mount -o bind "$z" "$ANIM" || say "cannot mount the boot animation"
  fi
  exit 0 ;;

# --- run <slot> ---------------------------------------------------------------------------------
run)
  slot=$2
  case "$slot" in
    radio|netwatch|svc1|svc2) long=1 ;;
    firstboot|update) long="" ;;
    *) say "unknown slot '$slot'"; exit 2 ;;
  esac
  B=""; FV=""
  if root_dir "$RUN" && root_file "$RUN/active"; then
    # shellcheck disable=SC2046
    set -- $(cat "$RUN/active"); B=${1:-}; FV=${2:-}
  fi
  export GW_BOOT="$HERE"
  if root_dir "$ROOT" && root_file "$ROOT/dev/override" && root_file "$ROOT/dev/$slot.sh"; then
    GW_SOURCE=dev GW_DEV=1 GW_BUNDLE="$B" exec /system/bin/sh "$ROOT/dev/$slot.sh"
  fi
  why=""
  if [ -n "$B" ] && [ -f "$B/services/$slot.sh" ]; then
    if [ -e "$BDIR/bad-$slot" ]; then
      why="bundle $FV: its $slot script kept exiting"
    elif [ -z "$long" ]; then
      GW_SOURCE="bundle $FV" GW_BUNDLE="$B" exec /system/bin/sh "$B/services/$slot.sh"
    else
      quick=0
      while :; do
        t0=$(uptime_s)
        GW_SOURCE="bundle $FV" GW_BUNDLE="$B" /system/bin/sh "$B/services/$slot.sh"
        rc=$?
        [ -n "$GW_LOADER_ONCE" ] && exit $rc   # tests only
        if [ $(( $(uptime_s) - t0 )) -lt 60 ]; then quick=$((quick + 1)); else quick=0; fi
        say "$slot from bundle $FV exited ($rc), quick exits in a row: $quick"
        [ $quick -ge 3 ] && break
        sleep 2
      done
      : > "$BDIR/bad-$slot"
      why="bundle $FV: its $slot script kept exiting"
    fi
    [ -n "$why" ] && { say "$why; running the boot image's copy"; B=""; }
  fi
  [ -f "$HERE/glassworks_$slot.sh" ] || exit 0   # a spare slot this bundle doesn't use
  GW_SOURCE="boot${why:+ ($why)}" GW_BUNDLE="$B" exec /system/bin/sh "$HERE/glassworks_$slot.sh" ;;

# --- install ------------------------------------------------------------------------------------
install)
  STAGE=$BDIR/stage
  status() { st_=$1; setp sys.glassworks.bundle.status "$st_"; say "$1"; }
  fail() { status "error: $*"; rm -rf "$STAGE"; exit 1; }
  status "running: checking the request"
  line=$(pm list packages -U "$PKG" 2>/dev/null | while IFS= read -r l; do
    case "$l" in "package:$PKG "*) echo "$l" ;; esac
  done)
  UID_=$(echo "$line" | tr ' ' '\n' | sed -n 's/^uid://p')
  num "$UID_" && [ "$UID_" -ge 10000 ] || fail "$PKG is not installed"
  [ "$(stat -c %u "$DATA" 2>/dev/null)" = "$UID_" ] || fail "$DATA is not owned by the kiosk (uid $UID_)"
  own_dirs || fail "cannot prepare $BDIR"
  rm -rf "$STAGE"; mkdir "$STAGE" && chmod 700 "$STAGE" || fail "cannot create the staging dir"

  take bundle-in/manifest "max:$MAX_MANIFEST" "$STAGE/manifest" || fail "$WHY"
  take bundle-in/manifest.sig max:256 "$STAGE/manifest.sig" || fail "$WHY"
  sig_ok "$STAGE" || fail "$WHY"
  # Signed by us from here on. The frame and the version first, before copying megabytes.
  [ "$(head -1 "$STAGE/manifest")" = "GLASSWORKS-BUNDLE 1" ] || fail "refused: not a frame bundle"
  fid=$(sed -n 's/^frame \([^ ]*\) [^ ]*$/\1/p' "$STAGE/manifest" | head -1)
  [ "$fid" = "$FRAME_ID" ] || fail "refused: a bundle for ${fid:-no frame}, not $FRAME_ID"
  v=$(sed -n 's/^version \([0-9][0-9]*\)$/\1/p' "$STAGE/manifest" | head -1)
  num "$v" || fail "manifest: no version"
  fl=$(floor)
  [ "$v" -ge "$fl" ] || fail "refused: bundle version $v is below the floor $fl (no downgrades)"
  set -- "$BDIR"/bad-*
  if [ ! -e "$BDIR/next" ] && [ ! -e "$1" ] && root_dir "$BDIR/current" && cmp -s "$STAGE/manifest" "$BDIR/current/manifest" && check_dir "$BDIR/current"; then
    rm -rf "$STAGE"; status "ok: frame bundle $FVER is already loaded"; exit 0
  fi
  status "running: copying the frame bundle"
  walk "$STAGE/manifest" copy "$STAGE" || fail "$WHY"
  [ "$VERSION" = "$v" ] || fail "manifest: version lines disagree"
  # The bundle staged before stays until this one is in place.
  rm -rf "$BDIR/next.old"
  [ -e "$BDIR/next" ] && { mv "$BDIR/next" "$BDIR/next.old" || fail "cannot move the staged bundle aside"; }
  if ! mv "$STAGE" "$BDIR/next"; then
    [ -e "$BDIR/next.old" ] && mv "$BDIR/next.old" "$BDIR/next"
    fail "cannot stage the bundle"
  fi
  rm -rf "$BDIR/next.old"
  status "ok: frame bundle $FVER staged ($NFILES files); it loads at the next restart"
  exit 0 ;;

*)
  echo "usage: $0 boot | run <slot> | install | check <dir>" >&2; exit 2 ;;
esac
