#!/usr/bin/env python3
"""Which of web-deploy.yml's projects to deploy: each one whose inputs changed since its last successful deploy.

`pick` and `check` are read-only: one GitHub GraphQL query (the repo's deployments, with $GITHUB_TOKEN) and git
diff, writing nothing but their output and the run summary. `start` creates one GitHub deployment and `finish`
adds a status to it.

Usage:
  tools/ci/web_deploy.py pick --event <event> [--project both|site|kiln|installer|refract] [--summary <file>]
      site=, kiln=, installer=, refract= true|false, for $GITHUB_OUTPUT (the changes job)
  tools/ci/web_deploy.py check <project> --event <event> --started-for <sha> [--asked <project>] [--summary <file>]
      deploy=true|false, commit=<sha> and base=<sha>, in a deploy job once it holds the project's queue (--asked:
      what a run by hand asked for)
  tools/ci/web_deploy.py start <project> --commit <sha>
      just before the upload: a GitHub deployment of that commit (task TASK), in progress; prints id=<id>
  tools/ci/web_deploy.py finish <project> --id <id> --state success|failure
      after the upload: its outcome

The base. A project's base is the commit of its newest record that reached success: the commit the deploy job
checked out, marked only once Vercel (or Cloudflare, for Refract) took the upload. Not the push's `before`: a
deploy that was cancelled by a newer run or failed (Vercel's daily cap) left its change undeployed, and a later push
that doesn't touch the project would never deploy it. Not the deployment GitHub makes for each job in an environment either: that one has
the commit the run was started for, older than main at checkout, and succeeds when `check` skips the deploy too.

Unsettled. A record newer than the base that never reached success (the upload failed, the job was cancelled or
timed out, or GitHub didn't take the success status) may or may not be live. Then production can't be read from
the base, so the project deploys whatever the diff says.

Refract first. Kiln downloads every APK and image through Refract, so Kiln never deploys a commit whose Refract
inputs changed since Refract's last successful deploy: `check kiln` says deploy=false then (Kiln's record stays
where it was, so the run after Refract's deploy takes it). That covers a Refract deploy that failed, and a push that
landed on main between Refract's checkout and Kiln's. A run by hand that asks for Kiln plans Refract as a push would.

Events:
  - workflow_dispatch: the project(s) asked for, with no diff (a deploy by hand always deploys), and Refract by its
    diff when Kiln is asked for;
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
ENVIRONMENTS = {'site': 'glassworks.dev', 'kiln': 'kiln.glassworks.dev', 'installer': 's1.edm.sh',
                'refract': 'refract.glassworks.dev'}
URLS = {'site': 'https://www.glassworks.dev', 'kiln': 'https://kiln.glassworks.dev', 'installer': 'https://s1.edm.sh',
        'refract': 'https://refract.glassworks.dev'}
NAMES = {'site': 'glassworks.dev', 'kiln': 'Kiln', 'installer': 's1.edm.sh', 'refract': 'Refract'}

# Kiln's app: its npm workspaces (kiln/, the frames' recipes, packages/), what every workspace builds with, and
# the repo files kiln/scripts/stage-assets.mjs stages. Tests, mockups, Markdown and the Refract worker
# (kiln/workers, its own job below) don't go into it. The workflow and this picker redeploy it, to try a change to them.
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

# Refract, the Cloudflare Worker Kiln downloads every APK and image through: its code, its allowlist and the
# release lines generated from the frames (releases.json), and what it's deployed with. Kiln's job waits for this
# one, so a Kiln that names a new download never goes live before Refract serves it.
REFRACT = (
    'kiln/workers/refract', '.github/workflows/web-deploy.yml', 'tools/ci/web_deploy.py',
    '!**/*.md', '!kiln/workers/refract/test',
)
PATTERNS = {'kiln': KILN, 'installer': INSTALLER, 'refract': REFRACT}

# How many of a project's newest deployments to look through for a record (each deploy makes two: the job's and
# the record).
DEPTH = 50

# The task of a `record` deployment; the ones GitHub makes for a job have the task `deploy`.
TASK = 'web-deploy'

DISPATCH = {'both': ('site', 'kiln'), 'site': ('site',), 'kiln': ('kiln',), 'installer': ('installer',),
            'refract': ('refract',)}


def short(sha):
    return sha[:9] if sha else '?'


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True, text=True).stdout


def wants(project, changed, deleted=()):
    """The first changed path that deploys `project`, or None."""
    if project == 'site':
        runs, why = changes.select(list(changed), list(deleted))['site']
        return why if runs else None
    return next((f for f in changed if changes.matches(f, PATTERNS[project])), None)


class Last(tuple):
    """(base, unsettled): the commit of the newest record that succeeded, and of a newer record that didn't."""
    __slots__ = ()

    def __new__(cls, base=None, unsettled=None):
        return super().__new__(cls, (base, unsettled))

    base = property(lambda self: self[0])
    unsettled = property(lambda self: self[1])


