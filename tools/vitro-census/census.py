#!/usr/bin/env python3
"""Count dashboard card types and mark which ones Vitro draws natively.

Touches: read-only. Reads the dispatch in VitroView.kt and the dashboard files you pass
(HA lovelace JSON, as `lovelace/config` returns it, or YAML; `!include` and other tags are ignored).
Never talks to Home Assistant or GitHub.

  python3 tools/vitro-census/census.py dash1.json dash2.yaml ...        # per file, then totals
  python3 tools/vitro-census/census.py --min-cards 5 comm/*/*.yaml      # one file = one dashboard
  python3 tools/vitro-census/census.py --per-dir --min-cards 5 comm/*/*.yaml   # one folder (repo) = one dashboard

The community sample in docs/research/vitro-island-census.md is the --per-dir run over the files
sample.py fetches.

Native = a card type the `when (card.type)` in VitroView.kt routes to a native view; everything else
goes to the web island. A dashboard's cards are the ones under views[].cards and
views[].sections[].cards, plus nested `cards` and `card` (stacks, grids, conditional).
Entity rows, picture-elements and chips are not cards.
"""
import argparse, collections, json, os, re, sys
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/ui/VitroView.kt")
# Entries that are view/row names a loose YAML walk can mistake for cards.
NOT_CARDS = {"divider", "sections", "masonry", "panel", "section", "sidebar"}


def native_types():
    src = open(KT).read()
    seg = src[src.index("val built: View = when (card.type)"):src.index("else -> WebIslandCardView")]
    return set(re.findall(r'"((?:custom:)?[a-z0-9-]+)"', seg))


class Loader(yaml.SafeLoader):
    pass


Loader.add_multi_constructor("!", lambda l, s, n: l.construct_scalar(n) if isinstance(n, yaml.ScalarNode)
                             else l.construct_sequence(n) if isinstance(n, yaml.SequenceNode) else l.construct_mapping(n))


def walk(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "cards" and isinstance(v, list):
                for c in v:
                    handle(c, out)
            elif k == "card" and isinstance(v, dict):
                handle(v, out)
            elif isinstance(v, (dict, list)) and k not in ("entities", "elements", "chips", "badges"):
                walk(v, out)
    elif isinstance(node, list):
        for x in node:
            walk(x, out)


def handle(c, out):
    if isinstance(c, dict):
        if isinstance(c.get("type"), str):
            out.append(c["type"])
        walk(c, out)


def cards(doc):
    out = []
    # A storage export wraps the dashboard (`{"config": {...}}`, `{"data": {"config": {...}}}`): count it as the
    # dashboard, not as a fragment, so an unused template card in it is left out as it is unwrapped.
    if isinstance(doc, dict) and "views" not in doc:
        inner = doc.get("config") if isinstance(doc.get("config"), dict) else (doc.get("data") or {}).get("config") if isinstance(doc.get("data"), dict) else None
        if isinstance(inner, dict) and "views" in inner:
            return cards(inner)
    views = doc.get("views") if isinstance(doc, dict) else None
    if views is not None:
        for v in views or []:
            if isinstance(v, dict):
                for c in v.get("cards") or []:
                    handle(c, out)
                for s in v.get("sections") or []:
                    for c in (s.get("cards") or []) if isinstance(s, dict) else []:
                        handle(c, out)
    elif isinstance(doc, dict) and "type" in doc:
        handle(doc, out)
    elif isinstance(doc, dict):
        walk(doc, out)
    elif isinstance(doc, list):
        for c in doc:
            if isinstance(c, dict) and "type" in c:
                handle(c, out)
            elif isinstance(c, dict):
                out += cards({"views": [c]})
    return [t for t in out if t not in NOT_CARDS]


def load(path):
    text = open(path, errors="ignore").read()
    return [d for d in yaml.load_all(text, Loader=Loader)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--min-cards", type=int, default=1, help="skip dashboards with fewer cards (fragments)")
    ap.add_argument("--per-dir", action="store_true", help="merge the files in each folder into one dashboard")
    a = ap.parse_args()
    nat = native_types()
    occ, dash = collections.Counter(), collections.Counter()
    groups = collections.OrderedDict()
    for f in a.files:
        groups.setdefault(os.path.dirname(f) if a.per_dir else f, []).append(f)
    n = 0
    for f, files in groups.items():
        c = []
        for path in files:
            try:
                c += [t for d in load(path) for t in cards(d)]
            except Exception as e:  # noqa: BLE001
                print(f"skip {path}: {e}", file=sys.stderr)
        if len(c) < a.min_cards:
            continue
        n += 1
        occ.update(c)
        dash.update(set(c))
        if len(groups) <= 12:
            k = sum(t in nat for t in c)
            print(f"{os.path.basename(f)}: {len(c)} cards, {k} native, {len(c) - k} island")
    tot = sum(occ.values())
    isl = sum(v for t, v in occ.items() if t not in nat)
    print(f"\n{n} dashboards, {tot} cards, {isl} island ({100 * isl / max(tot, 1):.1f}%)\n")
    print("dashboards  cards  type")
    for t, d in sorted(dash.items(), key=lambda x: (-x[1], -occ[x[0]])):
        print(f"{d:10d} {occ[t]:6d}  {t}{'' if t in nat else '   ISLAND'}")


if __name__ == "__main__":
    main()
