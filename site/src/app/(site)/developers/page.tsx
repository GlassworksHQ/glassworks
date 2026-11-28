import Link from "next/link";
import { StackStory } from "@/components/StackStory";
import { Chip, ListCard, Row, Sq, Tile, Tiles, manage as m } from "@/components/Manage";
import { JsonLd } from "@/components/JsonLd";
import {
  BookGlyph,
  ChipGlyph,
  CreditGlyph,
  FrameGlyph,
  PaneGlyph,
} from "@/components/Glyphs";
import { apiReferences } from "@/lib/apiDocs";
import { getDocs } from "@/lib/docs";
import { frames, heroPanel } from "@/lib/frames";
import libraries from "@/lib/libraries.json";
import { release } from "@/lib/release";
import { breadcrumbLd, pageMetadata } from "@/lib/seo";
import { links } from "@/lib/site";
import s from "./developers.module.css";

export const metadata = pageMetadata({
  title: "Developers",
  description:
    "Build on Glassworks: the 6-layer architecture, Pane API, standalone MIT libraries, and MPL-2.0 licensing for hardware makers.",
  path: "/developers",
});

export default async function DevelopersPage() {
  const [docs, latest] = await Promise.all([getDocs(), release()]);
  const app = frames.find((f) => f.project)?.project?.name ?? "Glassworks";
  const docUrls = new Set(docs.map((d) => d.url));
  const apiRefs = apiReferences();

  return (
    <>
      <JsonLd nodes={[breadcrumbLd([{ name: "Developers", path: "/developers" }])]} />

      <StackStory
        mode="developers"
        release={{ name: app, version: latest.version, href: latest.href }}
        panel={heroPanel()}
      />

      <ListCard id="libraries" title="Standalone MIT libraries" aside={<Link href="/docs/libraries">All library docs ›</Link>}>
        {libraries.map((lib) => {
          const docPath = `/docs/libraries/${lib.name}`;
          const href = docUrls.has(docPath) ? docPath : links.s1;
          const api = apiRefs.get(lib.name);
          const apiLabel = api?.kind === "site" ? `API ${api.version}` : api?.kind === "elsewhere" ? "API" : undefined;

          return (
            <Row
              key={lib.name}
              href={href}
              title={<code className={s.libCode}>{lib.name}</code>}
              sub={lib.description}
              end={
                <span className={s.endChips}>
                  {apiLabel && <Chip>{apiLabel}</Chip>}
                  <Chip>
                    <b>MIT</b>
                  </Chip>
                </span>
              }
            />
          );
        })}
      </ListCard>

      <section aria-labelledby="why">
        <div className={s.whyHead}>
          <h2 id="why">Why MPL-2.0</h2>
          <span>Glassworks stays open while your product stays yours.</span>
        </div>
        <Tiles className={m.trio}>
          <Tile value="Port it" sub="Write a frame for any Android wall panel, smart mirror, or tablet." />
          <Tile value="Ship your own hardware" sub="Bundle Glassworks on hardware you sell. Your custom frames and panes can stay closed." />
          <Tile value="Share core fixes back" sub="Improvements to Glassworks core files stay open so every supported device gets better." />
        </Tiles>
      </section>

      <ListCard id="build" title="Build with Glassworks" aside={<a href={links.s1}>GitHub ›</a>}>
        <Row
          href="/docs/why-glassworks"
          lead={
            <Sq lit>
              <ChipGlyph />
            </Sq>
          }
          title="Why Glassworks (technical overview)"
          sub="How Glassworks compares to other wall-panel stacks, plus Kiln, security, Clarity, and Vitro architecture."
        />
        <Row
          href="/docs/frames"
          lead={
            <Sq>
              <FrameGlyph />
            </Sq>
          }
          title="Adding a device"
          sub="Define hardware capabilities, install recipes, and frame bindings for a new panel."
        />
        <Row
          href="/docs/core/pane-api"
          lead={
            <Sq lit>
              <PaneGlyph />
            </Sq>
          }
          title="Pane API"
          sub="The stable contract for ESPHome entities, setup steps, settings, and overlays."
        />
        <Row
          href="/docs/frame-agent"
          lead={
            <Sq>
              <ChipGlyph />
            </Sq>
          }
          title="The frame agent"
          sub="How privileged hardware actions run cleanly and safely on rooted panels."
        />
        <Row
          href="/research"
          lead={
            <Sq>
              <BookGlyph />
            </Sq>
          }
          title="Open hardware research"
          sub="Teardowns, boot chains, radio logs, and bring-up notes published from the repo."
        />
        <Row
          href="/docs/principles"
          lead={
            <Sq>
              <CreditGlyph />
            </Sq>
          }
          title="Project principles"
          sub="Why Home Assistant stays the brain, ESPHome stays the wire, and nothing installs in HA."
        />
      </ListCard>
    </>
  );
}
