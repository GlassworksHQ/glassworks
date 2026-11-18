#!/usr/bin/env python3
"""Glassworks migration step 7 (#22), and the S1 frame's move (#527): move the Kotlin and Java sources out of
sh.edm.s1kiosk.* into dev.glassworks.*, and rewrite every reference to a moved class or file in the repo.

    python3 tools/migrate/rename-22.py            apply, then print what is left
    python3 tools/migrate/rename-22.py --dry-run  print the moves only, change nothing
    python3 tools/migrate/rename-22.py --check    exit 1 if anything would still move

Touches: tracked files in this checkout only (git mv, text rewrites). No device, no network. Runs
`node packages/frame-card/scripts/panes.mjs` to regenerate the pane catalog when node is on the PATH.

Re-runnable: every decision comes from the tree as it is. On a renamed tree it moves nothing and
rewrites nothing, so it also renames the files a branch adds after the rename landed. Start from a
clean tree; if a run stops halfway, reset the tree and run it again.

The core went to dev.glassworks.* in #22; the S1 frame's own code (the S1 app and its wall-base pane) goes to
dev.glassworks.s1.* in #527 (S1_PACKAGES), and the S1 app's namespace with it. What stays in sh.edm.s1kiosk is
S1_SHIMS: one-line subclasses at the component names Android, the field and the root side hold (the HOME activity,
the device admin, what check-update-compat.sh names, and the receiver a 0.4.3 install's PendingIntent targets).
The S1 app's manifest names every component in full. A component's old name in a string, a doc, Kiln or a
manifest is its component name and is never rewritten. CHECK_UNCHANGED fails the run if any file that names
them changed.
"""
import argparse
import os
import re
import subprocess
import sys
from collections import defaultdict

OLD = "sh.edm.s1kiosk"

# One old package, one new package, in every module. Four old names would land in a published
# library's package (libs/: dev.glassworks.esphome, .improv, .sendspin, .voice), so they get their own.
PACKAGES = {
    "": "dev.glassworks",
    "auth": "dev.glassworks.auth",
    "camera": "dev.glassworks.camera",
    "conformance": "dev.glassworks.conformance",
    "dashboard": "dev.glassworks.dashboard",
    "discovery": "dev.glassworks.discovery",
    "esphome": "dev.glassworks.device",
    "gecko": "dev.glassworks.engine.gecko",
    "improv": "dev.glassworks.provisioning",
    "legacycheck": "dev.glassworks.legacycheck",
    "manage": "dev.glassworks.manage",
    "notifications": "dev.glassworks.notifications",
    "radio": "dev.glassworks.radio",
    "radio.ezsp": "dev.glassworks.radio.ezsp",
    "radio.ui": "dev.glassworks.radio.ui",
    "screen": "dev.glassworks.screen",
    "sendspin": "dev.glassworks.speaker",
    "settings": "dev.glassworks.settings",
    "setup": "dev.glassworks.setup",
    "timers": "dev.glassworks.timers",
    "ui": "dev.glassworks.ui",
    "update": "dev.glassworks.update",
    "voice": "dev.glassworks.assist",
}
# The S1 frame's own code (#527): old subpackage -> new, in the S1 app and its wall-base pane.
S1_PACKAGES = {
    "": "dev.glassworks.s1",
    "base": "dev.glassworks.s1.base",
    "frame": "dev.glassworks.s1.frame",
    "manage": "dev.glassworks.s1.manage",
    "settings": "dev.glassworks.s1.settings",
}
S1_NAMESPACE = "dev.glassworks.s1"
# The S1 app's subclasses that keep their old names (#527): Android, Kiln, install.sh, the root helper and
# check-update-compat.sh hold these. Its other components are declared under their dev.glassworks names.
S1_SHIMS = {f"{OLD}.MainActivity", f"{OLD}.AdminReceiver", f"{OLD}.BootReceiver", f"{OLD}.improv.ImprovActivity",
            f"{OLD}.update.UpdateInstallReceiver"}
