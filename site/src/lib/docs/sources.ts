import fs from "node:fs";
import path from "node:path";

/*
 * Which Markdown in the repo the site publishes, at which URL, under which heading. GitHub stays
 * the source: the site renders these files at build time and never keeps its own copy. A file not
 * listed here is linked on GitHub instead. One map, used by the pages, the nav and the renderer's
 * link rewriting, so a link that works on GitHub works on the site.
 */
export const REPO = path.join(process.cwd(), "..");

export type Section = "panel" | "frames" | "philosophy" | "build" | "project" | "notes";

export const SECTIONS: { id: Section; label: string }[] = [
  { id: "panel", label: "Using a panel" },
  { id: "frames", label: "Frames" },
  { id: "philosophy", label: "Philosophy" },
  { id: "build", label: "Panes and libraries" },
  { id: "project", label: "Project" },
  { id: "notes", label: "Engineering notes" },
];

export type Source = {
  /** repo path of the Markdown */
  file: string;
  /** site path, no trailing slash */
  url: string;
  section: Section;
  /** the frame a frame doc or note belongs to */
  frame?: string;
  /** publish only this H2 of the file, as its own page */
  cut?: string;
  /** the nav's short name; the page title otherwise */
  label?: string;
};

const exists = (p: string) => fs.existsSync(path.join(/* turbopackIgnore: true */ REPO, p));
const ls = (dir: string) => (exists(dir) ? fs.readdirSync(path.join(/* turbopackIgnore: true */ REPO, dir), { withFileTypes: true }) : []);
const md = (dir: string, skip: string[] = []) =>
  ls(dir)
    .filter((e) => e.isFile() && e.name.endsWith(".md") && !skip.includes(e.name))
    .map((e) => e.name.slice(0, -3))
    .sort();
const dirs = (dir: string) => ls(dir).filter((e) => e.isDirectory()).map((e) => e.name).sort();

/** a README somewhere under dir (a pane keeps its README next to its code) */
function findReadme(dir: string): string | undefined {
  for (const e of ls(dir)) {
    const p = `${dir}/${e.name}`;
    if (e.isFile() && e.name === "README.md") return p;
    if (e.isDirectory()) {
      const r = findReadme(p);
      if (r) return r;
    }
  }
}

/* a frame's guides, in reading order and with the nav's short names; the rest follow by name */
const FRAME_DOC_ORDER = ["install", "radio-guide", "sensors", "radio"];
const FRAME_DOC_LABEL: Record<string, string> = { install: "Install", "radio-guide": "Using the radio", sensors: "Sensors", radio: "Radio modes" };
const byOrder = (order: string[]) => (a: string, b: string) => {
  const ia = order.indexOf(a);
  const ib = order.indexOf(b);
  return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.localeCompare(b);
};

export const PANE_API = "pane-api/src/main/java/dev/glassworks/README.md";

function load(): Source[] {
  const out: Source[] = [];
  const add = (s: Source) => exists(s.file) && out.push(s);

  const panel: [string, string][] = [
    ["manage", "The Manage page"],
    ["esphome", "ESPHome entities"],
    ["notifications", "Notifications"],
    ["sendspin", "Music Assistant (Sendspin)"],
  ];
  for (const [slug, label] of panel) add({ file: `docs/${slug}.md`, url: `/docs/${slug}`, section: "panel", label });

  for (const id of dirs("frames")) {
    if (!exists(`frames/${id}/design/frame.json`)) continue;
    add({ file: `frames/${id}/README.md`, url: `/docs/frames/${id}`, section: "frames", frame: id });
    for (const slug of md(`frames/${id}/docs`).sort(byOrder(FRAME_DOC_ORDER))) {
      add({ file: `frames/${id}/docs/${slug}.md`, url: `/docs/frames/${id}/${slug}`, section: "frames", frame: id, label: FRAME_DOC_LABEL[slug] });
    }
    for (const slug of md(`frames/${id}/research`, ["README.md"])) {
      add({ file: `frames/${id}/research/${slug}.md`, url: `/docs/frames/${id}/notes/${slug}`, section: "notes", frame: id });
    }
  }
  add({ file: "frames/README.md", url: "/docs/frames", section: "frames", label: "Writing a frame" });

  add({ file: "docs/principles.md", url: "/docs/principles", section: "philosophy", label: "Principles" });
  add({ file: PANE_API, url: "/docs/what-makes-a-pane", section: "philosophy", cut: "What makes a pane", label: "What makes a pane" });

  add({ file: PANE_API, url: "/docs/core/pane-api", section: "build", label: "Pane API" });
  for (const id of dirs("panes")) {
    const readme = findReadme(`panes/${id}`);
    if (readme) add({ file: readme, url: `/docs/panes/${id}`, section: "build" });
  }
  for (const [dir, list] of [["libs", dirs("libs")], ["kiln/packages", dirs("kiln/packages")], ["packages", dirs("packages")]] as const) {
    for (const name of list) add({ file: `${dir}/${name}/README.md`, url: `/docs/libraries/${name}`, section: "build" });
  }
  add({ file: "kiln/apps/kiln/README.md", url: "/docs/kiln", section: "build", label: "Kiln" });
  add({ file: "docs/design/kiln/README.md", url: "/docs/kiln/design", section: "build", label: "Kiln's design" });

  const project: [string, string][] = [
    ["perf", "Clarity, the performance grade"],
    ["size", "Size"],
    ["releasing", "Releasing"],
    ["branding", "Branding"],
    ["legal", "Legal"],
  ];
  for (const [slug, label] of project) add({ file: `docs/${slug}.md`, url: `/docs/${slug}`, section: "project", label });
  add({ file: "CHANGELOG.md", url: "/changelog", section: "project", label: "Changelog" });

  add({ file: "docs/research/README.md", url: "/docs/notes", section: "notes", label: "How we got here" });
  for (const slug of md("docs/research", ["README.md", "TEMPLATE.md"])) {
    add({ file: `docs/research/${slug}.md`, url: `/docs/notes/${slug}`, section: "notes" });
  }
  add({ file: "docs/onboarding.md", url: "/docs/notes/onboarding", section: "notes" });
  return out;
}

export const SOURCES = load();

/* files the site stands in for: their links go to these pages */
const ALIASES: Record<string, string> = {
  "README.md": "/docs",
  "docs/README.md": "/docs",
};

/** The site URL a repo file is published at, or undefined when it lives on GitHub only. */
export function siteUrl(file: string): string | undefined {
  if (ALIASES[file]) return ALIASES[file];
  return SOURCES.find((s) => s.file === file && !s.cut)?.url;
}
