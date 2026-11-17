import { Chip, Chips, ListCard, Row } from "../Manage";
import { EVIDENCE } from "@/lib/docs/notes";
import type { NoteItem, TopicGroup } from "@/lib/docs/research";
import d from "./Docs.module.css";

/** How sure a note is, and its date: the Status line's, else its last commit's (unless the page shows that already). */
export function NoteFacts({ note, frame, commitDate = true }: { note: NoteItem; frame?: string; commitDate?: boolean }) {
  return (
    <Chips>
      {frame && <Chip>{frame}</Chip>}
      {note.evidence && (
        <Chip>
          <b>{EVIDENCE[note.evidence]}</b>
        </Chip>
      )}
      {note.date && (commitDate || note.dateFrom === "status") && <Chip>{note.dateFrom === "commit" ? `Updated ${note.date}` : note.date}</Chip>}
    </Chips>
  );
}

/** Under "How we got here": the notes themselves, browsable. */
export function ResearchLink() {
  return (
    <ListCard id="notes-all" title="The notes">
      <Row href="/research" title="Every note, by frame and topic" sub="With its date, and whether it was verified, read or is untested" />
    </ListCard>
  );
}

/** A note as a row: title, the index's one line, how sure, when; and its frame, in a list across frames. */
export function NoteRow({ note, frame }: { note: NoteItem; frame?: string }) {
  return (
    <Row
      href={note.url}
      title={note.title}
      sub={<span dangerouslySetInnerHTML={{ __html: note.blurb }} />}
      extra={<NoteFacts note={note} frame={frame} />}
    />
  );
}

/** One topic's notes, as rows. */
export function TopicSection({ group, h = "h2" }: { group: TopicGroup<NoteItem>; h?: "h2" | "h3" }) {
  const H = h;
  return (
    <section className={d.topic} aria-labelledby={group.id}>
      <H id={group.id}>
        {group.label} <small>{group.notes.length}</small>
      </H>
      {group.notes.map((n) => (
        <NoteRow key={n.url} note={n} />
      ))}
    </section>
  );
}
