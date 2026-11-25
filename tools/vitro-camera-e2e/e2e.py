#!/usr/bin/env python3
"""Vitro's camera end-to-end test (MIT): plays a live camera over each transport (WebRTC, HLS, MJPEG) in Vitro's
test APK on an Android emulator, against Clarity's fake Home Assistant (serve.py, home.py), and asserts that real
frames reach the screen, that hiding the app pauses the stream, and that removing the card closes it.

What it touches: one emulator or device over adb (ANDROID_SERIAL picks it), where it starts, stops and drives
dev.glassworks.vitro.test only, and reads its logcat and screen. The fake HA on 127.0.0.1 (reads its counters, and
removes and restores its own test cards). Writes only under --out. Run it against an emulator only: it presses
HOME and force-stops the test app.

  tools/vitro-camera-e2e/e2e.py [--transports webrtc,hls,mjpeg] [--out DIR] [--first-frame-budget-ms 10000]

Before it runs: the APK is installed, serve.py is up, and `adb reverse tcp:8089 tcp:8089` and `tcp:8090 tcp:8090`
are set (run.sh and the CI job do all of that). ADB names the adb to run (`adb.exe` from WSL).

Per transport (each a view of home.py's dashboard, one live picture-entity card):
  1. open    the app starts on a view without a camera (the thread baseline), then goes to the camera's view
  2. first   the stream's first frame (the seam's Listener.onFirstFrame, logged by the test app's CameraProbe) comes
             within the budget, from the expected kind of stream, and the fake serves that transport
  3. render  the screen moves between captures 0.5 s apart (a still or the poster never does) and the moving area
             has the test pattern's colours (a blank or black surface has none)
  4. pause   HOME: the stream goes inactive (setActive false) and draws nothing; the fake stops sending MJPEG
             frames and serving HLS fetches. Back: active again, a first frame again, and motion again
  5. close   the card is removed from the dashboard (HA's lovelace_updated): the stream closes, no stream is left
             open, the fake has no session, connection or fetches left for it, and the app has no thread the
             stream started still running
"""
import argparse
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import time
import urllib.request

PKG = "dev.glassworks.vitro.test"
ACTIVITY = f"{PKG}/.VitroTestActivity"
TAG = "VitroCamE2E"
DASHBOARD = "clarity-ref"
# The view and camera per transport (home.py VIEWS), and the stream kind the seam names it.
TRANSPORTS = {
    "webrtc": ("camera.clarity_cam_1", "WEB_RTC"),
    "hls": ("camera.clarity_hls_1", "HLS"),
    "mjpeg": ("camera.vitro_e2e_mjpeg", "MJPEG"),
}
# Threads a process keeps for good once something used them, shared by every stream: Vitro's image loader pool,
# media3's static BackgroundExecutor (androidx.media3.common.util, "ExoPlayer:BackgroundExecutor"; a player's own
# thread is "ExoPlayer:Playback", and that one must go), and Android's binder pools, which grow when MediaCodec is
# first used. A stream starting one isn't a leak. Every other thread that appeared with the stream must be gone once
# it closed. Names are /proc's comm: 15 characters, after the last '.' (vitro-mjpeg-camera.x_mjpeg -> x_mjpeg).
SHARED_THREADS = re.compile(r"^(vitro-img|ExoPlayer:Backg|HwBinder:|Binder:)")


