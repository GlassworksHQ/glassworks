import type { MetadataRoute } from "next";
import { frames } from "./frames";
import { absoluteUrl, type SitePath } from "./seo";
import { panes } from "./site";
import { lastChanged } from "./docs";
import { SOURCES as DOC_SOURCES } from "./docs/sources";
import { researchUrl } from "./docs/research";
import { POSTS } from "./blog/posts";
import { apiReferences } from "./apiDocs";

/*
 * Every page search engines should know about, for app/sitemap.ts. Each section of the site is one
 * source: a function that lists its pages from the same data its routes are built from, so a new
 * frame, pane, doc or post is in the sitemap in the same commit, with no list to keep by hand.
 * scripts/check-seo.mjs fails the build when a prerendered page is missing here, or the other way round.
 *
 * Docs and the blog are being designed. When one lands, its source goes in `SOURCES` below, reading the
 * same content index its `generateStaticParams` reads (e.g. `docs.map((d) => ({ path: `/docs/${d.slug}`,
 * modified: d.updated }))`), and its pages call `pageMetadata` (src/lib/seo.ts) with `type: "article"`.
 */

export type SiteRoute = {
  path: SitePath;
  /** an ISO date that is true (a post's date, a doc's last edit). Leave it out rather than guess. */
  modified?: string;
};

export type RouteSource = () => SiteRoute[];

/** The site's own pages: not from data. */
const pages: RouteSource = () => [
  { path: "/" },
  { path: "/frames" },
  { path: "/panes" },
  { path: "/blueprints" },
  { path: "/brand" },
  { path: "/trademarks" },
];

const framePages: RouteSource = () => frames.map((f) => ({ path: `/frames/${f.id}` }));

const panePages: RouteSource = () => panes.map((p) => ({ path: `/panes/${p.id}` }));

/** /docs, its indexes, every published doc and /changelog (src/lib/docs/sources.ts). */
const docs: RouteSource = () => [
  { path: "/docs" },
  { path: "/docs/panes" },
  { path: "/docs/libraries" },
  ...DOC_SOURCES.map((s) => ({ path: s.url as SitePath, modified: lastChanged(s.file) })),
  // each frame's research tab (app/docs/frames/[id]/research)
  ...[...new Set(DOC_SOURCES.filter((s) => s.section === "notes" && s.frame).map((s) => s.frame!))].map((id) => ({ path: researchUrl(id) as SitePath })),
];

/** The index of each library's API reference this site serves: static Dokka pages (scripts/api-docs.mjs). */
const apiDocs: RouteSource = () => [...apiReferences().values()].flatMap((r) => (r.kind === "site" ? [{ path: r.href }] : []));

/** /blog and each post this build shows. A draft is noindex but listed, so preview builds stay checkable. */
const blog: RouteSource = () => [{ path: "/blog" }, ...POSTS.map((p) => ({ path: p.url as SitePath, modified: p.date }))];

export const SOURCES: Record<string, RouteSource> = { pages, frames: framePages, panes: panePages, docs, apiDocs, blog };

export function siteRoutes(): SiteRoute[] {
  const all = Object.values(SOURCES).flatMap((s) => s());
  const seen = new Set<string>();
  for (const r of all) {
    if (seen.has(r.path)) throw new Error(`src/lib/routes.ts: ${r.path} is listed twice`);
    seen.add(r.path);
  }
  return all;
}

export function sitemapEntries(): MetadataRoute.Sitemap {
  return siteRoutes().map((r) => ({
    url: absoluteUrl(r.path),
    ...(r.modified ? { lastModified: r.modified } : {}),
  }));
}
