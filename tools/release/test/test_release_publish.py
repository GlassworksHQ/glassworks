"""tools/release/publish.sh: the tags and releases the publish job makes from a plan, and what it refuses.

gh is a stand-in on PATH that keeps its releases in a temporary folder; the tag remote is a bare git repo there.
Touches nothing else. Run from the repo root: python3 -m unittest discover -s tools/release/test -v
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
PUBLISH = ROOT / 'tools/release/publish.sh'

# gh's release commands over a folder: releases/<tag with / as %>/{meta.json, files/}. Logs every call.
FAKE_GH = r'''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
state = pathlib.Path(os.environ['FAKE_GH_STATE'])
with open(state / 'calls.log', 'a') as f:
    f.write(json.dumps(sys.argv[1:]) + '\n')
args = [a for a in sys.argv[1:]]
def opt(name):
    return args[args.index(name) + 1] if name in args else None
def rel(tag):
    return state / 'releases' / tag.replace('/', '%')
cmd = args[:2]
if cmd == ['release', 'list']:
    race = os.environ.get('FAKE_RACE_TAG')   # "<tag> <commit> <remote>": pushed at the first list, after its check
    if race and not (state / 'raced').exists():
        (state / 'raced').write_text('')
        tag, commit, remote = race.split()
        import subprocess
        subprocess.run(['git', 'push', '-q', remote, f'{commit}:refs/tags/{tag}'], check=True)
    for d in sorted((state / 'releases').iterdir()):
        m = json.loads((d / 'meta.json').read_text())
        if not m['draft']:
            print(m['tag'])
    for t in os.environ.get('FAKE_TAGS', '').split():
        print(t)
elif cmd == ['release', 'view']:
    d = rel(args[2])
    if not d.exists():
        print('release not found', file=sys.stderr); sys.exit(1)
    m = json.loads((d / 'meta.json').read_text())
    if opt('--json') == 'isDraft':
        print('true' if m['draft'] else 'false')
    else:
        for f in sorted((d / 'files').iterdir()):
            print(f'{f.name} {m.get("states", {}).get(f.name, "uploaded")}')
elif cmd == ['release', 'download']:
    out = pathlib.Path(opt('--dir'))
    out.mkdir(parents=True, exist_ok=True)
    for f in (rel(args[2]) / 'files').iterdir():
        shutil.copy(f, out / f.name)
elif cmd == ['release', 'delete']:
    shutil.rmtree(rel(args[2]))
elif cmd == ['release', 'create']:
    tag = args[2]
    if os.environ.get('FAKE_GH_FAIL_CREATE') == tag:
        print('upload failed', file=sys.stderr); sys.exit(1)
    d = rel(tag)
    if d.exists():
        print('already exists', file=sys.stderr); sys.exit(1)
    (d / 'files').mkdir(parents=True)
    skip = {'-R', '--title', '--notes-file'}
    i, files = 3, []
    while i < len(args):
        if args[i] in skip:
            i += 2; continue
        if args[i].startswith('--'):
            i += 1; continue
        files.append(args[i]); i += 1
    for f in files:
        shutil.copy(f, d / 'files' / os.path.basename(f))
    meta = {'tag': tag, 'draft': False, 'title': opt('--title'), 'notes': pathlib.Path(opt('--notes-file')).read_text(),
            'flags': [a for a in args if a in ('--latest', '--latest=false', '--prerelease', '--verify-tag')]}
    (d / 'meta.json').write_text(json.dumps(meta))
else:
    print(f'fake gh: unexpected {args}', file=sys.stderr); sys.exit(2)
'''


def git(*args, cwd):
    return subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@example.com', *args], cwd=cwd,
                          capture_output=True, text=True, check=True).stdout.strip()


class Publish(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='release-publish-'))
        self.addCleanup(shutil.rmtree, self.tmp)
        self.state = self.tmp / 'gh'
        (self.state / 'releases').mkdir(parents=True)
        self.bin = self.tmp / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text(FAKE_GH)
        (self.bin / 'gh').chmod(0o755)
        # The remote, and the run's checkout of it.
        self.remote = self.tmp / 'remote.git'
        git('init', '-q', '--bare', str(self.remote), cwd=self.tmp)
        self.work = self.tmp / 'work'
        self.work.mkdir()
        git('init', '-q', cwd=self.work)
        (self.work / 'CHANGELOG.md').write_text('## [Unreleased]\n\n## [1.0.0] - 2026-11-21\n\n**One.** S1 notes.\n')
        (self.work / 'frames/generic-android').mkdir(parents=True)
        (self.work / 'frames/generic-android/CHANGELOG.md').write_text('## [1.0.0] - 2026-11-21\n\nGeneric notes.\n')
        (self.work / 'frames/lululemon-mirror').mkdir(parents=True)
        (self.work / 'frames/lululemon-mirror/FRAME-CHANGELOG.md').write_text('## [1.0.0] - 2026-11-21\n\nMirror frame.\n')
        git('add', '-A', cwd=self.work)
        git('commit', '-q', '-m', 'one', cwd=self.work)
        self.sha = git('rev-parse', 'HEAD', cwd=self.work)
        git('push', '-q', str(self.remote), 'HEAD:refs/heads/main', cwd=self.work)
        self.dist = self.tmp / 'dist'
        self.items = []

    def line(self, name, files, changelog, title, latest=False, prerelease=False, notes='1.0.0'):
        d = self.dist / f'line-{name}'
        d.mkdir(parents=True)
        assets = []
        for f in files:
            (d / f).write_bytes(f.encode())
            (d / f'{f}.sha256').write_text(f'{hashlib.sha256(f.encode()).hexdigest()}  {f}\n')
            assets += [f, f'{f}.sha256']
        self.items.append({'kind': 'line', 'name': name, 'version': '1.0.0', 'tag': f'{name}-v1.0.0', 'title': title,
                           'changelog': changelog, 'status': 'due', 'check': name, 'latest': latest,
                           'prerelease': prerelease, 'assets': assets, 'notes': notes})

    def notice(self, fid):
        self.items.append({'kind': 'frame', 'name': fid, 'version': '1.0.0', 'tag': f'frames/{fid}/v1.0.0', 'bundle': 'notice',
                           'title': 'lululemon Mirror frame 1.0.0', 'changelog': f'frames/{fid}/FRAME-CHANGELOG.md',
                           'status': 'due', 'check': f'frames/{fid}', 'latest': False, 'prerelease': False, 'assets': [],
                           'notes': '1.0.0'})

    def standard(self, prerelease=False):
        self.line('aqara-s1-plus', ['s1-kiosk-1.0.0.apk', 's1-kiosk-arm64-v8a-1.0.0.apk'], 'CHANGELOG.md',
                  'Glassworks for the Aqara S1 Plus 1.0.0', latest=not prerelease, prerelease=prerelease)
        self.line('generic-android-legacy', ['glassworks-generic-android-legacy-arm64-v8a-1.0.0.apk'],
                  'frames/generic-android/CHANGELOG.md', 'Glassworks for generic Android, legacy build 1.0.0', prerelease=prerelease)
        if not prerelease:
            self.notice('lululemon-mirror')

    def run_publish(self, **env):
        plan = self.tmp / 'plan.json'
        plan.write_text(json.dumps({'version': '1.0.0', 'channel': 'stable', 'items': self.items}))
        e = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}', FAKE_GH_STATE=str(self.state), GH_TOKEN='x',
                 GITHUB_REPOSITORY='example/repo', GITHUB_SHA=self.sha, TAG_REMOTE=str(self.remote),
                 GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_NOSYSTEM='1', **env)
        return subprocess.run([str(PUBLISH), str(plan), str(self.dist), str(self.tmp / 'published')], cwd=self.work,
                              env=e, capture_output=True, text=True)

    def releases(self):
        out = {}
        for d in (self.state / 'releases').iterdir():
            m = json.loads((d / 'meta.json').read_text())
            m['files'] = sorted(f.name for f in (d / 'files').iterdir())
            out[m['tag']] = m
        return out

    def remote_tags(self):
        out = subprocess.run(['git', 'ls-remote', '--tags', str(self.remote)], capture_output=True, text=True).stdout
        return {l.split('\t')[1].removeprefix('refs/tags/'): l.split('\t')[0] for l in out.splitlines()}

    def creates(self):
        log = (self.state / 'calls.log').read_text().splitlines() if (self.state / 'calls.log').exists() else []
        return [json.loads(l) for l in log if json.loads(l)[:2] == ['release', 'create']]

    def test_every_item_gets_its_tag_on_the_commit_and_its_release(self):
        self.standard()
        r = self.run_publish()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual({'aqara-s1-plus-v1.0.0': self.sha, 'generic-android-legacy-v1.0.0': self.sha,
                          'frames/lululemon-mirror/v1.0.0': self.sha}, self.remote_tags())
        rel = self.releases()
        s1 = rel['aqara-s1-plus-v1.0.0']
        self.assertEqual(['--verify-tag', '--latest'], s1['flags'])
        self.assertEqual(['s1-kiosk-1.0.0.apk', 's1-kiosk-1.0.0.apk.sha256', 's1-kiosk-arm64-v8a-1.0.0.apk',
                          's1-kiosk-arm64-v8a-1.0.0.apk.sha256'], s1['files'])
        self.assertEqual('**One.** S1 notes.\n', s1['notes'])
        self.assertEqual('Glassworks for the Aqara S1 Plus 1.0.0', s1['title'])
        legacy = rel['generic-android-legacy-v1.0.0']
        self.assertEqual(['--verify-tag', '--latest=false'], legacy['flags'])
        self.assertEqual('Generic notes.\n', legacy['notes'])
        mirror = rel['frames/lululemon-mirror/v1.0.0']
        self.assertEqual([], mirror['files'])
        self.assertEqual(['--verify-tag', '--latest=false'], mirror['flags'])
        self.assertEqual(['aqara-s1-plus-v1.0.0', 'generic-android-legacy-v1.0.0', 'frames/lululemon-mirror/v1.0.0'],
                         (self.tmp / 'published').read_text().split())

    def test_a_beta_is_a_pre_release_and_never_latest(self):
        self.standard(prerelease=True)
        r = self.run_publish()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        for tag in ('aqara-s1-plus-v1.0.0', 'generic-android-legacy-v1.0.0'):
            self.assertEqual(['--verify-tag', '--prerelease', '--latest=false'], self.releases()[tag]['flags'])

    def test_a_tag_on_another_commit_stops_everything_before_any_change(self):
        self.standard()
        (self.work / 'later').write_text('x')
        git('add', 'later', cwd=self.work)
        git('commit', '-q', '-m', 'later', cwd=self.work)
        git('push', '-q', str(self.remote), 'HEAD:refs/tags/frames/lululemon-mirror/v1.0.0', cwd=self.work)
        git('reset', '-q', '--hard', self.sha, cwd=self.work)
        r = self.run_publish()
        self.assertNotEqual(0, r.returncode)
        self.assertIn('frames/lululemon-mirror/v1.0.0 already names', r.stderr)
        self.assertEqual([], self.creates())
        self.assertEqual(['frames/lululemon-mirror/v1.0.0'], list(self.remote_tags()))   # nothing else pushed

    def later_commit(self):
        (self.work / 'later').write_text('x')
        git('add', 'later', cwd=self.work)
        git('commit', '-q', '-m', 'later', cwd=self.work)
        later = git('rev-parse', 'HEAD', cwd=self.work)
        git('push', '-q', str(self.remote), f'{later}:refs/heads/later', cwd=self.work)
        git('reset', '-q', '--hard', self.sha, cwd=self.work)
        return later

    def test_a_tag_pushed_elsewhere_after_the_checks_is_never_published_over(self):
        # The S1 app's tag appears on another commit between the checks and its push: no release under it.
        self.standard()
        r = self.run_publish(FAKE_RACE_TAG=f'aqara-s1-plus-v1.0.0 {self.later_commit()} {self.remote}')
        self.assertNotEqual(0, r.returncode)
        self.assertIn('aqara-s1-plus-v1.0.0 now names', r.stderr)
        self.assertEqual([], self.creates())

    def test_a_tag_pushed_by_hand_on_this_commit_is_used(self):
        self.standard()
        git('push', '-q', str(self.remote), f'{self.sha}:refs/tags/aqara-s1-plus-v1.0.0', cwd=self.work)
        r = self.run_publish()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('aqara-s1-plus-v1.0.0', self.releases())

    def test_a_version_not_higher_than_the_last_stops_everything(self):
        self.standard()
        r = self.run_publish(FAKE_TAGS='generic-android-legacy-v1.1.0')
        self.assertNotEqual(0, r.returncode)
        self.assertIn('not higher than generic-android-legacy', r.stdout + r.stderr)
        self.assertEqual([], self.creates())
        self.assertEqual({}, self.remote_tags())

    def test_files_that_arent_the_plans_stop_everything(self):
        self.standard()
        (self.dist / 'line-generic-android-legacy/extra.apk').write_text('x')
        r = self.run_publish()
        self.assertNotEqual(0, r.returncode)
        self.assertIn("aren't the plan's", r.stderr)
        self.assertEqual([], self.creates())

    def test_a_file_that_doesnt_match_its_sha256_stops_everything(self):
        self.standard()
        (self.dist / 'line-aqara-s1-plus/s1-kiosk-1.0.0.apk').write_text('changed')
        r = self.run_publish()
        self.assertNotEqual(0, r.returncode)
        self.assertEqual([], self.creates())

    def test_a_dry_runs_unreleased_notes_never_publish(self):
        self.line('aqara-s1-plus', ['s1-kiosk-1.0.0.apk'], 'CHANGELOG.md', 'x', notes='Unreleased')
        r = self.run_publish()
        self.assertNotEqual(0, r.returncode)
        self.assertIn('refusing to publish', r.stderr)

    def test_a_rerun_after_a_later_item_failed_finishes_the_rest(self):
        self.standard()
        r = self.run_publish(FAKE_GH_FAIL_CREATE='generic-android-legacy-v1.0.0')
        self.assertNotEqual(0, r.returncode)
        self.assertEqual(['aqara-s1-plus-v1.0.0'], list(self.releases()))
        r = self.run_publish()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('aqara-s1-plus-v1.0.0: already published with these files', r.stdout)
        self.assertEqual(3, len(self.releases()))
        # The S1 app's release isn't made again (the legacy line's first create failed); the list for Discord has all three.
        self.assertEqual(['aqara-s1-plus-v1.0.0', 'generic-android-legacy-v1.0.0', 'generic-android-legacy-v1.0.0',
                          'frames/lululemon-mirror/v1.0.0'], [c[2] for c in self.creates()])
        self.assertEqual(3, len((self.tmp / 'published').read_text().split()))

    def test_a_published_release_with_other_files_is_never_touched(self):
        self.standard()
        self.assertEqual(0, self.run_publish().returncode)
        shutil.rmtree(self.state / 'releases' / 'frames%lululemon-mirror%v1.0.0')
        (self.dist / 'line-aqara-s1-plus/s1-kiosk-1.0.0.apk').write_text('rebuilt')
        (self.dist / 'line-aqara-s1-plus/s1-kiosk-1.0.0.apk.sha256').write_text(
            f'{hashlib.sha256(b"rebuilt").hexdigest()}  s1-kiosk-1.0.0.apk\n')
        r = self.run_publish()
        self.assertNotEqual(0, r.returncode)
        self.assertIn('published with other files', r.stderr)

    def test_an_old_draft_is_never_made_again_over_a_newer_release(self):
        # A draft 1.0.0 left by an earlier run, then 1.1.0 published: retrying the old publish must stop.
        self.standard()
        d = self.state / 'releases' / 'aqara-s1-plus-v1.0.0'
        (d / 'files').mkdir(parents=True)
        (d / 'meta.json').write_text(json.dumps({'tag': 'aqara-s1-plus-v1.0.0', 'draft': True}))
        r = self.run_publish(FAKE_TAGS='aqara-s1-plus-v1.1.0')
        self.assertNotEqual(0, r.returncode)
        self.assertIn('not higher than aqara-s1-plus', r.stdout + r.stderr)
        self.assertEqual([], self.creates())
        self.assertTrue(d.exists())   # the draft is left as it was

    def test_a_draft_is_made_again(self):
        self.standard()
        d = self.state / 'releases' / 'aqara-s1-plus-v1.0.0'
        (d / 'files').mkdir(parents=True)
        (d / 'files' / 's1-kiosk-1.0.0.apk').write_text('half')
        (d / 'meta.json').write_text(json.dumps({'tag': 'aqara-s1-plus-v1.0.0', 'draft': True}))
        r = self.run_publish()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(4, len(self.releases()['aqara-s1-plus-v1.0.0']['files']))


if __name__ == '__main__':
    unittest.main()
