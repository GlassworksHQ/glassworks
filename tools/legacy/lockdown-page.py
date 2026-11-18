#!/usr/bin/env python3
"""The legacy engine's lockdown check (#198, MIT). Two tiny web servers on this computer:

  --port      (default 18125) the "Home Assistant": the page the engine is told to load, so its origin is allowed.
  --port + 1  (default 18126) "somewhere else": another origin (another port), so nothing may reach it.

The page shows what the engine let through. Rows that run by themselves sit in same-origin frames, so a leak
can't take the page away; rows that would leave the page are buttons. **The proof is this script's output: the
other origin prints a `LEAK` line for every connection and every request it gets, and must print none.**

It touches nothing on the device and nothing on this computer but its own two ports, on 127.0.0.1 only. The device
reaches them through `adb reverse`:

  python3 tools/legacy/lockdown-page.py
  adb -s <serial> reverse tcp:18125 tcp:18125 && adb -s <serial> reverse tcp:18126 tcp:18126
  adb -s <serial> shell am start -n sh.edm.s1kiosk.legacy/dev.glassworks.legacycheck.EngineCheckActivity \\
      --es url http://127.0.0.1:18125/

On an emulator, `--es url http://10.0.2.2:18125/` reaches the same server without `adb reverse`, at a host that
isn't loopback: Gecko exempts loopback from rules a real Home Assistant meets (#455, legacy-engine.md step 15).
"""
import argparse
import base64
import hashlib
import time
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
<!-- Speculative connections: with the legacy prefs, none of these may reach the other origin. They name it as
     "localhost", not 127.0.0.1: Gecko never connects speculatively to a local IP literal (nsHttpConnectionMgr), so
     an IP here would pass even without the prefs. localhost reaches the same server through adb reverse. -->
