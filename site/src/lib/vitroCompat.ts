// Vitro dashboard compatibility check, 100% client-side. Nothing here touches the network: the
// caller hands in the dashboard text and gets a verdict back. The lists come from
// public/vitro-compat.json (also served at /vitro-compat.json), which a test keeps equal to
// LovelaceModel.kt in the Vitro engine and to the audit script's lists.
import manifest from "../../public/vitro-compat.json";

export const VITRO_COMPAT = manifest;

const NATIVE_CORE = new Set<string>(manifest.nativeCoreTypes);
const NATIVE_HACS = new Set<string>(manifest.nativeHacsTypes);
const CONTAINERS = new Set<string>(manifest.containerTypes);
const TILE_FEATURES = new Set<string>(manifest.supportedTileFeatures);

// ---------------------------------------------------------------------------------------------
// A small YAML reader for Lovelace: block maps and lists, flow [..] and {..}, plain and quoted
// scalars, | and > block scalars, comments, anchors (&a, *a, <<), and the first document only.
// ---------------------------------------------------------------------------------------------

export class YamlError extends Error {
  constructor(message: string, readonly line: number) {
    super(`YAML line ${line}: ${message}`);
  }
}

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

interface Row {
  indent: number;
  text: string; // comment stripped and trimmed; may be rewritten for "- key: value" items
  raw: string;
  no: number;
}

/** Cut a trailing ` # comment` that isn't inside quotes. */
function stripComment(s: string): string {
  let quote = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      if (c === "\\" && quote === '"') i++;
      else if (c === quote) {
        if (quote === "'" && s[i + 1] === "'") i++;
        else quote = "";
      }
    } else if ((c === '"' || c === "'") && (i === 0 || /[\s[{,:-]/.test(s[i - 1]))) {
      quote = c;
    } else if (c === "#" && (i === 0 || /\s/.test(s[i - 1]))) {
      return s.slice(0, i).trimEnd();
    }
  }
  return s.trimEnd();
}

/** Index of the `:` that ends a block-map key, or -1 when the text isn't a `key: value` line. */
function keyColon(text: string): number {
  const first = text[0];
  if (first === "[" || first === "{" || first === "|" || first === ">" || first === "*") return -1;
  let i = 0;
  if (first === '"' || first === "'") {
    for (i = 1; i < text.length; i++) {
      if (first === '"' && text[i] === "\\") i++;
      else if (text[i] === first) {
        if (first === "'" && text[i + 1] === "'") i++;
        else break;
      }
    }
    i++;
    while (text[i] === " ") i++;
    return text[i] === ":" && (i + 1 === text.length || text[i + 1] === " ") ? i : -1;
  }
  for (; i < text.length; i++) {
    if (text[i] === ":" && (i + 1 === text.length || text[i + 1] === " ")) return i;
  }
  return -1;
}

function unquote(s: string, line: number): string {
  const q = s[0];
  const body = s.slice(1, -1);
  if (q === "'") return body.replace(/''/g, "'");
  return body.replace(/\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)/g, (_m, e: string) => {
    if (e[0] === "u" || e[0] === "x") return String.fromCharCode(parseInt(e.slice(1), 16));
    const map: Record<string, string> = { n: "\n", t: "\t", r: "\r", "0": "\0", '"': '"', "\\": "\\", "/": "/", " ": " " };
    if (e in map) return map[e];
    throw new YamlError(`unknown escape \\${e}`, line);
  });
}

function scalar(s: string, line: number): Json {
  const t = s.trim();
  if (t === "") return null;
  if ((t[0] === '"' || t[0] === "'") && t.length >= 2 && t[t.length - 1] === t[0]) return unquote(t, line);
  if (/^(~|null|Null|NULL)$/.test(t)) return null;
  if (/^(true|True|TRUE)$/.test(t)) return true;
  if (/^(false|False|FALSE)$/.test(t)) return false;
  if (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) return Number(t);
  return t;
}

