import type { Element, ElementContent, Root, RootContent } from "hast";
import { toHtml } from "hast-util-to-html";
import { toString } from "hast-util-to-string";
import rehypeShiki from "@shikijs/rehype";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema, type Options as SanitizeSchema } from "rehype-sanitize";
import rehypeSlug from "rehype-slug";
import rehypeStringify from "rehype-stringify";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";
import { visit, SKIP } from "unist-util-visit";

/*
 * Repo Markdown → the site's HTML, at build time. GitHub's rules throughout: GFM, raw HTML
 * (sanitized), GitHub's heading slugs, and its alerts as callouts. The README header (banner,
 * centred title, tagline, badges) becomes the page header. Links and images go through `resolve`,
 * which the caller owns, so this file knows nothing about URLs.
 */

export type Link = { href: string; kind: "site" | "source" | "external" | "anchor" };
export type Image = { src: string; width?: number; height?: number };
export type Toc = { depth: 2 | 3; id: string; text: string };

export type Resolver = {
  link: (href: string) => Link;
  image: (src: string) => Image;
};

export type Rendered = {
  title: string;
  /** GitHub's id for the title, which the page header keeps so links to it still land */
  titleId: string;
  tagline: string;
  badges: string;
  html: string;
  toc: Toc[];
  /** every id on the page, for checking #anchors that point here */
  ids: Set<string>;
  /** the first paragraph, as plain text */
  lead: string;
};

const schema: SanitizeSchema = {
  ...defaultSchema,
  // keep GitHub's anchors as written, so #links into the page work
  clobberPrefix: "",
  clobber: [],
  tagNames: [...(defaultSchema.tagNames ?? []), "picture", "source"],
  attributes: {
    ...defaultSchema.attributes,
    "*": [...(defaultSchema.attributes?.["*"] ?? []), "align", "id", "name"],
    img: [...(defaultSchema.attributes?.img ?? []), "width", "height", "align"],
    source: ["srcSet", "media"],
    code: [["className", /^language-/]],
  },
};

const el = (tagName: string, properties: Element["properties"], children: ElementContent[] = []): Element => ({
  type: "element",
  tagName,
  properties,
  children,
});
const isEl = (n: RootContent | ElementContent | undefined, tag?: string): n is Element =>
  n?.type === "element" && (!tag || n.tagName === tag);
const blank = (n: RootContent | ElementContent) => n.type === "text" && !n.value.trim();
const classOf = (n: Element) => (Array.isArray(n.properties.className) ? (n.properties.className as string[]) : []);
const addClass = (n: Element, c: string) => (n.properties.className = [...classOf(n), c]);

const hasImg = (n: Element): boolean => n.children.some((c) => isEl(c) && (c.tagName === "img" || c.tagName === "picture" || hasImg(c)));
const isBanner = (n: Element) => {
  let banner = false;
  visit(n, "element", (c: Element) => {
    if (c.tagName === "img" && /banner-/.test(String(c.properties.src ?? ""))) banner = true;
  });
  return banner;
};

/** The README header block: banner, centred h1, tagline, badges, up to the first rule. */
function takeHeader(tree: Root) {
  const head = { title: "", titleId: "", tagline: [] as ElementContent[], badges: [] as ElementContent[] };
  const kids = tree.children;
  const first = kids.findIndex((n) => !blank(n));
  const centred = kids.findIndex((n) => isEl(n, "h1") && n.properties.align === "center");
  if (centred >= 0 && centred <= first + 2) {
    let i = first;
    for (; i < kids.length; i++) {
      const n = kids[i];
      if (blank(n)) continue;
      if (n.type === "element" && n.tagName === "hr") {
        i++;
        break;
      }
      if (!isEl(n) || (n.tagName !== "p" && n.tagName !== "h1" && n.tagName !== "div")) break;
      if (n.tagName === "h1") {
        head.title = toString(n).trim();
        head.titleId = String(n.properties.id ?? "");
      }
      else if (isBanner(n)) continue;
      else if (hasImg(n)) head.badges.push(...n.children);
      else if (n.properties.align === "center") head.tagline.push(...n.children.map((c) => (isEl(c, "br") ? { type: "text" as const, value: " " } : c)));
      else break;
    }
    tree.children = kids.slice(i);
    return head;
  }
  const h1 = kids.findIndex((n) => isEl(n, "h1"));
  if (h1 >= 0) {
    head.title = toString(kids[h1] as Element).trim();
    head.titleId = String((kids[h1] as Element).properties.id ?? "");
    tree.children = [...kids.slice(0, h1), ...kids.slice(h1 + 1)];
  }
  return head;
}

