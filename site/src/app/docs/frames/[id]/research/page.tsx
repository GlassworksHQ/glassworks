import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { manage as m } from "@/components/Manage";
import { FrameStrip } from "@/components/docs/DocArticle";
import { FrameTabs } from "@/components/docs/FrameTabs";
import { TopicSection } from "@/components/docs/NoteBits";
import { Toc } from "@/components/docs/Toc";
import { JsonLd } from "@/components/JsonLd";
import { EVIDENCE, type Evidence } from "@/lib/docs/notes";
import { INDEX, byTopic, getNotes, notesOf, researchUrl } from "@/lib/docs/research";
import { SOURCES } from "@/lib/docs/sources";
import { frameById } from "@/lib/frames";
import { links } from "@/lib/links";
import { breadcrumbLd, pageMetadata, type SitePath } from "@/lib/seo";
import d from "@/components/docs/Docs.module.css";
import "@/components/docs/prose.css";

/* every frame with notes in frames/<id>/research/ */
const withNotes = () => [...new Set(SOURCES.filter((s) => s.section === "notes" && s.frame).map((s) => s.frame!))];

export function generateStaticParams() {
  return withNotes().map((id) => ({ id }));
}

async function load(id: string) {
  const frame = withNotes().includes(id) ? frameById(id) : undefined;
  if (!frame) notFound();
  return { frame, notes: notesOf(await getNotes(), id) };
}

export async function generateMetadata(props: PageProps<"/docs/frames/[id]/research">): Promise<Metadata> {
  const { frame, notes } = await load((await props.params).id);
  return pageMetadata({
    title: `${frame.name}: research notes`,
    description: `How the ${frame.name} was opened up: ${notes.length} engineering notes by topic, each with its date and how sure it is.`,
    path: researchUrl(frame.id) as SitePath,
  });
}

export default function FrameResearch(props: PageProps<"/docs/frames/[id]/research">) {
  return (
    <Suspense fallback={<div style={{ minHeight: "100svh" }} aria-busy="true" />}>
      <Research params={props.params} />
    </Suspense>
  );
}

async function Research({ params }: { params: PageProps<"/docs/frames/[id]/research">["params"] }) {
  const { frame, notes } = await load((await params).id);
  const groups = byTopic(notes);
  const counts = (Object.keys(EVIDENCE) as Evidence[]).map((e) => [e, notes.filter((n) => n.evidence === e).length] as const).filter(([, n]) => n);
  const toc = groups.map((g) => ({ id: g.id, text: g.label, depth: 2 as const }));
  return (
    <>
      <JsonLd
        nodes={[
          breadcrumbLd([
            { name: "Docs", path: "/docs" },
            { name: frame.name, path: `/docs/frames/${frame.id}` },
            { name: "Research", path: researchUrl(frame.id) as SitePath },
          ]),
        ]}
      />
      <div className={d.layout}>
        <div className={d.column}>
          <article className={`${m.card} ${d.article}`}>
            <header className={d.head}>
              <nav className={d.crumbs} aria-label="Breadcrumb">
                <Link href="/docs">Docs</Link>
                <span aria-hidden="true">/</span>
                <Link href={`/docs/frames/${frame.id}`}>{frame.name}</Link>
              </nav>
              <FrameStrip frame={frame} tabs={<FrameTabs frame={frame.id} on="research" notes={notes.length} />} />
              <h1 className={d.h1}>Research</h1>
              <p className={d.tagline}>
                How the {frame.name} was opened up: what we tried, what worked, and how sure we are. Each note says what was{" "}
                <b>verified</b> on a device, <b>read</b> from source or a binary, or is still <b>untested</b>.
              </p>
              <p className={d.meta}>
                <span>
                  {notes.length} notes
                  {counts.length > 0 && ": "}
                  {counts.map(([e, n]) => `${n} ${EVIDENCE[e].toLowerCase()}`).join(", ")}
                </span>
                <a href={`${links.s1}/blob/main/${INDEX}`}>The index on GitHub</a>
              </p>
            </header>
            {toc.length >= 2 && (
              <details className={d.tocFold}>
                <summary>Topics</summary>
                <ol>
                  {toc.map((t) => (
                    <li key={t.id}>
                      <a href={`#${t.id}`}>{t.text}</a>
                    </li>
                  ))}
                </ol>
              </details>
            )}
            {groups.map((g) => (
              <TopicSection key={g.id} group={g} />
            ))}
          </article>
        </div>
        {toc.length >= 2 && <Toc toc={toc} />}
      </div>
    </>
  );
}
