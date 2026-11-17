import { DocsSidebar } from "@/components/docs/DocsSidebar";
import { Frame } from "@/components/shell/Frame";

/* the research index sits in the docs: same sidebar */
export default function ResearchLayout({ children }: LayoutProps<"/research">) {
  return <Frame nav={<DocsSidebar />}>{children}</Frame>;
}