/** Reads one flow collection or scalar from `s`, starting at `pos`. */
function flow(s: string, pos: { i: number }, line: number): Json {
  const skip = () => {
    while (pos.i < s.length && /\s/.test(s[pos.i])) pos.i++;
  };
  skip();
  const c = s[pos.i];
  if (c === "[") {
    pos.i++;
    const out: Json[] = [];
    for (;;) {
      skip();
      if (pos.i >= s.length) throw new YamlError("unterminated [ list", line);
      if (s[pos.i] === "]") {
        pos.i++;
        return out;
      }
      out.push(flow(s, pos, line));
      skip();
      if (s[pos.i] === ",") pos.i++;
      else if (s[pos.i] !== "]") throw new YamlError("expected , or ] in a flow list", line);
    }
  }
  if (c === "{") {
    pos.i++;
    const out: { [k: string]: Json } = {};
    for (;;) {
      skip();
      if (pos.i >= s.length) throw new YamlError("unterminated { map", line);
      if (s[pos.i] === "}") {
        pos.i++;
        return out;
      }
      const key = flowScalar(s, pos, line, true);
      skip();
      let value: Json = null;
      if (s[pos.i] === ":") {
        pos.i++;
        skip();
        if (s[pos.i] !== "," && s[pos.i] !== "}") value = flow(s, pos, line);
      }
      out[String(key)] = value;
      skip();
      if (s[pos.i] === ",") pos.i++;
      else if (s[pos.i] !== "}") throw new YamlError("expected , or } in a flow map", line);
    }
  }
  return flowScalar(s, pos, line, false);
}

function flowScalar(s: string, pos: { i: number }, line: number, isKey: boolean): Json {
  const start = pos.i;
  const q = s[start];
  if (q === '"' || q === "'") {
    let i = start + 1;
    for (; i < s.length; i++) {
      if (q === '"' && s[i] === "\\") i++;
      else if (s[i] === q) {
        if (q === "'" && s[i + 1] === "'") i++;
        else break;
      }
    }
    if (i >= s.length) throw new YamlError("unterminated quoted string", line);
    pos.i = i + 1;
    return unquote(s.slice(start, i + 1), line);
  }
  let i = start;
  while (i < s.length) {
    const ch = s[i];
    if (ch === "," || ch === "]" || ch === "}") break;
    if (isKey && ch === ":" && (s[i + 1] === " " || s[i + 1] === "," || s[i + 1] === "}" || i + 1 === s.length)) break;
    i++;
  }
  pos.i = i;
  const raw = s.slice(start, i);
  // An alias inside [ ] or { } (a value, or a map key) would read as the text "*name": refuse it instead.
  if (/^\*\S+$/.test(raw.trim())) {
    throw new YamlError(`alias ${raw.trim()} inside a flow collection isn't supported; use a block list`, line);
  }
  return isKey ? raw.trim() : scalar(raw, line);
}

/** True while a flow collection or quote is still open in `s`. */
function isOpen(s: string): boolean {
  let depth = 0;
  let quote = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      if (c === "\\" && quote === '"') i++;
      else if (c === quote) quote = "";
    } else if ((c === '"' || c === "'") && (i === 0 || /[\s[{,:]/.test(s[i - 1]))) quote = c;
    else if (c === "[" || c === "{") depth++;
    else if (c === "]" || c === "}") depth--;
  }
  return depth > 0 || quote !== "";
}

class Reader {
  private rows: Row[] = [];
  private raws: string[];
  private p = 0; // next raw line
  private anchors = new Map<string, Json>();

  constructor(source: string) {
    this.raws = source.replace(/^﻿/, "").split(/\r?\n/);
  }

  /** The next non-blank, non-comment row at or after the cursor, without consuming it. */
  private peek(): Row | null {
    while (this.p < this.raws.length) {
      const raw = this.raws[this.p];
      const no = this.p + 1;
      const trimmed = raw.trim();
      if (trimmed === "" || trimmed[0] === "#") {
        this.p++;
        continue;
      }
      const lead = raw.match(/^[ \t]*/)![0];
      if (lead.includes("\t")) throw new YamlError("tabs can't indent YAML, use spaces", no);
      if (lead.length === 0 && (/^---(\s|$)/.test(raw) || /^\.\.\.(\s|$)/.test(raw))) return null; // document marker
      const cached = this.rows[this.p];
      if (cached) return cached;
      const row: Row = { indent: lead.length, text: stripComment(trimmed), raw, no };
      this.rows[this.p] = row;
      return row;
    }
    return null;
  }

