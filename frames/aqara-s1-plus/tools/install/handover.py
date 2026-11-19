#!/usr/bin/env python3
"""Talks to Glassworks' secret hand-over socket (core SecretHandover, @glassworks_api_key) through an adb forward.

    adb forward tcp:0 localabstract:glassworks_api_key        # prints the local port
    printf 'get-signin\\n' | handover.py <port> [seconds]      # one reply line per request line
    printf '%s' "$base64" | handover.py --fingerprint          # the 4-hex fingerprint the app logs

The app hands out and takes back its Home Assistant sign-in and ESPHome API key there, for install.sh's signing-key
switch (#584). Requests come on stdin, never as arguments, because a request can carry a secret, and so can a reply:
the caller keeps replies in memory and never prints them. Touches nothing on the panel but that socket, which only adb
can open. Exit status: 0 every request had a reply, 1 the connection failed or a reply didn't come in time (10 s, or
the seconds given).
"""
import base64
import hashlib
import socket
import sys


def fingerprint(b64):
    """NoiseKeys.fingerprint: the first two bytes of the SHA-256 of the secret's bytes, in hex. Reveals nothing."""
    return hashlib.sha256(base64.b64decode(b64.strip(), validate=True)).hexdigest()[:4]


def main():
    if sys.argv[1:] == ["--fingerprint"]:
        print(fingerprint(sys.stdin.read()))
        return 0
    if len(sys.argv) not in (2, 3) or not all(a.isdigit() for a in sys.argv[1:]):
        print("usage: handover.py <port> [seconds] | --fingerprint  (requests or the secret on stdin)", file=sys.stderr)
        return 2
    timeout = max(1, int(sys.argv[2])) if len(sys.argv) == 3 else 10
    requests = [line.strip() for line in sys.stdin.read().splitlines() if line.strip()]
    try:
        with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=timeout) as s:
            f = s.makefile("rwb")
            for r in requests:
                f.write(r.encode("ascii") + b"\n")
                f.flush()
                reply = f.readline()
                if not reply.endswith(b"\n"):
                    print("handover: the app didn't answer", file=sys.stderr)
                    return 1
                sys.stdout.write(reply.decode("ascii").strip() + "\n")
                sys.stdout.flush()
    except (OSError, UnicodeError) as e:
        # the error's class only: never a request, which may hold a secret
        print(f"handover: no connection to the app ({type(e).__name__})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
