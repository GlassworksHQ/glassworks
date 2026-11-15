#!/usr/bin/env python3
"""Are the ranges a boot image repack would drop all zero? Read-only: reads image files and prints.

  python3 -I tools/kiln/boot-padding.py <boot.img>...

For each Android boot image with header v0..v2 (AOSP bootimg.h), it prints the size of, and whether
these are all zero bytes:
- gap: from the end of the last page-aligned block to the vbmeta block (AVB footer present and
  pointing at "AVB0"), else to the footer, else to the end of the image;
- tail: from the end of the vbmeta block to the 64-byte AVB footer.
Files without the ANDROID! magic, or with header v3+, are skipped. Used by
docs/research/kiln-cleanroom/bootpatch-spec.md (the "unsupported" refusal).
"""
import pathlib
import struct
import sys


def ranges(b: bytes):
    page, version = struct.unpack_from('<II', b, 36)
    sizes = [struct.unpack_from('<I', b, o)[0] for o in (8, 16, 24)]
    if version >= 1:
        sizes.append(struct.unpack_from('<I', b, 1632)[0])
    if version >= 2:
        sizes.append(struct.unpack_from('<I', b, 1648)[0])
    end = page + sum(-(-s // page) * page for s in sizes)
    if b[-64:-60] != b'AVBf':
        return b[end:], b''
    vb_off, vb_size = struct.unpack_from('>QQ', b, len(b) - 64 + 20)
    if b[vb_off:vb_off + 4] != b'AVB0':
        return b[end:len(b) - 64], b''
    return b[end:vb_off], b[vb_off + vb_size:len(b) - 64]


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for f in sys.argv[1:]:
        b = pathlib.Path(f).read_bytes()
        if b[:8] != b'ANDROID!' or struct.unpack_from('<I', b, 40)[0] > 2:
            continue
        gap, tail = ranges(b)
        print(f'{pathlib.Path(f).name}: gap {len(gap)} bytes, all zero {not any(gap)}; '
              f'tail {len(tail)} bytes, all zero {not any(tail)}')


if __name__ == '__main__':
    main()
