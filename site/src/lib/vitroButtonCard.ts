// custom:button-card in the Vitro compatibility check: the same routing as the engine's ButtonCardCompat.kt, from
// the lists in public/vitro-compat.json. A config with JavaScript templates (`[[[ … ]]]`), cards or HTML in it, or a
// style Vitro doesn't draw goes to a web island; the rest is drawn natively. Dashboard templates
// (`button_card_templates`) are merged first, as button-card merges them.
import manifest from "../../public/vitro-compat.json";
import { stripCssComments } from "./vitroCss";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const BC = manifest.buttonCard;
const STYLE_PROPS: Record<string, string[]> = BC.styleProperties;
const BENIGN_VALUES: Record<string, string[]> = BC.benignValues;

const truthy = (v: unknown) => !!v && v !== "0";

/** button-card's `mergeDeep`: lists concatenate (one level flattened), objects merge, anything else replaces. */
export function mergeDeep(prev: Obj, obj: Obj): Obj {
  const out: Obj = { ...prev };
  for (const [k, o] of Object.entries(obj)) {
    const p = out[k];
    if (Array.isArray(p) && Array.isArray(o)) out[k] = p.concat(...o);
    else if (isObj(p) && isObj(o)) out[k] = mergeDeep(p, o);
    else out[k] = o;
  }
  return out;
}

/** button-card's `mergeStatesById`. */
export function mergeStatesById(into: unknown, from: unknown): unknown[] {
  const a = Array.isArray(into) ? into : undefined;
  const b = Array.isArray(from) ? from : undefined;
  const id = (s: unknown) => (isObj(s) && truthy(s.id) ? s.id : undefined);
  const out: unknown[] = [];
  for (const s of a ?? []) {
    let local = s;
    for (const f of b ?? []) if (id(f) !== undefined && id(s) !== undefined && id(f) == id(s) && isObj(local) && isObj(f)) local = mergeDeep(local, f);
    out.push(local);
  }
  for (const f of b ?? []) if (!a || !a.some((s) => id(s) !== undefined && id(f) !== undefined && id(s) == id(f))) out.push(f);
  return out;
}

/** [card] with its `template:` merged in, or null when a template is missing (or they loop). */
export function expandTemplates(card: Obj, templates: Obj, depth = 0): Obj | null {
  if (depth > 32) return null;
  const tpl = card.template;
  if (!tpl) return { ...card };
  let result: Obj = {};
  let states: unknown[] | undefined;
  for (const name of Array.isArray(tpl) ? tpl : [tpl]) {
    if (typeof name !== "string" || !isObj(templates[name])) return null;
    const res = expandTemplates(templates[name] as Obj, templates, depth + 1);
    if (!res) return null;
    result = mergeDeep(result, res);
    states = mergeStatesById(states, res.state);
  }
  result = mergeDeep(result, card);
  result.state = mergeStatesById(states, card.state);
  delete result.template;
  return result;
}

const JS = /^(\[{3,})([\s\S]*?)(\]{3,})$/;
export const isJs = (s: string) => {
  const m = s.trim().match(JS);
  return !!m && m[1].length === 3 && m[3].length === 3;
};

function jsPaths(v: unknown, path: string, out: string[]) {
  if (typeof v === "string") {
    if (isJs(v)) out.push(path || "(value)");
  } else if (Array.isArray(v)) v.forEach((x, i) => jsPaths(x, `${path}[${i}]`, out));
  else if (isObj(v)) for (const [k, x] of Object.entries(v)) if (path || k !== "variables") jsPaths(x, path ? `${path}.${k}` : k, out);
}

const TAG = /<[a-zA-Z/!]/;
const ENTITY_REF = /&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);/g;
const NAMED_REFS = new Set(["amp", "lt", "gt", "quot", "apos", "nbsp", "deg"]);
const isHtml = (s: string) => TAG.test(s) || [...s.matchAll(ENTITY_REF)].some((m) => !m[1].startsWith("#") && !NAMED_REFS.has(m[1]));

const LENGTH = /^[+-]?(\d+\.?\d*|\.\d+)(px|%|em|rem|vw|vh|pt)?$/i;
const isLength = (s: string, auto = false) => (auto && s === "auto") || (LENGTH.test(s) && (/[a-z%]$/i.test(s) || parseFloat(s) === 0));
const isBox = (s: string, auto = false) => {
  const p = s.trim().split(/\s+/);
  return p.length >= 1 && p.length <= 4 && p.every((x) => isLength(x, auto));
};
const ALIGN = new Set(["start", "end", "center", "stretch", "normal", "left", "right", "self-start", "self-end", "flex-start", "flex-end", "baseline", "auto"]);

