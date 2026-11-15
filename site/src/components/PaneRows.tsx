import { paneIcon } from "./DeviceBits";
import { Row, Sq, StageChip } from "./Manage";
import { paneLine, type Pane } from "@/lib/site";

/** The panes as Manage rows: icon, what it does and needs, stage. */
export function PaneRows({ panes }: { panes: (Pane & { href?: string })[] }) {
  return panes.map((p) => {
    const Icon = paneIcon[p.id];
    return (
      <Row
        key={p.id}
        href={p.href ?? `/panes/${p.id}`}
        lead={
          <Sq>
            <Icon />
          </Sq>
        }
        title={p.name}
        sub={paneLine(p)}
        end={<StageChip support={p.status} />}
      />
    );
  });
}
