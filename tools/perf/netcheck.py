#!/usr/bin/env python3
"""The test computer's own network, before a Clarity run (MIT, #649).

The fake Home Assistant runs on this computer. Its live changes reach the panel through `adb reverse`, and over
wireless adb that crosses this computer's network link; the cameras' video over the LAN (lanprobe.py's lan-udp
and lan-turn) does on any panel. A real Home Assistant server sits on a wired or quiet link. A computer whose own
Wi-Fi stalls now and then (macOS: AWDL, power save) adds its stalls to the panel's numbers: on 2026-11-19 a Mac on
Wi-Fi reached its gateway in 2.3 ms at best and 68 to 84 ms at worst, while the Pixel Tablet reached the same
gateway in 9.7 ms at worst, and run 3's live changes waited 65 to 91 ms (p95) to arrive.

What it does: 40 pings from this computer to its default gateway and 40 to the panel, at the same time (8 s),
and reads how adb reaches the panel (`adb devices -l`) and the panel's address (`ip -4 -o addr show` over adb
shell, read-only). Nothing on the panel changes. It records numbers only, never an address or the serial.

  tools/perf/netcheck.py --out DIR [--count 40] [--ha-over-lan]

--ha-over-lan (run.sh --ha-transport lan): the fake Home Assistant reaches the panel over the LAN, not adb reverse, so
its live updates cross this computer's network whatever adb goes over.

Writes DIR/netcheck.json:
  gateway, panel     the pings' sent, received, min, median, p95 and max (ms); null when not measured
  link               this computer's link to the gateway: "wifi", "wired" or null (unknown)
  adb_over_network   whether adb reaches the panel over the network (wireless debugging), not USB
  ha_over_lan        whether the fake Home Assistant went over the LAN (--ha-over-lan), not through adb reverse
  quiet              whether this computer's own link (the gateway's pings) stays at or under NET_TAIL_MS at
                     p95 and at most; null when the gateway didn't answer
report.py marks the grade provisional when it isn't quiet and it carried graded traffic. It never fails the run.
GW_PERF_PING names another ping and GW_PERF_GATEWAY the gateway (the off-device tests' fakes).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import perflib as P  # noqa: E402

ADB = os.environ.get("ADB", "adb")
# This computer's own link is quiet when its pings to the gateway stay at or under this, at p95 and at most:
# a wired link, or a quiet Wi-Fi. The Pixel Tablet's own Wi-Fi stayed under 10 ms (2026-11-19).
NET_TAIL_MS = 20
INTERVAL_S = 0.2           # the shortest ping interval macOS and Linux allow without root
TIME_RE = re.compile(r"time[=<]\s*([\d.]+)\s*ms")
ADVICE = ("put this computer on a wired link for the run, or on macOS turn AWDL off for it "
          "(`sudo ifconfig awdl0 down`; `sudo ifconfig awdl0 up` after, and AirDrop or Sidecar may turn it back on)")


def parse_ping(text):
    """Round-trip times (ms) of each reply in ping's output, macOS's or Linux's ("time=3.14 ms", "time<1 ms")."""
    return [float(m.group(1)) for m in TIME_RE.finditer(text)]


def ping_stats(rtts, sent):
    """sent, received and the round trips' min, median, p95 and max (ms); None when nothing came back."""
    if not rtts:
        return None
    xs = sorted(rtts)
    return {"sent": sent, "received": len(xs), "min_ms": round(xs[0], 1), "median_ms": round(P.nearest_rank(xs, 0.5), 1),
            "p95_ms": round(P.nearest_rank(xs, 0.95), 1), "max_ms": round(xs[-1], 1)}


def ping(host, count):
    """`ping -c count -i 0.2 host` -> its round trips (ms), [] when none came back or ping failed."""
    exe = os.environ.get("GW_PERF_PING") or shutil.which("ping")
    if not host or not exe:
        return []
    try:
        out = subprocess.run([exe, "-c", str(count), "-i", str(INTERVAL_S), host], capture_output=True, text=True,
                             timeout=count * INTERVAL_S + 20).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_ping(out)


def parse_default_route(text):
    """(gateway, interface) from macOS's `route -n get default` or Linux's `ip route show default`."""
    gw = re.search(r"gateway:\s*(\S+)", text) or re.search(r"default via (\S+)", text)
    dev = re.search(r"interface:\s*(\S+)", text) or re.search(r"\bdev (\S+)", text)
    return (gw.group(1) if gw else None), (dev.group(1) if dev else None)


def default_route():
    if os.environ.get("GW_PERF_GATEWAY"):
        return os.environ["GW_PERF_GATEWAY"], None          # the off-device tests' gateway
    for cmd in (["route", "-n", "get", "default"], ["ip", "route", "show", "default"]):
        try:
            gw, dev = parse_default_route(subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout)
        except (OSError, subprocess.TimeoutExpired):
            continue
        if gw:
            return gw, dev
    return None, None