/** A contents list by hand at the top (only #links): the page's own rail replaces it. */
function dropContentsList(tree: Root) {
  const firstH2 = tree.children.findIndex((n) => isEl(n, "h2"));
  const end = firstH2 < 0 ? tree.children.length : firstH2;
  for (let i = 0; i < end; i++) {
    const n = tree.children[i];
    if (!isEl(n, "ul")) continue;
    const items = n.children.filter((c): c is Element => isEl(c, "li"));
    const onlyAnchors = items.every((li) => {
      const a = li.children.find((c): c is Element => isEl(c, "a"));
      return a && String(a.properties.href ?? "").startsWith("#");
    });
    if (items.length >= 3 && onlyAnchors) tree.children.splice(i, 1);
    return;
  }
}

/** > [!NOTE] … → a callout */
function alert(node: Element): boolean {
  const p = node.children.find((c): c is Element => isEl(c, "p"));
  const t = p?.children[0];
  if (!p || t?.type !== "text") return false;
  const m = t.value.match(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/);
  if (!m) return false;
  const kind = m[1];
  t.value = t.value.slice(m[0].length);
  if (isEl(p.children[1], "br")) p.children.splice(1, 1);
  node.tagName = "div";
  node.properties = { className: ["md-callout", `md-${kind.toLowerCase()}`] };
  node.children.unshift(el("p", { className: ["md-ct"] }, [{ type: "text", value: kind[0] + kind.slice(1).toLowerCase() }]));
  return true;
}

/** <picture> with a dark source → two images the site's theme switches between */
function themed(pic: Element, image: Resolver["image"]): Element | undefined {
  const source = pic.children.find((c): c is Element => isEl(c, "source"));
  const img = pic.children.find((c): c is Element => isEl(c, "img"));
  if (!img) return;
  const dark = source && /dark/.test(String(source.properties.media)) ? String(source.properties.srcSet) : undefined;
  const light = image(String(img.properties.src));
  const both = (src: Image, cls: string) => el("img", { ...img.properties, src: src.src, width: img.properties.width ?? src.width, height: img.properties.height ?? src.height, className: [cls] });
  if (!dark) return both(light, "md-img");
  return el("span", { className: ["md-themed"] }, [both(light, "md-on-light"), both(image(dark), "md-on-dark")]);
}

