import Link from "next/link";
import { MorphStory } from "@/components/home/MorphStory";
import { ListCard, manage as m } from "@/components/Manage";
import { FrameGrid } from "@/components/FrameGrid";
import { PaneRows } from "@/components/PaneRows";
import { frames, KILN } from "@/lib/frames";
import { release } from "@/lib/release";
import { panes } from "@/lib/site";
import { JsonLd } from "@/components/JsonLd";
import { SITE_DESCRIPTION, organizationLd, pageMetadata, softwareLd, websiteLd } from "@/lib/seo";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { getPosts } from "@/lib/docs";
import b from "@/components/blog/Blog.module.css";
import s from "./page.module.css";

export const metadata = pageMetadata({
  title: "Glassworks: your home, on the wall",
  absoluteTitle: true,
  description: SITE_DESCRIPTION,
  path: "/",
});

export default async function Home() {
  const [[post], latest] = await Promise.all([getPosts(), release()]);

  return (
    <>
      <JsonLd nodes={[organizationLd(), websiteLd(), softwareLd(SITE_DESCRIPTION)]} />

      <MorphStory
        release={{ version: latest.version, href: latest.href }}
        post={post?.fresh ? { title: post.title, href: post.url } : undefined}
      />

      <div className={s.below}>
        {/* 3-Pillar Editorial Hairline Strip */}
        <section className={s.pillars} aria-label="Core pillars">
          <a href={KILN} className={s.pillar}>
            <span className={`${s.pillarNum} ${s.pillarEmber}`}>01 / KILN INSTALLER</span>
            <h2>Installed from Chrome, reversible in one click</h2>
            <p>Backs up your device over USB before writing a byte, verifies every write, and keeps a clean way back to stock.</p>
          </a>

          <Link href="/docs/perf" className={s.pillar}>
            <span className={s.pillarNum}>02 / CLARITY GRADES</span>
            <h2>Honest A+ to E wall-panel speed</h2>
            <p>Every supported panel is tested on real Home Assistant dashboards for taps, scrolling, live updates, and camera latency.</p>
          </Link>

          <Link href="/developers" className={s.pillar}>
            <span className={s.pillarNum}>03 / OPEN CORE (MPL-2.0)</span>
            <h2>Built for hardware makers &amp; contributors</h2>
            <p>Take apart the 6-layer architecture, browse the standalone MIT libraries, or port Glassworks to a new device.</p>
          </Link>
        </section>

        <FrameGrid
          id="devices"
          title="Supported devices"
          aside={<Link href="/frames">All devices ›</Link>}
          frames={frames}
        />

        <ListCard id="panes" title="What your panel can do" aside={<Link href="/panes">All features ›</Link>}>
          <PaneRows panes={panes} />
        </ListCard>

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
      </div>
    </>
  );
}
