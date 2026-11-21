"""tools/ci/discord_release.py: which channel a release goes to, and what it posts."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('discord_release', ROOT / 'tools/ci/discord_release.py')
dr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dr)

URL = 'https://github.com/GlassworksHQ/glassworks/releases/tag/v1.2.3'


class Channel(unittest.TestCase):
    def test_libraries_go_to_ecosystem_updates(self):
        self.assertEqual(dr.channel('libs/improv/v0.1.0'), 'DISCORD_ECOSYSTEM_WEBHOOK')

    def test_apps_and_frames_go_to_glassworks_releases(self):
        for tag in ('v0.4.4', 'aqara-s1-plus-v1.0.0', 'generic-android-v0.2.0', 'generic-android-legacy-v0.2.0',
                    'frames/aqara-s1-plus/v1.0.0', 'frames/lululemon-mirror/v1.0.0'):
            self.assertEqual(dr.channel(tag), 'DISCORD_RELEASES_WEBHOOK', tag)


class Message(unittest.TestCase):
    def test_short_notes_are_posted_whole_without_a_link(self):
        body = dr.message({'name': 'Glassworks 1.2.3', 'tagName': 'v1.2.3', 'url': URL, 'body': '**Fixed** a thing.\n'})
        embed = body['embeds'][0]
        self.assertEqual(embed['title'], 'Glassworks 1.2.3')
        self.assertEqual(embed['url'], URL)
        self.assertEqual(embed['description'], '**Fixed** a thing.')
        self.assertEqual(embed['footer'], {'text': 'v1.2.3'})

    def test_long_notes_are_cut_at_a_line_and_link_the_release(self):
        notes = '\n'.join(f'- line {i} ' + 'x' * 40 for i in range(100))
        d = dr.message({'name': 'n', 'tagName': 'v1.2.3', 'url': URL, 'body': notes})['embeds'][0]['description']
        self.assertLess(len(d), 4096)
        self.assertTrue(d.endswith(f'[Full release notes]({URL})'))
        kept = d.split('\n…')[0]
        self.assertTrue(notes.startswith(kept))
        self.assertTrue(kept.endswith('x'))

    def test_no_notes_and_no_name_still_post(self):
        embed = dr.message({'name': '', 'tagName': 'v1.2.3', 'url': URL, 'body': None})['embeds'][0]
        self.assertEqual(embed['title'], 'v1.2.3')
        self.assertIsNone(embed['description'])

    def test_it_never_pings_anyone(self):
        body = dr.message({'name': 'n', 'tagName': 'v1', 'url': URL, 'body': '@everyone'})
        self.assertEqual(body['allowed_mentions'], {'parse': []})


class Webhook(unittest.TestCase):
    def test_only_discord_webhooks_are_accepted(self):
        self.assertTrue(dr.valid_webhook('https://discord.com/api/webhooks/1/abc'))
        self.assertFalse(dr.valid_webhook('https://example.com/api/webhooks/1/abc'))
        self.assertFalse(dr.valid_webhook('http://discord.com/api/webhooks/1/abc'))


class Tags(unittest.TestCase):
    def test_release_tags_are_accepted(self):
        for tag in ('v0.4.4', 'aqara-s1-plus-v1.0.0', 'generic-android-legacy-v0.2.0', 'frames/lululemon-mirror/v1.0.0',
                    'libs/esphome-api/v0.1.1'):
            self.assertTrue(dr.TAG.match(tag), tag)

    def test_anything_else_is_refused_before_gh_runs(self):
        # The Release run's published-tags artifact is text: a line that isn't a release tag never reaches gh.
        for tag in ('--repo=evil/x', 'main', 'v1.2', 'frames/../v1.0.0', 'V1.0.0', 'a b-v1.0.0', ''):
            with self.assertRaises(SystemExit, msg=tag):
                dr.main(tag)


class Workflow(unittest.TestCase):
    def test_the_release_buttons_runs_are_posted_from_their_list(self):
        text = (ROOT / '.github/workflows/discord-releases.yml').read_text()
        self.assertIn('workflows: ["Release", "Library release"]', text)
        self.assertIn("github.event.workflow_run.event == 'workflow_dispatch'", text)
        self.assertIn('-n published-tags', text)
        release = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('name: Release\n', release)
        self.assertIn('name: published-tags', release)


if __name__ == '__main__':
    unittest.main()
