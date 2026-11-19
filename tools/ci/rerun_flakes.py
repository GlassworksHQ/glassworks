#!/usr/bin/env python3
"""Reruns the failed jobs of a red `PR checks` run once, when every failed job failed on a download (docs/ci.md, Reruns).

.github/workflows/rerun-download-flakes.yml runs this after a first attempt of pr.yml ends in failure. A download that
fails on one runner tends to keep failing there for a minute or more (#631: the plugin portal and Maven Central both
answered 404 three times in a row, 45 s apart), and a rerun on a fresh runner passed every time (5 of 5 on
2026-11-18 and 19). So it reruns the failed jobs, through `gh run rerun --failed`, and only when each one's log
shows a download failure and no other failure. One real failure anywhere, a job without a log, a run that isn't the
first attempt, or a PR with a newer commit: no rerun. It checks the run and the PR again just before the rerun.

Reads the run's jobs and logs and asks GitHub for the rerun, with `gh` (GH_TOKEN). Writes one line to the job summary
when given --summary. Never comments on a PR.

    tools/ci/rerun_flakes.py --repo OWNER/REPO --run RUN_ID [--summary FILE] [--dry-run]
    tools/ci/rerun_flakes.py --repo OWNER/REPO --run RUN_ID --dry-run --any-attempt   # what it would have decided
                                                                                       # on a past run's first attempt
"""
import argparse
import json
import re
import subprocess
import sys

# What a failed download looks like in a job's log, from real failures on this repo's runners (2026-11-18 and 19).
DOWNLOAD = [
    # Gradle, a module the repository answered 404 for: "Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10."
    ('Gradle: a dependency not found', re.compile(r'Could not find [\w.-]+:[\w.-]+:[\w.+-]+\.?$', re.M)),
    # Gradle, a file the repository answered 404 for: "Could not find okio-jvm-3.15.0.jar (com.squareup.okio:okio-jvm:3.15.0)."
    ('Gradle: a file not found', re.compile(r'Could not find [\w.+-]+\.(?:jar|aar|pom|module|klib) \([\w.-]+:[\w.-]+:[\w.+-]+\)')),
    # Gradle, a plugin: "Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.6'] was not found in any of the
    # following sources: … could not resolve plugin artifact …"
    ('Gradle: a plugin not found', re.compile(r"Plugin \[id: '[\w.-]+'(?:, version: '[\w.+-]+')?\] was not found in any of the following sources")),
    # Gradle, the network: "Could not GET 'https://…'", "Could not HEAD", "Received status code 502 from server".
    ('Gradle: a request failed', re.compile(r"Could not (?:GET|HEAD|download) '?https?://|Received status code 5\d\d from server")),
    # Robolectric fetching its android-all jar while the tests run: "java.lang.AssertionError at MavenArtifactFetcher.java:130".
    ('Robolectric: a jar fetch failed', re.compile(r'at MavenArtifactFetcher\.java:\d+')),
    # apt: "E: Failed to fetch http://… 503  Service Unavailable", "Unable to fetch some archives".
    ('apt: a mirror failed', re.compile(r'Failed to fetch \S+\s+(?:5\d\d|Connection failed|Could not connect|Temporary failure)|Unable to fetch some archives')),
    # Node's fetch (the site's prerender of /blueprints): "TypeError: fetch failed" with "other side closed".
    ('Node: a connection dropped', re.compile(r'fetch failed\][\s\S]{0,200}?(?:other side closed|ECONNRESET|ETIMEDOUT|EAI_AGAIN)|SocketError: other side closed')),
    # npm and other tools: a reset, a timeout or a DNS miss.
    ('a connection dropped', re.compile(r'\b(?:ECONNRESET|ETIMEDOUT|EAI_AGAIN)\b|Connection reset by peer|TLS handshake timeout')),
]

# A failure that isn't a download. A log with any of these never reruns, whatever download line it also has.
OTHER_FAILURE = [
    re.compile(r'Could not find method '),            # a build script error ("Could not find method implementation() …")
    re.compile(r'Script compilation error'),          # a Gradle build script that doesn't compile
    re.compile(r'^e: (?:file:|/)', re.M),             # Kotlin's compiler
    re.compile(r': error: |\berror TS\d+'),           # javac, clang, TypeScript
    re.compile(r'^\s*\d+:\d+\s+error\s', re.M),       # ESLint
    re.compile(r'^(?:FAIL|ERROR): \w', re.M),          # Python's unittest
    re.compile(r'^\s*\u2716 |^not ok \d+', re.M),      # node --test ("✖ name"), TAP
]
# A Gradle test that failed, and the line under it with the cause: only Robolectric's fetch counts as a download.
TEST_FAILED = re.compile(r'^\S[^\n]* > [^\n]* FAILED\n([^\n]*)', re.M)

