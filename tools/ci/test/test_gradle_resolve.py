""".github/actions/gradle-resolve: configure the build once, and again after a failed download (docs/ci.md)."""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / '.github/actions/gradle-resolve/resolve.sh'

# What Gradle printed on 2026-11-19 when the plugin portal answered 404 (CI's app job, a rerun passed).
PORTAL_404 = """FAILURE: Build failed with an exception.
* What went wrong:
Error resolving plugin [id: 'com.android.application', version: '9.3.3', apply: false]
> A problem occurred configuring project ':build-logic'.
   > Could not resolve all artifacts for configuration 'classpath'.
      > Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.
        Searched in the following locations:
          - https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom
"""
# What it printed with kotlin-dsl's plugin marker missing from the cache and the repository (#631's injected run).
PLUGIN_MISSING = """FAILURE: Build failed with an exception.
* Where:
Build file '/home/runner/work/glassworks/glassworks/build-logic/build.gradle.kts' line: 2
* What went wrong:
Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.6'] was not found in any of the following sources:
- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:6.7.6')
  Searched in the following repositories:
    Gradle Central Plugin Repository
"""
COMPILE_ERROR = """FAILURE: Build failed with an exception.
* What went wrong:
Script compilation error:
  Line 3: plugins { id("glassworks.android") ; oops }
"""


class Resolve(unittest.TestCase):
    def run_with(self, outputs):
        """A fake ./gradlew that fails with each of `outputs` in turn, then succeeds."""
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            home = d / 'gradle-home'
            (home / 'caches/modules-2/metadata-2.107').mkdir(parents=True)
            (home / 'caches/modules-2/files-2.1').mkdir(parents=True)
            for i, text in enumerate(outputs):
                (d / f'fail{i + 1}.txt').write_text(text)
            gradlew = d / 'gradlew'
            gradlew.write_text('#!/bin/sh\n'
                               'n=$(($(cat calls 2>/dev/null || echo 0) + 1)); echo $n > calls\n'
                               '[ "$*" = "--no-daemon -q help" ] || { echo "unexpected args: $*"; exit 3; }\n'
                               'if [ -f fail$n.txt ]; then cat fail$n.txt; exit 1; fi\n')
            gradlew.chmod(0o755)
            env = dict(os.environ, GRADLE_USER_HOME=str(home), GW_RESOLVE_BACKOFF='0')
            r = subprocess.run(['bash', str(SCRIPT)], cwd=d, env=env, capture_output=True, text=True)
            calls = int((d / 'calls').read_text())
            metadata = (home / 'caches/modules-2/metadata-2.107').exists()
            files = (home / 'caches/modules-2/files-2.1').exists()
            fallback = home / 'init.d/glassworks-central-fallback.init.gradle'
            self.fallback = fallback.read_text() if fallback.exists() else None
            return r.returncode, r.stdout, calls, metadata, files

    def test_a_clean_build_configures_once_and_keeps_the_cache(self):
        code, out, calls, metadata, files = self.run_with([])
        self.assertEqual((code, calls, metadata, files), (0, 1, True, True))
        self.assertIn('Configured.', out)
        self.assertIsNone(self.fallback, 'no Central fallback unless a download failed')

    def test_a_404_from_the_portal_drops_the_metadata_and_tries_again(self):
        code, out, calls, metadata, files = self.run_with([PORTAL_404])
        self.assertEqual((code, calls), (0, 2), out)
        self.assertFalse(metadata, 'the missing-module record must go')
        self.assertTrue(files, 'the downloaded files stay')
        self.assertIn('Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.', out)
        self.assertIn('::warning::', out)
        self.assertIn('Configured on attempt 2 of 3.', out)
        # The rest of the job's Gradle runs look in Central too, after the build's own repositories.
        self.assertEqual(self.fallback, (SCRIPT.parent / 'central-fallback.init.gradle').read_text())
        self.assertIn('repos.mavenCentral()', self.fallback)

    def test_a_plugin_the_portal_did_not_answer_for_is_a_download_too(self):
        code, out, calls, metadata, _ = self.run_with([PLUGIN_MISSING])
        self.assertEqual((code, calls, metadata), (0, 2, False), out)
        self.assertIn("Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.6'] was not found", out)

    def test_another_failure_fails_at_once_with_gradles_output(self):
        code, out, calls, metadata, _ = self.run_with([COMPILE_ERROR])
        self.assertEqual((code, calls, metadata), (1, 1, True))
        self.assertIsNone(self.fallback)
        self.assertIn('Script compilation error', out)
        self.assertIn('::error::', out)

    def test_three_failed_downloads_fail_the_job(self):
        code, out, calls, _, _ = self.run_with([PORTAL_404] * 3)
        self.assertEqual((code, calls), (1, 3))
        self.assertIn("couldn't fetch its dependencies in 3 attempts", out)


class EveryGradleJobConfiguresFirst(unittest.TestCase):
    def test_each_gradle_job_runs_it_after_the_sdk_and_before_its_own_gradle_steps(self):
        yml = (ROOT / '.github/workflows/pr.yml').read_text()
        parts = re.split(r'^  ([a-z0-9-]+):\n', yml.split('\njobs:\n', 1)[1], flags=re.M)
        gradle = {job: body for job, body in zip(parts[1::2], parts[2::2]) if 'gradle/actions/setup-gradle@' in body}
        self.assertGreaterEqual(len(gradle), 7)
        for job, body in gradle.items():
            step = body.find('      - uses: ./.github/actions/gradle-resolve\n')
            self.assertGreater(step, body.find('sdkmanager --install'), job)
            self.assertGreater(body.find('sdkmanager --install'), 0, job)
            self.assertLess(step, body.find('./gradlew'), job)


if __name__ == '__main__':
    unittest.main()
