import fs from "node:fs";
import path from "node:path";

/*
 * Which Markdown files in the repo the site publishes, at which URL, under which audience track.
 * GitHub stays the source: the site renders these files at build time and never keeps its own copy.
 * A file not listed here is linked on GitHub instead. One map drives the pages, navigation, search
 * sections, and link rewriting, so links that work on GitHub also work on glassworks.dev.
 */
export const REPO = path.join(process.cwd(), "..");

export type Section = "user" | "developers" | "notes";

export const SECTIONS: { id: Section; label: string }[] = [
  { id: "user", label: "User Guide" },
  { id: "developers", label: "Developers & Hardware Makers" },
  { id: "notes", label: "Hardware Research" },
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

/* a frame's guides, in reading order and with short nav labels; the rest follow alphabetically */
const FRAME_DOC_ORDER = ["install", "radio-guide", "sensors", "radio"];
const FRAME_DOC_LABEL: Record<string, string> = { install: "Install", "radio-guide": "Using the radio", sensors: "Sensors", radio: "S1 Plus radio reference" };
/** Frame docs that are deep technical references rather than everyday user guides. */
const DEVELOPER_FRAME_DOCS = new Set(["radio"]);
const byOrder = (order: string[]) => (a: string, b: string) => {
  const ia = order.indexOf(a);
  const ib = order.indexOf(b);
  return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.localeCompare(b);
};

export const PANE_API = "pane-api/src/main/java/dev/glassworks/README.md";

function load(): Source[] {
  const out: Source[] = [];
  const add = (s: Source) => exists(s.file) && out.push(s);

  /* 1. User Guide: Getting Started, Supported Devices & Install guides, Daily Features */
  add({ file: "docs/getting-started.md", url: "/docs/getting-started", section: "user", label: "Getting Started" });

  for (const id of dirs("frames")) {
    if (!exists(`frames/${id}/design/frame.json`)) continue;
    add({ file: `frames/${id}/README.md`, url: `/docs/frames/${id}`, section: "user", frame: id });
    for (const slug of md(`frames/${id}/docs`).sort(byOrder(FRAME_DOC_ORDER))) {
      const section: Section = DEVELOPER_FRAME_DOCS.has(slug) ? "developers" : "user";
      add({ file: `frames/${id}/docs/${slug}.md`, url: `/docs/frames/${id}/${slug}`, section, frame: id, label: FRAME_DOC_LABEL[slug] });
    }
    for (const slug of md(`frames/${id}/research`, ["README.md"])) {
      add({ file: `frames/${id}/research/${slug}.md`, url: `/docs/frames/${id}/notes/${slug}`, section: "notes", frame: id });
    }
  }

  const dailyFeatures: [string, string][] = [
    ["manage", "The Manage page"],
    ["voice-and-timers", "Voice and Timers"],
    ["sendspin", "Music Assistant (Sendspin)"],
    ["notifications", "Notifications"],
    ["esphome", "ESPHome & Automations"],
    ["updates", "Updates"],
    ["blueprints", "Blueprints"],
    ["clarity", "Clarity Grades"],
    ["vitro", "Try Vitro"],
  ];
  for (const [slug, label] of dailyFeatures) {
    add({ file: `docs/${slug}.md`, url: `/docs/${slug}`, section: "user", label });
  }

  /* 2. Developers & Hardware Makers: Principles, Frames, Panes, Libraries, Kiln, Security, Ops */
  add({ file: "docs/why-glassworks.md", url: "/docs/why-glassworks", section: "developers", label: "Why Glassworks" });
  add({ file: "docs/principles.md", url: "/docs/principles", section: "developers", label: "Principles" });
  add({ file: PANE_API, url: "/docs/what-makes-a-pane", section: "developers", cut: "What makes a pane", label: "What makes a pane" });
  add({ file: "frames/README.md", url: "/docs/frames", section: "developers", label: "Writing a frame" });
  add({ file: "docs/frame-agent.md", url: "/docs/frame-agent", section: "developers", label: "The frame agent" });
  add({ file: "docs/engine-contract.md", url: "/docs/engine-contract", section: "developers", label: "The engine contract" });
  add({ file: PANE_API, url: "/docs/core/pane-api", section: "developers", label: "Pane API" });

  for (const id of dirs("panes")) {
    const readme = findReadme(`panes/${id}`);
    if (readme) add({ file: readme, url: `/docs/panes/${id}`, section: "developers" });
  }
  for (const [dir, list] of [["libs", dirs("libs")], ["kiln/packages", dirs("kiln/packages")], ["packages", dirs("packages")]] as const) {
    for (const name of list) add({ file: `${dir}/${name}/README.md`, url: `/docs/libraries/${name}`, section: "developers" });
  }

  add({ file: "kiln/apps/kiln/README.md", url: "/docs/kiln", section: "developers", label: "Kiln" });
  add({ file: "docs/design/kiln/README.md", url: "/docs/kiln/design", section: "developers", label: "Kiln design" });
  add({ file: "docs/kiln-images.md", url: "/docs/kiln/images", section: "developers", label: "Community images in Kiln" });
  add({ file: "docs/secrets.md", url: "/docs/secrets", section: "developers", label: "Panel secrets & security" });

  const projectOps: [string, string][] = [
    ["perf", "Clarity benchmark spec"],
    ["size", "APK size"],
    ["ci", "CI"],
    ["releasing", "Releasing"],
    ["branding", "Branding"],
    ["legal", "Legal"],
  ];
  for (const [slug, label] of projectOps) {
    add({ file: `docs/${slug}.md`, url: `/docs/${slug}`, section: "developers", label });
  }
  add({ file: "CHANGELOG.md", url: "/changelog", section: "developers", label: "Changelog" });

  /* 3. Hardware Research: engineering notes and bring-up history */
  add({ file: "docs/research/README.md", url: "/docs/notes", section: "notes", label: "How we got here" });
  for (const slug of md("docs/research", ["README.md", "TEMPLATE.md"])) {
    add({ file: `docs/research/${slug}.md`, url: `/docs/notes/${slug}`, section: "notes" });
  }
  add({ file: "docs/research/kiln-cleanroom/README.md", url: "/docs/notes/kiln-cleanroom", section: "notes", label: "Kiln clean-room rewrite" });
  for (const slug of ["fel-spec", "bootpatch-spec"]) {
    add({ file: `docs/research/kiln-cleanroom/${slug}.md`, url: `/docs/notes/kiln-cleanroom/${slug}`, section: "notes" });
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
