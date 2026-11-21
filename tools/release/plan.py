#!/usr/bin/env python3
"""What one Release run builds and publishes, from the frames' data (docs/releasing.md, "Release").

  tools/release/plan.py --version 1.0.0 [--what all] [--channel stable|beta] [--released FILE]
                        [--root DIR] [--json OUT] [--summary OUT] [--github-output OUT] [--dry-run]

Each frame's `release` block in frames/<id>/design/frame.json says what it ships:

  "release": {
    "lines": { "<line>": { ... } },   the app lines this frame's app module builds (one owner per line)
    "app": ["<line>", ...],           the app lines its devices run (its own, or another frame's)
    "frame": { "bundle": "signed" | "notice", ... }   its own frame release, if it has one
  }

The plan is every app line some frame runs, each once whatever the number of frames on it, and every frame
release. With --what all (the default), an item is due when its changelog has a `## [<version>]` section and no
release has its tag yet. --what can name some instead, comma or space separated: `<frame>` (its app lines and its
frame release), `<frame>:app`, `<frame>:frame`, or an app line by name. A named item must be due: no section, an
existing release or a beta of a frame release fail the plan. Frames have no betas, so --channel beta skips them.

--released is a file of the tags that have a release (published or pre-release; not drafts), one per line, as
`gh release list --exclude-drafts --json tagName --jq '.[].tagName'` prints them. Read-only: reads the frames and
changelogs, runs tools/release/changelog-section.sh, writes only the files it is given. Exit 1 on a plan error, with
the reasons on stderr (and in the summary).
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

# The S1 app's and the generic app's rule (their build.gradle.kts): MAJOR.MINOR.PATCH, minor and patch below 100,
# so versionCode = major*10000 + minor*100 + patch. No SemVer pre-release part: a beta is a plain version.
APP_VERSION = re.compile(r'^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$')
# The frame loader's rule (glassworks_loader.sh semver): each part 0..999, no leading zeros, not 0.0.0.
FRAME_VERSION = re.compile(r'^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$')
RECIPES = ('s1-kiosk', 'gradle')
BUNDLES = ('signed', 'notice')


class PlanError(Exception):
    pass


def read_frames(root):
    frames = {}
    for f in sorted((root / 'frames').glob('*/design/frame.json')):
        data = json.loads(f.read_text())
        fid = f.parent.parent.name
        if data.get('id') != fid:
            raise PlanError(f'{f}: id {data.get("id")!r} is not its folder, {fid}')
        frames[fid] = data
    if not frames:
        raise PlanError(f'no frames/*/design/frame.json under {root}')
    return frames


def frame_abis(frames):
    """Every ABI some frame runs, in frame-id order, each once: tools/release/frame-abis.sh's list."""
    out = []
    for fid in sorted(frames):
        for a in frames[fid].get('abis') or []:
            if a not in out:
                out.append(a)
    return out


def asset_names(pattern, abis, version):
    """The APKs and their .sha256 for `assets` ({"<abi>": name, "*": name}) at `version`."""
    names = []
    for abi in abis:
        p = pattern.get(abi, pattern.get('*'))
        if not p:
            raise PlanError(f'no asset name for {abi} in {pattern}')
        n = p.replace('{abi}', abi).replace('{version}', version)
        names += [n, n + '.sha256']
    return names


