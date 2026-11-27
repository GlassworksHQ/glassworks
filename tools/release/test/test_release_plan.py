"""tools/release/plan.py: given the frames' data, which app lines, frame releases and tags one Release run makes.

Runs on the repo's own frames, and on copies of them in a temporary folder with made-up changelogs. Touches
nothing else. Run from the repo root: python3 -m unittest discover -s tools/release/test -v
"""
import importlib.util
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('plan', ROOT / 'tools/release/plan.py')
plan_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan_mod)
PlanError = plan_mod.PlanError

MIRROR_VERSION_FILE = 'frames/lululemon-mirror/installer/src/image.ts'

CHANGELOGS = {
    'app': 'CHANGELOG.md',
    'generic': 'frames/generic-android/CHANGELOG.md',
    's1-frame': 'frames/aqara-s1-plus/FRAME-CHANGELOG.md',
    'mirror-frame': 'frames/lululemon-mirror/FRAME-CHANGELOG.md',
}


def by(p, kind, name):
    return next(i for i in p['items'] if i['kind'] == kind and i['name'] == name)


def due(p):
    return {(i['kind'], i['name']) for i in p['items'] if i['status'] == 'due'}


def s1_marked_latest():
    """The committed S1 line's `latest` (false for now, 1.0.0 included, docs/releasing.md#tags)."""
    data = json.loads((ROOT / 'frames/aqara-s1-plus/design/frame.json').read_text())
    return bool(data['release']['lines']['aqara-s1-plus'].get('latest'))


class Fixture(unittest.TestCase):
    """A copy of the repo's frames (design/frame.json only) and changelogs with the sections a test gives."""

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix='release-plan-'))
        self.addCleanup(shutil.rmtree, self.root)
        for f in ROOT.glob('frames/*/design/frame.json'):
            d = self.root / f.relative_to(ROOT)
            d.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(f, d)
        (self.root / 'tools/release').mkdir(parents=True)
        shutil.copy(ROOT / 'tools/release/changelog-section.sh', self.root / 'tools/release/')
        self.sections()
        self.mirror_version('1.0.0')

    def mirror_version(self, version):
        """Kiln's Mirror recipe, as committed, with MIRROR_FRAME at `version`: what freshly flashed Mirrors report."""
        text = (ROOT / MIRROR_VERSION_FILE).read_text()
        text, n = re.subn(r"(MIRROR_FRAME = \{ id: 'lululemon-mirror', version: ')[^']*(')", rf'\g<1>{version}\g<2>', text)
        self.assertEqual(1, n, 'MIRROR_FRAME is no longer declared as the plan reads it')
        p = self.root / MIRROR_VERSION_FILE
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def sections(self, **versions):
        """Each changelog with an Unreleased section and a [<version>] section for the versions given (by key)."""
        for key, path in CHANGELOGS.items():
            text = '# Changelog\n\n## [Unreleased]\n\n- Something new.\n'
            for v in versions.get(key.replace('-', '_'), ()):
                text += f'\n## [{v}] - 2026-11-21\n\n**{key} {v}.** One line.\n'
            p = self.root / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)

    def frame(self, fid, data):
        p = self.root / 'frames' / fid / 'design/frame.json'
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(dict({'id': fid, 'abis': ['arm64-v8a']}, **data)))

    def edit(self, fid, change):
        p = self.root / 'frames' / fid / 'design/frame.json'
        data = json.loads(p.read_text())
        change(data)
        p.write_text(json.dumps(data))

    def plan(self, version='1.0.0', **kw):
        return plan_mod.plan(self.root, version, **kw)


