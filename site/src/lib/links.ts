/* The site's addresses, in a module of their own so build-time code (the docs) can use them
   without the pane catalog. */
/** The site's own origin, for anything that leaves it: README snippets, metadata. */
export const SITE = "https://www.glassworks.dev";

/** Kiln's site: the browser installer for Glassworks frames. */
export const KILN = "https://kiln.glassworks.dev";

export const links = {
  org: "https://github.com/GlassworksHQ",
  s1: "https://github.com/GlassworksHQ/glassworks",
  discord: "https://discord.gg/BTn9NstFM",
  /** the project's public email (maintainer, 2026-11-13) */
  contact: "mailto:hi@glassworks.dev",
} as const;
