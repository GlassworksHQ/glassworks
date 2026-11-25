"""Tests for tools/check-status.py, on a made-up repo in a temporary folder (touches nothing else)."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('check_status', HERE.parent / 'check-status.py')
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

BADGE = '<img alt="Status: {}" src="x.svg">'


class CheckStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.frame('panel', 'stable', provides=['microphone'], panes={'radio': {'support': 'beta'}})
        self.frame('tablet', 'beta')
        self.pane('panes/assist', 'assist', 'Assist', 'stable', ['microphone'])
        self.pane('panes/notifications', 'notifications', 'Notifications', 'beta', [])
        self.pane('panes/radio', 'radio', 'Zigbee / Thread radio', 'beta', ['silabs-radio'])
        self.pane('frames/panel/panes/base', 'wall-base', 'Wall base', 'alpha', ['s1-wall-base'], frame='panel')
        self.write('README.md', '| Frame | Status |\n|---|---|\n| [Panel](frames/panel/README.md) | Stable |\n'
                                '| [Tablet](frames/tablet/README.md) | Beta |\n')
        self.write('frames/panel/README.md', BADGE.format('stable') + '\n\n**Status: stable.** Yes.\n\n## Panes\n\n'
                   '| Pane | Support |\n|---|---|\n| Assist | Stable |\n| Notifications | Beta |\n'
                   '| Zigbee / Thread radio | Beta |\n| Wall base | Not supported |\n')
        self.write('frames/tablet/README.md', BADGE.format('beta') + '\n\n## Panes\n\n'
                   '| Pane | Support |\n|---|---|\n| Assist | Depends on device |\n| **Notifications** | Beta |\n'
                   '| Zigbee / Thread radio | Depends on device |\n| Wall base | Not supported |\n')
        self.write('frames/panel/android/README.md', BADGE.format('stable'))

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf-8')

    def frame(self, fid, status, **extra):
        self.write(f'frames/{fid}/design/frame.json', json.dumps({'id': fid, 'status': status, 'panes': {}, **extra}))

    def pane(self, module, pid, name, status, requires, **extra):
        self.write(f'{module}/src/main/java/dev/x/{pid}/pane.json',
                   json.dumps({'id': pid, 'name': name, 'status': status, 'requires': requires, **extra}))

    def test_agreeing_docs_pass(self):
        self.assertEqual([], cs.check(self.root))

    def test_a_frame_table_row_that_disagrees_fails(self):
        self.write('README.md', '| Frame | Status |\n|---|---|\n| [Panel](frames/panel/README.md) | Beta |\n')
        self.assertEqual(['README.md:3: panel: says Beta, frames/panel/design/frame.json says Stable'], cs.check(self.root))

    def test_badges_and_the_lead_line_follow_the_frame(self):
        self.write('frames/panel/android/README.md', BADGE.format('beta'))
        self.frame('tablet', 'alpha')
        out = cs.check(self.root)
        self.assertIn('frames/panel/android/README.md:1: status badge says beta, frames/panel/design/frame.json says stable', out)
        self.assertIn('frames/tablet/README.md:1: status badge says beta, frames/tablet/design/frame.json says alpha', out)
        self.assertIn('README.md:4: tablet: says Beta, frames/tablet/design/frame.json says Alpha', out)

    def test_a_pane_readme_badge_follows_its_pane_json(self):
        self.write('panes/assist/src/main/java/dev/x/assist/README.md', BADGE.format('beta'))
        self.assertEqual(['panes/assist/src/main/java/dev/x/assist/README.md:1: status badge says beta, pane.json says stable'],
                         cs.check(self.root))

    def test_pane_support_is_worked_out_as_frame_card_does(self):
        self.write('frames/panel/README.md', BADGE.format('stable') + '\n\n## Panes\n\n'
                   '| Pane | Support |\n|---|---|\n| Assist | Beta |\n| Notifications | Beta |\n'
                   '| Zigbee / Thread radio | Not supported |\n| Wall base | Not supported |\n')
        self.assertEqual(['frames/panel/README.md:7: Assist: says Beta, the data gives Stable',
                          'frames/panel/README.md:9: Zigbee / Thread radio: says Not supported, the data gives Beta'],
                         cs.check(self.root))

    def test_a_panes_table_lists_every_pane_by_its_name(self):
        self.write('frames/tablet/README.md', BADGE.format('beta') + '\n\n## Panes\n\n'
                   '| Pane | Support |\n|---|---|\n| Assist | Depends on device |\n| Zigbee radio | Depends on device |\n')
        out = cs.check(self.root)
        self.assertIn('frames/tablet/README.md:8: "Zigbee radio" is no pane in the catalog; name it as its pane.json does', out)
        self.assertIn('frames/tablet/README.md:8: the Panes table has no row for Notifications', out)
        self.assertIn('frames/tablet/README.md:8: the Panes table has no row for Wall base', out)

    def test_a_frame_that_does_not_ship_plans_its_panes(self):
        self.frame('tablet', 'development')
        self.write('README.md', '')
        self.write('frames/tablet/README.md', BADGE.format('under development') + '\n\n## Panes\n\n'
                   '| Pane | Support |\n|---|---|\n| Assist | Depends on device |\n| Notifications | Planned |\n'
                   '| Zigbee / Thread radio | Depends on device |\n| Wall base | Not supported |\n')
        self.assertEqual([], cs.check(self.root))


if __name__ == '__main__':
    unittest.main()
