import Link from "next/link";
import type { ReactNode } from "react";
import { StageChip, manage as m } from "../Manage";
import { EditGlyph } from "../Glyphs";
import { Toc } from "./Toc";
import { NoteFacts } from "./NoteBits";
import type { Note } from "@/lib/docs/research";
import { SECTIONS, type Doc } from "@/lib/docs";
import { frameById } from "@/lib/frames";
import type { ApiReference } from "@/lib/apiDocs";
import type { FrameDesign } from "@glassworks/frame-card";
import d from "./Docs.module.css";
import "./prose.css";

/** A library's API reference, as a strip under the breadcrumb. */
function ApiStrip({ name, reference: r }: { name: string; reference: ApiReference }) {
  return (
    <div className={d.strip} data-pagefind-ignore>
      {r.kind === "site" && (
        <>
          <span>
            API reference: <a href={r.href}>{name} {r.version}</a>
          </span>
          <code>{r.coordinate}</code>
        </>
      )}
      {r.kind === "elsewhere" && (
        <span>
          API reference: <a href={r.href}>on {r.host}</a>
        </span>
      )}
      {r.kind === "unreleased" && <span>API reference: after the first release</span>}
    </div>
  );
}

/** The frame a page belongs to, its stage, and on a frame's own pages its Docs and Research tabs. */
export function FrameStrip({ frame, tabs }: { frame: FrameDesign; tabs?: ReactNode }) {
  const name = (
    <span>
      Frame: <Link href={`/frames/${frame.id}`}>{frame.name}</Link>
    </span>
  );
  return (
    <div className={d.strip} data-pagefind-ignore>
      {tabs ? (
        <span className={d.stripFrame}>
          {name}
          <StageChip support={frame.status} />
        </span>
      ) : (
        <>
          {name}
          <StageChip support={frame.status} />
        </>
      )}
      {tabs}
    </div>
  );
}

/** One published doc: its header from the README's, the Markdown, and where to edit it. */
export function DocArticle({
  doc,
  prev,
  next,
  after,
  api,
  tabs,
  crumbs,
}: {
  doc: Doc | Note;
  prev?: Doc;
  next?: Doc;
  after?: ReactNode;
  /** a library's README: its API reference */
  api?: { name: string; reference: ApiReference };
  /** a frame's page: Docs and Research, in the frame's strip */
  tabs?: ReactNode;
  /** the breadcrumb after "Docs", when the section's name isn't the right one */
  crumbs?: ReactNode;
}) {
  const note = "blurb" in doc ? doc : undefined;
  const section = SECTIONS.find((s) => s.id === doc.section)!.label;
  const frame = doc.frame ? frameById(doc.frame) : undefined;
  const toc = doc.toc.length >= 2 ? doc.toc : [];
  return (
    <div className={d.layout}>
      <div className={d.column}>
      <article className={`${m.card} ${d.article}`} data-pagefind-body data-pagefind-meta={`section:${section}`}>
        <header className={d.head}>
          <nav className={d.crumbs} aria-label="Breadcrumb" data-pagefind-ignore>
            <Link href="/docs">Docs</Link>
            <span aria-hidden="true">/</span>
            {crumbs ?? <span>{frame && doc.section === "notes" ? `${section} · ${frame.name}` : section}</span>}
          </nav>
          {frame && (
            <FrameStrip frame={frame} tabs={tabs} />
          )}
          {api && <ApiStrip {...api} />}
          <h1 id={doc.titleId || undefined} className={d.h1} data-pagefind-meta="title">
            {doc.title}
          </h1>
          {doc.tagline && <p className={d.tagline} dangerouslySetInnerHTML={{ __html: doc.tagline }} />}
          {doc.badges && <div className="md-badges" dangerouslySetInnerHTML={{ __html: doc.badges }} data-pagefind-ignore />}
          <p className={d.meta} data-pagefind-ignore>
            <a href={doc.edit}>
              <EditGlyph />
              Edit on GitHub
            </a>
            {note ? (
              <a href={doc.edit.replace("/edit/main/", "/blob/main/")}>
                <code>{doc.file}</code>
              </a>
            ) : (
              <code>{doc.file}</code>
            )}
            {doc.updated && <span>Updated {doc.updated}</span>}
          </p>
          {note && (
            <div className={d.facts} data-pagefind-ignore>
              <NoteFacts note={note} commitDate={false} />
            </div>
          )}
        </header>
        {toc.length > 0 && (
          <details className={d.tocFold} data-pagefind-ignore>
            <summary>On this page</summary>
            <ol>
              {toc.map((t) => (
                <li key={t.id} style={t.depth === 3 ? { paddingLeft: 14 } : undefined}>
                  <a href={`#${t.id}`}>{t.text}</a>
                </li>
              ))}
            </ol>
          </details>
        )}
        {/* the repo's own Markdown, rendered and sanitized at build time (src/lib/docs/render.ts) */}
        <div className="md" dangerouslySetInnerHTML={{ __html: doc.html }} />
        {(prev || next) && (
          <nav className={d.foot} aria-label="More docs" data-pagefind-ignore>
            {prev ? (
              <Link href={prev.url} className={d.pager}>
                <small>Previous</small>
                <b>{prev.label ?? prev.title}</b>
              </Link>
            ) : (
              <span />
            )}
            {next && (
              <Link href={next.url} className={`${d.pager} ${d.next}`}>
                <small>Next</small>
                <b>{next.label ?? next.title}</b>
              </Link>
            )}
          </nav>
        )}
      </article>
      {after}
      </div>
      {toc.length > 0 && <Toc toc={toc} />}
    </div>
  );
}