def parse_hardware_ports(text, iface):
    """macOS's `networksetup -listallhardwareports` -> "wifi" or "wired" for this interface, or None."""
    for port, dev in re.findall(r"Hardware Port:\s*(.+?)\s*\nDevice:\s*(\S+)", text):
        if dev == iface:
            return "wifi" if re.search(r"Wi-?Fi|AirPort", port, re.I) else "wired"
    return None


def link_kind(iface):
    """Whether this computer reaches its gateway over Wi-Fi or a wire (None: can't tell)."""
    if not iface:
        return None
    if os.path.isdir(f"/sys/class/net/{iface}"):
        return "wifi" if os.path.isdir(f"/sys/class/net/{iface}/wireless") else "wired"
    try:
        out = subprocess.run(["networksetup", "-listallhardwareports"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    return parse_hardware_ports(out, iface)


# A device adb reaches over the network: `host:port` (adb connect, or wireless debugging by address) or an mDNS
# service name (wireless debugging, `adb-<serial>-<id>._adb-tls-connect._tcp`). Not the absence of a `usb:` field:
# adb's libusb backend lists USB devices without one too (CodeRabbit on #650, from adb's manual).
NETWORK_SERIAL_RE = re.compile(r"^\S+:\d+$|\._adb(-tls-connect)?\._tcp\.?$")


def parse_adb_devices(text, serial):
    """From `adb devices -l`: True when the device reaches adb over the network (its serial is an address or an
    mDNS name), False otherwise (USB, an emulator), None when it isn't listed. `serial` is adb's (ANDROID_SERIAL),
    or None for the only device."""
    rows = [l.split() for l in text.splitlines()[1:] if l.strip() and not l.startswith("*")]
    rows = [r for r in rows if len(r) > 1 and r[1] == "device"]
    row = next((r for r in rows if r[0] == serial), None) if serial else (rows[0] if len(rows) == 1 else None)
    if row is None:
        return None
    return bool(NETWORK_SERIAL_RE.search(row[0]))


def parse_panel_address(text):
    """The panel's first IPv4 address that isn't loopback, from `ip -4 -o addr show`."""
    for m in re.finditer(r"inet (\d+\.\d+\.\d+\.\d+)/\d+", text):
        if not m.group(1).startswith("127."):
            return m.group(1)
    return None


def adb(*args):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


def quiet(rtts):
    """This computer's own link is quiet: its gateway's round trips (ms, as measured: the report's are rounded) at
    or under NET_TAIL_MS at p95 and at most. None when none came back."""
    if not rtts:
        return None
    xs = sorted(rtts)
    return P.nearest_rank(xs, 0.95) <= NET_TAIL_MS and xs[-1] <= NET_TAIL_MS


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=40)
    ap.add_argument("--ha-over-lan", action="store_true", help="the fake Home Assistant goes over the LAN, not adb reverse")
    a = ap.parse_args()
    gw, iface = default_route()
    panel = parse_panel_address(adb("shell", "ip -4 -o addr show"))
    results = {}

    def run(name, host):
        results[name] = ping(host, a.count)
    threads = [threading.Thread(target=run, args=(n, h)) for n, h in (("gateway", gw), ("panel", panel))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    out = {"gateway": ping_stats(results.get("gateway"), a.count), "panel": ping_stats(results.get("panel"), a.count),
           "link": link_kind(iface),
           "adb_over_network": parse_adb_devices(adb("devices", "-l"), os.environ.get("ANDROID_SERIAL")),
           "tail_ms": NET_TAIL_MS}
    if a.ha_over_lan:
        out["ha_over_lan"] = True       # only then, so a run through adb reverse writes the file as before
    out["quiet"] = quiet(results.get("gateway"))
    with open(os.path.join(a.out, "netcheck.json"), "w") as f:
        json.dump(out, f, indent=1)
    g, p = out["gateway"], out["panel"]
    fmt = lambda s: f"p95 {s['p95_ms']} ms, max {s['max_ms']} ms" if s else "no answer"  # noqa: E731
    print(f"this computer's network ({out['link'] or 'link unknown'}): to its gateway {fmt(g)}; to the panel {fmt(p)}",
          file=sys.stderr)
    if out["quiet"] is False:
        print(f"warning: this computer's own link to its gateway goes over {NET_TAIL_MS} ms (p95 or max). Its stalls add "
              f"to the panel's live updates and cameras where they cross it, and the report will be provisional: {ADVICE}",
              file=sys.stderr)


if __name__ == "__main__":
    main()
