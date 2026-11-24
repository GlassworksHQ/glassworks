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
    os.makedirs(out, exist_ok=True)
    hits = {}
    for q in QUERIES:
        for page in (1, 2, 3):
            p = subprocess.run(["gh", "api", "-X", "GET", "search/code", "-f", "q=" + q, "-f", "per_page=100",
                                "-f", f"page={page}"], capture_output=True, text=True)
            items = json.loads(p.stdout or "{}").get("items")
            if items is None:  # fail before writing hits.json, so a draw never uses a partial search
                sys.exit(f"search failed ({q!r}, page {page}): {(p.stderr or p.stdout)[:200]}")
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
    def distinct(pairs):  # one file can match several queries; keep its first hit only
        seen = set()
        return [(p, u) for p, u in pairs if not (p in seen or seen.add(p))]
    manifest = [{"repo": r, "files": [{"path": p, "url": u} for p, u in distinct(hits[r])[:FILES_PER_REPO]]}
                for r in repos[:REPOS]]
    json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=1)
    print(len(manifest), "of", len(repos), "repositories")


def fetch(manifest, out):
    comm = os.path.join(out, "comm")
    if os.path.exists(comm) and os.listdir(comm):
        sys.exit(f"{comm} isn't empty; fetch into a new folder, or census.py --per-dir would count old files too")
    failed = []
    for entry in json.load(open(manifest)):
        d = os.path.join(comm, entry["repo"].replace("/", "__"))
        os.makedirs(d, exist_ok=True)
        for i, f in enumerate(entry["files"]):
            if not f["url"].startswith("https://github.com/"):
                sys.exit(f"not a GitHub file URL: {f['url']}")
            raw = f["url"].replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/", 1)
            dest = os.path.join(d, f"{i}.yaml")
            # Download beside the file and rename on success, so a failed transfer leaves nothing for the census.
            if subprocess.run(["curl", "-sfL", "-m", "20", "-o", dest + ".part", raw]).returncode == 0:
                os.replace(dest + ".part", dest)
            else:
                if os.path.exists(dest + ".part"):
                    os.remove(dest + ".part")
                failed.append(f"{entry['repo']}: {f['path']}")
    if failed:
        sys.exit("download failed, so the sample is incomplete:\n  " + "\n  ".join(failed))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["-h"]
    {"search": search, "draw": draw, "fetch": fetch}.get(cmd, lambda *_: print(__doc__))(*args)
