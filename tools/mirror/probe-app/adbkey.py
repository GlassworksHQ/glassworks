#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 aguilaair
"""adbkey.py: a throwaway adb key pair for the Mirror probe's allow-adb-key test (#29).

Touches no device and no network. Needs only Python 3 and the openssl CLI.

  adbkey.py new [DIR]           writes DIR/adbkey (private, 0600) and DIR/adbkey.pub in the formats
                                adb uses in ~/.android; DIR defaults to out/adbkey-test here
                                (git-ignored). Refuses to overwrite a key.
  adbkey.py fingerprint PUB     prints the public key's fingerprints, never the key
  adbkey.py fingerprints FILE   one SHA-256 fingerprint per key line of FILE (- for stdin), e.g. the
                                Mirror's /data/misc/adb/adb_keys read over adb
  adbkey.py check KEY PUB       whether PUB is KEY's public half, in adb's format ("match" or "differ")

Only fingerprints are printed: the SHA-256 prefix the probe logs, and the MD5 form Android's
UsbDebuggingManager logs ("Fingerprints do not match. Got …"). Never commit a key.
"""
import base64
import hashlib
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMMENT = "probe-test@glassworks"  # adb writes user@host here; a made-up one


def adb_public(private_key_path):
    """adb's public key line for an RSA private key: RSAPublicKey (len, n0inv, n, rr, exponent) as base64."""
    out = subprocess.run(["openssl", "rsa", "-in", private_key_path, "-noout", "-modulus"],
                         check=True, capture_output=True, text=True).stdout
    n = int(re.search(r"Modulus=([0-9A-F]+)", out).group(1), 16)
    if n.bit_length() != 2048:
        raise SystemExit("adb keys are 2048-bit RSA")
    words = 64
    n0inv = (-pow(n % 2**32, -1, 2**32)) % 2**32
    rr = pow(2, 2 * 2048, n)
    blob = (words.to_bytes(4, "little") + n0inv.to_bytes(4, "little") + n.to_bytes(256, "little")
            + rr.to_bytes(256, "little") + (65537).to_bytes(4, "little"))
    return base64.b64encode(blob).decode()


def fingerprints(pub_line):
    key = base64.b64decode(pub_line.split()[0])
    sha = "sha256:" + hashlib.sha256(key).hexdigest()[:16]
    md5 = ":".join(f"{b:02X}" for b in hashlib.md5(key).digest())
    return sha, md5


def ignored_or_outside_repo(path):
    repo = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()
    real = os.path.realpath(path)
    if not repo or not real.startswith(os.path.realpath(repo) + os.sep):
        return True
    return subprocess.run(["git", "-C", repo, "check-ignore", "-q", real]).returncode == 0


def main(argv):
    if len(argv) >= 1 and argv[0] == "new":
        d = argv[1] if len(argv) > 1 else os.path.join(HERE, "out", "adbkey-test")
        os.makedirs(d, exist_ok=True)
        if not ignored_or_outside_repo(os.path.join(d, "adbkey")):
            raise SystemExit(f"{d} is in the repo and not git-ignored: a key must never be committed")
        key, pub = os.path.join(d, "adbkey"), os.path.join(d, "adbkey.pub")
        if os.path.exists(key) or os.path.exists(pub):
            raise SystemExit(f"{d} already holds a key; delete it first, or use it")
        old = os.umask(0o077)
        try:
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key],
                           check=True, capture_output=True)
        finally:
            os.umask(old)
        with open(pub, "w") as f:
            f.write(f"{adb_public(key)} {COMMENT}\n")
        sha, md5 = fingerprints(open(pub).read())
        print(f"new throwaway adb key in {d}\n  {sha}\n  md5 {md5}")
    elif len(argv) == 2 and argv[0] == "fingerprint":
        sha, md5 = fingerprints(open(argv[1]).read())
        print(f"{sha}\nmd5 {md5}")
    elif len(argv) == 2 and argv[0] == "fingerprints":
        lines = (sys.stdin if argv[1] == "-" else open(argv[1])).read().splitlines()
        for line in (l.strip() for l in lines):
            if line:
                print(fingerprints(line)[0])
    elif len(argv) == 3 and argv[0] == "check":
        print("match" if adb_public(argv[1]) == open(argv[2]).read().split()[0] else "differ")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
