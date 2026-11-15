/* The site's addresses, in a module of their own so build-time code (the docs) can use them
   without the pane catalog. */
/** The site's own origin, for anything that leaves it: README snippets, metadata. */
export const SITE = "https://www.glassworks.dev";

export const links = {
  org: "https://github.com/GlassworksHQ",
  s1: "https://github.com/GlassworksHQ/glassworks",
  /** the project's public email (maintainer, 2026-11-13) */
  contact: "mailto:hi@glassworks.dev",
} as const;
