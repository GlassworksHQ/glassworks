import { frames } from "../frames";
import { PANES, STAGE_TEXT } from "@glassworks/frame-card";
import { getDocs, type Doc, type Section } from "./index";
import { apiReferenceOf } from "../apiDocs";
import { researchUrl } from "./research";

/** The docs sidebar: three cleanly separated audience tracks (Users, Developers, Research). */
export type NavItem = {
  label: string;
  href: string;
  aside?: string;
  children?: NavItem[];
  /** a page Next doesn't route (the static API reference, javadoc.io, jsdocs.io): a plain link */
  plain?: boolean;
  /** one step deeper than a child */
  deep?: boolean;
  /** pages under this path light it too */
  also?: string;
};
export type NavGroup = { label: string; items: NavItem[] };
export type NavTrack = {
  id: Section;
  label: string;
  shortLabel: string;
  groups: NavGroup[];
};

const item = (d: Doc): NavItem => ({ label: d.label ?? d.title, href: d.url });

export async function docsTracks(): Promise<NavTrack[]> {
  const docs = await getDocs();
  const of = (section: string) => docs.filter((d) => d.section === section);
  const byUrl = (url: string) => docs.filter((d) => d.url === url).map(item);
  const libraries = docs.filter((d) => d.url.startsWith("/docs/libraries/"));

  /* User Guide frames: install & daily device guides only (no reverse-engineering notes mixed in) */
  const frameItems: NavItem[] = frames.map((f) => {
    const children = of("user")
      .filter((d) => d.frame === f.id && d.url !== `/docs/frames/${f.id}`)
      .map(item);
    return {
      label: f.name,
      href: `/docs/frames/${f.id}`,
      aside: f.status === "beta" || f.status === "stable" ? undefined : STAGE_TEXT[f.status],
      ...(children.length > 0 ? { children } : {}),
    };
  });

  const userDaily = of("user").filter((d) => !d.frame && d.url !== "/docs/getting-started");

  /* Per-frame research links live strictly inside the Research track */
  const frameResearch: NavItem[] = frames.flatMap((f) => {
    const n = docs.filter((d) => d.section === "notes" && d.frame === f.id).length;
    return n
      ? [
          {
            label: f.name,
            href: researchUrl(f.id),
            aside: String(n),
            also: `/docs/frames/${f.id}/notes/`,
          },
        ]
      : [];
  });

  return [
    {
      id: "user",
      label: "User Guide",
      shortLabel: "Users",
      groups: [
        {
          label: "Start here",
          items: [{ label: "Docs overview", href: "/docs" }, ...byUrl("/docs/getting-started")],
        },
        {
          label: "Devices",
          items: frameItems,
        },
        {
          label: "Daily features",
          items: userDaily.map(item),
        },
      ],
    },
    {
      id: "developers",
      label: "Developers & Hardware Makers",
      shortLabel: "Developers",
      groups: [
        {
          label: "Architecture",
          items: [
            ...byUrl("/docs/why-glassworks"),
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
                    : api?.kind === "elsewhere"
                      ? [{ label: "API", href: api.href, aside: api.host, plain: true, deep: true }]
                      : [];
                return [item(d), ...ref];
              }),
            },
          ],
        },
        {
          label: "Kiln",
          items: docs.filter((d) => d.url.startsWith("/docs/kiln")).map(item),
        },
        {
          label: "Security & operations",
          items: [
            ...byUrl("/docs/secrets"),
            ...["/docs/perf", "/docs/frames/aqara-s1-plus/radio", "/docs/size", "/docs/ci", "/docs/releasing", "/docs/branding", "/docs/legal", "/changelog"].flatMap(byUrl),
          ],
        },
      ],
    },
    {
      id: "notes",
      label: "Hardware Research",
      shortLabel: "Research",
      groups: [
        {
          label: "Hardware research",
          items: [
            { label: "All research notes", href: "/research", aside: String(of("notes").length - 1), also: "/docs/notes/" },
            { label: "How we got here", href: "/docs/notes" },
          ],
        },
        ...(frameResearch.length > 0 ? [{ label: "By device", items: frameResearch }] : []),
      ],
    },
  ];
}

export async function docsNav(): Promise<NavGroup[]> {
  const tracks = await docsTracks();
  return tracks.flatMap((t) => t.groups);
}
