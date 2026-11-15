import type { MetadataRoute } from "next";

/** /manifest.webmanifest: the name and icons browsers show for the site. The icons are the brand's own. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Glassworks",
    short_name: "Glassworks",
    description: "The open core for Home Assistant wall panels.",
    start_url: "/",
    display: "browser",
    background_color: "#0a0b0f",
    theme_color: "#0a0b0f",
    icons: [
      { src: "/icon.svg", type: "image/svg+xml", sizes: "any" },
      { src: "/apple-icon.png", type: "image/png", sizes: "180x180" },
      { src: "/brand/github-avatar.png", type: "image/png", sizes: "1024x1024" },
    ],
  };
}
