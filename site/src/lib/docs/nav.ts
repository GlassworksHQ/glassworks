import { frames } from "../frames";
import { PANES, STAGE_TEXT } from "@glassworks/frame-card";
import { getDocs, SECTIONS, type Doc } from "./index";
import { apiReferenceOf } from "../apiDocs";
import { researchUrl } from "./research";

/** The docs sidebar: three audience tracks with expandable frame guides and library API references. */
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
  const byUrl = (url: string) => docs.filter((d) => d.url === url).map(item);
  const libraries = docs.filter((d) => d.url.startsWith("/docs/libraries/"));

  const frameItems: NavItem[] = frames.map((f) => ({
    label: f.name,
    href: `/docs/frames/${f.id}`,
    aside: f.status === "beta" || f.status === "stable" ? undefined : STAGE_TEXT[f.status],
    children: [
      ...of("user")
        .filter((d) => d.frame === f.id && d.url !== `/docs/frames/${f.id}`)
        .map(item),
      ...research(f.id),
    ],
  }));

  const userDaily = of("user").filter((d) => !d.frame && d.url !== "/docs/getting-started");

  const groups: Record<string, NavItem[]> = {
    user: [
      { label: "Docs overview", href: "/docs" },
      ...byUrl("/docs/getting-started"),
      ...frameItems,
      ...userDaily.map(item),
    ],
    developers: [
      ...byUrl("/docs/principles"),
      ...byUrl("/docs/what-makes-a-pane"),
      ...byUrl("/docs/frames"),
      ...byUrl("/docs/frame-agent"),
      ...byUrl("/docs/core/pane-api"),
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
      ...byUrl("/docs/secrets"),
      ...["/docs/size", "/docs/ci", "/docs/releasing", "/docs/branding", "/docs/legal", "/changelog"].flatMap(byUrl),
    ],
    notes: [
      { label: "All research notes", href: "/research", aside: String(of("notes").length - 1), also: "/docs/notes/" },
      { label: "How we got here", href: "/docs/notes" },
    ],
  };
  return SECTIONS.map((s) => ({ label: s.label, items: groups[s.id] }));
}
