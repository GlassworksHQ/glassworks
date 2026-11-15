import { Frame } from "@/components/shell/Frame";
import { Sidebar } from "@/components/shell/Sidebar";

export default function SiteLayout({ children }: LayoutProps<"/">) {
  return <Frame nav={<Sidebar />}>{children}</Frame>;
}
