import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { cacheLife } from "next/cache";
import { imageSize } from "image-size";
import { cutSection, render, type Image, type Link, type Rendered, type Toc } from "./render";
import { REPO, SECTIONS, SOURCES, siteUrl, type Section, type Source } from "./sources";
import { links } from "../links";

export { SECTIONS, type Section, type Source, type Toc };

export type Doc = Source &
  Omit<Rendered, "ids" | "lead"> & {
    /** one line for cards and search: the tagline, else the first paragraph */
    summary: string;
    edit: string;
    /** the last commit's date, when the build has the repo's history */
    updated?: string;
  };

const GH = links.s1;

/*
 * The last commit date of a file. A build without the full history (a shallow clone, or a CLI
 * deploy that uploads files without .git) would give wrong dates, so it gives none. Production is
 * built from a full clone (site/README.md → Deploying).
 */
const history = (() => {
  try {
    return execFileSync("git", ["-C", REPO, "rev-parse", "--is-shallow-repository"], { encoding: "utf8" }).trim() === "false";
  } catch {
    return false;
  }
})();
export const lastChanged = (file: string) => {
  if (!history) return undefined;
  try {
    return execFileSync("git", ["-C", REPO, "log", "-1", "--format=%cs", "--", file], { encoding: "utf8" }).trim() || undefined;
  } catch {
    return undefined;
  }
};

/*
 * The build checks every link and fails on a bad one. A deployed function can render a doc again
 * (a revalidation) with only the Markdown and images in its bundle (next.config.ts), so there a
 * file it can't see is one the build already checked: it links to GitHub instead of failing.
 */
const BUILDING = process.env.NEXT_PHASE === "phase-production-build";

const inRepo = (p: string) => fs.existsSync(path.join(/* turbopackIgnore: true */ REPO, p));
const isDir = (p: string) => inRepo(p) && fs.statSync(path.join(/* turbopackIgnore: true */ REPO, p)).isDirectory();

/** Every image a doc uses is served under this, by its repo path (app/doc-assets). */
export const ASSET_PREFIX = "/doc-assets/";

const short = (t: string, n = 150) => (t.length <= n ? t : `${t.slice(0, t.lastIndexOf(" ", n))}…`);
const plain = (html: string) =>
  html.replace(/<[^>]+>/g, "").replace(/&amp;/g, "&").replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"').replace(/\s+/g, " ").trim();

async function build(): Promise<{ docs: Doc[]; assets: string[] }> {
  const assets = new Set<string>();
  const read = (s: Source) => {
    const src = fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, s.file), "utf8");
    return s.cut ? cutSection(src, s.cut) : src;
  };

  // first pass: the ids on every page, so the second can check #anchors
  const idle = { link: (href: string): Link => ({ href, kind: "site" }), image: (src: string): Image => ({ src }) };
  const ids = new Map<string, Set<string>>();
  for (const s of SOURCES) ids.set(s.url, (await render(read(s), idle)).ids);

  const errors: string[] = [];
  const docs: Doc[] = [];
  for (const s of SOURCES) {
    const dir = path.posix.dirname(s.file);
    const full = SOURCES.find((o) => o.file === s.file && !o.cut);
    const link = (href: string): Link => {
      const site = href.match(/^https?:\/\/(?:www\.)?glassworks\.dev(\/[^#]*)?(#.*)?$/);
      if (site) return { href: (site[1] || "/") + (site[2] ?? ""), kind: "site" };
      if (/^(https?:)?\/\//.test(href)) return { href, kind: "external" };
      if (/^(mailto|tel):/.test(href)) return { href, kind: "site" };
      if (href.startsWith("#")) {
        const id = decodeURIComponent(href.slice(1));
        if (ids.get(s.url)?.has(id)) return { href, kind: "anchor" };
        // a cut section linking to the rest of its file
        if (full && ids.get(full.url)?.has(id)) return { href: `${full.url}${href}`, kind: "site" };
        errors.push(`${s.file}: ${href}: no such anchor on the page`);
        return { href, kind: "anchor" };
      }
      const [p, hash] = href.split("#");
      const target = path.posix.normalize(p.startsWith("/") ? p.slice(1) : path.posix.join(dir, decodeURIComponent(p))).replace(/\/$/, "");
      const url = siteUrl(target);
      const anchor = hash ? `#${hash}` : "";
      if (url && (!hash || ids.get(url)?.has(decodeURIComponent(hash)))) return { href: url + anchor, kind: "site" };
      if (url && ids.has(url)) {
        errors.push(`${s.file}: ${href}: ${target} has no #${hash}`);
        return { href: url, kind: "site" };
      }
      if (target === "." || inRepo(target) || !BUILDING) {
        const kind = target === "." || isDir(target) ? "tree" : "blob";
        return { href: `${GH}/${kind}/main/${target === "." ? "" : target}${anchor}`, kind: "source" };
      }
      errors.push(`${s.file}: ${href}: no such file (${target})`);
      return { href, kind: "source" };
    };
    const image = (src: string): Image => {
      const brand = src.match(/^https?:\/\/(?:www\.)?glassworks\.dev(\/.*)$/);
      if (brand) return { src: brand[1] };
      if (/^(https?:|data:)/.test(src)) return { src };
      const target = path.posix.normalize(src.startsWith("/") ? src.slice(1) : path.posix.join(dir, decodeURIComponent(src)));
      if (!inRepo(target)) {
        errors.push(`${s.file}: image ${src}: no such file (${target})`);
        return { src };
      }
      assets.add(target);
      try {
        const { width, height } = imageSize(fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, target)));
        return { src: ASSET_PREFIX + target, width, height };
      } catch {
        return { src: ASSET_PREFIX + target };
      }
    };
    const r = await render(read(s), { link, image });
    docs.push({
      ...s,
      titleId: r.titleId,
      tagline: r.tagline,
      badges: r.badges,
      html: r.html,
      toc: r.toc,
      title: r.title || s.label || path.posix.basename(s.file, ".md"),
      summary: short(plain(r.tagline) || r.lead),
      edit: `${GH}/edit/main/${s.file}`,
      updated: lastChanged(s.file),
    });
  }
  if (errors.length && BUILDING) throw new Error(`Docs: ${errors.length} link(s) the site can't resolve (fix the Markdown):\n${errors.join("\n")}`);
  return { docs, assets: [...assets] };
}

let memo: ReturnType<typeof build> | undefined;

/*
 * Rendered once per build worker, and cached for the build: the docs change only with a deploy.
 * Throws when a link can't be resolved, which fails the build.
 */
async function rendered() {
  "use cache";
  cacheLife("max");
  // a failed build isn't kept: the next call tries again
  return (memo ??= build().catch((e) => {
    memo = undefined;
    throw e;
  }));
}

/** Every published doc. */
export const getDocs = async () => (await rendered()).docs;

export async function getDoc(url: string) {
  return (await getDocs()).find((d) => d.url === url);
}

/** Every image the docs use, by repo path. */
export const docAssets = async () => (await rendered()).assets;
