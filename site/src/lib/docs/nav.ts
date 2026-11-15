import { frames } from "../frames";
import { PANES, STAGE_TEXT } from "@glassworks/frame-card";
import { getDocs, SECTIONS, type Doc } from "./index";

/** The docs' sidebar: sections of links, a frame's guides nested under it. */
export type NavItem = { label: string; href: string; aside?: string; children?: NavItem[] };
export type NavGroup = { label: string; items: NavItem[] };

const item = (d: Doc): NavItem => ({ label: d.label ?? d.title, href: d.url });

export async function docsNav(): Promise<NavGroup[]> {
  const docs = await getDocs();
  const of = (section: string) => docs.filter((d) => d.section === section);
  const libraries = docs.filter((d) => d.url.startsWith("/docs/libraries/"));
  const groups: Record<string, NavItem[]> = {
    panel: [{ label: "Introduction", href: "/docs" }, ...of("panel").map(item)],
    frames: [
      ...frames.map((f) => ({
        label: f.name,
        href: `/docs/frames/${f.id}`,
        aside: f.status === "beta" || f.status === "stable" ? undefined : STAGE_TEXT[f.status],
        children: of("frames")
          .filter((d) => d.frame === f.id && d.url !== `/docs/frames/${f.id}`)
          .map(item),
      })),
      ...of("frames").filter((d) => !d.frame).map(item),
    ],
    philosophy: of("philosophy").map(item),
    build: [
      ...docs.filter((d) => d.url === "/docs/core/pane-api").map(item),
      { label: "Panes", href: "/docs/panes", aside: String(PANES.length) },
      { label: "Libraries", href: "/docs/libraries", aside: String(libraries.length) },
      ...docs.filter((d) => d.url.startsWith("/docs/kiln")).map(item),
    ],
    project: of("project").map(item),
    notes: [{ label: "How we got here", href: "/docs/notes", aside: String(of("notes").length - 1) }],
  };
  return SECTIONS.map((s) => ({ label: s.label, items: groups[s.id] }));
}
