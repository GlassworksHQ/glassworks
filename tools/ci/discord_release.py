#!/usr/bin/env python3
"""Post one GitHub release to the Glassworks Discord server.

  tools/ci/discord_release.py <tag>

Run by .github/workflows/discord-releases.yml, never by hand. Reads the release with `gh release view`
(GH_TOKEN), then posts it through a Discord webhook: DISCORD_ECOSYSTEM_WEBHOOK for libraries (`libs/*`),
DISCORD_RELEASES_WEBHOOK for everything else (the apps and the frames). Drafts and pre-releases are skipped.
A missing webhook secret skips the post, so a fork or a repo without the secrets passes. It only reads
from GitHub and writes one Discord message. See docs/releasing.md#discord.
"""
import json
import os
import subprocess
import sys
import urllib.request

# Discord allows 4096 characters in an embed's description. Keep the post short and link the rest.
MAX_NOTES = 1500


def channel(tag):
    """Which webhook secret a tag posts to."""
    return 'DISCORD_ECOSYSTEM_WEBHOOK' if tag.startswith('libs/') else 'DISCORD_RELEASES_WEBHOOK'


def trim(notes, limit=MAX_NOTES):
    """The notes up to `limit` characters, cut at a line break, with a marker when something was cut."""
    notes = notes.strip()
    if len(notes) <= limit:
        return notes
    cut = notes.rfind('\n', 0, limit)
    if cut < limit // 2:
        cut = limit
    return notes[:cut].rstrip() + '\n…'


def message(release):
    """The webhook body for a release from `gh release view --json name,tagName,url,body`."""
    notes = trim(release.get('body') or '')
    if notes != (release.get('body') or '').strip():
        notes += f'\n\n[Full release notes]({release["url"]})'
    return {
        'username': 'Glassworks releases',
        'allowed_mentions': {'parse': []},
        'embeds': [{
            'title': release.get('name') or release['tagName'],
            'url': release['url'],
            'description': notes or None,
            'footer': {'text': release['tagName']},
        }],
    }


def valid_webhook(url):
    return url.startswith(('https://discord.com/api/webhooks/', 'https://discordapp.com/api/webhooks/'))


def main(tag):
    out = subprocess.run(
        ['gh', 'release', 'view', tag, '-R', os.environ['GITHUB_REPOSITORY'],
         '--json', 'name,tagName,url,body,isDraft,isPrerelease'],
        check=True, capture_output=True, text=True).stdout
    release = json.loads(out)
    if release['isDraft'] or release['isPrerelease']:
        print(f'{tag}: draft or pre-release, not posted.')
        return
    secret = channel(tag)
    url = os.environ.get(secret, '')
    if not url:
        print(f'{tag}: no {secret} secret, not posted.')
        return
    if not valid_webhook(url):
        sys.exit(f'::error::{secret} is not a Discord webhook URL')
    req = urllib.request.Request(url, data=json.dumps(message(release)).encode(), method='POST',
                                 headers={'Content-Type': 'application/json', 'User-Agent': 'glassworks-release-feed'})
    with urllib.request.urlopen(req, timeout=30) as r:
        print(f'{tag}: posted ({secret}, HTTP {r.status}).')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
