#!/usr/bin/env python3
"""Which of pr.yml's jobs a change needs (#536). Read-only: runs git diff, writes nothing but its output.

Usage:
  tools/ci/changes.py --base <commit> [--head <commit>]   # key=true|false per job, for $GITHUB_OUTPUT
                      [--summary <file>]                  # and a Markdown table of what runs and why
  tools/ci/changes.py --all                               # every job (a schedule, a manual run, no base)
  tools/ci/changes.py --files <path>...                   # the same from a list of changed files (tests)
  tools/ci/changes.py --table                             # the Markdown table docs/ci.md carries

It fails closed: without a base, or when git fails, every job runs. A change to any path in EVERYTHING
runs every job. A job skipped here still reports success, so a required check stays green.

Patterns: a pattern matches a path and everything under it; `*` matches within one folder,
`**` across folders, and `{a,b}` either. A pattern starting with `!` takes a path back out (the last match wins).
"""
import argparse
import re
import subprocess
import sys
from dataclasses import dataclass

# The workflows, this selector and the files every Gradle module or npm workspace builds with (the root
# build.gradle.kts reads every frame's frame.json for the ABIs).
EVERYTHING = (
    '.github/workflows', '.github/actions', 'tools/ci',
    'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle',
    'frames/*/design/frame.json', 'package.json', 'package-lock.json',
)

# Every Gradle module and what builds them.
ANDROID = ('libs', 'core', 'pane-api', 'engines', 'panes', 'testing', 'build-logic',
           'frames/*/android', 'frames/*/panes')
MODULES = '{' + ','.join(ANDROID) + '}'

# What the release APKs take in from outside the modules: the licence assets and the notices check.
APK_INPUTS = ('THIRD-PARTY-NOTICES.md', 'LICENSE', 'LICENSES')

# What the unit tests read from the repo beyond the modules: Test.readsRepoFiles in
# build-logic/src/main/kotlin/glassworks.android.gradle.kts, plus debloat.sh (BootRestTest).
TEST_INPUTS = APK_INPUTS + (
    'CHANGELOG.md', 'README.md', 'docs/**/*.md', 'frames/*/README.md', 'frames/**/blueprints/*.yaml',
    'tools/release/frame-abis.sh', 'tools/release/check-update-compat.sh', 'tools/voice/fetch-models.sh',
    'kiln/packages/brand/src/tokens.css', 'kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json',
    'branding/glass-tile/bootanimation.zip', 'frames/aqara-s1-plus/tools/bracket/base_emulator.py',
    'frames/aqara-s1-plus/tools/debloat/debloat.sh', 'frames/generic-android/installer/src/devices.ts',
    'frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh',  # CaptivePortalTest: the portal's ports are the helper's
    '**/sh/edm/**',
)

# Clarity's tests: tools/perf, and the frames' budgets and published grades they read.
PERF = ('tools/perf', 'frames/*/budgets.json', 'frames/*/clarity')

LIBS = ('libs', 'build-logic')

HOTSPOT_NAME = 'frames/aqara-s1-plus/tools/radio/hotspot-name'


@dataclass(frozen=True)
class Rule:
    """`when`: the job runs if a changed file matches. `unless_only`: it runs unless every changed file matches."""
    what: str
    when: tuple = ()
    unless_only: tuple = ()
    on_delete: bool = False  # any deleted or renamed file runs it (links to it may break)


# Keys are pr.yml's job ids with `_` for `-`, or a step group inside a job (app_apk, esphome, perf).
RULES = {
    # hotspot-name: the S1's setup hotspot overlay, which app builds beside the release APK (#430).
    'app': Rule('app: every module\'s unit tests', when=ANDROID + TEST_INPUTS + (HOTSPOT_NAME,)),
    'app_apk': Rule('app: release build, update check', when=ANDROID + APK_INPUTS + (
        'tools/release/check-update-compat.sh', HOTSPOT_NAME)),
    'esphome': Rule('app: aioesphomeapi tests', when=LIBS),
    'libs_api23': Rule('libs-api23: lint at minSdk 23', when=LIBS),
    'api23': Rule('api23', when=ANDROID + ('tools/legacy',)),
    'libs_publish': Rule('libs-publish', when=LIBS + (
        'LICENSE', 'LICENSES', 'THIRD-PARTY-NOTICES.md', 'tools/release/check-lib-artifact.sh',
        'tools/release/lib-consumer-check.sh')),
    'wake_word': Rule('wake-word', when=LIBS + ('tools/voice',)),
    # The workspaces read across the repo (frames, docs' JSON and images, tools, the panes' pane.json and icons,
    # core's setup page and font, RepoFiles.kt), so this rule is the other way round: what can't reach them is
    # Markdown (their tests and sources only name it) and the modules' Kotlin, Java, resources and tests.
    'web': Rule('web', unless_only=(
        '**/*.md', f'{MODULES}/**/{{*.kt,*.java}}', f'{MODULES}/**/src/{{test,androidTest}}/**', f'{MODULES}/**/src/*/res/**',
        '!testing/kotlin/dev/glassworks/testing/RepoFiles.kt', '!core/src/main/res/font/**',
        # site/src/lib/vitroCompat.test.ts checks the site's card lists against these files.
        '!engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/model/LovelaceModel.kt',
        '!engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/compat/VitroSupport.kt',
        '!engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/cards/HacsParityCards.kt',
        # ... and routes the strict compat rule's shared cases as the engine's NativeOptionsTest does.
        '!engines/engine-vitro/src/test/resources/dev/glassworks/engine/vitro/compat/native-options-cases.json')),
    'refract': Rule('refract', when=('kiln/workers',)),
    'perf': Rule('scripts: Clarity tests (ffmpeg, fast and slow sets)', when=PERF + ('docs/perf.md', 'tools/vitro-camera-e2e')),  # test_notes; the camera e2e's off-device tests
    'perf_webrtc': Rule('perf-webrtc', when=PERF),
    'root': Rule('root', when=('frames/aqara-s1-plus/tools/root', 'frames/aqara-s1-plus/tools/radio')),
    'size': Rule('size', when=ANDROID + APK_INPUTS + (
        'frames/*/budgets.json', 'tools/size', 'tools/release/frame-abis.sh', 'frames/aqara-s1-plus/tools/radio',
        'kiln/assets.lock.json')),
    # The generic app builds from no S1 module (frames/aqara-s1-plus/android or /panes).
    'size_generic': Rule('size-generic', when=(
        'frames/generic-android', 'core', 'pane-api', 'panes', 'libs', 'engines', 'testing', 'build-logic',
        'LICENSE', 'LICENSES', 'THIRD-PARTY-NOTICES.md', 'tools/size', 'tools/legacy/apk-icons.py',
        'tools/release/check-update-compat.sh')),
    'branding': Rule('branding', when=(
        'branding/boot', 'branding/glass-tile', 'branding/lululemon-mirror', 'branding/bootlogos.json',
        'frames/aqara-s1-plus/design/frame.json', 'frames/lululemon-mirror/design/frame.json')),
    # site/scripts/api-docs.mjs reads the libraries' build files and gradle.properties.
    'site': Rule('site', when=(
        'site', 'packages', 'frames', 'docs', 'tools/perf/grade.ts', 'tools/perf/page/rubric.json', '**/*.md',
        'libs/*/build.gradle.kts', 'libs/*/gradle.properties'), on_delete=True),
}


