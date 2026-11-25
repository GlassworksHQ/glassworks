#!/usr/bin/env python3
"""Count dashboard card types and mark which ones Vitro draws natively.

Touches: read-only. Reads the native card types in VitroSupport.kt and the dashboard files you pass
(HA lovelace JSON, as `lovelace/config` returns it, or YAML; `!include` and other tags are ignored).
Never talks to Home Assistant or GitHub. Writes only the file --dump-cards names.

  python3 tools/vitro-census/census.py dash1.json dash2.yaml ...        # per file, then totals
  python3 tools/vitro-census/census.py --min-cards 5 comm/*/*.yaml      # one file = one dashboard
  python3 tools/vitro-census/census.py --per-dir --min-cards 5 comm/*/*.yaml   # one folder (repo) = one dashboard

  python3 tools/vitro-census/census.py --per-dir --min-cards 5 --dump-cards cards.jsonl comm/*/*.yaml
  VITRO_CENSUS_CARDS=$PWD/cards.jsonl VITRO_CENSUS_OUT=$PWD/verdicts.jsonl \
    ./gradlew --max-workers=2 :engine-vitro:testDebugUnitTest --tests '*CensusLocal*'      # the engine's verdicts
  python3 tools/vitro-census/census.py --per-dir --min-cards 5 --verdicts verdicts.jsonl comm/*/*.yaml

The community sample in docs/research/vitro-island-census.md is the --per-dir run over the files
sample.py fetches.

Native = a card type VitroSupport.kt lists as native (CORE_CARD_TYPES, HACS_CARD_TYPES); everything else goes
to the web island. Some native types still send some configs to an island (custom:button-card with JavaScript
templates, a swipe-card with Swiper parameters Vitro doesn't run): with --verdicts, each card of such a type
takes the engine's own verdict (CompatChecker, through --dump-cards and the CensusLocal test), with the
dashboard's `button_card_templates` merged in. A dashboard's cards are the ones under views[].cards and
views[].sections[].cards, plus nested `cards` and `card` (stacks, grids, conditional).
Entity rows, picture-elements and chips are not cards.
"""
import argparse, collections, json, os, re, sys
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/compat/VitroSupport.kt")
# Native types whose route depends on the config: --verdicts decides each of their cards.
BY_CONFIG = {"custom:button-card", "custom:swipe-card", "custom:weather-card"}
# Entries that are view/row names a loose YAML walk can mistake for cards.
NOT_CARDS = {"divider", "sections", "masonry", "panel", "section", "sidebar"}


def native_types():
    src = open(KT).read()
    out = set()
    for name in ("CORE_CARD_TYPES", "HACS_CARD_TYPES"):
        seg = re.search(r"val " + name + r": Set<String> = setOf\(([\s\S]*?)\)\n", src).group(1)
        out |= set(re.findall(r'"([^"]+)"', seg))
    return out


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


CONFIGS = []   # the card configs handle() saw, in the order of their types


def handle(c, out):
    if isinstance(c, dict):
        if isinstance(c.get("type"), str):
            out.append(c["type"])
            # cards() drops NOT_CARDS from the types; the configs stay in step with what it keeps.
            if c["type"] not in NOT_CARDS:
                CONFIGS.append(c)
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


def templates_of(docs):
    """The dashboard's `button_card_templates` (top level, or in a storage export's config)."""
    out = {}
    for d in docs:
        if not isinstance(d, dict):
            continue
        for holder in (d, d.get("config"), (d.get("data") or {}).get("config") if isinstance(d.get("data"), dict) else None):
            if isinstance(holder, dict) and isinstance(holder.get("button_card_templates"), dict):
                out.update(holder["button_card_templates"])
    return out


def load(path):
    text = open(path, errors="ignore").read()
    return [d for d in yaml.load_all(text, Loader=Loader)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--min-cards", type=int, default=1, help="skip dashboards with fewer cards (fragments)")
    ap.add_argument("--per-dir", action="store_true", help="merge the files in each folder into one dashboard")
    ap.add_argument("--dump-cards", help="write each card of a config-dependent type, with its templates, as JSON lines")
    ap.add_argument("--verdicts", help="the CensusLocal test's verdicts (JSON lines) for the --dump-cards cards")
    a = ap.parse_args()
    nat = native_types()
    verdicts = {}
    if a.verdicts:
        for line in open(a.verdicts):
            v = json.loads(line)
            verdicts[v["id"]] = v
    dump = open(a.dump_cards, "w") if a.dump_cards else None
    by_reason = collections.Counter()
    occ, dash = collections.Counter(), collections.Counter()
    groups = collections.OrderedDict()
    for f in a.files:
        groups.setdefault(os.path.dirname(f) if a.per_dir else f, []).append(f)
    n = 0
    for f, files in groups.items():
        c = []
        docs = []
        del CONFIGS[:]
        for path in files:
            try:
                ds = load(path)
                docs += ds
                c += [t for d in ds for t in cards(d)]
            except Exception as e:  # noqa: BLE001
                print(f"skip {path}: {e}", file=sys.stderr)
        configs = list(CONFIGS)
        if len(c) < a.min_cards:
            continue
        tpl = templates_of(docs)
        # A config-dependent card is native only when the engine says so (else it counts under its type + ISLAND).
        for i, (t, cfg) in enumerate(zip(c, configs)):
            if t not in BY_CONFIG:
                continue
            cid = f"{f}#{i}"
            if dump:
                dump.write(json.dumps({"id": cid, "templates": tpl, "card": cfg}, default=str) + "\n")
            v = verdicts.get(cid)
            if v and v["route"] != "NATIVE":
                c[i] = t + " (island)"
                by_reason[(v.get("reasons") or ["?"])[0].split(" (")[0][:90]] += 1
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
    if by_reason:
        print("\nwhy config-dependent cards go to an island (first reason):")
        for r, n in by_reason.most_common():
            print(f"{n:6d}  {r}")


if __name__ == "__main__":
    main()
