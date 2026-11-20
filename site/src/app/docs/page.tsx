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
  description: "Glassworks documentation: user guides for setting up and running a panel, developer references for building frames and panes, and hardware research notes.",
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
  const byUrls = (urls: string[]) => urls.map((u) => docs.find((x) => x.url === u)).filter((x): x is Doc => Boolean(x));

  const dailyFeatures = of("user").filter((x) => !x.frame && x.url !== "/docs/getting-started");
  const architectureDocs = byUrls(["/docs/principles", "/docs/what-makes-a-pane", "/docs/frames", "/docs/frame-agent", "/docs/core/pane-api"]);
  const kilnDocs = byUrls(["/docs/kiln", "/docs/kiln/design", "/docs/kiln/images"]);
  const opsDocs = byUrls(["/docs/secrets", "/docs/size", "/docs/ci", "/docs/releasing", "/docs/branding", "/docs/legal", "/changelog"]);
  const libraries = docs.filter((x) => x.url.startsWith("/docs/libraries/"));
  const notes = of("notes").filter((n) => n.url !== "/docs/notes");

  return (
    <>
      <header className={d.landingHead}>
        <span className="label">Docs</span>
        <h1 className={d.landingH1}>Glassworks documentation</h1>
        <p className={m.lede}>Set up a Home Assistant wall panel, build a new frame or pane, or dive into our hardware research notes.</p>
        <SearchButton variant="big" />
      </header>

      <div className={d.trackHead}>
        <span className="label">User Guide</span>
        <p>Everything you need to install Glassworks, connect your panel to Home Assistant, and use it every day.</p>
      </div>
      <div className={d.cards}>
        <Card id="start" icon="frame" title="Get started & supported devices" lede="Follow the quickstart or jump straight to your hardware's install guide.">
          <Row href="/docs/getting-started" title="Getting Started" end={<em>Start here</em>} />
          {frames.map((f) => {
            const guides = of("user").filter((x) => x.frame === f.id).length - 1;
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
        <Card id="features" icon="core" title="Daily features" lede="Manage your panel, use voice and timers, stream music, and automate with Home Assistant.">
          {rows(dailyFeatures)}
        </Card>
      </div>

      <div className={d.trackHead}>
        <span className="label">Developers &amp; Hardware Makers</span>
        <p>Design principles, architecture, standalone libraries, Kiln recipes, security, and project operations.</p>
      </div>
      <div className={d.cards}>
        <Card id="build" icon="pane" title="Architecture, frames & panes" lede="Port a new wall panel, build a pane plugin, or reuse our MIT libraries.">
          {rows(architectureDocs)}
          <Row href="/docs/panes" title="Panes" end={<em>{PANES.length}</em>} />
          <Row href="/docs/libraries" title="Standalone libraries" end={<em>{libraries.length} · MIT</em>} />
          {rows(kilnDocs)}
        </Card>
        <Card id="ops" icon="core" title="Security & project operations" lede="Keystore security, APK budgets, CI workflows, releases, brand assets, and legal notes.">
          {rows(opsDocs)}
        </Card>
      </div>

      <div className={d.trackHead}>
        <span className="label">Hardware Research</span>
        <p>Reverse-engineering logs, bring-up playbooks, protocol captures, and dated hardware measurements.</p>
      </div>
      <Card id="notes" icon="kiln" title="Engineering & hardware research" lede="How each device was unlocked, what worked, what failed, and how confident we are." wide>
        <Row href="/docs/notes" title="How we got here" end={<em>Overview</em>} />
        {rows(notes.filter((n) => !n.frame).slice(0, 4))}
        <Row href="/research" title={`Browse all ${notes.length} research notes`} />
      </Card>
    </>
  );
}