def _regex(pattern):
    if not any(c in pattern for c in '*{'):
        return re.compile(re.escape(pattern) + '(/.*)?')
    out, i = '', 0
    while i < len(pattern):
        if pattern.startswith('**/', i):
            out, i = out + '(.*/)?', i + 3
        elif pattern.startswith('**', i):
            out, i = out + '.*', i + 2
        elif pattern[i] == '*':
            out, i = out + '[^/]*', i + 1
        elif pattern[i] in '{,}':
            out, i = out + {'{': '(?:', ',': '|', '}': ')'}[pattern[i]], i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + '(/.*)?')


def matches(path, patterns):
    hit = False
    for p in patterns:
        negate = p.startswith('!')
        if _regex(p.lstrip('!')).fullmatch(path):
            hit = not negate
    return hit


def select(changed, deleted=()):
    """{key: (runs, why)} for the changed paths. `deleted` lists the deleted ones (renames count as both)."""
    trigger = next((f for f in changed if matches(f, EVERYTHING)), None)
    if trigger:
        return {k: (True, f'{trigger} runs everything') for k in RULES}
    out = {}
    for key, rule in RULES.items():
        if rule.when:
            hit = next((f for f in changed if matches(f, rule.when)), None)
        else:
            hit = next((f for f in changed if not matches(f, rule.unless_only)), None)
        if hit is None and rule.on_delete and deleted:
            hit = f'{deleted[0]} (deleted)'
        out[key] = (hit is not None, hit or 'nothing it reads changed')
    return out


def everything(why):
    return {k: (True, why) for k in RULES}


def from_git(base, head):
    try:
        if not base or set(base) == {'0'}:
            return everything('no base commit')
        rows = subprocess.run(['git', 'diff', '--no-renames', '--name-status', '-z', '--end-of-options', base, head],
                              check=True, capture_output=True, text=True).stdout.split('\0')
    except (subprocess.CalledProcessError, OSError) as e:
        return everything(f'git diff failed ({e})')
    pairs = list(zip(rows[0::2], rows[1::2]))
    changed = [p for _, p in pairs]
    if not changed:
        return everything('git diff found no change')
    return select(changed, [p for s, p in pairs if s == 'D'])


def table():
    def cell(patterns):
        return ', '.join(f'`{p}`' for p in patterns)
    lines = ['| Job or step | Runs when a PR changes |', '|---|---|',
             f'| every job | {cell(EVERYTHING)} |']
    for rule in RULES.values():
        if rule.when:
            text = cell(rule.when) + ('; or deletes or renames any file' if rule.on_delete else '')
        else:
            text = 'anything but ' + cell(rule.unless_only)
        lines.append(f'| {rule.what} | {text} |')
    return '\n'.join(lines)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--base')
    g.add_argument('--all', action='store_true')
    g.add_argument('--files', nargs='+')
    g.add_argument('--table', action='store_true')
    ap.add_argument('--head', default='HEAD')
    ap.add_argument('--summary', help='append a Markdown table of what runs and why')
    a = ap.parse_args(argv)
    if a.table:
        print(table())
        return 0
    result = (everything('every job on this event') if a.all else select(a.files) if a.files
              else from_git(a.base, a.head))
    for key, (runs, why) in result.items():
        print(f'{key}={"true" if runs else "false"}')
    if a.summary:
        with open(a.summary, 'a') as f:
            f.write('### Jobs for this change (tools/ci/changes.py)\n\n| Job or step | Runs | Why |\n|---|---|---|\n')
            for key, (runs, why) in result.items():
                f.write(f'| {RULES[key].what} | {"yes" if runs else "no"} | `{why}` |\n')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
