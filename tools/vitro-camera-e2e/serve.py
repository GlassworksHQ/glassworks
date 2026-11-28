#!/usr/bin/env python3
"""Clarity's fake Home Assistant with the camera end-to-end test's home (home.py) (MIT). Run it from
tools/perf/webrtcenv.py's venv, so its WebRTC peer has aiortc:

  "$(python3 tools/perf/webrtcenv.py)" tools/vitro-camera-e2e/serve.py [--port 8089] [--log fake.jsonl]

It listens on 127.0.0.1 only: HTTP and the websocket on --port, the WebRTC peer's TURN relay (over TCP) on
--port + 1. The emulator reaches both through `adb reverse`. It needs ffmpeg with libx264 for the camera streams
(tools/perf/camera.py makes them once into the cache). Nothing here reaches a real Home Assistant.
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "perf"))
sys.path.insert(0, HERE)
import camera  # noqa: E402
import fakeha  # noqa: E402
import home  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--log", help="append the fake's log (JSON lines) to this file")
    a = ap.parse_args()
    try:
        camera.root()
        camera.mjpeg_frames("360p")       # made now, not inside the first stream's request
    except camera.Unavailable as e:
        sys.exit(str(e))
    srv = fakeha.serve(a.port, a.log, harness=False, home=home)
    w = fakeha.Handler.hub.webrtc
    rtc = f"WebRTC relay on 127.0.0.1:{fakeha.Handler.hub.turn_port}, media on {w.addr}" if w else "no WebRTC (no aiortc)"
    print(f"camera e2e home on http://127.0.0.1:{a.port}/{home.DASHBOARD}/<webrtc|hls|mjpeg|none>; {rtc}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
