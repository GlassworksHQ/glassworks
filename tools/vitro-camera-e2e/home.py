"""The camera end-to-end test's home for Clarity's fake Home Assistant (MIT): Clarity's home, cameras and all,
with one dashboard view per camera transport, each a single live picture-entity card, so the video is the only
thing on screen that moves.

It is a home module for tools/perf/fakeha.py (`serve(home=home)`, like tools/vitro-parity/fakehome.py); serve.py
starts it. The cameras are Clarity's own (tools/perf/dashboards.py), so the WebRTC peer serves them:

  /clarity-ref/webrtc   camera.clarity_cam_1, offering HLS and WebRTC: HA's frontend plays WebRTC
  /clarity-ref/hls      camera.clarity_hls_1, offering HLS only
  /clarity-ref/mjpeg    camera.vitro_e2e_mjpeg, without HA's STREAM feature: the MJPEG proxy stream
  /clarity-ref/none     no camera (a view to switch to)

All three are the same 640x360 stream (camera.py's 360p profile: a moving test pattern with a burnt-in counter).

  GET /vitro-camera-e2e/remove?view=<path>   that view's card is gone from the config from now on
  GET /vitro-camera-e2e/restore              every card is back

Neither tells the frontend: the test then asks fakeha for HA's `lovelace_updated` (/clarity/lovelace_updated).
Made-up names and loopback only; nothing here reads or reaches a real Home Assistant.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "perf"))
import dashboards as _clarity  # noqa: E402

DASHBOARD = _clarity.DASHBOARD
MJPEG_CAMERA = "camera.vitro_e2e_mjpeg"
WEBRTC_CAMERAS = True       # fakeha.serve: this home's cameras are Clarity's, so its WebRTC peer serves them

# Per view: its camera (None: the view has none) and what HA's frontend plays it with.
VIEWS = {
    "webrtc": ("camera.clarity_cam_1", "web_rtc"),
    "hls": ("camera.clarity_hls_1", "hls"),
    "mjpeg": (MJPEG_CAMERA, "mjpeg"),
    "none": (None, None),
}

CAMERA_PROFILES = dict(_clarity.CAMERA_PROFILES, **{MJPEG_CAMERA: "360p"})
# camera/capabilities for the stream cameras; the MJPEG one has no STREAM feature, so the frontend never asks.
CAMERA_STREAM_TYPES = dict(_clarity.CAMERA_STREAM_TYPES, **{MJPEG_CAMERA: []})

REMOVED = set()


def __getattr__(name):
    """What this home doesn't set is Clarity's."""
    return getattr(_clarity, name)


def states():
    out = _clarity.states()
    token = "e2e-" + MJPEG_CAMERA.split(".")[1]
    base = out["camera.clarity_hls_1"]
    out[MJPEG_CAMERA] = dict(base, entity_id=MJPEG_CAMERA, state="idle", attributes={
        "friendly_name": "Camera MJPEG", "access_token": token,
        "entity_picture": f"/api/camera_proxy/{MJPEG_CAMERA}?token={token}", "supported_features": 0})
    return out


def card(view):
    cam = VIEWS[view][0]
    if cam is None:
        return {"type": "markdown", "content": "No camera on this view."}
    return {"type": "picture-entity", "entity": cam, "camera_view": "live", "show_name": False, "show_state": False}


def lovelace(url_path):
    if url_path != DASHBOARD:
        return _clarity.lovelace(url_path)
    return {"views": [{"title": f"Camera {v}", "path": v, "type": "panel",
                       "cards": [] if v in REMOVED else [card(v)]} for v in VIEWS]}


def http_get(path, query):
    if path == "/vitro-camera-e2e/remove":
        view = (query.get("view") or [""])[0]
        if view not in VIEWS:
            return 400, b"unknown view", "text/plain"
        REMOVED.add(view)
        return 200, json.dumps(sorted(REMOVED)).encode(), "application/json"
    if path == "/vitro-camera-e2e/restore":
        REMOVED.clear()
        return 200, b"[]", "application/json"
    return None