class TheRepoFrames(unittest.TestCase):
    """The frames as committed: what they ship, and that the names stay the ones panels and Kiln read."""

    def setUp(self):
        self.frames = plan_mod.read_frames(ROOT)
        self.lines, self.users, self.frame_rel = plan_mod.lines_and_frames(self.frames)

    def test_three_app_lines_each_built_once(self):
        self.assertEqual(['aqara-s1-plus', 'generic-android', 'generic-android-legacy'], sorted(self.lines))
        self.assertEqual(['aqara-s1-plus'], self.users['aqara-s1-plus'])
        self.assertEqual(['generic-android', 'google-pixel-tablet', 'samsung-galaxy-tab-a-10-1-2016'], self.users['generic-android'])
        self.assertEqual(['generic-android', 'lululemon-mirror'], self.users['generic-android-legacy'])

    def test_the_frame_releases(self):
        self.assertEqual({'aqara-s1-plus': 'signed', 'lululemon-mirror': 'notice'},
                         {k: v['bundle'] for k, v in self.frame_rel.items()})
        self.assertEqual('frames/lululemon-mirror/FRAME-CHANGELOG.md', self.frame_rel['lululemon-mirror']['changelog'])
        self.assertTrue((ROOT / self.frame_rel['lululemon-mirror']['changelog']).is_file())
        s1 = self.frame_rel['aqara-s1-plus']
        for k in ('script', 'pubkey', 'changelog'):
            self.assertTrue((ROOT / s1[k]).is_file(), s1[k])
        self.assertIn('Frame update', s1['note'])  # how panels install the zip: the summary says so

    def test_the_mirror_names_where_kiln_sets_its_frame_version(self):
        mirror = self.frame_rel['lululemon-mirror']
        self.assertEqual(MIRROR_VERSION_FILE, mirror['versionFile'])
        # The declaration the plan reads is the one Kiln writes into build.prop (image.test.ts checks that part).
        v = plan_mod.device_version(ROOT, 'lululemon-mirror', mirror['versionFile'])
        self.assertRegex(v, r'^\d+\.\d+\.\d+(-dev)?$')
        # The S1 sets its frame version from the signed bundle at boot: nothing in the repo to compare.
        self.assertNotIn('versionFile', self.frame_rel['aqara-s1-plus'])

    def test_tags_and_assets_are_the_ones_panels_and_kiln_read(self):
        p = plan_mod.plan(ROOT, '1.2.3', dry_run=True)
        tags = {(i['kind'], i['name']): i['tag'] for i in p['items']}
        self.assertEqual({
            ('line', 'aqara-s1-plus'): 'aqara-s1-plus-v1.2.3',
            ('line', 'generic-android'): 'generic-android-v1.2.3',
            ('line', 'generic-android-legacy'): 'generic-android-legacy-v1.2.3',
            ('frame', 'aqara-s1-plus'): 'frames/aqara-s1-plus/v1.2.3',
            ('frame', 'lululemon-mirror'): 'frames/lululemon-mirror/v1.2.3',
        }, tags)
        # = Releases.kt: ApkAbi.apkName for the S1 app, ReleaseLine.apkName for the generic lines.
        self.assertEqual(['s1-kiosk-1.2.3.apk', 's1-kiosk-1.2.3.apk.sha256',
                          's1-kiosk-arm64-v8a-1.2.3.apk', 's1-kiosk-arm64-v8a-1.2.3.apk.sha256'],
                         by(p, 'line', 'aqara-s1-plus')['assets'])
        for line in ('generic-android', 'generic-android-legacy'):
            self.assertEqual([f'glassworks-{line}-arm64-v8a-1.2.3.apk', f'glassworks-{line}-arm64-v8a-1.2.3.apk.sha256',
                              f'glassworks-{line}-armeabi-v7a-1.2.3.apk', f'glassworks-{line}-armeabi-v7a-1.2.3.apk.sha256'],
                             by(p, 'line', line)['assets'])
        self.assertEqual(['frame-bundle-aqara-s1-plus-1.2.3.zip', 'frame-bundle-aqara-s1-plus-1.2.3.zip.sha256'],
                         by(p, 'frame', 'aqara-s1-plus')['assets'])
        self.assertEqual([], by(p, 'frame', 'lululemon-mirror')['assets'])

    def test_the_s1_apks_are_named_as_release_yml_names_them(self):
        fn = next(l.strip() for l in (ROOT / '.github/workflows/release.yml').read_text().splitlines()
                  if l.strip().startswith('apk_name() {'))
        item = by(plan_mod.plan(ROOT, '1.2.3', dry_run=True), 'line', 'aqara-s1-plus')
        for abi in item['abis']:
            out = subprocess.run(['bash', '-c', f'{fn}\napk_name "$1"', 'bash', abi], capture_output=True, text=True,
                                 env={'VERSION': '1.2.3', 'PATH': '/usr/bin:/bin'}).stdout.strip()
            self.assertIn(out, item['assets'], abi)

    def test_kilns_release_patterns_match_the_tags_and_apks(self):
        # Kiln's release route finds a line's releases with frames/<id>/design/installer.json's patterns (read here).
        p = plan_mod.plan(ROOT, '1.2.3', dry_run=True)
        for f in ROOT.glob('frames/*/design/installer.json'):
            for r in json.loads(f.read_text()).get('releases') or []:
                item = by(p, 'line', r['line'])
                self.assertEqual('1.2.3', re.match(r['tag'], item['tag']).group(1), r['line'])
                for a in r['assets']:
                    names = {a.replace('{abi}', abi).replace('{version}', '1.2.3') for abi in item['abis']}
                    self.assertTrue(names & set(item['assets']), f'{r["line"]}: {a}')

    def test_no_line_is_releases_latest_while_v0_4_4_holds_it(self):
        # Panels on 0.4.3 read only releases/latest and only understand v1.2.3: v0.4.4 keeps it, and they reach
        # anything newer through 0.4.4 until the maintainer marks the S1 line latest again, after 1.0.0
        # (docs/releasing.md#tags). That PR
        # changes this test to expect ['aqara-s1-plus']; the others follow the data.
        self.assertEqual([], [n for n, l in self.lines.items() if l.get('latest')])

    def test_the_s1_app_builds_every_frames_abi_and_the_generic_lines_their_frames(self):
        p = plan_mod.plan(ROOT, '1.2.3', dry_run=True)
        abis = subprocess.run([str(ROOT / 'tools/release/frame-abis.sh')], capture_output=True, text=True, check=True).stdout.split()
        self.assertEqual(abis, by(p, 'line', 'aqara-s1-plus')['abis'])
        self.assertEqual(self.frames['generic-android']['abis'], by(p, 'line', 'generic-android')['abis'])

    def test_a_tested_device_carries_only_an_app_reference(self):
        # The Pixel Tablet's release block is one key, apart from the rest of its data (its status changes elsewhere).
        self.assertEqual({'app': ['generic-android']}, self.frames['google-pixel-tablet']['release'])