class Fail(AssertionError):
    pass


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Device:
    def __init__(self, adb):
        self.adb = shlex.split(adb)

    def run(self, *args, check=True, timeout=60, binary=False):
        # No stdin: adb (adb.exe from WSL above all) reads it, and would eat a calling script's input.
        p = subprocess.run(self.adb + list(args), capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {p.stderr.decode(errors='replace').strip()}")
        return p.stdout if binary else p.stdout.decode(errors="replace").replace("\r\n", "\n")

    def shell(self, cmd, **kw):
        return self.run("shell", cmd, **kw)

    def api(self):
        return int(self.shell("getprop ro.build.version.sdk").strip())

    def pid(self):
        for line in self.shell("ps -A 2>/dev/null || ps", check=False).splitlines():
            cols = line.split()
            if cols and cols[-1] == PKG:
                return cols[1]
        return None

    def threads(self):
        pid = self.pid()
        if not pid:
            return []
        out = self.shell(f"run-as {PKG} sh -c 'cat /proc/{pid}/task/*/comm'", check=False)
        return [t.strip() for t in out.splitlines() if t.strip()]

    def logcat(self):
        return self.run("logcat", "-d", "-v", "brief", "-s", f"{TAG}:I", check=False)

    def frame(self):
        """The screen, raw RGBA: (width, height, bytes). screencap's raw header is 12 or 16 bytes by version."""
        raw = self.run("exec-out", "screencap", binary=True, timeout=30)
        w, h = struct.unpack_from("<II", raw, 0)
        head = len(raw) - w * h * 4
        if head not in (12, 16) or w * h == 0:
            raise RuntimeError(f"unexpected screencap ({len(raw)} bytes for {w}x{h})")
        return w, h, raw[head:]

    def png(self, path):
        with open(path, "wb") as f:
            f.write(self.run("exec-out", "screencap", "-p", binary=True, timeout=30))

    def open(self, url, fresh=False):
        args = ["am", "start", "-W"] + (["-S"] if fresh else []) + ["-n", ACTIVITY, "--es", "url", url, "--ez", "hud", "false"]
        self.shell(" ".join(shlex.quote(a) for a in args))


class Probe:
    """The test app's CameraProbe lines, read from logcat as they come."""

    LINE = re.compile(rf"{TAG}\(\s*\d+\):\s*(\w+)\s*(.*)$")

    def __init__(self, dev):
        self.dev, self.events = dev, []

    def poll(self):
        seen = len(self.events)
        events = []
        for line in self.dev.logcat().splitlines():
            m = self.LINE.search(line)
            if m:
                ev = dict(kv.split("=", 1) for kv in m.group(2).split() if "=" in kv)
                ev["event"] = m.group(1)
                events.append(ev)
        self.events = events
        return events[seen:]

    def wait(self, pred, timeout, step=0.2):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            self.poll()
            hit = next((e for e in self.events if pred(e)), None)
            if hit:
                return hit
            time.sleep(step)
        return None


class Fake:
    def __init__(self, base):
        self.base = base

    def get(self, path):
        with urllib.request.urlopen(self.base + path, timeout=10) as r:
            body = r.read()
        return json.loads(body) if body and r.headers.get("Content-Type", "").startswith("application/json") else None

    def cameras(self):
        return self.get("/clarity/cameras")

    def lovelace_updated(self):
        self.get(f"/clarity/lovelace_updated?url_path={DASHBOARD}")


# ---- the screen


def changed(a, b, step=4, tol=24):
    """The sampled pixels (every `step` px each way) that differ between two raw frames, as (x, y) points."""
    (w, h, pa), (_, _, pb) = a, b
    out = []
    for y in range(0, h, step):
        row = y * w * 4
        for x in range(0, w, step):
            i = row + x * 4
            if abs(pa[i] - pb[i]) + abs(pa[i + 1] - pb[i + 1]) + abs(pa[i + 2] - pb[i + 2]) > tol:
                out.append((x, y))
    return out


def colours(frame, box, step=4):
    """How many distinct coarse colours (4 bits a channel) a box of a raw frame has."""
    w, _, px = frame
    x0, y0, x1, y1 = box
    seen = set()
    for y in range(y0, y1, step):
        for x in range(x0, x1, step):
            i = (y * w + x) * 4
            seen.add((px[i] >> 4, px[i + 1] >> 4, px[i + 2] >> 4))
    return len(seen)


def check_motion(dev, captures=4, gap=0.5):
    """Captures spaced `gap` s apart: every pair must differ (a live picture), over the same area (the video)."""
    frames = []
    for _ in range(captures):
        frames.append(dev.frame())
        time.sleep(gap)
    w, h, _ = frames[0]
    sampled = (w // 4) * (h // 4)
    pairs = [changed(frames[k], frames[k + 1]) for k in range(len(frames) - 1)]
    moving = [len(p) / sampled for p in pairs]
    pts = [pt for p in pairs for pt in p]
    box = None
    if pts:
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        box = (min(xs), min(ys), max(xs) + 1, max(ys) + 1)
    return {"moving_fraction": [round(m, 4) for m in moving], "box": box,
            "colours": colours(frames[-1], box) if box else 0, "size": [w, h]}


def first_motion(dev, t0, timeout, streak=3):
    """When a moving picture first shows, as the screen sees it, in ms since t0 (monotonic): the first of `streak`
    back-to-back captures in a row that each changed. One change alone is a window or view appearing, or the poster
    giving way to the video, not motion. Coarse: a capture takes 150 to 300 ms on an emulator."""
    end = t0 + timeout
    prev, run, since = dev.frame(), 0, None
    while time.monotonic() < end:
        at = time.monotonic()
        cur = dev.frame()
        w, h, _ = cur
        if len(changed(prev, cur, step=8)) > 0.01 * (w // 8) * (h // 8):
            since = at if run == 0 else since
            run += 1
            if run >= streak:
                return round((since - t0) * 1000)
        else:
            run = 0
        prev = cur
    return None


def assert_rendered(m, what):
    if min(m["moving_fraction"]) < 0.001:
        raise Fail(f"{what}: the picture didn't move between captures {m['moving_fraction']} (a still, or no video)")
    x0, y0, x1, y1 = m["box"]
    w, h = m["size"]
    if (x1 - x0) * (y1 - y0) < 0.02 * w * h:
        raise Fail(f"{what}: only a small area moved ({m['box']}): not a video picture")
    if m["colours"] < 8:
        raise Fail(f"{what}: the moving area has {m['colours']} colours: blank, not the test pattern")


# ---- one transport


def run_transport(dev, fake, probe, name, base_url, budget_ms, out, seam_required, r):
    """Fills `r` as it goes, so a failure still reports what was measured before it."""
    cam, kind = TRANSPORTS[name]
    r.update({"transport": name, "camera": cam, "kind": kind})
    url = f"{base_url}/{DASHBOARD}"

    # 1. A fresh app on the view without a camera: the threads it runs without any stream.
    dev.shell(f"am force-stop {PKG}")
    dev.run("logcat", "-c")
    dev.open(f"{url}/none", fresh=True)
    time.sleep(4)
    baseline = set(dev.threads())
    probe.poll()

    # 2. The camera's view: its stream's first frame.
    t0 = time.monotonic()
    dev.open(f"{url}/{name}")
    r["first_motion_ms"] = first_motion(dev, t0, budget_ms / 1000 + 5)
    # Without the seam required, a build that plays cameras another way says nothing: once its picture moves, a
    # few seconds are enough to know.
    wait_s = budget_ms / 1000 + 5 if seam_required or r["first_motion_ms"] is None else 3
    opened = probe.wait(lambda e: e["event"] == "open" and e.get("entity") == cam, wait_s)
    first = probe.wait(lambda e: e["event"] == "first_frame" and e.get("entity") == cam,
                       budget_ms / 1000 + 5 if opened else 0.1)
    r["wall_to_first_frame_ms"] = round((time.monotonic() - t0) * 1000) if first else None
    r["opened"] = [(e.get("kind"), e.get("id")) for e in probe.events if e["event"] == "open" and e.get("entity") == cam]
    r["errors"] = [e for e in probe.events if e["event"] in ("error", "ended")]
    if first:
        r["first_frame_ms"] = int(first["since_start_ms"])
        r["first_frame_since_open_ms"] = int(first["since_open_ms"])
        r["played_kind"] = first.get("kind")
    elif opened or seam_required:
        raise Fail(f"{name}: no first frame within {budget_ms} ms (streams opened: {r['opened']}, errors: {r['errors']})")
    else:
        r["seam"] = "silent: no CameraStream opened (the build plays cameras without the seam); screen checks only"
        if r["first_motion_ms"] is None or r["first_motion_ms"] > budget_ms:
            raise Fail(f"{name}: no moving picture within {budget_ms} ms (first motion: {r['first_motion_ms']})")
    if first:
        if first.get("kind") != kind:
            raise Fail(f"{name}: the first frame came from a {first.get('kind')} stream, not {kind} (opened {r['opened']})")
        if r["first_frame_ms"] > budget_ms:
            raise Fail(f"{name}: first frame after {r['first_frame_ms']} ms, over the {budget_ms} ms budget")
    sid = first["id"] if first else None
    served = fake.cameras()
    r["served"] = {k: [s for s in v if s.get("camera") == cam] for k, v in served.items() if isinstance(v, list)}
    want = {"webrtc": "webrtc", "hls": "hls", "mjpeg": "mjpeg"}[name]
    if not r["served"][want]:
        raise Fail(f"{name}: the fake serves no {want} stream for {cam}: {served}")

    # 3. Real frames on screen.
    time.sleep(1.0)
    r["render"] = check_motion(dev)
    dev.png(os.path.join(out, f"{name}-playing.png"))
    assert_rendered(r["render"], name)
    stream_threads = sorted(t for t in set(dev.threads()) - baseline)
    r["stream_threads"] = stream_threads

    # 4. Hidden: paused. Shown again: playing.
    dev.shell("input keyevent KEYCODE_HOME")
    if sid:
        inactive = probe.wait(lambda e: e["event"] in ("active", "close") and e.get("id") == sid
                              and e.get("value", "false") == "false", 5)
        if not inactive:
            raise Fail(f"{name}: HOME didn't pause the stream (no setActive(false) or close)")
        r["hidden"] = inactive["event"] if inactive["event"] == "close" else "inactive"
    time.sleep(2.0)
    n_before = len(probe.events)
    pause = {"before": fake.cameras()}
    time.sleep(3.0)
    pause["after"] = fake.cameras()
    probe.poll()
    drawn = [e for e in probe.events[n_before:] if e["event"] in ("frames", "first_frame") and e.get("id") == sid]
    r["pause"] = check_paused(name, cam, pause)
    if drawn:
        raise Fail(f"{name}: the hidden stream still drew frames: {drawn}")
    mark = len(probe.events)
    t1 = time.monotonic()
    dev.open(f"{url}/{name}")
    # A paused stream may have let its player go (HLS does): back, it connects again before it moves.
    r["resume_first_motion_ms"] = first_motion(dev, t1, budget_ms / 1000 + 5)
    if not sid and (r["resume_first_motion_ms"] is None or r["resume_first_motion_ms"] > budget_ms):
        raise Fail(f"{name}: no moving picture within {budget_ms} ms after the app came back")
    again = probe.wait(lambda e: e["event"] == "first_frame" and e.get("entity") == cam
                       and probe.events.index(e) >= mark, budget_ms / 1000 + 5) if sid else None
    if sid and not again:
        raise Fail(f"{name}: no first frame after the app came back")
    r["resume_first_frame_ms"] = int(again["since_start_ms"]) if again else None
    r["resume_wall_ms"] = round((time.monotonic() - t1) * 1000) if again else None
    if again:
        sid = again["id"]
    time.sleep(1.0)
    r["render_after_resume"] = check_motion(dev)
    assert_rendered(r["render_after_resume"], f"{name} after resume")

    # 5. The card removed: the stream closes, and nothing of it is left.
    fake.get(f"/vitro-camera-e2e/remove?view={name}")
    fake.lovelace_updated()
    if sid:
        closed = probe.wait(lambda e: e["event"] == "close" and e.get("entity") == cam and e.get("id") == sid, 10)
        if not closed:
            raise Fail(f"{name}: removing the card didn't close its stream")
        time.sleep(0.5)
        probe.poll()
        last = [e for e in probe.events if "live" in e][-1]
        r["live_after_close"] = int(last["live"])
        if r["live_after_close"] != 0:
            raise Fail(f"{name}: {last['live']} camera streams still open after the card went")
    time.sleep(4.0)
    left = fake.cameras()
    r["served_after_close"] = {k: [s for s in v if s.get("camera") == cam] for k, v in left.items() if isinstance(v, list)}
    if r["served_after_close"]["mjpeg"] or r["served_after_close"]["webrtc"]:
        raise Fail(f"{name}: the fake still serves {cam} after the card went: {r['served_after_close']}")
    if any(s["idle_s"] < 3 for s in r["served_after_close"]["hls"]):
        raise Fail(f"{name}: HLS fetches for {cam} continue after the card went: {r['served_after_close']['hls']}")
    leaked = sorted(t for t in (set(dev.threads()) - baseline) & set(stream_threads) if not SHARED_THREADS.search(t))
    r["threads_left"] = leaked
    if leaked:
        raise Fail(f"{name}: the stream's threads outlived it: {leaked}")
    dev.png(os.path.join(out, f"{name}-removed.png"))
    fake.get("/vitro-camera-e2e/restore")
    fake.lovelace_updated()
    return r


def check_paused(name, cam, pause):
    """While hidden, the fake sends no MJPEG frames and serves no HLS fetches for the camera."""
    b, a = pause["before"], pause["after"]
    out = {}
    if name == "mjpeg":
        before = {s["stream"]: s["frames"] for s in b["mjpeg"] if s["camera"] == cam}
        after = {s["stream"]: s["frames"] for s in a["mjpeg"] if s["camera"] == cam}
        sent = sum(after.get(k, v) - v for k, v in before.items())
        out = {"mjpeg_frames_sent_while_hidden": sent, "open": len(after)}
        # A paused reader stops reading; TCP buffers still take a few frames before the fake's writes block.
        if sent > 10:
            raise Fail(f"mjpeg: the fake sent {sent} frames in 3 s while the app was hidden")
    elif name == "hls":
        fetched = sum(s["segments"] + s["playlists"] for s in a["hls"] if s["camera"] == cam) - \
            sum(s["segments"] + s["playlists"] for s in b["hls"] if s["camera"] == cam)
        out = {"hls_fetches_while_hidden": fetched}
        if fetched > 0:
            raise Fail(f"hls: {fetched} HLS fetches in 3 s while the app was hidden")
    else:
        out = {"webrtc_sessions_while_hidden": len([s for s in a["webrtc"] if s["camera"] == cam])}
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--transports", default="webrtc,hls,mjpeg")
    ap.add_argument("--base", default="http://127.0.0.1:8089", help="the fake as the device reaches it (adb reverse)")
    ap.add_argument("--fake", default="http://127.0.0.1:8089", help="the fake as this computer reaches it")
    ap.add_argument("--first-frame-budget-ms", type=int, default=10000)
    ap.add_argument("--seam", choices=("required", "optional"), default="required",
                    help="optional: a build that plays cameras without the CameraStream seam passes on the screen checks")
    ap.add_argument("--out", default="vitro-camera-e2e-out")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = Device(os.environ.get("ADB", "adb"))
    fake, probe = Fake(a.fake), Probe(dev)
    api = dev.api()
    log(f"device API {api}; fake {a.fake}; transports {a.transports}")
    results, failed = [], False
    for name in [t.strip() for t in a.transports.split(",") if t.strip()]:
        if name == "webrtc" and not fake.cameras().get("webrtc_available"):
            raise SystemExit("the fake has no WebRTC peer (run serve.py from webrtcenv.py's venv)")
        log(f"{name}: start")
        r = {}
        try:
            run_transport(dev, fake, probe, name, a.base, a.first_frame_budget_ms, a.out, a.seam == "required", r)
            r["ok"] = True
        except (Fail, RuntimeError, subprocess.TimeoutExpired) as e:
            failed = True
            r.update({"transport": name, "ok": False, "error": str(e)})
            try:
                dev.png(os.path.join(a.out, f"{name}-failed.png"))
                with open(os.path.join(a.out, f"{name}-logcat.txt"), "w") as f:
                    f.write(dev.run("logcat", "-d", "-v", "threadtime", check=False)[-400_000:])
                fake.get("/vitro-camera-e2e/restore")
                fake.lovelace_updated()
            except Exception as e2:  # noqa: BLE001 - the report matters more than the evidence
                r["evidence_error"] = repr(e2)
        r["api"] = api
        results.append(r)
        log(f"{name}: {'ok' if r['ok'] else 'FAILED: ' + r['error']}" + (
            f" (first frame {r.get('first_frame_ms')} ms, resumed {r.get('resume_first_frame_ms')} ms)" if r["ok"] else ""))
    dev.shell(f"am force-stop {PKG}", check=False)
    with open(os.path.join(a.out, f"results-api{api}.json"), "w") as f:
        json.dump(results, f, indent=1)
    print(json.dumps([{k: r.get(k) for k in ("transport", "api", "ok", "first_frame_ms", "first_frame_since_open_ms", "first_motion_ms",
                                              "resume_first_frame_ms", "resume_first_motion_ms", "error", "seam")}
                      for r in results], indent=1))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