# The S1 app's activity alias: a manifest name, not a class; the HOME a tablet recorded may be it.
S1_ALIAS = f"{OLD}.Launcher"
# The Android components: old name -> where the code goes. Android remembers them by class name (device admin,
# HOME, autostart, PackageInstaller's status PendingIntent, `am start -n` in Kiln, install.sh and Clarity).
COMPONENTS = {
    f"{OLD}.MainActivity": "dev.glassworks.MainActivity",
    f"{OLD}.KioskService": "dev.glassworks.KioskService",
    f"{OLD}.AdminReceiver": "dev.glassworks.AdminReceiver",
    f"{OLD}.BootReceiver": "dev.glassworks.BootReceiver",
    f"{OLD}.improv.ImprovActivity": "dev.glassworks.provisioning.ImprovActivity",
    f"{OLD}.update.UpdateInstallReceiver": "dev.glassworks.update.UpdateInstallReceiver",
    f"{OLD}.CameraAdbActivity": "dev.glassworks.camera.CameraAdbActivity",
}
# Apps whose installed base knows the old component names: module -> its namespace (for `.Name` in its manifest).
SHIM_MODULES = {
    "frames/aqara-s1-plus/android": "sh.edm.s1kiosk",
    # The S1 app's legacy test APK compiles the S1's sources and names their components; it has no shims of its own.
    "engines/engine-gecko-legacy/test-app": "sh.edm.s1kiosk",
}
SHIM = """package {pkg}

/** The name Android, Kiln and the installed apps know this component by (#22). The code is [{new}]. */
class {name} : {new}()
"""
# The S1 frame's modules (MIT): their sources go to S1_PACKAGES, not PACKAGES.
S1_MODULES = {"frames/aqara-s1-plus/panes/wall-base"}
S1_APP = "frames/aqara-s1-plus/android"
S1_GRADLE = {S1_APP + "/build.gradle.kts", "engines/engine-gecko-legacy/test-app/build.gradle.kts"}
# Text never rewritten: history, the probe's README (it borrowed sh.edm.s1kiosk before #527), this script.
SKIP_TEXT = ("CHANGELOG.md", "tools/mirror/probe-app/", "tools/migrate/rename-22.py")
# Research notes are dated evidence: their logs and tables stay as recorded, only their links follow the move.
RESEARCH = re.compile(r"(^|/)research/")
LINK = re.compile(r"\]\(([^)\s]+)\)")
# Files that name only kept classes. The run fails if it changed any of them.
CHECK_UNCHANGED = [
    "tools/release/check-update-compat.sh",
    "frames/aqara-s1-plus/installer/test/golden/s1-adb-commands.json",
    "frames/generic-android/installer/test/golden/generic-adb-commands.json",
    "frames/lululemon-mirror/design/frame.json",
    "frames/generic-android/installer/src/devices.ts",
    "frames/generic-android/android/src/main/AndroidManifest.xml",
    "frames/aqara-s1-plus/tools/root",
    "frames/aqara-s1-plus/tools/radio",
    "frames/aqara-s1-plus/tools/install",
    "frames/aqara-s1-plus/tools/install.sh",
    "kiln/packages/app-adb/src",
    "kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json",
    "tools/perf/run.sh", "tools/perf/perfrun.py", "tools/perf/perflib.py",
]

DECL = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|inline|value|const|"
    r"suspend|operator|infix|tailrec|external|final|static|lateinit)\s+)*)"
    r"(?:fun\s+interface|class|object|interface|fun|val|var|typealias|@interface)\s+"
    r"(?:<[^>]*>\s*)?(?:[\w.<>?, *]+\.)?(?P<name>[A-Za-z_]\w*)")