class Due(Fixture):
    def test_nothing_due_fails(self):
        p = self.plan()
        self.assertEqual(set(), due(p))
        self.assertTrue(any('nothing is due' in e for e in p['errors']))

    def test_one_point_oh_with_every_section(self):
        self.sections(app=['1.0.0'], generic=['1.0.0'], s1_frame=['1.0.0'], mirror_frame=['1.0.0'])
        p = self.plan()
        self.assertEqual([], p['errors'])
        self.assertEqual({('line', 'aqara-s1-plus'), ('line', 'generic-android'), ('line', 'generic-android-legacy'),
                          ('frame', 'aqara-s1-plus'), ('frame', 'lululemon-mirror')}, due(p))
        # One item per line, however many frames run it.
        self.assertEqual(5, len(p['items']))
        s1 = by(p, 'line', 'aqara-s1-plus')
        self.assertEqual(s1_marked_latest(), s1['latest'])  # false while v0.4.4 holds releases/latest
        self.assertFalse(by(p, 'line', 'generic-android')['latest'])
        self.assertEqual('Glassworks for the Aqara S1 Plus 1.0.0', s1['title'])
        self.assertEqual('Glassworks for generic Android, legacy build 1.0.0', by(p, 'line', 'generic-android-legacy')['title'])
        self.assertEqual('lululemon Mirror frame 1.0.0', by(p, 'frame', 'lululemon-mirror')['title'])
        self.assertEqual('Aqara S1 Plus frame 1.0.0', by(p, 'frame', 'aqara-s1-plus')['title'])
        for i in p['items']:
            self.assertEqual('1.0.0', i['notes'])

    def test_a_partial_release_is_what_has_a_section(self):
        self.sections(app=['1.0.1'])
        p = self.plan('1.0.1')
        self.assertEqual({('line', 'aqara-s1-plus')}, due(p))
        self.assertEqual('no [1.0.1] section in frames/generic-android/CHANGELOG.md', by(p, 'line', 'generic-android')['why'])

    def test_a_released_tag_is_skipped(self):
        self.sections(app=['1.0.0'], generic=['1.0.0'])
        p = self.plan(released=['generic-android-v1.0.0'])
        self.assertEqual({('line', 'aqara-s1-plus'), ('line', 'generic-android-legacy')}, due(p))
        self.assertIn('released already', by(p, 'line', 'generic-android')['why'])

    def test_app_and_frame_versions_follow_their_own_rules(self):
        self.sections(app=['1.100.0'], s1_frame=['1.100.0'])
        p = self.plan('1.100.0')
        self.assertEqual({('frame', 'aqara-s1-plus')}, due(p))  # a frame part goes to 999; an app's minor stops at 99
        self.assertIn('not an app version', by(p, 'line', 'aqara-s1-plus')['why'])
        p = self.plan('1.100.0', what='aqara-s1-plus:app')
        self.assertTrue(any('not an app version' in e for e in p['errors']))
        self.sections(s1_frame=['0.0.0'])
        self.assertTrue(self.plan('0.0.0', what='aqara-s1-plus:frame')['errors'])


