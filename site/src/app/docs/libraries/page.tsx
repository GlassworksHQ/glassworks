import { PageHead, ListCard, Row } from "@/components/Manage";
import { getDocs } from "@/lib/docs";
import { pageMetadata } from "@/lib/seo";

export const metadata = pageMetadata({
  title: "Libraries",
  description: "Libraries from the Glassworks repo that work on their own: the ESPHome native API, Improv Wi-Fi, fastboot over WebUSB and more. MIT.",
  path: "/docs/libraries",
});

export default async function DocsLibraries() {
  const libraries = (await getDocs()).filter((x) => x.url.startsWith("/docs/libraries/"));
  return (
    <>
      <PageHead title="Libraries" lede="Parts of Glassworks you can use on their own. MIT." />
      <ListCard id="libraries" title="Libraries" aside={<span>{libraries.length}</span>}>
        {libraries.map((x) => (
          <Row key={x.url} href={x.url} title={x.title} sub={x.summary} />
        ))}
      </ListCard>
    </>
  );
}
