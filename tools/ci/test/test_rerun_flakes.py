"""tools/ci/rerun_flakes.py: which red runs get their failed jobs rerun, and the workflow that runs it (docs/ci.md, Reruns)."""
import importlib.util
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('rerun_flakes', ROOT / 'tools/ci/rerun_flakes.py')
rf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rf)

T = '2026-11-19T13:41:58.1234567Z '  # how each line of a job's log starts


def log(*lines):
    return ''.join(T + line + '\n' for line in lines)


def job(*bodies, step='./gradlew --no-daemon --parallel test', before=''):
    """A job's log as GitHub serves it: a step that passed, then the step that failed with `bodies` as its output."""
    return (log('##[group]Run actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09', '##[endgroup]', 'Syncing repository')
            + before
            + log(f'##[group]Run {step}', step, 'shell: /usr/bin/bash -e {0}', '##[endgroup]')
            + ''.join(bodies)
            + log('##[error]Process completed with exit code 1.', 'Post job cleanup.'))


def df(*bodies, **kw):
    return rf.download_failure(job(*bodies, **kw))


# From real failed jobs on this repo (2026-11-18 and 19), cut to the lines that matter. The mirror's address is RFC 5737's.
PORTAL_404 = log(
    'FAILURE: Build failed with an exception.',
    "Error resolving plugin [id: 'com.android.application', version: '9.3.3', apply: false]",
    "   > Could not resolve all artifacts for configuration 'classpath'.",
    '      > Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.',
    '        Searched in the following locations:',
    '          - https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom')
CENTRAL_JAR_404 = log(
    "    > Could not resolve all artifacts for configuration 'classpath'.",
    '       > Could not find error_prone_annotations-2.27.0.jar (com.google.errorprone:error_prone_annotations:2.27.0).',
    '         Searched in the following locations:',
    '             https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.27.0/error_prone_annotations-2.27.0.jar')
PLUGIN_NOT_FOUND = log(
    "Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.6'] was not found in any of the following sources:",
    "- Plugin Repositories (could not resolve plugin artifact 'org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:6.7.6')")
ROBOLECTRIC = log(
    'WallBaseHandOverTest > classMethod FAILED',
    '    java.lang.AssertionError at MavenArtifactFetcher.java:130',
    '        Caused by: java.util.concurrent.ExecutionException at AbstractFuture.java:168',
    '            Caused by: java.io.FileNotFoundException at HttpURLConnection.java:2030',
    '> Task :aqara-s1-plus-wall-base:testDebugUnitTest FAILED')
APT_503 = log(
    'W: Failed to fetch mirror+file:/etc/apt/apt-mirrors.txt/dists/noble/InRelease  503  Service Unavailable [IP: 192.0.2.7 80]',
    'E: Unable to fetch some archives, maybe run apt-get update or try with --fix-missing?')
SITE_PRERENDER = log(
    ' ⨯ [TypeError: fetch failed] {',
    '   [cause]: Error [SocketError]: other side closed',
    'Error occurred prerendering page "/blueprints". Read more: https://nextjs.org/docs/messages/prerender-error')
GRADLE_GET = log("   > Could not GET 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.3/gradle-9.3.3.pom'.",
                 '      > Received status code 502 from server: Bad Gateway')

# What must never rerun.
NO_SUCH_METHOD = log(
    "* What went wrong:",
    "A problem occurred evaluating project ':core'.",
    "> Could not find method implementation() for arguments [org.robolectric:robolectric:4.17] on object of type "
    "org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler.")
METHOD_NEXT_TO_DOWNLOAD = NO_SUCH_METHOD + PORTAL_404
TEST_FAILURE = log(
    'UartBaseLinkEmulatorTest > driverAgainstEmulator FAILED',
    '    java.lang.AssertionError at UartBaseLinkEmulatorTest.kt:88',
    '42 tests completed, 1 failed')
