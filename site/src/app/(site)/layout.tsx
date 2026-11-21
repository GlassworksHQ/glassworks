import { TopNav } from "@/components/TopNav";
import { SiteFooter } from "@/components/SiteFooter";
import { getPosts } from "@/lib/docs";
import s from "./layout.module.css";

export default async function SiteLayout({ children }: LayoutProps<"/">) {
  const fresh = (await getPosts()).some((p) => p.fresh);
  return (
    <div className={s.shell}>
      <TopNav hasFreshPost={fresh} />
      <main id="content" className={s.main}>
        {children}
      </main>
      <SiteFooter layout="quiet" />
    </div>
  );
}
