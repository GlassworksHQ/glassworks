import { SupportTag } from "@/components/DeviceBits";
import { ListCard, PageHead } from "@/components/Manage";
import { PaneRows } from "@/components/PaneRows";
import type { Support } from "@/lib/frameText";
import { framePanes, generalPanes } from "@/lib/site";
import { pageMetadata } from "@/lib/seo";
import s from "./panes.module.css";

export const metadata = pageMetadata({
  title: "Panes: plugins for Home Assistant panels",
  description:
    "Plugins for Glassworks panels: voice, music, a Zigbee/Thread radio, a Bluetooth proxy. What each does, what it needs and where it runs.",
  path: "/panes",
});

export default function PanesPage() {
  return (
    <>
      <PageHead
        title="Features and panes"
        lede="Every capability beyond the core ships as a modular pane matched to what your panel supports — some run automatically, while opt-in panes like the Bluetooth proxy stay off until you turn them on."
      />

      <ListCard id="general" title="Works across devices" aside={<span>{generalPanes.length} panes</span>}>
        <PaneRows panes={generalPanes} />
      </ListCard>

      {framePanes.length > 0 && (
        <ListCard id="frame-panes" title="Device-specific panes" aside={<span>Built for individual hardware</span>}>
          <PaneRows panes={framePanes} />
        </ListCard>
      )}

      <ul className={s.key} aria-label="Key">
        {(["stable", "beta", "alpha", "development", "planned", "depends", "unknown", "no"] as Support[]).map((k) => (
          <li key={k}>
            <SupportTag support={k} />
          </li>
        ))}
      </ul>
    </>
  );
}
