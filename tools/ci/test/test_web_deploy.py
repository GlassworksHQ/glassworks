"""tools/ci/web_deploy.py: each project deploys when its inputs changed since its last successful deploy."""
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('web_deploy', ROOT / 'tools/ci/web_deploy.py')
wd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wd)
WORKFLOW = (ROOT / '.github/workflows/web-deploy.yml').read_text()


def node(sha, *states, task='web-deploy'):
    return {'task': task, 'commitOid': sha, 'statuses': {'nodes': [{'state': s} for s in states]}}


class Repo:
    """A scratch git repo: commit(files) returns the new commit's sha."""

    def __init__(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = self.dir.name
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'ci@example.com')
        self.git('config', 'user.name', 'ci')
        self.git('config', 'commit.gpgsign', 'false')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.path, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, *files, delete=()):
        for f in files:
            p = Path(self.path, f)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(p.read_text() + 'x' if p.exists() else 'x')
        for f in delete:
            self.git('rm', '-q', f)
        self.git('add', '-A')
        self.git('commit', '-q', '--allow-empty', '-m', 'c')
        return self.git('rev-parse', 'HEAD')


class InRepo(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.dir.cleanup)
        cwd = os.getcwd()
        os.chdir(self.repo.path)
        self.addCleanup(os.chdir, cwd)

    def plan(self, found, event='push', **kw):
        return {p: deploy for p, (deploy, _, _) in wd.plan(list(wd.ENVIRONMENTS), event, fetch=lambda _: found, **kw).items()}


class LastSuccess(unittest.TestCase):
    def test_the_newest_deployment_that_succeeded(self):
        nodes = [node('c', 'FAILURE', 'IN_PROGRESS', 'QUEUED'), node('b', 'ERROR'), node('a', 'SUCCESS', 'IN_PROGRESS'),
                 node('z', 'SUCCESS')]
        self.assertEqual(wd.last_success(nodes), 'a')

    def test_a_success_a_later_deploy_made_inactive_still_counts(self):
        self.assertEqual(wd.last_success([node('b', 'ERROR'), node('a', 'INACTIVE', 'SUCCESS', 'IN_PROGRESS')]), 'a')

    def test_the_deployment_github_makes_for_a_job_is_not_one(self):
        # Its commit is the one the run was started for, and it succeeds when check skips the deploy.
        self.assertEqual(wd.last_success([node('t', 'SUCCESS', task='deploy'), node('d', 'SUCCESS')]), 'd')
        self.assertIsNone(wd.last_success([node('t', 'SUCCESS', 'IN_PROGRESS', task='deploy')]))

    def test_a_newer_record_that_never_succeeded_is_unsettled(self):
        self.assertEqual(wd.last([node('c', 'FAILURE', 'IN_PROGRESS'), node('b', 'IN_PROGRESS'), node('a', 'SUCCESS')]),
                         ('a', 'c'))
        self.assertEqual(wd.last([node('a', 'SUCCESS'), node('z', 'IN_PROGRESS')]), ('a', None))  # older: settled
        self.assertEqual(wd.last([node('b', 'IN_PROGRESS', task='deploy'), node('a', 'SUCCESS')]), ('a', None))
        self.assertEqual(wd.last([node('b', 'IN_PROGRESS')]), (None, 'b'))

    def test_a_deploy_still_running_or_cancelled_is_not_one(self):
        # A cancelled job's deployment ends ERROR, though its upload may have reached Vercel: deploying again is safe.
        self.assertIsNone(wd.last_success([node('b', 'IN_PROGRESS', 'QUEUED'), node('a', 'ERROR', 'QUEUED')]))
        self.assertIsNone(wd.last_success([]))


