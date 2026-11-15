import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { HeroModel } from "@/components/FrameCard";
import { RatingLabel, RatingValue, SpecLabel, paneIcon } from "@/components/DeviceBits";
import { Chip, FactRow, ListCard, Row, Sq, StageChip, Tile, Tiles, manage as m } from "@/components/Manage";
import { BookGlyph, CreditGlyph, KilnGlyph } from "@/components/Glyphs";
import { ClarityPanel } from "@/components/clarity/Clarity";
import { ClarityChipLink } from "@/components/clarity/ClarityChipLink";
import { CLARITY, clarityFor } from "@/lib/clarity";
import { frameById, frames, installLink } from "@/lib/frames";
import { getDocs } from "@/lib/docs";
import { frameSeo, ratingKeys, ratingText, ships, type Support } from "@/lib/frameText";
import { JsonLd } from "@/components/JsonLd";
import { breadcrumbLd, pageMetadata } from "@/lib/seo";
import { panes } from "@/lib/site";
import { support } from "@/lib/paneSupport";
import s from "./device.module.css";

export function generateStaticParams() {
  return frames.map((f) => ({ slug: f.id }));
}

export async function generateMetadata(props: PageProps<"/frames/[slug]">): Promise<Metadata> {
  const { slug } = await props.params;
  const d = frameById(slug);
  if (!d) return {};
  return pageMetadata({
    ...frameSeo(d),
    path: `/frames/${d.id}`,
    image: { path: `/frames/${d.id}/card.png`, alt: `${d.name}, a Glassworks frame` },
  });
}

type Params = PageProps<"/frames/[slug]">["params"];

/* The slug is URL data, so it's read inside <Suspense> to keep navigations
   instant. All frames are prerendered, so the fallback only shows briefly on
   an unprefetched client navigation. */
export default function DevicePage(props: PageProps<"/frames/[slug]">) {
  return (
    <Suspense fallback={<div className={s.fallback} aria-busy="true" />}>
      <Device params={props.params} />
    </Suspense>
  );
}

const RANK: Record<Support, number> = { stable: 0, beta: 1, alpha: 2, development: 3, planned: 4, depends: 5, unknown: 6, no: 7 };

