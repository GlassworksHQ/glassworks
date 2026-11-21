import Link from "next/link";
import { AddCard, FrameGrid } from "@/components/FrameGrid";
import { PageHead } from "@/components/Manage";
import {
  RatingLabel,
  RatingValue,
  SupportTag,
  paneIcon,
} from "@/components/DeviceBits";
import { frames } from "@/lib/frames";
import { difficultyHelp, ratingKeys, ships } from "@/lib/frameText";
import { panes } from "@/lib/site";
import { support } from "@/lib/paneSupport";
import { pageMetadata } from "@/lib/seo";
import s from "./frames.module.css";

export const metadata = pageMetadata({
  title: "Wall panels for Home Assistant",
  description:
    "Wall panels and tablets Glassworks runs on, or will: what installing changes, and which panes work on each.",
  path: "/frames",
});

export default function DevicesPage() {
  return (
    <div className={s.page}>
      <PageHead
        title="Supported devices"
        lede="Wall switches, smart mirrors, and everyday Android tablets running Glassworks—compare install steps, Clarity performance grades, and supported features side by side."
      />
      <FrameGrid id="frames" title="Devices" aside={<span>{frames.length} supported &amp; planned</span>} frames={frames}>
        <AddCard href="/docs/frames" title="Bring your own panel">
          Have another Android panel in mind? Read the porting guide to bring Glassworks to your hardware.
        </AddCard>
      </FrameGrid>

      <section className={s.section} aria-labelledby="changes">
        <h2 id="changes" className={s.h2}>
          What installing changes
        </h2>
        <div className={`glass ${s.tableWrap}`}>
          <table className={`${s.table} ${s.changes}`}>
            <thead>
              <tr>
                <th scope="col">
                  <span className="visually-hidden">Change</span>
                </th>
                {frames.map((d) => (
                  <th key={d.id} scope="col">
                    <Link href={`/frames/${d.id}`}>{d.name}</Link>
                    {!ships(d.status) && (
                      <span className={s.expected}>expected</span>
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ratingKeys.map((k) => (
                <tr key={k}>
                  <th scope="row">
                    <RatingLabel field={k} />
                  </th>
                  {frames.map((d) => (
                    <td key={d.id}>
                      <RatingValue field={k} rating={d.ratings[k]} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className={s.legend}>
          {difficultyHelp.map((h) => (
            <div key={h.name}>
              <dt>{h.name}</dt>
              <dd>{h.means}</dd>
            </div>
          ))}
        </dl>
        <p className={s.note}>
          Planned devices show what the install is expected to do—we haven&rsquo;t run it on hardware yet.
        </p>
      </section>

      <section className={s.section} aria-labelledby="matrix">
        <h2 id="matrix" className={s.h2}>
          Panes by device
        </h2>
        <div className={`glass ${s.tableWrap}`}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Pane</th>
                {frames.map((d) => (
                  <th key={d.id} scope="col">
                    <Link href={`/frames/${d.id}`}>{d.name}</Link>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {panes.map((p) => {
                const Icon = paneIcon[p.id];
                return (
                  <tr key={p.id}>
                    <th scope="row">
                      <span className={s.paneCell}>
                        <Icon className={s.paneIcon} />
                        {p.name}
                      </span>
                    </th>
                    {frames.map((d) => (
                      <td key={d.id} title={support(d, p.id).note}>
                        <SupportTag support={support(d, p.id).support} />
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <p className={s.note}>
          &ldquo;Depends&rdquo; means the feature requires hardware only some devices include.
          &ldquo;Not checked&rdquo; means we haven&rsquo;t verified it on that device yet.
        </p>
      </section>
    </div>
  );
}