<link rel="preconnect" href="{other_name}">
<link rel="dns-prefetch" href="//{other_name_host}">
<link rel="prefetch" href="{other_name}/hit/prefetch">
<style>
 body {{ font: 14px/1.35 system-ui, sans-serif; margin: 12px; background: #fff; color: #111; }}
 h1 {{ font-size: 16px; margin: 0 0 6px; }}
 h2 {{ font-size: 14px; margin: 10px 0 4px; }}
 .row {{ display: flex; gap: 8px; flex-wrap: wrap; align-items: flex-start; margin: 6px 0; }}
 .box {{ border: 1px solid #ccc; border-radius: 8px; padding: 6px 8px; width: 250px; }}
 iframe {{ width: 230px; height: 60px; border: 1px solid #999; }}
 img {{ width: 24px; height: 24px; image-rendering: pixelated; border: 1px solid #999; vertical-align: middle; }}
 button, a.btn {{ font: inherit; padding: 6px 10px; margin: 3px 3px 0 0; display: inline-block; }}
 code {{ font-size: 12px; }}
</style></head>
<body>
<h1>Lockdown check: this page is "Home Assistant" ({home}). Nothing may reach {other}.</h1>
<h2>Runs by itself</h2>
<div class="row">
 <div class="box"><b>Helper extension</b><br>window.s1kiosk: <code id="s1kiosk">waiting…</code></div>
 <div class="box"><b>Same origin</b> (loads)<br><img src="/green.png" alt="same-origin image"><br>
  <iframe src="/frame.html" title="same-origin frame"></iframe></div>
 <div class="box"><b>Other origin</b> (blocked)<br><img src="{other}/hit/red.png" alt="cross-origin image"
  onerror="this.alt='blocked'"><br><iframe src="{other}/hit/frame" title="cross-origin frame"></iframe></div>
 <div class="box"><b>Other origin, scripts</b> (blocked)<br>fetch: <code id="fetch">…</code><br>
  WebSocket: <code id="ws">…</code><br>WebRTC: <code id="rtc">…</code></div>
</div>
<div class="row">
 <div class="box"><b>Meta refresh in a frame</b> (refused)<br><iframe src="/t/meta" title="meta refresh"></iframe></div>
 <div class="box"><b>302 in a frame</b> (refused)<br><iframe src="/t/redirect?to=frame" title="302"></iframe></div>
 <div class="box"><b>POST form, auto-submitted</b> (refused)<br><iframe src="/t/post?target=_self" title="post"></iframe></div>
 <div class="box"><b>POST form to the top</b> (refused; this page stays)<br><iframe src="/t/post?target=_top" title="post top"></iframe></div>
 <div class="box"><b>Link to the top from a frame</b> (refused; this page stays)<br><iframe src="/t/link-top" title="link top"></iframe></div>
 <div class="box"><b>Preconnect, DNS prefetch, prefetch</b> to {other_name} (in this page's head)<br>nothing on the other origin's log</div>
</div>
<h2>Tap each, then come back with Back if the page changed</h2>
<div class="row">
 <a class="btn" id="link" href="{other}/hit/link">Link to the other origin</a>
 <button id="open" onclick="window.open('{other}/hit/window', '_blank')">window.open, other origin</button>
 <button id="open-same" onclick="window.open('/t/same-window', '_blank')">window.open, same origin (opens here)</button>
 <a class="btn" id="dl" href="/download.bin" download>Download a file</a>
 <a class="btn" id="dl-other" href="{other}/hit/download.bin" download>Download from the other origin</a>
 <a class="btn" id="r302" href="/t/redirect?to=top">A page that 302s elsewhere</a>
 <a class="btn" id="meta-top" href="/t/meta?top=1">A page that meta-refreshes elsewhere</a>
</div>
<script>
 function s1() {{ document.getElementById("s1kiosk").textContent = window.s1kiosk ? JSON.stringify(window.s1kiosk) : "not set"; }}
 window.addEventListener("s1kiosk-state", s1); setTimeout(s1, 3000);
 const out = (id, t) => document.getElementById(id).textContent = t;
 fetch("{other}/hit/data.json").then(r => r.text()).then(t => out("fetch", "LOADED " + t), e => out("fetch", "blocked (" + e.name + ")"));
 try {{
  const ws = new WebSocket("{other_ws}/hit/socket");
  ws.onopen = () => out("ws", "OPEN");
  ws.onerror = () => out("ws", "blocked"); // the server answers it: an allowed socket opens
 }} catch (e) {{ out("ws", "blocked (" + e.name + ")"); }}
 if (typeof RTCPeerConnection === "undefined") {{
  out("rtc", "off (no RTCPeerConnection)");
 }} else {{
  try {{
   const pc = new RTCPeerConnection({{ iceServers: [{{ urls: "stun:127.0.0.1:{other_port}" }}] }});
   let n = 0;
   pc.onicecandidate = (e) => {{ if (e.candidate) out("rtc", "ON: " + (++n) + " ICE candidate(s)"); }};
   pc.createDataChannel("x");
   pc.createOffer().then((o) => pc.setLocalDescription(o)).then(() => out("rtc", "ON: gathering"), (e) => out("rtc", "ON, offer failed: " + e.name));
  }} catch (e) {{ out("rtc", "blocked (" + e.name + ")"); }}
 }}
</script>
</body></html>
"""

T_META = """<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="refresh" content="{delay};url={other}/hit/meta-{where}"></head>
<body style="margin:4px;font:13px system-ui">{text}</body></html>"""

T_POST = """<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:4px;font:13px system-ui">
<form id="f" method="post" action="{other}/hit/post-{name}" target="{target}"><input type="hidden" name="x" value="1"></form>
waiting to submit… (if this stays, it was refused)
<script>setTimeout(() => document.getElementById("f").submit(), 1500);</script></body></html>"""

T_LINK_TOP = """<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:4px;font:13px system-ui">
<a id="a" href="{other}/hit/link-top" target="_top">to the top</a> (clicked by script; this page stays if refused)
<script>setTimeout(() => document.getElementById("a").click(), 2000);</script></body></html>"""

T_SAME = """<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:16px;font:16px system-ui">
<p><b>PASS:</b> window.open to Home Assistant's own origin opened here, in place.</p><p><a href="/">Back to the check</a></p></body></html>"""

FRAME = """<!doctype html><html><head><meta charset="utf-8"></head>
<body style="margin:0;font:13px system-ui;background:{bg};color:#fff;display:flex;align-items:center;justify-content:center;height:100vh">
{text}</body></html>"""


def handler(home, other, is_home):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"  # a WebSocket's 101 needs it; every other answer has a Content-Length

        def log_message(self, fmt, *args):
            tag = "[home]" if is_home else "[other] LEAK:"
            print(f"{tag} {self.address_string()} {fmt % args}", flush=True)

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            self.rfile.read(n)
            self.send(200, "text/html; charset=utf-8",
                      FRAME.format(bg="#b91c1c", text=f"POST REACHED {'HOME' if is_home else 'THE OTHER ORIGIN'}").encode())

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
            key = self.headers.get("Sec-WebSocket-Key")
            if path.endswith("/socket") and key:
                # A real WebSocket handshake, so a socket the lockdown let through shows OPEN, not a false "blocked".
                accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
                self.send_response(101)
                self.send_header("Upgrade", "websocket")
                self.send_header("Connection", "Upgrade")
                self.send_header("Sec-WebSocket-Accept", accept)
                self.end_headers()
                self.wfile.flush()
                time.sleep(2)
                self.close_connection = True
                return
            query = dict(p.split("=", 1) for p in self.path.partition("?")[2].split("&") if "=" in p)
            html = "text/html; charset=utf-8"
            if path in ("/", "/index.html") and is_home:
                other_ws = other.replace("http://", "ws://")
                other_name = other.replace("127.0.0.1", "localhost")
                page = PAGE.format(home=home, other=other, other_ws=other_ws, other_port=other.rsplit(":", 1)[1],
                                   other_name=other_name, other_name_host=other_name.split("://", 1)[1])
                self.send(200, html, page.encode())
            elif is_home and path == "/t/meta":
                top = query.get("top") == "1"
                text = ("<b>If this page stays, the meta refresh to the other origin was refused (PASS).</b> "
                        "<a href='/'>Back</a>") if top else "refreshing… (if this stays, it was refused)"
                self.send(200, html, T_META.format(other=other, where="top" if top else "frame", delay=2 if top else 1, text=text).encode())
            elif is_home and path == "/t/redirect":
                self.send(302, "text/plain", b"redirect", [("Location", f"{other}/hit/redirect-{query.get('to', 'x')}")])
            elif is_home and path == "/t/post":
                target = "_top" if query.get("target") == "_top" else "_self"
                self.send(200, html, T_POST.format(other=other, target=target, name=target.strip("_")).encode())
            elif is_home and path == "/t/link-top":
                self.send(200, html, T_LINK_TOP.format(other=other).encode())
            elif is_home and path == "/t/same-window":
                self.send(200, html, T_SAME.encode())
            elif path == "/frame.html" and is_home:
                self.send(200, html, FRAME.format(bg="#15803d", text="same-origin frame: loaded").encode())
            elif path == "/green.png" and is_home:
                self.send(200, "image/png", GREEN)
            elif path == "/download.bin" and is_home:
                self.send(200, "application/octet-stream", b"lockdown check download\n",
                          [("Content-Disposition", 'attachment; filename="lockdown-check.bin"')])
            elif not is_home and path.endswith(".png"):
                self.send(200, "image/png", RED)
            elif not is_home and path.endswith(".json"):
                self.send(200, "application/json", b'{"cross_origin": "loaded"}')
            elif not is_home and path.endswith(".bin"):
                self.send(200, "application/octet-stream", b"other origin download\n",
                          [("Content-Disposition", 'attachment; filename="other-origin.bin"')])
            elif not is_home:
                self.send(200, html, FRAME.format(bg="#b91c1c", text=f"OTHER ORIGIN REACHED: {path}").encode())
            else:
                self.send(404, "text/plain", b"not found")
    return H


class OtherServer(ThreadingHTTPServer):
    """The other origin: logs every connection too, so a preconnect that never sends a request still shows."""

    def verify_request(self, request, client_address):
        print(f"[other] LEAK: connection from {client_address[0]}", flush=True)
        return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=18125)
    a = ap.parse_args()
    home, other = f"http://127.0.0.1:{a.port}", f"http://127.0.0.1:{a.port + 1}"
    servers = [ThreadingHTTPServer(("127.0.0.1", a.port), handler(home, other, True)),
               OtherServer(("127.0.0.1", a.port + 1), handler(home, other, False))]
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print(f"lockdown check: home {home}/, other origin {other}/ (any line with LEAK is a failure)", flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
