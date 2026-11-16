"""The pane ids the app's Kotlin declares, for the tests that check every pane has its budgets and parts
(tools/perf/test/test_grade.py, tools/size/test/test_apksize.py). Reads source files only; changes nothing."""
import os
import re


# Where Gradle modules live, as RepoFiles.SEARCH (testing/kotlin), by top folder.
MODULE_TOPS = ("libs", "core", "pane-api", "engines", "panes", "frames")
SKIP = {"build", ".gradle", ".kotlin", "node_modules", ".git", "test", "androidTest"}


def main_sources(root):
    """Every module's src/main/java and src/main/kotlin under [root]."""
    found = []
    for top in MODULE_TOPS:
        for d, dirs, _ in os.walk(os.path.join(root, top)):
            dirs[:] = [x for x in dirs if x not in SKIP]
            if d.endswith(os.path.join("src", "main", "java")) or d.endswith(os.path.join("src", "main", "kotlin")):
                found.append(d)
                dirs[:] = []
    assert found, f"no module sources under {root}"
    return found


def pane_ids_in(src):
    """The pane ids the Kotlin under [src] declares with `override val id = …`: a literal, the file's own
    `const val ID = "…"`, or another class's (`CameraPane.ID`). An id that resolves to nothing fails, so a
    new form can't slip past the budget check (#117: the camera pane's constant did)."""
    texts = []
    for d, _, fs in (w for s in ([src] if isinstance(src, str) else src) for w in os.walk(s)):
        for f in fs:
            if f.endswith(".kt"):
                with open(os.path.join(d, f)) as fh:
                    texts.append(fh.read())
    consts = {}  # class name -> its companion's ID
    for t in texts:
        for cls, body in re.findall(r'class (\w+)\b(.*?)(?=\n\S[^\n]*\bclass \w|\Z)', t, re.S):
            m = re.search(r'const val ID = "([a-z0-9-]+)"', body)
            if m:
                consts[cls] = m.group(1)
    ids = set()
    for t in texts:
        for cls, body in re.findall(r'class (\w+)\b(.*?)(?=\n\S[^\n]*\bclass \w|\Z)', t, re.S):
            for ref in re.findall(r'override val id = ([^\n]+)', body):
                ref = ref.split("//")[0].strip()
                lit = re.fullmatch(r'"([a-z0-9-]+)"', ref)
                if lit:
                    ids.add(lit.group(1))
                    continue
                name = re.fullmatch(r'(?:(\w+)\.)?ID', ref)
                assert name, f"{cls}: a pane id this test can't read: {ref}"
                owner = name.group(1) or cls
                assert owner in consts, f"{cls}: {ref} isn't a const val ID this test can find"
                ids.add(consts[owner])
    return ids
