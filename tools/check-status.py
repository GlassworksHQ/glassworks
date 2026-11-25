#!/usr/bin/env python3
"""Check that the status labels in the Markdown agree with frame.json and pane.json (read-only).

Usage: tools/check-status.py
  frames/<id>/design/frame.json (`status`, `panes`) and each pane's pane.json (`status`) are the only source.
  This checks what the Markdown says about them:
  - every `Status: <stage>` badge: a README beside a pane.json says the pane's status; any other README under
    frames/<id>/ (its app's too) says the frame's;
  - a frame README's lead line, `**Status: <stage>.**`;
  - every table with a Status or Support column whose first cell links to a frame (the root README,
    docs/getting-started.md, frames/README.md): that frame's status;
  - each frame README's `## Panes` table: one row per pane in the catalog, named as its pane.json names it, at
    the support the frame gives it. That is the frame's own rating in frame.json, or one worked out from the
    pane's `requires` and the frame's `provides`, as `paneSupport()` in packages/frame-card/src/panes.ts.
Exits 1 and lists each disagreement. Changes nothing.
"""
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {'node_modules', 'build', '.gradle', '.kotlin', '.next', '.git'}
# Where the Markdown is looked for, besides the root README.md (as tools/check-doc-links.py).
DOC_ROOTS = ('docs', 'frames', 'libs', 'core', 'pane-api', 'engines', 'panes', 'kiln/apps', 'kiln/packages',
             'packages', 'site')

# A stage or a support, as the Markdown words it: the status badges' words, then frame-card's Support.
WORDS = {
    'stable': 'Stable', 'beta': 'Beta', 'alpha': 'Alpha', 'development': 'Under development',
    'planned': 'Planned', 'depends': 'Depends on device', 'unknown': 'Unknown', 'no': 'Not supported',
}
SHIPS = {'alpha', 'beta', 'stable'}
# Old pane ids frame.json files may still use (frame-card PANE_ALIASES).
PANE_ALIASES = {'base': 'wall-base'}

BADGE = re.compile(r'alt="Status: ([a-z ]+)"')
LEAD = re.compile(r'^\*\*Status: ([a-z ]+)\.\*\*')
LINK = re.compile(r'\]\(\s*<?([^)\s>#]+)')
FENCE = re.compile(r'^\s*(```|~~~)')


def walk(top, name):
    for d, dirs, files in os.walk(top):
        dirs[:] = sorted(x for x in dirs if x not in SKIP)
        for f in sorted(files):
            if f == name:
                yield Path(d) / f


def load_frames(root):
    frames = {}
    for f in sorted((root / 'frames').glob('*/design/frame.json')):
        data = json.loads(f.read_text(encoding='utf-8'))
        frames[data['id']] = data
    assert frames, f'no frames/*/design/frame.json under {root}'
    return frames


def load_panes(root):
    """Every pane.json in a module's src/main (as packages/frame-card/scripts/panes.mjs), with its folder."""
    panes = []
    for top in ('libs', 'core', 'pane-api', 'engines', 'panes', 'frames'):
        for f in walk(root / top, 'pane.json'):
            parts = f.relative_to(root).parts
            if 'src' not in parts or parts[parts.index('src') + 1:parts.index('src') + 2] != ('main',):
                continue
            data = json.loads(f.read_text(encoding='utf-8'))
            data['dir'] = f.parent
            panes.append(data)
    assert panes, f'no pane.json under {root}'
    return panes


def word(stage):
    return WORDS.get(stage, stage)


def pane_support(frame, pane):
    """frame-card's paneSupport(), the support only."""
    own = frame.get('panes', {})
    for key in [pane['id']] + [a for a, p in PANE_ALIASES.items() if p == pane['id']]:
        if key in own:
            return own[key]['support']
    if pane.get('frame') and pane['frame'] != frame['id']:
        return 'no'
    ships = frame['status'] in SHIPS
    if not pane['requires']:
        return pane['status'] if ships else 'planned'
    if 'provides' not in frame:
        return 'depends'
    if any(r not in frame['provides'] for r in pane['requires']):
        return 'no'
    return pane['status'] if ships else 'planned'


def lines(path):
    fenced = False
    for n, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
        elif not fenced:
            yield n, line


def cells(line):
    return [c.strip() for c in line.strip().strip('|').split('|')]


