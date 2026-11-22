"""tools/release/npm-package.mjs: what npm gets from a workspace package (docs/releasing.md "npm packages").

Runs the script's functions in Node; builds nothing, writes nothing. Run from the repo root:
python3 -m unittest discover -s tools/release/test -v
"""
import json
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'tools/release/npm-package.mjs'


def node(expr):
    """The JSON value of `expr`, with the script's exports in scope."""
    code = f"import * as m from {json.dumps(SCRIPT.as_uri())}; const {{ absoluteLinks, dtsSpecifiers, manifest, publishable }} = m;" \
           f" console.log(JSON.stringify({expr}));"
    out = subprocess.run(['node', '--input-type=module', '-e', code], cwd=ROOT, capture_output=True, text=True)
    if out.returncode:
        raise AssertionError(out.stderr.strip())
    return json.loads(out.stdout)


PKG = {
    'name': '@glassworks/widget', 'version': '0.2.0', 'private': True, 'description': 'A widget.', 'license': 'MIT',
    'type': 'module', 'keywords': ['widget'],
    'exports': {'.': './src/index.ts', './wire': './src/wire/index.ts', './fakes': './test/fakes.ts'},
    'scripts': {'test': 'vitest run'}, 'devDependencies': {'vitest': '*'},
    'dependencies': {'@glassworks/fel': '0.1.0', '@types/w3c-web-usb': '^1.0.14'},
}
FEL = {'name': '@glassworks/fel', 'version': '0.1.0'}


def manifest(pkg=PKG, others=(FEL,)):
    me = {'name': 'widget', 'dir': 'kiln/packages/widget', 'pkg': pkg}
    all_ = [me] + [{'name': o['name'].split('/')[-1], 'dir': 'x', 'pkg': o} for o in others]
    return node(f"manifest({json.dumps(me)}, {json.dumps(all_)})")


class Manifest(unittest.TestCase):
    def test_exports_point_at_dist_with_types_and_leave_the_fakes_out(self):
        self.assertEqual({
            '.': {'types': './dist/index.d.ts', 'default': './dist/index.js'},
            './wire': {'types': './dist/wire/index.d.ts', 'default': './dist/wire/index.js'},
            './package.json': './package.json',
        }, manifest()['exports'])

    def test_workspace_only_fields_are_dropped(self):
        m = manifest()
        for field in ('private', 'scripts', 'devDependencies'):
            self.assertNotIn(field, m)
        self.assertEqual({'access': 'public'}, m['publishConfig'])
        self.assertEqual(['dist', 'src', 'CHANGELOG.md'], m['files'])
        self.assertEqual({'type': 'git', 'url': 'git+https://github.com/GlassworksHQ/glassworks.git',
                          'directory': 'kiln/packages/widget'}, m['repository'])

    def test_a_workspace_dependency_gets_a_caret_range(self):
        self.assertEqual({'@glassworks/fel': '^0.1.0', '@types/w3c-web-usb': '^1.0.14'}, manifest()['dependencies'])

    def test_refusals(self):
        cases = {
            'a pin that is not the dependency version': ({**PKG, 'dependencies': {'@glassworks/fel': '0.0.9'}}, (FEL,)),
            'an unpublished @glassworks dependency': ({**PKG, 'dependencies': {'@glassworks/kiln-core': '0.0.0'}}, ()),
            'version 0.0.0': ({**PKG, 'version': '0.0.0'}, (FEL,)),
            'a name outside the scope': ({**PKG, 'name': 'widget'}, (FEL,)),
            'a licence other than MIT': ({**PKG, 'license': 'GPL-2.0'}, (FEL,)),
            'an export outside src/ and test/': ({**PKG, 'exports': {'.': './lib/index.js'}}, (FEL,)),
        }
        for what, (pkg, others) in cases.items():
            with self.subTest(what), self.assertRaises(AssertionError):
                manifest(pkg, others)


class Links(unittest.TestCase):
    def links(self, md):
        return node(f"absoluteLinks({json.dumps(md)}, 'kiln/packages/fel', 'npm/fel/v0.1.0')")

    def test_relative_links_go_to_github_at_the_tag(self):
        blob = 'https://github.com/GlassworksHQ/glassworks/blob/npm/fel/v0.1.0'
        self.assertEqual(f'[spec]({blob}/docs/research/kiln-cleanroom/fel-spec.md#the-a133)',
                         self.links('[spec](../../../docs/research/kiln-cleanroom/fel-spec.md#the-a133)'))
        self.assertEqual(f'[log]({blob}/kiln/packages/fel/CHANGELOG.md)', self.links('[log](CHANGELOG.md)'))
        self.assertEqual(f'<a href="{blob}/LICENSE">', self.links('<a href="../../../LICENSE">'))

    def test_images_go_to_the_raw_file(self):
        self.assertEqual('![x](https://raw.githubusercontent.com/GlassworksHQ/glassworks/npm/fel/v0.1.0/docs/img/x.webp)',
                         self.links('![x](../../../docs/img/x.webp)'))

    def test_absolute_links_and_anchors_stay(self):
        for md in ('[a](https://linux-sunxi.org/FEL)', '[b](#install)', '<img src="https://www.glassworks.dev/brand/x.svg">',
                   '[c](mailto:someone@example.com)'):
            self.assertEqual(md, self.links(md))

    def test_a_link_out_of_the_repo_is_refused(self):
        with self.assertRaises(AssertionError):
            self.links('[x](../../../../elsewhere.md)')


class Declarations(unittest.TestCase):
    def test_relative_ts_specifiers_become_js(self):
        dts = "export { A } from './a.ts';\nimport type { B } from \"../b/index.ts\";\ntype C = import('./c.ts').C;\n" \
              "import type { D } from 'some-package.ts';\n"
        self.assertEqual("export { A } from './a.js';\nimport type { B } from \"../b/index.js\";\ntype C = import('./c.js').C;\n"
                         "import type { D } from 'some-package.ts';\n", node(f"dtsSpecifiers({json.dumps(dts)})"))


class TheRepo(unittest.TestCase):
    def test_the_published_packages(self):
        self.assertEqual(['ext4', 'fastboot', 'fel', 'improv-serial'], node('publishable().map((p) => p.name)'))

    def test_each_one_makes_a_manifest(self):
        # The workspace pins of one published package on another must stay its version (release fel before fastboot).
        names = node('publishable().map((p) => manifest(p, publishable()).name)')
        self.assertEqual(['@glassworks/ext4', '@glassworks/fastboot', '@glassworks/fel', '@glassworks/improv-serial'], names)

    def test_each_one_has_a_changelog_section_for_its_version(self):
        for p in node('publishable().map((p) => [p.dir, p.pkg.version])'):
            with self.subTest(p[0]):
                subprocess.run([str(ROOT / 'tools/release/changelog-section.sh'), p[1], str(ROOT / p[0] / 'CHANGELOG.md')],
                               check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