  parseDocument(): Json {
    // skip directives and a leading ---
    while (this.p < this.raws.length) {
      const t = this.raws[this.p].trim();
      if (t === "" || t[0] === "#" || t[0] === "%") this.p++;
      else if (/^---(\s|$)/.test(this.raws[this.p])) {
        const rest = stripComment(this.raws[this.p].slice(3).trim());
        this.p++;
        if (rest) {
          this.raws.splice(this.p, 0, rest); // "--- {…}" style: content on the marker line
        }
        break;
      } else break;
    }
    const row = this.peek();
    if (!row) return null;
    const value = this.block(row.indent);
    const extra = this.peek();
    if (extra) throw new YamlError("unexpected content, check the indentation", extra.no);
    return value;
  }

  private block(minIndent: number): Json {
    const row = this.peek();
    if (!row || row.indent < minIndent) return null;
    if (row.text === "-" || row.text.startsWith("- ")) return this.seq(row.indent);
    if (keyColon(row.text) !== -1) return this.map(row.indent);
    this.p++;
    return this.inline(row.text, row.indent - 1, row.no);
  }

  private seq(indent: number): Json {
    const out: Json[] = [];
    for (;;) {
      const row = this.peek();
      if (!row || row.indent !== indent || !(row.text === "-" || row.text.startsWith("- "))) break;
      let rest = row.text.slice(1).trimStart();
      if (rest === "") {
        this.p++;
        out.push(this.block(indent + 1));
        continue;
      }
      let anchor: string | null = null;
      const am = rest.match(/^&(\S+)\s*/);
      if (am) {
        anchor = am[1];
        rest = rest.slice(am[0].length);
      }
      let value: Json;
      if (rest === "") {
        this.p++;
        value = this.block(indent + 1);
      } else if (rest === "-" || rest.startsWith("- ") || keyColon(rest) !== -1) {
        // "- key: value" and "- - x": the item's own lines continue at the column of `rest`
        const offset = row.text.length - rest.length;
        this.rows[this.p] = { ...row, indent: indent + offset, text: rest };
        value = this.block(indent + offset);
      } else {
        this.p++;
        value = this.inline(rest, indent, row.no);
      }
      if (anchor) this.anchors.set(anchor, value);
      out.push(value);
    }
    return out;
  }

  private map(indent: number): Json {
    const out: { [k: string]: Json } = {};
    const merges: Json[] = [];
    for (;;) {
      const row = this.peek();
      if (!row || row.indent !== indent) break;
      const colon = keyColon(row.text);
      if (colon === -1) {
        if (row.text.startsWith("- ")) break;
        throw new YamlError(`expected "key: value", found "${row.text.slice(0, 40)}"`, row.no);
      }
      const keyText = row.text.slice(0, colon).trim();
      const key = keyText[0] === '"' || keyText[0] === "'" ? unquote(keyText, row.no) : keyText;
      let rest = row.text.slice(colon + 1).trim();
      let anchor: string | null = null;
      const am = rest.match(/^&(\S+)\s*/);
      if (am) {
        anchor = am[1];
        rest = rest.slice(am[0].length);
      }
      this.p++;
      let value: Json;
      if (rest === "") {
        const next = this.peek();
        if (next && next.indent > indent) value = this.block(next.indent);
        else if (next && next.indent === indent && (next.text === "-" || next.text.startsWith("- "))) value = this.seq(indent);
        else value = null;
      } else {
        value = this.inline(rest, indent, row.no);
      }
      if (anchor) this.anchors.set(anchor, value);
      if (key === "<<") merges.push(value);
      else out[key] = value;
    }
    for (const m of merges) {
      for (const src of Array.isArray(m) ? m : [m]) {
        if (src && typeof src === "object" && !Array.isArray(src)) {
          for (const [k, v] of Object.entries(src)) if (!(k in out)) out[k] = v;
        }
      }
    }
    return out;
  }

  /** A value written after `key:` or `- `, which may carry on over the lines indented below it. */
  private inline(text: string, parentIndent: number, no: number): Json {
    if (text[0] === "*" && /^\*\S+$/.test(text)) {
      const name = text.slice(1);
      if (!this.anchors.has(name)) throw new YamlError(`alias *${name} has no anchor`, no);
      return this.anchors.get(name)!;
    }
    const block = text.match(/^([|>])([+-]?)(\d?)([+-]?)$/);
    if (block) return this.blockScalar(block[1] === ">", block[2] || block[4], parentIndent);

    if (text[0] === "[" || text[0] === "{" || text[0] === '"' || text[0] === "'") {
      let joined = text;
      while (isOpen(joined)) {
        if (this.p >= this.raws.length) throw new YamlError("unterminated flow collection or quoted string", no);
        const next = this.raws[this.p++].trim();
        joined += text[0] === '"' || text[0] === "'" ? ` ${next}` : ` ${stripComment(next)}`;
      }
      const t = stripComment(joined);
      if (text[0] === "[" || text[0] === "{") {
        const pos = { i: 0 };
        const v = flow(t, pos, no);
        if (t.slice(pos.i).trim() !== "") throw new YamlError("unexpected text after a flow collection", no);
        return v;
      }
      return scalar(t, no);
    }

    // plain scalar, which may continue on more-indented lines
    let value = text;
    for (;;) {
      const next = this.peek();
      if (!next || next.indent <= parentIndent) break;
      if (next.text === "-" || next.text.startsWith("- ") || keyColon(next.text) !== -1) break;
      value += ` ${next.text}`;
      this.p++;
    }
    return scalar(value, no);
  }

