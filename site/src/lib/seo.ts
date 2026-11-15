import type { Metadata } from "next";
import { SITE, links } from "./site";

/*
 * Metadata for every page, in one place. A page calls `pageMetadata` with its title, description and
 * path, and gets the canonical URL, Open Graph and Twitter cards that match. Docs and blog pages use the
 * same helper (`type: "article"` with dates), so they get all of it without writing any of it.
 *
 * The preview image is the site's Glass Tile unless the page passes `image`. Frames and panes pass their
 * own (`/frames/<id>/card.png`, a route that calls `tileImage` from src/lib/ogTile.tsx); a new kind of
 * page can do the same. The image has to be set here: a page's `openGraph` replaces the layout's whole.
 *
 *   export const metadata = pageMetadata({ title: "Brand", description: "…", path: "/brand" });
 *
 *   export async function generateMetadata(props) {        // a page per item
 *     const d = …;
 *     return pageMetadata({ title, description, path: `/docs/${d.slug}`, type: "article", modified: d.updated });
 *   }
 */

export const SITE_NAME = "Glassworks";

/** The site's description: the home page's, and the fallback for a page without one. */
export const SITE_DESCRIPTION =
  "Open-source core for Home Assistant wall panels: dashboard, ESPHome device, setup and updates, with plugins for everything else.";

/** The site's card (app/opengraph-image.png, drawn by scripts/brand/raster.mjs) and its alt text. */
const SITE_CARD = { width: 1200, height: 630, alt: "Glassworks: the open core for Home Assistant wall panels" };

/** A path on the site: starts with "/", no trailing slash (the site redirects /x/ to /x). */
export type SitePath = "/" | `/${string}`;

export type PageSeo = {
  /** the page's own title; the layout adds " · Glassworks" unless `absoluteTitle` */
  title: string;
  /** 110–160 characters reads best in search results */
  description: string;
  path: SitePath;
  /** the home page's title is the whole title, with no suffix */
  absoluteTitle?: boolean;
  /** "article" for docs and blog posts; everything else is a "website" page */
  type?: "website" | "article";
  /** ISO dates, for articles */
  published?: string;
  modified?: string;
  authors?: string[];
  /** the link preview, 1200 × 630 PNG: a site path and its alt text. The site's card when left out. */
  image?: { path: SitePath; alt: string };
  /** keep a page out of search (a draft, a preview) */
  noindex?: boolean;
};

/** The absolute URL of a site path, as search engines should see it. */
export const absoluteUrl = (path: SitePath) => (path === "/" ? `${SITE}/` : `${SITE}${path}`);

export function pageMetadata(p: PageSeo): Metadata {
  const full = p.absoluteTitle ? p.title : `${p.title} · ${SITE_NAME}`;
  const image = p.image
    ? { url: p.image.path, width: SITE_CARD.width, height: SITE_CARD.height, alt: p.image.alt }
    : { url: "/opengraph-image.png", ...SITE_CARD };
  const shared = {
    title: full,
    description: p.description,
    url: absoluteUrl(p.path),
    siteName: SITE_NAME,
    locale: "en",
    images: [image],
  };
  return {
    title: p.absoluteTitle ? { absolute: p.title } : p.title,
    description: p.description,
    alternates: { canonical: absoluteUrl(p.path) },
    openGraph:
      p.type === "article"
        ? {
            ...shared,
            type: "article",
            publishedTime: p.published,
            modifiedTime: p.modified,
            authors: p.authors,
          }
        : { ...shared, type: "website" },
    twitter: {
      card: "summary_large_image",
      title: full,
      description: p.description,
      images: [image],
    },
    ...(p.noindex ? { robots: { index: false, follow: true } } : {}),
  };
}

/* ---------- structured data (JSON-LD), rendered by <JsonLd> ---------- */

type Ld = Record<string, unknown>;

const ORG_ID = `${SITE}/#organization`;
const SITE_ID = `${SITE}/#website`;

export const organizationLd = (): Ld => ({
  "@type": "Organization",
  "@id": ORG_ID,
  name: SITE_NAME,
  url: absoluteUrl("/"),
  logo: `${SITE}/brand/github-avatar.png`,
  email: links.contact.replace(/^mailto:/, ""),
  sameAs: [links.org],
});

export const websiteLd = (): Ld => ({
  "@type": "WebSite",
  "@id": SITE_ID,
  name: SITE_NAME,
  url: absoluteUrl("/"),
  inLanguage: "en",
  publisher: { "@id": ORG_ID },
});

/** The Glassworks core: free, open source (MPL-2.0), runs on Android panels. */
export const softwareLd = (description: string): Ld => ({
  "@type": "SoftwareApplication",
  name: SITE_NAME,
  description,
  url: absoluteUrl("/"),
  applicationCategory: "UtilitiesApplication",
  operatingSystem: "Android",
  license: "https://www.mozilla.org/en-US/MPL/2.0/",
  isAccessibleForFree: true,
  offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
  sameAs: [links.s1],
  publisher: { "@id": ORG_ID },
});

/** The visible breadcrumb, as data: the home page first, the page itself last. */
export const breadcrumbLd = (trail: { name: string; path: SitePath }[]): Ld => ({
  "@type": "BreadcrumbList",
  itemListElement: [{ name: "Home", path: "/" as SitePath }, ...trail].map((c, i) => ({
    "@type": "ListItem",
    position: i + 1,
    name: c.name,
    item: absoluteUrl(c.path),
  })),
});

/** A docs page or blog post. `kind` "TechArticle" for docs, "BlogPosting" for the blog. */
export const articleLd = (a: {
  kind: "TechArticle" | "BlogPosting";
  title: string;
  description: string;
  path: SitePath;
  published?: string;
  modified?: string;
  authors?: string[];
}): Ld => ({
  "@type": a.kind,
  headline: a.title,
  description: a.description,
  url: absoluteUrl(a.path),
  mainEntityOfPage: absoluteUrl(a.path),
  datePublished: a.published,
  dateModified: a.modified ?? a.published,
  author: a.authors?.length ? a.authors.map((name) => ({ "@type": "Person", name })) : { "@id": ORG_ID },
  publisher: { "@id": ORG_ID },
  inLanguage: "en",
});

/** One JSON-LD document for a page: its nodes in a @graph, `<` escaped so text can't close the script. */
export const jsonLd = (...nodes: Ld[]) =>
  JSON.stringify({ "@context": "https://schema.org", "@graph": nodes }).replace(/</g, "\\u003c");
