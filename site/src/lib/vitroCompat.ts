// Vitro dashboard compatibility check, 100% client-side. Nothing here touches the network: the
// caller hands in the dashboard text and gets a verdict back. It routes each card the way the Vitro
// engine does (compat/CardCompat.kt and CompatReport.kt), with every card's script and card-mod
// assumed installed, as the engine's own offline checker assumes. The lists come from
// public/vitro-compat.json (also served at /vitro-compat.json), which a test keeps equal to
// VitroSupport.kt in the engine and to the audit script's lists.
import manifest from "../../public/vitro-compat.json";
import { buttonCardReasons } from "./vitroButtonCard";
import { stripCssComments } from "./vitroCss";
import { type NativeOptionsSchema, cardOptionReasons, viewOptionReasons } from "./vitroNativeOptions";

export { stripCssComments };

export const VITRO_COMPAT = manifest;

const CORE = new Set<string>(manifest.nativeCoreTypes);
const HACS = new Set<string>(manifest.nativeHacsTypes);
const CONTAINERS = new Set<string>(manifest.containerTypes);
/** Containers whose children are their `cards:` list (LovelaceParser.CARDS_FIELD_TYPES): all but `conditional`. */
const CARDS_FIELD = new Set<string>(manifest.containerTypes.filter((t) => t !== "conditional"));
const STACK_TYPES = new Set<string>(manifest.stackTypes);
/** What each native card draws exactly as HA does: the engine's native-options.json (vitroNativeOptions.ts). */
const NATIVE_OPTIONS = manifest.nativeOptions as unknown as NativeOptionsSchema;
const SWIPE_PARAMETERS = new Set<string>(manifest.swipeCardParameters);
/** The dashboard's `button_card_templates`, which button-card merges before it draws (set by auditDashboard). */
let buttonCardTemplates: Record<string, unknown> = {};
const CARD_MOD_SHEETS: Record<string, string[]> = manifest.cardModSheets;
const CARD_MOD_OTHER_KEYS: Record<string, string> = manifest.cardModOtherKeys;
const CARD_MOD_NOT_APPLIED: Record<string, string> = manifest.cardModNotApplied;
const FONT_SIZE_TYPES = new Set<string>(manifest.fontSizeTypes);
const CARD_PROPERTIES = new Set<string>(manifest.cardProperties);
const CARD_FONT_PROPERTIES = new Set<string>(manifest.cardFontProperties);
const STACK_PROPERTIES = new Set<string>(manifest.stackProperties);
const CARD_VARIABLES = new Set<string>(manifest.cardVariables);
const CASCADE_PROPERTIES = new Set<string>(manifest.cascadeProperties);
const MARKDOWN_DISPLAY = new Set<string>(manifest.markdownDisplay);
const PSEUDO_CLASSES = new Set<string>(manifest.pseudoClasses);
const BENIGN_PSEUDO_CLASSES: Record<string, string> = manifest.benignPseudoClasses;
const BENIGN_PSEUDO_ELEMENTS: Record<string, string> = manifest.benignPseudoElements;
const BENIGN_PROPERTIES: Record<string, string> = manifest.benignProperties;
const BENIGN_VALUES: Record<string, string[]> = manifest.benignValues;
const KNOWN_GAPS: Record<string, string> = manifest.knownGaps;
const UNDRAWN_MARKDOWN_TAGS: Record<string, string> = manifest.undrawnMarkdownTags;

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
  // An alias inside [ ] or { } (a value, a map key, or either side of an implicit pair) would read as the text
  // "*name": refuse it instead.
  if (/(^|:\s+)\*[^\s,\]}]+(\s|$)/.test(raw.trim())) {
    throw new YamlError(`alias ${raw.trim()} inside a flow collection isn't supported; use a block list`, line);
  }
  // `[type: custom:button-card]` is a list holding a one-pair map in YAML, not the text "type: …": read as a string
  // it would hide the card from the audit (a PASS for a custom card), so refuse it.
  if (!isKey && /:(\s|$)/.test(raw.trim())) {
    throw new YamlError(`a key: value pair inside [ ] (${raw.trim().split(":")[0]}) isn't supported; write it as {key: value} or a block list`, line);
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
// The audit: the engine's routing (CardCompat.kt, CompatReport.kt), card by card
// ---------------------------------------------------------------------------------------------

export interface TriggerItem {
  view: string;
  path: string;
  type: string;
  /** Why the card goes to an island, as the engine's checker words it. */
  reasons: string[];
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
const has = <T>(o: Record<string, T>, k: string): k is string => Object.prototype.hasOwnProperty.call(o, k);

function fail(error: string): VitroAuditResult {
  return { ok: false, error, isFullyNative: false, totals: { tier1: 0, tier2: 0, tier3: 0 }, views: [], triggers: [], warnings: [] };
}

/** org.json's `optString`: the value as text, or `fallback` when it's missing or null. */
function optString(o: Obj, key: string, fallback: string): string {
  const v = o[key];
  if (v === undefined || v === null) return fallback;
  return typeof v === "string" ? v : typeof v === "object" ? JSON.stringify(v) : String(v);
}

/** The objects in a list, as the engine's `objects()` reads it: anything else in it is skipped. */
const objects = (v: unknown): Obj[] => (Array.isArray(v) ? v.filter(isObj) : []);

/** A card's type as the engine parses it: `entities` when it has none. */
const cardType = (card: Obj): string => optString(card, "type", "entities") || "entities";

/** A reason or a note for the page: the engine's wording, without its issue numbers. */
const plain = (s: string) => s.replace(/;\s*#\d+$/, "");

/** A known-gap note: what it's about, then why it stays native. */
const gap = (key: string, subject: string) => `${subject}: known gap, ${plain(has(KNOWN_GAPS, key) ? KNOWN_GAPS[key] : key)}`;

// ---- The cards a card builds (CardRouter.builtChildren, producedCards) ----

const LAYOUT_BREAK = "custom:layout-break";

/** The children a container builds, with their index in its `cards:` (a conditional's is its `card:`). */
function builtChildren(card: Obj, type = cardType(card)): [number, Obj][] {
  let all: [number, Obj][] = [];
  if (CARDS_FIELD.has(type)) all = (Array.isArray(card.cards) ? card.cards : []).filter(isObj).map((c, i) => [i, c] as [number, Obj]);
  else if (type === "conditional" && isObj(card.card)) all = [[0, card.card]];
  if (type !== "custom:layout-card") return all;
  const layout = optString(card, "layout_type", "masonry-layout").replace(/^custom:/, "");
  return layout === "vertical-layout" || layout === "vertical" ? all.filter(([, c]) => cardType(c) !== LAYOUT_BREAK) : all;
}

/** The cards auto-entities builds per entity, one per `entities:` entry and include rule (no entity filled in). */
function generatedCards(card: Obj): Obj[] {
  const out: Obj[] = [];
  if (Array.isArray(card.entities)) {
    for (const e of card.entities) {
      if (typeof e === "string") out.push({});
      else if (isObj(e)) {
        const copy: Obj = { ...e };
        delete copy.entity;
        out.push(copy);
      }
    }
  }
  const include = isObj(card.filter) && Array.isArray(card.filter.include) ? card.filter.include : null;
  if (include) for (const r of include) out.push(isObj(r) && isObj(r.options) ? { ...r.options } : {});
  for (const c of out) if (!("type" in c)) c.type = "tile";
  return out;
}

const generatesCards = (card: Obj, type: string) => type === "custom:auto-entities" && optString(card, "card_param", "entities") === "cards";

/** Every card a card's config produces one level down: a container's children, and the cards auto-entities builds. */
function producedCards(card: Obj, perEntity: boolean): [number, Obj][] {
  const type = cardType(card);
  const out = builtChildren(card, type);
  if (type !== "custom:auto-entities") return out;
  // Only top-level fields change, so a shallow copy keeps the config as written.
  const inner: Obj = isObj(card.card) ? { ...card.card } : {};
  if (!("type" in inner)) inner.type = "entities";
  const param = optString(card, "card_param", "entities") || "entities";
  // auto-entities replaces this field with what it matched, so what the config wrote there never shows.
  delete inner[param];
  // Its explicit `entities:` entries are always among them (the ones a filter adds depend on live states), so the strict
  // rule checks them as the inner card's own, as the engine's WebFallback does. Under `cards`, each is a card.
  if (param !== "cards" && Array.isArray(card.entities)) inner[param] = card.entities;
  const generated = param === "cards" ? generatedCards(card) : [];
  // Vitro writes this field, so the strict rule doesn't judge it as the user's option (the engine's WebFallback drops it
  // too): it's there for the walk, but not among the card's keys.
  if (param === "cards") Object.defineProperty(inner, "cards", { value: generated.map((g) => ({ ...g })), enumerable: false });
  out.push([out.length, inner]);
  if (perEntity) for (const g of generated) out.push([out.length, g]);
  return out;
}

/** True when a card is built in or produces one at any depth: the island can only draw those as summaries. */
function needsHaFrontend(card: Obj): boolean {
  const type = cardType(card);
  return !type.startsWith("custom:") || type === "custom:stack-in-card" || producedCards(card, true).some(([, c]) => needsHaFrontend(c));
}

// ---- One card's verdict (CardCompat.check, with every script and card-mod assumed installed) ----

interface Verdict {
  native: boolean;
  /** True when the card's view goes to Home Assistant's frontend (WebFallback), not the card to an island. */
  frontend?: boolean;
  reasons: string[];
  notes: string[];
}

/** A card the island can't draw (CardCompat.staysNative): built in, or a custom card Vitro draws with one inside. */
const staysNative = (card: Obj, type: string) => CORE.has(type) || (HACS.has(type) && needsHaFrontend(card));

function verdict(card: Obj): Verdict {
  const type = cardType(card);
  const reasons = new Set<string>();
  const notes = new Set<string>();
  // A built-in card Vitro has no renderer for (`map`, the energy cards): the island can't draw it, so its view opens in
  // HA's frontend (WebFallback). A custom card goes to its island.
  if (!type.startsWith("custom:") && !CORE.has(type)) return { native: false, frontend: true, reasons: [`the built-in \`${type}\` card`], notes: [] };
  // A shortcut whose default label or icon only HA's frontend knows, and an iframe of HA's own pages (WebFallback).
  const own = type === "shortcut" ? shortcutReason(card) : type === "iframe" && sameOriginIframe(optString(card, "url", "")) ? "an `iframe` card of a Home Assistant page" : null;
  if (own) return { native: false, frontend: true, reasons: [own], notes: [] };
  if (!CORE.has(type) && !HACS.has(type)) {
    reasons.add(`\`${type}\` is a custom card with no native renderer`);
    return { native: false, reasons: [...reasons], notes: [...notes] };
  }
  // The strict rule (NativeOptions): an option or value the native card doesn't draw as HA does. A card the island
  // can't draw sends its view to HA's frontend for it (WebFallback); any other card goes to its island.
  const options = cardOptionReasons(NATIVE_OPTIONS, type, card);
  const frontendOnly = staysNative(card, type);
  if (frontendOnly) {
    const frontend = [...options, ...(type === "markdown" ? markdownReasons(optString(card, "content", "")) : [])];
    if (frontend.length > 0) return { native: false, frontend: true, reasons: frontend, notes: [] };
  } else {
    for (const r of options) reasons.add(r);
  }
  checkOptions(card, type, reasons);
  checkCardMod(card, type, reasons, notes);
  // The island runs a custom card's own code but has no HA frontend, so a built-in card there would be a plain
  // summary. A built-in card stays native for its card_mod, and so does a custom card Vitro draws with a built-in card
  // inside it.
  if (reasons.size > 0 && frontendOnly) {
    const why = CORE.has(type) ? "the island can't draw built-in cards yet" : "the island can't draw the built-in cards inside it yet";
    for (const r of reasons) notes.add(`would fall back for ${r}, but ${why}`);
    return { native: true, reasons: [], notes: [...notes] };
  }
  return { native: reasons.size === 0, reasons: [...reasons], notes: [...notes] };
}

/**
 * Why a `shortcut` card needs HA's frontend (WebFallback.shortcutReason): with no `label` (or no `icon`), HA shows its
 * action's own, which for a dashboard, view or panel path is that page's title and icon, and for an action the
 * service's name. Vitro draws the defaults it knows: none, `url`, and area, device and settings paths. It doesn't open
 * Assist.
 */
function shortcutReason(card: Obj): string | null {
  const assist = (k: string) => isObj(card[k]) && (card[k] as Obj).action === "assist";
  if (assist("tap_action") || assist("hold_action")) return "a `shortcut` card that opens Assist";
  if (optString(card, "label", "") !== "" && optString(card, "icon", "") !== "") return null;
  const tap = isObj(card.tap_action) ? card.tap_action : null;
  const action = tap ? optString(tap, "action", "none") : "none";
  if (action === "perform-action" || action === "call-service") return "a `shortcut` card that shows its action's name or icon";
  if (action === "navigate" && !knownNavigation(optString(tap ?? {}, "navigation_path", ""))) {
    return "a `shortcut` card that shows the page it opens by its title or icon";
  }
  return null;
}

const CONFIG_ROUTES = new Set(["automation", "scene", "script", "tools", "integrations", "devices", "entities"]);

/** ShortcutDefaults.navigation: a path whose default label and icon Vitro knows without HA's frontend. */
function knownNavigation(path: string): boolean {
  if (!path) return true;
  const segments = path.replace(/^\//, "").split(/[/?]/);
  const panel = segments[0] ?? "";
  const sub = segments[1];
  if (panel === "config" && sub === "areas" && segments[2] === "area" && segments[3]) return true;
  if (panel === "config" && sub === "devices" && segments[2] === "device" && segments[3]) return true;
  if (panel === "config" && sub !== undefined && CONFIG_ROUTES.has(sub)) return true;
  return sub !== undefined && sub.startsWith("areas-");
}

/** WebFallback.sameOriginIframe: an iframe `url` HA resolves on its own origin, which a loopback island can't frame. */
function sameOriginIframe(url: string): boolean {
  return !/^[A-Za-z][A-Za-z0-9+.\-]*:\/\//.test(url.trim());
}

function checkOptions(card: Obj, type: string, reasons: Set<string>) {
  switch (type) {
    // button-card without JavaScript templates, cards or HTML, and with styles Vitro draws (vitroButtonCard.ts).
    case "custom:button-card":
      for (const r of buttonCardReasons(card, buttonCardTemplates)) reasons.add(`button-card ${r}`);
      break;
    case "custom:swipe-card": {
      const params = isObj(card.parameters) ? card.parameters : {};
      for (const k of Object.keys(params)) if (!SWIPE_PARAMETERS.has(k)) reasons.add("swipe-card has a Swiper parameter Vitro doesn't run natively");
      if ("pagination" in params) {
        const p = params.pagination;
        const t = isObj(p) && typeof p.type === "string" && p.type ? p.type : "bullets";
        if (t !== "bullets" && t !== "fraction") reasons.add("swipe-card `pagination.type` isn't one Vitro draws natively");
        if (isObj(p) && p.dynamicBullets === true) reasons.add("swipe-card `dynamicBullets` isn't drawn natively");
      }
      if ("slidesPerView" in params) {
        const v = params.slidesPerView;
        if (v !== "auto" && !(typeof v === "number" && v > 0)) reasons.add("swipe-card `slidesPerView` isn't a value Vitro draws natively");
      }
      if ("card_width" in card) reasons.add("swipe-card `card_width` isn't drawn natively");
      break;
    }
    // weather-card's script shows an error without an entity; the island draws that error as HA does.
    case "custom:weather-card":
      if (!optString(card, "entity", "")) reasons.add("weather-card needs an `entity` (HA shows an error)");
      break;
  }
}

const OPEN_TAG = /<([a-zA-Z][a-zA-Z0-9-]*)/g;

/** Tags HA's Markdown sanitizer keeps that Vitro doesn't draw (CardCompat.markdownReasons): they send the view to HA. */
function markdownReasons(content: string): string[] {
  const reasons = new Set<string>();
  for (const m of content.matchAll(OPEN_TAG)) {
    const tag = m[1].toLowerCase();
    if (has(UNDRAWN_MARKDOWN_TAGS, tag)) reasons.add(`Markdown \`<${tag}>\` (${UNDRAWN_MARKDOWN_TAGS[tag]}) isn't drawn natively`);
  }
  return [...reasons];
}

// ---- card_mod ----

/** Which CSS a sheet holds, so selectors and properties are checked against what Vitro draws there. */
type Sheet = "card" | "stack" | "markdownCard" | "markdownContent" | "clockCard" | "clockDigital";

function checkCardMod(card: Obj, type: string, reasons: Set<string>, notes: Set<string>) {
  const cardMod = card.card_mod;
  if (!isObj(cardMod)) return;
  for (const key of Object.keys(cardMod)) {
    if (key === "style") continue;
    if (key === "class") notes.add(gap("card_mod.class", "`card_mod.class`"));
    else if (!has(CARD_MOD_OTHER_KEYS, key)) notes.add(`card_mod \`${key}:\` is ignored`);
  }
  const style = cardMod.style;
  const sheets: [string, string][] = [];
  if (typeof style === "string") sheets.push([".", style]);
  else if (isObj(style)) {
    for (const [k, v] of Object.entries(style)) {
      if (typeof v === "string") sheets.push([k, v]);
      else {
        // A nested selector map walks further into shadow roots than Vitro models.
        reasons.add(`card_mod path \`${redactAttributeValues(k)}\` nests further into the card's shadow DOM, which Vitro doesn't model`);
        sheets.push([k, ""]);
      }
    }
  }
  const allowed = has(CARD_MOD_SHEETS, type) ? CARD_MOD_SHEETS[type] : CARD_MOD_SHEETS["*"];
  const notApplied = has(CARD_MOD_NOT_APPLIED, type) ? CARD_MOD_NOT_APPLIED[type] : null;
  for (const [key, css] of sheets) {
    if (!allowed.includes(key)) {
      reasons.add(`card_mod path \`${redactAttributeValues(key)}\` styles the card's inner DOM, which Vitro doesn't model`);
      continue;
    }
    if (css.trim() === "") continue;
    const sheet: Sheet =
      key === "ha-markdown$"
        ? "markdownContent"
        : key === "hui-clock-card-digital$"
          ? "clockDigital"
          : type === "markdown"
            ? "markdownCard"
            : type === "clock"
              ? "clockCard"
              : STACK_TYPES.has(type)
                ? "stack"
                : "card";
    if (notApplied !== null && key === ".") {
      // Vitro doesn't apply this sheet yet: its properties are one known gap. A selector reaching into the card's
      // DOM still needs the island, as it would on any card.
      checkSheet(type, key, css, sheet, reasons, new Set(), new Set());
      notes.add(`card_mod on \`${type}\`: known gap, ${plain(notApplied)}`);
    } else {
      checkSheet(type, key, css, sheet, reasons, reasons, notes);
    }
  }
}

const TPL = "__vitro_tpl__";
const JINJA_BLOCK = /\{%[\s\S]*?%\}|\{#[\s\S]*?#\}/g;
const JINJA_EXPR = /\{\{[\s\S]*?\}\}/g;
const JINJA_ANY = /\{%[\s\S]*?%\}|\{\{[\s\S]*?\}\}|\{#[\s\S]*?#\}/g;
const HARMLESS_AT_RULES = new Set(["keyframes", "-webkit-keyframes", "charset"]);
const OFFSETS = ["top", "right", "bottom", "left"];
const PLACEMENT = new Set(["position", "width", "transform", ...OFFSETS]);
const UNSET = new Set(["auto", "unset", "initial"]);

/**
 * Which characters of the sheet are quoted text (ThemeResolver.quotedChars): a string, its quotes included, or a
 * backslash escape and the character after it. A brace, `;` or `,` there isn't structure (`--label: "{ ; }"`).
 */
function quotedChars(css: string): boolean[] {
  const quoted = new Array<boolean>(css.length).fill(false);
  let quote = "";
  let i = 0;
  while (i < css.length) {
    const c = css[i];
    if (c === "\\" && i + 1 < css.length) {
      quoted[i] = quoted[i + 1] = true;
      i += 2;
      continue;
    }
    if (quote) {
      quoted[i] = true;
      if (c === quote || c === "\n") quote = "";
    } else if (c === '"' || c === "'") {
      quoted[i] = true;
      quote = c;
    }
    i++;
  }
  return quoted;
}

/** A rule body split into declarations at each `;` outside quoted text and parentheses (ThemeResolver.splitDeclarations). */
function splitDeclarations(body: string): string[] {
  const quoted = quotedChars(body);
  const out: string[] = [];
  let parens = 0;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    if (quoted[i]) continue;
    const c = body[i];
    if (c === "(") parens++;
    else if (c === ")" && parens > 0) parens--;
    else if (c === ";" && parens === 0) {
      out.push(body.slice(start, i));
      start = i + 1;
    }
  }
  out.push(body.slice(start));
  return out;
}

/** Splits `s` at `sep` outside brackets, parentheses and quotes. */
function splitTopLevel(s: string, sep: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let quote = "";
  let b = 0;
  for (let k = 0; k < s.length; k++) {
    const c = s[k];
    if (quote) {
      if (c === quote) quote = "";
    } else if (c === '"' || c === "'") quote = c;
    else if (c === "(" || c === "[") depth++;
    else if (c === ")" || c === "]") depth--;
    else if (c === sep && depth === 0) {
      out.push(s.slice(b, k));
      b = k + 1;
    }
  }
  out.push(s.slice(b));
  return out;
}

type Decl = { name: string; value: string; important: boolean };

/** The sheet's rules as the browser reads them (ThemeResolver.cssRulesImportant): at-rules and bare declarations left out. */
function cssRules(css: string): { selector: string; decls: Decl[] }[] {
  const cleaned = stripCssComments(css).replace(JINJA_ANY, " ");
  const out: { selector: string; decls: Decl[] }[] = [];
  let depth = 0;
  let segStart = 0;
  let bodyStart = 0;
  let selector = "";
  const quoted = quotedChars(cleaned);
  for (let i = 0; i < cleaned.length; i++) {
    if (quoted[i]) continue;
    const c = cleaned[i];
    if (c === "{") {
      if (depth === 0) {
        selector = cleaned.slice(segStart, i).trim();
        bodyStart = i + 1;
      }
      depth++;
    } else if (c === "}" && depth > 0) {
      depth--;
      if (depth === 0) {
        const decls: Decl[] = [];
        for (const d of splitDeclarations(cleaned.slice(bodyStart, i))) {
          const colon = d.indexOf(":");
          if (colon <= 0) continue;
          const name = d.slice(0, colon).trim().toLowerCase();
          const raw = d.slice(colon + 1);
          const value = raw.replace(/\s*!\s*important\s*$/i, "").trim();
          if (name && value) decls.push({ name, value, important: /\s*!\s*important\s*$/i.test(raw) });
        }
        if (selector && !selector.startsWith("@")) out.push({ selector, decls });
        segStart = i + 1;
      }
    } else if (c === ";" && depth === 0) segStart = i + 1;
  }
  return out;
}

/**
 * The names of at-rules at the top level of a sheet (`@media`, `@keyframes`, ...), as CardCompat.topLevelAtRules finds
 * them: an `@` counts only where a rule can start (the start of the sheet, or after a `}` or `;`, whitespace aside) and
 * outside quoted strings and parentheses, so `url(/local/a@home.png)` or `[data-label="@media"]` isn't one.
 */
export function topLevelAtRules(css: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let parens = 0;
  let quote = "";
  let ruleStart = true;
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === "\\") i++;
    else if (quote) {
      if (c === quote || c === "\n") quote = "";
    } else if (c === '"' || c === "'") quote = c;
    else if (c === "(") parens++;
    else if (c === ")" && parens > 0) parens--;
    else if (c === "{") depth++;
    else if (c === "}" && depth > 0) depth--;
    else if (c === "@" && depth === 0 && parens === 0 && ruleStart) {
      const m = css.slice(i).match(/^@([a-zA-Z-]+)/);
      if (m) out.push(m[1].toLowerCase());
    }
    if (!/\s/.test(c)) ruleStart = !quote && (c === "{" || c === "}" || c === ";");
  }
  return out;
}

type Sel =
  | { kind: "honored"; host: boolean }
  | { kind: "benign" }
  | { kind: "unchecked"; selector: string }
  | { kind: "noted"; selector: string; gap: string }
  | { kind: "unsupported"; selector: string; why: string };

const INTERACTION = /:(hover|active|focus|focus-visible|focus-within)\b/;
const PSEUDO_ELEMENT = /::|:(before|after|first-line|first-letter)\b/;
const ATTRIBUTE = /\[[^\]]*\]/g;
const ATTRIBUTE_VALUE = /\[(?:"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|\\[\s\S]|[^\]"'\\])*\]/g;
const PSEUDO = /(::?)([a-zA-Z-]+)/g;
const FUNCTIONAL = /:(not|is|where|matches|-webkit-any|has)\(/g;
const LEGACY_PSEUDO_ELEMENTS = new Set(["before", "after", "first-line", "first-letter"]);

/** True when a selector has a combinator (whitespace, `>`, `+`, `~`) outside brackets, parentheses and quotes. */
function hasCombinator(s: string): boolean {
  let depth = 0;
  let quote = "";
  for (const c of s) {
    if (quote) {
      if (c === quote) quote = "";
    } else if (c === '"' || c === "'") quote = c;
    else if (c === "(" || c === "[") depth++;
    else if (c === ")" || c === "]") {
      if (depth > 0) depth--;
    } else if (depth === 0 && (c === " " || c === ">" || c === "+" || c === "~")) return true;
  }
  return false;
}

/** True when a selector styles the card itself: `ha-card`, `:host`, `:host ha-card`, `ha-card.x` (ThemeResolver.isHostSelector). */
function isHostSelector(selector: string): boolean {
  return splitTopLevel(selector, ",").some((raw) => {
    const s = raw.trim().toLowerCase().replace(/\s+/g, " ");
    return (
      s === "ha-card" ||
      s === ":host" ||
      s === "." ||
      s === ":host ha-card" ||
      s === ":host > ha-card" ||
      (s.startsWith("ha-card") &&
        s.length > 7 &&
        ".[#".includes(s[7]) &&
        !INTERACTION.test(s) &&
        !hasCombinator(s) &&
        !PSEUDO_ELEMENT.test(s.replace(ATTRIBUTE, "[]")))
    );
  });
}

/** A selector with each attribute selector's value replaced by `…`, its name and operator kept (`[src^=…]`). */
function redactAttributeValues(sel: string): string {
  return sel.replace(ATTRIBUTE_VALUE, (m) => {
    const inner = m.slice(1, -1);
    const op = inner.match(/[~|^$*]?=/);
    return op && op.index !== undefined ? `[${inner.slice(0, op.index + op[0].length)}…]` : m;
  });
}

/** Each functional pseudo-class in a selector (`:not(`, `:is(`, ...) with the range of its parentheses. */
function functionalGroups(sel: string): [string, number, number][] {
  return [...sel.matchAll(FUNCTIONAL)].map((m) => {
    const open = m.index! + m[0].length - 1;
    let depth = 0;
    let end = sel.length - 1;
    for (let k = open; k < sel.length; k++) {
      if (sel[k] === "(") depth++;
      else if (sel[k] === ")" && --depth === 0) {
        end = k;
        break;
      }
    }
    return [m[1], open, end];
  });
}

/**
 * True when the interaction state at `at` keeps the selector from matching at rest: it isn't inside a `:not()` or
 * `:has()`, and inside each enclosing `:is()` / `:where()` every alternative has an interaction state too.
 */
function rulesOutRest(sel: string, at: number, groups: [string, number, number][], memo: Map<string, boolean>): boolean {
  for (const [name, from, to] of groups) {
    if (at < from || at > to) continue;
    if (name === "not" || name === "has") return false;
    const alternatives = splitTopLevel(sel.slice(from + 1, to), ",");
    if (alternatives.some((alt) => !requiresInteraction(alt.trim(), memo))) return false;
  }
  return true;
}

function requiresInteraction(sel: string, memo: Map<string, boolean>): boolean {
  const known = memo.get(sel);
  if (known !== undefined) return known;
  const groups = functionalGroups(sel);
  const out = [...sel.matchAll(PSEUDO)].some((m) => m[1] === ":" && has(BENIGN_PSEUDO_CLASSES, m[2]) && rulesOutRest(sel, m.index!, groups, memo));
  memo.set(sel, out);
  return out;
}

/**
 * One selector against what Vitro draws for its sheet. In a Markdown or clock sheet, the engine also checks that
 * the selector matches an element Vitro rebuilds; this checker leaves that to the panel's report, so it reads any
 * other selector there as applied. Those cards are built in, so it only changes a note, never the route.
 */
function classifySelector(sel: string, sheet: Sheet): Sel {
  if (sel.includes(TPL)) return { kind: "unchecked", selector: sel.split(TPL).join("{{ … }}") };
  const norm = sel.toLowerCase().replace(/\s+/g, " ");
  if (sheet === "stack") {
    // A stack has no ha-card of its own: `ha-card …` in its sheet matches nothing in HA either.
    if (norm === ":host") return { kind: "honored", host: true };
    if (norm.startsWith("ha-card") || norm.startsWith(":host ha-card") || norm.startsWith(":host > ha-card")) return { kind: "benign" };
  } else if (isHostSelector(norm)) {
    return { kind: "honored", host: true };
  }
  let benign = false;
  let restLink = false;
  const stripped = norm.replace(ATTRIBUTE_VALUE, "[]");
  const groups = functionalGroups(stripped);
  const memo = new Map<string, boolean>();
  for (const m of stripped.matchAll(PSEUDO)) {
    const name = m[2];
    const element = m[1] === "::" || LEGACY_PSEUDO_ELEMENTS.has(name);
    if (element && has(BENIGN_PSEUDO_ELEMENTS, name)) benign = true;
    else if (element) return { kind: "unsupported", selector: sel, why: `pseudo-element \`::${name}\` isn't drawn natively` };
    else if (name === "link") restLink = true;
    else if (has(BENIGN_PSEUDO_CLASSES, name)) {
      if (rulesOutRest(stripped, m.index!, groups, memo)) benign = true;
    } else if (PSEUDO_CLASSES.has(name)) continue;
    else if (name === "host") return { kind: "unsupported", selector: sel, why: "`:host` with a selector isn't modeled" };
    else return { kind: "unsupported", selector: sel, why: `\`:${name}\` isn't evaluated natively` };
  }
  if (benign) return { kind: "benign" };
  if (restLink && sheet !== "card" && sheet !== "stack") return { kind: "noted", selector: sel, gap: ":link" };
  if (sheet === "card") return { kind: "unsupported", selector: sel, why: "it reaches inside the card, whose DOM Vitro doesn't model" };
  if (sheet === "stack") return { kind: "unsupported", selector: sel, why: "it reaches inside the stack, whose DOM Vitro doesn't model" };
  return { kind: "honored", host: false };
}

function checkSheet(type: string, key: string, rawCss: string, sheet: Sheet, reasons: Set<string>, propertyReasons: Set<string>, notes: Set<string>) {
  // Template blocks go; expressions become a placeholder so the shape around them still parses.
  const css = stripCssComments(rawCss).replace(JINJA_BLOCK, " ").replace(JINJA_EXPR, TPL);
  for (const at of topLevelAtRules(css)) {
    if (!HARMLESS_AT_RULES.has(at)) reasons.add(`card_mod \`@${at}\` rules aren't evaluated natively`);
  }
  const rules = cssRules(css).map(({ selector, decls }) => ({
    decls,
    verdicts: splitTopLevel(selector, ",")
      .map((p) => p.trim())
      .filter((p) => p !== "")
      .map((p) => classifySelector(p, sheet)),
  }));
  // Every rule on the card itself merges into one style, so the position that wins there pins the card for all of
  // them; as in the browser, the last `!important` one wins, else the last one. The offsets settle the same way.
  const hostDecls = rules.filter((r) => r.verdicts.some((v) => v.kind === "honored" && v.host)).flatMap((r) => r.decls);
  const hostPlacement: Record<string, string> = {};
  for (const name of PLACEMENT) {
    const all = hostDecls.filter((d) => d.name === name);
    const win = [...all].reverse().find((d) => d.important) ?? all[all.length - 1];
    if (win) hostPlacement[name] = win.value.trim().toLowerCase();
  }
  const surfaceSheet = sheet === "card" || sheet === "markdownCard" || sheet === "clockCard";
  if (surfaceSheet && (hostPlacement.position === "fixed" || hostPlacement.position === "absolute")) {
    const problem = pinnedGeometryProblem(hostPlacement);
    if (problem) propertyReasons.add(`card_mod \`${key}\` pinned card: ${problem}`);
  }
  const shownKey = redactAttributeValues(key);
  for (const { decls, verdicts } of rules) {
    // card-mod keeps `!important`; the cascade inside Markdown and the clock doesn't honor it yet.
    if (sheet !== "card" && sheet !== "stack" && decls.some((d) => d.important) && verdicts.some((v) => v.kind === "honored")) {
      notes.add(gap("!important", `card_mod \`${key}\` \`!important\``));
    }
    for (const v of verdicts) {
      if (v.kind === "unsupported") reasons.add(`card_mod \`${shownKey}\` selector \`${redactAttributeValues(v.selector)}\`: ${v.why}`);
      else if (v.kind === "unchecked") notes.add(gap("templated selector", `card_mod \`${shownKey}\` \`${redactAttributeValues(v.selector)}\``));
      else if (v.kind === "noted") notes.add(gap(v.gap, `card_mod \`${shownKey}\` \`${redactAttributeValues(v.selector)}\``));
    }
    const honored = verdicts.filter((v): v is { kind: "honored"; host: boolean } => v.kind === "honored");
    if (honored.length === 0) continue;
    const host = honored.some((v) => v.host);
    checkDeclarations(type, key, decls, sheet, host, host ? hostPlacement : {}, propertyReasons, notes);
  }
}

const PINNED_SIZE = /^-?[\d.]+(px|dp|%|r?em)?$/;
const TRANSLATE = /^(translate[xy]?)\((.*)\)$/;
const CENTER_OR_ZERO = new Set(["-50%", "0", "0px", "0%"]);
const BORDER_STYLE_WORDS = new Set(["dashed", "dotted", "double", "groove", "ridge", "inset", "outset"]);
const GLOW = /^0(px)?\s+0(px)?\s/;

/**
 * A value as the report shows it, as the engine's `CardCompat.loggable` writes it: `url(…)`, a quoted string that
 * looks like an address (in `image-set()` or anywhere) as `"…"`, and cut at 80 characters.
 */
function shownValue(value: string): string {
  const safe = value
    .trim()
    .replace(/url\(\s*(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|(?:\\.|[^)\\])*)\s*\)/gi, "url(…)")
    .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, (q) => {
      const inner = q.slice(1, -1).trim();
      return inner.includes("://") || inner.startsWith("/") || inner.includes("?") || inner.includes("=") || /image-set\(/i.test(value) ? '"…"' : q;
    });
  return safe.length <= 80 ? safe : `${safe.slice(0, 80)}…`;
}

/** Checks one rule's declarations against what Vitro draws for its sheet (CardCompat.checkDeclarations). */
function checkDeclarations(
  type: string,
  key: string,
  decls: Decl[],
  sheet: Sheet,
  host: boolean,
  hostPlacement: Record<string, string>,
  reasons: Set<string>,
  notes: Set<string>,
) {
  const cascade = sheet !== "card" && sheet !== "stack";
  const surface = sheet === "card" || (host && (sheet === "markdownCard" || sheet === "clockCard"));
  const byName: Record<string, string> = {};
  for (const d of decls) byName[d.name] = d.value.trim().toLowerCase();
  const placement = host ? hostPlacement : byName;
  const position = placement.position as string | undefined;
  const pinned = position === "fixed" || position === "absolute";
  for (const { name, value: rawValue } of decls) {
    const shown = () => `\`${name}: ${shownValue(rawValue)}\``;
    const value = rawValue.trim().toLowerCase();
    // On the card itself only the placement that wins the cascade counts.
    if (host && PLACEMENT.has(name) && hostPlacement[name] !== undefined && value !== hostPlacement[name]) continue;
    // A templated or var() value can't be checked here: the property alone decides.
    const checkable = !value.includes(TPL) && !value.includes("var(");
    if (name.startsWith("--")) {
      if (!cascade && !CARD_VARIABLES.has(name.slice(2))) notes.add(gap("card variable", `\`${name}\` on \`${type}\``));
      continue;
    }
    const asCard = surface && (CARD_PROPERTIES.has(name) || (CARD_FONT_PROPERTIES.has(name) && FONT_SIZE_TYPES.has(type)));
    const asStack = sheet === "stack" && STACK_PROPERTIES.has(name);
    const asCascade = cascade && (CASCADE_PROPERTIES.has(name) || name === "position" || OFFSETS.includes(name));
    if (!asCard && !asStack && !asCascade) {
      const benignValues = has(BENIGN_VALUES, name) ? BENIGN_VALUES[name] : null;
      if (has(BENIGN_PROPERTIES, name)) continue;
      if (benignValues && benignValues.includes(value)) continue;
      if (has(KNOWN_GAPS, name)) notes.add(gap(name, shown()));
      // Offsets without a position do nothing in the browser either.
      else if (OFFSETS.includes(name) && (position === undefined || position === "static")) continue;
      else reasons.add(`card_mod \`${key}\` ${shown()} isn't supported here`);
      continue;
    }
    if (!checkable) continue;
    const problem = asCard ? checkCardValue(name, value, pinned, position, shown, notes) : asCascade ? checkCascadeValue(sheet, name, value, position, shown, notes) : null;
    if (problem) reasons.add(`card_mod \`${key}\` ${shown()} ${problem}`);
  }
}

/** Why a card-surface value can't be drawn, or null when it can. */
function checkCardValue(name: string, value: string, pinned: boolean, position: string | undefined, shown: () => string, notes: Set<string>): string | null {
  switch (name) {
    case "background":
    case "background-image":
      return backgroundProblem(value, true);
    case "border":
      return value.split(/\s+/).some((w) => BORDER_STYLE_WORDS.has(w)) ? "isn't drawn natively (only solid borders)" : null;
    case "position":
      return ["fixed", "absolute", "static", "relative", "initial", "unset", "inherit"].includes(value) ? null : "isn't drawn natively";
    case "top":
    case "right":
    case "bottom":
    case "left":
      if (pinned) return UNSET.has(value) || PINNED_SIZE.test(value) ? null : "isn't drawn natively (a pinned offset is px, rem or %)";
      if (position !== undefined && position !== "static") notes.add(gap("position: relative", shown()));
      return null;
    case "width":
      if (["auto", "100%", "initial", "unset"].includes(value)) return null;
      if (!pinned) return "only applies to pinned (`position: fixed`) cards";
      if (!PINNED_SIZE.test(value)) return "isn't drawn natively (a pinned width is px, rem or %)";
      // As the engine reads it: a number that doesn't parse counts as positive, and zero or less is refused.
      const n = Number(value.replace(/[a-z%]+$/, ""));
      return (Number.isNaN(n) ? 1 : n) <= 0 ? "isn't drawn natively (a pinned width of zero)" : null;
    case "height":
      return ["auto", "100%", "initial", "unset", "inherit", "fit-content"].includes(value) ? null : "isn't drawn natively (only `auto` and `100%`)";
    case "transform":
      if (value === "none" || (pinned && centeredAxes(value) !== null)) return null;
      return "isn't drawn natively (only centering a pinned card at `left: 50%` / `top: 50%`)";
    case "box-shadow":
      if (value === "none") return null;
      if (value.includes("inset")) return "isn't drawn natively (inset shadow)";
      if (GLOW.test(value)) notes.add(gap("box-shadow: glow", shown()));
      return null;
    default:
      return null;
  }
}

/** Which axes a pinned card's transform centers, or null for a transform the overlay can't apply. */
function centeredAxes(value: string): [boolean, boolean] | null {
  if (value === "none") return [false, false];
  let x = 0;
  let y = 0;
  for (const fn of splitTopLevel(value, " ").filter((f) => f.trim() !== "")) {
    const m = fn.match(TRANSLATE);
    if (!m) return null;
    const args = splitTopLevel(m[2], ",").map((a) => a.trim());
    const maxArgs = m[1] === "translate" ? 2 : 1;
    if (args.length < 1 || args.length > maxArgs || args.some((a) => !CENTER_OR_ZERO.has(a))) return null;
    if (m[1] === "translate") {
      if (args[0] === "-50%") x++;
      if (args[1] === "-50%") y++;
    } else if (m[1] === "translatex" && args[0] === "-50%") x++;
    else if (m[1] === "translatey" && args[0] === "-50%") y++;
  }
  return x > 1 || y > 1 ? null : [x === 1, y === 1];
}

/** Why the overlay can't place a pinned card the way HA does, or null when it can. */
function pinnedGeometryProblem(placement: Record<string, string>): string | null {
  const inset = (n: string) => (placement[n] !== undefined && !UNSET.has(placement[n]) ? placement[n] : null);
  const left = inset("left");
  const right = inset("right");
  const top = inset("top");
  const bottom = inset("bottom");
  const width = placement.width !== undefined && !UNSET.has(placement.width) ? placement.width : null;
  const [cx, cy] = (placement.transform !== undefined ? centeredAxes(placement.transform) : null) ?? [false, false];
  if ((cx && (left !== "50%" || right !== null)) || (cy && (top !== "50%" || bottom !== null))) {
    return "isn't drawn natively (only centering a pinned card at `left: 50%` / `top: 50%`)";
  }
  if (left !== null && right !== null && width === null) return "`left` and `right` without a `width` stretch it between them, which the overlay doesn't draw";
  if (top !== null && bottom !== null) return "`top` and `bottom` stretch it between them, which the overlay doesn't draw";
  return null;
}

/** Why a value in a Markdown or clock sheet can't be drawn, or null when it can. */
function checkCascadeValue(sheet: Sheet, name: string, value: string, position: string | undefined, shown: () => string, notes: Set<string>): string | null {
  switch (name) {
    case "display":
      return sheet === "clockCard" || sheet === "clockDigital" || MARKDOWN_DISPLAY.has(value) ? null : "isn't laid out natively";
    case "background":
    case "background-color":
      return backgroundProblem(value, false);
    case "white-space":
      if (value === "nowrap") notes.add(gap("white-space: nowrap", shown()));
      return null;
    case "position":
      if (value === "static" || value === "initial" || value === "unset") return null;
      if (value === "relative") {
        notes.add(gap("position: relative", shown()));
        return null;
      }
      return "isn't laid out natively";
    case "top":
    case "right":
    case "bottom":
    case "left":
      return position === undefined || position === "static" || position === "relative" ? null : "isn't laid out natively";
    case "overflow":
    case "overflow-x":
    case "overflow-y":
      return ["hidden", "clip", "visible", "initial", "unset"].includes(value) ? null : "isn't drawn natively (no scrolling inside a card)";
    default:
      return null;
  }
}

function backgroundProblem(value: string, allowGradient: boolean): string | null {
  if (value.includes("url(") || value.includes("image-set(")) return "isn't drawn natively (background images)";
  if (value.includes("radial-gradient") || value.includes("conic-gradient") || value.includes("repeating-")) return "isn't drawn natively (only linear gradients)";
  if (!allowGradient && value.includes("gradient")) return "isn't drawn natively (no gradients inside Markdown)";
  if (splitTopLevel(value, ",").length > 1) return "isn't drawn natively (layered backgrounds)";
  return null;
}

// ---- The dashboard (CompatChecker.walk) ----

/**
 * Walks one card the way the engine's checker does: an island draws everything inside it, so its cards aren't
 * listed; a native card's children are. A container that builds its children natively isn't counted itself.
 * `holdsGenerated`: the card is auto-entities' inner card under `card_param: cards`, whose first `holdsGenerated`
 * cards are the explicit `entities:` entries (the ones its filter adds depend on live states).
 */
function walkCard(card: Obj, loc: string, view: ViewAudit, holdsGenerated = -1) {
  const type = cardType(card);
  if (!("type" in card) || card.type === null || card.type === "") view.warnings.push(`${loc}: no \`type\`, so Vitro draws it as an entities card`);
  const v = verdict(card);
  for (const note of v.notes) view.warnings.push(`${loc}: ${note}`);
  if (!v.native) {
    view.tier3++;
    const reasons = v.frontend ? v.reasons.map((r) => `${FRONTEND} ${r}`) : v.reasons;
    view.triggers.push({ view: view.name, path: loc, type, reasons });
    return;
  }
  if (type === "iframe") {
    // The view stays native, but the frame's own page runs in a GeckoView island: it isn't browser-free.
    view.tier3++;
    view.triggers.push({ view: view.name, path: loc, type, reasons: ["its page runs in a GeckoView island (the view stays native)"] });
    return;
  }
  if (holdsGenerated >= 0 && CARDS_FIELD.has(type)) {
    for (const [i, child] of producedCards(card, false)) if (i < holdsGenerated) walkCard(child, `${loc}.entities[${i}]`, view);
    return;
  }
  const children = producedCards(card, false);
  if (!(CONTAINERS.has(type) && children.length > 0)) {
    if (CORE.has(type)) view.tier1++;
    else view.tier2++;
  }
  const explicit = generatesCards(card, type) ? (Array.isArray(card.entities) ? card.entities.length : 0) : -1;
  if (type === "custom:auto-entities") {
    // The cards a filter rule's `options` build aren't listed (they depend on live states), but the engine routes them,
    // so one Vitro can't draw sends the view to HA's frontend or itself to an island, as listed cards do.
    // The explicit entries come first, and the inner card's walk below lists them.
    const entries = Array.isArray(card.entities) ? card.entities.filter((e) => typeof e === "string" || isObj(e)).length : 0;
    // Every generated card at any depth: one that needs HA's frontend decides, whatever comes before it.
    const off: [Obj, Verdict][] = [];
    const scan = (c: Obj) => {
      const v = verdict(c);
      if (!v.native) off.push([c, v]);
      // An iframe's page runs in a GeckoView island, as a listed one's does.
      else if (cardType(c) === "iframe") off.push([c, { native: false, reasons: ["its page runs in a GeckoView island (the view stays native)"], notes: [] }]);
      else for (const [, inner] of producedCards(c, true)) scan(inner);
    };
    for (const [, g] of producedCards(card, true).slice(children.length + (explicit >= 0 ? entries : 0))) scan(g);
    const first = off.find(([, v]) => v.frontend) ?? off[0];
    if (first) {
      const [g, gv] = first;
      view.tier3++;
      view.triggers.push({ view: view.name, path: `${loc}.generated`, type: cardType(g), reasons: gv.frontend ? gv.reasons.map((r) => `${FRONTEND} ${r}`) : gv.reasons });
      // The whole view goes to HA's frontend. An island for a generated card leaves the inner card and its explicit
      // entries to check below, since one of them may still send the view there.
      if (gv.frontend) return;
    }
    // Its rows are picked at run time, so no config check can claim them: the engine checks what it matched (WebFallback).
    view.warnings.push(`${loc}: ${MATCHED_ROWS_NOTE}`);
  }
  for (const [i, child] of children) {
    const where = type === "custom:auto-entities" ? `${loc}.card` : `${loc}.${type}[${i}]`;
    walkCard(child, where, view, explicit);
  }
}

/** The engine's WebFallback.MATCHED_ROWS_NOTE: what a native auto-entities card is native on. */
export const MATCHED_ROWS_NOTE =
  "auto-entities picks its rows at run time: the view is native if the rows it matches are, and a row Vitro doesn't draw natively opens the view in Home Assistant's frontend";

/** How a reason that sends the whole view to Home Assistant's frontend (WebFallback) starts. */
const FRONTEND = "Home Assistant's frontend draws this view:";

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

/** The view's type as the engine infers it: its `type`, else `panel` with `panel: true`, `sections` with sections. */
function viewType(v: Obj, sections: Obj[]): string {
  const explicit = optString(v, "type", "");
  if (explicit) return explicit;
  // `panel` as org.json's optBoolean reads it: true, or the text "true" in any case.
  if (v.panel === true || (typeof v.panel === "string" && v.panel.toLowerCase() === "true")) return "panel";
  return sections.length > 0 ? "sections" : "masonry";
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

  buttonCardTemplates = isObj(data.button_card_templates) ? data.button_card_templates : {};
  const views: ViewAudit[] = [];
  data.views.forEach((v, vi) => {
    if (!isObj(v)) return;
    const name = String(v.path ?? v.title ?? `view_${vi}`);
    const sections = objects(v.sections);
    const type = viewType(v, sections);
    const view: ViewAudit = { name, type, tier1: 0, tier2: 0, tier3: 0, native: true, triggers: [], warnings: [] };
    // The view's own config (its header, sections, badges): what Vitro doesn't draw as HA does sends it to HA.
    const own = viewOptionReasons(NATIVE_OPTIONS, v);
    if (own.length > 0) {
      view.tier3++;
      view.triggers.push({ view: name, path: name, type: "view", reasons: own.map((r) => `${FRONTEND} ${r}`) });
    }
    const each = (list: unknown, loc: (i: number) => string) => {
      (Array.isArray(list) ? list : []).forEach((card, i) => {
        if (isObj(card)) walkCard(card, loc(i), view);
        else view.warnings.push(`${loc(i)}: not a card (expected a map with a type), skipped`);
      });
    };
    // The cards the engine builds for a view: a panel view's first card, a sections view's section cards, or else the
    // view's own cards.
    const cards = Array.isArray(v.cards) ? v.cards : [];
    const cardObjects = cards.filter(isObj);
    if (type === "panel" && cardObjects.length > 0) {
      // The engine parses only the objects in `cards:`, so a panel view's card is the first of those.
      const first = cards.indexOf(cardObjects[0]);
      walkCard(cardObjects[0], `${name}/card[${first}]`, view);
      if (cardObjects.length > 1) {
        view.warnings.push(`${name}: a panel view shows only its first card, so the other ${cardObjects.length - 1} aren't checked`);
      }
    } else if (type === "sections" && sections.length > 0) {
      sections.forEach((sec, si) => each(sec.cards, (ci) => `${name}/section[${si}]/card[${ci}]`));
    } else {
      each(cards, (ci) => `${name}/card[${ci}]`);
    }
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