COMPILE_ERROR = log("e: file:///home/runner/work/glassworks/glassworks/core/src/main/java/dev/glassworks/Foo.kt:3:5 Unresolved reference 'bar'.")
CLARITY_FAILURE = log('FAIL: test_runner (test_runner.SlowSet.test_view_open)', 'AssertionError: 2.9 != 2.4')


class DownloadFailure(unittest.TestCase):
    def test_each_real_download_failure_is_one(self):
        for name, text, kind in [
            ('portal 404', PORTAL_404, 'Gradle: a dependency not found'),
            ('Central jar 404', CENTRAL_JAR_404, 'Gradle: a file not found'),
            ('plugin not found', PLUGIN_NOT_FOUND, 'Gradle: a plugin not found'),
            ('Robolectric', ROBOLECTRIC, 'Robolectric: a jar fetch failed'),
            ('apt 503', APT_503, 'apt: a mirror failed'),
            ('site prerender', SITE_PRERENDER, 'Node: a connection dropped'),
            ('Gradle GET 502', GRADLE_GET, 'Gradle: a request failed'),
        ]:
            with self.subTest(name):
                reason = df(text)
                self.assertIsNotNone(reason)
                self.assertTrue(reason.startswith(kind + ': '), reason)

    def test_the_line_it_names_is_the_one_that_matched(self):
        self.assertEqual(df(PORTAL_404),
                         'Gradle: a dependency not found: Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.')

    def test_a_mirrors_address_stays_out_of_the_summary(self):
        reason = df(APT_503)
        self.assertNotIn('192.0.2.7', reason)
        self.assertIn('[IP: <ip> 80]', reason)

    def test_could_not_find_method_is_never_a_download(self):
        self.assertIsNone(df(NO_SUCH_METHOD))
        # Not even next to a download line: the build script is broken either way.
        self.assertIsNone(df(METHOD_NEXT_TO_DOWNLOAD))

    def test_a_download_next_to_another_failure_is_not_one(self):
        for name, text in [('portal 404 and a test', PORTAL_404 + TEST_FAILURE),
                           ('Robolectric and a test', ROBOLECTRIC + TEST_FAILURE),
                           ('apt and Clarity', APT_503 + CLARITY_FAILURE),
                           ('a dropped connection and a compile error', SITE_PRERENDER + COMPILE_ERROR)]:
            with self.subTest(name):
                self.assertIsNone(df(text))

    def test_robolectric_counts_only_when_every_failed_test_says_its_fetch(self):
        two = ROBOLECTRIC + log('SystemLocaleApi23SetTest > classMethod FAILED', '    java.lang.AssertionError at MavenArtifactFetcher.java:130')
        self.assertIsNotNone(df(two))
        self.assertIsNone(df(ROBOLECTRIC + log('CantRunTest > shows FAILED', '    java.lang.AssertionError at CantRunTest.kt:40')))

    def test_only_the_last_try_of_a_step_that_retries_itself_counts(self):
        # pr.yml's site build: a fetch failed, it built once more, and that failed on something else (CodeRabbit, #632).
        retry = log("##[warning]The site build lost a connection to GitHub's API (fetch failed). Building once more.")
        missing = log("Module not found: Can't resolve './missing'", 'Build failed because of webpack errors')
        self.assertIsNone(df(SITE_PRERENDER, retry, missing, step='npm run build -w site'))
        # Its second try failed on a fetch too: that's a download.
        self.assertIsNotNone(df(SITE_PRERENDER, retry, SITE_PRERENDER, step='npm run build -w site'))
        # apt-install's fallback: the update that followed failed on something else.
        update = log("Not from the image's package lists alone; updating them.", "E: Package 'ffmpeg' has no installation candidate")
        self.assertIsNone(df(APT_503, update, step='bash apt-install'))

    def test_only_the_step_that_failed_counts(self):
        # An earlier step got past a download (and printed it); the step that failed failed on something else.
        earlier = log('##[group]Run npm ci', 'npm ci', '##[endgroup]', 'npm warn ECONNRESET, retrying',
                      '      > Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.')
        self.assertIsNone(df(log("Module not found: Can't resolve './missing'"), before=earlier))
        self.assertIsNotNone(df(PORTAL_404, before=earlier))

    def test_the_steps_own_script_isnt_its_output(self):
        # GitHub prints the script at the top of the step; its words aren't what happened.
        self.assertIsNone(df(log('Module not found'), step="grep -q 'fetch failed' \"$log\" ECONNRESET"))

    def test_a_log_without_a_failed_step_never_reruns(self):
        self.assertIsNone(rf.download_failure(PORTAL_404))

    def test_other_failures_are_not_downloads(self):
        for name, text in [('test', TEST_FAILURE), ('compile', COMPILE_ERROR), ('Clarity', CLARITY_FAILURE), ('empty', '')]:
            with self.subTest(name):
                self.assertIsNone(df(text))

    def test_colours_and_timestamps_dont_hide_a_match(self):
        coloured = '\x1b[36;1m' + PORTAL_404 + '\x1b[0m'
        self.assertIsNotNone(df(coloured))