class Bases(unittest.TestCase):
    def fake(self, body):
        seen = {}

        class Res(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def opener(req, timeout):
            seen.setdefault('calls', []).append((req.full_url, json.loads(req.data)))
            seen['body'] = json.loads(req.data)
            seen['auth'] = req.headers['Authorization']
            return Res(json.dumps(body(req.full_url) if callable(body) else body).encode())
        return opener, seen

    def test_one_query_for_every_project_by_its_environment(self):
        opener, seen = self.fake({'data': {'repository': {
            'p0': {'nodes': [node('s2', 'FAILURE'), node('s1', 'SUCCESS')]},
            'p1': {'nodes': [node('k1', 'INACTIVE', 'SUCCESS')]},
            'p2': {'nodes': []}}}})
        got = wd.bases(['site', 'kiln', 'installer'], repo='o/r', token='t', url='https://api.invalid/graphql', opener=opener)
        self.assertEqual(got, {'site': ('s1', 's2'), 'kiln': ('k1', None), 'installer': (None, None)})
        v = seen['body']['variables']
        self.assertEqual((v['owner'], v['name'], v['e0'], v['e1'], v['e2']),
                         ('o', 'r', ['glassworks.dev'], ['kiln.glassworks.dev'], ['s1.edm.sh']))
        self.assertEqual(seen['auth'], 'bearer t')
        self.assertIn(f'first: {wd.DEPTH}', seen['body']['query'])
        self.assertIn(' task ', seen['body']['query'])

    def test_start_writes_an_in_progress_deployment_of_the_checked_out_commit(self):
        opener, seen = self.fake(lambda url: {'id': 7} if url.endswith('/deployments') else {})
        got = wd.start('kiln', 'abc123', repo='o/r', token='t', api='https://api.invalid', run_url='https://run.invalid/1',
                       opener=opener)
        self.assertEqual(got, 7)
        (u1, dep), (u2, status) = seen['calls']
        self.assertEqual(u1, 'https://api.invalid/repos/o/r/deployments')
        self.assertEqual((dep['ref'], dep['task'], dep['environment'], dep['required_contexts'], dep['auto_merge']),
                         ('abc123', 'web-deploy', 'kiln.glassworks.dev', [], False))
        self.assertEqual(u2, 'https://api.invalid/repos/o/r/deployments/7/statuses')
        self.assertEqual(status, {'state': 'in_progress', 'log_url': 'https://run.invalid/1'})

    def test_start_fails_when_github_makes_no_deployment(self):
        opener, seen = self.fake({'message': 'Conflict'})
        with self.assertRaises(RuntimeError):
            wd.start('site', 'abc', repo='o/r', token='t', api='https://api.invalid', run_url='x', opener=opener)
        self.assertEqual(len(seen['calls']), 1)

    def test_finish_marks_the_outcome(self):
        opener, seen = self.fake({})
        wd.finish('installer', 7, 'success', repo='o/r', token='t', api='https://api.invalid', opener=opener)
        wd.finish('installer', 8, 'failure', repo='o/r', token='t', api='https://api.invalid', opener=opener)
        self.assertEqual(seen['calls'], [
            ('https://api.invalid/repos/o/r/deployments/7/statuses', {'state': 'success', 'environment_url': 'https://s1.edm.sh'}),
            ('https://api.invalid/repos/o/r/deployments/8/statuses', {'state': 'failure'})])


class Push(InRepo):
    def test_a_kiln_change_a_cancelled_run_left_deploys_on_a_push_that_does_not_touch_kiln(self):
        # Today's gap: A changed Kiln, its deploy was cancelled or capped; B changes only a doc. B's diff from its
        # `before` (A) lists no Kiln input, but Kiln was last deployed from before A.
        deployed = self.repo.commit('kiln/apps/kiln/src/page.tsx', 'site/src/app/page.tsx')
        self.repo.commit('kiln/apps/kiln/src/wizard/Flow.tsx')  # A
        self.repo.commit('docs/esphome.md')  # B
        got = self.plan({'site': self.repo.git('rev-parse', 'HEAD'), 'kiln': deployed, 'installer': deployed})
        self.assertEqual(got, {'site': False, 'kiln': True, 'installer': False})

    def test_a_revert_after_the_deployed_commit_deploys(self):
        # A's run starts; B, pushed before A's job checks out main, changes Kiln again, so A's job deploys B. C
        # reverts B. The base is B, the commit Vercel took, so C deploys. From A, the commit the run was started
        # for, C would look like no change while B stays live.
        a = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        b = self.repo.commit('kiln/apps/kiln/src/flow.tsx')
        self.repo.git('revert', '--no-edit', 'HEAD')  # C
        self.assertTrue(self.plan({'site': b, 'kiln': b, 'installer': b})['kiln'])
        self.assertFalse(self.plan({'site': a, 'kiln': a, 'installer': a})['kiln'])  # what the old base missed

    def test_an_upload_whose_success_was_never_recorded_deploys_after_a_revert(self):
        # B is recorded as started but never reached success (Vercel took it, GitHub didn't take the status). C
        # reverts B, so its inputs equal A's, the base. B may be live, so C deploys.
        a = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        b = self.repo.commit('kiln/apps/kiln/src/flow.tsx')
        self.repo.git('revert', '--no-edit', 'HEAD')
        got = self.plan({'site': wd.Last(a), 'kiln': wd.Last(a, b), 'installer': wd.Last(a)})
        self.assertEqual(got, {'site': False, 'kiln': True, 'installer': False})
        got = self.plan({'site': wd.Last(a), 'kiln': wd.Last(a, b), 'installer': wd.Last(a)}, event='schedule')
        self.assertTrue(got['kiln'])

    def test_nothing_deploys_when_nothing_it_builds_from_changed(self):
        deployed = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        self.repo.commit('kiln/apps/kiln/test/flow.test.ts', 'kiln/README.md', 'kiln/workers/refract/src/a.ts')
        self.assertEqual(self.plan({'site': None, 'kiln': deployed, 'installer': deployed})['kiln'], False)

    def test_an_already_deployed_commit_deploys_nothing_even_the_site(self):
        # changes.py runs everything on an empty diff; here an empty diff means it is live already.
        head = self.repo.commit('site/src/app/page.tsx')
        self.assertEqual(self.plan({'site': head, 'kiln': head, 'installer': head}),
                         {'site': False, 'kiln': False, 'installer': False})

    def test_the_site_follows_changes_py_site_rule(self):
        deployed = self.repo.commit('site/src/app/page.tsx')
        self.repo.commit('docs/esphome.md')
        self.assertTrue(self.plan({'site': deployed, 'kiln': deployed, 'installer': deployed})['site'])
        deployed = self.repo.commit('core/src/main/java/a/B.kt')
        self.repo.commit('core/src/main/java/a/C.kt')
        self.assertFalse(self.plan({'site': deployed, 'kiln': deployed, 'installer': deployed})['site'])

    def test_a_deleted_file_deploys_the_site(self):
        deployed = self.repo.commit('core/src/main/java/a/B.kt')
        self.repo.commit(delete=('core/src/main/java/a/B.kt',))
        self.assertTrue(self.plan({'site': deployed, 'kiln': deployed, 'installer': deployed})['site'])

    def test_the_redirect_deploys_the_installer_and_not_kiln(self):
        deployed = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        self.repo.commit('kiln/s1-redirect/vercel.json')
        self.assertEqual(self.plan({'site': deployed, 'kiln': deployed, 'installer': deployed}),
                         {'site': False, 'kiln': False, 'installer': True})

    def test_never_deployed_fails_closed(self):
        self.repo.commit('docs/esphome.md')
        self.assertEqual(self.plan({'site': None, 'kiln': None, 'installer': None}),
                         {'site': True, 'kiln': True, 'installer': True})

    def test_a_base_git_does_not_have_fails_closed(self):
        self.repo.commit('docs/esphome.md')
        self.assertTrue(self.plan({'site': None, 'kiln': 'f' * 40, 'installer': None})['kiln'])

    def test_an_api_failure_fails_closed(self):
        self.repo.commit('docs/esphome.md')

        def broken(_):
            raise OSError('network down')
        got = wd.plan(list(wd.ENVIRONMENTS), 'push', fetch=broken)
        self.assertEqual({p: d for p, (d, _, _) in got.items()}, {'site': True, 'kiln': True, 'installer': True})


class Schedule(InRepo):
    def test_a_capped_deploy_is_tried_again_and_the_rest_left(self):
        deployed = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        self.repo.commit('kiln/apps/kiln/src/wizard/Flow.tsx')
        head = self.repo.git('rev-parse', 'HEAD')
        self.assertEqual(self.plan({'site': head, 'kiln': deployed, 'installer': head}, event='schedule'),
                         {'site': False, 'kiln': True, 'installer': False})

    def test_a_project_that_never_deployed_is_tried(self):
        self.repo.commit('docs/esphome.md')
        self.assertTrue(self.plan({'site': None, 'kiln': None, 'installer': None}, event='schedule')['installer'])

    def test_an_api_failure_deploys_nothing(self):
        self.repo.commit('docs/esphome.md')

        def broken(_):
            raise OSError('network down')
        got = wd.plan(list(wd.ENVIRONMENTS), 'schedule', fetch=broken)
        self.assertEqual({p: d for p, (d, _, _) in got.items()}, {'site': False, 'kiln': False, 'installer': False})


class Dispatch(unittest.TestCase):
    def test_by_hand_deploys_what_was_asked_with_no_diff(self):
        def never(_):
            raise AssertionError('a deploy by hand reads no deployments')
        for project, want in (('both', {'site', 'kiln'}), ('site', {'site'}), ('kiln', {'kiln'}),
                              ('installer', {'installer'})):
            got = wd.plan(list(wd.ENVIRONMENTS), 'workflow_dispatch', project, fetch=never)
            self.assertEqual({p for p, (d, _, _) in got.items() if d}, want, project)


class Check(InRepo):
    def run_check(self, started_for, event='push'):
        out = io.StringIO()
        real, sys.stdout = sys.stdout, out
        try:
            code = wd.main(['check', 'kiln', '--event', event, '--started-for', started_for])
        finally:
            sys.stdout = real
        return code, dict(line.split('=', 1) for line in out.getvalue().splitlines() if '=' in line)

    def test_a_queued_job_skips_when_the_deploy_before_it_had_every_change(self):
        # The run that held the queue deployed Kiln; this one was queued for a later push that didn't touch it.
        first = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        mine = self.repo.commit('docs/esphome.md')
        with mock.patch.object(wd, 'bases', lambda _: {'kiln': first}):
            code, out = self.run_check(mine)
        self.assertEqual((code, out['deploy'], out['commit'], out['base']), (0, 'false', mine, first))

    def test_it_deploys_main_as_it_is_now(self):
        old = self.repo.commit('site/x')
        mine = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        newer = self.repo.commit('kiln/apps/kiln/src/other.tsx')
        with mock.patch.object(wd, 'bases', lambda _: {'kiln': old}):
            code, out = self.run_check(mine)
        self.assertEqual((code, out['deploy'], out['commit']), (0, 'true', newer))

    def test_a_job_started_by_hand_always_deploys(self):
        mine = self.repo.commit('docs/esphome.md')
        with mock.patch.object(wd, 'bases', lambda _: {'installer': mine}):
            out = io.StringIO()
            with mock.patch.object(sys, 'stdout', out):
                code = wd.main(['check', 'installer', '--event', 'workflow_dispatch', '--started-for', mine])
        self.assertEqual((code, 'deploy=true' in out.getvalue()), (0, True))

    def test_main_without_the_commit_it_was_started_for_fails(self):
        base = self.repo.commit('site/x')
        self.repo.git('checkout', '-q', '-b', 'other')
        elsewhere = self.repo.commit('kiln/apps/kiln/src/page.tsx')
        self.repo.git('checkout', '-q', 'main')
        self.repo.commit('kiln/apps/kiln/src/other.tsx')
        with mock.patch.object(wd, 'bases', lambda _: {'kiln': base}):
            code, _ = self.run_check(elsewhere)
        self.assertEqual(code, 1)


class Workflow(unittest.TestCase):
    def test_each_deploy_job_runs_in_the_environment_the_picker_reads(self):
        for project, env in wd.ENVIRONMENTS.items():
            job = re.search(rf'^  {project}:\n(.*?)(?=^  \S|\Z)', WORKFLOW, re.M | re.S)
            self.assertIsNotNone(job, project)
            self.assertRegex(job.group(1), rf'environment:\n\s+name: {re.escape(env)}\n\s+url: {re.escape(wd.URLS[project])}\n',
                             project)
            self.assertTrue(f'tools/ci/web_deploy.py check {project}' in job.group(1), project)
            self.assertTrue('deployments: write' in job.group(1), project)

    def test_each_job_records_its_upload_around_the_deploy_step(self):
        for project in wd.ENVIRONMENTS:
            job = re.search(rf'^  {project}:\n(.*?)(?=^  \S|\Z)', WORKFLOW, re.M | re.S).group(1)
            names = [n for n in re.findall(r'^      - (?:name: (.*)|uses: )', job, re.M) if n]
            deploy = next(i for i, n in enumerate(names) if n.startswith('Deploy'))
            self.assertEqual(names[deploy - 1:], ['Record the deploy as started', names[deploy],
                                                  'Record the deploy as done', 'Record the deploy as failed'], project)

            def step(name):
                return job[job.index(f'name: {name}'):].split('\n      - ')[0]
            self.assertTrue("if: steps.check.outputs.deploy == 'true'" in step('Record the deploy as started'), project)
            self.assertTrue(f'web_deploy.py start {project} --commit "$COMMIT"' in step('Record the deploy as started'))
            self.assertTrue("if: steps.check.outputs.deploy == 'true'" in step('Record the deploy as done'), project)
            self.assertTrue(f'web_deploy.py finish {project} --id "$ID" --state success' in step('Record the deploy as done'))
            self.assertTrue("if: failure() && steps.start.outputs.id != ''" in step('Record the deploy as failed'), project)

    def test_the_workflow_picks_with_the_script_and_retries_on_a_schedule(self):
        self.assertTrue('tools/ci/web_deploy.py pick' in WORKFLOW)
        self.assertRegex(WORKFLOW, r'schedule:\n\s+- cron:')
        self.assertTrue('deployments: read' in WORKFLOW)

    def test_a_failing_picker_fails_its_step(self):
        # GitHub's default `run` shell has no pipefail, so `python3 ... | tee` would pass with the picker failed.
        self.assertRegex(WORKFLOW, r'\ndefaults:\n  run:\n    shell: bash\n')

    def test_a_running_deploy_is_never_cancelled(self):
        # Vercel counts a cancelled deploy against the daily cap; a newer run waits, and its check skips if it can.
        self.assertFalse('cancel-in-progress: true' in WORKFLOW)

    def test_stage_assets_points_at_the_kiln_list(self):
        self.assertTrue('KILN in tools/ci/web_deploy.py' in (ROOT / 'kiln/scripts/stage-assets.mjs').read_text())


class KilnPatterns(unittest.TestCase):
    def test_kilns_inputs(self):
        for f in ('kiln/apps/kiln/src/page.tsx', 'frames/aqara-s1-plus/installer/src/recipe.ts',
                  'frames/lululemon-mirror/design/frame.json', 'packages/frame-card/src/Card.tsx',
                  'branding/glass-tile/x.png', 'docs/img/fel-button.jpg', 'package-lock.json',
                  'frames/aqara-s1-plus/tools/root/s1kiosk_root.sh', '.github/workflows/web-deploy.yml',
                  'tools/ci/web_deploy.py'):
            self.assertEqual(wd.wants('kiln', [f]), f, f)

    def test_not_kilns_inputs(self):
        for f in ('kiln/README.md', 'kiln/test/a.test.ts', 'kiln/packages/fel/test/a.test.ts', 'kiln/apps/kiln/test/a.tsx',
                  'kiln/mockup/a.html', 'kiln/workers/refract/src/a.ts', 'kiln/s1-redirect/vercel.json',
                  'frames/aqara-s1-plus/installer/test/a.test.ts', 'frames/aqara-s1-plus/tools/root/test/a.sh',
                  'site/src/app/page.tsx', 'docs/esphome.md', 'core/src/main/java/a/B.kt'):
            self.assertIsNone(wd.wants('kiln', [f]), f)


if __name__ == '__main__':
    unittest.main()