def lines_and_frames(frames):
    """The app lines (each once, with the frames that run it) and the frame releases, from the release blocks."""
    lines, users = {}, {}
    for fid in sorted(frames):
        rel = frames[fid].get('release') or {}
        for name, line in (rel.get('lines') or {}).items():
            if name in lines:
                raise PlanError(f'app line {name} is defined by both {lines[name]["owner"]} and {fid}')
            if line.get('build') not in RECIPES:
                raise PlanError(f'{fid}: line {name}: build must be one of {", ".join(RECIPES)}')
            for k in ('title', 'changelog', 'assets', 'tag'):
                if not line.get(k):
                    raise PlanError(f'{fid}: line {name} has no {k}')
            lines[name] = dict(line, owner=fid)
    for fid in sorted(frames):
        rel = frames[fid].get('release') or {}
        for name in rel.get('app') or []:
            if name not in lines:
                raise PlanError(f'{fid}: release.app names {name}, which no frame defines in release.lines')
            users.setdefault(name, []).append(fid)
    for name in lines:
        if name not in users:
            raise PlanError(f'app line {name} is defined by {lines[name]["owner"]} but no frame runs it (release.app)')
    latest = [n for n, l in lines.items() if l.get('latest')]
    if len(latest) > 1:
        raise PlanError(f'only one line can be releases/latest: {", ".join(latest)}')
    frame_rel = {}
    for fid in sorted(frames):
        fr = (frames[fid].get('release') or {}).get('frame')
        if not fr:
            continue
        if fr.get('bundle') not in BUNDLES:
            raise PlanError(f'{fid}: release.frame.bundle must be one of {", ".join(BUNDLES)}')
        for k in ('title', 'changelog'):
            if not fr.get(k):
                raise PlanError(f'{fid}: release.frame has no {k}')
        if fr['bundle'] == 'signed' and not (fr.get('script') and fr.get('pubkey') and fr.get('assets')):
            raise PlanError(f'{fid}: a signed frame bundle names its script, pubkey and assets')
        if fr['bundle'] == 'notice' and fr.get('assets'):
            raise PlanError(f'{fid}: a notice has no assets')
        frame_rel[fid] = fr
    return lines, users, frame_rel


