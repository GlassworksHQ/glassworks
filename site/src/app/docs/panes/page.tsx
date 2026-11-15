import { PageHead, ListCard } from "@/components/Manage";
import { PaneRows } from "@/components/PaneRows";
import { getDocs } from "@/lib/docs";
import { panes } from "@/lib/site";
import { pageMetadata } from "@/lib/seo";

export const metadata = pageMetadata({
  title: "Panes in the docs",
  description: "Each Glassworks pane's own docs, where it has them: what it does, its settings and entities, and how it works.",
  path: "/docs/panes",
});

/** Every pane: its README when it has one, else its pane page. */
export default async function DocsPanes() {
  const docs = await getDocs();
  const withDoc = panes.map((p) => ({ ...p, href: docs.find((x) => x.url === `/docs/panes/${p.id}`)?.url ?? `/panes/${p.id}` }));
  return (
    <>
      <PageHead title="Panes" lede="A pane's own docs, where it has them. The rest link to the pane's page." />
      <ListCard id="panes" title="Panes" aside={<span>{panes.length} panes</span>}>
        <PaneRows panes={withDoc} />
      </ListCard>
    </>
  );
}
