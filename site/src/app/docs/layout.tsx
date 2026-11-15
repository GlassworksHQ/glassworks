import { DocsSidebar } from "@/components/docs/DocsSidebar";
import { Frame } from "@/components/shell/Frame";

export default function DocsLayout({ children }: LayoutProps<"/docs">) {
  return <Frame nav={<DocsSidebar />}>{children}</Frame>;
}
