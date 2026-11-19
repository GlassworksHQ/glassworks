import Link from "next/link";
import { BlueprintRows } from "@/components/Blueprints";
import { paneIcon } from "@/components/DeviceBits";
import { DeviceGlyph } from "@/components/Glyphs";
import { ListCard, PageHead, Sq, Tile, Tiles, manage as m } from "@/components/Manage";
import { blueprints, isCore, releaseName } from "@/lib/blueprints";
import { pageMetadata } from "@/lib/seo";
import { panes } from "@/lib/site";
import s from "./blueprints.module.css";

export const metadata = pageMetadata({
  title: "Blueprints: ready-made Home Assistant automations",
  description:
    "Home Assistant blueprints for Glassworks panels: night mode for the screens, ring the panels when the doorbell rings, ask a question on the panels, lights on at the panel.",
  path: "/blueprints",
});

export default async function BlueprintsPage() {
  const { ref, list } = await blueprints();
  // the panel's own (its settings pages') first, on every panel whatever its panes
  const core = list.filter(isCore);
  // the panes' own order: general panes first, frame panes last
  const groups = panes.map((p) => ({ pane: p, list: list.filter((b) => b.pane === p.id) })).filter((g) => g.list.length > 0);
  // a blueprint the page can't place would vanish from it without a word: stop the build instead
  const placed = new Set([...core, ...groups.flatMap((g) => g.list)]);
  const lost = list.filter((b) => !placed.has(b));
  if (lost.length > 0) throw new Error(`blueprints: no card for ${lost.map((b) => `${b.path} (pane ${b.pane})`).join(", ")}`);

  return (
    <>
      <PageHead
        title="Blueprints"
        lede="Ready-made Home Assistant automations for the panel and its panes. Home Assistant imports each one, and you pick your devices when you make the automation."
      />

      <Tiles cols={3} className={m.trio}>
        <Tile label="1" value="Add to Home Assistant" sub="Opens your Home Assistant. The first time, it asks for its address." />
        <Tile label="2" value="Import" sub="Preview, then Import blueprint. Only an administrator can." />
        <Tile label="3" value="Create the automation" sub="Pick your doorbell, lights or panels. Nothing else to install." />
      </Tiles>

      {core.length > 0 && (
        <ListCard
          id="bp-panel"
          title={
            <span className={s.title}>
              <Sq>
                <DeviceGlyph />
              </Sq>
              Panel
            </span>
          }
        >
          <BlueprintRows list={core} />
        </ListCard>
      )}

      {core.length + groups.length === 0 ? (
        <section className={`${m.card} ${m.list}`} aria-label="Blueprints">
          <p className={s.none}>None in a release yet. The first ones come with the next release.</p>
        </section>
      ) : (
        groups.map(({ pane, list }) => {
          const Icon = paneIcon[pane.id];
          return (
            <ListCard
              key={pane.id}
              id={`bp-${pane.id}`}
              title={
                <span className={s.title}>
                  <Sq>
                    <Icon />
                  </Sq>
                  {pane.name}
                </span>
              }
              aside={<Link href={`/panes/${pane.id}`}>The pane ›</Link>}
            >
              <BlueprintRows list={list} />
            </ListCard>
          );
        })
      )}

      {ref && core.length + groups.length > 0 && (
        <p className={s.fine}>From {releaseName(ref)}: the same files a panel on that version offers. MIT, like the rest of the repository.</p>
      )}
    </>
  );
}
