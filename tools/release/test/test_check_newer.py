"""tools/release/check-newer-than-released.sh: what a new version is compared against, per app line and frame.

Touches nothing outside a temporary folder: gh is a stand-in on PATH that records its arguments.
Run from the repo root: python3 -m unittest discover -s tools/release/test -v
"""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
CHECK_NEWER = ROOT / "tools/release/check-newer-than-released.sh"

FAKE_GH = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
case "$1 $2" in
  "release list") printf '%s\\n' $FAKE_TAGS ;;
  "release create") ;;
  *) echo "fake gh: unexpected $*" >&2; exit 2 ;;
esac
"""


def run(cmd, cwd=None, env=None):
    return subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)


class Scratch(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="release-channel-"))
        self.addCleanup(shutil.rmtree, self.tmp)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.log = self.tmp / "gh.log"

    def env(self, **more):
        e = dict(os.environ)
        e.update(PATH=f"{self.bin}:{e['PATH']}", FAKE_GH_LOG=str(self.log), GITHUB_REPOSITORY="example/repo",
                 GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1")
        e.update(more)
        return e

    def gh_calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []


class CheckNewerTest(Scratch):
    def check(self, line, version, tags):
        return run([str(CHECK_NEWER), line, version], env=self.env(FAKE_TAGS=" ".join(tags)))

    def test_betas_count(self):
        # The gh list includes pre-releases: only drafts are left out.
        tags = ["generic-android-v0.3.0", "generic-android-v0.2.0"]  # 0.3.0 is a beta
        r = self.check("generic-android", "0.2.1", tags)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not higher than generic-android's last release or beta, 0.3.0", r.stdout)
        self.assertEqual(0, self.check("generic-android", "0.3.1", tags).returncode)
        call = self.gh_calls()[0]
        self.assertIn("--exclude-drafts", call)
        self.assertNotIn("--exclude-pre-releases", call)

    def test_the_same_version_again_fails(self):
        self.assertNotEqual(0, self.check("generic-android-legacy", "0.2.0", ["generic-android-legacy-v0.2.0"]).returncode)

    def test_each_line_reads_only_its_own_tags(self):
        tags = ["generic-android-legacy-v2.0.0", "aqara-s1-plus-v1.0.0", "frames/lululemon-mirror/v1.0.0", "generic-android-v0.2.0"]
        self.assertEqual(0, self.check("generic-android", "0.3.0", tags).returncode)
        self.assertEqual(0, self.check("generic-android-legacy", "2.0.1", tags).returncode)
        self.assertNotEqual(0, self.check("generic-android-legacy", "1.0.0", tags).returncode)

    def test_the_s1_line_counts_its_plain_tags_too(self):
        tags = ["v0.4.4", "generic-android-v3.0.0", "aqara-s1-plus-v0.5.0"]
        self.assertEqual(0, self.check("aqara-s1-plus", "0.5.1", tags).returncode)
        self.assertNotEqual(0, self.check("aqara-s1-plus", "0.4.5", tags).returncode)
        self.assertNotEqual(0, self.check("aqara-s1-plus", "0.5.0", ["v0.5.0"]).returncode)
        self.assertEqual(0, self.check("aqara-s1-plus", "0.1.0", []).returncode)

    def test_a_frame_reads_its_frames_tags(self):
        # Panels refuse a lower frame version for good: a frame release must go up too.
        tags = ["frames/aqara-s1-plus/v1.0.0", "frames/lululemon-mirror/v2.0.0", "aqara-s1-plus-v3.0.0"]
        self.assertNotEqual(0, self.check("frames/aqara-s1-plus", "1.0.0", tags).returncode)
        self.assertEqual(0, self.check("frames/aqara-s1-plus", "1.0.1", tags).returncode)
        self.assertEqual(0, self.check("frames/lululemon-mirror", "2.0.1", tags).returncode)
        self.assertNotEqual(0, self.check("frames/lululemon-mirror", "1.9.9", tags).returncode)
        self.assertEqual(0, self.check("frames/google-pixel-tablet", "1.0.0", tags).returncode)

    def test_a_line_name_with_pattern_characters_is_refused(self):
        r = self.check("generic.android", "1.0.0", [])
        self.assertEqual(2, r.returncode)


if __name__ == "__main__":
    unittest.main()
