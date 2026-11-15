import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

const repo = fileURLToPath(new URL("..", import.meta.url));

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  // The site reads the repo around it (frames/, packages/frame-card, tools/perf), so the root is the
  // repo, as for Kiln (kiln/apps/kiln/next.config.ts).
  turbopack: { root: repo },
  outputFileTracingRoot: repo,
  // The frame card ships TypeScript and CSS modules, not built JavaScript (packages/frame-card).
  // shiki (the docs' code highlighting) is on Next's list of packages it leaves external, and the
  // trace missed the @shikijs/* packages it imports, so on Vercel every page rendered on demand failed
  // with a 500. Bundled, nothing of it has to be found at run time. `npm run check:trace` loads every
  // remaining external module from each route's bundle alone.
  transpilePackages: ["@glassworks/frame-card", "shiki"],
  // The site reads frames/*/design, frames/*/clarity/clarity.json and the Markdown the docs and blog
  // publish (src/lib/docs/sources.ts, src/lib/blog/posts.ts), with the images beside it. A function
  // may render a doc again on revalidation, so those go into the server bundles; nothing else from the
  // repo does (a published report.json is a few hundred kB). `npm run check:trace` checks after a build
  // that each docs route carries every published file.
  outputFileTracingIncludes: {
    "*": [
      "../{README.md,CHANGELOG.md}",
      "../docs/**/*.{md,png,jpg,jpeg,webp,gif,svg}",
      "../frames/README.md",
      "../frames/*/{README.md,docs/**,research/**}",
      "../{libs,kiln/packages,packages}/*/README.md",
      "../kiln/apps/kiln/README.md",
      "../{panes,pane-api}/**/README.md",
      "../branding/glass-tile/preview.webp",
      "./blog/**",
      // which libraries have an API reference (scripts/api-docs.mjs, src/lib/apiDocs.ts)
      "./.api-docs/libraries.json",
    ],
  },
  outputFileTracingExcludes: {
    "*": [
      "../{tools,LICENSES,core,engines,testing}/**",
      "../kiln/{src,apps,packages}/**/!(README.md)",
      "../frames/*/{installer,android,panes}/**",
      "../frames/*/clarity/report.*",
    ],
  },
  // Hosts allowed to load dev-server resources (HMR, dev chunks) when you open
  // `npm run dev` from another machine. Comma-separated, set in .env.local.
  allowedDevOrigins: process.env.DEV_ORIGINS?.split(",").map((h) => h.trim()).filter(Boolean),
  redirects() {
    return [
      // The S1's page was /frames/aqara-s1 before the catalog came from frame.json, whose id is aqara-s1-plus.
      { source: "/frames/aqara-s1", destination: "/frames/aqara-s1-plus", permanent: true },
      // The pane catalog (issue 231) uses the app's ids: the wall base was "base" here, and "sensors"
      // isn't a pane (presence and light are core entities), so its page goes to the list.
      { source: "/panes/base", destination: "/panes/wall-base", permanent: true },
      { source: "/panes/sensors", destination: "/panes", permanent: true },
      // A library's API reference is static Dokka HTML (scripts/api-docs.mjs), whose links are relative to
      // its index.html, so the folder's own address goes there.
      { source: "/docs/libraries/:name/api", destination: "/docs/libraries/:name/api/index.html", permanent: false },
    ];
  },
};

export default nextConfig;
