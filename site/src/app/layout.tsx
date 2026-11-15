import { SITE } from "@/lib/site";
import { SITE_DESCRIPTION, SITE_NAME } from "@/lib/seo";
import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import { MarkDefs } from "@/components/Mark";
import { SearchDialog } from "@/components/search/SearchDialog";
import { THEME_SCRIPT } from "@/components/shell/ThemeToggle";
import chrome from "@/components/chrome.module.css";
import "./globals.css";

const inter = Inter({ variable: "--font-inter", subsets: ["latin", "greek"] });
const mono = JetBrains_Mono({ variable: "--font-mono", subsets: ["latin"] });

/* Every page is built ahead of time. An address under a dynamic route that wasn't built (an unknown doc,
   post, frame or pane) then renders in full before the response starts, so notFound() answers 404.
   Without it, Partial Prefetching sends the route's App Shell with a 200 before notFound() runs. */
export const ensureStatic = "navigation";

/* Defaults for every page. Pages set their own title, description and canonical with pageMetadata
   (src/lib/seo.ts); a page without one (the 404) falls back to these, with no canonical. */
export const metadata: Metadata = {
  metadataBase: new URL(SITE),
  title: { default: SITE_NAME, template: `%s · ${SITE_NAME}` },
  description: SITE_DESCRIPTION,
  applicationName: SITE_NAME,
  openGraph: { siteName: SITE_NAME, type: "website", locale: "en" },
};

export const viewport: Viewport = {
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#eceef2" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0b0f" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // data-theme is set before paint from the visitor's pick, so React must not fight it
    <html lang="en" className={`${inter.variable} ${mono.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body>
        <MarkDefs />
        <a href="#content" className={chrome.skip}>
          Skip to content
        </a>
        {children}
        <SearchDialog />
      </body>
    </html>
  );
}
