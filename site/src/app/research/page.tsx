import Link from "next/link";
import { ListCard, Row, StageChip, manage as m } from "@/components/Manage";
import { ResearchIndex } from "@/components/docs/ResearchIndex";
import { TopicSection } from "@/components/docs/NoteBits";
import { EVIDENCE, type Evidence } from "@/lib/docs/notes";
import { byTopic, getNotes, noteItem, notesOf, researchUrl, type Note } from "@/lib/docs/research";
import { frames } from "@/lib/frames";
import { pageMetadata } from "@/lib/seo";
import d from "@/components/docs/Docs.module.css";
import "@/components/docs/prose.css";

export const metadata = pageMetadata({
  title: "Research",
  description: "Engineering notes from Glassworks: how each device was opened up, what worked, what failed, and what we verified on hardware.",
  path: "/research",
});

const tally = (notes: Note[]) => {
  const parts = (Object.keys(EVIDENCE) as Evidence[]).map((e) => [e, notes.filter((n) => n.evidence === e).length] as const).filter(([, n]) => n);
  return `${notes.length} ${notes.length === 1 ? "note" : "notes"}${parts.length ? `: ${parts.map(([e, n]) => `${n} ${EVIDENCE[e].toLowerCase()}`).join(", ")}` : ""}`;
};

/** Every research note: each frame's first, then the ones about every frame, by topic. */
export default async function Research() {
  const notes = await getNotes();
  // frames with the most notes first; a frame without notes still shows, with its docs
  const byFrame = frames.map((f) => ({ f, notes: notesOf(notes, f.id) })).sort((a, b) => b.notes.length - a.notes.length);
  const general = notesOf(notes, undefined);
  return (
    <>
      <header className={d.landingHead}>
        <span className="label">Research</span>
        <h1 className={d.landingH1}>Research</h1>
        <p className={m.lede}>
          How each device was opened up, what worked, what hit a dead end, and how confident we are. Every note is tagged as verified on hardware, read from source or binaries, or untested. Start with <Link href="/docs/notes">How we got here</Link> for the full story.
        </p>
      </header>
      <ResearchIndex notes={notes.map(noteItem)} frameNames={Object.fromEntries(frames.map((f) => [f.id, f.name]))}>
        <ListCard id="frames" title="Frames" aside={<span>{byFrame.length} frames</span>}>
          {byFrame.map(({ f, notes: n }) => (
            <Row
              key={f.id}
              href={n.length ? researchUrl(f.id) : `/docs/frames/${f.id}`}
              title={f.name}
              sub={n.length ? tally(n) : "No notes yet; its docs"}
              end={<StageChip support={f.status} />}
            />
          ))}
        </ListCard>
        <section className={`${m.card} ${m.list}`} aria-labelledby="general">
          <div className={m.lh}>
            <h2 id="general">Across frames</h2>
            <span>{tally(general)}</span>
          </div>
          <div className={d.topics}>
            {byTopic(general).map((g) => (
              <TopicSection key={g.id} group={g} h="h3" />
            ))}
          </div>
        </section>
      </ResearchIndex>
    </>
  );
}
