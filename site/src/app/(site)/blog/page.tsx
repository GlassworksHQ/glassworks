import Link from "next/link";
import { ListCard, Row, manage as m } from "@/components/Manage";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { FeedGlyph } from "@/components/Glyphs";
import { getPosts } from "@/lib/docs";
import { pageMetadata } from "@/lib/seo";
import b from "@/components/blog/Blog.module.css";

export const metadata = pageMetadata({
  title: "Blog",
  description: "News from Glassworks, the open core for Home Assistant wall panels: releases, new frames and what we learned getting there.",
  path: "/blog",
});

export default async function Blog() {
  const [latest, ...older] = await getPosts();
  return (
    <>
      <header className={b.head}>
        <span className="label">Blog</span>
        <h1 className={b.h1}>News from Glassworks</h1>
        <p className={m.lede}>Releases, new frames, and what we learned.</p>
        <a href="/blog/feed.xml" className={b.feed}>
          <FeedGlyph />
          Atom feed
        </a>
      </header>

      {!latest && <p className={b.empty}>No posts yet.</p>}

      {latest && (
        <Link href={latest.url} className={`${m.card} ${b.feature}`}>
          <PostCover post={latest} priority />
          <div className={b.featureText}>
            <div className={b.meta}>
              {latest.draft && <DraftFlag />}
              <time dateTime={latest.date}>{longDate(latest.date)}</time>
              <span>{latest.author}</span>
            </div>
            <h2>{latest.title}</h2>
            <p>{latest.summary}</p>
          </div>
        </Link>
      )}

      {older.length > 0 && (
        <ListCard id="older" title="Earlier">
          {older.map((p) => (
            <Row key={p.url} href={p.url} title={p.title} sub={`${longDate(p.date)} · ${p.summary}`} end={p.draft ? <DraftFlag /> : undefined} />
          ))}
        </ListCard>
      )}
    </>
  );
}