/** A value the browser takes: no `;`, braces or unbalanced brackets outside quotes (styleMap drops the rest). */
function valid(v: string): boolean {
  let depth = 0;
  let quote = "";
  for (const c of v) {
    if (quote) {
      if (c === quote) quote = "";
      continue;
    }
    if (c === '"' || c === "'") quote = c;
    else if (c === "(") depth++;
    else if (c === ")" && --depth < 0) return false;
    else if (c === ";" || c === "{" || c === "}") return false;
  }
  return depth === 0 && !quote;
}

/** Why a declaration can't be drawn natively, or null (ButtonCardCss.problem, by property). */
function problem(part: string, name: string, value: string): string | null {
  const n = name.toLowerCase();
  const v = value.trim().toLowerCase();
  if (n.startsWith("--") || BC.benignProperties.includes(n)) return null;
  if (BENIGN_VALUES[n]?.includes(v)) return null;
  const allowed = STYLE_PROPS[part];
  if (!allowed) return `the \`${part}\` style part`;
  if (!allowed.includes(n)) return `\`${n}\``;
  if (v.includes("var(")) return null;
  const bad = (() => {
    switch (n) {
      case "border":
        return /\b(dashed|dotted|double|groove|ridge|inset|outset)\b/.test(v);
      case "border-style":
        return !["solid", "none", "hidden"].includes(v);
      case "background":
      case "background-image":
        return v.includes("url(") || v.includes("radial-gradient") || v.includes("conic-gradient");
      case "box-shadow":
        return v.includes("inset");
      case "transform":
        return v !== "none" && !/^translate(x|y)?\([^)]*\)$/.test(v);
      case "animation":
        return v !== "none" && !BC.animations.some((a) => v.split(/\s+/).includes(a));
      case "position":
        return !["absolute", "relative", "static"].includes(v);
      case "display":
        return !["none", "block", "flex", "inline-block", "inline-flex", "grid", "inline"].includes(v);
      case "justify-self":
      case "align-self":
      case "justify-items":
      case "align-items":
        return !ALIGN.has(v);
      case "padding":
      case "padding-top":
      case "padding-right":
      case "padding-bottom":
      case "padding-left":
      case "border-radius":
        return !isBox(v);
      case "margin":
      case "margin-top":
      case "margin-right":
      case "margin-bottom":
      case "margin-left":
        return !isBox(v, true);
      case "width":
      case "height":
      case "min-height":
      case "min-width":
      case "max-width":
      case "max-height":
        return !(isLength(v, true) || v === "fit-content" || (n.startsWith("max-") && v === "none"));
      default:
        return false;
    }
  })();
  return bad ? `\`${n}: ${value.trim().slice(0, 40)}\`` : null;
}

function checkList(list: unknown, part: string, where: string, out: string[]) {
  if (list === undefined || list === null) return;
  if (!Array.isArray(list)) {
    out.push(`\`${where}\` isn't a list of styles`);
    return;
  }
  const decls = new Map<string, string>();
  for (const e of list) if (isObj(e)) for (const [k, v] of Object.entries(e)) if (v !== null && v !== undefined) decls.set(k, typeof v === "string" ? v : String(v));
  for (const [k, raw] of decls) {
    // Comments first, quote-aware (ButtonCardCss.toDecl): a quoted `/*` is text, not a comment.
    const v = stripCssComments(raw).trim().replace(/!important$/, "").trim();
    if (!v || !valid(v) || isJs(raw)) continue;
    const p = problem(part, k, v);
    if (p) out.push(`\`${where}\` ${p}`);
  }
}

function checkStyles(styles: unknown, where: string, out: string[]) {
  if (styles === undefined || styles === null) return;
  if (!isObj(styles)) {
    out.push(`\`${where}\` isn't a map of style lists`);
    return;
  }
  for (const [part, list] of Object.entries(styles)) {
    if (!BC.styleParts.includes(part) || part === "tooltip" || part === "spinner") continue;
    if (part === "custom_fields") {
      if (isObj(list)) for (const [f, l] of Object.entries(list)) checkList(l, "custom_field", `${where}.custom_fields.${f}`, out);
      continue;
    }
    checkList(list, part, `${where}.${part}`, out);
  }
}

function checkFields(o: Obj, where: string, out: string[]) {
  if (!isObj(o.custom_fields)) return;
  for (const [k, v] of Object.entries(o.custom_fields)) {
    if (isObj(v)) out.push(v.card ? `\`${where}.${k}\` holds a card` : `\`${where}.${k}\` isn't text`);
    else if (Array.isArray(v)) out.push(`\`${where}.${k}\` isn't text`);
    else if (typeof v === "string" && !isJs(v) && isHtml(v)) out.push(`\`${where}.${k}\` holds HTML`);
  }
}

