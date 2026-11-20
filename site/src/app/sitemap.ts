import type { MetadataRoute } from "next";
import { sitemapEntries } from "@/lib/routes";
import { absoluteUrl } from "@/lib/seo";

/** /sitemap.xml: every page, from src/lib/routes.ts plus /developers. */
export default function sitemap(): MetadataRoute.Sitemap {
  const entries = sitemapEntries();
  const devUrl = absoluteUrl("/developers");
  if (!entries.some((e) => e.url === devUrl)) {
    entries.push({ url: devUrl });
  }
  return entries;
}
