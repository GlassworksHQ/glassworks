import fs from "node:fs";
import path from "node:path";
import { REPO } from "../docs/sources";
import { frontMatter } from "../frontMatter";

/*
 * The blog: one folder per post in site/blog, named <YYYY-MM-DD>-<slug>, with index.md and any images
 * beside it. The slug is the URL: /blog/<slug>. Front matter is checked when it's read, so a bad
 * post fails the build naming its file.
 *
 * Drafts (`draft: true`) show only in `next dev`, on Vercel preview deploys, or with SITE_DRAFTS=1.
 * A production build and the feed never have them.
 */
export const SHOW_DRAFTS =
  process.env.NODE_ENV === "development" || process.env.VERCEL_ENV === "preview" || process.env.SITE_DRAFTS === "1";

const BLOG = "site/blog";

export type PostSource = {
  /** repo path of index.md */
  file: string;
  url: string;
  slug: string;
  title: string;
  /** YYYY-MM-DD */
  date: string;
  author: string;
  summary: string;
  draft: boolean;
  /** repo path of cover.webp, when the post has one; else its preview card stands in */
  cover?: string;
  coverAlt?: string;
  /** the Markdown after the front matter */
  body: string;
};

function read(dir: string): PostSource {
  const file = `${BLOG}/${dir}/index.md`;
  const bad = (why: string) => {
    throw new Error(`${file}: ${why}`);
  };
  const name = dir.match(/^(\d{4}-\d{2}-\d{2})-([a-z0-9-]+)$/);
  if (!name) bad(`the folder should be named YYYY-MM-DD-slug`);
  const src = fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, file), "utf8");
  if (!/^---\r?\n/.test(src)) bad("no front matter (--- … ---)");
  const { data, body } = frontMatter(file, src);
  const str = (k: string) => {
    const v = data[k];
    if (typeof v !== "string" || !v) bad(`front matter needs ${k}`);
    return v as string;
  };
  const date = str("date");
  if (date !== name![1]) bad(`date ${date} isn't the folder's date ${name![1]}`);
  // a real day: 2026-13-01 or 2026-02-30 would make a feed date no reader accepts
  const day = Date.parse(`${date}T00:00:00Z`);
  if (Number.isNaN(day) || new Date(day).toISOString().slice(0, 10) !== date) bad(`${date} isn't a calendar date`);
  if (data.draft !== undefined && typeof data.draft !== "boolean") bad("draft should be true or false");
  const cover = fs.existsSync(path.join(/* turbopackIgnore: true */ REPO, BLOG, dir, "cover.webp")) ? `${BLOG}/${dir}/cover.webp` : undefined;
  if (cover && !data.cover_alt) bad("cover.webp needs cover_alt");
  return {
    file,
    url: `/blog/${name![2]}`,
    slug: name![2],
    title: str("title"),
    date,
    author: str("author"),
    summary: str("summary"),
    draft: data.draft === true,
    cover,
    coverAlt: typeof data.cover_alt === "string" ? data.cover_alt : undefined,
    body,
  };
}

function load(): PostSource[] {
  const root = path.join(/* turbopackIgnore: true */ REPO, BLOG);
  if (!fs.existsSync(root)) return [];
  const all = fs
    .readdirSync(root, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => read(e.name))
    .sort((a, b) => b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug));
  const seen = new Set<string>();
  for (const p of all) {
    if (seen.has(p.slug)) throw new Error(`${p.file}: another post already uses /blog/${p.slug}`);
    seen.add(p.slug);
  }
  return all;
}

/** Every post folder, drafts included. Only for building routes: a hidden draft's route is a 404. */
export const ALL_POSTS = load();

/** The posts this build shows, newest first. */
export const POSTS = ALL_POSTS.filter((p) => SHOW_DRAFTS || !p.draft);
