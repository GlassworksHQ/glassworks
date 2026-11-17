import fs from "node:fs";
import path from "node:path";
import { getDocs, type Doc } from "./index";
import { REPO } from "./sources";
import { TOPICS, type Topic } from "./notes";

/*
 * The research notes as lists: a frame's, and the general ones. Nothing here is written by hand. The
 * notes are the files in frames/<id>/research/ and docs/research/ (sources.ts), their topic and
 * evidence come from their front matter (notes.ts), their date from their own Status line, and their
 * one-line summary from the tables in docs/research/README.md, the index the notes are listed in.
 */

export const INDEX = "docs/research/README.md";

export type Note = Doc & {
  /** the Status line's date, else the file's last commit */
  date?: string;
  dateFrom?: "status" | "commit";
  /** one line, as inline HTML: the README table's, else the note's first paragraph */
  blurb: string;
};

/** What a list row needs of a note: small enough to hand to the /research page's filter. */
export type NoteItem = Pick<Note, "url" | "title" | "blurb" | "evidence" | "date" | "dateFrom" | "topic" | "frame">;

export type TopicGroup<N extends NoteItem = Note> = { id: Topic | "unsorted"; label: string; notes: N[] };

const esc = (t: string) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** A README cell's Markdown as inline HTML: code spans kept, links and emphasis as their text. */
function inline(md: string): string {
  return md
    .split(/(`[^`]+`)/)
    .map((part) =>
      part.startsWith("`")
        ? `<code>${esc(part.slice(1, -1))}</code>`
        : esc(part.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/\*\*([^*]+)\*\*/g, "$1").replace(/\\\|/g, "|")),
    )
    .join("");
}

/** The index's table rows: repo path of the note → its "What's in it" cell. */
function indexBlurbs(): Map<string, string> {
  const out = new Map<string, string>();
  const src = fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, INDEX), "utf8");
  for (const line of src.split("\n")) {
    const row = line.match(/^\|\s*\[[^\]]+\]\(([^)#]+)[^)]*\)\s*\|\s*(.+?)\s*\|\s*$/);
    if (!row) continue;
    let target = path.posix.normalize(path.posix.join(path.posix.dirname(INDEX), row[1]));
    if (target.endsWith("/")) target += "README.md";
    out.set(target, inline(row[2]));
  }
  return out;
}

const STATUS_DATE = /^\*{0,2}Status[^\n]*?(\d{4}-\d{2}-\d{2})/m;
const TITLE_DATE = /^# [^\n]*?(\d{4}-\d{2}-\d{2})/m;

function statusDate(file: string): string | undefined {
  const src = fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, file), "utf8").slice(0, 4000);
  return src.match(STATUS_DATE)?.[1] ?? src.match(TITLE_DATE)?.[1];
}

const TOPIC_ORDER = Object.keys(TOPICS) as Topic[];
const rank = (n: Note) => (n.topic ? TOPIC_ORDER.indexOf(n.topic) : TOPIC_ORDER.length);

/**
 * Every research note, in reading order: frame by frame, then the general ones; within each, by
 * topic, then newest first. Prev and next on a note page follow this order.
 */
export async function getNotes(): Promise<Note[]> {
  const blurbs = indexBlurbs();
  const notes = (await getDocs()).filter((d) => d.section === "notes" && d.file !== INDEX);
  return notes
    .map((d): Note => {
      const status = statusDate(d.file);
      return {
        ...d,
        date: status ?? d.updated,
        dateFrom: status ? "status" : d.updated ? "commit" : undefined,
        blurb: blurbs.get(d.file) ?? esc(d.summary),
      };
    })
    .sort((a, b) => (a.frame ?? "~").localeCompare(b.frame ?? "~") || rank(a) - rank(b) || (b.date ?? "").localeCompare(a.date ?? "") || a.title.localeCompare(b.title));
}

/** A frame's notes, or the general ones (frame undefined). */
export const notesOf = <N extends NoteItem>(notes: N[], frame: string | undefined) => notes.filter((n) => n.frame === frame);

/** A note as a list row needs it. */
export const noteItem = ({ url, title, blurb, evidence, date, dateFrom, topic, frame }: Note): NoteItem => ({ url, title, blurb, evidence, date, dateFrom, topic, frame });

/** Notes by topic, in the topics' order; notes without front matter last. */
export function byTopic<N extends NoteItem>(notes: N[]): TopicGroup<N>[] {
  const groups: TopicGroup<N>[] = TOPIC_ORDER.map((id) => ({ id, label: TOPICS[id], notes: notes.filter((n) => n.topic === id) }));
  groups.push({ id: "unsorted", label: "Not sorted yet", notes: notes.filter((n) => !n.topic) });
  return groups.filter((g) => g.notes.length);
}

/** The research tab of a frame. */
export const researchUrl = (frame: string) => `/docs/frames/${frame}/research`;
