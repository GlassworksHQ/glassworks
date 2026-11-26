// CSS text helpers the compat checker (vitroCompat.ts) and the button-card checker (vitroButtonCard.ts) share, as
// the engine's ThemeResolver does.

/**
 * The sheet with each comment replaced by a space, as the browser reads it (ThemeResolver.stripCssComments): a
 * slash-star inside a quoted string doesn't open one, so it can't hide the rules after it. A string ends at its closing
 * quote or at a newline, a backslash and the character after it are copied as they are, and a comment that is never
 * closed runs to the end.
 */
export function stripCssComments(css: string): string {
  if (!css.includes("/*")) return css;
  let out = "";
  let quote = "";
  let i = 0;
  while (i < css.length) {
    const c = css[i];
    if (c === "\\" && i + 1 < css.length) {
      out += c + css[i + 1];
      i += 2;
      continue;
    }
    if (quote) {
      if (c === quote || c === "\n") quote = "";
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === "/" && css[i + 1] === "*") {
      const end = css.indexOf("*/", i + 2);
      out += " ";
      i = end < 0 ? css.length : end + 2;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}
