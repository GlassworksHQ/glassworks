import { Chip, Chips, Row } from "../Manage";
import { EVIDENCE } from "@/lib/docs/notes";
import type { Note, TopicGroup } from "@/lib/docs/research";
import d from "./Docs.module.css";

/** How sure a note is, and its date: the Status line's, else its last commit's. */
export function NoteFacts({ note }: { note: Note }) {
  return (
    <Chips>
      {note.evidence && (
        <Chip>
          <b>{EVIDENCE[note.evidence]}</b>
        </Chip>
      )}
      {note.date && <Chip>{note.dateFrom === "commit" ? `Updated ${note.date}` : note.date}</Chip>}
    </Chips>
  );
}

/** One topic's notes, as rows: title, the index's one line, how sure, when. */
export function TopicSection({ group, idPrefix = "" }: { group: TopicGroup; idPrefix?: string }) {
  return (
    <section className={d.topic} aria-labelledby={`${idPrefix}${group.id}`}>
      <h2 id={`${idPrefix}${group.id}`}>
        {group.label} <small>{group.notes.length}</small>
      </h2>
      {group.notes.map((n) => (
        <Row key={n.url} href={n.url} title={n.title} sub={<span dangerouslySetInnerHTML={{ __html: n.blurb }} />} extra={<NoteFacts note={n} />} />
      ))}
    </section>
  );
}
