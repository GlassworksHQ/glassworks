"""Off-device tests for Vitro's camera end-to-end test (MIT): its fake HA home (home.py, served by Clarity's fakeha.py
on a stub frontend and stub camera files) and the driver's checks (e2e.py) on made-up screens and logcat lines. No
device, no ffmpeg, no aiortc.

  python3 -m unittest discover -s tools/vitro-camera-e2e/test -v
"""
import http.client
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
PERF = os.path.join(TOOL, "..", "perf")
os.environ.setdefault("GW_PERF_FRONTEND", os.path.join(PERF, "test", "stub_frontend"))
STUB_CAMERA = tempfile.mkdtemp(prefix="vitro-camera-e2e-")
sys.path.insert(0, PERF)
import camera  # noqa: E402
os.makedirs(os.path.join(STUB_CAMERA, "mjpeg"))
for _prof in ("720p", "360p"):
    os.makedirs(os.path.join(STUB_CAMERA, _prof))
    with open(os.path.join(STUB_CAMERA, "mjpeg", camera.mjpeg_name(_prof)), "wb") as _f:
        _f.write(b"\xff\xd8a\xff\xd9\xff\xd8b\xff\xd9")
os.environ["GW_PERF_CAMERA"] = STUB_CAMERA
sys.path.insert(0, PERF)
sys.path.insert(0, TOOL)
import e2e  # noqa: E402
import fakeha  # noqa: E402
import home  # noqa: E402

W, H = 64, 32


def frame(fill=(0, 0, 0), box=None, box_fill=None):
    """A raw RGBA screen, optionally with a box of another colour (or a function of x, y) inside."""
    px = bytearray(bytes((*fill, 255)) * (W * H))
    if box:
        x0, y0, x1, y1 = box
        for y in range(y0, y1):
            for x in range(x0, x1):
                c = box_fill(x, y) if callable(box_fill) else box_fill
                px[(y * W + x) * 4:(y * W + x) * 4 + 3] = bytes(c)
    return W, H, bytes(px)


def pattern(shift):
    """A moving, many-coloured picture: what a live test-pattern stream looks like on screen."""
    return lambda x, y: (((x + shift) * 37) % 256, (y * 53 + shift * 11) % 256, ((x + y + shift) * 19) % 256)


class Home(unittest.TestCase):
    def tearDown(self):
        home.REMOVED.clear()

    def test_one_panel_view_per_transport_each_with_one_live_camera_card(self):
        views = {v["path"]: v for v in home.lovelace(home.DASHBOARD)["views"]}
        self.assertEqual(set(views), {"webrtc", "hls", "mjpeg", "none"})
        for name, (cam, _) in e2e.TRANSPORTS.items():
            self.assertEqual(views[name]["type"], "panel")
            self.assertEqual(views[name]["cards"], [{"type": "picture-entity", "entity": cam, "camera_view": "live",
                                                     "show_name": False, "show_state": False}])
            self.assertEqual(home.VIEWS[name][0], cam)
        self.assertNotIn("picture-entity", json.dumps(views["none"]))

    def test_each_camera_offers_what_its_transport_needs(self):
        self.assertIn("web_rtc", home.CAMERA_STREAM_TYPES["camera.clarity_cam_1"])
        self.assertEqual(home.CAMERA_STREAM_TYPES["camera.clarity_hls_1"], ["hls"])
        st = home.states()[home.MJPEG_CAMERA]
        self.assertEqual(st["attributes"]["supported_features"], 0)      # no STREAM: HA's frontend plays MJPEG
        self.assertTrue(st["attributes"]["entity_picture"].startswith(f"/api/camera_proxy/{home.MJPEG_CAMERA}?token="))
        self.assertEqual(home.CAMERA_PROFILES[home.MJPEG_CAMERA], "360p")

    def test_remove_and_restore_a_card(self):
        self.assertEqual(home.http_get("/vitro-camera-e2e/remove", {"view": ["hls"]})[0], 200)
        views = {v["path"]: v for v in home.lovelace(home.DASHBOARD)["views"]}
        self.assertEqual(views["hls"]["cards"], [])
        self.assertEqual(len(views["mjpeg"]["cards"]), 1)
        self.assertEqual(home.http_get("/vitro-camera-e2e/remove", {"view": ["nope"]})[0], 400)
        home.http_get("/vitro-camera-e2e/restore", {})
        self.assertEqual(len({v["path"]: v for v in home.lovelace(home.DASHBOARD)["views"]}["hls"]["cards"]), 1)
        self.assertIsNone(home.http_get("/somewhere/else", {}))

    def test_a_drop_refuses_websockets_for_its_seconds(self):
        try:
            self.assertFalse(home.ws_refused())
            status, body, _ = home.http_get("/vitro-camera-e2e/drop", {"seconds": ["0.3"]})
            self.assertEqual((status, json.loads(body)["seconds"]), (200, 0.3))
            self.assertTrue(home.ws_refused())
            time.sleep(0.4)
            self.assertFalse(home.ws_refused())
            self.assertEqual(home.http_get("/vitro-camera-e2e/drop", {"seconds": ["soon"]})[0], 400)
            self.assertEqual(home.http_get("/vitro-camera-e2e/drop", {"seconds": ["600"]})[0], 400)
        finally:
            home.DOWN_UNTIL = 0.0