async function Device({ params }: { params: Params }) {
  const { slug } = await params;
  const d = frameById(slug);
  if (!d) notFound();
  const install = installLink(d);
  const c = clarityFor(d.id);
  const live = ships(d.status);

  const rows = panes
    .map((p) => ({ p, ...support(d, p.id) }))
    .sort((a, b) => RANK[a.support] - RANK[b.support] || a.p.name.localeCompare(b.p.name, "en"));
  const runs = rows.filter((r) => r.support !== "no");
  const notRunning = rows.filter((r) => !ships(r.support)).map((r) => r.p.name);
  const running = rows.filter((r) => ships(r.support)).length;
  const all = await getDocs();
  const guides = all.filter((x) => x.frame === d.id && x.section === "frames" && x.url !== `/docs/frames/${d.id}`);
  const notes = all.filter((x) => x.frame === d.id && x.section === "notes");
  const installGuide = guides.find((g) => g.url.endsWith("/install"));

  return (
    <article>
      <JsonLd
        nodes={[
          breadcrumbLd([
            { name: "Frames", path: "/frames" },
            { name: d.name, path: `/frames/${d.id}` },
          ]),
        ]}
      />
      <header className={`${m.card} ${s.hero}`}>
        <div>
          <div className={s.chips}>
            <StageChip support={d.status} />
            {c.state === "graded" && (
              <ClarityChipLink className={s.chipLink}>
                <Chip>
                  <b>
                    {CLARITY} {c.grade.grade}
                  </b>
                </Chip>
              </ClarityChipLink>
            )}
            <Chip>
              Frame <code>{d.id}</code>
            </Chip>
          </div>
          <h1 className={s.h1}>{d.name}</h1>
          {d.aka && <p className={s.aka}>{d.aka}</p>}
          <p className={s.lede}>{d.summary}</p>
          <div className={s.acts}>
            {install?.kiln && (
              <a href={install.href} className={`${m.btn} ${m.btnKiln}`}>
                <KilnGlyph />
                Open Kiln
              </a>
            )}
            {install && !install.kiln && (
              <a href={install.href} className={m.btn}>
                Open {new URL(install.href).host}
              </a>
            )}
            {installGuide && (
              <Link href={installGuide.url} className={m.btn}>
                <BookGlyph />
                Install guide
              </Link>
            )}
            {d.project && (
              <a href={d.project.href} className={m.btn}>
                {d.project.name} ↗
              </a>
            )}
          </div>
          <p className={s.how}>{install ? d.install.how : `Not in Kiln yet. ${d.install.how}`}</p>
        </div>
        <HeroModel f={d} />
      </header>

      {d.notice && <p className={s.notice}>{d.notice}</p>}

      {d.credits && d.credits.length > 0 && (
        <ListCard id="credits" title="Built on community work" aside={<span>Thank you</span>}>
          {d.credits.map((c) => (
            <div key={c.id} className={`${m.row} ${s.credit}`}>
              <Sq>
                <CreditGlyph />
              </Sq>
              <span className={m.rt}>
                <b>
                  <a href={c.project.href}>{c.project.name}</a> · {c.what}
                </b>
                <span>
                  By <a href={c.who.href}>{c.who.name}</a>
                  {c.licence && <> · {c.licence}</>}
                </span>
                <span className={s.uses}>{c.uses}</span>
                {c.quote && (
                  <span className={s.quote}>
                    <q cite={c.quote.href}>{c.quote.text}</q> <a href={c.quote.href}>Their words</a>
                  </span>
                )}
              </span>
            </div>
          ))}
        </ListCard>
      )}

      <Tiles>
        <Tile label="Install" value={ratingText("difficulty", d.ratings.difficulty.value)} sub={d.ratings.difficulty.detail} />
        <Tile label="Reversible" value={ratingText("reversible", d.ratings.reversible.value)} sub={d.ratings.reversible.detail} />
        <Tile
          label="Panes"
          value={`${live ? running : runs.length} of ${rows.length}`}
          sub={!live ? "Planned" : notRunning.length ? `All but ${notRunning.join(", ")}` : "All of them"}
        />
        <Tile label="Updates" value={ratingText("updates", d.ratings.updates.value)} sub={d.ratings.updates.detail} />
      </Tiles>

      <div className={s.clarity}>
        <ClarityPanel frameId={d.id} />
      </div>

      {d.unverified && <p className={s.notice}>These details come from public writeups. Nobody has checked them on hardware yet.</p>}

      <div className={m.cols2}>
        <ListCard id="panes" title="Panes" aside={<span>{live ? `${running} run here` : `${runs.length} planned`}</span>}>
          {runs.map(({ p, support: sup, note }) => {
            const Icon = paneIcon[p.id];
            return (
              <Row
                key={p.id}
                href={`/panes/${p.id}`}
                lead={
                  <Sq>
                    <Icon />
                  </Sq>
                }
                title={p.name}
                sub={note}
                end={<StageChip support={sup} />}
              />
            );
          })}
        </ListCard>
        <div>
          <ListCard id="hardware" title="Hardware">
            {d.specs.map((sp) => (
              <FactRow key={sp.label} label={<SpecLabel label={sp.label} />} value={sp.value} />
            ))}
          </ListCard>
          <ListCard id="frame-docs" title="Docs for this frame" aside={<Link href={`/docs/frames/${d.id}`}>All docs ›</Link>}>
            {guides.map((g) => (
              <Row key={g.url} href={g.url} title={g.label ?? g.title} sub={g.toc.filter((t) => t.depth === 2).slice(0, 3).map((t) => t.text.replace(/\s*\(.*?\)/g, "")).join(", ")} />
            ))}
            {notes.length > 0 && <Row href={`/docs/notes#notes-${d.id}`} title="Engineering notes" sub={`${notes.length} notes`} />}
            {!guides.length && !notes.length && <Row href={`/docs/frames/${d.id}`} title="About this frame" sub="What it is and how it's put together" />}
          </ListCard>
        </div>
      </div>

      <section className={`${m.card} ${m.list}`} aria-labelledby="changes">
        <div className={s.changesHead}>
          <h2 id="changes">{live ? "What installing changes" : "What installing will change"}</h2>
          {d.ratingsNote && <span>{d.ratingsNote}</span>}
        </div>
        <dl className={s.kvs}>
          {ratingKeys.map((k) => (
            <div key={k} className={s.kv}>
              <dt>
                <RatingLabel field={k} />
              </dt>
              <dd>
                <RatingValue field={k} rating={d.ratings[k]} />
              </dd>
            </div>
          ))}
        </dl>
        {(!live || d.unverified) && <p className={s.expected}>Expected. Nothing has run on this device yet.</p>}
      </section>

      <p className={s.back}>
        <Link href="/frames">All frames</Link>
      </p>
    </article>
  );
}
