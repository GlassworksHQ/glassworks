/**
 * The front matter the repo's Markdown uses: `key: value` lines between `---` lines at the top, values
 * plain, quoted, or true/false. Posts need it; a research note has `topic` and `evidence` in it. A file
 * without it gives no data and its whole text as the body. A line it can't read fails the build, naming
 * the file.
 */
export function frontMatter(file: string, src: string): { data: Record<string, string | boolean>; body: string } {
  // CRLF too: a Windows checkout with core.autocrlf hands the build CRLF files
  const m = src.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n/);
  if (!m) return { data: {}, body: src };
  const data: Record<string, string | boolean> = {};
  for (const line of m[1].split(/\r?\n/)) {
    if (!line.trim()) continue;
    const kv = line.match(/^(\w+):\s*(.*)$/);
    if (!kv) throw new Error(`${file}: can't read front matter line "${line}"`);
    const v = kv[2].trim();
    data[kv[1]] = v === "true" ? true : v === "false" ? false : v.replace(/^"([\s\S]*)"$/, "$1").replace(/\\"/g, '"');
  }
  return { data, body: src.slice(m[0].length) };
}