class What(Fixture):
    def setUp(self):
        super().setUp()
        self.sections(app=['1.0.0'], generic=['1.0.0'], s1_frame=['1.0.0'], mirror_frame=['1.0.0'])

    def test_a_frame_is_its_app_lines_and_its_frame_release(self):
        p = self.plan(what='aqara-s1-plus')
        self.assertEqual({('line', 'aqara-s1-plus'), ('frame', 'aqara-s1-plus')}, due(p))
        self.assertEqual([], p['warnings'])

    def test_just_the_s1_app_or_just_its_frame(self):
        self.assertEqual({('line', 'aqara-s1-plus')}, due(self.plan(what='aqara-s1-plus:app')))
        self.assertEqual({('frame', 'aqara-s1-plus')}, due(self.plan(what='aqara-s1-plus:frame')))

    def test_the_mirrors_app_is_the_legacy_line_with_a_warning(self):
        p = self.plan(what='lululemon-mirror:app')
        self.assertEqual({('line', 'generic-android-legacy')}, due(p))
        self.assertEqual(['generic-android-legacy is also the app line of generic-android: releasing it for '
                          'lululemon-mirror updates those too'], p['warnings'])
        self.assertIn('**Warnings**', plan_mod.summary(p))

    def test_the_mirrors_frame_is_a_notice(self):
        p = self.plan(what='lululemon-mirror:frame')
        self.assertEqual({('frame', 'lululemon-mirror')}, due(p))
        self.assertEqual([], by(p, 'frame', 'lululemon-mirror')['assets'])

    def test_the_tablet_is_the_generic_line(self):
        p = self.plan(what='google-pixel-tablet')
        self.assertEqual({('line', 'generic-android')}, due(p))
        self.assertTrue(p['warnings'])

    def test_generic_android_is_both_its_lines(self):
        self.assertEqual({('line', 'generic-android'), ('line', 'generic-android-legacy')}, due(self.plan(what='generic-android')))

    def test_a_line_by_name_and_several_at_once(self):
        p = self.plan(what='generic-android-legacy, lululemon-mirror:frame aqara-s1-plus:frame')
        self.assertEqual({('line', 'generic-android-legacy'), ('frame', 'lululemon-mirror'), ('frame', 'aqara-s1-plus')}, due(p))

    def test_mistakes_fail(self):
        for what, words in [('nope', 'no frame or app line called nope'), ('google-pixel-tablet:frame', 'has no frame release'),
                            ('aqara-s1-plus:bundle', 'app or frame'), ('generic-android-legacy:frame', 'no frame or app line')]:
            with self.assertRaises(PlanError, msg=what) as e:
                self.plan(what=what)
            self.assertIn(words, str(e.exception), what)

    def test_a_named_item_must_be_due(self):
        self.sections(app=['1.0.0'])
        p = self.plan(what='lululemon-mirror:frame')
        self.assertEqual(['frames/lululemon-mirror/v1.0.0: no [1.0.0] section in frames/lululemon-mirror/FRAME-CHANGELOG.md'], p['errors'])
        self.sections(app=['1.0.0'], generic=['1.0.0'])
        p = self.plan(what='generic-android-legacy', released=['generic-android-legacy-v1.0.0'])
        self.assertTrue(any('released already' in e for e in p['errors']))