DOT_TOKEN = re.compile(r"(?<![\w.])sh\.edm\.s1kiosk(?:\.[A-Za-z_]\w*)*")
SLASH_TOKEN = re.compile(r"(?<![\w.])sh/edm/s1kiosk(?:/[A-Za-z_][\w]*)*(?:\.(?:kt|java|json|svg))?")
CONSTRUCTED = re.compile(r'(listOf|setOf)\(((?:\s*"[^"$]*"\s*,?)+)\)\s*\.map \{ "sh/edm/s1kiosk/\$it" \}(\.toSet\(\))?')
IMPORT = re.compile(r"^import\s+(static\s+)?([\w.]+)(\.\*)?(?:\s+as\s+(\w+))?\s*;?\s*$")


def git(*args, check=True):
    return subprocess.run(["git", *args], check=check, capture_output=True, text=True).stdout


def code_only(text):
    """The text without comments and string literals, keeping string templates (`$x`, `${...}`)."""
    out, i, n = [], 0, len(text)
    stack = []  # open string kinds; a '{' entry marks code inside ${...}
    while i < n:
        c = text[i]
        if stack and stack[-1] in ('"', '"""'):
            q = stack[-1]
            if q == '"' and c == "\\":
                i += 2
                continue
            if text.startswith(q, i):
                stack.pop(); i += len(q); out.append(" ")
                continue
            if text.startswith("${", i):
                stack.append("{"); i += 2; out.append(" ")
                continue
            if c == "$" and i + 1 < n and (text[i + 1].isalpha() or text[i + 1] == "_"):
                j = i + 1
                while j < n and (text[j].isalnum() or text[j] == "_"):
                    j += 1
                out.append(" " + text[i + 1:j] + " "); i = j
                continue
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i): depth += 1; i += 2
                elif text.startswith("*/", i): depth -= 1; i += 2
                else: i += 1
            continue
        if text.startswith('"""', i):
            stack.append('"""'); i += 3
            continue
        if c == '"':
            stack.append('"'); i += 1
            continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'" and j - i < 8:
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            continue
        if stack and stack[-1] == "{":
            if c == "{": stack.append("{{")
            elif c == "}": stack.pop()
        elif stack and stack[-1] == "{{" and c == "}":
            stack.pop()
        out.append(c); i += 1
    return "".join(out)


class Source:
    def __init__(self, path):
        self.path = path
        self.text = open(path, encoding="utf-8").read()
        m = re.search(r"^package\s+([\w.]+)", self.text, re.M)
        self.pkg = m.group(1) if m else ""
        self.sub = self.pkg[len(OLD) + 1:] if self.pkg.startswith(OLD + ".") else ("" if self.pkg == OLD else None)
        self.ext = path.rsplit(".", 1)[1]
        self.stem = os.path.basename(path).rsplit(".", 1)[0]
        pkg_dir = self.pkg.replace(".", "/")
        d = os.path.dirname(path)
        if self.sub is not None and not d.endswith("/" + pkg_dir):
            raise SystemExit(f"{path}: package {self.pkg} does not match its folder")
        self.root = d[: -len(pkg_dir) - 1] if d.endswith("/" + pkg_dir) else d
        self.module = module_of(path)
        self.test = "/src/main/" not in path
        self.decls, self.private = [], set()
        for line in self.text.splitlines():
            m = DECL.match(line)
            if m:
                if "private" in m.group("mods"):
                    self.private.add(m.group("name"))
                else:
                    self.decls.append(m.group("name"))
        self.decls = list(dict.fromkeys(self.decls))
        self.new_pkg = self.pkg

    def fq(self, name):
        return f"{self.pkg}.{name}"


def module_of(path):
    if path.startswith("testing/"):
        return "/".join(path.split("/")[:2])
    i = path.find("/src/")
    return path[:i] if i >= 0 else os.path.dirname(path)


