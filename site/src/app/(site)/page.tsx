import { StackStory } from "@/components/StackStory";
import Link from "next/link";
import { ListCard, Tile, Tiles, manage as m } from "@/components/Manage";
import { FrameRows } from "@/components/FrameRows";
import { PaneRows } from "@/components/PaneRows";
import { frames } from "@/lib/frames";
import { release } from "@/lib/release";
import { panes, stageCounts } from "@/lib/site";
import { JsonLd } from "@/components/JsonLd";
import { SITE_DESCRIPTION, organizationLd, pageMetadata, softwareLd, websiteLd } from "@/lib/seo";
import styles from "./page.module.css";

export const metadata = pageMetadata({
  title: "Glassworks: the open core for Home Assistant wall panels",
  absoluteTitle: true,
  description: SITE_DESCRIPTION,
  path: "/",
});

export default function Home() {
  const app = frames.find((f) => f.project)?.project?.name ?? "Glassworks";
  return (
    <>
      <JsonLd nodes={[organizationLd(), websiteLd(), softwareLd(SITE_DESCRIPTION)]} />
      <StackStory release={{ name: app, version: release.version, href: release.href }} />

      <Tiles>
        <Tile href="/frames" label="Frames" value={frames.length} sub={stageCounts(frames)} />
        <Tile href="/panes" label="Panes" value={panes.length} sub={stageCounts(panes)} />
        <Tile href={release.href} label="Latest release" value={release.version} sub={`${app} · ${release.date}`} />
        <Tile href="/docs/principles" label="Docs" value="Principles" sub="What it is and isn't" />
      </Tiles>

      <ListCard id="panes" title="Panes" aside={<Link href="/panes">All panes ›</Link>}>
        <PaneRows panes={panes} />
      </ListCard>

      <ListCard id="devices" title="Devices" aside={<span>{frames.length} frames</span>}>
        <FrameRows frames={frames} />
      </ListCard>

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