class Served(unittest.TestCase):
    """The home as fakeha serves it: its dashboard, its removal route and the MJPEG camera's stream."""

    @classmethod
    def setUpClass(cls):
        cls.srv = fakeha.serve(0, harness=False, home=home)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        fakeha.D = fakeha.CLARITY

    def get(self, path):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            conn.request("GET", path)
            r = conn.getresponse()
            return r.status, r.read()
        finally:
            conn.close()

    def test_the_mjpeg_camera_streams_and_the_removal_route_answers(self):
        self.assertEqual(self.get("/vitro-camera-e2e/remove?view=mjpeg")[0], 200)
        self.assertEqual(self.get("/vitro-camera-e2e/restore")[0], 200)
        self.assertEqual(self.get("/clarity/lovelace_updated?url_path=clarity-ref")[0], 204)
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", f"/api/camera_proxy_stream/{home.MJPEG_CAMERA}")
        r = conn.getresponse()
        self.assertEqual(r.status, 200)
        self.assertIn(b"\xff\xd8a\xff\xd9", r.read1(4096) + r.read1(4096))
        conn.close()
        cams = json.loads(self.get("/clarity/cameras")[1])
        self.assertIn("webrtc_available", cams)


    def websocket(self):
        """A websocket to the fake, signed in: its socket, or the HTTP status that refused it."""
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        sock.sendall(b"GET /api/websocket HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                     b"Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                     b"Sec-WebSocket-Version: 13\r\n\r\n")
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = sock.recv(1)
            if not chunk:
                break
            head += chunk
        status = int(head.split(b" ", 2)[1])
        if status != 101:
            sock.close()
            return status
        self.assertIn(b"auth_required", self.ws_message(sock))
        msg = json.dumps({"type": "auth", "access_token": "e2e"}).encode()
        mask = b"\x01\x02\x03\x04"
        sock.sendall(bytes([0x81, 0x80 | len(msg)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))
        self.assertIn(b"auth_ok", self.ws_message(sock))
        return sock

    @staticmethod
    def ws_message(sock):
        """One unmasked server frame's payload. fakeha's exact reader: a socket that closes mid-frame raises
        ConnectionError rather than reading empty bytes for ever."""
        h = fakeha._recv(sock, 2)
        n = h[1] & 0x7F
        if n == 126:
            n = int.from_bytes(fakeha._recv(sock, 2), "big")
        return fakeha._recv(sock, n)

    def test_a_frame_cut_short_by_a_closed_socket_raises(self):
        a, b = socket.socketpair()
        try:
            a.sendall(bytes([0x81, 10]) + b"auth")
            a.close()
            b.settimeout(5)
            with self.assertRaises(ConnectionError):
                self.ws_message(b)
        finally:
            b.close()

    def test_a_drop_cuts_the_websockets_and_refuses_new_ones_until_ha_is_back(self):
        sock = self.websocket()
        try:
            for _ in range(50):         # the fake counts the connection once it has said auth_ok
                if fakeha.Handler.hub.conns:
                    break
                time.sleep(0.02)
            cut = json.loads(self.get("/vitro-camera-e2e/drop?seconds=0.5")[1])["cut"]
            self.assertEqual(cut, 1)
            self.assertEqual(sock.recv(16), b"", "the open websocket was cut")
            self.assertEqual(self.websocket(), 503)
            # HTTP stays up: stills and the MJPEG stream still answer.
            self.assertEqual(self.get("/vitro-camera-e2e/restore")[0], 200)
            time.sleep(0.6)
            again = self.websocket()
            self.assertNotEqual(again, 503)
            again.close()
        finally:
            sock.close()
            home.DOWN_UNTIL = 0.0


class Screen(unittest.TestCase):
    def test_a_moving_many_coloured_area_is_a_rendered_video(self):
        frames = iter([frame(box=(8, 4, 56, 28), box_fill=pattern(k)) for k in range(4)])

        class Dev:
            def frame(self):
                return next(frames)
        m = e2e.check_motion(Dev(), captures=4, gap=0)
        self.assertTrue(all(f > 0.1 for f in m["moving_fraction"]))
        self.assertGreaterEqual(m["colours"], 8)
        e2e.assert_rendered(m, "test")

    def test_a_still_fails(self):
        still = frame(box=(8, 4, 56, 28), box_fill=pattern(0))

        class Dev:
            def frame(self):
                return still
        with self.assertRaisesRegex(e2e.Fail, "didn't move"):
            e2e.assert_rendered(e2e.check_motion(Dev(), captures=3, gap=0), "test")

    def test_a_blinking_blank_surface_fails(self):
        frames = iter([frame(box=(8, 4, 56, 28), box_fill=(k * 60, k * 60, k * 60)) for k in range(4)])

        class Dev:
            def frame(self):
                return next(frames)
        with self.assertRaisesRegex(e2e.Fail, "colours"):
            e2e.assert_rendered(e2e.check_motion(Dev(), captures=4, gap=0), "test")

    def test_moving_grey_noise_isnt_the_test_pattern(self):
        grey = lambda k: lambda x, y: ((x * 31 + y * 17 + k * 47) % 256,) * 3  # noqa: E731
        frames = iter([frame(box=(8, 4, 56, 28), box_fill=grey(k)) for k in range(4)])

        class Dev:
            def frame(self):
                return next(frames)
        m = e2e.check_motion(Dev(), captures=4, gap=0)
        self.assertGreaterEqual(m["colours"], 8)
        with self.assertRaisesRegex(e2e.Fail, "hues"):
            e2e.assert_rendered(m, "test")

    def test_a_still_colour_poster_inside_a_moving_grey_border_fails(self):
        def poster(k):
            w, h, px = frame(box=(8, 4, 56, 28), box_fill=pattern(0))       # the colours never change
            px = bytearray(px)
            for y in range(h):
                for x in range(w):
                    if not (8 <= x < 56 and 4 <= y < 28):                   # everything around them does, in grey
                        v = (x * 13 + y * 7 + k * 90) % 256
                        px[(y * w + x) * 4:(y * w + x) * 4 + 3] = bytes((v, v, v))
            return w, h, bytes(px)
        frames = iter([poster(k) for k in range(4)])

        class Dev:
            def frame(self):
                return next(frames)
        m = e2e.check_motion(Dev(), captures=4, gap=0)
        self.assertGreater(m["box"][2] - m["box"][0], 40)                     # the moving area spans the poster
        with self.assertRaisesRegex(e2e.Fail, "hues"):
            e2e.assert_rendered(m, "test")

    def test_a_small_moving_spot_isnt_a_video(self):
        frames = iter([frame(box=(4, 4, 10, 10), box_fill=pattern(k)) for k in range(4)])

        class Dev:
            def frame(self):
                return next(frames)
        with self.assertRaisesRegex(e2e.Fail, "small area"):
            e2e.assert_rendered(e2e.check_motion(Dev(), captures=4, gap=0), "test")


class FirstMotion(unittest.TestCase):
    @staticmethod
    def dev(frames):
        it = iter(frames)

        class Dev:
            def frame(self):
                return next(it, frames[-1])
        return Dev()

    def test_a_window_appearing_once_isnt_motion(self):
        still = frame(box=(8, 4, 56, 28), box_fill=pattern(0))
        self.assertIsNone(e2e.first_motion(self.dev([frame(), still, still, still, still]), time.monotonic(), 0.3))

    def test_a_moving_picture_is(self):
        frames = [frame()] + [frame(box=(8, 4, 56, 28), box_fill=pattern(k)) for k in range(6)]
        self.assertIsNotNone(e2e.first_motion(self.dev(frames), time.monotonic(), 5))


class Probe(unittest.TestCase):
    def test_reads_the_test_apps_lines(self):
        lines = "\n".join([
            "--------- beginning of main",
            "I/VitroCamE2E( 4242): wrap kind=HLS",
            "I/VitroCamE2E( 4242): open id=1 entity=camera.clarity_hls_1 kind=HLS live=1",
            "I/VitroCamE2E( 4242): first_frame id=1 entity=camera.clarity_hls_1 kind=HLS since_start_ms=812 since_open_ms=830",
            "I/VitroCamE2E( 4242): close id=1 entity=camera.clarity_hls_1 kind=HLS total=40 live=0",
            "I/VitroCamE2E( 4242): error id=2 entity=camera.clarity_cam_1 kind=WEB_RTC reason=start_failed: the offer has no H.264 video",
        ])

        class Dev:
            def logcat(self):
                return lines
        p = e2e.Probe(Dev())
        new = p.poll()
        self.assertEqual([e["event"] for e in new], ["wrap", "open", "first_frame", "close", "error"])
        self.assertEqual((new[-1]["kind"], new[-1]["reason"]), ("WEB_RTC", "start_failed: the offer has no H.264 video"))
        first = p.wait(lambda e: e["event"] == "first_frame", 1)
        self.assertEqual((first["entity"], first["kind"], first["since_start_ms"]), ("camera.clarity_hls_1", "HLS", "812"))
        self.assertEqual(p.poll(), [])


class Pause(unittest.TestCase):
    CAM = "camera.vitro_e2e_mjpeg"

    @staticmethod
    def cams(sent, live=()):
        return {"mjpeg": [{"stream": n, "camera": Pause.CAM, "frames": f} for n, f in live],
                "mjpeg_sent": {Pause.CAM: sent}, "hls": [], "webrtc": []}

    def test_mjpeg_frames_that_stop_are_a_pause(self):
        b, a = self.cams(100, [(1, 100)]), self.cams(104, [(1, 104)])
        self.assertEqual(e2e.check_paused("mjpeg", self.CAM, {"before": b, "after": a}),
                         {"mjpeg_frames_sent_while_hidden": 4, "open": 1})
        with self.assertRaises(e2e.Fail):
            e2e.check_paused("mjpeg", self.CAM, {"before": b, "after": self.cams(145, [(1, 145)])})

    def test_streams_opened_or_closed_while_hidden_count(self):
        # Stream 1 sent 45 and closed; stream 2 opened and sent 4: the running total has both.
        b, a = self.cams(100, [(1, 100)]), self.cams(149, [(2, 4)])
        with self.assertRaisesRegex(e2e.Fail, "49 frames"):
            e2e.check_paused("mjpeg", self.CAM, {"before": b, "after": a})

    def test_hls_fetches_while_hidden_fail(self):
        cam = "camera.clarity_hls_1"
        rec = [{"token": "t", "camera": cam, "segments": 5, "playlists": 6, "idle_s": 0.5}]
        b = {"mjpeg": [], "hls": rec, "hls_fetched": {cam: 11}, "webrtc": []}
        a = {"mjpeg": [], "hls": rec, "hls_fetched": {cam: 11}, "webrtc": []}
        self.assertEqual(e2e.check_paused("hls", cam, {"before": b, "after": a}), {"hls_fetches_while_hidden": 0})
        a = dict(a, hls_fetched={cam: 13})
        with self.assertRaises(e2e.Fail):
            e2e.check_paused("hls", cam, {"before": b, "after": a})

    def test_an_expired_hls_record_cant_hide_a_fetch(self):
        cam = "camera.clarity_hls_1"
        b = {"mjpeg": [], "hls": [{"token": "t", "camera": cam, "segments": 5, "playlists": 6, "idle_s": 299.0}],
             "hls_fetched": {cam: 11}, "webrtc": []}
        a = {"mjpeg": [], "hls": [], "hls_fetched": {cam: 12}, "webrtc": []}     # the record went, the fetch counts
        with self.assertRaisesRegex(e2e.Fail, "1 HLS fetches"):
            e2e.check_paused("hls", cam, {"before": b, "after": a})

    def test_another_clients_stream_is_seen(self):
        cams = {"mjpeg": [], "webrtc": [{"session": "s", "camera": "camera.x", "sent": 3}],
                "hls": [{"token": "t", "camera": "camera.y", "playlists": 1, "segments": 0, "idle_s": 1.0},
                        {"token": "u", "camera": "camera.z", "playlists": 9, "segments": 8, "idle_s": 40.0}]}
        self.assertEqual(e2e.live_streams(cams), [("hls", "camera.y"), ("webrtc", "camera.x")])


if __name__ == "__main__":
    unittest.main()
