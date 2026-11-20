import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { DocArticle } from "@/components/docs/DocArticle";
import { ResearchLink } from "@/components/docs/NoteBits";
import { JsonLd } from "@/components/JsonLd";
import { apiReferenceOf } from "@/lib/apiDocs";
import { getDoc, getDocs } from "@/lib/docs";
import { SOURCES } from "@/lib/docs/sources";
import { getNotes, notesOf, researchUrl } from "@/lib/docs/research";
import { frameById } from "@/lib/frames";
import { FrameTabs } from "@/components/docs/FrameTabs";
import Link from "next/link";
import { articleLd, breadcrumbLd, pageMetadata, type SitePath } from "@/lib/seo";

const urlOf = (slug: string[]) => `/docs/${slug.join("/")}`;

export function generateStaticParams() {
  return SOURCES.filter((s) => s.url.startsWith("/docs/")).map((s) => ({ slug: s.url.slice("/docs/".length).split("/") }));
}

export async function generateMetadata(props: PageProps<"/docs/[...slug]">): Promise<Metadata> {
  const doc = await getDoc(urlOf((await props.params).slug));
  if (!doc) notFound();
  return pageMetadata({ title: doc.title, description: doc.summary, path: doc.url as SitePath, type: "article", modified: doc.updated });
}

export default function DocPage(props: PageProps<"/docs/[...slug]">) {
  return (
    <Suspense fallback={<div style={{ minHeight: "100svh" }} aria-busy="true" />}>
      <Doc params={props.params} />
    </Suspense>
  );
}

async function Doc({ params }: { params: PageProps<"/docs/[...slug]">["params"] }) {
  const url = urlOf((await params).slug);
  // an address that isn't a doc is a 404 before anything renders
  if (!SOURCES.some((s) => s.url === url)) notFound();
  const doc = await getDoc(url);
  if (!doc) notFound();
  const notes = await getNotes();
  const note = notes.find((n) => n.url === doc.url);
  // a note's prev and next stay in its frame's notes; a frame guide stays within its frame; other docs page within their audience track
  const same = note
    ? notesOf(notes, note.frame)
    : (await getDocs()).filter((d) =>
        doc.frame ? d.frame === doc.frame && d.section !== "notes" : !d.frame && d.section === doc.section && d.url.startsWith("/docs/"),
      );
  // matched by URL: each cached call hands back its own copies
  const i = same.findIndex((d) => d.url === doc.url);
  const frame = doc.frame ? frameById(doc.frame) : undefined;
  const frameNotes = frame ? notesOf(notes, frame.id).length : 0;
  const tabs = frame && frameNotes > 0 ? <FrameTabs frame={frame.id} on={note ? "research" : "docs"} notes={frameNotes} /> : undefined;
  const crumbs =
    note && frame ? (
      <>
        <Link href={`/docs/frames/${frame.id}`}>{frame.name}</Link>
        <span aria-hidden="true">/</span>
        <Link href={researchUrl(frame.id)}>Research</Link>
      </>
    ) : note || doc.url === "/docs/notes" ? (
      <Link href="/research">Research</Link>
    ) : undefined;
  return (
    <>
      <JsonLd
        nodes={[
          articleLd({ kind: "TechArticle", title: doc.title, description: doc.summary, path: doc.url as SitePath, modified: doc.updated }),
          breadcrumbLd([
            { name: "Docs", path: "/docs" },
            { name: doc.title, path: doc.url as SitePath },
          ]),
        ]}
      />
      <DocArticle
        doc={note ?? doc}
        prev={same[i - 1]}
        next={same[i + 1]}
        after={doc.url === "/docs/notes" ? <ResearchLink /> : undefined}
        api={apiReferenceOf(doc.file)}
        tabs={tabs}
        crumbs={crumbs}
      />
    </>
  );
}
