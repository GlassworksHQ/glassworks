#!/usr/bin/env python3
"""GlowBench's table (test-build/glowbench-s1, never merged): reads the JSON and gfxinfo files run.sh wrote to
<dir> and prints each variant's phases against the bar. Reads files only.

    python3 tools/perf/glowbench/report.py <dir>

The bar, per phase, on the panel's refresh rate (vsync = 16.7 ms at 60 Hz):
  p90 within one vsync (+1 ms of jitter): nine frames in ten on time
  p99 within two vsyncs (+1 ms): at most one missed frame in the slowest 1 %
"""
import json
import pathlib
import re
import sys

ORDER = ["enter", "listening", "thinking", "speaking", "exit"]


def main(d: pathlib.Path) -> int:
    files = sorted(d.glob("*.json"))
    if not files:
        print(f"no results in {d}", file=sys.stderr)
        return 1
    bad = 0
    head = f"{'variant':7} {'phase':9} {'frames':>6} {'p50':>6} {'p90':>6} {'p99':>6} {'max':>6} {'>16.7':>6} {'>33':>5} {'draw90':>7}  verdict"
    for f in files:
        r = json.loads(f.read_text())
        vsync = 1000.0 / r["refreshHz"]
        print(f"\n{f.stem}: {r['device']}, Android {r['android']}, {r['refreshHz']:.0f} Hz, {r['loops']} loops")
        print(head)
        rows = sorted(r["phases"], key=lambda x: (x["variant"], ORDER.index(x["phase"]) if x["phase"] in ORDER else 9))
        for x in rows:
            ok = x["p90"] <= vsync + 1 and x["p99"] <= 2 * vsync + 1
            bad += not ok
            print(f"{x['variant']:7} {x['phase']:9} {x['frames']:6d} {x['p50']:6.1f} {x['p90']:6.1f} {x['p99']:6.1f} {x['max']:6.1f} "
                  f"{x['over16_7']:6d} {x['over33']:5d} {x['drawP90']:7.1f}  {'ok' if ok else 'SLOW'}")
        gfx = d / f"{f.stem}-gfxinfo.txt"
        if gfx.exists():
            t = gfx.read_text()
            keep = [l.strip() for l in t.splitlines() if re.match(r"\s*(Total frames rendered|Janky frames|90th|95th|99th)", l)]
            if keep:
                print("gfxinfo (the kiosk's whole window during this variant): " + "; ".join(keep[:5]))
    print(f"\n{'all phases within the bar' if not bad else f'{bad} phase(s) over the bar'}"
          f" (p90 <= 1 vsync + 1 ms, p99 <= 2 vsyncs + 1 ms)")
    return 0 if not bad else 3


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")))
