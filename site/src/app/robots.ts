import type { MetadataRoute } from "next";
import { SITE } from "@/lib/site";

/*
 * /robots.txt. Production is open to every crawler and points at the sitemap. A Vercel preview
 * deployment (VERCEL_ENV "preview" or "development") asks crawlers to stay out, so a *.vercel.app
 * URL never competes with www.glassworks.dev. Local and CI builds have no VERCEL_ENV and act as
 * production, so scripts/check-seo.mjs checks what production serves.
 */
export default function robots(): MetadataRoute.Robots {
  const env = process.env.VERCEL_ENV;
  if (env && env !== "production") return { rules: { userAgent: "*", disallow: "/" } };
  return {
    rules: { userAgent: "*", allow: "/" },
    sitemap: `${SITE}/sitemap.xml`,
  };
}