def module_graph():
    """Gradle module folder -> the module folders it can see (itself and its project deps, transitively)."""
    settings = open("settings.gradle.kts").read()
    dirs = dict(re.findall(r'project\("(:[\w-]+)"\)\.projectDir = file\("([^"]+)"\)', settings))
    deps = {}
    for gp, d in dirs.items():
        b = os.path.join(d, "build.gradle.kts")
        txt = open(b).read() if os.path.exists(b) else ""
        deps[d] = {dirs[p] for p in re.findall(r'project\("(:[\w-]+)"\)', txt) if p in dirs and dirs[p] != d}
    seen = {}

    def close(d):
        if d not in seen:
            seen[d] = {d}
            for x in deps.get(d, ()):
                seen[d] |= close(x)
        return seen[d]

    for d in dirs.values():
        close(d)
    return seen


def decide(sources):
    """Sets new_pkg on every sh.edm source."""
    by_old = defaultdict(dict)  # old package -> decl -> main Source
    for s in sources:
        if not s.test:
            for d in s.decls:
                by_old[s.pkg][d] = s

    def base_rule(s):
        if s.module in SHIM_MODULES and s.fq(s.stem) in COMPONENTS:
            return s.pkg  # an app's subclass at the old name (write_shims), not the component's code
        if s.module in S1_MODULES or s.module == S1_APP or s.sub in ("base", "frame"):
            if s.sub in S1_PACKAGES:
                return S1_PACKAGES[s.sub]
            # A test of a core class that sits in the S1 app's tests (sh.edm.s1kiosk.camera, say) goes with the core.
        if s.sub not in PACKAGES:
            return s.pkg
        if s.fq(s.stem) in COMPONENTS and not s.test:
            return COMPONENTS[s.fq(s.stem)].rsplit(".", 1)[0]
        return PACKAGES[s.sub]

    mains = [s for s in sources if not s.test]
    for s in mains:
        s.new_pkg = base_rule(s)
    for s in sources:
        if s.test:
            tested = None
            for suffix in ("Test", "Tests", "Mockups", "PinTest", "Harness"):
                if s.stem.endswith(suffix) and s.stem[: -len(suffix)] in by_old[s.pkg]:
                    tested = by_old[s.pkg][s.stem[: -len(suffix)]]
            s.new_pkg = tested.new_pkg if tested and s.module not in S1_MODULES and s.module != S1_APP else base_rule(s)


