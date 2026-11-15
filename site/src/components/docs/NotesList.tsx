import { ListCard, Row } from "../Manage";
import { getDocs } from "@/lib/docs";
import { frameById } from "@/lib/frames";

/** Every engineering note, the project's first and then each frame's. */
export async function NotesList() {
  const notes = (await getDocs()).filter((d) => d.section === "notes" && d.url !== "/docs/notes");
  const groups = [...new Set(notes.map((n) => n.frame ?? ""))];
  return groups.map((g) => (
    <ListCard key={g || "core"} id={`notes-${g || "core"}`} title={g ? frameById(g)?.name ?? g : "Core"} aside={<span>{notes.filter((n) => (n.frame ?? "") === g).length} notes</span>}>
      {notes
        .filter((n) => (n.frame ?? "") === g)
        .map((n) => (
          <Row key={n.url} href={n.url} title={n.title} sub={n.summary} />
        ))}
    </ListCard>
  ));
}
