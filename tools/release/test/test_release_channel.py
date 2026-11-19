"""The release side of the update channel (#605): which tag publishes a beta, what the version check compares
against, and the publish steps of release.yml and generic-release.yml run with a fake gh.

Touches nothing outside a temporary folder: git runs in a scratch repo, gh is a stand-in on PATH that records its
arguments. Run from the repo root: python3 -m unittest discover -s tools/release/test -v
"""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
TAG_CHANNEL = ROOT / "tools/release/tag-channel.sh"
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


def step_script(workflow: str, step: str) -> str:
    """The `run: |` block of the step named [step] in [workflow], dedented."""
    lines = (ROOT / ".github/workflows" / workflow).read_text().splitlines()
    start = next(i for i, l in enumerate(lines) if l.strip() == f"- name: {step}")
    run_at = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "run: |")
    indent = len(lines[run_at]) - len(lines[run_at].lstrip())
    body = []
    for l in lines[run_at + 1:]:
        if l.strip() and len(l) - len(l.lstrip()) <= indent:
            break
        body.append(l)
    pad = min(len(l) - len(l.lstrip()) for l in body if l.strip())
    return "\n".join(l[pad:] for l in body) + "\n"


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


class TagChannelTest(Scratch):
    def setUp(self):
        super().setUp()
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        for c in (["init", "-q"], ["-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "x"]):
            run(["git", *c], cwd=self.repo, env=self.env()).check_returncode()

    def tag(self, name, *messages):
        args = ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "tag"]
        if messages:
            args.append("-a")
            for m in messages:
                args += ["-m", m]
        run(args + [name], cwd=self.repo, env=self.env()).check_returncode()

    def channel(self, *args):
        return run([str(TAG_CHANNEL), *args], cwd=self.repo, env=self.env())

    def test_a_plain_tag_is_stable(self):
        self.tag("generic-android-legacy-v0.2.0", "Glassworks for generic Android 0.2.0, legacy build")
        self.tag("v0.4.4")  # lightweight, as the S1's old tags
        for t in ("generic-android-legacy-v0.2.0", "v0.4.4"):
            r = self.channel(t)
            self.assertEqual(0, r.returncode, r.stderr)
            self.assertEqual("stable", r.stdout.strip())

    def test_channel_beta_in_the_message_is_a_beta(self):
        self.tag("aqara-s1-plus-v0.6.0", "Glassworks 0.6.0", "Channel: beta")
        self.tag("generic-android-v0.3.0", "Glassworks 0.3.0\n\nchannel:  Beta  ")
        self.assertEqual("beta", self.channel("aqara-s1-plus-v0.6.0").stdout.strip())
        self.assertEqual("beta", self.channel("generic-android-v0.3.0").stdout.strip())

    def test_the_word_beta_elsewhere_is_not_a_channel(self):
        self.tag("generic-android-v0.3.0", "Glassworks 0.3.0: beta testers asked for this")
        self.assertEqual("stable", self.channel("generic-android-v0.3.0").stdout.strip())

    def test_a_typo_fails_rather_than_publish_a_stable(self):
        self.tag("generic-android-v0.3.0", "Glassworks 0.3.0", "Channel: bet")
        r = self.channel("generic-android-v0.3.0")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("only beta or stable", r.stderr)
        self.tag("generic-android-v0.3.1", "Glassworks 0.3.1", "Channel: beta\nChannel: stable")
        self.assertNotEqual(0, self.channel("generic-android-v0.3.1").returncode)

    def test_a_missing_tag_fails_unless_a_dry_run(self):
        self.assertNotEqual(0, self.channel("generic-android-v9.0.0").returncode)
        r = self.channel("--missing-ok", "generic-android-v9.0.0")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual("stable", r.stdout.strip())


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


class PublishStepTest(Scratch):
    """The publish steps as the workflows have them, with gh standing in."""

    def workdir(self, apks):
        w = self.tmp / "work"
        (w / "dist").mkdir(parents=True)
        (w / "tools").symlink_to(ROOT / "tools")
        (w / "frames/generic-android").mkdir(parents=True)
        (w / "frames/generic-android/CHANGELOG.md").write_text("## [Unreleased]\n\n## [0.3.0] - 2026-11-20\n\nOne line.\n")
        for a in apks:
            (w / "dist" / a).write_text("x")
            (w / "dist" / f"{a}.sha256").write_text("x")
        rt = self.tmp / "runner"
        rt.mkdir()
        (rt / "notes.md").write_text("One line.\n")
        return w, rt

    def generic(self, channel):
        w, rt = self.workdir(["glassworks-generic-android-arm64-v8a-0.3.0.apk"])
        env = self.env(TAG="generic-android-v0.3.0", TITLE="Glassworks for generic Android 0.3.0", CHANGELOG_VERSION="0.3.0",
                       LINE="generic-android", VERSION="0.3.0", CHANNEL=channel, RUNNER_TEMP=str(rt),
                       FAKE_TAGS="generic-android-v0.2.0", GH_TOKEN="x")
        r = run(["bash", "-e", "-c", step_script("generic-release.yml", "Publish the GitHub Release")], cwd=w, env=env)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return next(c for c in self.gh_calls() if c.startswith("release create"))

    def s1(self, channel):
        w, rt = self.workdir(["s1-kiosk-0.6.0.apk", "s1-kiosk-arm64-v8a-0.6.0.apk"])
        env = self.env(GITHUB_REF_NAME="aqara-s1-plus-v0.6.0", VERSION="0.6.0", CHANNEL=channel, RUNNER_TEMP=str(rt), GH_TOKEN="x")
        r = run(["bash", "-e", "-c", step_script("release.yml", "Publish the GitHub Release")], cwd=w, env=env)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return next(c for c in self.gh_calls() if c.startswith("release create"))

    def test_a_generic_stable_is_as_before(self):
        c = self.generic("stable")
        self.assertIn("--latest=false", c)
        self.assertNotIn("--prerelease", c)
        self.assertIn("--title Glassworks for generic Android 0.3.0 --notes-file", c)

    def test_a_generic_beta_is_a_prerelease_and_never_latest(self):
        c = self.generic("beta")
        self.assertIn("--prerelease", c)
        self.assertIn("--latest=false", c)
        self.assertIn("Glassworks for generic Android 0.3.0 beta", c)

    def test_an_s1_stable_is_latest(self):
        c = self.s1("stable")
        self.assertIn("--latest", c.split())
        self.assertNotIn("--prerelease", c)
        self.assertIn("dist/s1-kiosk-0.6.0.apk", c)

    def test_an_s1_beta_keeps_releases_latest_on_the_stable(self):
        c = self.s1("beta").split()
        self.assertIn("--prerelease", c)
        self.assertIn("--latest=false", c)
        self.assertNotIn("--latest", c)

    def test_both_workflows_read_the_channel_from_the_tag(self):
        for wf in ("release.yml", "generic-release.yml"):
            script = step_script(wf, "Channel from the tag's message (stable, or beta)")
            self.assertIn("tools/release/tag-channel.sh", script, wf)
            self.assertIn("git fetch --no-tags --force origin", script, wf)

    def test_a_beta_doesnt_rebuild_the_installer(self):
        text = (ROOT / ".github/workflows/release.yml").read_text()
        block = text.split("- name: Rebuild the web installer", 1)[1].split("- name:", 1)[0]
        self.assertIn("if: env.CHANNEL != 'beta'", block)


if __name__ == "__main__":
    unittest.main()