def new_path(s):
    return f"{s.root}/{s.new_pkg.replace('.', '/')}/{os.path.basename(s.path)}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    os.chdir(git("rev-parse", "--show-toplevel").strip())

    clean = not git("status", "--porcelain").strip()
    if clean and not a.check and not a.dry_run:
        s1_frame_setup()
    code_files = [f for f in git("ls-files", "*.kt", "*.java").split("\n") if f]
    sources = [Source(f) for f in code_files]
    edm = [s for s in sources if s.sub is not None]
    decide(edm)
    moving = [s for s in edm if s.new_pkg != s.pkg]
    if a.check:
        for s in moving:
            print(f"would move {s.path} -> {new_path(s)}")
        sys.exit(1 if moving else 0)
    if not moving:
        print("nothing to move: rewriting old names that a rebased branch still uses")
    if not clean and not a.dry_run:
        raise SystemExit("the tree has changes: commit or reset them first, then run again")

    # Collisions: two top-level names, or two files of one name, in one package.
    final = defaultdict(lambda: defaultdict(list))
    for s in sources:
        scope = "test:" + s.module if s.test else "main"
        for d in s.decls + [s.stem + "Kt"]:
            final[(s.new_pkg, scope)][d].append(s.path)
    clashes = []
    moving_paths = {m.path for m in moving}
    for (pkg, scope), names in list(final.items()):
        for d, paths in names.items():
            others = final.get((pkg, "main"), {}).get(d, []) if scope != "main" else []
            all_paths = sorted(set(paths + others))
            if len(all_paths) > 1 and moving_paths.intersection(all_paths):
                clashes.append(f"{pkg}.{d}: {', '.join(all_paths)}")
    if clashes:
        raise SystemExit("moving would put two declarations under one name:\n  " + "\n  ".join(clashes))

    if a.dry_run:
        for s in sorted(moving, key=lambda s: s.path):
            print(f"{s.path} -> {new_path(s)}")
        print(f"{len(moving)} files would move")
        return

    # Maps from the old names to the new ones.
    decl_map, keep_names = {}, set()
    for s in edm:
        for d in s.decls:
            if s.new_pkg != s.pkg:
                decl_map[s.fq(d)] = f"{s.new_pkg}.{d}"
            else:
                keep_names.add(s.fq(d))
    # Code a previous run (or #22 and #527 on main) already moved: a rebased branch may still name it the old way.
    old_of = {new: OLD + (f".{sub}" if sub else "") for sub, new in [*PACKAGES.items(), *S1_PACKAGES.items()]}
    for s in sources:
        if s.sub is None and s.pkg in old_of and not s.test:
            for d in s.decls:
                fq = f"{old_of[s.pkg]}.{d}"
                if fq not in keep_names:
                    decl_map.setdefault(fq, f"{s.pkg}.{d}")
    for old, new in COMPONENTS.items():
        decl_map.setdefault(old, new)
    for gen in ("R", "BuildConfig"):  # the S1 app's generated classes follow its namespace
        decl_map[f"{OLD}.{gen}"] = f"{S1_NAMESPACE}.{gen}"
    decl_map[f"{OLD}.legacy"] = f"{S1_NAMESPACE}.legacy"  # the legacy check app's id (s1_frame_setup)
    targets = defaultdict(set)  # old package -> new packages its files went to
    for s in edm:
        targets[s.pkg].add(s.new_pkg)
    for s in sources:
        if s.sub is None and s.pkg in old_of and not s.test:
            targets[old_of[s.pkg]].add(s.pkg)
    whole_pkg = {p: next(iter(t)) for p, t in targets.items() if len(t) == 1 and p not in t}
    path_map, ambiguous = {}, set()
    for s in edm:
        old_rel = s.path[len(s.root) + 1:]
        new_rel = new_path(s)[len(s.root) + 1:]
        if path_map.get(old_rel, new_rel) != new_rel:
            ambiguous.add(old_rel)
        path_map[old_rel] = new_rel
    for d in ambiguous:
        path_map.pop(d)
    # Files that sit next to the sources (pane.json, icon.svg) move with their folder.
    assets = []
    for f in git("ls-files", "*/sh/edm/s1kiosk/*").split("\n"):
        if f and not f.endswith((".kt", ".java")):
            d = os.path.dirname(f)
            dests = {s.new_pkg for s in edm if os.path.dirname(s.path) == d and not s.test}
            if len(dests) == 1 and (dest := dests.pop()) != d.split("/java/")[-1].replace("/", "."):
                root = d[: d.index("/sh/edm/s1kiosk")]
                assets.append((f, f"{root}/{dest.replace('.', '/')}/{os.path.basename(f)}"))
                path_map[f[len(root) + 1:]] = assets[-1][1][len(root) + 1:]

    def map_dot(m, components=True):
        tok = m.group(0)
        segs = tok.split(".")
        for k in range(len(segs), 3, -1):
            pre = ".".join(segs[:k])
            if pre in COMPONENTS and not components and pre in S1_SHIMS:
                return tok
            if pre in decl_map:
                return decl_map[pre] + tok[len(pre):]
            if pre in keep_names:
                return tok
        for k in range(len(segs), 3, -1):
            pre = ".".join(segs[:k])
            if pre in whole_pkg:
                return whole_pkg[pre] + tok[len(pre):]
        return tok

    def map_slash(m):
        tok = m.group(0)
        if tok in path_map:
            return path_map[tok]
        pkg = tok.replace("/", ".")
        return whole_pkg[pkg].replace(".", "/") if pkg in whole_pkg else tok

    def expand(m):
        kind = "setOf" if m.group(1) == "setOf" or m.group(3) else "listOf"
        items = re.findall(r'"([^"]*)"', m.group(2))
        return kind + "(" + ", ".join(f'"sh/edm/s1kiosk/{x}"' for x in items) + ")"

    # 1. Move the files.
    for s in moving:
        os.makedirs(os.path.dirname(new_path(s)), exist_ok=True)
        git("mv", s.path, new_path(s))
    for old, new in assets:
        os.makedirs(os.path.dirname(new), exist_ok=True)
        git("mv", old, new)
    moved_to = {s.path: new_path(s) for s in moving}
    for s in moving:
        if not s.test and s.fq(s.stem) in COMPONENTS:
            f = new_path(s)
            text = open(f).read()
            open(f, "w").write(re.sub(rf"^class {s.stem}\b", f"open class {s.stem}", text, count=1, flags=re.M))
    shims = write_shims()
    shim_paths = {f"{m}/src/main/java/{fq.replace('.', '/')}.kt" for m, fqs in shims.items() for fq in fqs}

    # 2. Rewrite text: package lines, qualified names, file paths.
    graph = module_graph()
    by_old_pkg = defaultdict(list)
    for s in edm:
        if not s.test:
            by_old_pkg[s.pkg].append(s)
    changed = 0
    for f in git("ls-files").split("\n"):
        if not f or f.startswith(SKIP_TEXT) or not os.path.isfile(f) or f in shim_paths:
            continue
        try:
            text = open(f, encoding="utf-8").read()
        except (UnicodeDecodeError, IsADirectoryError):
            continue
        src = next((s for s in sources if moved_to.get(s.path) == f or s.path == f), None)
        if "sh.edm" not in text and "sh/edm" not in text and not (src and src.sub is not None):
            continue
        if RESEARCH.search(f):
            new = LINK.sub(lambda m: "](" + SLASH_TOKEN.sub(map_slash, m.group(1)) + ")", text)
            if new != text:
                open(f, "w", encoding="utf-8").write(new)
                changed += 1
            continue
        new = CONSTRUCTED.sub(expand, text)
        if src and src.new_pkg != src.pkg:
            new = re.sub(rf"^package\s+{re.escape(src.pkg)}\b", f"package {src.new_pkg}", new, count=1, flags=re.M)
        new = rewrite_wildcards(new, targets)
        new = rewrite_dots(new, f, map_dot)
        if f.endswith((".kt", ".java")):
            new = drop_duplicate_imports(new)
        new = SLASH_TOKEN.sub(map_slash, new)
        if src and src.sub is not None:
            new = add_imports(new, src, by_old_pkg, graph, shims)
        if new != text:
            open(f, "w", encoding="utf-8").write(new)
            changed += 1

    # 3. Generated files.
    if subprocess.run(["which", "node"], capture_output=True).returncode == 0:
        subprocess.run(["node", "packages/frame-card/scripts/panes.mjs"], check=True)
    else:
        print("warning: node not on the PATH, packages/frame-card/src/panes.generated.ts not regenerated")

    print(f"moved {len(moving)} sources and {len(assets)} other files, rewrote {changed} files")
    bad = [p for p in CHECK_UNCHANGED if git("status", "--porcelain", "--", p).strip()]
    if bad:
        raise SystemExit("these must not change, but did: " + ", ".join(bad))
    report([Source(f) for f in git("ls-files", "*.kt", "*.java").split("\n") if f])


