#!/usr/bin/env python3
"""Which of web-deploy.yml's projects to deploy: each one whose inputs changed since its last successful deploy.

`pick` and `check` are read-only: one GitHub GraphQL query (the repo's deployments, with $GITHUB_TOKEN) and git
diff, writing nothing but their output and the run summary. `record` creates one GitHub deployment.

Usage:
  tools/ci/web_deploy.py pick --event <event> [--project both|site|kiln|installer] [--summary <file>]
      site=, kiln=, installer= true|false, for $GITHUB_OUTPUT (the changes job)
  tools/ci/web_deploy.py check <project> --event <event> --started-for <sha> [--summary <file>]
      deploy=true|false, commit=<sha> and base=<sha>, in a deploy job once it holds the project's queue
  tools/ci/web_deploy.py record <project> --commit <sha>
      after Vercel took the deploy: a GitHub deployment of that commit (task TASK) with a success status

The base. A project's base is the commit of its newest `record`: the commit the deploy job checked out and
Vercel took, written only once the upload succeeded. Not the push's `before`: a deploy that was cancelled by a newer
run or failed (Vercel's daily cap) left its change undeployed, and a later push that doesn't touch the project
would never deploy it. Not the deployment GitHub makes for each job in an environment either: that one has the
commit the run was started for, older than main at checkout, and succeeds when `check` skips the deploy too.
A cancelled job whose upload still reached Vercel records nothing, so the project deploys once more.

Events:
  - workflow_dispatch: the project(s) asked for, with no diff (a deploy by hand always deploys);
  - push: what changed since the base. With no record in the last DEPTH deployments, or when GitHub's API or
    git fails, it deploys, failing closed as changes.py does;
  - schedule: the same, so a deploy that failed is tried again without a push, and nothing deploys when nothing
    changed. When the API or git fails it deploys nothing: the free plan caps deploys a day, and the next push
    fails closed anyway.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import changes  # noqa: E402

# The GitHub environment each deploy job runs in (its `environment` in web-deploy.yml), where `record` writes.
ENVIRONMENTS = {'site': 'glassworks.dev', 'kiln': 'kiln.glassworks.dev', 'installer': 's1.edm.sh'}
URLS = {'site': 'https://www.glassworks.dev', 'kiln': 'https://kiln.glassworks.dev', 'installer': 'https://s1.edm.sh'}
NAMES = {'site': 'glassworks.dev', 'kiln': 'Kiln', 'installer': 's1.edm.sh'}

# Kiln's app: its npm workspaces (kiln/, the frames' recipes, packages/), what every workspace builds with, and
# the repo files kiln/scripts/stage-assets.mjs stages. Tests, mockups, Markdown and the Refract worker
# (kiln/workers, its own deploy) don't go into it. The workflow and this picker redeploy it, to try a change to them.
KILN = (
    'kiln', 'frames/*/installer', 'frames/*/design', 'packages', 'branding',
    'frames/lululemon-mirror/overlay', 'frames/aqara-s1-plus/tools/root', 'frames/aqara-s1-plus/tools/radio',
    'frames/aqara-s1-plus/tools/install', 'docs/img/fel-button.jpg',  # the S1 recipe imports it
    'package.json', 'package-lock.json', 'tsconfig.base.json',
    '.github/workflows/web-deploy.yml', 'tools/ci/web_deploy.py',
    '!**/*.md', '!kiln/test', '!kiln/packages/*/test', '!kiln/apps/*/test', '!kiln/mockup', '!kiln/workers', '!kiln/s1-redirect',
    '!frames/*/installer/test', '!frames/aqara-s1-plus/tools/*/test',
)

# s1.edm.sh's redirect.
INSTALLER = ('kiln/s1-redirect',)

# How many of a project's newest deployments to look through for a record (each deploy makes two: the job's and
# the record).
DEPTH = 50

# The task of a `record` deployment; the ones GitHub makes for a job have the task `deploy`.
TASK = 'web-deploy'

DISPATCH = {'both': ('site', 'kiln'), 'site': ('site',), 'kiln': ('kiln',), 'installer': ('installer',)}


def short(sha):
    return sha[:9] if sha else '?'


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True, text=True).stdout


def wants(project, changed, deleted=()):
    """The first changed path that deploys `project`, or None."""
    if project == 'site':
        runs, why = changes.select(list(changed), list(deleted))['site']
        return why if runs else None
    patterns = KILN if project == 'kiln' else INSTALLER
    return next((f for f in changed if changes.matches(f, patterns)), None)


def last_success(nodes):
    """The commit of the newest record that succeeded (a later one marks it INACTIVE, after its SUCCESS)."""
    for node in nodes:
        if node.get('task') != TASK:
            continue
        if any(s.get('state') == 'SUCCESS' for s in (node.get('statuses') or {}).get('nodes') or ()):
            return node.get('commitOid') or None
    return None


def query(projects):
    keys = list(projects)
    variables = ''.join(f', $e{i}: [String!]' for i in range(len(keys)))
    fields = ''.join(
        f' p{i}: deployments(environments: $e{i}, first: {DEPTH}, orderBy: {{field: CREATED_AT, direction: DESC}})'
        ' { nodes { task commitOid statuses(first: 10) { nodes { state } } } }' for i in range(len(keys)))
    return f'query($owner: String!, $name: String!{variables}) {{ repository(owner: $owner, name: $name) {{{fields} }} }}'


def call(url, payload, token, opener):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method='POST',
                                 headers={'Authorization': f'bearer {token}', 'Content-Type': 'application/json',
                                          'Accept': 'application/vnd.github+json', 'User-Agent': 'glassworks-web-deploy'})
    with opener(req, timeout=30) as res:
        return json.load(res)


def bases(projects, repo=None, token=None, url=None, opener=urllib.request.urlopen):
    """{project: commit or None}: each project's last recorded deploy. Raises when GitHub can't say."""
    repo = repo or os.environ['GITHUB_REPOSITORY']
    token = token or os.environ['GITHUB_TOKEN']
    url = url or os.environ.get('GITHUB_GRAPHQL_URL', 'https://api.github.com/graphql')
    owner, name = repo.split('/', 1)
    keys = list(projects)
    variables = {'owner': owner, 'name': name, **{f'e{i}': [ENVIRONMENTS[k]] for i, k in enumerate(keys)}}
    body = call(url, {'query': query(keys), 'variables': variables}, token, opener)
    if body.get('errors') or not (body.get('data') or {}).get('repository'):
        raise RuntimeError(f"GitHub's API: {body.get('errors') or 'no repository'}")
    data = body['data']['repository']
    return {k: last_success(((data.get(f'p{i}') or {}).get('nodes')) or ()) for i, k in enumerate(keys)}


def record(project, commit, repo=None, token=None, api=None, run_url=None, opener=urllib.request.urlopen):
    """A deployment of `commit` in the project's environment, with a success status: the next run's base."""
    repo = repo or os.environ['GITHUB_REPOSITORY']
    token = token or os.environ['GITHUB_TOKEN']
    api = api or os.environ.get('GITHUB_API_URL', 'https://api.github.com')
    run_url = run_url or '{}/{}/actions/runs/{}'.format(
        os.environ.get('GITHUB_SERVER_URL', 'https://github.com'), repo, os.environ.get('GITHUB_RUN_ID', ''))
    dep = call(f'{api}/repos/{repo}/deployments', {
        'ref': commit, 'task': TASK, 'environment': ENVIRONMENTS[project], 'auto_merge': False,
        'required_contexts': [], 'production_environment': True,
        'description': f'{NAMES[project]} deployed from this commit by web-deploy.yml'}, token, opener)
    if not isinstance(dep.get('id'), int):
        raise RuntimeError(f"GitHub didn't create the deployment: {dep.get('message') or dep}")
    call(f'{api}/repos/{repo}/deployments/{dep["id"]}/statuses', {
        'state': 'success', 'environment_url': URLS[project], 'log_url': run_url}, token, opener)
    return dep['id']


def decide(project, base, event, head='HEAD', unread=None):
    """(deploy, why) for a push or a schedule. `unread`: why GitHub couldn't say, when it couldn't."""
    if unread:
        if event == 'schedule':
            return False, f'{unread}, so a scheduled run leaves it (a push deploys it)'
        return True, unread
    if not base:
        return True, f'no recorded deploy in its last {DEPTH} deployments'
    try:
        git('cat-file', '-e', f'{base}^{{commit}}')
        rows = git('diff', '--no-renames', '--name-status', '-z', '--end-of-options', base, head).split('\0')
    except (subprocess.CalledProcessError, OSError) as e:
        if event == 'schedule':
            return False, f'git diff from {short(base)} failed ({e}), so a scheduled run leaves it'
        return True, f'git diff from {short(base)} failed ({e})'
    pairs = list(zip(rows[0::2], rows[1::2]))
    changed = [p for _, p in pairs]
    if not changed:
        return False, f'already deployed from {short(base)}, nothing changed since'
    hit = wants(project, changed, [p for s, p in pairs if s == 'D'])
    if hit is None:
        return False, f'nothing it builds from changed since {short(base)}'
    return True, f'{hit} changed since {short(base)}'


def plan(projects, event, project='both', head='HEAD', fetch=None):
    """{project: (deploy, base, why)}. `fetch`: bases() unless given."""
    if event == 'workflow_dispatch':
        chosen = DISPATCH.get(project or 'both', DISPATCH['both'])
        return {p: (p in chosen, None, 'asked for by hand' if p in chosen else 'not asked for') for p in projects}
    unread = None
    try:
        found = (fetch or bases)(projects)
    except Exception as e:  # any failure to read the deployments: fail closed (or skip, on a schedule)
        print(f"::warning::Couldn't read the last deploys from GitHub ({e})", file=sys.stderr)
        found, unread = {}, "couldn't read the last deploy from GitHub"
    out = {}
    for p in projects:
        deploy, why = decide(p, found.get(p), event, head, unread)
        out[p] = (deploy, found.get(p), why)
    return out


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    pick = sub.add_parser('pick')
    pick.add_argument('--project', default='both')
    check = sub.add_parser('check')
    check.add_argument('project', choices=list(ENVIRONMENTS))
    check.add_argument('--started-for', required=True, help='the commit the run was started for (github.sha)')
    rec = sub.add_parser('record')
    rec.add_argument('project', choices=list(ENVIRONMENTS))
    rec.add_argument('--commit', required=True, help='the commit the job checked out and deployed')
    for p in (pick, check):
        p.add_argument('--event', required=True)
        p.add_argument('--head', default='HEAD')
        p.add_argument('--summary', help='append what it decided, in Markdown')
    a = ap.parse_args(argv)

    if a.cmd == 'pick':
        result = plan(list(ENVIRONMENTS), a.event, a.project, a.head)
        for p, (deploy, _, _) in result.items():
            print(f'{p}={"true" if deploy else "false"}')
        if a.summary:
            with open(a.summary, 'a') as f:
                f.write('### Projects to deploy (tools/ci/web_deploy.py)\n\n| Project | Deploys | Last deploy | Why |\n'
                        '|---|---|---|---|\n')
                for p, (deploy, base, why) in result.items():
                    f.write(f'| {NAMES[p]} | {"yes" if deploy else "no"} | `{short(base)}` | {why} |\n')
        return 0

    if a.cmd == 'record':
        try:
            record(a.project, a.commit)
        except Exception as e:  # Vercel has it, but the next run doesn't know: it deploys again, which is safe
            print(f"::error::{NAMES[a.project]} is deployed from {short(a.commit)}, but GitHub didn't take the record "
                  f'({e}): the next run deploys it again', file=sys.stderr)
            return 1
        print(f'Recorded {NAMES[a.project]} as deployed from {a.commit}')
        return 0

    # check: the job deploys main as checked out now, which must have the commit the run was started for.
    commit = git('rev-parse', a.head).strip()
    try:
        git('merge-base', '--is-ancestor', a.started_for, commit)
    except subprocess.CalledProcessError:
        print(f'::error::main at {short(commit)} does not have {short(a.started_for)}, the commit this run was '
              f'started for: {NAMES[a.project]} was not deployed', file=sys.stderr)
        return 1
    deploy, base, why = plan([a.project], a.event, a.project, head=commit)[a.project]
    print(f'deploy={"true" if deploy else "false"}')
    print(f'commit={commit}')
    print(f'base={base or ""}')
    if a.summary:
        with open(a.summary, 'a') as f:
            what = f'deploys main at `{short(commit)}`' if deploy else 'not deployed'
            f.write(f'**{NAMES[a.project]}**: {what} (this run was started for `{short(a.started_for)}`): {why}.\n\n')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
