// The strict compat rule, as the Vitro engine checks it (compat/NativeOptions.kt): a card Vitro draws natively claims
// a config only when it draws every option and value in it the way Home Assistant does. What each card supports is
// the engine's own declaration (native-options.json, published in vitro-compat.json as `nativeOptions`); this walks a
// config against it with the engine's rules and words its reasons the same way. The format is documented in
// NativeOptions.kt.

type Spec = unknown;
type Obj = Record<string, unknown>;

export interface NativeOptionsSchema {
  defs: Record<string, Spec>;
  common: Record<string, Spec>;
  cards: Record<string, Spec>;
  view: Spec;
  [key: string]: unknown;
}

const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const has = (o: Obj, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** Values that can be named in a reason: a key, a type, an enum value. Never a path, a URL or an entity id. */
const SAFE = /^[A-Za-z0-9_:+-]{1,48}$/;

/** A number written as text, as all three checkers read one (no hex, `NaN`, `Infinity` or `_`). */
const NUMBER = /^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$/;

function shown(value: unknown): string | null {
  if (typeof value === "boolean") return String(value);
  if (typeof value === "number") return String(value).length <= 24 ? String(value) : null;
  if (typeof value === "string" && SAFE.test(value)) return value;
  return null;
}

const join = (path: string, value: string | null) => (value === null ? path : path === "" ? value : `${path}: ${value}`);

/** JSON equality for the scalars a `values` list holds. */
const same = (a: unknown, b: unknown) => a === b;

class Walk {
  readonly reasons = new Set<string>();
  constructor(
    private readonly schema: NativeOptionsSchema,
    private readonly subject: string,
  ) {}

  private fail(path: string, detail: string | null = null, why: string | null = null) {
    const where = path === "" ? "" : ` \`${path}\``;
    this.reasons.add(`${this.subject}${where}${detail !== null ? ` (${detail})` : ""} isn't drawn natively${why !== null ? `: ${why}` : ""}`);
  }

  private resolve(spec: Spec): Spec {
    let s = spec;
    for (let hops = 0; typeof s === "string" && s.startsWith("@"); hops++) {
      const name = s.slice(1);
      if (!has(this.schema.defs, name)) throw new Error(`native-options.json: no def ${name}`);
      if (hops > 16) throw new Error("native-options.json: a def refers to itself");
      s = this.schema.defs[name];
    }
    return s;
  }

  /** Checks `value` (never null: the caller skips those) against `spec` at `path`. */
  check(value: unknown, rawSpec: Spec, path: string, extra: Obj | null) {
    const spec = this.resolve(rawSpec);
    if (spec === "any") return;
    if (spec === "text") {
      if (typeof value === "object" && value !== null) this.fail(path, "an object or a list");
      return;
    }
    if (!isObj(spec)) throw new Error(`native-options.json: unknown spec ${JSON.stringify(spec)} at ${path}`);
    this.checkObject(value, spec, path, extra);
  }

  private checkObject(value: unknown, spec: Obj, path: string, extra: Obj | null) {
    const why = typeof spec.why === "string" && spec.why ? spec.why : null;
    if (has(spec, "gap")) {
      this.fail(path, null, String(spec.gap));
    } else if (has(spec, "values")) {
      if (!(spec.values as unknown[]).some((v) => same(v, value))) this.fail(join(path, shown(value)), null, why);
    } else if (has(spec, "pattern")) {
      if (typeof value !== "string" || !new RegExp(String(spec.pattern)).test(value)) this.fail(path, null, why);
    } else if (has(spec, "range")) {
      const [min, max] = spec.range as (number | null)[];
      const n = typeof value === "number" ? value : typeof value === "string" && NUMBER.test(value) ? Number(value.trim()) : NaN;
      const ok = Number.isFinite(n) && (min === null || n >= min) && (max === null || n <= max);
      // Never the value itself: a number that isn't one may be any text of the dashboard's.
      if (!ok) this.fail(path, "not a number in range", why);
    } else if (has(spec, "entity")) {
      const domains = spec.entity as string[];
      const domain = typeof value === "string" && value.includes(".") ? value.slice(0, value.indexOf(".")).trim() : "";
      const ok = domain !== "" && domains.some((d) => d === "*" || d === domain);
      if (!ok) this.fail(path, domain !== "" && SAFE.test(domain) ? `a \`${domain}\` entity` : "this entity", why);
    } else if (has(spec, "keys")) {
      if (!isObj(value)) return this.fail(path, "not an object", why);
      const keys = spec.keys as Obj;
      for (const k of (spec.required as string[] | undefined) ?? []) {
        if (value[k] === null || value[k] === undefined) this.fail(path === "" ? k : `${path}.${k}`, "missing", why);
      }
      for (const k of Object.keys(value)) {
        const v = value[k];
        if (v === null || v === undefined) continue;
        const sub = has(keys, k) ? keys[k] : extra && has(extra, k) ? extra[k] : undefined;
        const at = path === "" ? k : `${path}.${k}`;
        if (sub === undefined) this.fail(SAFE.test(k) ? at : join(path, "an option"), null, why);
        else this.check(v, sub, at, null);
      }
    } else if (has(spec, "list")) {
      if (Array.isArray(value)) value.forEach((item, i) => item !== null && item !== undefined && this.check(item, spec.list, `${path}[${i}]`, null));
      else this.check(value, spec.list, path, null);
    } else if (has(spec, "anyOf")) {
      const options = spec.anyOf as Spec[];
      const tries = options.map((o) => {
        const w = new Walk(this.schema, this.subject);
        w.check(value, o, path, extra);
        return w;
      });
      if (tries.some((w) => w.reasons.size === 0)) return;
      // The option meant for this shape of value says best what's wrong with it.
      const fit = options.findIndex((o) => this.fits(value, o));
      for (const r of tries[fit >= 0 ? fit : 0].reasons) this.reasons.add(r);
    } else if (has(spec, "by")) {
      if (!isObj(value)) return this.fail(path, "not an object", why);
      const field = String(spec.by);
      const raw = value[field];
      const picked = raw === null || raw === undefined ? (typeof spec.default === "string" ? spec.default : "") : typeof raw === "object" ? JSON.stringify(raw) : String(raw);
      const cases = spec.cases as Obj;
      if (!has(cases, picked)) this.fail(join(path === "" ? field : `${path}.${field}`, shown(picked)), null, why);
      else this.check(value, cases[picked], path, extra);
    } else {
      throw new Error(`native-options.json: unknown spec ${JSON.stringify(spec)} at ${path}`);
    }
  }

  /** Whether `spec` is meant for a value shaped like `value` (an object, a list, or a scalar). */
  private fits(value: unknown, spec: Spec): boolean {
    const s = this.resolve(spec);
    const scalar = typeof value !== "object" || value === null;
    if (s === "any") return true;
    if (s === "text") return scalar;
    if (!isObj(s)) return false;
    if (has(s, "keys") || has(s, "by")) return isObj(value);
    if (has(s, "list")) return Array.isArray(value);
    if (has(s, "anyOf")) return (s.anyOf as Spec[]).some((o) => this.fits(value, o));
    return scalar;
  }
}

/** Why a `type` card's config isn't drawn exactly natively (NativeOptions.cardReasons): empty when it is, or undeclared. */
export function cardOptionReasons(schema: NativeOptionsSchema, type: string, card: Obj): string[] {
  if (!has(schema.cards, type)) return [];
  const w = new Walk(schema, `\`${type}\``);
  w.check(card, schema.cards[type], "", schema.common as Obj);
  return [...w.reasons];
}

/** Why a view's own config (its keys, sections and badges) isn't drawn exactly natively (NativeOptions.viewReasons). */
export function viewOptionReasons(schema: NativeOptionsSchema, view: Obj): string[] {
  const w = new Walk(schema, "the view");
  w.check(view, schema.view, "", null);
  return [...w.reasons];
}
