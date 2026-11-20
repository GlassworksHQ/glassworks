import Link from "next/link";
import { PanelHero, type ShowcaseFrame } from "@/components/home/PanelHero";
import { ListCard, Row, Sq, manage as m } from "@/components/Manage";
import { FrameGrid } from "@/components/FrameGrid";
import { PaneRows } from "@/components/PaneRows";
import {
  CheckGlyph,
  CreditGlyph,
  GaugeGlyph,
  KilnGlyph,
  PaneGlyph,
} from "@/components/Glyphs";
import { frames, installLink, KILN } from "@/lib/frames";
import { CLARITY, clarityFor } from "@/lib/clarity";
import { release } from "@/lib/release";
import { panes } from "@/lib/site";
import { JsonLd } from "@/components/JsonLd";
import { SITE_DESCRIPTION, organizationLd, pageMetadata, softwareLd, websiteLd } from "@/lib/seo";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { getPosts } from "@/lib/docs";
import b from "@/components/blog/Blog.module.css";

export const metadata = pageMetadata({
  title: "Glassworks: your Home Assistant dashboard, on the wall",
  absoluteTitle: true,
  description: SITE_DESCRIPTION,
  path: "/",
});

const FEATURED_ORDER = ["aqara-s1-plus", "lululemon-mirror", "google-pixel-tablet"];

function showcaseFrames(): ShowcaseFrame[] {
  const byId = new Map(frames.map((f) => [f.id, f]));
  const picked = FEATURED_ORDER.map((id) => byId.get(id)).filter((f): f is (typeof frames)[number] => Boolean(f));
  const list = picked.length > 0 ? picked : frames.slice(0, 3);
  return list.map((f) => {
    const c = clarityFor(f.id);
    const install = installLink(f);
    const { width, height, depth } = f.model.body;
    return {
      frame: f,
      dimensions: `${width} × ${height} × ${depth} mm`,
      clarity: c.state === "graded" ? `${CLARITY} ${c.grade.grade}` : undefined,
      kilnHref: install?.kiln ? install.href : undefined,
      page: `/frames/${f.id}`,
    };
  });
}

export default async function Home() {
  const [[post], latest] = await Promise.all([getPosts(), release()]);
  const app = frames.find((f) => f.project)?.project?.name ?? "Glassworks";

  return (
    <>
      <JsonLd nodes={[organizationLd(), websiteLd(), softwareLd(SITE_DESCRIPTION)]} />

      <PanelHero
        frames={showcaseFrames()}
        release={{ name: app, version: latest.version, href: latest.href }}
        post={post?.fresh ? { title: post.title, href: post.url } : undefined}
      />

      <ListCard id="how" title="How Glassworks works" aside={<Link href="/docs">Read the docs ›</Link>}>
        <Row
          href="/docs/manage"
          lead={
            <Sq lit>
              <PaneGlyph />
            </Sq>
          }
          title="Your own dashboard, full screen"
          sub="Shows the Home Assistant dashboard you already built, with no custom launcher to learn."
        />
        <Row
          href="/docs/esphome"
          lead={
            <Sq>
              <CheckGlyph />
            </Sq>
          }
          title="One native ESPHome device"
          sub="Adopt it in Home Assistant with no HACS integration, no cloud account, and automatic updates under Settings → Updates."
        />
        <Row
          href={KILN}
          lead={
            <Sq>
              <KilnGlyph />
            </Sq>
          }
          title="Safe browser install with Kiln"
          sub="Installs over USB in Chrome or Edge, backs up your device first, verifies every write, and keeps one-click undo."
        />
        <Row
          href="/docs/perf"
          lead={
            <Sq>
              <GaugeGlyph />
            </Sq>
          }
          title="Honest Clarity grades"
          sub="Every device is tested on the real Home Assistant frontend and graded A+ to E."
        />
      </ListCard>

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

      <ListCard id="developers" title="For hardware makers & developers" aside={<span>MPL-2.0</span>}>
        <Row
          href="/developers"
          lead={
            <Sq>
              <CreditGlyph />
            </Sq>
          }
          title="Explore the open core"
          sub="Take apart the 6-layer architecture, browse the standalone MIT libraries, or port Glassworks to a new device."
        />
      </ListCard>
    </>
  );
}
