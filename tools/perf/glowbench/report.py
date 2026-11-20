#!/usr/bin/env python3
"""GlowBench's table (test-build/glowbench-s1, never merged), round 3: reads the JSON and gfxinfo files run.sh wrote
to <dir> and prints each look's phases against the bar. Reads files only.

    python3 tools/perf/glowbench/report.py <dir>

Columns: `frame` is the interval between frames the window drew (FrameMetrics: what the screen shows, RenderThread
animation frames included); `render` is the time to render a frame; `ui` is the UI thread's frame interval
(Choreographer). The bar, per phase, on the drawn frames, at the display's rate (vsync = 16.7 ms at 60 Hz):
  p90 within one vsync (+1 ms of jitter), p99 within two (+1 ms).
"""
import json
import pathlib
import re
import sys

ORDER = ["enter", "listening", "thinking", "speaking", "error", "exit"]


def main(d: pathlib.Path) -> int:
    files = sorted(d.glob("*.json"))
    if not files:
        print(f"no results in {d}", file=sys.stderr)
        return 1
    bad = 0
    head = (f"{'phase':9} {'frames':>6} {'frame50':>7} {'frame90':>7} {'frame99':>7} {'>16.7':>6} {'>33':>5} "
            f"{'render90':>8} {'ui50':>6} {'ui90':>6}  verdict")
    for f in files:
        r = json.loads(f.read_text())
        vsync = 1000.0 / r["refreshHz"]
        runs = "runs" if r.get("mode") == "live" else "loops"
        print(f"\n{f.stem}: look={r.get('look', 'glow')}, {r.get('package', '?')} on {r['device']}, Android {r['android']}, "
              f"{r.get('screen', '?')} at {r['refreshHz']:.0f} Hz, {r['loops']} {runs}, drawn frames from {r.get('frameSource', 'framemetrics')}")
        print(head)
        rows = sorted(r["phases"], key=lambda x: ORDER.index(x["phase"]) if x["phase"] in ORDER else 9)
        for x in rows:
            ok = x["frames"] > 0 and x["p90"] <= vsync + 1 and x["p99"] <= 2 * vsync + 1
            bad += not ok
            print(f"{x['phase']:9} {x['frames']:6d} {x['p50']:7.1f} {x['p90']:7.1f} {x['p99']:7.1f} {x['over16_7']:6d} "
                  f"{x['over33']:5d} {x.get('renderP90', 0):8.1f} {x.get('uiP50', 0):6.1f} {x.get('uiP90', 0):6.1f}  "
                  f"{'ok' if ok else 'SLOW'}")
        gfx = d / f"{f.stem}-gfxinfo.txt"
        if gfx.exists():
            keep = [l.strip() for l in gfx.read_text().splitlines()
                    if re.match(r"\s*(Total frames rendered|Janky frames|90th|95th|99th)", l)]
            if keep:
                print("gfxinfo (the kiosk's whole window): " + "; ".join(keep[:5]))
    print(f"\n{'all phases within the bar' if not bad else f'{bad} phase(s) over the bar'}"
          f" (drawn frames: p90 <= 1 vsync + 1 ms, p99 <= 2 vsyncs + 1 ms)")
    return 0 if not bad else 3


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")))