  private blockScalar(fold: boolean, chomp: string, parentIndent: number): string {
    const lines: string[] = [];
    let blockIndent = -1;
    while (this.p < this.raws.length) {
      const raw = this.raws[this.p];
      if (raw.trim() === "") {
        lines.push("");
        this.p++;
        continue;
      }
      const indent = raw.match(/^ */)![0].length;
      if (indent <= parentIndent) break;
      if (blockIndent === -1) blockIndent = indent;
      if (indent < blockIndent) break;
      lines.push(raw.slice(blockIndent));
      this.p++;
    }
    // trailing blank lines are chomping, not content
    let trailing = 0;
    while (lines.length > 0 && lines[lines.length - 1] === "") {
      lines.pop();
      trailing++;
    }
    if (lines.length === 0) return "";
    let body: string;
    if (fold) {
      body = "";
      for (let i = 0; i < lines.length; i++) {
        const l = lines[i];
        if (i === 0) body = l;
        else if (l === "") body += "\n";
        else if (lines[i - 1] === "" || l.startsWith(" ") || lines[i - 1].startsWith(" ")) body += (lines[i - 1] === "" ? "" : "\n") + l;
        else body += ` ${l}`;
      }
    } else body = lines.join("\n");
    if (chomp === "-") return body;
    if (chomp === "+") return `${body}\n${"\n".repeat(trailing)}`;
    return `${body}\n`;
  }
}

export function parseYaml(source: string): unknown {
  return new Reader(source).parseDocument();
}

/** Parses pasted dashboard text: JSON first, then YAML. */
export function parseDashboardText(input: string): unknown {
  const t = input.trim();
  if (t === "") throw new Error("Paste a dashboard first.");
  if (t[0] === "{" || t[0] === "[") {
    try {
      return JSON.parse(t);
    } catch {
      // flow-style YAML looks the same at the first character
    }
  }
  return parseYaml(input);
}

// ---------------------------------------------------------------------------------------------
// The audit
// ---------------------------------------------------------------------------------------------

export interface TriggerItem {
  view: string;
  path: string;
  type: string;
}

export interface ViewAudit {
  name: string;
  type: string;
  tier1: number;
  tier2: number;
  tier3: number;
  native: boolean;
  triggers: TriggerItem[];
  warnings: string[];
}

export interface VitroAuditResult {
  ok: boolean;
  error?: string;
  isFullyNative: boolean;
  totals: { tier1: number; tier2: number; tier3: number };
  views: ViewAudit[];
  triggers: TriggerItem[];
  warnings: string[];
}

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

function fail(error: string): VitroAuditResult {
  return { ok: false, error, isFullyNative: false, totals: { tier1: 0, tier2: 0, tier3: 0 }, views: [], triggers: [], warnings: [] };
}

function cardModWarning(card: Obj, loc: string, out: string[]) {
  const cm = card.card_mod;
  if (!isObj(cm)) return;
  const style = cm.style;
  if (isObj(style) || (typeof style === "string" && (style.includes("$:") || style.includes("@keyframes") || style.includes("::")))) {
    out.push(`${loc}: complex Shadow-DOM, pseudo-element, or keyframe card_mod (only ha-card container styles carry over natively)`);
  }
}

