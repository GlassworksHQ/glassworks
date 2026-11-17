import { frames } from "../frames";
import { PANES, STAGE_TEXT } from "@glassworks/frame-card";
import { getDocs, SECTIONS, type Doc } from "./index";
import { apiReferenceOf } from "../apiDocs";
import { researchUrl } from "./research";

/** The docs' sidebar: sections of links, a frame's guides nested under it, a library's API reference under it. */
export type NavItem = {
  label: string;
  href: string;
  aside?: string;
  children?: NavItem[];
  /** a page Next doesn't route (the static API reference, javadoc.io): a plain link */
  plain?: boolean;
  /** one step deeper than a child */
  deep?: boolean;
  /** pages under this path light it too */
  also?: string;
};
export type NavGroup = { label: string; items: NavItem[] };

const item = (d: Doc): NavItem => ({ label: d.label ?? d.title, href: d.url });

export async function docsNav(): Promise<NavGroup[]> {
  const docs = await getDocs();
  // a frame's research tab, lit on its notes too
  const research = (frame: string): NavItem[] => {
    const n = docs.filter((d) => d.section === "notes" && d.frame === frame).length;
    return n ? [{ label: "Research", href: researchUrl(frame), aside: String(n), also: `/docs/frames/${frame}/notes/` }] : [];
  };
  const of = (section: string) => docs.filter((d) => d.section === section);
  const libraries = docs.filter((d) => d.url.startsWith("/docs/libraries/"));
  const groups: Record<string, NavItem[]> = {
    panel: [{ label: "Introduction", href: "/docs" }, ...of("panel").map(item)],
    frames: [
      ...frames.map((f) => ({
        label: f.name,
        href: `/docs/frames/${f.id}`,
        aside: f.status === "beta" || f.status === "stable" ? undefined : STAGE_TEXT[f.status],
        children: [
          ...of("frames")
            .filter((d) => d.frame === f.id && d.url !== `/docs/frames/${f.id}`)
            .map(item),
          ...research(f.id),
        ],
      })),
      ...of("frames").filter((d) => !d.frame).map(item),
    ],
    philosophy: of("philosophy").map(item),
    build: [
      ...docs.filter((d) => d.url === "/docs/core/pane-api").map(item),
      { label: "Panes", href: "/docs/panes", aside: String(PANES.length) },
      {
        label: "Libraries",
        href: "/docs/libraries",
        aside: String(libraries.length),
        children: libraries.flatMap((d) => {
          const api = apiReferenceOf(d.file)?.reference;
          const ref: NavItem[] =
            api?.kind === "site"
              ? [{ label: "API", href: api.href, aside: api.version, plain: true, deep: true }]
              : api?.kind === "javadoc.io"
                ? [{ label: "API", href: api.href, aside: "javadoc.io", plain: true, deep: true }]
                : [];
          return [item(d), ...ref];
        }),
      },
      ...docs.filter((d) => d.url.startsWith("/docs/kiln")).map(item),
    ],
    project: of("project").map(item),
    notes: [{ label: "How we got here", href: "/docs/notes", aside: String(of("notes").length - 1) }],
  };
  return SECTIONS.map((s) => ({ label: s.label, items: groups[s.id] }));
}
