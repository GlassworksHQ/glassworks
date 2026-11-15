#!/usr/bin/env python3
"""What one of our source files shares with an upstream source tree. Read-only: it reads files and
prints, nothing else.

  python3 -I tools/licence-audit/overlap.py <our file> <upstream dir or file>...

It prints three counts:
- code lines: lines of our file (whitespace collapsed, 24 characters or more, not comments) that
  appear verbatim in the upstream tree;
- hex words: distinct 32-bit hex constants (0x12345678) found in both (a set, so order and repeats
  are not compared);
- strings: string literals of 12 characters or more from our file that occur anywhere upstream.

A translation from C or Rust to TypeScript shares no lines, so read the strings and a side-by-side
comparison too. Upstream files read: .c .h .S .rs .cpp .hpp .sh. Used by docs/research/kiln-gpl-code.md
and the clean-room check in #355.
"""
import pathlib
import re
import sys


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    ours = pathlib.Path(sys.argv[1]).read_text()
    paths = []
    for a in sys.argv[2:]:
        p = pathlib.Path(a)
        paths += [p] if p.is_file() else [q for q in p.rglob('*') if q.is_file()]
    up = ''.join(p.read_text(errors='ignore') for p in paths if p.suffix in {'.c', '.h', '.S', '.rs', '.cpp', '.hpp', '.sh'})

    norm = lambda s: re.sub(r'\s+', ' ', s).strip()
    up_lines = {norm(line) for line in up.splitlines()}
    lines = [norm(line) for line in ours.splitlines()
             if len(norm(line)) >= 24 and not norm(line).startswith(('//', '*', '/*', '#'))]
    same = [line for line in lines if line in up_lines]
    hexw = set(re.findall(r'0x[0-9a-f]{8}', ours.lower()))
    uphex = set(re.findall(r'0x[0-9a-f]{8}', up.lower()))
    strs = sorted(set(re.findall(r"""['"`]([^'"`\n${}]{12,})['"`]""", ours)))
    shared = [s for s in strs if s in up]

    print(f'{sys.argv[1]}: code lines {len(same)}/{len(lines)} identical; '
          f'hex words {len(hexw & uphex)}/{len(hexw)}; strings {len(shared)}/{len(strs)}')
    for line in same[:20]:
        print('   =', line[:120])
    if shared:
        print('   strings:', shared)


if __name__ == '__main__':
    main()