def last(nodes):
    """Last for a project's deployments, newest first. A SUCCESS a later deploy made INACTIVE still counts."""
    unsettled = None
    for node in nodes:
        if node.get('task') != TASK:
            continue
        if any(s.get('state') == 'SUCCESS' for s in (node.get('statuses') or {}).get('nodes') or ()):
            return Last(node.get('commitOid') or None, unsettled)
        unsettled = unsettled or node.get('commitOid') or '?'
    return Last(None, unsettled)


def last_success(nodes):
    return last(nodes).base


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
    """{project: Last}: each project's last recorded deploy. Raises when GitHub can't say."""
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
    return {k: last(((data.get(f'p{i}') or {}).get('nodes')) or ()) for i, k in enumerate(keys)}


def env(repo, token, api):
    return (repo or os.environ['GITHUB_REPOSITORY'], token or os.environ['GITHUB_TOKEN'],
            api or os.environ.get('GITHUB_API_URL', 'https://api.github.com'))


def start(project, commit, repo=None, token=None, api=None, run_url=None, opener=urllib.request.urlopen):
    """A deployment of `commit` in the project's environment, in progress: its id."""
    repo, token, api = env(repo, token, api)
    run_url = run_url or '{}/{}/actions/runs/{}'.format(
        os.environ.get('GITHUB_SERVER_URL', 'https://github.com'), repo, os.environ.get('GITHUB_RUN_ID', ''))
    dep = call(f'{api}/repos/{repo}/deployments', {
        'ref': commit, 'task': TASK, 'environment': ENVIRONMENTS[project], 'auto_merge': False,
        'required_contexts': [], 'production_environment': True,
        'description': f'{NAMES[project]} deployed from this commit by web-deploy.yml'}, token, opener)
    if not isinstance(dep.get('id'), int):
        raise RuntimeError(f"GitHub didn't create the deployment: {dep.get('message') or dep}")
    call(f'{api}/repos/{repo}/deployments/{dep["id"]}/statuses', {'state': 'in_progress', 'log_url': run_url},
         token, opener)
    return dep['id']


def finish(project, dep_id, state, repo=None, token=None, api=None, opener=urllib.request.urlopen):
    """The upload's outcome on the deployment `start` made. Success makes its commit the next run's base."""
    repo, token, api = env(repo, token, api)
    body = {'state': state}
    if state == 'success':
        body['environment_url'] = URLS[project]
    call(f'{api}/repos/{repo}/deployments/{dep_id}/statuses', body, token, opener)


