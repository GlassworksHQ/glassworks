#!/usr/bin/env python3
"""The legacy engine's lockdown check page (#198, MIT). Two tiny web servers on this computer:

  --port      (default 18125) the "Home Assistant": the page the engine is told to load, so its origin is allowed.
  --port + 1  (default 18126) "somewhere else": another origin (another port), so everything from it must be blocked.

The page shows what the engine let through: window.s1kiosk (the helper extension), a same-origin iframe and image
(should load), a cross-origin iframe, image, fetch and WebSocket (should be blocked, with the lockdown's note),
and buttons for a cross-origin link, window.open and a download (should be refused, with a note).

It touches nothing on the device and nothing on this computer but its own two ports, on 127.0.0.1 only. The device
reaches them through `adb reverse`:

  python3 tools/legacy/lockdown-page.py
  adb -s <serial> reverse tcp:18125 tcp:18125 && adb -s <serial> reverse tcp:18126 tcp:18126
  adb -s <serial> shell am start -n sh.edm.s1kiosk.legacy/sh.edm.s1kiosk.legacycheck.EngineCheckActivity \\
      --es url http://127.0.0.1:18125/
"""
import argparse
import base64
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# A 16x16 PNG, one flat colour: green for "loaded from Home Assistant", red for "loaded from elsewhere".
GREEN = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR4nGMQbbAlCTGMahjVMHw1AAB939IBri6i9AAAAABJRU5ErkJggg==")
RED = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR4nGPYKSNDEmIY1TCqYfhqAAC9r/EBhrs/3wAAAABJRU5ErkJggg==")

PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lockdown check</title>
<style>
 body {{ font: 15px/1.4 system-ui, sans-serif; margin: 16px; background: #fff; color: #111; }}
 h1 {{ font-size: 18px; margin: 0 0 8px; }}
 .row {{ display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-start; margin: 8px 0; }}
 .box {{ border: 1px solid #ccc; border-radius: 8px; padding: 8px; width: 260px; }}
 iframe {{ width: 240px; height: 90px; border: 1px solid #999; }}
 img {{ width: 32px; height: 32px; image-rendering: pixelated; border: 1px solid #999; }}
 button, a.btn {{ font: inherit; padding: 6px 10px; margin: 4px 4px 0 0; display: inline-block; }}
 code {{ font-size: 13px; }}
</style></head>
<body>
<h1>Lockdown check: this page is "Home Assistant" ({home})</h1>
<div class="row">
 <div class="box"><b>Helper extension</b><br>window.s1kiosk: <code id="s1kiosk">waiting…</code></div>
 <div class="box"><b>Same origin</b> (should load)<br><img src="/green.png" alt="same-origin image"><br>
  <iframe src="/frame.html" title="same-origin frame"></iframe></div>
 <div class="box"><b>Other origin</b> (should be blocked)<br><img src="{other}/red.png" alt="cross-origin image"
  onerror="this.alt='blocked'"><br><iframe src="{other}/frame.html" title="cross-origin frame"></iframe></div>
 <div class="box"><b>Other origin, scripts</b> (should be blocked)<br>fetch: <code id="fetch">…</code><br>
  WebSocket: <code id="ws">…</code></div>
</div>
<div class="row">
 <a class="btn" id="link" href="{other}/">Link to the other origin</a>
 <button id="open" onclick="window.open('{other}/', '_blank')">window.open</button>
 <a class="btn" id="dl" href="/download.bin" download>Download a file</a>
</div>
<script>
 function s1() {{ document.getElementById("s1kiosk").textContent = window.s1kiosk ? JSON.stringify(window.s1kiosk) : "not set"; }}
 window.addEventListener("s1kiosk-state", s1); setTimeout(s1, 3000);
 fetch("{other}/data.json").then(r => r.text()).then(t => document.getElementById("fetch").textContent = "LOADED " + t,
   e => document.getElementById("fetch").textContent = "blocked (" + e.name + ")");
 try {{
  const ws = new WebSocket("{other_ws}/socket");
  ws.onopen = () => document.getElementById("ws").textContent = "OPEN";
  ws.onerror = () => document.getElementById("ws").textContent = "blocked";
 }} catch (e) {{ document.getElementById("ws").textContent = "blocked (" + e.name + ")"; }}
</script>
</body></html>
"""

FRAME = """<!doctype html><html><head><meta charset="utf-8"></head>
<body style="margin:0;font:13px system-ui;background:{bg};color:#fff;display:flex;align-items:center;justify-content:center;height:100vh">
{text}</body></html>"""


def handler(home, other, is_home):
    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            print(f"[{'home' if is_home else 'other'}] {self.address_string()} {fmt % args}", flush=True)

        def send(self, code, ctype, body, extra=()):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            for k, v in extra:
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path in ("/", "/index.html") and is_home:
                other_ws = other.replace("http://", "ws://")
                self.send(200, "text/html; charset=utf-8", PAGE.format(home=home, other=other, other_ws=other_ws).encode())
            elif path == "/frame.html":
                text = "same-origin frame: loaded" if is_home else "CROSS-ORIGIN FRAME LOADED"
                self.send(200, "text/html; charset=utf-8", FRAME.format(bg="#15803d" if is_home else "#b91c1c", text=text).encode())
            elif path == "/green.png":
                self.send(200, "image/png", GREEN)
            elif path == "/red.png":
                self.send(200, "image/png", RED)
            elif path == "/data.json":
                self.send(200, "application/json", b'{"cross_origin": "loaded"}')
            elif path == "/download.bin":
                self.send(200, "application/octet-stream", b"lockdown check download\n",
                          [("Content-Disposition", 'attachment; filename="lockdown-check.bin"')])
            elif path == "/" and not is_home:
                self.send(200, "text/html; charset=utf-8", FRAME.format(bg="#b91c1c", text="OTHER ORIGIN PAGE LOADED").encode())
            else:
                self.send(404, "text/plain", b"not found")
    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=18125)
    a = ap.parse_args()
    home, other = f"http://127.0.0.1:{a.port}", f"http://127.0.0.1:{a.port + 1}"
    servers = [ThreadingHTTPServer(("127.0.0.1", a.port), handler(home, other, True)),
               ThreadingHTTPServer(("127.0.0.1", a.port + 1), handler(home, other, False))]
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print(f"lockdown check: home {home}/, other origin {other}/", flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
