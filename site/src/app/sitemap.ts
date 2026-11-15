import type { MetadataRoute } from "next";
import { sitemapEntries } from "@/lib/routes";

/** /sitemap.xml: every page, from src/lib/routes.ts. */
export default function sitemap(): MetadataRoute.Sitemap {
  return sitemapEntries();
}
