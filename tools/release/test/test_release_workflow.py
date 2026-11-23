"""The Release workflow's shape (.github/workflows/release.yml): one button, the keys kept apart as before.

Reads the workflow files only. Run from the repo root: python3 -m unittest discover -s tools/release/test -v
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / '.github/workflows'
RELEASE = (WORKFLOWS / 'release.yml').read_text()


def jobs(text):
    """Each job's text, by name (two-space keys under `jobs:`)."""
    body = text.split('\njobs:\n', 1)[1]
    parts = re.split(r'^  ([a-z][a-z0-9-]*):\n', body, flags=re.M)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts), 2)}


JOBS = jobs(RELEASE)


class OneButton(unittest.TestCase):
    def test_it_starts_only_from_the_button(self):
        on = RELEASE.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn('  workflow_dispatch:\n', on)
        self.assertNotIn('push', on)
        for name in ('version:', 'what:', 'channel:', 'dry_run:'):
            self.assertIn(name, on)
        self.assertIn('default: true', on.split('dry_run:', 1)[1])   # a run is a dry run unless asked

    def test_no_workflow_runs_on_a_release_tag(self):
        # A tag the publish job pushes, or one pushed by hand, must start nothing: the button is the only way in.
        # Only the libraries' own tags start a workflow: Maven Central's and npm's.
        library_tags = {'libs-release.yml': ['libs/*/v*'], 'npm-release.yml': ['npm/*/v*']}
        for wf in WORKFLOWS.glob('*.yml'):
            m = re.search(r'^\s+tags:\s*\[([^\]]*)\]', wf.read_text(), flags=re.M)
            if not m:
                continue
            patterns = [p.strip().strip("'\"") for p in m.group(1).split(',')]
            self.assertEqual(library_tags.get(wf.name), patterns, wf.name)

    def test_the_old_workflows_are_gone(self):
        for old in ('generic-release.yml', 'frame-release.yml'):
            self.assertFalse((WORKFLOWS / old).exists(), old)

    def test_the_jobs(self):
        self.assertEqual(['plan', 'inputs', 'sign-bundle', 'build-kiosk', 'build-apps', 'publish'], list(JOBS))


class Keys(unittest.TestCase):
    def secrets(self, job):
        return set(re.findall(r'secrets\.([A-Z0-9_]+)', JOBS[job]))

    def test_each_key_in_its_own_job(self):
        self.assertEqual(set(), self.secrets('plan') | self.secrets('inputs'))
        self.assertEqual({'S1_RADIO_BUNDLE_KEY'}, self.secrets('sign-bundle'))
        self.assertEqual({'S1_KEYSTORE_B64', 'S1_KEYSTORE_PASSWORD', 'S1_KEY_ALIAS', 'S1_KEY_PASSWORD'}, self.secrets('build-kiosk'))
        self.assertEqual({'GENERIC_KEYSTORE_B64', 'GENERIC_KEYSTORE_PASSWORD', 'GENERIC_KEY_PASSWORD'}, self.secrets('build-apps'))
        self.assertEqual({'RELEASE_TAG_DEPLOY_KEY'}, self.secrets('publish'))

    def test_the_environments(self):
        env = {j: (re.search(r'^    environment: ([a-z-]+)', t, flags=re.M) or [None, None])[1] for j, t in JOBS.items()}
        self.assertEqual({'plan': None, 'inputs': None, 'sign-bundle': 'radio-signing', 'build-kiosk': 'release-signing',
                          'build-apps': 'release-signing', 'publish': 'release-signing'}, env)

    def test_the_bundle_job_runs_only_first_party_artifact_actions_and_signs_only_main(self):
        uses = re.findall(r'uses: ([^@\s]+)@', JOBS['sign-bundle'])
        self.assertEqual({'actions/checkout', 'actions/download-artifact', 'actions/upload-artifact'}, set(uses))
        self.assertIn('git merge-base --is-ancestor HEAD origin/main', JOBS['sign-bundle'])
        # From the plan job it takes names only, and reads every fact from the commit.
        self.assertIn('--item "frame:$id"', JOBS['sign-bundle'])
        self.assertNotIn('fromJSON', JOBS['sign-bundle'])
        self.assertIn('sisdk-runtime.sha256', JOBS['sign-bundle'])

    def test_only_publish_writes(self):
        for job, text in JOBS.items():
            self.assertEqual(job == 'publish', 'contents: write' in text, job)
        self.assertIn('permissions:\n  contents: read\n', RELEASE)

    def test_every_action_is_pinned_by_commit(self):
        for wf in ('release.yml', 'discord-releases.yml'):
            for ref in re.findall(r'uses: \S+@(\S+)', (WORKFLOWS / wf).read_text()):
                self.assertRegex(ref, r'^[0-9a-f]{40}$', wf)

    def test_keys_are_removed(self):
        for job, f in (('sign-bundle', 'radio-bundle.pem'), ('build-kiosk', 'glassworks-s1-release.jks'),
                       ('build-apps', 'glassworks-release.jks'), ('publish', 'tag-key')):
            self.assertRegex(JOBS[job], r'if: always\(\)\n        run: rm -f "\$RUNNER_TEMP/' + re.escape(f), job)


class Publish(unittest.TestCase):
    def test_publish_waits_for_every_part_and_never_runs_on_a_dry_run(self):
        head = JOBS['publish'].split('steps:', 1)[0]
        self.assertIn('!inputs.dry_run', head)
        for need in ('needs.inputs.result', 'needs.sign-bundle.result', 'needs.build-kiosk.result', 'needs.build-apps.result'):
            self.assertIn(f"{need} == 'success'", head)
        self.assertIn('group: release-publish\n', head)
        self.assertIn('cancel-in-progress: false', head)
        self.assertIn('refs/heads/main', JOBS['publish'])

    def test_tags_go_over_ssh_to_github_only(self):
        self.assertIn('StrictHostKeyChecking=yes', JOBS['publish'])
        self.assertIn('tools/release/github-known-hosts', JOBS['publish'])
        self.assertIn('TAG_REMOTE="git@github.com:$GITHUB_REPOSITORY.git"', JOBS['publish'])
        hosts = (ROOT / 'tools/release/github-known-hosts').read_text().splitlines()
        self.assertTrue(all(l.startswith('github.com ') for l in hosts if l and not l.startswith('#')))
        self.assertIn('github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl', hosts)

    def test_the_plan_checks_who_and_where(self):
        self.assertIn('github.triggering_actor', JOBS['plan'])
        self.assertIn('[ "$role" = admin ]', JOBS['plan'])
        self.assertIn('tools/release/check-newer-than-released.sh', JOBS['plan'])

    def test_no_release_deploys_the_installer(self):
        # Kiln reads the releases at run time (its release route): a release redeploys nothing.
        self.assertNotIn('VERCEL', RELEASE)


if __name__ == '__main__':
    unittest.main()
