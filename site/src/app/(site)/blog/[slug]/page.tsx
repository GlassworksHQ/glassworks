import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { manage as m } from "@/components/Manage";
import { EditGlyph } from "@/components/Glyphs";
import { JsonLd } from "@/components/JsonLd";
import { Mark } from "@/components/Mark";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { getPosts } from "@/lib/docs";
import { ALL_POSTS } from "@/lib/blog/posts";
import { articleLd, breadcrumbLd, pageMetadata, type SitePath } from "@/lib/seo";
import b from "@/components/blog/Blog.module.css";
import "@/components/docs/prose.css";

export function generateStaticParams() {
  // every folder, so the route has at least one page; a draft this build hides renders the 404
  return ALL_POSTS.map((p) => ({ slug: p.slug }));
}

const postBySlug = async (slug: string) => (await getPosts()).find((p) => p.slug === slug);

export async function generateMetadata(props: PageProps<"/blog/[slug]">): Promise<Metadata> {
  const p = await postBySlug((await props.params).slug);
  if (!p) return {};
  return pageMetadata({
    title: p.title,
    description: p.summary,
    path: p.url as SitePath,
    type: "article",
    published: p.date,
    authors: [p.author],
    image: { path: `${p.url}/card.png` as SitePath, alt: `${p.title}, on the Glassworks blog` },
    noindex: p.draft,
  });
}

export default function PostPage(props: PageProps<"/blog/[slug]">) {
  return (
    <Suspense fallback={<div style={{ minHeight: "100svh" }} aria-busy="true" />}>
      <Post params={props.params} />
    </Suspense>
  );
}

async function Post({ params }: { params: PageProps<"/blog/[slug]">["params"] }) {
  const p = await postBySlug((await params).slug);
  if (!p) notFound();
  return (
    <article className={`${m.card} ${b.post}`}>
      <JsonLd
        nodes={[
          articleLd({ kind: "BlogPosting", title: p.title, description: p.summary, path: p.url as SitePath, published: p.date }),
          breadcrumbLd([
            { name: "Blog", path: "/blog" },
            { name: p.title, path: p.url as SitePath },
          ]),
        ]}
      />
      <div className={b.inner}>
        {p.draft && (
          <p className={b.banner}>
            <DraftFlag />
            Not published. Drafts don&rsquo;t reach the live site or the feed.
          </p>
        )}
        <nav className={b.meta} aria-label="Breadcrumb">
          <Link href="/blog">Blog</Link>
        </nav>
        <h1 id={p.titleId || undefined} className={b.title}>
          {p.title}
        </h1>
        <p className={b.summary}>{p.summary}</p>
        <p className={b.meta}>
          <span className={b.by}>
            <Mark cut="small" />
            {p.author}
          </span>
          <time dateTime={p.date}>{longDate(p.date)}</time>
          <span>{p.minutes} min read</span>
          <a href={p.edit}>
            <EditGlyph />
            Edit on GitHub
          </a>
        </p>
        <PostCover post={p} priority />
        {/* the post's own Markdown, rendered and sanitized at build time (src/lib/docs/render.ts) */}
        <div className="md" dangerouslySetInnerHTML={{ __html: p.html }} />
        <nav className={b.foot} aria-label="More">
          <Link href="/blog" className={b.pager}>
            <small>Blog</small>
            <b>All posts</b>
          </Link>
          <a href="/blog/feed.xml" className={`${b.pager} ${b.right}`}>
            <small>Follow</small>
            <b>Atom feed</b>
          </a>
        </nav>
      </div>
    </article>
  );
}