class MirrorFrameVersion(Fixture):
    """A Mirror frame release must be the version Kiln writes into the Mirrors it flashes (MIRROR_FRAME, #850)."""

    def setUp(self):
        super().setUp()
        self.sections(generic=['1.0.0'], mirror_frame=['1.0.0', '1.0.1'])

    def test_the_version_kiln_installs_passes(self):
        p = self.plan(what='lululemon-mirror:frame')
        self.assertEqual([], p['errors'])
        self.assertEqual({('frame', 'lululemon-mirror')}, due(p))

    def test_another_version_fails_named_or_with_all(self):
        self.mirror_version('1.0.0-dev')
        for what in ('lululemon-mirror:frame', 'lululemon-mirror', 'all'):
            p = self.plan(what=what)
            self.assertEqual([f"frames/lululemon-mirror/v1.0.0: {MIRROR_VERSION_FILE} installs frame version 1.0.0-dev, "
                              'not 1.0.0: bump it to 1.0.0 with the [1.0.0] section'], p['errors'], what)
        self.assertIn('**The plan fails**', plan_mod.summary(p))
        self.mirror_version('1.0.0')
        self.assertTrue(self.plan('1.0.1', what='lululemon-mirror:frame')['errors'])   # an older one fails too

    def test_a_dry_run_with_the_section_fails_too(self):
        self.mirror_version('1.0.0-dev')
        self.assertTrue(self.plan(dry_run=True)['errors'])

    def test_a_dry_run_on_the_unreleased_notes_only_warns(self):
        self.mirror_version('1.0.0-dev')
        p = self.plan('1.1.0', dry_run=True)
        self.assertEqual([], p['errors'])
        self.assertTrue(any(w.startswith(f'frames/lululemon-mirror/v1.1.0: {MIRROR_VERSION_FILE} installs frame version '
                                         '1.0.0-dev') and w.endswith('a real run would fail') for w in p['warnings']),
                        p['warnings'])

    def test_a_skipped_mirror_frame_is_not_checked(self):
        self.mirror_version('1.0.0-dev')
        self.sections(app=['1.0.0'], mirror_frame=['1.0.0'])
        p = self.plan(what='aqara-s1-plus:app')
        self.assertEqual([], p['errors'])
        self.assertEqual([], self.plan(what='lululemon-mirror:frame', released=['frames/lululemon-mirror/v1.0.0'])['warnings'])

    def test_the_declaration_is_read_once(self):
        p = self.root / MIRROR_VERSION_FILE
        p.write_text(p.read_text() + "\nexport const OTHER = { id: 'lululemon-mirror', version: '1.0.0' };\n")
        with self.assertRaises(PlanError) as e:
            self.plan(what='lululemon-mirror:frame')
        self.assertIn('not 2 times', str(e.exception))
        p.write_text('export const NOTHING = 1;\n')
        with self.assertRaises(PlanError) as e:
            self.plan(what='lululemon-mirror:frame')
        self.assertIn('not 0 times', str(e.exception))
        p.unlink()
        with self.assertRaises(PlanError) as e:
            self.plan(what='lululemon-mirror:frame')
        self.assertIn('does not exist', str(e.exception))

    def test_a_bad_version_file_entry(self):
        self.edit('lululemon-mirror', lambda d: d['release']['frame'].update(versionFile=''))
        with self.assertRaises(PlanError):
            self.plan()


