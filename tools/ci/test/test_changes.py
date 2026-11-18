"""tools/ci/changes.py: which of pr.yml's jobs a change runs, and that pr.yml and docs/ci.md follow its table."""
import importlib.util
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('changes', ROOT / 'tools/ci/changes.py')
changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changes)


def running(*files, deleted=()):
    return {k for k, (runs, _) in changes.select(list(files), list(deleted)).items() if runs}


class PrTypes(unittest.TestCase):
    def test_docs_markdown_runs_the_unit_tests_that_read_docs_and_the_site_only(self):
        self.assertEqual(running('docs/esphome.md'), {'app', 'site'})

    def test_a_docs_image_runs_the_site_and_web_which_stages_one(self):
        self.assertEqual(running('docs/img/fel-button.jpg'), {'site', 'web'})

    def test_kiln_runs_web_only(self):
        self.assertEqual(running('kiln/apps/kiln/src/wizard/Flow.tsx'), {'web'})

    def test_the_root_side_runs_root_web_which_stages_its_scripts_and_the_site_which_reads_frames(self):
        self.assertEqual(running('frames/aqara-s1-plus/tools/root/s1kiosk_root.sh'), {'root', 'web', 'site'})

    def test_the_radio_also_runs_the_s1_size_check_which_bundles_it(self):
        self.assertEqual(running('frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh'), {'root', 'size', 'web', 'site'})

    def test_a_frame_module_runs_the_android_jobs(self):
        self.assertTrue({'app', 'app_apk', 'api23', 'size'} <= running('frames/aqara-s1-plus/android/src/main/java/a/B.kt'))

    def test_core_kotlin_runs_the_android_jobs_but_not_the_libraries_or_web(self):
        self.assertEqual(running('core/src/main/java/dev/glassworks/setup/SetupOverlay.kt'),
                         {'app', 'app_apk', 'api23', 'size', 'size_generic'})

    def test_a_library_runs_its_own_checks(self):
        self.assertEqual(running('libs/voice/src/main/java/dev/glassworks/voice/WakeWords.kt'),
                         {'app', 'app_apk', 'esphome', 'libs_api23', 'api23', 'libs_publish', 'wake_word', 'size', 'size_generic'})

    def test_an_s1_only_module_skips_the_generic_size_check(self):
        self.assertNotIn('size_generic', running('frames/aqara-s1-plus/panes/wall-base/src/main/java/a/B.kt'))

    def test_clarity_runs_its_tests(self):
        self.assertEqual(running('tools/perf/perfrun.py'), {'perf', 'perf_webrtc', 'web'})

    def test_workflows_and_shared_build_files_run_everything(self):
        for f in ('.github/workflows/core-emulators.yml', 'settings.gradle.kts', 'build.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties',
                  'package.json', 'package-lock.json', 'tools/ci/changes.py', 'frames/lululemon-mirror/design/frame.json'):
            self.assertEqual(running(f), set(changes.RULES), f)

    def test_a_module_build_file_is_not_the_root_one(self):
        self.assertNotEqual(running('core/build.gradle.kts'), set(changes.RULES))

    def test_a_packaged_licence_runs_the_publishing_dry_run(self):
        self.assertIn('libs_publish', running('LICENSES/BSD-3-Clause.txt'))

    def test_a_librarys_build_file_runs_the_site_which_lists_its_api_reference(self):
        self.assertIn('site', running('libs/voice/build.gradle.kts'))
        self.assertIn('site', running('libs/voice/gradle.properties'))
        self.assertNotIn('site', running('libs/voice/src/main/java/dev/glassworks/voice/WakeWords.kt'))

    def test_a_deleted_file_runs_the_site_whose_docs_may_link_to_it(self):
        self.assertIn('site', running('tools/mictest/x.py', deleted=['tools/mictest/x.py']))
        self.assertNotIn('site', running('tools/mictest/x.py'))


class WhatTheWorkspacesRead(unittest.TestCase):
    def test_web_runs_for_files_the_workspaces_read_from_the_modules(self):
        for f in ('testing/kotlin/dev/glassworks/testing/RepoFiles.kt', 'core/src/main/res/font/inter.ttf',
                  'core/src/main/assets/setup/setup.js', 'panes/camera/src/main/java/dev/glassworks/camera/pane.json',
                  'panes/camera/blueprints/x.yaml', 'tools/mirror/probe-app/src/main/java/sh/edm/s1kiosk/Probe.java'):
            self.assertIn('web', running(f), f)

    def test_web_skips_module_code_resources_tests_and_markdown(self):
        for f in ('core/src/main/java/a/B.kt', 'frames/aqara-s1-plus/android/src/main/res/values/strings.xml',
                  'panes/camera/src/test/resources/golden.txt', 'core/src/androidTest/java/a/C.kt', 'README.md'):
            self.assertNotIn('web', running(f), f)


