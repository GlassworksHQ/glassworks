import { PANES, STAGE_TEXT } from "@glassworks/frame-card";
import type { ReactNode } from "react";
import { Row, manage as m } from "@/components/Manage";
import { SearchButton } from "@/components/search/SearchButton";
import { getDocs, type Doc } from "@/lib/docs";
import { frames } from "@/lib/frames";
import { pageMetadata } from "@/lib/seo";
import d from "@/components/docs/Docs.module.css";
import "@/components/docs/prose.css";

export const metadata = pageMetadata({
  title: "Docs",
  description: "Glassworks docs: install and use a panel, write a frame or a pane, the libraries, and the engineering notes behind them.",
  path: "/docs",
});

function Icon({ name }: { name: string }) {
  return (
    <span className={d.cardIcon} aria-hidden="true">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="md-on-light" src={`/brand/icon-${name}-on-light.svg`} alt="" width={30} height={30} />
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="md-on-dark" src={`/brand/icon-${name}-on-dark.svg`} alt="" width={30} height={30} />
    </span>
  );
}

function Card({ id, icon, title, lede, children, wide = false }: { id: string; icon: string; title: string; lede: string; children: ReactNode; wide?: boolean }) {
  return (
    <section className={`${m.card} ${m.list}`} aria-labelledby={id} style={wide ? { marginBottom: 16 } : { marginBottom: 0 }}>
      <div className={d.cardHead}>
        <Icon name={icon} />
        <h2 id={id}>{title}</h2>
      </div>
      <p className={d.cardLede}>{lede}</p>
      {wide ? <div className={d.wideRows}>{children}</div> : children}
    </section>
  );
}

const rows = (docs: Doc[]) => docs.map((doc) => <Row key={doc.url} href={doc.url} title={doc.label ?? doc.title} />);

export default async function DocsHome() {
  const docs = await getDocs();
  const of = (section: string) => docs.filter((x) => x.section === section);
  const notes = of("notes").filter((n) => n.url !== "/docs/notes");
  const libraries = docs.filter((x) => x.url.startsWith("/docs/libraries/"));
  return (
    <>
      <header className={d.landingHead}>
        <span className="label">Docs</span>
        <h1 className={d.landingH1}>Glassworks docs</h1>
        <p className={m.lede}>Install and use a panel, port a device, or write a pane.</p>
        <SearchButton variant="big" />
      </header>

      <div className={d.cards}>
        <Card id="device" icon="frame" title="Your device" lede="Install it and set it up. One section per frame.">
          {frames.map((f) => {
            const guides = of("frames").filter((x) => x.frame === f.id).length - 1;
            return (
              <Row
                key={f.id}
                href={`/docs/frames/${f.id}`}
                title={f.name}
                end={<em>{[STAGE_TEXT[f.status], guides > 0 ? `${guides} guides` : ""].filter(Boolean).join(" · ")}</em>}
              />
            );
          })}
        </Card>
        <Card id="panel" icon="core" title="Using a panel" lede="What every panel does, on any frame.">
          {rows(of("panel"))}
        </Card>
        <Card id="philosophy" icon="core" title="Philosophy" lede="What Glassworks is and isn't.">
          {of("philosophy").map((x, i) => (
            <Row key={x.url} href={x.url} title={x.label ?? x.title} end={i === 0 ? <em>Start here</em> : undefined} />
          ))}
        </Card>
        <Card id="build" icon="pane" title="Panes and libraries" lede="Write a pane, or use a library on its own.">
          {rows(docs.filter((x) => x.url === "/docs/core/pane-api"))}
          <Row href="/docs/panes" title="Panes" end={<em>{PANES.length}</em>} />
          <Row href="/docs/libraries" title="Libraries" end={<em>{libraries.length} · MIT</em>} />
          {rows(docs.filter((x) => x.url === "/docs/kiln"))}
        </Card>
      </div>

      <Card id="project" icon="core" title="Project" lede="How it's measured, released and named." wide>
        {rows(of("project"))}
      </Card>
      <Card id="notes" icon="kiln" title="Engineering notes" lede="How each device was opened up, what failed, and how sure we are." wide>
        {rows(notes.filter((n) => !n.frame).slice(0, 5))}
        <Row href="/research" title={`All ${notes.length} notes, by frame and topic`} />
      </Card>
    </>
  );
}