function walkCard(card: unknown, loc: string, view: ViewAudit) {
  if (!isObj(card)) {
    view.warnings.push(`${loc}: not a card (expected a map with a type), skipped`);
    return;
  }
  const type = typeof card.type === "string" ? card.type.trim() : "";
  cardModWarning(card, loc, view.warnings);

  if (type === "tile" && Array.isArray(card.features)) {
    for (const f of card.features) {
      const ft = isObj(f) ? (typeof f.type === "string" ? f.type : "") : String(f);
      if (ft && !TILE_FEATURES.has(ft)) view.warnings.push(`${loc}: tile feature '${ft}' is not rendered natively yet`);
    }
  }

  if (CONTAINERS.has(type)) {
    let children: unknown[] = Array.isArray(card.cards) ? card.cards : [];
    if (children.length === 0 && isObj(card.card)) children = [card.card];
    if (children.length > 0) {
      children.forEach((child, i) => walkCard(child, `${loc}.${type}[${i}]`, view));
      return;
    }
  }

  if (NATIVE_CORE.has(type)) view.tier1++;
  else if (NATIVE_HACS.has(type)) view.tier2++;
  else {
    view.tier3++;
    view.triggers.push({ view: view.name, path: loc, type: type || "<missing>" });
  }
}

/** Finds the `{ views: [...] }` object in a pasted Lovelace config or a lovelace/config response. */
function unwrap(data: unknown): unknown {
  let d = data;
  for (let i = 0; i < 3; i++) {
    if (!isObj(d) || Array.isArray(d.views)) break;
    const inner = isObj(d.result) ? d.result : isObj(d.config) ? d.config : isObj(d.data) && isObj(d.data.config) ? d.data.config : null;
    if (!inner) break;
    d = inner;
  }
  return d;
}

export function auditDashboard(input: string): VitroAuditResult {
  let data: unknown;
  try {
    data = unwrap(parseDashboardText(input));
  } catch (e) {
    return fail(e instanceof Error ? e.message : "Couldn't read that as JSON or YAML.");
  }
  if (!isObj(data) || !Array.isArray(data.views)) {
    return fail(
      isObj(data) && isObj(data.strategy)
        ? "This dashboard is auto-generated, so it has no views to check. Take control of it in Home Assistant first, then paste the raw configuration."
        : "Expected a Lovelace dashboard with a top-level `views` list.",
    );
  }

  const views: ViewAudit[] = [];
  data.views.forEach((v, vi) => {
    if (!isObj(v)) return;
    const name = String(v.path ?? v.title ?? `view_${vi}`);
    const view: ViewAudit = { name, type: typeof v.type === "string" ? v.type : "masonry", tier1: 0, tier2: 0, tier3: 0, native: true, triggers: [], warnings: [] };
    const sections = Array.isArray(v.sections) ? v.sections : [];
    sections.forEach((sec, si) => {
      if (!isObj(sec) || !Array.isArray(sec.cards)) return;
      sec.cards.forEach((card, ci) => walkCard(card, `${name}/section[${si}]/card[${ci}]`, view));
    });
    if (Array.isArray(v.cards)) v.cards.forEach((card, ci) => walkCard(card, `${name}/card[${ci}]`, view));
    view.native = view.tier3 === 0;
    views.push(view);
  });

  const totals = { tier1: 0, tier2: 0, tier3: 0 };
  for (const v of views) {
    totals.tier1 += v.tier1;
    totals.tier2 += v.tier2;
    totals.tier3 += v.tier3;
  }
  return {
    ok: true,
    isFullyNative: totals.tier3 === 0,
    totals,
    views,
    triggers: views.flatMap((v) => v.triggers),
    warnings: views.flatMap((v) => v.warnings),
  };
}

/** A short dashboard to try the checker with: one custom card (the last) is on purpose. */
export const SAMPLE_DASHBOARD = `# Paste your own from Settings > Dashboards > ⋮ > Raw configuration editor
title: Home
views:
  - title: Living room
    path: living-room
    type: sections
    sections:
      - cards:
          - type: heading
            heading: Lights
          - type: grid
            columns: 2
            cards:
              - type: tile
                entity: light.lamp # Tier 1
                features:
                  - type: light-brightness
                  - type: light-effect-list
              - type: custom:mushroom-light-card # Tier 2
                entity: light.shelf
          - type: conditional
            conditions:
              - condition: state
                entity: input_boolean.guests
                state: "on"
            card:
              type: markdown
              content: |
                Guests are here.
                Quiet mode is on.
  - title: Wall panel
    path: wall
    panel: true
    cards:
      - type: custom:button-card # Tier 3: boots GeckoView
        entity: light.lamp
        card_mod:
          style: |
            ha-card::before { content: ""; }
`;