STRING = re.compile(r'"(?:\\.|[^"\\\n])*"')


def rewrite_dots(text, path, map_dot):
    """Qualified names: a component's old name is rewritten only where it is code (Kotlin and Java outside
    string literals, and manifests of apps without shims); elsewhere it is the component's name. In an app with
    shims, an import of a component stays on the app's own subclass."""
    module = module_of(path)
    if path.endswith((".kt", ".java")):
        keep = lambda t: map_dot(t, components=False)
        lines = []
        for line in text.split("\n"):
            if module in SHIM_MODULES and line.startswith("import "):
                lines.append(DOT_TOKEN.sub(keep, line))
                continue
            if line.lstrip().startswith(("*", "/*", "//")):  # a comment names the component, as a string does
                lines.append(DOT_TOKEN.sub(keep, line))
                continue
            out, at = [], 0
            for m in STRING.finditer(line):
                out.append(DOT_TOKEN.sub(map_dot, line[at:m.start()]))
                out.append(DOT_TOKEN.sub(keep, m.group(0)))
                at = m.end()
            lines.append("".join(out) + DOT_TOKEN.sub(map_dot, line[at:]))
        return "\n".join(lines)
    components = path.endswith(".xml") and module not in SHIM_MODULES and module not in S1_MODULES
    return DOT_TOKEN.sub(lambda t: map_dot(t, components=components), text)