def section(root, version, changelog):
    """The changelog's section for `version`, as changelog-section.sh prints it (the release notes), or None."""
    path = root / changelog
    if not path.is_file():
        raise PlanError(f'{changelog} does not exist')
    r = subprocess.run([str(root / 'tools/release/changelog-section.sh'), version, str(path)], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def parse_what(what, frames, lines, users, frame_rel):
    """--what as a set of ('line', name) and ('frame', id), or None for everything due. Also the warnings."""
    tokens = [t for t in re.split(r'[,\s]+', (what or '').strip()) if t]
    if not tokens or tokens in (['all'], ['all', 'due']):
        return None, []
    picked, warnings = [], []

    def add(item):
        if item not in picked:
            picked.append(item)

    for t in tokens:
        name, _, part = t.partition(':')
        if part not in ('', 'app', 'frame'):
            raise PlanError(f'--what {t}: the part after ":" is app or frame')
        if name in frames:
            rel = frames[name].get('release') or {}
            app, has_frame = rel.get('app') or [], name in frame_rel
            if part == 'frame' and not has_frame:
                raise PlanError(f'--what {t}: {name} has no frame release')
            if part == 'app' and not app:
                raise PlanError(f'--what {t}: {name} runs no app line')
            if not part and not app and not has_frame:
                raise PlanError(f'--what {t}: {name} has nothing to release')
            if part in ('', 'app'):
                for line in app:
                    add(('line', line))
                    others = [f for f in users[line] if f != name]
                    if others:
                        warnings.append(f'{line} is also the app line of {", ".join(others)}: releasing it for {name} '
                                        f'updates those too')
            if part in ('', 'frame') and has_frame:
                add(('frame', name))
        elif name in lines and part in ('', 'app'):
            add(('line', name))
        else:
            raise PlanError(f'--what {t}: no frame or app line called {name}')
    return picked, warnings


def plan(root, version, what='all', channel='stable', released=(), dry_run=False):
    root = pathlib.Path(root)
    if channel not in ('stable', 'beta'):
        raise PlanError(f'--channel {channel}: stable or beta')
    frames = read_frames(root)
    lines, users, frame_rel = lines_and_frames(frames)
    picked, warnings = parse_what(what, frames, lines, users, frame_rel)
    released = set(released)
    errors, items = [], []
    every_abi = frame_abis(frames)

    def wanted(kind, name):
        return picked is None or (kind, name) in picked

    def has_notes(item, explicit):
        """Whether the item's changelog has its section, which is its notes. A dry run stands in Unreleased."""
        if section(root, version, item['changelog']) is not None:
            item['notes'] = version
            return True
        item['why'] = f'no [{version}] section in {item["changelog"]}'
        if dry_run:
            item['notes'] = 'Unreleased'
            warnings.append(f'{item["tag"]}: {item["why"]}; the dry run builds it with the Unreleased notes, '
                            f'a real run would {"fail" if explicit else "skip it"}')
            return True
        if explicit:
            errors.append(f'{item["tag"]}: {item["why"]}')
        return False

    for name in sorted(lines, key=lambda n: (not lines[n].get('latest'), n)):
        line = lines[name]
        abis = every_abi if line.get('abis') == 'every-frame' else list(frames[line['owner']].get('abis') or [])
        tag = line['tag'].replace('{version}', version)
        title = f'{line["title"]} {version}' + (' beta' if channel == 'beta' else '')
        item = {
            'kind': 'line', 'name': name, 'version': version, 'tag': tag, 'title': title, 'changelog': line['changelog'],
            'build': line['build'], 'frames': users[name], 'abis': abis, 'check': name,
            'latest': bool(line.get('latest')) and channel == 'stable', 'prerelease': channel == 'beta',
        }
        for k in ('task', 'apk', 'package', 'minSdk', 'writeSettings'):
            if k in line:
                item[k] = line[k]
        explicit = picked is not None and ('line', name) in picked
        item['status'], item['why'] = 'skipped', ''
        if not wanted('line', name):
            item['why'] = 'not picked'
        elif not APP_VERSION.match(version):
            item['why'] = f'{version} is not an app version (MAJOR.MINOR.PATCH, minor and patch below 100)'
            if explicit:
                errors.append(f'{name}: {item["why"]}')
        elif tag in released:
            item['why'] = f'{tag} is released already'
            if explicit:
                errors.append(f'{name}: {item["why"]}')
        elif not has_notes(item, explicit):
            pass
        else:
            item['status'] = 'due'
            item['assets'] = asset_names(line['assets'], abis, version)
        items.append(item)

    for fid in sorted(frame_rel):
        fr = frame_rel[fid]
        tag = f'frames/{fid}/v{version}'
        item = {
            'kind': 'frame', 'name': fid, 'version': version, 'tag': tag, 'title': f'{fr["title"]} {version}',
            'changelog': fr['changelog'], 'bundle': fr['bundle'], 'frames': [fid], 'check': f'frames/{fid}',
            'latest': False, 'prerelease': False, 'assets': [],
        }
        if fr['bundle'] == 'signed':
            item.update(script=fr['script'], pubkey=fr['pubkey'])
        if fr.get('note'):
            item['note'] = fr['note']
        explicit = picked is not None and ('frame', fid) in picked
        item['status'], item['why'] = 'skipped', ''
        if not wanted('frame', fid):
            item['why'] = 'not picked'
        elif channel == 'beta':
            item['why'] = 'frame releases have no betas'
            if explicit:
                errors.append(f'{tag}: {item["why"]}: release it on the stable channel')
        elif not FRAME_VERSION.match(version) or version == '0.0.0':
            item['why'] = f'{version} is not a frame version (MAJOR.MINOR.PATCH, each 0 to 999)'
            if explicit:
                errors.append(f'{tag}: {item["why"]}')
        elif tag in released:
            item['why'] = f'{tag} is released already'
            if explicit:
                errors.append(f'{tag}: {item["why"]}')
        elif not has_notes(item, explicit):
            pass
        else:
            item['status'] = 'due'
            if fr['bundle'] == 'signed':
                item['assets'] = [a.replace('{version}', version) for a in fr['assets']]
                item['assets'] += [a + '.sha256' for a in item['assets']]
        items.append(item)

    due = [i for i in items if i['status'] == 'due']
    if not due and not errors:
        errors.append(f'nothing is due at {version}: no line or frame has a [{version}] section that is not released yet')
    return {'version': version, 'channel': channel, 'dryRun': bool(dry_run), 'what': what or 'all',
            'items': items, 'warnings': warnings, 'errors': errors}


def summary(p):
    """The plan as the run's summary: one row per line and frame release."""
    dry = p['dryRun']
    out = [f'## Release {p["version"]} ({p["channel"]}){" — dry run: builds and signs, publishes nothing" if dry else ""}',
           '', f'What: `{p["what"]}`', '',
           '| Line or frame | Kind | Version | Tag | Plan |', '|---|---|---|---|---|']
    for i in p['items']:
        kind = f'app line ({i["build"]}), for {", ".join(i["frames"])}' if i['kind'] == 'line' else f'frame release ({i["bundle"]})'
        if i['status'] == 'due':
            what = 'build, check' + (', sign the bundle' if i.get('bundle') == 'signed' else '')
            what = ('notes only' if i.get('bundle') == 'notice' else what) + ('; not published' if dry else '; publish')
            flags = ' (latest)' if i['latest'] else (' (pre-release)' if i['prerelease'] else '')
            plan_cell = f'**{what}**{flags}'
        else:
            plan_cell = f'skipped: {i["why"]}'
        out.append(f'| `{i["name"]}` | {kind} | {i["version"]} | `{i["tag"]}` | {plan_cell} |')
    notes = [f'- `{i["tag"]}`: {i["note"]}' for i in p['items'] if i['status'] == 'due' and i.get('note')]
    if notes:
        out += ['', '**Notes**', ''] + notes
    if p['warnings']:
        out += ['', '**Warnings**', ''] + [f'- {w}' for w in p['warnings']]
    if p['errors']:
        out += ['', '**The plan fails**', ''] + [f'- {e}' for e in p['errors']]
    return '\n'.join(out) + '\n'


def github_outputs(p):
    """The job outputs the workflow's later jobs read."""
    due = [i for i in p['items'] if i['status'] == 'due']
    kiosk = [i for i in due if i['kind'] == 'line' and i['build'] == 's1-kiosk']
    apps = [i for i in due if i['kind'] == 'line' and i['build'] == 'gradle']
    bundles = [i for i in due if i['kind'] == 'frame' and i['bundle'] == 'signed']
    return {
        'plan': json.dumps({'version': p['version'], 'channel': p['channel'], 'items': due}, separators=(',', ':')),
        'kiosk': json.dumps(kiosk[0], separators=(',', ':')) if kiosk else '',
        'apps': json.dumps({'include': apps}, separators=(',', ':')),
        'has_apps': str(bool(apps)).lower(),
        'bundles': json.dumps(bundles, separators=(',', ':')),
        'sign': str(bool(kiosk or bundles)).lower(),
        'any': str(bool(due)).lower(),
    }


def print_item(root, version, spec):
    """One item of the plan, as the signing jobs read it: they take only its name from the plan job."""
    kind, _, name = spec.partition(':')
    if kind not in ('line', 'frame') or not re.match(r'^[a-z0-9][a-z0-9-]*$', name):
        print(f'::error::--item {spec}: line:<name> or frame:<id>', file=sys.stderr)
        return 1
    try:
        p = plan(root, version, f'{name}:app' if kind == 'line' and (pathlib.Path(root) / 'frames' / name).is_dir()
                 else (f'{name}:frame' if kind == 'frame' else name), 'stable', (), dry_run=True)
    except PlanError as e:
        print(f'::error::{e}', file=sys.stderr)
        return 1
    found = [i for i in p['items'] if i['kind'] == kind and i['name'] == name and i['status'] == 'due']
    if p['errors'] or len(found) != 1:
        print(f'::error::--item {spec} at {version}: {"; ".join(p["errors"]) or "no such item"}', file=sys.stderr)
        return 1
    print(json.dumps(found[0]))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--version', required=True)
    ap.add_argument('--what', default='all')
    ap.add_argument('--channel', default='stable')
    ap.add_argument('--released', help='file of the tags that have a release, one per line')
    ap.add_argument('--root', default=str(pathlib.Path(__file__).resolve().parents[2]))
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--json', help='write the whole plan here')
    ap.add_argument('--summary', help='append the Markdown table here (GITHUB_STEP_SUMMARY)')
    ap.add_argument('--github-output', help='append the job outputs here (GITHUB_OUTPUT)')
    ap.add_argument('--item', metavar='line:NAME|frame:ID',
                    help='print only this item as JSON, due or not: what a build job builds, from the commit itself')
    a = ap.parse_args(argv)
    if a.item:
        return print_item(a.root, a.version, a.item)
    released = pathlib.Path(a.released).read_text().split() if a.released else []
    try:
        p = plan(a.root, a.version, a.what, a.channel, released, a.dry_run)
    except PlanError as e:
        p = {'version': a.version, 'channel': a.channel, 'dryRun': a.dry_run, 'what': a.what, 'items': [],
             'warnings': [], 'errors': [str(e)]}
    text = summary(p)
    print(text)
    if a.summary:
        with open(a.summary, 'a') as f:
            f.write(text)
    if a.json:
        pathlib.Path(a.json).write_text(json.dumps(p, indent=2) + '\n')
    for w in p['warnings']:
        print(f'::warning::{w}')
    if p['errors']:
        for e in p['errors']:
            print(f'::error::{e}', file=sys.stderr)
        return 1
    if a.github_output:
        with open(a.github_output, 'a') as f:
            for k, v in github_outputs(p).items():
                f.write(f'{k}={v}\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