# A mirror's address in apt's or curl's output ("[IP: 192.0.2.7 80]", "http://192.0.2.7/"): kept out of the summary.
IPV4 = re.compile(r'(?<=IP: )\d{1,3}(?:\.\d{1,3}){3}|(?<=://)\d{1,3}(?:\.\d{1,3}){3}')
TIMESTAMP = re.compile(r'^\d{4}-\d\d-\d\dT[\d:.]+Z ', re.M)
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def clean(log):
    """The log as text: no ANSI colours, no timestamp at the start of each line."""
    return TIMESTAMP.sub('', ANSI.sub('', log))


def download_failure(log):
    """What the log says failed to download ('<kind>: <line>'), or None when it shows no download failure or also shows
    any other failure (a test, a compile error, a build script error)."""
    text = clean(log)
    if any(p.search(text) for p in OTHER_FAILURE):
        return None
    if any('MavenArtifactFetcher' not in m.group(1) for m in TEST_FAILED.finditer(text)):
        return None
    for kind, pattern in DOWNLOAD:
        m = pattern.search(text)
        if m:
            start = text.rfind('\n', 0, m.start()) + 1
            end = text.find('\n', m.start())
            line = text[start:end if end >= 0 else len(text)]
            return f"{kind}: {IPV4.sub('<ip>', line.strip(' >'))[:160]}"
    return None


def decide(failed):
    """failed: [(job name, log or None)]. Returns (rerun?, one line for the summary)."""
    if not failed:
        return False, 'no failed job'
    why = []
    for name, log in failed:
        reason = download_failure(log) if log else None
        if reason is None:
            return False, f'`{name}` failed on something other than a download' if log else f'no log for `{name}`'
        why.append(f'`{name}` ({reason})')
    return True, 'reran the failed jobs, each failed on a download: ' + '; '.join(why)


def gh(*args):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


def why_not(repo, run, gh):
    """None when this run may rerun now, else why not."""
    if run.get('run_attempt') != 1 or run.get('conclusion') != 'failure':
        return f"attempt {run.get('run_attempt')}, {run.get('conclusion')}: only a first attempt that failed reruns"
    if (run.get('head_repository') or {}).get('full_name') != repo:
        return "a fork's run"
    # A PR with a newer commit: a rerun would only report on the old one.
    for pr in run.get('pull_requests') or []:
        if json.loads(gh('api', f"repos/{repo}/pulls/{pr['number']}"))['head']['sha'] != run['head_sha']:
            return f"PR #{pr['number']} has a newer commit, whose own run counts"
    return None


def judge(repo, run_id, gh=gh, dry_run=False, any_attempt=False):
    """Decides, reruns unless dry_run, and returns the summary line."""
    run = json.loads(gh('api', f'repos/{repo}/actions/runs/{run_id}'))
    reason = None if any_attempt else why_not(repo, run, gh)
    if reason:
        return f'Run {run_id}: {reason}; no rerun.'
    failed = []
    for page in json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/actions/runs/{run_id}/attempts/1/jobs?per_page=100')):
        for job in page['jobs']:
            if job['conclusion'] in ('failure', 'timed_out'):
                try:
                    log = gh('api', '--allow-escape-sequences', f"repos/{repo}/actions/jobs/{job['id']}/logs")
                except subprocess.CalledProcessError:
                    log = None
                failed.append((job['name'], log))
    rerun, line = decide(failed)
    if not rerun:
        return f'Run {run_id}: {line}; no rerun.'
    if dry_run:
        return f'Run {run_id}: {line} (dry run).'
    # Reading the logs takes a while: a new attempt or a new commit may have come since. Look again just before.
    reason = why_not(repo, json.loads(gh('api', f'repos/{repo}/actions/runs/{run_id}')), gh)
    if reason:
        return f'Run {run_id}: {reason} (since its logs were read); no rerun.'
    gh('run', 'rerun', str(run_id), '--failed', '-R', repo)
    return f'Run {run_id}: {line}.'


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--repo', required=True)
    ap.add_argument('--run', required=True, type=int)
    ap.add_argument('--summary')
    ap.add_argument('--dry-run', action='store_true', help='decide and say so, rerun nothing')
    ap.add_argument('--any-attempt', action='store_true',
                    help='with --dry-run: judge attempt 1 of a run that was rerun or has moved on since')
    a = ap.parse_args()
    if a.any_attempt and not a.dry_run:
        ap.error('--any-attempt needs --dry-run')
    line = judge(a.repo, a.run, dry_run=a.dry_run, any_attempt=a.any_attempt)
    print(line)
    if a.summary:
        with open(a.summary, 'a') as f:
            f.write(line + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
