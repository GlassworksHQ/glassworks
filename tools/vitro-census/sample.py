#!/usr/bin/env python3
"""Draw the community dashboard sample for the Vitro island census (docs/research/vitro-island-census.md).

Touches: read-only on GitHub (code search through `gh api`, raw file downloads); writes only under OUT.
Never stores tokens. The files it downloads are public dashboards and stay in OUT, outside the repo.

  python3 tools/vitro-census/sample.py search OUT        # 3 code searches -> OUT/hits.json (needs `gh auth`)
  python3 tools/vitro-census/sample.py draw OUT          # seeded draw of 80 repos -> OUT/manifest.json
  python3 tools/vitro-census/sample.py fetch MANIFEST OUT   # download each pinned file -> OUT/comm/<repo>/<i>.yaml
  python3 tools/vitro-census/census.py --per-dir --min-cards 5 OUT/comm/*/*.yaml

Code search results change over time, so `search` + `draw` won't give the same repos twice. The manifest of the
published sample (sample-manifest.json, next to this script) pins every file to the commit GitHub returned, so
`fetch sample-manifest.json OUT` reproduces the note's numbers exactly.
"""
import json, os, random, subprocess, sys, time

QUERIES = ['"views:" "type: custom:" filename:ui-lovelace.yaml',
           '"type: custom:" "cards:" path:dashboards extension:yaml',
           '"type: custom:" "views:" path:lovelace extension:yaml']
SEED, REPOS, FILES_PER_REPO = 798, 80, 4


def search(out):
    hits = {}
    for q in QUERIES:
        for page in (1, 2, 3):
            p = subprocess.run(["gh", "api", "-X", "GET", "search/code", "-f", "q=" + q, "-f", "per_page=100",
                                "-f", f"page={page}"], capture_output=True, text=True)
            items = json.loads(p.stdout or "{}").get("items")
            if items is None:
                print("search failed:", p.stderr[:200], file=sys.stderr)
                break
            for it in items:
                hits.setdefault(it["repository"]["full_name"], []).append([it["path"], it["html_url"]])
            if len(items) < 100:
                break
            time.sleep(7)  # code search allows about 10 requests a minute
    json.dump(hits, open(os.path.join(out, "hits.json"), "w"), indent=1, sort_keys=True)
    print(len(hits), "repositories")


def draw(out):
    hits = json.load(open(os.path.join(out, "hits.json")))
    random.seed(SEED)
    repos = sorted(hits)
    random.shuffle(repos)
    manifest = [{"repo": r, "files": [{"path": p, "url": u} for p, u in hits[r][:FILES_PER_REPO]]} for r in repos[:REPOS]]
    json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=1)
    print(len(manifest), "of", len(repos), "repositories")


def fetch(manifest, out):
    for entry in json.load(open(manifest)):
        d = os.path.join(out, "comm", entry["repo"].replace("/", "__"))
        os.makedirs(d, exist_ok=True)
        for i, f in enumerate(entry["files"]):
            raw = f["url"].replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/", 1)
            subprocess.run(["curl", "-sfL", "-m", "20", "-o", os.path.join(d, f"{i}.yaml"), raw])


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["-h"]
    {"search": search, "draw": draw, "fetch": fetch}.get(cmd, lambda *_: print(__doc__))(*args)
