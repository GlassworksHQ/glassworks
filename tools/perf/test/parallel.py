#!/usr/bin/env python3
"""Clarity's off-device tests, in parallel (MIT, #268). Touches no device: the same tests as
`python3 -m unittest discover -s tools/perf/test`, split into units that run side by side.

  tools/perf/test/parallel.py [--set fast|slow|all] [-j N] [-v]

- **fast**: the grading, report, export and notes tests (seconds). Run them after a change there.
- **slow**: everything else: the runner end to end against the fake panel (test_runner) and the
  fakes. Run it too when you change perfrun.py, run.sh, lanprobe.py, clock.py or a fake.
- **all** (the default): both.

Each unit is one `python3 -m unittest` process: a test_runner class whose tests share a run
(setUpClass), else one test_runner test, else a whole module. A unit gets its worker's port block
(GW_PERF_TEST_PORT_BASE, see test_runner.port) and a temp directory of its own (TMPDIR, also the
cache: GW_PERF_CACHE), so units never share a port, a path or a fake panel. Which worker runs which
unit varies, what a unit sees doesn't. A failed unit prints its whole output. Exits 1 if any failed.
"""
import argparse
import ast
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
FAST = ("test_grade", "test_report", "test_summary", "test_notes")
# Worker k's ports: PORT_BASE + k * PORT_BLOCK + 76 .. + 100 (test_runner.port). Above the default
# block (18000) and below every OS's ephemeral range (Linux 32768+, macOS 49152+), where the
# tests' own port-0 servers land.
PORT_BASE, PORT_BLOCK = 20000, 200
SUMMARY = re.compile(r"^Ran (\d+) tests? in", re.M)


def modules(which):
    names = sorted(f[:-3] for f in os.listdir(HERE) if f.startswith("test_") and f.endswith(".py"))
    if which == "fast":
        return [m for m in names if m in FAST]
    if which == "slow":
        return [m for m in names if m not in FAST]
    return names


def weight(node, src):
    """How long a test likely takes, from its source: each run.sh run counts, a --pane-ram frame's
    (aqara-s1-plus, many samples) three times; a perfrun-only test counts 1, and five times when it
    runs in real time (GW_PERF_TIME_SCALE="1", the tests that measure the runner's own delay)."""
    text = ast.get_source_segment(src, node) or ""
    runs = sum(1 for n in ast.walk(node) if isinstance(n, ast.Name) and n.id == "RUN")
    real = 5 if 'GW_PERF_TIME_SCALE="1"' in text else 1
    return real + runs * (3 if "aqara-s1-plus" in text else 1)


def units(mods):
    """Unit names, the likely longest first: test_runner's shared-run classes, the other modules,
    then test_runner's tests by weight() (file order among equals). Read from the source (ast), not
    imported: importing test_runner here would put its stub directories in this process's
    environment, which every unit would then share. The order only spreads the load over the
    workers; it decides no result."""
    shared, single, whole = [], [], []
    for m in mods:
        if m != "test_runner":
            whole.append(m)
            continue
        with open(os.path.join(HERE, m + ".py")) as f:
            src = f.read()
        for cls in ast.parse(src).body:
            if not (isinstance(cls, ast.ClassDef) and any(ast.unparse(b).endswith("TestCase") for b in cls.bases)):
                continue
            defs = [d for d in cls.body if isinstance(d, ast.FunctionDef)]
            if "setUpClass" in [d.name for d in defs]:
                shared.append(f"{m}.{cls.name}")
            else:
                single += [(-weight(d, src), len(single), f"{m}.{cls.name}.{d.name}") for d in defs if d.name.startswith("test")]
    return shared + whole + [name for _, _, name in sorted(single)]


def run_unit(name, worker, tmp, verbose):
    d = tempfile.mkdtemp(prefix=re.sub(r"\W", "_", name)[-60:] + "-", dir=tmp)
    env = dict(os.environ, TMPDIR=d, GW_PERF_CACHE=os.path.join(d, "cache"),
               GW_PERF_TEST_PORT_BASE=str(PORT_BASE + worker * PORT_BLOCK))
    t0 = time.monotonic()
    p = subprocess.run([sys.executable, "-m", "unittest"] + (["-v"] if verbose else []) + [name],
                       cwd=HERE, env=env, capture_output=True, text=True)
    m = SUMMARY.search(p.stderr)
    shutil.rmtree(d, ignore_errors=True)
    return {"name": name, "ok": p.returncode == 0, "tests": int(m.group(1)) if m else 0,
            "skipped": sum(int(n) for n in re.findall(r"skipped=(\d+)", p.stderr[-300:])),
            "s": time.monotonic() - t0, "out": p.stderr + p.stdout}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--set", choices=("fast", "slow", "all"), default="all")
    # Twice the cores, at most 8: a unit mostly waits on its fakes, but the clock is compressed,
    # so a starved CPU shows up five times over in what the runner measures.
    jobs = int(os.environ.get("GW_PERF_TEST_JOBS") or min(8, 2 * (os.cpu_count() or 1)))
    ap.add_argument("-j", "--jobs", type=int, default=jobs, help=f"units at once (default {jobs}: GW_PERF_TEST_JOBS, else twice the cores up to 8)")
    ap.add_argument("-v", "--verbose", action="store_true", help="each unit's tests by name")
    a = ap.parse_args()
    todo = queue.Queue()
    for u in units(modules(a.set)):
        todo.put(u)
    total = todo.qsize()
    done, lock = [], threading.Lock()
    tmp = tempfile.mkdtemp(prefix="clarity-tests-")
    t0 = time.monotonic()

    def worker(k):
        while True:
            try:
                name = todo.get_nowait()
            except queue.Empty:
                return
            r = run_unit(name, k, tmp, a.verbose)
            with lock:
                done.append(r)
                print(f"[{len(done)}/{total}] {'ok  ' if r['ok'] else 'FAIL'} {r['s']:6.1f} s  {name}", flush=True)
                if a.verbose or not r["ok"]:
                    print(r["out"].rstrip() + "\n", flush=True)

    threads = [threading.Thread(target=worker, args=(k,)) for k in range(max(1, a.jobs))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    shutil.rmtree(tmp, ignore_errors=True)
    failed = [r["name"] for r in done if not r["ok"]]
    print(f"\n{sum(r['tests'] for r in done)} tests in {len(done)} units, {a.jobs} at a time: "
          f"{time.monotonic() - t0:.1f} s ({sum(r['s'] for r in done):.1f} s of units); "
          f"{sum(r['skipped'] for r in done)} skipped; slowest: "
          + ", ".join(f"{r['name']} {r['s']:.0f} s" for r in sorted(done, key=lambda r: -r["s"])[:3]))
    if failed:
        print("FAILED: " + " ".join(failed))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
