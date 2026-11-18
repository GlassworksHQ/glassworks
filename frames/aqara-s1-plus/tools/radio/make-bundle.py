#!/usr/bin/env python3
"""Build and sign a frame bundle (frames/aqara-s1-plus/research/frame-loader.md, "The bundle").

    frames/aqara-s1-plus/tools/radio/make-bundle.py --frame aqara-s1-plus --frame-version 1.0.1 \\
        --key KEY.pem --out OUTDIR \\
        --file services/radio.sh=frames/aqara-s1-plus/tools/radio/glassworks_radio.sh:0700 \\
        --file otbr-agent=frames/aqara-s1-plus/tools/radio/out/otbr-agent:0700 \\
        --dir sisdk=frames/aqara-s1-plus/tools/radio/out/sisdk \\
        [--file bootanimation.zip=branding/glass-tile/bootanimation.zip] \\
        [--firmware ID=PATH-IN-BUNDLE:APP-VERSION:LABEL ...]

Writes OUTDIR/frame-bundle/{manifest,manifest.sig,<files>} (the layout the app ships as an APK asset
and the panel's glassworks_loader.sh installs). KEY.pem is the project's Ed25519 private key (PKCS#8
PEM, GitHub secret S1_RADIO_BUNDLE_KEY in CI); it is only read by `openssl pkeyutl -sign`. --pubkey
FILE checks the result against a public key (frames/aqara-s1-plus/tools/root/glassworks_bundle.pub).

The manifest format is the one glassworks_loader.sh parses (its header). The frame id and version are
inside the signed text, so a bundle only loads on its own frame. Panels refuse a version below the
highest they ever loaded, so a typo such as 10.0.0 for 1.0.0 blocks every later bundle: take the
version from the frame's one source of truth, never by hand. The script refuses to package Silicon
Labs' zigbeed binary (MSLA object code, never redistributed by this project: the panel downloads it
from Silicon Labs). Only the standard library and an OpenSSL 3 command line are needed.

Transition (#528), remove with the s1kiosk names: with --version (and --app-version) instead of
--frame, it writes the old radio bundle (OUTDIR/radio-bundle, "S1RADIO-BUNDLE 1", version = the app's
versionCode) that the s1kiosk_bundle.sh of 0.4.3 boot images installs.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

PATH_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]*(/[A-Za-z0-9][A-Za-z0-9._+-]*){0,4}$")
FRAME_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
SEMVER_RE = re.compile(r"^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$")
MODES = {"0600", "0644", "0700", "0755"}
FW_ID_RE = re.compile(r"^[a-z0-9-]{1,16}$")
APP_RE = re.compile(r"^[A-Za-z0-9._-]{1,16}$")
# Silicon Labs zigbeed 8.2.2 armhf (frames/aqara-s1-plus/tools/radio/build-zigbeed.sh): must never end up in a bundle.
MSLA_SHA256 = {"a66d45842a770e1c449a55208a6d2a3b36786eb1c94bf10d3c3aac317274e3bb"}
MAX_FILES = 200
MAX_TOTAL = 96 * 1024 * 1024
MAX_MANIFEST = 65536  # glassworks_loader.sh and s1kiosk_bundle.sh refuse a longer manifest


def die(msg):
    sys.exit(f"make-bundle: {msg}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def good_path(p):
    return bool(PATH_RE.match(p)) and len(p) <= 160 and p not in ("manifest", "manifest.sig")


def is_msla(path, rel):
    if sha256(path) in MSLA_SHA256:
        return True
    # any ELF called zigbeed (a different build would still be Silicon Labs' object code)
    if os.path.basename(rel) == "zigbeed":
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    return False


def openssl():
    for c in (os.environ.get("OPENSSL"), "openssl", "/opt/local/bin/openssl", "/opt/homebrew/opt/openssl@3/bin/openssl"):
        if not c:
            continue
        try:
            out = subprocess.run([c, "version"], capture_output=True, text=True, check=True).stdout
        except (OSError, subprocess.CalledProcessError):
            continue
        if out.startswith("OpenSSL 3"):
            return c
    die("need an OpenSSL 3 command line (set OPENSSL=<path>)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--frame", help="the frame id the bundle is for, e.g. aqara-s1-plus")
    ap.add_argument("--frame-version", help="the frame version, MAJOR.MINOR.PATCH (each 0..999)")
    ap.add_argument("--version", type=int, help="old radio bundle only (#528 transition): the app's versionCode")
    ap.add_argument("--app-version", help="old radio bundle only: the APK's MAJOR.MINOR.PATCH; the version must not exceed its versionCode")
    ap.add_argument("--force", action="store_true", help="old radio bundle only: skip the --app-version check (tests, emergencies)")
    ap.add_argument("--key", required=True, help="Ed25519 private key, PKCS#8 PEM")
    ap.add_argument("--out", required=True)
    ap.add_argument("--file", action="append", default=[], help="BUNDLE-PATH=SOURCE[:MODE]")
    ap.add_argument("--dir", action="append", default=[], help="BUNDLE-PREFIX=SOURCE-DIR (modes kept: 0755 if executable else 0644)")
    ap.add_argument("--firmware", action="append", default=[], help="ID=PATH-IN-BUNDLE:APP-VERSION:LABEL")
    ap.add_argument("--created", default=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    ap.add_argument("--pubkey", help="verify the signature with this public key file (64 hex digits)")
    a = ap.parse_args()

    if a.frame is None and a.frame_version is not None:
        die("--frame-version needs --frame")
    if a.frame is not None:
        if a.version is not None or a.app_version is not None:
            die("--frame and --version are two formats: give one")
        if not FRAME_RE.fullmatch(a.frame):
            die(f"bad frame id {a.frame!r}")
        m = SEMVER_RE.fullmatch(a.frame_version or "")
        if not m:
            die(f"bad frame version {a.frame_version!r}: MAJOR.MINOR.PATCH, each 0..999")
        version = int(m[1]) * 1_000_000 + int(m[2]) * 1_000 + int(m[3])
        if version == 0:
            die("frame version 0.0.0 is not allowed")
        head = ["GLASSWORKS-BUNDLE 1", f"frame {a.frame} {a.frame_version}", f"version {version}"]
        out_name, what = "frame-bundle", f"frame bundle {a.frame} {a.frame_version}"
    else:
        # The old radio bundle (#528 transition): remove with the s1kiosk names.
        if a.version is None:
            die("--frame and --frame-version are required")
        if not 0 < a.version <= 999_999_999:
            die("version must be 1..999999999")
        # Panels never accept a lower version than the highest they ever installed, so a typo like 3020000
        # instead of 30200 would block every later bundle. The version is the app's versionCode.
        if not a.force:
            m = re.match(r"^(\d{1,4})\.(\d{1,2})\.(\d{1,2})$", a.app_version or "")
            if not m:
                die("--app-version MAJOR.MINOR.PATCH is required (or --force)")
            code = int(m[1]) * 10000 + int(m[2]) * 100 + int(m[3])
            if a.version > code:
                die(f"version {a.version} is above the app's versionCode {code}: panels would refuse every later bundle (--force to override)")
        version = a.version
        head = ["S1RADIO-BUNDLE 1", f"version {version}"]
        out_name, what = "radio-bundle", f"radio bundle {version}"
    files = {}  # bundle path -> (source, mode)
    for spec in a.file:
        dst, _, src = spec.partition("=")
        mode = "0644"
        if re.search(r":0[67][0-7][0-7]$", src):
            src, mode = src[:-5], src[-4:]
        files[dst] = (src, mode)
    for spec in a.dir:
        prefix, _, src = spec.partition("=")
        if not os.path.isdir(src):
            die(f"{src}: not a directory")
        for root, dirs, names in os.walk(src):
            dirs.sort()
            for n in sorted(names):
                p = os.path.join(root, n)
                rel = prefix + "/" + os.path.relpath(p, src).replace(os.sep, "/")
                files[rel] = (p, "0755" if os.access(p, os.X_OK) else "0644")

    if not files:
        die("no files")
    if len(files) > MAX_FILES:
        die("too many files")
    total = 0
    lines = [*head, f"created {a.created}"]
    for dst in sorted(files):
        src, mode = files[dst]
        if not good_path(dst) or (out_name == "radio-bundle" and dst.count("/") > 3):
            die(f"bad bundle path {dst!r}")
        if mode not in MODES:
            die(f"{dst}: mode {mode} not allowed")
        if not os.path.isfile(src) or os.path.islink(src):
            die(f"{src}: not a regular file")
        if is_msla(src, dst):
            die(f"{src}: that is Silicon Labs' zigbeed (MSLA): it is never bundled; the panel downloads it")
        size = os.path.getsize(src)
        total += size
        lines.append(f"file {mode} {size} {sha256(src)} {dst}")
    if total > MAX_TOTAL:
        die("bundle too big")
    for spec in a.firmware:
        fid, _, rest = spec.partition("=")
        path, app, label = (rest.split(":", 2) + ["", ""])[:3]
        if not FW_ID_RE.match(fid) or fid == "stock":
            die(f"bad firmware id {fid!r}")
        if path not in files:
            die(f"firmware {fid}: {path} is not one of the files")
        if not APP_RE.match(app):
            die(f"firmware {fid}: bad APP version {app!r}")
        label = re.sub(r"\s+", " ", label).strip() or fid
        lines.append(f"firmware {fid} {path} {app} {label}")
    lines.append("end")
    manifest = ("\n".join(lines) + "\n").encode()
    if len(manifest) > MAX_MANIFEST:
        die(f"manifest is {len(manifest)} bytes; panels refuse more than {MAX_MANIFEST}")
    for l in lines:
        if "\n" in l or "\r" in l:
            die(f"manifest line {l!r} spans lines (a line break in --created?)")
        if "  " in l or l != l.strip():
            die(f"manifest line {l!r} has stray spaces")

    out = os.path.join(a.out, out_name)
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    for dst, (src, mode) in files.items():
        d = os.path.join(out, dst)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copyfile(src, d)
        os.chmod(d, int(mode, 8))
    with open(os.path.join(out, "manifest"), "wb") as f:
        f.write(manifest)

    ossl = openssl()
    with tempfile.TemporaryDirectory() as t:
        sig_raw = os.path.join(t, "sig")
        subprocess.run([ossl, "pkeyutl", "-sign", "-inkey", a.key, "-rawin", "-in", os.path.join(out, "manifest"),
                        "-out", sig_raw], check=True)
        sig = open(sig_raw, "rb").read()
        if len(sig) != 64:
            die("openssl produced no Ed25519 signature (is the key Ed25519?)")
        with open(os.path.join(out, "manifest.sig"), "w") as f:
            f.write(sig.hex() + "\n")
        if a.pubkey:
            pub = bytes.fromhex(open(a.pubkey).read().strip())
            if len(pub) != 32:
                die(f"{a.pubkey}: not a 32-byte public key")
            # SubjectPublicKeyInfo DER for Ed25519 = fixed 12-byte prefix + the key
            der = bytes.fromhex("302a300506032b6570032100") + pub
            pem = os.path.join(t, "pub.der")
            open(pem, "wb").write(der)
            r = subprocess.run([ossl, "pkeyutl", "-verify", "-pubin", "-inkey", pem, "-keyform", "DER", "-rawin",
                                "-in", os.path.join(out, "manifest"), "-sigfile", sig_raw], capture_output=True, text=True)
            if r.returncode != 0:
                die(f"the signature does not verify with {a.pubkey}: wrong key? {r.stdout.strip()} {r.stderr.strip()}")
    print(f"{what}: {len(files)} files, {total} bytes -> {out}")
    print(manifest.decode(), end="")


if __name__ == "__main__":
    main()
