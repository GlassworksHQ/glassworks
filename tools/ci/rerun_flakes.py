#!/usr/bin/env python3
"""Reruns the failed jobs of a red `PR checks` run once, when every failed job failed on a download (docs/ci.md, Reruns).

.github/workflows/rerun-download-flakes.yml runs this after a first attempt of pr.yml ends in failure. A download that
fails on one runner tends to keep failing there for a minute or more (#631: the plugin portal and Maven Central both
answered 404 three times in a row, 45 s apart), and a rerun on a fresh runner passed every time (5 of 5 on
2026-11-18 and 19). So it reruns the failed jobs, through `gh run rerun --failed`, and only when each one's log
shows a download failure. One real failure anywhere, a job without a log, or a run that isn't the first attempt:
no rerun.

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

# What says it was something else, even next to a download line: these never rerun.
NOT_DOWNLOAD = [
    # A build script error, not a download ("Could not find method implementation() for arguments …").
    re.compile(r'Could not find method '),
]

# A mirror's address in apt's or curl's output ("[IP: 192.0.2.7 80]", "http://192.0.2.7/"): kept out of the summary.
IPV4 = re.compile(r'(?<=IP: )\d{1,3}(?:\.\d{1,3}){3}|(?<=://)\d{1,3}(?:\.\d{1,3}){3}')
TIMESTAMP = re.compile(r'^\d{4}-\d\d-\d\dT[\d:.]+Z ', re.M)
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def clean(log):
    """The log as text: no ANSI colours, no timestamp at the start of each line."""
    return TIMESTAMP.sub('', ANSI.sub('', log))


def download_failure(log):
    """What the log says failed to download ('<kind>: <line>'), or None when it isn't (only) a download failure."""
    text = clean(log)
    if any(p.search(text) for p in NOT_DOWNLOAD):
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

    run = json.loads(gh('api', f'repos/{a.repo}/actions/runs/{a.run}'))
    # A PR that has a newer commit since: rerunning this one would only report on the old commit.
    moved = None
    for pr in [] if a.any_attempt else run.get('pull_requests') or []:
        if json.loads(gh('api', f"repos/{a.repo}/pulls/{pr['number']}"))['head']['sha'] != run['head_sha']:
            moved = pr['number']
    if not a.any_attempt and (run.get('run_attempt') != 1 or run.get('conclusion') != 'failure'):
        rerun, line = False, f"attempt {run.get('run_attempt')}, {run.get('conclusion')}: only a first attempt that failed reruns"
    elif run.get('head_repository', {}).get('full_name') != a.repo:
        rerun, line = False, 'a fork\'s run'
    elif moved:
        rerun, line = False, f'PR #{moved} has a newer commit, whose own run counts'
    else:
        jobs = json.loads(gh('api', '--paginate', '--slurp', f'repos/{a.repo}/actions/runs/{a.run}/attempts/1/jobs?per_page=100'))
        failed = []
        for page in jobs:
            for job in page['jobs']:
                if job['conclusion'] in ('failure', 'timed_out'):
                    try:
                        log = gh('api', '--allow-escape-sequences', f"repos/{a.repo}/actions/jobs/{job['id']}/logs")
                    except subprocess.CalledProcessError:
                        log = None
                    failed.append((job['name'], log))
        rerun, line = decide(failed)
    if rerun and not a.dry_run:
        gh('run', 'rerun', str(a.run), '--failed', '-R', a.repo)
    line = f"Run {a.run}: {line}" + (' (dry run)' if rerun and a.dry_run else '') + ('.' if rerun else '; no rerun.')
    print(line)
    if a.summary:
        with open(a.summary, 'a') as f:
            f.write(line + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