def tables(path):
    """Each Markdown table in [path]: (its heading, its header cells, [(line number, cells)])."""
    out, heading, rows, header = [], '', None, None
    for n, line in lines(path):
        if line.startswith('#'):
            heading = line.lstrip('#').strip()
        if line.lstrip().startswith('|'):
            c = cells(line)
            if header is None:
                header, rows = c, []
            elif not all(re.fullmatch(r':?-+:?', x) for x in c):
                rows.append((n, c))
        elif header is not None:
            out.append((heading, header, rows))
            header = None
    if header is not None:
        out.append((heading, header, rows))
    return out


def plain(cell):
    """A cell's text without Markdown emphasis, links or code marks."""
    cell = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', cell)
    return re.sub(r'[*_`]', '', cell).strip()


def frame_of(md, cell, root, frames):
    """The frame id a table's first cell links to, or None."""
    for target in LINK.findall(cell):
        if re.match(r'[a-z]+:', target):
            continue
        p = (md.parent / target).resolve()
        try:
            rel = p.relative_to(root / 'frames').parts
        except ValueError:
            continue
        if rel and rel[0] in frames:
            return rel[0]
    return None


def owner(md, root, frames, panes):
    """What a README's status badge describes: ('pane', pane) or ('frame', id), or None."""
    for pane in panes:
        if md.parent == pane['dir']:
            return 'pane', pane
    rel = md.relative_to(root).parts
    if len(rel) > 2 and rel[0] == 'frames' and rel[1] in frames and 'research' not in rel:
        return 'frame', rel[1]
    return None


def markdown(root):
    files = [root / 'README.md']
    for top in DOC_ROOTS:
        if (root / top).is_dir():
            files += [p for p in sorted((root / top).rglob('*.md')) if not SKIP.intersection(p.relative_to(root).parts)]
    return files


def check(root=ROOT):
    root = Path(root).resolve()
    frames, panes = load_frames(root), load_panes(root)
    by_name = {p['name'].lower(): p for p in panes}
    problems = []

    def bad(md, n, msg):
        problems.append(f'{md.relative_to(root)}:{n}: {msg}')

    for md in markdown(root):
        who = owner(md, root, frames, panes)
        if who:
            kind, x = who
            want, what = (x['status'], f"pane.json says {x['status']}") if kind == 'pane' else \
                (frames[x]['status'], f"frames/{x}/design/frame.json says {frames[x]['status']}")
            for n, line in lines(md):
                for got in BADGE.findall(line):
                    if got != word(want).lower():
                        bad(md, n, f'status badge says {got}, {what}')
                m = LEAD.match(line)
                if m and kind == 'frame' and m.group(1) != word(want).lower():
                    bad(md, n, f'"Status: {m.group(1)}", {what}')

        for heading, header, rows in tables(md):
            cols = [i for i, h in enumerate(header) if plain(h).lower() in ('status', 'support')]
            if not cols:
                continue
            col = cols[0]
            frame_id = None
            rel = md.relative_to(root).parts
            if heading.lower() == 'panes' and len(rel) == 3 and rel[0] == 'frames' and rel[2] == 'README.md':
                frame_id = rel[1]
            if frame_id:
                seen = set()
                for n, c in rows:
                    pane = by_name.get(plain(c[0]).lower())
                    if pane is None:
                        bad(md, n, f'"{plain(c[0])}" is no pane in the catalog; name it as its pane.json does')
                        continue
                    seen.add(pane['id'])
                    want = word(pane_support(frames[frame_id], pane))
                    if col >= len(c) or plain(c[col]) != want:
                        bad(md, n, f"{pane['name']}: says {plain(c[col]) if col < len(c) else 'nothing'}, the data gives {want}")
                for pane in panes:
                    if pane['id'] not in seen:
                        bad(md, rows[-1][0] if rows else 0, f"the Panes table has no row for {pane['name']}")
                continue
            for n, c in rows:
                fid = frame_of(md, c[0], root, frames)
                if fid is None or col >= len(c):
                    continue
                want = word(frames[fid]['status'])
                if plain(c[col]) != want:
                    bad(md, n, f"{fid}: says {plain(c[col])}, frames/{fid}/design/frame.json says {want}")
    return problems


def main():
    problems = check()
    for p in problems:
        print(p)
    if problems:
        print(f'\n{len(problems)} status label(s) disagree with frame.json or pane.json', file=sys.stderr)
        return 1
    print('status labels agree with frame.json and pane.json')
    return 0


if __name__ == '__main__':
    sys.exit(main())