class Channel(Fixture):
    def setUp(self):
        super().setUp()
        self.sections(app=['1.1.0'], generic=['1.1.0'], s1_frame=['1.1.0'], mirror_frame=['1.1.0'])

    def test_a_beta_is_a_pre_release_of_the_app_lines_only(self):
        p = self.plan('1.1.0', channel='beta')
        self.assertEqual({('line', 'aqara-s1-plus'), ('line', 'generic-android'), ('line', 'generic-android-legacy')}, due(p))
        s1 = by(p, 'line', 'aqara-s1-plus')
        self.assertTrue(s1['prerelease'])
        self.assertFalse(s1['latest'])  # never releases/latest
        self.assertEqual('Glassworks for the Aqara S1 Plus 1.1.0 beta', s1['title'])
        self.assertEqual('frame releases have no betas', by(p, 'frame', 'aqara-s1-plus')['why'])

    def test_a_named_frame_on_beta_fails(self):
        self.assertTrue(self.plan('1.1.0', channel='beta', what='aqara-s1-plus:frame')['errors'])

    def test_only_two_channels(self):
        with self.assertRaises(PlanError):
            self.plan('1.1.0', channel='nightly')


class DryRun(Fixture):
    def test_a_dry_run_before_the_sections_builds_everything_with_unreleased(self):
        p = self.plan(dry_run=True)
        self.assertEqual(5, len(due(p)))
        self.assertEqual({'Unreleased'}, {i['notes'] for i in p['items']})
        self.assertEqual(5, len(p['warnings']))
        self.assertIn('dry run', plan_mod.summary(p))
        self.assertIn('not published', plan_mod.summary(p))

    def test_with_its_sections_a_dry_run_uses_them(self):
        self.sections(app=['1.0.0'])
        p = self.plan(dry_run=True, what='aqara-s1-plus:app')
        self.assertEqual('1.0.0', by(p, 'line', 'aqara-s1-plus')['notes'])
        self.assertEqual([], p['warnings'])


class NewFramesAreData(Fixture):
    def test_a_device_on_an_existing_line_adds_no_build(self):
        self.sections(generic=['1.0.0'])
        self.frame('example-tablet', {'release': {'app': ['generic-android']}})
        p = self.plan()
        self.assertEqual(['example-tablet', 'generic-android', 'google-pixel-tablet', 'samsung-galaxy-tab-a-10-1-2016'],
                         by(p, 'line', 'generic-android')['frames'])
        self.assertEqual(1, sum(1 for i in p['items'] if i['name'] == 'generic-android' and i['kind'] == 'line'))
        self.assertIn('example-tablet', by(p, 'line', 'generic-android')['frames'])
        self.assertEqual({('line', 'generic-android'), ('line', 'generic-android-legacy')}, due(p))

    def test_a_frame_with_a_notice_adds_its_release(self):
        (self.root / 'frames/example-panel').mkdir(parents=True)
        (self.root / 'frames/example-panel/FRAME-CHANGELOG.md').write_text('## [Unreleased]\n\n## [1.0.0] - 2026-11-21\n\nFirst.\n')
        self.frame('example-panel', {'release': {'app': ['generic-android'], 'frame': {
            'bundle': 'notice', 'title': 'Example panel frame', 'changelog': 'frames/example-panel/FRAME-CHANGELOG.md'}}})
        p = self.plan(what='example-panel:frame')
        self.assertEqual({('frame', 'example-panel')}, due(p))
        self.assertEqual('frames/example-panel/v1.0.0', by(p, 'frame', 'example-panel')['tag'])


class BadData(Fixture):
    def test_a_line_no_frame_defines(self):
        self.frame('example-tablet', {'release': {'app': ['generic-android-tv']}})
        with self.assertRaises(PlanError) as e:
            self.plan()
        self.assertIn('no frame defines', str(e.exception))

    def test_a_line_defined_twice(self):
        line = json.loads((ROOT / 'frames/generic-android/design/frame.json').read_text())['release']['lines']['generic-android']
        self.frame('example-tablet', {'release': {'lines': {'generic-android': line}, 'app': ['generic-android']}})
        with self.assertRaises(PlanError) as e:
            self.plan()
        self.assertIn('defined by both', str(e.exception))

    def test_a_line_marked_latest_is_latest_on_a_stable_only(self):
        self.edit('aqara-s1-plus', lambda d: d['release']['lines']['aqara-s1-plus'].update(latest=True))
        self.sections(app=['1.1.0'])
        self.assertTrue(by(self.plan('1.1.0'), 'line', 'aqara-s1-plus')['latest'])
        self.assertFalse(by(self.plan('1.1.0', channel='beta'), 'line', 'aqara-s1-plus')['latest'])

    def test_two_latest_lines(self):
        self.edit('aqara-s1-plus', lambda d: d['release']['lines']['aqara-s1-plus'].update(latest=True))
        self.edit('generic-android', lambda d: d['release']['lines']['generic-android'].update(latest=True))
        with self.assertRaises(PlanError) as e:
            self.plan()
        self.assertIn('only one line', str(e.exception))

    def test_a_signed_bundle_names_its_script(self):
        self.edit('aqara-s1-plus', lambda d: d['release']['frame'].pop('script'))
        with self.assertRaises(PlanError):
            self.plan()

    def test_an_unknown_recipe(self):
        self.edit('generic-android', lambda d: d['release']['lines']['generic-android'].update(build='make'))
        with self.assertRaises(PlanError):
            self.plan()