class Decide(unittest.TestCase):
    def test_every_failed_job_on_a_download_reruns(self):
        rerun, line = rf.decide([('app', job(PORTAL_404)), ('site', job(SITE_PRERENDER))])
        self.assertTrue(rerun)
        self.assertIn('`app` (Gradle: a dependency not found', line)
        self.assertIn('`site` (Node: a connection dropped', line)

    def test_one_real_failure_keeps_the_whole_run_red(self):
        rerun, line = rf.decide([('app', job(ROBOLECTRIC)), ('scripts', job(CLARITY_FAILURE))])
        self.assertFalse(rerun)
        self.assertIn('`scripts` failed on something other than a download', line)

    def test_a_job_without_a_log_doesnt_rerun(self):
        rerun, line = rf.decide([('app', job(PORTAL_404)), ('root', None)])
        self.assertEqual((rerun, line), (False, 'no log for `root`'))

    def test_no_failed_job_no_rerun(self):
        self.assertEqual(rf.decide([]), (False, 'no failed job'))


class FakeGh:
    """gh for judge(): the run as GitHub returns it on each read, one failed job, the PR's head on each read."""

    def __init__(self, runs, logs, heads=None):
        self.runs, self.logs, self.heads, self.calls = list(runs), logs, list(heads or []), []

    def __call__(self, *args):
        self.calls.append(args)
        url = args[-1]
        if args[0] == 'run':
            return ''
        if url.endswith('/logs'):
            return job(self.logs[url.split('/')[-2]])
        if '/jobs' in url:
            return json.dumps([{'jobs': [{'id': int(i), 'name': f'job{i}', 'conclusion': 'failure'} for i in self.logs]}])
        if '/pulls/' in url:
            return json.dumps({'head': {'sha': self.heads.pop(0)}})
        return json.dumps(self.runs.pop(0) if len(self.runs) > 1 else self.runs[0])

    def reran(self):
        return any(c[:3] == ('run', 'rerun', '7') for c in self.calls)


def a_run(attempt=1, conclusion='failure', repo='o/r', prs=(5,)):
    return {'run_attempt': attempt, 'conclusion': conclusion, 'head_sha': 'abc',
            'head_repository': {'full_name': repo}, 'pull_requests': [{'number': n} for n in prs]}