class TheUnitTestsRepoInputs(unittest.TestCase):
    def test_each_file_tests_read_outside_the_modules_runs_app(self):
        gradle = (ROOT / 'build-logic/src/main/kotlin/glassworks.android.gradle.kts').read_text()
        files = re.search(r'val files = listOf\((.*?)\n    \)', gradle, re.S).group(1)
        for f in re.findall(r'"([^"]+)"', files):
            self.assertIn('app', running(f), f)


class Patterns(unittest.TestCase):
    def test_a_plain_path_matches_itself_and_below_not_a_longer_name(self):
        self.assertTrue(changes.matches('libs/voice/x', ['libs']))
        self.assertTrue(changes.matches('LICENSE', ['LICENSE']))
        self.assertFalse(changes.matches('LICENSES/x.txt', ['LICENSE']))

    def test_star_stays_in_one_folder_and_double_star_crosses(self):
        self.assertTrue(changes.matches('frames/a/android/x', ['frames/*/android']))
        self.assertFalse(changes.matches('frames/a/b/android/x', ['frames/*/android']))
        self.assertFalse(changes.matches('frames/a/androidx', ['frames/*/android']))
        self.assertTrue(changes.matches('docs/a/b.md', ['docs/**/*.md']))
        self.assertTrue(changes.matches('docs/b.md', ['docs/**/*.md']))

    def test_the_last_match_wins(self):
        self.assertFalse(changes.matches('core/a.kt', ['**/*.kt', '!core/**']))
        self.assertTrue(changes.matches('core/a.kt', ['!core/**', '**/*.kt']))


class FailsClosed(unittest.TestCase):
    def test_no_base_runs_everything(self):
        for base in ('', '0' * 40):
            self.assertTrue(all(r for r, _ in changes.from_git(base, 'HEAD').values()))

    def test_a_base_git_cannot_find_runs_everything(self):
        self.assertTrue(all(r for r, _ in changes.from_git('f' * 40, 'HEAD').values()))


class PrYmlFollowsTheTable(unittest.TestCase):
    yml = (ROOT / '.github/workflows/pr.yml').read_text()
    parts = re.split(r'^  ([a-z0-9-]+):\n', yml.split('\njobs:\n', 1)[1], flags=re.M)
    jobs = dict(zip(parts[1::2], parts[2::2]))

    def test_every_job_needs_changes_and_skips_at_job_level(self):
        for job, body in self.jobs.items():
            if job == 'changes':
                continue
            self.assertIn('    needs: changes\n', body, job)
            self.assertRegex(body, r"    if: \$\{\{ !cancelled\(\)", job)

    def test_every_key_is_an_output_of_changes_and_used(self):
        outputs = re.findall(r'\n      ([a-z0-9_]+): \$\{\{ steps\.pick\.outputs\.\1 \}\}', self.jobs['changes'])
        self.assertEqual(sorted(outputs), sorted(changes.RULES))
        for key in changes.RULES:
            self.assertIn(f"needs.changes.result != 'success' || needs.changes.outputs.{key} != 'false'", self.yml, key)

    def test_every_use_of_an_output_runs_when_changes_did_not_succeed(self):
        uses = re.findall(r"^ *(?:if: )?(.*needs\.changes\.outputs\.[a-z0-9_]+.*)$", self.yml, re.M)
        self.assertGreater(len(uses), len(changes.RULES))
        for line in uses:
            self.assertRegex(line, r"needs\.changes\.result != 'success' \|\| needs\.changes\.outputs\.[a-z0-9_]+ != 'false'", line)

    def test_a_job_with_a_rule_is_gated_on_it_at_job_level(self):
        for job, body in self.jobs.items():
            key = job.replace('-', '_')
            if key in changes.RULES:
                job_if = re.search(r'^    if: (.*)$', body, re.M).group(1)
                self.assertEqual(job_if, f"${{{{ !cancelled() && (needs.changes.result != 'success' || needs.changes.outputs.{key} != 'false') }}}}", job)


class DocsCarryTheTable(unittest.TestCase):
    def test_docs_ci_md_has_the_current_table(self):
        doc = (ROOT / 'docs/ci.md').read_text()
        self.assertIn(changes.table(), doc, 'run tools/ci/changes.py --table and paste it into docs/ci.md')


class Cli(unittest.TestCase):
    def test_all_prints_every_key_true(self):
        out = subprocess.run(['python3', str(ROOT / 'tools/ci/changes.py'), '--all'], check=True, capture_output=True, text=True).stdout
        self.assertEqual(out.split(), [f'{k}=true' for k in changes.RULES])


if __name__ == '__main__':
    unittest.main()