class Outputs(Fixture):
    def test_what_the_later_jobs_read(self):
        self.sections(app=['1.0.0'], generic=['1.0.0'], mirror_frame=['1.0.0'])
        out = plan_mod.github_outputs(self.plan())
        self.assertEqual('aqara-s1-plus', json.loads(out['kiosk'])['name'])
        self.assertEqual(['generic-android', 'generic-android-legacy'], [i['name'] for i in json.loads(out['apps'])['include']])
        self.assertEqual('true', out['has_apps'])
        self.assertEqual('[]', out['bundles'])
        self.assertEqual('true', out['sign'])   # the S1 app's radio bundle
        self.assertEqual('true', out['any'])
        for v in out.values():
            self.assertNotIn('\n', v)
        plan = json.loads(out['plan'])
        self.assertEqual({'aqara-s1-plus', 'generic-android', 'generic-android-legacy', 'lululemon-mirror'},
                         {i['name'] for i in plan['items']})

    def test_only_a_notice_signs_nothing(self):
        self.sections(mirror_frame=['1.0.0'])
        out = plan_mod.github_outputs(self.plan())
        self.assertEqual(('', 'false', 'false'), (out['kiosk'], out['has_apps'], out['sign']))

    def test_the_command_line(self):
        self.sections(app=['1.0.0'])
        tmp = self.root / 'out'
        tmp.mkdir()
        (tmp / 'released').write_text('v0.4.4\ngeneric-android-v0.2.0\n')
        r = subprocess.run(['python3', str(ROOT / 'tools/release/plan.py'), '--root', str(self.root), '--version', '1.0.0',
                            '--released', str(tmp / 'released'), '--summary', str(tmp / 'summary'),
                            '--github-output', str(tmp / 'out'), '--json', str(tmp / 'plan.json')],
                           capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn('| `aqara-s1-plus` | app line (s1-kiosk), for aqara-s1-plus | 1.0.0 | `aqara-s1-plus-v1.0.0` | **build, check; publish**' + (' (latest)' if s1_marked_latest() else '') + ' |',
                      (tmp / 'summary').read_text())
        self.assertIn('kiosk={', (tmp / 'out').read_text())
        r = subprocess.run(['python3', str(ROOT / 'tools/release/plan.py'), '--root', str(self.root), '--version', '9.9.9',
                            '--github-output', str(tmp / 'out2')], capture_output=True, text=True)
        self.assertEqual(1, r.returncode)
        self.assertFalse((tmp / 'out2').exists())   # no outputs, so no later job runs

    def test_one_item_from_the_commit(self):
        r = subprocess.run(['python3', str(ROOT / 'tools/release/plan.py'), '--root', str(self.root), '--version', '1.0.0',
                            '--item', 'line:generic-android-legacy'], capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)
        item = json.loads(r.stdout)
        self.assertEqual((':generic-android:assembleLegacy', 23, True), (item['task'], item['minSdk'], item['writeSettings']))
        for bad in ('line:../x', 'frame:google-pixel-tablet', 'bundle:aqara-s1-plus'):
            r = subprocess.run(['python3', str(ROOT / 'tools/release/plan.py'), '--root', str(self.root), '--version', '1.0.0',
                                '--item', bad], capture_output=True, text=True)
            self.assertEqual(1, r.returncode, bad)


if __name__ == '__main__':
    unittest.main()
