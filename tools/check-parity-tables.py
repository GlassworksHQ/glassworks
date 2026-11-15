#!/usr/bin/env python3
"""Check the feature-parity tables in the libraries' READMEs (read-only).

Usage: tools/check-parity-tables.py [--counts]
  Each file below has a "## Compared with ..." section of Markdown tables. In each status column,
  every cell starts with one of the status words, a Planned cell links an issue (#123), and the
  section's count line for that column ("**Count for <column>** ...: N supported, N partial, N
  planned, N not yet, N not planned.") matches the cells.
  --counts prints the counts, to paste into a count line.
Exits 1 and lists each problem.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# file -> the headers of its status columns
FILES = {
    'libs/esphome-api/README.md': ['esphome-api'],
    'libs/bluetooth-proxy/README.md': ['bluetooth-proxy'],
    'libs/improv/README.md': ['improv (device)', 'improv-serial (browser)'],
}
STATUSES = ['Supported', 'Partial', 'Planned', 'Not yet', 'Not planned']
COUNT = re.compile(r'\*\*Count for (.+?)\*\*.*?: (\d+) supported, (\d+) partial, (\d+) planned, '
                   r'(\d+) not yet, (\d+) not planned\.')


def cells(line):
    return [c.strip() for c in line.strip().strip('|').split('|')]


def section(text):
    out, inside = [], False
    for line in text.splitlines():
        if line.startswith('## '):
            inside = line.startswith('## Compared with')
        elif inside:
            out.append(line)
    return out


def status(cell):
    # longest first: "Not planned" before "Not yet" doesn't matter, but "Planned" must not match "Not planned"
    for s in sorted(STATUSES, key=len, reverse=True):
        if cell == s or cell.startswith(s + ':') or cell.startswith(s + '.') or cell.startswith(s + ' ('):
            return s
    return None


def check(path, columns):
    problems, counts = [], {c: dict.fromkeys(STATUSES, 0) for c in columns}
    lines = section((ROOT / path).read_text(encoding='utf-8'))
    if not lines:
        return [f'{path}: no "## Compared with" section'], counts
    header = None
    for n, line in enumerate(lines):
        if not line.startswith('|'):
            header = None
            continue
        row = cells(line)
        if header is None:
            header = row
            continue
        if set(''.join(row)) <= set('-: '):
            continue
        for col in columns:
            if col not in header:
                continue
            cell = row[header.index(col)] if header.index(col) < len(row) else ''
            s = status(cell)
            if s is None:
                problems.append(f'{path}: "{row[0]}" ({col}): starts with none of {STATUSES}: {cell[:40]!r}')
                continue
            counts[col][s] += 1
            if s == 'Planned' and not re.search(r'#\d+', ' '.join(row)):
                problems.append(f'{path}: "{row[0]}" ({col}): Planned without an issue link')
    seen = set()
    for m in COUNT.finditer('\n'.join(lines)):
        col, said = m.group(1), [int(x) for x in m.groups()[1:]]
        seen.add(col)
        if col not in counts:
            problems.append(f'{path}: a count line for "{col}", which is not a status column')
            continue
        real = [counts[col][s] for s in STATUSES]
        if said != real:
            problems.append(f'{path}: count line for "{col}" says {said}, the table has {real} '
                            f'({", ".join(s.lower() for s in STATUSES)})')
    for col in columns:
        if sum(counts[col].values()) == 0:
            problems.append(f'{path}: no table has a "{col}" column')
        if col not in seen:
            problems.append(f'{path}: no "**Count for {col}**" line')
    return problems, counts


def main():
    problems = []
    for path, columns in FILES.items():
        p, counts = check(path, columns)
        problems += p
        if '--counts' in sys.argv:
            for col, c in counts.items():
                print(f'{path} {col}: ' + ', '.join(f'{n} {s.lower()}' for s, n in c.items()) + '.')
    for p in problems:
        print(p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
