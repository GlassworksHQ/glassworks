import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { PaneBlueprints } from "@/components/Blueprints";
import { paneIcon } from "@/components/DeviceBits";
import { Chip, ListCard, PageHead, Row, Sq, StageChip, Tile, Tiles, manage as m } from "@/components/Manage";
import { frameById, frames } from "@/lib/frames";
import { paneSeo, ships } from "@/lib/frameText";
import { JsonLd } from "@/components/JsonLd";
import { breadcrumbLd, pageMetadata } from "@/lib/seo";
import { panes } from "@/lib/site";
import { support } from "@/lib/paneSupport";
import s from "./pane.module.css";

const paneById = (id: string) => panes.find((p) => p.id === id);

export function generateStaticParams() {
  return panes.map((p) => ({ id: p.id }));
}

export async function generateMetadata(props: PageProps<"/panes/[id]">): Promise<Metadata> {
  const { id } = await props.params;
  const p = paneById(id);
  if (!p) return {};
  const frame = p.frame ? frameById(p.frame) : undefined;
  return pageMetadata({
    ...paneSeo(p, frame?.name),
    path: `/panes/${p.id}`,
    image: { path: `/panes/${p.id}/card.png`, alt: `${p.name}, a Glassworks pane` },
  });
}

type Params = PageProps<"/panes/[id]">["params"];

/* Same shape as the frame pages: the id is read inside <Suspense>. */
export default function PanePage(props: PageProps<"/panes/[id]">) {
  return (
    <Suspense fallback={<div className={s.fallback} aria-busy="true" />}>
      <Pane params={props.params} />
    </Suspense>
  );
}

async function Pane({ params }: { params: Params }) {
  const { id } = await params;
  const p = paneById(id);
  if (!p) notFound();
  const Icon = paneIcon[p.id];
  const frame = p.frame ? frameById(p.frame) : undefined;
  if (p.frame && !frame) throw new Error(`src/lib/site.ts: pane "${p.id}" names frame "${p.frame}", which no frame.json has`);
  // a frame pane only means something on its own device
  const rows = frame ? [frame] : frames;
  const runsOn = rows.filter((d) => ships(support(d, p.id).support)).length;

  return (
    <article>
      <JsonLd
        nodes={[
          breadcrumbLd([
            { name: "Panes", path: "/panes" },
            { name: p.name, path: `/panes/${p.id}` },
          ]),
        ]}
      />
      <PageHead
        crumbs={
          <>
            <Link href="/panes">Panes</Link>
            <span aria-hidden="true">/</span>
            <span aria-current="page">{p.name}</span>
          </>
        }
        title={
          <span className={s.title}>
            <Sq lit>
              <Icon />
            </Sq>
            {p.name}
          </span>
        }
        lede={p.does}
      >
        <span className={s.chips}>
          <StageChip support={p.status} />
          {frame && <Chip>Frame pane</Chip>}
        </span>
      </PageHead>

      <Tiles cols={3}>
        <Tile label="Needs" value={p.requires} />
        <Tile label="Runs on" value={`${runsOn} of ${rows.length} ${rows.length === 1 ? "frame" : "frames"}`} />
        <Tile label="Kind" value={frame ? "Frame pane" : "General"} sub={frame ? `Lives in the ${frame.name} frame` : "Any frame with the hardware"} />
      </Tiles>

      <PaneBlueprints pane={p.id} />

      <ListCard id="where" title="Where it runs">
        {rows.map((d) => {
          const sup = support(d, p.id);
          return (
            <Row
              key={d.id}
              href={`/frames/${d.id}`}
              lead={d.render ? <Image className={m.thumb} src={`/frames/${d.id}/render.webp`} alt="" width={92} height={56} unoptimized /> : undefined}
              title={d.name}
              sub={sup.note}
              end={<StageChip support={sup.support} />}
            />
          );
        })}
      </ListCard>
    </article>
  );
}
