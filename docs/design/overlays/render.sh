#!/usr/bin/env bash
# Renders the #691 overlay mockups (mockups.html) to PNGs with headless Chrome, at the panels' real
# pixel sizes: the S1 at 1440x720 (960x480 dp, density 1.5) and the Mirror at 1080x1920 (540x960 dp,
# density 2). Touches nothing but the output directory. Placeholder data only.
#
#   docs/design/overlays/render.sh <out-dir> [scene-filter]
#
# Needs Google Chrome (CHROME=... to point at another Chromium).
set -euo pipefail
out=${1:?usage: render.sh <out-dir> [filter]}
filter=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
mkdir -p "$out"

shot() { # name query w h scale
  local name=$1 query=$2 w=$3 h=$4 scale=$5
  [[ -n $filter && $name != *$filter* ]] && return 0
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor="$scale" \
    --window-size="$w,$h" --virtual-time-budget=1500 --screenshot="$out/$name.png" \
    "file://$here/mockups.html?$query" >/dev/null 2>&1
  echo "$out/$name.png"
}

frame_size() { case $1 in s1) echo "960 480 1.5" ;; mirror) echo "540 960 2" ;; esac }

# Assist (orb and edge glow) and timers: both frames, both modes, two themes each.
for frame in s1 mirror; do
  read -r w h s <<<"$(frame_size $frame)"
  for combo in "dark glassworks" "light dusk" "dark aurora" "light glassworks"; do
    read -r mode theme <<<"$combo"
    for scene in orb-listening orb-thinking orb-speaking orb-error glow-listening glow-thinking glow-speaking glow-error \
                 timer-dock timer-board timer-done timer-ring; do
      shot "$frame-$scene-$theme-$mode" "scene=$scene&frame=$frame&mode=$mode&theme=$theme" "$w" "$h" "$s"
    done
  done
done

# The theme picker: on the S1's settings, and the matching Manage page (desktop and phone width).
for combo in "dark glassworks" "light dusk" "dark dune" "light ha"; do
  read -r mode theme <<<"$combo"
  shot "s1-picker-$theme-$mode" "scene=picker&frame=s1&mode=$mode&theme=$theme" 960 480 1.5
  shot "manage-desk-$theme-$mode" "scene=manage&frame=desk&mode=$mode&theme=$theme" 1200 1000 1
  shot "manage-phone-$theme-$mode" "scene=manage&frame=phone&mode=$mode&theme=$theme" 390 860 2
done

# The motion preview as GIFs (one run of the edge glow on a loop): frames at 12 fps, then ffmpeg.
# Only with MOTION=1, as it takes a few minutes. Needs ffmpeg.
motion() { # name frame mode theme scene w h scale
  local name=$1 frame=$2 mode=$3 theme=$4 scene=$5 w=$6 h=$7 scale=$8 tmp i=0 t
  [[ -s $out/$name.gif ]] && { echo "$out/$name.gif (kept)"; return 0; }
  tmp=$(mktemp -d)
  for ((t = 0; t < 14200; t += 83)); do
    "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor="$scale" \
      --window-size="$w,$h" --virtual-time-budget=800 --screenshot="$tmp/$(printf %04d $i).png" \
      "file://$here/mockups.html?scene=$scene&frame=$frame&mode=$mode&theme=$theme&t=$t" >/dev/null 2>&1 || true
    [[ -s $tmp/$(printf %04d $i).png ]] || { echo "frame $i failed at t=$t" >&2; exit 1; }
    i=$((i + 1))
  done
  ffmpeg -loglevel error -y -framerate 12 -i "$tmp/%04d.png" \
    -vf "split[a][b];[a]palettegen=max_colors=192:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" "$out/$name.gif"
  ffmpeg -loglevel error -y -framerate 12 -i "$tmp/%04d.png" -pix_fmt yuv420p -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" "$out/$name.mp4"
  rm -rf "$tmp"
  echo "$out/$name.gif"
}
if [[ ${MOTION:-} == 1 ]]; then
  motion s1-glow-motion-glassworks-dark s1 dark glassworks glow-cycle 960 480 0.75
  motion s1-glow-motion-dusk-light s1 light dusk glow-cycle 960 480 0.75
  motion mirror-glow-motion-aurora-dark mirror dark aurora glow-cycle 540 960 0.75
  motion s1-orb-motion-glassworks-dark s1 dark glassworks orb-cycle 960 480 0.75
fi
