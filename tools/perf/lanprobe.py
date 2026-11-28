#!/usr/bin/env python3
"""How the panel's camera video reaches this computer's WebRTC peer (MIT, #202, option B).

Real setups play WebRTC over the LAN, UDP between the browser and go2rtc. Through `adb reverse` the
video shares one USB link with everything else, and on the S1's heavy view it stalled there or on
the busy panel (#202, smokes 4 and 5). So before the graded part, run.sh asks the panel whether it
can reach this computer over the network, and picks:

1. `lan-udp`: the panel's datagram reached this computer. The browser and the peer then pair over
   UDP on the LAN, as a real setup does (no relay, no TCP).
2. `lan-turn`: UDP didn't arrive but a TCP connection did (a network or firewall that drops UDP).
   The same relay as with adb, over TCP to this computer's address.
3. `adb`: neither. TURN over TCP through adb reverse, the same on any network.

What it does on the panel: two `toybox nc` commands over `adb shell`, run as adb's shell user:
one sends a datagram with a random token to this computer's media address and port, one opens a TCP
connection there and sends the token. **These are connections from the panel**, to this computer
only; nothing on the panel is written or changed. On this computer it listens on that address and
port for a few seconds, before the fake starts. It records no addresses.

With --ha-over-lan (run.sh --ha-transport lan: the panel reaches the fake Home Assistant over the LAN, for a device
whose `adb reverse` doesn't work) there is no adb path: auto picks `lan-udp` when the datagram arrived, else
`lan-turn`, the relay over TCP as the fake's own pages go (Android 6 has no `toybox nc`, so it can't be asked).

  tools/perf/lanprobe.py --port 8090 --out DIR [--transport auto|adb|lan-turn|lan-udp] [--ha-over-lan]

Writes DIR/transport.json ({transport, chosen_by, reason, checks}) and prints the transport.
Run it with the Python the fake runs under (the computer's firewall decides per program).
"""
import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clock as C  # noqa: E402
import webrtc  # noqa: E402

ADB = os.environ.get("ADB", "adb")


def panel(cmd, timeout=15):
    """One `adb shell` command on the panel; (exit status, output). Never raises."""
    try:
        p = subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, str(e)


def udp_check(addr, port, wait=4.0):
    """The panel sends a datagram with a token to addr:port; True if it arrived."""
    token = secrets.token_hex(8)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((addr, port))
        s.settimeout(0.25)
        # Three lines in one go: one datagram may be lost on Wi-Fi.
        status, out = panel(f"for i in 1 2 3; do echo {token}; sleep 0.2; done | toybox nc -u -w 2 -q 1 {addr} {port}")
        end = time.monotonic() + C.real(wait)
        while time.monotonic() < end:
            try:
                data, _ = s.recvfrom(2048)
            except socket.timeout:
                continue
            if token.encode() in data:
                return True, status, out
    return False, status, out


def tcp_check(addr, port, wait=4.0):
    """The panel opens a TCP connection to addr:port and sends a token; True if it arrived."""
    token = secrets.token_hex(8)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((addr, port))
        s.listen(1)
        s.settimeout(C.real(wait))
        got = {}

        def accept():
            try:
                c, _ = s.accept()
                with c:
                    c.settimeout(2)
                    got["data"] = c.recv(256)
            except OSError:
                pass
        import threading
        th = threading.Thread(target=accept, daemon=True)
        th.start()
        status, out = panel(f"echo {token} | toybox nc -w 2 -q 1 {addr} {port}")
        th.join(C.real(wait))
        return token.encode() in got.get("data", b""), status, out


# With the fake Home Assistant over the LAN: why the relay over TCP, when the panel's datagram didn't arrive.
HA_LAN_TURN = ("UDP from the panel didn't arrive (or it has no toybox nc to send it), and adb reverse isn't used: "
               "the relay over TCP on the LAN, as the fake Home Assistant's pages go")


def choose(port, transport="auto", ha_over_lan=False):
    """{transport, chosen_by, reason, checks}; never raises. ha_over_lan: no adb path, so never `adb`."""
    if transport != "auto":
        return {"transport": transport, "chosen_by": "flag", "reason": "set with --webrtc-transport", "checks": {}}
    if ha_over_lan:
        got = choose(port)
        if got["transport"] == "lan-udp":
            return got
        # The TCP check needs toybox nc too; the fake's own pages over the LAN show TCP gets through.
        return dict(got, transport="lan-turn", reason=HA_LAN_TURN)
    try:
        addr = webrtc.media_address()
    except Exception as e:     # no usable IPv4 address here: nothing to reach over the LAN
        return {"transport": "adb", "chosen_by": "auto", "checks": {},
                "reason": f"this computer has no LAN address the panel could use ({e})"}
    checks = {}
    try:
        ok, status, out = udp_check(addr, port)
    except OSError as e:
        ok, status, out = False, None, f"couldn't listen here: {e}"
    checks["udp"] = {"reached": ok, "nc_status": status}
    if ok:
        return {"transport": "lan-udp", "chosen_by": "auto", "checks": checks,
                "reason": "the panel reached this computer over UDP on the LAN, as a real setup's video goes"}
    if status not in (0, 1) and "not found" in out:
        checks["udp"]["note"] = "the panel has no toybox nc"
    try:
        ok, status, out = tcp_check(addr, port)
    except OSError as e:
        ok, status, out = False, None, f"couldn't listen here: {e}"
    checks["tcp"] = {"reached": ok, "nc_status": status}
    if ok:
        return {"transport": "lan-turn", "chosen_by": "auto", "checks": checks,
                "reason": "UDP from the panel didn't arrive, a TCP connection did: the relay over the LAN"}
    return {"transport": "adb", "chosen_by": "auto", "checks": checks,
            "reason": "the panel couldn't reach this computer over the LAN: TURN over TCP through adb reverse"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, required=True, help="the relay's port (--port + 1 in run.sh)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--transport", default="auto", choices=("auto",) + webrtc.TRANSPORTS)
    ap.add_argument("--ha-over-lan", action="store_true", help="the fake Home Assistant goes over the LAN: no adb path")
    a = ap.parse_args()
    if a.ha_over_lan and a.transport == "adb":
        ap.error("--transport adb goes through adb reverse, which --ha-over-lan doesn't use: lan-turn, lan-udp or auto")
    C.check_device(lambda name: panel(f"getprop {name}")[1])
    got = choose(a.port, a.transport, a.ha_over_lan)
    with open(os.path.join(a.out, "transport.json"), "w") as f:
        json.dump(got, f, indent=1)
        f.write("\n")
    print(got["transport"])


if __name__ == "__main__":
    main()
