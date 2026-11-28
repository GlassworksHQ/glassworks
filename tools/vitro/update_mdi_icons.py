#!/usr/bin/env python3
"""Regenerates Vitro's bundled Material Design Icons catalog (MIT; the icons are Apache-2.0).

Downloads the official `@mdi/svg` npm tarball (Pictogrammers, Apache-2.0) from registry.npmjs.org and
writes every icon's 24x24 path data as one deterministic, gzipped `name<TAB>pathData` table:

  engines/engine-vitro/src/main/assets/vitro-mdi.tsv.gz

That file is the only thing it writes. It touches no device and no Home Assistant, needs no credentials,
and reads nothing else from this repo. The output is sorted by name and gzipped with mtime=0 and no
file name, so the same version always gives the same bytes (check with `git diff --exit-code`).

  python3 tools/vitro/update_mdi_icons.py              # 7.4.47, the version THIRD-PARTY-NOTICES.md names
  python3 tools/vitro/update_mdi_icons.py --version 7.4.47 --tarball ~/Downloads/svg-7.4.47.tgz

A new version also needs its number in THIRD-PARTY-NOTICES.md and in MdiIcons.kt's comment.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "engines/engine-vitro/src/main/assets/vitro-mdi.tsv.gz"
DEFAULT_VERSION = "7.4.47"
REGISTRY = "https://registry.npmjs.org/@mdi/svg/-/svg-{version}.tgz"

NAME = re.compile(r"^package/svg/([a-z0-9-]+)\.svg$")
PATH_D = re.compile(r'<path[^>]*\sd="([^"]+)"')


def fetch(version: str) -> bytes:
    url = REGISTRY.format(version=version)
    req = urllib.request.Request(url, headers={"User-Agent": "glassworks-update-mdi-icons"})
    with urllib.request.urlopen(req, timeout=60) as res:  # noqa: S310 (fixed https URL)
        return res.read()


def icons_from_tarball(data: bytes) -> dict[str, str]:
    icons: dict[str, str] = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar:
            m = NAME.match(member.name)
            if not m or not member.isfile():
                continue
            f = tar.extractfile(member)
            if f is None:
                continue
            svg = f.read().decode("utf-8")
            paths = PATH_D.findall(svg)
            if len(paths) != 1:
                raise SystemExit(f"{member.name}: expected one <path d=…>, found {len(paths)}")
            d = " ".join(paths[0].split())
            if "\t" in d or "\n" in d or not d.isascii():
                raise SystemExit(f"{member.name}: path data has characters the TSV can't hold")
            icons[m.group(1)] = d
    if not icons:
        raise SystemExit("no icons found in the tarball")
    return icons


def build(icons: dict[str, str]) -> bytes:
    tsv = "".join(f"{name}\t{icons[name]}\n" for name in sorted(icons)).encode("ascii")
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as gz:
        gz.write(tsv)
    return buf.getvalue()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--version", default=DEFAULT_VERSION, help=f"@mdi/svg version (default {DEFAULT_VERSION})")
    ap.add_argument("--tarball", type=Path, help="use this downloaded svg-<version>.tgz instead of the registry")
    ap.add_argument("--out", type=Path, default=OUT, help="where to write (default: the engine's asset)")
    args = ap.parse_args(argv)

    data = args.tarball.read_bytes() if args.tarball else fetch(args.version)
    icons = icons_from_tarball(data)
    out = build(icons)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(out)
    print(
        f"@mdi/svg {args.version}: {len(icons)} icons -> {args.out} "
        f"({len(out)} bytes, sha256 {hashlib.sha256(out).hexdigest()[:16]}…)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
