"""api23-report.py on a made-up repo (#198): which modules it reads, how it reads lint's XML, and what --check
fails on. No Gradle: the reports are written here. Touches only a temporary folder."""
import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("api23_report", os.path.join(HERE, "..", "api23-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

LIBRARY = 'plugins {\n    id("com.android.library")\n}\n'
JVM = 'plugins {\n    id("org.jetbrains.kotlin.jvm")\n}\n'


def write(path, text):
    with open(path, "w") as f:
        f.write(text)


def issue(file, line, msg):
    return (f'<issue id="NewApi" message="{msg}"><location file="{file}" line="{line}"/></issue>')


class Api23ReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        report.ROOT = self.root
        settings = []
        for i in range(10):
            self.module(f":pane-{i}", f"panes/p{i}", LIBRARY)
            settings.append(f'include(":pane-{i}")\nproject(":pane-{i}").projectDir = file("panes/p{i}")')
        self.module(":engine-gecko", "engines/engine-gecko", LIBRARY)  # left out on purpose
        self.module(":esphome-api", "libs/esphome-api", JVM)  # not Android
        settings.append('project(":engine-gecko").projectDir = file("engines/engine-gecko")')
        settings.append('project(":esphome-api").projectDir = file("libs/esphome-api")')
        write(os.path.join(self.root, "settings.gradle.kts"), "\n".join(settings))
        for i in range(10):
            self.lint(f"panes/p{i}", [])
        src = os.path.join(self.root, "panes/p0/src/main/java/dev/glassworks/x")
        self.lint("panes/p0", [
            issue(f"{src}/A.kt", 12, "Call requires API level 24 (current min is 23): `android.net.ConnectivityManager#registerDefaultNetworkCallback`"),
            issue(f"{src}/A.kt", 30, "Call requires API level 26, or core library desugaring (current min is 23): `java.util.Base64#getEncoder`"),
        ])

    def tearDown(self):
        self.tmp.cleanup()

    def module(self, path, folder, build):
        os.makedirs(os.path.join(self.root, folder), exist_ok=True)
        write(os.path.join(self.root, folder, "build.gradle.kts"), build)

    def lint(self, folder, issues):
        d = os.path.join(self.root, folder, "build", "reports")
        os.makedirs(d, exist_ok=True)
        write(os.path.join(d, "lint-results-debug.xml"), '<?xml version="1.0"?><issues format="6">' + "".join(issues) + "</issues>")

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        argv = sys.argv
        sys.argv = ["api23-report.py", "--no-gradle", *args]
        code = 0
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                report.main()
        except SystemExit as e:  # sys.exit("why") prints "why" only at the top level: keep it here
            code = e.code if isinstance(e.code, int) else 1
            if isinstance(e.code, str):
                err.write(e.code + "\n")
        finally:
            sys.argv = argv
        return code, out.getvalue(), err.getvalue()

    def baseline(self, *lines):
        p = os.path.join(self.root, "baseline.txt")
        write(p, "# comment\n" + "".join(l + "\n" for l in lines))
        return p

    def test_reads_every_android_library_but_the_current_engine(self):
        paths = [p for p, _ in report.modules()]
        self.assertEqual([f":pane-{i}" for i in range(10)], paths)

    def test_findings_name_the_package_path_the_api_and_whether_desugaring_covers_it(self):
        found = report.findings(report.modules())
        self.assertEqual(
            [("dev/glassworks/x/A.kt", 12, "android.net.ConnectivityManager#registerDefaultNetworkCallback", 24, False),
             ("dev/glassworks/x/A.kt", 30, "java.util.Base64#getEncoder", 26, True)],
            [(f["file"], f["line"], f["api"], f["level"], f["desugar"]) for f in found])

    def test_check_fails_on_a_call_the_baseline_does_not_list(self):
        code, _, err = self.run_main("--check", self.baseline())
        self.assertEqual(1, code)
        self.assertIn("dev/glassworks/x/A.kt|android.net.ConnectivityManager#registerDefaultNetworkCallback", err)
        self.assertNotIn("Base64", err, "a desugared call never fails the check")

    def test_check_passes_when_the_baseline_lists_it_wherever_the_line_is(self):
        code, _, err = self.run_main("--check", self.baseline(
            "dev/glassworks/x/A.kt|android.net.ConnectivityManager#registerDefaultNetworkCallback",
            "dev/glassworks/x/Gone.kt|android.os.LocaleList#get"))
        self.assertEqual(0, code, err)
        self.assertIn("no longer found", err)
        self.assertIn("Gone.kt", err)

    def test_a_missing_report_fails_instead_of_passing(self):
        os.remove(os.path.join(self.root, "panes/p3/build/reports/lint-results-debug.xml"))
        code, _, err = self.run_main("--check", self.baseline())
        self.assertEqual(1, code)
        self.assertIn("no lint report for :pane-3", err)

    def test_the_table_counts_each_module(self):
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        self.assertIn("| `:pane-0` | 2 | 1 |", out)
        self.assertIn("| `:pane-1` | 0 | 0 |", out)


if __name__ == "__main__":
    unittest.main()