def drop_duplicate_imports(text):
    """Kotlin refuses an import twice; a rewrite can make an old import equal one already there."""
    seen, out = set(), []
    for line in text.split("\n"):
        if line.startswith("import "):
            key = line.strip().rstrip(";")
            if key in seen:
                continue
            seen.add(key)
        out.append(line)
    return "\n".join(out)


def write_shims():
    """Each app in SHIM_MODULES gets a subclass at the old name of every component its manifest declares."""
    shims = defaultdict(set)  # module -> old FQCNs
    for module, namespace in SHIM_MODULES.items():
        manifest = f"{module}/src/main/AndroidManifest.xml"
        if not os.path.exists(manifest):
            continue
        names = re.findall(r'<(?:activity|service|receiver)\b[^>]*?android:name="([^"]+)"', open(manifest).read(), re.S)
        for n in names:
            fq = namespace + n if n.startswith(".") else n
            if fq not in COMPONENTS or (module == S1_APP and fq not in S1_SHIMS):
                continue
            shims[module].add(fq)
            path = f"{module}/src/main/java/{fq.replace('.', '/')}.kt"
            if not os.path.exists(path):
                os.makedirs(os.path.dirname(path), exist_ok=True)
                pkg, name = fq.rsplit(".", 1)
                open(path, "w").write(SHIM.format(pkg=pkg, name=name, new=COMPONENTS[fq]))
                git("add", path)
    return shims


def rewrite_wildcards(text, targets):
    def sub(m):
        pkg = m.group(2)
        if pkg not in targets or targets[pkg] == {pkg}:
            return m.group(0)
        semi = ";" if m.group(0).rstrip().endswith(";") else ""
        return "\n".join(f"import {p}.*{semi}" for p in sorted(targets[pkg]))
    return re.sub(r"^import\s+(static\s+)?(sh\.edm\.s1kiosk(?:\.\w+)*)\.\*\s*;?\s*$", sub, text, flags=re.M)


def add_imports(text, src, by_old_pkg, graph, shims):
    """A file that leaves its package, or stays while package-mates leave, imports what it used unqualified."""
    sees = graph.get(src.module)
    imported = set()
    for line in text.splitlines():
        m = IMPORT.match(line)
        if m and not m.group(3):
            imported.add(m.group(4) or m.group(2).rsplit(".", 1)[1])
    body = code_only("\n".join(l for l in text.splitlines() if not l.startswith(("package ", "import "))))
    words = set(re.findall(r"\b[A-Za-z_]\w*\b", body))
    own = set(src.decls) | src.private
    add = []
    for other in by_old_pkg.get(src.pkg, []):
        if other.new_pkg == src.new_pkg or other.path == src.path:
            continue
        if sees is not None and other.module not in sees and not other.module.startswith("testing/"):
            continue
        for d in other.decls:
            if f"{src.new_pkg}.{d}" in shims.get(src.module, ()):
                continue  # the app's own subclass, in this package
            if d in words and d not in imported and d not in own:
                add.append(f"{other.new_pkg}.{d}")
                imported.add(d)
    if src.pkg == OLD and src.new_pkg != OLD:  # the S1 app's dropped subclasses: their code is the core's (#527)
        for fq, new in COMPONENTS.items():
            pkg, name = fq.rsplit(".", 1)
            if pkg == OLD and fq not in S1_SHIMS and name in words and name not in imported and name not in own:
                add.append(new)
                imported.add(name)
    if src.module in (S1_APP, "engines/engine-gecko-legacy/test-app", *S1_MODULES) and src.new_pkg != S1_NAMESPACE:
        for gen in ("R", "BuildConfig"):
            if gen in words and gen not in imported and src.module != "frames/aqara-s1-plus/panes/wall-base":
                add.append(f"{S1_NAMESPACE}.{gen}")
    if not add:
        return text
    semi = ";" if src.ext == "java" else ""
    lines = text.split("\n")
    last = max((i for i, l in enumerate(lines) if l.startswith("import ")), default=None)
    block = [f"import {x}{semi}" for x in sorted(add)]
    if last is None:
        pkg_line = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines[pkg_line + 1:pkg_line + 1] = [""] + block
    else:
        lines[last + 1:last + 1] = block
    return "\n".join(lines)


