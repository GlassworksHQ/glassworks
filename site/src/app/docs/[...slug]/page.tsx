import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { DocArticle } from "@/components/docs/DocArticle";
import { NotesList } from "@/components/docs/NotesList";
import { JsonLd } from "@/components/JsonLd";
import { apiReferenceOf } from "@/lib/apiDocs";
import { getDoc, getDocs } from "@/lib/docs";
import { SOURCES } from "@/lib/docs/sources";
import { articleLd, breadcrumbLd, pageMetadata, type SitePath } from "@/lib/seo";

const urlOf = (slug: string[]) => `/docs/${slug.join("/")}`;

export function generateStaticParams() {
  return SOURCES.filter((s) => s.url.startsWith("/docs/")).map((s) => ({ slug: s.url.slice("/docs/".length).split("/") }));
}

export async function generateMetadata(props: PageProps<"/docs/[...slug]">): Promise<Metadata> {
  const doc = await getDoc(urlOf((await props.params).slug));
  if (!doc) return {};
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
  const same = (await getDocs()).filter((d) => d.section === doc.section && d.url.startsWith("/docs/"));
  // matched by URL: each cached call hands back its own copies
  const i = same.findIndex((d) => d.url === doc.url);
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
      <DocArticle doc={doc} prev={same[i - 1]} next={same[i + 1]} after={doc.url === "/docs/notes" ? <NotesList /> : undefined} api={apiReferenceOf(doc.file)} />
    </>
  );
}