def decide(project, base, event, head='HEAD', unread=None, unsettled=None):
    """(deploy, why) for a push or a schedule. `unread`: why GitHub couldn't say, when it couldn't. `unsettled`:
    the commit of a newer deploy that never reached success, which may be live."""
    if unread:
        if event == 'schedule':
            return False, f'{unread}, so a scheduled run leaves it (a push deploys it)'
        return True, unread
    if unsettled:
        return True, f'the deploy from {short(unsettled)} never reached success, so what is live is unknown'
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
        out = {p: (p in chosen, None, 'asked for by hand' if p in chosen else 'not asked for') for p in projects}
        if 'kiln' in chosen and 'refract' in projects and 'refract' not in chosen:
            # Kiln goes out through Refract: Refract first, when what it serves changed.
            deploy, base, why = plan(['refract'], 'push', head=head, fetch=fetch)['refract']
            out['refract'] = (deploy, base, f'Kiln was asked for by hand, and {why}')
        return out
    unread = None
    try:
        found = (fetch or bases)(projects)
    except Exception as e:  # any failure to read the deployments: fail closed (or skip, on a schedule)
        print(f"::warning::Couldn't read the last deploys from GitHub ({e})", file=sys.stderr)
        found, unread = {}, "couldn't read the last deploy from GitHub"
    out = {}
    for p in projects:
        got = found.get(p)
        got = got if isinstance(got, Last) else Last(got)  # a commit alone: a record that succeeded, nothing after
        deploy, why = decide(p, got.base, event, head, unread, got.unsettled)
        out[p] = (deploy, got.base, why)
    return out


def refract_behind(head='HEAD', fetch=None):
    """Why Refract may not serve what Kiln at `head` downloads, or None: a Refract input changed since its last
    successful deploy, a newer Refract deploy never reached success, Refract has no recorded deploy, or GitHub
    couldn't say. Kiln waits then."""
    deploy, _, why = plan(['refract'], 'push', head=head, fetch=fetch)['refract']
    return why if deploy else None


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    pick = sub.add_parser('pick')
    pick.add_argument('--project', default='both')
    check = sub.add_parser('check')
    check.add_argument('project', choices=list(ENVIRONMENTS))
    check.add_argument('--started-for', required=True, help='the commit the run was started for (github.sha)')
    check.add_argument('--asked', help='what a run by hand asked for (inputs.project); the project itself if not given')
    st = sub.add_parser('start')
    st.add_argument('project', choices=list(ENVIRONMENTS))
    st.add_argument('--commit', required=True, help='the commit the job checked out and is about to upload')
    fin = sub.add_parser('finish')
    fin.add_argument('project', choices=list(ENVIRONMENTS))
    fin.add_argument('--id', required=True, type=int, help="start's deployment id")
    fin.add_argument('--state', required=True, choices=('success', 'failure'))
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

    if a.cmd == 'start':  # fails the job before the upload when GitHub won't take the record
        print(f'id={start(a.project, a.commit)}')
        return 0
    if a.cmd == 'finish':
        try:
            finish(a.project, a.id, a.state)
        except (OSError, ValueError, RuntimeError) as e:  # urllib's errors are OSErrors; JSON's are ValueErrors
            # The record stays unsettled, and an unsettled record makes the next run deploy.
            print(f"::error::GitHub didn't take {NAMES[a.project]}'s deploy outcome ({a.state}, {e}). Its record "
                  'stays in progress, so the next push or schedule deploys it again', file=sys.stderr)
            return 1
        return 0

    # check: the job deploys main as checked out now, which must have the commit the run was started for.
    commit = git('rev-parse', a.head).strip()
    try:
        git('merge-base', '--is-ancestor', a.started_for, commit)
    except subprocess.CalledProcessError:
        print(f'::error::main at {short(commit)} does not have {short(a.started_for)}, the commit this run was '
              f'started for: {NAMES[a.project]} was not deployed', file=sys.stderr)
        return 1
    deploy, base, why = plan([a.project], a.event, a.asked or a.project, head=commit)[a.project]
    if deploy and a.project == 'kiln':
        behind = refract_behind(commit)
        if behind:
            deploy, why = False, f'Refract may not serve its downloads yet ({behind}), so Kiln waits for Refract\'s deploy'
            print(f'::warning::Kiln not deployed: {why}', file=sys.stderr)
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
