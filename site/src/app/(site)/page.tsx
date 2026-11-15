import { StackStory } from "@/components/StackStory";
import Link from "next/link";
import { ListCard, Tile, Tiles, manage as m } from "@/components/Manage";
import { FrameGrid } from "@/components/FrameGrid";
import { PaneRows } from "@/components/PaneRows";
import { frames } from "@/lib/frames";
import { release } from "@/lib/release";
import { panes, stageCounts } from "@/lib/site";
import { JsonLd } from "@/components/JsonLd";
import { SITE_DESCRIPTION, organizationLd, pageMetadata, softwareLd, websiteLd } from "@/lib/seo";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { getPosts } from "@/lib/docs";
import b from "@/components/blog/Blog.module.css";
import styles from "./page.module.css";

export const metadata = pageMetadata({
  title: "Glassworks: the open core for Home Assistant wall panels",
  absoluteTitle: true,
  description: SITE_DESCRIPTION,
  path: "/",
});

export default async function Home() {
  const [post] = await getPosts();
  const app = frames.find((f) => f.project)?.project?.name ?? "Glassworks";
  return (
    <>
      <JsonLd nodes={[organizationLd(), websiteLd(), softwareLd(SITE_DESCRIPTION)]} />
      <StackStory
        release={{ name: app, version: release.version, href: release.href }}
        post={post?.fresh ? { title: post.title, href: post.url } : undefined}
      />

      <Tiles>
        <Tile href="/frames" label="Frames" value={frames.length} sub={stageCounts(frames)} />
        <Tile href="/panes" label="Panes" value={panes.length} sub={stageCounts(panes)} />
        <Tile href={release.href} label="Latest release" value={release.version} sub={`${app} · ${release.date}`} />
        <Tile href="/docs/principles" label="Docs" value="Principles" sub="What it is and isn't" />
      </Tiles>

      {post && (
        <Link href={post.url} className={`${m.card} ${b.homeCard}`}>
          <PostCover post={post} />
          <div>
            <div className={b.meta}>
              {post.draft && <DraftFlag />}
              <span>Blog</span>
              <time dateTime={post.date}>{longDate(post.date)}</time>
            </div>
            <h2>{post.title}</h2>
            <p>{post.summary}</p>
            <span className={b.read}>Read the post</span>
          </div>
        </Link>
      )}

      <ListCard id="panes" title="Panes" aside={<Link href="/panes">All panes ›</Link>}>
        <PaneRows panes={panes} />
      </ListCard>

      <FrameGrid id="devices" title="Devices" aside={<Link href="/frames">All frames ›</Link>} frames={frames} />

      <section aria-labelledby="why">
        <div className={styles.whyHead}>
          <h2 id="why">Why MPL-2.0</h2>
          <span>Glassworks stays open. Your product doesn&rsquo;t have to.</span>
        </div>
        <Tiles className={m.trio}>
          <Tile value="Port it" sub="Write a frame for your device." />
          <Tile value="Ship it" sub="Sell panels. Your frames and panes can stay closed." />
          <Tile value="Share back" sub="Changes to Glassworks' own files stay open." />
        </Tiles>
      </section>
    </>
  );
}
