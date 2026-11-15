import type { MetadataRoute } from "next";
import { frames } from "./frames";
import { absoluteUrl, type SitePath } from "./seo";
import { panes } from "./site";

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

/** /docs and /docs/<slug>: none yet. */
const docs: RouteSource = () => [];

/** /blog and /blog/<slug>: none yet. */
const blog: RouteSource = () => [];

export const SOURCES: Record<string, RouteSource> = { pages, frames: framePages, panes: panePages, docs, blog };

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