function transform(r: Resolver, out: { toc: Toc[]; ids: Set<string> }) {
  return (tree: Root) => {
    dropContentsList(tree);
    visit(tree, "element", (node: Element, index, parent) => {
      if (node.properties.id) out.ids.add(String(node.properties.id));
      if (node.properties.name) out.ids.add(String(node.properties.name));

      if (node.tagName === "blockquote") alert(node);

      if (node.tagName === "picture" && parent && index !== undefined) {
        const t = themed(node, r.image);
        if (t) parent.children[index] = t;
        return SKIP;
      }
      if (node.tagName === "img") {
        const i = r.image(String(node.properties.src ?? ""));
        node.properties.src = i.src;
        node.properties.width ??= i.width;
        node.properties.height ??= i.height;
        node.properties.loading = "lazy";
      }
      if (node.tagName === "a" && node.properties.href) {
        const l = r.link(String(node.properties.href));
        node.properties.href = l.href;
        if (l.kind === "external") addClass(node, "md-ext");
        if (l.kind === "source") addClass(node, "md-src");
      }
      if (/^h[2-6]$/.test(node.tagName) && node.properties.id) {
        const id = String(node.properties.id);
        const depth = Number(node.tagName[1]);
        if (depth <= 3) out.toc.push({ depth: depth as 2 | 3, id, text: toString(node).trim() });
        node.children.unshift(el("a", { className: ["md-anchor"], href: `#${id}`, ariaHidden: "true", tabIndex: -1, dataPagefindIgnore: "" }, [{ type: "text", value: "#" }]));
      }
      // a paragraph of badges only: one row
      if (node.tagName === "p" && node.children.length && node.children.every((c) => blank(c) || (isEl(c) && (c.tagName === "picture" || c.tagName === "img" || (c.tagName === "a" && hasImg(c)))))) {
        addClass(node, "md-badges");
      }
    });
    // tables scroll inside a frame; wrapped after the links in them are rewritten
    visit(tree, "element", (node: Element, index, parent) => {
      if (node.tagName !== "table" || !parent || index === undefined) return;
      parent.children[index] = el("div", { className: ["md-tbl"] }, [node]);
      return SKIP;
    });
  };
}

/** The anchors GitHub gives a Markdown file: heading slugs and ids written in its HTML. */
export function anchorsOf(src: string): Set<string> {
  const proc = unified().use(remarkParse).use(remarkGfm).use(remarkRehype, { allowDangerousHtml: true }).use(rehypeRaw).use(rehypeSlug);
  const tree = proc.runSync(proc.parse(src)) as Root;
  const ids = new Set<string>();
  visit(tree, "element", (n: Element) => {
    for (const k of ["id", "name"] as const) if (n.properties[k]) ids.add(String(n.properties[k]));
  });
  return ids;
}

/** Publish one H2 section of a file as its own page, its heading the title. */
export function cutSection(src: string, heading: string): string {
  const start = src.search(new RegExp(`^## ${heading.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*$`, "m"));
  if (start < 0) throw new Error(`no "## ${heading}" section`);
  const rest = src.slice(start + 3);
  const end = rest.search(/\n## /);
  return `# ${end > 0 ? rest.slice(0, end) : rest}`;
}

export async function render(src: string, r: Resolver): Promise<Rendered> {
  const out = { toc: [] as Toc[], ids: new Set<string>() };
  let head = { title: "", titleId: "", tagline: [] as ElementContent[], badges: [] as ElementContent[] };
  let lead = "";
  const file = await unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype, { allowDangerousHtml: true })
    .use(rehypeRaw)
    .use(rehypeSanitize, schema)
    .use(rehypeSlug)
    .use(() => (tree: Root) => {
      head = takeHeader(tree);
      const p = tree.children.find((n): n is Element => isEl(n, "p") && !hasImg(n));
      lead = p ? toString(p).replace(/\s+/g, " ").trim() : "";
    })
    .use(transform, r, out)
    .use(rehypeShiki, {
      themes: { light: "github-light", dark: "github-dark" },
      defaultColor: false,
      fallbackLanguage: "text",
      lazy: true,
      transformers: [
        {
          pre(node: Element) {
            if (this.options.lang && this.options.lang !== "text") node.properties["data-lang"] = this.options.lang;
          },
        },
      ],
    })
    .use(rehypeStringify)
    .process(src);
  // the header's badges and tagline get the same image and link rewriting as the body
  const scratch = { toc: [] as Toc[], ids: new Set<string>() };
  const badges: Root = { type: "root", children: head.badges.filter((b) => !blank(b)) as RootContent[] };
  const tagline: Root = { type: "root", children: head.tagline as RootContent[] };
  transform(r, scratch)(badges);
  transform(r, scratch)(tagline);
  if (head.titleId) out.ids.add(head.titleId);
  return {
    title: head.title,
    titleId: head.titleId,
    tagline: toHtml(tagline).trim(),
    badges: toHtml(badges),
    html: String(file),
    toc: out.toc,
    ids: out.ids,
    lead,
  };
}