const aspectRatio = (v: unknown) => {
  if (typeof v === "number") return v > 0;
  if (typeof v !== "string") return false;
  const s = v.trim();
  if (s && !isNaN(Number(s))) return Number(s) > 0;
  const p = s.split("/");
  return p.length === 2 && Number(p[0]) > 0 && Number(p[1]) > 0;
};

/**
 * Why Vitro sends this button-card to an island, empty when it draws it natively. [templates] is the dashboard's
 * `button_card_templates`.
 */
export function buttonCardReasons(card: Obj, templates: Obj): string[] {
  const out: string[] = [];
  const raw = card.template ? expandTemplates(card, templates) : card;
  if (!raw) return [`template \`${String(card.template)}\` isn't in the dashboard's \`button_card_templates\``];
  const js: string[] = [];
  jsPaths(raw, "", js);
  if (js.length) out.push(`JavaScript templates (\`[[[ ]]]\`) in ${js.slice(0, 3).map((p) => `\`${p}\``).join(", ")}`);
  if (isObj(raw.variables) && Object.values(raw.variables).some((v) => isObj(v) && v.force_eval === true)) out.push("variables with `force_eval`");
  for (const key of BC.unsupportedOptions) {
    if (!(key in raw)) continue;
    const v = raw[key];
    const on = key.endsWith("_action") ? (isObj(v) ? !!v.action && v.action !== "none" : typeof v === "string" && !!v) : truthy(v);
    if (on) out.push(`\`${key}:\` isn't drawn natively`);
  }
  // button-card counts a timer down every second, which Vitro doesn't draw: only when no `state_display` replaces it.
  // `show_state` as button-card reads it (plain JavaScript truthiness: the string "0" shows the state), as the engine does.
  if (typeof raw.entity === "string" && raw.entity.split(".")[0] === "timer" && !!raw.show_state && !(typeof raw.state_display === "string" && raw.state_display))
    out.push("`show_state:` on a timer (its countdown) isn't drawn natively");
  if (raw.layout !== undefined && raw.layout !== null && !BC.layouts.includes(String(raw.layout))) out.push(`\`layout: ${String(raw.layout)}\` isn't drawn natively`);
  if (raw.aspect_ratio !== undefined && raw.aspect_ratio !== null && raw.aspect_ratio !== "" && !aspectRatio(raw.aspect_ratio)) out.push("an `aspect_ratio` Vitro doesn't read");
  if (raw.size !== undefined && raw.size !== null && !(typeof raw.size === "string" && isLength(raw.size.trim().toLowerCase(), true))) out.push("a `size` Vitro doesn't read");
  if (typeof raw.entity_picture === "string" && raw.entity_picture.startsWith("media-source://")) out.push("an `entity_picture` from the media browser");
  for (const k of ["name", "label", "state_display"]) if (typeof raw[k] === "string" && !isJs(raw[k] as string) && isHtml(raw[k] as string)) out.push(`\`${k}\` holds HTML`);
  for (const k of ["tap_action", "hold_action", "double_tap_action"]) {
    const a = raw[k];
    if (!isObj(a) || !a.action || a.action === "none") continue;
    if (!BC.actions.includes(String(a.action))) out.push(`\`${k}: ${String(a.action)}\` isn't run natively`);
    for (const f of BC.unsupportedActionFields) if (truthy(a[f])) out.push(`\`${k}.${f}\` isn't run natively`);
    if (isObj(a.confirmation) && "exemptions" in a.confirmation) out.push(`\`${k}.confirmation.exemptions\` isn't run natively`);
  }
  if (isObj(raw.confirmation) && "exemptions" in raw.confirmation) out.push("`confirmation.exemptions` isn't run natively");
  if (isObj(raw.lock) && raw.lock.unlock !== undefined && !["tap", "hold", "double_tap"].includes(String(raw.lock.unlock))) out.push("a `lock.unlock` Vitro doesn't run");
  checkFields(raw, "custom_fields", out);
  checkStyles(raw.styles, "styles", out);
  if (raw.state !== undefined && raw.state !== null) {
    if (!Array.isArray(raw.state)) out.push("`state:` isn't a list");
    else
      raw.state.forEach((s, i) => {
        if (!isObj(s)) return;
        const op = typeof s.operator === "string" ? s.operator : "";
        if (op && !["==", "<=", "<", ">=", ">", "!=", "regex", "template", "default"].includes(op)) out.push(`state operator \`${op}\``);
        for (const k of ["name", "label", "state_display"]) if (typeof s[k] === "string" && !isJs(s[k] as string) && isHtml(s[k] as string)) out.push(`\`state[${i}].${k}\` holds HTML`);
        checkFields(s, `state[${i}].custom_fields`, out);
        checkStyles(s.styles, `state[${i}].styles`, out);
      });
  }
  return out;
}
