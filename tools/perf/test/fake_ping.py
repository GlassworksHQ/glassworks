#!/usr/bin/env python3
"""A stand-in for `ping`, for netcheck.py's tests (#649): answers `-c N` with N replies, as macOS's ping prints
them. Their times: FAKE_PING_GATEWAY_MS or FAKE_PING_PANEL_MS (comma-separated, repeated to N; the panel is the
fake's 192.0.2.10, anything else the gateway), else 3 ms each. FAKE_PING_SILENT names a host that never answers."""
import os
import sys

args = sys.argv[1:]
host, n = args[-1], int(args[args.index("-c") + 1])
print(f"PING {host} ({host}): 56 data bytes")
if host != os.environ.get("FAKE_PING_SILENT"):
    raw = os.environ.get("FAKE_PING_PANEL_MS" if host == "192.0.2.10" else "FAKE_PING_GATEWAY_MS") or "3"
    times = [float(x) for x in raw.split(",")]
    for i in range(n):
        print(f"64 bytes from {host}: icmp_seq={i} ttl=64 time={times[i % len(times)]:.3f} ms")
print(f"--- {host} ping statistics ---")