def s1_frame_setup():
    """#527, before the move: the S1 app's namespace becomes S1_NAMESPACE (the legacy check app's too, with its own
    id), its subclasses outside S1_SHIMS go, and its manifest names every component in full. Idempotent."""
    for g in S1_GRADLE:
        if os.path.exists(g):
            t = open(g).read()
            n = t.replace(f'namespace = "{OLD}"', f'namespace = "{S1_NAMESPACE}"')
            n = n.replace(f'applicationId = "{OLD}.legacy"', f'applicationId = "{S1_NAMESPACE}.legacy"')
            if n != t:
                open(g, "w").write(n)
    manifest = f"{S1_APP}/src/main/AndroidManifest.xml"
    if not os.path.exists(manifest):
        return
    for fq in COMPONENTS:
        path = f"{S1_APP}/src/main/java/{fq.replace('.', '/')}.kt"
        if fq not in S1_SHIMS and os.path.exists(path):
            git("rm", "-q", path)
    text = open(manifest).read()

    def full(m):
        attr, name = m.group(1), m.group(2)
        fq = OLD + name if name.startswith(".") else name
        if fq in S1_SHIMS or fq == S1_ALIAS:
            return f'{attr}="{fq}"'
        if fq in COMPONENTS:
            return f'{attr}="{COMPONENTS[fq]}"'
        if fq.startswith(OLD + "."):
            rest = fq[len(OLD) + 1:]
            sub, _, cls = rest.rpartition(".")
            if sub in S1_PACKAGES:
                return f'{attr}="{S1_PACKAGES[sub]}.{cls}"'
            if sub in PACKAGES:  # a core class an S1 branch added under the old package goes with the core (base_rule)
                return f'{attr}="{PACKAGES[sub]}.{cls}"'
        return m.group(0)
    new = re.sub(r'(android:(?:name|targetActivity))="(\.[\w.]+|sh\.edm\.s1kiosk\.[\w.]+)"', full, text)
    if new != text:
        open(manifest, "w").write(new)


def report(sources):
    left = defaultdict(list)
    for s in sources:
        if s.pkg == OLD or s.pkg.startswith(OLD + "."):
            if s.module == S1_APP and s.fq(s.stem) in S1_SHIMS:
                kind = "S1 component name kept"
            elif s.module in SHIM_MODULES and s.fq(s.stem) in COMPONENTS:
                kind = "component name kept by its app"
            else:
                kind = "UNEXPECTED"
            left[kind].append(s.path)
    for kind, paths in sorted(left.items()):
        print(f"{kind}: {len(paths)} files left in {OLD}")
        if kind != "S1 component name kept":
            for p in sorted(paths):
                print(f"  {p}")
    if left.get("UNEXPECTED"):
        sys.exit(1)


if __name__ == "__main__":
    main()
