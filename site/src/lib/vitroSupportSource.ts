// Reads the Vitro engine's supported set straight from its Kotlin source, for the tests that keep
// public/vitro-compat.json (and with it the site's checker) equal to what the engine routes. Test-only:
// it reads files from the repository, so nothing in the site's pages imports it.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const repo = join(import.meta.dirname, "../../..");
const vitroDir = join(repo, "engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro");

/** VitroSupport.kt (the supported set), LovelaceModel.kt (containers) and HacsParityCards.kt (auto-entities' rule keys). */
export const kotlin = ["compat/VitroSupport.kt", "model/LovelaceModel.kt", "cards/HacsParityCards.kt"]
  .map((f) => readFileSync(join(vitroDir, f), "utf8"))
  .join("\n");

const STRING = /"((?:[^"\\]|\\.)*)"/g;
const unescape = (s: string) => s.replace(/\\(.)/g, "$1");
const strings = (body: string) => [...body.matchAll(STRING)].map((m) => unescape(m[1]));

/** The text between `open` and its matching `)`, from the first `val NAME … = open`. */
function body(name: string, open: "setOf(" | "mapOf("): string {
  const start = kotlin.search(new RegExp(`val ${name}(?::[^=]+)? = ${open.replace("(", "\\(")}`));
  if (start < 0) throw new Error(`${name} not found in the Vitro engine's Kotlin`);
  let i = kotlin.indexOf(open, start) + open.length;
  let depth = 1;
  let quote = false;
  let out = "";
  for (; i < kotlin.length; i++) {
    const c = kotlin[i];
    if (quote) {
      if (c === "\\") out += kotlin[i++];
      else if (c === '"') quote = false;
    } else if (c === "/" && kotlin[i + 1] === "/") {
      // a line comment: skip to the end of the line
      while (i < kotlin.length && kotlin[i] !== "\n") i++;
    } else if (c === '"') quote = true;
    else if (c === "(") depth++;
    else if (c === ")" && --depth === 0) break;
    out += kotlin[i];
  }
  // The literal must be the whole initializer: `setOf(…) + other` would leave part of the set unread.
  if (!/^[ \t]*(\/\/[^\n]*)?(\n|$)/.test(kotlin.slice(i + 1))) throw new Error(`${name}: ${open}…) isn't its whole initializer`);
  return out;
}

/** `val NAME = setOf("a", "b")`, in source order. */
export function kotlinSet(name: string): string[] {
  return strings(body(name, "setOf("));
}

/** Splits a `mapOf(…)` body into its top-level `key to value` entries. */
function entries(text: string): [string, string][] {
  const out: [string, string][] = [];
  let depth = 0;
  let quote = false;
  let from = 0;
  const push = (to: number) => {
    const e = text.slice(from, to).trim();
    if (e) {
      const m = e.match(/^"((?:[^"\\]|\\.)*)"\s+to\s+([\s\S]*)$/);
      if (!m) throw new Error(`can't read map entry: ${e.slice(0, 60)}`);
      out.push([unescape(m[1]), m[2].trim()]);
    }
  };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === "\\") i++;
      else if (c === '"') quote = false;
    } else if (c === '"') quote = true;
    else if (c === "(") depth++;
    else if (c === ")") depth--;
    else if (c === "," && depth === 0) {
      push(i);
      from = i + 1;
    }
  }
  push(text.length);
  return out;
}

/** `val NAME = mapOf("k" to "v" + "more")`: each value's string literals joined, as Kotlin concatenates them. */
export function kotlinStringMap(name: string): Record<string, string> {
  return Object.fromEntries(entries(body(name, "mapOf(")).map(([k, v]) => [k, strings(v).join("")]));
}

/** `val NAME = mapOf("k" to setOf("a", "b"))`. */
export function kotlinSetMap(name: string): Record<string, string[]> {
  return Object.fromEntries(entries(body(name, "mapOf(")).map(([k, v]) => [k, strings(v)]));
}

/** `val NAME = mapOf("card" to mapOf("option" to "what"))`. */
export function kotlinNestedMap(name: string): Record<string, Record<string, string>> {
  return Object.fromEntries(
    entries(body(name, "mapOf(")).map(([k, v]) => {
      const inner = v.match(/^mapOf\(([\s\S]*)\)$/)?.[1];
      if (inner === undefined) throw new Error(`${name}.${k} isn't a mapOf`);
      return [k, Object.fromEntries(entries(inner).map(([ik, iv]) => [ik, strings(iv).join("")]))];
    }),
  );
}

/** `fun cardModSheets(type)`: the sheets per card type, with `"*"` for the `else` branch. */
export function kotlinCardModSheets(): Record<string, string[]> {
  const fn = kotlin.match(/fun cardModSheets\(type: String\): Set<String> = when \(type\) \{([\s\S]*?)\n {4}\}/)?.[1];
  if (!fn) throw new Error("cardModSheets not found in VitroSupport.kt");
  const branches = [...fn.matchAll(/^\s*("[^"]+"|else) -> setOf\(([^)]*)\)\s*$/gm)];
  // Every branch must be one this reads: a new form (several types, a block) fails the test instead of going missing.
  const arrows = fn.match(/->/g)?.length ?? 0;
  if (branches.length !== arrows) throw new Error(`cardModSheets has ${arrows} branches, ${branches.length} of them readable: update kotlinCardModSheets`);
  return Object.fromEntries(branches.map((m) => [m[1] === "else" ? "*" : m[1].slice(1, -1), strings(m[2])]));
}