class Judge(unittest.TestCase):
    def test_a_first_failed_attempt_on_downloads_reruns_its_failed_jobs(self):
        g = FakeGh([a_run(), a_run()], {'1': PORTAL_404, '2': ROBOLECTRIC}, heads=['abc', 'abc'])
        line = rf.judge('o/r', 7, gh=g)
        self.assertTrue(g.reran())
        self.assertIn(('run', 'rerun', '7', '--failed', '-R', 'o/r'), g.calls)
        self.assertTrue(line.startswith('Run 7: reran the failed jobs'), line)

    def test_a_new_attempt_while_the_logs_were_read_stops_it(self):
        g = FakeGh([a_run(), a_run(attempt=2, conclusion='failure')], {'1': PORTAL_404}, heads=['abc'])
        line = rf.judge('o/r', 7, gh=g)
        self.assertFalse(g.reran())
        self.assertIn('since its logs were read', line)

    def test_a_new_commit_on_the_pr_while_the_logs_were_read_stops_it(self):
        g = FakeGh([a_run(), a_run()], {'1': PORTAL_404}, heads=['abc', 'def'])
        line = rf.judge('o/r', 7, gh=g)
        self.assertFalse(g.reran())
        self.assertIn('PR #5 has a newer commit', line)

    def test_a_pr_that_moved_on_before_is_left_alone(self):
        g = FakeGh([a_run()], {'1': PORTAL_404}, heads=['def'])
        self.assertIn('PR #5 has a newer commit', rf.judge('o/r', 7, gh=g))
        self.assertFalse(any('/jobs' in c[-1] for c in g.calls), 'no logs read')

    def test_never_a_forks_run_or_a_second_attempt(self):
        for run in (a_run(repo='someone/fork'), a_run(attempt=2)):
            g = FakeGh([run], {'1': PORTAL_404}, heads=['abc'])
            rf.judge('o/r', 7, gh=g)
            self.assertFalse(g.reran())

    def test_a_dry_run_reruns_nothing(self):
        g = FakeGh([a_run()], {'1': PORTAL_404}, heads=['abc'])
        self.assertTrue(rf.judge('o/r', 7, gh=g, dry_run=True).endswith('(dry run).'))
        self.assertFalse(g.reran())

    def test_a_real_failure_reruns_nothing(self):
        g = FakeGh([a_run()], {'1': PORTAL_404, '2': TEST_FAILURE}, heads=['abc'])
        self.assertIn('`job2` failed on something other than a download', rf.judge('o/r', 7, gh=g))
        self.assertFalse(g.reran())


class TheWorkflow(unittest.TestCase):
    yml = (ROOT / '.github/workflows/rerun-download-flakes.yml').read_text()

    def test_it_follows_pr_checks_first_failed_attempts_from_this_repo_only(self):
        self.assertIn('  workflow_run:\n    workflows: ["PR checks"]\n    types: [completed]\n', self.yml)
        job_if = re.search(r'^    if: (.*(?:\n      .*)*)', self.yml, re.M).group(1)
        for condition in ("github.event.workflow_run.conclusion == 'failure'",
                          'github.event.workflow_run.run_attempt == 1',
                          'github.event.workflow_run.head_repository.full_name == github.repository'):
            self.assertIn(condition, job_if)

    def test_it_may_only_rerun_and_read(self):
        perms = self.yml.split('\npermissions:\n', 1)[1].split('\n\n', 1)[0]
        self.assertEqual(sorted(re.findall(r'^  ([a-z-]+): (\w+)$', perms, re.M)), [('actions', 'write'), ('contents', 'read')])
        self.assertNotIn('pull-requests', self.yml)
        self.assertNotIn('gh pr comment', self.yml)
        self.assertNotIn('issues/', self.yml)

    def test_it_never_checks_out_the_runs_code(self):
        self.assertNotIn('head_sha', self.yml.split('steps:', 1)[1].split('run:', 1)[0])
        self.assertNotIn('ref:', self.yml)
        self.assertIn('persist-credentials: false', self.yml)

    def test_it_says_what_it_did_in_the_job_summary(self):
        self.assertIn('--summary "$GITHUB_STEP_SUMMARY"', self.yml)


if __name__ == '__main__':
    unittest.main()
